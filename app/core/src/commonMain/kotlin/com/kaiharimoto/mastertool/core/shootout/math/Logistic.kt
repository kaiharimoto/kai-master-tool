package com.kaiharimoto.mastertool.core.shootout.math

import kotlin.math.exp
import kotlin.math.ln

/**
 * The logistic curve the shootout reads every value through: a hand's value is a log-odds, its win chance the
 * curve at that value (Phase S §2).
 */
object Logistic {

    /** 1 / (1 + e^-x), computed so neither tail overflows. */
    fun of(x: Double): Double = if (x >= 0) 1.0 / (1.0 + exp(-x)) else exp(x).let { it / (1.0 + it) }

    /** The curve's slope at x, the most one unit of value can move the win chance there. */
    fun slope(x: Double): Double = of(x).let { it * (1.0 - it) }

    /** ln of the curve at x, exact in the far tail where the curve itself rounds to 0. */
    fun logOf(x: Double): Double = if (x >= 0) -ln(1.0 + exp(-x)) else x - ln(1.0 + exp(x))

    /** The log-odds of a probability: where the curve reaches [p]. */
    fun logit(p: Double): Double = ln(p / (1.0 - p))
}

/** Two-sided normal quantiles for the ranges the shootout reports. */
object Normal {
    /** The 80 % range is the estimate ± this many standard deviations. */
    const val Z80 = 1.2815515655446004

    /** The 95 % range is the estimate ± this many standard deviations. */
    const val Z95 = 1.959963984540054
}
