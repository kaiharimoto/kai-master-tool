package com.kaiharimoto.neue

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.mcp.McpServerCore
import com.kaiharimoto.mastertool.core.data.CardRepository
import com.kaiharimoto.mastertool.core.data.DatabaseFactory
import com.kaiharimoto.mastertool.core.data.DeckRepository
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import com.kaiharimoto.mastertool.core.update.NeueUpdateChecker
import com.kaiharimoto.mastertool.core.update.Release
import com.kaiharimoto.mastertool.core.update.UpdateChecker
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.DeckFileAccess
import com.kaiharimoto.mastertool.ui.ImportedFile
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.mastertool.ui.update.AppUpdater
import com.kaiharimoto.mastertool.ui.update.InstallOutcome
import com.kaiharimoto.neue.ai.AiDesk
import com.kaiharimoto.neue.ai.guideForPrompt
import com.kaiharimoto.neue.ai.AnthropicBackend
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.builder.NeueDrag
import com.kaiharimoto.neue.prep.Prep
import com.kaiharimoto.neue.shot.DeckShots
import com.kaiharimoto.neue.update.NeueUpdates
import com.kaiharimoto.neue.web.Webs
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.eval.EvalLog
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.ai.evalRuns
import com.kaiharimoto.neue.ai.startEval
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ai's tools against the real app, headless: a real [DeckBuilderState], [Webs] and
 * [NeueState] on an in-memory database with a seeded card pool, driven through
 * `AiHost.run` exactly as a model's tool calls are — then the deck's counts, the web,
 * the siding plan, the memory and the settings are read back.
 */
class AiEndToEndTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun holders(): NeueHolders {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), MasterToolDatabase.Schema)
        val database = DatabaseFactory.create { driver }
        database.transaction {
            listOf(
                Seed(14558127, "Ash Blossom & Joyous Spring", "Effect Monster", "effect", alts = listOf(14558128)),
                Seed(27204311, "Nibiru, the Primal Being", "Effect Monster", "effect"),
                Seed(23434538, "Maxx \"C\"", "Effect Monster", "effect", tcg = "FORBIDDEN"),
                Seed(86066372, "Accesscode Talker", "Link Monster", "link"),
                Seed(10045474, "Infinite Impermanence", "Trap Card", "trap"),
                Seed(55144522, "Pot of Greed", "Spell Card", "spell", tcg = "FORBIDDEN"),
                Seed(12580477, "Raigeki", "Spell Card", "spell"),
            ).forEach { s ->
                database.cardQueries.insert(
                    id = s.id.toLong(), name = s.name, type = s.type, frameType = s.frame,
                    description = "Text of ${s.name}.", race = if (s.frame == "spell") "Normal" else "Spellcaster", attribute = "DARK",
                    atk = 0L, def = 0L, level = if (s.frame == "link") null else 4L,
                    linkValue = if (s.frame == "link") 4L else null, linkMarkers = "", pendulumScale = null, archetype = null,
                    imageUrl = null, imageUrlSmall = null, tcgBanStatus = s.tcg, ocgBanStatus = "UNLIMITED", alternateIds = (listOf(s.id) + s.alts).joinToString(","),
                )
            }
        }
        // Nothing here may reach the network: the pool's refresh is answered with an error.
        val offline = HttpClientFactory.create(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
        val deps = AppDependencies(
            cardRepository = CardRepository(database = database, api = YgoProDeckApi(offline), clock = System::currentTimeMillis, ioDispatcher = Dispatchers.IO),
            deckRepository = DeckRepository(database = database, clock = System::currentTimeMillis, ioDispatcher = Dispatchers.IO),
            preferencesRepository = PreferencesRepository(database = database, ioDispatcher = Dispatchers.IO),
            fileAccess = object : DeckFileAccess {
                override suspend fun importDeck(): ImportedFile? = null
                override suspend fun exportDeck(suggestedName: String, content: String) = false
                override suspend fun shareDeck(suggestedName: String, content: String) = Unit
            },
            updateChecker = UpdateChecker(GitHubReleaseApi(offline), "test"),
            updater = object : AppUpdater {
                override val currentVersionName = "test"
                override val canInstallInPlace = false
                override suspend fun downloadAndInstall(release: Release, onProgress: (Float?) -> Unit) = InstallOutcome.HandedToInstaller
                override fun openReleasePage(url: String) = Unit
            },
            newDeckId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
        val builder = DeckBuilderState(deps, scope)
        val art = ArtLibrary(Files.createTempDirectory("art").toFile(), scope)
        val h = NeueHolders(
            deps = deps,
            builder = builder,
            layout = DeckLayoutState(deps.preferencesRepository, scope),
            neue = NeueState(deps.preferencesRepository, scope),
            drag = NeueDrag(builder),
            updates = NeueUpdates(NeueUpdateChecker(GitHubReleaseApi(offline), "test", DesktopOs.LINUX), scope),
            art = art,
            shots = DeckShots(art, scope),
            webs = Webs(deps, scope),
            prep = Prep(deps, scope),
        )
        builder.start()
        h.webs.load()
        runBlocking { withTimeout(10_000) { while (builder.index.size < 7) delay(20) } }
        return h
    }

    private class Seed(val id: Int, val name: String, val type: String, val frame: String, val tcg: String = "UNLIMITED", val alts: List<Int> = emptyList())

    private fun input(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                is List<*> -> putJsonArray(k) { v.forEach { e -> add(if (e is JsonObject) e else JsonPrimitive(e.toString())) } }
                is JsonObject -> put(k, v)
                null -> Unit
            }
        }
    }

    private suspend fun NeueHolders.tool(name: String, vararg args: Pair<String, Any?>): Part.ToolResult =
        ai.host.run(Part.ToolUse("t-" + UUID.randomUUID(), name, input(*args)))

    @Test
    fun theHarnessWorksNumbersOutAndKeepsAPlan() = runBlocking {
        val h = holders()
        // Three copies at most of a card: a nine-card deck, 3 Ash among them.
        h.tool("new_deck", "name" to "Odds deck", "main" to listOf("3 Ash Blossom & Joyous Spring", "3 Infinite Impermanence", "3 Raigeki"))
        val calc = h.tool("calculate", "expression" to "atleast(40,3,5,1)")
        assertFalse(calc.isError, calc.content)
        assertTrue("0.337" in calc.content, calc.content)
        val odds = h.tool("hand_odds", "cards" to listOf("Ash Blossom & Joyous Spring"), "turn" to "first")
        assertFalse(odds.isError, odds.content)
        assertTrue("95.2381%" in odds.content, "3 of 9 in five cards is 1 − C(6,5)/C(9,5): ${odds.content}")
        val both = h.tool("hand_odds", "cards" to listOf("Ash Blossom & Joyous Spring"), "and_cards" to listOf("Infinite Impermanence"))
        assertTrue("going second" in both.content, both.content)
        val bad = h.tool("calculate", "expression" to "2 +")
        assertTrue(bad.isError)
        h.tool("todo_write", "items" to listOf("[x] Read the deck", "[>] Work out the odds", "[ ] Suggest cuts"))
        assertEquals(3, h.ai.todos.size)
        val offline = h.tool("web_fetch", "url" to "http://example.com")
        assertTrue(offline.isError, "plain http is refused")

        // Fine Tuning's guide (1.0.48): the open deck's own file, once it is saved.
        val unsaved = h.tool("memory", "action" to "add", "scope" to "guide", "text" to "Game plan: go second.")
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val wrote = h.tool("memory", "action" to "add", "scope" to "guide", "text" to "Card roles: [[Ash Blossom & Joyous Spring]] — hand trap.")
        assertFalse(wrote.isError, wrote.content + " / " + unsaved.content)
        val guide = h.ai.files.read("guides/${h.builder.deckId}.md").orEmpty()
        assertTrue("Card roles" in guide, guide)
        val read = h.tool("memory_read", "scope" to "guide")
        assertTrue("hand trap" in read.content, read.content)
    }

    @Test
    fun tournamentPrepRunsOnItsToolsEventGamesNumbersAndDrills() = runBlocking {
        val h = holders()
        h.tool("new_deck", "name" to "Event deck", "main" to listOf("3 Ash Blossom & Joyous Spring", "3 Infinite Impermanence"), "side" to listOf("3 Raigeki"))
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val deckId = h.builder.deckId!!

        assertTrue(h.tool("set_event", "name" to "Regional", "date" to "next week").isError, "a date that is not yyyy-mm-dd is refused")
        val made = h.tool("set_event", "name" to "Regional", "date" to "2026-10-17", "tier" to 2, "players" to 96, "deck_id" to deckId)
        assertFalse(made.isError, made.content)
        assertEquals("Regional", h.prep.active?.name)
        assertEquals(Page.PREP, h.neue.page)

        listOf("win", "loss", "win").forEachIndexed { n, r ->
            val g = h.tool("log_game", "against" to "Yubel", "turn" to if (n == 1) "second" else "first", "result" to r, "minutes" to 20)
            assertFalse(g.isError, g.content)
        }
        assertEquals(3, h.prep.doc.games.size)
        val table = h.tool("matchup_matrix")
        assertTrue("| Yubel" in table.content && "time risk" in table.content, table.content)
        assertTrue(h.tool("expected_winrate").isError, "no web of the field linked yet")
        val state = h.tool("prep_state")
        assertTrue("Regional" in state.content && "7 rounds of Swiss" in state.content, state.content)

        assertTrue(h.tool("drill", "action" to "next").isError, "nothing to drill before a plan is written")
        h.tool("set_siding_plan", "deck_id" to deckId, "against" to "Yubel", "turn" to "first", "out" to listOf("Ash Blossom & Joyous Spring"), "in" to listOf("Raigeki"), "why" to "They do not search.")
        val next = h.tool("drill", "action" to "next")
        assertFalse(next.isError, next.content)
        val key = Regex("Drill (\\S+):").find(next.content)!!.groupValues[1]
        val answered = h.tool("drill", "action" to "answer", "key" to key, "out" to listOf("Ash Blossom & Joyous Spring"), "in" to listOf("Raigeki"))
        assertTrue("Exactly the plan" in answered.content, answered.content)
        assertEquals(1, h.prep.doc.drills[key]?.box)
    }

    @Test
    fun aiBuildsEditsGroupsAndSidesADeckThroughItsTools() = runBlocking {
        val h = holders()
        val b = h.builder

        val made = h.tool(
            "new_deck",
            "name" to "Test deck",
            "main" to listOf("3 Ash Blossom & Joyous Spring", "Nibiru, the Primal Being", "3x Maxx \"C\"", "2 Infinite Impermanence"),
            "extra" to listOf("Accesscode Talker"),
            "side" to listOf("3 Raigeki"),
        )
        assertFalse(made.isError, made.content)
        assertTrue("Left out" in made.content, "a forbidden card is left out and said so: ${made.content}")
        assertEquals(6, b.deck.main.size)
        assertEquals(listOf(CardId(86066372)), b.deck.extra)
        assertEquals(3, b.deck.side.size)
        withTimeout(5_000) { while (b.deckId == null) delay(20) }
        val deckId = b.deckId!!

        val edited = h.tool(
            "edit_deck",
            "ops" to listOf(
                input("op" to "set", "card" to "Ash Blossom & Joyous Spring", "count" to 2),
                input("op" to "remove", "card" to "Infinite Impermanence", "count" to 1),
                input("op" to "add", "card" to "Pot of Greed"),
            ),
        )
        assertEquals(4, b.deck.main.size, edited.content)
        assertTrue("Pot of Greed" in edited.content, "the refused add is reported")
        h.tool("undo")
        assertEquals(6, b.deck.main.size, "the whole edit is one step of undo")

        h.tool("set_groups", "groups" to listOf(input("name" to "Hand traps", "cards" to listOf("Ash Blossom & Joyous Spring", "Nibiru, the Primal Being", "Infinite Impermanence"))))
        val group = b.groups.groups.single()
        assertEquals("Hand traps", group.name)
        assertEquals(3, b.groups.assignments.count { it.value == group.id })

        h.tool("save_deck")
        val web = h.tool("create_web", "name" to "YCS Test")
        assertFalse(web.isError, web.content)
        val webId = h.webs.library.webs.single().id
        val added = h.tool("add_deck_to_web", "web_id" to webId, "deck_id" to deckId, "share" to 30, "mine" to true)
        assertFalse(added.isError, added.content)
        withTimeout(5_000) { while (h.webs.library.byId(webId)!!.entries.isEmpty()) delay(20) }
        val entry = h.webs.library.byId(webId)!!.entries.single()
        assertEquals(30, entry.share)
        assertTrue(entry.mine)

        val plan = h.tool("set_siding_plan", "deck_id" to entry.deckId, "against" to "Snake-Eye", "turn" to "second", "out" to listOf("Nibiru, the Primal Being"), "in" to listOf("Raigeki"), "why" to "They go first.")
        assertFalse(plan.isError, plan.content)
        withTimeout(5_000) {
            while (SidingCodec.read(h.deps.deckRepository.byId(entry.deckId)?.extended).matchups.isEmpty()) delay(20)
        }
        val matchup = SidingCodec.read(h.deps.deckRepository.byId(entry.deckId)?.extended).matchups.single()
        assertEquals("Snake-Eye", matchup.name)
        assertEquals(listOf(CardId(27204311)), matchup.plan(Turn.SECOND).out)
        assertEquals(listOf(CardId(12580477)), matchup.plan(Turn.SECOND).into)
        assertEquals("They go first.", matchup.plan(Turn.SECOND).note)
    }

    @Test
    fun aiBuildsADeckProfileOnPresentThroughItsTools() = runBlocking {
        val h = holders()
        h.tool(
            "new_deck",
            "name" to "Profiled",
            "main" to listOf("3 Ash Blossom & Joyous Spring", "Nibiru, the Primal Being", "2 Infinite Impermanence", "Raigeki"),
            "extra" to listOf("Accesscode Talker"),
        )
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val deckId = h.builder.deckId!!
        try {
            val made = h.tool("present_edit", "ops" to listOf(input("action" to "create", "deck_id" to deckId, "style" to "build_up", "creator" to "kai")))
            assertFalse(made.isError, made.content)
            val p = h.present.open ?: error("create opens the presentation")
            assertEquals(Presentation.STYLE_BUILD_UP, p.style)
            assertEquals(deckId, p.deck?.deckId)
            assertEquals(Page.PRESENT, h.neue.page)

            // A batch is one step of Undo; an op naming a card that is not one stops there and says so.
            val before = h.present.open!!.slides.size
            val edit = h.tool(
                "present_edit",
                "ops" to listOf(
                    input("action" to "add_slide", "layout" to "CARD_FOCUS", "slots" to input("title" to "The hand trap", "card" to "Ash Blossom & Joyous Spring")),
                    input("action" to "add_slide", "layout" to "CARDS_ROW", "slots" to input("cards" to listOf("No Such Card"))),
                ),
            )
            assertTrue("Operation 2" in edit.content && "Stopped" in edit.content, edit.content)
            assertEquals(before + 1, h.present.open!!.slides.size)
            val focus = h.present.open!!.slides.first { it.title == "The hand trap" }
            assertEquals(listOf(14558127), focus.elements.first { it.type == Element.CARD }.cards)
            h.present.undo()
            assertEquals(before, h.present.open!!.slides.size, "the batch undoes as one")

            // Modules read the app's own data, and say what is missing rather than inventing it.
            val picks = h.tool("present_edit", "ops" to listOf(input("action" to "add_module", "type" to "PERFORMERS", "strong" to listOf(input("card" to "Nibiru, the Primal Being", "note" to "Breaks boards")))))
            assertFalse(picks.isError, picks.content)
            assertTrue(h.present.open!!.slides.any { it.module?.type == "PERFORMERS" })
            val siding = h.tool("present_edit", "ops" to listOf(input("action" to "add_module", "type" to "SIDING")))
            assertTrue("no siding plans" in siding.content, siding.content)

            val outline = h.tool("present_state")
            assertTrue("Profiled" in outline.content && "Build-up" in outline.content, outline.content)
            val view = h.tool("present_view", "slide" to "1")
            assertFalse(view.isError, view.content)
            assertTrue(view.content.startsWith("Slide 1 of"), view.content)
        } finally {
            h.present.open?.let { h.present.delete(it) }
        }
    }

    @Test
    fun handOddsCountsByCardSaysWhatItCouldNotFindAndCountsOverlapExactly() = runBlocking {
        val h = holders()
        // Phase B: two Ash and one of its alternate artwork are three Ash; nine cards in all.
        val ash = CardId(14558127)
        val alt = CardId(14558128)
        val imperm = CardId(10045474)
        val raigeki = CardId(12580477)
        val main = listOf(ash, ash, alt) + List(3) { imperm } + List(3) { raigeki }
        h.deps.deckRepository.save("alt-deck", "Alt deck", Deck(main, emptyList(), emptyList()))
        val odds = h.tool("hand_odds", "deck_id" to "alt-deck", "cards" to listOf("Ash Blossom & Joyous Spring"), "turn" to "first")
        assertFalse(odds.isError, odds.content)
        assertTrue("(3 in 9)" in odds.content && "95.2381%" in odds.content, "the alternate counts: ${odds.content}")
        // Overlap: Ash is among the second set too, so one Ash meets both — the odds are Ash's alone.
        val both = h.tool("hand_odds", "deck_id" to "alt-deck", "cards" to listOf("Ash Blossom & Joyous Spring"),
            "and_cards" to listOf("Ash Blossom & Joyous Spring", "Infinite Impermanence"), "turn" to "first")
        assertTrue("95.2381%" in both.content && "in both sets" in both.content, both.content)
        // A name it cannot find is said; a set with none found is refused.
        val partly = h.tool("hand_odds", "deck_id" to "alt-deck", "cards" to listOf("Ash Blossom & Joyous Spring", "Qwxzv Plorbington"), "turn" to "first")
        assertFalse(partly.isError, partly.content)
        assertTrue("Not found, so not counted: “Qwxzv Plorbington”" in partly.content, partly.content)
        val none = h.tool("hand_odds", "deck_id" to "alt-deck", "cards" to listOf("Qwxzv Plorbington"))
        assertTrue(none.isError && "Could not find" in none.content, none.content)
        val noGroup = h.tool("hand_odds", "deck_id" to "alt-deck", "cards" to listOf("Ash Blossom & Joyous Spring"), "and_group" to "Nope")
        assertTrue(noGroup.isError && "no group" in noGroup.content, noGroup.content)
    }

    @Test
    fun aGuideNumberCarriesItsProofAndGoesStaleWithTheDeck() = runBlocking {
        val h = holders()
        h.tool("new_deck", "name" to "Odds deck", "main" to listOf("3 Ash Blossom & Joyous Spring", "3 Infinite Impermanence", "3 Raigeki"))
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val deckId = h.builder.deckId!!
        // A number nobody computed is not written.
        val naked = h.tool("memory", "action" to "add", "scope" to "guide", "text" to "Opens Ash 95.2% of the time going first.")
        assertTrue(naked.isError && "Not written" in naked.content, naked.content)
        // Computed in the conversation, it is — with its proof.
        val args = arrayOf("cards" to listOf("Ash Blossom & Joyous Spring"), "turn" to "first")
        val odds = h.tool("hand_odds", *args)
        assertTrue("95.2381%" in odds.content, odds.content)
        val call = Part.ToolUse("o1", "hand_odds", input(*args))
        h.ai.session = AiSession("s1", turns = listOf(ChatTurn(Role.ASSISTANT, listOf(call)), ChatTurn(Role.USER, listOf(odds.copy(id = "o1")))))
        val proved = h.tool("memory", "action" to "add", "scope" to "guide", "text" to "Opens Ash 95.2% of the time going first.")
        assertFalse(proved.isError, proved.content)
        val proof = Ledger.read(h.ai.files.read(Ledger.path(deckId))).single()
        assertEquals(Proven.Status.CHECKED, proof.status)
        assertEquals("hand_odds", proof.proofs.single().tool)
        // An estimate said to be one is kept as one.
        assertFalse(h.tool("memory", "action" to "add", "scope" to "guide", "text" to "Bricks maybe 10% of games (estimate).").isError)
        // The deck changes: the number is stale where Ai reads it, and checked again it no longer holds.
        h.tool("edit_deck", "ops" to listOf(input("op" to "set", "card" to "Ash Blossom & Joyous Spring", "count" to 1)))
        assertTrue("stale" in h.ai.guideForPrompt(deckId), h.ai.guideForPrompt(deckId))
        withTimeout(10_000) {
            while (Ledger.read(h.ai.files.read(Ledger.path(deckId))).none { it.status == Proven.Status.CONTRADICTED }) delay(50)
        }
        assertTrue("contradicted" in h.ai.guideForPrompt(deckId))
        assertTrue("estimate" in h.ai.guideForPrompt(deckId))
    }

    @Test
    fun trustRunsASetThroughTheRealRunnerAndKeepsTheScore() = runBlocking {
        val h = holders()
        val conn = AiConnection("c-test", "anthropic", model = "scripted")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(conn), active = conn.id)) }
        // A scripted model: "yes" to every question; to the fact-checker, nothing wrong.
        val scripted = object : ModelBackend {
            override val runsOwnLoop = false
            override fun turn(request: TurnRequest) = flow {
                val checking = request.system.startsWith("You check")
                val text = if (checking) "{\"claims\": []}" else "I am sure.\nANSWER: yes"
                emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant(text), usage = Usage(input = 100, output = 10)))
            }
        }
        h.ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to scripted
        // The data folder outlives a test run: start from no runs.
        h.ai.files.delete(EvalLog.path(conn.id))
        val rulings = EvalSets.rulings()
        h.ai.startEval(rulings, conn, tries = 2)
        withTimeout(20_000) { while (h.ai.evalProgress != null || h.ai.evalJob != null) delay(20) }
        val run = h.ai.evalRuns(conn.id).single()
        val yes = rulings.items.count { (it.grader as Grader.YesNo).expected }
        assertEquals(yes, run.items.count { it.firstPass }, "every yes right, every no wrong")
        assertEquals(yes.toDouble() / rulings.items.size, run.passAll, 1e-9, "the same both tries")
        assertEquals(2, run.tries)
        assertTrue(run.tokensIn > 0)
        // The fact-checker that finds nothing: every clean answer left alone, every planted mistake missed.
        h.ai.startEval(EvalSets.planted(), conn)
        withTimeout(20_000) { while (h.ai.evalJob != null) delay(20) }
        val checked = EvalLog.latest(h.ai.evalRuns(conn.id))[EvalSets.PLANTED]!!
        assertEquals(12, checked.items.count { it.firstPass }, "12 clean answers left alone, 12 mistakes missed")
    }

    @Test
    fun memorySettingsAndConfirmations() = runBlocking {
        val h = holders()
        val remembered = h.tool("memory", "action" to "add", "scope" to "user", "text" to "Plays Branded in TCG.")
        assertFalse(remembered.isError, remembered.content)
        assertTrue("Plays Branded in TCG." in h.tool("memory_read", "scope" to "user").content)
        assertTrue(h.tool("memory", "action" to "remove", "scope" to "user", "old_text" to "Branded").content.startsWith("Removed"))

        h.tool("set_setting", "key" to "theme", "value" to "INK")
        assertEquals(NeueTheme.INK, h.neue.prefs.theme)
        h.tool("run_action", "action" to "TOGGLE_THEME")
        assertEquals(NeueTheme.PAPER, h.neue.prefs.theme)
        assertTrue("\"theme\"" in h.tool("get_settings").content)

        // A destructive tool waits on the person: here they say no.
        h.tool("new_deck", "name" to "Doomed", "main" to listOf("Raigeki"))
        withTimeout(5_000) { while (h.builder.deckId == null) delay(20) }
        val doomed = h.builder.deckId!!
        val refuse = launch { while (h.ai.confirm == null) delay(10); h.ai.confirm!!.reply(false) }
        val refused = h.tool("delete_deck", "deck_id" to doomed)
        refuse.join()
        assertTrue(refused.isError && "said no" in refused.content, refused.content)
        assertNotNull(h.deps.deckRepository.byId(doomed))
        val accept = launch { while (h.ai.confirm == null) delay(10); h.ai.confirm!!.reply(true) }
        assertFalse(h.tool("delete_deck", "deck_id" to doomed).isError)
        accept.join()
        assertEquals(null, h.deps.deckRepository.byId(doomed))

        // Turning Ai off asks too.
        val yes = launch { while (h.ai.confirm == null) delay(10); h.ai.confirm!!.reply(true) }
        h.tool("set_setting", "key" to "ai.enabled", "value" to false)
        yes.join()
        assertFalse(h.neue.prefs.ai.enabled)
    }

    @Test
    fun badInputIsRefusedBeforeItRuns() = runBlocking {
        val h = holders()
        assertTrue(h.tool("rename_deck").isError)
        assertTrue(h.tool("no_such_tool").isError)
        assertTrue(h.tool("rename_deck", "name" to "x", "nmae" to "y").content.contains("nmae"))
    }

    @Test
    fun theMcpServerAnswersOverHttpAndOnlyWithTheToken() {
        val core = McpServerCore(tools = { AiTools.all.take(2) }, call = { Part.ToolResult(it.id, it.name, "ok") })
        val server = AiDesk.startMcp { _, body -> core.handle(body) }!!
        try {
            val http = HttpClient.newHttpClient()
            fun post(token: String?, body: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI(server.url)).POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, post(null, "{}").statusCode())
            assertEquals(401, post("wrong", "{}").statusCode())
            val list = post(server.token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            assertEquals(200, list.statusCode())
            assertTrue(AiTools.all.first().name in list.body())
            val call = post(server.token, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"${AiTools.all.first().name}","arguments":{}}}""")
            assertTrue("\"ok\"" in call.body(), call.body())
            assertEquals(202, post(server.token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
            assertTrue(server.url.startsWith("http://127.0.0.1:"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun everyToolBecomesAnAnthropicToolAndAReplyIsReplayedExactly() {
        val backend = AnthropicBackend("sk-ant-test")
        val params = backend.params(
            TurnRequest(
                "system",
                listOf(
                    ChatTurn.user("hi", "Page: Builder"),
                    ChatTurn(Role.ASSISTANT, listOf(Part.Text("Looking."), Part.ToolUse("toolu_1", "app_state", JsonObject(emptyMap())))),
                    ChatTurn(Role.USER, listOf(Part.ToolResult("toolu_1", "app_state", "{}"))),
                ),
                AiTools.all,
                model = "claude-opus-5-5",
                effort = "medium",
            ),
            "claude-opus-5-5",
        )
        assertEquals(AiTools.all.size, params.tools().get().size)
        assertEquals(3, params.messages().size)
        // A reply's blocks, thinking and signature included, come back as they went out.
        val thinking = com.anthropic.models.messages.ContentBlockParam.ofThinking(
            com.anthropic.models.messages.ThinkingBlockParam.builder().thinking("hm").signature("sig-123").build(),
        )
        val text = com.anthropic.models.messages.ContentBlockParam.ofText(com.anthropic.models.messages.TextBlockParam.builder().text("Done.").build())
        val opaque = backend.text(listOf(thinking, text))
        val replayed = backend.blocks(ChatTurn(Role.ASSISTANT, listOf(Part.Text("Done."), Part.Opaque(AnthropicBackend.PROVIDER, opaque))))
        assertEquals(2, replayed.size)
        assertEquals("sig-123", replayed[0].asThinking().signature())
        assertEquals("Done.", replayed[1].asText().text())
        backend.close()
    }
}
