package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.match.CueKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What is asked of Ai's seat at kai's own table (Phase E, E.3b), read off the table and the engine's priority. */
class TableCueTest {
    private fun table(g: DuelGame) = FxTable(g.state, FxFold.fold(g.header, g.played, FxRef.book, FxRef.facts, g.state), FxRef.book, FxRef.facts, g.header.seed)

    private fun commit(g: DuelGame, t: FxTable, seat: Int, m: FxMove): DuelGame {
        val p = FxEngine.play(t, seat, m) { d -> if (d is Decision.YesNo) listOf(1) else listOf(0) }
        check(p is FxPlay.Done) { "$m: $p" }
        return g.act(p.actions, seat, fx = p.tags).game
    }

    @Test
    fun itsTurnAChainItHasPriorityOnItsOwnLinkAndAnAskAreEachACue() {
        // Seat 1 is Ai's. On kai's turn with the table open, nothing waits on it; on its own, its turn is.
        val (kaiTurn, t0) = FxRef.game(Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.SCOUT)), Side(hand = listOf(FxRef.WARD)), turn = 2, active = 0)
        assertNull(TableCue.of(t0, 1))
        assertEquals(CueKind.PLAY, TableCue.of(table(FxRef.game(Side(), Side(hand = listOf(FxRef.PAWN)), turn = 2, active = 1).first), 1))
        // kai's Scout searches: its link stands, and Ai's seat has priority on it.
        val summoned = commit(kaiTurn, t0, 0, FxMove.NormalSummon(FxRef.uid(t0, FxRef.SCOUT)))
        assertEquals(CueKind.CHAIN, TableCue.of(table(summoned), 1))
        // kai asks to move on: Ai's seat answers.
        val asked = kaiTurn.act(listOf(DuelAction.Propose(0, end = true)), 0).game
        assertEquals(CueKind.ANSWER, TableCue.of(table(asked), 1))
        // A one-player table has no other seat.
        assertNull(TableCue.of(table(FxRef.game(Side(hand = listOf(FxRef.SCOUT)), Side()).first.let { it.copy(state = it.state.copy(solo = true)) }), 1))
    }

    @Test
    fun itsOwnLinkPassedOnIsItsToResolve() {
        // Ai's Scout (seat 1) searches on its turn; kai passes; the link is Ai's to resolve.
        val (g0, t0) = FxRef.game(Side(hand = listOf(FxRef.PAWN)), Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.SCOUT)), turn = 2, active = 1)
        val summoned = commit(g0, t0, 1, FxMove.NormalSummon(FxRef.uid(t0, FxRef.SCOUT, 1)))
        assertEquals(null, TableCue.of(table(summoned), 1), "kai has priority on Ai's link")
        val passed = commit(summoned, table(summoned), 0, FxMove.Pass)
        assertEquals(CueKind.RESOLVE, TableCue.of(table(passed), 1))
    }
}
