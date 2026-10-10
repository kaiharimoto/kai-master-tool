package com.kaiharimoto.mastertool.core.duel.search.ladder

import com.kaiharimoto.mastertool.core.duel.mapper.train.Elo
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainGate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A player's place on the ladder: its Elo and 95 % range, the games it played and its share of the points in them. */
data class LadderRating(val player: String, val elo: Double, val low: Double, val high: Double, val games: Int, val score: Double) {
    /** Whether the two ranges overlap: if they do, the ladder cannot yet tell the two apart. */
    fun overlaps(o: LadderRating): Boolean = low <= o.high && o.low <= high
}

/**
 * The Bradley–Terry fit (E.md §4.5): each player a strength θ, the chance i beats j being 1 ÷ (1 + e^(θj − θi)), fitted by
 * maximum likelihood over every game, a draw half a win to each. Hunter's minorise–maximise steps converge from anywhere.
 *
 * - **Anchored**: the [anchor] (greedy) stands at [Elo.BASE]; Elo is 400 · log10 of the strength ratio.
 * - **One drawn game against the anchor** is added for every other player, so a clean sweep is a large number with a wide
 *   range rather than infinity, and a player whose games reach the anchor only through others is still placed.
 * - **Ranges** are 95 % from the fit's curvature: the observed information (the likelihood's second derivatives) with the
 *   anchor held fixed, inverted. A player is as uncertain as its games leave it, and the anchor has none.
 */
object BradleyTerry {
    /** [players]' ratings from [games] (each a pair and the first's points: 1, ½ or 0), anchored on [anchor]. */
    fun fit(players: List<String>, games: List<Triple<String, String, Double>>, anchor: String): List<LadderRating> {
        val ids = (listOf(anchor) + players).distinct()
        val n = ids.size
        val at = ids.withIndex().associate { (i, p) -> p to i }
        val count = Array(n) { DoubleArray(n) }
        val won = DoubleArray(n)
        val played = IntArray(n)
        val points = DoubleArray(n)
        for ((a, b, s) in games) {
            val i = at[a] ?: continue
            val j = at[b] ?: continue
            if (i == j) continue
            count[i][j] += 1.0
            count[j][i] += 1.0
            won[i] += s
            won[j] += 1.0 - s
            played[i]++
            played[j]++
            points[i] += s
            points[j] += 1.0 - s
        }
        // The prior: one drawn game between each player and the anchor.
        for (i in 1 until n) {
            count[0][i] += 1.0
            count[i][0] += 1.0
            won[0] += 0.5
            won[i] += 0.5
        }
        val pi = DoubleArray(n) { 1.0 }
        for (step in 0 until MAX_STEPS) {
            var moved = 0.0
            for (i in 0 until n) {
                var d = 0.0
                for (j in 0 until n) if (j != i && count[i][j] > 0) d += count[i][j] / (pi[i] + pi[j])
                val next = if (d > 0) won[i] / d else pi[i]
                moved = maxOf(moved, abs(ln(next) - ln(pi[i])))
                pi[i] = next
            }
            val a = pi[0]
            for (i in 0 until n) pi[i] /= a
            if (moved < 1e-10) break
        }
        val theta = DoubleArray(n) { ln(pi[it]) }
        val se = errors(theta, count)
        val scale = 400.0 / ln(10.0)
        return ids.mapIndexed { i, p ->
            val elo = Elo.BASE + scale * theta[i]
            val half = Z * scale * se[i]
            LadderRating(p, elo, elo - half, elo + half, played[i], if (played[i] == 0) 0.5 else points[i] / played[i])
        }.filter { it.player in players || it.player == anchor }
    }

