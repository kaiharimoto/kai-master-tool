package com.kaiharimoto.mastertool.core.present.record

/**
 * A take's own clock (1.1.13): milliseconds of recording, which a pause stops — so a take has no gaps, and every
 * event, camera frame and sample of sound is stamped on the same line. [now] is the machine's clock in ms (a
 * test's hand-turned one).
 */
class TakeClock(private val now: () -> Long) {
    private var startedAt = -1L
    private var pausedAt = -1L
    private var pausedFor = 0L

    /** Whether it has been started. */
    val started: Boolean get() = startedAt >= 0

    /** Whether it is stopped for a pause. */
    val paused: Boolean get() = pausedAt >= 0

    /** Running: started and not paused. */
    val running: Boolean get() = started && !paused

    fun start() {
        if (started) return
        startedAt = now()
    }

    fun pause() {
        if (!started || paused) return
        pausedAt = now()
    }

    fun resume() {
        if (!paused) return
        pausedFor += now() - pausedAt
        pausedAt = -1L
    }

    /** Milliseconds of take so far: 0 before it starts, held still while paused. */
    val elapsed: Long
        get() {
            if (!started) return 0L
            val end = if (paused) pausedAt else now()
            return (end - startedAt - pausedFor).coerceAtLeast(0L)
        }
}
