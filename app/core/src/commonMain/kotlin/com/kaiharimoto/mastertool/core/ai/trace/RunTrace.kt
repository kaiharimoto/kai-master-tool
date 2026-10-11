package com.kaiharimoto.mastertool.core.ai.trace

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.ToolTiers
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One run of Ai, traced (Phase H, H.8; `docs/ROADMAP.md` F5): from a message sent to the answer's end — which connection and
 * model, how many rounds and what each read and wrote, every tool it called (its name, how long, whether it failed or the
 * person refused it, how much it answered), the retries, how it ended, and what it cost when that is known.
 *
 * **Never a word of the conversation**: no message, no tool's input or answer, no card or deck. A trace is written to be
 * sent (the diagnostics file), and kept on this device only for [TraceBook.KEEP_DAYS] days.
 */
@Serializable
data class RunTrace(
    val version: Int = VERSION,
    val id: String = "",
    /** The conversation's id: which runs were one conversation's, and nothing of what it said. */
    val session: String = "",
    val mode: String = "",
    val provider: String = "",
    val model: String = "",
    val started: Long = 0L,
    val ended: Long = 0L,
    val rounds: List<Round> = emptyList(),
    val tools: List<ToolCall> = emptyList(),
    val retries: Int = 0,
    /** How it ended: [DONE], [STOPPED] (the person's Stop), [FAILED], or [OUT_OF_STEPS]. */
    val end: String = "",
    /** The provider's own words when it failed, cut short; never the conversation's. */
    val failure: String = "",
    val usage: Usage = Usage(),
    /** In US dollars: the provider's own figure, else the list price's ([Prices]), else null. */
    val costUsd: Double? = null,
) {
    /** One round: what the model read and wrote. */
    @Serializable
    data class Round(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0)

    /** One tool call: its [name], [ms] from call to answer, whether it [failed] or the person [refused] it, and the answer's length. */
    @Serializable
    data class ToolCall(val name: String = "", val ms: Long = 0, val failed: Boolean = false, val refused: Boolean = false, val chars: Int = 0)

    val ms: Long get() = (ended - started).coerceAtLeast(0)
    val refusals: Int get() = tools.count { it.refused }
    val toolErrors: Int get() = tools.count { it.failed && !it.refused }

    companion object {
        const val VERSION = 1
        const val DONE = "done"
        const val STOPPED = "stopped"
        const val FAILED = "failed"
        const val OUT_OF_STEPS = "out of steps"

        /** The longest failure kept: a provider's message, not a page. */
        const val FAILURE_MAX = 300
    }
}

/**
 * A run traced as it goes (H.8): fed the loop's events in order, and finished once. Pure, so the order of events alone
 * decides the trace, and a test can feed it as the loop does.
 */
