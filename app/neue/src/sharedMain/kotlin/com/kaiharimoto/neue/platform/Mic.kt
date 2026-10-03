package com.kaiharimoto.neue.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one microphone (1.0.87). [Voice] listens once at a time — one line open, one stop flag — so Ai's voice
 * (its mic button and talk mode) and the duel's push-to-talk take turns here: whoever listens next stops the
 * one listening and is told nothing it heard; the one stopped hears [listen]'s `onLost`, so talk mode ends
 * rather than listening again over the duel.
 */
object Mic {
    private class Hold(val owner: String, val job: Job, val onLost: () -> Unit)

    @Volatile
    private var hold: Hold? = null

    /** Who is listening now ("ai", "duel"), or null. */
    val owner: String? get() = hold?.takeIf { it.job.isActive }?.owner

    /**
     * [block] run as [owner]'s listening, in [scope]. Whoever listened before is stopped first — told by its
     * `onLost` when it is someone else — and given a moment to close the microphone (a transcription already
     * running is not waited for: the microphone is shut by then).
     */
    fun listen(scope: CoroutineScope, owner: String, onLost: () -> Unit = {}, block: suspend () -> Unit): Job {
        val before = hold?.takeIf { it.job.isActive }
        if (before != null && before.owner != owner) before.onLost()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (before != null) {
                Voice.stopListening()
                withTimeoutOrNull(WAIT_MS) { before.job.cancelAndJoin() } ?: before.job.cancel()
            }
            block()
        }
        val mine = Hold(owner, job, onLost)
        hold = mine
        job.invokeOnCompletion { if (hold === mine) hold = null }
        job.start()
        return job
    }

    /** How long a listening stopped is waited for to let go of the microphone. */
    private const val WAIT_MS = 400L
}
