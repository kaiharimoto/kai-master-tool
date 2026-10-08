package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WELL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mapper over many dealt hands (M.md §2.5) on the toy deck, every count checked against the hands themselves. */
class MapperTest {
    private val kit = GoldfishFixtures.kit()
    private val main: List<Int> = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }
    private val toy: GoldfishDeck = GoldfishDeck(main, id = "toy", fingerprint = "fp")

    private fun run(hands: Int = 60, first: Boolean = true) = Mapper.runHere(MapperSetup(toy, first, hands, seed = 3), kit, BoardLibrary(deck = "fp", first = first))

    @Test
    fun everyHandIsTheGoldfishsAndBelongsToOnePart() {
        val (r, _) = run()
        assertEquals(60, r.hands)
        assertEquals(60, r.dealt.size)
        assertEquals(60, r.parts.sumOf { it.hands })
        r.dealt.forEachIndexed { k, p ->
            val hand = GoldfishHands.hand(main, 3, k, true)
            // A hand's part is its engine cards; the cards played as inert are its fodder, dealt beside it.
            assertEquals(hand.filterNot(kit::inert).sorted(), r.parts[p].cards)
            assertEquals(hand.count(kit::inert), r.parts[p].fodder.size)
        }
        // Hands that differ only in their blanks share a map: fewer maps than hands. An inert card something could still
        // match (a Pond Elder: the Caller looks for a Pond monster) is no blank, so it tells two hands apart.
        assertTrue(r.parts.size < 60)
        assertEquals(r.parts.map { it.cards to it.fodder }.distinct(), r.parts.map { it.cards to it.fodder })
    }

    @Test
    fun aShareIsTheHandsThatCanMakeABoardLikeThat() {
        val (r, lib) = run()
        // A negate on the board: the Sage, summoned from the hand or called from the Deck by a Caller. Nothing else has one.
        val can = (0 until 60).count { k -> GoldfishHands.hand(main, 3, k, true).let { CALLER in it || SAGE in it } }
        assertEquals(can, r.reaching { it.negates >= 1 })
        val negate = r.share(BoardPreset(filters = listOf(BoardFilter("negates", min = 1.0))))
        assertEquals(can, negate.hits)
        val (lo, hi) = negate.range
        assertTrue(lo <= negate.share && negate.share <= hi)
        assertEquals(can, r.atLeast(BoardTraits(interruptions = 1, negates = 1)).hits)
        assertEquals(60, r.atLeast(BoardTraits()).hits)
        // The library took the best board of each field as the hands came (a board beaten by a later hand's stays: runs only
        // add), never two that measure the same, and the stones dealt beside a hand are on none of them.
        assertTrue(r.added.isNotEmpty() && r.added.all { it in lib.byKey })
        assertEquals(lib.boards.size, r.added.size)
        lib.boards.groupBy { BoardLibrary.field(it.cards) }.values.forEach { same ->
            val v = same.map { BoardLibrary.judged(it.cards, it.traits).toList() }
            assertEquals(v.distinct(), v, "one field's boards: no two measure the same")
        }
        assertTrue(lib.boards.none { STONE in it.cards.hand || STONE in it.cards.gy })
        assertEquals(r.traits.distinct(), r.traits)
        assertEquals(0, r.incomplete)
    }

    @Test
    fun aRunReadsBackAndKnowsWhenItIsStale() {
        val (r, _) = run(hands = 20)
        val back = assertNotNull(MapperRun.decode(r.encode()))
        assertEquals(r, back)
        assertTrue(r.encode().contains("\"keys\":${BoardKey.VERSION}"))
        assertTrue(!r.stale("fp", r.library))
        assertTrue(r.stale("another deck", r.library))
        assertTrue(r.stale("fp", "other scripts"))
        assertNull(MapperRun.decode("{oops"))
    }

    @Test
    fun goingSecondDealsSixAndGrowsTheOtherLibrary() {
        val (r, lib) = run(hands = 20, first = false)
        assertTrue(!lib.first)
        r.parts.forEach { assertEquals(6, it.cards.size + it.fodder.size) }
    }

    @Test
    fun aDeckWhoseOrderIsReadMapsEveryHandOnItsOwn() {
        val well = GoldfishFixtures.deck(CALLER to 3, FROG to 3, SAGE to 3, WELL to 3) + List(28) { STONE }
        val (r, _) = Mapper.runHere(MapperSetup(GoldfishDeck(well), hands = 12, seed = 5), kit, BoardLibrary())
        assertEquals(12, r.parts.size)
        assertEquals((0 until 12).toList(), r.dealt)
    }

    @Test
    fun theSameRunTwiceIsTheSameLibrary() {
        val a = run(hands = 40)
        val b = run(hands = 40)
        assertEquals(a.second, b.second)
        assertEquals(a.first.copy(ms = 0), b.first.copy(ms = 0))
    }
}
