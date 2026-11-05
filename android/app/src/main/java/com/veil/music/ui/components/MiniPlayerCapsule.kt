package com.veil.music.ui.components

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veil.music.data.model.Song
import com.veil.music.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **迷你胶囊**（MiniPlayerCapsule）—— 底部迷你播放条，按参考截图的布局 1:1 复刻。
 *
 * 三枚浮动胶囊之一：顶栏胶囊（TopNavCapsule，切页）／迷你胶囊（这一枚，播放控制）／
 * 调节胶囊（TuningCapsule，播放页长按下键呼出）。它们都画在内容层与播放层之上，
 * 不参与返回栈 —— 见 AppShell 顶部的层级说明。
 *
 * 从左到右：随机 / 上一首 / 播放暂停 / 下一首 / 循环 ｜ 封面 + 标题 + 副标题 ｜ 歌词 / 队列 / 音量，
 * 最底部压一条细进度线。整条是**一枚圆角胶囊**，贴在屏幕底部居中、离底留一点空隙。
 *
 * 它取代了原来顶栏里的「正在播放」胶囊：那一枚既占着导航栏的位置，又要和页面抢焦点。
 * 现在播放/切歌/看进度都不用跳页，只按「确定」落在中间那块才进完整播放页。
 *
 * 尺寸不是写死的：[heightPct] 缩放整条胶囊的高度（图标、封面、文字等比跟着缩），
 * [widthPct] 只改它占屏幕的宽度。电视从 32 寸到 85 寸都有，同一套固定尺寸在两头都不合适。
 *
 * 焦点：左右键在 9 个可点区之间走，上下键交还给系统做几何搜索（离开这条就是回到上面的内容），
 * 所以在任何页面按两下上键都能正常回到列表。
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

    Box(
        modifier = modifier
            // 宽度：基准是屏宽 94%，再乘用户比例；上限也跟着缩，否则 1920 屏上
            // 1280dp 的封顶会把 70%–100% 这一段全部吃掉，调了等于没调。
            .fillMaxWidth((0.94f * ws).coerceIn(0.4f, 0.94f))
            .widthIn(max = 1280.dp * ws)
            .height(barHeight)
            .clip(RoundedCornerShape(barHeight / 2))
            .background(Color(0xEB1B1B1E))
            .border(
                width = 1.dp,
                color = if (anyFocused) Color(0x4DFFFFFF) else Color(0x14FFFFFF),
                shape = RoundedCornerShape(barHeight / 2),
            ),
    ) {
        // 顶部一层极淡高光，让胶囊看起来是「浮」在内容上的实体而不是一块色带。
        Box(
            Modifier
                .fillMaxWidth()
                .height(38.dp * hs)
                .background(
                    Brush.verticalGradient(0f to Color(0x17FFFFFF), 1f to Color.Transparent)
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
                glyph = if (state.repeatMode == com.veil.music.ui.viewmodel.RepeatMode.One)
                    Glyph.REPEAT_ONE else Glyph.REPEAT,
                iconSize = 20.dp * hs,
                scale = hs,
                active = state.repeatMode != com.veil.music.ui.viewmodel.RepeatMode.Off,
                modifier = nav(4),
                onClick = { playerVm.toggleRepeat() },
            )

            Spacer(Modifier.width(10.dp * hs))

            // 中间：封面 + 两行文字。整块可点 → 进完整播放页。
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
                        color = Color.White,
                        fontSize = 15.sp * hs,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp * hs))
                    Text(
                        text = listOf(song.artistName, song.albumName)
                            .filter { it.isNotBlank() }
                            .joinToString(" - "),
                        color = Color(0xB3FFFFFF),
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
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(Color(0xE6FFFFFF)),
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
            .background(Color(0xFF2A2A2E)),
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
 * [scale] 跟着胶囊高度走，缩小时按钮不能还是 40dp —— 会顶破 45dp 高的胶囊。
 */
@Composable
private fun MiniButton(
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    scale: Float = 1f,
    big: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
    custom: (@Composable (Color) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) Color(0x26FFFFFF) else Color.Transparent, tween(140), label = "miniBg",
    )
    val tint = when {
        active -> Color(0xFFFA233B)
        focused -> Color.White
        else -> Color(0xFFE6E6E6)
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
private fun SpeakerIcon(tint: Color, muted: Boolean, iconSize: androidx.compose.ui.unit.Dp = 21.dp) {
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
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
            drawArc(
                color = tint,
                startAngle = -52f,
                sweepAngle = 104f,
                useCenter = false,
                topLeft = Offset(8.2f * u, 4.0f * u),
                size = androidx.compose.ui.geometry.Size(12.6f * u, 16.0f * u),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        } else {
            drawLine(
                color = tint,
                start = Offset(15.0f * u, 8.0f * u),
                end = Offset(21.0f * u, 16.0f * u),
                strokeWidth = w,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}
