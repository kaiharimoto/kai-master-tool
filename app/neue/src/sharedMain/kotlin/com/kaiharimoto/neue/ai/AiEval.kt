package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.eval.EvalItem
import com.kaiharimoto.mastertool.core.ai.eval.EvalLog
import com.kaiharimoto.mastertool.core.ai.eval.EvalRun
import com.kaiharimoto.mastertool.core.ai.eval.EvalSet
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.Graded
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.ai.eval.Grading
import com.kaiharimoto.mastertool.core.ai.eval.ItemOutcome
import com.kaiharimoto.mastertool.core.ai.rules.RulesPrimer
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import kotlinx.coroutines.launch

// Trust (1.0.99, Phase A, docs/phases/A.md), on [AiState]: a set of questions with known answers run against a
// connection, each answer graded by code, every run kept per connection (`ai/evals/<connection>.json`).

/** The tools a question is answered with: the ones that look up, as a person's question is answered. */
internal val EVAL_TOOLS = setOf("card_info", "search_cards", "rulings", "calculate", "hand_odds", "resolve_cards")

/** Roughly what one question costs, in tokens read and written, for the estimate shown before a run. */
internal const val EVAL_TOKENS_EACH = 6_000

/** A connection's runs, oldest first. */
fun AiState.evalRuns(connection: String): List<EvalRun> = EvalLog.read(files.read(EvalLog.path(connection)))

/**
 * Runs [set] against [connection], [tries] times an item, in the background: progress in [evalProgress], the run kept
 * in the connection's log when done (or stopped — what was graded is kept, marked as stopped early).
 */
fun AiState.startEval(set: EvalSet, connection: AiConnection, tries: Int = 1) {
    if (evalJob?.isActive == true) return
    val model = runCatching { backendFor(connection) }.getOrNull() ?: run {
        evalNote = "Could not connect to ${connection.label.ifBlank { connection.provider }}."
        return
    }
    if (model.runsOwnLoop) {
        evalNote = "Trust runs on API connections: a plan's command-line app runs its own loop and every tool, so its answers cannot be held to the look-up tools."
        return
    }
    evalNote = null
    evalProgress = Triple(set.id, 0, set.items.size)
    evalJob = scope.launch {
        val started = System.currentTimeMillis()
        val outcomes = mutableListOf<ItemOutcome>()
        var usage = Usage()
        var stopped = true
        try {
            for ((i, item) in set.items.withIndex()) {
                var passes = 0
                var first: Graded? = null
                var firstAnswer = ""
                val t0 = System.currentTimeMillis()
                repeat(tries) { k ->
                    val (graded, answer, spent) = if (set.checker) checkPlanted(model, connection, item) else answer(model, connection, item)
                    usage += spent
                    if (graded.pass) passes++
                    if (k == 0) {
                        first = graded
                        firstAnswer = answer
                    }
                }
                val g = first ?: Graded(false, "not run")
                outcomes += ItemOutcome(item.id, passes, tries, g.pass, g.read.take(200), firstAnswer.takeLast(400), System.currentTimeMillis() - t0)
                evalProgress = Triple(set.id, i + 1, set.items.size)
            }
            stopped = false
        } finally {
            if (outcomes.isNotEmpty()) {
                val run = EvalRun(set.id, connection.id, connection.model, started, tries, outcomes.toList(), usage.input + usage.cacheRead, usage.output, stoppedEarly = stopped)
                files.write(EvalLog.path(connection.id), EvalLog.write(evalRuns(connection.id) + run))
                evalVersion++
            }
            evalProgress = null
            evalJob = null
        }
    }
}

fun AiState.stopEval() {
    evalJob?.cancel()
}

/** One question asked as a person's would be — the rules primer in, the look-up tools offered — and its answer graded. */
private suspend fun AiState.answer(model: ModelBackend, connection: AiConnection, item: EvalItem): Triple<Graded, String, Usage> {
    val runner = ToolRunner { call ->
        if (call.name.removePrefix("mcp__neue__") in EVAL_TOOLS) host.run(call)
        else Part.ToolResult(call.id, call.name, "Only look-ups are used while being tested.", isError = true)
    }
    var said = ""
    var spent = Usage()
    AgentLoop(model, runner, maxSteps = 8, now = System::currentTimeMillis, budget = budgetFor(connection))
        .run(
            TurnRequest(
                EvalSets.INSTRUCTIONS + "\n\n" + RulesPrimer.TEXT,
                listOf(ChatTurn.user(item.prompt)),
                tools.filter { it.name in EVAL_TOOLS },
                connection.model,
                prefs.effort,
            ),
        )
        .collect { e ->
            when (e) {
                is AgentEvent.Appended -> if (e.turn.role == Role.ASSISTANT && e.turn.text.isNotBlank()) said = e.turn.text
                is AgentEvent.Done -> spent = e.usage
                is AgentEvent.Failed -> said = "(failed: ${e.message})"
                else -> Unit
            }
        }
    return Triple(Grading.grade(item.grader, said), said, spent)
}

/** A planted answer given to the checker the chat uses, and its claims graded: a mistake caught, a clean answer left alone. */
private suspend fun AiState.checkPlanted(model: ModelBackend, connection: AiConnection, item: EvalItem): Triple<Graded, String, Usage> {
    val (claims, spent) = runChecker(model, connection, item.prompt)
    val graded = Grading.planted(item.grader as Grader.Planted, claims)
    return Triple(graded, claims.joinToString("\n") { "${it.verdict}: ${it.claim}" }, spent)
}
