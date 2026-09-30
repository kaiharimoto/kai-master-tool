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

class MemoryReviewTest {
    @Test
    fun whatWasLearnedIsReadEntryByEntry() {
        val before = mapOf("USER.md" to "# t\n\n- Plays Branded.\n- Goes second.\n", "MEMORY.md" to null)
        val after = mapOf("USER.md" to "# t\n\n- Plays Branded Dracotail.\n- Goes second.\n", "MEMORY.md" to "# n\n\n- Keep answers short.\n")
        val changes = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.diff(before, after)
        assertEquals(listOf("MEMORY.md", "USER.md"), changes.map { it.path })
        assertEquals(listOf("Plays Branded Dracotail."), changes[1].added)
        assertEquals(listOf("Plays Branded."), changes[1].removed)
        assertEquals(3, com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.count(changes))
        assertTrue(com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.diff(before, before).isEmpty())
    }
}

class LearningTest {
    @Test
    fun aReflectionReadsOnlyWhatIsNew() {
        val turns = listOf(
            ChatTurn.user("Build me Branded."),
            ChatTurn.assistant("Done."),
            ChatTurn(Role.USER, listOf(Part.ToolResult("t", "get_deck", "…"))),
            ChatTurn.user("I go second."),
        )
        val s = AiSession("s", turns = turns)
        assertEquals(2, s.unreflected, "tool results are not the person speaking")
        assertEquals(0, s.copy(reflected = turns.size).unreflected)
        assertEquals(1, s.copy(reflected = 3).unreflected)
    }

    @Test
    fun fineTuningIsSaidInTheFrozenPromptAndOnlyThere() {
        val base = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.Setup("Ai", "soul", "", "", "", "desktop")
        val chat = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.system(base)
        val tune = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.system(base.copy(mode = AiSession.MODE_TUNE))
        assertFalse("Fine Tuning" in chat.substringAfter("## Skills"))
        assertTrue("## This conversation is Fine Tuning" in tune)
        assertTrue(tune.startsWith(chat.trimEnd()), "tuning appends; the rest of the prompt is the same bytes")
        assertEquals(tune, com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.system(base.copy(mode = AiSession.MODE_TUNE)))
    }
}

class SetupGuideTest {
    @Test
    fun everyPathSaysWhatItNeedsAndWhereItGoesWrong() {
        val providers = com.kaiharimoto.mastertool.core.ai.providers.Providers.all
        assertTrue(providers.isNotEmpty())
        providers.forEach { p ->
            val guide = com.kaiharimoto.mastertool.core.ai.providers.SetupGuide
            assertTrue(guide.needs(p.kind, p).isNotEmpty(), "${p.id} needs")
            // Every step on the provider's path that can fail says how.
            val steps = com.kaiharimoto.mastertool.core.ai.providers.SetupSteps.of(p)
            listOf(
                com.kaiharimoto.mastertool.core.ai.providers.SetupStep.INSTALL,
                com.kaiharimoto.mastertool.core.ai.providers.SetupStep.SIGN_IN,
                com.kaiharimoto.mastertool.core.ai.providers.SetupStep.KEY,
                com.kaiharimoto.mastertool.core.ai.providers.SetupStep.SERVER,
                com.kaiharimoto.mastertool.core.ai.providers.SetupStep.MODEL,
            ).filter { it in steps }.forEach { step ->
                val trouble = guide.trouble(step, p)
                assertTrue(trouble.isNotEmpty(), "${p.id} at $step")
                assertTrue(trouble.all { it.problem.isNotBlank() && it.fix.isNotBlank() })
            }
        }
        com.kaiharimoto.mastertool.core.ai.providers.ConnectKind.entries.forEach { k ->
            assertTrue(com.kaiharimoto.mastertool.core.ai.providers.SetupGuide.needs(k, null).isNotEmpty())
        }
        assertEquals(3, com.kaiharimoto.mastertool.core.ai.providers.SetupGuide.choosing.size)
    }
}
