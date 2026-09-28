package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.isPrimaryPress
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.input.TouchGesture
import com.kaiharimoto.neue.cursor.cursorPointer
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
import com.kaiharimoto.neue.zen.LocalZen
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.motion.ZenGestures
import androidx.compose.ui.input.pointer.positionChange

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
 * - In deep zen a primary press on a card with a [zenKey] is none of these: it
 *   carries the card, freely, and puts it down where it is let go.
 * - A secondary press is a right-click if it comes up before [DeskMouse.HOLD_MS]
 *   and a right-hold if it does not — so a right-click fires on release, the
 *   one moment it is known not to be a hold. Shift is read at the press.
 *
 * The press is found by [awaitAnyDown], not by Compose's `awaitFirstDown`:
 * that one answers only to the primary button, and every right-click in 1.0.3
 * to 1.0.8 went past the card as though it were not there.
 *
 * What each of those *means* is the table's business, not this modifier's:
 * it resolves the gesture against [target] and hands [onAction] the answer,
 * with where it happened in window pixels.
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
    /** The card's name in zen's arrangement, for a card that may be moved freely in deep zen. */
    zenKey: Int? = null,
    /** A group is being drawn up: a finger's double-tap on the deck is two votes, never a removal (touch swarm, rec 7). */
    drafting: Boolean = false,
): Modifier {
    val zen = LocalZen.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val last = remember { longArrayOf(0L) }
    val act by rememberUpdatedState(onAction)
    val heldIndex by rememberUpdatedState(index)
    val heldCard by rememberUpdatedState(card)
    val on by rememberUpdatedState(target)
    val voting by rememberUpdatedState(drafting)

    fun fire(gesture: MouseGesture, at: Offset) {
        DeskMouse.resolve(on, gesture)?.let { act(it, origin + at) }
    }

    return this
        .onGloballyPositioned {
            origin = it.positionInWindow()
            size = it.size
        }
        // A click selects; in deep zen a press carries the card instead.
        // In deep zen the cursor stays on the card it is carrying: hovers from the cards it
        // passes over snapped the frame back and forth between them (the jitter kai saw).
        .cursorPointer(
            caption = when {
                zenKey == null || neue.zen != ZenPhase.DEEP -> "Select"
                zenKey in zen.selection && zen.selection.size > 1 -> "Move ${zen.selection.size}"
                else -> "Move"
            },
            emphasis = true,
            holdOnPress = zenKey != null && neue.zen == ZenPhase.DEEP,
        )
        .onPointer(PointerEventType.Enter) { neue.hovered = heldCard }
        .onPointer(PointerEventType.Exit) { if (neue.hovered == heldCard) neue.hovered = null }
        .pointerInput(dragEnabled, from) {
            awaitEachGesture {
                // A press something above has already spent (the click that wakes zen) is not a card's.
                val down = awaitAnyDown()
                val buttons = currentEvent.buttons
                val shift = currentEvent.keyboardModifiers.isShiftPressed
                // Deep zen: the cards are the garden. A press picks one up — or every card
                // picked out with it — and puts it down wherever it is let go; nothing
                // about the deck changes (1.0.14 for the picking out: see ZenGestures).
                if (zenKey != null && neue.zen == ZenPhase.DEEP) {
                    down.consume()
                    if (!currentEvent.isPrimaryPress) {
                        spend()
                        return@awaitEachGesture
                    }
                    // A double-click picks out the card's whole block; with Shift, adds it.
                    val now = down.uptimeMillis
                    if (now - last[0] < DeskMouse.DOUBLE_CLICK_MS) {
                        last[0] = 0L
                        zen.selection = ZenGestures.doubleClick(zen.selection, zen.blockOf(zenKey), shift)
                        spend()
                        return@awaitEachGesture
                    }
                    last[0] = now
                    // Past the slop it is a carry; up before it, a click.
                    val crossed = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (crossed == null) {
                        zen.selection = ZenGestures.click(zen.selection, zenKey, shift)
                        return@awaitEachGesture
                    }
                    last[0] = 0L
                    val group = ZenGestures.carried(zen.selection, zenKey, shift)
                    zen.selection = ZenGestures.selectionWhileCarrying(zen.selection, zenKey, shift)
                    zen.carrying = group
                    // The move is drawn at deep × gather, so it is stored divided by it:
                    // the card stays under the pointer while zen is still arriving.
                    fun carry(d: Offset) {
                        val k = (zen.deep * zen.gather).coerceAtLeast(0.2f)
                        zen.moveAll(group, d.x / k, d.y / k)
                    }
                    try {
                        // The slop itself is part of the move: the card was under the pointer when it was pressed.
                        carry(crossed.position - down.position)
                        drag(crossed.id) { change ->
                            val d = change.positionChange()
                            change.consume()
                            carry(d)
                        }
                    } finally {
                        zen.carrying = emptySet()
                        // Let go: back into their slots, flush beside another card, or where they are.
                        zen.dropAll(group, zenKey)
                    }
                    return@awaitEachGesture
                }
                // A finger (1.3.0): `DeskTouch` rather than `DeskMouse`.
                if (down.byFinger) {
                    touch(down, on, dragEnabled, press, last,
                        onGesture = { gesture, at ->
                            if (gesture == TouchGesture.TAP) neue.hovered = null
                            DeskTouch.resolve(on, gesture, voting)?.let { act(it, origin + at) }
                        },
                        onDrag = { start ->
                            drag.start(Held(heldCard, from, heldIndex, size), origin + start.position)
                            val completed = drag(start.id) { change ->
                                change.consume()
                                drag.moveTo(origin + change.position)
                            }
                            if (completed) drag.drop() else drag.cancel()
                        },
                    )
                    return@awaitEachGesture
                }
                if (buttons.isSecondaryPressed && !buttons.isPrimaryPressed) {
                    down.consume()
                    press.down = true
                    try {
                        // Up before the hold, or not.
                        val up = withTimeoutOrNull(DeskMouse.HOLD_MS) {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.buttons.isSecondaryPressed && event.changes.any { it.pressed })
                            true
                        } ?: false
                        press.down = false
                        if (up) {
                            fire(if (shift) MouseGesture.SHIFT_RIGHT_CLICK else MouseGesture.RIGHT_CLICK, down.position)
                        } else {
                            fire(MouseGesture.RIGHT_HOLD, down.position)
                            spend()
                        }
                    } finally {
                        press.down = false
                    }
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
                            spend()
                        }
                    }
                } finally {
                    press.down = false
                }
            }
        }
}

