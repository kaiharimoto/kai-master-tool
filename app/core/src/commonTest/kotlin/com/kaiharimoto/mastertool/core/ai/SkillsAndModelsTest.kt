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

    @Test
    fun aSkillAiWroteIsReviewedLineByLine() {
        val path = Skills.path("Side Against Snake-Eye")
        assertEquals("skills/side-against-snake-eye/SKILL.md", path)
        assertTrue(Skills.isPath(path))
        assertFalse(Skills.isPath("USER.md") || Skills.isPath("guides/d1.md"))
        val v1 = com.kaiharimoto.mastertool.core.ai.skills.Skill("side-against-snake-eye", "Siding against Snake-Eye.", "1. Read the web.\n2. Bring Droll.").render()
        val v2 = v1.replace("Bring Droll.", "Bring Ash.")
        val review = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview
        // Written in the session: every line of it is new, so the review shows it and Undo deletes it.
        val written = review.diff(mapOf(path to null), mapOf(path to v1)).single()
        assertEquals(path, written.path)
        assertEquals(listOf("When to use it: Siding against Snake-Eye.", "1. Read the web.", "2. Bring Droll."), written.added)
        assertTrue(written.removed.isEmpty())
        // Patched: the line gone and the line come.
        val patched = review.diff(mapOf(path to v1), mapOf(path to v2)).single()
        assertEquals(listOf("2. Bring Ash."), patched.added)
        assertEquals(listOf("2. Bring Droll."), patched.removed)
        // Deleted: missing from the after's map altogether, as a folder gone is.
        assertEquals(3, review.diff(mapOf(path to v1), emptyMap()).single().removed.size)
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

    @Test
    fun aHelperHasALeanPromptOfItsOwn() {
        val helper = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.helper("Ai", "I am the soul.")
        assertTrue(helper.startsWith("I am the soul."))
        assertTrue("## Your job" in helper && "your final message is your report" in helper)
        assertTrue("## The rules of the game" in helper, "the rules, always")
        val base = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.Setup("Ai", "I am the soul.", "Plays Branded.", "", "- fine-tuning: …", "desktop", mode = AiSession.MODE_TUNE)
        val tune = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.system(base)
        // None of the conversation's own: its mode, its blocks, its memory, its skills.
        listOf("## This conversation", "```chart", "## Skills", "Plays Branded.").forEach {
            assertTrue(it in tune, "the conversation's prompt has $it")
            assertFalse(it in helper, "a helper's prompt has $it")
        }
        assertTrue(helper.length < tune.length, "the rules are most of it; the rest is the conversation's alone")
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

    /** kai: "allow me to use API keys that are openai compatible with different custom providers". */
    @Test
    fun anyOpenAiCompatibleProviderConnectsWithItsAddressAndKey() {
        val P = com.kaiharimoto.mastertool.core.ai.providers.Providers
        val steps = com.kaiharimoto.mastertool.core.ai.providers.SetupSteps.of(P.compatible)
        assertTrue(com.kaiharimoto.mastertool.core.ai.providers.SetupStep.KEY in steps, "a key, not a local server step")
        assertTrue(P.compatible in P.of(com.kaiharimoto.mastertool.core.ai.providers.ConnectKind.KEY), "offered with the API keys")
        assertTrue(P.typedAddress(P.compatible) && P.typedAddress(P.ollama) && !P.typedAddress(P.openai))
        assertTrue(P.compatiblePresets.size >= 6)
        assertEquals(P.compatiblePresets.size, P.compatiblePresets.map { it.name }.distinct().size)
        P.compatiblePresets.forEach { preset ->
            assertTrue(preset.baseUrl.startsWith("https://") && preset.baseUrl.endsWith("/v1"), preset.baseUrl)
            assertEquals(null, P.addressProblem(preset.baseUrl), preset.name)
        }
        assertEquals(null, P.addressProblem("http://192.168.1.20:4000/v1"), "a gateway on the home network")
        assertTrue(P.addressProblem("http://api.example.com/v1") != null, "plain http across the internet is refused")
        assertTrue(P.addressProblem("api.example.com/v1") != null)
        assertTrue(P.addressProblem("") != null)
        assertEquals(null, P.keyProblem(P.compatible, "gsk_anything"), "no prefix is assumed for a provider we do not know")
        val keyTrouble = com.kaiharimoto.mastertool.core.ai.providers.SetupGuide.trouble(com.kaiharimoto.mastertool.core.ai.providers.SetupStep.KEY, P.compatible)
        assertTrue(keyTrouble.none { it.fix.contains("blocks .") }, "no empty host in the words: $keyTrouble")
    }

    /** kai's phone reached for localhost: on a phone or tablet the model is on a computer across the Wi-Fi. */
    @Test
    fun onAPhoneALocalModelIsOnTheComputerNotTheDevice() {
        val P = com.kaiharimoto.mastertool.core.ai.providers.Providers
        val guide = com.kaiharimoto.mastertool.core.ai.providers.SetupGuide
        val server = com.kaiharimoto.mastertool.core.ai.providers.SetupStep.SERVER
        assertTrue(P.isThisDevice("http://localhost:8000/v1"))
        assertTrue(P.isThisDevice("http://127.0.0.1:11434/v1"))
        assertTrue(P.isThisDevice("http://[::1]:1234/v1"))
        assertFalse(P.isThisDevice("http://192.168.1.20:11434/v1"))
        assertEquals("http://192.168.1.20:11434/v1", P.examplePhoneAddress(P.ollama))
        assertEquals("http://192.168.1.20:1234/v1", P.examplePhoneAddress(P.lmstudio))
        assertEquals("", P.startingAddress(P.ollama, onDevice = true), "no localhost to start from on a phone")
        assertEquals("http://localhost:11434/v1", P.startingAddress(P.ollama, onDevice = false))
        assertTrue(P.plainHttpAllowed(P.examplePhoneAddress(P.ollama)), "the example is an address the app accepts")
        listOf(P.ollama, P.lmstudio, P.custom).forEach { p ->
            val needs = guide.needs(p.kind, p, onDevice = true)
            assertTrue(needs.any { "Wi-Fi" in it }, "${p.id}: $needs")
            val trouble = guide.trouble(server, p, onDevice = true)
            assertTrue(trouble.any { "localhost" in it.problem }, "${p.id}: $trouble")
        }
        assertTrue(guide.trouble(server, P.ollama, onDevice = true).any { "OLLAMA_HOST=0.0.0.0" in it.fix })
        assertTrue(guide.needs(P.ollama.kind, P.ollama, onDevice = true).any { "OLLAMA_HOST=0.0.0.0" in it })
        // An API key reads the same everywhere.
        assertEquals(guide.needs(P.anthropic.kind, P.anthropic), guide.needs(P.anthropic.kind, P.anthropic, onDevice = true))
    }
}

class MicroCapsTest {
    private fun caps(s: String, keep: Set<String> = setOf("Ai")) = com.kaiharimoto.mastertool.core.ai.text.MicroCaps.of(s, keep)

    @Test
    fun theNameKeepsItsSpellingAndNothingElseDoes() {
        assertEquals("Ai", caps("Ai"))
        assertEquals("SETTING UP Ai", caps("Setting up Ai"))
        assertEquals("Ai's NOTES", caps("Ai's notes"))
        assertEquals("WHAT Ai’s LEARNED", caps("What Ai’s learned"))
        assertEquals("ASK Ai: NEW CONVERSATION", caps("Ask Ai: new conversation"))
        assertEquals("MAIDEN AIR", caps("Maiden air"), "only the whole word, case for case")
        assertEquals("AI", caps("AI"), "the letters, not the name")
        assertEquals("DECKS", caps("Decks", emptySet()))
        assertEquals("ASK Yusaku", caps("Ask Yusaku", setOf("Yusaku")), "a renamed assistant too")
    }
}

class ChatBlocksTest {
    private fun md(s: String, streaming: Boolean = false) = com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown.parse(s, streaming)

    @Test
    fun tablesReadWithOrWithoutOuterPipesAndKeepPipesInCode() {
        val t = md("Name | Count\n:--- | ---:\nAsh | 3\n`a|b` | 1").single() as com.kaiharimoto.mastertool.core.ai.text.Block.Table
        assertEquals(2, t.header.size)
        assertEquals(listOf(com.kaiharimoto.mastertool.core.ai.text.Align.LEFT, com.kaiharimoto.mastertool.core.ai.text.Align.RIGHT), t.align)
        assertEquals(2, t.rows.size)
        assertEquals(2, t.rows[1].size, "a pipe inside code is not a cell break")
    }

    @Test
    fun aChartBlockIsAChartAndABadOneIsCode() {
        val good = md("```chart\n{\"type\":\"bar\",\"title\":\"Odds\",\"labels\":[\"1\",\"2\"],\"series\":[{\"name\":\"first\",\"values\":[40,65.5]}],\"unit\":\"%\"}\n```").single()
        val chart = (good as com.kaiharimoto.mastertool.core.ai.text.Block.Chart).chart
        assertEquals(65.5, chart.max)
        val bad = md("```chart\n{\"labels\":[\"a\",\"b\"],\"series\":[{\"values\":[1]}]}\n```").single()
        assertTrue(bad is com.kaiharimoto.mastertool.core.ai.text.Block.Code)
        assertTrue(com.kaiharimoto.mastertool.core.ai.text.ChatChart.parse("{\"type\":\"pie\",\"labels\":[\"a\"],\"values\":[3]}").isSuccess, "a flat single series reads")
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0), com.kaiharimoto.mastertool.core.ai.text.ChatChart.ticks(80.0))
        assertEquals("42%", com.kaiharimoto.mastertool.core.ai.text.ChatChart.label(42.0, "%"))
    }

    @Test
    fun aCardsBlockReadsCountsEitherSide() {
        val cards = (md("```cards\n3 Ash Blossom & Joyous Spring\nNibiru, the Primal Being x2\n[[Called by the Grave]]\n```").single() as com.kaiharimoto.mastertool.core.ai.text.Block.Cards).lines
        assertEquals(listOf(3, 2, 1), cards.map { it.count })
        assertEquals("Called by the Grave", cards[2].name)
    }

    @Test
    fun whatIsHalfWrittenWaitsWhileItStreams() {
        val m = com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
        assertEquals("Play ", m.settled("Play [[Ash Blo"))
        assertEquals("It is ", m.settled("It is **very"))
        assertEquals("Done.", m.settled("Done.\n| a | b |"), "a table waits for its rule line")
        assertEquals("| a | b |\n| --- | --- |\n| 1", m.settled("| a | b |\n| --- | --- |\n| 1"))
        val pending = md("Look:\n```chart\n{\"labels\":", streaming = true).last()
        assertTrue(pending is com.kaiharimoto.mastertool.core.ai.text.Block.Pending)
    }
}

