package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.wire.OpenAiStream
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Context you can see and steer (1.0.56): windows, what fills them, clearing, carrying on, recall. */
class ContextTest {
    @Test
    fun windowsAreReadOffTheModelsName() {
        assertEquals(200_000, ContextWindows.of("anthropic", "claude-opus-5-5"))
        assertEquals(1_000_000, ContextWindows.of("anthropic", "claude-sonnet-5-5[1m]"))
        assertEquals(1_000_000, ContextWindows.of("gemini", "gemini-2.5-pro"))
        assertEquals(400_000, ContextWindows.of("openai", "gpt-5"))
        assertEquals(128_000, ContextWindows.of("compatible", "deepseek-chat"))
        assertEquals(32_000, ContextWindows.of("ollama", "qwen3:8b", local = true))
        assertEquals("950", ContextWindows.words(950))
        assertEquals("3.4k", ContextWindows.words(3_456))
        assertEquals("31k", ContextWindows.words(31_200))
        assertEquals("200k", ContextWindows.words(200_000))
        assertEquals("1M", ContextWindows.words(1_000_000))
    }

    @Test
    fun theToolsCountInTheEstimate() {
        val turns = listOf(ChatTurn.user("hello"))
        assertTrue(Compaction.estimate("sys", turns, AiTools.all) > Compaction.estimate("sys", turns) + 5_000, "fifty-odd tool specs are thousands of tokens")
    }

    @Test
    fun theBreakdownSumsToWhatTheProviderCounted() {
        val session = AiSession(
            "s",
            system = "You are Ai.\n## The rules of the game\n" + "r".repeat(4000) + "\n## Memory\n" + "m".repeat(800) + "\n## Skills\n- a skill",
            turns = listOf(
                ChatTurn.user("What should I side?", context = "page: builder"),
                ChatTurn(Role.ASSISTANT, listOf(Part.ToolUse("t", "get_deck", JsonObject(emptyMap())))),
                ChatTurn(Role.USER, listOf(Part.ToolResult("t", "get_deck", "x".repeat(2000)))),
                ChatTurn.assistant("Side these."),
            ),
        )
        val estimated = ContextBreakdown.of(session, emptyList())
        assertEquals(listOf("Voice and instructions", "Rules of the game", "Memory", "Skills", "What the app showed", "The conversation", "Tools' results"), estimated.map { it.label })
        assertTrue(estimated.first { it.label == "Rules of the game" }.tokens in 1000L..1010L)
        val scaled = ContextBreakdown.of(session, emptyList(), measured = 10_000)
        val sum = scaled.sumOf { it.tokens }
        assertTrue(sum in 9_990..10_000, "scaled to the measured total: $sum")
    }

    @Test
    fun clearedResultsAreSentShortAndKeptWhole() {
        val long = "y".repeat(5_000)
        val turns = listOf(
            ChatTurn(Role.USER, listOf(Part.ToolResult("a", "get_deck", long))),
            ChatTurn.assistant("ok"),
            ChatTurn(Role.USER, listOf(Part.ToolResult("b", "get_deck", long))),
        )
        val s = AiSession("s", turns = turns, clearedBefore = 2)
        assertTrue(s.sent[0].toolResults.single().content.length < 400, "before the mark: cut short")
        assertEquals(5_000, s.sent[2].toolResults.single().content.length, "after it: whole")
        assertEquals(5_000, s.turns[0].toolResults.single().content.length, "the saved turns stay whole")
    }

    @Test
    fun aFreshStartCarriesTheSummaryInFront() {
        val s = AiSession("s", turns = listOf(ChatTurn.user("Next?")), summary = "They play Labrynth.", carriedFrom = "old")
        val first = s.sent.first()
        assertTrue((first.parts.first() as Part.Context).text.contains("carries on from"))
        assertTrue(first.parts.first().let { it is Part.Context && "Labrynth" in it.text })
        assertEquals(s.turns, AiSession("s", turns = s.turns).sent, "no summary, nothing added")
    }

    @Test
    fun recallFindsTheSummarisedWords() {
        val a = AiSession(
            "a", title = "Siding", turns = listOf(
                ChatTurn(Role.USER, listOf(Part.Text("I side 3 Nibiru against Snake-Eye going second")), at = 10),
                ChatTurn(Role.ASSISTANT, listOf(Part.Text("Noted.")), at = 11),
            ),
        )
        val b = AiSession("b", title = "Other", turns = listOf(ChatTurn(Role.USER, listOf(Part.Text("Nibiru is good")), at = 20)))
        val hits = Recall.search(listOf(a, b), "nibiru snake-eye")
        assertEquals("a", hits.first().session, "both words beat one word")
        assertEquals(Role.USER, hits.first().who)
        assertTrue(Recall.search(listOf(a), "zzz").isEmpty())
    }

    @Test
    fun openAiUsageKeepsTheCacheApartAsAnthropicDoes() {
        val stream = OpenAiStream()
        stream.line("""data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1000,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":800}}}""")
        val usage = (stream.finish() as BackendEvent.Finished).usage!!
        assertEquals(200, usage.input)
        assertEquals(800, usage.cacheRead)
        assertEquals(1000, usage.read)
    }
}
