package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.ui.lyrics.ProgressState
import com.veacon.veilmusic.ui.lyrics.toKaraokeLyricLine
import com.veacon.veilmusic.R
import com.veacon.veilmusic.ui.wall.CachedWallCard
import com.veacon.veilmusic.ui.wall.WallKind
import com.veacon.veilmusic.ui.wall.AlbumWallGrid
import com.veacon.veilmusic.ui.wall.wallTouchNavigation

import com.veacon.veilmusic.ui.components.FadeThrough
import com.veacon.veilmusic.ui.components.FadeVisibility
import com.veacon.veilmusic.ui.components.LocalUiMotion
import com.veacon.veilmusic.ui.components.motionInput
import com.veacon.veilmusic.ui.components.menuInitialFocus
import com.veacon.veilmusic.ui.components.TouchSurface as Surface

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.veacon.veilmusic.ui.navigation.ReturnLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.veacon.veilmusic.ui.viewmodel.playbackOrder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.palette.graphics.Palette
import androidx.tv.material3.*
import androidx.tv.material3.Border
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.veacon.veilmusic.data.network.LyricLine
import com.veacon.veilmusic.data.network.LyricWord
import com.veacon.veilmusic.ui.viewmodel.NavigationViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import com.veacon.veilmusic.ui.components.Glyph
import com.veacon.veilmusic.ui.components.TouchSeekBar
import com.veacon.veilmusic.ui.components.artworkCornerClip
import com.veacon.veilmusic.ui.components.artworkCorners
import com.veacon.veilmusic.ui.background.MacBackground
import com.veacon.veilmusic.ui.background.LavaBackground
import com.veacon.veilmusic.ui.components.touchClick
import com.veacon.veilmusic.util.TvDevice
import com.veacon.veilmusic.ui.viewmodel.RepeatMode
import androidx.compose.foundation.gestures.scrollBy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Only the foreground below the cover is reduced; layout and focus targets retain their size.
private const val UnderCoverVisualScale = 0.77f

@Composable
private fun referenceTextHeight(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val resolvedStyle = LocalTextStyle.current.merge(style)
    val height = measurer.measure(text, resolvedStyle, maxLines = 1, softWrap = false).size.height
    return with(LocalDensity.current) { height.toDp() }
}

