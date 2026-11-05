package com.veil.music.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.cos
import kotlin.math.sin
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.palette.graphics.Palette
import androidx.tv.material3.*
import androidx.tv.material3.Border
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.veil.music.data.model.LyricLine
import com.veil.music.data.model.LyricWord
import com.veil.music.ui.navigation.LocalNavBarHasFocus
import com.veil.music.ui.viewmodel.FsStyle
import com.veil.music.ui.viewmodel.FullscreenLyricMode
import com.veil.music.ui.viewmodel.LyricFontChoice
import com.veil.music.ui.player.fullscreen.InkScrollFx
import com.veil.music.ui.player.fullscreen.TrackBedFx
import com.veil.music.ui.theme.MingMeiSpring
import com.veil.music.ui.theme.PingFang
import com.veil.music.ui.viewmodel.NavigationViewModel
import com.veil.music.ui.viewmodel.NowPlayingBackground
import com.veil.music.ui.viewmodel.PlayerViewModel
import com.veil.music.ui.components.Glyph
import com.veil.music.ui.viewmodel.RepeatMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** DIAGNOSTIC: off = don't spin up the motion-artwork video decoder (CPU test). */
private const val MOTION_ENABLED = true

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerVm: PlayerViewModel,
    navVm: NavigationViewModel,
    /**
     * 播放/暂停键的 requester。
     *
     * 由 AppShell 持有并绑到顶栏「正在播放」胶囊的上下键上：从顶栏按上键直接落到这里，
     * 而不是按几何位置掉进右侧播放队列。默认自己造一个，供单独预览时使用。
     */
    playFocus: FocusRequester = remember { FocusRequester() },
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by playerVm.state.collectAsState()
    val song = state.currentSong

    val toggleCount by navVm.toggleQueue.collectAsState()
    val showQueue = toggleCount % 2 == 1

    // While a track is still being fetched/decrypted (isLoading), freeze the clock: the seek bar
    // and lyrics must not advance before there's actually audio. Resumes when playback really starts.
    val smoothProgress = rememberSmoothProgressMs(state.progressMs, state.isPlaying && !state.isLoading)

    DisposableEffect(Unit) {
        playerVm.nowPlayingVisible = true
        onDispose { playerVm.nowPlayingVisible = false }
    }

    val artistFocusHolder = remember { FocusRequester() }
    // 控件行末位「⋯」的 requester：右侧的队列列表在几何上够不着，得靠它点名。

    Box(modifier = modifier.fillMaxSize()) {
        // Screensaver state is declared here (not lower down) so the backdrop can react to it: when
        // idle mode kicks in and the user hasn't opted to keep the beat, the backdrop drops to plain
        // black regardless of the chosen mode.
        var screensaverOn by remember { mutableStateOf(false) }
        var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        // Auto-hide chrome (clock, controls, queue) after a short idle so a steady state is just
        // artwork + orbs. Only D-pad NAVIGATION brings it back — the media transport keys deliberately
        // don't, so play/pause/skip from across the room leaves the clean view alone.
        var chromeVisible by remember { mutableStateOf(true) }
        var lastNavMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(chromeVisible, lastNavMs) {
            if (!chromeVisible) return@LaunchedEffect
            while (isActive) {
                kotlinx.coroutines.delay(500)
                if (System.currentTimeMillis() - lastNavMs > CHROME_HIDE_MS) { chromeVisible = false; break }
            }
        }
        // Asymmetric transition: dropping INTO idle is a slow ~1.6 s eased settle (chrome fades, art
        // eases into place) so it reads as one deliberate move; coming BACK is a fast ~240 ms punch so
        // the controls snap to attention the instant you touch the D-pad — no waiting for a fade.
        val idle by animateFloatAsState(
            if (chromeVisible) 0f else 1f,
            animationSpec = if (chromeVisible)
                tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            else
                tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "idle",
        )
        val chromeAlpha = 1f - idle
        val backgroundMode = if (screensaverOn && !state.screensaverKeepBackground)
            NowPlayingBackground.BLACK else state.nowPlayingBackground
        // 专辑取色只算一次：背景的水光、当前歌词的字色都从这里拿，避免同一张封面被解码两遍。
        val paletteUrl = song?.artworkUrl?.replace("{w}", "300")?.replace("{h}", "300")?.replace("{f}", "jpg")
        val albumPalette = rememberArtworkPalette(paletteUrl, seed = song?.id ?: "")
        val lyricAccent = remember(albumPalette) { lyricAccentOf(albumPalette) }
        DynamicBackground(artworkUrlTemplate = song?.artworkUrl, songKey = song?.id ?: "", beatAnalyzer = playerVm.beatAnalyzer, beatMultiplier = state.beatIntensity, mode = backgroundMode, playing = state.isPlaying, orbSpeed = state.orbSpeed, reduceMotion = state.reduceMotion, lowPower = state.lowPowerMode, palette = albumPalette)

        if (song == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("暂无播放", color = Color(0xFF666666), fontSize = 18.sp)
            }
            return@Box
        }

        // System clock — top-left
        var clockText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
            while (isActive) {
                clockText = LocalDateTime.now().format(fmt)
                kotlinx.coroutines.delay(1_000)
            }
        }
        val sleepLabel = when {
            state.sleepAfterSong -> "睡眠定时：播完当前歌曲"
            state.sleepTimerEndsAt != null -> {
                val remaining = (state.sleepTimerEndsAt!! - System.currentTimeMillis()).coerceAtLeast(0)
                val mins = remaining / 60_000
                val secs = (remaining % 60_000) / 1_000
                "睡眠定时 ${mins}:${secs.toString().padStart(2, '0')}"
            }
            else -> null
        }
        if (state.showNowPlayingInfo) Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 66.dp)
                .graphicsLayer { alpha = chromeAlpha },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 当前这首实际拿到的音质 + 容器格式（如「无损 FLAC · FLAC」「高品 320k · MP3」）。
            // 只在这一批流解析完之后才有值；解析中/本地文件没有就不显示。
            // 注意它和时钟在同一个 Row、同一个 chromeAlpha 里：控件淡出时一起消失，不会留孤零零一个角标。
            val qualityText = state.qualityLabel?.let { q ->
                val f = state.qualityFormat
                if (f.isNullOrBlank() || q.contains(f, ignoreCase = true)) q else "$q · $f"
            }
            if (qualityText != null) {
                Text(
                    qualityText,
                    style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)),
                )
            }
            if (sleepLabel != null) {
                Text(sleepLabel, style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
            }
            Text(clockText, style = TextStyle(fontSize = 15.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
        }

        // 顶栏是否持焦：遥控器在顶栏上用左右键切页时会切到这一页，此时绝不能再把焦点
        // 抓到播放按钮上 —— 否则焦点离开顶栏，右边的「设置」就永远按不到了。
        val navBarHasFocus = LocalNavBarHasFocus.current
        val navBarFocus = com.veil.music.ui.navigation.LocalNavBarFocus.current
        // 「上键回顶栏」的出口。顶栏的 requester 绑在当前 tab 的胶囊上；万一它此刻没有节点
        // （顶栏正在淡出/重组），requestFocus 会抛异常 —— 返回 false 让事件继续走默认几何搜索。
        val upToNavBar: () -> Boolean = {
            val req = navBarFocus
            if (req == null) false else runCatching { req.requestFocus() }.isSuccess
        }
        // 播放队列第一行。这块布局的几何焦点搜索到不了右侧列表，由「列表」键的右键点名进入。
        val queueEntryFocus = remember { FocusRequester() }
        // When the chrome hides for idle, park focus back on the play/pause button (after the fade), so
        // the moment you wake it the centre control is the highlighted one — not wherever you'd left it.
        LaunchedEffect(chromeVisible) {
            if (!chromeVisible) {
                kotlinx.coroutines.delay(1700)
                if (!chromeVisible && !navBarHasFocus.value) runCatching { playFocus.requestFocus() }
            }
        }
        var fullScreenLyrics by remember { mutableStateOf(false) }
        // 墨卷 / 声轨：全屏沉浸歌词，不含任何播放控件。null = 不进入。
        var fullscreenFx by remember { mutableStateOf<FullscreenLyricMode?>(null) }
        /**
         * 退出时要淡出的那一个「是哪一种」。
         *
         * Crossfade 在退场动画里仍会重组 targetState 那一分支的内容，而此时 [fullscreenFx]
         * 已经被置成 null —— 分支里 `fullscreenFx ?: INK_SCROLL` 就会在最后 450ms 把声轨
         * 闪成「墨卷」，取参数时 `FsStyle.of(null)` 又回落到 PLAIN 的默认字号，看起来就是
         * 「返回时跳到墨卷默认字体版」。所以进入时把模式记在这里，退出时不清它。
         */
        var fxMode by remember { mutableStateOf(FullscreenLyricMode.INK_SCROLL) }
        // System Back exits full-screen lyrics (the 3-dots menu isn't reachable there).
        androidx.activity.compose.BackHandler(enabled = fullScreenLyrics || fullscreenFx != null) {
            fullScreenLyrics = false
            fullscreenFx = null
        }

        // Ambient screensaver: after 10 min of no input while playing, drop to just the
        // drifting background + a small now-playing chip. Any key wakes it.
        // (screensaverOn / lastInteractionMs are declared above so the backdrop can read them.)
        LaunchedEffect(state.screensaverTimeoutMin, state.isPlaying) {
            if (state.screensaverTimeoutMin <= 0) { screensaverOn = false; return@LaunchedEffect }
            val thresholdMs = state.screensaverTimeoutMin * 60_000L
            while (isActive) {
                kotlinx.coroutines.delay(15_000)
                if (state.isPlaying && !screensaverOn &&
                    System.currentTimeMillis() - lastInteractionMs > thresholdMs) screensaverOn = true
            }
        }

        // Cross-dissolve between the player, full-screen lyrics and the screensaver so every
        // switch (and back) animates instead of hard-cutting.
        val npMode = when {
            screensaverOn -> 2
            fullscreenFx != null && state.lyrics.isNotEmpty() -> 3
            fullScreenLyrics && state.lyrics.isNotEmpty() -> 1
            else -> 0
        }
        androidx.compose.animation.Crossfade(
            targetState = npMode,
            animationSpec = tween(450),
            modifier = Modifier.fillMaxSize(),
            label = "npMode",
        ) { mode -> when (mode) {
            2 -> AmbientScreensaver(song = song) { screensaverOn = false; lastInteractionMs = System.currentTimeMillis() }
            3 -> {
                // 墨卷 / 声轨各用自己的那套排版（设置 → 全屏歌词 里分开调）。
                // 用 fxMode 而不是 fullscreenFx：退场动画期间后者已经是 null 了。
                val fxPrefs = state.fsPrefs(FsStyle.of(fxMode))
                FullscreenLyricStage(
                    mode = fxMode,
                    lyrics = state.lyrics,
                    progressState = smoothProgress,
                    offsetMs = state.avLyricsMs,
                    song = song,
                    fontChoice = fxPrefs.font,
                    fontSizeSp = fxPrefs.fontSp,
                    lineGapPct = fxPrefs.lineGapPct,
                    tracking = fxPrefs.letterSpacing,
                    light = fxPrefs.light,
                    onExit = { fullscreenFx = null },
                )
            }
            1 -> {
                // 普通全屏歌词也有自己那套参数：字号 → 相对 24sp 的缩放，行距 → 倍数。
                val plain = state.fsPrefs(FsStyle.PLAIN)
                FullScreenLyrics(
                    lyrics = state.lyrics,
                    progressState = smoothProgress,
                    offsetMs = state.avLyricsMs,
                    onSeek = { ms -> playerVm.player.seekTo(ms) },
                    onExit = { fullScreenLyrics = false },
                    fontScale = plain.fontSp / 24f,
                    lineGap = plain.lineGapPct / 100f,
                    letterSpacing = plain.letterSpacing,
                    fontChoice = plain.font,
                    lyricAccent = lyricAccent,
                )
            }
            else -> {
        Row(
            // 顶部 96dp = 原来的「外壳顶栏 56dp + 本页 40dp」。2.2.2 起本页背景铺满全窗，
            // 外壳不再为播放页让位，所以这 56dp 挪到这里，视觉位置与之前完全一致。
            modifier = Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 96.dp, bottom = 40.dp)
                .onPreviewKeyEvent { e ->
                    // Navigation only: media keys don't count as activity, so they neither wake the
                    // chrome nor postpone the screensaver.
                    if (e.type == KeyEventType.KeyDown && isNavKey(e.key)) {
                        val now = System.currentTimeMillis()
                        lastInteractionMs = now
                        lastNavMs = now
                        // First nav press just reveals the chrome — consume it so focus doesn't also
                        // jump while everything's still invisible.
                        if (!chromeVisible) { chromeVisible = true; return@onPreviewKeyEvent true }
                    }
                    false
                },
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            // Left — artwork + info + controls, grouped and vertically centred so idle (controls
            // hidden) doesn't leave an awkward void. Eases up slightly as it settles into idle.
            Column(
                modifier = Modifier.width(340.dp).fillMaxHeight().padding(vertical = 8.dp)
                    .graphicsLayer {
                        // On idle the controls + progress bar fade but still hold their layout space,
                        // which leaves the art sitting high. Ease the whole group DOWN a touch (and a
                        // hair larger) so the artwork + title + artist settle into the visual centre.
                        val s = 1f + 0.03f * idle
                        scaleX = s; scaleY = s
                        translationY = idle * 58f
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Live radio: broadcast-style LIVE badge ABOVE the album art.
                if (state.isLiveRadio) {
                    Row(
                        modifier = Modifier.padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFA233B))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                        Text("直播", color = Color.White, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(256.dp)
                        .clip(RoundedCornerShape(if (state.artworkRounded) 18.dp else 0.dp))
                        .background(Color(0xFF1A1A2E)),
                ) {
                    // Cross-fade the cover instead of hard-swapping it on song change. Live radio
                    // paused → show the station's own cover (there's no "current track" while paused).
                    val coverUrl = if (state.isLiveRadio && !state.isPlaying && state.radioStationArt != null)
                        state.radioStationArt else song.artworkUrl(600)
                    if (coverUrl != null) {
                        androidx.compose.animation.Crossfade(
                            targetState = coverUrl,
                            animationSpec = tween(1000),
                            label = "cover",
                        ) { url ->
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(url).crossfade(1000).build(),
                                contentDescription = song.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    // DIAGNOSTIC: motion artwork spins up a SECOND ExoPlayer/video
                    // decoder. Gated off to test whether it starves the audio decoder
                    // (standalone frame-drop chop) on the weak Fire TV.
                    // In PiP, drop the motion decoder entirely: it's a whole second ExoPlayer/video
                    // decoder, and holding it alive through the PiP transition is a memory spike that
                    // the Fire TV's low-memory killer answers by killing us. onDispose releases it.
                    @Suppress("ConstantConditionIf")
                    if (MOTION_ENABLED && state.motionUrl != null && !state.isInPip && !state.lowPowerMode) {
                        MotionCover(url = state.motionUrl!!, modifier = Modifier.fillMaxSize())
                    }
                }

                Spacer(Modifier.height(10.dp))

                var showOptionsMenu by remember { mutableStateOf(false) }
                var showSleepSubmenu by remember { mutableStateOf(false) }
                var showAddTo by remember { mutableStateOf(false) }
                if (showAddTo) com.veil.music.ui.components.AddToDialog(playerVm, song, onDismiss = { showAddTo = false })

                MarqueeText(
                    song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 38.dp),
                )
                // No gap between title and artist — a fixed spacer here read as an abrupt
                // collapse against the idle chrome fade. Artist sits directly under the title.
                if (song.artistId != null) {
                    Surface(
                        onClick = { onArtistClick(song.artistId) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x1AFFFFFF)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        modifier = Modifier.focusRequester(artistFocusHolder).fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        MarqueeText(song.artistName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFA233B),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    }
                } else {
                    MarqueeText(song.artistName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFA233B),
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))

                LaunchedEffect(song.id) {
                    // 从顶栏切进来时焦点留在顶栏，不抢。
                    // 先取一次快照：换歌瞬间队列会整段重排，正在聚焦的那一行会被销毁，系统随后
                    // 会把焦点"找回"成顶栏第一个胶囊。等两帧（重排落地）再把焦点拉回播放键，
                    // 用户点队列里的歌才会**留在这个界面**继续播，而不是被打回别的页面。
                    val navHadFocus = navBarHasFocus.value
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    if (!navHadFocus) runCatching { playFocus.requestFocus() }
                }
                val optionsFocus = remember { FocusRequester() }
                // 没有 MENU 键的设备（绝大多数 Android TV）才需要屏幕上的「列表」键；
                // 有没有它决定了「⋯」左边是谁，右键链要按这个接。
                val showPanelButton = com.veil.music.util.TvDevice
                    .needsOnScreenMenuToggle(LocalContext.current, playerVm.remoteOverride())
                val toOptions: () -> Boolean = { runCatching { optionsFocus.requestFocus() }.isSuccess }
                Row(
                    modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
                    // 16dp 而不是 30dp：行末多了「⋯」，5 个控件要挤进 340dp 的左侧栏。
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 上键统一回顶栏（顶栏胶囊在屏幕正上方居中，与左侧这一列横向并不重叠，
                    // 只靠几何搜索会卡在控件列里 —— 显式给一条通道，见 TransportButton.onUp）。
                    TransportButton(TransportIcon.Prev, onClick = playerVm::prev,
                        onUp = upToNavBar,
                        modifier = Modifier.focusProperties { left = artistFocusHolder })
                    TransportButton(
                        if (state.isPlaying) TransportIcon.Pause else TransportIcon.Play,
                        loading = state.isLoading,
                        onClick = playerVm::togglePlayPause,
                        large = true,
                        onUp = upToNavBar,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    TransportButton(
                        TransportIcon.Next, onClick = playerVm::next, onUp = upToNavBar,
                        onRight = if (showPanelButton) null else toOptions,
                    )
                    // Google TV and most non-Fire remotes have no Menu key, so the
                    // queue/lyrics toggle needs a control of its own. Fire TV keeps
                    // using Menu and doesn't need the extra button.
                    if (showPanelButton) {
                        // 右键进播放队列：几何搜索在这块布局上找不到右侧的列表，
                        // 不给条通道的话队列就只能看不能选歌。
                        TransportButton(TransportIcon.Panel, onClick = navVm::toggleQueuePanel,
                            onUp = upToNavBar,
                            onRight = toOptions)
                    }
                    // 「⋯」原来挂在标题右侧，但标题行本来就不在焦点链上（从播放键按上键直接回顶栏），
                    // 遥控器没有任何路径能走到那个坐标 —— 菜单只能靠触摸打开。挪到控件行末位后，
                    // 左右键一路走到底就能选中它，和 Apple TV 播放器把「更多」放在控件行是一致的。
                    OptionsButton(
                        onClick = { showOptionsMenu = true; showSleepSubmenu = false },
                        onUp = upToNavBar,
                        onRight = { runCatching { queueEntryFocus.requestFocus() }.isSuccess },
                        modifier = Modifier.focusRequester(optionsFocus),
                    )
                }

                // ⋯ options dialog
                if (showOptionsMenu) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showOptionsMenu = false; showSleepSubmenu = false }) {
                        val menuFocus = remember { FocusRequester() }
                        LaunchedEffect(showSleepSubmenu) { kotlinx.coroutines.delay(100); runCatching { menuFocus.requestFocus() } }
                        Column(
                            Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (showSleepSubmenu) {
                                NpMenuItem("返回", Modifier.focusRequester(menuFocus), icon = Glyph.BACK) { showSleepSubmenu = false }
                                if (state.sleepTimerEndsAt != null || state.sleepAfterSong)
                                    NpMenuItem("取消定时") { playerVm.cancelSleepTimer(); showOptionsMenu = false }
                                NpMenuItem("播完当前歌曲", checked = state.sleepAfterSong) { playerVm.setSleepAfterSong(); showOptionsMenu = false }
                                listOf(15, 30, 45, 60).forEach { min ->
                                    NpMenuItem("$min 分钟后停止") { playerVm.setSleepTimer(min); showOptionsMenu = false }
                                }
                            } else {
                                val timerLabel = when {
                                    state.sleepAfterSong -> "睡眠定时：播完当前歌曲"
                                    state.sleepTimerEndsAt != null -> {
                                        val m = ((state.sleepTimerEndsAt!! - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                                        "睡眠定时（剩 ${m} 分钟）"
                                    }
                                    else -> "睡眠定时"
                                }
                                val timerOn = state.sleepAfterSong || state.sleepTimerEndsAt != null
                                NpMenuItem(timerLabel, Modifier.focusRequester(menuFocus), icon = Glyph.MOON, checked = timerOn) { showSleepSubmenu = true }
                                // Crossfade + Beat Pulse live in Settings now — keep this menu short.
                                // Settings items leave the menu open so you can see the label
                                // flip and keep cycling. Only navigation and the sleep timer
                                // close it; Back dismisses.
                                NpMenuItem("随机播放", icon = Glyph.SHUFFLE, checked = state.isShuffled) { playerVm.toggleShuffle() }
                                val repeatLabel = when (state.repeatMode) { RepeatMode.Off -> "循环：关"; RepeatMode.All -> "循环：列表"; RepeatMode.One -> "循环：单曲" }
                                NpMenuItem(repeatLabel, icon = if (state.repeatMode == RepeatMode.One) Glyph.REPEAT_ONE else Glyph.REPEAT, checked = state.repeatMode != RepeatMode.Off) { playerVm.toggleRepeat() }
                                if (state.lyrics.isNotEmpty()) NpMenuItem("全屏歌词", icon = Glyph.LYRICS) { fullScreenLyrics = true; showOptionsMenu = false }
                                if (state.lyrics.isNotEmpty()) NpMenuItem("墨卷（全屏）", icon = Glyph.LYRICS) {
                                    fxMode = FullscreenLyricMode.INK_SCROLL
                                    fullscreenFx = FullscreenLyricMode.INK_SCROLL; showOptionsMenu = false
                                }
                                if (state.lyrics.isNotEmpty()) NpMenuItem("声轨（全屏）", icon = Glyph.MUSIC_NOTE) {
                                    fxMode = FullscreenLyricMode.TRACK_BED
                                    fullscreenFx = FullscreenLyricMode.TRACK_BED; showOptionsMenu = false
                                }
                                NpMenuItem("添加到…", icon = Glyph.ADD_TO) { showAddTo = true; showOptionsMenu = false }
                                NpMenuItem("开始屏保", icon = Glyph.STAR) { lastInteractionMs = System.currentTimeMillis(); screensaverOn = true; showOptionsMenu = false }
                                if (song.artistId != null) NpMenuItem("查看歌手", icon = Glyph.ARTIST) { onArtistClick(song.artistId); showOptionsMenu = false }
                                if (song.albumId != null) NpMenuItem("查看专辑", icon = Glyph.ALBUM) { onAlbumClick(song.albumId); showOptionsMenu = false }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Column, not Box: PlayerProgressBar emits the bar AND a time row, which a Box would
                // overlap (the elapsed/duration text clipped over the bar).
                Column(Modifier.graphicsLayer { alpha = chromeAlpha }) {
                    PlayerProgressBar(
                        progressState = smoothProgress,
                        song = song,
                        playFocus = playFocus,
                        onUp = upToNavBar,
                        player = playerVm.player,
                    )
                }
            }

            // Right — lyrics or queue. Lyrics are content, not chrome, so they stay put on idle;
            // only the queue fades out with the rest of the controls.
            val rightIsLyrics = !showQueue && state.lyrics.isNotEmpty()
            // The "Lyrics • Menu = Queue" hint is a teaching aid, not permanent chrome. Show it only
            // while you're actually working with the panel (it has focus) — and only if the Now Playing
            // info setting is on — then fade it away. It still reserves its row so nothing jumps.
            var rightFocused by remember { mutableStateOf(false) }
            val hintAlpha by animateFloatAsState(
                if (state.showNowPlayingInfo && rightFocused) 1f else 0f, tween(250), label = "panelHint")
            Column(modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = if (rightIsLyrics) 1f else chromeAlpha }) {
                val label = when {
                    state.isLiveRadio -> ""       // live radio has no queue/lyrics panel
                    showQueue -> "播放队列  •  菜单键切歌词"
                    state.lyrics.isNotEmpty() -> "歌词  •  菜单键切队列"
                    else -> "播放队列"
                }
                Text(
                    label,
                    fontSize = 10.sp,
                    color = Color(0x99FFFFFF),
                    modifier = Modifier.align(Alignment.End).padding(bottom = 6.dp).graphicsLayer { alpha = hintAlpha },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().fillMaxHeight().onFocusChanged { rightFocused = it.hasFocus }) {
                    if (state.isLiveRadio) {
                        // Live radio: no Up Next (single continuous stream) and no lyrics (can't sync
                        // per-track reliably) — the panel is intentionally empty.
                    } else if (showQueue) {
                        QueuePanel(
                            queue = state.queue,
                            currentIndex = state.queueIndex,
                            userQueue = state.userQueue,
                            onSelect = { idx -> playerVm.playFromQueue(idx) },
                            onSelectUserQueue = { idx -> playerVm.playFromUserQueue(idx) },
                            onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                            leftFocus = playFocus,
                            firstRowFocus = queueEntryFocus,
                        )
                    } else if (state.lyrics.isNotEmpty()) {
                        LyricsPanel(
                            lyrics = state.lyrics,
                            progressState = smoothProgress,
                            offsetMs = state.avLyricsMs,
                            onSeek = { ms -> playerVm.player.seekTo(ms) },
                            playFocus = playFocus,
                            fontScale = state.lyricsScale,
                            lineGap = state.lyricLineGap,
                            // 字间距（em×100）：设置 →「播放与外观」→ 歌词 里调，范围 -1.00–1.00em。
                            tracking = state.lyricTracking,
                            accent = lyricAccent,
                        )
                    } else {
                        QueuePanel(
                            queue = state.queue,
                            currentIndex = state.queueIndex,
                            userQueue = state.userQueue,
                            onSelect = { idx -> playerVm.playFromQueue(idx) },
                            onSelectUserQueue = { idx -> playerVm.playFromUserQueue(idx) },
                            onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                            leftFocus = playFocus,
                            firstRowFocus = queueEntryFocus,
                        )
                    }
                }
            }
        }
            }
        } }
        if (!screensaverOn) NextUpToast(
            progressState = smoothProgress,
            durationMs = song.durationMs,
            nextTitle = (state.userQueue.firstOrNull() ?: state.queue.getOrNull(state.queueIndex + 1))?.title,
            songKey = song.id,
        )
    }
}

/**
 * Small "Next: …" toast, bottom-right, that fades in ~15s before the track switches.
 * Reads the per-frame clock via derivedStateOf so only the boolean flip recomposes it.
 */
@Composable
private fun BoxScope.NextUpToast(progressState: androidx.compose.runtime.State<Long>, durationMs: Long, nextTitle: String?, songKey: String) {
    val rawShow by remember(durationMs, nextTitle) {
        androidx.compose.runtime.derivedStateOf {
            nextTitle != null && durationMs > 0L && (durationMs - progressState.value) in 1_000L..15_000L
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
    val alpha by animateFloatAsState(if (shownTitle != null) 1f else 0f, tween(300), label = "nextToast")
    val title = shownTitle
    if (alpha <= 0.01f || title == null) return
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 52.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(Color(0xE6161616), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("接下来", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFA233B), letterSpacing = 1.5.sp))
        Text(title, maxLines = 1, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
    }
}

/**
 * Ambient screensaver — the drifting background (rendered behind) plus a small
 * now-playing chip bottom-left. Grabs focus so any key wakes it back to the player.
 */
@Composable
private fun AmbientScreensaver(song: com.veil.music.data.model.Song, onWake: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
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
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Column {
                Text(song.title, maxLines = 1, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
                Text(song.artistName, maxLines = 1, style = TextStyle(fontSize = 14.sp, color = Color(0xCCFFFFFF), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 10f)))
            }
        }
    }
}

/**
 * 墨卷 / 声轨全屏舞台。
 *
 * 刻意**不含任何播放控件**：没有封面、没有播放条、没有按钮，整屏只有歌词本身。
 * 只有返回键退出，上下左右均无任何操作与提示。退出后回到播放页。
 */
@Composable
private fun FullscreenLyricStage(
    mode: FullscreenLyricMode,
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    song: com.veil.music.data.model.Song,
    fontChoice: LyricFontChoice,
    fontSizeSp: Int,
    lineGapPct: Int,
    tracking: Int,
    light: Boolean,
    onExit: () -> Unit,
) {
    val stageFocus = remember { FocusRequester() }
    val position: () -> Long = { (progressState.value + offsetMs).coerceAtLeast(0L) }
    val fontFamily: androidx.compose.ui.text.font.FontFamily? = when (fontChoice) {
        LyricFontChoice.SYSTEM -> null
        LyricFontChoice.PINGFANG -> PingFang
        LyricFontChoice.MINGMEI -> MingMeiSpring
    }

    LaunchedEffect(mode) {
        repeat(10) {
            if (runCatching { stageFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(stageFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.key == Key.Back) { onExit(); true } else false
            },
    ) {
        when (mode) {
            FullscreenLyricMode.INK_SCROLL -> InkScrollFx(
                lyrics = lyrics,
                positionProvider = position,
                fontSizeSp = fontSizeSp,
                fontFamily = fontFamily,
                lineGapPct = lineGapPct,
                letterSpacingPct = tracking,
                trackName = song.title,
                artist = song.artistName,
                light = light,
            )
            FullscreenLyricMode.TRACK_BED -> TrackBedFx(
                lyrics = lyrics,
                positionProvider = position,
                fontSizeSp = fontSizeSp,
                fontFamily = fontFamily,
                lineGapPct = lineGapPct,
                letterSpacingPct = tracking,
                trackName = song.title,
                artist = song.artistName,
                light = light,
            )
        }
    }
}

/**
 * Full-screen lyrics view — hides the split layout, shows only the karaoke lyrics.
 * Reached from the 3-dots menu; Back exits. 无任何控件与按键提示，仅返回键退出。
 */
@Composable
private fun FullScreenLyrics(
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    onExit: () -> Unit,
    fontScale: Float = 1f,
    lineGap: Float = 1f,
    /** 字间距（em × 100）。0 = 原设计的紧缩排版。 */
    letterSpacing: Int = 0,
    fontChoice: LyricFontChoice = LyricFontChoice.SYSTEM,
    lyricAccent: Color = Color.White,
) {
    // 纯歌词展示：无播放控件、无焦点目标，只吃返回键退出。
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Back) { onExit(); true } else false
        },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 128.dp, top = 92.dp, bottom = 120.dp)) {
            LyricsPanel(
                lyrics = lyrics,
                progressState = progressState,
                offsetMs = offsetMs,
                onSeek = onSeek,
                playFocus = null,
                fontScale = fontScale,
                autoReturnMs = 5_000L,
                lineGap = lineGap,
                tracking = letterSpacing,
                fontFamily = lyricFontFamily(fontChoice),
                accent = lyricAccent,
            )
        }
    }
}

