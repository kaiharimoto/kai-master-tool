package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ShootoutTools
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.rules.RulesPrimer
import com.kaiharimoto.mastertool.core.ai.skills.ShootoutSkills
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriors
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeBrief
import com.kaiharimoto.mastertool.core.shootout.teach.PriorsBrief
import com.kaiharimoto.mastertool.core.shootout.teach.Rubric
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.shootout.Shootouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

// Shootout's Ai (Phase S stage 3, S.md §6½), on [AiState]: a hand judged as Ai's own judge — a request of its own, handed
// the hand, the cards' text, the model's prediction, the rubric and the person's nearest examples, never the person's answer
// to it — and the interview that writes the matchup's rubric, reviewed on Finish like Fine Tuning.

/** Ai's answer to one hand, or why there is none. */
sealed interface Judged {
    class Verdict(val verdict: AiVerdict, val answer: Answer?, val prefersLeft: Boolean?) : Judged
    class Failed(val why: String) : Judged
}

/** Why Ai cannot judge Shootout hands now, in words; null when it can. */
fun AiState.judgeProblem(): String? = shootoutProblem("judges hands")

/** Why Ai cannot read a deck for Shootout now (S4.3), in words; null when it can. */
fun AiState.readProblem(): String? = shootoutProblem("reads the deck")

/**
 * Why Shootout cannot ask Ai now: off, or no connection. Any connection will do (S4.4): an API one answers in a request of
 * its own, and a coding plan's command-line app through an MCP server of its own that offers the one tool and nothing else.
 */
private fun AiState.shootoutProblem(what: String): String? {
    if (!enabled) return "Ai is off."
    val connection = prefs.connection ?: return "Connect Ai first: Ai $what on a connection of yours."
    if (runsAsCli(connection) && !AiDesk.canRunCli) return "A plan's command-line app runs on the desktop app only."
    return null
}

/**
 * One of Shootout's requests to Ai (a hand judged, the deck read): [system] as its instructions, [message] alone in the
 * conversation, the tools named [offered] and nothing else, each call answered by [answer] — so nothing from any
 * conversation reaches it. On a coding plan's command-line app (S4.4) the same tools are served by an MCP server of the
 * request's own, stopped when it ends. Ends as soon as [done]. The failure in words, or null.
 */
private suspend fun AiState.shootoutRequest(
    kind: String,
    system: String,
    message: String,
    offered: Set<String>,
    maxSteps: Int,
    effort: (AiConnection) -> String,
    done: () -> Boolean,
    answer: suspend (Part.ToolUse) -> Part.ToolResult,
): String? {
    val connection = prefs.connection ?: return "No connection."
    val specs = tools.filter { it.name in offered }
    val served = if (runsAsCli(connection)) {
        ownMcp(specs) { call -> withContext(Dispatchers.Main) { answer(call) } } ?: return "The app could not open the tools to the command-line app."
    } else {
        null
    }
    val model = try {
        if (served != null) newBackend(connection, served) else backendFor(connection)
    } catch (t: Throwable) {
        served?.stop()
        return t.message ?: "Could not connect."
    }
    var failed: String? = null
    try {
        AgentLoop(model, ToolRunner { answer(it) }, maxSteps = maxSteps, now = System::currentTimeMillis, budget = budgetFor(connection))
            .run(TurnRequest(system, listOf(ChatTurn.user(message)), specs, connection.model, effort(connection)))
            // The answer is all that is wanted: the loop stops as soon as it lands, without another round.
            .transformWhile { e -> emit(e); !(e is AgentEvent.ToolDone && done()) }
            .tracedFor(this, kind, "shootout", connection).collect { e -> if (e is AgentEvent.Failed) failed = e.message }
    } finally {
        if (served != null) {
            closeBackend(model)
            served.stop()
        }
    }
    return failed
}

/**
 * One hand judged (S.md §6½): a request of its own — the judge skill and the rules primer as its instructions, [brief] as
 * the message, `shootout_judge` the only tool — so nothing from any conversation, and never the person's answer, reaches
 * it. Ends as soon as the answer lands.
 */