/** DIAGNOSTIC: off = don't spin up the motion-artwork video decoder (CPU test). */
private const val MOTION_ENABLED = true

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerVm: PlayerViewModel,
    navVm: NavigationViewModel,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onPlaylistClick: (String, String, String?) -> Unit = { _, _, _ -> },
    onWallCardClick: ((com.veacon.veilmusic.ui.wall.WallDescriptor) -> Unit)? = null,
    wallPlaybackReturn: Boolean = false,
    onConsumeWallPlaybackReturn: () -> Unit = {},
    onWallStateChanged: (Boolean) -> Unit = {},
    /** 空播放页的出口：没有任何歌曲时页面本身没有可聚焦控件，必须给遥控器留一条路。 */
    onBrowse: () -> Unit = {},
) {
    // P0-6：进度每秒校验一次，但不该驱动整页重组 —— 投影里把 progressMs 归零后去重，
    // 只有真正改变界面的字段才会触发重组；进度本身走单独的 Flow，交给叶子节点读取。
    val uiStateFlow = remember(playerVm) {
        playerVm.state.map { it.copy(progressMs = 0L) }.distinctUntilChanged()
    }
    val progressFlow = remember(playerVm) {
        playerVm.state.map { it.progressMs }.distinctUntilChanged()
    }
    val playbackState by uiStateFlow.collectAsStateWithLifecycle(playerVm.state.value.copy(progressMs = 0L))
    // 逐字歌词开关：关掉后当前行整句直接亮起，其余样式与滚动行为完全不变。
    val wordByWord by playerVm.wordByWordLyrics.collectAsState()
    // 歌词动效开关：遮罩扫过 + 长音字符弹起 + 当前行挤压（对齐 am_atv / Apple Music）。
    val lyricElastic by playerVm.lyricElastic.collectAsState()
    val artworkCentered by playerVm.artworkCentered.collectAsState()
    val state = playbackState
    val fontState = com.veacon.veilmusic.ui.theme.LocalAppFontState.current
    val song = state.currentSong
    val toggleCount by navVm.toggleQueue.collectAsState()
    val interaction by navVm.playerInteraction.collectAsState()
    val showQueue = toggleCount % 2 == 1
    val smoothProgress = rememberSmoothProgressMs(progressFlow, state.isPlaying && !state.isLoading)
    // ── 麦克风律动：RECORD_AUDIO 权限流 + 采集联动（进播放页且播放中才采集）──
    val appContext = LocalContext.current.applicationContext
    var spectrumGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val spectrumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        spectrumGranted = granted
        // 拒绝时记录并关闭开关，避免开关开着却采不到频谱、也避免反复弹窗。
        playerVm.spectrumPrefs.recordPermissionResult(granted)
        playerVm.audioSpectrum.setActive(playerVm.spectrumEnabled() && granted && state.isPlaying)
    }
    fun requestSpectrumPermission() {
        playerVm.spectrumPrefs.markPermissionRequested()
        spectrumLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // 进播放页且播放中且开关开且未授权且从未弹过 → 自动弹一次系统权限。
    // 设备不支持、或用户已拒绝过（记录里 permissionRequested=true）都不再自动弹，
    // 改由设置页明确提示用户去系统设置开启，避免反复骚扰。
    LaunchedEffect(state.isPlaying, spectrumGranted) {
        if (playerVm.spectrumEnabled() && state.isPlaying && !spectrumGranted &&
            !playerVm.spectrumPrefs.permissionRequested() &&
            com.veacon.veilmusic.data.microphonePermissionState(appContext, playerVm.spectrumPrefs) !=
                com.veacon.veilmusic.data.SpectrumPermissionState.Unavailable) requestSpectrumPermission()
    }
    // 采集联动：暂停/关开关/离开播放页 → 停止采集（无播放时也不做会话探针）
    DisposableEffect(state.isPlaying, spectrumGranted) {
        playerVm.audioSpectrum.setActive(playerVm.spectrumEnabled() && state.isPlaying && spectrumGranted)
        onDispose { playerVm.audioSpectrum.setActive(false) }
    }
    val spectrumProvider: (() -> com.veacon.veilmusic.media.AudioSpectrum)? =
        if (playerVm.spectrumEnabled() && spectrumGranted && state.isPlaying) playerVm.audioSpectrum::sample else null
    // 背景风格：流体（Mac 流体）/ 熔岩（Lava 流体，随封面取色）——设置页可切换
    val backgroundStyle by playerVm.backgroundStyle.collectAsState()
    val pulseMultiplier = com.veacon.veilmusic.ui.background.LavaPulseSpeed.multiplier(
        playerVm.pulseSpeedIndex.collectAsState().value)
    val playFocus = remember { FocusRequester() }
    val splitCoverFocus = remember { FocusRequester() }
    val fullCoverFocus = remember { FocusRequester() }
    val fullPlayFocus = remember { FocusRequester() }
    val wallGridFocus = remember { FocusRequester() }
    var wallSelection by rememberSaveable { mutableStateOf(false) }
    var wallSelectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    var returnToWallCover by remember { mutableStateOf(false) }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var transferCoverFocus by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showSongDestinations by remember(song?.id) { mutableStateOf(false) }
    // V84 上游：检测歌曲菜单的打开方式（遥控器 OK/Enter/菜单键 vs 触摸触摸封面）。
    // 遥控器打开的菜单要给当前项画白底高亮（焦点建立前的视觉反馈），触摸则不用。
    var songMenuOpeningMode by remember { mutableStateOf<androidx.compose.ui.input.InputMode?>(null) }
    val songMenuInput = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu))
            songMenuOpeningMode = androidx.compose.ui.input.InputMode.Keyboard
        false
    }.pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); songMenuOpeningMode = androidx.compose.ui.input.InputMode.Touch }
    }
    // 菜单键唤出的「播放信息」浮层（歌曲/音质/音源/解码器）。
    var showPlaybackInfo by remember { mutableStateOf(false) }
    var showLyricsMenu by remember { mutableStateOf(false) }
    var lyricsHidden by rememberSaveable { mutableStateOf(false) }
    // A portrait viewport overrides presentation without changing the user's normal choice.
    var preferredFullScreenLyrics by rememberSaveable { mutableStateOf(false) }
    var albumWall by rememberSaveable { mutableStateOf(false) }
    var coverOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var playerOrigin by remember { mutableStateOf(Offset.Zero) }
    var coverMoving by remember { mutableStateOf(false) }
    val wallProgress = remember { Animatable(if (albumWall) 1f else 0f) }
    var previousWall by remember { mutableStateOf(albumWall) }
    LaunchedEffect(albumWall) {
        if (previousWall != albumWall) {
            previousWall = albumWall
            wallProgress.animateTo(if (albumWall) 1f else 0f, tween(500, easing = FastOutSlowInEasing))
            coverMoving = false
        }
    }
    fun closeAlbumWall() {
        transferCoverFocus = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard
        coverMoving = coverOrigin != null
        albumWall = false
        wallSelection = false
        playerVm.prepareNextAlbumWall()
        navVm.wakePlayerControls()
    }
    var libraryWallArt by remember { mutableStateOf<List<CachedWallCard>>(emptyList()) }
    var wallReady by remember { mutableStateOf(false) }
    var wallCapacity by remember { mutableIntStateOf(0) }
    var wallRequested by remember { mutableStateOf(false) }
    LaunchedEffect(playerVm, wallCapacity) {
        if (wallCapacity <= 0) return@LaunchedEffect
        libraryWallArt = playerVm.prefetchAlbumWall(wallCapacity).await()
        wallReady = true
        if (wallRequested) {
            playerVm.takePreparedAlbumWall()?.let { libraryWallArt = it; wallSelectedIndex = -1 }
            coverMoving = coverOrigin != null
            albumWall = true; wallRequested = false
        }
    }
    val wallBoard = remember(playerVm, libraryWallArt, wallCapacity) {
        playerVm.albumWallBoard(libraryWallArt, wallCapacity)
    }
    val visibleWallArt = wallBoard.cards
    fun openAlbumWall() {
        transferCoverFocus = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard
        navVm.closeQueuePanel()
        if (wallReady) {
            playerVm.takePreparedAlbumWall()?.let { libraryWallArt = it; wallSelectedIndex = -1 }
            coverMoving = coverOrigin != null
            albumWall = true
        } else wallRequested = true
        navVm.wakePlayerControls()
    }
    fun openWallCard(card: CachedWallCard) {
        showSongDestinations = false
        onWallCardClick?.let { it(card.info); return }
        when (card.info.kind) {
            WallKind.Album -> onAlbumClick(card.info.id)
            WallKind.Playlist -> onPlaylistClick(card.info.id, card.info.title, card.info.artworkUrl)
        }
    }
    var lyricsHiddenBeforeFullScreen by rememberSaveable { mutableStateOf(false) }
    val uiMotion = LocalUiMotion.current
    val chromeAlpha by animateFloatAsState(if (chromeVisible) 1f else 0f, tween(playerMotionDuration(uiMotion.enabled)), label = "playerChrome")
    val idle by animateFloatAsState(if (chromeVisible) 0f else 1f, tween(playerMotionDuration(uiMotion.enabled)), label = "playerIdle")

    LaunchedEffect(wallPlaybackReturn) {
        if (wallPlaybackReturn) {
            albumWall = true
            wallSelection = false
            coverMoving = false
            transferCoverFocus = false
            returnToWallCover = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard
            chromeVisible = true
            navVm.closeQueuePanel()
            navVm.wakePlayerControls()
            onConsumeWallPlaybackReturn()
        }
    }

    SideEffect { onWallStateChanged(albumWall) }
    val albumWallOpen by rememberUpdatedState(albumWall)
    DisposableEffect(Unit) {
        playerVm.nowPlayingVisible = true
        onDispose {
            playerVm.nowPlayingVisible = false
            if (albumWallOpen) playerVm.prepareNextAlbumWall()
            navVm.setPlayerChromeVisible(true)
            navVm.fullScreenLyricsHasSplitOrigin = false
        }
    }
    LaunchedEffect(interaction) { lastInteractionMs = System.currentTimeMillis(); chromeVisible = true }
    PlayerChromeAutoHide(song?.id, state.isLoading, state.isPlaying, lastInteractionMs, showSongDestinations || showLyricsMenu || wallSelection) {
        chromeVisible = it
        navVm.setPlayerChromeVisible(it)
    }
    LaunchedEffect(chromeVisible) { navVm.setPlayerChromeVisible(chromeVisible) }
    // Transient queue closes first; the shell then returns to the exact source entry.

    // Background-only GLES2 integration; the approved foreground layout stays in place.
    BoxWithConstraints(
        modifier.fillMaxSize().background(Color.Black)
            .onGloballyPositioned { playerOrigin = it.positionInRoot() }
            // The wake-up gate belongs only to the player, never to the floating tabs.
            .wallTouchNavigation(wallVisible = albumWall && !coverMoving, selectionMode = wallSelection,
                cards = visibleWallArt, blocked = { wallBoard.flip?.tile == it }, controlsVisible = { navVm.playerChromeVisible.value },
                onWake = navVm::wakePlayerControls,
                onOpen = { index, card ->
                    if (wallSelection) wallSelectedIndex = index
                    openWallCard(card)
                })
            .onPreviewKeyEvent { event ->
                // 菜单键打开播放信息浮层。原来用「长按 ↓」，但多数电视遥控器按住方向键时
                // 系统只连发 repeatCount=0 的事件、甚至直接吞掉长按，实机上很难触发；
                // 菜单键按一下即可，且不与「下移焦点」的正常用途冲突。
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu && !albumWall) {
                    showPlaybackInfo = true
                    return@onPreviewKeyEvent true
                }
                if (albumWall && !wallSelection && libraryWallArt.isNotEmpty() &&
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    wallSelectedIndex = -1
                    wallSelection = true
                    navVm.wakePlayerControls()
                    true
                } else if(navVm.consumePlayerWakeKey(event.nativeKeyEvent)) true
                else {
                    if(event.type==KeyEventType.KeyDown && isNavKey(event.key)) {
                        lastInteractionMs=System.currentTimeMillis()
                        chromeVisible=true
                    }
                    false
                }
            },
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val shortEdge = minOf(constraints.maxWidth, constraints.maxHeight)
        if (shortEdge > 0) {
            val cell = shortEdge / 5f
            val count = kotlin.math.ceil(constraints.maxWidth / cell).toInt() * kotlin.math.ceil(constraints.maxHeight / cell).toInt()
            SideEffect { wallCapacity = count.coerceIn(1, 100) }
        }
        // Use the actual page viewport: every portrait size presents full-screen lyrics.
        val forceFullScreenLyrics = constraints.maxWidth > 0 &&
            constraints.maxHeight > constraints.maxWidth
        val fullScreenLyrics = forceFullScreenLyrics || preferredFullScreenLyrics
        LaunchedEffect(albumWall) {
            if (transferCoverFocus) {
                val target = if (albumWall || fullScreenLyrics) fullCoverFocus else splitCoverFocus
                repeat(90) {
                    withFrameNanos { }
                    if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                        transferCoverFocus = false
                        return@LaunchedEffect
                    }
                }
                transferCoverFocus = false
            }
        }
        LaunchedEffect(wallSelection, albumWall) {
            if (wallSelection && albumWall) repeat(60) {
                withFrameNanos { }
                if (runCatching { wallGridFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        LaunchedEffect(returnToWallCover) {
            if (returnToWallCover) {
                repeat(60) {
                    withFrameNanos { }
                    if (runCatching { fullCoverFocus.requestFocus() }.getOrDefault(false)) {
                        returnToWallCover = false; return@LaunchedEffect
                    }
                }
                returnToWallCover = false
            }
        }
        SideEffect { navVm.fullScreenLyricsHasSplitOrigin = preferredFullScreenLyrics && !forceFullScreenLyrics }
        androidx.activity.compose.BackHandler(enabled = !chromeVisible) { navVm.wakePlayerControls() }
        androidx.activity.compose.BackHandler(enabled = showQueue && chromeVisible && !fullScreenLyrics) { navVm.toggleQueuePanel() }
        FullScreenLyricsBackHandler(preferredFullScreenLyrics, forceFullScreenLyrics) {
            preferredFullScreenLyrics = false
            lyricsHidden = lyricsHiddenBeforeFullScreen
            navVm.wakePlayerControls()
        }
        androidx.activity.compose.BackHandler(enabled = albumWall) {
            if (wallSelection) {
                wallSelection = false; returnToWallCover = true; navVm.wakePlayerControls()
            } else if (chromeVisible) closeAlbumWall() else navVm.wakePlayerControls()
        }
        LaunchedEffect(forceFullScreenLyrics) { showSongDestinations = false }
        val lyricsPanelVisible = !lyricsHidden && (fullScreenLyrics || !(showQueue && chromeVisible))
        LaunchedEffect(song?.id, state.lyricsNotice, state.lyricsNoticeGeneration, state.isLoading, fontState.ready, lyricsPanelVisible) {
            if (state.lyricsNotice == com.veacon.veilmusic.ui.viewmodel.LyricsNoticePhase.Pending &&
                !state.isLoading && fontState.ready && state.lyrics.isEmpty() && lyricsPanelVisible &&
                song?.isMusicVideo == false && state.lyricsNoticeSongId == song.id)
                playerVm.showEmptyLyricsNotice(song.id,state.lyricsNoticeGeneration)
        }
        FadeThrough(state, Modifier.fillMaxSize(), contentKey = { it.currentSong != null }) { state ->
            val song = state.currentSong
        if (song == null) {
            IdlePlayerBackground(state.fluidQuality,state.reduceMotion,state.lowPowerMode)
            Box(Modifier.fillMaxSize(),Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    PlayerStatusText(tr(R.string.msg_279),state.lyricsScale,Modifier)
                    // 没有歌曲时整页没有可聚焦控件：遥控器上下左右/确认全失效，只剩返回。
                    // 给一个「去浏览音乐」按钮，进入即聚焦，保证这个 Tab 不会是死路。
                    val browseFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        repeat(60) {
                            withFrameNanos { }
                            if (runCatching { browseFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                        }
                    }
                    Surface(
                        onClick = onBrowse,
                        modifier = Modifier.focusRequester(browseFocus),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                            focusedContainerColor = Color(0xFFFA233B),
                            focusedContentColor = Color.White,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    ) {
                        Text(tr(R.string.msg_326), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                    }
                }
            }
            return@FadeThrough
        }
        val wallArt = visibleWallArt
        Crossfade(targetState = albumWall, animationSpec = tween(350), label = "wallBackground") { showingWall ->
            if (showingWall) AlbumWallGrid(cards = wallArt, board = wallBoard,
                autoFlipEnabled = albumWall && !coverMoving && !wallSelection && !showSongDestinations &&
                    !showLyricsMenu && !state.reduceMotion && !state.isInPip,
                selectionMode = wallSelection && albumWall, selectedIndex = wallSelectedIndex,
                onSelectedIndex = { wallSelectedIndex = it }, gridFocus = wallGridFocus,
                touchEnabled = albumWall && chromeVisible,
                onOpen = ::openWallCard)
            else if (backgroundStyle == com.veacon.veilmusic.data.BackgroundStylePreferences.LAVA) LavaBackground(
                artworkUrl = song.artworkUrl(500), quality = state.fluidQuality,
                playing = state.isPlaying, reduceMotion = state.reduceMotion, lowPower = state.lowPowerMode,
                modifier = Modifier.fillMaxSize(),
                spectrum = spectrumProvider,
                pulseSpeedMultiplier = pulseMultiplier,
            )
            else MacBackground(
                artworkUrl = song.artworkUrl(500), quality = state.fluidQuality,
                playing = state.isPlaying, reduceMotion = state.reduceMotion, lowPower = state.lowPowerMode,
                modifier = Modifier.fillMaxSize(),
                spectrum = spectrumProvider,
                pulseSpeedMultiplier = pulseMultiplier,
            )
        }
        // Remount when switching orientation so the old split view cannot flash in portrait.
        // Playback, queue, progress and chrome timing remain outside this presentation key.
        key(forceFullScreenLyrics) {
        FadeThrough(fullScreenLyrics || albumWall, Modifier.fillMaxSize()) { displayedFullScreen ->
            if (displayedFullScreen) {
                FullScreenLyrics(
                    lyrics = state.lyrics, progressState = smoothProgress, offsetMs = state.avLyricsMs,
                    song = state.currentSong ?: song, isPlaying = state.isPlaying, onSeek = { playerVm.player.seekTo(it) },
                    onPrev = playerVm::prev, onPlayPause = playerVm::togglePlayPause, onNext = playerVm::next,
                    lyricsScale = state.lyricsScale, chromeVisible = chromeVisible,
                    loading = state.isLoading && !state.isPlaying,
                    showEmptyLyrics = emptyLyricsNoticeVisible(state,state),
                    lyricsHidden = lyricsHidden || albumWall,
                    onArtworkClick = { if (albumWall) closeAlbumWall() else openAlbumWall() },
                    coverFocusRequester = fullCoverFocus,
                    suppressPlayFocus = transferCoverFocus || wallSelection || returnToWallCover,
                    footerVisible = !wallSelection,
                    playFocusRequester = fullPlayFocus,
                    onSongInfoClick = { showSongDestinations = !showSongDestinations },
                    songInfoInput = songMenuInput,
                    songInfoMenu = {
                        SongDestinationsPopup(visible = showSongDestinations,
                            song = song, playerVm = playerVm, openingMode = songMenuOpeningMode, onDismiss = { showSongDestinations = false },
                            onArtistClick = { showSongDestinations = false; onArtistClick(it) },
                            onAlbumClick = { showSongDestinations = false; onAlbumClick(it) })
                    },
                    hideArtwork = coverMoving,
                    onArtworkPosition = { rect -> if (!albumWall && !coverMoving) coverOrigin = rect.translate(-playerOrigin) },
                    wordByWord = wordByWord,
                    elastic = lyricElastic,
                    artworkCompat = state.renderCompat,
                )
            } else {
                PlayerPlayFocusEffect(playFocus,chromeVisible, suppressInitialFocus = transferCoverFocus)
                val originalSide = (screenWidth * 0.075f).coerceIn(32.dp, 96.dp)
                val originalGap = (screenWidth * 0.07f).coerceIn(36.dp, 112.dp)
                val columnWidth = (screenWidth * 0.34f).coerceIn(260.dp, 380.dp)
                val coverSize = minOf(columnWidth, (screenWidth * 0.29f).coerceAtMost(360.dp),
                    (screenHeight - 316.dp).coerceAtLeast(120.dp))
                val spacing = playerColumnSpacing(screenWidth, originalSide, originalGap, coverSize)
                // Retain the former metadata height so smaller text adds breathing room without
                // moving the vertically centred cover or the neighbouring lyrics column.
                val metadataHeight = referenceTextHeight(song.title,
                    TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)) +
                    referenceTextHeight(song.artistName,
                        TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal)) + 10.dp
                // 专辑下方歌名/歌手字号档位（设置 → 封面动效）；基准高度按标准档预留，
                // 小档多出呼吸空间不挪封面，大档自然撑开。
                val songInfoScale = state.songInfoScale
                val centeredControls=centerPlayerControls(state,lyricsHidden,showQueue&&chromeVisible,fontState.ready,artworkCentered)
                PlayerSplitLayout(screenWidth,coverSize,spacing,
                    centered=centeredControls,
                    controls={
                    Column(
                        Modifier.width(coverSize).graphicsLayer {
                            translationY = idle * 36.dp.toPx()
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(coverSize)
                                .onGloballyPositioned { if (!albumWall && !coverMoving) coverOrigin = it.boundsInRoot().translate(-playerOrigin) }
                                // 不再常驻一张 alpha 图层：只有翻开/收起封面墙时才需要遮住封面。
                                // 常驻离屏层会在个别电视 GPU（Realtek RTD2886 等）上于封面区域留下马赛克。
                                .then(if (coverMoving) Modifier.graphicsLayer { alpha = 0f } else Modifier)
                                .albumCoverFocus(splitCoverFocus, if (state.artworkRounded) 10.dp else 0.dp) { openAlbumWall() }
                                // 圆角：马赛克修复开启时用画布裁剪（不开离屏图层），否则保持旧路径。
                                .artworkCorners(if (state.artworkRounded) 10.dp else 0.dp, state.renderCompat)
                                .background(Color(0xFF181818)),
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(song.artworkUrl(900))
                                    .crossfade(675).build(),
                                contentDescription = song.title, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            // 渲染兼容模式只改圆角的合成路径（画布裁剪代替离屏图层）；
                            // 且开关开启时不再叠加动态封面（歌单卡片与精选页不受影响）。
                            if (state.motionArtworkEnabled && !state.lowPowerMode && !state.renderCompat) state.motionUrl?.let { url ->
                                com.veacon.veilmusic.ui.components.MotionArtwork(
                                    url, play = state.isPlaying, modifier = Modifier.fillMaxSize(),
                                    startDelayMs = 250L, maxVideoSize = 640,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().heightIn(min = metadataHeight), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp)) {
                                FadeVisibility(chromeVisible, durationMs = PlayerMotionDurationMs) { PlayerAccessoryButton(Glyph.QUEUE, if (showQueue) tr(R.string.msg_141) else tr(R.string.msg_280), showQueue,
                                    visualScale = UnderCoverVisualScale) {
                                    navVm.toggleQueuePanel()
                                } }
                            }
                            Box(Modifier.weight(1f)) {
                                SongInfoFocusSurface(
                                    onClick = { showSongDestinations = !showSongDestinations },
                                    chromeAlpha = chromeAlpha,
                                    modifier = songMenuInput.fillMaxWidth().semantics { contentDescription = tr(R.string.msg_281, song.title, song.artistName) },
                                ) {
                                    FadeThrough(song, Modifier.fillMaxWidth(), contentKey = { it.id }) { song ->
                                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally) {
                                            MarqueeText(song.title, fontSize = 17.sp * songInfoScale * UnderCoverVisualScale, fontWeight = FontWeight.SemiBold,
                                                color = Color.White, modifier = Modifier.fillMaxWidth())
                                            MarqueeText(song.artistName, fontSize = 14.sp * songInfoScale * UnderCoverVisualScale, color = PlayerSecondaryColor,
                                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                                        }
                                    }
                                }
                                SongDestinationsPopup(
                                    visible = showSongDestinations,
                                    song = song, playerVm = playerVm, openingMode = songMenuOpeningMode,
                                    onDismiss = { showSongDestinations = false },
                                    onArtistClick = { showSongDestinations = false; onArtistClick(it) },
                                    onAlbumClick = { showSongDestinations = false; onAlbumClick(it) },
                                )
                            }
                            Box(Modifier.size(36.dp)) {
                                FadeVisibility(chromeVisible, durationMs = PlayerMotionDurationMs) {
                                    LyricsOptionsButton(lyricsHidden,showLyricsMenu,{showLyricsMenu=it},
                                        onToggleLyrics={lyricsHidden=!lyricsHidden;navVm.closeQueuePanel()},
                                        onFullScreen={lyricsHiddenBeforeFullScreen=lyricsHidden;lyricsHidden=false;navVm.closeQueuePanel();preferredFullScreenLyrics=true},
                                        visualScale=UnderCoverVisualScale)
                                }
                            }
                        }
                        Spacer(Modifier.height(0.dp))
                        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = chromeAlpha }) {
                            PlayerProgressBar(smoothProgress, song, playFocus, playerVm.player,
                                visualScale = UnderCoverVisualScale, loading = state.isLoading && !state.isPlaying)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                PlayerFavoriteButton(visualScale=UnderCoverVisualScale) {playerVm.addToLibrary(song)}
                                TransportButton(TransportIcon.Prev, playerVm::prev, visualScale = UnderCoverVisualScale, inactiveColor = PlayerControlInactiveColor)
                                TransportButton(if (state.isPlaying) TransportIcon.Pause else TransportIcon.Play,
                                    playerVm::togglePlayPause, large = true, loading = state.isLoading && !state.isPlaying,
                                    active = state.isPlaying,
                                    modifier = Modifier.focusRequester(playFocus), visualScale = UnderCoverVisualScale, inactiveColor = PlayerControlInactiveColor)
                                TransportButton(TransportIcon.Next, playerVm::next, visualScale = UnderCoverVisualScale, inactiveColor = PlayerControlInactiveColor)
                                PlaybackOrderButton(state.playbackOrder(),visualScale=UnderCoverVisualScale,
                                    onClick=playerVm::cyclePlaybackOrder)
                            }
                        }
                    }
                    }, panel={
                        val panel = when {
                            showQueue && chromeVisible -> "queue"
                            lyricsHidden -> "hidden"
                            state.lyrics.isNotEmpty() -> "lyrics"
                            else -> "empty"
                        }
                        val currentLyricsState = state
                        val emptyPanelIsCurrent = panel == "empty"
                        FadeThrough(panel to state, Modifier.fillMaxSize(), contentKey = { it.first }) { (panel, state) ->
                            when {
                                panel == "hidden" -> Unit
                                panel == "queue" -> QueuePanel(
                                    state.queue, state.queueIndex, state.userQueue,
                                    onSelect = playerVm::playFromQueue,
                                    onSelectUserQueue = playerVm::playFromUserQueue,
                                    onMove = playerVm::moveQueueItem, leftFocus = playFocus,
                                )
                                else -> com.veacon.veilmusic.ui.components.FontGatedLyrics(fontState, fontScale = state.lyricsScale) {
                                if (panel == "lyrics") LyricsPanel(
                                    lyrics = state.lyrics, progressState = smoothProgress, offsetMs = state.avLyricsMs,
                                    onSeek = { playerVm.player.seekTo(it) }, playFocus = playFocus,
                                    fontScale = state.lyricsScale, wordByWord = wordByWord,
                                    elastic = lyricElastic,
                                )
                                else if (panel == "empty" && !state.isLoading) EmptyLyrics(song.id, state.lyricsScale, emptyPanelIsCurrent && emptyLyricsNoticeVisible(state,currentLyricsState), centered=centeredControls)
                                }
                            }
                        }
                    })
            }
        }
        }
        if (coverMoving) coverOrigin?.let { origin ->
            val density = LocalDensity.current
            val portrait = screenHeight > screenWidth
            val side = if (portrait) (screenWidth * 0.07f).coerceIn(20.dp, 48.dp) else 64.dp
            val bottom = if (portrait) side else 48.dp
            val targetSize = with(density) { 64.dp.toPx() }
            val targetLeft = with(density) { side.toPx() }
            val targetTop = with(density) { screenHeight.toPx() - bottom.toPx() - targetSize }
            val t = wallProgress.value
            val x = lerp(origin.left, targetLeft, t)
            val y = lerp(origin.top, targetTop, t)
            val size = lerp(origin.width, targetSize, t)
            AsyncImage(
                model = song.artworkUrl(900), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { size.toDp() }).artworkCorners(10.dp, state.renderCompat),
            )
        }
        NextUpToast(
            smoothProgress, song.durationMs,
            (state.userQueue.firstOrNull() ?: state.queue.getOrNull(state.queueIndex + 1))?.title, song.id, visible = chromeVisible && !wallSelection,
        )
        if (showPlaybackInfo) PlaybackInfoDialog(
            song = song,
            stream = state.resolvedStream?.takeIf { it.songId == song.id },
            decoderMode = state.decoderMode,
            lyricsSource = state.lyricsSource,
            onRetrySource = { showPlaybackInfo = false; playerVm.retryCurrentSource() },
            onRetryLyrics = { showPlaybackInfo = false; playerVm.retryCurrentLyrics() },
            onDismiss = { showPlaybackInfo = false },
        )
        }
    }
}

