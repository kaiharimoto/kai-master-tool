package com.kaiharimoto.mastertool.core.ai

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
