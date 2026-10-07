package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookSearch
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place

/**
 * The cards in play as [seat] knows them, by name — its hand, what it has on the field, in its GY, banished and in its
 * Extra Deck, and what it can see of the other seat's — so the playbook's entries for this position come first at the
 * table (mastery, 1.1.42). Only what [viewer]'s eyes see is named: a hidden card is never a clue to a line.
 */
object DuelPosition {
    fun of(s: DuelState, seat: Int, viewer: Int?, catalog: DuelCatalog): PlaybookSearch.Position {
        val hand = ArrayList<String>()
        val mine = ArrayList<String>()
        val theirs = ArrayList<String>()
        for ((uid, c) in s.cards) {
            if (!DuelSight.sees(s, uid, viewer)) continue
            val name = c.name ?: catalog.info(c.code)?.name ?: continue
            when (val p = s.placeOf(uid)) {
                is Place.Pile -> when {
                    p.kind == PileKind.DECK -> Unit
                    p.kind == PileKind.HAND && p.seat == seat -> hand += name
                    p.seat == seat -> mine += name
                    p.kind != PileKind.HAND -> theirs += name
                }
                is Place.Zone, is Place.Under -> if (c.controller == seat) mine += name else theirs += name
                else -> Unit
            }
        }
        // Turn 1 is the first player's, and turns alternate.
        val first = if (s.turn <= 0) null else (if (s.turn % 2 == 1) s.active == seat else s.active != seat)
        return PlaybookSearch.Position(hand.distinct(), mine.distinct(), theirs.distinct(), first)
    }
}
