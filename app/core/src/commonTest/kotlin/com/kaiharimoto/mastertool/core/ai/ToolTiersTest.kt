package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.cli.CliWeb
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.TierCeiling
import com.kaiharimoto.mastertool.core.prefs.TierPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Ai may do, by tier (Phase H, H.4): every tool in one tier, the person's choice by tier, and Ai never sets it. */
class ToolTiersTest {
    private fun spec(name: String) = AiTools.named(name)!!

    @Test
    fun everyToolHasOneTierAndTheListsNameRealTools() {
        val names = AiTools.all.map { it.name }.toSet()
        assertTrue(ToolTiers.OUTWARD.all { it in names }, "an outward name that is no tool: ${ToolTiers.OUTWARD - names}")
        assertTrue(ToolTiers.CONVERSATION.all { it in names }, "a conversation name that is no tool: ${ToolTiers.CONVERSATION - names}")
        AiTools.all.forEach { s ->
            val tier = ToolTiers.of(s)
            if (s.destructive) assertEquals(ToolTier.DESTRUCTIVE, tier, s.name)
            if (s.name in ToolTiers.OUTWARD) assertEquals(ToolTier.OUTWARD, tier, s.name)
        }
        assertEquals(ToolTier.READ, ToolTiers.of(spec("get_deck")))
        assertEquals(ToolTier.READ, ToolTiers.of(spec("ask_user")))
        assertEquals(ToolTier.WRITE, ToolTiers.of(spec("edit_deck")))
        assertEquals(ToolTier.WRITE, ToolTiers.of(spec("duel_act")))
        assertEquals(ToolTier.DESTRUCTIVE, ToolTiers.of(spec("delete_deck")))
        assertEquals(ToolTier.OUTWARD, ToolTiers.of(spec("web_fetch")))
        assertEquals(ToolTier.OUTWARD, ToolTiers.of(spec("ygopro_field_snapshot")))
        // The red team's R10: past_format changes nothing but refreshes the banlists from Yugipedia when they are old.
        assertEquals(ToolTier.OUTWARD, ToolTiers.of(spec("past_format")))
        assertFalse("past_format" in ToolTiers.offered(AiTools.all, AiPrefs(tiers = TierPrefs(outward = TierPrefs.OFF))).map { it.name })
    }

