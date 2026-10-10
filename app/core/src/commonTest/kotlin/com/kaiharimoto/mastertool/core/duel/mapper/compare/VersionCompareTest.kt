package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two versions of the toy deck on the same hands (Phase G, G2–G4), every count held to the hands themselves. */
class VersionCompareTest {
    private val kit = GoldfishFixtures.kit()
    private val main: List<Int> = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }
    private val toy = GoldfishDeck(main, id = "toy", fingerprint = "fp")
    private val negate = CompareAsk("a negate") { it.negates >= 1 }
    private val notExtra: (Int) -> Boolean = { false }

    /** Whether hand [k] of [deck] can make a negate: a Sage summoned, or one called from the Deck by a Caller. */
    private fun opens(deck: GoldfishDeck, k: Int, seed: Long): Boolean =
        GoldfishHands.hand(deck.main, seed, k, true, keyAs = deck.keyAs).let { CALLER in it || SAGE in it }

    @Test
    fun aFourthCallerIsBetterOnTheSameHands() = runTest {
        val b = assertNotNull(Variants.apply(toy, listOf(DeckChange(out = STONE, into = CALLER)), notExtra))
        val c = VersionCompare.run(CompareSetup(toy, b, negate, seed = 5, batch = 120, most = 240, sequential = false), kit, workers = 1)
        assertTrue(c.guard!!.ok)
        assertEquals(240, c.paired.hands)
        // Every count is the hands' own: A's from A's deal, B's from B's.
        val ya = (0 until 240).map { opens(toy, it, 5) }
        val yb = (0 until 240).map { opens(b, it, 5) }
        assertEquals(ya.indices.count { ya[it] && yb[it] }, c.paired.both)
        assertEquals(ya.indices.count { ya[it] && !yb[it] }, c.paired.onlyA)
        assertEquals(ya.indices.count { !ya[it] && yb[it] }, c.paired.onlyB)
        // A stone cut for a Caller never loses a hand: the stone did nothing.
        assertEquals(0, c.paired.onlyA)
        assertTrue(c.paired.onlyB > 0)
        assertEquals(c.paired.onlyB, c.changed.size)
        // The Caller is dealt where the stone it replaced was: a changed hand is the same hand with a Caller for a stone.
        c.changed.forEach { h ->
            assertEquals(HandAnswer.NO, h.a)
            assertEquals(HandAnswer.YES, h.b)
            assertEquals(0, h.handA.count { it == CALLER }, "${h.handA} / ${h.handB}")
            assertEquals(1, h.handB.count { it == CALLER }, "${h.handA} / ${h.handB}")
            assertEquals(h.handA.size, h.handB.size)
            assertEquals(h.handA.count { it == STONE } - 1, h.handB.count { it == STONE })
        }
        // Every other hand is the same cards.
        (0 until 240).filter { k -> c.changed.none { it.k == k } }.take(40).forEach { k ->
            val ha = GoldfishHands.hand(toy.main, 5, k, true)
            val hb = GoldfishHands.hand(b.main, 5, k, true, keyAs = b.keyAs)
            assertTrue(ha.sorted() == hb.sorted() || (CALLER in hb && STONE in ha), "hand $k: $ha / $hb")
        }
        assertTrue(c.paired.difference > 0)
    }

    @Test
    fun ablatingABlankIsExactlyNothingAndMapsNothing() = runTest {
        val row = assertNotNull(Ablation.run(toy, STONE, Ablation.Copies.ONE, negate, kit, hands = 200, workers = 1))
        assertEquals(0, row.comparison.paired.discordant)
        assertEquals(0.0, row.comparison.paired.difference)
        // The stone is a blank and so is what took its place: every map of B is one of A's.
        assertEquals(0, row.comparison.mappedB)
        assertTrue(row.comparison.cachedB > 0)
    }

    @Test
    fun withoutItsSagesTheDeckLosesEveryNegate() = runTest {
        val row = assertNotNull(Ablation.run(toy, SAGE, Ablation.Copies.ALL, negate, kit, hands = 120, workers = 1))
        val ya = (0 until 120).count { opens(toy, it, 1) }
        assertEquals(ya, row.comparison.paired.onlyA)
        assertEquals(0, row.comparison.paired.both + row.comparison.paired.onlyB)
        assertEquals(ya, row.bricks.size)
        assertTrue(row.worth > 0)
        assertTrue(row.comparison.kindsLost.any { it.negates >= 1 })
        // A card the deck does not hold has no worth to measure.
        assertEquals(null, Ablation.run(toy, 12345, Ablation.Copies.ONE, negate, kit, workers = 1))
    }

    @Test
    fun aDifferingCardTheEngineCannotPlayIsRefused() = runTest {
        // An Elder plays as inert, yet a Caller could pick it: a comparison with it would be biased, so it is refused.
        val b = assertNotNull(Variants.apply(toy, listOf(DeckChange(out = STONE, into = ELDER)), notExtra))
        val check = CoverageGuard.check(toy, b, kit)
        assertFalse(check.ok)
        assertEquals(listOf(ELDER), check.unwritten)
        val c = VersionCompare.run(CompareSetup(toy, b, negate), kit, workers = 1)
        assertEquals(0, c.paired.hands)
        assertEquals(listOf(ELDER), c.guard?.unwritten)
        // The same deck is no comparison either.
        assertFalse(CoverageGuard.check(toy, toy, kit).ok)
        // A cut more than the deck holds is no variant.
        assertEquals(null, Variants.apply(toy, listOf(DeckChange(out = WALL, copies = 4)), notExtra))
    }

    @Test
    fun anEngineCardCutForAHandTrapIsAQuestion() {
        // A Wall answers the other player only: with no opponent the goldfish cannot value it.
        val b = assertNotNull(Variants.apply(toy, listOf(DeckChange(out = CALLER, into = WALL)), notExtra))
        val check = CoverageGuard.check(toy, b, kit)
        assertTrue(check.ok)
        assertEquals(1, check.questions.size)
        assertTrue("Pond Wall" in check.questions.single(), check.questions.single())
    }

    @Test
    fun aOneCopySwapCostsAboutOneRunOnceTheDeckIsMapped() = runTest {
        val cache = MapCache()
        val hands = 200
        // The deck as it is, mapped once (a blank swapped for a blank maps A and nothing else).
        val same = assertNotNull(Ablation.run(toy, STONE, Ablation.Copies.ONE, negate, kit, cache, hands = hands, workers = 1))
        assertTrue(same.comparison.mappedA > 0)
        val b = assertNotNull(Variants.apply(toy, listOf(DeckChange(out = STONE, into = CALLER)), notExtra))
        val c = VersionCompare.run(CompareSetup(toy, b, negate, batch = hands, most = hands, sequential = false), kit, cache, workers = 1)
        assertEquals(0, c.mappedA)
        // What it cost is B's own run, and little more: no map of A is searched again.
        val one = Mapper.runHere(MapperSetup(b, hands = hands), kit, BoardLibrary(deck = "b")).first.moves
        assertTrue(c.moves <= 1.3 * one, "the comparison cost ${c.moves} moves against one run's $one")
    }

    @Test
    fun theBlankIsNothingAnywhere() {
        assertTrue(kit.inert(GoldfishKit.BLANK))
        assertEquals("a blank card", kit.name(GoldfishKit.BLANK))
        val v = assertNotNull(Ablation.variant(toy, SAGE, Ablation.Copies.ALL, kit))
        assertEquals(40, v.main.size)
        assertEquals(3, v.main.count { it == GoldfishKit.BLANK })
        assertEquals(0, v.main.count { it == SAGE })
    }
}
