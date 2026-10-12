package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.eval.EvalItem
import com.kaiharimoto.mastertool.core.ai.eval.EvalLog
import com.kaiharimoto.mastertool.core.ai.eval.EvalSet
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.ai.eval.Optimizations
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.ai.evalRuns
import com.kaiharimoto.neue.ai.startEval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every set of Test scores through the real runner on a scripted model, in CI on every push (Phase I, I.6; F1: "CI runs
 * the code-graded sets against a recorded model"): an oracle that answers each item's key passes every item, a guesser fails
 * them, and an optimization is solved on its own table by measuring before and after and proposing with those numbers — so
 * a grader, the runner or a set that drifts from its key is a red build, not a surprise at the next live run.
 */
class EvalOracleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun answered(text: String) = BackendEvent.Finished(StopReason.END, ChatTurn.assistant(text), usage = Usage(input = 100, output = 10))

    /** The item [asked] is: the longest prompt it holds, since a set's prompts may share their openings. */
    private fun itemOf(sets: List<EvalSet>, asked: String): EvalItem =
        sets.flatMap { it.items }.filter { asked.contains(it.prompt) }.maxBy { it.prompt.length }

    /** The key, said as an answer is asked to be said. */
    private fun key(item: EvalItem): String = when (val g = item.grader) {
        is Grader.Percent -> "ANSWER: " + String.format(Locale.ROOT, "%.${g.decimals}f", g.expected * 100) + "%"
        is Grader.YesNo -> "ANSWER: " + if (g.expected) "yes" else "no"
        is Grader.Number -> "ANSWER: ${g.expected}"
        is Grader.Decklist -> "ANSWER:\n" + g.expected.entries.joinToString("\n") { "${it.value} ${it.key}" }
        else -> error("not an answer set: ${item.id}")
    }

    /** Every question that asked about a picture, and whether the picture came with it. */
    private val pictured = mutableListOf<Pair<String, Boolean>>()

    private fun backend(answer: (String) -> String) = object : ModelBackend {
        override val runsOwnLoop = false
        override fun turn(request: TurnRequest) = flow {
            val asked = request.history.first()
            if ("Picture " in asked.text) pictured += asked.text.substringAfter("(").substringBefore(")") to asked.images.any { !it.data.isNullOrEmpty() }
            emit(answered(answer(asked.text)))
        }
    }

    @Test
    fun everyAnswerSetPassesOnAnOracleAndFailsOnAGuesser() = runBlocking {
        val h = TestHolders.make(scope)
        val conn = AiConnection("c-oracle", "anthropic", model = "scripted")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(conn), active = conn.id)) }
        val sets = listOf(EvalSets.handOdds(), EvalSets.rulings(), EvalSets.decklists(), EvalSets.decklistPictures(), EvalSets.cardTruth())
        h.ai.files.delete(EvalLog.path(conn.id))
        h.ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to backend { asked -> key(itemOf(sets, asked)) }
        sets.forEach { set ->
            h.ai.startEval(set, conn)
            withTimeout(60_000) { while (h.ai.evalJob != null) delay(20) }
            val run = EvalLog.latest(h.ai.evalRuns(conn.id))[set.id]!!
            assertEquals(set.items.size, run.items.size, set.id)
            val missed = run.items.filter { !it.firstPass }
            assertTrue(missed.isEmpty(), "${set.id}: the key graded wrong — ${missed.take(3).map { "${it.id}: ${it.read}" }}")
        }
        assertEquals(20, pictured.size, "every decklist picture asked")
        assertTrue(pictured.all { it.second }, "and each sent with its picture: ${pictured.filter { !it.second }}")
        // A guesser: the same words every time, which no set's key is throughout.
        h.ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to backend { "I think so.\nANSWER: 3 Pot of Greed" }
        sets.forEach { set ->
            h.ai.startEval(set, conn)
            withTimeout(60_000) { while (h.ai.evalJob != null) delay(20) }
            val run = EvalLog.latest(h.ai.evalRuns(conn.id))[set.id]!!
            assertTrue(run.items.count { it.firstPass } < set.items.size / 2, "${set.id}: a guesser passed ${run.items.count { it.firstPass }}")
        }
    }

    @Test
    fun anOptimizationIsSolvedOnItsOwnTableByMeasuringFirst() = runBlocking {
        val h = TestHolders.make(scope)
        val conn = AiConnection("c-optimize", "anthropic", model = "scripted")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(conn), active = conn.id)) }
        val set = Optimizations.set()
        // The scripted model does what OptimizeBaselines.solved does, through the table's own tools: the starters' odds as the
        // deck stands, the odds with the planted card swapped back, then the proposal carrying those two numbers.
        val scripted = object : ModelBackend {
            override val runsOwnLoop = false
            override fun turn(request: TurnRequest) = flow {
                val item = itemOf(listOf(set), request.history.first().text)
                val case = Optimizations.byId((item.grader as Grader.Optimize).id)!!
                val results = request.history.flatMap { t -> t.parts.filterIsInstance<Part.ToolResult>() }
                val out = case.out.first { o -> case.main.contains(o) }
                val into = case.into.first()
                val starters = case.groups.first().first
                fun last(s: String) = Regex("""([0-9]+(?:\.[0-9]+)?)%""").findAll(s).last().groupValues[1]
                fun call(name: String, input: JsonObject) =
                    BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(Part.ToolUse("t${results.size}", name, input))), usage = Usage(input = 100, output = 10))
                when (results.size) {
                    0 -> emit(call("hand_odds", obj("group" to starters, "turn" to "first")))
                    1 -> emit(call("hand_odds", obj("condition" to "\"$starters\">=1 | \"$into\">=1", "turn" to "first", "without" to listOf(out), "with" to listOf(into))))
                    2 -> {
                        val b = last(results[0].content)
                        val a = last(results[1].content)
                        emit(
                            call(
                                "deck_propose",
                                obj(
                                    "title" to "$into over $out",
                                    "ops" to listOf(obj("op" to "remove", "card" to out, "count" to 1), obj("op" to "add", "card" to into, "count" to 1)),
                                    "why" to "Opening a starter going first rises from $b% to $a%.",
                                    "expect" to listOf(obj("metric" to "Opens going first", "before" to b.toDouble(), "after" to a.toDouble())),
                                ),
                            ),
                        )
                    }
                    else -> emit(answered("Proposed."))
                }
            }
        }
        h.ai.files.delete(EvalLog.path(conn.id))
        h.ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to scripted
        h.ai.startEval(set, conn)
        withTimeout(120_000) { while (h.ai.evalJob != null) delay(20) }
        val run = EvalLog.latest(h.ai.evalRuns(conn.id))[set.id]!!
        val missed = run.items.filter { !it.firstPass }
        assertTrue(missed.isEmpty(), "measured and proposed, every case passes: ${missed.map { "${it.id}: ${it.read}" }}")
    }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

    private fun json(v: Any): kotlinx.serialization.json.JsonElement = when (v) {
        is JsonObject -> v
        is List<*> -> JsonArray(v.map { json(it!!) })
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}
