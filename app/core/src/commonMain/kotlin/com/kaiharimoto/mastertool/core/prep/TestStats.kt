package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.web.WebEntry
import kotlin.math.sqrt

/**
 * What the test games log says: each matchup's game win rate split the ways
 * that matter to a Yu-Gi-Oh! match — going first or second, before siding or
 * after — and what those rates are worth as matches against the field.
 *
 * Game rates, not match rates, are what is recorded, because a match is three
 * games of different kinds: Game 1 is a coin flip for the turn with the main
 * deck, and afterwards the loser picks the turn with a sided deck. [matchWin]
 * turns the one into the other exactly, so logging single games (which is all a
 * testing session produces) is enough to answer "what are my odds at this event".
 *
 * Draws count in no rate: they are rare, a match with one goes on to another
 * duel, and counting them as half a win would move the rates for a result that
 * decides nothing.
 */
object TestStats {

    /** [wins] out of [games] decided games. */
    data class Rate(val wins: Int, val games: Int) {
        val pct: Double get() = if (games == 0) 0.0 else wins.toDouble() / games

        /**
         * The Wilson score interval: where the true rate probably lies (95 % at
         * the default [z]). Ten games say far less than the percentage suggests,
         * and this is the honest width of that — it stays inside 0 to 1 and does
         * not collapse to a point at 0/3 or 3/3 the way the plain ±z·√(p(1−p)/n)
         * does. No games is no knowledge: 0 to 1.
         */
        fun wilson(z: Double = 1.96): Pair<Double, Double> {
            if (games == 0) return 0.0 to 1.0
            val n = games.toDouble()
            val p = pct
            val z2 = z * z
            val centre = p + z2 / (2 * n)
            val spread = z * sqrt(p * (1 - p) / n + z2 / (4 * n * n))
            val denominator = 1 + z2 / n
            val low = ((centre - spread) / denominator).coerceIn(0.0, 1.0)
            val high = ((centre + spread) / denominator).coerceIn(0.0, 1.0)
            return low to high
        }

        operator fun plus(other: Rate) = Rate(wins + other.wins, games + other.games)

        companion object {
            val NONE = Rate(0, 0)
        }
    }

    /** One opponent's line in the matrix. */
    data class Row(
        /** The opponent's key: a web deck's id, or its typed name. */
        val opponent: String,
        /** Its name as last logged. */
        val name: String,
        val first: Rate,
        val second: Rate,
        val preSide: Rate,
        val postSide: Rate,
        val all: Rate,
        /** Mean length of the games that were timed, or null when none were. */
        val avgMinutes: Double?,
        /** Game 1 going first: the main deck, before siding (Phase B). */
        val preFirst: Rate = Rate.NONE,
        /** Game 1 going second. */
        val preSecond: Rate = Rate.NONE,
        /** Games 2 and 3 going first: the sided deck. */
        val postFirst: Rate = Rate.NONE,
        /** Games 2 and 3 going second. */
        val postSecond: Rate = Rate.NONE,
    )

    /**
     * Every opponent played, with its rates, most-played first. Only [deckId]'s
     * games when one is given, since a log spans every deck you tested.
     */
    fun matrix(games: List<TestGame>, deckId: String? = null): List<Row> =
        games.asSequence()
            .filter { deckId == null || it.deckId == deckId }
            .groupBy { it.opponent }
            .map { (opponent, list) ->
                fun rate(filter: (TestGame) -> Boolean): Rate {
                    val decided = list.filter { filter(it) && it.result != TestGame.DRAW }
                    return Rate(decided.count { it.result == TestGame.WIN }, decided.size)
                }
                val timed = list.mapNotNull { it.minutes }
                Row(
                    opponent = opponent,
                    name = list.maxByOrNull { it.at }?.opponentName?.takeIf { it.isNotBlank() } ?: opponent,
                    first = rate { it.turn == TestGame.FIRST },
                    second = rate { it.turn == TestGame.SECOND },
                    preSide = rate { !it.postSide },
                    postSide = rate { it.postSide },
                    all = rate { true },
                    avgMinutes = if (timed.isEmpty()) null else timed.average(),
                    preFirst = rate { !it.postSide && it.turn == TestGame.FIRST },
                    preSecond = rate { !it.postSide && it.turn == TestGame.SECOND },
                    postFirst = rate { it.postSide && it.turn == TestGame.FIRST },
                    postSecond = rate { it.postSide && it.turn == TestGame.SECOND },
                )
            }
            .sortedWith(compareByDescending<Row> { it.all.games }.thenBy { it.name.lowercase() })

