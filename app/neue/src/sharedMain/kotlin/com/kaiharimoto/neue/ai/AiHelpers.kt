package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.check.FactCheck
import com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import kotlinx.coroutines.launch

// Helpers with a fresh mind, on [AiState]: the fact-check pass after an answer (1.0.58) and `delegate` (1.0.47).

/**
 * The last answer checked (1.0.58, kai's pick for "frontier level"): a helper with a fresh
 * mind and only the tools that look up card text, rulings and numbers lists every claim and
 * checks it. The result is kept with the conversation and drawn under the answer; if a claim
 * was wrong, the model is told what the check found and writes a short correction. API
 * connections only — a plan's command-line app runs its own loop.
 */
internal fun AiState.checkLastAnswer() {
    if (correcting) {
        correcting = false
        return
    }
    if (!prefs.factCheck || AiState.PHASE < 3) return
    val s = session ?: return
    if (s.mode != AiSession.MODE_CHAT) return
    val at = s.turns.indexOfLast { it.role == Role.ASSISTANT && it.text.isNotBlank() }
    if (at < 0 || s.checks.any { it.turn == at }) return
    val reply = s.turns[at].text
    if (!FactCheck.worthChecking(reply)) return
    val connection = prefs.connection ?: return
    val model = runCatching { backendFor(connection) }.getOrNull() ?: return
    if (model.runsOwnLoop) return
    checking = true
    backgroundJobs.removeAll { it.isCompleted }
    backgroundJobs += scope.launch {
        try {
            val index = h.builder.index
            val cards = ChatMarkdown.cards(reply).mapNotNull { name ->
                (index.byName(name) ?: (CardWords.resolve(name, index) as? Resolved.Found)?.card)
                    ?.let { it.name to it.description }
            }
            val look = setOf("card_info", "rulings", "calculate", "hand_odds", "search_cards")
            // What the checker's look-ups said: its "ok"s are held to these (FactCheck.ground), not taken on its word.
            val looked = mutableListOf<String>()
            val runner = ToolRunner { call ->
                if (call.name.removePrefix("mcp__neue__") !in look) {
                    Part.ToolResult(call.id, call.name, "A checker can only look up cards, rulings and numbers.", isError = true)
                } else {
                    host.run(call).also { if (!it.isError) looked += it.content }
                }
            }
            var said = ""
            var spent = Usage()
            AgentLoop(model, runner, maxSteps = 8, now = System::currentTimeMillis, budget = budgetFor(connection))
                .run(
                    TurnRequest(
                        FactCheck.CHECKER,
                        listOf(ChatTurn.user(FactCheck.brief(reply, cards))),
                        tools.filter { it.name in look },
                        connection.model,
                        "low",
                    ),
                )
                .collect { e ->
                    when (e) {
                        is AgentEvent.Appended -> if (e.turn.role == Role.ASSISTANT && e.turn.text.isNotBlank()) said = e.turn.text
                        is AgentEvent.Done -> spent = e.usage
                        else -> Unit
                    }
                }
            val claims = FactCheck.ground(FactCheck.parse(said), looked, cards.map { it.second }).ifEmpty { FactCheck.unreadable(said) }
            if (claims.isEmpty()) return@launch
            val check = FactCheck.Check(at, claims)
            val now = session?.takeIf { it.id == s.id } ?: return@launch
            val next = now.copy(checks = now.checks + check, usage = now.usage + spent)
            commit(next)
            // Wrong: the model says so itself, briefly, in a reply of its own (the answer above is never rewritten).
            if (check.wrong.isNotEmpty() && !running) {
                correcting = true
                val ask = ChatTurn(Role.USER, listOf(Part.Context(FactCheck.correction(check))), System.currentTimeMillis())
                val corrected = next.copy(turns = next.turns + ask, updatedAt = System.currentTimeMillis())
                commit(corrected)
                respond(corrected, connection)
            }
        } finally {
            checking = false
        }
    }
}

// ---- a helper with a fresh mind (1.0.47) --------------------------------------

/**
 * A big reading job handed to a helper (`delegate`, after DeepSeek Harness's and Claude
 * Code's sub-agents): the same model, a fresh history holding only [task], and only the
 * tools that look — it reads twenty lists or a whole web and hands back one report, so
 * the conversation carries the report, not the reading. API connections only: a CLI
 * runs its own loop and has its own helpers.
 */
suspend fun AiState.delegate(task: String, steps: Int): Result<String> = runCatching {
    val connection = prefs.connection ?: error("No connection is set up.")
    val model = backendFor(connection)
    if (model.runsOwnLoop) error("A helper needs an API connection; on a plan's command-line app, do the reading yourself.")
    val look = tools.filter { it.name in AiTools.readOnly }
    // Its own lean prompt: the conversation's carries its mode (an interview, a study), which is not the helper's job.
    val system = PromptBuilder.helper(name, files.soul(name))
    val ask = ChatTurn.user("The job: " + task.trim())
    var report = ""
    var cutShort = false
    val runner = ToolRunner { call ->
        if (call.name.removePrefix("mcp__neue__") !in AiTools.readOnly) {
            Part.ToolResult(call.id, call.name, "A helper can only look; ${call.name} is not one of its tools.", isError = true)
        } else {
            host.run(call)
        }
    }
    AgentLoop(model, runner, maxSteps = steps, now = System::currentTimeMillis, budget = budgetFor(connection))
        .run(TurnRequest(system, listOf(ask), look, connection.model, "medium"))
        .collect { e ->
            when (e) {
                is AgentEvent.Appended -> if (e.turn.role == Role.ASSISTANT && e.turn.text.isNotBlank()) report = e.turn.text
                is AgentEvent.Failed -> error(e.message)
                is AgentEvent.Done -> cutShort = e.outOfSteps
                else -> Unit
            }
        }
    // Stopped at its cap, its last words are a note on the way, not a report: the assistant is told so.
    if (cutShort) {
        "${PromptBuilder.HELPER_CUT_SHORT}\n\n" + report.ifBlank { "(Nothing written yet.)" }
    } else {
        report.ifBlank { error("The helper came back without a report.") }
    }
}
