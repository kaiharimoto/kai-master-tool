package com.kaiharimoto.mastertool.core.library

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeckLibraryTest {

    private fun entry(id: String, updated: Long, deck: Deck = Deck.EMPTY) = DeckEntry(id, id, deck, createdAtEpochMs = 0, updatedAtEpochMs = updated)

    @Test
    fun theDefaultWinsWhileItIsInTheLibrary() {
        val decks = listOf(entry("a", 10), entry("b", 30), entry("c", 20))
        assertEquals("a", StartingDeck.pick(decks, defaultId = "a"))
    }

    @Test
    fun withoutOneTheDeckSavedLastOpens() {
        val decks = listOf(entry("a", 10), entry("b", 30), entry("c", 20))
        assertEquals("b", StartingDeck.pick(decks, defaultId = null))
        // A default since deleted is no default.
        assertEquals("b", StartingDeck.pick(decks, defaultId = "gone"))
    }

    @Test
    fun anEmptyLibraryOpensNothing() {
        assertNull(StartingDeck.pick(emptyList(), defaultId = "a"))
    }

    @Test
    fun coversAreUpToThreeAndAFourthLetsGoOfTheFirst() {
        var covers = emptyList<Int>()
        listOf(1, 2, 3).forEach { covers = DeckCovers.toggle(covers, it) }
        assertEquals(listOf(1, 2, 3), covers)
        covers = DeckCovers.toggle(covers, 4)
        assertEquals(listOf(2, 3, 4), covers)
        // Choosing one that is already a cover takes it off.
        assertEquals(listOf(2, 4), DeckCovers.toggle(covers, 3))
    }

    @Test
    fun aCoverThatLeftTheDeckIsNotDrawnAndNoneFallsBackToTheMostPlayed() {
        val deck = Deck(main = listOf(CardId(7), CardId(9), CardId(9)), extra = listOf(CardId(5)))
        assertEquals(listOf(CardId(5), CardId(7)), DeckCovers.shown(listOf(5, 42, 7), deck))
        assertEquals(listOf(CardId(9)), DeckCovers.shown(emptyList(), deck))
        assertEquals(listOf(CardId(9)), DeckCovers.shown(listOf(42), deck))
        assertEquals(emptyList(), DeckCovers.shown(emptyList(), Deck.EMPTY))
    }
}
