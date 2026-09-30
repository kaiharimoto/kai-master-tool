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
}
