package com.veacon.veilmusic.ui

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R

import com.veacon.veilmusic.ui.navigation.contentFocusExitPolicy

import com.veacon.veilmusic.ui.components.FadeThrough
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import com.veacon.veilmusic.ui.components.UiMotion
import com.veacon.veilmusic.ui.components.LocalUiMotion
import com.veacon.veilmusic.ui.components.motionInput
import android.app.Activity
import android.view.WindowManager
import com.veacon.veilmusic.ui.navigation.RootPageBackHandler
import com.veacon.veilmusic.ui.navigation.NavigationReturnState
import com.veacon.veilmusic.ui.navigation.LocalNavigationReturn
import com.veacon.veilmusic.ui.navigation.returningComposable
import com.veacon.veilmusic.ui.navigation.openDetail
import com.veacon.veilmusic.ui.navigation.returnToPlayerAfterPlayback
import com.veacon.veilmusic.ui.navigation.WALL_PLAYBACK_RETURN
import com.veacon.veilmusic.ui.navigation.PLAYER_IS_WALL
import com.veacon.veilmusic.ui.navigation.selectTab
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.veacon.veilmusic.ui.viewmodel.shellUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.veacon.veilmusic.ui.components.TopNavBar
import com.veacon.veilmusic.ui.components.LocalMiniCapsuleInset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.veacon.veilmusic.ui.components.LocalMiniCapsuleFocus
import com.veacon.veilmusic.ui.components.LocalVersionFooterFocus
import com.veacon.veilmusic.ui.components.MiniPlayerCapsule
import androidx.compose.ui.draw.clipToBounds
import com.veacon.veilmusic.ui.navigation.BrowsingPageMotion
import com.veacon.veilmusic.ui.navigation.Screen
import com.veacon.veilmusic.ui.navigation.TopNavTab
import com.veacon.veilmusic.ui.screens.*
import java.net.URLDecoder
import com.veacon.veilmusic.ui.viewmodel.LibraryViewModel
import com.veacon.veilmusic.ui.viewmodel.NavigationViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import com.veacon.veilmusic.data.model.Song
import androidx.compose.material3.Text

