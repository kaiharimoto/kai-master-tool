package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Compaction
import com.kaiharimoto.mastertool.core.ai.ContextBreakdown
import com.kaiharimoto.mastertool.core.ai.ContextWindows
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.providers.ConnectKind
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import kotlinx.coroutines.launch

// A long conversation (1.0.47, 1.0.56), on [AiState]: the model's window, how full it is, summarising the start to
// fit, and the Context panel's Compact now, Clear old tool results and Start fresh.

/**
 * How much a connection's model can read, in tokens (1.0.56: read off the model's name,
 * `ContextWindows`, unless the person said): past most of it the oldest turns are summarised.
 */
fun AiState.windowOf(connection: AiConnection): Int = connection.window?.takeIf { it > 0 } ?: ContextWindows.of(
    connection.provider,
    connection.model,
    local = Providers.byId(connection.provider)?.kind == ConnectKind.LOCAL,
)

internal fun AiState.budgetFor(connection: AiConnection): Int = windowOf(connection)

/** Whether the connection in use keeps its own history — a plan's command-line app — so the app cannot compact it. */
val AiState.ownsContext: Boolean
    get() = prefs.connection?.let { Providers.byId(it.provider)?.wire.let { w -> w == Wire.CLAUDE_CLI || w == Wire.CODEX_CLI } } == true

/** The model's window for the connection in use, in tokens. */
val AiState.window: Int get() = prefs.connection?.let(::windowOf) ?: 0

/** What fills the conversation now, in tokens: the provider's count when it gave one, else an estimate. */
val AiState.contextUsed: Long
    get() = session?.let { ContextBreakdown.total(it, tools) } ?: 0

/** Whether [contextUsed] is the provider's own count rather than an estimate. */
val AiState.contextMeasured: Boolean get() = (session?.context ?: 0) > 0

/**
 * [s] with its oldest turns folded into a summary the model writes, once they no longer
 * fit — or now, when [force]d, keeping only the last few exchanges; [focus] says what the
 * summary must keep. The summary is kept with the conversation, so it is written once.
 */
internal suspend fun AiState.summarizedIfLong(
    s: AiSession,
    model: ModelBackend,
    connection: AiConnection,
    budget: Int,
    force: Boolean = false,
    focus: String? = null,
): AiSession {
    // What the provider measured last round, plus the newest turns since, where there is a measure (1.0.98, the red team):
    // the estimate alone counted too much and summarised — lossily — far too early.
    val used = if (s.context > 0) s.context.toInt() + Compaction.estimate("", s.sent.takeLast(2)) else Compaction.estimate(s.system, s.sent, tools)
    if (!force && used <= budget * Compaction.SUMMARIZE_AT) return s
    val keep = if (force) minOf((budget * 0.3).toInt(), used / 4) else (budget * 0.3).toInt()
    val cut = Compaction.cutAt(s.turns, keep, from = s.summarized) ?: return s
    if (cut <= s.summarized) return s
    val summary = summarise(s, model, connection, cut, focus) ?: return s
    // What stood in the summarised turns' context and must not leave with them (1.0.98, the red team): the deck's guide
    // and the scope's notes, read afresh, so a long run never writes them again from nothing.
    val next = s.copy(summary = summary + standingContext(s), summarized = cut, context = 0)
    commit(next)
    notice = null
    return next
}

/** The conversation's turns before [cut], with what was summarised before, in the model's own summary. */
private suspend fun AiState.summarise(s: AiSession, model: ModelBackend, connection: AiConnection, cut: Int, focus: String?): String? {
    status = "Summarising the start of this conversation to fit"
    val keep = focus?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\nThe person asked that the summary keep: $it" }.orEmpty()
    // A piece at a time, the summary carried along (1.0.98): never a transcript with its middle cut away.
    val pieces = Compaction.chunks(s.turns.subList(s.summarized.coerceAtMost(cut), cut))
    var carried = s.summary.trim()
    for ((i, piece) in pieces.withIndex()) {
        if (pieces.size > 1) status = "Summarising the start of this conversation to fit (${i + 1} of ${pieces.size})"
        val earlier = buildString {
            if (carried.isNotBlank()) appendLine("Summary so far: $carried\n")
            append(Compaction.transcript(piece))
        }
        val ask = ChatTurn.user(Compaction.SUMMARY_ASK + keep + "\n\n" + earlier)
        var summary = ""
        runCatching {
            model.turn(TurnRequest(s.system, listOf(ask), emptyList(), connection.model, "low")).collect { e ->
                if (e is BackendEvent.Finished) {
                    summary = e.turn?.text?.ifBlank { null } ?: e.text
                    e.usage?.let { u -> session?.let { commit(it.copy(usage = it.usage + u)) } }
                }
            }
        }
        // A piece that could not be summarised ends it: no summary rather than one with a hole in it.
        if (summary.isBlank()) {
            status = null
            return null
        }
        carried = summary.trim()
    }
    status = null
    return carried.takeIf { it.isNotEmpty() }
}

