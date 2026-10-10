package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.RealWorld
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
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

    /**
     * The band an answer names, as a count in ten (S.md §4½: the person's answers are the scale's anchor): games won in a
     * matchup, hands that do what the deck wants alone. "In 10" keeps "%" for the model's own numbers (design review, 1.1.6).
     */
    fun band(answer: Answer): String = when (answer) {
        Answer.CLEAR_WIN -> "8+ in 10"
        Answer.LEAN_WIN -> "6–8 in 10"
        Answer.COIN_FLIP -> "4–6 in 10"
        Answer.LEAN_LOSS -> "2–4 in 10"
        Answer.CLEAR_LOSS -> "0–2 in 10"
    }

    /** The one question a rating asks, over its five answers. */
    fun question(alone: Boolean): String =
        if (alone) "How often does a hand like this do what the deck wants?" else "How does this game go for you?"

    /**
     * A stratum's short name, a column head: `G1 first`, `Sided second`, `Going first`. The same words as [situation],
     * shortened, so one situation has one name everywhere (design review, 1.1.6).
     */
    fun stratum(s: Stratum): String = when (s) {
        Stratum.ALONE_FIRST -> "Going first"
        Stratum.ALONE_SECOND -> "Going second"
        Stratum.G1_FIRST -> "G1 first"
        Stratum.G1_SECOND -> "G1 second"
        Stratum.SIDED_FIRST -> "Sided first"
        Stratum.SIDED_SECOND -> "Sided second"
    }

    /** A stratum in full, over a trial: which game, and who goes first. [opponent] is kept for callers; the words are yours. */
    @Suppress("UNUSED_PARAMETER")
    fun situation(s: Stratum, opponent: String?): String = when (s) {
        Stratum.ALONE_FIRST -> "Going first"
        Stratum.ALONE_SECOND -> "Going second"
        Stratum.G1_FIRST -> "Game 1 · going first"
        Stratum.G1_SECOND -> "Game 1 · going second"
        Stratum.SIDED_FIRST -> "Sided · going first"
        Stratum.SIDED_SECOND -> "Sided · going second"
    }

    /** "1 hand", "60 hands": what a person reads is hands, never trials (the code keeps "trial"). */
    fun hands(n: Int): String = "$n hand" + if (n == 1) "" else "s"

    /** How sure Ai said it was, in words, so "%" stays the win chance's: sure, fairly sure, unsure. */
    fun certainty(sure: Double): String = when {
        sure >= 0.8 -> "sure"
        sure >= 0.6 -> "fairly sure"
        else -> "unsure"
    }

    /** A share in tens, rounded: 0.68 → "7 in 10". */
    fun inTen(x: Double): String = "${round(x.coerceIn(0.0, 1.0) * 10).toInt()} in 10"

    /** The person's steadiness ([ShootoutResults.steadiness]) as a sentence. */
    fun steadiness(same: Double): String = "You answer the same hand the same way about ${inTen(same)} times"

    /** A stratum inside a sentence: "going first", "in game 1, going second", "after siding, going first". */
    fun where(s: Stratum): String = when (s) {
        Stratum.ALONE_FIRST -> "going first"
        Stratum.ALONE_SECOND -> "going second"
        Stratum.G1_FIRST -> "in game 1, going first"
        Stratum.G1_SECOND -> "in game 1, going second"
        Stratum.SIDED_FIRST -> "after siding, going first"
        Stratum.SIDED_SECOND -> "after siding, going second"
    }

    /**
     * One line of the results' "So far" (S.md §5: a verdict only where the range supports one): [name]'s worth where it is
     * called — clear of zero at 95 % with every card and situation counted (Phase G, D4); [best] marks the largest gain.
     */
    fun call(name: String, call: Call, best: Boolean): String {
        // Whole points: "about" with a tenth reads as more certain than the range allows.
        val about = abs(round(call.estimate.value)).toInt()
        return if (call.gains) {
            "$name: worth about +$about points ${where(call.stratum)}" + if (best) ", the best card called so far" else ""
        } else {
            "$name: about −$about points ${where(call.stratum)}; below zero with every card counted, so a copy could go"
        }
    }

    /**
     * The roll's call (Phase G, mockup A): "Win the roll: go second (+17 points, ±15)" once its 95 % range clears zero, else
     * how it leans so far.
     */
    fun roll(r: ShootoutResults.Roll): String {
        val by = abs(round(r.difference)).toInt()
        val half = round(r.half95).toInt()
        return when (r.choice) {
            false -> "Win the roll: go second (+$by points, ±$half)"
            true -> "Win the roll: go first (+$by points, ±$half)"
            null -> "Win the roll: too close to call so far (going second ${points(r.difference)} points, ±$half)"
        }
    }

    /** A number with its 95 % range, for a sentence: "+2.1 points (95 %: −0.3 to +4.5)". */
    fun range(e: Estimate): String = "${points(e.value)} points (95 %: ${points(e.range95.start)} to ${points(e.range95.endInclusive)})"

    /**
     * What one more copy is worth (Phase G, D2), beside a card's number: "one more copy +1.2 (±3.0)"; "unrated" for a card no
     * hand has shown, never a number.
     */
    fun nextCopy(e: Estimate?, trials: Int): String = when {
        trials == 0 -> "unrated"
        e == null -> ""
        else -> "one more copy ${points(e.value)} (±${points(e.halfWidth95).removePrefix("+")})"
    }

    /** The results' "So far" when nothing is called: how much is known, and about how many more hands. */
    fun tooEarly(settled: StopRule.Settled, more: Int?): String =
        "Too early to call: ${settled.known} of ${settled.of} cards known within ±${settled.halfWidth.toInt()} points." +
            when {
                more == null -> ""
                more <= 0 -> ""
                else -> " About ${roundHands(more)} more hands."
            }

    /** A count said as "about": to the nearest 10 past 20, the nearest 50 past 200. */
    fun roundHands(n: Int): Int = when {
        n <= 20 -> n
        n <= 200 -> ((n + 5) / 10) * 10
        else -> ((n + 25) / 50) * 50
    }

    /**
     * The random-hands check beside a stratum's win rate, as how far the plain hands' answers run from the model's — never
     * as a rate of its own. The check reads answers as their bands' middles (10, 30 … 90), so a rate made of it sits nearer
     * 50 than the headline even when the model is exactly right: "80 %" beside "90 %" said the model was wrong when it was
     * not (the red team, 2026-10). The difference is read on the answers' own scale, so it says only what it can.
     */
    fun randomCheck(k: RealWorld.Check): String {
        val r = round(k.residual * 10) / 10
        val off = when {
            !k.residualSd.isNaN() && abs(k.residual) <= 2 * k.residualSd -> "in line with the model"
            r > 0 -> "${points(r).drop(1)} points above the model"
            r < 0 -> "${points(r).drop(1)} points below the model"
            else -> "in line with the model"
        }
        return "Random hands only: answers $off (${hands(k.plainTrials)})"
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

    /** A share as whole percent, the way the app writes one: `62%`. */
    fun percent(x: Double): String = "${round(x * 100).toInt()}%"

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
