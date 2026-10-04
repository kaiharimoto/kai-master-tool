package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class TmpTimingTest {
    @Test
    fun time() {
        val cards = (1..20).map { Card(CardId(it), "Card $it", "Effect Monster", "effect") }
        val deck = DeckEntry("d", "D", Deck(main = (1..20).flatMap { i -> List(2) { CardId(i) } }), 0, 0)
        val host = object : WorldHost {
            override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
            override fun cardNamed(name: String) = null
            override fun search(query: String, limit: Int) = emptyList<Card>()
            override fun deck(id: String?) = deck
            override fun decks() = listOf(deck)
            override fun groups(deckId: String) = mapOf("A" to (1..6).toList(), "B" to (5..10).toList(), "C" to (11..14).toList())
        }
        val conds = JsonArray(List(12) { JsonPrimitive("A>=1 & B>=1 & C<=1") })
        val t = System.currentTimeMillis()
        Instruments.run("openings", JsonObject(mapOf("conditions" to conds, "trials" to JsonPrimitive(500000))), host)
        println("TIMING openings 12x500k: ${System.currentTimeMillis() - t} ms")
    }
}
