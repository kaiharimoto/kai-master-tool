package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.deck.DeckIssue
import com.kaiharimoto.mastertool.core.deck.DeckValidation
import com.kaiharimoto.mastertool.core.deck.IssueSeverity
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrepPlanTest {

    @Test
    fun isoDatesRoundTripAndCountDays() {
        assertEquals(0L, IsoDate.epochDay("1970-01-01"))
        assertEquals("2026-09-30", IsoDate.of(IsoDate.epochDay("2026-09-30")!!))
        // Across a leap day and a year end.
        assertEquals(2L, IsoDate.daysBetween("2028-02-28", "2028-03-01"))
        assertEquals(1L, IsoDate.daysBetween("2026-12-31", "2027-01-01"))
        assertEquals(-3L, IsoDate.daysBetween("2026-10-04", "2026-10-01"))
        // A date and time reads as its date; nonsense reads as nothing.
        assertEquals(IsoDate.epochDay("2026-10-17"), IsoDate.epochDay("2026-10-17T09:00"))
        assertNull(IsoDate.epochDay("next Saturday"))
        assertNull(IsoDate.epochDay("2026-13-01"))
        assertEquals("in 12 days", IsoDate.words(12))
        assertEquals("tomorrow", IsoDate.words(1))
        assertEquals("2 days ago", IsoDate.words(-2))
    }

    @Test
    fun theCountdownRunsFromTheEventBackwards() {
        val event = PrepEvent("e", "Regional", "2026-10-17", tier = 2, deadline = "2026-10-15", decklist = PrepEvent.DECKLIST_PAPER)
        val m = Countdown.milestones(event, today = "2026-10-01")
        assertEquals(listOf("main", "side", "deadline", "print", "day"), m.map { it.id })
        assertEquals("2026-10-10", m.first { it.id == "main" }.date)
        assertEquals(16L, m.last().days)
        // Online registration prints nothing; a locals asks for no list at all.
        val online = Countdown.milestones(event.copy(decklist = PrepEvent.DECKLIST_NEURON), "2026-10-01")
        assertFalse(online.any { it.id == "print" })
        assertTrue(Countdown.milestones(event.copy(date = "soon"), "2026-10-01").isEmpty())
    }

    private val deck = Deck(
        main = List(40) { CardId(1 + it % 14) },
        extra = listOf(CardId(100)),
        side = listOf(CardId(200), CardId(200), CardId(201)),
    )

    @Test
    fun theCheckFindsUnevenStaleAndLongPlans() {
        val even = SidePlan(listOf(CardId(1)), listOf(CardId(200)))
        val uneven = SidePlan(listOf(CardId(1), CardId(2)), listOf(CardId(200)))
        val stale = SidePlan(listOf(CardId(999)), listOf(CardId(201)))
        val siding = DeckSiding(
            listOf(
                Matchup("a", "Yubel", first = even, second = uneven),
                Matchup("b", "Tenpai", first = stale),
            ),
        )
        val items = EventCheck.check(deck, DeckValidation(emptyList()), siding, tier = 2)
        assertTrue(items.first().ok)
        val card = items.first { it.title.contains("card for card") }
        assertFalse(card.ok)
        assertTrue("Yubel, going second" in card.detail, card.detail)
        assertFalse(items.first { it.title.contains("no longer holds") }.ok)
        assertTrue(items.any { it.title == "A decklist is required" })
        assertTrue(items.any { it.title == "Sleeves are required" })
    }

    @Test
    fun anIllegalDeckSaysWhy() {
        val v = DeckValidation(listOf(DeckIssue(IssueSeverity.ERROR, "Main Deck has 39 cards")))
        val items = EventCheck.check(deck, v, DeckSiding.EMPTY, tier = 1)
        assertFalse(items.first().ok)
        assertEquals("Main Deck has 39 cards", items.first().detail)
        assertTrue(items.any { it.title == "No siding plans yet" })
        assertFalse(items.any { it.title == "Sleeves are required" })
    }

    @Test
    fun theDecklistSortsTheMainDeckIntoColumns() {
        val cards = mapOf(
            1 to Card(CardId(1), "Ash Blossom & Joyous Spring", "Effect Monster", "effect"),
            2 to Card(CardId(2), "Called by the Grave", "Spell Card", "spell"),
            3 to Card(CardId(3), "Infinite Impermanence", "Trap Card", "trap"),
            9 to Card(CardId(9), "Some Extra", "Fusion Monster", "fusion"),
        )
        val d = Deck(main = listOf(1, 1, 2, 3, 3, 3, 4).map(::CardId), extra = listOf(CardId(9)), side = listOf(CardId(2)))
        val c = Decklists.content(d, "Test", { cards[it.value] }, PrepProfile("Kai Hari Moto", "0123456789", "US"), null)
        assertEquals(listOf(DecklistSheet.Line(2, "Ash Blossom & Joyous Spring"), DecklistSheet.Line(1, "#4")), c.monsters)
        assertEquals(listOf(DecklistSheet.Line(1, "Called by the Grave")), c.spells)
        assertEquals(3, c.traps.single().count)
        assertEquals(7, c.mainTotal)
        assertEquals("Some Extra", c.extra.single().name)
    }

    @Test
    fun theChecklistFollowsTheTier() {
        val locals = Checklist.of(PrepEvent("e", "Locals", "2026-10-17", tier = 1)).map { it.id }
        val regional = Checklist.of(PrepEvent("e", "Regional", "2026-10-17", tier = 2)).map { it.id }
        assertFalse("sleeves" in locals)
        assertFalse("decklist" in locals)
        assertTrue("sleeves" in regional && "decklist" in regional)
        assertEquals(regional.size, regional.distinct().size)
    }
}
