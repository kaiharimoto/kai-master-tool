package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.test.Test
import kotlin.test.assertEquals

class CardSetOddsTest {
    private val ash = TestCards.ashBlossom.copy(alternateIds = listOf(CardId(14558127), CardId(14558128)))
    private val imperm = TestCards.infiniteImpermanence
    private val nibiru = TestCards.nibiru
    private val pool: Map<CardId, Card> = listOf(ash, imperm, nibiru).flatMap { c -> c.passcodes.map { it to c } }.toMap()
    private val lookup: (CardId) -> Card? = pool::get

    private fun deck(vararg parts: Pair<Int, Int>, size: Int = 40): List<CardId> {
        val named = parts.flatMap { (id, n) -> List(n) { CardId(id) } }
        return named + List(size - named.size) { CardId(1_000_000 + it) }
    }

    private fun atLeastOne(k: Int, n: Int, h: Int) = 1 - HandOdds.binomial(n - k, h) / HandOdds.binomial(n, h)

    @Test
    fun anAlternateArtworkIsTheSameCard() {
        // Two Ash of each printing: three copies asked for by name read as three, not two.
        val main = deck(14558127 to 2, 14558128 to 1)
        val a = CardSetOdds.of(main, listOf(CardSetOdds.Need(setOf(ash.id))), lookup)
        assertEquals(listOf(3), a.copies)
        assertEquals(atLeastOne(3, 40, 5), a.first, 1e-12)
        assertEquals(atLeastOne(3, 40, 6), a.second, 1e-12)
        // Asked by the alternate's passcode, the same answer.
        assertEquals(a.first, CardSetOdds.probability(main, listOf(CardSetOdds.Need(setOf(CardId(14558128)))), 5, lookup), 1e-12)
    }

    @Test
    fun overlappingSetsAreCountedExactly() {
        // Ash in both sets: "an Ash and a hand trap" is met by one Ash alone.
        val main = deck(14558127 to 3, 10045474 to 3)
        val both = CardSetOdds.of(main, listOf(CardSetOdds.Need(setOf(ash.id)), CardSetOdds.Need(setOf(ash.id, imperm.id))), lookup)
        assertEquals(listOf(3, 6), both.copies)
        assertEquals(3, both.shared)
        // The second set holds the first, so the answer is the first's alone.
        assertEquals(atLeastOne(3, 40, 5), both.first, 1e-12)
        // Disjoint, it is the product of two hypergeometrics' joint chance, as HandOdds counts it.
        val disjoint = CardSetOdds.of(main, listOf(CardSetOdds.Need(setOf(ash.id)), CardSetOdds.Need(setOf(imperm.id))), lookup)
        val sizes = mapOf("a" to 3, "b" to 3)
        val q = HandQuery(listOf(HandConstraint("a", 1, 60), HandConstraint("b", 1, 60)))
        assertEquals(HandOdds.probability(sizes, 40, 5, q), disjoint.first, 1e-12)
        assertEquals(0, disjoint.shared)
        // The old reading — the second set minus the first — asked for an Imperm besides the Ash: far too low.
        val old = HandOdds.probability(mapOf("a" to 3, "b" to 3), 40, 5, q)
        assertEquals(true, both.first > old + 0.2)
    }

    @Test
    fun atLeastTwoOfASetWithItsPrintingsMixed() {
        val main = deck(14558127 to 1, 14558128 to 2, 27204311 to 2)
        val p = CardSetOdds.probability(main, listOf(CardSetOdds.Need(setOf(ash.id, nibiru.id), atLeast = 2)), 5, lookup)
        val q = HandQuery(listOf(HandConstraint("x", 2, 60)))
        assertEquals(HandOdds.probability(mapOf("x" to 5), 40, 5, q), p, 1e-12)
    }

    @Test
    fun aDeckCutToADatedListLosesItsLastCopiesAndSaysWhich() {
        // Ash limited to one that day; one copy already in the Side Deck uses it, so every Main Deck copy goes.
        val limits = BanSource { if (it.id == ash.id) BanStatus.LIMITED else BanStatus.UNLIMITED }
        val d = Deck(main = listOf(CardId(14558127), CardId(10045474), CardId(14558128), CardId(10045474)), side = listOf(CardId(14558128)))
        val cut = CardSetOdds.legalised(d, lookup, limits)
        assertEquals(listOf(CardId(10045474), CardId(10045474)), cut.main)
        assertEquals(listOf(ash.name to 2), cut.removed)
        // Semi-limited with none elsewhere: the first two printings stay, the third goes.
        val semi = CardSetOdds.legalised(Deck(main = listOf(CardId(14558128), CardId(14558127), CardId(14558127))), lookup) { BanStatus.SEMI_LIMITED }
        assertEquals(listOf(CardId(14558128), CardId(14558127)), semi.main)
        // A card the pool does not know stays.
        assertEquals(listOf(CardId(1)), CardSetOdds.legalised(Deck(main = listOf(CardId(1))), lookup) { BanStatus.FORBIDDEN }.main)
    }
}
