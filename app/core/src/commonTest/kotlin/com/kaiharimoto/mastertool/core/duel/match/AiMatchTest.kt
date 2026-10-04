package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.record.ResultSeat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ai vs Ai (`docs/phases/C.md` §6): the referee of two sessions, one a seat — the opening roll and the winner's choice,
 * whose move it is (the turn player, a response window's responder, the seat that resolves its link), a seat that stalls
 * having its turn ended for it, a session that keeps failing forfeiting, the turn cap ending it as a draw by limit — and
 * each seat told only what its own seat sees. Scripted players stand in for the models.
 */
class AiMatchTest {
    private val catalog = DuelCatalog { code ->
        when (code) {
            in 1..8 -> DuelCardInfo("Alpha Knight $code", CardKind.MONSTER, atk = 1000 + code * 100, def = 1000, level = 4)
            9 -> DuelCardInfo("Alpha Charm", CardKind.SPELL, sub = "Normal")
            in 101..108 -> DuelCardInfo("Beta Dragon ${code - 100}", CardKind.MONSTER, atk = 1000 + (code - 100) * 100, def = 1200, level = 4)
            109 -> DuelCardInfo("Beta Charm", CardKind.SPELL, sub = "Normal")
            else -> null
        }
    }

    private val alphaNames = (1..8).map { "Alpha Knight $it" } + "Alpha Charm"
    private val betaNames = (1..8).map { "Beta Dragon $it" } + "Beta Charm"

    private fun seats(a: List<Int>, b: List<Int>) = listOf(
        MatchSeat("Opus", "da", "Alpha", a, connection = "Anthropic", model = "claude-opus-5-5"),
        MatchSeat("Gpt", "db", "Beta", b, connection = "OpenAI", model = "gpt-x"),
    )

    private val monstersA = (1..8).flatMap { listOf(it, it, it, it, it) }
    private val monstersB = (101..108).flatMap { listOf(it, it, it, it, it) }

    private fun table(rules: MatchRules, a: List<Int> = monstersA, b: List<Int> = monstersB, seed: Long = 7): MatchTable {
        val ss = seats(a, b)
        return MatchTable(DuelGame.start(MatchTable.header("m$seed", seed, ss, 0)), catalog, rules)
    }

    private fun engines() = listOf(DuelResults.Engine("Anthropic", "claude-opus-5-5"), DuelResults.Engine("OpenAI", "gpt-x"))

    /** A scripted player: what it is told and given back is kept, every word, as a model would read it. */
    private class Bot(val act: suspend Bot.(String) -> Unit) : MatchPlayer {
        val heard = mutableListOf<String>()
        val cues = mutableListOf<String>()
        var turnsActed = mutableSetOf<Int>()
        private lateinit var tools: ToolRunner
        private var n = 0

        suspend fun call(name: String, vararg ops: String): String {
            val input = buildJsonObject {
                if (ops.isNotEmpty()) putJsonArray("ops") { ops.forEach { add(it) } }
            }
            return tools.run(Part.ToolUse("t${n++}", name, input)).content.also { heard += it }
        }

        suspend fun cards(vararg names: String): String {
            val input = buildJsonObject { putJsonArray("cards") { names.forEach { add(it) } } }
            return tools.run(Part.ToolUse("t${n++}", "card_info", input)).content.also { heard += it }
        }

        override suspend fun cue(text: String, tools: ToolRunner): CueResult {
            heard += text
            cues += text.lineSequence().first()
            this.tools = tools
            act(text)
            return CueResult(tokens = 1_000)
        }
    }

    private fun turnOf(text: String): Int = Regex("turn (\\d+)").find(text)!!.groupValues[1].toInt()

    /** Goes first when it wins, summons a monster face-up and ends each of its turns; never responds. */
    private fun summoner() = Bot { text ->
        when {
            "· choose]" in text -> call("duel_act", "go first")
            "· play]" in text -> {
                val menu = call("duel_moves")
                val summon = Regex("`(s h\\d)`").find(menu)?.groupValues?.get(1)
                if (summon != null) call("duel_act", "m1", summon, "end") else call("duel_act", "end")
            }
        }
    }

    @Test
    fun theOpeningRollIsThrownTheWinnerChoosesAndTheTurnCapEndsItAsADrawByLimit() = runTest {
        val t = table(MatchRules(turnCap = 3, paceMs = 0))
        // Before anything, the referee throws: each seat's two dice, by the table, never a cue.
        val first = MatchReferee.next(t.game, MatchMemo(), t.rules)
        assertTrue(first is MatchReferee.Next.Table && first.actions.single() is DuelAction.OpeningRoll, first.toString())
        val a = summoner()
        val b = summoner()
        val end = AiMatch(t, listOf(a, b), engines()).run()
        val r = assertNotNull(end.result)
        assertEquals(DuelResult.AI_VS_AI, r.kind)
        assertEquals(DuelResult.LIMIT, r.how)
        assertNull(r.winner, "a draw by limit")
        assertEquals(4, r.turns)
        assertTrue(r.said.orEmpty().contains("turn 4 reached"), r.said)
        assertEquals(listOf("claude-opus-5-5", "gpt-x"), r.seats.map { it.engine })
        assertEquals(listOf("Anthropic", "OpenAI"), r.seats.map { it.connection })
        assertEquals(DuelResult.ROLL, r.firstBy)
        assertTrue(r.rolls.isNotEmpty())
        assertEquals(Provenance.AI, r.chosenBy, "the winner's own session chose")
        assertEquals(r.rollWinner, r.first, "it chose to go first")
        assertEquals(7L, r.seed)
        assertNull(r.ai, "no seat is a person's")
        // Each seat's moves are its own session's, and the table's draws are the table's.
        assertTrue(t.game.played.filter { it.by?.byAi == true }.all { it.seat == it.by?.aiSeat }, "no session moved the other's seat")
        assertTrue(t.game.played.any { it.action is DuelAction.Draw && it.by?.by == Provenance.TABLE && it.seat != null }, "turns draw for themselves")
        // The winner's cue was "choose"; turn 1's player played first; each cue said whose turn it was.
        val chooser = if (r.rollWinner == 0) a else b
        assertTrue(chooser.cues.first().endsWith("· choose]"), chooser.cues.toString())
        // Counted apart: never against a person; read as Ai vs Ai.
        assertTrue(DuelResults.aiAgainst(listOf(r)).isEmpty())
        assertEquals("Ai vs Ai: claude-opus-5-5 and gpt-x won 0 each of 1 (1 drawn; going first won 0).", DuelResults.summary(listOf(r)))
    }

    @Test
    fun aSeatThatMakesNoMoveHasItsTurnEndedAndNoChoiceGoesFirst() = runTest {
        val t = table(MatchRules(turnCap = 2, stalls = 2, paceMs = 0))
        val idle = { Bot { } }
        val end = AiMatch(t, listOf(idle(), idle()), engines()).run()
        val notes = t.game.played.mapNotNull { (it.action as? DuelAction.Note)?.text }
        assertTrue(notes.any { "made no choice: it goes first, the default" in it }, notes.toString())
        assertTrue(notes.count { "made no move for 2 cues: the table ends its turn" in it } >= 2, notes.toString())
        assertEquals(DuelResult.LIMIT, end.result?.how)
        // Ended by the table, as the table: never an Ai move.
        assertTrue(t.game.played.filter { it.action == DuelAction.EndTurn }.all { it.by?.by == Provenance.TABLE })
    }

    @Test
    fun aResponseWindowCuesTheOtherSeatAndBothPassingResolvesTheChain() = runTest {
        val spellsA = List(40) { 9 }
        val spellsB = List(40) { 109 }
        val t = table(MatchRules(turnCap = 2, windows = Windows.ACTIVATIONS, paceMs = 0), spellsA, spellsB)
        fun activator() = Bot { text ->
            val turn = turnOf(text)
            when {
                "· choose]" in text -> call("duel_act", "go first")
                "· resolve]" in text -> call("duel_act", "resolve")
                "· play]" in text && turn !in turnsActed -> {
                    turnsActed += turn
                    val menu = call("duel_moves")
                    val activate = Regex("`(a h\\d)`").find(menu)?.groupValues?.get(1)
                    assertNotNull(activate, menu)
                    // A window opens on the activation: the next op waits for it, and the session is told to stop.
                    val said = call("duel_act", "m1", activate, "end")
                    assertTrue("A response window is open" in said, said)
                }
                "· play]" in text -> call("duel_act", "end")
                // Cued to respond: it passes by moving nothing.
            }
        }
        val a = activator()
        val b = activator()
        AiMatch(t, listOf(a, b), engines()).run()
        val starter = t.game.played.first { it.action is DuelAction.GoFirst }.seat!!
        val other = if (starter == 0) b else a
        val first = if (starter == 0) a else b
        assertTrue(other.cues.any { it.endsWith("· respond]") }, other.cues.toString())
        assertTrue(first.cues.any { it.endsWith("· resolve]") }, first.cues.toString())
        val notes = t.game.played.mapNotNull { (it.action as? DuelAction.Note)?.text }
        assertTrue(notes.any { it.endsWith("passed.") }, notes.toString())
        // The chain resolved and the Normal Spell went to the GY with it.
        assertTrue(t.state.seats[starter].gy.isNotEmpty())
        assertTrue(t.state.chain.isEmpty())
    }

    @Test
    fun aSessionThatKeepsFailingForfeits() = runTest {
        val t = table(MatchRules(turnCap = 10, failures = 3, paceMs = 0))
        val broken = object : MatchPlayer {
            override suspend fun cue(text: String, tools: ToolRunner) = CueResult(failed = "the provider is down")
        }
        val end = AiMatch(t, listOf(summoner(), broken), engines()).run()
        val r = assertNotNull(end.result)
        assertEquals(0, r.winner)
        assertEquals(DuelResult.CONCEDE, r.how)
        assertTrue(r.said.orEmpty().contains("failed 3 times in a row"), r.said)
        assertTrue(r.said.orEmpty().contains("forfeits"), r.said)
        assertEquals(1, t.state.conceded)
    }

    @Test
    fun theSummaryReadsByModelAndTheCostIsSaidAndCapped() {
        fun r(winner: Int?, first: Int, a: String = "claude-opus-5-5", b: String = "gpt-x") = DuelResult(
            id = "r$winner$first$a", seats = listOf(
                ResultSeat("A", model = a, player = Provenance.AI),
                ResultSeat("B", model = b, player = Provenance.AI),
            ),
            winner = winner, first = first, kind = DuelResult.AI_VS_AI, how = if (winner == null) DuelResult.LIMIT else DuelResult.LP,
        )
        // kai's words: "claude-opus-5-5 beat gpt-x 3 of 5 (going first won 4)", whichever seat each sat at.
        val five = listOf(r(0, 0), r(0, 0), r(1, 1, a = "gpt-x", b = "claude-opus-5-5"), r(1, 1), r(null, 0))
        assertEquals("Ai vs Ai: claude-opus-5-5 beat gpt-x 3 of 5 (1 drawn; going first won 4).", DuelResults.summary(five).lines().single())
        assertEquals("Ai vs Ai: claude-opus-5-5 against itself, two sessions, 1 played (going first won 1).", DuelResults.matchWords(DuelResults.aiVsAi(listOf(r(0, 0, b = "claude-opus-5-5"))).single()))
        // Said before it starts: cues to the cap at a cue's cost, never past the budget.
        val c = AiMatch.cost(MatchRules(turnCap = 12, tokenCap = 500_000))
        assertEquals(50, c.cues)
        assertEquals(50 * AiMatch.TOKENS_PER_CUE, c.estimate)
        assertEquals(500_000L, c.tokens)
        assertTrue(c.capped)
        assertTrue(!AiMatch.cost(MatchRules(turnCap = 6, tokenCap = 2_000_000)).capped)
    }

    @Test
    fun eachSeatIsToldOnlyWhatItsOwnSeatSees() = runTest {
        // Both seats only set their monsters and say their hands aloud: neither ever sees a card of the other's, so no word
        // either is told — no cue, no tool result — may name one. Their own cards they read by name.
        fun setter(mine: List<String>) = Bot { text ->
            when {
                "· choose]" in text -> call("duel_act", "go first")
                "· play]" in text -> {
                    call("duel_state")
                    call("duel_moves")
                    cards("oh1", "om1")
                    call("duel_act", "m1", "say I hold ${mine.joinToString(" and ")}", "set h1", "end")
                }
            }
        }
        val a = setter(alphaNames.dropLast(1))
        val b = setter(betaNames.dropLast(1))
        val t = table(MatchRules(turnCap = 4, paceMs = 0))
        AiMatch(t, listOf(a, b), engines()).run()
        val toA = a.heard.joinToString("\n")
        val toB = b.heard.joinToString("\n")
        betaNames.forEach { assertTrue(it !in toA, "seat 0 was told “$it”") }
        alphaNames.forEach { assertTrue(it !in toB, "seat 1 was told “$it”") }
        assertTrue(alphaNames.any { it in toA } && betaNames.any { it in toB }, "each reads its own cards")
        // Their words reached the log, kept from naming the hidden cards: "a card" where a name was.
        val said = t.game.played.mapNotNull { (it.action as? DuelAction.Chat)?.text }
        assertTrue(said.isNotEmpty() && said.all { s -> (alphaNames + betaNames).none { it in s } }, said.toString())
        // The opponent's set cards, read by their place, are refused by name: card_info reaches no hidden card.
        assertTrue(t.state.seats.all { it.monsters.any { u -> u != null } }, "both set monsters")
        assertTrue(a.heard.any { "No card named “om1”" in it } || a.heard.any { "No card named" in it })
        assertTrue(t.state.seats.all { s -> s.pile(PileKind.HAND).isNotEmpty() })
    }
}
