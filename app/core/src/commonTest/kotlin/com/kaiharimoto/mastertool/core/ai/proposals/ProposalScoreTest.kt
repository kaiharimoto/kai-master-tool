package com.kaiharimoto.mastertool.core.ai.proposals

import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ai's suggestions scored against the person's games (Phase G part two, G2.3). */
class ProposalScoreTest {
    private var k = 0

    private fun games(print: String, wins: Int, losses: Int, by: String? = null): List<TestGame> =
        List(wins + losses) { i -> TestGame("g${k++}", k.toLong(), "d", "x", "X", TestGame.FIRST, result = if (i < wins) TestGame.WIN else TestGame.LOSS, deckPrint = print, source = TestGame.SOURCE_PERSON, by = by) }

    private fun p(id: String, from: String, to: String, e: Expect, state: String = Proposal.APPLIED) =
        Proposal(id, "d", "Change $id", expect = listOf(e), state = state, fromPrint = from, toPrint = to)

    private val series = Expect("Match win against the field", 48.0, 53.0, 49.0, 57.0, kind = Expect.SERIES, run = "s1", n = 12)

    @Test
    fun anAppliedSuggestionIsHeldToTheGamesPlayedSince() {
        val gs = games("a", 5, 5) + games("b", 7, 3)
        val s = ProposalScore.score(listOf(p("1", "a", "b", series)), gs)
        val x = s.scored.single()
        assertEquals(5.0, x.predicted, 1e-9)
        assertEquals(1.0, x.low, 1e-9)
        assertEquals(9.0, x.high, 1e-9)
        // Seen: 70% after against 50% before, +20 — outside the range it gave.
        assertEquals(20.0, x.seen, 1e-6)
        assertTrue(!x.held)
        assertTrue(x.helps > 0.99, "${x.helps}")
        assertTrue(x.helped)
        assertTrue(x.brier < 0.001)
        assertEquals(0, s.held)
    }

    @Test
    fun tooFewGamesWaitAndWhatCannotBeScoredIsNot() {
        val gs = games("a", 5, 5) + games("b", 2, 1) + games("c", 4, 4) + games("e", 4, 4)
        val proposals = listOf(
            p("waits", "a", "b", series),
            // No range: nothing to hold it to.
            p("bare", "a", "c", Expect("Match win", 50.0, 55.0)),
            // Not a win rate.
            p("odds", "a", "c", Expect("Opens going first", 71.0, 76.0, 74.0, 78.0, kind = Expect.EXACT)),
            // Open: never applied.
            p("open", "a", "e", series, state = Proposal.OPEN),
        )
        val s = ProposalScore.score(proposals, gs)
        assertEquals(emptyList(), s.scored)
        assertEquals(listOf("waits"), s.waiting.map { it.id })
        assertEquals(null, s.brier)
        assertTrue("waits for 8 games" in ProposalScore.words(s), ProposalScore.words(s))
    }

    @Test
    fun gamesAiLoggedNeverMarkItsOwnSuggestion() {
        // The red team's R4: games Ai wrote down through log_game are the person's in the ledger, but scoring its own suggestion
        // with them would let it mark itself. Eight a side, every one Ai's on the version it made: still waiting.
        val gs = games("a", 5, 5) + games("b", 7, 3, by = TestGame.BY_AI)
        val s = ProposalScore.score(listOf(p("1", "a", "b", series)), gs)
        assertEquals(emptyList(), s.scored)
        assertEquals(listOf("1"), s.waiting.map { it.id })
        assertTrue(ProposalScore.OWN_GAMES in ProposalScore.words(s), ProposalScore.words(s))
        // The same games logged by the person score it.
        assertEquals(1, ProposalScore.score(listOf(p("1", "a", "b", series)), games("a", 5, 5) + games("b", 7, 3)).scored.size)
    }

    @Test
    fun theScoresAreOverEverySuggestionScored() {
        val gs = games("a", 5, 5) + games("b", 6, 4) + games("c", 4, 6)
        // Held: said +5 (−5 to +15), saw +10. Missed: said +5 (+1 to +9), saw −10, and it did not help.
        val held = p("h", "a", "b", Expect("Match win", 50.0, 55.0, 45.0, 65.0, kind = Expect.GAMES))
        val missed = p("m", "a", "c", series)
        val s = ProposalScore.score(listOf(held, missed), gs)
        assertEquals(2, s.scored.size)
        assertEquals(1, s.held)
        assertTrue(s.brier!! > 0.25, "${s.brier}")
        val words = ProposalScore.words(s)
        assertTrue("2 suggestions scored" in words && "1 of 2 times" in words && "too few yet" in words && "— held" in words && "— missed" in words, words)
    }
}