    /**
     * The chance of winning a best-of-three match from the chance of winning a
     * game going first ([pFirst]) and going second ([pSecond]), by walking every
     * way the match can go.
     *
     * Game 1's turn is a coin flip (§IV.F: a random method picks who decides, and
     * everyone who decides picks the same). After that the loser of the last
     * duel decides, and is assumed to take the turn that is better for them —
     * going first when [firstChoice], as nearly every current deck would, else
     * going second. Both players choose alike, so after a win you play the turn
     * your opponent left you.
     */
    fun matchWin(pFirst: Double, pSecond: Double, firstChoice: Boolean = true): Double =
        matchWin(pFirst, pSecond, pFirst, pSecond, firstChoice)

    /**
     * The same walk with Game 1 and the sided games apart (Phase B): Game 1 is played with the main deck at
     * [g1First]/[g1Second] (the coin decides the turn), games 2 and 3 with the sided deck at
     * [sidedFirst]/[sidedSecond] (the loser deciding). Pooling them read a deck that is strong only after siding as
     * strong in Game 1 too, and the other way round.
     */
    fun matchWin(g1First: Double, g1Second: Double, sidedFirst: Double, sidedSecond: Double, firstChoice: Boolean = true): Double {
        val pFirst = g1First
        val pSecond = g1Second
        val afterLoss = if (firstChoice) sidedFirst else sidedSecond
        val afterWin = if (firstChoice) sidedSecond else sidedFirst

        // The chance of taking the match from here, having won `won` and lost
        // `lost`, playing the next game at `p`.
        fun walk(won: Int, lost: Int, p: Double): Double = when {
            won == 2 -> 1.0
            lost == 2 -> 0.0
            else -> p * walk(won + 1, lost, afterWin) + (1 - p) * walk(won, lost + 1, afterLoss)
        }
        return 0.5 * walk(0, 0, pFirst) + 0.5 * walk(0, 0, pSecond)
    }

    /**
     * The match win rate to expect against a field: each opponent's [matchWin]
     * weighted by its share of the field in [shares] (percent, normalised over
     * what is given, so shares that do not sum to 100 still weigh correctly).
     *
     * A handful of games is not a rate, so each is pulled toward [prior] by
     * [priorWeight] games' worth of it — Beta smoothing: three wins from three
     * games going first reads as (3 + 2) / (3 + 4) ≈ 71 %, not 100 %. An opponent
     * in the field with no games logged at all counts at the prior.
     *
     * Game 1 and the sided games are played at their own rates (Phase B), going first
     * and second: four rates, each smoothed so, and a split with no games at all falls
     * back to the turn's pooled rate (Game 1 and sided together), so a log that never
     * says which game it was reads exactly as it did before the split.
     *
     * The person's own deck belongs in [shares] at its share, as the mirror ([field]):
     * its rate is the games logged against it ([mirrored]), else the prior — 50 %.
     */
    fun expected(rows: List<Row>, shares: Map<String, Int>, prior: Double = 0.5, priorWeight: Int = 4): Double {
        val weighed = shares.filterValues { it > 0 }
        val total = weighed.values.sum()
        if (total == 0) return matchWin(prior, prior)
        val byOpponent = rows.associateBy { it.opponent }
        return weighed.entries.sumOf { (opponent, share) ->
            share.toDouble() / total * matchAgainst(byOpponent[opponent], prior, priorWeight)
        }
    }

    /** Best of three against one opponent's [row] (none: never played), its four rates smoothed as [expected] says. */
    fun matchAgainst(row: Row?, prior: Double = 0.5, priorWeight: Int = 4): Double {
        val r = smoothed(row, prior, priorWeight)
        return matchWin(r[0], r[1], r[2], r[3])
    }

