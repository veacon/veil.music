package com.veacon.veilmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.ui.theme.AppPalette
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import com.veacon.veilmusic.ui.viewmodel.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **迷你胶囊**（MiniPlayerCapsule）—— 底部迷你播放条，移植自 v1.1.2。
 *
 * 从左到右：随机 / 上一首 / 播放暂停 / 下一首 / 循环 ｜ 封面 + 歌名 + 当前歌词 ｜ 歌词 / 队列 / 音量，
 * 最底部压一条细进度线。整条是一枚圆角胶囊，贴在屏幕底部居中。
 *
 * 与 v1.1.2 的差异：
 *  - 背景跟随当前浅色/深色主题（[AppPalette.adaptive]），不再写死深色；
 *  - 底衬一层当前封面的高斯模糊（API 31+ 生效，低版本退化为半透明主题底），做出毛玻璃质感；
 *  - 中间信息区第二行显示**当前歌词**（无歌词时回退歌手 - 专辑），字体保持应用默认，不引入新字体。
 *
 * [heightPct] 同时缩放整条胶囊的高度与内容（图标、封面、文字等比），[widthPct] 只改占屏宽度。
 */
@Composable
fun MiniPlayerCapsule(
    playerVm: PlayerViewModel,
    onOpen: () -> Unit,
    onOpenQueue: () -> Unit,
    heightPct: Int = 100,
    widthPct: Int = 100,
    modifier: Modifier = Modifier,
) {
    val state by playerVm.state.collectAsState()
    val song = state.currentSong ?: return

    // 高度比例同时是「内容缩放比例」：只改外框会在缩小时露出空白边。
    val hs = (heightPct / 100f).coerceIn(0.5f, 1.5f)
    val ws = (widthPct / 100f).coerceIn(0.5f, 1.0f)
    val barHeight = 76.dp * hs

    val duration = song.durationMs.takeIf { it > 0 } ?: 1L
    val progress = (state.progressMs.toFloat() / duration).coerceIn(0f, 1f)
    val muted = playerVm.player.volume <= 0.001f
    var lastVolume by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()

    // 当前歌词行：取最后一条 startMs 已到的行；没有歌词时回退歌手 - 专辑。
    val lyricLine = run {
        val idx = state.lyrics.indexOfLast { it.startMs <= state.progressMs }
        state.lyrics.getOrNull(idx)?.text?.takeIf { it.isNotBlank() }
    }
    val subtitle = lyricLine
        ?: listOf(song.artistName, song.albumName).filter { it.isNotBlank() }.joinToString(" - ")

    // 9 个可点区：0 随机 1 上一首 2 播放/暂停 3 下一首 4 循环 5 中间（进播放页）6 歌词 7 队列 8 音量
    val slots = 9
    val reqs = remember { List(slots) { FocusRequester() } }
    val focusedStates = remember { List(slots) { mutableStateOf(false) } }
    val anyFocused = focusedStates.any { it.value }

    /** 左右键在条内行走：点名而不靠几何搜索，避免焦点跑到条外。 */
    fun step(from: Int, delta: Int) {
        val to = (from + delta).coerceIn(0, slots - 1)
        if (to == from) return
        scope.launch {
            delay(16)
            runCatching { reqs[to].requestFocus() }
        }
    }

    fun nav(index: Int): Modifier = Modifier
        .focusRequester(reqs[index])
        .onFocusChanged { focusedStates[index].value = it.isFocused }
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { step(index, -1); true }
                Key.DirectionRight -> { step(index, 1); true }
                else -> false
            }
        }

    // 玻璃底：不透明兜底色 + 封面模糊层 + 半透明着色层，做出毛玻璃质感（Android 8 也可用）。
    val glassBase = AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF1B1B1E))
    val glassTint = AppPalette.adaptive(Color(0x99FFFFFF), Color(0x991B1B1E))
    val borderCol = if (anyFocused) AppPalette.adaptive(Color(0x33000000), Color(0x4DFFFFFF))
                    else AppPalette.adaptive(Color(0x14000000), Color(0x14FFFFFF))

    Box(
        modifier = modifier
            .fillMaxWidth((0.94f * ws).coerceIn(0.4f, 0.94f))
            .widthIn(max = 1280.dp * ws)
            .height(barHeight)
            .clip(RoundedCornerShape(barHeight / 2))
            .background(glassBase)
            .border(width = 1.dp, color = borderCol, shape = RoundedCornerShape(barHeight / 2)),
    ) {
        // 毛玻璃底：封面先做高斯模糊（Coil 预模糊，Android 8 也生效；替代只有 API 31+ 才有的 Modifier.blur）。
        val cover = song.artworkUrl(320)
        if (cover != null) {
            val context = LocalContext.current
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(cover)
                    .transformations(ArtworkBlurTransformation())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.92f),
            )
        }
        // 玻璃着色层：统一色温、保证文字可读，并让深浅主题各自呼应。
        Box(Modifier.fillMaxSize().background(glassTint))
        // 顶部高光 + 底部内阴影：苹果玻璃招牌的立体感，让胶囊是「浮」在内容上的实体。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x2EFFFFFF),
                        0.45f to Color(0x00FFFFFF),
                        0.72f to Color(0x00FFFFFF),
                        1f to Color(0x1F000000),
                    )
                ),
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp * hs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniButton(
                glyph = Glyph.SHUFFLE,
                iconSize = 20.dp * hs,
                scale = hs,
                active = state.isShuffled,
                modifier = nav(0),
                onClick = { playerVm.toggleShuffle() },
            )
            MiniButton(
                glyph = Glyph.PREV,
                iconSize = 24.dp * hs,
                scale = hs,
                modifier = nav(1),
                onClick = { playerVm.prev() },
            )
            MiniButton(
                glyph = if (state.isPlaying) Glyph.PAUSE else Glyph.PLAY,
                iconSize = 30.dp * hs,
                scale = hs,
                big = true,
                modifier = nav(2),
                onClick = { playerVm.togglePlayPause() },
            )
            MiniButton(
                glyph = Glyph.NEXT,
                iconSize = 24.dp * hs,
                scale = hs,
                modifier = nav(3),
                onClick = { playerVm.next() },
            )
            MiniButton(
                glyph = if (state.repeatMode == RepeatMode.One) Glyph.REPEAT_ONE else Glyph.REPEAT,
                iconSize = 20.dp * hs,
                scale = hs,
                active = state.repeatMode != RepeatMode.Off,
                modifier = nav(4),
                onClick = { playerVm.toggleRepeat() },
            )

            Spacer(Modifier.width(10.dp * hs))

            // 中间：封面 + 两行文字（歌名在上，当前歌词在下）。整块可点 → 进完整播放页。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp * hs))
                    .then(nav(5))
                    .clickable { onOpen() }
                    .padding(horizontal = 8.dp * hs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song, hs)
                Spacer(Modifier.width(12.dp * hs))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = AppPalette.adaptive(Color(0xFF1D1D1F), Color.White),
                        fontSize = 15.sp * hs,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp * hs))
                    Text(
                        text = subtitle,
                        color = AppPalette.adaptive(Color(0xB3000000), Color(0xB3FFFFFF)),
                        fontSize = 12.sp * hs,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(6.dp * hs))

            MiniButton(
                glyph = Glyph.LYRICS,
                iconSize = 21.dp * hs,
                scale = hs,
                modifier = nav(6),
                onClick = onOpen,
            )
            MiniButton(
                glyph = Glyph.QUEUE,
                iconSize = 21.dp * hs,
                scale = hs,
                modifier = nav(7),
                onClick = onOpenQueue,
            )
            MiniButton(
                iconSize = 21.dp * hs,
                scale = hs,
                active = muted,
                modifier = nav(8),
                onClick = {
                    val p = playerVm.player
                    if (p.volume <= 0.001f) {
                        p.volume = lastVolume.coerceAtLeast(0.05f)
                    } else {
                        lastVolume = p.volume
                        p.volume = 0f
                    }
                },
                custom = { tint -> SpeakerIcon(tint, muted, 21.dp * hs) },
            )
        }

        // 细进度线：贴内底边，不与内容抢视觉。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp * hs, end = 24.dp * hs, bottom = 9.dp * hs)
                .fillMaxWidth()
                .height(2.dp * hs)
                .clip(RoundedCornerShape(1.dp * hs))
                .background(AppPalette.adaptive(Color(0x22000000), Color(0x33FFFFFF))),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(AppPalette.adaptive(Color(0xCC000000), Color(0xE6FFFFFF))),
            )
        }
    }
}

