package com.kaiharimoto.mastertool.core.ai.trace

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.ToolTiers
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.sync.InboundPath
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A run of Ai traced (Phase H, H.8): what it did and cost, and never a word of what was said. */
class RunTraceTest {
    private fun tracer(provider: String = "anthropic", model: String = "claude-opus-5-5") =
        RunTracer("r1", "s1", "chat", provider, model, started = 1_000)

    @Test
    fun aRunIsItsRoundsToolsRetriesAndCost() {
        val t = tracer()
        t.round(Usage(input = 10_000, output = 500))
        t.toolStarted("c1", "card_info", at = 2_000)
        t.toolDone("c1", "card_info", error = false, content = "Ash Blossom & Joyous Spring: …", at = 2_400)
        t.retry()
        t.toolStarted("c2", "edit_deck", at = 3_000)
        t.toolDone("c2", "edit_deck", error = true, content = ToolTiers.DENIED, at = 3_100)
        t.toolStarted("c3", "web_fetch", at = 3_200)
        t.toolDone("c3", "web_fetch", error = true, content = "The page could not be read.", at = 3_300)
        t.round(Usage(input = 12_000, output = 300, cacheRead = 9_000))
        t.done(Usage(), outOfSteps = false)
        val r = t.finish(at = 9_000, stopped = false)
        assertEquals(2, r.rounds.size)
        assertEquals(listOf("card_info", "edit_deck", "web_fetch"), r.tools.map { it.name })
        assertEquals(400, r.tools[0].ms)
        assertEquals(1, r.refusals)
        assertEquals(1, r.toolErrors)
        assertEquals(1, r.retries)
        assertEquals(RunTrace.DONE, r.end)
        assertEquals(8_000, r.ms)
        assertEquals(22_000, r.usage.input)
        // Priced by the list when the provider does not say.
        assertTrue((r.costUsd ?: 0.0) > 0.0, "${r.costUsd}")
        val words = TraceWords.run(r)
        assertTrue("2 rounds" in words && "3 tools (1 failed, 1 refused)" in words && "1 retry" in words && "≈ $" in words, words)
    }

    @Test
    fun aTraceHoldsNoWordOfTheConversation() {
        val t = tracer()
        t.toolStarted("c1", "memory", at = 0)
        t.toolDone("c1", "memory", error = false, content = "The person's secret plan: side 3 Droll against Labrynth", at = 10)
        t.failed("overloaded_error: the provider is busy " + "x".repeat(1_000))
        val r = t.finish(at = 20, stopped = false)
        val line = TraceBook.line(r)
        assertFalse("secret" in line || "Droll" in line || "Labrynth" in line, line)
        assertEquals(RunTrace.FAILED, r.end)
        assertEquals(RunTrace.FAILURE_MAX, r.failure.length)
        // Every refusal the host words is told apart, and no ordinary answer is.
        assertTrue(ToolTiers.refused(ToolTiers.offWords(AiTools.named("edit_deck")!!)))
        assertFalse(ToolTiers.refused("Added 3 Ash Blossom"))
    }

    @Test
    fun aStopMidCallKeepsTheCallUnanswered() {
        val t = tracer(provider = "openai-compatible", model = "my-local-model")
        t.toolStarted("c1", "duel_search", at = 100)
        val r = t.finish(at = 600, stopped = true)
        assertEquals(RunTrace.STOPPED, r.end)
        assertEquals(1, r.tools.size)
        assertTrue(r.tools.single().failed)
        assertEquals(500, r.tools.single().ms)
        // A model with no list price and no figure of its own is not priced: unknown, never zero.
        assertNull(r.costUsd)
    }

    @Test
    fun aCliSaysItsOwnCostAtTheEnd() {
        val t = tracer(provider = "claude-code", model = "sonnet")
        t.round(Usage(input = 100, output = 50))
        t.done(Usage(input = 4_000, output = 900, costUsd = 0.12), outOfSteps = true)
        val r = t.finish(at = 2_000, stopped = false)
        assertEquals(0.12, r.costUsd)
        assertEquals(4_000, r.usage.input)
        assertEquals(RunTrace.OUT_OF_STEPS, r.end)
    }

    @Test
    fun daysAreKeptTwoWeeksAndReadForgivingly() {
        val files = listOf("2026-09-20.jsonl", "2026-09-26.jsonl", "2026-09-27.jsonl", "2026-10-11.jsonl", "notes.txt", "2026-13-01.jsonl")
        assertEquals(listOf("2026-09-20.jsonl", "2026-09-26.jsonl"), TraceBook.stale(files, "2026-10-11"))
        assertEquals(TraceBook.dayNumber("2026-10-11") - 1, TraceBook.dayNumber("2026-10-10"))
        assertEquals(TraceBook.dayNumber("2026-03-01") - 1, TraceBook.dayNumber("2026-02-28"))
        val a = tracer().finish(at = 5, stopped = false)
        val text = TraceBook.line(a) + "\n{not json\n" + TraceBook.line(a.copy(id = "r2")) + """{"id":"r3","later":1}""".let { "\n$it" }
        assertEquals(listOf("r1", "r2", "r3"), TraceBook.read(text).map { it.id })
        assertEquals("No runs yet.", TraceWords.total(emptyList()))
        assertTrue(TraceWords.total(TraceBook.read(text)).startsWith("3 runs"))
    }

    @Test
    fun tracesNeverTravel() {
        assertTrue(InboundPath.aiPrivate("${TraceBook.FOLDER}/2026-10-11.jsonl"))
        assertEquals(null, InboundPath.safe("ai/${TraceBook.FOLDER}/2026-10-11.jsonl"))
    }

    @Test
    fun aFlowOfEventsIsTracedAsItPasses() = runTest {
        var clock = 0L
        var kept: RunTrace? = null
        val call = Part.ToolUse("c1", "card_info", JsonObject(emptyMap()))
        val result = Part.ToolResult("c1", "card_info", "Ash Blossom")
        val events = flowOf(
            AgentEvent.ToolRunning(call),
            AgentEvent.Text("Ash is a hand trap."),
            AgentEvent.ToolDone(result),
            AgentEvent.Round(Usage(input = 900, output = 40), measured = true),
            AgentEvent.Done(StopReason.END, Usage(input = 900, output = 40)),
        )
        val passed = events.traced(tracer(), { clock += 100; clock }) { kept = it }.toList()
        assertEquals(5, passed.size)
        val r = kept!!
        assertEquals(RunTrace.DONE, r.end)
        assertEquals(listOf("card_info"), r.tools.map { it.name })
        assertFalse("hand trap" in TraceBook.line(r))
    }
}
