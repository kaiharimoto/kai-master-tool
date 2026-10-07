package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.match.CueKind
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.lounge.RoomAiTurn.Next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomAiTest {
    private val rules = MatchRules()

    private fun DuelGame.made(seat: Int?, vararg a: DuelAction): DuelGame {
        val r = act(a.toList(), seat, 0L)
        assertTrue(r.ok, r.problem)
        return r.game
    }

    /** Dealt with the opening roll, both seats thrown, seat 0 chooses to go first: turn 1, seat 0's Draw Phase. */
    private fun started(): DuelGame {
        var g = DuelGame.start(header().copy(openingRoll = true), 0L)
        while (g.state.opening?.winner == null) {
            g = g.made(0, DuelAction.OpeningRoll(0)).made(1, DuelAction.OpeningRoll(1))
        }
        val winner = g.state.opening!!.winner!!
        return g.made(winner, DuelAction.GoFirst(winner, first = winner == 0))
    }

    @Test
    fun aPersonsMoveIsWaitedForAndAisIsCued() {
        // Before the roll, Ai throws its own dice; the person throws theirs.
        val dealt = DuelGame.start(header().copy(openingRoll = true), 0L)
        assertEquals(Next.Table(1, listOf(DuelAction.OpeningRoll(1))), RoomAiTurn.next(dealt, setOf(1), RoomAiMemo(), rules))
        assertEquals(Next.Wait, RoomAiTurn.next(dealt.made(1, DuelAction.OpeningRoll(1)), setOf(1), RoomAiMemo(), rules))
        // Seat 0's turn: a person's; with seat 0 Ai's, its draw-less turn 1 is played on a cue.
        val g = started()
        assertEquals(0, g.state.active)
        assertEquals(Next.Wait, RoomAiTurn.next(g, setOf(1), RoomAiMemo(), rules))
        assertEquals(Next.Cue(0, CueKind.PLAY), RoomAiTurn.next(g, setOf(0, 1), RoomAiMemo(), rules))
        // No Ai at the table: nothing is ever Ai's to do.
        assertEquals(Next.Wait, RoomAiTurn.next(g, emptySet(), RoomAiMemo(), rules))
    }

    @Test
    fun aChainWaitsOnWhoeverHasPriorityAndAPersonsPassCounts() {
        var g = started()
        val card = g.state.seats[0].hand[0]
        // The person (seat 0) sets and activates a card: Ai (seat 1) is cued to chain to it.
        g = g.made(0, DuelAction.Move(card, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_UP_ATK), DuelAction.ChainAdd(0, card))
        assertEquals(Next.Cue(1, CueKind.CHAIN), RoomAiTurn.next(g, setOf(1), RoomAiMemo(), rules))
        // Ai passes: the person's own link is theirs to resolve, and the table waits on them.
        val passed = g.made(1, DuelAction.Answer(1, respond = false))
        assertTrue(RoomAiTurn.passedOnTop(passed))
        assertEquals(Next.Wait, RoomAiTurn.next(passed, setOf(1), RoomAiMemo(), rules))
        // Ai's own link on top, the person not yet answered: the table waits; once they pass, Ai resolves it.
        val aiCard = g.state.seats[1].hand[0]
        val aiLink = g.made(1, DuelAction.ChainAdd(1, aiCard))
        assertEquals(Next.Wait, RoomAiTurn.next(aiLink, setOf(1), RoomAiMemo(), rules))
        assertEquals(Next.Cue(1, CueKind.RESOLVE), RoomAiTurn.next(aiLink.made(0, DuelAction.Answer(0, respond = false)), setOf(1), RoomAiMemo(), rules))
    }

    @Test
    fun anAiThatStallsInItsTurnHasItEndedAndOneThatFailsConcedes() {
        val g = started().made(0, DuelAction.EndTurn)
        // Seat 1's turn 2: the table draws for Ai first.
        val draw = RoomAiTurn.next(g, setOf(1), RoomAiMemo(), rules)
        assertIs<Next.Table>(draw)
        val drawn = g.made(1, *draw.actions.toTypedArray())
        assertEquals(Next.Cue(1, CueKind.PLAY), RoomAiTurn.next(drawn, setOf(1), RoomAiMemo(), rules))
        // Two cues with no move: the turn is ended for it.
        var memo = RoomAiMemo()
        repeat(rules.stalls - 1) { memo = RoomAiTurn.after(drawn, drawn, RoomAiTurn.Cued(1, CueKind.PLAY, moves = 0), memo, rules).memo }
        val ended = RoomAiTurn.after(drawn, drawn, RoomAiTurn.Cued(1, CueKind.PLAY, moves = 0), memo, rules)
        assertEquals(listOf(DuelAction.EndTurn), ended.table?.actions)
        // Failing cue after cue: it concedes.
        memo = RoomAiMemo()
        repeat(rules.failures - 1) { memo = RoomAiTurn.after(drawn, drawn, RoomAiTurn.Cued(1, CueKind.PLAY, 0, failed = "timeout"), memo, rules).memo }
        val gave = RoomAiTurn.after(drawn, drawn, RoomAiTurn.Cued(1, CueKind.PLAY, 0, failed = "timeout"), memo, rules)
        assertEquals(listOf(DuelAction.Concede(1)), gave.table?.actions)
    }

    @Test
    fun aDaysBudgetIsSpentAndANewDayStartsAfresh() {
        var spend = AiSpend()
        spend = spend.add("2026-10-07", 1_500_000)
        assertTrue(!spend.spent("2026-10-07", 2_000_000))
        spend = spend.add("2026-10-07", 600_000)
        assertTrue(spend.spent("2026-10-07", 2_000_000))
        // A cap of nothing keeps Ai out; the next day counts from zero.
        assertTrue(AiSpend().spent("2026-10-07", 0))
        assertEquals(0L, spend.on("2026-10-08").tokens)
        assertTrue(!spend.spent("2026-10-08", 2_000_000))
    }
}