class RunTracer(
    private val id: String,
    private val session: String,
    private val mode: String,
    private val provider: String,
    private val model: String,
    private val started: Long,
) {
    private val rounds = ArrayList<RunTrace.Round>()
    private val tools = ArrayList<RunTrace.ToolCall>()
    private val open = LinkedHashMap<String, Pair<String, Long>>()
    private var retries = 0
    private var usage = Usage()
    private var failure = ""
    private var outOfSteps = false

    fun round(u: Usage) {
        rounds += RunTrace.Round(u.input, u.output, u.cacheRead, u.cacheWrite)
        usage += u
    }

    fun retry() {
        retries++
    }

    fun toolStarted(callId: String, name: String, at: Long) {
        open[callId] = name to at
    }

    /** A tool's answer: [error] as the loop marked it, [content] read only to tell a refusal and to measure, never kept. */
    fun toolDone(callId: String, name: String, error: Boolean, content: String, at: Long) {
        val (n, from) = open.remove(callId) ?: (name to at)
        val refused = error && ToolTiers.refused(content)
        tools += RunTrace.ToolCall(n.ifEmpty { name }, (at - from).coerceAtLeast(0), failed = error, refused = refused, chars = content.length)
    }

    /** The loop's own total: kept when it says more than the rounds did (a CLI reports its cost only at the end). */
    fun done(total: Usage, outOfSteps: Boolean) {
        if (total.costUsd != null) usage = usage.copy(costUsd = total.costUsd)
        if (total.input + total.output > usage.input + usage.output) usage = total.copy(costUsd = total.costUsd ?: usage.costUsd)
        this.outOfSteps = outOfSteps
    }

    fun failed(message: String) {
        failure = message.take(RunTrace.FAILURE_MAX)
    }

    /** The trace, ended at [at]; [stopped] when the person's Stop ended it. */
    fun finish(at: Long, stopped: Boolean): RunTrace {
        val end = when {
            failure.isNotEmpty() -> RunTrace.FAILED
            stopped -> RunTrace.STOPPED
            outOfSteps -> RunTrace.OUT_OF_STEPS
            else -> RunTrace.DONE
        }
        // A call still open when the run ended (a Stop between call and answer) is kept as unanswered.
        open.forEach { (_, p) -> tools += RunTrace.ToolCall(p.first, (at - p.second).coerceAtLeast(0), failed = true) }
        open.clear()
        val price = Prices.of(provider, model)
        val cost = usage.costUsd ?: price?.let { Prices.cost(it, usage) }
        return RunTrace(
            id = id, session = session, mode = mode, provider = provider, model = model, started = started, ended = at,
            rounds = rounds.toList(), tools = tools.toList(), retries = retries, end = end, failure = failure, usage = usage, costUsd = cost,
        )
    }
}

/**
 * Where traces are kept (H.8): `<data>/ai/traces/<yyyy-mm-dd>.jsonl`, one run a line, a file a day, the days past
 * [KEEP_DAYS] deleted. This device's own: never synced or backed up (`InboundPath.aiPrivate`).
 */
object TraceBook {
    /** The folder under `<data>/ai/`. */
    const val FOLDER = "traces"

    const val KEEP_DAYS = 14

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The file a day's runs go in: [day] as `yyyy-mm-dd`. */
    fun file(day: String): String = "$day.jsonl"

    /** One line for [t]. */
    fun line(t: RunTrace): String = JSON.encodeToString(RunTrace.serializer(), t)

