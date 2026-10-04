package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The instruments are only worth having if they are right: each is checked against arithmetic done another way. */
class InstrumentsTest {
    private val cards = listOf(
        Card(CardId(1), "Snake-Eye Ash", "Effect Monster", "effect", "If this card is Normal or Special Summoned: You can add 1 Level 1 FIRE monster from your Deck to your hand.", level = 1, atk = 800),
        Card(CardId(2), "Snake-Eye Oak", "Effect Monster", "effect", "You can Special Summon 1 \"Snake-Eye\" monster from your hand or GY.", level = 1, atk = 1200),
        Card(CardId(3), "Brick", "Normal Monster", "normal", "", level = 8, atk = 3000),
        Card(CardId(4), "Ash Blossom & Joyous Spring", "Tuner Effect Monster", "effect", "Discard this card; negate that effect.", level = 3, atk = 0),
        Card(CardId(5), "Original Sinful Spoils - Snake-Eye", "Spell Card", "spell", "Add 1 \"Snake-Eye Ash\" from your Deck to your hand."),
        Card(CardId(6), "Filler", "Spell Card", "spell", ""),
        Card(CardId(7), "I:P Masquerena", "Link Effect Monster", "effect_link", "", linkValue = 2),
    )

    private val deck = DeckEntry(
        "d1", "Snake-Eye",
        Deck(
            main = List(3) { CardId(1) } + List(3) { CardId(2) } + List(3) { CardId(3) } + List(3) { CardId(4) } +
                List(3) { CardId(5) } + List(25) { CardId(6) },
            extra = listOf(CardId(7)),
        ),
        0, 0,
    )

