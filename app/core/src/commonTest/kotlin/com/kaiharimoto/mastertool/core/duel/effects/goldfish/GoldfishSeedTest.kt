package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.deck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.pondMonster
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Hands and seeds (D.md §5.3, step 4's `GoldfishSeedTest`): hand k is the duel's own Fisher–Yates keyed by
 * `DuelRandom.forRoll(seed, k)`, so the same seed deals the same hands on every run and every platform. The orders below are
 * pinned: a change to them is a change to every number a seed ever proved. (The APK runs this module's JVM build, so the JVM
 * test stands for Android too; there is no Android unit-test source set in `:core`.)
 */
class GoldfishSeedTest {
    private val forty = (1..40).toList()

    @Test
    fun handKIsTheRiffleKeyedByItsRoll() {
        (0 until 5).forEach { k ->
            assertEquals(DuelRandom.riffle(forty, DuelRandom.forRoll(7, k).nextLong()), GoldfishHands.order(forty, 7, k))
        }
    }

    @Test
    fun theOrdersArePinned() {
        assertEquals(PINNED_7_0, GoldfishHands.order(forty, 7, 0).take(10))
        assertEquals(PINNED_7_1, GoldfishHands.order(forty, 7, 1).take(10))
        assertEquals(PINNED_42_999, GoldfishHands.order(forty, 42, 999).take(10))
    }

    @Test
    fun theSameSeedDealsTheSameHandsAndAnotherSeedOthers() {
        val a = (0 until 50).map { GoldfishHands.hand(forty, 7, it, true) }
        val b = (0 until 50).map { GoldfishHands.hand(forty, 7, it, true) }
        assertEquals(a, b)
        assertNotEquals(a, (0 until 50).map { GoldfishHands.hand(forty, 8, it, true) })
        // Going second is the same order, one card more.
        (0 until 50).forEach { k -> assertEquals(GoldfishHands.hand(forty, 7, k, true), GoldfishHands.hand(forty, 7, k, false).take(5)) }
    }

    @Test
    fun aRunIsTheSameEveryTime() {
        val kit = GoldfishFixtures.kit()
        val setup = GoldfishSetup(GoldfishDeck(deck(FROG to 3, CALLER to 3, STONE to 34)), target(BoardCond.Controls(pondMonster, 2)), hands = 150, seed = 21)
        val a = Goldfish.runHere(setup, kit)
        val b = Goldfish.runHere(setup, GoldfishFixtures.kit())
        assertEquals(a.copy(ms = 0), b.copy(ms = 0))
    }

    companion object {
        val PINNED_7_0 = listOf(16, 7, 37, 8, 10, 33, 6, 36, 30, 26)
        val PINNED_7_1 = listOf(29, 17, 4, 37, 20, 27, 40, 25, 14, 11)
        val PINNED_42_999 = listOf(2, 35, 27, 32, 40, 3, 15, 36, 33, 39)
    }
}