    /** Each strength's standard error: the information matrix, the anchor's row and column taken out, inverted. */
    private fun errors(theta: DoubleArray, count: Array<DoubleArray>): DoubleArray {
        val n = theta.size
        val m = n - 1
        if (m <= 0) return DoubleArray(n)
        val h = Array(m) { DoubleArray(m) }
        for (i in 1 until n) for (j in 0 until n) {
            if (i == j || count[i][j] == 0.0) continue
            val p = 1.0 / (1.0 + exp(theta[j] - theta[i]))
            val w = count[i][j] * p * (1 - p)
            h[i - 1][i - 1] += w
            if (j > 0) h[i - 1][j - 1] -= w
        }
        val inv = invert(h) ?: return DoubleArray(n) { if (it == 0) 0.0 else Double.POSITIVE_INFINITY }
        return DoubleArray(n) { i -> if (i == 0) 0.0 else sqrt(inv[i - 1][i - 1].coerceAtLeast(0.0)) }
    }

    /** Gauss–Jordan with partial pivoting; null when the matrix is singular. */
    internal fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(m[it][c]) } ?: return null
            if (abs(m[p][c]) < 1e-12) return null
            val t = m[p]; m[p] = m[c]; m[c] = t
            val d = m[c][c]
            for (j in 0 until 2 * n) m[c][j] /= d
            for (r in 0 until n) if (r != c) {
                val f = m[r][c]
                if (f != 0.0) for (j in 0 until 2 * n) m[r][j] -= f * m[c][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> m[i][j + n] } }
    }

    private const val MAX_STEPS = 10_000
    private const val Z = 1.96
}

/**
 * What the gate decided for [candidate] against [parent] on the ladder's seeds (E.md §4.5): a seed is the pair of games
 * dealt by it, the candidate in each seat; it is a win when the candidate took more than half the points (2–0, 1½–½), a loss
 * when fewer, else a tie the sign test leaves out.
 */
data class LadderVerdict(
    val candidate: String,
    val parent: String,
    /** Seeds with both games played. */
    val seeds: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    /** The one-sided sign test's p on the decisive seeds, and the bar it had to clear ([TrainGate.ALPHA] ÷ attempts). */
    val p: Double,
    val bar: Double,
    val pass: Boolean,
    /** The deals and the test behind the verdict, in words: "ahead on 13 deals, behind on none, even on 11 (p < 0.001)". */
    val why: String,
) {
    /** The verdict itself: clearly ahead, not clear yet, or too few deals to say. */
    val words: String
        get() = when {
            seeds < LadderGate.MIN_SEEDS -> "too few deals"
            pass -> "clearly ahead"
            else -> "not clear yet"
        }
}

object LadderGate {
    /** The fewest seeds, both games played, a verdict is made on. */
    const val MIN_SEEDS = 24

    /**
     * Whether [candidate] beats [parent] on [games]' paired seeds; [attempts] is how many candidates were tried since the
     * last promotion, each spending some of the chance of passing by luck.
     */
    fun judge(games: List<LadderGame>, candidate: String, parent: String, attempts: Int = 1): LadderVerdict {
        val bySeed = games.filter { it.players.toSet() == setOf(candidate, parent) && it.players.size == 2 }.groupBy { it.seed }
        var wins = 0
        var losses = 0
        var ties = 0
        var seeds = 0
        for ((_, gs) in bySeed) {
            val first = gs.firstOrNull { it.players[0] == candidate } ?: continue
            val second = gs.firstOrNull { it.players[1] == candidate } ?: continue
            seeds++
            val pts = (first.score(candidate) ?: 0.0) + (second.score(candidate) ?: 0.0)
            when {
                pts > 1.0 -> wins++
                pts < 1.0 -> losses++
                else -> ties++
            }
        }
        val p = TrainGate.signTest(wins, losses)
        val bar = TrainGate.ALPHA / attempts.coerceAtLeast(1)
        val pass = seeds >= MIN_SEEDS && p < bar
        fun n(k: Int) = if (k == 0) "none" else "$k"
        val counts = "ahead on ${n(wins)} deal${if (wins == 1) "" else "s"}, behind on ${n(losses)}, even on ${n(ties)}"
        val why = when {
            seeds < MIN_SEEDS -> "$seeds deal${if (seeds == 1) "" else "s"} played both ways of the $MIN_SEEDS it needs"
            pass -> "$counts (p ${fmt(p)}, below ${fmt(bar)})"
            else -> "$counts (p ${fmt(p)}; it needs below ${fmt(bar)})"
        }
        return LadderVerdict(candidate, parent, seeds, wins, losses, ties, p, bar, pass, why)
    }