@Composable
private fun Artwork(song: Song, scale: Float) {
    Box(
        Modifier
            .size(46.dp * scale)
            .clip(RoundedCornerShape(8.dp * scale))
            .background(AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF2A2A2E))),
    ) {
        val url = song.artworkUrl(160)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 一枚圆钮。[active] 用品牌红标出「已开启」的开关（随机 / 循环 / 静音）。
 * 传 [custom] 时用自定义画法（音量图标没有现成的矢量资源）。
 * [scale] 跟着胶囊高度走，缩小时按钮不能还是 40dp —— 会顶破矮胶囊。
 */
@Composable
private fun MiniButton(
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    iconSize: Dp = 22.dp,
    scale: Float = 1f,
    big: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
    custom: (@Composable (Color) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) AppPalette.adaptive(Color(0x14000000), Color(0x26FFFFFF)) else Color.Transparent,
        tween(140), label = "miniBg",
    )
    val tint = when {
        active -> AppPalette.accent
        focused -> AppPalette.adaptive(Color(0xFF1D1D1F), Color.White)
        else -> AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE6E6E6))
    }
    Box(
        modifier = modifier
            .size((if (big) 48.dp else 40.dp) * scale)
            .clip(CircleShape)
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            custom != null -> custom(tint)
            glyph != null -> Icon(glyph, size = iconSize, color = tint)
        }
    }
}

/** 音量图标（喇叭 + 两道弧）。静音时画一条斜杠。 */
@Composable
private fun SpeakerIcon(tint: Color, muted: Boolean, iconSize: Dp = 21.dp) {
    Canvas(Modifier.size(iconSize)) {
        val u = size.minDimension / 24f
        val w = 1.9f * u
        val body = Path().apply {
            moveTo(3.4f * u, 9.4f * u)
            lineTo(7.0f * u, 9.4f * u)
            lineTo(11.4f * u, 5.0f * u)
            lineTo(11.4f * u, 19.0f * u)
            lineTo(7.0f * u, 14.6f * u)
            lineTo(3.4f * u, 14.6f * u)
            close()
        }
        drawPath(body, tint)
        if (!muted) {
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(8.6f * u, 7.4f * u),
                size = androidx.compose.ui.geometry.Size(7.0f * u, 9.2f * u),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
            drawArc(
                color = tint,
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(8.2f * u, 4.0f * u),
                size = androidx.compose.ui.geometry.Size(12.6f * u, 16.0f * u),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        } else {
            drawLine(
                color = tint,
                start = Offset(15.0f * u, 8.0f * u),
                end = Offset(21.0f * u, 16.0f * u),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    }
}
