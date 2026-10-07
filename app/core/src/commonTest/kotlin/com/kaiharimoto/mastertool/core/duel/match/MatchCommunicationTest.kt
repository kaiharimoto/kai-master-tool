package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.ChainLink
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelReach
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.record.ResultSeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray


/**
 * The red team on Ai vs Ai (2026-10), release B — how the seats hear each other: the other seat asked at every moment a
 * player answers (summons, attacks, each phase, the End Phase), an activation whole with its words before the other is
 * asked, each seat's first cue telling it everything before it, a long list cut only with saying so, the activator's own
 * chance to chain, and the tools in this table's own words.
 */
class MatchCommunicationTest {
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
            return CueResult(tokens = 1_000, usage = Usage(input = 200, output = 100, cacheRead = 700))
        }
    }



    private val full = MatchRules(turnCap = 2, paceMs = 0, windows = Windows.FULL)

    private fun quiet() = Bot { text -> if ("· choose]" in text) call("duel_act", "go second") }

    @Test
    fun theOtherSeatIsAskedOnASummonAndAtTheEndPhase() = runTest {
        var step = 0
        val first = Bot { text ->
            when {
                "· choose]" in text -> call("duel_act", "go first")
                "· play]" in text -> when (step++) {
                    0 -> {
                        val summon = Regex("`(s h\\d)`").find(call("duel_moves"))?.groupValues?.get(1) ?: "s h1"
                        call("duel_act", "m1", summon)
                    }
                    else -> call("duel_act", "end")
                }
            }
        }
        val other = quiet()
        val t = table(full)
        AiMatch(t, listOf(first, other), engines()).run()
        val responds = other.cues.count { "· respond]" in it }
        assertTrue(responds >= 2, "asked on the summon and at the End Phase: ${other.cues}")
        assertTrue(first.heard.any { "The End Phase:" in it }, "the End Phase came before the turn ended")
        assertTrue(t.game.played.any { it.action == DuelAction.Phase(DuelPhase.END) && it.by?.aiSeat == 0 })
    }

    @Test
    fun anActivationsWordsReachTheOtherBeforeItAnswersAndNothingElseDoes() = runTest {
        var acted = false
        val charmer = Bot { text ->
            when {
                "· choose]" in text -> call("duel_act", "go first")
                "· play]" in text && !acted -> {
                    acted = true
                    call("duel_act", "a h1", "say searching with its first effect", "draw 1")
                }
                "· resolve]" in text -> call("duel_act", "resolve")
                "· play]" in text -> call("duel_act", "end")
            }
        }
        val other = quiet()
        val t = table(full, a = List(40) { 9 })
        AiMatch(t, listOf(charmer, other), engines()).run()
        val asked = other.heard.first { "· respond]" in it.lineSequence().first() }
        assertTrue("Opus: searching with its first effect" in asked, asked.take(1500))
        // A move after the activation waits for the answer: it is refused, said with why.
        assertTrue(charmer.heard.any { "✗ draw 1" in it }, charmer.heard.joinToString("\n").take(1200))
        // Passed on, the activator is told it may still chain to its own link, and the brief gives it priority.
        val resolve = charmer.heard.first { "· resolve]" in it.lineSequence().first() }
        assertTrue("Chain to it yourself" in resolve && "Priority: yours" in resolve, resolve.takeLast(900))
    }

    @Test
    fun aSeatsFirstCueTellsItEverythingBeforeIt() = runTest {
        val first = Bot { text ->
            when {
                "· choose]" in text -> call("duel_act", "go first")
                "· play]" in text -> call("duel_act", "m1", "say hello from turn one", "end")
            }
        }
        val second = quiet()
        AiMatch(table(MatchRules(turnCap = 2, paceMs = 0)), listOf(first, second), engines()).run()
        // Its first cue after the other's turn — whether its very first (it lost the roll) or the next after choosing.
        val cue = second.heard.first { it.startsWith("[Ai vs Ai") && "turn 2" in it.lineSequence().first() }
        assertTrue("hello from turn one" in cue, cue.take(1500))
        // A seat's very first cue says what happened before it, the opening roll included.
        val firstEver = listOf(first, second).map { b -> b.heard.first { it.startsWith("[Ai vs Ai") } }
        assertTrue(firstEver.all { "What happened before it" in it || "the duel has been dealt" in it }, firstEver.joinToString("\n---\n") { it.take(300) })
    }

    @Test
    fun aLongListIsCutOnlyWithSayingSo() = runTest {
        val first = Bot { text ->
            when {
                "· choose]" in text -> call("duel_act", "go first")
                "· play]" in text -> {
                    // Phases back and forth: many lines of the other's turn, none of them talk.
                    repeat(9) { call("duel_act", "m2", "m1", "m2", "m1", "m2", "m1", "m2", "m1", "m2", "m1") }
                    call("duel_act", "end")
                }
            }
        }
        val second = quiet()
        AiMatch(table(MatchRules(turnCap = 2, paceMs = 0, cueMoves = 200)), listOf(first, second), engines()).run()
        val cue = second.heard.first { it.startsWith("[Ai vs Ai") && "turn 2" in it.lineSequence().first() }
        assertTrue("lines between are left out here" in cue, cue.take(600))
    }

    @Test
    fun theToolsAreDescribedAsThisTableAnswersThem() {
        val specs = MatchPrompt.tools(com.kaiharimoto.mastertool.core.ai.AiTools.all.filter { it.name in AiMatch.TOOLS })
        val act = specs.first { it.name == "duel_act" }
        assertFalse("\"at\"" in act.schema.toString(), act.schema.toString())
        assertTrue("no undo" in act.description)
        val state = specs.first { it.name == "duel_state" }
        assertFalse("perspective" in state.schema.toString())
        assertEquals(AiMatch.TOOLS, specs.map { it.name }.toSet())
    }
}
