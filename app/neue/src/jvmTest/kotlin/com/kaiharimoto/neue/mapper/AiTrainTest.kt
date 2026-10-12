package com.kaiharimoto.neue.mapper

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolTiers
import com.kaiharimoto.mastertool.core.prefs.TierPrefs
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.TestHolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ai's hands on the Mapper's training (I.10), on the real app: the switch is the person's, a round runs the person's Python
 * and is judged by the outside as well as Changes, and reading the run is reading.
 */
class AiTrainTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun NeueHolders.tool(name: String, vararg args: Pair<String, String>): Part.ToolResult =
        ai.host.run(Part.ToolUse("t-" + UUID.randomUUID(), name, JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) })))

    @Test
    fun aRoundNeverStartsWhileThePersonsSwitchIsOff() = runBlocking {
        val h = TestHolders.make(scope)
        assertFalse(h.neue.prefs.mapperTrain.on)
        val start = h.tool("train_start")
        assertTrue(start.isError, start.content)
        assertTrue("Only the person switches it on" in start.content || "Save the deck first" in start.content, start.content)
        assertFalse(h.training.running)
        assertFalse(h.neue.prefs.mapperTrain.on, "and Ai's call never switched it on")
        val status = h.tool("train_status")
        assertTrue(!status.isError || "Save the deck first" in status.content, status.content)
        // With the outside turned off, a round is not even offered: it runs the person's Python.
        h.neue.update { it.copy(ai = it.ai.copy(tiers = TierPrefs(outward = TierPrefs.OFF))) }
        val off = h.tool("train_start")
        assertTrue(off.isError && ToolTiers.refused(off.content) && "own Python" in off.content, off.content)
        // Reading the run and the held-out aggregates stays reading.
        val held = h.tool("eval_heldout")
        assertFalse(ToolTiers.refused(held.content), held.content)
    }
}