@Composable
private fun SongDestinationsPopup(
    visible: Boolean,
    song: com.veacon.veilmusic.data.model.Song,
    playerVm: PlayerViewModel,
    openingMode: androidx.compose.ui.input.InputMode?,
    onDismiss: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    val visibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    visibility.targetState = visible
    if (visibility.isIdle && !visibility.currentState) return
    var ids by remember(song.id) { mutableStateOf(song.artistId to song.albumId) }
    var resolving by remember(song.id) { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    // V84 上游：遥控器打开的菜单，在焦点真正建立前先用白底标出当前项。
    var remoteSelection by remember(song.id) { mutableIntStateOf(if (ids.first != null) 0 else 1) }
    LaunchedEffect(ids) { if (ids.first != null) remoteSelection = 0 else if (ids.second != null) remoteSelection = 1 }
    LaunchedEffect(song.id) {
        if (ids.first == null || ids.second == null) {
            resolving = true
            val resolved = playerVm.lookupSongIds(song.id)
            ids = (ids.first ?: resolved.first) to (ids.second ?: resolved.second)
            resolving = false
        }
    }
    PlayerBubbleMenu(visibility,onDismiss,initialFocus=firstFocus, focusAvailable=ids.first != null || ids.second != null, openingMode=openingMode) {
        if(resolving) Text(tr(R.string.msg_282),color=Color(0xFF6E6E73),fontSize=12.sp,modifier=Modifier.padding(12.dp))
        NpMenuItem(tr(R.string.msg_132),modifier=(if (ids.first != null) Modifier.focusRequester(firstFocus) else Modifier).onFocusChanged { if (it.isFocused) remoteSelection = 0 },selectedByRemote=openingMode == androidx.compose.ui.input.InputMode.Keyboard && remoteSelection == 0 && ids.first != null,icon=Glyph.ARTIST,
            enabled=ids.first!=null) {ids.first?.let(onArtistClick)}
        NpMenuItem(tr(R.string.msg_133),modifier=(if (ids.first == null && ids.second != null) Modifier.focusRequester(firstFocus) else Modifier).onFocusChanged { if (it.isFocused) remoteSelection = 1 },selectedByRemote=openingMode == androidx.compose.ui.input.InputMode.Keyboard && remoteSelection == 1 && ids.second != null,icon=Glyph.ALBUM,enabled=ids.second!=null) {ids.second?.let(onAlbumClick)}
    }
}

private fun infoText(zh: String, en: String): String =
    if (com.veacon.veilmusic.i18n.AppLanguage.code == "en") en else zh

/** 平台代码 → 展示名。落雪脚本用的是 wy / tx / kg 这套缩写。 */
private fun platformLabel(raw: String?): String = when (raw?.lowercase()) {
    "wy", "netease", "163" -> infoText("网易云", "NetEase")
    "tx", "qq" -> "QQ"
    "kg", "kugou" -> infoText("酷狗", "Kugou")
    "mg", "migu" -> infoText("咪咕", "Migu")
    null, "" -> ""
    else -> raw
}

/**
 * 菜单键唤出的播放信息浮层：当前歌曲-歌手、音质、使用的音源、使用的播放器（硬解/软解）。
 *
 * 取代过去每首歌都弹一次的「准备播放 …」toast —— 同样的信息，但只在需要时出现。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackInfoDialog(
    song: com.veacon.veilmusic.data.model.Song,
    stream: com.veacon.veilmusic.ui.viewmodel.ResolvedStream?,
    decoderMode: com.veacon.veilmusic.data.DecoderMode,
    lyricsSource: String?,
    onRetrySource: () -> Unit,
    onRetryLyrics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    val primary = com.veacon.veilmusic.ui.theme.AppPalette.primary
    val secondary = com.veacon.veilmusic.ui.theme.AppPalette.secondary
    val surface = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF1C1C1E))
    val divider = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE3E3E8), Color(0xFF3A3A3F))

    val sourceValue = buildString {
        val name = stream?.sourceName?.takeIf { it.isNotBlank() }
        val platform = platformLabel(stream?.platform)
        if (name != null) append(name)
        if (platform.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(platform)
        }
    }.ifEmpty { infoText("未知", "Unknown") }

    val playerValue = when (decoderMode) {
        com.veacon.veilmusic.data.DecoderMode.HARDWARE -> infoText("硬解 · MediaCodec", "Hardware · MediaCodec")
        com.veacon.veilmusic.data.DecoderMode.SOFTWARE -> infoText("软解 · FFmpeg", "Software · FFmpeg")
    }

    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(18.dp)).background(surface)
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(8.dp))
                        .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF2C2C31))),
                ) {
                    if (song.artworkUrl != null) AsyncImage(
                        model = song.artworkUrl(120), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("${song.title} - ${song.artistName}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = primary, maxLines = 2)
                    Text(infoText("播放信息", "Playback info"), fontSize = 11.sp,
                        color = secondary, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            PlaybackInfoRow(infoText("音质", "Quality"), stream?.qualityLabel ?: "—", primary, secondary)
            PlaybackInfoRow(infoText("音源", "Source"), sourceValue, primary, secondary)
            PlaybackInfoRow(infoText("播放器", "Decoder"), playerValue, primary, secondary)
            PlaybackInfoRow(infoText("歌词来源", "Lyrics source"), lyricsSource?.takeIf { it.isNotBlank() } ?: "—", primary, secondary)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
            Spacer(Modifier.height(14.dp))
            // 歌曲/歌词偶尔会解析错或拿到坏源，这里给一条手动换源的口子。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = { onRetrySource() },
                    modifier = Modifier.weight(1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                        focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF48484D)),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Text(tr(R.string.msg_332), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                }
                Surface(
                    onClick = { onRetryLyrics() },
                    modifier = Modifier.weight(1f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                        focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF48484D)),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Text(tr(R.string.msg_333), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
                    .menuInitialFocus(closeFocus),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                    focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF48484D)),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            ) {
                Text(infoText("关闭", "Close"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun PlaybackInfoRow(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = valueColor, modifier = Modifier.width(78.dp))
        Text(value, fontSize = 14.sp, color = labelColor, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

@Composable
internal fun PlayerAccessoryButton(
    glyph: Glyph, label: String, selected: Boolean = false,
    visualScale: Float = 1f, modifier: Modifier = Modifier, onContextMenu: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, onLongClick = onContextMenu, onContextMenu = onContextMenu,
        modifier = modifier.size(36.dp).semantics { contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = Color(0x22FFFFFF)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            com.veacon.veilmusic.ui.components.Icon(glyph, size = 18.dp * visualScale,
                color = if (selected) Color.White else PlayerControlInactiveColor)
        }
    }
}

/**
 * Small "下一首…" toast, bottom-right, that fades in ~15s before the track switches.
 * Reads the per-frame clock via derivedStateOf so only the boolean flip recomposes it.
 */
@Composable
private fun BoxScope.NextUpToast(progressState: androidx.compose.runtime.State<Long>, durationMs: Long, nextTitle: String?, songKey: String, visible: Boolean = true,
) {
    val rawShow by remember(durationMs, nextTitle, visible) {
        androidx.compose.runtime.derivedStateOf {
            visible && nextTitle != null && durationMs > 0L && (durationMs - progressState.value) in 1_000L..15_000L
        }
    }
    // Capture the "next" title ONCE, when the toast arms, and keep showing that. At a track boundary
    // the queue advances a few frames before the current song does, so the live next-title briefly
    // points one song too far ahead (the "next next"). Freezing the captured title ignores that
    // transient; a real track change flips songKey and resets us.
    var shownTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(songKey) { shownTitle = null }
    LaunchedEffect(rawShow, songKey) {
        if (!rawShow) { shownTitle = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        if (rawShow) shownTitle = nextTitle
    }
    var lastTitle by remember { mutableStateOf<String?>(null) }
    SideEffect { if (shownTitle != null) lastTitle = shownTitle }
    val motion = LocalUiMotion.current
    val alpha by animateFloatAsState(if (shownTitle != null) 1f else 0f,
        tween(if (motion.enabled) motion.enterMs else 0), label = "nextToast")
    val title = shownTitle ?: lastTitle
    if (alpha <= 0.01f || title == null) return
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 52.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(Color(0xE6161616), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(tr(R.string.msg_283), style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFA233B), letterSpacing = 1.5.sp))
        Text(title, maxLines = 1, style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
    }
}

/**
 * Ambient screensaver — the drifting background (rendered behind) plus a small
 * now-playing chip bottom-left. Grabs focus so any key wakes it back to the player.
 */
@Composable
private fun AmbientScreensaver(song: com.veacon.veilmusic.data.model.Song, onWake: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .touchClick(onWake)
            .focusRequester(fr)
            .focusable()
            .onKeyEvent { onWake(); true },
    ) {
        // Heavy dim over the drifting background — colours + motion still faintly show.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)))
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (song.artworkUrl != null) {
                AsyncImage(
                    model = song.artworkUrl(160),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).artworkCornerClip(with(LocalDensity.current) { 10.dp.toPx() }),
                )
            }
            Column {
                Text(song.title, maxLines = 1, style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                Text(song.artistName, maxLines = 1, style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 14.sp, color = PlayerSecondaryColor))
            }
        }
    }
}

