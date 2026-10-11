package com.kaiharimoto.mastertool.core.duel.match.series

import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.search.ladder.Bench
import com.kaiharimoto.mastertool.core.duel.search.ladder.GreedyPlayer
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.SidePlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Series played on the bench (G2.1): by the quick Greedy player, so the rules of a match are held, not its strength. */
class SeriesPlayTest {
    private fun deck(id: String, side: List<Int> = emptyList()): SeriesDeck {
        val b = Bench.deck(id)!!
        return SeriesDeck(b.id, b.name, Deck(b.main.map(::CardId), b.extra.map(::CardId), side.map(::CardId)))
    }

    private val isExtra: (CardId) -> Boolean = { Bench.kit.facts[it.value]?.extraDeck == true }

    @Test
    fun aMatchIsPlayedAsATournamentPlaysIt() = runTest(timeout = 10.minutes) {
        val scouts = deck("scouts", side = listOf(FxRef.COLOSSUS, FxRef.COLOSSUS))
            .let { it.copy(second = SidePlan(out = listOf(CardId(FxRef.PAWN), CardId(FxRef.PAWN)), into = listOf(CardId(FxRef.COLOSSUS), CardId(FxRef.COLOSSUS)))) }
        val giants = deck("giants")
        for ((seed, opener) in MatchSeries.seeds(2)) {
            val m = MatchSeries.play(scouts, giants, seed, opener, Bench.kit, { _, _ -> GreedyPlayer() }, isExtra, turns = 6)
            assertTrue(m.games.size in 2..3, "$m")
            assertEquals(opener, m.games.first().first)
            m.games.zipWithNext().forEach { (x, y) -> assertEquals(MatchSeries.nextFirst(x), y.first, "$m") }
            assertEquals(MatchSeries.winner(m.games), m.winner)
            // The same seed plays the same match.
            assertEquals(m, MatchSeries.play(scouts, giants, seed, opener, Bench.kit, { _, _ -> GreedyPlayer() }, isExtra, turns = 6))
        }
    }

    @Test
    fun aComparisonPlaysEachPairOnceAndGoesOnWhereItStopped() = runTest(timeout = 10.minutes) {
        val a = deck("scouts")
        val b = a.copy(name = "Scouts with Colossus", deck = a.deck.copy(main = a.deck.main.map { if (it.value == FxRef.PAWN) CardId(FxRef.COLOSSUS) else it }))
        val opp = SeriesOpponent(deck("giants"), 1.0)
        val start = SeriesRun("t", pairs = 1, opponents = listOf(OpponentRun("giants", "Giants", 1.0)))
        var kept = 0
        val done = SeriesRunner.run(start, listOf(a, b), listOf(opp), Bench.kit, { _, _ -> GreedyPlayer() }, isExtra, workers = 2, stop = { false }) { kept++ }
        assertEquals(4, done.played)
        assertEquals(4, kept)
        assertTrue(done.complete)
        assertEquals(2, SeriesReading.of(done).pairs)
        // Nothing left to play: a run picked up again plays nothing.
        var again = 0
        val same = SeriesRunner.run(done, listOf(a, b), listOf(opp), Bench.kit, { _, _ -> GreedyPlayer() }, isExtra, workers = 2, stop = { false }) { again++ }
        assertEquals(0, again)
        assertEquals(done, same)
        assertEquals(1.0, SeriesCoverage.of("scouts", "Scouts", a.deck, Bench.kit).share)
    }
}
