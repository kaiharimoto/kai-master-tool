package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.input.DeskMouse
import com.kaiharimoto.mastertool.core.input.MouseAction
import com.kaiharimoto.mastertool.core.input.MouseGesture
import com.kaiharimoto.mastertool.core.input.MouseTarget
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.motion.DeskLean
import com.kaiharimoto.mastertool.core.motion.LeanPose
import com.kaiharimoto.neue.NeueState

/**
 * A card being pressed: how far through a hold it is, 0..1, so the card can
 * rise under the button while the hold counts down — the press says what is
 * about to happen before it happens.
 */
class Press {
    var down by mutableStateOf(false)
        internal set
    internal var rise: State<Float>? = null

    /** The lift the press contributes, read in the draw phase. */
    fun pose(): LeanPose = LeanPose(lift = (rise?.value ?: 0f) * DeskLean.HOVER_LIFT * 1.4f)
}

@Composable
fun rememberPress(): Press {
    val press = remember { Press() }
    // Rises over the hold's own length, and settles back at the family's pace.
    press.rise = animateFloatAsState(
        if (press.down) 1f else 0f,
        if (press.down) tween(DeskMouse.HOLD_MS.toInt(), easing = LinearEasing) else tween(120),
        label = "press",
    )
    return press
}

/**
 * Everything a mouse can do to a card, on one modifier, read off `DeskMouse`.
 *
 * - Hover tells the inspector what to show.
 * - A primary press selects at once (never waiting out a double-click), then
 *   becomes one of three things: a release (a click), a move past the slop (a
 *   drag, which lifts the card off the page), or [DeskMouse.HOLD_MS] of
 *   stillness (a hold).
 * - A second press within [DeskMouse.DOUBLE_CLICK_MS] is a double-click.
 * - A secondary press is a right-click, with or without Shift.
 *
 * What each of those *means* is the table's business, not this modifier's:
 * it resolves the gesture against [target] and hands [onAction] the answer,
 * with where it happened in window pixels (a menu opens there).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.cardPointer(
    card: Card,
    neue: NeueState,
    drag: NeueDrag,
    from: DeckSection?,
    index: Int,
    target: MouseTarget,
    press: Press,
    onAction: (MouseAction, Offset) -> Unit,
    dragEnabled: Boolean = true,
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val last = remember { longArrayOf(0L) }
    val act by rememberUpdatedState(onAction)
    val heldIndex by rememberUpdatedState(index)
    val heldCard by rememberUpdatedState(card)
    val on by rememberUpdatedState(target)

    fun fire(gesture: MouseGesture, at: Offset) {
        DeskMouse.resolve(on, gesture)?.let { act(it, origin + at) }
    }

    return this
        .onGloballyPositioned {
            origin = it.positionInWindow()
            size = it.size
        }
        .pointerHoverIcon(PointerIcon.Hand)
        .onPointerEvent(PointerEventType.Enter) { neue.hovered = heldCard }
        .onPointerEvent(PointerEventType.Exit) { if (neue.hovered == heldCard) neue.hovered = null }
        .pointerInput(dragEnabled, from) {
            awaitEachGesture {
                // A press something above has already spent (the click that wakes zen) is not a card's.
                val down = awaitFirstDown(requireUnconsumed = true)
                val buttons = currentEvent.buttons
                val shift = currentEvent.keyboardModifiers.isShiftPressed
                if (buttons.isSecondaryPressed) {
                    down.consume()
                    fire(if (shift) MouseGesture.SHIFT_RIGHT_CLICK else MouseGesture.RIGHT_CLICK, down.position)
                    return@awaitEachGesture
                }
                if (!buttons.isPrimaryPressed) return@awaitEachGesture

                val now = down.uptimeMillis
                if (now - last[0] < DeskMouse.DOUBLE_CLICK_MS) {
                    last[0] = 0L
                    fire(if (shift) MouseGesture.SHIFT_DOUBLE_CLICK else MouseGesture.DOUBLE_CLICK, down.position)
                    return@awaitEachGesture
                }
                last[0] = now
                fire(MouseGesture.CLICK, down.position)

                press.down = true
                try {
                    // Up, a drag, or a hold — whichever comes first.
                    var moved: PointerInputChange? = null
                    var released = false
                    withTimeoutOrNull(DeskMouse.HOLD_MS) {
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                            moved = change
                        }
                        if (slop == null) released = true
                    }
                    val start = moved
                    when {
                        start != null && dragEnabled -> {
                            press.down = false
                            // A drag is not the second half of a double-click.
                            last[0] = 0L
                            drag.start(Held(heldCard, from, heldIndex, size), origin + start.position)
                            val completed = drag(start.id) { change ->
                                change.consume()
                                drag.moveTo(origin + change.position)
                            }
                            if (completed) drag.drop() else drag.cancel()
                        }
                        released || start != null -> Unit
                        else -> {
                            // Held still: the hold fires once, and the rest of the press is spent.
                            last[0] = 0L
                            press.down = false
                            fire(MouseGesture.HOLD, down.position)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } finally {
                    press.down = false
                }
            }
        }
}
