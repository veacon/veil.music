package com.veacon.veilmusic.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶部导航栏实际占用的高度（Bar 本体 70dp：18 上边距 + 40 内容 + 2 下边距 + 内边距），
 * 再加 26dp 的呼吸边距。所有滚动页统一用这个值，避免各页各写一个 100.dp。
 */
val TopNavInset: Dp = 96.dp

/**
 * 页面底部留白。
 *
 * 主区域是一个没有固定底栏的界面：底部只有「浮层」（迷你播放胶囊）。
 * 这里预留的是「最底一行不至于贴到屏幕边缘」的基础留白，真正的胶囊高度由
 * [LocalMiniCapsuleInset] 额外叠加 —— 胶囊浮在内容之上，但并不挤占视口，
 * 只是让可滚动内容多留一点底，最后一行能滚到胶囊上方。
 */
val BottomNavInset: Dp = 32.dp

/**
 * 当前浮层（迷你播放胶囊）遮住的高度；胶囊关闭时为 0。
 *
 * 由 AppShell 在导航内容外层下发。用它来做「内容底部内边距」，
 * 而不是给整个 NavHost 加 padding —— 后者会在胶囊上方留出一条同色空白带，
 * 视觉上像多了一个栏，很割裂。
 */
val LocalMiniCapsuleInset = compositionLocalOf { 0.dp }

/**
 * 迷你播放胶囊的焦点入口（AppShell 下发）；胶囊关闭 / 无歌时为 null。
 *
 * 资料库侧栏走到最后一项（「艺人」）按 Down 时直接落到胶囊 —— 这是层级一
 * 的下边界出口；胶囊没开时调用方应把 Down 吞掉停在原地，不要让焦点乱跳。
 */
val LocalMiniCapsuleFocus = compositionLocalOf<androidx.compose.ui.focus.FocusRequester?> { null }

/**
 * 版本行自己的焦点入口：内容区走到最底时先落到它（[AppVersionFooter] 挂上这个 requester），
 * 再由版本行的 down 交给迷你胶囊 —— 让「内容→版本行→胶囊」这条链路真正成立。
 */
val LocalVersionFooterFocus = compositionLocalOf<androidx.compose.ui.focus.FocusRequester?> { null }

/**
 * 把顶部导航占用的高度放进「接口」而不是「内容」里。
 *
 * 写成 `contentPadding = PaddingValues(top = 100.dp)` 时，100.dp 会算进
 * LazyColumn 的接口，Compose 的 bring-into-view 在遥控器聚焦时会把当前项
 * 滚到接口顶端 y=0，于是卡片上半截被顶部导航压住。
 * 所以页面用「padding 在 verticalScroll 之外」的写法，或者用这个统一扩展：
 * `fillMaxSize().navAwareViewport()`。
 *
 * 注意要放在 `fillMaxSize()` **之后**，padding 才会缩小可见接口而不是推内容。
 */
@Composable
fun Modifier.navAwareViewport(bottom: Dp = BottomNavInset): Modifier =
    this.padding(top = TopNavInset, bottom = bottom)

/**
 * 给列表 / 网格的 `contentPadding` 用的底部值：基础留白 + 胶囊高度。
 * 这样胶囊浮在上面时，最后一行仍能滚出来，且页面背景是连续的一整块。
 */
@Composable
fun capsuleAwareBottom(base: Dp): Dp = base + LocalMiniCapsuleInset.current