    private val host = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override fun search(query: String, limit: Int) = emptyList<Card>()
        override fun deck(id: String?) = if (id == null || id == "d1") deck else null
        override fun decks() = listOf(deck)
        override fun groups(deckId: String) = mapOf("Starters" to listOf(1, 2, 5), "Hand traps" to listOf(4), "Bricks" to listOf(3))
        override fun games() = listOf(
            game("a", TestGame.FIRST, TestGame.WIN), game("a", TestGame.FIRST, TestGame.WIN), game("a", TestGame.SECOND, TestGame.LOSS),
            game("b", TestGame.SECOND, TestGame.WIN, 2),
        )
    }

    private fun game(opp: String, turn: String, result: String, g: Int = 1) =
        TestGame("$opp$turn$result$g", 0, "d1", opp, opp.uppercase(), turn, g, result)

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    @Test
    fun conditionsRead() {
        assertEquals(listOf(Instruments.Clause("Starters", 1, 60), Instruments.Clause("Hand traps", 1, 60)), Instruments.parseCondition("Starters>=1 & Hand traps>=1"))
        assertEquals(listOf(Instruments.Clause("Bricks", 0, 1)), Instruments.parseCondition("Bricks < 2"))
        // A name with an & in it, quoted, stays one name.
        assertEquals(
            listOf(Instruments.Clause("Ash Blossom & Joyous Spring", 1, 1), Instruments.Clause("Bricks", 0, 0)),
            Instruments.parseCondition("\"Ash Blossom & Joyous Spring\"=1 and Bricks<=0"),
        )
        assertFailsWith<IllegalArgumentException> { Instruments.parseCondition("Starters") }
    }

    @Test
    fun openingsAgreesWithTheHypergeometricAndItsOwnSimulation() {
        val r = Instruments.run("openings", args("conditions" to listOf("Starters>=1", "Starters>=1 & Hand traps>=1"), "trials" to 40_000), host)
        val rows = r.answer.jsonArray.map { it.jsonObject }
        // 9 starters in 40, five cards: 1 − C(31,5)/C(40,5).
        val exact = 1 - 169_911.0 / 658_008.0
        assertTrue(abs(rows[0]["exactFirst"]!!.jsonPrimitive.double - exact) < 1e-9)
        rows.forEach { row ->
            assertTrue(abs(row["exactFirst"]!!.jsonPrimitive.double - row["simFirst"]!!.jsonPrimitive.double) < 0.01, row.toString())
            assertTrue(abs(row["exactSecond"]!!.jsonPrimitive.double - row["simSecond"]!!.jsonPrimitive.double) < 0.01, row.toString())
            assertTrue(row["exactSecond"]!!.jsonPrimitive.double > row["exactFirst"]!!.jsonPrimitive.double)
        }
        assertEquals(listOf(BoardKind.TABLE, BoardKind.CHART, BoardKind.CARDS), r.boards.map { it.kind })
        r.boards.filter { it.kind == BoardKind.CHART }.forEach { assertTrue(WorldChart.parse(it.payload).isSuccess) }
    }

    @Test
    fun overlappingSetsAreSimulatedNotMiscounted() {
        val r = Instruments.run(
            "openings",
            JsonObject(mapOf(
                "conditions" to JsonArray(listOf(JsonPrimitive("Snakes>=1 & Ash>=1"))),
                "groups" to JsonObject(mapOf(
                    "Snakes" to JsonArray(listOf(JsonPrimitive("Snake-Eye Ash"), JsonPrimitive("Snake-Eye Oak"))),
                    "Ash" to JsonArray(listOf(JsonPrimitive("Snake-Eye Ash"))),
                )),
            )),
            host,
        )
        val row = r.answer.jsonArray.single().jsonObject
        assertNull(row["exactFirst"])
        assertTrue("simulated" in r.lines.joinToString())
    }

    @Test
    fun theSameSeedGivesTheSameStudy() {
        val a = Instruments.run("openings", args("conditions" to listOf("Bricks>=1"), "seed" to 9), host)
        val b = Instruments.run("openings", args("conditions" to listOf("Bricks>=1"), "seed" to 9), host)
        assertEquals(a.answer, b.answer)
        assertEquals(a.boards, b.boards)
    }

    @Test
    fun ratiosRiseWithCopies() {
        val r = Instruments.run("ratios", args("condition" to "Starters>=1", "card" to "Snake-Eye Ash"), host)
        val first = r.answer.jsonObject["first"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(4, first.size)
        assertTrue(first.zipWithNext().all { (a, b) -> b > a }, first.toString())
        val grow = Instruments.run("ratios", args("condition" to "Starters>=1", "grow" to 3), host)
        val g = grow.answer.jsonObject["first"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertTrue(g.zipWithNext().all { (a, b) -> b < a }, g.toString())
    }

    @Test
    fun theCardWebReadsWhoNamesWhom() {
        val r = Instruments.run("card_web", JsonObject(emptyMap()), host)
        val edges = r.answer.jsonObject["edges"]!!.jsonArray.map { e -> e.jsonArray.map { it.jsonPrimitive.content } }
        assertTrue(listOf("Original Sinful Spoils - Snake-Eye", "Snake-Eye Ash", "searches") in edges, edges.toString())
        assertTrue(listOf("Snake-Eye Oak", "Snake-Eye Ash", "summons") in edges, edges.toString())
        // An archetype named in quotes reaches every card of it but the card itself.
        assertTrue(edges.none { it[0] == it[1] })
        assertTrue(WorldGraph.parse(r.boards.single().payload).isSuccess)
    }

    @Test
    fun compositionAndMatchupsDrawWhatTheyCount() {
        val c = Instruments.run("composition", JsonObject(emptyMap()), host)
        assertEquals(2, c.answer.jsonObject["kinds"]!!.jsonObject.size) // monsters and spells; no traps
        c.boards.forEach { assertTrue(WorldChart.parse(it.payload).isSuccess, it.title) }
        val m = Instruments.run("matchups", JsonObject(emptyMap()), host)
        val heat = WorldChart.parse(m.boards.first().payload).getOrThrow() as WorldChart.Heatmap
        assertEquals(listOf("A", "B"), heat.rows)
        assertEquals(66.7, heat.values[0][4])
    }

    @Test
    fun anUnknownInstrumentOrWordSaysWhy() {
        assertFailsWith<IllegalArgumentException> { Instruments.run("telescope", JsonObject(emptyMap()), host) }
        val e = assertFailsWith<IllegalArgumentException> { Instruments.run("openings", args("conditions" to listOf("Dragons>=1")), host) }
        assertTrue("neither a group" in e.message.orEmpty())
    }
}
