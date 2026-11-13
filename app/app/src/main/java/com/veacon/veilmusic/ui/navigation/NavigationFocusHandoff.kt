package com.veacon.veilmusic.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.navigation.NavController
import androidx.navigation.NavHostController

/** Reserve content focus synchronously with navigation, before outgoing nodes disappear. */
@Composable
internal fun rememberNavigationFocusHandoff(nav: NavHostController, returns: NavigationReturnState): Boolean {
    val mode = LocalInputModeManager.current
    DisposableEffect(nav, returns, mode) {
        val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
            val entry = controller.currentBackStackEntry
            val detail = destination.route in setOf(Screen.AlbumDetail.route, Screen.PlaylistDetail.route, Screen.ArtistDetail.route)
            if (detail && entry != null && !returns.restoring && mode.inputMode != InputMode.Touch &&
                entry.savedStateHandle.get<Boolean>("detail.playEntryHandled") != true) {
                returns.pendingDetailFocus = entry.id
            } else if (returns.pendingDetailFocus != entry?.id) {
                returns.pendingDetailFocus = null
            }
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    return returns.contentFocusHandoff
}

/** Remote Back on a detail must reach its BackHandler without an intermediate focus exit. */
internal fun Modifier.contentFocusExitPolicy(
    isOnNowPlaying: Boolean,
    browsingRoot: Boolean,
    tab: FocusRequester,
    /** 底部版本行：内容区向下走到头时先落到它（再由它交给胶囊）。null＝没有版本行。 */
    footer: FocusRequester? = null,
    /** 底部迷你胶囊：版本行之后用它。null＝胶囊没开。 */
    capsule: FocusRequester? = null,
): Modifier = focusProperties {
    onExit = {
        when {
            // 内容区向下无路可走：先落到底部版本行（光标能停在最底部看信息），
            // 没有版本行时才直接交给胶囊。左右方向保持页面内行为。
            !isOnNowPlaying && requestedFocusDirection == FocusDirection.Down ->
                (footer ?: capsule)?.requestFocus()
            requestedFocusDirection == FocusDirection.Up && !isOnNowPlaying ||
                requestedFocusDirection == FocusDirection.Exit && browsingRoot -> tab.requestFocus()
            requestedFocusDirection == FocusDirection.Exit && !isOnNowPlaying -> cancelFocusChange()
        }
    }
}
