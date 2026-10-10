package com.kaiharimoto.mastertool.core.ai.eval

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The optimization set (Phase G, G.9): every case is a legal deck, and only the fix, measured and proved, passes. */
class OptimizationsTest {
    @Test
    fun everyCaseIsAFortyCardDeckWithItsPlantInIt() {
        Optimizations.all.forEach { c ->
            assertEquals(40, c.main.size, c.id)
            assertTrue(c.out.any { it in c.main }, "${c.id}: a planted card is in the deck")
            assertTrue(c.into.all { i -> c.binder.any { it.first == i } }, "${c.id}: every fix is a card the person owns")
            assertTrue(c.main.groupingBy { it }.eachCount().values.all { it <= 3 }, "${c.id}: three copies at most")
        }
        assertEquals(Optimizations.all.map { it.id }, EvalSets.byId(EvalSets.OPTIMIZE)!!.items.map { it.id })
    }

    @Test
    fun theBoundsAreNothingGuessedAndSolved() {
        // Doing nothing and guessing the numbers both fail; measuring and proving passes every case.
        assertEquals(Triple(0, 0, Optimizations.all.size), OptimizeBaselines.bounds())
        val c = Optimizations.all.first()
        assertTrue(OptimizeBaselines.guessed(c).read.startsWith("proposals refused"), OptimizeBaselines.guessed(c).read)
    }

    @Test
    fun anotherChangeProvedIsNotThePlant() {
        val c = Optimizations.byId("brick-for-starter")!!
        val t = OptimizeTable(c)
        val (before, err) = t.tool("hand_odds", obj("group" to "Hand traps", "turn" to "second"))
        assertFalse(err, before)
        val (after, _) = t.tool("hand_odds", obj("group" to "Hand traps", "turn" to "second", "without" to listOf("Bonfire"), "with" to listOf("Droll & Lock Bird")))
        fun pct(s: String) = Regex("""([0-9.]+)%""").findAll(s).last().groupValues[1]
        val proposed = t.tool(
            "deck_propose",
            obj(
                "title" to "Droll over Bonfire",
                "ops" to listOf(obj("op" to "remove", "card" to "Bonfire", "count" to 1), obj("op" to "add", "card" to "Droll & Lock Bird", "count" to 1)),
                "why" to "A hand trap going second rises from ${pct(before)}% to ${pct(after)}%.",
            ),
        )
        assertFalse(proposed.second, proposed.first)
        val g = t.grade()
        assertFalse(g.pass)
        assertTrue(g.read.startsWith("proposed another change"), g.read)
        // The table's own refusals.
        assertTrue(t.tool("hand_odds", obj("group" to "Nonsense")).second)
        assertTrue(t.tool("hand_odds", obj("cards" to listOf("Not a card"))).second)
        assertTrue(t.tool("edit_deck", obj()).second, "only the three tools")
    }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

    private fun json(v: Any): kotlinx.serialization.json.JsonElement = when (v) {
        is JsonObject -> v
        is List<*> -> JsonArray(v.map { json(it!!) })
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}
