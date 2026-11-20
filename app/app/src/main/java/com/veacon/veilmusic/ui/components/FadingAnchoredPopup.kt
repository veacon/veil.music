package com.veacon.veilmusic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * No Layout node before Popup: its anchor remains the caller's metadata Box.
 * Only the separate popup window contains an animation layout. Keep the window
 * until the exit finishes, without changing the anchor rectangle or provider.
 *
 * V84 上游修复：
 * - 入场改 [EnterTransition.None]——部分 TV 窗口合成器不会重绘首帧全透明的 Popup，
 *   要等下一次按键才出现（「按了没反应」的根因）；
 * - [restoreFocusOnDismiss] 透传给 ReturnFocusLayer：进全屏歌词等场景关闭弹窗后
 *   不再把焦点送回打开它的控件。
 */
@Composable
fun FadingAnchoredPopup(
    visibleState: MutableTransitionState<Boolean>,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    restoreFocusOnDismiss: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (visibleState.isIdle && !visibleState.currentState) return
    val openingMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    val motion = LocalUiMotion.current
    val interactive = LocalMotionInteractive.current && visibleState.targetState
    com.veacon.veilmusic.ui.navigation.ReturnFocusLayer(restoreFocusOnDismiss) {
    Popup(popupPositionProvider = popupPositionProvider, onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visibleState, modifier = Modifier.motionInput(interactive),
            // Render the popup at full opacity on its first frame. Some TV window
            // compositors do not repaint a zero-alpha popup until the next key event.
            enter = EnterTransition.None,
            exit = fadeOut(tween(if (motion.enabled) motion.exitMs else 0))) {
            CompositionLocalProvider(
                LocalMotionInteractive provides interactive,
                LocalMenuOpeningInputMode provides openingMode,
                // Popup 是独立 window，坐标与 Activity 根上的焦点外圈 overlay 不一致，
                // 关闭上报避免外圈错位。
                LocalFocusFrameEnabled provides false,
            ) { content() }
        }
    }
    }
}
