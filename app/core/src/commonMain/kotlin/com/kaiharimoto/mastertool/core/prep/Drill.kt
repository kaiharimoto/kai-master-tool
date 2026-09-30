package com.kaiharimoto.mastertool.core.prep

import kotlinx.serialization.Serializable

/**
 * How one siding drill is going: how often it was asked, how often answered
 * perfectly, when last, and its Leitner box — 0 for "ask again soon" up to 4
 * for "known".
 */
@Serializable
data class DrillStat(
    val seen: Int = 0,
    val correct: Int = 0,
    val lastAt: Long = 0,
    val box: Int = 0,
)

/**
 * Siding drills: the plan for a matchup and turn is hidden, you pick what comes
 * out and what goes in, and the pick is scored against the plan.
 *
 * It exists because of one rule. The policy forbids reading any notes during a
 * match, between games included (§IV.J names "a list of Side Deck choices" as
 * the example), and gives three minutes to side (§VII.C). A plan is only worth
 * something at the table if it is remembered, so it is drilled the way anything
 * to be remembered is: spaced repetition by Leitner boxes, the plans you get
 * wrong coming back first.
 */
object Drill {

    /** The most boxes a drill climbs through. */
    const val TOP_BOX = 4

    /**
     * How long after its last answer a drill in each box is due again: at once,
     * an hour, a day, three days, a week. Short, because a player drills in the
     * week before an event, not over months.
     */
    private val DUE_AFTER = longArrayOf(0L, HOUR, DAY, 3 * DAY, 7 * DAY)

    /**
     * An answer scored against the plan, copy by copy — out and in apart, since
     * putting the right card in and forgetting what it replaces is a different
     * mistake from the opposite.
     */
    data class Score(
        /** Copies taken out that the plan takes out. */
        val outRight: Int,
        /** Copies taken out that the plan keeps. */
        val outWrong: Int,
        /** Copies the plan takes out that were not. */
        val outMissed: Int,
        val inRight: Int,
        val inWrong: Int,
        val inMissed: Int,
    ) {
        val perfect: Boolean get() = outWrong == 0 && outMissed == 0 && inWrong == 0 && inMissed == 0

        /**
         * How much of it was right, 0 to 1: right copies over everything either
         * the plan or the answer named. An empty plan answered with nothing is 1.
         */
        val fraction: Double
            get() {
                val right = outRight + inRight
                val all = right + outWrong + outMissed + inWrong + inMissed
                return if (all == 0) 1.0 else right.toDouble() / all
            }
    }

    /**
     * [pickedOut] and [pickedIn] against [planOut] and [planIn], as multisets of
     * passcodes: the plan says two copies of a card, one picked is one right and
     * one missed; three picked is two right and one wrong. Order never counts.
     */
    fun score(planOut: List<Int>, planIn: List<Int>, pickedOut: List<Int>, pickedIn: List<Int>): Score {
        val out = compare(planOut, pickedOut)
        val into = compare(planIn, pickedIn)
        return Score(out[0], out[1], out[2], into[0], into[1], into[2])
    }

    /** Right, wrong, missed. */
    private fun compare(plan: List<Int>, picked: List<Int>): IntArray {
        val want = plan.groupingBy { it }.eachCount()
        val got = picked.groupingBy { it }.eachCount()
        var right = 0
        (want.keys + got.keys).forEach { id ->
            right += minOf(want[id] ?: 0, got[id] ?: 0)
        }
        return intArrayOf(right, picked.size - right, plan.size - right)
    }

    /**
     * [stat] after an answer scoring [score] at [now]. Perfect climbs a box; a
     * near miss (three quarters or more right) drops one; anything worse goes
     * back to the first box, to be asked again at once.
     */
    fun update(stat: DrillStat, score: Score, now: Long): DrillStat {
        val box = when {
            score.perfect -> (stat.box + 1).coerceAtMost(TOP_BOX)
            score.fraction >= 0.75 -> (stat.box - 1).coerceAtLeast(0)
            else -> 0
        }
        return stat.copy(
            seen = stat.seen + 1,
            correct = stat.correct + if (score.perfect) 1 else 0,
            lastAt = now,
            box = box,
        )
    }

    /**
     * The drill to ask next among [keys] (each `"<matchupId>:<FIRST|SECOND>"`):
     * the lowest box first, then the one answered longest ago; a drill never
     * asked is box 0, never answered, so new plans come first. Drills that are
     * due at [now] go before those that are not, but when none is due the best
     * of the rest is still given — a player who opens the drill wants to drill.
     * The one just answered waits while another is due. Null for no keys.
     */
    fun next(keys: List<String>, stats: Map<String, DrillStat>, now: Long): String? {
        if (keys.isEmpty()) return null
        fun stat(key: String) = stats[key] ?: DrillStat()
        fun due(key: String): Boolean {
            val s = stat(key)
            if (s.seen == 0) return true
            val wait = DUE_AFTER[s.box.coerceIn(0, TOP_BOX)]
            return now - s.lastAt >= wait
        }
        val latest = keys.maxOfOrNull { stat(it).lastAt } ?: 0L
        return keys.distinct().minWithOrNull(
            compareBy<String> { if (due(it)) 0 else 1 }
                .thenBy { if (keys.size > 1 && stat(it).seen > 0 && stat(it).lastAt == latest) 1 else 0 }
                .thenBy { stat(it).box }
                .thenBy { stat(it).lastAt }
                .thenBy { keys.indexOf(it) },
        )
    }

    /** The key a drill is kept under. */
    fun key(matchupId: String, turn: String): String = "$matchupId:$turn"
}

private const val HOUR = 60L * 60 * 1000
private const val DAY = 24 * HOUR
