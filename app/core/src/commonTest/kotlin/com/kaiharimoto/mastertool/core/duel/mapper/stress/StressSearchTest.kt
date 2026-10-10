package com.kaiharimoto.mastertool.core.duel.mapper.stress

import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapLine
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.MapperWords
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stress tests' search (E.md §4.1) on the goldfish's Pond cards, against our own Ash-like (Example Ward: negates an
 * effect that searches) and Effect Veiler-like (Example Veil) cards, where every answer can be worked out by hand.
 *
 * Pond Net searches a Pond card, Pond Caller Special Summons a Pond monster from the Deck, Pond Sage negates an activation
 * from the field (one interruption), and Pond Wall is a Trap (one interruption once Set).
 */
class StressSearchTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(GoldfishFixtures.scripts + FxRef.script(FxRef.WARD) + FxRef.script(FxRef.VEIL)))
    private val main = GoldfishFixtures.deck(CALLER to 3, NET to 3, SAGE to 3, WALL to 3, FROG to 3) + List(25) { STONE }

    private fun table(hand: List<Int>, against: List<Int>) = StressDeal(MapDeal(hand), against).table(main, emptyList(), kit)

    private fun stress(hand: List<Int>, against: List<Int>, ceiling: Int? = null): StressResult =
        StressSearch(kit).test(table(hand, against), ceiling = ceiling)

    private fun unopposed(hand: List<Int>): Int =
        MapSearch(kit).map(MapDeal(hand).table(main, emptyList(), kit)).ends.maxOf { it.traits.interruptions }

    @Test
    fun withNothingInTheirHandTheGuaranteeIsTheMapsBest() {
        listOf(listOf(CALLER), listOf(NET), listOf(CALLER, NET), listOf(NET, NET)).forEach { hand ->
            val r = stress(hand, emptyList())
            assertTrue(r.complete, "$hand")
            assertEquals(unopposed(hand), r.guarantee, "$hand")
            assertTrue(r.chokes.isEmpty())
        }
    }

    @Test
    fun leadingWithTheSageProtectsTheSearch() {
        // Unopposed, Caller (for Sage) and Net (for Wall) make two in either order. Against Ward, Net first is answered and
        // Wall never comes; Caller first puts Sage on the field, whose negate stops Ward: two whatever they do.
        val hand = listOf(CALLER, NET)
        assertEquals(2, unopposed(hand))
        val r = stress(hand, listOf(FxRef.WARD), ceiling = 2)
        assertTrue(r.complete)
        assertEquals(2, r.guarantee)
        val first = r.line.first { it.seat == StressSearch.YOU }
        assertEquals(CALLER, first.card, "the line leads with Caller: ${r.line}")
        assertEquals(2, r.endTraits?.interruptions)
        assertEquals(0, r.cost, "nothing is given up to play around Ward here")
    }

    @Test
    fun wardAndVeilTogetherKeepItToOne() {
        // Veil negates Sage's effects for the turn, in answer to its negate or before it: Ward then stops the search.
        val r = stress(listOf(CALLER, NET), listOf(FxRef.WARD, FxRef.VEIL))
        assertTrue(r.complete)
        assertEquals(1, r.guarantee)
        assertTrue(r.chokes.isNotEmpty(), "they spend an interruption on the line: ${r.line}")
        assertEquals(1, r.endTraits?.interruptions)
    }

    @Test
    fun twoSearchesAgainstWardKeepOne() {
        // Net and Net: whichever they answer first, one search is left for Sage (Normal Summoned) or Wall, never both.
        val r = stress(listOf(NET, NET), listOf(FxRef.WARD))
        assertTrue(r.complete)
        assertEquals(2, unopposed(listOf(NET, NET)))
        assertEquals(1, r.guarantee)
        val choke = r.line[r.chokes.first()]
        assertEquals(FxRef.WARD, choke.card)
        assertTrue(choke.move is FxMove.Activate)
        assertEquals(2, r.ifTheyPass, "the same choices, unanswered, make two")
    }

    @Test
    fun aGuaranteeOfNothingStillShowsTheAttemptAndItsAnswer() {
        // Net alone makes one unopposed; Ward answers its search, so nothing is guaranteed. The line is the one a player
        // tries — Net, answered by Ward — never an empty turn that "keeps" nothing.
        val r = stress(listOf(NET), listOf(FxRef.WARD), ceiling = 1)
        assertEquals(0, r.guarantee)
        assertTrue(r.complete)
        assertEquals(NET, r.line.first { it.seat == StressSearch.YOU }.card)
        assertEquals(FxRef.WARD, r.line[r.chokes.single()].card)
        assertEquals(1, r.ifTheyPass)
        assertEquals(0, r.cost)
    }

    @Test
    fun theCeilingStopsTheTests() {
        val r = stress(listOf(CALLER, NET), listOf(FxRef.WARD), ceiling = 2)
        assertEquals(2, r.guarantee)
        assertTrue(r.complete)
        assertEquals(2, r.unopposed)
    }

    @Test
    fun aBoardsLineIsAnsweredAtItsWorstPoint() {
        val hand = listOf(CALLER, NET)
        val deal = MapDeal(hand)
        val m = MapSearch(kit).map(deal.table(main, emptyList(), kit))
        val both = m.ends.filter { it.traits.interruptions == 2 }
        assertTrue(both.isNotEmpty())
        val search = StressSearch(kit)
        val throughs = both.map { e -> search.through(StressDeal(deal, listOf(FxRef.WARD)).table(main, emptyList(), kit), MapLine.of(deal, e)) }
        throughs.forEach { assertTrue(it.replayed) }
        // Whichever way the kept line goes: Net before Sage is on the field keeps one (Ward on Net); Sage first keeps two.
        throughs.zip(both).forEach { (th, e) ->
            val netFirst = e.line.indexOfFirst { it.card == NET } < e.line.indexOfFirst { it.card == CALLER }
            assertEquals(if (netFirst) 1 else 2, th.keeps, "${e.line.map { it.card }}")
            if (netFirst) assertEquals(FxRef.WARD, th.answer?.card)
        }
        // With nothing to fear, a line keeps its own board.
        val calm = StressSearch(kit).through(StressDeal(deal, emptyList()).table(main, emptyList(), kit), MapLine.of(deal, both.first()))
        assertEquals(2, calm.keeps)
    }

    @Test
    fun theSameTestGivesTheSameAnswer() {
        val a = stress(listOf(NET, NET), listOf(FxRef.WARD))
        val b = stress(listOf(NET, NET), listOf(FxRef.WARD))
        assertEquals(a.guarantee, b.guarantee)
        assertEquals(a.line, b.line)
        assertEquals(a.moves, b.moves)
    }

    @Test
    fun aRunWritesWhatEachBoardKeepsAndTestsEveryStarter() {
        val table = StarterTable.run(main, emptyList(), kit, BoardLibrary(deck = "pond"), pairs = true, budget = 20_000)
        val deck = GoldfishDeck(main, fingerprint = "pond")
        val ward = StressSet(listOf(FxRef.WARD))
        val keys = table.library.boards.map { it.key }
        val (lib, report) = StressRun.run(table.library, table.rows, deck, ward, kit, keys)
        assertEquals(emptyList(), report.missing)
        assertEquals(table.rows.size, report.hands.size)
        assertTrue(report.settled > 0)
        // A board never keeps more through Ward than it has, and each settled board says what it keeps.
        lib.boards.filter { ward.key in it.traits.through }.forEach { b -> assertTrue(b.traits.through.getValue(ward.key) <= b.traits.interruptions) }
        assertEquals(report.settled, lib.boards.count { ward.key in it.traits.through })
        val both = report.hands.single { it.starter == listOf(CALLER, NET).sorted() }
        assertEquals(2, both.result.guarantee)
        val names = mapOf(CALLER to "Pond Caller", NET to "Pond Net", SAGE to "Pond Sage", WALL to "Pond Wall", FxRef.WARD to "Example Ward")
        val name = { c: Int -> names[c] ?: "#$c" }
        assertEquals("Through Example Ward: 2 of 2 interruptions guaranteed", StressWords.guarantee(both.result, StressSuite.words(ward.key, name)))
        assertEquals("Activate Pond Caller", StressWords.line(both.result, name).first())
        assertEquals("Through Example Ward", MapperWords.head("through:" + ward.key, name))
        // A card with no written effect is never tested: the run says which, and writes nothing.
        val unwritten = StressSet(listOf(GoldfishFixtures.ELDER))
        val (same, none) = StressRun.run(table.library, table.rows, deck, unwritten, kit, keys)
        assertEquals(listOf(GoldfishFixtures.ELDER), none.missing)
        assertEquals(table.library, same)
    }
}
