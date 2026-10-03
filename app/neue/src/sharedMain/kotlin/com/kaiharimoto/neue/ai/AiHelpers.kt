package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.TurnRequest
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
    if (!com.kaiharimoto.mastertool.core.ai.check.FactCheck.worthChecking(reply)) return
    val connection = prefs.connection ?: return
    val model = runCatching { backendFor(connection) }.getOrNull() ?: return
    if (model.runsOwnLoop) return
    checking = true
    scope.launch {
        try {
            val index = h.builder.index
            val cards = com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown.cards(reply).mapNotNull { name ->
                (index.byName(name) ?: (com.kaiharimoto.mastertool.core.ai.CardWords.resolve(name, index) as? com.kaiharimoto.mastertool.core.ai.Resolved.Found)?.card)
                    ?.let { it.name to it.description }
            }
            val look = setOf("card_info", "rulings", "calculate", "hand_odds", "search_cards")
            val runner = com.kaiharimoto.mastertool.core.ai.ToolRunner { call ->
                if (call.name.removePrefix("mcp__neue__") !in look) {
                    Part.ToolResult(call.id, call.name, "A checker can only look up cards, rulings and numbers.", isError = true)
                } else {
                    host.run(call)
                }
            }
            var said = ""
            var spent = com.kaiharimoto.mastertool.core.ai.Usage()
            AgentLoop(model, runner, maxSteps = 8, now = System::currentTimeMillis, budget = budgetFor(connection))
                .run(
                    TurnRequest(
                        com.kaiharimoto.mastertool.core.ai.check.FactCheck.CHECKER,
                        listOf(ChatTurn.user(com.kaiharimoto.mastertool.core.ai.check.FactCheck.brief(reply, cards))),
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
            val claims = com.kaiharimoto.mastertool.core.ai.check.FactCheck.parse(said)
            if (claims.isEmpty()) return@launch
            val check = com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check(at, claims)
            val now = session?.takeIf { it.id == s.id } ?: return@launch
            val next = now.copy(checks = now.checks + check, usage = now.usage + spent)
            commit(next)
            // Wrong: the model says so itself, briefly, in a reply of its own (the answer above is never rewritten).
            if (check.wrong.isNotEmpty() && !running) {
                correcting = true
                val ask = ChatTurn(Role.USER, listOf(Part.Context(com.kaiharimoto.mastertool.core.ai.check.FactCheck.correction(check))), System.currentTimeMillis())
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
    val system = session?.system ?: systemPrompt(connection)
    val ask = ChatTurn.user(
        "You are a helper the assistant sent to do one job and report back. Nothing you say reaches the person directly; " +
            "your final message is your report, so make it complete and plain: the facts, the numbers, the card names, the ids. " +
            "You can only look, never change anything.\n\nThe job: " + task.trim(),
    )
    var report = ""
    val runner = com.kaiharimoto.mastertool.core.ai.ToolRunner { call ->
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
                else -> Unit
            }
        }
    report.ifBlank { error("The helper came back without a report.") }
}