/**
 * The guide and the scope's notes as they stand (1.0.98): put after a summary, because the turns that carried them
 * are summarised away and the conversation's `guideShown`/`scopeShown` say they were already given.
 */
private fun AiState.standingContext(s: AiSession): String {
    val deckId = s.deckId?.takeIf { s.mode in AiSession.DECK_MODES } ?: s.guideShown
    val deckName = s.deckName ?: h.builder.deckName.takeIf { deckId == h.builder.deckId } ?: "the deck"
    val guide = deckId?.let { guideForPrompt(it) }?.takeIf { it.isNotBlank() }
    val scope = host.scope()
    val notes = scope?.let { host.notes(it) }?.takeIf { it.isNotBlank() }
    if (guide == null && notes == null) return ""
    return buildString {
        append("\n\n(Still in force after the summary.)")
        guide?.let { append("\n\nYour guide to how “$deckName” plays (memory scope guide):\n").append(it) }
        notes?.let { append("\n\nNotes for ${scope.name}:\n").append(it) }
    }
}

/**
 * The start of the conversation summarised now (1.0.56, the Context panel's Compact now, or
 * Ai's own `compact`): all but the last few exchanges, keeping [focus]. Waits for an answer
 * under way to finish first.
 */
fun AiState.compactNow(focus: String? = null) {
    if (running) {
        pendingCompact = focus.orEmpty()
        return
    }
    val s = session ?: return
    val connection = prefs.connection ?: return
    if (ownsContext) {
        notice = "${Providers.byId(connection.provider)?.label ?: "The command-line app"} keeps its own history and compacts it itself."
        return
    }
    val model = runCatching { backendFor(connection) }.getOrElse {
        problem = (it.message ?: "Could not connect.") to true
        return
    }
    running = true
    job = scope.launch {
        try {
            val next = summarizedIfLong(s, model, connection, budgetFor(connection), force = true, focus = focus)
            notice = if (next.summarized > s.summarized) "The start of the conversation was summarised: ${next.summarized} messages in a few paragraphs." else "There was not enough to summarise yet."
        } finally {
            running = false
            status = null
            job = null
        }
    }
}

/** Every old tool result sent cut short from now on (1.0.56): the fastest room there is, and it costs nothing. */
fun AiState.clearToolResults() {
    val s = session ?: return
    commit(s.copy(clearedBefore = s.turns.size, context = 0))
    notice = "Old tool results are sent cut short from now on; the conversation keeps them whole."
}

/**
 * A new conversation that carries this one's summary (1.0.56): the room of a fresh start
 * without losing the thread.
 */
fun AiState.startFresh() {
    val s = session ?: return
    val connection = prefs.connection ?: return
    if (s.turns.isEmpty() || running) return
    if (ownsContext) {
        newChat()
        return
    }
    val model = runCatching { backendFor(connection) }.getOrElse {
        problem = (it.message ?: "Could not connect.") to true
        return
    }
    running = true
    job = scope.launch {
        try {
            val summary = summarise(s, model, connection, s.turns.size, null)
            running = false
            newChat()
            if (summary != null) session?.let { commit(it.copy(summary = summary, carriedFrom = s.id)) }
            notice = if (summary != null) "A fresh conversation, carrying a summary of the last one." else "A fresh conversation; the summary could not be written."
        } finally {
            running = false
            status = null
            job = null
        }
    }
}

/** What `context_status` tells Ai, and the Context panel shows in words. */
fun AiState.contextReport(): String {
    val s = session ?: return "No conversation yet."
    val used = contextUsed
    val w = window
    val words = ContextWindows::words
    return buildString {
        if (ownsContext) {
            appendLine("This connection's app keeps its own history and compacts it itself; the numbers below are the app's estimate.")
        }
        appendLine(
            "Context: ${words(used)} of ${words(w.toLong())} tokens" + (if (w > 0) " (${used * 100 / w}%)" else "") +
                if (contextMeasured) ", as the provider counted last round." else ", estimated.",
        )
        ContextBreakdown.of(s, tools, s.context).forEach { appendLine("- ${it.label}: ${words(it.tokens)}") }
        if (s.summarized > 0) appendLine("The first ${s.summarized} messages are summarised (${s.summary.length} characters); recall finds their words.")
        if (s.carriedFrom != null) appendLine("This conversation carries on from an earlier one, whose summary it holds.")
        if (s.clearedBefore > 0) appendLine("Tool results before message ${s.clearedBefore} are sent cut short.")
        appendLine("Spent in this conversation: ${words(s.usage.read)} read, ${words(s.usage.output)} written" + (s.usage.costUsd?.let { ", about $" + "%.2f".format(it) }.orEmpty()) + ".")
    }.trimEnd()
}
