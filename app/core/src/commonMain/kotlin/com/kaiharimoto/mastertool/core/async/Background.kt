package com.kaiharimoto.mastertool.core.async

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Work that outlives a screen (Phase H, H.1; `docs/ROADMAP.md`): every app-lifetime scope is made here, so a job that throws
 * is a line in the log and a quiet word, never the app's end. A coroutine launched in a scope with no handler hands what
 * it threw to the thread, and on Android that closes the app — the banlist refresh did, at start, until 1.1.82.
 *
 * The scope is a supervisor (one job failing never cancels its siblings) with a name and a handler. What failed goes to
 * [report] — the app points it at its diagnostic log when it starts — and to [failed], which the shell shows.
 */
object Background {
    /** One job that failed: where (its scope's name), what it threw, in words, and when. */
    data class Failure(val where: String, val what: String, val at: Long)

    private val last = MutableStateFlow<Failure?>(null)

    /** The newest failure, for the shell's note; null until one happens. */
    val failed: StateFlow<Failure?> = last.asStateFlow()

    /** Where a failure is written down: the app sets it as it starts (its diagnostic log). Called on the failing thread. */
    var report: (where: String, error: Throwable) -> Unit = { _, _ -> }

    /** A scope for work named [where] that lives as long as the app: a supervisor, on [dispatcher], with the handler. */
    fun scope(where: String, dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(where) + handler(where))

    /** The handler alone, for a scope made elsewhere (a composable's): what [scope] puts in every one. */
    fun handler(where: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> failure(where, e) }

    /**
     * Work named [where] failed with [e]: written down ([report]) and said ([failed]), and nothing more — what every scope's
     * handler does, and what a thread made by `Background.thread` (the JVM's) does with what it throws. A cancellation is no
     * failure.
     */
    fun failure(where: String, e: Throwable) {
        if (e is CancellationException) return
        runCatching { report(where, e) }
        last.value = Failure(where, words(e), clock())
    }

    /** What [e] says, in one line: its kind and message, and its cause's when it has one. */
    fun words(e: Throwable): String {
        val own = "${e::class.simpleName ?: "Error"}${e.message?.let { ": $it" }.orEmpty()}"
        val cause = e.cause?.takeIf { it !== e }?.let { c -> " (${c::class.simpleName}${c.message?.let { ": $it" }.orEmpty()})" }.orEmpty()
        return (own + cause).replace('\n', ' ').take(300)
    }

    /** The time a failure is stamped with; the tests set it. */
    var clock: () -> Long = { 0L }
}