    /**
     * Game 1 first and second, sided first and second, smoothed (2026-10, the red team's finding 2). A turn with games in
     * neither split reads its pooled rate pulled toward [prior], as an older log always did. Otherwise each split is pulled
     * by [priorWeight] games toward the other split of its turn, itself pulled toward [prior] — the two read apart, each
     * leaning on the other where it has few games. The old rule pulled a split with games toward [prior] but read a split
     * without any at the turn's pooled rate, so one Game 1 win logged beside 25 of 29 sided wins dropped Game 1 from 82 %
     * to 60 %: a win lowered the matchup. Now a win never lowers a rate.
     */
    fun smoothed(row: Row?, prior: Double = 0.5, priorWeight: Int = 4): DoubleArray {
        fun turn(pre: Rate?, post: Rate?, pooled: Rate?): Pair<Double, Double> {
            if ((pre?.games ?: 0) == 0 && (post?.games ?: 0) == 0) {
                val p = smooth(pooled, prior, priorWeight)
                return p to p
            }
            val preAlone = smooth(pre, prior, priorWeight)
            val postAlone = smooth(post, prior, priorWeight)
            return smooth(pre, postAlone, priorWeight) to smooth(post, preAlone, priorWeight)
        }
        val (preFirst, postFirst) = turn(row?.preFirst, row?.postFirst, row?.first)
        val (preSecond, postSecond) = turn(row?.preSecond, row?.postSecond, row?.second)
        return doubleArrayOf(preFirst, preSecond, postFirst, postSecond)
    }

    /**
     * Game 1 first and second, sided first and second, each by [rate]; a split with no games is the turn's pooled
     * rate instead ([Row.first], [Row.second]). For raw rates; the smoothed ones are [smoothed].
     */
    fun rates(row: Row?, rate: (Rate?) -> Double): DoubleArray {
        fun split(r: Rate?, pooled: Rate?) = if ((r?.games ?: 0) > 0) rate(r) else rate(pooled)
        return doubleArrayOf(
            split(row?.preFirst, row?.first),
            split(row?.preSecond, row?.second),
            split(row?.postFirst, row?.first),
            split(row?.postSecond, row?.second),
        )
    }

    /** [r] pulled toward [prior] by [priorWeight] games' worth of it (Beta smoothing). */
    fun smooth(r: Rate?, prior: Double = 0.5, priorWeight: Int = 4): Double {
        val wins = r?.wins ?: 0
        val games = r?.games ?: 0
        val pseudo = priorWeight.coerceAtLeast(0)
        return if (games + pseudo == 0) prior else (wins + prior * pseudo) / (games + pseudo)
    }

    /**
     * The field [expected] weighs, from a web's decks: every deck with a share, **the person's own among them**.
     *
     * Why the own deck stays (Phase B): its share is how much of the room plays it, so that much of the event is the
     * mirror. Dropping it and renormalising handed its share to every other deck in proportion — a field that is a
     * fifth mirror read as if those rounds were against everyone else, and the expected win moved by however far the
     * other matchups sit from the mirror's (about 50 %). Its key is its deck id, so games logged against it are its
     * rate; with none it counts at the prior, 50 %.
     */
    fun field(entries: List<WebEntry>): Map<String, Int> =
        entries.mapNotNull { e -> e.share?.takeIf { it > 0 }?.let { e.deckId to it } }.toMap()

    /**
     * [games] with the mirror under one key: a game logged against [mine] by name (typed, because the person's own
     * deck is not offered as an opponent) is put under [mine]'s id, so the mirror's games are one rate.
     */
    fun mirrored(games: List<TestGame>, mine: String?, names: Collection<String>): List<TestGame> {
        if (mine == null) return games
        val folded = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (folded.isEmpty()) return games
        return games.map { g -> if (g.opponent != mine && g.opponent.trim().lowercase() in folded) g.copy(opponent = mine) else g }
    }

    /**
     * Opponents whose games run long enough that a three-game match would not
     * fit in the round (their mean game × 3 over [roundMinutes]) — the matchups
     * where time, not the opponent, decides: at 50 minutes an unfinished match
     * is a loss for both (§V.B). Their keys, in the rows' order.
     */
    fun timeRisk(rows: List<Row>, roundMinutes: Int = Policy.ROUND_MINUTES): List<String> =
        rows.filter { row -> row.avgMinutes?.let { it * 3 > roundMinutes } == true }.map { it.opponent }
}
