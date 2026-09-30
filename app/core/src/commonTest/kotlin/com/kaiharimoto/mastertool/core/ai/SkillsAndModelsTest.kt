package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.ai.wire.AnthropicModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillsAndModelsTest {

    @Test
    fun everyToolASkillNamesExists() {
        val names = AiTools.all.map { it.name }.toSet()
        BuiltInSkills.all.forEach { skill ->
            val named = Regex("`([a-z_]{4,})`").findAll(skill.body).map { it.groupValues[1] }.filter { '_' in it }.toSet()
            val missing = named - names - setOf("skill_view")
            assertTrue(missing.isEmpty() || missing.all { it.uppercase() == it }, "${skill.name} names tools that do not exist: $missing")
        }
    }

    @Test
    fun builtInSkillsRoundTripAndArriveByPhase() {
        BuiltInSkills.all.forEach { s -> assertEquals(s.copy(builtIn = false), Skills.parse(s.render())) }
        val first = BuiltInSkills.upTo(1).map { it.name }
        assertTrue("app-control" in first && "deck-assessment" in first)
        assertFalse("format-webs" in first)
        assertTrue("fine-tuning" in BuiltInSkills.upTo(3).map { it.name })
    }

    @Test
    fun eachClaudeModelGetsOnlyTheFieldsItTakes() {
        assertTrue(AnthropicModels.adaptive("claude-opus-5-5"))
        assertTrue(AnthropicModels.adaptive("claude-sonnet-4-6"))
        assertFalse(AnthropicModels.adaptive("claude-haiku-4-5"))
        assertFalse(AnthropicModels.adaptive("claude-sonnet-4-5-20250929"))
        assertTrue(AnthropicModels.adaptive("claude-something-new-9"), "an unknown id is assumed current")
        assertEquals("medium", AnthropicModels.effort("claude-opus-5-5", "medium"))
        assertNull(AnthropicModels.effort("claude-haiku-4-5", "high"))
        assertEquals("high", AnthropicModels.effort("claude-opus-4-6", "xhigh"), "xhigh arrived with 4.7")
        assertNull(AnthropicModels.effort("claude-opus-5-5", ""))
        assertTrue(AnthropicModels.fallbacks("claude-opus-5-5"))
        assertFalse(AnthropicModels.fallbacks("claude-haiku-4-5"))
        assertEquals(AnthropicModels.Id("opus", 5, 0), AnthropicModels.parse("claude-opus-5"))
    }
}
