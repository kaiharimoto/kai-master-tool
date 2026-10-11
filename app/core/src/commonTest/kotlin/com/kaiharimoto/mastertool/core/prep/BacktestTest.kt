package com.kaiharimoto.mastertool.core.prep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Predictions scored (Phase F, F.2): each round called from the games before it, and the calls scored. */
class BacktestTest {
    private var n = 0

    private fun g(at: Long, opponent: String, result: String, turn: String = TestGame.FIRST, event: String? = null, round: Int? = null, deck: String = "d") =
        TestGame("g${n++}", at, deck, opponent, opponent.uppercase(), turn, result = result, eventId = event, round = round)

    @Test
    fun aRoundIsCalledFromTheGamesBeforeItAndNeverAfter() {
        val practice = List(8) { i -> g(10L + i, "x", if (i < 6) TestGame.WIN else TestGame.LOSS, if (i % 2 == 0) TestGame.FIRST else TestGame.SECOND) }
        val round1 = g(100, "x", TestGame.WIN, event = "e", round = 1)
        val round2 = g(200, "y", TestGame.LOSS, event = "e", round = 2)
        // Games against X after round 1 — all lost — must not move what was said before it.
        val later = List(10) { i -> g(300L + i, "x", TestGame.LOSS) }
        val (calls, undecided) = Backtest.rounds(practice + round1 + round2 + later)
        assertEquals(0, undecided)
        assertEquals(2, calls.size)
        val c1 = calls[0]
        assertEquals(1, c1.round)
        assertEquals(8, c1.before)
        assertTrue(c1.won)
        assertEquals(TestStats.matchAgainst(TestStats.matrix(practice).single()), c1.said)
        assertTrue(c1.said > 0.5, "${c1.said}")
        // Never played Y before: a coin's call.
        assertEquals(0.5, calls[1].said, 1e-9)
        assertEquals(0, calls[1].before)
        assertEquals("Y", calls[1].name)
    }

    @Test
    fun aDrawnRoundIsNotCountedAndAnotherDecksGamesAreNotItsHistory() {
        val other = List(6) { i -> g(1L + i, "x", TestGame.WIN, deck = "other") }
        val drawn = g(50, "x", TestGame.DRAW, event = "e", round = 1)
        val lost = g(60, "x", TestGame.LOSS, event = "e", round = 2)
        val (calls, undecided) = Backtest.rounds(other + drawn + lost, deckId = "d")
        assertEquals(1, undecided)
        // Round 2's history is round 1, a draw (no decided game): still a coin's call, never the other deck's wins.
        assertEquals(0.5, calls.single().said, 1e-9)
    }

    @Test
    fun theScoreIsBriersWithItsSkillBinsAndMatchups() {
        val calls = listOf(
            Backtest.Call("e", 1, "x", "X", 1, 0.8, true, 10),
            Backtest.Call("e", 2, "x", "X", 2, 0.8, false, 11),
            Backtest.Call("e", 3, "y", "Y", 3, 0.4, false, 3),
            Backtest.Call("e", 4, "y", "Y", 4, 0.5, true, 0),
        )
        val s = Backtest.score(calls)
        // (0.04 + 0.64 + 0.16 + 0.25) / 4
        assertEquals(0.2725, s.brier, 1e-9)
        assertEquals(1 - 0.2725 / 0.25, s.skill, 1e-9)
        assertEquals(listOf(0, 1, 1, 2), s.bins.map { it.n })
        val x = s.matchups.first { it.opponent == "x" }
        assertEquals(2, x.calls)
        assertEquals(0.34, x.brier, 1e-9)
        assertEquals(0.5, x.won)
        val words = Backtest.words(s)
        assertTrue("4 rounds called" in words && "too few rounds" in words && "- X: 2 rounds" in words, words)
        assertEquals(0, Backtest.score(emptyList<Backtest.Call>()).n)
        assertTrue("No round logged" in Backtest.words(Backtest.score(emptyList<Backtest.Call>())))
    }

    @Test
    fun aRoundLoggedAsOneGameWithTheMatchResultIsItsMatch() {
        // The Day tab logs a round as one game with the match's result (2–1, Time …): that is the round won or lost.
        val history = List(4) { i -> g(1L + i, "x", TestGame.WIN) }
        val round = g(10, "x", TestGame.LOSS, event = "e", round = 1).copy(note = "Time", reason = TestGame.REASON_TIME)
        val s = Backtest.score(history + round)
        assertEquals(1, s.n)
        assertTrue(!s.calls.single().won)
        assertTrue(s.brier > 0.25, "${s.brier}")
    }
}
