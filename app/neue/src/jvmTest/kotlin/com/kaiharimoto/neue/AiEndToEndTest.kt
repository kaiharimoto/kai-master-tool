package com.kaiharimoto.neue

import com.kaiharimoto.neue.builder.legalityRules
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
import com.kaiharimoto.mastertool.core.cards.BanlistDoc
import com.kaiharimoto.mastertool.core.cards.LimitationList
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.TournamentDecks
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.remote.YgoProDeckDecks
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
import io.ktor.client.engine.mock.respond
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
import java.time.LocalDate
import java.util.Properties
import java.util.UUID
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.eval.EvalLog
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.ai.eval.Puzzles
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
                                                                                                             konamiId = null, tcgDate = null, ocgDate = null, formats = "", genesysPoints = null,
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
    fun theBanlistIsAskedByDateAndADeckCheckedAgainstIt() = runBlocking {
        val h = holders()
        // The lists as Yugipedia's pages read; held for this run, so nothing is fetched.
        h.banlists.use(
            Format.TCG,
            BanlistDoc(
                region = Format.TCG,
                lists = listOf(
                    LimitationList(Format.TCG, "January 2025 Lists (TCG)", "2025-01-01", "2025-03-31", mapOf("Pot of Greed" to BanStatus.LIMITED, "Maxx \"C\"" to BanStatus.FORBIDDEN)),
                    LimitationList(Format.TCG, "April 2025 Lists (TCG)", "2025-04-01", null, mapOf("Pot of Greed" to BanStatus.FORBIDDEN, "Maxx \"C\"" to BanStatus.FORBIDDEN, "Raigeki" to BanStatus.FORBIDDEN, "Not A Card" to BanStatus.LIMITED)),
                ),
                checked = System.currentTimeMillis(),
            ),
        )
        val feb = h.tool("banlist", "date" to "2025-02-01", "region" to "tcg")
        assertFalse(feb.isError, feb.content)
        assertTrue("January 2025 Lists (TCG) — the TCG Forbidden & Limited list, in force 1 Jan 2025 – 31 Mar 2025" in feb.content, feb.content)
        assertTrue("<untrusted source=\"Yugipedia: January 2025 Lists (TCG)\">" in feb.content && "Limited (1): Pot of Greed" in feb.content, feb.content)
        assertTrue("CC BY-SA" in feb.content && "yugipedia.com/wiki/January_2025_Lists_(TCG)" in feb.content, feb.content)
        // Today's by default, the names the pool lacks said.
        val now = h.tool("banlist")
        assertTrue("April 2025 Lists (TCG)" in now.content && "Not matched to a card in the app's pool (1): Not A Card" in now.content, now.content)

        val card = h.tool("banlist", "card" to "pot of greed", "date" to "2025-05-01")
        assertTrue("Pot of Greed on 1 May 2025: Forbidden on the April 2025 Lists (TCG)" in card.content, card.content)
        assertTrue("- Limited from 1 Jan 2025 until 1 Apr 2025 (January 2025 Lists (TCG))" in card.content, card.content)
        val moved = h.tool("banlist", "date" to "2025-05-01", "compare_to" to "2025-02-01")
        assertTrue("- Raigeki: Unlimited → Forbidden" in moved.content && "- Pot of Greed: Limited → Forbidden" in moved.content, moved.content)
        assertFalse("Maxx" in moved.content, "unchanged is not a change")
        assertTrue(h.tool("banlist", "date" to "May 2025").isError)
        assertTrue(h.tool("banlist", "date" to "2024-01-01").isError, "before the first list kept")
        assertTrue(h.tool("banlist", "region" to "md").isError, "tcg or ocg")

        // validate_deck as of a day: the list in force then.
        h.tool("new_deck", "name" to "Old format", "main" to listOf("3 Raigeki", "Ash Blossom & Joyous Spring"))
        val may = h.tool("validate_deck", "as_of" to "2025-05-01")
        assertTrue("TCG on 1 May 2025 (the April 2025 Lists (TCG)" in may.content, may.content)
        assertTrue("Raigeki is Forbidden on the April 2025 Lists (TCG), deck has 3." in may.content, may.content)
        val feb2 = h.tool("validate_deck", "as_of" to "2025-02-01")
        assertFalse("Raigeki is" in feb2.content, feb2.content)
        assertFalse("Raigeki is" in h.tool("validate_deck").content, "today's pool has Raigeki unlimited")
        assertTrue(h.tool("validate_deck", "as_of" to "1999-01-01").isError)

        // "Odds as of the March list": the copies that list forbids are out of the deck first.
        val today = h.tool("hand_odds", "cards" to listOf("Raigeki"))
        assertTrue("(3 in 4)" in today.content, today.content)
        val may2 = h.tool("hand_odds", "cards" to listOf("Ash Blossom & Joyous Spring"), "as_of" to "2025-05-01")
        assertFalse(may2.isError, may2.content)
        assertTrue("(1 in 1)" in may2.content, may2.content)
        assertTrue("on the April 2025 Lists (TCG) (Yugipedia, CC BY-SA): taken out first: 3 × Raigeki." in may2.content, may2.content)
        val feb3 = h.tool("hand_odds", "cards" to listOf("Raigeki"), "as_of" to "2025-02-01")
        assertTrue("every copy allowed." in feb3.content && "(3 in 4)" in feb3.content, feb3.content)
        val gone = h.tool("hand_odds", "cards" to listOf("Raigeki"), "as_of" to "2025-05-01")
        assertTrue("(0 in 1)" in gone.content, "a forbidden card is a chance of nothing, said as such: " + gone.content)

        // The builder checks a chosen day (1.1.1): its list, named, and validate_deck reads the same.
        h.builder.rules = h.legalityRules(h.neue.prefs.copy(legalAsOf = "2025-05-01"), Format.TCG)
        assertEquals("TCG on 1 May 2025, by the April 2025 Lists (TCG)", h.builder.rulesInForce.words())
        assertTrue(h.builder.validation.errors.any { it.message == "Raigeki is Forbidden on the April 2025 Lists (TCG), deck has 3." }, h.builder.validation.errors.toString())
        val asBuilder = h.tool("validate_deck")
        assertTrue("in TCG on 1 May 2025, by the April 2025 Lists (TCG)" in asBuilder.content && "Raigeki is Forbidden" in asBuilder.content, asBuilder.content)
        // Genesys: no list, so Raigeki is fine.
        h.builder.rules = h.legalityRules(h.neue.prefs.copy(genesys = true), Format.TCG)
        assertTrue(h.builder.validation.errors.none { "Raigeki" in it.message }, h.builder.validation.errors.toString())
        assertTrue("Genesys, 100 points" in h.tool("validate_deck").content)
        h.builder.rules = h.legalityRules(h.neue.prefs, Format.TCG)
        assertEquals("TCG", h.builder.rulesInForce.words())
    }

    @Test
    fun theFieldIsReadAsOfAPastDayOnThatDaysList() = runBlocking {
        val h = holders()
        h.banlists.use(
            Format.TCG,
            BanlistDoc(
                region = Format.TCG,
                lists = listOf(
                    LimitationList(Format.TCG, "January 2025 Lists (TCG)", "2025-01-01", "2025-03-31", mapOf("Pot of Greed" to BanStatus.LIMITED, "Maxx \"C\"" to BanStatus.FORBIDDEN)),
                    LimitationList(Format.TCG, "April 2025 Lists (TCG)", "2025-04-01", null, mapOf("Pot of Greed" to BanStatus.FORBIDDEN, "Maxx \"C\"" to BanStatus.FORBIDDEN, "Raigeki" to BanStatus.FORBIDDEN)),
                ),
                checked = System.currentTimeMillis(),
            ),
        )
        // YGOPRODeck as it pages: tier 2 newest first, twenty lists a page, two a day, each dated by its event in the
        // description. Three strategies: Raigeki (legal until April 2025), one Pot (legal on the January list, Forbidden
        // today) and two Pots (over January's Limited). A clock inside today that steps past the one-a-second pacing.
        val today = LocalDate.now()
        val names = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        fun list(n: Int, age: Int): String {
            val day = today.minusDays(age.toLong())
            val (name, main) = when (n % 3) {
                0 -> "Raigeki Burn" to List(3) { 12580477 } + 14558127 + 27204311
                1 -> "Pot Control" to listOf(55144522, 14558127, 10045474, 10045474)
                else -> "Greedy Pots" to listOf(55144522, 55144522, 86066372, 27204311)
            }
            return """{"deck_name":"$name","deck_description":"<p>Tournament: Event $n &ndash; ${names[day.monthValue - 1]} ${day.dayOfMonth}th ${day.year}</p>",""" +
                """"main_deck":"[${main.joinToString(",") { "\\\"$it\\\"" }}]","deckNum":$n,"format":"Tournament Meta Decks","submit_date":"$age days ago",""" +
                """"tournamentName":"Event $n","tournamentPlayerCount":64,"tournamentPlacement":"Top 8"}"""
        }
        val pages = (today.toEpochDay() - LocalDate.parse("2024-12-01").toEpochDay()).toInt() / 10
        var asked = 0
        var tick = 0L
        h.ai.tournaments = YgoProDeckDecks(
            HttpClientFactory.create(
                MockEngine { req ->
                    val url = req.url.toString()
                    val p = Regex("offset=(\\d+)").find(url)!!.groupValues[1].toInt() / YgoProDeckDecks.PAGE
                    val body = if ("tier-2" in url && p < pages) {
                        asked++
                        (0 until YgoProDeckDecks.PAGE).joinToString(",", "[", "]") { i -> list(p * YgoProDeckDecks.PAGE + i, 10 * p + i / 2) }
                    } else "[]"
                    respond(body, HttpStatusCode.OK)
                },
            ),
            clock = { today.toEpochDay() * YgoProDeckDecks.DAY_MS + 3_600_000L + 1_001L * tick++ },
        )

        val feb = h.tool("ygopro_field_snapshot", "as_of" to "2025-02-15", "format" to "tcg")
        assertFalse(feb.isError, feb.content)
        assertTrue("45 days to 15 Feb 2025" in feb.content, feb.content)
        assertTrue("illegal under the January 2025 Lists (TCG) were left out" in feb.content && "more than 1 Pot of Greed (Limited)" in feb.content, feb.content)
        assertTrue("Legal as of 15 Feb 2025: the January 2025 Lists (TCG)" in feb.content, feb.content)
        assertTrue("CC BY-SA" in feb.content && "yugipedia.com/wiki/January_2025_Lists_(TCG)" in feb.content, feb.content)
        assertTrue("Pot Control" in feb.content && "Raigeki Burn" in feb.content, "one Pot was legal then: ${feb.content}")
        assertTrue(asked < 60, "found by a search: $asked pages")
        // Today the one-Pot lists are Forbidden too, and the window is the last days.
        val now = h.tool("ygopro_field_snapshot", "format" to "tcg")
        assertTrue("last 45 days" in now.content && "today's TCG Forbidden & Limited list" in now.content, now.content)
        assertFalse("Pot Control" in now.content, now.content)
        // On the April list Raigeki is Forbidden.
        val may = h.tool("ygopro_field_snapshot", "as_of" to "2025-05-01")
        assertTrue("illegal under the April 2025 Lists (TCG)" in may.content && "Raigeki (Forbidden)" in may.content, may.content)

        // The lists themselves, as of the day: none newer, dated by the event; the window cut where the pages ran out, said.
        val lists = h.tool("ygopro_tournament_decks", "as_of" to "2025-02-15", "tier" to 2)
        assertFalse(lists.isError, lists.content)
        val days = Regex("""(\w+ \d+th \d{4})""").findAll(lists.content).mapNotNull { TournamentDecks.eventDay(it.value) }.toList()
        assertTrue(days.isNotEmpty() && days.all { it <= "2025-02-15" }, days.toString())
        assertTrue("ask again with as_of" in lists.content && "not the 60 days to 15 Feb 2025 asked for" in lists.content, lists.content)
        assertTrue("Legal as of 15 Feb 2025" in lists.content, lists.content)

        assertTrue(h.tool("ygopro_field_snapshot", "as_of" to "Feb 2025").isError)
        assertTrue(h.tool("ygopro_field_snapshot", "as_of" to today.plusDays(3).toString()).isError, "after today")
        assertTrue(h.tool("ygopro_field_snapshot", "as_of" to "2024-06-01").isError, "before the first list kept")
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
    fun puzzlesArePlayedOnTablesOfTheirOwnAndGradedOnTheTable() = runBlocking {
        val h = holders()
        val conn = AiConnection("c-puzzle", "anthropic", model = "scripted")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(conn), active = conn.id)) }
        // A scripted model: on p01 it plays the recorded line through duel_act; on every other puzzle it cheats by typing
        // the damage; then it says DONE. Only the tools it was given are offered.
        val offered = mutableSetOf<String>()
        val scripted = object : ModelBackend {
            override val runsOwnLoop = false
            override fun turn(request: TurnRequest) = flow {
                offered += request.tools.map { it.name }
                val asked = request.history.first().text
                val acted = request.history.any { t -> t.parts.any { it is Part.ToolResult } }
                if (acted) {
                    emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("DONE"), usage = Usage(input = 100, output = 5)))
                } else {
                    val ops = if ("p01" in asked) Puzzles.byId("p01")!!.solution else listOf("lp opp =0")
                    val call = Part.ToolUse("a1", "duel_act", buildJsonObject { putJsonArray("ops") { ops.forEach { add(JsonPrimitive(it)) } } })
                    emit(BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(call)), usage = Usage(input = 100, output = 10)))
                }
            }
        }
        h.ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to scripted
        h.ai.files.delete(EvalLog.path(conn.id))
        val before = h.duel.game
        h.ai.startEval(Puzzles.set(), conn)
        withTimeout(30_000) { while (h.ai.evalJob != null) delay(20) }
        val run = EvalLog.latest(h.ai.evalRuns(conn.id))[EvalSets.PUZZLES]!!
        assertEquals(listOf("p01"), run.items.filter { it.firstPass }.map { it.id }, "the line played wins; typed damage is refused")
        assertTrue("met" in run.items.first { it.id == "p01" }.read, run.items.first().read)
        assertEquals(setOf("duel_state", "duel_moves", "duel_act"), offered)
        assertEquals(before, h.duel.game, "the duel in play is never touched")
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
