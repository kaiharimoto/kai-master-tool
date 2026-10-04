package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardIndexTest {

    private val index = CardIndex.build(TestCards.all)

    @Test
    fun resolvesAlternateArtworkPasscodes() {
        // A deck file exported elsewhere may reference an alternate printing.
        assertEquals(TestCards.accesscode, index.byId(CardId(11111111)))
        assertEquals(TestCards.accesscode, index.byId(CardId(86066372)))
    }

    @Test
    fun aSearchNobodyWaitsForStops() {
        // Asked to stop, it gives up at once and empty; never asked, it is the search it always was (1.0.92).
        assertEquals(SearchOutcome(emptyList(), 0), index.search("ash", cancelled = { true }))
        assertEquals(index.search("ash"), index.search("ash", cancelled = { false }))
    }

    @Test
    fun unknownPasscodeReturnsNull() {
        assertNull(index.byId(CardId(1)))
    }

    @Test
    fun findsCardIgnoringPunctuation() {
        val hits = index.search("ash blossom").cards
        assertEquals(TestCards.ashBlossom, hits.first())
    }

    @Test
    fun findsCardBeforeTheAmpersand() {
        assertEquals(TestCards.ashBlossom, index.search("ash").cards.first())
    }

    @Test
    fun matchesWordAfterAComma() {
        assertEquals(TestCards.nibiru, index.search("primal").cards.first())
    }

    @Test
    fun matchesTokensOutOfOrder() {
        assertEquals(TestCards.nibiru, index.search("primal nibiru").cards.first())
    }

    @Test
    fun handlesQuotesInNames() {
        assertEquals(TestCards.maxxC, index.search("maxx c").cards.first())
        assertEquals(TestCards.maxxC, index.search("maxx").cards.first())
    }

    @Test
    fun toleratesTypos() {
        assertEquals(TestCards.nibiru, index.search("nibiro").cards.first())
        assertEquals(TestCards.accesscode, index.search("accesscode taker").cards.first())
    }

    @Test
    fun exactMatchOutranksPrefixMatch() {
        val cards = listOf(
            TestCards.monster(1, "Dark Magician"),
            TestCards.monster(2, "Dark Magician Girl"),
        )
        val hits = CardIndex.build(cards).search("dark magician").cards
        assertEquals("Dark Magician", hits.first().name)
    }

    @Test
    fun emptyQueryReturnsPoolUpToLimit() {
        assertEquals(TestCards.all.size, index.search("", limit = 100).cards.size)
        assertEquals(3, index.search("", limit = 3).cards.size)
    }

    @Test
    fun emptyQueryOrdersByNameSoBrowsingIsStable() {
        // Without a query there is nothing to rank by, and the pool arrives in
        // whatever order the API produced. Two identical browses must agree.
        val shuffled = CardIndex.build(TestCards.all.reversed())
        val page = shuffled.search("", limit = 4).cards

        assertEquals(page.map { it.name }.sorted(), page.map { it.name })
        assertEquals(index.search("", limit = 4).cards, page)
    }

    @Test
    fun respectsResultLimit() {
        val many = (1..500).map { TestCards.monster(it, "Test Card $it") }
        assertEquals(10, CardIndex.build(many).search("test", limit = 10).cards.size)
    }

    @Test
    fun reportsTotalMatchesBeyondTheLimit() {
        // The UI shows "150 of N matched", so N must count every match rather
        // than the page that was drawn.
        val many = (1..500).map { TestCards.monster(it, "Test Card $it") }
        val outcome = CardIndex.build(many).search("test", limit = 10)

        assertEquals(10, outcome.cards.size)
        assertEquals(500, outcome.matchCount)
        assertTrue(outcome.truncated)
    }

    @Test
    fun reportsTotalMatchesForAnEmptyQueryToo() {
        val outcome = index.search("", limit = 3)

        assertEquals(3, outcome.cards.size)
        assertEquals(TestCards.all.size, outcome.matchCount)
        assertTrue(outcome.truncated)
    }

    @Test
    fun aFullyDrawnResultIsNotTruncated() {
        val outcome = index.search("ash blossom", limit = 50)

        assertEquals(outcome.cards.size, outcome.matchCount)
        assertFalse(outcome.truncated)
    }

    @Test
    fun nonsenseQueryReturnsNothing() {
        val outcome = index.search("zzzzqqqqxxxx")

        assertTrue(outcome.cards.isEmpty())
        assertEquals(0, outcome.matchCount)
    }

    @Test
    fun filtersByCategory() {
        val hits =
            index.search("", CardFilter(categories = setOf(CardCategory.SPELL)), limit = 100).cards
        assertEquals(listOf(TestCards.pot), hits)
    }

    @Test
    fun filtersByAttributeAndLevel() {
        val filter = CardFilter(attributes = setOf(Attribute.FIRE), levels = setOf(3))
        assertEquals(listOf(TestCards.ashBlossom), index.search("", filter, limit = 100).cards)
    }

    @Test
    fun filtersByExtraDeck() {
        val extra = index.search("", CardFilter(extraDeckOnly = true), limit = 100).cards
        assertTrue(extra.all { it.isExtraDeck })
        assertTrue(TestCards.accesscode in extra)
        assertTrue(TestCards.pendulumFusion in extra)
    }

    @Test
    fun filtersByBanStatus() {
        val limited =
            index.search("", CardFilter(banStatuses = setOf(BanStatus.LIMITED)), limit = 100).cards
        assertEquals(listOf(TestCards.ashBlossom), limited)
    }

    @Test
    fun atkRangeExcludesCardsWithoutTheStat() {
        // Link monsters have no DEF, so a DEF range must not silently treat it as 0.
        val hits = index.search("", CardFilter(defRange = 0..1000), limit = 100).cards
        assertTrue(TestCards.accesscode !in hits)
    }

    @Test
    fun filterAndQueryCombine() {
        val filter = CardFilter(categories = setOf(CardCategory.MONSTER))
        assertTrue(index.search("pot", filter, limit = 100).cards.isEmpty())
    }

    @Test
    fun byNameIsPunctuationInsensitive() {
        assertNotNull(index.byName("ash blossom joyous spring"))
        assertEquals(TestCards.ashBlossom, index.byName("Ash Blossom & Joyous Spring"))
    }

    @Test
    fun facetListsArePopulatedForFilterChips() {
        assertTrue("Zombie" in index.races)
        assertTrue(3 in index.levels)
    }
}