internal suspend fun AiState.judgeHand(brief: JudgeBrief): Judged {
    judgeProblem()?.let { return Judged.Failed(it) }
    val connection = prefs.connection ?: return Judged.Failed("No connection.")
    var got: Judged.Verdict? = null
    val failed = shootoutRequest(
        "shootout-judge", ShootoutSkills.JUDGE + "\n\n" + RulesPrimer.TEXT, brief.text, ShootoutTools.JUDGING, maxSteps = 3,
        effort = { c ->
            val provider = Providers.byId(c.provider)
            prefs.effort.ifBlank { if (provider?.efforts?.contains("low") == true) "low" else provider?.defaultEffort.orEmpty() }
        },
        done = { got != null },
    ) { call ->
        if (call.name.removePrefix("mcp__neue__") != "shootout_judge") {
            return@shootoutRequest Part.ToolResult(call.id, call.name, "Only shootout_judge answers here.", isError = true)
        }
        val i = call.input
        val sure = ShootoutTools.sure(i)
        val why = ToolArgs.string(i, "why")
        val prefer = ToolArgs.string(i, "prefer")?.lowercase()
        val answer = JudgeBrief.parseAnswer(ToolArgs.string(i, "answer"))
        val problem = when {
            brief.compare && prefer !in setOf("left", "right") -> "prefer must be left or right."
            !brief.compare && answer == null -> "answer must be 1 to 5, or its words."
            sure == null -> "sure must be a number from 0 to 1."
            else -> null
        }
        if (problem != null) return@shootoutRequest Part.ToolResult(call.id, call.name, problem, isError = true)
        val left = if (brief.compare) prefer == "left" else null
        got = Judged.Verdict(brief.verdict(answer, left, sure, why, connection.model, ToolArgs.string(i, "question")), answer, left)
        Part.ToolResult(call.id, call.name, "Kept.", isError = false, summary = "Judged a hand")
    }
    return got ?: Judged.Failed(failed ?: "Ai answered without judging the hand.")
}

/** Ai's reading of a deck (S4.3), or why there is none. */
sealed interface DeckReading {
    class Read(val priors: ShootoutPriors) : DeckReading
    class Failed(val why: String) : DeckReading
}

/**
 * The deck read before any hand (S4.3, S.md §6 1): a request of its own like a judged hand — the priors skill and the rules
 * primer as its instructions, the deck as the message ([PriorsBrief.text]), `shootout_priors` the only tool, read against the
 * bench ([PriorsBrief.read]) and handed back to Ai with what was wrong until it reads. Ends as soon as the reading lands.
 */
internal suspend fun AiState.readDeck(bench: Bench, deckName: String, name: (Int) -> String, cardText: (Int) -> String?): DeckReading {
    readProblem()?.let { return DeckReading.Failed(it) }
    val connection = prefs.connection ?: return DeckReading.Failed("No connection.")
    var got: ShootoutPriors? = null
    val failed = shootoutRequest(
        "shootout-priors", ShootoutSkills.PRIORS + "\n\n" + RulesPrimer.TEXT, PriorsBrief.text(bench, deckName, name, cardText),
        ShootoutTools.READING, maxSteps = 4,
        effort = { c -> prefs.effort.ifBlank { Providers.byId(c.provider)?.defaultEffort.orEmpty() } },
        done = { got != null },
    ) { call ->
        if (call.name.removePrefix("mcp__neue__") !in ShootoutTools.READING) {
            return@shootoutRequest Part.ToolResult(call.id, call.name, "Only shootout_priors answers here.", isError = true)
        }
        when (val read = PriorsBrief.read(call.input, bench, name, connection.model, System.currentTimeMillis())) {
            is PriorsBrief.Read.Problem -> Part.ToolResult(call.id, call.name, read.message, isError = true)
            is PriorsBrief.Read.Done -> {
                got = read.priors
                Part.ToolResult(call.id, call.name, "Kept.", isError = false, summary = "Read the deck")
            }
        }
    }
    return got?.let { DeckReading.Read(it) } ?: DeckReading.Failed(failed ?: "Ai answered without reading the deck.")
}

/**
 * Shootout's interview (S.md §6½ "Interview"): a conversation of its own about the matchup on the page, Ai asking how the
 * person judges and writing the rubric as it goes. Like Fine Tuning, Finish shows every change to keep or undo.
 */