/**
 * Full-screen lyrics view — hides the split layout, shows only the karaoke lyrics with a
 * small now-playing chip. Portrait forces this view; Back keeps the shell's normal behavior.
 */
@Composable
internal fun FullScreenLyrics(
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    song: com.veacon.veilmusic.data.model.Song,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    lyricsScale: Float = 1f,
    chromeVisible: Boolean = true,
    loading: Boolean = false,
    showEmptyLyrics: Boolean = true,
    lyricsHidden: Boolean = false,
    onArtworkClick: () -> Unit = {},
    coverFocusRequester: FocusRequester = remember { FocusRequester() },
    suppressPlayFocus: Boolean = false,
    footerVisible: Boolean = true,
    playFocusRequester: FocusRequester? = null,
    onSongInfoClick: () -> Unit = {},
    songInfoInput: Modifier = Modifier,
    songInfoMenu: @Composable () -> Unit = {},
    hideArtwork: Boolean = false,
    onArtworkPosition: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** 逐字播放开关，透传给 [LyricsPanel]。 */
    wordByWord: Boolean = true,
    /** 歌词弹性开关，透传给 [LyricsPanel]。 */
    elastic: Boolean = false,
    /** 封面马赛克修复开关（见 [com.veacon.veilmusic.util.RenderCompat]）：开启时封面圆角走画布裁剪。 */
    artworkCompat: Boolean = false,
) {
    // Focus lands on the play button (not the top lyric line). Passing it to LyricsPanel
    // as playFocus also makes RIGHT jump here and the 7s idle auto-return work, so the
    // lyrics don't get stranded scrolled-away after you fiddle with the D-pad.
    val playFocus = playFocusRequester ?: remember { FocusRequester() }
    PlayerPlayFocusEffect(playFocus,chromeVisible, suppressInitialFocus = suppressPlayFocus)

    val density = LocalDensity.current
    var landscapeInfoHeight by remember(density) { mutableStateOf(64.dp) }
    var landscapeControlsHeight by remember(density) { mutableStateOf(46.dp) }
    val uiMotion = LocalUiMotion.current
    val footerAlpha by animateFloatAsState(if (footerVisible) 1f else 0f, tween(300), label = "wallFooterContents")
    val ctlAlpha by animateFloatAsState(if (chromeVisible) 1f else 0f, tween(playerMotionDuration(uiMotion.enabled)), label = "fsControls")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val portraitSide = (maxWidth * 0.07f).coerceIn(20.dp, 48.dp)
        val landscapeTitleWidth = maxWidth * 0.42f
        AnimatedVisibility(visible = !lyricsHidden, enter = fadeIn(tween(350)), exit = fadeOut(tween(350))) {
        Column(Modifier.fillMaxSize().padding(
            start = if (portrait) portraitSide else 72.dp,
            end = if (portrait) portraitSide else 128.dp,
            top = if (portrait) 24.dp else 36.dp,
            bottom = 120.dp)) {
            if(!lyricsHidden) com.veacon.veilmusic.ui.components.FontGatedLyrics(com.veacon.veilmusic.ui.theme.LocalAppFontState.current, fontScale = 1.3f * lyricsScale) {
            if (lyrics.isNotEmpty()) LyricsPanel(
                lyrics = lyrics,
                progressState = progressState,
                offsetMs = offsetMs,
                onSeek = onSeek,
                playFocus = playFocus,
                fontScale = 1.3f * lyricsScale,
                autoReturnMs = 5_000L,
                wordByWord = wordByWord,
                elastic = elastic,
            )
            else if (!loading) EmptyLyrics(song.id, 1.3f * lyricsScale, showEmptyLyrics)
            }
        }
        }
        if (footerVisible || footerAlpha > 0f) {
        CompositionLocalProvider(com.veacon.veilmusic.ui.components.LocalMotionInteractive provides
            (com.veacon.veilmusic.ui.components.LocalMotionInteractive.current && footerVisible)) {
        if (portrait) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer { alpha = footerAlpha }.motionInput(footerVisible)
                    .padding(start = portraitSide, end = portraitSide, bottom = portraitSide),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (song.artworkUrl != null) AsyncImage(
                    model = song.artworkUrl(160), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).onGloballyPositioned { onArtworkPosition(it.boundsInRoot()) }
                        // 只在需要遮住封面时才加图层（常驻图层会在个别电视 GPU 上残留缓冲区）。
                        .then(if (hideArtwork) Modifier.graphicsLayer { alpha = 0f } else Modifier)
                        .albumCoverFocus(coverFocusRequester, 10.dp, onArtworkClick)
                        .artworkCorners(10.dp, artworkCompat).testTag("fullscreenArtwork"),
                )
                Box(Modifier.weight(1f)) {
                    SongInfoFocusSurface(chromeAlpha = ctlAlpha, onClick = onSongInfoClick, modifier = songInfoInput, compact = true) {
                        Column {
                            Text(song.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                            Text(song.artistName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 14.sp, color = PlayerSecondaryColor))
                        }
                    }
                    songInfoMenu()
                }
                Row(Modifier.graphicsLayer { alpha = ctlAlpha },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransportButton(TransportIcon.Prev, onClick = onPrev)
                    TransportButton(if (isPlaying) TransportIcon.Pause else TransportIcon.Play, onClick = onPlayPause,
                        loading = loading, active = isPlaying, modifier = Modifier.focusRequester(playFocus))
                    TransportButton(TransportIcon.Next, onClick = onNext)
                }
            }
        } else {
        // Now-playing chip, bottom-left — same placement as the screensaver.
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 48.dp)
                .graphicsLayer { alpha = footerAlpha }.motionInput(footerVisible)
                .onSizeChanged { landscapeInfoHeight = with(density) { it.height.toDp() } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (song.artworkUrl != null) {
                AsyncImage(
                    model = song.artworkUrl(160),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).onGloballyPositioned { onArtworkPosition(it.boundsInRoot()) }
                        // 只在需要遮住封面时才加图层（常驻图层会在个别电视 GPU 上残留缓冲区）。
                        .then(if (hideArtwork) Modifier.graphicsLayer { alpha = 0f } else Modifier)
                        .albumCoverFocus(coverFocusRequester, 10.dp, onArtworkClick)
                        .artworkCorners(10.dp, artworkCompat).testTag("fullscreenArtwork"),
                )
            }
            Box(Modifier.widthIn(max = landscapeTitleWidth)) {
                SongInfoFocusSurface(chromeAlpha = ctlAlpha, onClick = onSongInfoClick, modifier = songInfoInput, compact = true) {
                    Column {
                        Text(song.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                        Text(song.artistName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip, style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 14.sp, color = PlayerSecondaryColor))
                    }
                }
                songInfoMenu()
            }
        }
        // Transport controls, bottom-right (where "Back to exit" used to be). Fades on idle.
        Row(
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 56.dp, bottom = 48.dp + (landscapeInfoHeight - landscapeControlsHeight) / 2)
                .onSizeChanged { landscapeControlsHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { alpha = ctlAlpha * footerAlpha }.motionInput(footerVisible),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportButton(TransportIcon.Prev, onClick = onPrev)
            TransportButton(if (isPlaying) TransportIcon.Pause else TransportIcon.Play, onClick = onPlayPause,
                loading = loading, active = isPlaying, modifier = Modifier.focusRequester(playFocus))
            TransportButton(TransportIcon.Next, onClick = onNext)
        }
        }
        }
    }
}
}