/**
 * Interpolates smooth 60fps playback position between the ~200ms server
 * polling ticks, so word-by-word lyric animation doesn't stutter.
 */
@Composable
private fun rememberSmoothProgressMs(reportedMs: Long, isPlaying: Boolean): androidx.compose.runtime.State<Long> {
    val anchorRealMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anchorPosMs  = remember { mutableLongStateOf(reportedMs) }
    // Returned as State so callers can defer the read to leaf composables — only the
    // one thing that reads .value (the seek bar, the active word wipe) recomposes per
    // frame, instead of the whole Now Playing tree.
    val smoothMs = remember { mutableLongStateOf(reportedMs) }

    LaunchedEffect(reportedMs) {
        anchorPosMs.longValue  = reportedMs
        anchorRealMs.longValue = System.currentTimeMillis()
        smoothMs.longValue = reportedMs
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
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
private fun PlayerProgressBar(
    progressState: androidx.compose.runtime.State<Long>,
    song: com.veil.music.data.model.Song,
    playFocus: FocusRequester,
    player: androidx.media3.common.Player,
    onUp: (() -> Boolean)? = null,
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
    Surface(
        onClick = { if (seekBarFocused) { player.seekTo(scrubMs); runCatching { playFocus.requestFocus() } } },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = noBorder),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { seekBarFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft  -> { scrubMs = (scrubMs - 10_000).coerceAtLeast(0); true }
                        Key.DirectionRight -> { scrubMs = (scrubMs + 10_000).coerceAtMost(duration); true }
                        Key.DirectionUp    -> onUp?.invoke() ?: false
                        else -> false
                    }
                } else false
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(if (seekBarFocused) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))) {
                Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFFA233B)))
                if (seekBarFocused) {
                    Box(modifier = Modifier.fillMaxWidth(scrubProgress).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(
            if (seekBarFocused) formatMs(scrubMs) else formatMs(smoothProgressMs),
            style = TextStyle(fontSize = 11.sp, color = if (seekBarFocused) Color.White else Color(0xFFAAAAAA), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
        )
        Text(song.durationFormatted, style = TextStyle(fontSize = 11.sp, color = Color(0xFFAAAAAA), shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)))
    }
}


