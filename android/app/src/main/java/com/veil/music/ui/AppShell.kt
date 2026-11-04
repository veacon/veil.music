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
import com.veil.music.ui.components.NowPlayingBar
import com.veil.music.ui.components.TopNavBar
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
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import java.net.URLDecoder

/**
 * 应用外壳：顶部标签 + 内容区 + 底部播放条。
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
                        NowPlayingScreen(playerVm = playerVm, navVm = navVm, playFocus = nowPlayingPlayFocus)
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

            // 底部播放信息栏已移除：它在 TV 上既抢不到焦点（点不到），又白占一条高度。
            // 「现在在听什么」改由右上角胶囊显示歌名（见 TopNavBar 的 nowPlayingTitle）。
        }

        // 播放页是沉浸式全屏（自己铺满动态背景），不渲染顶栏 —— 顶栏的 4 个 tab
        // 和右上角「正在播放」胶囊只在 4 个主页面出现。播放页退回用返回键（见 BackHandler）。
        if (!isOnNowPlaying) {
            val ps = playerVm.state.collectAsState().value
            TopNavBar(
                selected = selectedTab,
                onSelect = { tab -> navController.navigateTab(tab) },
                isPlaying = ps.isPlaying,
                // 播放中显示歌名，没播放显示「未在播放」（胶囊内部兜底）。
                nowPlayingTitle = ps.currentSong?.title,
                updateAvailable = updateAvailable,
                beatAnalyzer = playerVm.beatAnalyzer,
                upEntryFocus = navBarFocus,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // 返回：发现页 → 退出确认；其它页 → 回发现页。
        BackHandler(enabled = !showExitDialog && currentRoute == Screen.Discover.route) {
            showExitDialog = true
        }
        BackHandler(
            enabled = !showExitDialog && currentRoute != Screen.Discover.route && !isOnNowPlaying,
        ) {
            navController.navigate(Screen.Discover.route) {
                popUpTo(Screen.Discover.route) { inclusive = true }
                launchSingleTop = true
            }
        }
        BackHandler(enabled = !showExitDialog && isOnNowPlaying) {
            navController.navigate(Screen.Discover.route) {
                popUpTo(Screen.Discover.route) { inclusive = true }
                launchSingleTop = true
            }
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
        TopNavTab.NowPlaying -> Screen.NowPlaying.route
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
