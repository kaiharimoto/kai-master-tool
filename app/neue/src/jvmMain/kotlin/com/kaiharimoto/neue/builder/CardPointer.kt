package com.kaiharimoto.neue.builder

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec

/** Two presses this close together, on the same card, are a double-click. */
private const val DOUBLE_CLICK_MS = 350L

/**
 * Everything a mouse can do to a card, on one modifier.
 *
 * - Hover tells the inspector what to show.
 * - A press selects at once — no waiting out the double-click timeout, which
 *   is what `combinedClickable` does and what makes a click feel late.
 * - A second press within [DOUBLE_CLICK_MS] is the double-click; Shift is
 *   passed through (Shift + double-click sends a card to the side deck).
 * - A right press opens [menu] where the pointer is.
 * - A primary drag past the slop picks the card up. Only the primary button
 *   drags: a right-drag is somebody reaching for the menu.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.cardPointer(
    card: Card,
    neue: NeueState,
    drag: NeueDrag,
    from: DeckSection?,
    index: Int,
    onSelect: () -> Unit,
    onDouble: (shift: Boolean) -> Unit,
    menu: () -> List<MenuEntry>,
    dragEnabled: Boolean = true,
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val last = remember { longArrayOf(0L) }
    val select by rememberUpdatedState(onSelect)
    val double by rememberUpdatedState(onDouble)
    val entries by rememberUpdatedState(menu)
    val heldIndex by rememberUpdatedState(index)
    val heldCard by rememberUpdatedState(card)

    return this
        .onGloballyPositioned {
            origin = it.positionInWindow()
            size = it.size
        }
        .pointerHoverIcon(PointerIcon.Hand)
        .onPointerEvent(PointerEventType.Enter) { neue.hovered = heldCard }
        .onPointerEvent(PointerEventType.Exit) { if (neue.hovered == heldCard) neue.hovered = null }
        .onPointerEvent(PointerEventType.Press) { event ->
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            when {
                event.buttons.isSecondaryPressed -> {
                    select()
                    neue.menu = MenuSpec(origin + change.position, entries())
                }
                event.buttons.isPrimaryPressed -> {
                    val now = change.uptimeMillis
                    if (now - last[0] < DOUBLE_CLICK_MS) {
                        last[0] = 0L
                        double(event.keyboardModifiers.isShiftPressed)
                    } else {
                        last[0] = now
                        select()
                    }
                }
            }
        }
        .pointerInput(dragEnabled, from) {
            if (!dragEnabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                var started = false
                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                    change.consume()
                    started = true
                    drag.start(Held(heldCard, from, heldIndex, size), origin + change.position)
                }
                if (slop == null || !started) return@awaitEachGesture
                val completed = drag(slop.id) { change ->
                    change.consume()
                    drag.moveTo(origin + change.position)
                }
                if (completed) drag.drop() else drag.cancel()
            }
        }
}