/** 字间距：0 = 原设计的紧缩排版（-0.02em ≈ -0.4sp），调大后按 em 放开。 */
private fun lyricTracking(tracking: Int, base: Float) = (base + tracking / 100f).em

/** 全屏歌词字体：系统 / 苹方 → null（跟随主题，本就是苹方），明媚 → 内置字体。 */
private fun lyricFontFamily(choice: LyricFontChoice): androidx.compose.ui.text.font.FontFamily? =
    when (choice) {
        LyricFontChoice.SYSTEM, LyricFontChoice.PINGFANG -> null
        LyricFontChoice.MINGMEI -> com.veil.music.ui.theme.MingMeiSpring
    }

/** Looping, muted motion album-art video layered over the static cover. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun MotionCover(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ready by remember(url) { mutableStateOf(false) }

    val exo = remember(url) {
        // Apple's motion-art master offers variants up to ~6 Mbps 1080p HEVC. That's absurd for a
        // small square loop and it choked this MediaTek Fire TV (sustained 4-6 Mbps HEVC → visibly
        // choppy). Cap the ladder to a small, low-bitrate rung — the cover is only ~300dp.
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(640, 640)
                .setMaxVideoBitrate(1_500_000)
                .build()
        }
        androidx.media3.exoplayer.ExoPlayer.Builder(context).setTrackSelector(selector).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            addListener(object : androidx.media3.common.Player.Listener {
                // ready tracks an ACTUAL rendered frame, not buffer state. A buffered-but-not-yet-
                // drawn surface is exactly when the green YUV frame shows, so gating the fade on
                // onRenderedFirstFrame (not STATE_READY) means alpha never reveals a green surface.
                override fun onRenderedFirstFrame() { ready = true }
            })
            prepare()
        }
    }

    // On resume from background the surface reattaches and briefly shows a green
    // YUV frame. Reset ready=false on pause so the shutter hides it until the
    // first decoded frame arrives.
    DisposableEffect(lifecycleOwner, exo) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { ready = false; exo.pause() }
                Lifecycle.Event.ON_RESUME -> {
                    // Keep the shutter up (ready=false) until the decoder redraws a real frame —
                    // onRenderedFirstFrame flips it. Never optimistically reveal the surface.
                    ready = false
                    exo.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exo.release()
        }
    }

    val alpha by animateFloatAsState(
        if (ready) 1f else 0f,
        tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "motionFade",
    )

    // PlayerView → SurfaceView: the video gets its OWN hardware overlay plane, so the GPU
    // barely touches it. A TextureView composites the 1080p HEVC frame through the shared
    // GPU layer every frame, fighting the DynamicBackground Screen-blend passes — that made
    // the cover choppy on Fire TV. RESIZE_MODE_ZOOM fills the box; the black shutter + alpha
    // fade hide the green-frame handoff instead.
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = exo
            }
        },
        update = { view -> view.player = exo },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
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

/**
 * 从专辑调色板里挑一个「能在黑底上读」的字色。
 *
 * [rememberArtworkPalette] 返回的颜色是给背景水光用的，压过亮度（VALUE_CEILING ≈ 0.80），
 * 直接拿来写字会糊在黑底里。这里只取它的色相，重新给一个够亮的明度，并给饱和度兜底 ——
 * 黑白封面的饱和度接近 0，那种情况退回纯白，不要凭空造一个灰。
 */
