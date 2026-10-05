package com.kaiharimoto.mastertool.core.present.record

import com.kaiharimoto.mastertool.core.present.play.Cursor

/**
 * How a take becomes a video (1.1.13), frame by frame: what the stage shows at each frame's moment, which camera
 * frame stands in the zone, and how much sound goes with it. The renderer draws what this says with the slide
 * painter, so the plan is the whole of the arithmetic and is tested here.
 */
object RenderPlan {
    /** One frame of the video: its number, its moment in take ms, and the presenter's state then. */
    data class Frame(val n: Int, val atMs: Long, val state: TakeState) {
        /** How long since the last move, in ms: how far the transition and the step's builds have run. */
        val sinceMove: Long get() = atMs - state.since

        /** How long since the whole deck was shown or put away, in ms. */
        val sinceOverview: Long get() = atMs - state.overviewSince
    }

    /** The video's frame count for [take]: at least one. */
    fun frames(take: Take, fps: Int = take.fps): Int = TakeTimeline.frames(take.durationMs, fps)

    /**
     * Every frame of [take] at [fps], in order. Replaying from the start for each frame would be quadratic in a long
     * take, so the events are walked once: each frame replays only the events up to its moment from the last
     * frame's state.
     */
    fun walk(take: Take, fps: Int = take.fps, start: Cursor = Cursor(0)): Sequence<Frame> = sequence {
        val events = take.events.sortedBy { it.at }
        val total = frames(take, fps)
        val replay = Replay(start)
        var next = 0
        for (n in 0 until total) {
            val t = TakeTimeline.msOf(n, fps)
            while (next < events.size && events[next].at <= t) replay.apply(events[next++])
            yield(Frame(n, t, replay.state()))
        }
    }

    /** One frame alone, replayed from the start: for a preview or a test. */
    fun frame(take: Take, n: Int, fps: Int = take.fps): Frame {
        val t = TakeTimeline.msOf(n, fps)
        return Frame(n, t, TakeTimeline.stateAt(take.events.sortedBy { it.at }, t))
    }

    /**
     * Which recorded camera frame stands in frame [atMs]: the newest one at or before it. [stamps] are the camera
     * frames' moments in take ms, rising; null before the first.
     */
    fun cameraFrame(stamps: List<Long>, atMs: Long): Int? {
        var lo = 0
        var hi = stamps.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (stamps[mid] <= atMs) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * Whether a camera read in order should move on to its next frame for video frame [atMs]: the next frame's moment
     * [nextMs] has come. A renderer reading the camera's file once, front to back, asks this until it says no.
     */
    fun advanceCamera(nextMs: Long?, atMs: Long): Boolean = nextMs != null && nextMs <= atMs

    /**
     * The samples of sound at [rate] that belong with frames up to and including [n] at [fps]: the running total, so
     * sound and picture never drift apart however long the take.
     */
    fun samplesThrough(n: Int, fps: Int, rate: Int): Long = (n + 1).toLong() * rate / fps

    /** The video's length in ms for [frames] at [fps]. */
    fun lengthMs(frames: Int, fps: Int): Long = frames * 1000L / fps

    /** [TakeTimeline.stateAt]'s replay, kept between frames. */
    private class Replay(start: Cursor) {
        private var cursor = start
        private var from: Cursor? = null
        private var since = 0L
        private var back = false
        private var overview = false
        private var overviewSince = 0L
        private var blank: String? = null
        private var laser: Pair<Float, Float>? = null
        private val ink = ArrayList<MutableList<Pair<Float, Float>>>()

        fun apply(e: TakeEvent) {
            when (e.kind) {
                TakeEvent.GO -> {
                    val next = Cursor(e.slide, e.step)
                    if (next != cursor) {
                        back = next.slide < cursor.slide || next.slide == cursor.slide && next.step < cursor.step
                        if (next.slide != cursor.slide) ink.clear()
                        from = cursor
                        cursor = next
                        since = e.at
                    }
                }
                TakeEvent.OVERVIEW -> if (!overview) { overview = true; overviewSince = e.at }
                TakeEvent.OVERVIEW_OFF -> if (overview) { overview = false; overviewSince = e.at }
                TakeEvent.BLANK -> blank = e.text.ifBlank { null }
                TakeEvent.LASER -> laser = e.x to e.y
                TakeEvent.LASER_OFF -> laser = null
                TakeEvent.INK_START -> ink += mutableListOf(e.x to e.y)
                TakeEvent.INK -> ink.lastOrNull()?.add(e.x to e.y) ?: ink.add(mutableListOf(e.x to e.y))
                TakeEvent.INK_CLEAR -> ink.clear()
            }
        }

        fun state() = TakeState(cursor, from, since, back, overview, overviewSince, blank, laser, ink.map { it.toList() })
    }
}
