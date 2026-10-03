package com.kaiharimoto.neue.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.layout.EdgeReveal
import com.kaiharimoto.mastertool.core.motion.ZenCorner
import com.kaiharimoto.mastertool.core.motion.ZenGestures
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.motion.ZenPick
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.ZEN_GAP_MAX
import com.kaiharimoto.neue.ZEN_GAP_MIN
import com.kaiharimoto.neue.builder.IMMERSIVE_TOP
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.isPrimaryPress
import com.kaiharimoto.neue.run
import com.kaiharimoto.neue.theme.MuShell

/**
 * The window's pointer watcher, lifted out of `Shell` as it was: the family cursor, waking from zen, deep zen's box
 * and wheel, the folding bars (`EdgeReveal`), a finger's taps on the strips, and two- and three-finger undo and redo.
 * [measured] is how tall the folded bars were last laid out.
 */
internal fun Modifier.windowPointer(h: NeueHolders, neue: NeueState, state: DeckBuilderState, measured: FoldedBars): Modifier =
    pointerInput(Unit) {
        // One watcher over the whole window, on the way down, consuming
        // nothing: every bar that folds away comes out from here.
        awaitPointerEventScope {
            var still = Offset.Unspecified
            // A box being dragged over the table in deep zen: where it started, and what
            // was picked out before it when Shift added to that.
            var boxFrom: Offset? = null
            var boxBase = emptySet<Int>()
            var boxShift = false
            // A finger's press, for telling a tap from a swipe (touch swarm, rec 3).
            var fingerFrom: Offset? = null
            var fingerAt = 0L
            // Every finger of the gesture in hand: when each went down and came up, and how
            // far the furthest travelled — two together are undo, three redo (rec 22).
            val tapDowns = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Pair<Long, Offset>>()
            val tapUps = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Long>()
            var tapTravel = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val at = event.changes.firstOrNull()?.position
                val gone = event.type == PointerEventType.Exit
                // The family cursor is a mouse's: a finger (or a tablet, where the system
                // draws its own pointer) leaves it hidden.
                val mouse = event.changes.none { it.byFinger } && !neue.touchFirst
                h.cursor.moved(if (gone || !mouse) null else at, mouse && event.buttons.areAnyPressed)
                // Where the pointer is, for the deck to turn toward in zen.
                if (neue.immersive) h.zen.pointer = if (gone) null else at
                // Waking. Before zen is deep, any real movement, press or scroll brings
                // the builder back (a pointer that twitches a pixel on a desk does not).
                // Once it is deep, nothing the pointer does wakes it — the pointer is for
                // arranging the cards, which are the garden — and only a key ends it.
                val deepZen = neue.zen == ZenPhase.DEEP
                // The groups' palettes stay out while they are tried, and fold at a press
                // off the Groups panel (1.0.24). Consuming nothing, so the press still does
                // what it was for. An overlay's press is the overlay's.
                if (event.type == PointerEventType.Press && neue.groupPalettesOpen && !neue.overlayOpen && at != null) {
                    val panel = neue.groupsPanel
                    if (panel == null || !panel.contains(at)) neue.groupPalettesOpen = false
                }
                when (event.type) {
                    PointerEventType.Press -> if (!deepZen) h.wake()
                    // In deep zen the wheel opens and closes the gaps between the groups
                    // (kai, 1.0.17); up past closed opens them, and zen re-fits the cards.
                    PointerEventType.Scroll -> if (!deepZen) {
                        h.wake()
                    } else {
                        val d = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                        val step = if (d.y != 0f) d.y else d.x
                        if (step != 0f && state.groups.groups.isNotEmpty()) {
                            if (!h.zen.groups && step < 0f) {
                                h.zen.groups = true
                            } else if (h.zen.groups) {
                                h.zen.gapScale = (h.zen.gapScale - step * 0.15f).coerceIn(ZEN_GAP_MIN, ZEN_GAP_MAX)
                            }
                        }
                    }
                    PointerEventType.Move -> if (at != null && !deepZen) {
                        if (!still.isSpecified || (at - still).getDistance() > 3f) {
                            still = at
                            h.wake()
                        }
                    }
                }
                // The corner where "put the cards back" comes out.
                // On a touch screen nothing can reach for the corner, so in deep zen it stays out.
                h.zen.corner = deepZen && (neue.touchFirst || at != null && !gone &&
                    ZenCorner.reaches(at.x, at.y, size.width.toFloat(), size.height.toFloat(), h.zen.cornerRow))
                // Deep zen, 1.0.14: a press on the table rather than on a card draws a box,
                // and the cards it touches are picked out to move together (ZenGestures).
                // The press is spent here, on the way down, so the pool and the inspector
                // — faded out, not gone — never hear it.
                val zen = h.zen
                val from = boxFrom
                when {
                    from == null && deepZen && event.type == PointerEventType.Press && at != null &&
                        event.isPrimaryPress && zen.deck.width > 0f &&
                        !zen.corner && zen.pickAt(at) == null -> {
                        boxFrom = at
                        boxShift = event.keyboardModifiers.isShiftPressed
                        boxBase = zen.selection
                        zen.marquee = Rect(at, at)
                        event.changes.forEach { it.consume() }
                    }
                    from != null && at != null && event.type == PointerEventType.Move -> {
                        val box = Rect(minOf(from.x, at.x), minOf(from.y, at.y), maxOf(from.x, at.x), maxOf(from.y, at.y))
                        zen.marquee = box
                        if (box.width > ZenPick.BOX_SLOP || box.height > ZenPick.BOX_SLOP) {
                            zen.selection = ZenPick.combine(boxBase, zen.within(box), boxShift)
                        }
                        event.changes.forEach { it.consume() }
                    }
                    from != null && (event.type == PointerEventType.Release || !event.buttons.areAnyPressed) -> {
                        val box = zen.marquee
                        if (box == null || (box.width <= ZenPick.BOX_SLOP && box.height <= ZenPick.BOX_SLOP)) {
                            zen.selection = ZenGestures.tableClick(boxBase, boxShift)
                        }
                        zen.marquee = null
                        boxFrom = null
                        event.changes.forEach { it.consume() }
                    }
                }
                if (boxFrom != null && !deepZen) {
                    zen.marquee = null
                    boxFrom = null
                }
                // A click anywhere below the folded-out header lets go of the deck name:
                // on a desktop nothing else takes focus from a text field, so the bar
                // that is held out while you type would otherwise never fold away.
                val typing = (state.textInputFocused && !neue.searchFocused) || (neue.page == Page.DUEL && h.textFocus.any)
                // The index held out by the logo folds at a press outside it (1.0.89).
                if (event.type == PointerEventType.Press && neue.railHeld && at != null &&
                    at.x > (if (neue.touchFirst) MuShell.strip else MuShell.rail).toPx() && at.y > measured.top
                ) {
                    neue.railHeld = false
                }
                if (event.type == PointerEventType.Press && neue.immersive && neue.revealed.top &&
                    typing && at != null && at.y > measured.top
                ) {
                    h.focus?.clearFocus()
                }
                neue.revealed = EdgeReveal.next(
                    current = neue.revealed,
                    x = if (gone) null else at?.x,
                    y = if (gone) null else at?.y,
                    height = size.height.toFloat(),
                    railWidth = (if (neue.touchFirst) MuShell.strip else MuShell.rail).toPx(),
                    topHeight = measured.top.toFloat(),
                    bottomHeight = measured.bottom.toFloat(),
                    immersive = neue.immersive,
                    holdTop = typing,
                    suppress = h.drag.held != null || neue.menu != null || neue.zen == ZenPhase.DEEP || (neue.page == Page.DUEL && h.duel.carrying),
                // The builder has no footer since 1.0.9: nothing comes up from the bottom.
                ).copy(bottom = false)
                // Two fingers tapped together undo, three redo, anywhere in the window (rec 22,
                // `MultiTap`): a pinch travels, so it is never one. Watched on the way down,
                // and never consumed: a card under the first finger has already let it go.
                // More than one finger down: whatever each is doing to a card, it is not a tap to read it.
                if (event.changes.count { it.byFinger && it.pressed } > 1) neue.fingersAt = System.nanoTime() / 1_000_000
                event.changes.filter { it.byFinger }.forEach { change ->
                    if (change.pressed && !change.previousPressed) {
                        if (tapDowns.isEmpty()) {
                            tapUps.clear()
                            tapTravel = 0f
                        }
                        tapDowns[change.id] = change.uptimeMillis to change.position
                        // A second finger in the same tap, whether or not one event ever held
                        // both pressed (the emulator's injected pairs did not, 1.0.32): no card's
                        // tap under it opens the viewer.
                        if (tapDowns.size > 1) neue.fingersAt = System.nanoTime() / 1_000_000
                    }
                    tapDowns[change.id]?.let { (_, from) -> tapTravel = maxOf(tapTravel, (change.position - from).getDistance()) }
                    if (!change.pressed && change.previousPressed && change.id in tapDowns) tapUps[change.id] = change.uptimeMillis
                }
                if (tapDowns.isNotEmpty() && tapUps.size == tapDowns.size) {
                    val gesture = com.kaiharimoto.mastertool.core.input.MultiTap.classify(
                        downs = tapDowns.values.map { it.first },
                        ups = tapDowns.keys.map { tapUps.getValue(it) },
                        travel = tapTravel,
                        slop = viewConfiguration.touchSlop,
                    )
                    // The last finger's own tap is released after this, in the card's pass, and
                    // schedules its open then: marked now, it is skipped when it comes due.
                    if (tapDowns.size > 1) neue.fingersAt = System.nanoTime() / 1_000_000
                    tapDowns.clear()
                    tapUps.clear()
                    com.kaiharimoto.mastertool.core.input.DeskTouch.window.firstOrNull { it.gesture == gesture }?.let { h.run(it.action) }
                }
                // A finger cannot reach an edge the system does not take, so in immersive a
                // tap on the paper strip along the top, or in the gutter down the left,
                // brings that bar out; a tap anywhere else folds it (EdgeReveal.onTap).
                val finger = event.changes.firstOrNull()?.takeIf { it.byFinger }
                if (finger != null && event.type == PointerEventType.Press) {
                    fingerFrom = finger.position
                    fingerAt = finger.uptimeMillis
                } else if (finger != null && event.type == PointerEventType.Release) {
                    val from0 = fingerFrom
                    fingerFrom = null
                    if (from0 != null && neue.immersive && neue.zen != ZenPhase.DEEP && h.drag.held == null &&
                        (finger.position - from0).getDistance() < viewConfiguration.touchSlop &&
                        finger.uptimeMillis - fingerAt < com.kaiharimoto.mastertool.core.input.DeskTouch.DOUBLE_TAP_MS
                    ) {
                        neue.revealed = EdgeReveal.onTap(
                            current = neue.revealed,
                            x = finger.position.x,
                            y = finger.position.y,
                            topStrip = IMMERSIVE_TOP.toPx(),
                            leftStrip = 32.dp.toPx(),
                            topHeight = measured.top.toFloat(),
                            railWidth = MuShell.strip.toPx(),
                            immersive = true,
                        )
                    }
                }
            }
        }
    }

/** How tall the folded bars were when last laid out, in pixels. Plain fields: only the pointer watcher reads them. */
internal class FoldedBars {
    var top: Int = 0
    var bottom: Int = 0
}
