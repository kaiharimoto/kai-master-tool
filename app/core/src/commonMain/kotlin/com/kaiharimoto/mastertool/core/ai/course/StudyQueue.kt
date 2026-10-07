package com.kaiharimoto.mastertool.core.ai.course

/**
 * What a course study does next, from the course as it is saved and nothing else — so a study stopped anywhere (a crash,
 * a restart, the app closed, the person's Pause) goes on from the same place. The study's runner does a step, saves the
 * course, and asks again.
 */
object StudyQueue {
    /** How often a chapter is tried before it is passed over. */
    const val ATTEMPTS = 3

    sealed interface Step {
        /** Read the guide's contents: its chapters, in order. */
        data object List : Step

        /** Load chapter [n] and keep its text. */
        data class Read(val n: Int) : Step

        /** Take notes from chapter [n]'s text. */
        data class Notes(val n: Int) : Step

        /** Look chapter [n]'s page over for DuelingBook replays (a chapter read before the study looked for them). */
        data class Scan(val n: Int) : Step

        /** Read replay [n] from its page and keep it in words. */
        data class Replay(val n: Int) : Step

        /** Take notes from replay [n]. */
        data class ReplayNotes(val n: Int) : Step

        /** Distil every chapter's notes, and every replay's, into the deck's guide. */
        data object Distil : Step

        /** The chapters were distilled before the replays were studied: distil the replays' notes on their own. */
        data object ReplayDistil : Step

        /** Nothing left: the review waits for the person. */
        data object Done : Step

        /** Nothing to do until the person does something: [why] says what. */
        data class Waiting(val why: String) : Step
    }

    /** The next step for [course]; [canWatch] is whether this build can take notes from a video chapter. */
    fun next(course: Course, canWatch: Boolean = false): Step {
        when (course.state) {
            Course.State.PAUSED -> return Step.Waiting("Paused.")
            Course.State.BLOCKED -> return Step.Waiting(course.note.ifBlank { "Stopped." })
            Course.State.DONE -> return Step.Done
            Course.State.STUDYING -> Unit
        }
        if (course.cap > 0 && course.spent >= course.cap) return Step.Waiting(CAP_REACHED)
        if (!course.listed) return Step.List
        // In the guide's order: a chapter's notes are taken before the next is loaded, so the notes read like the guide.
        for (c in course.chapters.sortedBy { it.n }) {
            when (c.state) {
                Chapter.State.PENDING -> return Step.Read(c.n)
                Chapter.State.READ -> return Step.Notes(c.n)
                Chapter.State.FAILED -> if (!c.gaveUp) return Step.Read(c.n)
                Chapter.State.WAITING -> if (canWatch) return Step.Read(c.n)
                Chapter.State.NOTED -> Unit
            }
        }
        // The replays the chapters link to: every read chapter looked over for them, then each read and noted in turn.
        for (c in course.chapters.sortedBy { it.n }) {
            if (!c.scanned && c.state in SCANNABLE) return Step.Scan(c.n)
        }
        for (r in course.replays.sortedBy { it.n }) {
            when (r.state) {
                Chapter.State.PENDING, Chapter.State.WAITING -> return Step.Replay(r.n)
                Chapter.State.READ -> return Step.ReplayNotes(r.n)
                Chapter.State.FAILED -> if (!r.gaveUp) return Step.Replay(r.n)
                Chapter.State.NOTED -> Unit
            }
        }
        val replaysNoted = course.replays.any { it.state == Chapter.State.NOTED }
        if (course.chapters.none { it.state == Chapter.State.NOTED } && !replaysNoted) return Step.Waiting(NOTHING_READ)
        return when {
            !course.distilled -> Step.Distil
            replaysNoted && !course.replaysDistilled -> Step.ReplayDistil
            else -> Step.Done
        }
    }

    /** The chapters whose pages are looked over for replays: those the study could open. */
    private val SCANNABLE = setOf(Chapter.State.READ, Chapter.State.NOTED, Chapter.State.WAITING)

    /** Whether [course], finished or stopped, has study left in it that this build can do (its replays, from 1.1.41). */
    fun more(course: Course, canWatch: Boolean = false): Boolean =
        course.listed && next(course.copy(state = Course.State.STUDYING), canWatch).let { it != Step.Done && it !is Step.Waiting }

    const val CAP_REACHED = "It has spent what you allowed it. Raise the limit to go on."
    const val NOTHING_READ = "No chapter could be read: nothing to learn from yet."

    /** [course] after chapter [n] failed with [why]: tried again later, or passed over after [ATTEMPTS]. */
    fun failed(course: Course, n: Int, why: String): Course {
        val c = course.chapter(n) ?: return course
        return course.with(c.copy(state = Chapter.State.FAILED, error = why.take(300), attempts = c.attempts + 1))
    }

    /** What the person reads about a course: where it is and what it is doing. */
    fun line(course: Course, step: Step = next(course)): String {
        val of = course.chapters.size
        val replays = course.replays.size
        return when (step) {
            Step.List -> "Reading the contents"
            is Step.Read -> "Reading chapter ${step.n} of $of"
            is Step.Notes -> "Taking notes on chapter ${step.n} of $of"
            is Step.Scan -> "Looking for replays in chapter ${step.n} of $of"
            is Step.Replay -> "Reading replay ${step.n} of $replays"
            is Step.ReplayNotes -> "Taking notes on replay ${step.n} of $replays"
            Step.Distil -> "Writing what it learned into the guide"
            Step.ReplayDistil -> "Writing what the replays taught into the guide"
            Step.Done -> "Studied ${course.done} of $of chapters" + if (replays > 0) " and ${course.replaysDone} of $replays replays" else ""
            is Step.Waiting -> step.why
        }
    }

    /** [course] after replay [n] failed with [why]: tried again later, or passed over after [ATTEMPTS]. */
    fun replayFailed(course: Course, n: Int, why: String, giveUp: Boolean = false): Course {
        val r = course.replay(n) ?: return course
        return course.with(r.copy(state = Chapter.State.FAILED, error = why.take(300), attempts = if (giveUp) ATTEMPTS else r.attempts + 1))
    }
}
