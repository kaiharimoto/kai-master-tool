package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.mapper.MapperView.Density
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView.Moment
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView.Order
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the page shows at a moment (M.md §6½): its state, density, order, leads, sections and trade-offs. */
class MapperViewTest {
    private fun board(key: String, traits: BoardTraits, moves: Int = 5, hand: Int = 1) =
        BoardEntry(key, BoardCards(), traits, lines = listOf(MapLine(MapDeal(List(hand) { 1 }), List(moves) { MapStep(kind = "a") } + MapStep(kind = "x"))))

    private val three = board("a", BoardTraits(interruptions = 3, negates = 1, removal = 2, bodies = 3), moves = 9)
    private val twoA = board("b", BoardTraits(interruptions = 2, negates = 2, bodies = 2, hand = 1), moves = 4)
    private val twoB = board("c", BoardTraits(interruptions = 2, negates = 2, bodies = 1, hand = 2), moves = 2)
    private val one = board("d", BoardTraits(interruptions = 1, hand = 3), moves = 1)
    private val weights = mapOf("interruptions" to 1.0, "negates" to 1.0)
    private val ranked = BoardQuery.rank(listOf(three, twoA, twoB, one), BoardPreset(weights = weights))
    private val shares = mapOf("a" to 0.03, "b" to 0.28, "c" to 0.28, "d" to 0.9)

    @Test
    fun thePageLeadsWithWhatTheMomentNeeds() {
        assertEquals(Moment.NO_DECK, MapperView.moment(false, true, false, 5, true))
        assertEquals(Moment.LOADING, MapperView.moment(true, false, false, 0, false))
        assertEquals(Moment.UNREADABLE, MapperView.moment(true, true, true, 5, true))
        assertEquals(Moment.FIRST_RUN, MapperView.moment(true, true, false, 0, false))
        assertEquals(Moment.UNCOUNTED, MapperView.moment(true, true, false, 5, false))
        assertEquals(Moment.READY, MapperView.moment(true, true, false, 5, true))
    }

    @Test
    fun densityStepsByBoardsToAScreenAndStopsAtTheEnds() {
        assertEquals(Density.ROWS, Density.CARDS.denser())
        assertEquals(Density.OVERVIEW, Density.ROWS.denser())
        assertEquals(Density.OVERVIEW, Density.OVERVIEW.denser())
        assertEquals(Density.CARDS, Density.CARDS.looser())
        assertEquals(Density.CARDS, MapperView.autoDensity(12, phone = false))
        assertEquals(Density.OVERVIEW, MapperView.autoDensity(200, phone = false))
        assertEquals(Density.CARDS, MapperView.autoDensity(30, phone = true))
    }

    @Test
    fun aBoardLeadsWithWhatWasAskedHeaviestFirstAndAZeroAskedForIsShown() {
        val leads = MapperView.leads(one.traits, mapOf("negates" to 2.0, "interruptions" to 1.0))
        assertEquals(listOf("negates" to 0, "interruptions" to 1), leads.map { it.head to it.value })
        assertTrue(leads.all { it.asked })
        // Nothing asked: the plainly better traits it has.
        assertEquals(listOf("interruptions", "negates", "removal"), MapperView.leads(three.traits, emptyMap()).map { it.head })
        assertEquals("3 bodies", MapperView.rest(three.traits, MapperView.leads(three.traits, emptyMap())))
    }

    @Test
    fun sectionsNameWhatTheirBoardsShareAndNeverReorder() {
        val s = MapperView.sections(ranked, Order.ASKED, weights) { shares[it.key] }
        assertEquals(ranked.map { it.entry.key }, s.flatMap { it.boards }.map { it.entry.key })
        val two = s.first { it.boards.size == 2 }
        assertEquals("2 interruptions · 2 negates", two.title)
        assertEquals(setOf("b", "c"), two.boards.map { it.entry.key }.toSet())
    }

    @Test
    fun mostOftenAndShortestReorderAndCutByTheirOwnMeasure() {
        val often = MapperView.order(ranked, Order.OFTEN) { shares[it.key] }
        assertEquals("d", often.first().entry.key)
        assertEquals("a", often.last().entry.key)
        val s = MapperView.sections(often, Order.OFTEN, weights) { shares[it.key] }
        assertEquals(listOf("In most hands · 50 % or more", "Often · 20 to 50 %", "Rarely · under 5 %"), s.map { it.title })
        val short = MapperView.order(ranked, Order.SHORTEST) { null }
        assertEquals(listOf("d", "c", "b", "a"), short.map { it.entry.key })
        assertEquals(1, MapperView.shortest(one))
    }

    @Test
    fun oneSectionIsNotCut() {
        val s = MapperView.sections(ranked.take(1), Order.ASKED, weights) { null }
        assertEquals(listOf(""), s.map { it.title })
    }

    @Test
    fun aBoardSaysWhatItTradesAgainstTheFirstAskedTraitsFirst() {
        assertEquals(
            listOf("1 fewer interruption", "1 more negate", "2 less removal", "1 fewer body", "1 more kept in hand"),
            MapperView.versus(twoA.traits, three.traits, weights),
        )
        assertTrue(MapperView.versus(three.traits, three.traits, weights).isEmpty())
    }

    @Test
    fun theAsksAreKnownByTheirWeights() {
        assertEquals("Most interruptions", MapperView.askOf(mapOf("interruptions" to 1.0))?.name)
        assertNull(MapperView.askOf(mapOf("interruptions" to 1.5)))
        assertEquals("28 %", MapperView.pct(0.283))
        assertEquals("3.3 %", MapperView.pct(0.0333))
    }
}