private fun lyricAccentOf(colors: List<Color>): Color {
    if (colors.isEmpty()) return Color.White
    val hsv = FloatArray(3)
    val best = colors.maxByOrNull { c ->
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[1] * 0.70f + hsv[2] * 0.30f
    } ?: return Color.White
    android.graphics.Color.colorToHSV(best.toArgb(), hsv)
    if (hsv[1] < 0.12f) return Color.White
    hsv[1] = hsv[1].coerceIn(0.45f, 1f)
    hsv[2] = 0.95f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Full-screen backdrop: blurred album artwork (loaded tiny → upscaled) with
 * 4 radial color blobs drifting in quadrants, Screen-blended on top.
 * Beat energy pulses blob radius and alpha.
 */
@Composable
private fun DynamicBackground(artworkUrlTemplate: String?, songKey: String, beatAnalyzer: com.veil.music.media.BeatAnalyzer, beatMultiplier: Float = 1f, mode: NowPlayingBackground = NowPlayingBackground.DYNAMIC, playing: Boolean = true, orbSpeed: Float = 1f, reduceMotion: Boolean = false, lowPower: Boolean = false, palette: List<Color> = emptyList()) {
    // BLACK: plain black, no blobs, no beat. Nothing else to compute.
    if (mode == NowPlayingBackground.BLACK) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val projector = mode == NowPlayingBackground.PROJECTOR

    // 空调色板会让下面 `i % n` 直接除零。理论上不会发生（调用方总会给 6 个色），
    // 但背景崩掉的代价太大，守一道。
    if (palette.isEmpty()) {
        Box(Modifier.fillMaxSize().background(if (projector) Color.Black else Color(0xFF050505)))
        return
    }

    // Intensity (Calm 0.55 … Crazy 3.5) is applied as a render AMPLITUDE below, NOT by multiplying the
    // level and clipping to 1 — that just pinned everything at max, so Strong and Crazy looked the same
    // and the vocal orb sat maxed. Levels stay 0..1; the multiplier drives how big/bright they swell.
    val amp = beatMultiplier
    // PERF: everything animated below is kept as State and read INSIDE drawBehind, never with `by` at
    // composable scope. Reading them here made DynamicBackground recompose ~60×/sec — rebuilding colour
    // lists and brushes every frame, which was the app's main GC-churn source. Read in the draw lambda,
    // only the draw phase re-runs each frame; the composable recomposes only on song/palette change.
    val rawEnergy by beatAnalyzer.energy.collectAsState()
    // 背景摆动速度（0.1–2.0 倍）：色块漂移周期与跟拍弹簧都按它缩放，两件事必须同步 ——
    // 只改漂移会让色块慢下来、鼓点却还在抽，看着更卡。
    val sp = orbSpeed.coerceIn(com.veil.music.ui.viewmodel.ORB_SPEED_MIN, com.veil.music.ui.viewmodel.ORB_SPEED_MAX)
    // Critically damped (dampingRatio 1.0), not the old 0.5: an underdamped spring overshoots and
    // rings after every hit, which read as the punch "bouncing all over the place". 1.0 gives a snappy
    // attack that settles cleanly with no wobble, so each beat lands once and decays.
    // 能量跟随：换成更软的弹簧（StiffnessLow），并去掉原本略微欠阻尼的 0.6。低刚度意味着
    // 每个鼓点不会被「甩」一下再弹回来 —— 背景是陪衬，跟着节拍抽动比慢漂移更容易让人觉得卡。
    // 刚度再乘 sp：0.1 倍时鼓点几乎只是缓一口气，2 倍时才接近原来那种利落的抽动。
    val beatSpring = androidx.compose.animation.core.spring<Float>(
        dampingRatio = 1f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessLow * sp,
    )
    val energyState = animateFloatAsState(rawEnergy.coerceIn(0f, 1f), beatSpring, label = "beat")

    val rawBands by beatAnalyzer.bands.collectAsState()
    val bandSpring = androidx.compose.animation.core.spring<Float>(
        dampingRatio = 0.85f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessLow * sp,
    )
    val band0State = animateFloatAsState(rawBands.getOrElse(0) { 0f }.coerceIn(0f, 1f), bandSpring, label = "bass")
    val band1State = animateFloatAsState(rawBands.getOrElse(1) { 0f }.coerceIn(0f, 1f), bandSpring, label = "vocal")
    val band2State = animateFloatAsState(rawBands.getOrElse(2) { 0f }.coerceIn(0f, 1f), bandSpring, label = "treble")

    // 调色板由调用方算好传进来（同一张封面同时要给歌词取色，不能解两遍）。
    val animatedStates = palette.mapIndexed { i, c ->
        animateColorAsState(c, tween(1500), label = "blob$i")
    }

    // Orb speed scales the drift periods (faster speed → shorter tween).
    //
    // 基准周期是 38/52/66/28 秒（比早先的 20/27/34/15 慢了近一倍）：电视是三米外看的背景，
    // 原来那组速度色块每分钟要横穿画面三四趟，眼睛会一直被迫跟着它走，看着就「快、还在抖」。
    // 慢下来之后它是「呼吸」而不是「漂移」，同一帧率下也就看不出掉帧了。
    // 用户现在可以在「设置 → 播放与外观」里用 0.1–2.0 倍继续调，这里只按倍率缩放周期。
    val infinite = rememberInfiniteTransition(label = "pool")
    val t1s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((38_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t1")
    val t2s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((52_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t2")
    val t3s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((66_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t3")
    val t4s = infinite.animateFloat(0f, 1f, infiniteRepeatable(tween((28_000 / sp).toInt(), easing = LinearEasing), AnimRepeatMode.Reverse), label = "t4")

    // PERF + accessibility: freeze the perpetual drift while paused OR when Reduce Motion is on. We
    // snapshot the drift phase and read that in the draw instead of the live State, so the draw phase
    // isn't invalidated 60×/sec. Beat energy/bands settle to 0 on their own (and Reduce Motion zeroes
    // them below, so the orbs hold completely still).
    val moving = playing && !reduceMotion
    val frozen = remember { floatArrayOf(0f, 0f, 0f, 0f) }
    LaunchedEffect(moving) {
        if (!moving) { frozen[0] = t1s.value; frozen[1] = t2s.value; frozen[2] = t3s.value; frozen[3] = t4s.value }
    }

    // PROJECTOR uses TRUE black; a projector throws light on a wall, so the near-black #050505 lift
    // that stops a panel crushing shadows becomes a visible grey rectangle instead.
    Box(Modifier.fillMaxSize().background(if (projector) Color.Black else Color(0xFF050505))) {
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width; val h = size.height
            // Deferred reads — this is the draw phase, so these re-run per frame without recomposing.
            // While paused we read the frozen snapshot (not the live State) so the draw stops updating.
            val t1 = if (moving) t1s.value else frozen[0]
            val t2 = if (moving) t2s.value else frozen[1]
            val t3 = if (moving) t3s.value else frozen[2]
            val t4 = if (moving) t4s.value else frozen[3]
            val n = animatedStates.size
            val motionAmp = if (reduceMotion) 0f else 1f   // Reduce Motion → orbs hold at base, no pulse

            if (projector) {
                // THREE ORBS, ONE PER BAND — bass (slow, low), vocal (centre-panned, mid), treble
                // (fast, high). Each rides an ellipse on its own animator+phase so the composition
                // never repeats, swells on its band, and glows from a palette colour.
                // Small drift toward the next accent so colour evolves but each orb keeps its identity.
                val orbColors = List(3) { i -> lerp(animatedStates[i % n].value, animatedStates[(i + 1) % n].value, 0.05f + 0.10f * floatArrayOf(t1, t2, t3)[i]) }
                val orbLevels = floatArrayOf(band0State.value * motionAmp, band1State.value * motionAmp, band2State.value * motionAmp)
                // Pushed right of the album art (which sits in the left column) so no orb hides behind it.
                val anchorX = floatArrayOf(0.52f, 0.62f, 0.72f)
                val anchorY = floatArrayOf(0.44f, 0.56f, 0.46f)
                val phase   = floatArrayOf(0f, 2.1f, 4.2f)
                val drift   = floatArrayOf(t1, t2, t3)
                val twoPi = (2.0 * Math.PI).toFloat()
                // Smaller orbs than the full-screen blobs — a projector glow is a light source, not a
                // wash. Wider drift so they still roam the frame at the smaller size.
                // Per-band character so the three read as three: bass is the big slow one, treble the
                // small snappy one, vocal in between. Each is DIM at rest and swells hard on its band,
                // so the beat is the difference you see — not a constant wash.
                val bandBaseR = floatArrayOf(minOf(w, h) * 0.26f, minOf(w, h) * 0.21f, minOf(w, h) * 0.16f)
                val sizeRide  = floatArrayOf(0.75f, 0.85f, 1.05f)   // treble punches biggest relative to size
                for (i in 0 until 3) {
                    val lvl = orbLevels[i]
                    // Gentler drift so the orbs roam slowly instead of swimming around the frame.
                    val cx = anchorX[i] * w + cos(drift[i] * twoPi + phase[i]) * 0.08f * w
                    val cy = anchorY[i] * h + sin(drift[i] * twoPi + phase[i]) * 0.045f * h
                    val c = Offset(cx, cy)
                    val col = orbColors[i]
                    // Halo alpha now RIDES the beat: dim at rest (won't wash the lyrics), bright on the
                    // hit. On true black under Screen blend that's exactly the expressive pulse we want.
                    // Intensity (amp) scales the SWELL, not the base: Calm barely moves, Crazy swings big
                    // and bright. Generous caps so higher tiers stay visibly punchier instead of clipping.
                    val r = bandBaseR[i] * (1f + (lvl * sizeRide[i] * amp).coerceAtMost(2.2f))
                    val haloA = (0.24f + lvl * 0.42f * amp).coerceAtMost(0.9f)
                    drawCircle(
                        brush = Brush.radialGradient(listOf(col.copy(alpha = haloA), col.copy(alpha = 0f)), center = c, radius = r),
                        radius = r, center = c, blendMode = BlendMode.Screen,
                    )
                    // Core: small, whitened (a real glow's hottest point desaturates), punches hard on
                    // the band — a tiny fraction of the area, so it can flare without lifting the black.
                    // Low Power skips the core (a second gradient per orb per frame) — the halo carries it.
                    if (!lowPower) {
                        val cr = r * 0.34f
                        val core = lerp(col, Color.White, 0.6f)
                        drawCircle(
                            brush = Brush.radialGradient(listOf(core.copy(alpha = (0.12f + lvl * 0.62f * amp).coerceAtMost(0.92f)), core.copy(alpha = 0f)), center = c, radius = cr),
                            radius = cr, center = c, blendMode = BlendMode.Screen,
                        )
                    }
                }
                // A LIGHT edge fade only — just enough to guarantee no lit rectangle at the frame edge.
                // The orbs are small and pulled inward, so they never reach the edge anyway; a heavy
                // vignette here was painting black back over the glow and killing it.
                val ev = h * 0.10f; val eh = w * 0.10f
                drawRect(Brush.verticalGradient(listOf(Color.Black, Color(0x00000000)), startY = 0f, endY = ev))
                drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color.Black), startY = h - ev, endY = h))
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color(0x00000000)), startX = 0f, endX = eh))
                drawRect(Brush.horizontalGradient(listOf(Color(0x00000000), Color.Black), startX = w - eh, endX = w))
                // Right-side darkening for lyrics readability (a gradient, not an edge). Stronger now —
                // a bright orb drifting under the lyric column was washing out the dim inactive lines.
                drawRect(Brush.horizontalGradient(listOf(Color(0x00000000), Color(0x9E000000)), startX = w * 0.30f, endX = w))
                return@drawBehind
            }

            // DYNAMIC — four drifting album-colour blobs. Intensity (amp) scales the beat swing;
            // Reduce Motion (motionAmp=0) holds them still.
            val energy = energyState.value * motionAmp
            // 3 blobs, not 4: each BlendMode.Screen blob forces a full-screen offscreen compositing
            // pass recorded every frame, and on Fire TV that draw-command recording (behind the
            // per-word lyric text) was the measured jank on this screen. 3 colour pools still read as
            // Apple's oil-painting spread; the 4th blob was mostly overlap.
            val blobCount = if (lowPower) 2 else 3
            // Each blob is PINNED to its own distinct palette colour — no pair-crossfade. The old
            // lerp blended two colours per blob and, with big overlapping blobs under Screen, merged
            // the whole thing into one moving gradient wash. One colour per blob + smaller radius
            // (below) lets the separate colours read as distinct pools, like Apple's oil-painting
            // patches, instead of averaging into a single tint.
            val colors4 = List(4) { i -> animatedStates[i % n].value }
            val eAmp = (energy * amp).coerceIn(0f, 2.2f)
            // Per-corner band ride (projector-mode flavour): each corner breathes on its own
            // frequency band on top of the shared beat, so bass thumps two corners and treble
            // shimmers the other two — the palette pools now read the spectrum, not one pulse.
            val bandRide = floatArrayOf(
                band0State.value,   // top-left  → bass
                band2State.value,   // top-right → treble
                band1State.value,   // bot-left  → vocal/mid
                band0State.value,   // bot-right → bass
            )
            // 节拍幅度收一半：原来半径靠 25%、透明度靠 20% 的整屏色块跟着鼓点一起放大缩小，
            // 每一拍都是一次全屏重绘，观感上就是「一闪一闪 + 掉帧」。现在只是轻微起伏。
            val beatScale = 1f + eAmp * 0.12f
            val beatAlpha = (0.60f + eAmp * 0.10f).coerceAtMost(0.92f)
            // Smaller than the old 0.62 so blobs overlap less and stay recognisably separate colours.
            val r = maxOf(w, h) * 0.42f * beatScale
            val nudge = eAmp * maxOf(w, h) * 0.008f
            val nudgeOffsets = listOf(
                Offset( nudge,  nudge * 0.5f),
                Offset(-nudge, -nudge * 0.7f),
                Offset( nudge * 0.6f, -nudge),
                Offset(-nudge * 0.4f,  nudge * 0.8f),
            )
            val centers = listOf(
                Offset(lerp(0.02f, 0.28f, t1) * w, lerp(0.05f, 0.32f, t2) * h),
                Offset(lerp(0.72f, 0.98f, t2) * w, lerp(0.05f, 0.35f, t3) * h),
                Offset(lerp(0.05f, 0.30f, t3) * w, lerp(0.68f, 0.95f, t1) * h),
                Offset(lerp(0.70f, 0.98f, t1) * w, lerp(0.65f, 0.95f, t3) * h),
            ).mapIndexed { i, c -> c + nudgeOffsets[i] }
            colors4.take(blobCount).forEachIndexed { i, color ->
                val ride = (bandRide[i] * amp).coerceIn(0f, 1.6f)
                val rr = r * (1f + ride * 0.18f)
                val aa = (beatAlpha + ride * 0.10f).coerceAtMost(0.96f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = aa), color.copy(alpha = 0f)),
                        center = centers[i], radius = rr,
                    ),
                    radius = rr, center = centers[i],
                    blendMode = BlendMode.Screen,
                )
            }
            // Center darkening
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x77000000), Color(0x00000000)),
                    center = Offset(w * 0.5f, h * 0.5f), radius = maxOf(w, h) * 0.55f,
                ),
                radius = maxOf(w, h) * 0.55f, center = Offset(w * 0.5f, h * 0.5f),
            )
            // Right-side darkening for lyrics readability
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0x00000000), Color(0x7A000000)),
                    startX = w * 0.35f, endX = w,
                ),
            )
            // Kept low: the flat veil mutes every hue equally, so readability comes
            // from the right-side gradient above instead.
            drawRect(Color(0x22000000))
        })
    }
}

