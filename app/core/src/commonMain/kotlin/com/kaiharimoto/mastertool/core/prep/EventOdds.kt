package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.web.WebEntry
import com.kaiharimoto.mastertool.core.world.MatchMath
import kotlin.math.round

/**
 * Your odds at the event, in one reading (Phase G, G.5; the red team's M1, M3, M4): the match win to expect against the
 * field with its 95 % range and the games behind it, the chance of making the cut at that rate, the games to practise next,
 * and the roll's call per opponent. Prep's plan and practice, Format's event and Ai's `expected_winrate` all read this, so
 * a number is the same wherever it is shown.
 */
object EventOdds {
    class Reading(
        val rows: List<TestStats.Row>,
        val shares: Map<String, Int>,
        /** The expected match win and its 95 % range; null with no shares to weigh. */
        val interval: MatchMath.Interval?,
        /** Decided games logged with this deck. */
        val games: Int,
        val other: TestStats.Other?,
        val swiss: Policy.Swiss?,
        /** The chance of making the cut at [interval]'s point, and the record that makes it; null with no cut or no players. */
        val cut: Double?,
        val cutRecord: String?,
        val next: PracticePlan.Step?,
        /** Each opponent's Game 1 call on a won roll, by key. */
        val calls: Map<String, TestStats.TurnCall>,
    )

    /**
     * [games] (practice: no round) played with [mine] against the web's [entries], for [event]. [mineName] folds games
     * logged against the deck by name into the mirror ([TestStats.mirrored]).
     */
    fun read(games: List<TestGame>, entries: List<WebEntry>, event: PrepEvent, mine: String?, mineName: String?, draws: Int = 4000): Reading {
        val played = games.filter { it.round == null && (mine == null || it.deckId == mine) }
        val rows = TestStats.matrix(TestStats.mirrored(played, mine, listOfNotNull(mineName)))
        val shares = TestStats.field(entries)
        val other = event.otherShare.takeIf { it > 0 }?.let { TestStats.Other(it, event.otherWin.coerceIn(0, 100) / 100.0) }
        val interval = if (shares.isEmpty()) null else MatchMath.field(rows, shares, draws = draws, other = other, timed = event.countTime)
        val swiss = event.attendance.takeIf { it > 0 }?.let { Policy.swiss(event.tier, it) }
        val cut = if (interval != null && swiss != null) Policy.cutChance(interval.point, swiss, event.attendance) else null
        val record = swiss?.let { Policy.cutRecord(it, event.attendance) }
        val next = PracticePlan.next(rows, shares, other = other)
        val calls = rows.associate { it.opponent to TestStats.turnCall(it) }
        return Reading(rows, shares, interval, played.count { it.result != TestGame.DRAW }, other, swiss, cut, record, next, calls)
    }

    fun pct(x: Double): String = "${round(x * 100).toInt()}%"

    /** "49% (41–58%), 37 games": the point, its range and the games behind it. */
    fun words(i: MatchMath.Interval, games: Int): String = "${pct(i.point)} (${pct(i.low)}–${pct(i.high)}), $games ${if (games == 1) "game" else "games"}"

    /** "Win the roll: go second (83% sure, 14 games)", or why not yet. */
    fun callWords(call: TestStats.TurnCall): String = when (call.choice()) {
        false -> "go second (${pct(call.secondBetter)} sure, ${call.games} games)"
        true -> "go first (${pct(1 - call.secondBetter)} sure, ${call.games} games)"
        null -> if (call.games == 0) "no Game 1s yet" else "too close to call (${call.games} games)"
    }
}