/**
 * Interpolates smooth 60fps playback position between the ~200ms server
 * polling ticks, so word-by-word lyric animation doesn't stutter.
 */
@Composable
private fun rememberSmoothProgressMs(reported: kotlinx.coroutines.flow.Flow<Long>, isPlaying: Boolean): androidx.compose.runtime.State<Long> {
    val anchorRealMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anchorPosMs  = remember { mutableLongStateOf(0L) }
    // Returned as State so callers can defer the read to leaf composables — only the
    // one thing that reads .value (the seek bar, the active word wipe) recomposes per
    // frame, instead of the whole Now Playing tree.
    val smoothMs = remember { mutableLongStateOf(0L) }

    LaunchedEffect(reported) {
        reported.collect { ms ->
            anchorPosMs.longValue  = ms
            anchorRealMs.longValue = System.currentTimeMillis()
            smoothMs.longValue = ms
        }
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        // Resume from the frozen position instead of counting wall time spent paused.
        anchorPosMs.longValue = smoothMs.longValue
        anchorRealMs.longValue = System.currentTimeMillis()
        while (isActive) {
            withFrameMillis {
                smoothMs.longValue = anchorPosMs.longValue + (System.currentTimeMillis() - anchorRealMs.longValue)
            }
        }
    }

    return smoothMs
}

/**
 * Seek bar + time labels. Reads the per-frame progress State in its own scope so the
 * smooth clock recomposes only this small composable, not the whole Now Playing screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerProgressBar(
    progressState: androidx.compose.runtime.State<Long>,
    song: com.veacon.veilmusic.data.model.Song,
    playFocus: FocusRequester,
    player: androidx.media3.common.Player,
    visualScale: Float = 1f,
    loading: Boolean = false,
) {
    val smoothProgressMs = progressState.value
    val duration = song.durationMs.takeIf { it > 0 } ?: 1L
    val progress = (smoothProgressMs.toFloat() / duration).coerceIn(0f, 1f)
    var seekBarFocused by remember { mutableStateOf(false) }
    var scrubMs by remember(song.id) { mutableLongStateOf(smoothProgressMs) }
    // Keep scrub cursor in sync with playback when not focused
    LaunchedEffect(smoothProgressMs, seekBarFocused) {
        if (!seekBarFocused) scrubMs = smoothProgressMs
    }
    val scrubProgress = (scrubMs.toFloat() / duration).coerceIn(0f, 1f)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    if (TvDevice.usesTouchControls(LocalContext.current)) {
        key(song.id) {
            TouchSeekBar(
                progressMs = smoothProgressMs,
                durationMs = song.durationMs,
                onSeek = player::seekTo,
                modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
                trackHeight = 3.dp * visualScale,
                loading = loading,
            )
        }
    } else {
        Surface(
            enabled = !loading,
            onClick = { if (seekBarFocused) { player.seekTo(scrubMs); runCatching { playFocus.requestFocus() } } },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }
                .onFocusChanged { seekBarFocused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionLeft  -> { scrubMs = (scrubMs - 10_000).coerceAtLeast(0); true }
                            Key.DirectionRight -> { scrubMs = (scrubMs + 10_000).coerceAtMost(duration); true }
                            else -> false
                        }
                    } else false
                },
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
                val trackHeight = if (seekBarFocused) 6.dp else 4.dp
                Box(Modifier.fillMaxWidth().height(trackHeight), contentAlignment = Alignment.Center) {
                    val trackModifier = Modifier.fillMaxWidth().height(trackHeight * visualScale).clip(RoundedCornerShape(3.dp))
                    if (loading) com.veacon.veilmusic.ui.components.PlayerLoadingTrack(trackModifier)
                    else Box(modifier = trackModifier.background(Color(0x44FFFFFF))) {
                        Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White))
                        if (seekBarFocused) {
                            Box(modifier = Modifier.fillMaxWidth(scrubProgress).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                        }
                    }
                }
            }
        }
    }
    val timeHeight = referenceTextHeight("−" + formatMs(song.durationMs), TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 11.sp))
    Row(Modifier.fillMaxWidth().heightIn(min = timeHeight), Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (seekBarFocused) formatMs(scrubMs) else formatMs(smoothProgressMs),
            style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 11.sp * visualScale, color = if (seekBarFocused) Color.White else PlayerSecondaryColor)
        )
        Text("−" + formatMs((song.durationMs - smoothProgressMs).coerceAtLeast(0)), style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = 11.sp * visualScale, color = PlayerSecondaryColor))
    }
}

/** Keep the song information readable while its focus backing follows control visibility. */
@OptIn(ExperimentalTvMaterial3Api::class)
/** Cover focus has no overlay; the outline stays outside the image with a 1dp gap. */
@Composable
private fun Modifier.albumCoverFocus(requester: FocusRequester, radius: Dp, onClick: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val source = remember { MutableInteractionSource() }
    return this.focusRequester(requester).onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused && inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard) {
                val gap = 1.dp.toPx(); val stroke = 2.dp.toPx(); val inset = gap + stroke / 2
                drawRoundRect(color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(-inset, -inset),
                    size = androidx.compose.ui.geometry.Size(size.width + inset * 2, size.height + inset * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx() + inset),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
        }.clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
internal fun SongInfoFocusSurface(chromeAlpha: Float, onClick: () -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Surface(onClick = onClick, onLongClick = onClick, onContextMenu = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent, pressedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None)) {
        val bounds = if (compact) Modifier.height(64.dp) else Modifier.fillMaxWidth()
        Box(bounds.background(
            Color.White.copy(alpha = if (focused) (0x1A / 255f) * chromeAlpha.coerceIn(0f, 1f) else 0f), shape)
            .then(if (compact) Modifier.padding(horizontal = 12.dp) else Modifier),
            contentAlignment = if (compact) Alignment.CenterStart else Alignment.TopStart,
            content = content)
    }
}


private fun Color.hsvHue(): Float {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val delta = max - min
    if (delta < 0.001f) return 0f
    val h = when (max) {
        r -> 60f * (((g - b) / delta).mod(6f))
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return if (h < 0f) h + 360f else h
}

private fun hueDist(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b); return minOf(d, 360f - d)
}

/** Make a lighter/darker (and slightly de/re-saturated) shade of [c] — SAME hue. Used to fill orb
 *  slots from an album that only has one colour family, so we never invent a hue (blue/green) the
 *  artwork doesn't contain. step 0 = lighter, 1 = darker, 2 = lighter+, … */
