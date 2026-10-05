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
import com.kaiharimoto.mastertool.core.ai.eval.PuzzleBaselines
import com.kaiharimoto.mastertool.core.ai.eval.PuzzleTable
import com.kaiharimoto.mastertool.core.ai.eval.Puzzles
import com.kaiharimoto.mastertool.core.ai.rules.RulesPrimer
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import kotlinx.coroutines.launch

// Trust (1.0.99, Phase A, docs/phases/A.md), on [AiState]: a set of questions with known answers run against a
// connection, each answer graded by code, every run kept per connection (`ai/evals/<connection>.json`).

/**
 * The tools a question is answered with: the ones that look up, as a person's question is answered. `banlist` (1.1.2,
 * the Card truth set) reads any past list and only reads; `validate_deck` is left out — it checks the person's own
 * decks, never a list written in the question.
 */
internal val EVAL_TOOLS = setOf("card_info", "search_cards", "rulings", "calculate", "hand_odds", "resolve_cards", "banlist")

/** Roughly what one question costs, in tokens read and written, for the estimate shown before a run. */
internal const val EVAL_TOKENS_EACH = 6_000

/** A puzzle is a short game: the table read and played over several rounds (Phase C stage 3). */
internal const val PUZZLE_TOKENS_EACH = 30_000

internal fun tokensEach(set: EvalSet): Int = if (set.id == EvalSets.PUZZLES) PUZZLE_TOKENS_EACH else EVAL_TOKENS_EACH

/**
 * The puzzle set's bounds, worked out by playing it (Phase C stage 3): doing nothing, a battle-only greedy player and the
 * recorded solutions, each through the same table and referee a model plays.
 */
internal val PUZZLE_BOUNDS: Triple<Int, Int, Int> by lazy { PuzzleBaselines.bounds() }

/** How many puzzles the set holds: the scale's far end. */
internal val PUZZLE_COUNT: Int get() = Puzzles.all.size

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
                    val (graded, answer, spent) = when {
                        set.checker -> checkPlanted(model, connection, item)
                        item.grader is Grader.Puzzle -> playPuzzle(model, connection, item)
                        else -> answer(model, connection, item)
                    }
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

/**
 * A duel puzzle played (Phase C stage 3): Ai gets a table of its own with the puzzle's position and the three duel tools
 * it plays kai with — `duel_state`, `duel_moves`, `duel_act`, answered by the puzzle's [PuzzleTable] and never by the
 * duel in play — acts within the budget, and the goal is checked on the table after its moves.
 */
private suspend fun AiState.playPuzzle(model: ModelBackend, connection: AiConnection, item: EvalItem): Triple<Graded, String, Usage> {
    val puzzle = Puzzles.byId((item.grader as Grader.Puzzle).id) ?: return Triple(Graded(false, "no puzzle ${item.id}"), "", Usage())
    val table = PuzzleTable(puzzle)
    val runner = ToolRunner { call ->
        val (text, error) = table.tool(call.name, call.input)
        Part.ToolResult(call.id, call.name, text, isError = error)
    }
    var said = ""
    var spent = Usage()
    AgentLoop(model, runner, maxSteps = PUZZLE_STEPS + puzzle.budget, now = System::currentTimeMillis, budget = budgetFor(connection))
        .run(
            TurnRequest(
                PuzzleTable.RULES + "\n\n" + RulesPrimer.TEXT,
                listOf(ChatTurn.user(item.prompt)),
                tools.filter { it.name in PuzzleTable.TOOLS },
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
    val moves = table.lines.joinToString("; ").ifBlank { "no moves" }
    return Triple(table.grade(), "Played: $moves\n$said", spent)
}

/** Rounds a puzzle may take beyond its budget of moves: reading the table and the menu. */
private const val PUZZLE_STEPS = 8

/** A planted answer given to the checker the chat uses, and its claims graded: a mistake caught, a clean answer left alone. */
private suspend fun AiState.checkPlanted(model: ModelBackend, connection: AiConnection, item: EvalItem): Triple<Graded, String, Usage> {
    val (claims, spent) = runChecker(model, connection, item.prompt)
    val graded = Grading.planted(item.grader as Grader.Planted, claims)
    return Triple(graded, claims.joinToString("\n") { "${it.verdict}: ${it.claim}" }, spent)
}
