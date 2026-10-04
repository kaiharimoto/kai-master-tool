package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.CardRating
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Ratings
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Picker
import com.kaiharimoto.mastertool.core.shootout.select.PickerSettings
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.RealWorld
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.sim.SyntheticDeck
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The picker's honesty rules (Phase S §3), each on its own: plain hands, repeats, balance, pinning, the stop rule. */
class ShootoutPickerTest {

    private val world = SyntheticDeck.matchup(seed = 21)
    private val spec = world.spec

    /** A cheap picker: few candidates, no climbing, so a test can ask it hundreds of times. */
    private fun picker(settings: PickerSettings) = Picker(
        spec, world.decks,
        settings.copy(handCandidates = 6, compareCandidates = 4, climb = 0, targetPool = 20),
        seed = 3,
    )

    private fun rated(n: Int, stratum: (Int) -> Stratum, plain: Boolean = false): List<Trial> {
        val r = Random(5)
        return List(n) {
            val s = stratum(it)
            val (hand, opp) = world.decks.deal(s, r)
            Rated(hand, opp, s, Answer.entries[r.nextInt(5)], plain = plain)
        }
    }

    @Test
    fun aboutOneTrialInSixIsAPlainHand() {
        val p = picker(PickerSettings())
        val fit = Fitter.fit(spec, emptyList())
        val reasons = List(300) { p.next(emptyList(), fit).reason }
        val plain = reasons.count { it == Reason.PLAIN }
        // 1/6 of 300 is 50; a binomial's sd is 6.5.
        assertTrue(plain in 32..68, "$plain of 300 were plain")
        assertTrue(reasons.count { it == Reason.CHOSEN } > 200)
    }

    @Test
    fun repeatsComeBackOnlyOnceTheyAreOld() {
        val trials = rated(20, { if (it % 2 == 0) Stratum.G1_FIRST else Stratum.G1_SECOND })
        val p = picker(PickerSettings(plainShare = 0.0, repeatShare = 1.0, repeatAge = 8))
        val fit = Fitter.fit(spec, trials)
        val old = trials.take(12).map { (it as Rated).hand }.toSet()
        repeat(40) {
            val next = p.next(trials, fit)
            assertEquals(Reason.REPEAT, next.reason)
            assertTrue((next as Proposal.Rate).hand in old, "a repeat must be at least eight trials old")
        }
    }

    @Test
    fun aStratumThatFallsBehindIsDealtNext() {
        val p = picker(PickerSettings())
        assertEquals(spec.strata, p.lagging(rated(4, { if (it % 2 == 0) Stratum.G1_FIRST else Stratum.G1_SECOND })))
        assertEquals(listOf(Stratum.G1_SECOND), p.lagging(rated(10, { Stratum.G1_FIRST })))
    }

    @Test
    fun aPinnedSessionDealsOneStratum() {
        val p = picker(PickerSettings(pinned = Stratum.G1_SECOND))
        val fit = Fitter.fit(spec, emptyList())
        assertTrue(List(30) { p.next(emptyList(), fit).stratum }.all { it == Stratum.G1_SECOND })
    }

    @Test
    fun aComparisonIsOneCardApart() {
        val trials = rated(12, { Stratum.G1_FIRST })
        val p = picker(PickerSettings())
        val fit = Fitter.fit(spec, trials)
        val c = p.chosen(trials, fit, listOf(Stratum.G1_FIRST), comparisonsOnly = true) as Proposal.Compare
        val left = c.left.toCounts()
        val right = c.right.toCounts()
        assertEquals(2, left.indices.sumOf { abs(left[it] - right[it]) }, "one card given up, one taken")
        assertEquals(c.left.size, c.right.size)
    }

    @Test
    fun theRealWorldCheckReadsOnlyPlainHands() {
        val chosen = rated(30, { Stratum.G1_FIRST })
        val plain = rated(10, { Stratum.G1_FIRST }, plain = true)
        val trials = chosen + plain
        val fit = Fitter.fit(spec, trials)
        val checks = RealWorld.check(spec, fit, Reporter(spec, world.decks, 50), trials)
        assertEquals(10, checks.first { it.stratum == Stratum.G1_FIRST }.plainTrials)
        assertEquals(0, checks.first { it.stratum == Stratum.G1_SECOND }.plainTrials)
    }

    @Test
    fun theStopRuleCountsCardsKnownInEveryStratum() {
        fun rating(card: Int, s: Stratum, sd: Double) = CardRating(card, s, Estimate(10.0, sd), 0.3)
        val ratings = Ratings(
            cards = listOf(
                rating(0, Stratum.G1_FIRST, 1.0), rating(0, Stratum.G1_SECOND, 1.0), // known in both
                rating(1, Stratum.G1_FIRST, 1.0), rating(1, Stratum.G1_SECOND, 3.0), // not in the second
                CardRating(2, Stratum.G1_FIRST, Estimate(0.0, 0.0), 0.0), // never drawn: left out
            ),
            pairs = emptyList(),
            winRates = emptyMap(),
        )
        val settled = StopRule(halfWidth = 3.0, share = 0.5).read(ratings, listOf(Stratum.G1_FIRST, Stratum.G1_SECOND))
        assertEquals(1, settled.known)
        assertEquals(2, settled.of)
        assertTrue(settled.enough)
        assertEquals("1 of 2 cards known within ±3 points", settled.toString())
        assertFalse(StopRule(3.0, 0.875).read(ratings, listOf(Stratum.G1_FIRST, Stratum.G1_SECOND)).enough)
        // Only the first stratum in play: both cards are known there.
        assertEquals(2, StopRule(3.0).read(ratings, listOf(Stratum.G1_FIRST)).known)
    }
}
