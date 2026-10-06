package com.kaiharimoto.mastertool.core.ai.course

/**
 * How fast the study may load pages: as a person reads, never faster (Metafy's Code of Conduct forbids automated access
 * that "sends more request messages … than a human can produce in the same period of time"). A page at most every
 * [MIN_GAP_MS] plus a jitter, and [DAILY] pages a day.
 */
object HumanPace {
    const val MIN_GAP_MS = 12_000L
    const val JITTER_MS = 18_000L
    const val DAILY = 150
    private const val DAY_MS = 86_400_000L

    /** How long to wait before the next page, given the last load at [last] (0: none yet) and [roll] in 0..1. */
    fun wait(last: Long, now: Long, roll: Double): Long {
        if (last <= 0) return 0
        val gap = MIN_GAP_MS + (JITTER_MS * roll.coerceIn(0.0, 1.0)).toLong()
        return (last + gap - now).coerceAtLeast(0)
    }

    /** [course] with one more page loaded at [now], the count starting again on a new day. */
    fun loaded(course: Course, now: Long): Course {
        val day = now / DAY_MS
        return if (course.loadsDay == day) course.copy(loads = course.loads + 1) else course.copy(loadsDay = day, loads = 1)
    }

    /** Whether today's pages are spent: the study rests until tomorrow. */
    fun spent(course: Course, now: Long): Boolean = course.loadsDay == now / DAY_MS && course.loads >= DAILY

    /** How long until the next day begins, when [spent]. */
    fun untilTomorrow(now: Long): Long = DAY_MS - now % DAY_MS
}