@Composable
fun AppShell(modifier: Modifier = Modifier, onStartupReady: () -> Unit = {}) {
    val navController = rememberNavController()
    val returnState = remember(navController) { NavigationReturnState(navController) }
    var selectedTab   by remember { mutableStateOf(TopNavTab.ListenNow) }
    val playerVm: PlayerViewModel  = hiltViewModel()
    val navVm: NavigationViewModel = hiltViewModel()
    val tabFocus = remember { TopNavTab.entries.associateWith { FocusRequester() } }
    var pendingTabFocus by remember { mutableStateOf<TopNavTab?>(null) }
    var tabFocusRequestId by remember { mutableIntStateOf(0) }
    var focusedTopTab by remember { mutableStateOf<TopNavTab?>(null) }
    var navBarHasFocus by remember { mutableStateOf(false) }
    val libraryContentFocus = remember { FocusRequester() }
    fun requestTabFocus(tab: TopNavTab) {
        // V84 上游：cancel 会清掉挂起的 Tab 返回标记；从播放器返回时这个标记
        // 还要用于「返回期间禁动画」，所以在 cancel 前先读出来、cancel 后恢复。
        val returningToTab = returnState.tabReturnPending
        returnState.cancel()
        returnState.tabReturnPending = returningToTab
        selectedTab = tab
        pendingTabFocus = tab
        tabFocusRequestId++
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val inputModeManager = androidx.compose.ui.platform.LocalInputModeManager.current
    val contentFocusHandoff = com.veacon.veilmusic.ui.navigation.rememberNavigationFocusHandoff(navController, returnState)
    val profileVm: com.veacon.veilmusic.ui.viewmodel.UserProfileViewModel = hiltViewModel()
    val avatarUrl by profileVm.avatar.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profileVm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) profileVm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val contentFocus = remember { FocusRequester() }
    // Keep library identity across tabs; its cache/network work starts on Library entry only.
    val libraryVm: LibraryViewModel = hiltViewModel()
    // Hoisted so a server re-check from the Dev menu can refetch both screens —
    // reconnecting is useless if the stale "server down" content stays on screen.
    val homeVm: com.veacon.veilmusic.ui.viewmodel.HomeViewModel = hiltViewModel()

    // First run: setup owns the whole screen. Nothing behind it is usable until the
    // server and token are configured, so there is no nav bar and nothing to browse.
    val onboardingVm: com.veacon.veilmusic.ui.viewmodel.OnboardingViewModel = hiltViewModel()
    // 免登录模式：不再有登录引导，启动直接进首页。
    // 只有用户在开发者菜单主动「重播设置」时才显示（replay 分支保留）。
    var showOnboarding by remember { mutableStateOf(false) }
    var postLoginHomeReturnPending by rememberSaveable { mutableStateOf(false) }
    var postLoginHomeScrollPending by rememberSaveable { mutableStateOf(false) }
    val authorizationRevision by playerVm.authorizationRevision.collectAsState()
    val warmPages = !showOnboarding || authorizationRevision > 0L
    val browseVm: BrowseViewModel? = if (warmPages) hiltViewModel<BrowseViewModel>() else null
    val searchVm: com.veacon.veilmusic.ui.viewmodel.SearchViewModel? = if (warmPages) hiltViewModel<com.veacon.veilmusic.ui.viewmodel.SearchViewModel>() else null
    // Authorization arrival, not leaving the login screen, starts each root page's first load.
    LaunchedEffect(authorizationRevision, browseVm, searchVm) {
        if (authorizationRevision > 0L) {
            homeVm.load(clearForLogin = true).invokeOnCompletion { cause -> if (cause == null) postLoginHomeReturnPending = true }
            browseVm?.load(force = true)
            libraryVm.preloadAfterLogin()
        }
    }
    // "重新设置" in Settings resets the pref and fires this — bring setup up right away
    // instead of only on the next launch.
    val replay by playerVm.replayOnboarding.collectAsState()
    LaunchedEffect(replay) {
        if (replay) { showOnboarding = true; playerVm.consumeReplayOnboarding() }
    }
    val initialHome by homeVm.state.collectAsState()
    val startupCanDismiss = showOnboarding || initialHome.sections.isNotEmpty() || !initialHome.isLoading
    LaunchedEffect(startupCanDismiss) {
        if (startupCanDismiss) { withFrameNanos { }; onStartupReady() }
    }
    val uiMotion by remember(playerVm) {
        playerVm.state.map { state ->
            UiMotion(enabled = !state.reduceMotion)
        }.distinctUntilChanged()
    }.collectAsState(UiMotion(enabled = !playerVm.state.value.reduceMotion))
    com.veacon.veilmusic.ui.components.PageLayout {
    CompositionLocalProvider(LocalUiMotion provides uiMotion, LocalNavigationReturn provides returnState) {
    FadeThrough(showOnboarding, Modifier.fillMaxSize()) { displayedSetup ->
    if (displayedSetup) {
        OnboardingScreen(
            vm = onboardingVm,
            onDone = {
                showOnboarding = false
                playerVm.onOnboardingFinished()
                // Observe this login's actual load completion, not the pre-login cached feed.
                if (authorizationRevision == 0L) {
                    homeVm.load().invokeOnCompletion { cause ->
                        if (cause == null) postLoginHomeReturnPending = true
                    }
                    libraryVm.refresh()
                }
            },
            modifier = modifier,
        )
        return@FadeThrough
    }

    // Transient errors surface as a toast: a TV user is across the room and won't
    // find an inline banner, and silence reads as a crash.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        playerVm.toasts.collect { msg ->
            android.widget.Toast.makeText(appContext, com.veacon.veilmusic.i18n.AppLanguage.message(msg), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    LaunchedEffect(postLoginHomeReturnPending, initialHome.isLoading, initialHome.sections.isNotEmpty()) {
        if (postLoginHomeReturnPending && !initialHome.isLoading && initialHome.sections.isNotEmpty()) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
            postLoginHomeScrollPending = true
            requestTabFocus(TopNavTab.ListenNow)
            postLoginHomeReturnPending = false
        }
    }
    var tabFocusEntryId by remember { mutableIntStateOf(0) }
    val isOnNowPlaying = currentRoute == Screen.NowPlaying.route
    val destinationTab = returnState.owner(currentEntry)
    val idleLifecycle = remember { kotlinx.coroutines.flow.MutableStateFlow(androidx.lifecycle.Lifecycle.State.INITIALIZED) }
    val entryLifecycle by (currentEntry?.lifecycle?.currentStateFlow ?: idleLifecycle).collectAsState()
    // Route and actual focus share the same stable per-tab targets. Wait for a
    // remounted navigation bar after player transitions, then acknowledge real focus.
    LaunchedEffect(tabFocusRequestId, currentRoute, entryLifecycle) {
        val target = pendingTabFocus
        if (target != null && !contentFocusHandoff && destinationTab == target && !isOnNowPlaying &&
            entryLifecycle == androidx.lifecycle.Lifecycle.State.RESUMED) {
            // A touch entry leaves standard selectable Tabs in Touch input mode.
            // Explicit Back/Up means focus must be visible on a Tab immediately,
            // rather than waiting for a second D-pad event to enter keyboard mode.
            inputModeManager.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            // V84 上游：不再 clearFocus(force)——强制清空会把刚要落位的光标打掉，
            // 表现为「返回 Tab 后按一下方向键光标才出现」。
            repeat(12) {
                withFrameNanos { }
                if (focusedTopTab == target) { pendingTabFocus = null; returnState.tabReturnPending = false; return@LaunchedEffect }
                runCatching { tabFocus.getValue(target).requestFocus() }
            }
            returnState.tabReturnPending = false
        }
    }
    LaunchedEffect(returnState.pending, returnState.pendingDetailFocus) { if (contentFocusHandoff) pendingTabFocus=null }
    LaunchedEffect(isOnNowPlaying) {
        if (isOnNowPlaying) { navBarHasFocus = false; focusedTopTab = null }
    }
    val browsingRoot = currentRoute in setOf(Screen.Home.route, Screen.Browse.route,
        Screen.Library.route, Screen.Search.route)
    LaunchedEffect(Unit) {
        playerVm.userPlaybackRequests.drop(1).collect {
            navVm.notePlayerInteraction()
            if (returnState.returnToPlayerAfterPlayback()) {
                selectedTab = TopNavTab.NowPlaying
                navVm.consumeNowPlayingNavigation()
                navVm.wakePlayerControls()
            } else navVm.navigateToNowPlaying()
        }
    }

    // Keep the nav-bar highlight in sync with the ACTUAL route — otherwise popping back from
    // a pushed screen (e.g. artist opened from the video player) left the white pill stuck on
    // Now Playing. Detail routes leave the pill on whatever top tab we came from.
    LaunchedEffect(currentRoute) {
        when (currentRoute) {
            Screen.Home.route      -> selectedTab = TopNavTab.ListenNow
            Screen.Browse.route    -> selectedTab = TopNavTab.Browse
            Screen.Library.route   -> selectedTab = TopNavTab.Library
            Screen.Search.route    -> selectedTab = TopNavTab.Search
            Screen.NowPlaying.route -> selectedTab = TopNavTab.NowPlaying
            Screen.DevMenu.route   -> selectedTab = TopNavTab.Dev
            else -> {}
        }
    }
    LaunchedEffect(isOnNowPlaying) { navVm.isOnNowPlaying = isOnNowPlaying }

    // Keep the screen awake ONLY while music is actually playing. When paused, drop the
    // flag so Fire TV's own screensaver / sleep can take over (our ambient screensaver only
    // ever runs while playing).
    // Shell navigation does not render the playback clock. Ignore progress-only updates so
    // the entire navigation host is not invalidated five times a second during playback.
    val shellState = remember(playerVm) {
        playerVm.state.map { it.shellUiState() }.distinctUntilChanged()
    }
    val playerState by shellState.collectAsStateWithLifecycle(playerVm.state.value.shellUiState())
    val keepScreenOn = playerState.isPlaying
    val activity = LocalContext.current as? Activity
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val goToNowPlaying by navVm.goToNowPlaying.collectAsState()
    LaunchedEffect(goToNowPlaying) {
        if (goToNowPlaying) {
            selectedTab = TopNavTab.NowPlaying
            if (navController.currentDestination?.route != Screen.NowPlaying.route) {
                navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
            }
            navVm.consumeNowPlayingNavigation()
        }
    }

    // Overlay layout: content fills the whole screen (so Now Playing's
    // background can run fullscreen to the very top), with the nav bar drawn
    // last → it sits on a higher layer. Non-fullscreen screens get top padding
    // equal to the bar so their content isn't hidden underneath it.

    // 迷你胶囊的占位高度：胶囊本身是浮层，内容视口必须让出这么高，
    // 否则每一页的最后一行（还有部分页面的底栏）会被胶囊盖住、显示不全。
    val miniCapsuleVisible = playerState.miniCapsuleEnabled &&
        playerState.currentSong != null && !isOnNowPlaying
    val miniCapsuleInset = if (miniCapsuleVisible) {
        val hs = (playerState.miniCapsuleHeightPct / 100f).coerceIn(0.5f, 1.5f)
        76.dp * hs + 34.dp
    } else 0.dp
    // 焦点兜底：内容区走到头时把焦点交给胶囊，方便遥控。
    // 胶囊关闭时，列表第一行按 Down 还能落到下一首/下一条，左右键不动，避免焦点乱跳。
    // 新手引导：首次安装 / 本次升级后的第一次启动自动弹出，设置页也能手动重开。
        val capsuleFocus = remember { FocusRequester() }
        var contentHasFocus by remember { mutableStateOf(false) }

    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = showExitDialog) { showExitDialog = false }
    val returnToListenNow: () -> Unit = {
        requestTabFocus(TopNavTab.ListenNow)
        navController.navigate(Screen.Home.route) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }
    val returnFromPlayer: () -> Unit = {
        // V84 上游：返回播放页前的强制清焦点会丢掉恢复链路需要的焦点状态，
        // 交给 returnState.back() 自己处理。
        returnState.back()
    }
    LaunchedEffect(returnState.returnToTab) {
        returnState.returnToTab?.let { requestTabFocus(it); returnState.returnToTab=null }
    }
    // Register root-page handlers INSIDE each NavHost destination, before its
    // content. This beats NavHost's pop handler while preserving child dialogs/IME.

    // Keep a stable light underlay. The player destination paints its own black
    // canvas, which now enters/exits as part of the same NavHost crossfade.
    val shellColor = com.veacon.veilmusic.ui.theme.AppPalette.background
    androidx.compose.runtime.CompositionLocalProvider(
        com.veacon.veilmusic.ui.components.LocalMotionArtworkEnabled provides
            (playerState.motionArtworkEnabled && !playerState.lowPowerMode),
    ) {
    Box(modifier = modifier.fillMaxSize().background(shellColor)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key !in setOf(Key.Back, Key.Escape)) returnState.cancel()
            false
        }
        .pointerInput(returnState) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                returnState.cancel()
            }
        }
        .pointerInput(isOnNowPlaying) {
            if (!isOnNowPlaying) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                navVm.notePlayerInteraction()
            }
        }) {
        // 胶囊是纯悬浮层：内容区保持整屏（背景一路铺到屏幕底部），
        // 只把「胶囊高度」通过 CompositionLocal 下发，让各页把可滚动内容的
        // 底部内边距加出来 —— 既不会露出割裂的一条空白带，最后一行也能滚到胶囊上方。
        // 同时下发胶囊的焦点入口：资料库侧栏走到「艺人」按 Down 时直接落到胶囊。
        val versionFooterFocus = remember { FocusRequester() }
        // 引导页显示期间**不组合底层页面**（方案 A）：整棵 UI 树只存在一份，
        // 既省掉双份组合 / 双份动画的开销，也彻底杜绝光标钻到"看不见的"底层页面里。
        // 代价：退出引导后各页滚动位置会重置一次（ViewModel 是 Activity 级的，数据不受影响）。
        run {
        CompositionLocalProvider(
            LocalMiniCapsuleInset provides miniCapsuleInset,
            LocalMiniCapsuleFocus provides (if (miniCapsuleVisible) capsuleFocus else null),
            LocalVersionFooterFocus provides versionFooterFocus,
        ) {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            enterTransition = { BrowsingPageMotion.enter(initialState.destination.route, targetState.destination.route, uiMotion) },
            exitTransition = { BrowsingPageMotion.exit(initialState.destination.route, targetState.destination.route, uiMotion) },
            popEnterTransition = { BrowsingPageMotion.enter(initialState.destination.route, targetState.destination.route, uiMotion) },
            popExitTransition = { BrowsingPageMotion.exit(initialState.destination.route, targetState.destination.route, uiMotion) },
            modifier         = Modifier
                .fillMaxSize()
                .clipToBounds()
                .focusRequester(contentFocus)
.contentFocusExitPolicy(isOnNowPlaying, browsingRoot, tabFocus.getValue(destinationTab),
    footer = versionFooterFocus,
    capsule = if (miniCapsuleVisible && contentHasFocus) capsuleFocus else null,
)
                // 内容区按「下」走到头时兜底落到迷你胶囊：先按常规向下移动焦点，
                // 移不动说明已经到最底部 —— 直接把焦点交给胶囊。
                // （之前只靠版本行 / contentFocusExitPolicy 的出口，多数页面到底后按不动。）
                .onPreviewKeyEvent { e ->
                    val isDown = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        e.key == androidx.compose.ui.input.key.Key.DirectionDown
                    if (!isDown || !miniCapsuleVisible || isOnNowPlaying || !contentHasFocus) {
                        false
                    } else {
                        val moved = focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                        if (!moved) runCatching { capsuleFocus.requestFocus() }
                        true
                    }
                }
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusGroup(),
        ) {
            returningComposable(Screen.Home.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Home.route,
                    isHome = true,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                HomeScreen(
                    restoreTopAfterLogin = postLoginHomeScrollPending,
                    onPostLoginTopRestored = { postLoginHomeScrollPending = false },
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm = playerVm,
                    vm = homeVm,
                    onAlbumClick = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        returnState.openDetail(Screen.PlaylistDetail.route(id, name, artworkUrl))
                    },
                    // "Find Your Mood" cards are already prefixed (ac-/c-/mr-) for CategoryScreen.
                    onCategoryClick = { navController.navigate(Screen.Category.route(it)) },
                )
            }
            returningComposable(Screen.Browse.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Browse.route,
                    isHome = false,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                BrowseScreen(
                    vm = requireNotNull(browseVm),
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm       = playerVm,
                    onAlbumClick   = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art)) },
                    onGenreClick   = { id, name -> navController.navigate(Screen.Genre.route(id, name)) },
                    onCuratorClick = { navController.navigate(Screen.Category.route(it)) },
                    // "更多" at the end of a shelf → that shelf's full editorial room page.
                    onSeeAll       = { navController.navigate(Screen.Category.route("room-$it")) },
                )
            }
            returningComposable(
                route     = Screen.Genre.route,
                arguments = listOf(
                    navArgument("genreId")   { type = NavType.StringType },
                    navArgument("genreName") { type = NavType.StringType },
                ),
            ) { back ->
                val gid  = URLDecoder.decode(back.arguments?.getString("genreId")   ?: "", "UTF-8")
                val gnm  = URLDecoder.decode(back.arguments?.getString("genreName") ?: "", "UTF-8")
                GenreScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    genreId = gid, genreName = gnm, playerVm = playerVm,
                    onAlbumClick    = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art)) },
                )
            }
            returningComposable(Screen.Category.route) {
                CategoryScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    onAlbumClick    = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art)) },
                    onCuratorClick  = { navController.navigate(Screen.Category.route(it)) },
                    onArtistClick   = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    playerVm        = playerVm,
                )
            }
            returningComposable(Screen.Library.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Library.route,
                    isHome = false,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                LibraryScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    vm         = libraryVm,
                    playerVm   = playerVm,
                    contentFocusRequester = libraryContentFocus,
                    onAlbumClick    = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        val route = if (id.startsWith("imp-") || id.startsWith("lp-"))
                            Screen.LocalPlaylist.route(id)
                        else Screen.PlaylistDetail.route(id, name, artworkUrl ?: "")
                        returnState.openDetail(route)
                    },
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                )
            }
            returningComposable(Screen.Search.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Search.route,
                    isHome = false,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                SearchScreen(
                    vm = requireNotNull(searchVm),
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm = playerVm,
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        returnState.openDetail(Screen.PlaylistDetail.route(id, name, artworkUrl))
                    },
                    onCuratorClick = { id, kind ->
                        val prefix = when (kind) {
                            "multiroom"     -> "mr-"
                            "apple-curator" -> "ac-"
                            "room"          -> "room-"
                            "grouping"      -> "grouping-"
                            else            -> "c-"
                        }
                        navController.navigate(Screen.Category.route(prefix + id))
                    },
                )
            }
            returningComposable(Screen.NowPlaying.route) { entry ->
                val playerHandle = remember(entry) { entry.savedStateHandle }
                val wallPlaybackReturn by playerHandle
                    .getStateFlow(WALL_PLAYBACK_RETURN, false).collectAsState()
                BackHandler(enabled = !showExitDialog && isOnNowPlaying,
                    onBack = returnFromPlayer)
                // When a video used to fill this tab it sat over the audio screen. MV 链路已删除，
                // 这里永远只组合音频播放页。
                        NowPlayingScreen(
                        playerVm = playerVm,
                        navVm = navVm,
                        onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it), fromPlayer = true) },
                        onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it), fromPlayer = true) },
                        onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art ?: ""), fromPlayer = true) },
                        onWallCardClick = { card ->
                            val route = when (card.kind) {
                                com.veacon.veilmusic.ui.wall.WallKind.Album -> Screen.AlbumDetail.route(card.id)
                                com.veacon.veilmusic.ui.wall.WallKind.Playlist -> Screen.PlaylistDetail.route(card.id, card.title, card.artworkUrl)
                            }
                            returnState.openDetail(route, fromWall = true)
                        },
                        wallPlaybackReturn = wallPlaybackReturn,
                        onConsumeWallPlaybackReturn = { playerHandle[WALL_PLAYBACK_RETURN] = false },
                        onWallStateChanged = { playerHandle[PLAYER_IS_WALL] = it },
                        onBrowse = {
                            // 空播放页的出口：回到浏览 Tab（走和标签栏一样的路径，保留返回栈语义）。
                            selectedTab = TopNavTab.Browse
                            returnState.selectTab(TopNavTab.Browse)
                        },
                    )
            }
            returningComposable(Screen.DevMenu.route)    {
                DevMenuScreen(
                    playerVm = playerVm,
                    onDataRefresh = { homeVm.load(); libraryVm.refresh() },
                    onReturnToTab = { requestTabFocus(TopNavTab.Dev) },
                    tabBarFocused = navBarHasFocus,
                )
            }
            returningComposable(
                route     = Screen.LocalPlaylist.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) { entry ->
                LocalPlaylistScreen(
                    playlistId = java.net.URLDecoder.decode(
                        entry.arguments?.getString("playlistId").orEmpty(), "UTF-8"),
                    playerVm   = playerVm,
                    onBack     = returnState::back,
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                )
            }
            returningComposable(
                route     = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(onReturnToTab = { requestTabFocus(destinationTab) }, tabBarFocused = navBarHasFocus, playerVm = playerVm, onBack = returnState::back,
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) })
            }
            returningComposable(
                route     = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                ArtistDetailScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm = playerVm,
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                )
            }
            returningComposable(
                route     = Screen.PlaylistDetail.route,
                arguments = listOf(
                    navArgument("playlistId")   { type = NavType.StringType },
                    navArgument("playlistName") { type = NavType.StringType },
                    navArgument("artworkUrl")   { type = NavType.StringType },
                ),
            ) { back ->
                val id         = URLDecoder.decode(back.arguments?.getString("playlistId")   ?: "", "UTF-8")
                val name       = URLDecoder.decode(back.arguments?.getString("playlistName") ?: "", "UTF-8")
                val artworkUrl = URLDecoder.decode(back.arguments?.getString("artworkUrl")   ?: "", "UTF-8")
                PlaylistDetailScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playlistId    = id,
                    playlistName  = name,
                    artworkUrl    = artworkUrl.ifEmpty { null },
                    playerVm      = playerVm,
                    onBack        = returnState::back,
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                )
            }
        }
        // MUT expired banner
        if (playerState.mutExpired) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).fillMaxWidth(0.5f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFB22222))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(tr(R.string.msg_002), color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                    com.veacon.veilmusic.ui.components.TouchSurface(
                        onClick = { playerVm.dismissMutExpired() },
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color(0x33FFFFFF), focusedContainerColor = androidx.compose.ui.graphics.Color(0x55FFFFFF)),
                    ) { androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        com.veacon.veilmusic.ui.components.Icon(com.veacon.veilmusic.ui.components.Glyph.CLOSE, size = 13.dp, color = androidx.compose.ui.graphics.Color.White)
                    } }
                }
            }
        }

        // Preview mode banner
        if (!playerState.isFullStream && playerState.currentSong != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFB22222))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.Text(
                    tr(R.string.msg_003),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        }

        // Exit dialog
        FadeThrough(showExitDialog, contentKey = { if (it) true else null }) { visible ->
            if (visible) {
                com.veacon.veilmusic.ui.components.ExitAppDialog(onDismiss = { showExitDialog = false }) {
                    showExitDialog = false
                    playerVm.stopPlayback()
                    if (activity?.moveTaskToBack(true) != true) activity?.finish()
                }
            }
        }

        }

        // Keep pixels for the short exit, but disable focus/touch as soon as the player opens.
        AnimatedVisibility(visible = !isOnNowPlaying,
            modifier = Modifier.align(Alignment.TopCenter).motionInput(!isOnNowPlaying),
            enter = fadeIn(tween(
                com.veacon.veilmusic.ui.navigation.PlayerEntryMotion.duration(uiMotion),
                easing = com.veacon.veilmusic.ui.navigation.PlayerEntryMotion.easing,
            )),
            exit = fadeOut(tween(
                com.veacon.veilmusic.ui.navigation.PlayerEntryMotion.duration(uiMotion),
                easing = com.veacon.veilmusic.ui.navigation.PlayerEntryMotion.easing,
            ))) {
        Box(
            modifier = Modifier
                .onFocusChanged {
                    val hasFocus = it.hasFocus && !isOnNowPlaying && !contentFocusHandoff
                    if (hasFocus && !navBarHasFocus && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard)
                        tabFocusEntryId++
                    navBarHasFocus = hasFocus
                }
                .focusProperties {
                    // 顶栏已经是最上面：Up 不再向外找目标，避免焦点被弹到底部胶囊上。
                    up = FocusRequester.Cancel
                    onEnter = { if (!contentFocusHandoff && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard) tabFocus.getValue(destinationTab).requestFocus() }
                }.focusGroup(),
        ) {
            TopNavBar(
                selected = destinationTab,
                focusEntryId = tabFocusEntryId,
                navigationHasFocus = navBarHasFocus && !contentFocusHandoff,
                focusEnabled = !contentFocusHandoff,
                avatarUrl = avatarUrl,
                onAvatarLoadError = profileVm::onAvatarDisplayError,
                tabFocusRequesters = tabFocus,
                onTabFocusChanged = { tab, focused ->
                    if (focused) android.util.Log.i("AppleMusicFocus", "header focused ${tab.name}")
                    if (focused && !isOnNowPlaying) focusedTopTab = tab else if (focusedTopTab == tab) focusedTopTab = null
                },
                contentFocusRequester = if (currentRoute == Screen.Library.route) libraryContentFocus else contentFocus,
                // 顶栏的「正在播放」律动小条：波次1 删掉节拍 DSP 后只按播放状态显示。
                isPlaying = playerState.isPlaying,
                // 实验室开关：开启后用当前歌名替换顶栏的「正在播放」。
                nowPlayingTitle = if (playerState.navTitleAsSong) playerState.currentSong?.title else null,
                onSelect = { tab ->
                    // Focus can move again before the next composition. Read the live
                    // destination so rapid arrows never select against a stale route.
                    val activeRoute = navController.currentDestination?.route
                    if (activeRoute == Screen.NowPlaying.route) return@TopNavBar
                    if (returnState.contentFocusHandoff) return@TopNavBar
                    pendingTabFocus = null
                    selectedTab = tab
                    returnState.selectTab(tab)
                },
            )
        }
        }
        }

        // 版本行已改为各页滚动内容的最末尾（ReturnLazyColumn/ReturnLazyVerticalGrid 的
        // versionFooter 开关），不再是固定底部的浮层——不滑到最底部看不见。

        // 实验室：底部迷你播放胶囊（移植自 v1.1.2）。播放页不显示（页内已有完整控件）。
        if (miniCapsuleVisible) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp)
                    .focusRequester(capsuleFocus)
                    .focusGroup(),
            ) {
                MiniPlayerCapsule(
                    playerVm = playerVm,
                    onOpen = { navVm.navigateToNowPlaying() },
                    onOpenQueue = {
                        navVm.toggleQueuePanel()
                        navVm.navigateToNowPlaying()
                    },
                    heightPct = playerState.miniCapsuleHeightPct,
                    widthPct = playerState.miniCapsuleWidthPct,
                    albumTint = playerState.miniCapsuleAlbumTint,
                    progressLine = playerState.miniCapsuleProgressLine,
                    // 从胶囊按 Up 回到内容区（导航壳会自动接回原来的页面/侧栏）。
                    onExitUp = { runCatching { contentFocus.requestFocus() } },
                )
            }
        }
          LaunchedEffect(Unit) {
              while (true) {
                  runCatching { com.veacon.veilmusic.data.AppearanceSchedule.apply(appContext) }
                  kotlinx.coroutines.delay(60_000)
              }
          }

    }
    } // CompositionLocalProvider(LocalMotionArtworkEnabled)
    } // PageLayout
    }
    }
}







