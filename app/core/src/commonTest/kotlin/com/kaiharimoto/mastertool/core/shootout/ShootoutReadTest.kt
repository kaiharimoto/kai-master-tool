package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.bench.CardCell
import com.kaiharimoto.mastertool.core.shootout.bench.CardResult
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.model.DeckList
import com.kaiharimoto.mastertool.core.shootout.model.Decks
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Target
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.sim.SimJudge
import com.kaiharimoto.mastertool.core.shootout.sim.SyntheticDeck
import com.kaiharimoto.mastertool.core.shootout.sim.TrustStudy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shootout you can read (Phase G, G.4): calls that hold up, the roll's call, and the next copy's worth. */
class ShootoutReadTest {
    private val strata = listOf(Stratum.G1_FIRST, Stratum.G1_SECOND, Stratum.SIDED_FIRST, Stratum.SIDED_SECOND)

    private fun results(cards: List<CardResult>, winRates: Map<Stratum, Estimate> = emptyMap()) = ShootoutResults(
        strata = strata, waiting = emptyMap(), cards = cards, pairs = emptyList(), winRates = winRates, checks = emptyMap(),
        counts = emptyMap(), kept = 0, fitted = 0, olderPlans = 0, settled = StopRule.Settled(0, 0, 5.0, false), noise = 0.0,
    )

    private fun gauss(r: Random): Double = sqrt(-2 * ln(1 - r.nextDouble())) * cos(2 * kotlin.math.PI * r.nextDouble())

    @Test
    fun aNullDeckIsCalledWronglyAtMostOneRunInTwenty() {
        // Twenty cards in four strata, every true worth zero, each read with its own range: at 80 % a card apiece (the old
        // rule called something nearly every run); with Holm at 95 % family-wise, at most about one run in twenty.
        val r = Random(7)
        val runs = 2000
        var anyHolm = 0
        var anyOld = 0
        repeat(runs) {
            val cards = (1..20).map { c ->
                val cells = strata.associateWith { s -> val sd = 3.0 + r.nextDouble() * 6; CardCell(Estimate(gauss(r) * sd, sd), 0.4, 30) }
                CardResult(c, "Engine", 3, cells)
            }
            val res = results(cards)
            if (res.calls().isNotEmpty()) anyHolm++
            if (cards.any { row -> row.cells.values.any { c -> c.estimate.range80.let { it.start > 0 || it.endInclusive < 0 } } }) anyOld++
        }
        val holm = anyHolm.toDouble() / runs
        println("[read] a null deck called wrongly: ${anyOld * 100 / runs} % of runs at 80 % each, ${(holm * 1000).toInt() / 10.0} % with Holm")
        assertTrue(holm <= 0.06, "Holm called a null card in ${holm * 100} % of runs")
        assertTrue(anyOld > runs * 0.9)
    }

    @Test
    fun aRealCardIsStillCalled() {
        val strong = CardResult(1, "Engine", 3, mapOf(Stratum.G1_FIRST to CardCell(Estimate(20.0, 4.0), 0.4, 60)))
        val noise = (2..20).map { c -> CardResult(c, "Engine", 3, mapOf(Stratum.G1_FIRST to CardCell(Estimate(1.0, 5.0), 0.4, 30))) }
        val calls = results(listOf(strong) + noise).calls()
        assertEquals(listOf(1), calls.map { it.card })
        // Holm's step-down: the smallest p against alpha / m, the next against alpha / (m − 1), stopping at the first kept out.
        assertEquals(listOf(true, true, true), ShootoutResults.holm(listOf(0.001, 0.02, 0.04), 0.05).toList())
        assertEquals(listOf(true, false, false), ShootoutResults.holm(listOf(0.001, 0.03, 0.04), 0.05).toList())
        assertEquals(listOf(false, false), ShootoutResults.holm(listOf(0.03, 0.04), 0.05).toList())
    }

    @Test
    fun theRollIsCalledOnlyWhenItsRangeClearsZero() {
        val clear = results(emptyList(), mapOf(Stratum.G1_FIRST to Estimate(52.0, 4.0), Stratum.G1_SECOND to Estimate(69.0, 4.0))).roll()
        assertNotNull(clear)
        assertEquals(17.0, clear.difference, 1e-9)
        assertEquals(false, clear.choice, "going second is better")
        val close = results(emptyList(), mapOf(Stratum.G1_FIRST to Estimate(55.0, 6.0), Stratum.G1_SECOND to Estimate(58.0, 6.0))).roll()
        assertNull(assertNotNull(close).choice)
        assertNull(results(emptyList(), mapOf(Stratum.G1_FIRST to Estimate(55.0, 6.0))).roll())
    }