/** Idle time (no D-pad navigation) before the Now Playing chrome auto-hides (then a 2 s settle). */
private const val CHROME_HIDE_MS = 7000L

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
private const val GAP_FADEOUT_MS    = 500L


@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LyricsPanel(
    lyrics: List<LyricLine>,
    progressState: androidx.compose.runtime.State<Long>,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    playFocus: FocusRequester? = null,
    fontScale: Float = 1f,
    autoReturnMs: Long = 7000L,
    lineGap: Float = 1f,
    /** 字间距（em × 100）。0 = 原设计的紧缩排版。 */
    tracking: Int = 0,
    /** 字体；null = 跟随主题（苹方）。 */
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    accent: Color = Color.White,
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
    // Keep a line lit until its background vocals finish too — the lead line shouldn't
    // dim out while its (call-and-response) background part is still being sung.
    val activeIndex = if (passedIndex >= 0) {
        val ln = lyrics[passedIndex]
        val activeUntil = maxOf(ln.endMs, ln.background?.endMs ?: Long.MIN_VALUE) + LINE_END_GRACE_MS
        if (progressMs <= activeUntil) passedIndex else -1
    } else -1

    val scrollAnchor = passedIndex.coerceAtLeast(0)
    val firstLoad = remember { mutableStateOf(true) }

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

    LaunchedEffect(scrollAnchor, lyrics.size) {
        if (lyrics.isEmpty()) return@LaunchedEffect
        val target = (scrollAnchor - LYRIC_LEAD_LINES).coerceAtLeast(0)
        if (firstLoad.value) {
            listState.scrollToItem(target)
            firstLoad.value = false
            return@LaunchedEffect
        }
        if (userScrolled) {
            // Still browsing elsewhere — leave the list alone. Once the active line is
            // visible again this same effect fires on the next line change and takes over.
            val backInView = listState.layoutInfo.visibleItemsInfo.any { it.index == scrollAnchor }
            if (!backInView || listState.isScrollInProgress) return@LaunchedEffect
            userScrolled = false
            listState.animateScrollToItem(target)
            return@LaunchedEffect
        }

        // Centre the active line on every line change (Apple Music behaviour) — the
        // old "only scroll near the bottom" logic is what made it jump every few lines.
        val info = listState.layoutInfo
        val activeItem = info.visibleItemsInfo.firstOrNull { it.index == scrollAnchor }
        if (activeItem == null) {
            listState.animateScrollToItem(target)
        } else {
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val itemCentre = activeItem.offset + activeItem.size / 2
            // Sit the active line high — around the album-art midline (~30% down), not centre.
            val delta = (itemCentre - viewportH * 0.30f)
            if (kotlin.math.abs(delta) > 8f) listState.animateScrollBy(delta)
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
    Column(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).padding(end = 16.dp).nestedScroll(nestedScrollConnection)
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
        contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
            val inGap = progressMs in prevEnd until line.startMs

            if (gapMs >= GAP_THRESHOLD_MS && inGap && idx == passedIndex + 1) {
                val dotsAlpha = if (line.startMs - progressMs < GAP_FADEOUT_MS)
                    ((line.startMs - progressMs).toFloat() / GAP_FADEOUT_MS).coerceIn(0f, 1f)
                else 1f
                MusicalDots(
                    fraction = ((progressMs - prevEnd).toFloat() / gapMs).coerceIn(0f, 1f),
                    outerAlpha = dotsAlpha,
                    // Inset to align with the lyric text (and clear the left edge so the
                    // first dot isn't clipped).
                    modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                )
            }

            if (unsynced) {
                Text(
                    line.text,
                    style = TextStyle(
                        fontSize = (22f * fontScale).sp,
                        lineHeight = (28f * fontScale * lineGap).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        letterSpacing = lyricTracking(tracking, -0.02f),
                        color = Color(0x66FFFFFF),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                )
            } else {
                LyricLineRow(
                    line = line,
                    isActive = isActive,
                    isPast = isPast,
                    progress = progressProvider,
                    fontScale = fontScale,
                    lineGap = lineGap,
                    tracking = tracking,
                    fontFamily = fontFamily,
                    accent = accent,
                    focusRequester = if (isActive) activeLineFocus else null,
                    onSeek = { onSeek(line.startMs) },
                )
            }
        }
    }
    }
}

