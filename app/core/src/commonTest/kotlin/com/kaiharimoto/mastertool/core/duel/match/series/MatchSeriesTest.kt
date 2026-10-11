package com.kaiharimoto.mastertool.core.duel.match.series

import com.kaiharimoto.mastertool.core.ai.proposals.Expect
import com.kaiharimoto.mastertool.core.ai.proposals.Proposals
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.SidePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Simulated series (Phase G part two, G2.1): a best of three as a tournament plays it, and two versions read in pairs. */
class MatchSeriesTest {
    private fun ids(vararg c: Int) = c.map(::CardId)

    @Test
    fun eachSeedIsPlayedTwiceEachDeckGoingFirstOnce() {
        val s = MatchSeries.seeds(3)
        assertEquals(6, s.size)
        assertEquals(3, s.map { it.first }.distinct().size)
        s.chunked(2).forEach { (x, y) -> assertEquals(x.first, y.first); assertEquals(listOf(0, 1), listOf(x.second, y.second)) }
        assertEquals(s, MatchSeries.seeds(3))
        assertTrue(MatchSeries.gameSeed(s[0].first, 1) != MatchSeries.gameSeed(s[0].first, 2))
    }

    @Test
    fun theLoserGoesFirstAndTheMatchIsTheFirstToTwo() {
        assertEquals(1, MatchSeries.nextFirst(SeriesGame(first = 0, winner = 0)))
        assertEquals(0, MatchSeries.nextFirst(SeriesGame(first = 0, winner = 1)))
        // After a draw the same player goes first again.
        assertEquals(1, MatchSeries.nextFirst(SeriesGame(first = 1, winner = null)))
        val two = listOf(SeriesGame(0, 1), SeriesGame(0, 1))
        assertTrue(MatchSeries.over(two))
        assertEquals(1, MatchSeries.winner(two))
        assertFalse(MatchSeries.over(listOf(SeriesGame(0, 1), SeriesGame(0, 0))))
        assertNull(MatchSeries.winner(listOf(SeriesGame(0, 1), SeriesGame(0, 0))))
        // Three games and no one to two: more wins, else a drawn match.
        assertEquals(0, MatchSeries.winner(listOf(SeriesGame(0, 0), SeriesGame(1, null), SeriesGame(1, null))))
        assertNull(MatchSeries.winner(listOf(SeriesGame(0, 0), SeriesGame(1, 1), SeriesGame(0, null))))
    }

    @Test
    fun aDeckSidesBeforeGamesTwoAndThreeForTheTurnItPlays() {
        val deck = Deck(main = ids(FxRef.PAWN, FxRef.PAWN, FxRef.KNIGHT), side = ids(FxRef.WARD, FxRef.VEIL))
        val d = SeriesDeck("x", "X", deck, first = SidePlan(out = ids(FxRef.PAWN), into = ids(FxRef.WARD)), second = SidePlan(out = ids(FxRef.KNIGHT), into = ids(FxRef.VEIL)))
        val isExtra: (CardId) -> Boolean = { false }
        assertEquals(deck, MatchSeries.decksFor(d, 1, goesFirst = true, isExtra))
        assertEquals(ids(FxRef.PAWN, FxRef.KNIGHT, FxRef.WARD), MatchSeries.decksFor(d, 2, goesFirst = true, isExtra).main)
        assertEquals(ids(FxRef.PAWN, FxRef.PAWN, FxRef.VEIL), MatchSeries.decksFor(d, 3, goesFirst = false, isExtra).main)
    }

    @Test
    fun aChangeIsMadeWhereItsCardsBelong() {
        val deck = Deck(main = ids(FxRef.PAWN, FxRef.PAWN, FxRef.KNIGHT), extra = ids(FxRef.BRIDGE), side = ids(FxRef.WARD))
        val isExtra: (CardId) -> Boolean = { it.value == FxRef.BRIDGE || it.value == FxRef.ARCH }
        val b = MatchSeries.changed(deck, DeckChange(out = FxRef.PAWN, into = FxRef.LAMP, copies = 2), isExtra)!!
        assertEquals(ids(FxRef.KNIGHT, FxRef.LAMP, FxRef.LAMP), b.main)
        assertEquals(deck.side, b.side)
        assertEquals(ids(FxRef.BRIDGE, FxRef.ARCH), MatchSeries.changed(deck, DeckChange(into = FxRef.ARCH), isExtra)!!.extra)
        assertNull(MatchSeries.changed(deck, DeckChange(out = FxRef.KNIGHT, copies = 2), isExtra))
    }

