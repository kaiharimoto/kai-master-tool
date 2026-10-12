package com.kaiharimoto.neue

import com.anthropic.models.messages.ToolUnion
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.ToolTiers
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prefs.TierCeiling
import com.kaiharimoto.mastertool.core.prefs.TierPrefs
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.neue.ai.AnthropicBackend
import com.kaiharimoto.neue.ai.delegate
import com.kaiharimoto.neue.ai.heldOutProblem
import com.kaiharimoto.neue.ai.judgeProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Ai may do, held on the real app (the red team on Ai's permissions, 2026-10): the person's own actions never reached
 * through `run_action`, the provider's own web only when the outside is allowed without asking, a call judged by every tier
 * it needs, and the held-out tests never on Codex.
 */
class AiPermissionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun input(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                is List<*> -> putJsonArray(k) { v.forEach { e -> add(JsonPrimitive(e.toString())) } }
                null -> Unit
            }
        }
    }

    private suspend fun NeueHolders.tool(name: String, vararg args: Pair<String, Any?>): Part.ToolResult =
        ai.host.run(Part.ToolUse("t-" + UUID.randomUUID(), name, input(*args)))

    @Test
    fun runActionNeverAnswersAShootoutHandAsThePerson() = runBlocking {
        val h = TestHolders.make(scope)
        h.tool("new_deck", "name" to "Judged", "main" to listOf("3 Ash Blossom & Joyous Spring", "3 Infinite Impermanence", "3 Raigeki"))
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val s = withContext(Dispatchers.Main) { h.neue.go(Page.SHOOTOUT); h.shootout.also { it.prepare() } }
        withTimeout(10_000) { while (s.loading || s.bench == null) delay(20) }
        withContext(Dispatchers.Main) { s.start() }
        withTimeout(10_000) { while (!s.running || s.proposal == null) delay(20) }
        val before = s.log!!.trials.size

        // Every way Ai could answer, mark or take a hand through the keys' actions is refused, in words.
        listOf("SHOOTOUT_ANSWER_1", "SHOOTOUT_ANSWER_5", "SHOOTOUT_LEFT", "SHOOTOUT_RIGHT", "SHOOTOUT_ACCEPT", "SHOOTOUT_TAG_BRICKED", "SHOOTOUT_DECIDING").forEach { a ->
            val r = h.tool("run_action", "action" to a)
            assertTrue(r.isError, "$a: ${r.content}")
            assertTrue("the person's own" in r.content, r.content)
        }
        delay(300)
        assertEquals(before, s.log!!.trials.size, "no trial kept by Ai's hand")
        assertTrue(s.running)

        // The person's own key answers the same hand: the guard, not a dead session, stopped Ai.
        val key = if (s.proposal is Proposal.Rate) DeskAction.SHOOTOUT_ANSWER_1 else DeskAction.SHOOTOUT_LEFT
        withContext(Dispatchers.Main) { h.run(key) }
        withTimeout(10_000) { while (s.log!!.trials.size == before) delay(20) }
        assertEquals(StoredTrial.PERSON, s.log!!.trials.last().judge)
        // What only looks stays Ai's to press.
        assertFalse(h.tool("run_action", "action" to "SHOOTOUT_RESULTS").isError)
        withContext(Dispatchers.Main) { s.stop() }
    }

    @Test
    fun runActionNeverMovesThePersonsSeatAtTheTable() = runBlocking {
        val h = TestHolders.make(scope)
        withContext(Dispatchers.Main) { h.neue.go(Page.DUEL) }
        val before = h.duel.game
        listOf("DUEL_DRAW", "DUEL_NEXT_PHASE", "DUEL_RESOLVE_ALL", "DUEL_PASS", "DUEL_SUMMON", "DUEL_ZONE_1", "SHORTCUT_CONFIRM").forEach { a ->
            val r = h.tool("run_action", "action" to a)
            assertTrue(r.isError && "the person's own" in r.content && "duel_act" in r.content, "$a: ${r.content}")
        }
        assertEquals(before, h.duel.game, "the table untouched")
        // The World editor's run is the person's (logged as theirs): Ai's own is world_run.
        val run = h.tool("run_action", "action" to "WORLD_RUN")
        assertTrue(run.isError && "world_run" in run.content, run.content)
        assertTrue(h.tool("run_action", "action" to "NOT_AN_ACTION").content.startsWith("No action"))
        // What only arranges the table stays Ai's to press.
        assertFalse(h.tool("run_action", "action" to "DUEL_FACING").isError)
    }

    /** A model that hands back a report at once, keeping what each request offered. */
    private class Recorder : ModelBackend {
        override val runsOwnLoop = false
        val requests = mutableListOf<TurnRequest>()
        override fun turn(request: TurnRequest) = flow {
            requests += request
            emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("Report: nothing to add."), usage = Usage(input = 10, output = 5)))
        }
    }

    private fun NeueHolders.connect(conn: AiConnection, tiers: TierPrefs): Recorder {
        neue.update { it.copy(ai = it.ai.copy(connections = listOf(conn), active = conn.id, tiers = tiers)) }
        val model = Recorder()
        ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to model
        return model
    }

    @Test
    fun aHelperHasNoWebWithTheOutsideOffAndNoProviderWebUnlessItIsAllowed() = runBlocking {
        val h = TestHolders.make(scope)
        val web = setOf("web_search", "web_fetch")
        val conn = AiConnection("c-helper", "anthropic", model = "scripted")

        // Outside off: the helper is handed no web at all, and the provider's own web is closed.
        val off = h.connect(conn, TierPrefs(outward = TierPrefs.OFF))
        assertTrue(h.ai.delegate("Read every deck.", 2).isSuccess)
        val offTools = off.requests.single().tools.map { it.name }.toSet()
        assertTrue(offTools.none { it in web }, "$offTools")
        assertTrue(offTools.none { it in ToolTiers.OUTWARD }, "nothing outward: $offTools")
        assertTrue("card_info" in offTools, "the helper still looks")
        assertFalse(off.requests.single().serverWeb)

        // Ask me first: the app's own web tools go, which AiHost asks about — never the provider's, which nobody asks.
        val ask = h.connect(conn, TierPrefs(outward = TierPrefs.ASK))
        assertTrue(h.ai.delegate("Read every deck.", 2).isSuccess)
        assertTrue(ask.requests.single().tools.map { it.name }.containsAll(web))
        assertFalse(ask.requests.single().serverWeb)

        // Allowed: as before, the provider's own.
        val allow = h.connect(conn, TierPrefs())
        assertTrue(h.ai.delegate("Read every deck.", 2).isSuccess)
        assertTrue(allow.requests.single().serverWeb)

        // A No-web connection holds whatever the person allowed.
        val local = AiConnection("c-local", "anthropic", model = "scripted", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.NO_WEB))
        val limited = h.connect(local, TierPrefs())
        assertTrue(h.ai.delegate("Read every deck.", 2).isSuccess)
        assertTrue(limited.requests.single().tools.none { it.name in web })
        assertFalse(limited.requests.single().serverWeb)
    }

    @Test
    fun anthropicsServerWebIsSentOnlyWhenTheTurnMayUseIt() {
        val backend = AnthropicBackend("sk-ant-test")
        fun tools(serverWeb: Boolean): List<ToolUnion> =
            backend.params(TurnRequest("system", listOf(ChatTurn.user("hi")), AiTools.all, "claude-opus-5-5", serverWeb = serverWeb), "claude-opus-5-5").tools().get()
        val asked = tools(serverWeb = false)
        assertEquals(AiTools.all.size, asked.size)
        assertTrue(asked.all { it.isTool() }, "under Ask every tool is the app's own, answered (and asked about) by AiHost")
        assertTrue(asked.any { it.isTool() && it.asTool().name() == "web_search" })
        val allowed = tools(serverWeb = true)
        assertEquals(AiTools.all.size, allowed.size)
        assertEquals(2, allowed.count { !it.isTool() }, "web_search and web_fetch on Anthropic's side")
        assertTrue(allowed.none { it.isTool() && it.asTool().name() in setOf("web_search", "web_fetch") })
        backend.close()
    }

    @Test
    fun aCallIsJudgedByEveryTierItNeeds() = runBlocking {
        val h = TestHolders.make(scope)
        // No web on this connection: Python reaches outside, so it is refused before anything runs; JavaScript is not.
        val local = AiConnection("c-local", "ollama", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.NO_WEB))
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(local), active = local.id)) }
        val py = h.tool("world_run", "code" to "print(1)", "lang" to "py")
        assertTrue(py.isError && py.content.startsWith("Reaching outside the app") && "Python" in py.content, py.content)
        assertTrue(ToolTiers.refused(py.content))
        val pyFile = h.tool("world_run", "path" to "lab/odds.py")
        assertTrue(ToolTiers.refused(pyFile.content), pyFile.content)
        val js = h.tool("world_run", "code" to "1+1", "lang" to "js")
        assertFalse(ToolTiers.refused(js.content), "JavaScript is a change only: ${js.content}")
        // Changes off, the outside allowed: importing a deck from YGOPRODeck keeps it, so it is refused.
        h.neue.update { it.copy(ai = it.ai.copy(connections = emptyList(), active = null, tiers = TierPrefs(write = TierPrefs.OFF))) }
        val imported = h.tool("import_ygopro_deck", "deck_number" to 1)
        assertTrue(imported.isError && imported.content.startsWith("Changing") && ToolTiers.refused(imported.content), imported.content)
        // Deleting off, though the person said never to ask: an app of the World is not deleted, and nothing is asked.
        h.neue.update { it.copy(ai = it.ai.copy(tiers = TierPrefs(destructive = TierPrefs.OFF), alwaysAllow = true)) }
        // Bounded: were it asked, the question would wait on a person who is not here.
        val deleted = withTimeout(10_000) { h.tool("world_app", "action" to "delete", "slug" to "notes") }
        assertTrue(deleted.isError && deleted.content.startsWith("Deleting is turned off"), deleted.content)
        assertNull(h.ai.confirm, "refused, never asked")
    }

    @Test
    fun theExamAndTheAblationNeverRunOnCodex() {
        val h = TestHolders.make(scope)
        val codex = AiConnection("c-codex", "codex", program = "codex")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(codex), active = codex.id)) }
        // Ordinary judging stays open on Codex; the held-out tests do not.
        assertNull(h.ai.judgeProblem())
        val exam = h.ai.heldOutProblem("The exam")
        assertNotNull(exam)
        assertTrue("Codex" in exam && "held-out answers" in exam, exam)
        assertNotNull(h.shootout.ablation.problem())
        assertTrue("Codex" in h.shootout.ablation.problem()!!, h.shootout.ablation.problem())
        assertNotNull(h.shootout.teach.examProblem)
        // Claude Code runs with no tool of its own: both open there.
        val claude = AiConnection("c-claude", "claude-code", program = "claude")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(claude), active = claude.id)) }
        assertNull(h.ai.heldOutProblem("The exam"))
    }
}