private fun varyShade(c: Color, step: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    val dir = if (step % 2 == 0) 1f else -1f
    val mag = 0.18f + 0.12f * (step / 2)
    hsv[2] = (hsv[2] + dir * mag).coerceIn(0.30f, 0.98f)
    hsv[1] = (hsv[1] * (1f - dir * 0.12f)).coerceIn(0.35f, 1f)   // lighter reads a touch less saturated
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// Pick up to n colors distinct in hue (>= minAngle apart). If the album doesn't HAVE that many
// separated accents, fill the rest with lighter/darker SHADES of the accents we found — so every orb
// colour still comes from the artwork (an orange cover gives orange shades, never a fake blue/green).
private fun spreadByHue(colors: List<Color>, n: Int, minAngle: Float = 20f): List<Color> {
    val result = mutableListOf<Color>()
    val chosen = mutableListOf<Pair<Float, Float>>()   // (hue, value)
    // Keep a swatch if it adds EITHER hue variety OR tonal variety. An album that is one
    // colour family (most are) still yields several real orbs — vibrant / muted / dark /
    // light variants of that family — instead of collapsing to one hue and then padding
    // with computed shades. This is what gives the Apple "oil painting" spread. We only
    // drop a swatch that is near-identical in BOTH hue and brightness to one already kept.
    for (c in colors) {
        val h = c.hsvHue()
        val v = maxOf(c.red, c.green, c.blue)
        if (chosen.all { (ch, cv) -> hueDist(h, ch) >= minAngle || kotlin.math.abs(v - cv) >= 0.16f }) {
            result.add(c); chosen.add(h to v)
            if (result.size == n) break
        }
    }
    if (result.size < n) {
        val src = result.toList().ifEmpty { colors.take(1) }.ifEmpty { listOf(Color(0xFF888888)) }
        var k = 0
        while (result.size < n) {
            result.add(varyShade(src[k % src.size], k / src.size)); k++
        }
    }
    return result
}

// Monochrome equivalent of spreadByHue: separate greys by BRIGHTNESS (hue is meaningless when
// every swatch is grey, so spreadByHue would collapse them all onto the first one).
private fun spreadByValue(colors: List<Color>, n: Int, minGap: Float = 0.13f): List<Color> {
    val result = mutableListOf<Color>()
    val chosen = mutableListOf<Float>()
    for (c in colors) {
        val v = maxOf(c.red, c.green, c.blue)
        if (chosen.all { kotlin.math.abs(v - it) >= minGap }) {
            result.add(c); chosen.add(v)
            if (result.size == n) break
        }
    }
    if (result.size < n) {
        for (c in colors) {
            if (c !in result) { result.add(c); if (result.size == n) break }
        }
    }
    return result
}

// Backdrop vibrancy. Raising SAT_* makes colors read as colors rather than tints;
// VALUE_CEILING is the safety rail that keeps them from turning pale and competing
// with the white lyrics on the right half of the screen. Don't push it past ~0.85.
private const val SAT_BOOST = 1.45f
private const val SAT_FLOOR = 0.55f
private const val VALUE_CEILING = 0.80f

/** Extracts a dark base color + a vibrant accent color from the artwork. */
@Composable
private fun rememberArtworkPalette(artworkUrl: String?, seed: String = ""): List<Color> {
    val context = LocalContext.current
    val fallback = listOf(Color(0xFF0A0A0A), Color(0xFF0D0D0D), Color(0xFF0A0A0A), Color(0xFF111111), Color(0xFF080808), Color(0xFF0D0D0D))
    // A song with NO usable artwork (obscure "internet" library tracks) shouldn't sit on a
    // flat grey backdrop. Derive a stable, muted 6-colour set from a hash of the song id so
    // each one gets its own quiet colour instead — real B&W covers still go grey via the
    // monochrome branch below (they DO produce a bitmap).
    val seeded = remember(seed) {
        if (seed.isEmpty()) fallback else {
            val h = (seed.hashCode() and 0x7FFFFFFF)
            val baseHue = (h % 360).toFloat()
            val hsv = FloatArray(3)
            List(6) { i ->
                hsv[0] = (baseHue + i * 47f) % 360f
                hsv[1] = 0.55f
                hsv[2] = 0.34f + (i % 3) * 0.06f   // deep, below the white-lyric competition line
                Color(android.graphics.Color.HSVToColor(hsv))
            }
        }
    }
    var colors by remember(artworkUrl) { mutableStateOf(seeded) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) { colors = seeded; return@LaunchedEffect }
        try {
            // Decode a SMALL bitmap for the palette — Palette downsamples internally anyway, so a
            // 1200² ARGB bitmap (~5.7 MB, kept in RAM with allowHardware off) was pure waste on a
            // memory-starved Fire TV. 256² is ~256 KB and gives an identical palette. Reuse Coil's
            // shared loader instead of spinning up a new ImageLoader per song.
            val loader = context.applicationContext.let { coil.Coil.imageLoader(it) }
            // Software (RGB_565) bitmap so Palette can always read the pixels — a hardware bitmap
            // (or a null drawable from the previously-disabled cache path) made execute() return
            // nothing for some covers (DIGIDI DIGIDI: real blue/purple art, grey backdrop). Let it
            // reuse Coil's cache too, and retry once, so a single transient miss doesn't fall to grey.
            fun paletteRequest() = ImageRequest.Builder(context).data(artworkUrl).size(256)
                .allowHardware(false).bitmapConfig(android.graphics.Bitmap.Config.RGB_565).build()
            var bitmap = (loader.execute(paletteRequest()).drawable as? BitmapDrawable)?.bitmap
            if (bitmap == null) {
                kotlinx.coroutines.delay(250)
                bitmap = (loader.execute(paletteRequest()).drawable as? BitmapDrawable)?.bitmap
            }
            if (bitmap == null) { colors = seeded; return@LaunchedEffect }   // still nothing → seeded, not grey
            // Finer quantization (32 vs 16) surfaces smaller accent regions — a teal logo, a red
            // jacket — that the 6 named roles miss entirely.
            val p = Palette.from(bitmap).maximumColorCount(32).generate()
            val hsvTmp = FloatArray(3)
            // Use EVERY quantized swatch, not just the 6 named roles, so real accent colours are on
            // the table. Rank by a blend of saturation and prominence so a vivid accent outranks a
            // large dull background — that's what "grab more accent colours" needs.
            val named = listOfNotNull(
                p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
                p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch, p.dominantSwatch,
            )
            val maxPop = (p.swatches.maxOfOrNull { it.population } ?: 1).coerceAtLeast(1)
            val swatches = (named + p.swatches).distinctBy { it.rgb }
                .sortedByDescending { sw ->
                    android.graphics.Color.colorToHSV(sw.rgb, hsvTmp)
                    hsvTmp[1] * 0.62f + (sw.population.toFloat() / maxPop) * 0.38f
                }
            // Detect a grey / black-and-white cover from the ORIGINAL saturations, BEFORE the boost:
            // once every swatch is floored to SAT_FLOOR they all look colourful and the test can't
            // tell. A monochrome sleeve keeps its greys (and can go near-white), separated by
            // brightness instead of hue, so the orbs read as three shades of grey — not invented colour.
            val hsv = FloatArray(3)
            val maxSat = swatches.maxOfOrNull { android.graphics.Color.colorToHSV(it.rgb, hsv); hsv[1] } ?: 0f
            val monochrome = maxSat < 0.18f
            val picked = swatches.map { swatch ->
                android.graphics.Color.colorToHSV(swatch.rgb, hsv)
                if (monochrome) {
                    hsv[1] = 0f                                              // true grey, no invented hue
                    // Keep greys GREY — a near-white orb competes with the white lyrics. Cap well below
                    // white so monochrome art reads as smoky grey pools, not glowing white blobs.
                    hsv[2] = (0.30f + hsv[2] * 0.42f).coerceIn(0.30f, 0.68f)
                } else {
                    // Deep saturated colours read as colour; pastels read as light grey. Floor the
                    // saturation and cap the value so a pale swatch doesn't wash out the lyrics.
                    hsv[1] = (hsv[1] * SAT_BOOST).coerceIn(SAT_FLOOR, 1f)
                    hsv[2] = hsv[2].coerceAtMost(VALUE_CEILING)
                }
                Color(android.graphics.Color.HSVToColor(hsv))
            }.distinct()
            val dom = Color(p.getDominantColor(0xFF050505.toInt()))
            val domLum = 0.2126f * dom.red + 0.7152f * dom.green + 0.0722f * dom.blue

            colors = when {
                domLum < 0.06f && !monochrome -> fallback   // truly black colour art — nothing to extract
                picked.size >= 2 -> if (monochrome) spreadByValue(picked, 6) else spreadByHue(picked, 6)
                else -> {
                    val dark  = Color(dom.red * 0.4f, dom.green * 0.4f, dom.blue * 0.4f)
                    val light = Color((dom.red + 0.3f).coerceAtMost(1f), (dom.green + 0.3f).coerceAtMost(1f), (dom.blue + 0.3f).coerceAtMost(1f))
                    listOf(dom, light, dark, dom, light, dark)
                }
            }
        } catch (_: Exception) {}
    }
    return colors
}

/** Secondary player chrome blends with the fluid backdrop rather than painting opaque grey. */
private val PlayerControlInactiveColor = Color.White.copy(alpha = 0.50f)
private val PlayerSecondaryColor = Color.White.copy(alpha = 0.70f)

/** Idle time (no D-pad navigation) before the Now Playing chrome auto-hides (then a 2 s settle). */

/** D-pad navigation / select keys — the ones allowed to wake the auto-hidden chrome. Media transport
 *  keys are deliberately excluded so play/pause/skip don't bring the controls back. */
private fun isNavKey(key: Key): Boolean =
    key == Key.DirectionUp || key == Key.DirectionDown ||
        key == Key.DirectionLeft || key == Key.DirectionRight ||
        key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** How many lines of already-sung context stay above the active line after a scroll. */
private const val LYRIC_LEAD_LINES = 2
/** Scroll once the active line gets this close to the bottom of the viewport. */
private const val LYRIC_BOTTOM_MARGIN_PX = 40

private const val GAP_THRESHOLD_MS = 4000L
private const val LINE_END_GRACE_MS = 250L
private const val GAP_DOTS_PREROLL_MS = 400L


@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun LyricsPanel(
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    playFocus: FocusRequester? = null,
    fontScale: Float = 1f,
    autoReturnMs: Long = 7000L,
    /** 逐字播放开关：关闭时当前行整句亮起，不做逐字渐变。 */
    wordByWord: Boolean = true,
    /** 歌词弹性开关：逐字命中时弹一下并上浮。 */
    elastic: Boolean = false,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activeLineFocus = remember { FocusRequester() }

    // Structure (which line is active, gaps, auto-scroll) only needs coarse progress —
    // derivedStateOf quantises to 200ms so the list body recomposes ~5x/s, not 60x/s.
    // The active word wipe reads the raw per-frame State itself, staying smooth.
    val progressMs by remember(offsetMs) {
        androidx.compose.runtime.derivedStateOf { ((progressState.value + offsetMs) / 200L) * 200L }
    }
    val liveProgress = { progressState.value + offsetMs }

    val passedIndex = lyrics.indexOfLast { it.startMs <= progressMs }

    // 高亮与滚动统一用实时进度派生（derivedStateOf 只在"切行"那一刻通知重组，不每帧重组）：
    // 之前高亮走 200ms 量化、滚动锚点也慢半拍，导致"滚动已到下一行、上一行却还亮着"的割裂。
    val livePassed by remember(lyrics, offsetMs) {
        derivedStateOf { lyrics.indexOfLast { it.startMs <= progressState.value + offsetMs } }
    }
    // Keep a line lit until its background vocals finish too — the lead line shouldn't
    // dim out while its (call-and-response) background part is still being sung.
    val activeIndex = if (livePassed >= 0) {
        val ln = lyrics[livePassed]
        val activeUntil = maxOf(ln.endMs, ln.background?.endMs ?: Long.MIN_VALUE) + LINE_END_GRACE_MS
        if (progressState.value + offsetMs <= activeUntil) livePassed else -1
    } else -1

    val scrollAnchor = livePassed.coerceAtLeast(0)
    val firstLoad = remember(lyrics) { mutableStateOf(true) }
    // 行切换弹性开关：开启后自动滚动改用 am_atv 的 staggered FLIP 弹性曲线。
    val lyricScrollMotion = remember(elastic) { LyricScrollMotion(elastic = elastic) }

    // Track user-initiated scrolls so auto-scroll doesn't fight them. Auto-scroll
    // resumes the way Apple Music does: not on a timer, but when you bring the
    // current line back on screen — and then only at the next line change, so it
    // rejoins the flow instead of yanking mid-line.
    var userScrolled by remember { mutableStateOf(false) }
    // Also arm the auto-return when the lyric list simply HAS focus (you D-padded LEFT
    // into it) — not only after a scroll. Focus-in alone used to leave the timer disarmed,
    // so it never handed focus back to the play button.
    var listFocused by remember { mutableStateOf(false) }
    // After the lyrics have been focused/centred for a bit, hand focus back to the
    // play button so the D-pad isn't stranded in the lyric list.
    LaunchedEffect(userScrolled, listFocused) {
        if (playFocus == null || (!userScrolled && !listFocused)) return@LaunchedEffect
        kotlinx.coroutines.delay(autoReturnMs)
        if (!listState.isScrollInProgress) { runCatching { playFocus.requestFocus() }; userScrolled = false }
    }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) userScrolled = true
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(scrollAnchor, lyrics) {
        if (lyrics.isEmpty()) return@LaunchedEffect
        val target = (scrollAnchor - LYRIC_LEAD_LINES).coerceAtLeast(0)
        if (firstLoad.value) {
            lyricScrollMotion.reset()
            listState.scrollToItem(target)
            firstLoad.value = false
            return@LaunchedEffect
        }
        if (userScrolled) {
            lyricScrollMotion.reset()
            // Still browsing elsewhere — leave the list alone. Once the active line is
            // visible again this same effect fires on the next line change and takes over.
            val backInView = listState.layoutInfo.visibleItemsInfo.any { it.index == scrollAnchor }
            if (!backInView || listState.isScrollInProgress) return@LaunchedEffect
            userScrolled = false
            lyricScrollMotion.scrollToIndex(listState, target)
            return@LaunchedEffect
        }

        // Centre the active line on every line change (Apple Music behaviour) — the
        // old "only scroll near the bottom" logic is what made it jump every few lines.
        val info = listState.layoutInfo
        val activeItem = info.visibleItemsInfo.firstOrNull { it.index == scrollAnchor }
        if (activeItem == null) {
            // 目标行还没露头：用弹性曲线滚过去，不再用默认 spring 的大跨度瞬跳。
            lyricScrollMotion.scrollToIndex(listState, target)
        } else {
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val itemCentre = activeItem.offset + activeItem.size / 2
            // Sit the active line high — around the album-art midline (~30% down), not centre.
            val delta = (itemCentre - viewportH * 0.30f)
            if (kotlin.math.abs(delta) > 8f) {
                // 当前行已经掉到下半屏：直接落位。回弹动画 750ms，切行间隔短时会追不上，
                // 越追越靠下、最后贴到最下一行（用户实测现象），所以这里给硬锚点。
                if (itemCentre > viewportH * 0.55f) listState.scrollBy(delta)
                else lyricScrollMotion.scrollBy(listState, delta)
            }
        }
    }

    // When navigating back to this screen, scroll to the active line.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!firstLoad.value && lyrics.isNotEmpty()) {
                val target = (passedIndex - 3).coerceAtLeast(0)
                userScrolled = false
                listState.scrollToItem(target)
            }
        }
    }

    // No pinned/sticky line. It was tried for Get Lucky's held phrase, but
    // line-synced sources set endMs to the next line's startMs, so nearly every line
    // matched and one was permanently stuck at the top, fading in and out.
    // Widen only the drawing viewport. End padding preserves the paragraph's measured
    // width while leaving room for focus scaling outside its nominal container.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val focusOverflow = maxWidth * 0.10f
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true)
            .requiredWidth(maxWidth + focusOverflow)
            .align(Alignment.TopStart).padding(end = 16.dp).nestedScroll(nestedScrollConnection)
            .onFocusChanged { listFocused = it.hasFocus }
            // Soft fade the top + bottom edges so lines dissolve in/out instead of popping.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val h = size.height
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        (48f / h) to Color.Black,
                        ((h - 150f) / h).coerceIn(0f, 1f) to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .then(if (playFocus != null) Modifier.focusProperties {
                right = playFocus
                // LEFT from a lyric line always lands on play/pause too — user wants
                // either horizontal press out of the lyrics to go straight to transport.
                left = playFocus
                // Entering the list (e.g. LEFT from the play button) lands on the current
                // line, not whichever line happens to sit nearest the button.
                // Only steer focus to the active line if it is actually laid out right now.
                // Requesting focus on a detached FocusRequester leaves the window with no
                // focused view → input dispatch times out → ANR. Fall back to Default.
                enter = {
                    val activeVisible = activeIndex >= 0 &&
                        listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
                    if (activeVisible) activeLineFocus else androidx.compose.ui.focus.FocusRequester.Default
                }
            } else Modifier)
            .onPreviewKeyEvent { ev: androidx.compose.ui.input.key.KeyEvent ->
                // Block upward D-pad escape to top nav bar from lyrics
                ev.key == Key.DirectionUp && ev.type == KeyEventType.KeyDown &&
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            },
        contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp, end = focusOverflow),
        verticalArrangement = Arrangement.spacedBy(36.dp * (fontScale / 1.25f)),
    ) {
        // Key MUST be unique: two synced lines can share a startMs (a blank + first line both at 0ms
        // is common), and duplicate keys crash LazyColumn on the scroll-to-active remeasure. Fold in
        // the index — the list never reorders within a song, so index keeps keys stable and unique.
        items(lyrics.size, key = { "$it:${lyrics[it].startMs}" }) { idx ->
            val line = lyrics[idx]
            // One line lit at a time. Holding sustained lines lit was tried and removed:
            // line-synced sources set endMs to the next line's startMs, so "still within
            // its window" matched almost everything and the list scrolled and faded
            // wrongly on ordinary songs.
            // Unsynced (plain-text) lyrics come back with startMs = -1. Show them so
            // the panel isn't empty, but never highlighted and never seekable.
            val unsynced = line.startMs < 0
            // Sustained/held line stays lit in-flow while its own window still covers now (Get Lucky).
            val overlapActive = !unsynced && line.words.isNotEmpty() &&
                progressMs >= line.startMs && progressMs <= line.endMs &&
                (idx + 1 >= lyrics.size || line.endMs > lyrics[idx + 1].startMs)
            val isActive = (!unsynced && idx == activeIndex) || overlapActive
            val isPast = !unsynced && (idx < passedIndex || (idx == passedIndex && activeIndex == -1))
            // Only the active line reads the live per-frame clock (so only it recomposes
            // at 60fps). Inactive lines get a fixed value and never re-run on tick.
            val staticLineProgress = if (isPast) line.endMs else line.startMs - 1L
            val progressProvider: () -> Long = if (isActive) liveProgress else ({ staticLineProgress })

            val prevEnd = if (idx > 0) lyrics[idx - 1].endMs else 0L
            val gapMs = line.startMs - prevEnd
            // Mount before the gap so the live-clock fade starts at zero, even though
            // list structure is only checked every 200ms. Removal follows the fade.
            val nearGap = progressMs >= prevEnd - GAP_DOTS_PREROLL_MS && progressMs < line.startMs
            if (gapMs >= GAP_THRESHOLD_MS && nearGap && idx == passedIndex + 1) {
                MusicalDots(
                    progressState = progressState,
                    offsetMs = offsetMs,
                    gapStartMs = prevEnd,
                    gapEndMs = line.startMs,
                    modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                )
            }

            if (unsynced) {
                Text(
                    line.text,
                    style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = (32f * fontScale).sp, lineHeight = (44f * fontScale).sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.40f)),
                    modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                )
            } else {
                LyricLineRow(
                    line = line,
                    isActive = isActive,
                    isPast = isPast,
                    progress = progressProvider,
                    wordByWord = wordByWord,
                    elastic = elastic,
                    fontScale = fontScale,
                    focusRequester = if (isActive) activeLineFocus else null,
                    onSeek = { onSeek(line.startMs) },
                )
            }
        }
    }
    }
}