    private fun m(seed: Long, opener: Int, winner: Int?) = SeriesMatch(seed, opener, listOf(SeriesGame(opener, winner)), winner)

    @Test
    fun twoVersionsAreReadPairBySeedAndFirstPlayer() {
        val run = SeriesRun(
            "s1", pairs = 2,
            opponents = listOf(
                OpponentRun(
                    "o", "Giants", 1.0,
                    a = listOf(m(1, 0, 0), m(1, 1, 1), m(2, 0, 0), m(2, 1, 1)),
                    b = listOf(m(1, 0, 0), m(1, 1, 0), m(2, 0, 1), m(2, 1, 0)),
                ),
            ),
        )
        val r = SeriesReading.of(run)
        val p = r.opponents.single().paired
        // (1,0): both won; (1,1): only B; (2,0): only A; (2,1): only B.
        assertEquals(1, p.both)
        assertEquals(1, p.onlyA)
        assertEquals(2, p.onlyB)
        assertEquals(0, p.neither)
        assertEquals(0.25, r.difference, 1e-9)
        assertEquals(p.interval, r.interval)
        assertEquals(0, r.verdict)
        assertEquals(4, r.pairs)
        // A match of B's with no match of A's on the same seed is not paired.
        val half = run.copy(opponents = listOf(run.opponents.single().copy(a = run.opponents.single().a.take(1))))
        assertEquals(1, SeriesReading.of(half).pairs)
    }

    @Test
    fun aFieldIsWeightedByItsShares() {
        val win = List(6) { i -> m(i.toLong(), 0, 0) }
        val lose = List(6) { i -> m(i.toLong(), 0, 1) }
        val run = SeriesRun(
            "s2", pairs = 3,
            opponents = listOf(
                // B gains every match against the first, loses none against the second.
                OpponentRun("x", "X", 3.0, a = lose, b = win),
                OpponentRun("y", "Y", 1.0, a = win, b = win),
            ),
        )
        val r = SeriesReading.of(run)
        assertEquals(0.75, r.difference, 1e-9)
        assertTrue(r.interval.first > 0 && r.interval.second <= 1.0, "${r.interval}")
        assertEquals(1, r.verdict)
        // Each version's match win over the field: A wins only against Y (a quarter of it), B everywhere.
        assertEquals(0.25, r.a, 1e-9)
        assertEquals(1.0, r.b, 1e-9)
        val e = SeriesExpect.of(run)
        assertEquals(25.0, e.before)
        assertEquals(100.0, e.after)
        assertEquals(Expect.SERIES, e.kind)
        assertEquals("s2", e.run)
        assertEquals(12, e.n)
        assertTrue("simulated matches, 12 pairs" in Proposals.expectWords(e), Proposals.expectWords(e))
        val words = SeriesWords.of(run.copy(coverage = listOf(SeriesCoverage("a", "Mine", 30, 40), SeriesCoverage("x", "X", 10, 40))), r)
        assertTrue("+75 points of match win" in words && "the change is better" in words && "X 25% (mostly blanks)" in words, words)
    }

    @Test
    fun aRunIsKeptAndReadBack() {
        val run = SeriesRun("s3", "d", "Cut Pawn, add Ward", "Mine", "Mine with Ward", "p1", "p2", 2, "search-v0", "Fast",
            listOf(OpponentRun("o", "Giants", 0.5, a = listOf(m(1, 0, 0)))), listOf(SeriesCoverage("d", "Mine", 40, 40)), 9L, false)
        assertEquals(run, SeriesCodec.decode(SeriesCodec.encode(run)))
        assertNull(SeriesCodec.decode("not json"))
        assertEquals(1, run.played)
        assertEquals(8, run.total)
    }
}
