package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxTable

/**
 * The play-around set (Phase E, E.5a; `docs/phases/E.md` §4.6): positions whose right first move depends on the other seat
 * not seeing your hand, each with its known answer. A search that lets the other seat answer knowing everything finds every
 * first move here worth the same — it always saves its answer for what matters — so it cannot lead with a bait; a search
 * that cannot see your hand either is drawn out by the bait some of the time.
 *
 * Every card is a reference card ([FxRef]), every one written.
 */
object PlayAround {
    /**
     * One position: seat 0 to move in its Main Phase 1. [bait] is the card to lead with, [key] the card that matters, played
     * once the other seat's [answer] is spent on the bait; [about] says why, in words.
     */
    class Position(val id: String, val about: String, val table: FxTable, val bait: Int, val key: Int, val answer: Int) {
        /** The first move that activates or summons [code] from seat 0's hand. */
        fun lead(code: Int): FxMove = FxRef.uids(table, code).firstNotNullOf { u ->
            FxEngine.moves(table, 0).firstOrNull { m -> (m as? FxMove.Activate)?.uid == u || (m as? FxMove.NormalSummon)?.uid == u }
        }

        /** Whether [steps] play [code]'s card: an activation of it by [seat]. */
        fun plays(steps: List<TurnStep>, code: Int, seat: Int): Int =
            steps.indexOfFirst { st -> st is TurnStep.Move && st.seat == seat && st.move is FxMove.Activate && st.card == code }
    }

    /**
     * Lead with the bait: Example Call Special Summons Example Scout from the Deck, whose search on summon brings a small
     * monster; Example Oath brings Example Knight, the only monster that beats their Example Warden. The other seat holds
     * Example Ward, which negates one search from the Deck. Knowing your hand, it keeps Ward for Oath, so Oath is never worth
     * playing and is kept; not knowing it, it often spends Ward on the first search it sees — so Call goes first, and Oath
     * follows once Ward is gone.
     */
    val bait: Position by lazy {
        val t = FxRef.table(
            you = FxRef.Side(hand = listOf(FxRef.CALL, FxRef.OATH), deck = listOf(FxRef.SCOUT, FxRef.KNIGHT, FxRef.PAWN)),
            them = FxRef.Side(hand = listOf(FxRef.WARD), field = listOf(FxRef.Slot(FxRef.WARDEN, 0)), deck = List(5) { FxRef.PAWN }),
            turn = 2, active = 0, phase = DuelPhase.MAIN1,
        )
        Position(
            "bait", "Lead with the bait: Call's Scout searches first, so Ward is spent before Oath brings Knight to beat Warden.",
            t, bait = FxRef.CALL, key = FxRef.OATH, answer = FxRef.WARD,
        )
    }

    val all: List<Position> by lazy { listOf(bait) }
}
