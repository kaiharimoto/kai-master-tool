package com.kaiharimoto.mastertool.core.ai.providers

import com.kaiharimoto.mastertool.core.prefs.AiConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Setup reuses what was set up before (1.0.59): the person's own presets, their keys, and no twins. */
class SavedConnectionsTest {
    private val mimo = AiConnection("compatible-a", "compatible", "Xiaomi MiMo", "mimo-v2", "https://api.xiaomimimo.com/v1")
    private val work = AiConnection("compatible-b", "compatible", "Work gateway", "gpt-x", "https://llm.example.com/v1/")
    private val mimoAgain = AiConnection("compatible-c", "compatible", "Xiaomi MiMo", "mimo-v2-pro", "https://API.xiaomimimo.com/v1/")
    private val claude = AiConnection("anthropic-a", "anthropic", "Anthropic", "claude-opus-5-5")
    private val ollama = AiConnection("ollama-a", "ollama", "Ollama", "qwen3", "http://localhost:11434/v1")

    @Test
    fun addressesAreComparedAsServices() {
        assertEquals("https://api.xiaomimimo.com/v1", SavedConnections.address("https://API.XiaomiMiMo.com/v1/ "))
        assertEquals("https://llm.example.com/V1", SavedConnections.address("https://llm.example.com/V1"))
        assertEquals("", SavedConnections.address(null))
    }

    @Test
    fun savedCompatibleServicesArePresetsOnceEachNewestFirst() {
        val presets = SavedConnections.presets(listOf(mimo, claude, work, mimoAgain, ollama))
        assertEquals(listOf("compatible-c", "compatible-b"), presets.map { it.id })
        assertEquals(emptyList(), SavedConnections.presets(listOf(claude, ollama)))
    }

    @Test
    fun aKeyGivenBeforeIsOfferedAgainForTheSameService() {
        val all = listOf(mimo, work, claude)
        val keyed = setOf("compatible-a", "anthropic-a")
        assertEquals("compatible-a", SavedConnections.keyFrom(all, "compatible", "https://api.xiaomimimo.com/v1/", { it.id in keyed })?.id)
        assertNull(SavedConnections.keyFrom(all, "compatible", "https://llm.example.com/v1", { it.id in keyed }), "no key was kept for it")
        assertNull(SavedConnections.keyFrom(all, "compatible", "https://api.deepseek.com/v1", { true }), "another service's key is never offered")
        assertEquals("anthropic-a", SavedConnections.keyFrom(all, "anthropic", null, { it.id in keyed })?.id, "a fixed address is not compared")
    }

    @Test
    fun settingAConnectionUpAgainReplacesIt() {
        val again = AiConnection("compatible-new", "compatible", "xiaomi mimo", "mimo-v2", "https://api.xiaomimimo.com/v1/")
        assertEquals("compatible-a", SavedConnections.replaced(listOf(claude, mimo), again)?.id)
        assertNull(SavedConnections.replaced(listOf(mimo), again.copy(model = "mimo-v2-pro")), "another model is another connection")
    }
}
