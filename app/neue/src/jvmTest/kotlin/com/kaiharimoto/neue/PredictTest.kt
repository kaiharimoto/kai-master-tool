package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.predict.Predictions
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.neue.ai.predictions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `predict` through the real host (Phase I, I.6): a chance kept about the duel in play is settled by the duel's record
 * when it ends, one about a round by the games logged there, and neither can be said once it is decided.
 */
class PredictTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun input(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Double -> put(k, v)
                is List<*> -> putJsonArray(k) { v.forEach { add(JsonPrimitive(it.toString())) } }
                else -> error("$k")
            }
        }
    }

    private suspend fun NeueHolders.tool(name: String, vararg args: Pair<String, Any>): Part.ToolResult =
        ai.host.run(Part.ToolUse("t-" + UUID.randomUUID(), name, input(*args)))

    @Test
    fun aDuelsChanceIsScoredByItsEndAndARoundsByItsGames() = runBlocking {
        val h = TestHolders.make(scope)
        h.ai.files.delete(Predictions.PATH)
        h.tool("new_deck", "name" to "Predict deck", "main" to List(14) { "3 Ash Blossom & Joyous Spring" }.take(1) + listOf("3 Infinite Impermanence", "3 Raigeki", "3 Effect Veiler", "3 Maxx \"C\""))
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val setup = h.tool("duel_setup")
        assertFalse(setup.isError, setup.content)
        val duel = h.duel.game!!.header.id

        assertTrue(h.tool("predict", "about" to "duel", "chance" to 0.7).isError, "a duel's chance names the seat")
        assertTrue(h.tool("predict", "about" to "duel", "chance" to 170.0, "seat" to 0).isError, "170 is no chance, nor a percentage")
        val said = h.tool("predict", "about" to "duel", "chance" to 70.0, "seat" to 0)
        assertFalse(said.isError, said.content)
        assertTrue("70 %" in said.content && "Seat 0" in said.content, said.content)
        assertTrue("none settled yet" in said.content, said.content)

        // Seat 0 concedes: seat 1 wins, and the chance given to seat 0 did not come true.
        h.duel.run("concede")
        h.duel.noteResult() // as the Duel page does when the game changes
        withTimeout(10_000) { while (h.duel.readResults().none { it.duel == duel }) delay(20) }
        val settled = h.ai.predictions().single()
        assertEquals(false, settled.happened, "seat 0 conceded")
        assertTrue(h.tool("predict", "about" to "duel", "chance" to 0.2, "seat" to 0).isError, "that duel is over")

        // A round at the event: its games settle it, two won being the match.
        h.prep.putEvent(PrepEvent("e1", "Regional", "2026-10-17"))
        val r = h.tool("predict", "about" to "round", "round" to 1, "chance" to 0.6)
        assertFalse(r.isError, r.content)
        listOf(TestGame.WIN, TestGame.WIN).forEachIndexed { k, res ->
            h.prep.log(TestGame("g$k", 1_000L + k, h.builder.deckId, "Yubel", "Yubel", TestGame.FIRST, game = k + 1, result = res, eventId = "e1", round = 1))
        }
        val all = h.ai.predictions()
        assertEquals(listOf(false, true), all.map { it.happened })
        assertTrue(h.tool("predict", "about" to "round", "round" to 1, "chance" to 0.9).isError, "round 1 is decided")
        val record = Predictions.words(all)
        assertTrue(record.startsWith("2 settled: Brier 0.325"), "0.7 for a seat that lost, 0.6 for a round won: $record")
    }
}
