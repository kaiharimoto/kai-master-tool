package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.deck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.pondMonster
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every number opens its hands (Phase D step 4, agent (c)): the hands a number stands for are exactly the ones it counts,
 * each line's skeleton carries its cards for the pane's strip, a kept result says when it went stale, and its hands are
 * made again only on the deck they were dealt from.
 */
class GoldfishBrowseTest {
    private val kit = GoldfishFixtures.kit()
    private val pond = GoldfishDeck(deck(FROG to 3, CALLER to 3, STONE to 34), id = "pond", fingerprint = "fp-1", name = "Pond")
    private val two = target(BoardCond.Controls(pondMonster, 2), name = "two")
    private val setup = GoldfishSetup(pond, two, first = true, hands = 300, seed = 7)
    private val r = Goldfish.runHere(setup, kit)

    @Test
    fun everyNumberListsTheHandsItCounts() {
        listOf(HandEnd.REACHED, HandEnd.NO_LINE, HandEnd.UNDECIDED).forEach { end ->
            val hands = GoldfishBrowse.hands(r, HandPick.End(end))
            assertEquals(GoldfishBrowse.count(r, HandPick.End(end)), hands.size, end.name)
            assertTrue(hands.all { it.end == end })
            assertEquals(hands.map { it.index }.sorted(), hands.map { it.index }, "in index order")
        }
        r.lines.forEachIndexed { i, line ->
            val hands = GoldfishBrowse.hands(r, HandPick.Line(i))
            assertEquals(line.count, hands.size, line.skeleton)
            assertEquals(GoldfishBrowse.count(r, HandPick.Line(i)), hands.size)
        }
        assertEquals(r.reached, r.lines.indices.sumOf { GoldfishBrowse.hands(r, HandPick.Line(it)).size })
        assertEquals(r.heldUnknown, GoldfishBrowse.hands(r, HandPick.HeldUnknown).size)
        assertEquals(r.touchedUnknown, GoldfishBrowse.hands(r, HandPick.TouchedUnknown).size)
        assertTrue(GoldfishBrowse.title(r, HandPick.End(HandEnd.REACHED)).startsWith("Reached — ${r.reached} of 300 hands ("))
        val (a, b, c) = GoldfishBrowse.shares(r)
        assertEquals(1.0, a + b + c, 1e-9)
    }

    @Test
    fun aLineCarriesItsCardsInTheSkeletonsOrder() {
        assertTrue(r.lines.isNotEmpty())
        r.lines.forEach { line ->
            assertEquals(line.skeleton.split(" → ").size, line.cards.size, line.toString())
            assertEquals(line.skeleton.split(" → ").map { it.removePrefix("Set ") }, line.cards.map(kit::name))
            assertTrue(CALLER in line.cards)
        }
    }

    @Test
    fun aKeptResultSaysWhenItIsStaleAndOpensItsHandsOnlyOnItsOwnDeck() {
        val lib = kit.trust.library(pond.main + pond.extra)
        assertEquals(emptyList(), GoldfishBrowse.stale(r, "fp-1", lib))
        assertEquals(listOf("the deck changed since"), GoldfishBrowse.stale(r, "fp-2", lib))
        assertEquals(listOf("a written effect it trusted changed since"), GoldfishBrowse.stale(r, "fp-1", "000000000000"))
        // The same hands again, from the kept numbers alone.
        val (again, why) = GoldfishBrowse.setup(r, pond)
        assertNull(why)
        val s = assertNotNull(again)
        val k = GoldfishBrowse.hands(r, HandPick.End(HandEnd.REACHED)).first().index
        val replay = GoldfishReplay.of(s, kit, k)
        assertEquals(HandEnd.REACHED, replay.end)
        assertEquals(r.outcomes[k].hand, GoldfishHands.hand(pond.main, s.seed, k, s.first))
        // On a changed deck they are not offered.
        val (none, said) = GoldfishBrowse.setup(r, pond.copy(fingerprint = "fp-2"))
        assertNull(none)
        assertTrue(said.orEmpty().contains("deck changed"))
    }

    @Test
    fun keptResultsReadNewestFirstAndTypedNumbersAreRead() {
        val older = r.copy(at = 10L, seed = 1)
        val newer = r.copy(at = 20L, seed = 2)
        assertEquals(listOf(2L, 1L), GoldfishBrowse.newestFirst(listOf(older, newer)).map { it.seed })
        assertEquals(42L, GoldfishBrowse.seedOf(" 42 "))
        assertNull(GoldfishBrowse.seedOf("seven"))
        assertEquals(2000, GoldfishBrowse.handsOf("2,000"))
        assertNull(GoldfishBrowse.handsOf("0"))
        assertNull(GoldfishBrowse.handsOf("20001"))
        assertEquals("Hand 1", GoldfishBrowse.handName(r.outcomes.first()))
        assertTrue(GoldfishBrowse.replayName(r, 12).contains("hand 13 of 300, seed 7, going first"))
        assertTrue(GoldfishBrowse.progressWords(GoldfishProgress(812, 2000, 401, 21_000)).startsWith("812 of 2,000 hands · 401 reached · 38 hands a second"))
    }
}
