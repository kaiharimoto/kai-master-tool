package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.world.Instruments.pct
import com.kaiharimoto.mastertool.core.world.Study.Companion.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.round

/**
 * Logged games as matchups (1.0.97): how often each opponent was beaten, how sure that is, what is clear and what is
 * noise, why games were lost, and the match win to expect against a field. One deck's games unless asked for all —
 * a table of two decks' games is a wrong table.
 */
internal object MatchupInstrument {
    const val FEW = 10

    fun matchups(args: JsonObject, host: WorldHost): Instruments.Result {
        val asked = args.str("deck")?.trim()
        val all = asked.equals("all", ignoreCase = true)
        val entry = when {
            all -> null
            asked == null || asked.equals("open", ignoreCase = true) -> host.deck(null)
            else -> host.deck(asked) ?: host.decks().firstOrNull { it.name.equals(asked, ignoreCase = true) }
                ?: throw IllegalArgumentException("no deck “$asked”: give a deck's id or name, 'open', or 'all' for every deck's games")
        }
        val games = host.games().filter { all || entry == null || it.deckId == entry.id }
        require(games.isNotEmpty()) {
            if (entry != null) "no practice games are logged with “${entry.name}” yet (Prep, or log_game); deck: 'all' reads every deck's" else "no practice games are logged yet (Prep, or log_game)"
        }
        val draws = games.count { it.result == TestGame.DRAW }
        val rows = TestStats.matrix(games).filter { it.all.games > 0 }
        require(rows.isNotEmpty()) { "only draws are logged: nothing to rate" }
        val s = Study()
        val who = if (entry != null) "“${entry.name}”" else "every deck"
        s.say("matchups: $who — ${games.size} game${if (games.size == 1) "" else "s"} against ${rows.size} opponent${if (rows.size == 1) "" else "s"}. Which matchups are won, and how sure is that?")
        s.say("  method: each rate shrunk toward 50 % by four games' worth (Prep's own prior); best of three from going first and second, the loser choosing, with a 95 % interval over both rates")
        if (draws > 0) s.warn("$draws draw${if (draws == 1) "" else "s"} counted in no rate")
        data class Line(val row: TestStats.Row, val shrunk: Double, val bo3: MatchMath.Interval?)
        val lines = rows.map { r ->
            val bo3 = if (r.first.games + r.second.games > 0) MatchMath.bestOfThree(r.first, r.second) else null
            Line(r, MatchMath.shrunk(r.all), bo3)
        }
        lines.forEach { l ->
            val verdict = verdict(l.bo3, l.row.all.games)
            s.say("  ${l.row.name}: ${l.row.all.wins}/${l.row.all.games} (${pct(raw(l.row.all))}, shrunk ${pct(l.shrunk)})" +
                (l.bo3?.let { ", best of three ${pct(it.point)} (${pct(it.low)}–${pct(it.high)})" } ?: "") + " — $verdict")
        }
        val clear = lines.count { it.bo3 != null && (it.bo3.low > 0.5 || it.bo3.high < 0.5) }
        if (rows.size >= 5) s.say("  with ${rows.size} matchups, about ${round(rows.size * 0.05 * 10) / 10} would look clear by chance alone at 95 %: $clear do")
        // Why games were lost.
        val reasons = games.filter { it.result == TestGame.LOSS }.groupingBy { it.reason ?: "not said" }.eachCount()
        if (reasons.isNotEmpty()) s.say("  losses by reason: " + reasons.entries.sortedByDescending { it.value }.joinToString { "${it.value} ${it.key.lowercase()}" })
        // The field.
        val shares: Map<String, Int> = (args["shares"] as? JsonObject)?.mapNotNull { (k, v) ->
            val n = (v as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return@mapNotNull null
            (rows.firstOrNull { it.opponent.equals(k, true) || it.name.equals(k, true) }?.opponent ?: k) to n
        }?.toMap() ?: host.field(entry?.id)
        val field = shares.takeIf { it.isNotEmpty() }?.let { MatchMath.field(rows, it) }
        field?.let { f -> s.say("  against the field (${shares.size} decks): ${pct(f.point)} match win (${pct(f.low)}–${pct(f.high)}, 4,000 seeded draws, seed 1); an opponent never played counts as 50 %") }

        val cols = listOf("Going first", "Going second", "Before siding", "After siding", "All")
        s.board("matchups-heat", "Matchups, win %", BoardKind.CHART, WorldChart.encode(WorldChart.Heatmap("", rows.map { it.name }, cols,
            rows.map { r -> listOf(r.first, r.second, r.preSide, r.postSide, r.all).map { rate -> if (rate.games == 0) Double.NaN else round(raw(rate) * 1000) / 10 } }, "%")),
            "Raw rates from the games logged in Prep; an empty cell has no games.")
        s.table("matchups-table", "Matchups, and how sure", listOf("Opponent", "Won", "Raw", "Shrunk", "Best of three (95%)", "Verdict"),
            lines.map { l -> listOf(l.row.name, "${l.row.all.wins}/${l.row.all.games}", pct(raw(l.row.all)), pct(l.shrunk), l.bo3?.let { "${pct(it.point)} (${pct(it.low)}–${pct(it.high)})" } ?: "—", verdict(l.bo3, l.row.all.games)) },
            "Shrunk toward 50 % by a Beta(2, 2) prior; best of three with the loser choosing, its 95 % interval integrated over both rates. A verdict needs the interval to leave 50 %.")
        field?.let { f -> s.stat("matchups-field", "Against the field", pct(f.point), "match win to expect", "95 %: ${pct(f.low)}–${pct(f.high)}", "TestStats.expected for the point; the interval from 4,000 seeded draws of every rate (seed 1).") }
        return s.done(obj(
            "games" to JsonPrimitive(games.size),
            "draws" to JsonPrimitive(draws),
            "matchups" to JsonArray(lines.map { l ->
                obj("opponent" to JsonPrimitive(l.row.name), "wins" to JsonPrimitive(l.row.all.wins), "games" to JsonPrimitive(l.row.all.games), "shrunk" to JsonPrimitive(l.shrunk),
                    "bo3" to l.bo3?.let { JsonPrimitive(it.point) }, "low" to l.bo3?.let { JsonPrimitive(it.low) }, "high" to l.bo3?.let { JsonPrimitive(it.high) })
            }),
            "field" to field?.let { obj("point" to JsonPrimitive(it.point), "low" to JsonPrimitive(it.low), "high" to JsonPrimitive(it.high)) },
        ))
    }

    private fun raw(r: TestStats.Rate) = if (r.games == 0) Double.NaN else r.wins.toDouble() / r.games

    private fun verdict(bo3: MatchMath.Interval?, games: Int): String = when {
        bo3 == null -> "no games going first or second"
        bo3.low > 0.5 -> "favoured" + if (games < FEW) " (few games)" else ""
        bo3.high < 0.5 -> "unfavoured" + if (games < FEW) " (few games)" else ""
        games < FEW -> "too few games to say"
        else -> "close: the games cannot tell"
    }
}
