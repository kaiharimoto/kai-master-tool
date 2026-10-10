package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The paired design, studied on a deck whose truth is known (Phase G, G2's "done when"): version B swaps one blank for a
 * fourth starter, so it opens more often by an exact amount. Both versions are dealt by keys, hand k from the same keys,
 * and the question is put to the pairs.
 */
class CompareStudyTest {
    private val starter = 1
    private val blank = 2
    private val a = List(9) { starter } + List(31) { blank }
    private val b = List(10) { starter } + List(30) { blank }

    /** P(at least one starter in [hand] cards from a 40-card deck holding [starters]). */
    private fun truth(starters: Int, hand: Int = 5): Double = 1 - choose(40 - starters, hand) / choose(40, hand)

    private fun choose(n: Int, k: Int): Double = (0 until k).fold(1.0) { acc, i -> acc * (n - i) / (i + 1) }

    private fun answers(deck: List<Int>, seed: Long, n: Int, from: Int = 0) =
        (from until from + n).map { k -> if (starter in GoldfishHands.hand(deck, seed, k, true)) HandAnswer.YES else HandAnswer.NO }

    @Test
    fun theTruthIsAboutFourPoints() {
        val d = truth(10) - truth(9)
        assertTrue(d > 0.03 && d < 0.05, "B is better by ${PairedMath.points(d)} points")
    }

    @Test
    fun aKeyedPairOnlyDiffersWhereTheChangedCopyLands() {
        // Hand k of A and of B hold the same cards but the one copy that changed: never more than one card apart.
        (0 until 500).forEach { k ->
            val ha = GoldfishHands.hand(a, 9, k, true).sorted()
            val hb = GoldfishHands.hand(b, 9, k, true).sorted()
            val diff = ha.indices.count { ha[it] != hb[it] }
            assertTrue(diff <= 1, "hand $k: $ha against $hb")
        }
        // So B never loses a hand A opens: every changed pair is a hand B won.
        val p = Paired.of(answers(a, 9, 2000), answers(b, 9, 2000))
        assertEquals(0, p.onlyA)
        assertTrue(p.onlyB > 0)
    }

    @Test
    fun theIntervalCoversTheTruth() {
        // 200 seeds of 2,000 paired hands: the 95 % interval holds the true difference at least 93 % of the time.
        val t = truth(10) - truth(9)
        val covered = (1L..200L).count { seed ->
            val (lo, hi) = Paired.of(answers(a, seed, 2000), answers(b, seed, 2000)).interval
            t in lo..hi
        }
        assertTrue(covered >= 186, "covered $covered of 200")
    }

    @Test
    fun pairedNeedsAnEighthOfTheHands() {
        // The interval's width from n paired hands against the unpaired width from eight times as many hands per version
        // (two independent runs, each its own seed): the paired one is no wider, on average over 40 seeds.
        var paired = 0.0
        var unpaired = 0.0
        (1L..40L).forEach { seed ->
            val (lo, hi) = Paired.of(answers(a, seed, 250), answers(b, seed, 250)).interval
            paired += hi - lo
            val ya = answers(a, seed, 2000).count { it == HandAnswer.YES }
            val yb = answers(b, seed + 1000, 2000).count { it == HandAnswer.YES }
            val (ulo, uhi) = PairedMath.unpaired(ya, yb, 2000)
            unpaired += uhi - ulo
        }
        assertTrue(paired <= unpaired, "paired ${paired / 40} against unpaired ${unpaired / 40}")
    }

    @Test
    fun aSequentialRunStopsOnceItKnows() {
        // A batch at a time, stopping once settled: it finds B better well before 4,000 hands on most seeds.
        val stops = (1L..20L).map { seed ->
            var p = Paired()
            var n = 0
            while (n < 4000 && !p.settled()) {
                p += Paired.of(answers(a, seed, 100, n), answers(b, seed, 100, n))
                n += 100
            }
            Triple(n, p.verdict, p)
        }
        val found = stops.count { it.second == Paired.Verdict.B_BETTER }
        assertTrue(found >= 18, "B found better on $found of 20 seeds")
        val median = stops.map { it.first }.sorted()[10]
        assertTrue(median <= 1200, "the median run stopped at $median hands")
    }

    @Test
    fun mcnemarAndNewcombeHoldTheirKnownValues() {
        // The exact McNemar: six changed hands all one way, 2 × 0.5⁶; one against nine, 2 × (1 + 10) / 1024.
        assertTrue(abs(PairedMath.mcnemar(0, 6) - 0.03125) < 1e-12)
        assertTrue(abs(PairedMath.mcnemar(1, 9) - 22.0 / 1024) < 1e-12)
        assertEquals(1.0, PairedMath.mcnemar(5, 5))
        assertEquals(1.0, PairedMath.mcnemar(0, 0))
        // A thousand changed hands do not underflow.
        assertTrue(PairedMath.mcnemar(400, 600) < 1e-9)
        // Newcombe's method 10: with no changed hand the difference is 0 and its interval holds 0 and is narrow; it is
        // antisymmetric in the versions; it never leaves [−1, 1].
        val (lo0, hi0) = PairedMath.newcombe(500, 0, 0, 500)
        assertTrue(lo0 < 0 && hi0 > 0 && hi0 - lo0 < 0.02)
        val (lo, hi) = PairedMath.newcombe(40, 5, 15, 40)
        val (rlo, rhi) = PairedMath.newcombe(40, 15, 5, 40)
        assertTrue(abs(lo + rhi) < 1e-12 && abs(hi + rlo) < 1e-12)
        assertTrue(lo > 0.0 && hi < 0.25, "($lo, $hi)")
        val (elo, ehi) = PairedMath.newcombe(0, 0, 10, 0)
        assertTrue(elo >= -1.0 && ehi <= 1.0 && elo > 0.0)
        // Against the unpaired interval on the same counts, pairing that agrees on most hands is narrower.
        val (ulo, uhi) = PairedMath.unpaired(45, 55, 100)
        assertTrue(hi - lo < uhi - ulo)
    }

    @Test
    fun undecidedHandsAreCountedBothWays() {
        val a = listOf(HandAnswer.YES, HandAnswer.UNDECIDED, HandAnswer.NO, HandAnswer.UNDECIDED)
        val b = listOf(HandAnswer.YES, HandAnswer.YES, HandAnswer.UNDECIDED, HandAnswer.UNDECIDED)
        val p = Paired.of(a, b)
        // Undecided reads as a miss: (1, 0, 1, 2) and B ahead by a quarter.
        assertEquals(listOf(1, 0, 1, 2), listOf(p.both, p.onlyA, p.onlyB, p.neither))
        assertEquals(0.25, p.difference)
        assertEquals(2, p.undecidedA)
        assertEquals(2, p.undecidedB)
        val (least, most) = p.bounds
        assertTrue(abs(least - (-0.25)) < 1e-12 && abs(most - 0.75) < 1e-12)
        assertEquals("+25.0", PairedMath.points(p.difference))
        assertEquals("−0.4", PairedMath.points(-0.004))
        assertEquals("0.0", PairedMath.points(0.0004))
    }
}
