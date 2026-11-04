package com.veil.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import kotlin.math.pow
import com.veil.music.ui.navigation.TopNavTab
import com.veil.music.ui.navigation.LocalNavBarHasFocus
import com.veil.music.ui.components.Glyph
import com.veil.music.ui.components.Icon

/** Beat-reactive 3-bar equalizer beside the Now Playing tab. A gentle per-bar wobble keeps it alive
 *  between beats; the live [BeatAnalyzer] energy punches all three up on each beat (Apple-style). */
@Composable
private fun EqBars(beatAnalyzer: com.veil.music.media.BeatAnalyzer?) {
    val energy by (beatAnalyzer?.energy?.collectAsState() ?: remember { mutableStateOf(0f) })
    // Sensitivity: bass-onset energy peaks low, so lift small values (gamma < 1) and add gain
    // before clamping — a quiet beat should still visibly punch the bars.
    val sens = (energy.coerceIn(0f, 1f).pow(0.55f) * 1.6f).coerceIn(0f, 1f)
    // Snappy attack, graceful fall.
    val punch by animateFloatAsState(sens, tween(55, easing = LinearEasing), label = "eqPunch")
    val inf = rememberInfiniteTransition(label = "eq")
    val w1 by inf.animateFloat(0.30f, 0.70f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "w1")
    val w2 by inf.animateFloat(0.65f, 0.28f, infiniteRepeatable(tween(310, easing = LinearEasing), RepeatMode.Reverse), label = "w2")
    val w3 by inf.animateFloat(0.38f, 0.72f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "w3")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(12.dp)) {
        listOf(w1, w2, w3).forEach { wob ->
            val frac = (wob * 0.28f + punch * 0.9f).coerceIn(0.18f, 1f)
            Box(Modifier.width(2.dp).fillMaxHeight(frac).clip(RoundedCornerShape(1.dp)).background(Color(0xFFFA233B)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopNavBar(
    selected: TopNavTab,
    onSelect: (TopNavTab) -> Unit,
    isPlaying: Boolean = false,
    updateAvailable: Boolean = false,
    beatAnalyzer: com.veil.music.media.BeatAnalyzer? = null,
    /**
     * 页面按上键回到导航栏时落在哪个节点上。
     *
     * 这个 requester 绑的是**当前 tab**，所以从「设置」页按上键会回到「设置」那个胶囊，
     * 不会被顺手切到「发现」。早先它固定绑在第一个 tab 上，于是每次按上键页面都会跳回
     * 发现页 —— 用户只是想移动焦点，并不想换页。
     */
    upEntryFocus: FocusRequester? = null,
    /**
     * 从「正在播放」这枚胶囊按上键时的落点（播放/暂停键）。
     *
     * 不指定的话走几何搜索：胶囊在屏幕正上方居中，而正下方是右侧的播放队列 —— 于是按上键
     * 会直接掉进队列列表的第 28 首，而不是播放键。电视上这几乎是必经操作，所以显式给一条通道。
     */
    downTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val navBarHasFocus = LocalNavBarHasFocus.current
    // 每个胶囊自己的 requester：左右键在胶囊之间移动时按索引点名，不再依赖几何搜索。
    // 它恒绑在自己的胶囊上（不像 upEntryFocus 会跟着"当前选中项"搬家），所以换页之后可以立刻用它搬焦点。
    val tabFocus = remember { TopNavTab.entries.map { FocusRequester() } }
    val scope = rememberCoroutineScope()

    // 「正在播放」页的顶栏 3 秒后自动收起。
    // 之前是焦点一离开顶栏就立刻淡出 —— 从胶囊按下键切到播放键的那一瞬间胶囊就没了，
    // 看起来像顶栏闪了一下。现在给 3 秒：还在找按钮的时候它一直在，之后让位给封面。
    //
    // 如果 3 秒到的时候焦点还停在胶囊上，先把焦点交给播放键再收起 —— 不能把焦点留在一个
    // 已经透明了的控件上（用户会以为遥控器坏了）。焦点一回到顶栏，计时重新开始。
    var autoHidden by remember { mutableStateOf(false) }
    LaunchedEffect(selected, isFocused) {
        if (selected != TopNavTab.NowPlaying) {
            autoHidden = false
            return@LaunchedEffect
        }
        if (isFocused) {
            autoHidden = false
            kotlinx.coroutines.delay(3_000)
            val moved = downTarget?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
            if (moved) autoHidden = true
        } else {
            // 焦点已经在内容区：3 秒后收起（期间按回顶栏会取消这一次）。
            kotlinx.coroutines.delay(3_000)
            if (!isFocused) autoHidden = true
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (selected == TopNavTab.NowPlaying && autoHidden) 0f else 1f,
        animationSpec = tween(500),
        label = "navBarAlpha"
    )

    Box(
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                // 前台页面要知道顶栏是否持焦：持焦期间不许抢焦点（见 LocalNavBarHasFocus）。
                navBarHasFocus.value = it.hasFocus
            }
            .focusProperties {
                // 从内容区「进入」这一栏时，落点永远是当前 tab —— 这就是"上键回当前层级"。
                // 之前固定落第一个 tab，于是每次回到导航栏都会顺手切回发现页。
                enter = { upEntryFocus ?: FocusRequester.Default }
            }
            .graphicsLayer { this.alpha = alpha }
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape  = RoundedCornerShape(50),
            // Translucent material rather than a flat bar — reads as a floating pill.
            colors = SurfaceDefaults.colors(containerColor = Color(0xE61C1C1E)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color(0x14FFFFFF), 0.5f to Color.Transparent))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TopNavTab.entries.forEachIndexed { index, tab ->
                    val isSelected = tab == selected
                    val bgColor by animateColorAsState(
                        if (isSelected) Color.White else Color.Transparent, tween(180))
                    val textColor by animateColorAsState(
                        if (isSelected) Color.Black else Color(0xFF9A9A9A), tween(180))

                    // 左右键在胶囊之间移动的同时直接把页面切过去，不用再按 OK 确认。
                    // 这也是 tvOS / Apple Music 顶栏的行为 —— 方向键走一遍，页面就跟着走一遍。
                    //
                    // 触发点在**按键**上，不在焦点变化上。这样"焦点被动回到顶栏"（内容区节点被销毁、
                    // 系统把焦点塞回第一个可聚焦项）就不会顺带切页，播放页里点队列歌曲也不会再被打回发现页。
                    val tabModifier = Modifier
                        .focusRequester(tabFocus[index])
                        .then(
                            if (tab == selected && upEntryFocus != null) Modifier.focusRequester(upEntryFocus)
                            else Modifier
                        )
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    val step = if (e.key == Key.DirectionLeft) -1 else 1
                                    val target = (index + step)
                                        .coerceIn(0, TopNavTab.entries.lastIndex)
                                    if (target != index) {
                                        onSelect(TopNavTab.entries[target])
                                        // 下一帧把焦点搬到新胶囊上（tabFocus 恒绑在自己那一枚上，不用等重组）。
                                        scope.launch {
                                            kotlinx.coroutines.delay(16)
                                            runCatching { tabFocus[target].requestFocus() }
                                        }
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    // 播放页：上/下键 → 播放/暂停键，而不是几何上更近的队列列表。
                                    if (!isSelected) return@onKeyEvent false
                                    val target = downTarget ?: return@onKeyEvent false
                                    runCatching { target.requestFocus() }.isSuccess
                                }
                                else -> false
                            }
                        }

                    Surface(
                        onClick = { onSelect(tab) },
                        modifier = tabModifier,
                        shape   = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors  = ClickableSurfaceDefaults.colors(
                            containerColor        = bgColor,
                            focusedContainerColor = if (isSelected) Color.White else Color(0xFF2C2C2E),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (tab == TopNavTab.Settings) {
                                // Settings tab is a gear (with a badge when an update is available).
                                Icon(
                                    if (updateAvailable) Glyph.GEAR_BADGE else Glyph.GEAR,
                                    size = 18.dp, color = textColor,
                                )
                            } else {
                                Text(
                                    text       = tab.label,
                                    fontSize   = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    letterSpacing = if (isSelected) (-0.1).sp else 0.sp,
                                    color      = textColor,
                                )
                            }
                            if (tab == TopNavTab.NowPlaying && isPlaying && !isSelected) {
                                EqBars(beatAnalyzer)
                            }
                        }
                    }
                }
            }
        }
    }
}