/** How a finger's press ended, before it became a drag. */
private enum class TouchEnd { UP, HOLD, DRAG, SCROLL, CANCEL }

/**
 * A finger on a card (`DeskTouch`): up before the hold is a tap — or, soon after
 * another, a double-tap; still for [DeskMouse.HOLD_MS] is a long press; past the
 * slop is a drag, unless it runs along a scrolling pool, when the finger is the
 * pool's and the card lets it go. A second finger is a pinch, never the card's.
 */
private suspend fun AwaitPointerEventScope.touch(
    down: PointerInputChange,
    target: MouseTarget,
    dragEnabled: Boolean,
    press: Press,
    last: LongArray,
    onGesture: (TouchGesture, Offset) -> Unit,
    onDrag: suspend AwaitPointerEventScope.(PointerInputChange) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    var moved: PointerInputChange? = null
    press.down = true
    val end = try {
        withTimeoutOrNull(DeskMouse.HOLD_MS) {
            var total = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull TouchEnd.CANCEL
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull TouchEnd.CANCEL
                if (!change.pressed) return@withTimeoutOrNull TouchEnd.UP
                if (change.isConsumed) return@withTimeoutOrNull TouchEnd.CANCEL
                total += change.positionChange()
                if (total.getDistance() > slop) {
                    return@withTimeoutOrNull if (dragEnabled && DeskTouch.picksUp(target, total.x, total.y)) {
                        change.consume()
                        moved = change
                        TouchEnd.DRAG
                    } else {
                        TouchEnd.SCROLL
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            TouchEnd.CANCEL
        } ?: TouchEnd.HOLD
    } finally {
        press.down = false
    }
    when (end) {
        TouchEnd.UP -> {
            val now = down.uptimeMillis
            if (now - last[0] < DeskTouch.DOUBLE_TAP_MS) {
                last[0] = 0L
                onGesture(TouchGesture.DOUBLE_TAP, down.position)
            } else {
                last[0] = now
                onGesture(TouchGesture.TAP, down.position)
            }
        }
        TouchEnd.HOLD -> {
            last[0] = 0L
            onGesture(TouchGesture.LONG_PRESS, down.position)
            spend()
        }
        TouchEnd.DRAG -> {
            last[0] = 0L
            moved?.let { onDrag(it) }
        }
        TouchEnd.SCROLL, TouchEnd.CANCEL -> last[0] = 0L
    }
}

/**
 * The next press of any button that nothing above has spent (the click that
 * wakes zen is spent on waking).
 */
private suspend fun AwaitPointerEventScope.awaitAnyDown(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && event.changes.isNotEmpty() && event.changes.all { it.changedToDown() }) {
            return event.changes[0]
        }
    }
}

/** The rest of a press that has already done its one thing: nothing under it hears the release. */
private suspend fun AwaitPointerEventScope.spend() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