    private fun fmt(x: Double): String = if (x < 0.001) "< 0.001" else ((x * 1000).toInt() / 1000.0).toString()
}

/** The ladder read: every player's rating on the bench as it is, and the verdicts the release states. */
data class LadderReading(
    val ratings: List<LadderRating>,
    /** The search in use against greedy, and against its pre-search version. */
    val overGreedy: LadderVerdict,
    val overBlank: LadderVerdict,
    /** Games counted, games still owed, and games kept and not counted (an older bench, or past the ladder's deals). */
    val games: Int,
    val owed: Int,
    val older: Int,
    /** The share of the bench's cards the engine plays. */
    val coverage: Double,
) {
    fun of(player: String): LadderRating? = ratings.firstOrNull { it.player == player }

    companion object {
        fun of(book: LadderBook): LadderReading {
            val on = book.on()
            val games = on.mapNotNull { g -> g.players.takeIf { it.size == 2 }?.let { Triple(it[0], it[1], g.score(it[0]) ?: 0.5) } }
            val players = LadderPlayers.all.map { it.id }.filter { p -> on.any { p in it.players } }
            val ratings = if (players.isEmpty()) emptyList() else BradleyTerry.fit(players, games, LadderPlayers.GREEDY).sortedByDescending { it.elo }
            return LadderReading(
                ratings,
                LadderGate.judge(on, LadderPlayers.IN_USE, LadderPlayers.GREEDY),
                LadderGate.judge(on, LadderPlayers.IN_USE, LadderPlayers.BLANK_FAST),
                on.size,
                LadderRun.owed(book).size,
                book.games.size - on.size,
                Bench.coverage(),
            )
        }
    }
}

/** The ladder in words, for Ai (`duel_ladder`): every rating with its range, the two verdicts, and what stands behind them. */
object LadderWords {
    fun of(r: LadderReading, puzzles: Pair<Int, Int>? = null, puzzleCount: Int = 0): String = buildString {
        appendLine(
            "The ladder: the app's own players on the bench (no connection, nothing spent), each of ${LadderSeeds.COUNT} deals played " +
                "from both seats. ${r.games} games on this bench, ${r.owed} still owed; ${(r.coverage * 100).toInt()} % of the bench's " +
                "cards are written." + if (r.older > 0) " ${r.older} more games are kept and not counted (an older bench, or past the deals)." else "",
        )
        if (r.ratings.isEmpty()) {
            appendLine("Nothing has been played yet: the person plays the ladder from Test scores.")
        } else {
            appendLine("Ratings (Elo, Bradley–Terry, Greedy anchored at 1000, 95 % ranges):")
            r.ratings.forEach { x ->
                val name = LadderPlayers.of(x.player)?.name ?: x.player
                if (x.player == LadderPlayers.GREEDY) appendLine("- $name: 1000, the anchor; ${x.games} games")
                else appendLine("- $name: ${x.elo.roundToInt()} (${x.low.roundToInt()}–${x.high.roundToInt()}), ${x.games} games, ${(x.score * 100).roundToInt()} % of the points")
            }
            appendLine("Against Greedy, by the sign test on paired deals: ${r.overGreedy.words} — ${r.overGreedy.why}.")
            appendLine("Against itself guessing nothing (its pre-search version): ${r.overBlank.words} — ${r.overBlank.why}.")
            appendLine("Two ratings whose ranges overlap cannot yet be told apart.")
        }
        if (puzzles != null) appendLine("A second number, never in the Elo: of $puzzleCount puzzles Greedy solves ${puzzles.first}, the search ${puzzles.second}.")
    }.trimEnd()
}
