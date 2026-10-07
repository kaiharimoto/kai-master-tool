package com.kaiharimoto.neue.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import com.kaiharimoto.mastertool.core.input.DeskMouse
import com.kaiharimoto.mastertool.core.input.DeskTouch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A control's name for a finger (touch swarm, rec 8). The desk reads a tooltip
 * off a 300 ms hover; a finger cannot hover, and with the tip a no-op every
 * icon-only button on the tablet was unnamed. Android's own idiom for an
 * unlabelled icon is a press held still: the name shows, clear of the finger,
 * the rest of the press is spent so the lift does not also click, and the name
 * stays [DeskTouch.LABEL_LINGER_MS] after the finger leaves. A pen hovering over
 * the control shows it too, as a mouse's hover does.
 *
 * A Popup rather than the window's own layer: the rule that keeps menus out of
 * Popups is the family cursor's, and there is no family cursor on a tablet.
 */
@Composable
internal actual fun PlatformTip(tooltip: @Composable () -> Unit, modifier: Modifier, above: Boolean, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    Box(
        modifier.pointerInput(Unit) {
            coroutineScope {
                var timer: Job? = null
                var linger: Job? = null
                var holding = false
                var from = Offset.Zero
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val finger = change.type == PointerType.Touch || change.type == PointerType.Stylus
                        when (event.type) {
                            PointerEventType.Press -> if (finger && event.changes.count { it.pressed } == 1) {
                                from = change.position
                                timer?.cancel()
                                linger?.cancel()
                                timer = launch {
                                    delay(DeskMouse.HOLD_MS)
                                    holding = true
                                    shown = true
                                }
                            }
                            PointerEventType.Move -> if (change.pressed) {
                                if (!holding && (change.position - from).getDistance() > viewConfiguration.touchSlop) timer?.cancel()
                            } else if (change.type == PointerType.Stylus && !shown) {
                                // A pen hovering: the desk's 300 ms.
                                timer?.cancel()
                                timer = launch {
                                    delay(300)
                                    shown = true
                                }
                            }
                            PointerEventType.Release -> {
                                timer?.cancel()
                                if (holding) {
                                    event.changes.forEach { it.consume() }
                                    holding = false
                                    linger = launch {
                                        delay(DeskTouch.LABEL_LINGER_MS)
                                        shown = false
                                    }
                                }
                            }
                            PointerEventType.Exit -> if (!holding) {
                                timer?.cancel()
                                if (change.type == PointerType.Stylus) shown = false
                            }
                        }
                        // Held: the rest of the press is the name's, so the lift never clicks.
                        if (holding) event.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        content()
        if (shown) {
            Popup(
                popupPositionProvider = remember(above, gap) { ClearOfTheFinger(gap) },
                onDismissRequest = { shown = false },
                properties = PopupProperties(focusable = false),
            ) { tooltip() }
        }
    }
}

/** Above the control when there is room for the tip and the gap, below it otherwise; kept inside the window. */
private class ClearOfTheFinger(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val roomAbove = anchorBounds.top - popupContentSize.height - gap
        val y = if (roomAbove >= 0) roomAbove else (anchorBounds.bottom + gap).coerceAtMost(windowSize.height - popupContentSize.height)
        val centred = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centred.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
actual fun ProvideTextMenus(content: @Composable () -> Unit) = content()
