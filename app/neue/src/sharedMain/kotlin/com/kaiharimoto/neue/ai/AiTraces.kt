package com.kaiharimoto.neue.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.trace.RunTrace
import com.kaiharimoto.mastertool.core.ai.trace.RunTracer
import com.kaiharimoto.mastertool.core.ai.trace.TraceBook
import com.kaiharimoto.mastertool.core.ai.trace.traced
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Every run of Ai, traced (Phase H, H.8; `docs/ROADMAP.md` F5): kept in memory for the Context panel and appended to
 * `<data>/ai/traces/<day>.jsonl`, the days past two weeks deleted. This device's own, never synced or backed up, and never
 * a word of the conversation ([RunTrace]): what the diagnostics file carries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiTraces internal constructor(private val dir: () -> File) {
    // One writer at a time, so two runs ending together never interleave their lines.
    private val scope = Background.scope("Ai's traces", Dispatchers.IO.limitedParallelism(1))
    private var pruned = ""

    /** This launch's runs, newest last, at most [MAX]. */
    var recent by mutableStateOf<List<RunTrace>>(emptyList())
        private set

    fun keep(t: RunTrace) {
        recent = (recent + t).takeLast(MAX)
        scope.launch { write(t) }
    }

    /** This launch's runs of conversation [session]. */
    fun of(session: String): List<RunTrace> = recent.filter { it.session == session }

    /** The runs of the last [days] days on this device, oldest first: what the diagnostics file carries. */
    suspend fun read(days: Int = TraceBook.KEEP_DAYS): List<RunTrace> = withContext(Dispatchers.IO) {
        dir().listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }.takeLast(days)
            .flatMap { f -> runCatching { TraceBook.read(f.readText()) }.getOrDefault(emptyList()) }
    }

    /** The day files, for the diagnostics file to carry whole. */
    fun files(): List<File> = dir().listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }

    private fun write(t: RunTrace) {
        val d = dir()
        d.mkdirs()
        val day = LocalDate.now().toString()
        File(d, TraceBook.file(day)).appendText(TraceBook.line(t) + "\n")
        if (pruned != day) {
            pruned = day
            TraceBook.stale(d.list().orEmpty().toList(), day).forEach { File(d, it).delete() }
        }
    }

    companion object {
        const val MAX = 200
    }
}

/** [this] run's events traced into [ai]'s traces: the form a loop's `.run(…)` takes before its `.collect`. */
internal fun Flow<AgentEvent>.tracedFor(ai: AiState, mode: String, session: String, connection: AiConnection): Flow<AgentEvent> =
    ai.traced(this, mode, session, connection)

/**
 * [events] of a run in [mode] on [connection] (conversation [session]), traced into [AiState.traces] as they pass: every
 * loop Ai runs goes through this, the panel's and the helpers' alike.
 */
internal fun AiState.traced(events: Flow<AgentEvent>, mode: String, session: String, connection: AiConnection): Flow<AgentEvent> {
    val now = System.currentTimeMillis()
    val tracer = RunTracer("$session-$now", session, mode, connection.provider, connection.model, now)
    return events.traced(tracer, System::currentTimeMillis) { traces.keep(it) }
}
