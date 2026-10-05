package com.kaiharimoto.mastertool.core.world.apps

import kotlinx.serialization.json.JsonElement

/** What became of an event offered to an app's queue (§8.5). */
enum class Offered {
    /** Waiting its turn. */
    QUEUED,

    /** A `change` of the same widget was waiting: this one took its place. */
    REPLACED,

    /** The queue was full: dropped, with a line in the window. */
    DROPPED,
}

/**
 * An app's waiting events (§8.5): one call at a time per app, up to [AppLimits.QUEUE] waiting; a `change` of a widget
 * whose change is the last thing waiting replaces it (only the last value matters; behind a press it keeps its place); more are dropped and counted. Each event is
 * stamped with the app's next `seq` as it is offered — `Math.random` is seeded from it, so a run of events replays exactly.
 * Not thread-safe: the host owns it on one thread.
 */
class AppEvents(private var seq: Long = 0L) {
    private val waiting = ArrayDeque<UiEvent>()

    /** Events dropped since the window last said so. */
    var dropped: Int = 0
        private set

    val size: Int get() = waiting.size

    /** The last `seq` given, for the state's own record. */
    val lastSeq: Long get() = seq

    fun offer(id: String, type: String, value: JsonElement): Offered {
        if (type == UiEvent.CHANGE) {
            // Only a change still last in the queue is replaced: one with a press after it must reach the app before that
            // press (pick, then Log), or the press would read the newer value.
            val i = waiting.indexOfLast { it.type == UiEvent.CHANGE && it.id == id }.takeIf { it == waiting.lastIndex } ?: -1
            if (i >= 0) {
                waiting[i] = UiEvent(id, type, value, waiting[i].seq)
                return Offered.REPLACED
            }
        }
        if (waiting.size >= AppLimits.QUEUE) {
            dropped++
            return Offered.DROPPED
        }
        waiting.addLast(UiEvent(id, type, value, ++seq))
        return Offered.QUEUED
    }

    /** The next event to run, or null. */
    fun take(): UiEvent? = waiting.removeFirstOrNull()

    /** The window said how many were dropped. */
    fun saidDropped() {
        dropped = 0
    }

    fun clear() = waiting.clear()
}

/**
 * A `live` widget's changes (a slider moving, a field typed in, §8.3): at most [AppLimits.LIVE_PER_SECOND] a second; the
 * last is always delivered — the window sends it on release whether or not it was admitted.
 */
class LiveThrottle {
    private val last = HashMap<String, Long>()

    /** Whether a change of [id] at [now] goes now. */
    fun admit(id: String, now: Long): Boolean {
        val gap = 1_000L / AppLimits.LIVE_PER_SECOND
        val prev = last[id]
        if (prev != null && now - prev < gap) return false
        last[id] = now
        return true
    }

    /** The widget was let go: its next change goes at once. */
    fun release(id: String) {
        last.remove(id)
    }
}