    /** Every run a day's text holds, newest last; a line this build cannot read is passed over. */
    fun read(text: String): List<RunTrace> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
        runCatching { JSON.decodeFromString(RunTrace.serializer(), l) }.getOrNull()
    }.toList()

    /** Of [files] (day file names), the ones older than [KEEP_DAYS] days before [today] (`yyyy-mm-dd`): to delete. */
    fun stale(files: List<String>, today: String): List<String> {
        val cut = dayNumber(today) - KEEP_DAYS
        return files.filter { f -> f.endsWith(".jsonl") && dayNumber(f.removeSuffix(".jsonl")).let { it in 1 until cut } }
    }

    /** Days since 1970 of `yyyy-mm-dd`, or 0 when it is not one. */
    internal fun dayNumber(day: String): Long {
        val p = day.split('-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3 || p[1] !in 1..12 || p[2] !in 1..31) return 0
        // Days from civil (Howard Hinnant's algorithm): exact for the Gregorian calendar.
        val y = if (p[1] <= 2) p[0] - 1 else p[0]
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (p[1] + 9) % 12
        val doy = (153 * mp + 2) / 5 + p[2] - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}

/** Traces in words (H.8): what the Context panel lists and the diagnostics file carries. */
object TraceWords {
    /** One run in a line: "6 rounds · 11 tools (1 refused) · 42 s · 18.2k read, 1.4k written · ≈ $0.31". */
    fun run(t: RunTrace): String = listOfNotNull(
        "${t.rounds.size} round${if (t.rounds.size == 1) "" else "s"}",
        "${t.tools.size} tool${if (t.tools.size == 1) "" else "s"}" + listOfNotNull(
            t.toolErrors.takeIf { it > 0 }?.let { "$it failed" },
            t.refusals.takeIf { it > 0 }?.let { "$it refused" },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" },
        seconds(t.ms),
        "${tokens(t.usage.read)} read, ${tokens(t.usage.output)} written",
        t.retries.takeIf { it > 0 }?.let { "$it retr${if (it == 1) "y" else "ies"}" },
        t.costUsd?.let { "≈ ${Prices.dollars(it)}" },
        t.end.takeIf { it != RunTrace.DONE },
    ).joinToString(" · ")

    /** Several runs together: "12 runs · 48 rounds · 96 tools · 3 refused · ≈ $1.24". */
    fun total(ts: List<RunTrace>): String {
        if (ts.isEmpty()) return "No runs yet."
        val cost = ts.mapNotNull { it.costUsd }
        return listOfNotNull(
            "${ts.size} run${if (ts.size == 1) "" else "s"}",
            "${ts.sumOf { it.rounds.size }} rounds",
            "${ts.sumOf { it.tools.size }} tools",
            ts.sumOf { it.refusals }.takeIf { it > 0 }?.let { "$it refused" },
            ts.sumOf { it.retries }.takeIf { it > 0 }?.let { "$it retries" },
            ts.count { it.end == RunTrace.FAILED }.takeIf { it > 0 }?.let { "$it failed" },
            cost.takeIf { it.isNotEmpty() }?.let { "≈ ${Prices.dollars(it.sum())}" + if (cost.size < ts.size) " (of the runs priced)" else "" },
        ).joinToString(" · ")
    }

    /** The slowest tools of [ts], by name, as "card_info 12 × 1.4 s": what a slow run spent its time on. */
    fun slowest(ts: List<RunTrace>, n: Int = 3): List<String> =
        ts.flatMap { it.tools }.groupBy { it.name }.map { (name, calls) -> Triple(name, calls.size, calls.sumOf { it.ms }) }
            .sortedByDescending { it.third }.take(n).map { (name, count, ms) -> "$name $count × ${seconds(ms / count.coerceAtLeast(1))}" }

    fun seconds(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> "${(ms + 50) / 100 / 10.0} s".replace(".0 s", " s")
        else -> "${ms / 60_000} min ${(ms % 60_000) / 1_000} s"
    }

    fun tokens(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> "${(n + 50) / 100 / 10.0}k".replace(".0k", "k")
        else -> "${(n + 50_000) / 100_000 / 10.0}M".replace(".0M", "M")
    }
}

/** The loop's [e] at [at], fed to the trace: the events that say what a run did and cost; the words are passed over. */
fun RunTracer.feed(e: AgentEvent, at: Long) {
    when (e) {
        is AgentEvent.Round -> round(e.usage)
        AgentEvent.Retrying -> retry()
        is AgentEvent.ToolRunning -> toolStarted(e.call.id, e.call.name, at)
        is AgentEvent.ToolDone -> toolDone(e.result.id, e.result.name, e.result.isError, e.result.content, at)
        is AgentEvent.Done -> done(e.usage, e.outOfSteps)
        is AgentEvent.Failed -> failed(e.message)
        else -> Unit
    }
}

/**
 * A run's events, traced as they pass (H.8): each fed to [tracer], and the trace handed to [keep] when the run ends — a
 * Stop (the flow cancelled) as stopped, a throw as failed. The events pass through unchanged.
 */
fun Flow<AgentEvent>.traced(tracer: RunTracer, now: () -> Long, keep: (RunTrace) -> Unit): Flow<AgentEvent> =
    onEach { tracer.feed(it, now()) }.onCompletion { cause ->
        if (cause != null && cause !is CancellationException) tracer.failed(cause.message ?: cause::class.simpleName.orEmpty())
        keep(tracer.finish(now(), stopped = cause is CancellationException))
    }
