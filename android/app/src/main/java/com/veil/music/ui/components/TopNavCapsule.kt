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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import com.veil.music.ui.navigation.TopNavTab
import com.veil.music.ui.navigation.LocalNavBarHasFocus
import com.veil.music.ui.components.Glyph
import com.veil.music.ui.components.Icon

/**
 * **顶栏胶囊**（TopNavCapsule）：发现 / 我的 / 搜索 / 设置。
 *
 * 三枚浮动胶囊之一：顶栏胶囊（这一枚，切页）／迷你胶囊（MiniPlayerCapsule，底部播放条）／
 * 调节胶囊（TuningCapsule，播放页长按下键呼出）。三者都画在内容层与播放层之上，
 * 不参与返回栈 —— 见 AppShell 顶部的层级说明。
 *
 * 原本这里第一枚是「正在播放」，显示当前歌名 + EQ 动效，左右键走到它就直接切到
 * 播放页。那一枚的问题在于：它是一枚**导航**胶囊，却要承担「播放状态显示」的职责，
 * 于是切页时经常被顺手带过去、焦点也来回跳。现在这些信息与操作全部搬到屏幕底部的
 * 迷你播放条（见 MiniPlayerCapsule），顶栏只负责四个内容页之间的切换。
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopNavCapsule(
    selected: TopNavTab,
    onSelect: (TopNavTab) -> Unit,
    updateAvailable: Boolean = false,
    /**
     * 页面按上键回到导航栏时落在哪个节点上。
     *
     * 这个 requester 绑的是**当前 tab**，所以从「设置」页按上键会回到「设置」那个胶囊，
     * 不会被顺手切到别的页。早先它固定绑在第一个 tab 上，于是每次按上键页面都会跳回
     * 发现页 —— 用户只是想移动焦点，并不想换页。
     */
    upEntryFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val navBarHasFocus = LocalNavBarHasFocus.current
    // 每个胶囊自己的 requester：左右键在胶囊之间移动时按索引点名，不再依赖几何搜索。
    // 它恒绑在自己的胶囊上（不像 upEntryFocus 会跟着"当前选中项"搬家），所以换页之后可以立刻用它搬焦点。
    val tabFocus = remember { TopNavTab.entries.map { FocusRequester() } }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                // 前台页面要知道顶栏是否持焦：持焦期间不许抢焦点（见 LocalNavBarHasFocus）。
                navBarHasFocus.value = it.hasFocus
            }
            .focusProperties {
                // 从内容区「进入」这一栏时，落点永远是当前 tab —— 这就是"上键回当前层级"。
                enter = { upEntryFocus ?: FocusRequester.Default }
            }
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
                            when {
                                // Settings tab is a gear (with a badge when an update is available).
                                tab == TopNavTab.Settings -> {
                                    Icon(
                                        if (updateAvailable) Glyph.GEAR_BADGE else Glyph.GEAR,
                                        size = 18.dp, color = textColor,
                                    )
                                }
                                else -> {
                                    Text(
                                        text       = tab.label,
                                        fontSize   = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        letterSpacing = if (isSelected) (-0.1).sp else 0.sp,
                                        color      = textColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
