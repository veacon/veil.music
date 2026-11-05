package com.veil.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.veil.music.data.model.Album
import com.veil.music.ui.collection.CollectionScreen
import com.veil.music.ui.components.MiniPlayerCapsule
import com.veil.music.ui.components.TopNavCapsule
import com.veil.music.ui.discover.DiscoverScreen
import com.veil.music.ui.navigation.Screen
import com.veil.music.ui.navigation.TopNavTab
import com.veil.music.ui.navigation.LocalNavBarFocus
import com.veil.music.ui.navigation.LocalNavBarHasFocus
import com.veil.music.ui.screens.LibraryScreen
import com.veil.music.ui.screens.NowPlayingScreen
import com.veil.music.ui.screens.SearchScreen
import com.veil.music.ui.screens.SettingsScreen
import com.veil.music.ui.viewmodel.NavigationViewModel
import com.veil.music.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import java.net.URLDecoder

/**
 * 应用外壳：顶栏胶囊 + 内容区 + 迷你胶囊。
 *
 * ═══ 层级模型（改任何"返回键怎么走"之前先看这里）═══════════════════════════════
 *
 * **L1 内容层（最低）**：发现 / 我的 / 搜索 / 设置 / 歌单详情 —— NavHost 里的 tab 路由。
 *      这一层内部的返回 = 回到发现页；在发现页再按返回 = 退出确认。
 *
 * **L2 播放层（盖在 L1 上）**：标准播放页 → 墨卷 → 声轨 → 全屏歌词。
 *      这四张脸是**同一层**，按「换效」在层内轮转（cycleLyricView），彼此不分前后。
 *      没有歌词时轮转会停在标准播放页 —— 空歌词进去只会看到一片白。
 *      因为它们同层，返回键在层内**不**"回到上一张脸"，而是直接退出这一层回到 L1：
 *      播放页、墨卷、声轨、全屏歌词里按返回，落点都是发现页。
 *
 * **L3 浮动层（永远在最上面，不入返回栈）**：
 *      · 顶栏胶囊 TopNavCapsule —— 发现 / 我的 / 搜索 / 设置
 *      · 迷你胶囊 MiniPlayerCapsule —— 底部播放条
 *      · 调节胶囊 TuningCapsule —— 长按下键呼出（字号 / 字体 / 背景 / 换效）
 *      浮动层没有"返回"这回事，但它们比 L2 先拿到按键：调节胶囊展开时，
 *      返回键先把胶囊收起来，再按一次才退出 L2。
 *
 * 换句话说：越靠上越先吃键，返回键永远是"从最上面那一层开始撤"。
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 与原版相比删掉了 onboarding / 音乐视频 / 电台 / 分类页这些 Apple 专属路由，
 * 只保留「发现、我的、搜索、正在播放、设置」和歌单详情。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf(TopNavTab.Discover) }
    val playerVm: PlayerViewModel = hiltViewModel()
    val navVm: NavigationViewModel = hiltViewModel()

    val route by navController.currentBackStackEntryAsState()
    val currentRoute = route?.destination?.route ?: Screen.Discover.route
    val isOnNowPlaying = currentRoute == Screen.NowPlaying.route
    navVm.isOnNowPlaying = isOnNowPlaying

    // 冷启动：恢复上次的队列（不出声），再让播放条显示当前曲目。
    LaunchedEffect(Unit) { playerVm.restoreState() }

    // 遥控器 Menu 键触发进入播放页（MainActivity → navVm.navigateToNowPlaying）。
    LaunchedEffect(Unit) {
        navVm.goToNowPlaying.collect { go ->
            if (go) {
                navVm.consumeNowPlayingNavigation()
                navController.navigateToNowPlaying()
            }
        }
    }

    // 手机管理页推来的搜索关键词：跳到搜索页（SearchViewModel 自己订阅同一个流去真正搜）。
    LaunchedEffect(Unit) {
        navVm.searchKeyword.collect { kw ->
            if (kw != null) {
                navVm.consumeSearchKeyword()
                navController.navigateTab(TopNavTab.Search)
            }
        }
    }

    // 遥控器够不着的错误（解析失败、换源）用 toast 提示。
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        playerVm.toasts.collect { msg ->
            android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 手机管理页的副作用反馈（建歌单 / 导入歌单链接）同样走 toast。
    LaunchedEffect(Unit) {
        navVm.messages.collect { msg ->
            android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 底部迷你播放条只在「有歌」时才占高度。这里只投影一个布尔量再去重，
    // 不 collect 整个 PlayerState —— 否则每 900ms 的 progressMs 会把整壳（含 NavHost）一起重组。
    val hasSong by remember(playerVm) {
        playerVm.state.map { it.currentSong != null }.distinctUntilChanged()
    }.collectAsState(initial = false)

    // 迷你条的高度/宽度比例：高度决定它自己多高，也决定底部要给内容留多少空间 ——
    // 写死 104dp 的话，胶囊调到 140% 会压住列表最后一行，调到 60% 又空出一大块。
    val miniSize by remember(playerVm) {
        playerVm.state
            .map { it.miniCapsuleHeightPct to it.miniCapsuleWidthPct }
            .distinctUntilChanged()
    }.collectAsState(initial = 100 to 100)

    // 更新检查：启动一次 + 每 3 小时一次（TV 应用常驻，只查一次会错过滚动构建）。
    var updateAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val beta = com.veil.music.util.UpdatePreferences.betaEnabled(context)
            // 启动时的静默检查也用用户选定的加速线路（设置 → 关于 → GitHub 加速）。
            val proxy = com.veil.music.util.UpdatePreferences.githubProxy(context)
            com.veil.music.util.UpdateChecker.check(beta, proxy).onSuccess { result ->
                val current = com.veil.music.BuildConfig.VERSION_NAME
                updateAvailable = when (result) {
                    is com.veil.music.util.UpdateResult.Newer ->
                        result.info.version.trimStart('v', 'V') != current
                    else -> false
                }
            }
            kotlinx.coroutines.delay(3 * 60 * 60 * 1000L)
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }

    // 页面内容区按上键要回到的那个 tab（见 LocalNavBarFocus）。
    val navBarFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 播放页的「播放/暂停」键：进入播放页后，右上角胶囊按上/下键要直接落到它上面。
    // 由 NowPlayingScreen 绑到按钮上（播放页没显示时它没有节点，会自然退回几何搜索）。
    val nowPlayingPlayFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 顶栏此刻是否持焦。顶栏持焦期间页面不许抢焦点，否则左右键切到「设置」时
    // 会被播放按钮抓走焦点。
    val navBarHasFocus = remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFF08080A))) {
      CompositionLocalProvider(
        LocalNavBarFocus provides navBarFocus,
        LocalNavBarHasFocus provides navBarHasFocus,
      ) {
        // 播放页自己就是一层铺满全窗的动态背景，这里必须把 56dp 的顶栏让位收掉 ——
        // 否则背景会被裁在 56dp 处，屏幕上方出现一条横贯整屏的硬边（上亮下暗）。
        // 播放页改用内部 96dp 的上内边距来维持原有视觉位置（见 NowPlayingScreen）。
        val topInset = if (isOnNowPlaying) 0.dp else 56.dp
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(topInset))
            Box(Modifier.weight(1f)) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Discover.route,
                ) {
                    composable(Screen.Discover.route) {
                        selectedTab = TopNavTab.Discover
                        DiscoverScreen(
                            onOpenCollection = { album -> navController.openCollection(album) },
                        )
                    }
                    composable(Screen.Library.route) {
                        selectedTab = TopNavTab.Library
                        LibraryScreen(playerVm)
                    }
                    composable(Screen.Search.route) {
                        selectedTab = TopNavTab.Search
                        SearchScreen(playerVm)
                    }
                    composable(Screen.NowPlaying.route) {
                        NowPlayingScreen(
                            playerVm = playerVm,
                            navVm = navVm,
                            playFocus = nowPlayingPlayFocus,
                            // L2 里按返回 = 退出整层（墨卷/声轨/全屏歌词同样是这一条出口），
                            // 不是"回上一张脸" —— 四张脸同层，详见文件头的层级模型。
                            onExitLayer = {
                                navController.navigate(Screen.Discover.route) {
                                    popUpTo(Screen.Discover.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable(Screen.Settings.route) {
                        selectedTab = TopNavTab.Settings
                        SettingsScreen(playerVm)
                    }
                    composable(
                        route = Screen.Collection.route,
                        arguments = listOf(
                            navArgument("collectionId") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType },
                            navArgument("artwork") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val title = entry.decode("title")
                        val artwork = entry.decode("artwork")
                        CollectionScreen(title = title, artworkUrl = artwork, playerVm = playerVm)
                    }
                }
            }

            // 底部留出一条给迷你播放条的高度：内容不再被它压住，滚动到底也能看全最后一行。
            // 播放页不渲染这条（页内已经有完整控件），没有歌时也不留。
            if (!isOnNowPlaying && hasSong) {
                Spacer(Modifier.height(76.dp * (miniSize.first / 100f) + 28.dp))
            }
        }

        // 播放页是沉浸式全屏（自己铺满动态背景），不渲染顶栏 —— 顶栏的 4 个 tab
        // 只在 4 个主页面出现。播放页退回用返回键（见 BackHandler）。
        if (!isOnNowPlaying) {
            TopNavCapsule(
                selected = selectedTab,
                onSelect = { tab -> navController.navigateTab(tab) },
                updateAvailable = updateAvailable,
                upEntryFocus = navBarFocus,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // 迷你播放条：所有内容页的底部中央，离底留 26dp 空隙。
            // 它取代了顶栏里那枚「正在播放」—— 播放/切歌/看进度都在原地完成，
            // 只有按「确定」落在中间封面那块才进完整播放页。
            if (hasSong) {
                MiniPlayerCapsule(
                    playerVm = playerVm,
                    onOpen = { navController.navigateToNowPlaying() },
                    onOpenQueue = {
                        navVm.toggleQueuePanel()
                        navController.navigateToNowPlaying()
                    },
                    heightPct = miniSize.first,
                    widthPct = miniSize.second,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 26.dp),
                )
            }
        }

        // ── 返回键：只按层级走，不看"页面栈深度" ────────────────────────────────
        // L2 播放层：整层退出，回到 L1（见文件头的层级模型）。
        // 层内的墨卷 / 声轨 / 全屏歌词由 NowPlayingScreen 自己先接一手（它要等调节胶囊
        // 先收起来），最终也落到同一个出口。
        val backToLevel1: () -> Unit = {
            navController.navigate(Screen.Discover.route) {
                popUpTo(Screen.Discover.route) { inclusive = true }
                launchSingleTop = true
            }
        }
        // L1 内容层：非发现页 → 回发现页；发现页 → 退出确认。
        BackHandler(enabled = !showExitDialog && currentRoute != Screen.Discover.route) {
            backToLevel1()
        }
        BackHandler(enabled = !showExitDialog && currentRoute == Screen.Discover.route) {
            showExitDialog = true
        }

        if (showExitDialog) {
            ExitDialog(
                onDismiss = { showExitDialog = false },
                onExit = {
                    showExitDialog = false
                    (context as? Activity)?.moveTaskToBack(true)
                },
            )
        }
      }
    }
}

private fun androidx.navigation.NavBackStackEntry.decode(key: String): String =
    arguments?.getString(key)?.let { URLDecoder.decode(it, "UTF-8") }?.trim().orEmpty()

private fun androidx.navigation.NavHostController.openCollection(album: Album) {
    navigate(Screen.Collection.route(album.id, album.title, album.artworkUrl.orEmpty())) {
        launchSingleTop = true
    }
}

private fun androidx.navigation.NavHostController.navigateTab(tab: TopNavTab) {
    val target = when (tab) {
        TopNavTab.Discover -> Screen.Discover.route
        TopNavTab.Library -> Screen.Library.route
        TopNavTab.Search -> Screen.Search.route
        TopNavTab.Settings -> Screen.Settings.route
    }
    // 四个 tab 一律平级：先回到底（发现），再压入目标页。
    navigate(target) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 进入「正在播放」页。它不再是顶部 tab，改由右上角独立胶囊或遥控器 Menu 键触发，
 * 所以单独一条导航通道：直接压到「发现」上面，退回时回到发现页（见 BackHandler）。
 */
private fun androidx.navigation.NavHostController.navigateToNowPlaying() {
    navigate(Screen.NowPlaying.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExitDialog(onDismiss: () -> Unit, onExit: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF1C1C22)),
        ) {
            Column(Modifier.padding(28.dp)) {
                Text("退出薄音？", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("播放会在后台停止。", color = Color(0xFF8E8E93), fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                TextButton("退出", filled = true, onClick = onExit)
                Spacer(Modifier.height(8.dp))
                TextButton("继续听", filled = false, onClick = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TextButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (filled) Color(0xFFFA233B) else Color(0xFF2C2C2E),
            focusedContainerColor = if (filled) Color(0xFFFF3B30) else Color.White,
        ),
    ) {
        Text(
            label,
            color = if (filled) Color.White else Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}
