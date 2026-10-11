package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.layout.DuelFocus
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayout

/**
 * Command mode's focus (1.0.87), a part of [Duels] (H.3, moved unchanged): where the arrows stand on the table, the card
 * the focus goes with, what moved last, and the keys' own picks. [Duels] forwards every member here under its own name.
 */
class DuelFocusState(private val d: Duels) {
    /** Where the arrows stand on the table (`DuelFocus`), or nowhere yet. */
    var focus by mutableStateOf<DuelFocus.Slot?>(null)
        private set
    /** The card the focus was put on, so it goes with the card when the card moves; null on a place or a pile. */
    private var focusCard: Int? = null
    /** Set to [Duels.Input.KEYS] by the focus's keys, to [Duels.Input.POINTER] by the pointer moving over the table. */
    var lastInput by mutableStateOf(Duels.Input.POINTER)
    /** A card picked up by the keys (Shift Enter), put down where Enter is pressed next. */
    var picked by mutableStateOf<Int?>(null)
    /** The verb highlighted in the verb strip opened by Enter; null while the strip is the pointer's (or shut). */
    var verbCursor by mutableStateOf<Int?>(null)
    /** The table as last drawn (set by `DuelTable`): the focus's grid is read off its shape. */
    var tableLayout by mutableStateOf<DuelLayout?>(null)
    /** Whose eyes the table is drawn through, and the veils' secret (set by `DuelTable`): a hidden hand's order. */
    var eyes by mutableStateOf(DuelFocus.Eyes.ALL)

    /** The keys are in charge: the ring is drawn and the keys act on it. */
    val byKeys: Boolean get() = lastInput == Duels.Input.KEYS && focus != null

    /** The grid's shape: the table as drawn, an open pile's cards to a row. */
    fun focusShape(): DuelFocus.Shape {
        val l = tableLayout ?: return DuelFocus.Shape(twoSided = d.shown?.state?.solo == false)
        val per = d.strip?.let { (seat, kind) -> d.shown?.state?.seats?.get(seat)?.pile(kind)?.size }?.let { DuelFrames.stripGrid(it, l).perRow } ?: 1
        return DuelFocus.Shape.of(l, per)
    }

    /** The card the focus stands on, or null for an empty place (or no focus). */
    fun focusUid(): Int? {
        val f = focus ?: return null
        val s = d.shown?.state ?: return null
        return DuelFocus.uidAt(s, f, eyes)
    }

    /**
     * The card a key acts on: the focus's once the keys moved last (null on an empty place — a key there does
     * nothing), else the card under the pointer, the one selected, or the one being read, as before 1.0.87.
     */
    fun keyTarget(): Int? = if (byKeys) focusUid() else d.hovered ?: d.selection.singleOrNull() ?: d.inspected

    /** The card the inspector reads: the focus's while the keys lead, else the hovered or the last clicked. */
    fun reading(): Int? = (if (byKeys) focusUid() else null) ?: d.hovered ?: d.inspected

    /** Puts the focus on [slot]: the keys lead, the card there is read, a pile's open strip scrolls to it. */
    fun focusOn(slot: DuelFocus.Slot?) {
        focus = slot
        lastInput = Duels.Input.KEYS
        val s = d.shown?.state
        focusCard = if (slot == null || s == null || slot is DuelFocus.Slot.Pile || slot is DuelFocus.Slot.Link) null
        else DuelFocus.uidAt(s, slot, eyes)?.takeIf { followable(s, it) }
        if (slot is DuelFocus.Slot.PileCard) {
            val l = tableLayout ?: return
            val n = s?.seats?.get(slot.seat)?.pile(slot.kind)?.size ?: return
            val grid = DuelFrames.stripGrid(n, l)
            val row = slot.index / grid.perRow
            if (row < d.stripRow) d.stripRow = row
            if (row >= d.stripRow + grid.visibleRows) d.stripRow = row - grid.visibleRows + 1
        }
    }

    /** The arrows: a step on the table ([com.kaiharimoto.mastertool.core.layout.DuelFocus.step]); the first press starts at home. */
    fun walk(dir: DuelFocus.Dir) {
        val s = d.shown?.state ?: return
        d.verbStrip = false
        d.chainMenu = null
        val shape = focusShape()
        if (lastInput != Duels.Input.KEYS || focus == null) {
            // The keys take over: on the card under the pointer, else where the ring was left, else home.
            val start = d.hovered?.let { DuelFocus.slotOf(s, it, d.strip, eyes) }
                ?.let { DuelFocus.settle(it, s, d.bottom, shape) }
                ?: focus?.let { DuelFocus.settle(it, s, d.bottom, shape) }
            focusOn(start ?: DuelFocus.home(s, d.bottom))
            return
        }
        focusOn(DuelFocus.step(focus, dir, s, d.bottom, shape))
    }

    /** Shift ← / → : the row's first or last place. */
    fun walkRow(end: Boolean) {
        val s = d.shown?.state ?: return
        d.verbStrip = false
        val shape = focusShape()
        focusOn(if (end) DuelFocus.rowEnd(focus, s, d.bottom, shape) else DuelFocus.rowStart(focus, s, d.bottom, shape))
    }

    /** The table changed: the focus goes with the card it was on, or stays where it was, made good. */
    fun refocus() {
        val f = focus ?: return
        val s = d.shown?.state ?: return
        val open = (f as? DuelFocus.Slot.PileCard)?.takeIf { d.strip != it.seat to it.kind }
            ?.let { DuelFocus.Slot.Pile(it.seat, it.kind) }
        val next = if (open != null) open else DuelFocus.follow(f, focusCard, s, d.bottom, focusShape(), d.strip, eyes)
        if (next != f) focus = next
        focusCard = next?.takeIf { it !is DuelFocus.Slot.Pile && it !is DuelFocus.Slot.Link }?.let { DuelFocus.uidAt(s, it, eyes) }
            ?.takeIf { followable(s, it) }
        if (picked?.let { it !in s.cards } == true) picked = null
    }

    /**
     * Whether the ring may follow this card when it moves: only one the table's eyes can see (1.0.87, the red team). A
     * hidden card is followed by place, never by uid — following it would show which one a re-veiled hand lost to a Set.
     */
    private fun followable(s: DuelState, uid: Int): Boolean =
        eyes.viewers.isEmpty() || eyes.viewers.any { DuelSight.sees(s, uid, it) }

    /** Esc's last layer: the focus let go, and what was picked with it. */
    fun clearFocus() {
        focus = null
        focusCard = null
        picked = null
        lastInput = Duels.Input.POINTER
    }

    /** The open pile shut (`Duels.closeStrip`): a card focused in it gives the focus back to the pile. */
    fun pileShut() {
        (focus as? DuelFocus.Slot.PileCard)?.let { focus = DuelFocus.Slot.Pile(it.seat, it.kind); focusCard = null }
    }
}
