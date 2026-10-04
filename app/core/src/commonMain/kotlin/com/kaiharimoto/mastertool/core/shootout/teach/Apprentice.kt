package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial

/**
 * Apprentice mode's one rule for speaking up (S.md §6½): Ai predicts every hand silently, and only after the person has
 * answered — never before, so it cannot anchor them — may it ask its one question, and only where it disagreed or was
 * unsure, and at most once every [EVERY] trials. Its question was written with its answer, before it knew theirs.
 */
object Apprentice {
    /** At most one question in this many trials. */
    const val EVERY = 4

    /** Below this certainty Ai is unsure enough to ask even when it agreed. */
    const val UNSURE = 0.6

    /**
     * Whether Ai's [verdict] on the hand the person answered as [person] earns its question, [sinceLast] trials after the
     * last one asked (null when none has been).
     */
    fun asks(verdict: AiVerdict, person: StoredTrial, sinceLast: Int?): Boolean {
        if (verdict.question.isNullOrBlank()) return false
        if (sinceLast != null && sinceLast < EVERY) return false
        val agrees = Agreement.agrees(person, verdict.answer, verdict.prefer) ?: return false
        return !agrees || (verdict.sure ?: 0.0) < UNSURE
    }
}
