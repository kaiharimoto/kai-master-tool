package com.kaiharimoto.mastertool.core.library

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.GroupStats
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckTagsTest {
    private fun card(id: Int, name: String, type: String, frame: String, archetype: String? = null) =
        Card(CardId(id), name, type, frame, archetype = archetype)

    private val cards = listOf(
        card(1, "Labrynth Chandraglier", "Effect Monster", "effect", "Labrynth"),
        card(2, "Welcome Labrynth", "Normal Trap", "trap", "Labrynth"),
        card(3, "Ash Blossom & Joyous Spring", "Effect Monster", "effect"),
        card(4, "Pot of Prosperity", "Spell Card", "spell"),
        card(5, "Knightmare Unicorn", "Link Monster", "link", "Knightmare"),
        card(6, "I:P Masquerena", "Link Monster", "link"),
        card(7, "Arias the Labrynth Butler", "Effect Monster", "effect", "Labrynth"),
    ).associateBy { it.id }

    private val deck = Deck(
        main = List(3) { CardId(1) } + List(3) { CardId(2) } + List(3) { CardId(7) } + List(3) { CardId(3) } + List(28) { CardId(4) },
        extra = listOf(CardId(5), CardId(6)),
    )

    @Test
    fun archetypesMechanicsAndLeanInThatOrder() {
        assertEquals(listOf("Labrynth", "Link", "Spell-heavy"), DeckTags.of(deck, cards::get))
    }

    @Test
    fun aDeckIsFoundByItsNameByACardInItOrByATag() {
        val tags = DeckTags.of(deck, cards::get)
        assertTrue(DeckSearch.match("Lab 2024", deck, tags, "lab", cards::get)!!.byName)
        val byCard = DeckSearch.match("My deck", deck, tags, "ash blos", cards::get)!!
        assertEquals(listOf("Ash Blossom & Joyous Spring"), byCard.cards)
        assertEquals(listOf("Link"), DeckSearch.match("My deck", deck, tags, "link", cards::get)!!.tags)
        assertNull(DeckSearch.match("My deck", deck, tags, "Nibiru", cards::get))
    }

    @Test
    fun aCardFoundInTwoPrintingsIsNamedOnce() {
        // Phase B: an Ash and an alternate-art Ash are one Ash; the row says it once.
        val ash = card(3, "Ash Blossom & Joyous Spring", "Effect Monster", "effect").copy(alternateIds = listOf(CardId(3), CardId(33)))
        val pool = cards + (CardId(3) to ash) + (CardId(33) to ash)
        val mixed = deck.copy(main = deck.main + CardId(33))
        assertEquals(listOf("Ash Blossom & Joyous Spring"), DeckSearch.match("My deck", mixed, emptyList(), "ash", pool::get)!!.cards)
    }

    @Test
    fun aGroupsNumbersAreItsCountsItsTypesAndItsOdds() {
        val groups = DeckGroups(
            groups = listOf(DeckGroup("g", "Engine", color = 0, order = 0)),
            assignments = mapOf(CardId(1) to "g", CardId(2) to "g", CardId(5) to "g"),
        )
        val report = GroupStats.of(deck, groups, cards::get)
        val g = report.groups.single()
        assertEquals(6, g.main)
        assertEquals(1, g.extra)
        assertEquals(3, g.monsters)
        assertEquals(3, g.traps)
        assertEquals(6.0 * 5 / 40, g.expected, 1e-9)
        assertTrue(g.opening > 0.5 && g.opening < 0.6)
        assertEquals(34, report.ungroupedMain)
    }

    /** The 1.0.24 slides: going second (six cards), and too many (two or more in five). */
    @Test
    fun aGroupsOddsGoingSecondAndOfTooMany() {
        val groups = DeckGroups(
            groups = listOf(DeckGroup("g", "Engine", color = 0, order = 0)),
            assignments = mapOf(CardId(1) to "g", CardId(2) to "g"),
        )
        val g = GroupStats.of(deck, groups, cards::get).groups.single()
        fun c(n: Int, k: Int): Double = (0 until k).fold(1.0) { acc, i -> acc * (n - i) / (i + 1) }
        // Six of forty.
        assertEquals(1 - c(34, 5) / c(40, 5), g.opening, 1e-9)
        assertEquals(1 - c(34, 6) / c(40, 6), g.openingSecond, 1e-9)
        assertEquals(1 - c(34, 5) / c(40, 5) - 6 * c(34, 4) / c(40, 5), g.flood, 1e-9)
        assertTrue(g.openingSecond > g.opening)
    }
}
