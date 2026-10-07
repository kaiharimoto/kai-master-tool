package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.ai.DuelGuide
import com.kaiharimoto.mastertool.core.duel.match.AgentPlayer
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.MatchPrompt
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ai vs Ai end to end (`docs/phases/C.md` §6): two scripted backends — each standing in for a model on its own connection
 * — play a short match to the end through the Duel page's holder, the referee and the real agent loop. Everything each
 * backend is sent is kept and read: neither is ever told a card of the other's it could not see, nor the other's guide;
 * the match ends as a duel record of kind "ai-vs-ai" with both connections named, a replay and both conversations kept;
 * and the person's Stop ends both runs cleanly.
 */
class AiVsAiTest {
    private val catalog = DuelCatalog { code ->
        when (code) {
            in 1..8 -> DuelCardInfo("Alpha Knight $code", CardKind.MONSTER, atk = 1000 + code * 100, def = 1000, level = 4)
            in 101..108 -> DuelCardInfo("Beta Dragon ${code - 100}", CardKind.MONSTER, atk = 1000 + (code - 100) * 100, def = 1200, level = 4)
            else -> null
        }
    }
    private val alpha = (1..8).map { "Alpha Knight $it" }
    private val beta = (1..8).map { "Beta Dragon $it" }

    private fun seats() = listOf(
        MatchSeat("claude-opus-5-5", "da", "Alpha", (1..8).flatMap { listOf(it, it, it, it, it) }, connection = "Anthropic", model = "claude-opus-5-5"),
        MatchSeat("gpt-x", "db", "Beta", (101..108).flatMap { listOf(it, it, it, it, it) }, connection = "OpenAI", model = "gpt-x"),
    )

    /**
     * A model that plays by script: on its turn it sets a card, says its hand aloud and ends — and on turn 3 deals the
     * last 8000 in its Battle Phase — chooses to go first, resolves what it must and passes otherwise. Every request it is sent is kept.
     */
    private class Scripted(val mine: List<String>) : ModelBackend {
        override val runsOwnLoop = false
        val sent: MutableList<TurnRequest> = Collections.synchronizedList(mutableListOf())
        private var n = 0

