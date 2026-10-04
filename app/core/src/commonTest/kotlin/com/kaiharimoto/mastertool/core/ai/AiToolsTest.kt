package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.ai.providers.ConnectKind
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.SetupStep
import com.kaiharimoto.mastertool.core.ai.providers.SetupSteps
import com.kaiharimoto.mastertool.core.ai.text.Block
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.ai.text.Inline
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.KeyChord
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.update.DesktopOs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiToolsTest {

    @Test
    fun namesAreUniqueAndEverySchemaIsAnObjectThatNamesItsRequiredFields() {
        assertEquals(AiTools.all.size, AiTools.all.map { it.name }.toSet().size)
        AiTools.all.forEach { tool ->
            assertTrue(tool.name.matches(Regex("[a-z_]{3,64}")), tool.name)
            assertEquals(JsonPrimitive("object"), tool.schema["type"], tool.name)
            val props = tool.schema["properties"] as JsonObject
            (tool.schema["required"] as? JsonArray)?.forEach { assertTrue((it as JsonPrimitive).content in props, "${tool.name} requires a field it lacks") }
            assertTrue(tool.description.length in 20..1200, tool.name)
        }
    }

    @Test
    fun everyDeskActionIsReachableThroughRunAction() {
        val offered = ((AiTools.runAction.schema["properties"] as JsonObject)["action"] as JsonObject)["enum"] as JsonArray
        val names = offered.map { (it as JsonPrimitive).content }.toSet()
        val missing = DeskAction.entries.filter { it !in DeskAction.AI && it !in DeskAction.HELD && it.name !in names }
        assertTrue(missing.isEmpty(), "run_action cannot reach $missing")
    }

    @Test
    fun onlyTheToolsThatDeleteAreDestructive() {
        assertEquals(setOf("delete_deck", "remove_from_web", "delete_web"), AiTools.all.filter { it.destructive }.map { it.name }.toSet())
    }

    @Test
    fun theDecksAreClosedWhileAiLearnsADeckOrThePerson() {
        val names = AiTools.all.map { it.name }.toSet()
        assertTrue(names.containsAll(AiTools.DECK_CHANGING), "every deck-changing name is a tool: ${AiTools.DECK_CHANGING - names}")
        val learning = listOf(AiSession.MODE_TUNE, AiSession.MODE_STUDY, AiSession.MODE_PRINCIPLES, AiSession.MODE_REFACTOR, AiSession.MODE_WRITE, AiSession.MODE_PROFILE)
        learning.forEach { mode ->
            assertTrue(AiTools.barredIn(mode).containsAll(AiTools.DECK_CHANGING), "$mode leaves a deck tool open")
            assertTrue("edit_deck" in AiTools.barredIn(mode) && "memory" !in AiTools.barredIn(mode), mode)
            assertTrue(AiTools.barredWhy(mode, "edit_deck")!!.contains("not changed"), mode)
            assertNull(AiTools.barredWhy(mode, "card_info"), "$mode: looking stays open")
        }
        // The guide follows the builder's deck: no other deck is opened while one is learned.
        listOf(AiSession.MODE_TUNE, AiSession.MODE_STUDY, AiSession.MODE_PRINCIPLES, AiSession.MODE_REFACTOR, AiSession.MODE_WRITE).forEach { mode ->
            assertTrue("open_deck" in AiTools.barredIn(mode), mode)
        }
        assertFalse("open_deck" in AiTools.barredIn(AiSession.MODE_PROFILE), "the person's profile follows no deck")
        // First principles keeps its own bar and its own words.
        assertTrue(AiTools.barredIn(AiSession.MODE_PRINCIPLES).containsAll(AiTools.FIRST_PRINCIPLES_BARRED))
        assertFalse("web_search" in AiTools.barredIn(AiSession.MODE_STUDY), "a study reads online")
        assertTrue(AiTools.barredWhy(AiSession.MODE_PRINCIPLES, "web_search")!!.contains("card text and the rules"))
        // An ordinary conversation, a presentation and the duel close nothing here.
        listOf(AiSession.MODE_CHAT, AiSession.MODE_PRESENT, AiSession.MODE_RESTYLE, AiSession.MODE_DUEL).forEach { mode ->
            assertTrue(AiTools.barredIn(mode).isEmpty(), mode)
        }
    }

    @Test
    fun theFirstReleaseOffersTheHarnessAndLaterPhasesAddToIt() {
        val first = AiTools.offered(1).map { it.name }
        assertTrue("edit_deck" in first && "set_setting" in first)
        assertFalse("ygopro_field_snapshot" in first)
        assertEquals(AiTools.all.size, AiTools.offered(3).size)
    }

    @Test
    fun aToolsInputIsCheckedBeforeItRuns() {
        assertNull(ToolArgs.problem(AiTools.renameDeck, JsonObject(mapOf("name" to JsonPrimitive("x")))))
        assertTrue(ToolArgs.problem(AiTools.renameDeck, JsonObject(emptyMap()))!!.contains("name"))
        assertTrue(ToolArgs.problem(AiTools.renameDeck, JsonObject(mapOf("name" to JsonPrimitive("x"), "nme" to JsonPrimitive(1))))!!.contains("nme"))
        val lenient = JsonObject(mapOf("n" to JsonPrimitive("3"), "b" to JsonPrimitive("yes"), "s" to JsonPrimitive("a\nb")))
        assertEquals(3, ToolArgs.int(lenient, "n"))
        assertEquals(true, ToolArgs.bool(lenient, "b"))
        assertEquals(listOf("a", "b"), ToolArgs.strings(lenient, "s"))
    }

    @Test
    fun everySettingIsDescribedOrDeliberatelyInternal() {
        val keys = AiSettings.flatten(NeuePreferences()).keys
        val loose = keys.filter { it !in AiSettings.described && it !in AiSettings.INTERNAL }
        assertTrue(loose.isEmpty(), "Settings Ai cannot see or set: $loose — describe them in AiSettings or list them as internal")
    }

    @Test
    fun settingsAreSetByKeyInTheShapeTheyHave() {
        val prefs = NeuePreferences()
        assertEquals(1.25f, AiSettings.set(prefs, "scale", JsonPrimitive("1.25")).getOrThrow().scale)
        assertEquals("Roboppi", AiSettings.set(prefs, "ai.name", JsonPrimitive("Roboppi")).getOrThrow().ai.name)
        assertEquals(false, AiSettings.set(prefs, "ai.enabled", JsonPrimitive("false")).getOrThrow().ai.enabled)
        assertEquals(2f, AiSettings.set(prefs, "scale", JsonPrimitive(99)).getOrThrow().scale, "sanitised on the way in")
        assertTrue(AiSettings.set(prefs, "window", JsonPrimitive(1)).isFailure)
        assertTrue(AiSettings.set(prefs, "nonsense", JsonPrimitive(1)).isFailure)
        val described = AiSettings.describe(prefs, "TCG", true)
        assertEquals(JsonPrimitive("TCG"), described["format"]!!.jsonObject["value"])
    }

    @Test
    fun theAiKeyIsDeadWhileAiIsOff() {
        val ctrlI = KeyChord("i", ctrl = true)
        assertEquals(DeskAction.AI_PANEL, DeskShortcuts.resolve(ctrlI, DeskContext()))
        assertNull(DeskShortcuts.resolve(ctrlI, DeskContext(ai = false)))
        assertFalse(DeskMenuBar.enabled(DeskAction.AI_PANEL, DeskContext(ai = false)))
        DeskMenuBar.aiShown = false
        try {
            assertTrue(DeskMenuBar.menus.flatMap { it.items }.none { it.action == DeskAction.AI_PANEL })
        } finally {
            DeskMenuBar.aiShown = true
        }
        assertTrue(DeskMenuBar.menus.flatMap { it.items }.any { it.action == DeskAction.AI_PANEL })
    }

    @Test
    fun aiPrefsAreSanitised() {
        val p = AiPrefs(name = "   ", panelWidth = 9999f, effort = "ludicrous", active = "gone").sanitised()
        assertEquals("Ai", p.name)
        assertEquals(AiPrefs.MAX_PANEL_WIDTH, p.panelWidth)
        assertEquals("", p.effort)
        assertNull(p.active)
    }

    private val index = CardIndex.build(TestCards.all)

    @Test
    fun cardsAreReadTheWayPlayersWriteThem() {
        assertEquals(3, (CardWords.resolve("3 Ash Blossom & Joyous Spring", index) as Resolved.Found).count)
        assertEquals(2, (CardWords.resolve("Ash Blossom & Joyous Spring x2", index) as Resolved.Found).count)
        assertEquals("Ash Blossom & Joyous Spring", (CardWords.resolve("14558127", index) as Resolved.Found).card.name)
        assertEquals("Ash Blossom & Joyous Spring", (CardWords.resolve("ash blossom & joyous spring", index) as Resolved.Found).card.name)
        val guess = CardWords.resolve("Ash Blossom", index)
        assertIs<Resolved.Found>(guess)
        assertTrue(guess.guessed)
        assertIs<Resolved.Unknown>(CardWords.resolve("Not A Real Card At All", index))
    }

    @Test
    fun aPostedDecklistIsRead() {
        val (deck, problems) = CardWords.deckList(
            "Main Deck (4)\n3 Ash Blossom & Joyous Spring\n1 Nibiru, the Primal Being\n\nExtra Deck (1)\n1 Accesscode Talker\nSide:\n2x Infinite Impermanence\nMystery Card",
            index,
        )
        assertEquals(4, deck.main.size)
        assertEquals(1, deck[DeckSection.EXTRA].size)
        assertEquals(2, deck.side.size)
        assertEquals(1, problems.size)
    }

    @Test
    fun markdownIsReadIntoBlocksAndCardsBecomeChips() {
        val blocks = ChatMarkdown.parse("## Plan\n\nAdd **three** [[Ash Blossom & Joyous Spring]].\n\n- one\n- two\n\n1. first\n2. second\n\n```\ncode\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertIs<Block.Heading>(blocks[0])
        val para = blocks[1] as Block.Paragraph
        assertTrue(para.inlines.contains(Inline.Bold("three")))
        assertTrue(para.inlines.contains(Inline.Card("Ash Blossom & Joyous Spring")))
        assertEquals(2, (blocks[2] as Block.Bullets).items.size)
        assertEquals(2, (blocks[3] as Block.Numbered).items.size)
        assertEquals("code", (blocks[4] as Block.Code).text)
        assertEquals(1, (blocks[5] as Block.Table).rows.size)
        assertEquals(listOf(Inline.Text("an ** unclosed")), ChatMarkdown.inline("an ** unclosed"))
        assertEquals(listOf("A", "B"), ChatMarkdown.cards("[[A]] and [[B]] and [[A]]"))
    }

    @Test
    fun providersAreStepByStepAndTheCliOnesNeedADesk() {
        assertEquals(
            listOf(SetupStep.NAME, SetupStep.CONNECT, SetupStep.PROVIDER, SetupStep.INSTALL, SetupStep.SIGN_IN, SetupStep.MODEL, SetupStep.PERMISSIONS, SetupStep.DONE),
            SetupSteps.of(Providers.claudeCode),
        )
        assertEquals(SetupStep.KEY, SetupSteps.next(SetupStep.PROVIDER, Providers.anthropic))
        assertEquals(SetupStep.SERVER, SetupSteps.next(SetupStep.PROVIDER, Providers.ollama))
        assertFalse(Providers.available(Providers.codex, DesktopOs.ANDROID))
        assertTrue(Providers.available(Providers.anthropic, DesktopOs.ANDROID))
        assertEquals(3, ConnectKind.entries.size)
        assertEquals("claude-opus-5-5", Providers.recommended(Providers.anthropic, listOf("claude-haiku-4-5", "claude-opus-5-5", "claude-sonnet-5-5")))
        assertEquals("gpt-5.1", Providers.recommended(Providers.openai, listOf("text-embedding-3", "gpt-4o", "gpt-5.1", "whisper-1")))
        assertEquals(listOf("gpt-4o"), Providers.chatModels(listOf("gpt-4o", "text-embedding-3-small", "tts-1")))
    }

    @Test
    fun plainHttpOnlyOnThisMachineOrTheLocalNetwork() {
        assertTrue(Providers.plainHttpAllowed("http://localhost:11434/v1"))
        assertTrue(Providers.plainHttpAllowed("http://192.168.1.20:1234/v1"))
        assertTrue(Providers.plainHttpAllowed("https://api.example.com/v1"))
        assertFalse(Providers.plainHttpAllowed("http://api.example.com/v1"))
        assertFalse(Providers.plainHttpAllowed("http://8.8.8.8/v1"))
        assertTrue(Providers.keyProblem(Providers.anthropic, "sk-proj-abc")!!.contains("sk-ant-"))
        assertNull(Providers.keyProblem(Providers.anthropic, "sk-ant-abc"))
    }
}
