package com.kaiharimoto.mastertool.core.prep

import kotlin.math.sqrt

/**
 * What to practise next (Phase G, G.5; the red team's M1). Practice narrows what the person knows — it does not fix a weak
 * matchup — so the next games are the ones that narrow the field's range most: for each opponent with a share and each of
 * the four kinds of game (Game 1 or sided, first or second), how much five more games there would take off the width of
 * the expected match win's 95 % range.
 *
 * Read by the delta method on the rates [TestStats.expected] uses: each kind of game a Beta posterior (its games and the
 * four games' worth of prior), its variance carried to the match win through the walk's slope, each opponent weighted by
 * share. Five more games are taken at the rate as it stands, so the plan never assumes they go well or badly.
 */
object PracticePlan {
    /** Game 1 first, Game 1 second, sided first, sided second: the order [TestStats.smoothed] returns. */
    enum class Kind(val words: String) {
        G1_FIRST("Game 1s going first"),
        G1_SECOND("Game 1s going second"),
        SIDED_FIRST("sided games going first"),
        SIDED_SECOND("sided games going second"),
    }

    /** Playing [games] of [kind] against [opponent] would narrow the field's range from [before] to [after] (points, full width). */
    data class Step(val opponent: String, val name: String, val kind: Kind, val games: Int, val before: Double, val after: Double) {
        val gain: Double get() = before - after
    }

    /** Every opponent with a share, every kind of game, the most narrowing first. */
    fun steps(rows: List<TestStats.Row>, shares: Map<String, Int>, games: Int = 5, other: TestStats.Other? = null): List<Step> {
        val weighed = shares.filterValues { it > 0 }
        if (weighed.isEmpty()) return emptyList()
        val byKey = rows.associateBy { it.opponent }
        val total = weighed.values.sum() + (other?.share?.coerceAtLeast(0) ?: 0)
        // Each opponent's variance of the match win as it stands, and its four cells' slopes and posteriors.
        class Cell(val slope: Double, val p: Double, val n: Int)
        val cells = weighed.mapValues { (k, _) ->
            val row = byKey[k]
            val r = TestStats.smoothed(row)
            val n = counts(row)
            Kind.entries.map { kind -> Cell(slope(r, kind.ordinal), r[kind.ordinal], n[kind.ordinal]) }
        }
        fun variance(add: Pair<String, Kind>?): Double = weighed.entries.sumOf { (k, share) ->
            val w = share.toDouble() / total
            w * w * cells.getValue(k).withIndex().sumOf { (i, c) ->
                val extra = if (add != null && add.first == k && add.second.ordinal == i) games else 0
                c.slope * c.slope * c.p * (1 - c.p) / (c.n + PRIOR + extra + 1)
            }
        }
        val before = width(variance(null))
        return weighed.keys.flatMap { k ->
            Kind.entries.map { kind -> Step(k, byKey[k]?.name ?: k, kind, games, before, width(variance(k to kind))) }
        }.sortedByDescending { it.gain }
    }

    /** The one step that narrows the range most, or null with no field. */
    fun next(rows: List<TestStats.Row>, shares: Map<String, Int>, games: Int = 5, other: TestStats.Other? = null): Step? =
        steps(rows, shares, games, other).firstOrNull()

    /** Games' worth of prior each rate leans on ([TestStats.smooth]). */
    private const val PRIOR = 4

    private fun width(variance: Double) = 2 * 1.96 * sqrt(variance.coerceAtLeast(0.0)) * 100

    /** Each kind's own games; a kind with none counts its turn's pooled games, as the rates do. */
    private fun counts(row: TestStats.Row?): IntArray {
        if (row == null) return IntArray(4)
        fun split(r: TestStats.Rate, pooled: TestStats.Rate, other: TestStats.Rate) = if (r.games == 0 && other.games == 0) pooled.games else r.games
        return intArrayOf(
            split(row.preFirst, row.first, row.postFirst),
            split(row.preSecond, row.second, row.postSecond),
            split(row.postFirst, row.first, row.preFirst),
            split(row.postSecond, row.second, row.preSecond),
        )
    }

    /** The match win's slope in the rate of kind [i], read by a small step either side. */
    private fun slope(r: DoubleArray, i: Int): Double {
        val h = 1e-4
        val up = r.copyOf().also { it[i] = (it[i] + h).coerceAtMost(1.0) }
        val down = r.copyOf().also { it[i] = (it[i] - h).coerceAtLeast(0.0) }
        return (TestStats.matchWin(up[0], up[1], up[2], up[3]) - TestStats.matchWin(down[0], down[1], down[2], down[3])) / (up[i] - down[i])
    }

    /** "5 sided games going second against Yubel (−4 points of width)". */
    fun words(step: Step): String =
        "${step.games} ${step.kind.words} against ${step.name} (−${kotlin.math.round(step.gain).toInt().coerceAtLeast(0)} points of width)"
}
