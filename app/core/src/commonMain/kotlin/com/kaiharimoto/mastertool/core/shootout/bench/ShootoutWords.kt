package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import kotlin.math.abs
import kotlin.math.round

/**
 * The Shootout's words and its one scale (Phase S §1): five answers from best to worst under keys 1 to 5, the same
 * five points for the deck alone read as how often the hand does what the deck wants, and a phone's swipe onto them.
 */
object ShootoutWords {

    /** The answers in the order they stand and the keys that give them: 1 is the best, 5 the worst. */
    val SCALE: List<Answer> = listOf(Answer.CLEAR_WIN, Answer.LEAN_WIN, Answer.COIN_FLIP, Answer.LEAN_LOSS, Answer.CLEAR_LOSS)

    /** The answer key [key] (1 to 5) gives, or null. */
    fun byKey(key: Int): Answer? = SCALE.getOrNull(key - 1)

    /** The key that gives [answer]. */
    fun keyOf(answer: Answer): Int = SCALE.indexOf(answer) + 1

    /** An answer's words: in a matchup a game's outcome, for the deck alone what the hand does. */
    fun label(answer: Answer, alone: Boolean): String = if (alone) {
        when (answer) {
            Answer.CLEAR_WIN -> "Plays through"
            Answer.LEAN_WIN -> "Likely does"
            Answer.COIN_FLIP -> "Coin flip"
            Answer.LEAN_LOSS -> "Likely not"
            Answer.CLEAR_LOSS -> "Bricks"
        }
    } else {
        when (answer) {
            Answer.CLEAR_WIN -> "Clear win"
            Answer.LEAN_WIN -> "Lean win"
            Answer.COIN_FLIP -> "Coin flip"
            Answer.LEAN_LOSS -> "Lean loss"
            Answer.CLEAR_LOSS -> "Clear loss"
        }
    }

    /** The band of win chance an answer names (S.md §4½: the person's answers are the scale's anchor). */
    fun band(answer: Answer): String = when (answer) {
        Answer.CLEAR_WIN -> "80–100 %"
        Answer.LEAN_WIN -> "60–80 %"
        Answer.COIN_FLIP -> "40–60 %"
        Answer.LEAN_LOSS -> "20–40 %"
        Answer.CLEAR_LOSS -> "0–20 %"
    }

    /** A stratum's name as a column head: `G1 · first`, `Sided · second`, `Going first`. */
    fun stratum(s: Stratum): String = when (s) {
        Stratum.ALONE_FIRST -> "Going first"
        Stratum.ALONE_SECOND -> "Going second"
        Stratum.G1_FIRST -> "G1 · first"
        Stratum.G1_SECOND -> "G1 · second"
        Stratum.SIDED_FIRST -> "Sided · first"
        Stratum.SIDED_SECOND -> "Sided · second"
    }

    /** A stratum as a sentence over a trial: who goes first, and which game. */
    fun situation(s: Stratum, opponent: String?): String = when (s) {
        Stratum.ALONE_FIRST -> "You go first · five cards"
        Stratum.ALONE_SECOND -> "You go second · six cards"
        Stratum.G1_FIRST -> "Game one · you go first"
        Stratum.G1_SECOND -> "Game one · ${opponent ?: "they"} go${if (opponent == null) "" else "es"} first"
        Stratum.SIDED_FIRST -> "After siding · you go first"
        Stratum.SIDED_SECOND -> "After siding · ${opponent ?: "they"} go${if (opponent == null) "" else "es"} first"
    }

    /** Points of win chance, signed and to a tenth: `+4.2`, `−1.0`, `0.0`. */
    fun points(x: Double): String {
        val r = round(x * 10) / 10
        val body = abs(r).let { a -> val whole = a.toLong(); "$whole.${round((a - whole) * 10).toInt()}" }
        return when {
            r > 0 -> "+$body"
            r < 0 -> "−$body"
            else -> body
        }
    }

    /** A share as whole percent: `62 %`. */
    fun percent(x: Double): String = "${round(x * 100).toInt()} %"

    /**
     * A phone's swipe on a rating (S.md §1: "a swipe on a phone"), from how far the finger travelled as fractions of
     * the trial's width and height: right is a win, left a loss, a long swipe the clear answer and a short one the
     * lean; up is a coin flip. Too short, or neither way, is nothing.
     */
    fun swipe(dx: Float, dy: Float): Answer? {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ay > ax) return if (dy < -SWIPE_LEAN && ay > 1.5f * ax) Answer.COIN_FLIP else null
        return when {
            ax < SWIPE_LEAN -> null
            dx > 0 -> if (ax >= SWIPE_CLEAR) Answer.CLEAR_WIN else Answer.LEAN_WIN
            else -> if (ax >= SWIPE_CLEAR) Answer.CLEAR_LOSS else Answer.LEAN_LOSS
        }
    }

    /** A swipe this far across (a share of the width) is a lean; this far, a clear answer. */
    const val SWIPE_LEAN = 0.12f
    const val SWIPE_CLEAR = 0.32f
}
