package com.kaiharimoto.neue

import com.kaiharimoto.neue.builder.legalityRules
import com.kaiharimoto.neue.platform.Platform
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
import com.kaiharimoto.neue.ai.guideBlock
import com.kaiharimoto.neue.ai.memoryRoom
import com.kaiharimoto.neue.ai.standingContext
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryBudget
import com.kaiharimoto.mastertool.core.ai.memory.MemoryDoc
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.meta.FieldArchive
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
import java.io.File
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's holders, headless (1.0.43, shared since I.4): a real [DeckBuilderState], [Webs], [Prep] and [NeueState] on an
 * in-memory database with a seeded card pool, nothing reaching the network. What `AiEndToEndTest` drives Ai's tools through
 * and `BackupRoundTripTest` backs up and restores.
 */
internal object TestHolders {
    fun make(scope: CoroutineScope): NeueHolders {
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
}