fun AiState.startRubricInterview() {
    if (AiState.PHASE < 3) return
    val target = h.shootout.rubricTarget() ?: run {
        h.neue.note = Note("Choose a saved deck on Shootout first: the rubric is kept with its trials")
        return
    }
    val connection = prefs.connection ?: run {
        openWizard()
        return
    }
    stop()
    setOpen(true)
    wizardOpen = false
    historyOpen = false
    demoOpen = false
    settleTuning()
    h.shootout.interviewing = target
    tuneBefore = reviewSnapshot()
    session = begin(connection, AiSession.MODE_RUBRIC).copy(deckId = target.deck, deckName = target.deckName)
    send(
        "Let's write how I judge “${target.deckName}” ${target.opponentName?.let { "against “$it”" } ?: "on its own"} in Shootout. " +
            "Ask me one question at a time, and write the rubric as we go.",
    )
}

/**
 * Shootout's tools (Phase S stage 3): the matchup on the page and how far Ai is trusted on it, and its rubric. The judge's
 * tool answers only inside a judging request ([judgeHand]).
 */
internal class AiShootoutTools(private val h: NeueHolders, private val ai: AiState) {
    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "shootout_state" -> h.shootout.describeForAi()?.let { ok(it, "Looked at the Shootout") }
            ?: fail("Shootout has no deck chosen: open the page (navigate shootout) on a saved deck.")
        "shootout_judge" -> fail("shootout_judge is answered only when Shootout asks you to judge a hand.")
        "shootout_priors" -> fail("shootout_priors is answered only when Shootout asks you to read the deck (Ask ${ai.name} to read the deck, on its page).")
        "shootout_rubric" -> rubric(i)
        "shootout_results" -> h.shootout.resultsForAi()?.let { ok(it, "Read the Shootout's results") }
            ?: fail("Shootout has no deck chosen: open the page (navigate shootout) on a saved deck.")
        "shootout_whatif" -> {
            val (done, words) = h.shootout.whatIfForAi(
                ToolArgs.string(i, "from")?.takeIf { it.isNotBlank() },
                ToolArgs.string(i, "to")?.takeIf { it.isNotBlank() },
            )
            if (done) ok(words, "Asked Shootout what a change does") else fail(words)
        }
        else -> null
    }

    private fun rubric(i: JsonObject): MetaAnswer {
        val target = h.shootout.rubricTarget() ?: return fail("Shootout has no saved deck chosen, so there is no rubric to write.")
        val path = Shootouts.reviewPath(target)
        val doc = Rubric.read(ai.files.read(path), target.deckName, target.opponentName)
        val action = ToolArgs.string(i, "action")
        val text = ToolArgs.string(i, "text")
        val old = ToolArgs.string(i, "old_text")
        if (action == "read") return ok(doc.render().ifBlank { "(empty)" }, "Read the Shootout rubric")
        // Numbers carry their proof (the evidence ledger): one nobody computed or said is refused.
        if (text != null && action in setOf("add", "replace")) {
            val v = Evidence.judge(text, Evidence.sources(ai.session?.turns.orEmpty()), "", System.currentTimeMillis())
            if (v is Evidence.Verdict.Refused) return fail(v.message)
        }
        val write = when (action) {
            "add" -> Rubric.add(doc, text ?: return fail("add needs text."))
            "replace" -> Rubric.replace(doc, old ?: return fail("replace needs old_text."), text ?: return fail("replace needs text."))
            "remove" -> Rubric.remove(doc, old ?: text ?: return fail("remove needs old_text."))
            else -> return fail("Actions: read, add, replace, remove.")
        }
        return when (write) {
            is MemoryWrite.Done -> {
                ai.files.write(path, write.doc.render())
                h.shootout.rubricChanged()
                ok(write.message, when (action) {
                    "add" -> "Wrote to the rubric: ${text.orEmpty().take(100)}"
                    "replace" -> "Sharpened a rule of the rubric"
                    else -> "Took a rule out of the rubric"
                })
            }
            is MemoryWrite.Refused -> fail(write.message)
        }
    }
}
