package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.deck.DeckRules
import com.kaiharimoto.mastertool.core.hand.GoalCount
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Search that knows the rules in force (Phase G, R2), and reach(X) read off the cards' text (B3). */
class RulesFilterTest {
    private fun card(
        id: Int, name: String, type: String = "Effect Monster", frame: String = "effect", tcg: String? = "2020-01-01", ocg: String? = "2019-06-01",
        points: Int? = null, text: String = "", ban: BanStatus = BanStatus.UNLIMITED, formats: List<String> = listOf("TCG", "OCG"),
        absent: Set<String> = emptySet(),
    ) = Card(CardId(id), name, type, frame, text, tcgDate = tcg, ocgDate = ocg, genesysPoints = points, tcgBanStatus = ban, formats = formats, absentFrom = absent)

    private val old = card(1, "Old", points = 0)
    private val pricey = card(2, "Pricey", points = 100)
    // Out in the OCG only, as the pool has it and a second source agrees (Phase B: the pool alone is never enough to call
    // a card unreleased; missing data is unknown, never illegal).
    private val coming = card(3, "Coming", tcg = null, ocg = "2026-08-01", formats = listOf("OCG"), absent = setOf("TCG"))
    private val link = card(4, "Linky", "Link Monster", "link", points = 10)
    private val banned = card(5, "Banned", ban = BanStatus.FORBIDDEN)
    private val fresh = card(6, "Fresh", tcg = "2026-09-01", ocg = "2026-07-01")
    private val all = listOf(old, pricey, coming, link, banned, fresh)

    private fun pass(f: CardFilter) = all.filter(f::matches).map { it.name }

    @Test
    fun legalOnlyReadsTheRulesInForce() {
        val today = "2026-10-10"
        assertEquals(listOf("Old", "Pricey", "Linky", "Fresh"), pass(CardFilter(legalOnly = true, rules = DeckRules(), today = today)))
        // Genesys: no Link Monsters, no Forbidden list.
        assertEquals(listOf("Old", "Pricey", "Banned", "Fresh"), pass(CardFilter(legalOnly = true, rules = DeckRules(genesysCap = 100), today = today)))
        // On a day before Fresh came out, it is not legal yet.
        assertFalse("Fresh" in pass(CardFilter(legalOnly = true, rules = DeckRules(asOf = "2026-08-15"), today = today)))
        assertTrue(CardFilter(legalOnly = true).isActive)
    }

    @Test
    fun pointsDatesAndWhatIsComing() {
        assertEquals(listOf("Old", "Linky"), pass(CardFilter(points = 0..20)))
        assertEquals(listOf("Coming"), pass(CardFilter(notYetInTcg = true, today = "2026-10-10")))
        assertEquals(listOf("Fresh"), pass(CardFilter(releasedAfter = "2026-07-10")))
        assertEquals(listOf("Pricey", "Linky", "Old"), CardSort.POINTS.apply(listOf(old, pricey, link), reverse = false).map { it.name })
        assertEquals("Fresh", CardSort.NEWEST.apply(all, reverse = false).first().name)
        assertEquals(4, CardFilter(legalOnly = true, points = 0..1, releasedAfter = "2020-01-01", notYetInTcg = true).activeFacetCount)
    }

    @Test
    fun reachIsTheCardOrWhatSearchesIt() {
        val target = card(10, "Target Dragon")
        val searcher = card(11, "Searcher", text = "When this card is Normal Summoned: You can add 1 \"Target Dragon\" from your Deck to your hand.")
        val other = card(12, "Bystander", text = "Draw 1 card.")
        val byId = listOf(target, searcher, other).associateBy { it.id }
        val find = GoalCount.searchersIn(listOf(target.id, searcher.id, other.id), byId::get)
        assertEquals(setOf(searcher.id), find(setOf(target.id)))
    }
}
