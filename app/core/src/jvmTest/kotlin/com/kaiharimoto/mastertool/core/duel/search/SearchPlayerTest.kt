package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.match.MatchTable
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The search as a seat (Phase E, E.3; E.md §4.4): a match played through its door on the reference cards. */
class SearchPlayerTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }))

    /** A seat that goes second, ends each of its turns and passes every window: the other side of the table, quietly. */
    private class Quiet : MatchPlayer {
        private var n = 0
        override suspend fun cue(text: String, tools: ToolRunner): CueResult {
            val op = when {
                "· choose]" in text -> "go second"
                "· play]" in text -> "end"
                "· resolve]" in text -> "resolve"
                "· answer]" in text -> "accept"
                else -> "pass"
            }
            tools.run(Part.ToolUse("q${n++}", "duel_act", buildJsonObject { putJsonArray("ops") { add(op) } }))
            return CueResult()
        }
    }

    /** Two search seats on [seed]'s table: the game they played, and how it ended. */
    private suspend fun searchVsSearch(seed: Long): Pair<DuelGame, String> {
        val a = List(8) { FxRef.SCOUT } + List(8) { FxRef.PAWN } + List(6) { FxRef.KNIGHT } + List(4) { FxRef.WARD } + List(4) { FxRef.LAMP }
        val b = List(10) { FxRef.SCOUT } + List(10) { FxRef.PAWN } + List(6) { FxRef.WARD } + List(4) { FxRef.KNIGHT }
        val seats = listOf(
            MatchSeat("Search A", null, "Example A", a, connection = "Search", model = "${SearchPlayer.VERSION} · Fast"),
            MatchSeat("Search B", null, "Example B", b, connection = "Search", model = "${SearchPlayer.VERSION} · Fast"),
        )
        val table = MatchTable(DuelGame.start(MatchTable.header("search-$seed", seed, seats, 0)), kit.catalog, MatchRules(turnCap = 4, paceMs = 0))
        // Neither seat is shown the other's list: each guesses the other's hand from the same field.
        val field = Prior.Interaction(mapOf(FxRef.WARD to 0.15))
        val players = listOf(SearchPlayer({ field }, SearchPlayer.Strength.FAST), SearchPlayer({ field }, SearchPlayer.Strength.FAST))
        val engines = seats.map { DuelResults.Engine(it.connection, it.model) }
        val end = AiMatch(table, players, engines, kit = kit).run()
        return table.game to end.words
    }

    @Test
    fun twoSearchSeatsPlayAMatchToTheEndAndTheSameSeedGivesTheSameGame() = runTest {
        val (one, words) = searchVsSearch(21L)
        val (two, _) = searchVsSearch(21L)
        assertTrue(one.played.isNotEmpty(), words)
        assertTrue(words.isNotBlank())
        // Both seats moved, each only its own seat, every move the search's.
        val moved = one.played.filter { it.by?.byAi == true }
        assertTrue(moved.any { it.seat == 0 } && moved.any { it.seat == 1 }, words)
        assertTrue(moved.all { it.seat == it.by?.aiSeat && it.by?.engine == SearchPlayer.VERSION })
        // Never stuck: no turn of a search seat is ended for it by the table.
        val notes = one.played.mapNotNull { (it.action as? DuelAction.Note)?.text }
        assertTrue(notes.none { "without ending its turn" in it || "made no move" in it }, notes.joinToString("\n"))
        // Deterministic: the same seed, the same game, move for move.
        assertEquals(one.played.map { it.seat to it.action }, two.played.map { it.seat to it.action })
        assertEachMonsterAttacksOnceATurn(one)
    }

    @Test
    fun aSearchSeatPlaysAMatchThroughItsDoorAndItsMovesSaySo() = runTest {
        val mine = List(8) { FxRef.SCOUT } + List(8) { FxRef.PAWN } + List(6) { FxRef.KNIGHT } + List(4) { FxRef.WARD } + List(4) { FxRef.LAMP }
        val theirs = List(30) { FxRef.PAWN }
        val seats = listOf(
            MatchSeat("Search", null, "Example", mine, connection = "Search", model = "${SearchPlayer.VERSION} · Fast"),
            MatchSeat("Quiet", null, "Pawns", theirs, connection = "Script", model = "quiet"),
        )
        val table = MatchTable(DuelGame.start(MatchTable.header("search-1", 11L, seats, 0)), kit.catalog, MatchRules(turnCap = 4, paceMs = 0))
        val search = SearchPlayer({ Prior.Decklist(theirs) }, SearchPlayer.Strength.FAST)
        val engines = listOf(DuelResults.Engine("Search", "${SearchPlayer.VERSION} · Fast"), DuelResults.Engine("Script", "quiet"))
        val end = AiMatch(table, listOf(search, Quiet()), engines, kit = kit).run()
        assertNotNull(end.result, end.words)
        val played = table.game.played
        val made = played.filter { it.by?.engine == SearchPlayer.VERSION }
        // The search seat moved, only its own seat, and made engine moves with their tags.
        assertTrue(made.isNotEmpty(), end.words)
        assertTrue(made.all { it.seat == it.by?.aiSeat }, "no move of the other seat's")
        assertTrue(made.any { it.fx != null }, "engine moves, tagged: ${made.map { it.action::class.simpleName }}")
        assertNotNull(search.last)
        // It played its turns: a summon, an effect resolved by the engine, an attack.
        assertTrue(made.count { it.fx != null } >= 5, "${made.map { it.action::class.simpleName }}")
        assertTrue(made.any { it.action is DuelAction.Attack }, "${made.map { it.action::class.simpleName }}")
        val notes = played.mapNotNull { (it.action as? DuelAction.Note)?.text }
        assertTrue(notes.none { "without ending its turn" in it || "made no move" in it }, notes.joinToString("\n"))
        // Its attacks are fought (E.4): the battle's damage is its own move, and the quiet seat's life points fall.
        assertTrue(made.any { (it.action as? DuelAction.Lp)?.seat == 1 }, "${made.map { it.action::class.simpleName }}")
        assertTrue(table.state.seats[1].lp < 8000)
        assertEachMonsterAttacksOnceATurn(table.game)
    }

    /** Within each turn, no monster declares two attacks (E.4: one whose target was destroyed attacked again). */
    private fun assertEachMonsterAttacksOnceATurn(g: DuelGame) {
        var turn = 0
        val seen = HashSet<Pair<Int, Int>>()
        for (e in g.played) {
            when (val a = e.action) {
                DuelAction.EndTurn -> turn++
                is DuelAction.Attack -> assertTrue(seen.add(turn to a.attacker), "monster ${a.attacker} attacked twice in turn ${turn + 1}")
                else -> {}
            }
        }
    }
}
