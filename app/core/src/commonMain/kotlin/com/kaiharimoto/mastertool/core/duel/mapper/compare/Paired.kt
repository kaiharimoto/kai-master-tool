package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainGate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Two versions of a deck on the same hands (Phase G, G2): hand k of version A and hand k of version B are dealt from the same
 * keys (`GoldfishHands.keyed`), so they hold the same cards but where a changed copy lands. Each hand is a pair of answers,
 * and the question "is B better" is asked of the pairs, not of two separate shares — the hands both versions open alike say
 * nothing, and only the hands that differ carry the difference. That is why a paired run needs a fraction of the hands.
 */

/** One hand's answer for one version: it reached a board that passes the ask, it did not, or its map ran out undecided. */
enum class HandAnswer { YES, NO, UNDECIDED }

/**
 * The four paired counts of a comparison, with the hands either version left undecided counted apart. A hand undecided in a
 * version counts as a miss there in [both], [onlyA], [onlyB] and [neither] (what a run reports today: every share is at least
 * what it says), and [bounds] counts them both ways.
 */
data class Paired(
    /** Both versions reached it. */
    val both: Int = 0,
    /** Only A reached it: a hand B lost. */
    val onlyA: Int = 0,
    /** Only B reached it: a hand B won. */
    val onlyB: Int = 0,
    /** Neither reached it. */
    val neither: Int = 0,
    /** Hands A left undecided (counted as misses above), and of them those B reached. */
    val undecidedA: Int = 0,
    val undecidedB: Int = 0,
    /** Hands undecided in A that B decided either way, and hands undecided in B that A decided: they could swing the difference. */
    val swingA: Int = 0,
    val swingB: Int = 0,
) {
    val hands: Int get() = both + onlyA + onlyB + neither

    /** A's and B's shares. */
    val a: Double get() = if (hands == 0) 0.0 else (both + onlyA).toDouble() / hands
    val b: Double get() = if (hands == 0) 0.0 else (both + onlyB).toDouble() / hands

    /** B less A, as a share of the hands: positive is B better. */
    val difference: Double get() = if (hands == 0) 0.0 else (onlyB - onlyA).toDouble() / hands

    /** The hands that changed answer between the versions. */
    val discordant: Int get() = onlyA + onlyB

    /** The 95 % interval of [difference] for paired data (Newcombe 1998, method 10: Wilson limits, the pairs' correlation). */
    val interval: Pair<Double, Double> get() = PairedMath.newcombe(both, onlyA, onlyB, neither)

    /** The exact two-sided McNemar test: how often a difference this lopsided comes of chance when the versions are alike. */
    val pValue: Double get() = PairedMath.mcnemar(onlyA, onlyB)

    /**
     * The difference with every undecided hand counted both ways: undecided in A as reached and in B as missed (the least B
     * can be ahead), then the other way about (the most). Equal to [difference] when no hand was undecided.
     */
    val bounds: Pair<Double, Double>
        get() = if (hands == 0) 0.0 to 0.0 else (difference - undecidedA.toDouble() / hands) to (difference + undecidedB.toDouble() / hands)

    /**
     * Whether a sequential run may stop here: the interval leaves zero (B is better, or worse), or it lies within ±[close] (the
     * versions are alike, as far as this ask can tell). Never before [atLeast] hands — a handful of hands can look decided.
     */
    fun settled(close: Double = PairedMath.CLOSE, atLeast: Int = PairedMath.FIRST_LOOK): Boolean {
        if (hands < atLeast) return false
        val (lo, hi) = interval
        return lo > 0.0 || hi < 0.0 || (lo >= -close && hi <= close)
    }

    /** Which way it came out, in words a person reads first. */
    val verdict: Verdict
        get() {
            val (lo, hi) = interval
            return when {
                hands == 0 -> Verdict.NOTHING
                lo > 0.0 -> Verdict.B_BETTER
                hi < 0.0 -> Verdict.A_BETTER
                lo >= -PairedMath.CLOSE && hi <= PairedMath.CLOSE -> Verdict.ALIKE
                else -> Verdict.UNSURE
            }
        }

    operator fun plus(o: Paired) = Paired(
        both + o.both, onlyA + o.onlyA, onlyB + o.onlyB, neither + o.neither,
        undecidedA + o.undecidedA, undecidedB + o.undecidedB, swingA + o.swingA, swingB + o.swingB,
    )

    enum class Verdict { NOTHING, B_BETTER, A_BETTER, ALIKE, UNSURE }

    companion object {
        /** The pairs of [a] and [b], hand by hand: the lists are the same hands, in the same order. */
        fun of(a: List<HandAnswer>, b: List<HandAnswer>): Paired {
            require(a.size == b.size) { "two versions are compared on the same hands" }
            var both = 0; var onlyA = 0; var onlyB = 0; var neither = 0
            var ua = 0; var ub = 0; var sa = 0; var sb = 0
            for (i in a.indices) {
                val x = a[i] == HandAnswer.YES
                val y = b[i] == HandAnswer.YES
                when {
                    x && y -> both++
                    x -> onlyA++
                    y -> onlyB++
                    else -> neither++
                }
                if (a[i] == HandAnswer.UNDECIDED) { ua++; if (b[i] != HandAnswer.UNDECIDED) sa++ }
                if (b[i] == HandAnswer.UNDECIDED) { ub++; if (a[i] != HandAnswer.UNDECIDED) sb++ }
            }
            return Paired(both, onlyA, onlyB, neither, ua, ub, sa, sb)
        }
    }
}

