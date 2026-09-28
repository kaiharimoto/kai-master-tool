package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.test.Test
import kotlin.test.assertEquals

class DeckHistoryTest {
    private val ash = CardId(1)
    private val nib = CardId(2)
    private val names = mapOf(ash to "Ash Blossom", nib to "Nibiru")
    private fun say(before: Deck, after: Deck, groups: Boolean = false, deck: Boolean = false) =
        DeckHistory.describe(before, after, groups, deck) { names[it] }

    @Test
    fun addsAndRemovesAreNamedWithTheirSection() {
        assertEquals("+ Ash Blossom", say(Deck(), Deck(main = listOf(ash))))
        assertEquals("+ 2× Ash Blossom", say(Deck(), Deck(main = listOf(ash, ash))))
        assertEquals("− Nibiru (side)", say(Deck(side = listOf(nib)), Deck()))
    }

    @Test
    fun outOfOneSectionAndIntoAnotherIsAMove() {
        assertEquals("Moved Ash Blossom to side", say(Deck(main = listOf(ash)), Deck(side = listOf(ash))))
    }

    @Test
    fun severalChangesSayTheFirstAndCountTheRest() {
        assertEquals("+ Ash Blossom, and 1 more", say(Deck(), Deck(main = listOf(ash, nib))))
    }

    @Test
    fun sameCardsInAnotherOrderIsAReorder() {
        assertEquals("Reordered the main deck", say(Deck(main = listOf(ash, nib)), Deck(main = listOf(nib, ash))))
    }

    @Test
    fun theCardsUnchangedIsTheGroupsOrTheDeck() {
        assertEquals("Changed the groups", say(Deck(main = listOf(ash)), Deck(main = listOf(ash)), groups = true))
        assertEquals("Started a new deck", say(Deck(main = listOf(ash)), Deck(), deck = true))
        assertEquals("Opened a deck of 1", say(Deck(), Deck(main = listOf(ash)), deck = true))
    }
}