    @Test
    fun theDefaultsAreWhatEveryBuildDidBefore() {
        val p = AiPrefs()
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(spec("edit_deck"), p))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(spec("web_search"), p))
        assertEquals(TierPrefs.ASK, ToolTiers.policy(spec("delete_deck"), p))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(spec("delete_deck"), p.copy(alwaysAllow = true)))
        assertEquals(AiTools.all.size, ToolTiers.offered(AiTools.all, p).size)
    }

    @Test
    fun aTierTurnedOffIsNeitherOfferedNorRunAndReadingNeverIs() {
        val p = AiPrefs(tiers = TierPrefs(write = TierPrefs.OFF, outward = TierPrefs.OFF, destructive = TierPrefs.OFF), alwaysAllow = true)
        val offered = ToolTiers.offered(AiTools.all, p).map { it.name }.toSet()
        assertFalse("edit_deck" in offered)
        assertFalse("web_fetch" in offered)
        assertFalse("delete_deck" in offered, "off beats never asking")
        assertTrue("get_deck" in offered && "ask_user" in offered && "search_cards" in offered)
        assertTrue(ToolTiers.offWords(spec("web_fetch")).startsWith("Reaching outside the app"))
        assertEquals(TierPrefs.ASK, ToolTiers.policy(spec("edit_deck"), p.copy(tiers = p.tiers.copy(write = TierPrefs.ASK))))
    }

    @Test
    fun theTiersAreTheGuardsAiCannotSet() {
        assertTrue("ai.tiers" in AiSettings.GUARDS)
        val r = AiSettings.set(NeuePreferences(), "ai.tiers", buildJsonObject { put("write", "allow"); put("outward", "allow") })
        assertTrue(r.isFailure)
        assertTrue("safeguard" in r.exceptionOrNull()!!.message!!)
    }

    @Test
    fun anOlderDocumentReadsWithTheDefaultsAndAnUnknownPolicyIsTheDefault() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AiPrefs.serializer(), """{"enabled":true,"alwaysAllow":false}""")
        assertEquals(TierPrefs(), old.tiers)
        val odd = json.decodeFromString(AiPrefs.serializer(), """{"tiers":{"write":"sometimes","outward":"off","destructive":"allow"}}""").sanitised()
        assertEquals(TierPrefs.ALLOW, odd.tiers.write)
        assertEquals(TierPrefs.OFF, odd.tiers.outward)
        // Deleting's allow is alwaysAllow's, which every build reads: the tier holds only Off.
        assertEquals(TierPrefs.ASK, odd.tiers.destructive)
        assertEquals(JsonPrimitive("off"), JsonPrimitive(odd.tiers.outward))
    }

    @Test
    fun aConnectionsCeilingOnlyEverLowersTheTiers() {
        val local = AiConnection("local", "ollama", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.READ_ONLY))
        val claude = AiConnection("claude", "anthropic")
        val p = AiPrefs(connections = listOf(local, claude), active = "local", alwaysAllow = true)
        // Read only: nothing but reading is offered on the local model, whatever the person allowed.
        val offered = ToolTiers.offered(AiTools.all, p).map { it.name }.toSet()
        assertFalse("edit_deck" in offered || "web_fetch" in offered || "delete_deck" in offered)
        assertTrue("get_deck" in offered && "ask_user" in offered)
        assertTrue(ToolTiers.capped(ToolTier.WRITE, p))
        assertTrue("for this connection" in ToolTiers.offWords(spec("edit_deck"), p), ToolTiers.offWords(spec("edit_deck"), p))
        // The same preferences on Claude: the person's choice stands.
        assertEquals(AiTools.all.size, ToolTiers.offered(AiTools.all, p, claude).size)
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(spec("delete_deck"), p, claude))
        // Ask me first lowers Allow to Ask, and never lifts Off.
        val asks = local.copy(ceiling = TierCeiling.PRESETS.getValue(TierCeiling.ASK_FIRST))
        assertEquals(TierPrefs.ASK, ToolTiers.policy(spec("edit_deck"), p, asks))
        assertEquals(TierPrefs.ASK, ToolTiers.policy(spec("delete_deck"), p, asks))
        assertEquals(TierPrefs.OFF, ToolTiers.policy(spec("web_fetch"), p.copy(tiers = TierPrefs(outward = TierPrefs.OFF)), asks))
        assertFalse(ToolTiers.capped(ToolTier.OUTWARD, p.copy(tiers = TierPrefs(outward = TierPrefs.OFF)), asks))
        // No web: only the outside is turned off.
        val noWeb = local.copy(ceiling = TierCeiling.PRESETS.getValue(TierCeiling.NO_WEB))
        assertEquals(TierPrefs.OFF, ToolTiers.policy(spec("web_search"), p, noWeb))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(spec("edit_deck"), p, noWeb))
        // An unknown word in a ceiling is the strictest, never the loosest.
        assertEquals(TierPrefs.OFF, ToolTiers.policy(spec("edit_deck"), p, local.copy(ceiling = TierCeiling(write = "maybe"))))
    }

    @Test
    fun theProvidersOwnWebRunsOnlyWhenTheOutsideIsAllowedWithoutAsking() {
        // The red team (2026-10): Anthropic's server tools, Claude Code's WebSearch/WebFetch and Codex's live search never
        // pass AiHost, so they ran unasked under Ask, and a No-web connection's helper searched all the same.
        val claude = AiConnection("claude", "anthropic")
        assertTrue(ToolTiers.serverWeb(AiPrefs(connections = listOf(claude))))
        assertFalse(ToolTiers.serverWeb(AiPrefs(tiers = TierPrefs(outward = TierPrefs.ASK), connections = listOf(claude))))
        assertFalse(ToolTiers.serverWeb(AiPrefs(tiers = TierPrefs(outward = TierPrefs.OFF), connections = listOf(claude))))
        // A connection's own limit lowers it, whatever the person allowed.
        val noWeb = AiConnection("local", "ollama", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.NO_WEB))
        val asks = AiConnection("mid", "openai", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.ASK_FIRST))
        val p = AiPrefs(connections = listOf(claude, noWeb, asks))
        assertFalse(ToolTiers.serverWeb(p, noWeb))
        assertFalse(ToolTiers.serverWeb(p, asks))
        assertTrue(ToolTiers.serverWeb(p, claude))
        // Under Ask the app's own web tools are still offered (they ask, through AiHost); under Off neither is.
        val askOffered = ToolTiers.offered(AiTools.all, p, asks).map { it.name }
        assertTrue("web_search" in askOffered && "web_fetch" in askOffered)
        assertFalse(ToolTiers.offered(AiTools.all, p, noWeb).any { it.name == "web_search" || it.name == "web_fetch" })
        // A CLI's own web tools follow the turn's serverWeb, never the names alone.
        val tools = listOf(spec("web_search"), spec("web_fetch"), spec("card_info"))
        assertEquals(CliWeb(search = true, fetch = true), CliWeb.forTurn(TurnRequest("s", emptyList(), tools, serverWeb = true)))
        assertEquals(CliWeb.NONE, CliWeb.forTurn(TurnRequest("s", emptyList(), tools, serverWeb = false)))
        assertEquals(CliWeb.NONE, CliWeb.forTurn(TurnRequest("s", emptyList(), tools)), "nothing said, nothing on the provider's side")
        assertEquals(CliWeb.NONE, CliWeb.forTurn(TurnRequest("s", emptyList(), listOf(spec("card_info")), serverWeb = true)))
    }

    @Test
    fun aPythonRunReachesOutsideAndJavaScriptDoesNot() {
        // The red team (2026-10): Python in Ai World is a program on this computer with the network, so a Python world_run
        // needs the outside as well as Changes; JavaScript runs shut in inside the app and stays a change.
        val run = spec("world_run")
        val py = buildJsonObject { put("code", "print(1)"); put("lang", "py") }
        val pyFile = buildJsonObject { put("path", "lab/odds.py") }
        val js = buildJsonObject { put("code", "1+1"); put("lang", "js") }
        val jsFile = buildJsonObject { put("path", "lab/odds.js") }
        val bare = buildJsonObject { put("code", "1+1") }
        assertTrue(ToolTiers.runsPython(run, py) && ToolTiers.runsPython(run, pyFile))
        assertFalse(ToolTiers.runsPython(run, js) || ToolTiers.runsPython(run, jsFile) || ToolTiers.runsPython(run, bare))
        assertEquals(setOf(ToolTier.WRITE, ToolTier.OUTWARD), ToolTiers.tiersOf(run, py))
        assertEquals(setOf(ToolTier.WRITE), ToolTiers.tiersOf(run, js))

        // Under No web: Python is off, JavaScript allowed.
        val noWeb = AiConnection("local", "ollama", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.NO_WEB))
        val p = AiPrefs(connections = listOf(noWeb), active = noWeb.id)
        assertEquals(TierPrefs.OFF, ToolTiers.policyFor(run, py, p))
        assertEquals(TierPrefs.OFF, ToolTiers.policyFor(run, pyFile, p))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policyFor(run, js, p))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policyFor(run, bare, p))
        val words = ToolTiers.offWords(run, p, input = py)
        assertTrue(words.startsWith("Reaching outside the app") && "for this connection" in words && "Python" in words, words)
        assertTrue(ToolTiers.refused(words))
        // The person's own Off, and Ask: the stricter of the two tiers wins.
        val outOff = AiPrefs(tiers = TierPrefs(outward = TierPrefs.OFF))
        assertEquals(TierPrefs.OFF, ToolTiers.policyFor(run, py, outOff))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policyFor(run, js, outOff))
        val outAsk = AiPrefs(tiers = TierPrefs(outward = TierPrefs.ASK))
        assertEquals(TierPrefs.ASK, ToolTiers.policyFor(run, py, outAsk))
        assertEquals(setOf(ToolTier.OUTWARD), ToolTiers.asking(run, py, outAsk))
        val writeOff = AiPrefs(tiers = TierPrefs(write = TierPrefs.OFF))
        assertEquals(TierPrefs.OFF, ToolTiers.policyFor(run, py, writeOff))
        assertTrue(ToolTiers.offWords(run, writeOff, input = py).startsWith("Changing"))
        // Offered as ever: before a call its input is unknown, and world_run is a change.
        assertTrue(run in ToolTiers.offered(AiTools.all, outOff))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(run, outOff))
    }

    @Test
    fun aDeckImportedFromOutsideIsAChangeToo() {
        // The red team (2026-10): import_ygopro_deck saves a deck or puts one in a web, so Changes judges it beside the outside.
        val import = spec("import_ygopro_deck")
        assertEquals(ToolTier.OUTWARD, ToolTiers.of(import), "its own tier is still the outside's")
        assertEquals(setOf(ToolTier.OUTWARD, ToolTier.WRITE), ToolTiers.tiersOf(import))
        val writeOff = AiPrefs(tiers = TierPrefs(write = TierPrefs.OFF, outward = TierPrefs.ALLOW))
        assertEquals(TierPrefs.OFF, ToolTiers.policy(import, writeOff))
        assertEquals(TierPrefs.OFF, ToolTiers.policyFor(import, buildJsonObject { put("deck_number", 1) }, writeOff))
        assertFalse(import in ToolTiers.offered(AiTools.all, writeOff), "never offered with Changes off")
        assertTrue(spec("ygopro_deck") in ToolTiers.offered(AiTools.all, writeOff), "reading a list stays the outside's alone")
        assertTrue("keeps what it brings in" in ToolTiers.offWords(import, writeOff), ToolTiers.offWords(import, writeOff))
        val writeAsk = AiPrefs(tiers = TierPrefs(write = TierPrefs.ASK))
        assertEquals(TierPrefs.ASK, ToolTiers.policy(import, writeAsk))
        assertEquals(setOf(ToolTier.WRITE), ToolTiers.asking(import, null, writeAsk))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policy(import, AiPrefs()))
        assertTrue(ToolTiers.OUTWARD_CHANGES.all { it in ToolTiers.OUTWARD }, "an outward change is outward first")
    }

    @Test
    fun anAppOfTheWorldDeletedIsDeleting() {
        // The red team (2026-10): world_app's delete asked only when alwaysAllow was off, never refused with Deleting off.
        val app = spec("world_app")
        val delete = buildJsonObject { put("action", "delete"); put("slug", "notes") }
        val press = buildJsonObject { put("action", "press"); put("slug", "notes"); put("id", "go") }
        assertTrue(ToolTiers.deletes(app, delete))
        assertFalse(ToolTiers.deletes(app, press) || ToolTiers.deletes(app, null))
        assertTrue(ToolTiers.deletes(spec("delete_deck"), null))
        assertEquals(setOf(ToolTier.WRITE, ToolTier.DESTRUCTIVE), ToolTiers.tiersOf(app, delete))
        // Deleting off: refused, even when the person said never to ask.
        val off = AiPrefs(tiers = TierPrefs(destructive = TierPrefs.OFF), alwaysAllow = true)
        assertEquals(TierPrefs.OFF, ToolTiers.policyFor(app, delete, off))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policyFor(app, press, off), "every other action is a change")
        assertTrue(ToolTiers.offWords(app, off, input = delete).startsWith("Deleting is turned off"), ToolTiers.offWords(app, off, input = delete))
        // By default it asks; never asking is the person's word alone.
        assertEquals(TierPrefs.ASK, ToolTiers.policyFor(app, delete, AiPrefs()))
        assertEquals(TierPrefs.ALLOW, ToolTiers.policyFor(app, delete, AiPrefs(alwaysAllow = true)))
        // A connection's limit on deleting holds here too.
        val asks = AiConnection("mid", "openai", ceiling = TierCeiling(destructive = TierPrefs.ASK))
        assertEquals(TierPrefs.ASK, ToolTiers.policyFor(app, delete, AiPrefs(alwaysAllow = true, connections = listOf(asks), active = asks.id)))
    }

    @Test
    fun theMappersTrainerIsThePersonsPythonAndItsLookingIsReading() {
        // I.10: a training round runs the person's own Python, a program on this computer with its network, as World's does.
        val names = AiTools.all.map { it.name }.toSet()
        assertTrue(ToolTiers.RUNS_PYTHON.all { it in names }, "a Python-running name that is no tool: ${ToolTiers.RUNS_PYTHON - names}")
        assertEquals(setOf(ToolTier.WRITE, ToolTier.OUTWARD), ToolTiers.tiersOf(spec("train_start")))
        assertEquals(setOf(ToolTier.WRITE, ToolTier.OUTWARD), ToolTiers.tiersOf(spec("train_start"), buildJsonObject { }))
        val noOutside = AiPrefs(tiers = TierPrefs(outward = TierPrefs.OFF))
        assertFalse("train_start" in ToolTiers.offered(AiTools.all, noOutside).map { it.name })
        assertTrue("own Python" in ToolTiers.offWords(spec("train_start"), noOutside, input = buildJsonObject { }))
        // Pausing, tuning and the gate change things in the app; reading the run and the held-out aggregates is reading.
        listOf("train_pause", "train_tune", "train_gate").forEach { assertEquals(setOf(ToolTier.WRITE), ToolTiers.tiersOf(spec(it)), it) }
        listOf("train_status", "eval_heldout").forEach { assertEquals(ToolTier.READ, ToolTiers.of(spec(it)), it) }
        // The switch, the person's Python and their plan are internal: Ai can neither see them as settings nor set them.
        assertTrue("mapperTrain" in AiSettings.INTERNAL)
        assertTrue(AiSettings.set(NeuePreferences(), "mapperTrain", buildJsonObject { put("on", true) }).isFailure)
    }

    @Test
    fun heldOutTestsNeverRunOnCodex() {
        // The red team (2026-10): Codex's read-only shell reads the held-out answers on disk.
        assertTrue(ShootoutTools.heldOutRefusal("codex", "The exam")!!.startsWith("The exam does not run on Codex"))
        assertEquals(null, ShootoutTools.heldOutRefusal("claude-code", "The exam"))
        assertEquals(null, ShootoutTools.heldOutRefusal("anthropic", "Is it better?"))
        assertEquals(null, ShootoutTools.heldOutRefusal(null, "Is it better?"))
    }

    @Test
    fun aRunNeverSwitchesItselfOntoAConnectionThatMayDoMore() {
        val read = AiConnection("local", "ollama", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.READ_ONLY))
        val asks = AiConnection("mid", "openai", ceiling = TierCeiling.PRESETS.getValue(TierCeiling.ASK_FIRST))
        val free = AiConnection("claude", "anthropic", label = "Claude")
        assertTrue(ToolTiers.switchRefusal(read, free)!!.startsWith("Claude may do more"))
        assertTrue(ToolTiers.switchRefusal(read, asks) != null)
        assertEquals(null, ToolTiers.switchRefusal(free, read))
        assertEquals(null, ToolTiers.switchRefusal(asks, read))
        assertEquals(null, ToolTiers.switchRefusal(read, read.copy(id = "local-2")))
        // Ai never edits a connection, its ceiling included: the connections are internal.
        assertTrue("ai.connections" in AiSettings.INTERNAL)
        assertTrue(AiSettings.set(NeuePreferences(), "ai.connections", JsonPrimitive("[]")).isFailure)
        // Each preset is itself, and an older connection reads with no limit.
        TierCeiling.PRESETS.forEach { (id, c) -> assertEquals(id, c.preset) }
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AiConnection.serializer(), """{"id":"a","provider":"anthropic","model":"x"}""")
        assertTrue(old.ceiling.none)
    }
}