@Composable
private fun MusicalDots(
    progressState: State<Long>, offsetMs: Long, gapStartMs: Long, gapEndMs: Long,
    modifier: Modifier = Modifier,
) {
    // Only this leaf observes the per-frame clock; the lyric list remains coarse-grained.
    val progressMs = progressState.value + offsetMs
    val dotState = lyricDotsFrame(progressMs, gapStartMs, gapEndMs)
    Row(
        modifier = modifier.graphicsLayer { alpha = dotState.groupAlpha },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (alpha in dotState.dotAlphas) {
            Box(
                Modifier.size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun WordWipeLine(
    text: String,
    spans: List<LyricCharSpan>,
    progressMs: Long,
    live: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 32.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 44.sp,
    weight: FontWeight = FontWeight.Bold,
    sungColor: Color = Color.White,
    unsungColor: Color = Color.White.copy(alpha = 0.40f),
) {
    val activeIdx = spans.indexOfLast { it.startMs <= progressMs }
    val textStyle = TextStyle(
        fontFamily = LocalTextStyle.current.fontFamily,
        fontSize = fontSize, fontWeight = weight, lineHeight = lineHeight,
        color = sungColor, letterSpacing = (-0.25).sp,
    )
    val paragraph = buildAnnotatedString {
        append(text)
        var cursor = 0
        spans.forEachIndexed { index, span ->
            val start = text.indexOf(span.text, startIndex = cursor)
            if (start >= 0) {
                val end = start + span.text.length
                val fraction = when {
                    index < activeIdx -> 1f
                    index == activeIdx && live -> ((progressMs - span.startMs).toFloat() /
                        (span.endMs - span.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    else -> 0f
                }
                addStyle(SpanStyle(color = androidx.compose.ui.graphics.lerp(unsungColor, sungColor, fraction)), start, end)
                cursor = end
            }
        }
    }
    Text(paragraph, style = textStyle, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    progress: () -> Long,
    onSeek: () -> Unit,
    /** 关闭时当前行整句直接亮起（不做逐字渐变），其余表现不变。 */
    wordByWord: Boolean = true,
    /** 行切换弹性：当前行放大、上下行缩小，spring 挤压过渡（Apple Music / AMLL 风格），默认关。 */
    elastic: Boolean = false,
    fontScale: Float = 1f,
    focusRequester: FocusRequester? = null,
    /** 预览等只读场景设为 false：整行不可聚焦，遥控器光标不会停上来。 */
    focusable: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(if (focused) 1.06f else 1f, tween(180), label = "lyricFocusScale")
    // 行切换「挤压」弹性：Apple Music / AMLL 的 enableScale 做法——原位缩放而非位移，
    // 当前行 1.0、上下行 0.97，用 spring（damping 0.88 / stiffness 100）自然减速，无生硬起停。
    val lineScale by animateFloatAsState(
        // 走 am_atv 渲染时行缩放由它内部负责（0.98 ↔ 1.0 + LineScaleEasing）。
        targetValue = if (elastic) 1f else LyricElastic.lineScaleTarget(isActive, false),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = LyricElastic.LINE_SCALE_DAMPING_RATIO,
            stiffness = LyricElastic.LINE_SCALE_STIFFNESS,
        ),
        label = "lyricLineScale",
    )
    // Reading the provider here subscribes only this row: the active row (live clock)
    // recomposes per frame; inactive rows read a constant and never re-run on tick.
    val progressMs = progress()
    // 逐字时间：优先用歌词自带的 words；行级 LRC 没有 words，就按行时长合成。
    val wipeSpans = remember(line) {
        if (line.words.isNotEmpty()) lyricCharSpans(line.words)
        else estimateCharSpans(line.text, line.startMs, line.endMs)
    }
    // 透明度：走 am_atv 渲染时，明暗完全由移植过来的 KaraokeLyricLine 负责
    // （未唱 0.18 / 已唱 0.94，含它自己的渐变时序），这里必须保持不额外压暗，
    // 否则会二次相乘。关闭时保持旧的 1.0 / 0.35 / 0.40。
    val targetOpacity = if (elastic) 1f else LyricElastic.lineAlphaTarget(isActive, isPast, false)
    val opacity by animateFloatAsState(targetOpacity, tween(300), label = "lineOpacity")

    // am_atv 歌词渲染的数据适配：整行文本 + 时间 → 它的 LyricLine。
    // LRC 没有逐词时间时会被标成 Line，走「整行渐变 + 行缩放」分支（不会逐字放大）。
    val karaokeLine = remember(line) { line.toKaraokeLyricLine() }
    // 进度用固定的 State 对象包一层：如果每帧新建实例，移植代码里以它作
    // remember key 的派生状态会被重置，导致渐变反复从头开始。
    val progressHolder = remember { arrayOfNulls<() -> Long>(1) }
    progressHolder[0] = progress
    val positionState = remember { ProgressState { progressHolder[0]?.invoke() ?: 0L } }
    val noRetainedPresence = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = opacity
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            scaleX = focusScale * lineScale
            scaleY = focusScale * lineScale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            clip = false
        }
    ) {
        Surface(
            onClick = onSeek,
            modifier = Modifier.fillMaxWidth()
                .focusProperties { canFocus = focusable }
                .onFocusChanged { focused = it.isFocused }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
            border = ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = Border.None),
        ) {
            if (elastic) {
                // am_atv 原版歌词渲染（整段搬入 ui/lyrics）：
                // 行同步歌词 = 整行 alpha 渐变（0.18 ↔ 0.94）+ 行缩放（0.98 ↔ 1.0，
                // LineScaleEasing 800ms）；逐词歌词才会额外出现遮罩扫过与字符上浮。
                androidx.compose.runtime.CompositionLocalProvider(
                    com.veacon.veilmusic.ui.lyrics.LocalKaraokeTextScale provides fontScale
                ) {
                    com.veacon.veilmusic.ui.lyrics.KaraokeLyricLine(
                        line = karaokeLine,
                        isActive = isActive,
                        retainedPresence = noRetainedPresence,
                        retainedBackgroundPresence = noRetainedPresence,
                        isFocused = focused,
                        isStaticMode = false,
                        positionState = positionState,
                        pronunciationLarger = false,
                    )
                }
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (isActive && wordByWord && wipeSpans.isNotEmpty()) {
                    // 关闭动效：保持升级前的逐字颜色渐变，观感完全不变。
                    WordWipeLine(
                        text = line.text,
                        spans = wipeSpans,
                        progressMs = progressMs,
                        fontSize = (32f * fontScale).sp,
                        lineHeight = (44f * fontScale).sp,
                    )
                } else {
                    // Inactive sentences are pure white; only the animated row alpha dims them.
                    val style = when {
                        isActive -> TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = (32f * fontScale).sp, fontWeight = FontWeight.Bold, lineHeight = (44f * fontScale).sp, letterSpacing = (-0.4).sp, color = Color.White)
                        isPast   -> TextStyle(fontFamily = LocalTextStyle.current.fontFamily, color = Color.White, fontSize = (32f * fontScale).sp, fontWeight = FontWeight.Bold, lineHeight = (44f * fontScale).sp, letterSpacing = (-0.2).sp)
                        else     -> TextStyle(fontFamily = LocalTextStyle.current.fontFamily, color = Color.White, fontSize = (32f * fontScale).sp, fontWeight = FontWeight.Bold, lineHeight = (44f * fontScale).sp, letterSpacing = (-0.2).sp)
                    }
                    Text(text = AnnotatedString(line.text), style = style)
                }

                val bg = line.background
                if (bg != null && bg.text.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    val bgProgress = progressMs + 300L  // -300ms early start
                    val bgLive = bgProgress in bg.startMs..(bg.endMs + 600L)
                    val bgTargetScale = if (bgLive) 1.08f else 0.93f
                    val bgScale by animateFloatAsState(bgTargetScale, tween(375), label = "bgScale")

                    Box(
                        Modifier.graphicsLayer {
                            scaleX = bgScale; scaleY = bgScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                    ) {
                        if (bg.words.isNotEmpty()) {
                            // Same soft left→right wipe as the lead line, just smaller/dimmer.
                            WordWipeLine(
                                text = bg.text, spans = remember(bg.words) { lyricCharSpans(bg.words) },
                                progressMs = bgProgress,
                                live = bgLive,
                                fontSize = 19.sp, lineHeight = 24.sp, weight = FontWeight.SemiBold,
                                sungColor = Color.White,
                                unsungColor = Color.White.copy(alpha = if (isActive) 0.40f else 1f),
                            )
                        } else {
                            Text(
                                text = AnnotatedString(bg.text),
                                style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, 
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = if (!isActive || bgLive) 1f else 0.40f),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueuePanel(
    queue: List<com.veacon.veilmusic.data.model.Song>,
    currentIndex: Int,
    userQueue: List<com.veacon.veilmusic.data.model.Song> = emptyList(),
    onSelect: (Int) -> Unit,
    onSelectUserQueue: (Int) -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    leftFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    var movingIndex by remember { mutableStateOf<Int?>(null) }

    // Always scroll to top (current song) when index changes or userQueue gains items
    LaunchedEffect(currentIndex, userQueue.size) {
        listState.animateScrollToItem(0)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.msg_106), fontSize = 13.sp, color = PlayerSecondaryColor, fontWeight = FontWeight.Medium)
            if (movingIndex != null) Text(tr(R.string.msg_284), fontSize = 10.sp, color = PlayerSecondaryColor)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (userQueue.isNotEmpty()) {
                item {
                    Text(tr(R.string.msg_285), fontSize = 10.sp, color = Color(0xFFFA233B), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                items(userQueue.size, key = { "uq_${userQueue[it].id}_$it" }) { idx ->
                    val song = userQueue[idx]
                    Surface(
                        onClick = { onSelectUserQueue(idx) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0x15FA233B), focusedContainerColor = Color(0x25FA233B)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        modifier = Modifier.fillMaxWidth().let { m ->
                            if (leftFocus != null) m.focusProperties { left = leftFocus } else m
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("◆", fontSize = 9.sp, color = Color(0xFFFA233B), modifier = Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title + if (song.isMusicVideo) " (MV)" else "", fontSize = 13.sp, color = PlayerSecondaryColor, maxLines = 1)
                                Text(song.artistName, fontSize = 11.sp, color = PlayerSecondaryColor, maxLines = 1)
                            }
                            Text(song.durationFormatted, fontSize = 11.sp, color = PlayerSecondaryColor)
                        }
                    }
                }
                item {
                    Text(tr(R.string.msg_286), fontSize = 10.sp, color = PlayerSecondaryColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
            val visibleQueue = queue.drop(currentIndex + 1)
            items(visibleQueue.size, key = { rel -> "${queue.getOrNull(currentIndex + 1 + rel)?.id}_${currentIndex + 1 + rel}" }) { rel ->
                // visibleQueue[rel] == queue[currentIndex + 1 + rel]. The +1 was missing,
                // so tapping a row played the track before it.
                val idx = currentIndex + 1 + rel
                val song = visibleQueue[rel]
                val isCurrent = false
                val isMoving = idx == movingIndex
                val movingMod = if (isMoving) Modifier.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            val target = (movingIndex!! - 1).coerceAtLeast(currentIndex + 1)
                            if (target != movingIndex) { onMove(movingIndex!!, target); movingIndex = target }
                            true
                        }
                        Key.DirectionDown -> {
                            val target = (movingIndex!! + 1).coerceAtMost(queue.lastIndex)
                            if (target != movingIndex) { onMove(movingIndex!!, target); movingIndex = target }
                            true
                        }
                        Key.Enter -> { movingIndex = null; true }
                        else -> false
                    }
                } else Modifier
                Surface(
                    onClick = { if (movingIndex == idx) movingIndex = null else onSelect(idx) },
                    onLongClick = { if (rel > 0) movingIndex = idx },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor        = when { isMoving -> Color(0x44FA233B); isCurrent -> Color(0x26FFFFFF); else -> Color.Transparent },
                        focusedContainerColor = if (isMoving) Color(0x55FA233B) else Color(0x33FFFFFF),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth().then(movingMod).let { m ->
                        if (leftFocus != null) m.focusProperties { left = leftFocus } else m
                    },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (isMoving) "⠿" else "${idx + 1}",
                            fontSize = 11.sp,
                            color = if (isMoving) Color(0xFFFA233B) else if (isCurrent) Color(0xFFFA233B) else PlayerSecondaryColor,
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(song.title + if (song.isMusicVideo) " (MV)" else "", fontSize = 13.sp, color = if (isCurrent || isMoving) Color.White else PlayerSecondaryColor, maxLines = 1, fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal)
                            Text(song.artistName, fontSize = 11.sp, color = PlayerSecondaryColor, maxLines = 1)
                        }
                        Text(song.durationFormatted, fontSize = 11.sp, color = PlayerSecondaryColor)
                    }
                }
            }
        }
    }
}

/** "关闭" / "5 分钟" / "1 小时" / "2 小时" for a screensaver timeout in minutes. */
internal fun screensaverLabel(min: Int): String = when {
    min <= 0    -> tr(R.string.msg_004)
    min % 60 == 0 -> com.veacon.veilmusic.i18n.AppLanguage.quantity(R.plurals.hour_count, min / 60)
    else        -> com.veacon.veilmusic.i18n.AppLanguage.quantity(R.plurals.minute_count, min)
}

internal enum class TransportIcon { Play, Pause, Prev, Next, Panel }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TransportButton(
    icon: TransportIcon,
    onClick: () -> Unit,
    large: Boolean = false,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    visualScale: Float = 1f,
    inactiveColor: Color = Color.White,
    active: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val btnSize = if (large) 58.dp else 46.dp
    // SF Symbols sit inside an optical bounding box (~30% padding), so they render smaller than the
    // old edge-to-edge Canvas glyphs at the same size — bump to compensate and read as Apple-sized.
    // forward.fill/backward.fill pad even more than play.fill, so the non-large size is generous.
    val canvasSize = (if (large) 34.dp else 26.dp) * visualScale
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor        = Color.Transparent,
            focusedContainerColor = Color(0x22FFFFFF),
        ),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.10f),
        glow   = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
        modifier = modifier.size(btnSize).semantics {
            contentDescription = when (icon) {
                TransportIcon.Play -> tr(R.string.msg_001)
                TransportIcon.Pause -> tr(R.string.msg_000)
                TransportIcon.Prev -> tr(R.string.msg_289)
                TransportIcon.Next -> tr(R.string.msg_283)
                TransportIcon.Panel -> tr(R.string.msg_290)
            }
        },
        interactionSource = interactions,
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            val breath = if (loading) {
                val transition = rememberInfiniteTransition(label = "loadingBreath")
                val alpha by transition.animateFloat(
                    initialValue = 0.30f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(850,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing), AnimRepeatMode.Reverse),
                    label = "loadingButtonAlpha",
                )
                alpha
            } else 1f
            val ink = if (active || pressed) Color.White else inactiveColor
            val color = ink.copy(alpha = ink.alpha * breath)
            // SF Symbol transport glyphs (same set as the rest of the app).
            val glyph = when (icon) {
                TransportIcon.Play  -> com.veacon.veilmusic.ui.components.Glyph.PLAY
                TransportIcon.Pause -> com.veacon.veilmusic.ui.components.Glyph.PAUSE
                TransportIcon.Prev  -> com.veacon.veilmusic.ui.components.Glyph.PREV
                TransportIcon.Next  -> com.veacon.veilmusic.ui.components.Glyph.NEXT
                TransportIcon.Panel -> com.veacon.veilmusic.ui.components.Glyph.QUEUE
            }
            com.veacon.veilmusic.ui.components.Icon(glyph, size = canvasSize, color = color)
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NpMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    icon: com.veacon.veilmusic.ui.components.Glyph? = null,
    iconAlpha: Float = 1f,
    checked: Boolean = false,
    /** V84 上游：遥控器打开菜单时，焦点建立前的白底视觉选中。 */
    selectedByRemote: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink = if (enabled) Color(0xFF1D1D1F) else Color(0x611D1D1F)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = Color(0xFFECECEF), pressedContainerColor = Color(0xFFE2E2E6),
            disabledContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f,
            disabledScale = 1f, focusedDisabledScale = 1f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder,
            pressedBorder = noBorder, disabledBorder = noBorder, focusedDisabledBorder = noBorder),
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selectedByRemote) Color(0xFFECECEF) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                    com.veacon.veilmusic.ui.components.Icon(icon, size = 16.dp, color = ink.copy(alpha = ink.alpha * iconAlpha))
                }
            }
            Text(label, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
            if (checked) com.veacon.veilmusic.ui.components.Icon(
                com.veacon.veilmusic.ui.components.Glyph.CHECK, size = 14.dp, color = Color(0xFFFA233B))
        }
    }
}

