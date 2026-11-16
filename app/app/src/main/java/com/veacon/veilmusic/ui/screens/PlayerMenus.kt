package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import com.veacon.veilmusic.ui.components.Glyph
import com.veacon.veilmusic.ui.components.FadingAnchoredPopup
import com.veacon.veilmusic.ui.components.menuInitialFocus
import com.veacon.veilmusic.ui.viewmodel.PlaybackOrder

/** Shared by the existing artist/album menu and the new lyrics menu. */
@Composable
internal fun PlayerBubbleMenu(visibility: MutableTransitionState<Boolean>, onDismiss: () -> Unit,
    initialFocus: FocusRequester? = null, focusAvailable: Boolean = true,
    openingMode: InputMode? = null,
    restoreFocusOnDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
                (anchorBounds.left + (anchorBounds.width-popupContentSize.width)/2)
                    .coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top-popupContentSize.height).coerceAtLeast(0))
        }
    }
    // V84 上游：菜单初始焦点走原生 Popup 窗口焦点监听，并显式传入打开方式。
    FadingAnchoredPopup(visibleState=visibility,popupPositionProvider=position,onDismissRequest=onDismiss,restoreFocusOnDismiss=restoreFocusOnDismiss) {
        Column(Modifier.menuInitialFocus(initialFocus, visibility.targetState, focusAvailable, useNativePopupFocus = true, openingMode = openingMode),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Column(Modifier.width(230.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp),content=content)
            Canvas(Modifier.size(16.dp,8.dp)) {
                drawPath(Path().apply {moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width/2f,size.height);close()},Color.White)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun LyricsOptionsButton(hidden: Boolean, expanded: Boolean, onExpandedChange: (Boolean)->Unit,
    onToggleLyrics: ()->Unit, onFullScreen: ()->Unit, visualScale: Float=1f) {
    val visibility=remember {MutableTransitionState(false)}
    visibility.targetState=expanded
    val firstFocus=remember {FocusRequester()}
    val fullFocus=remember {FocusRequester()}
    // V84 上游：检测菜单打开方式（遥控器 OK/Enter/菜单键 vs 触摸）——遥控器打开时
    // 给当前项画白底高亮（焦点链路建立前的视觉反馈）；进全屏歌词后关闭弹窗不再恢复焦点。
    var openingMode by remember { mutableStateOf<InputMode?>(null) }
    var remoteSelection by remember { mutableIntStateOf(0) }
    var restoreFocus by remember { mutableStateOf(true) }
    Box(Modifier.size(36.dp)) {
        PlayerAccessoryButton(Glyph.LYRICS,tr(R.string.msg_140),selected=false,visualScale=visualScale,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu)) {
                    openingMode = InputMode.Keyboard
                    remoteSelection = 0
                    restoreFocus = true
                }
                false
            }.pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); openingMode = InputMode.Touch; restoreFocus = true }
            },
            onContextMenu={onExpandedChange(true)}) {onExpandedChange(!expanded)}
        PlayerBubbleMenu(visibility,onDismiss={onExpandedChange(false)},initialFocus=firstFocus, openingMode=openingMode, restoreFocusOnDismiss=restoreFocus) {
            NpMenuItem(if(hidden) tr(R.string.msg_141) else tr(R.string.msg_142),Modifier.focusRequester(firstFocus).onFocusChanged {
                if (it.isFocused) remoteSelection = 0
            }.focusProperties {down=fullFocus},
                selectedByRemote = openingMode == InputMode.Keyboard && remoteSelection == 0,
                icon=Glyph.LYRICS,iconAlpha=if(hidden) 1f else 0.5f) {onExpandedChange(false);onToggleLyrics()}
            NpMenuItem(tr(R.string.msg_143),Modifier.focusRequester(fullFocus).onFocusChanged {
                if (it.isFocused) remoteSelection = 1
            }.focusProperties {up=firstFocus},
                selectedByRemote = openingMode == InputMode.Keyboard && remoteSelection == 1,
                icon=Glyph.FULLSCREEN) {restoreFocus=false;onExpandedChange(false);onFullScreen()}
        }
    }
}

@Composable
internal fun PlaybackOrderButton(order: PlaybackOrder, visualScale: Float=1f, onClick: ()->Unit) {
    val glyph=when(order) {
        PlaybackOrder.Shuffle -> Glyph.SHUFFLE
        PlaybackOrder.RepeatOne -> Glyph.REPEAT_ONE
        else -> Glyph.REPEAT
    }
    PlayerAccessoryButton(glyph,tr(R.string.msg_144, order.label),order!=PlaybackOrder.Sequential,
        visualScale=visualScale,onClick=onClick)
}

@Composable
internal fun PlayerFavoriteButton(enabled: Boolean=true, visualScale: Float=1f, onAdd: ()->Unit) {
    PlayerAccessoryButton(Glyph.STAR,tr(R.string.msg_145),visualScale=visualScale) {if(enabled) onAdd()}
}
