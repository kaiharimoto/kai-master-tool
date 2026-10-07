package com.kaiharimoto.neue.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A control's name in the browser (the Lounge): the desk's tip, by hand, because the desktop's TooltipArea is
 * not on the web. The same 300 ms hover and the same places — under the control, clear of where the caption
 * would be, or above it for a control at the bottom of the window — and gone on a press or when the pointer
 * leaves.
 */
@Composable
internal actual fun PlatformTip(tooltip: @Composable () -> Unit, modifier: Modifier, above: Boolean, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val below = with(density) { 36.dp.roundToPx() }
    val over = with(density) { 8.dp.roundToPx() }
    Box(
        modifier.pointerInput(Unit) {
            coroutineScope {
                var timer: Job? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move ->
                                if (!shown && timer?.isActive != true && event.changes.none { it.pressed }) {
                                    timer = launch {
                                        delay(300)
                                        shown = true
                                    }
                                }
                            PointerEventType.Exit, PointerEventType.Press -> {
                                timer?.cancel()
                                shown = false
                            }
                        }
                    }
                }
            }
        },
    ) {
        content()
        if (shown) {
            Popup(
                popupPositionProvider = remember(above, below, over) { DeskTipPlace(above, below, over) },
                onDismissRequest = { shown = false },
                properties = PopupProperties(focusable = false),
            ) { tooltip() }
        }
    }
}

/** From the control's left edge: [below] under its bottom, or [over] above its top; kept inside the window. */
private class DeskTipPlace(private val above: Boolean, private val below: Int, private val over: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val y = if (above) anchorBounds.top - over - popupContentSize.height else anchorBounds.bottom + below
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable
actual fun ProvideTextMenus(content: @Composable () -> Unit) = content()
