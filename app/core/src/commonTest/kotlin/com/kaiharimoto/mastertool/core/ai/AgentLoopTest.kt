package com.kaiharimoto.mastertool.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentLoopTest {

    /** A model that answers from a script, one entry per round, and remembers what it was sent. */
    private class Scripted(private val rounds: List<List<BackendEvent>>, override val runsOwnLoop: Boolean = false) : ModelBackend {
        val sent = mutableListOf<TurnRequest>()
        override fun turn(request: TurnRequest): Flow<BackendEvent> = flow {
            sent += request
            rounds[sent.size - 1].forEach { emit(it) }
        }
    }

    private fun call(id: String, name: String) = Part.ToolUse(id, name, JsonObject(mapOf("x" to JsonPrimitive(1))))

    private val request = TurnRequest("system", listOf(ChatTurn.user("Build me Branded")), AiTools.all)

    @Test
    fun toolsRunAndEveryResultGoesBackInOneTurn() = runTest {
        val backend = Scripted(
            listOf(
                listOf(
                    BackendEvent.TextDelta("On it."),
                    BackendEvent.Finished(
                        StopReason.TOOL_USE,
                        ChatTurn(Role.ASSISTANT, listOf(Part.Text("On it."), call("a", "search_cards"), call("b", "card_info"))),
                    ),
                ),
                listOf(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Done."))),
            ),
        )
        val ran = mutableListOf<String>()
        val events = AgentLoop(backend, { c -> ran += c.name; Part.ToolResult(c.id, c.name, "ok ${c.id}") }).run(request).toList()

        assertEquals(listOf("search_cards", "card_info"), ran, "in the order the model wrote them")
        val second = backend.sent[1].history
        assertEquals(3, second.size)
        val answer = second.last()
        assertTrue(answer.isToolResults)
        assertEquals(listOf("a", "b"), answer.toolResults.map { it.id }, "both results in one turn")
        val done = events.last()
        assertIs<AgentEvent.Done>(done)
        assertEquals(StopReason.END, done.stop)
        assertEquals(3, events.count { it is AgentEvent.Appended })
    }

    @Test
    fun aToolThatThrowsIsAnErrorResultNotACrash() = runTest {
        val backend = Scripted(
            listOf(
                listOf(BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(call("a", "edit_deck"))))),
                listOf(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Sorry."))),
            ),
        )
        AgentLoop(backend, { error("boom") }).run(request).toList()
        val result = backend.sent[1].history.last().toolResults.single()
        assertTrue(result.isError)
        assertTrue("boom" in result.content)
    }

    @Test
    fun aCliRunsItsOwnLoopSoItsTurnIsTheLastRound() = runTest {
        val backend = Scripted(
            listOf(listOf(BackendEvent.Session("s-1"), BackendEvent.Finished(StopReason.END, null, text = "Made the deck."))),
            runsOwnLoop = true,
        )
        val events = AgentLoop(backend, { error("never") }).run(request).toList()
        assertEquals(1, backend.sent.size)
        val appended = events.filterIsInstance<AgentEvent.Appended>().single().turn
        assertEquals("Made the deck.", appended.text)
        assertEquals("s-1", events.filterIsInstance<AgentEvent.Session>().single().id)
    }

    @Test
    fun theSessionIsCarriedToTheNextRound() = runTest {
        val backend = Scripted(
            listOf(
                listOf(BackendEvent.Session("s-9"), BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(call("a", "app_state"))))),
                listOf(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("ok"))),
            ),
        )
        AgentLoop(backend, { Part.ToolResult(it.id, it.name, "x") }).run(request).toList()
        assertEquals("s-9", backend.sent[1].resume)
    }

    @Test
    fun aFailureEndsTheRunAndSaysWhetherItIsTheLogin() = runTest {
        val backend = Scripted(listOf(listOf(BackendEvent.Failed("Please run /login", auth = true))))
        val last = AgentLoop(backend, { error("never") }).run(request).toList().last()
        assertIs<AgentEvent.Failed>(last)
        assertTrue(last.auth)
    }

    @Test
    fun itStopsAfterTheStepCap() = runTest {
        val round = listOf(BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(call("a", "app_state")))))
        val backend = Scripted(List(3) { round })
        val events = AgentLoop(backend, { Part.ToolResult(it.id, it.name, "x") }, maxSteps = 3).run(request).toList()
        assertEquals(3, backend.sent.size)
        assertIs<AgentEvent.Done>(events.last())
    }

    @Test
    fun aStumbleIsTriedAgainButNeverAfterWordsWentOut() = runTest {
        val backend = Scripted(
            listOf(
                listOf(BackendEvent.Failed("overloaded", retryable = true)),
                listOf(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Here."))),
            ),
        )
        val events = AgentLoop(backend, { c -> Part.ToolResult(c.id, c.name, "") }, retryDelays = listOf(1, 1)).run(request).toList()
        assertEquals(2, backend.sent.size, "tried again once")
        assertIs<AgentEvent.Done>(events.last())

        val spoke = Scripted(listOf(listOf(BackendEvent.TextDelta("Half"), BackendEvent.Failed("dropped", retryable = true))))
        val out = AgentLoop(spoke, { c -> Part.ToolResult(c.id, c.name, "") }, retryDelays = listOf(1)).run(request).toList()
        assertEquals(1, spoke.sent.size, "words already shown: not said twice")
        assertIs<AgentEvent.Failed>(out.last())
    }

    @Test
    fun aHugeResultIsCutAndAPausedTurnCarriesOn() = runTest {
        val backend = Scripted(
            listOf(
                listOf(BackendEvent.Finished(StopReason.PAUSED, ChatTurn.assistant("Searching…"))),
                listOf(BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(call("a", "get_deck"))))),
                listOf(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Done."))),
            ),
        )
        AgentLoop(backend, { c -> Part.ToolResult(c.id, c.name, "x".repeat(50_000)) }).run(request).toList()
        assertEquals(3, backend.sent.size, "the paused turn was sent back to carry on")
        val result = backend.sent[2].history.last().toolResults.single().content
        assertTrue(result.length < 17_000 && "characters cut" in result)
    }

    @Test
    fun anOverflowShortensOldResultsAndTriesOnce() = runTest {
        val long = ChatTurn(Role.USER, listOf(Part.ToolResult("old", "get_deck", "y".repeat(5_000))))
        val history = listOf(ChatTurn.user("a"), ChatTurn(Role.ASSISTANT, listOf(call("old", "get_deck"))), long) +
            (1..4).flatMap { listOf(ChatTurn.user("q$it"), ChatTurn.assistant("a$it")) } + ChatTurn.user("now")
        val backend = Scripted(
            listOf(
                listOf(BackendEvent.Failed("prompt is too long: 250000 tokens > 200000 maximum")),
                listOf(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Fits now."))),
            ),
        )
        val events = AgentLoop(backend, { c -> Part.ToolResult(c.id, c.name, "") }).run(request.copy(history = history)).toList()
        assertEquals(2, backend.sent.size)
        assertTrue(backend.sent[1].history[2].toolResults.single().content.length < 1_000)
        assertTrue(events.any { it is AgentEvent.Notice })
        assertIs<AgentEvent.Done>(events.last())
    }
}

