package com.kaiharimoto.neue.ai.course

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.budgetFor
import com.kaiharimoto.neue.ai.closeBackend
import com.kaiharimoto.neue.ai.newBackend
import com.kaiharimoto.neue.ai.ownMcp
import com.kaiharimoto.neue.ai.runsAsCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A step the model or the network stopped: [auth] when the connection's key or account was refused ([StudyRetry]). */
internal class StepFailed(message: String, val auth: Boolean) : Exception(message)

/** What one step left: what it cost, and what it was told when it used its room in the guide. */
internal class StepOutcome(val usage: Usage, val filled: String?, val turns: List<ChatTurn>)

/**
 * One piece of unattended work as a conversation of its own (a course study's step, an exam's position): [brief] under
 * [system], [offered] tools and no others, the host answering for it ([StudyRun]) — never for the panel's conversation —
 * and [local] answering what is the work's own before the host is asked. On a coding plan's command-line app the tools
 * reach it over an MCP server of the step's own, stopped after. [monitor] is shown what it reads and writes.
 */
internal suspend fun AiState.studyStep(
    courseId: String,
    deckId: String,
    deckName: String,
    system: String,
    brief: String,
    offered: List<ToolSpec>,
    effort: String,
    maxSteps: Int,
    room: Triple<Int, Int, String>? = null,
    monitor: StudyMonitor? = null,
    local: suspend (Part.ToolUse) -> Part.ToolResult? = { null },
): StepOutcome {
    val connection = prefs.connection ?: error("$name has no connection set up.")
    val names = offered.map { it.name }.toSet()
    var turns = listOf(ChatTurn.user(brief))
    val run = StudyRun(courseId, deckId, deckName, { turns.drop(1) }, room)
    suspend fun answer(call: Part.ToolUse): Part.ToolResult {
        local(call)?.let { return it }
        return if (call.name.removePrefix("mcp__neue__") !in names) {
            Part.ToolResult(call.id, call.name, "${call.name} is not part of this work.", isError = true)
        } else {
            host.run(call).also { monitor?.saw(call, it) }
        }
    }
    monitor?.step()
    val served = if (runsAsCli(connection)) {
        ownMcp(offered) { call -> withContext(Dispatchers.Main + run) { answer(call).also { run.record(call, it) } } }
            ?: error("The app could not open the tools to the command-line app.")
    } else {
        null
    }
    val model = try {
        newBackend(connection, served)
    } catch (t: Throwable) {
        served?.stop()
        throw t
    }
    try {
        var spent = Usage()
        withContext(run) {
            AgentLoop(model, ToolRunner { call -> answer(call) }, maxSteps = maxSteps, now = System::currentTimeMillis, budget = budgetFor(connection))
                .run(TurnRequest(system, turns, offered, connection.model, effort))
                .collect { e ->
                    when (e) {
                        is AgentEvent.Appended -> turns = turns + e.turn
                        is AgentEvent.Text -> monitor?.thought(e.delta)
                        is AgentEvent.Reasoning -> monitor?.thought(e.delta)
                        is AgentEvent.Round -> spent += e.usage
                        is AgentEvent.Failed -> throw StepFailed(e.message, e.auth)
                        else -> Unit
                    }
                }
        }
        return StepOutcome(spent, run.filled, turns)
    } finally {
        closeBackend(model)
        served?.stop()
    }
}
