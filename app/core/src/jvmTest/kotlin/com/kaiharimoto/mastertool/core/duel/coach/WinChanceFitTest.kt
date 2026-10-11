package com.kaiharimoto.mastertool.core.duel.coach

import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.match.MatchTable
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import com.kaiharimoto.mastertool.core.duel.search.SearchVersions
import com.kaiharimoto.mastertool.core.duel.search.TurnValue
import com.kaiharimoto.mastertool.core.duel.search.ladder.Bench
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderSeeds
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * WinChance's fit (F.1), measured: only with `NEUE_FIT=1`. Search v0 at Fast plays itself on the ladder's deals from both
 * seats; each decision's chosen value for the turn player is kept with whether that player won, and a logistic is fitted by
 * Newton's method. The constants it prints go into [WinChance].
 */
class WinChanceFitTest {
    @Test
    fun fit() = runTest(timeout = 300.minutes) {
        if (System.getenv("NEUE_FIT") != "1") return@runTest
        val points = ArrayList<Pair<Double, Double>>()
        for (seed in LadderSeeds.all) for (flip in listOf(false, true)) {
            val decks = listOf(seed.decks.first, seed.decks.second).map { Bench.deck(it)!! }.let { if (flip) it.reversed() else it }
            val seats = decks.mapIndexed { i, d -> MatchSeat("Search ${i + 1}", null, d.name, d.main, d.extra, "Search", "search-v0 · Fast") }
            val table = MatchTable(DuelGame.start(MatchTable.header("fit-${seed.n}-$flip", seed.seed, seats, 0)), Bench.kit.catalog, MatchRules(turnCap = 10, paceMs = 0, cueMillis = 86_400_000))
            val seen = ArrayList<Pair<Int, Double>>()
            val players = (0..1).map { _ ->
                SearchPlayer({ Bench.prior }, SearchPlayer.Strength.FAST, version = SearchVersions.V0, decided = { d, s ->
                    val mean = d.choice?.mean
                    if (mean != null && abs(mean) < TurnValue.WIN / 2) seen += s.view.active to mean
                })
            }
            val end = AiMatch(table, players, seats.map { DuelResults.Engine(it.connection, it.model) }, kit = Bench.kit).run()
            val w = end.result?.winner
            for ((active, v) in seen) points += v to (if (w == null) 0.5 else if (w == active) 1.0 else 0.0)
            println("WinChanceFitTest: seed ${seed.n} flip $flip winner $w decisions ${seen.size}")
        }
        // Logistic regression, y in [0, 1], by Newton's method on a and b.
        var a = 0.0
        var b = 0.1
        repeat(50) {
            var ga = 0.0
            var gb = 0.0
            var haa = 0.0
            var hab = 0.0
            var hbb = 0.0
            for ((v, y) in points) {
                val p = 1 / (1 + exp(-(a + b * v)))
                val w = p * (1 - p)
                ga += y - p
                gb += (y - p) * v
                haa += w
                hab += w * v
                hbb += w * v * v
            }
            val det = haa * hbb - hab * hab
            if (abs(det) < 1e-12) return@repeat
            a += (hbb * ga - hab * gb) / det
            b += (-hab * ga + haa * gb) / det
        }
        val ll = points.sumOf { (v, y) -> val p = (1 / (1 + exp(-(a + b * v)))).coerceIn(1e-9, 1 - 1e-9); y * ln(p) + (1 - y) * ln(1 - p) }
        val brier = points.sumOf { (v, y) -> val p = 1 / (1 + exp(-(a + b * v))); (p - y) * (p - y) } / points.size
        val base = points.sumOf { (_, y) -> (0.5 - y) * (0.5 - y) } / points.size
        println("WinChanceFitTest: n ${points.size} A $a B $b log-loss ${-ll / points.size} brier $brier (a coin's $base)")
    }
}
