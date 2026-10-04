package com.kaiharimoto.mastertool.core.motion

import kotlin.math.ceil

/**
 * The frame meter's arithmetic (1.0.92): the last [size] frame intervals, and what they say — frames a second, the
 * typical frame, the slow one in twenty (p95) and the worst. Fed the frame clock's nanoseconds; a gap over [GAP_NS]
 * (the window hidden, the app asleep) starts the count again rather than reading as one dreadful frame.
 */
class FrameStats(private val size: Int = 240) {
    private val intervals = LongArray(size)
    private var count = 0
    private var next = 0
    private var last = 0L

    fun feed(frameNanos: Long) {
        val previous = last
        last = frameNanos
        if (previous == 0L) return
        val dt = frameNanos - previous
        if (dt <= 0L) return
        if (dt > GAP_NS) {
            count = 0
            next = 0
            return
        }
        intervals[next] = dt
        next = (next + 1) % size
        if (count < size) count++
    }

    data class Reading(val fps: Float, val medianMs: Float, val p95Ms: Float, val worstMs: Float, val frames: Int)

    fun reading(): Reading? {
        if (count == 0) return null
        val sorted = intervals.copyOf(count).also { it.sort() }
        val total = sorted.sum()
        fun ms(ns: Long) = ns / 1_000_000f
        return Reading(
            fps = count * 1e9f / total,
            medianMs = ms(sorted[(count - 1) / 2]),
            p95Ms = ms(sorted[(ceil(count * 0.95).toInt() - 1).coerceIn(0, count - 1)]),
            worstMs = ms(sorted[count - 1]),
            frames = count,
        )
    }

    companion object {
        const val GAP_NS = 1_000_000_000L
    }
}