        override fun turn(request: TurnRequest): Flow<BackendEvent> = flow {
            sent += request
            val last = request.history.last()
            val usage = Usage(input = 2_000, output = 100)
            if (last.isToolResults) {
                emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Done."), usage))
                return@flow
            }
            val head = last.text.lineSequence().first()
            val turn = Regex("turn (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val ops = when {
                "· choose]" in head -> listOf("go first")
                "· resolve]" in head -> listOf("resolve")
                // The last 8000 as battle damage, in its Battle Phase: the match's law refuses it anywhere else.
                "· play]" in head && turn >= 3 -> listOf("m1", "bp", "lp opp -8000")
                "· play]" in head -> listOf("m1", "say I hold ${mine.joinToString(" and ")}", "set h1", "end")
                else -> null
            }
            if (ops == null) {
                emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("I pass."), usage))
                return@flow
            }
            fun use(name: String, input: JsonObject) = Part.ToolUse("c${n++}", name, input)
            val calls = listOf(
                use("duel_state", JsonObject(emptyMap())),
                use("card_info", buildJsonObject { putJsonArray("cards") { add("oh1"); add("om1") } }),
                use("duel_act", buildJsonObject { putJsonArray("ops") { ops.forEach { add(it) } } }),
            )
            emit(BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(Part.Text("My move.")) + calls), usage))
        }
    }

    /** Every word a request carries: its instructions, every turn's text, context, tool calls and tool results. */
    private fun everything(r: TurnRequest): String = buildString {
        appendLine(r.system)
        r.tools.forEach { appendLine(it.description) }
        r.history.forEach { t ->
            t.parts.forEach { p ->
                when (p) {
                    is Part.Text -> appendLine(p.text)
                    is Part.Context -> appendLine(p.text)
                    is Part.ToolUse -> appendLine(p.input.toString())
                    is Part.ToolResult -> appendLine(p.content)
                    else -> appendLine(p.toString())
                }
            }
        }
    }

    private fun player(seat: Int, backend: ModelBackend, guide: String, kept: MutableList<AiSession>, rules: MatchRules): AgentPlayer {
        val s = seats()[seat]
        val system = MatchPrompt.system("Ai", seat, s.name, s.deckName, rules, guide)
        val session = AiSession(id = "seat$seat", connection = "c$seat", system = system, mode = AiSession.MODE_MATCH)
        val specs = AiTools.all.filter { it.name in AiMatch.TOOLS }
        return AgentPlayer(backend, system, specs, s.model, "low", rules.cueSteps, 0, System::currentTimeMillis, session, keep = { kept += it })
    }

    private fun choice(rules: MatchRules) = MatchChoice(
        seats(),
        listOf(AiConnection("c0", "anthropic", "Anthropic", "claude-opus-5-5"), AiConnection("c1", "openai", "OpenAI", "gpt-x")),
        seed = 21,
        rules = rules,
    )

    @Test
    fun twoSessionsPlayAShortMatchToTheEndEachSeeingOnlyItsOwnSeat() = runBlocking {
        val dir = Files.createTempDirectory("avai").toFile()
        val rules = MatchRules(turnCap = 8, paceMs = 0)
        val a = Scripted(alpha)
        val b = Scripted(beta)
        val kept: MutableList<AiSession> = Collections.synchronizedList(mutableListOf())
        // Each seat's own guide, marked so a leak of one into the other's session is caught.
        val guideA = DuelGuide.block("Alpha", "- GUIDE-ALPHA: open with a Knight.", emptyList())
        val guideB = DuelGuide.block("Beta", "- GUIDE-BETA: keep a Dragon back.", emptyList())
        val players = listOf(player(0, a, guideA, kept, rules), player(1, b, guideB, kept, rules))
        val d = withContext(Dispatchers.Main) { Duels(dir) }
        withContext(Dispatchers.Main) {
            d.matches.start(choice(rules), players, catalog, listOf("seat0", "seat1"), cardText = { name -> "Text of $name." })
            assertTrue(d.spectating, "the match is on the table")
            assertTrue(!d.act(listOf(DuelAction.Draw(0)), 0), "the person never plays into it")
        }
        withTimeout(60_000) { d.matches.join() }
        delay(300)
        withContext(Dispatchers.Main) {
            assertTrue(!d.matches.running)
            val g = assertNotNull(d.matches.live)
            assertTrue(g.state.seats.any { it.lp == 0 }, "it ended on life points")
            // The record: Ai vs Ai, each seat's connection and model, kept with the duel records and counted apart.
            val r = d.results.single()
            assertEquals(DuelResult.AI_VS_AI, r.kind)
            assertEquals(DuelResult.LP, r.how)
            assertEquals(listOf("Anthropic", "OpenAI"), r.seats.map { it.connection })
            assertEquals(listOf("claude-opus-5-5", "gpt-x"), r.seats.map { it.model })
            assertEquals(21L, r.seed)
            assertTrue(DuelResults.aiAgainst(d.results).isEmpty())
            assertTrue(DuelResults.summary(d.results).startsWith("Ai vs Ai: "), DuelResults.summary(d.results))
            assertTrue(File(dir, "records/${r.id}.json").exists())
            assertTrue(d.matches.ended.orEmpty().contains("won on life points"), d.matches.ended)
            // The result is the log's last line, so it is read there after the bar is closed (the design review, finding 5).
            assertEquals(d.matches.ended, (g.played.last().action as? DuelAction.Note)?.text)
            // What was spent is the bar's counter, against the match's budget (finding 9).
            assertEquals(rules.tokenCap, d.matches.budget)
            // And in money (finding 4): the seat on a listed model is priced, gpt-x is not, so no sum stands for the whole.
            assertEquals(listOf(true, false), d.matches.prices.map { it != null })
            assertEquals(null, d.matches.dollars)
            assertEquals(2, d.matches.usage.size)
            // The match is a replay too; the person's own duel (none here) was never touched.
            assertTrue(File(dir, "replays").listFiles().orEmpty().isNotEmpty())
            assertEquals(null, d.game)
            // Both seats played, each through its own session, kept.
            assertTrue(g.played.filter { it.by?.byAi == true }.map { it.by?.aiSeat }.toSet() == setOf(0, 1))
            assertTrue(g.played.filter { it.by?.byAi == true }.all { it.seat == it.by?.aiSeat })
            d.matches.close()
            assertTrue(!d.spectating)
        }
        // What each backend was sent: never a card of the other's it could not see, never the other's guide.
        val toA = a.sent.joinToString("\n") { everything(it) }
        val toB = b.sent.joinToString("\n") { everything(it) }
        assertTrue(a.sent.isNotEmpty() && b.sent.isNotEmpty())
        beta.forEach { assertTrue(it !in toA, "seat 0's session was sent “$it”") }
        alpha.forEach { assertTrue(it !in toB, "seat 1's session was sent “$it”") }
        assertTrue("GUIDE-ALPHA" in toA && "GUIDE-ALPHA" !in toB)
        assertTrue("GUIDE-BETA" in toB && "GUIDE-BETA" !in toA)
        // Their own cards they read, by name; their words to each other reach the log with no hidden card named.
        assertTrue(alpha.any { it in toA } && beta.any { it in toB })
        assertTrue("I hold a card" in toA || "I hold a card" in toB, "the other's words arrive redacted")
        // Only the four tools, both seats.
        (a.sent + b.sent).forEach { req -> assertEquals(AiMatch.TOOLS, req.tools.map { it.name }.toSet()) }
        // Each conversation kept, its own seat's, with the cues and the moves.
        val seat0 = kept.filter { it.id == "seat0" }.maxBy { it.turns.size }
        val seat1 = kept.filter { it.id == "seat1" }.maxBy { it.turns.size }
        assertEquals(AiSession.MODE_MATCH, seat0.mode)
        assertTrue(seat0.turns.any { it.toolUses.any { u -> u.name == "duel_act" } } && seat1.turns.any { it.toolUses.isNotEmpty() })
        assertTrue(seat0.usage.input > 0)
    }

    @Test
    fun stopEndsBothRunsCleanly() = runBlocking {
        val dir = Files.createTempDirectory("avai-stop").toFile()
        val rules = MatchRules(turnCap = 8, paceMs = 0)
        val hangs = object : ModelBackend {
            override val runsOwnLoop = false
            var asked = 0
            override fun turn(request: TurnRequest): Flow<BackendEvent> = flow {
                asked++
                awaitCancellation()
            }
        }
        val kept: MutableList<AiSession> = Collections.synchronizedList(mutableListOf())
        val players = listOf(player(0, hangs, "", kept, rules), player(1, hangs, "", kept, rules))
        val d = withContext(Dispatchers.Main) { Duels(dir) }
        withContext(Dispatchers.Main) { d.matches.start(choice(rules), players, catalog, listOf("seat0", "seat1")) }
        withTimeout(10_000) { while (hangs.asked == 0) delay(20) }
        withContext(Dispatchers.Main) {
            assertTrue(d.matches.running)
            d.matches.stop()
        }
        withTimeout(10_000) { d.matches.join() }
        delay(300)
        withContext(Dispatchers.Main) {
            assertTrue(!d.matches.running)
            assertTrue(d.matches.ended.orEmpty().startsWith("Stopped by the person"), d.matches.ended)
            assertTrue(d.results.isEmpty(), "a stopped match is no result")
            val notes = d.matches.live!!.played.mapNotNull { (it.action as? DuelAction.Note)?.text }
            assertTrue(notes.any { "Stopped by the person" in it }, notes.toString())
            // The seat that was thinking kept its conversation, its cue in it.
            assertTrue(kept.isNotEmpty() && kept.all { s -> s.turns.isNotEmpty() })
            assertTrue(File(dir, "replays").listFiles().orEmpty().any { "stopped" in it.readText() })
        }
    }

    @Test
    fun aSeatIsNamedByItsModelSaidShortAndTheRefusalNamesARealButton() {
        // The design review, finding 2: never a raw id the score column cuts to "CLAUD…".
        assertEquals("Opus 5.5", playerName(AiConnection("c0", "anthropic", "Anthropic", "claude-opus-5-5")))
        assertEquals("GPT-5", playerName(AiConnection("c1", "openai", "OpenAI", "gpt-5")))
        // No model: the connection's own label.
        assertEquals("My server", playerName(AiConnection("c2", "openai-compatible", "My server", "")))
        assertEquals("Ai", playerName(null))
        // Finding 1: "close it" named no button; Back to your duel is the button's own label.
        assertTrue("Back to your duel" in DuelMatches.ON_THE_TABLE)
        assertEquals("900k", tokens(900_000))
        assertEquals("1M", tokens(1_000_000))
    }
}
