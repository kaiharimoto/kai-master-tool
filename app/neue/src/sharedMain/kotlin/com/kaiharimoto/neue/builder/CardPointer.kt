package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.composed
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
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.core.input.CarryOffset
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.input.TapBurst
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

    /** How long this press's hold is: the desk's, or a finger's from the system (rec 11). */
    var holdMs by mutableStateOf(DeskMouse.HOLD_MS)
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
        if (press.down) tween(press.holdMs.toInt(), easing = LinearEasing) else tween(120),
        label = "press",
    )
    return press
}

/**
 * One surface's taps (touch swarm, rec 11): the pool's grid, or one deck section.
 * A double-tap belongs to the surface, not to the card, so one that drifts onto the
 * neighbour is still a double-tap on the first card — and in the pool each further
 * tap is another add. `TapBurst` has the rules.
 */
class TapSurface(repeats: Boolean) {
    internal val burst = TapBurst(repeats)

    /** The first card's own answer to the burst, run again for its double-tap and repeats. */
    internal var anchor: ((TouchGesture) -> Unit)? = null
}

@Composable
fun rememberTapSurface(repeats: Boolean, key: Any? = Unit): TapSurface = remember(key) { TapSurface(repeats) }

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
    /** The surface whose taps this card's are counted with (rec 11); null counts them on the card alone. */
    taps: TapSurface? = null,
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
    val ownTaps = remember { TapSurface(repeats = target == MouseTarget.POOL) }
    val surface = taps ?: ownTaps

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
                        // Joining a block is felt by a finger (touch swarm, rec 13).
                        if (down.byFinger && zen.blockOf(zenKey).size > group.size) {
                            neue.actingBy(finger = true) { neue.felt(DeskEvent.SNAPPED) }
                        }
                    }
                    return@awaitEachGesture
                }
                // The S Pen's side button is the right-click (touch swarm, rec 29): tested before
                // the finger's grammar, which a pen otherwise takes, so a pen with its button held
                // adds and removes in one press, as a mouse's right button does.
                val penButton = down.byFinger && buttons.isSecondaryPressed
                // A finger (1.3.0): `DeskTouch` rather than `DeskMouse`.
                if (down.byFinger && !penButton) {
                    touch(down, origin, on, dragEnabled, press, surface,
                        onGesture = { gesture, at ->
                            if (gesture == TouchGesture.TAP) neue.hovered = null
                            DeskTouch.resolve(on, gesture, voting)?.let { action ->
                                neue.actingBy(finger = true) { act(action, origin + at) }
                            }
                        },
                        onDrag = { start ->
                            neue.cancelViewSoon()
                            // Picked up after a hold: the card opened large gives way to the drag.
                            neue.viewing = null
                            neue.actingBy(finger = true) { neue.felt(DeskEvent.PICKED_UP) }
                            drag.start(Held(heldCard, from, heldIndex, size, finger = true, density = density), origin + start.position)
                            // A second finger landing lets the card go home: two fingers are a pinch (rec 21).
                            var completed = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) break
                                val change = event.changes.firstOrNull { it.id == start.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    completed = true
                                    break
                                }
                                if (drag.moveTo(origin + change.position)) {
                                    neue.actingBy(finger = true) { neue.felt(DeskEvent.SLOT_CHANGED) }
                                }
                            }
                            if (completed) {
                                neue.actingBy(finger = true) { neue.felt(drag.drop()) }
                            } else {
                                drag.cancel()
                            }
                        },
                    )
                    return@awaitEachGesture
                }
                if (penButton || buttons.isSecondaryPressed && !buttons.isPrimaryPressed) {
                    down.consume()
                    press.down = true
                    try {
                        // Up before the hold, or not.
                        val up = withTimeoutOrNull(DeskMouse.HOLD_MS) {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while ((penButton || event.buttons.isSecondaryPressed) && event.changes.any { it.pressed })
                            true
                        } ?: false
                        press.down = false
                        neue.actingBy(finger = penButton) {
                            when {
                                // A group being drawn up is chosen from, never edited: the pen's row says so.
                                penButton && up -> DeskTouch.resolve(on, TouchGesture.PEN_BUTTON_TAP, voting)?.let { act(it, origin + down.position) }
                                up -> fire(if (shift) MouseGesture.SHIFT_RIGHT_CLICK else MouseGesture.RIGHT_CLICK, down.position)
                                else -> fire(MouseGesture.RIGHT_HOLD, down.position)
                            }
                        }
                        if (!up) spend()
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
                    suspend fun AwaitPointerEventScope.carry(start: PointerInputChange) {
                        drag.start(Held(heldCard, from, heldIndex, size, density = density), origin + start.position)
                        val completed = drag(start.id) { change ->
                            change.consume()
                            drag.moveTo(origin + change.position)
                        }
                        if (completed) drag.drop() else drag.cancel()
                    }
                    when {
                        start != null && dragEnabled -> {
                            press.down = false
                            // A drag is not the second half of a double-click.
                            last[0] = 0L
                            carry(start)
                        }
                        released || start != null -> Unit
                        else -> {
                            // Held still: the hold fires once. Moved after it, the card is picked
                            // up after all and the viewer gives way (1.0.39: a press, a pause and
                            // then a drag opened the viewer and went nowhere); let go, it is spent.
                            last[0] = 0L
                            press.down = false
                            fire(MouseGesture.HOLD, down.position)
                            val late = if (dragEnabled) awaitMoveOrUp(down, viewConfiguration.touchSlop * 2f) else null.also { spend() }
                            if (late != null) {
                                neue.viewing = null
                                carry(late)
                            }
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
 * A finger on a card (`DeskTouch`): up before the hold is a tap — counted with the
 * surface's other taps, so that soon after another it is a double-tap on the first
 * card (`TapBurst`); still for the system's hold is a long press; past the slop is a
 * drag, unless it runs along a scrolling pool, when the finger is the pool's and the
 * card lets it go. A second finger is a pinch, never the card's.
 */
private suspend fun AwaitPointerEventScope.touch(
    down: PointerInputChange,
    origin: Offset,
    target: MouseTarget,
    dragEnabled: Boolean,
    press: Press,
    taps: TapSurface,
    onGesture: (TouchGesture, Offset) -> Unit,
    onDrag: suspend AwaitPointerEventScope.(PointerInputChange) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    // A deck card is picked up only past 12dp: a rolled tap moved cards a slot (rec 12).
    val pickUp = if (target == MouseTarget.DECK) maxOf(slop, CarryOffset.PICKUP_DP * density) else slop
    // The platform's hold, which honours the accessibility "touch and hold delay" (rec 11).
    val hold = DeskTouch.holdMs(viewConfiguration.longPressTimeoutMillis)
    var moved: PointerInputChange? = null
    var upAt = down.uptimeMillis
    press.holdMs = hold
    press.down = true
    val end = try {
        withTimeoutOrNull(hold) {
            var total = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull TouchEnd.CANCEL
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull TouchEnd.CANCEL
                if (!change.pressed) {
                    upAt = change.uptimeMillis
                    return@withTimeoutOrNull TouchEnd.UP
                }
                if (change.isConsumed) return@withTimeoutOrNull TouchEnd.CANCEL
                total += change.positionChange()
                val distance = total.getDistance()
                if (distance > slop && !DeskTouch.picksUp(target, total.x, total.y)) return@withTimeoutOrNull TouchEnd.SCROLL
                if (distance > pickUp) {
                    return@withTimeoutOrNull if (dragEnabled) {
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
            val at = (origin + down.position) / density
            taps.burst.press(down.uptimeMillis, at.x, at.y, 0)
            val result = taps.burst.release(upAt)
            if (result.kind == TapBurst.Kind.TAP) {
                val first: (TouchGesture) -> Unit = { gesture -> onGesture(gesture, down.position) }
                taps.anchor = first
                first(TouchGesture.TAP)
            } else {
                // A double-tap, or in the pool another add: the first card's, wherever this one landed.
                (taps.anchor ?: { gesture -> onGesture(gesture, down.position) })(TouchGesture.DOUBLE_TAP)
            }
        }
        TouchEnd.HOLD -> {
            taps.burst.reset()
            onGesture(TouchGesture.LONG_PRESS, down.position)
            // Held, then moved: picked up after all, Android's own "hold to drag" (1.0.39).
            val late = if (dragEnabled) awaitMoveOrUp(down, maxOf(viewConfiguration.touchSlop, CarryOffset.PICKUP_DP * density)) else null.also { spend() }
            late?.let { onDrag(it) }
        }
        TouchEnd.DRAG -> {
            taps.burst.reset()
            moved?.let { onDrag(it) }
        }
        TouchEnd.SCROLL, TouchEnd.CANCEL -> taps.burst.reset()
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

/**
 * After a hold: the press moved [slop] from where it went down (the change that crossed it),
 * or null once it lets go. Everything it sees is spent, so nothing under it hears the release.
 */
private suspend fun AwaitPointerEventScope.awaitMoveOrUp(down: PointerInputChange, slop: Float): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        event.changes.forEach { it.consume() }
        if (change == null || !change.pressed) {
            if (event.changes.any { it.pressed }) spend()
            return null
        }
        if ((change.position - down.position).getDistance() > slop) return change
    }
}

/** The rest of a press that has already done its one thing: nothing under it hears the release. */
private suspend fun AwaitPointerEventScope.spend() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/**
 * Taps counted with [taps]' surface, for a grid that only reads and adds — the
 * search pop-out's (touch swarm, rec 11). A tap answers at once, where Compose's
 * double-tap detector held every tap back for the double-tap's window; a second tap
 * soon after the first lift, near it, is the first card's double-tap.
 */
fun Modifier.surfaceTaps(taps: TapSurface, onTap: () -> Unit, onDoubleTap: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val double by rememberUpdatedState(onDoubleTap)
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(taps) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val up = waitForUpOrCancellation()
                if (up == null) {
                    taps.burst.reset()
                    return@awaitEachGesture
                }
                val at = (origin + down.position) / density
                taps.burst.press(down.uptimeMillis, at.x, at.y, 0)
                if (taps.burst.release(up.uptimeMillis).kind == TapBurst.Kind.TAP) {
                    val mine = tap
                    val mineDouble = double
                    taps.anchor = { gesture -> if (gesture == TouchGesture.DOUBLE_TAP) mineDouble() else mine() }
                    mine()
                } else {
                    taps.anchor?.invoke(TouchGesture.DOUBLE_TAP)
                }
            }
        }
}
