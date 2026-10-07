package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelSelection
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelVerb

/**
 * Several cards, one move (1.0.90, kai: "let me select multiple cards … and perform an action with them"), a part of
 * [Duels]: select mode, the verbs on the whole selection and the ordering strip. The selection itself is
 * [Duels.selection]; [Duels] forwards every member here under its own name.
 */
class DuelPicking(private val d: Duels) {
    /** A finger's select mode: after a press and hold, each tap puts a card into the selection or takes it out. */
    var selecting by mutableStateOf(false)

    var ordering by mutableStateOf<Duels.Ordering?>(null)

    /** The verb ↑/↓ have chosen in the selection's bar, once Enter put the keys there; null while it is the pointer's. */
    var selCursor by mutableStateOf<Int?>(null)

    /** Nothing selected, select mode off, no ordering open. */
    fun clearSelection() {
        d.selection = emptySet()
        selecting = false
        ordering = null
        selCursor = null
    }

    /** [uid] into the selection, or out of it (a Ctrl-click, a tap in select mode, Shift Space). The order picked is kept. */
    fun toggleSelect(uid: Int) {
        d.selection = DuelSelection.toggle(d.selection.toList(), uid).toSet()
        d.inspected = uid
        // One card: its verbs beside it, as a click; several: the selection's bar says what they can do.
        d.verbStrip = d.selection.size == 1
        if (d.selection.isEmpty()) selecting = false
    }

    /** A Shift-click: the run from the last card picked to [uid], in the row they share; else [uid] toggled. */
    fun rangeSelect(uid: Int) {
        val s = d.shown?.state ?: return
        val anchor = d.selection.lastOrNull()
        d.selection = DuelSelection.range(s, d.selection.toList(), anchor, uid) { seat -> d.eyes.hand(s, seat) }.toSet()
        d.inspected = uid
        d.verbStrip = d.selection.size == 1
    }

    /** Shift Space (1.0.90): the focused card into the selection, or out of it. */
    fun selectFocused(): Boolean {
        val uid = (if (d.byKeys) d.focusUid() else d.hovered) ?: run { d.problem = "Walk to a card first: the arrows, then Shift Space"; return false }
        toggleSelect(uid)
        return true
    }

    /**
     * [verb] on every selected card, in the order they were picked, as one group ([com.kaiharimoto.mastertool.core.duel.DuelSelection.actions]).
     * To the top or bottom of a Deck it opens the ordering strip first, the order picked as its first order; Attach waits
     * for the card they go under ([host]). Cards the verb cannot take are left, and said.
     */
    fun verbAll(verb: DuelVerb, host: Int?): Boolean {
        val g = d.shown ?: return false
        val s = g.state
        val uids = d.selection.filter { it in s.cards }
        if (uids.size < 2) {
            // Cards that left the duel leave the selection, so the one left is a one-card verb.
            d.selection = uids.toSet()
            return uids.singleOrNull()?.let { d.verb(it, verb, host = host) } ?: false
        }
        d.attacking = null
        val sees = { u: Int -> d.eyes.viewers.isEmpty() || d.eyes.viewers.any { DuelSight.sees(s, u, it) } }
        if (verb in DuelSelection.ORDERED) {
            // Only what goes into a Deck is ordered: a token or an Extra Deck monster is said, and left.
            val (fit, not) = DuelSelection.deckable(s, uids, d.catalog, sees)
            if (fit.isEmpty()) { d.problem = "None of these go into the Deck"; return false }
            d.problem = if (not.isEmpty()) null else "${not.size} of them do not go into the Deck: left out"
            ordering = Duels.Ordering(fit, bottom = verb == DuelVerb.DECK_BOTTOM)
            d.verbStrip = false
            return true
        }
        if (verb == DuelVerb.ATTACH && host == null) {
            d.attaching = uids.first()
            d.verbStrip = false
            d.problem = null
            return false
        }
        val plan = DuelSelection.actions(s, uids, verb, d.catalog, seat = d.bottom, seatFor = d::seatFor, host = host, sees = sees)
        if (!plan.ok) {
            d.problem = plan.skipped.firstOrNull()?.second?.let { "${verb.label}: $it" } ?: "Nothing to do with these together"
            return false
        }
        val ok = d.act(plan.actions, if (verb == DuelVerb.TARGET || verb == DuelVerb.REVEAL) d.bottom else d.seatFor(uids.first()))
        if (ok) {
            clearSelection()
            if (plan.skipped.isNotEmpty()) d.problem = "${plan.skipped.size} of ${uids.size} stayed where they were: ${plan.skipped.first().second}"
        }
        return ok
    }

    /** The ordering strip's arrows: the chosen card ([delta] -1 left, 1 right). */
    fun orderCursor(delta: Int) {
        val o = ordering ?: return
        ordering = o.copy(cursor = (o.cursor + delta).coerceIn(0, o.order.size - 1))
    }

    /** Alt ← / Alt → or a drag: the chosen card [delta] places nearer the top (−) or further down (+). */
    fun orderMove(delta: Int, from: Int?) {
        val o = ordering ?: return
        val i = from ?: o.cursor
        val j = (i + delta).coerceIn(0, o.order.size - 1)
        ordering = o.copy(order = DuelSelection.reorder(o.order, i, j), cursor = j)
    }

    /** The strip's Top / Bottom switch (K, Shift K while it is open). */
    fun orderTo(bottom: Boolean) {
        ordering = ordering?.copy(bottom = bottom)
    }

    /** Enter on the ordering strip: the cards onto the Deck as it shows them, top first. One group. */
    fun commitOrdering(): Boolean {
        val o = ordering ?: return false
        val s = d.shown?.state ?: return false
        val ok = d.act(DuelSelection.toDeck(s, o.order, o.bottom), d.seatFor(o.order.first()))
        if (ok) clearSelection()
        return ok
    }

    /** "Random order": chance picks the order, stamped as the move is made ([com.kaiharimoto.mastertool.core.duel.DuelAction.Pick]). */
    fun orderRandom(): Boolean {
        val o = ordering ?: return false
        val s = d.shown?.state ?: return false
        val ok = d.act(DuelSelection.randomToDeck(s, d.bottom, o.order, o.bottom), d.bottom)
        if (ok) clearSelection()
        return ok
    }

    /** "Shuffle in": every card into its Deck, each Deck shuffled once. */
    fun orderShuffle(): Boolean {
        val o = ordering ?: return false
        val s = d.shown?.state ?: return false
        val ok = d.act(DuelSelection.shuffledIn(s, o.order), d.seatFor(o.order.first()))
        if (ok) clearSelection()
        return ok
    }
}
