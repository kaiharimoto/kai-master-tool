package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The board library (M.md §2.6): runs only add, every kept line replays to its board, and an unreadable file is never emptied. */
class BoardLibraryTest {
    private val kit = GoldfishFixtures.kit()
    private val main = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }

    private fun mapped(deal: MapDeal) = MapSearch(kit).map(deal.table(main, emptyList(), kit))

    @Test
    fun aKnownBoardKeepsItsCheapestLinesAndItsStartersOnce() {
        val one = MapDeal(listOf(CALLER), seed = 1)
        val again = MapDeal(listOf(CALLER), seed = 2)
        val two = MapDeal(listOf(CALLER, CALLER))
        val lib = BoardLibrary().add(one, mapped(one), run = 1).add(again, mapped(again), run = 2).add(two, mapped(two), run = 3)
        val frog = lib.boards.single { it.cards.monsters == listOf(FROG) && it.cards.hand.isEmpty() && it.cards.gy == listOf(CALLER) }
        // Only one Caller makes this board (two leave a Caller in hand or a second in the GY); dealt twice, it is one starter.
        assertEquals(listOf(listOf(CALLER)), frog.starters)
        assertTrue(frog.lines.size in 1..BoardLibrary.LINES)
        assertEquals(frog.lines.sortedBy { it.cost }, frog.lines)
        assertEquals(3, lib.runs)
        assertEquals(lib.boards.map { it.key }.sorted(), lib.boards.map { it.key })
        // Two Callers reach boards one cannot: two monsters.
        assertTrue(lib.boards.any { it.cards.monsters.size == 2 && it.starters == listOf(listOf(CALLER, CALLER)) })
    }

    @Test
    fun everyKeptLineReplaysToItsBoard() {
        val deal = MapDeal(listOf(CALLER, WALL))
        val lib = BoardLibrary().add(deal, mapped(deal), run = 1)
        assertTrue(lib.boards.size > 2)
        lib.boards.forEach { e ->
            e.lines.forEach { line ->
                val r = MapReplay.of(line, main, emptyList(), kit)
                assertNull(r.problem, "replaying to ${e.cards}")
                assertEquals(e.key, BoardKey.of(BoardCards.of(r.table, 0)), "the replay of a line to ${e.cards}")
            }
        }
    }

    @Test
    fun aChangedDeckMarksEveryBoardStaleUntilReachedAgain() {
        val deal = MapDeal(listOf(CALLER))
        val lib = BoardLibrary(deck = "a", library = "x").add(deal, mapped(deal), run = 1)
        val moved = lib.rebased("b", "x")
        assertTrue(moved.boards.all { it.stale })
        assertTrue(moved.live.isEmpty())
        val again = moved.add(deal, mapped(deal), run = 2)
        assertTrue(again.boards.none { it.stale })
        assertEquals(lib.boards.map { it.key }, again.boards.map { it.key })
    }

    @Test
    fun stressResultsSurviveARemap() {
        val deal = MapDeal(listOf(CALLER))
        val lib = BoardLibrary().add(deal, mapped(deal), run = 1)
        val sage = lib.boards.single { it.cards.monsters == listOf(SAGE) }
        val tested = lib.through(sage.key, "ash", 1)
        val again = tested.add(deal, mapped(deal), run = 2)
        assertEquals(mapOf("ash" to 1), again.byKey.getValue(sage.key).traits.through)
    }

    @Test
    fun itRoundTripsAndAnUnreadableFileIsNull() {
        val deal = MapDeal(listOf(CALLER, WALL))
        val lib = BoardLibrary(deckId = "d1", deck = "fp").add(deal, mapped(deal), run = 1)
        val back = assertNotNull(BoardLibrary.decode(lib.encode()))
        assertEquals(lib, back)
        assertNull(BoardLibrary.decode("{not json"))
        // A newer build's field is ignored.
        assertNotNull(BoardLibrary.decode("""{"version":2,"deck":"fp","future":[1,2]}"""))
    }
}
