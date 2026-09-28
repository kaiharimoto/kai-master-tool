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

    @Test
    fun aGoalEditIsNamedAsOne() {
        // touch swarm, rec 22: goals travel in the groups' snapshot, and were listed as "Changed the groups".
        val same = Deck(main = listOf(ash))
        assertEquals(
            "Changed the hand goals",
            DeckHistory.describe(same, same, groupsChanged = true, deckChanged = false, name = { null }, goalsChanged = true),
        )
    }

    @Test
    fun anAddLandsWhereTheSectionFirstDiffers() {
        assertEquals(1, DeckHistory.addedAt(listOf(ash, nib), listOf(ash, ash, nib)))
        assertEquals(2, DeckHistory.addedAt(listOf(ash, nib), listOf(ash, nib, nib)))
        assertEquals(0, DeckHistory.addedAt(emptyList(), listOf(ash)))
        assertEquals(null, DeckHistory.addedAt(listOf(ash), listOf(ash)))
    }
}
