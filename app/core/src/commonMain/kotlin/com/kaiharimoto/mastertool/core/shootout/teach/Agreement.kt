package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.math.Normal
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import kotlin.math.sqrt

/**
 * The ways Ai is taught and the person's answers are given (S.md §6½ "The four ways to teach"), as stored on a trial's
 * [StoredTrial.mode]. A person's answer with none is an ordinary one, blind.
 */
object TeachModes {
    /** The person judges a fixed set chosen to cover the kinds of hand; blind. */
    const val CALIBRATION = "calibration"

    /** Ai answers the calibration set afterwards, blind, as its first exam. */
    const val EXAM = "exam"

    /** The person judges as normal and Ai predicts silently, asking at most one question now and then. */
    const val APPRENTICE = "apprentice"

    /** Ai judges first with a reason; the person accepts or corrects, having seen it. */
    const val SUPERVISED = "supervised"

    /** Ai alone, on a kind it has earned. */
    const val SOLO = "solo"

    /** One of Ai's solo hands sent back to the person blind, and Ai's answer to it. */
    const val AUDIT = "audit"
}

/** A range of a share, lower to upper, both 0 to 1. */
data class Range(val lower: Double, val upper: Double)

/** Agreement between two judges' answers to one hand, and the ranges counted from it. */
object Agreement {

    /**
     * Whether two five-point answers agree: within one band of each other. The scale is the person's own reading of a
     * hand's chances, and a reading a band away is the same call made a little warmer or cooler; two bands apart (a lean
     * win against a lean loss, a clear win against a coin flip) is a different call.
     */
    fun agrees(a: Answer, b: Answer): Boolean = kotlin.math.abs(a.ordinal - b.ordinal) <= 1

    /** Whether two answers kept as words agree: five-point answers by [agrees], a comparison by the same hand. */
    fun agrees(person: StoredTrial, answer: String?, prefer: String?): Boolean? {
        if (person.kind == StoredTrial.COMPARE) {
            return if (person.prefer == null || prefer == null) null else person.prefer == prefer
        }
        val p = Answer.entries.firstOrNull { it.name == person.answer } ?: return null
        val a = Answer.entries.firstOrNull { it.name == answer } ?: return null
        return agrees(p, a)
    }

    /**
     * The Wilson score range for [k] agreements of [n] (each may be a fraction: older evidence counts for less), at [z]
     * standard deviations. With no evidence it is the whole of 0 to 1. The default is the 80 % range, whose bottom is a
     * one-sided 90 % bound: "nine times in ten it agrees at least this often".
     */
    fun wilson(k: Double, n: Double, z: Double = Normal.Z80): Range {
        if (n <= 0.0) return Range(0.0, 1.0)
        val p = (k / n).coerceIn(0.0, 1.0)
        val z2 = z * z
        val denominator = 1 + z2 / n
        val centre = (p + z2 / (2 * n)) / denominator
        val half = z * sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator
        return Range((centre - half).coerceAtLeast(0.0), (centre + half).coerceAtMost(1.0))
    }
}

/**
 * One hand both the person and Ai answered (S.md §6½): the person's trial, Ai's answer to it, and what makes the pair
 * count. [heldOut] is the honesty test — Ai was never shown the person's answer to this hand, nor anything said after it
 * — and only held-out pairs on the person's blind answers make the confidence score; pairs where the person had seen
 * Ai's answer first ([seen]) measure how much seeing it moves them.
 */
class JudgedPair(
    val person: StoredTrial,
    /** Ai's own trial, or null for a verdict a 1.1.2 log kept on the person's trial. */
    val aiTrial: StoredTrial?,
    val verdict: AiVerdict,
    val agrees: Boolean,
    val heldOut: Boolean,
    val kind: String?,
) {
    val seen: Boolean get() = person.sawAi
    val sure: Double? get() = verdict.sure

    /** When the person answered: the pair's place in time. */
    val at: Long get() = person.at

    /** Whether it counts toward the confidence score. */
    val counts: Boolean get() = heldOut && !seen

    /** Whether both gave exactly the same answer. */
    val same: Boolean
        get() = if (person.kind == StoredTrial.COMPARE) person.prefer == (aiTrial?.prefer ?: verdict.prefer)
        else person.answer == (aiTrial?.answer ?: verdict.answer)

    companion object {
        /**
         * Every pair in [trials]: Ai's trials with the person's they answer ([StoredTrial.of]), and the verdicts a 1.1.2
         * log kept on a person's trial (never held out: nothing says what Ai was shown). [kindOf] names a trial's kind
         * when the verdict did not.
         */
        fun all(trials: List<StoredTrial>, kindOf: (StoredTrial) -> String? = { null }): List<JudgedPair> {
            val byId = trials.associateBy { it.id }
            val out = ArrayList<JudgedPair>()
            for (t in trials) {
                if (t.judge == StoredTrial.AI) {
                    val person = t.of?.let(byId::get)?.takeIf { it.judge == StoredTrial.PERSON } ?: continue
                    val verdict = t.ai ?: AiVerdict(answer = t.answer, prefer = t.prefer)
                    val agrees = Agreement.agrees(person, t.answer ?: verdict.answer, t.prefer ?: verdict.prefer) ?: continue
                    out += JudgedPair(person, t, verdict, agrees, heldOut(person, verdict, byId), verdict.kind ?: kindOf(person))
                } else {
                    val verdict = t.ai ?: continue
                    val agrees = Agreement.agrees(t, verdict.answer, verdict.prefer) ?: continue
                    out += JudgedPair(t, null, verdict, agrees, heldOut = false, kind = verdict.kind ?: kindOf(t))
                }
            }
            return out.sortedBy { it.at }
        }

        /**
         * Whether Ai answered [person]'s hand from what it had before the person did: its record says what it was shown,
         * that did not hold this hand's answer, and every example it was shown had been answered before this one.
         */
        fun heldOut(person: StoredTrial, verdict: AiVerdict, byId: Map<String, StoredTrial>): Boolean {
            if (verdict.asked == null) return false
            if (person.id in verdict.examples) return false
            return verdict.examples.all { id -> byId[id]?.let { it.at < person.at } ?: true }
        }
    }
}
