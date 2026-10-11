package com.kaiharimoto.mastertool.core.prep

import kotlin.math.abs

/**
 * Predictions scored (Phase F, F.2; `docs/phases/F.md`): every round logged at an event, against what the odds said before
 * it was played — the match win recomputed from the games logged before that round ([TestStats.matchAgainst], the same
 * smoothing the event's reading uses), so nothing had to be stored first. A Brier score per matchup and overall, against a
 * coin's 0.25, with its calibration in bins.
 */
object Backtest {
    /** One round, called before it was played: [said] its match win, [won] how it went, over [before] games logged before it. */
    data class Call(
        val event: String,
        val round: Int,
        val opponent: String,
        val name: String,
        val at: Long,
        val said: Double,
        val won: Boolean,
        val before: Int,
    ) {
        val error: Double get() = (said - (if (won) 1.0 else 0.0)).let { it * it }
    }

    /** Calls said between [from] and [to]: how many, the mean said, and how many went as said. */
    data class Bin(val from: Double, val to: Double, val n: Int, val said: Double, val won: Double)

    /** One opponent's rounds scored. */
    data class Matchup(val opponent: String, val name: String, val calls: Int, val brier: Double, val said: Double, val won: Double)

    data class Score(
        val calls: List<Call>,
        /** The mean squared gap between what was said and what happened (0 perfect; a coin's 0.25). */
        val brier: Double,
        /** Brier's skill against always saying a half: above 0 better than the coin, below 0 worse. */
        val skill: Double,
        /** How far, on average, a bin's said sits from what happened in it. */
        val ece: Double,
        val bins: List<Bin>,
        val matchups: List<Matchup>,
        /** Rounds left out: neither won nor lost (a draw, or games logged even). */
        val undecided: Int,
    ) {
        val n: Int get() = calls.size
    }

    /** The bins' edges: most calls sit near a half, so the middle is split finer. */
    val EDGES = listOf(0.0, 0.35, 0.5, 0.65, 1.0000001)

    /** A coin's Brier score. */
    const val COIN = 0.25

    /**
     * Every round at an event in [games] (the ledger's games, `MatchupLedger.select`), each called from the games of the same
     * deck against the same opponent logged before its first game; [deckId] keeps one deck's.
     */
    fun rounds(games: List<TestGame>, deckId: String? = null): Pair<List<Call>, Int> {
        val mine = games.filter { deckId == null || it.deckId == deckId }
        val rounds = mine.filter { it.eventId != null && it.round != null }.groupBy { Triple(it.eventId!!, it.round!!, it.opponent) }
        var undecided = 0
        val calls = rounds.mapNotNull { (key, list) ->
            val (event, round, opponent) = key
            val wins = list.count { it.result == TestGame.WIN }
            val losses = list.count { it.result == TestGame.LOSS }
            if (wins == losses) {
                undecided++
                return@mapNotNull null
            }
            val start = list.minOf { it.at }
            val deck = list.first().deckId
            val before = mine.filter { it.at < start && it.opponent == opponent && it.deckId == deck }
            val row = TestStats.matrix(before).firstOrNull { it.opponent == opponent }
            Call(event, round, opponent, list.maxByOrNull { it.at }?.opponentName?.ifBlank { null } ?: opponent, start, TestStats.matchAgainst(row), wins > losses, before.size)
        }.sortedBy { it.at }
        return calls to undecided
    }

    fun score(games: List<TestGame>, deckId: String? = null): Score {
        val (calls, undecided) = rounds(games, deckId)
        return score(calls, undecided)
    }

    fun score(calls: List<Call>, undecided: Int = 0): Score {
        if (calls.isEmpty()) return Score(emptyList(), 0.0, 0.0, 0.0, emptyList(), emptyList(), undecided)
        val brier = calls.sumOf { it.error } / calls.size
        val bins = EDGES.zipWithNext().map { (from, to) ->
            val inBin = calls.filter { it.said >= from && it.said < to }
            Bin(
                from, to.coerceAtMost(1.0), inBin.size,
                if (inBin.isEmpty()) 0.0 else inBin.sumOf { it.said } / inBin.size,
                if (inBin.isEmpty()) 0.0 else inBin.count { it.won }.toDouble() / inBin.size,
            )
        }
        val ece = bins.sumOf { b -> b.n.toDouble() / calls.size * abs(b.said - b.won) }
        val matchups = calls.groupBy { it.opponent }.map { (o, cs) ->
            Matchup(o, cs.last().name, cs.size, cs.sumOf { it.error } / cs.size, cs.sumOf { it.said } / cs.size, cs.count { it.won }.toDouble() / cs.size)
        }.sortedWith(compareByDescending<Matchup> { it.calls }.thenBy { it.name.lowercase() })
        return Score(calls, brier, 1 - brier / COIN, ece, bins, matchups, undecided)
    }

    /** The score in words (Prep and Ai's `prep_state`): the overall Brier against the coin, then each matchup's. */
    fun words(s: Score): String = buildString {
        if (s.n == 0) {
            append("No round logged at an event yet: once one is, what the odds said before it is scored against how it went.")
            return@buildString
        }
        append("${s.n} ${if (s.n == 1) "round" else "rounds"} called before they were played: Brier ${dec(s.brier)} against a coin's 0.25")
        append(
            when {
                s.n < ENOUGH -> " — too few rounds to say whether the odds are better than a coin."
                s.skill > 0 -> " — better than a coin by ${pct(s.skill)}."
                else -> " — no better than a coin."
            },
        )
        if (s.undecided > 0) append(" ${s.undecided} ${if (s.undecided == 1) "round was" else "rounds were"} neither won nor lost and are not counted.")
        s.matchups.forEach { m ->
            append("\n- ${m.name}: ${m.calls} ${if (m.calls == 1) "round" else "rounds"}, said ${pct(m.said)} on average, won ${pct(m.won)}; Brier ${dec(m.brier)}")
        }
        val filled = s.bins.filter { it.n > 0 }
        if (filled.isNotEmpty()) {
            append("\nCalibration: ")
            append(filled.joinToString("; ") { b -> "said ${pct(b.from)}–${pct(b.to)}: ${b.n} called at ${pct(b.said)}, won ${pct(b.won)}" })
        }
    }

    /** Fewer rounds than this and the score is said, never judged. */
    const val ENOUGH = 10

    private fun pct(x: Double) = "${kotlin.math.round(x * 100).toInt()}%"

    private fun dec(x: Double) = (kotlin.math.round(x * 1000) / 1000.0).toString()
}
