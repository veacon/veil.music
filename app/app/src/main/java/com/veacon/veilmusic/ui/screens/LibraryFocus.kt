package com.veacon.veilmusic.ui.screens

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/** Explicit sort requests must bypass the page's usual automatic list entry. */
internal class LibrarySortFocusRoute(val control: FocusRequester = FocusRequester()) {
    var requestingSort = false
        private set
    fun request(): Boolean {
        requestingSort = true
        return try { runCatching {control.requestFocus()}.getOrDefault(false) } finally { requestingSort = false }
    }
}

/**
 * Right from a sidebar row must land in the list, never on the sort control in the top-right corner.
 * Like [LibrarySortFocusRoute] it has to bypass the page's automatic entry redirect — otherwise the
 * incoming `onEnter` would bounce focus straight back to the sidebar and Right would look dead.
 */
internal class LibraryContentEntryRoute(val control: FocusRequester = FocusRequester()) {
    var requestingEntry = false
        private set
    fun request(): Boolean {
        requestingEntry = true
        return try { runCatching {control.requestFocus()}.getOrDefault(false) } finally { requestingEntry = false }
    }
}

/**
 * Entering the page (Down from the tab bar) hands focus to the matching left-sidebar row instead of
 * diving into the right-hand list — the sidebar is the page's natural starting point, and from there
 * Down walks the sections (selecting as it goes, exactly like the tab bar) while Right enters the list.
 *
 * [onEnterRequest] is invoked rather than called inline because the sidebar is a sibling of this
 * focus group; the request is posted so the incoming focus transition can finish first.
 */
internal fun Modifier.libraryEntryFocus(onEnterRequest: () -> Unit, restoring: Boolean,
    sort: LibrarySortFocusRoute, entry: LibraryContentEntryRoute? = null): Modifier = focusProperties {
    onEnter = { if (!restoring && !sort.requestingSort && entry?.requestingEntry != true) onEnterRequest() }
}

internal val LocalLibrarySortFocus = compositionLocalOf<LibrarySortFocusRoute?> {null}

/** The first focusable row/card of the mounted section — what Right from the sidebar aims at. */
internal val LocalLibraryContentEntryFocus = compositionLocalOf<FocusRequester?> {null}

/** Linear lists use Right; grid items opt in only at their right edge. */
@Composable
internal fun Modifier.librarySortOnRight(enabled: Boolean=true): Modifier {
    val route=LocalLibrarySortFocus.current
    return onPreviewKeyEvent {event ->
        enabled && route!=null && event.type==KeyEventType.KeyDown && event.key==Key.DirectionRight && route.request()
    }
}