    @Test
    fun theNextCopyRecoversAPlantedValue() {
        // The next copy's worth read off the fit lands near the same number read off the truth, across the cards.
        var err = 0.0
        var spread = 0.0
        val runs = 3
        for (seed in 1L..runs) {
            val world = SyntheticDeck.matchup(seed + 40, judges = 3)
            val study = TrustStudy(world, SimJudge(world, seed * 53), SimJudge(world, seed * 59, judge = 1), seed)
            study.apprentice(360)
            val reporter = Reporter(world.spec, world.decks, 300, seed)
            val stratum = world.spec.strata.first()
            val truth = reporter.nextCopy(world.truth, stratum).map { it.value }
            val est = reporter.nextCopy(Fitter.fit(world.spec, study.model.filter { it.judge == 0 }).theta, stratum).map { it.value }
            err += sqrt(truth.indices.sumOf { (est[it] - truth[it]).let { e -> e * e } } / truth.size) / runs
            val mean = truth.average()
            spread += sqrt(truth.sumOf { (it - mean) * (it - mean) } / truth.size) / runs
        }
        println("[read] next copy: error ${(err * 100).toInt() / 100.0} points against a spread of ${(spread * 100).toInt() / 100.0} between cards")
        assertTrue(err < spread, "the next copy's worth was not recovered: error $err, spread $spread")
    }

    @Test
    fun aCardWorthMoreGivesMoreAsTheNextCopy() {
        val world = SyntheticDeck.matchup(3, judges = 3)
        val reporter = Reporter(world.spec, world.decks, 300, 3)
        val stratum = world.spec.strata.first()
        val base = reporter.nextCopy(world.truth, stratum).map { it.value }
        // Plant: card 0 made much better; its next copy gains, against the same deck as it was.
        val planted = world.truth.copyOf().also { it[world.spec.layout.card(0)] += 2.0 }
        val after = reporter.nextCopy(planted, stratum).map { it.value }
        assertTrue(after[0] > base[0] + 0.5, "${after[0]} against ${base[0]}")
        assertTrue(abs(after[0]) < 100)
    }

    @Test
    fun aWhatIfIsTheChangedDecksOwnWinRate() {
        // Read on the deck's own hands, a change lands on what the changed deck's own shuffles give — a swap one deck, one
        // more or one fewer the mix of decks it stands for — within the noise of their hands.
        val world = SyntheticDeck.matchup(5)
        val stratum = Stratum.G1_SECOND
        val copies = SyntheticDeck.COPIES
        val base = Reporter(world.spec, world.decks, 4000, 2)
        fun rate(c: IntArray): Double =
            Reporter(world.spec, Decks.matchup(DeckList(c), DeckList(SyntheticDeck.OPPONENT_COPIES)), 30000, 9)
                .contrasts(world.truth, stratum).first { it.target is Target.WinRate }.value
        fun moved(from: Int, to: Int) = copies.copyOf().also { it[from]--; it[to]++ }
        val now = rate(copies)
        val size = copies.sum()
        fun what(from: Int?, to: Int?) = base.variants(world.truth, stratum, listOf(Reporter.Change(from, to))).first().value
        // A swap: one copy of card 0 made card 12.
        val swap = rate(moved(0, 12)) - now
        // One more of card 7, in any other card's place.
        val more = copies.indices.filter { it != 7 && copies[it] > 0 }.sumOf { y -> copies[y].toDouble() / (size - copies[7]) * (rate(moved(y, 7)) - now) }
        // One fewer of card 2, its place any other card's.
        val fewer = copies.indices.filter { it != 2 && copies[it] > 0 }.sumOf { y -> copies[y].toDouble() / (size - copies[2]) * (rate(moved(2, y)) - now) }
        println("[read] what-if: swap ${what(0, 12)} against $swap, one more ${what(null, 7)} against $more, one fewer ${what(2, null)} against $fewer")
        assertEquals(swap, what(0, 12), 1.0)
        assertEquals(more, what(null, 7), 1.0)
        assertEquals(fewer, what(2, null), 1.0)
        assertTrue(abs(swap) > 1.5, "the swap should move the rate enough to be tested: $swap")
        // One more is the next copy's number.
        assertEquals(base.nextCopy(world.truth, stratum)[7].value, what(null, 7), 1e-12)
    }
}