@Composable
private fun MusicalDots(fraction: Float, outerAlpha: Float = 1f, modifier: Modifier = Modifier) {
    // Each dot grows sequentially, then all shrink together.
    val dotStarts = floatArrayOf(0f, 0.24f, 0.48f)
    val dotDur    = 0.28f
    val shrinkStart = 0.82f

    Row(
        modifier = modifier.graphicsLayer { alpha = outerAlpha },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 3) {
            val growFrac   = ((fraction - dotStarts[i]) / dotDur).coerceIn(0f, 1f)
            val shrinkFrac = if (fraction >= shrinkStart)
                ((fraction - shrinkStart) / (1f - shrinkStart)).coerceIn(0f, 1f) else 0f
            val lit = if (fraction >= shrinkStart) 1f - shrinkFrac else growFrac
            val dotColor = lerp(Color(0xFF444444), Color.White, lit)
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer { val s = 0.55f + 0.65f * lit; scaleX = s; scaleY = s }
                    .clip(RoundedCornerShape(50))
                    .background(dotColor),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordWipeLine(
    words: List<LyricWord>,
    activeIdx: Int,
    progressMs: Long,
    live: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 26.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 32.sp,
    weight: FontWeight = FontWeight.Bold,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    letterSpacing: androidx.compose.ui.unit.TextUnit = (-0.4).sp,
    sungColor: Color = Color.White,
    unsungColor: Color = Color(0xFF76767C),
) {
    FlowRow(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        words.forEachIndexed { i, word ->
            val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
            val sung = i < activeIdx
            val isCurrent = i == activeIdx && live
            val frac = when {
                sung      -> 1f
                isCurrent -> ((progressMs - word.startMs).toFloat() / dur).coerceIn(0f, 1f)
                else      -> 0f
            }
            // Smooth sine swell — grows to a peak mid-word then eases back to original
            // size by the end. Sine (not a linear triangle) keeps it from reading as
            // shake; left-anchored so the first letter stays put. Slow/held words swell +
            // glow more; fast words stay flat.
            val slow  = (dur.coerceIn(300L, 1600L) - 300L) / 1300f
            val pulse = if (isCurrent) kotlin.math.sin(frac * Math.PI.toFloat()).coerceIn(0f, 1f) else 0f
            val grow  = 1f + 0.09f * slow * pulse
            val glowA = if (isCurrent && dur > 700L) 0.34f * pulse * slow else 0f
            WordWipe(
                text  = word.text + if (i < words.lastIndex) " " else "",
                frac  = frac, scale = grow, glowAlpha = glowA,
                fontSize = fontSize, lineHeight = lineHeight, weight = weight,
                fontFamily = fontFamily, letterSpacing = letterSpacing,
                sungColor = sungColor, unsungColor = unsungColor,
            )
        }
    }
}

@Composable
private fun WordWipe(
    text: String,
    frac: Float,
    scale: Float,
    glowAlpha: Float,
    fontSize: androidx.compose.ui.unit.TextUnit = 26.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 32.sp,
    weight: FontWeight = FontWeight.Bold,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    letterSpacing: androidx.compose.ui.unit.TextUnit = (-0.4).sp,
    sungColor: Color = Color.White,
    unsungColor: Color = Color(0xFF76767C),
) {
    val f = frac.coerceIn(0f, 1f)
    // Soft left→right sweep: a feathered gradient edge instead of a hard clip line,
    // so the sung/unsung boundary reads as a smooth blur rather than a moving cut.
    val feather = 0.22f
    val lo = (f - feather).coerceIn(0f, 1f)
    val hi = (f + feather).coerceIn(0f, 1f)
    val brush: Brush = when {
        f <= 0f -> SolidColor(unsungColor)
        f >= 1f -> SolidColor(sungColor)
        else -> Brush.horizontalGradient(0f to sungColor, lo to sungColor, hi to unsungColor, 1f to unsungColor)
    }
    // blurRadius 14, not 32: a blurred text shadow is recorded into the display list every frame the
    // current word swells, and a 32px blur is a costly record on Fire TV. 14 still reads as a soft
    // glow but roughly quarters the blur cost.
    val glow = if (glowAlpha > 0f) Shadow(Color.White.copy(alpha = glowAlpha), blurRadius = 14f) else null
    Text(
        text,
        style = TextStyle(fontSize = fontSize, fontWeight = weight, lineHeight = lineHeight, fontFamily = fontFamily, letterSpacing = letterSpacing, brush = brush, shadow = glow),
        modifier = Modifier.graphicsLayer {
            scaleX = scale; scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    progress: () -> Long,
    onSeek: () -> Unit,
    fontScale: Float = 1f,
    lineGap: Float = 1f,
    tracking: Int = 0,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    accent: Color = Color.White,
    focusRequester: FocusRequester? = null,
) {
    // Reading the provider here subscribes only this row: the active row (live clock)
    // recomposes per frame; inactive rows read a constant and never re-run on tick.
    val progressMs = progress()
    val targetOpacity = when {
        isActive -> 1f
        isPast   -> 0.18f
        else     -> 0.25f
    }
    // Active line grows via font size (20→26sp) only. The old 0.93→1.0 graphicsLayer scale tween made
    // rows visibly stretch/"melt" for a frame as the active line changed during a scroll — dropped it;
    // opacity alone carries the transition.
    val opacity by animateFloatAsState(targetOpacity, tween(200), label = "lineOpacity")

    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = opacity
            clip = false
        }
    ) {
        Surface(
            onClick = onSeek,
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color(0x1AFFFFFF),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = (3f * lineGap).dp)) {
                if (isActive && line.words.isNotEmpty()) {
                    // Karaoke wipe: each word fills left→right as it's sung, current word
                    // grows + glows on slow/held words. Apple Music style.
                    val activeIdx = line.words.indexOfLast { it.startMs <= progressMs }
                    // 逐字上色取当前唱片的专辑色：已唱过的字是这个颜色，没唱到的仍是灰的。
                    // 这样「唱到哪儿」和「这张专辑是什么颜色」是同一件事，比纯白更有归属感。
                    WordWipeLine(
                        words = line.words,
                        activeIdx = activeIdx,
                        progressMs = progressMs,
                        fontSize = (24f * fontScale).sp,
                        lineHeight = (30f * fontScale * lineGap).sp,
                        fontFamily = fontFamily,
                        letterSpacing = lyricTracking(tracking, -0.02f),
                        sungColor = accent,
                    )
                } else {
                    // Apple Music look: the active line is large + white; every other line is smaller
                    // and dim, so the sung line clearly stands out. (Inactive lines share one smaller
                    // size, so as the active line moves only two lines resize — the old active shrinks,
                    // the new one grows — which the scroll animation carries smoothly.)
                    // 行距只乘到 lineHeight：字号不变，行与行之间松开，字不会被拉扁。
                    val style = when {
                        isActive -> TextStyle(fontSize = (24f * fontScale).sp, fontWeight = FontWeight.Bold, lineHeight = (30f * fontScale * lineGap).sp, fontFamily = fontFamily, letterSpacing = lyricTracking(tracking, -0.02f), color = Color.White)
                        isPast   -> TextStyle(color = Color(0xFFCCCCCC), fontSize = (18.5f * fontScale).sp, fontWeight = FontWeight.SemiBold, lineHeight = (24f * fontScale * lineGap).sp, fontFamily = fontFamily, letterSpacing = lyricTracking(tracking, -0.01f))
                        else     -> TextStyle(color = Color(0xFF8E8E93), fontSize = (18.5f * fontScale).sp, fontWeight = FontWeight.SemiBold, lineHeight = (24f * fontScale * lineGap).sp, fontFamily = fontFamily, letterSpacing = lyricTracking(tracking, -0.01f))
                    }
                    Text(text = AnnotatedString(line.text), style = style)
                }

                val bg = line.background
                if (bg != null && bg.text.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    val bgProgress = progressMs + 300L  // -300ms early start
                    val bgLive = bgProgress in bg.startMs..(bg.endMs + 600L)
                    val bgTargetScale = if (bgLive) 1.08f else 0.93f
                    val bgScale by animateFloatAsState(bgTargetScale, tween(250), label = "bgScale")

                    Box(
                        Modifier.graphicsLayer {
                            scaleX = bgScale; scaleY = bgScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                    ) {
                        if (bg.words.isNotEmpty()) {
                            // Same soft left→right wipe as the lead line, just smaller/dimmer.
                            val bgActiveIdx = bg.words.indexOfLast { it.startMs <= bgProgress }
                            WordWipeLine(
                                words = bg.words, activeIdx = bgActiveIdx, progressMs = bgProgress,
                                live = bgLive,
                                fontSize = 19.sp, lineHeight = 24.sp, weight = FontWeight.SemiBold,
                                sungColor = Color(0xFFE0E0E0), unsungColor = Color(0xFF6E6E73),
                            )
                        } else {
                            Text(
                                text = AnnotatedString(bg.text),
                                style = TextStyle(
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (bgLive) Color(0xFFE0E0E0) else Color(0xFF6E6E73),
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
    queue: List<com.veil.music.data.model.Song>,
    currentIndex: Int,
    userQueue: List<com.veil.music.data.model.Song> = emptyList(),
    onSelect: (Int) -> Unit,
    onSelectUserQueue: (Int) -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    leftFocus: androidx.compose.ui.focus.FocusRequester? = null,
    /** 列表第一行的 requester：播放页「列表」键按右键点名进这里。 */
    firstRowFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    var movingIndex by remember { mutableStateOf<Int?>(null) }

    // Always scroll to top (current song) when index changes or userQueue gains items
    LaunchedEffect(currentIndex, userQueue.size) {
        listState.animateScrollToItem(0)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("接下来播放", fontSize = 13.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Medium)
            if (movingIndex != null) Text("长按确定键放下", fontSize = 10.sp, color = Color(0xFF888888))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (userQueue.isNotEmpty()) {
                item {
                    Text("即将播放", fontSize = 10.sp, color = Color(0xFFFA233B), fontWeight = FontWeight.SemiBold,
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
                            var mm = m
                            if (leftFocus != null) mm = mm.focusProperties { left = leftFocus }
                            if (idx == 0 && firstRowFocus != null) mm = mm.focusRequester(firstRowFocus)
                            mm
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("◆", fontSize = 9.sp, color = Color(0xFFFA233B), modifier = Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title + if (song.isMusicVideo) " (MV)" else "", fontSize = 13.sp, color = Color(0xFFDDDDDD), maxLines = 1)
                                Text(song.artistName, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                            }
                            Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
                item {
                    Text("来自队列", fontSize = 10.sp, color = Color(0xFF666666), fontWeight = FontWeight.SemiBold,
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
                        var mm = m
                        if (leftFocus != null) mm = mm.focusProperties { left = leftFocus }
                        // 用户队列为空时，真正可见的第一行是"来自队列"的 rel == 0。
                        if (rel == 0 && userQueue.isEmpty() && firstRowFocus != null)
                            mm = mm.focusRequester(firstRowFocus)
                        mm
                    },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (isMoving) "⠿" else "${idx + 1}",
                            fontSize = 11.sp,
                            color = if (isMoving) Color(0xFFFA233B) else if (isCurrent) Color(0xFFFA233B) else Color(0xFFAAAAAA),
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(song.title + if (song.isMusicVideo) " (MV)" else "", fontSize = 13.sp, color = if (isCurrent || isMoving) Color.White else Color(0xFFDDDDDD), maxLines = 1, fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal)
                            Text(song.artistName, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                        }
                        Text(song.durationFormatted, fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    }
                }
            }
        }
    }
}

/** "Off" / "5 min" / "1 h" / "2 h" for a screensaver timeout in minutes. */
internal fun screensaverLabel(min: Int): String = when {
    min <= 0    -> "关闭"
    min % 60 == 0 -> "${min / 60} 小时"
    else        -> "$min 分钟"
}

private enum class TransportIcon { Play, Pause, Prev, Next, Panel }

/**
 * 「⋯」更多操作。放在控件行末位（Apple TV 播放器的位置），不再挂在标题右侧 ——
 * 那个坐标不在任何键位上，遥控器选不到。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OptionsButton(
    onClick: () -> Unit,
    onUp: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    val focusBorder = Border(
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.38f)),
        shape = RoundedCornerShape(50),
    )
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x1AFFFFFF),
            focusedContainerColor = Color(0x33FFFFFF),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = focusBorder),
        modifier = modifier.size(38.dp).onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionUp -> onUp?.invoke() ?: false
                Key.DirectionRight -> onRight?.invoke() ?: false
                else -> false
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                val r = size.minDimension * 0.07f
                val cy = size.height / 2f
                listOf(0.26f, 0.5f, 0.74f).forEach { fx ->
                    drawCircle(Color.White, radius = r, center = androidx.compose.ui.geometry.Offset(size.width * fx, cy))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TransportButton(
    icon: TransportIcon,
    onClick: () -> Unit,
    large: Boolean = false,
    loading: Boolean = false,
    /**
     * 上键的显式出口（返回 true 表示已经接管）。
     *
     * 播放页的控件列在屏幕左侧、顶栏胶囊在正上方居中，两者横向不重叠 —— 纯几何搜索会卡住，
     * 遥控器就再也回不到顶栏（也就按不到「搜索」「设置」）。这里给一条明确通道。
     *
     * 用按键而不是 focusProperties：tv-material3 的 Surface 把可聚焦节点建在内部，外层挂的
     * focusProperties 不作用于它（实测按上键无反应）。
     */
    onUp: (() -> Boolean)? = null,
    /** 右键的显式出口（播放队列的第一行）。 */
    onRight: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val btnSize = if (large) 64.dp else 54.dp
    // SF Symbols sit inside an optical bounding box (~30% padding), so they render smaller than the
    // old edge-to-edge Canvas glyphs at the same size — bump to compensate and read as Apple-sized.
    // forward.fill/backward.fill pad even more than play.fill, so the non-large size is generous.
    val canvasSize = if (large) 34.dp else 31.dp
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    // 焦点态参考 upstream：用一圈细边 + 很轻的底，而不是一大团白色光晕。
    // 原来 alpha 0.3 / 10dp 的光晕在电视上看是一块发白的毛边，久了刺眼。
    val focusBorder = Border(
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.38f)),
        shape = RoundedCornerShape(12.dp),
    )
    Surface(
        onClick = onClick,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor        = Color.Transparent,
            focusedContainerColor = Color(0x1AFFFFFF),
        ),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow   = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.07f), 3.dp)),
        border = ClickableSurfaceDefaults.border(border = noBorder, focusedBorder = focusBorder),
        modifier = modifier.size(btnSize).onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionUp -> onUp?.invoke() ?: false
                Key.DirectionRight -> onRight?.invoke() ?: false
                else -> false
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            // Spinning ring while the track decrypts. Only composed when loading, so it
            // costs nothing during normal playback.
            if (loading) {
                val spin = rememberInfiniteTransition(label = "spin")
                val angle by spin.animateFloat(
                    0f, 360f,
                    infiniteRepeatable(tween(900, easing = LinearEasing), AnimRepeatMode.Restart),
                    label = "spinAngle",
                )
                // Rotate a layer rather than re-issuing drawArc with a new startAngle
                // each frame: the arc geometry is then rasterised once and the spin is
                // a matrix transform, which the Fire TV GPU does for free.
                Canvas(
                    Modifier.size(btnSize * 0.82f).graphicsLayer { rotationZ = angle },
                ) {
                    val sw = if (large) 3f.dp.toPx() else 2.2f.dp.toPx()
                    val inset = sw / 2f
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(
                        color = Color.White.copy(alpha = 0.22f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = 0f, sweepAngle = 90f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round),
                    )
                }
            }
            val color = if (loading) Color.White.copy(alpha = 0.45f) else Color.White
            // SF Symbol transport glyphs (same set as the rest of the app).
            val glyph = when (icon) {
                TransportIcon.Play  -> com.veil.music.ui.components.Glyph.PLAY
                TransportIcon.Pause -> com.veil.music.ui.components.Glyph.PAUSE
                TransportIcon.Prev  -> com.veil.music.ui.components.Glyph.PREV
                TransportIcon.Next  -> com.veil.music.ui.components.Glyph.NEXT
                TransportIcon.Panel -> com.veil.music.ui.components.Glyph.QUEUE
            }
            com.veil.music.ui.components.Icon(glyph, size = canvasSize, color = color)
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NpMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    icon: com.veil.music.ui.components.Glyph? = null,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF2E2E30)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                    com.veil.music.ui.components.Icon(icon, size = 16.dp, color = Color(0xFFC0C0C4))
                }
            }
            Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
            if (checked) com.veil.music.ui.components.Icon(
                com.veil.music.ui.components.Glyph.CHECK, size = 14.dp, color = Color(0xFFFA233B))
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
    var overflows by remember(text) { mutableStateOf(false) }
    var measured  by remember(text) { mutableStateOf(false) }
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
            style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = color,
                letterSpacing = letterSpacing,
                shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 10f)),
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