/** The arithmetic of [Paired], apart so it can be held to published values. */
object PairedMath {
    /** "Within ±1 point": two versions this close are alike for a player's purposes. */
    const val CLOSE = 0.01

    /** The fewest hands a sequential run looks at before it may stop. */
    const val FIRST_LOOK = 200

    /**
     * Newcombe's method 10 for the difference of paired proportions, B less A: [both] (a), [onlyA] (b), [onlyB] (c),
     * [neither] (d). Each version's Wilson limits, joined through the pairs' φ correlation — never wider than the unpaired
     * interval when the versions agree on most hands, and never outside [−1, 1].
     */
    fun newcombe(both: Int, onlyA: Int, onlyB: Int, neither: Int, z: Double = 1.96): Pair<Double, Double> {
        val n = both + onlyA + onlyB + neither
        if (n == 0) return -1.0 to 1.0
        // No hand changed: the method's correlation is 1 and its interval a point. The difference is then at most the rate of
        // changed hands, whose exact 95 % bound for none in n is 1 − 0.025^(1/n) — about 3.7 / n.
        if (onlyA + onlyB == 0) {
            val bound = 1 - 0.025.pow(1.0 / n)
            return -bound to bound
        }
        val p1 = (both + onlyA).toDouble() / n
        val p2 = (both + onlyB).toDouble() / n
        val (l1, u1) = TrainGate.wilson(p1, n, z)
        val (l2, u2) = TrainGate.wilson(p2, n, z)
        val a = both.toDouble(); val b = onlyA.toDouble(); val c = onlyB.toDouble(); val d = neither.toDouble()
        val margins = (a + b) * (c + d) * (a + c) * (b + d)
        val phi = if (margins == 0.0) 0.0 else (a * d - b * c) / sqrt(margins)
        val theta = p2 - p1
        val down = sqrt(((p2 - l2) * (p2 - l2) - 2 * phi * (p2 - l2) * (u1 - p1) + (u1 - p1) * (u1 - p1)).coerceAtLeast(0.0))
        val up = sqrt(((u2 - p2) * (u2 - p2) - 2 * phi * (u2 - p2) * (p1 - l1) + (p1 - l1) * (p1 - l1)).coerceAtLeast(0.0))
        return (theta - down).coerceAtLeast(-1.0) to (theta + up).coerceAtMost(1.0)
    }

    /**
     * The interval of B's share less A's from two separate runs of [n] hands each ([hitsA], [hitsB]): Newcombe's method 10
     * with no correlation. What comparing two runs dealt apart would say — the paired interval's yardstick.
     */
    fun unpaired(hitsA: Int, hitsB: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n == 0) return -1.0 to 1.0
        val p1 = hitsA.toDouble() / n
        val p2 = hitsB.toDouble() / n
        val (l1, u1) = TrainGate.wilson(p1, n, z)
        val (l2, u2) = TrainGate.wilson(p2, n, z)
        val theta = p2 - p1
        return (theta - sqrt((p2 - l2) * (p2 - l2) + (u1 - p1) * (u1 - p1))).coerceAtLeast(-1.0) to
            (theta + sqrt((u2 - p2) * (u2 - p2) + (p1 - l1) * (p1 - l1))).coerceAtMost(1.0)
    }

    /**
     * The exact McNemar test: of the [onlyA] + [onlyB] hands that changed, the chance of a split at least this lopsided when
     * each is a fair coin (two-sided, the binomial's tail doubled, at most 1). 1 when no hand changed.
     */
    fun mcnemar(onlyA: Int, onlyB: Int): Double {
        val n = onlyA + onlyB
        if (n == 0) return 1.0
        val k = minOf(onlyA, onlyB)
        // Summed in logs: a thousand changed hands would underflow 0.5^n.
        val logHalf = n * ln(0.5)
        var tail = 0.0
        var logC = 0.0
        for (i in 0..k) {
            if (i > 0) logC += ln((n - i + 1).toDouble()) - ln(i.toDouble())
            tail += exp(logC + logHalf)
        }
        return (2 * tail).coerceAtMost(1.0)
    }

    /** The difference in points, signed: "+3.1" or "−0.4". */
    fun points(x: Double): String {
        val p = kotlin.math.round(x * 1000) / 10.0
        val s = if (abs(p) < 0.05) "0.0" else abs(p).toString().let { if ('.' in it) it else "$it.0" }
        return when {
            p > 0.05 -> "+$s"
            p < -0.05 -> "−$s"
            else -> s
        }
    }
}
