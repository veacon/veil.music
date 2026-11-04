package com.veil.music.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * 顶部导航栏里「当前那一项」的焦点请求器。
 *
 * 为什么需要它：Compose 的焦点「向上」是一条几何搜索 —— 只在目标中心点上方、且横向有重叠的
 * 可聚焦元素里找。顶部导航是一枚居中的胶囊（横向大约只占中间一半），而页面内容的第一行
 * 通常从最左边开始排，两者的 x 完全不重叠。于是焦点停在第一行时按上键，搜索找不到任何
 * 目标，就地卡住 —— 这就是「内容区回不到最顶」。
 *
 * 页面把第一行可聚焦节点的 `up` 显式指向这里，等于给「回到顶部导航」补了一条不受几何
 * 位置限制的通道。
 *
 * 注意它绑的是**当前 tab**而不是「发现」：以前固定绑第一个，按上键会把页面一起切回发现页，
 * 用户只是想移到导航栏、不想离开当前这一页。
 */
val LocalNavBarFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * 顶栏此刻是否持有焦点。
 *
 * 页面在「自动把焦点抢到自己的第一个控件」之前必须先看它。用遥控器左右键从顶栏切到
 * 「正在播放」时，播放页会在首帧把焦点抓到播放/暂停按钮上 —— 焦点一离开顶栏，用户
 * 就再也按不到右边的「设置」了。顶栏持焦期间，页面不许抢焦点。
 */
val LocalNavBarHasFocus = staticCompositionLocalOf { mutableStateOf(false) }
