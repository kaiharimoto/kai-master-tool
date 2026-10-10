package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What to write first (D.md §3.1): the combos' cards by how many combos use each, then the engine's groups, then the Main
 * Deck by copies, then repairs — never a card already written, never a Normal Monster, every printing as its card.
 * Fictional cards (900000400–900000499).
 */
class FxSuggestTest {
    private fun card(id: Int, name: String, alt: Int? = null) =
        Card(CardId(id), name, "Effect Monster", "effect", "", alternateIds = listOfNotNull(alt?.let(::CardId)))

    private val herald = card(900_000_401, "Example Herald", alt = 900_000_411)
    private val squire = card(900_000_402, "Example Squire")
    private val knight = card(900_000_403, "Example Knight")
    private val lancer = card(900_000_404, "Example Lancer")
    private val ward = card(900_000_405, "Ward of Example")
    private val normal = card(900_000_406, "Example Farmer")
    private val written = card(900_000_407, "Example Scribe")
    private val cards = listOf(herald, squire, knight, lancer, ward, normal, written)
    private val byId = cards.flatMap { c -> c.passcodes.map { it.value to c } }.toMap()
    private val canonical: (Int) -> Int = { byId[it]?.id?.value ?: it }

    private val status: (Int) -> FxStatus = {
        when (it) {
            normal.id.value -> FxStatus.NONE
            written.id.value -> FxStatus.UNTESTED
            ward.id.value -> FxStatus.WARNED
            else -> FxStatus.MISSING
        }
    }

    @Test
    fun theCombosCardsFirstThenTheEngineThenCopiesThenRepairs() {
        // Herald in by its alternate artwork: it is the one card.
        val main = listOf(
            herald.id.value, 900_000_411, squire.id.value, knight.id.value, knight.id.value, knight.id.value,
            lancer.id.value, lancer.id.value, ward.id.value, normal.id.value, normal.id.value, normal.id.value, written.id.value,
        )
        val combos = listOf(setOf(squire.id.value, written.id.value), setOf(squire.id.value, 900_000_411))
        val picks = FxSuggest.of(main, emptyList(), combos, engine = setOf(lancer.id.value), status = status, canonical = canonical)
        assertEquals(
            listOf(
                FxSuggest.Pick(squire.id.value, FxSuggest.Why.COMBO, 2),
                FxSuggest.Pick(herald.id.value, FxSuggest.Why.COMBO, 1),
                FxSuggest.Pick(lancer.id.value, FxSuggest.Why.ENGINE),
                FxSuggest.Pick(knight.id.value, FxSuggest.Why.COPIES, 3, FxSuggest.opens(3, 13)),
                FxSuggest.Pick(ward.id.value, FxSuggest.Why.REPAIR),
            ),
            picks,
        )
        // Nothing written is suggested again; no Normal Monster at all.
        assertTrue(picks.none { it.card == written.id.value || it.card == normal.id.value })
        assertEquals("used by 2 combos", picks[0].words())
        // Three copies in thirteen cards are in 1 − C(10,5)/C(13,5) = 80 % of opening hands: written, it would play there.
        assertEquals("3 copies in the Main Deck: in 80 % of opening hands", picks[3].words())
        assertEquals(2, FxSuggest.of(main, emptyList(), combos, emptySet(), status, canonical, limit = 2).size)
    }

    @Test
    fun aCombosCardsAreReadOffItsNeedsAndSteps() {
        val combo = Combo(
            "c1", "Line 1",
            needs = listOf("herald", "Example Sq"),
            steps = listOf("summon Example Knight to m3", "u Example Lancer e1 pick=Ward of Example", "pass"),
        )
        assertEquals(setOf(herald.id.value, squire.id.value, knight.id.value, lancer.id.value, ward.id.value), FxSuggest.comboCards(combo, cards))
    }

    @Test
    fun theEngineIsTheGroupsThatReadAsOne() {
        val groups = DeckGroups(
            listOf(DeckGroup("g1", "Starters", 0, 0), DeckGroup("g2", "Hand traps", 1, 1), DeckGroup("g3", "Extenders", 2, 2)),
            mapOf(CardId(900_000_411) to "g1", squire.id to "g2", knight.id to "g3"),
        )
        assertEquals(setOf(herald.id.value, knight.id.value), FxSuggest.engine(groups, canonical))
    }
}
