package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A deck's versions (Phase G, G.8): one a print, numbered, worded, and a duplicate's lineage. */
class DeckVersionsTest {
    private fun deck(main: List<Int>, side: List<Int> = emptyList()) = Deck(main = main.map(::CardId), side = side.map(::CardId))

    // An alternate artwork (2) is the card 1.
    private val cards: (CardId) -> Card? = { id -> if (id.value == 2) Card(id = CardId(1), name = "One", type = "Effect Monster", frameType = "effect", description = "") else null }

    @Test
    fun aVersionIsMadeOnlyWhenTheCardsChange() {
        val a = DeckVersions.next(emptyList(), "d", deck(listOf(1, 1, 3)), "Lab", 10, cards)!!
        assertNull(a.parent)
        // The order, an alternate artwork and the Side Deck change no version.
        assertNull(DeckVersions.next(listOf(a), "d", deck(listOf(3, 2, 1)), "Lab", 20, cards))
        assertNull(DeckVersions.next(listOf(a), "d", deck(listOf(1, 1, 3), side = listOf(9)), "Lab", 20, cards))
        val b = DeckVersions.next(listOf(a), "d", deck(listOf(1, 3, 3)), "Lab", 30, cards)!!
        assertEquals(a.print, b.parent)
        assertNotEquals(a.print, b.print)
        assertEquals(listOf(1 to a, 2 to b), DeckVersions.numbered(listOf(b, a)))
        assertEquals("v2", DeckVersions.nameOf(b.print, listOf(a, b)))
        assertEquals(DeckVersions.UNKNOWN, DeckVersions.nameOf(null, listOf(a, b)))
        assertEquals(DeckVersions.UNKNOWN, DeckVersions.nameOf("a print from before", listOf(a, b)))
    }

    @Test
    fun theChangesAreWordedByCard() {
        val from = deck(listOf(1, 1, 3), side = listOf(5))
        val to = deck(listOf(2, 3, 3, 4), side = listOf(5, 5))
        val ch = DeckVersions.changes(from, to, cards)
        assertEquals(
            listOf(
                DeckVersions.Change(DeckSection.MAIN, CardId(1), -1),
                DeckVersions.Change(DeckSection.MAIN, CardId(3), 1),
                DeckVersions.Change(DeckSection.MAIN, CardId(4), 1),
                DeckVersions.Change(DeckSection.SIDE, CardId(5), 1),
            ),
            ch,
        )
        assertEquals("−1 #1 · +1 #3 · +1 #4 · Side +1 #5", DeckVersions.words(ch, { "#${it.value}" }))
        assertEquals("−1 #1 · +1 #3 · and 2 more", DeckVersions.words(ch, { "#${it.value}" }, most = 2))
        assertEquals("no change by card", DeckVersions.words(emptyList(), { "" }))
    }

    @Test
    fun aDuplicateKeepsItsLineage() {
        val a1 = DeckVersions.next(emptyList(), "a", deck(listOf(1, 3)), "A", 1, cards)!!
        val b1 = DeckVersions.next(emptyList(), "b", deck(listOf(1, 3)), "A copy", 5, cards, parentDeck = "a", parentPrint = a1.print)!!
        assertEquals("a", b1.parentDeck)
        assertEquals(a1.print, b1.parent)
        val b2 = DeckVersions.next(listOf(b1), "b", deck(listOf(1, 4)), "A copy", 6, cards)!!
        assertNull(b2.parentDeck, "only the first version names the deck it came from")
        val c1 = DeckVersions.next(emptyList(), "c", deck(listOf(1, 4)), "copy of copy", 9, cards, parentDeck = "b", parentPrint = b2.print)!!
        val all = mapOf("a" to listOf(a1), "b" to listOf(b1, b2), "c" to listOf(c1))
        assertEquals(listOf("b" to b2.print, "a" to a1.print), DeckVersions.lineage("c") { all[it].orEmpty() })
        assertEquals(emptyList(), DeckVersions.lineage("a") { all[it].orEmpty() })
        // A cycle a sync could bring stops.
        val loop = mapOf("x" to listOf(b1.copy(deckId = "x", parentDeck = "y")), "y" to listOf(b1.copy(deckId = "y", parentDeck = "x")))
        assertNotNull(DeckVersions.lineage("x") { loop[it].orEmpty() })
    }
}