class StreamFixesTest {
    @Test
    fun thinkSpansAreNotTheAnswerEvenCutAcrossDeltas() {
        val t = com.kaiharimoto.mastertool.core.ai.wire.ThinkSplitter()
        val words = StringBuilder()
        val thought = StringBuilder()
        listOf("<th", "ink>plan it</thi", "nk>Hello ", "world<", "3").forEach {
            val (w, r) = t.feed(it)
            words.append(w)
            thought.append(r)
        }
        val (w, r) = t.flush()
        words.append(w)
        thought.append(r)
        assertEquals("Hello world<3", words.toString())
        assertEquals("plan it", thought.toString())
    }

    @Test
    fun anEventSplitOverDataLinesIsReadWhole() {
        val s = com.kaiharimoto.mastertool.core.ai.wire.OpenAiStream()
        val out = listOf(
            "data: {\"choices\":[{\"delta\":",
            "data: {\"content\":\"Hi \"}}]}",
            "",
            "data: not json",
            "data: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}",
            "data: [DONE]",
        ).flatMap { s.line(it) }
        assertEquals("Hi there", out.filterIsInstance<BackendEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun claudeCodeCommitsWhatItsSnapshotsSayEvenIfADeltaWasLost() {
        val s = com.kaiharimoto.mastertool.core.ai.cli.ClaudeStream()
        listOf(
            """{"type":"stream_event","event":{"type":"message_start"}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Ash Blo"}}}""",
            // "ssom is a hand trap." was lost on the way.
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Ash Blossom is a hand trap."}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"Ash Blossom is a hand trap."}""",
        ).forEach { s.line(it) }
        assertEquals("Ash Blossom is a hand trap.", (s.finished as BackendEvent.Finished).text)
    }
}

class FineTuningTest {
    @Test
    fun twoWaysEachWithItsOwnInstructionsAndABudget() {
        val base = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.Setup("Ai", "soul", "", "", "", "desktop")
        val teach = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.system(base.copy(mode = AiSession.MODE_TUNE))
        val study = com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder.system(base.copy(mode = AiSession.MODE_STUDY))
        assertTrue("fine-tuning" in teach && "self-study" !in teach)
        assertTrue("self-study" in study && "Think out loud" in study)
        assertEquals(TuneIntensity.STANDARD, TuneIntensity.of("nonsense"))
        assertTrue(TuneIntensity.QUICK.steps < TuneIntensity.DEEP.steps)
        assertEquals("guides/d1.md", com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.GUIDE, "d1"))
        val names = BuiltInSkills.upTo(3).map { it.name }
        assertTrue("self-study" in names && "fine-tuning" in names && "game-rules" in names)
    }
}
