package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archetype that hinges on a card nobody's name contains.
 *
 * kai's report, verbatim in shape: there is a deck built out of cards that
 * *mention* "Light and Darkness Ritual", and a search that reads names can only
 * find the one card that is called it. Every test here is a reading of that
 * one sentence.
 */
class EffectSearchTest {

    private fun card(id: Int, name: String, text: String) = Card(
        id = CardId(id),
        name = name,
        type = "Effect Monster",
        frameType = "effect",
        description = text,
        attribute = Attribute.DARK,
    )

    private val theRitual = card(
        1,
        "Light and Darkness Ritual",
        "You can Ritual Summon 1 Ritual Monster from your hand.",
    )
    private val mentionsIt = card(
        2,
        "Chaos Hunter",
        "You can add 1 \"Light and Darkness Ritual\" from your Deck to your hand.",
    )
    private val alsoMentionsIt = card(
        3,
        "Umbral Servant",
        "Send 1 card to the GY; add \"Light and Darkness Ritual\" from your Deck.",
    )
    private val unrelated = card(
        4,
        "Pot of Greed",
        "Draw 2 cards.",
    )
    private val scattered = card(
        5,
        "Twilight Scholar",
        "While this card is in the GY: you can banish 1 LIGHT and 1 DARK monster.",
    )

    private val index = CardIndex.build(
        listOf(theRitual, mentionsIt, alsoMentionsIt, unrelated, scattered),
    )

    @Test
    fun `names only finds only the card with the name`() {
        val outcome = index.search("light and darkness ritual", scope = SearchScope.NAMES)
        assertEquals(listOf(theRitual.id), outcome.cards.map { it.id })
        assertEquals(0, outcome.effectMatchCount)
    }

    @Test
    fun `all finds the card and everything that mentions it, name first`() {
        val outcome = index.search("light and darkness ritual", scope = SearchScope.ALL)
        assertEquals(
            listOf(theRitual.id, mentionsIt.id, alsoMentionsIt.id),
            outcome.cards.map { it.id },
        )
        assertEquals(3, outcome.matchCount)
        // The two mentions, and not the card that is called it.
        assertEquals(2, outcome.effectMatchCount)
    }

    @Test
    fun `text scope drops the card that is merely called it`() {
        val outcome = index.search("light and darkness ritual", scope = SearchScope.TEXT)
        assertEquals(listOf(mentionsIt.id, alsoMentionsIt.id), outcome.cards.map { it.id })
        assertEquals(2, outcome.effectMatchCount)
    }

    @Test
    fun `the query can name its own scope`() {
        // The caller asks for names; the query overrules it.
        val outcome = index.search("text:light and darkness ritual", scope = SearchScope.NAMES)
        assertEquals(listOf(mentionsIt.id, alsoMentionsIt.id), outcome.cards.map { it.id })
    }

    @Test
    fun `a phrase is contiguous and scattered words are not`() {
        // The Scholar's text reads "LIGHT and 1 DARK": the three words are all
        // there, and the run is not, because a number sits in the middle of it.
        val phrase = index.search("\"light and dark\"", scope = SearchScope.TEXT)
        assertTrue(phrase.cards.isEmpty())

        val loose = index.search("light and dark ", scope = SearchScope.TEXT)
        assertEquals(listOf(scattered.id), loose.cards.map { it.id })
    }

    @Test
    fun `the word being typed matches as a prefix`() {
        val outcome = index.search("light and darkness rit", scope = SearchScope.TEXT)
        assertEquals(listOf(mentionsIt.id, alsoMentionsIt.id), outcome.cards.map { it.id })
    }

    @Test
    fun `a finished word does not match a longer one`() {
        // "ritual " — the space finishes the word, so "Rituals" would not do.
        assertTrue(EffectMatching.containsRun("Ritual Summon", "ritual", prefix = false))
        assertFalse(EffectMatching.containsRun("Rituals Summon", "ritual", prefix = false))
        assertTrue(EffectMatching.containsRun("Rituals Summon", "ritual", prefix = true))
    }

    @Test
    fun `punctuation between words is a word break, not a mismatch`() {
        assertTrue(EffectMatching.containsRun("add \"Light and Darkness Ritual\" from", "light and darkness ritual", prefix = false))
        assertTrue(EffectMatching.containsRun("LIGHT — AND", "light and", prefix = false))
        // A break in the needle must be a break in the text: "lightand" is one word.
        assertFalse(EffectMatching.containsRun("lightand darkness", "light and", prefix = false))
    }

    @Test
    fun `a run must start on a word boundary`() {
        // "and" is inside "Darkness and" only as a whole word; "ness" is not.
        assertFalse(EffectMatching.containsRun("Darkness", "ness", prefix = false))
        assertTrue(EffectMatching.containsRun("Darkness and light", "and", prefix = false))
    }

    @Test
    fun `two characters are not a search`() {
        assertNull(EffectMatching.score("Draw 2 cards.", SearchQuery.parse("dr")))
        assertNotNull(EffectMatching.score("Draw 2 cards.", SearchQuery.parse("dra")))
    }

    @Test
    fun `every text hit ranks below every name hit`() {
        // The whole reason widening the scope is safe.
        assertTrue(EffectMatching.PHRASE < 460)
        assertTrue(EffectMatching.TERMS < EffectMatching.PHRASE)
    }

    @Test
    fun `an empty query still browses`() {
        val outcome = index.search("", scope = SearchScope.ALL)
        assertEquals(5, outcome.matchCount)
        assertEquals(0, outcome.effectMatchCount)
    }

    @Test
    fun `a names-scoped search stays on names whatever the pool is searched by`() {
        // The text hits are the point of this fixture, so a scope that still
        // returns only the card actually *called* this is the assertion.
        val outcome = index.search("light and darkness", scope = SearchScope.NAMES, limit = 8)
        assertEquals(listOf(theRitual.id), outcome.cards.map { it.id })
    }
}