class CompactionTest {
    @Test
    fun aCutFallsOnSomethingThePersonSaidAndTheSummaryRidesInFront() {
        val turns = listOf(
            ChatTurn.user("Build Branded"),
            ChatTurn(Role.ASSISTANT, listOf(Part.ToolUse("t", "new_deck", JsonObject(emptyMap())))),
            ChatTurn(Role.USER, listOf(Part.ToolResult("t", "new_deck", "z".repeat(4_000)))),
            ChatTurn.assistant("Built."),
            ChatTurn.user("Now side it"),
            ChatTurn.assistant("Sided."),
        )
        val cut = Compaction.cutAt(turns, keepTokens = 100)
        assertEquals(4, cut, "the first message whose tail fits; never a tool result")
        val s = AiSession("s", turns = turns, summary = "Built Branded.", summarized = cut!!)
        assertEquals(2, s.sent.size)
        assertTrue(s.sent.first().parts.first() is Part.Context)
        assertEquals("Now side it", s.sent.first().text)
        val pruned = Compaction.prune(turns, keep = 2)
        assertTrue(pruned[2].toolResults.single().content.length < 700)
        assertEquals(turns[1], pruned[1], "an assistant's own turn is never touched")
        assertTrue(Compaction.overflowed("This model's maximum context length is 128000 tokens"))
    }
}
