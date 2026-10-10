package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.choose
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The starter table's odds counted exactly (Phase G, G5), held to the hypergeometric worked by hand. */
class StarterOddsTest {
    private val a = 1
    private val b = 2
    private val c = 3
    private val main = List(3) { a } + List(3) { b } + List(2) { c } + List(32) { 9 }

    private fun close(x: Double, y: Double) = assertTrue(abs(x - y) < 1e-12, "$x against $y")

    @Test
    fun oneCardIsTheChanceOfDrawingIt() {
        close(1 - choose(37, 5) / choose(40, 5), assertNotNull(StarterOdds.probability(listOf(listOf(a)), main, 5)))
        close(1 - choose(37, 6) / choose(40, 6), assertNotNull(StarterOdds.probability(listOf(listOf(a)), main, 6)))
    }

    @Test
    fun aPairBesideAStarterIsTheUnion() {
        // A alone, or B with C: 1 − P(no A and not (B and C)).
        val p = assertNotNull(StarterOdds.probability(listOf(listOf(a), listOf(b, c)), main, 5))
        // Hands with no A: from 37 cards. Of those, the ones with at least one B and at least one C, by inclusion–exclusion.
        val noA = choose(37, 5)
        val noANoB = choose(34, 5)
        val noANoC = choose(35, 5)
        val noANoBNoC = choose(32, 5)
        val bAndC = noA - noANoB - noANoC + noANoBNoC
        close(1 - (noA - bAndC) / choose(40, 5), p)
        // A pair whose card opens alone adds nothing.
        close(assertNotNull(StarterOdds.probability(listOf(listOf(a)), main, 5)), assertNotNull(StarterOdds.probability(listOf(listOf(a), listOf(a, b)), main, 5)))
        // Two copies of one card: at least two of it.
        close(1 - (choose(37, 5) + 3 * choose(37, 4)) / choose(40, 5), assertNotNull(StarterOdds.probability(listOf(listOf(a, a)), main, 5)))
        assertEquals(0.0, StarterOdds.probability(emptyList(), main, 5))
    }

    @Test
    fun aSweepHoldsTheDecksSize() {
        val s = StarterOdds.sweep(listOf(listOf(a)), main, a, 3)
        assertEquals(listOf(0, 1, 2, 3), s.map { it.first })
        assertEquals(0.0, s[0].second)
        close(1 - choose(39, 5) / choose(40, 5), s[1].second)
        close(1 - choose(37, 5) / choose(40, 5), s[3].second)
    }
}