@Composable
private fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    /** Tracking is size-specific: large text reads too loose at 0, so tighten it. */
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    val scrollState = rememberScrollState()
    // 溢出测量按「文本 + 字号」记：同曲换字号档位（设置 → 歌曲信息字号）后要重新测量。
    var overflows by remember(text, fontSize) { mutableStateOf(false) }
    var measured  by remember(text, fontSize) { mutableStateOf(false) }
    // Ping-pong: glide left→right to reveal the tail, pause, then glide back right→left — smooth
    // both ways, no fade-and-jump. Ease-in-out easing softens each turnaround.
    LaunchedEffect(text, overflows) {
        if (!overflows) return@LaunchedEffect
        val ease = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
        scrollState.scrollTo(0)
        while (true) {
            kotlinx.coroutines.delay(1400)
            scrollState.animateScrollTo(scrollState.maxValue, androidx.compose.animation.core.tween(6000, easing = ease))
            kotlinx.coroutines.delay(1400)
            scrollState.animateScrollTo(0, androidx.compose.animation.core.tween(6000, easing = ease))
        }
    }
    Box(
        modifier = modifier.clipToBounds().then(
            // When the text is scrolling, dissolve it at BOTH edges instead of hard-cutting.
            if (overflows) Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    val edge = 22f
                    // Only fade the LEFT edge once the text has actually scrolled off it —
                    // at rest (start of the title) the first letters must be fully solid.
                    val leftFade = if (scrollState.value > 2) Color.Transparent else Color.Black
                    val rightFade = if (scrollState.value < scrollState.maxValue - 2) Color.Transparent else Color.Black
                    drawRect(
                        Brush.horizontalGradient(
                            0f to leftFade,
                            (edge / w).coerceIn(0f, 0.5f) to Color.Black,
                            (1f - edge / w).coerceIn(0.5f, 1f) to Color.Black,
                            1f to rightFade,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = LocalTextStyle.current.fontFamily, fontSize = fontSize, fontWeight = fontWeight, color = color,
                letterSpacing = letterSpacing),
            maxLines = 1, softWrap = false,
            textAlign = if (overflows) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
            overflow = if (overflows) androidx.compose.ui.text.style.TextOverflow.Visible else androidx.compose.ui.text.style.TextOverflow.Clip,
            modifier = if (overflows)
                Modifier.horizontalScroll(scrollState, enabled = false)
            else
                Modifier.fillMaxWidth(),
            onTextLayout = { result ->
                if (!measured) { measured = true; overflows = result.didOverflowWidth }
            },
        )
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
