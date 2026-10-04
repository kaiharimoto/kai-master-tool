package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {

    private fun database(): MasterToolDatabase = testDatabase()

    private fun apiReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK): YgoProDeckApi {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return YgoProDeckApi(HttpClientFactory.create(engine))
    }

    private fun failingApi(): YgoProDeckApi {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        return YgoProDeckApi(HttpClientFactory.create(engine))
    }

    private val sampleFeed = """
        {"data":[
          {"id":14558127,"name":"Ash Blossom & Joyous Spring","type":"Effect Monster",
           "frameType":"effect","desc":"tuner","race":"Zombie","attribute":"FIRE",
           "atk":0,"def":1800,"level":3,
           "card_images":[{"id":14558127,"image_url":"a.jpg","image_url_small":"a_s.jpg"},
                          {"id":99999999,"image_url":"b.jpg","image_url_small":"b_s.jpg"}],
           "banlist_info":{"ban_tcg":"Limited"},
           "misc_info":[{"beta_name":"Ash Blossom","views":25663772,"formats":["Common Charity","TCG","OCG","Master Duel"],
                         "tcg_date":"2017-05-04","ocg_date":"2017-01-14","konami_id":12950,"has_effect":1,
                         "md_rarity":"Ultra Rare","genesys_points":20}]},
          {"id":86066372,"name":"Accesscode Talker","type":"Link Monster",
           "frameType":"link","desc":"link","race":"Cyberse","attribute":"DARK",
           "atk":2300,"linkval":4,"linkmarkers":["Left","Right"],
           "card_images":[{"id":86066372,"image_url":"c.jpg","image_url_small":"c_s.jpg"}]}
        ]}
    """.trimIndent()

    // ---- CardRepository ----------------------------------------------------

    @Test
    fun syncStoresCardsAndBuildsIndex() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1_000L })

        val result = repo.sync()

        assertIs<SyncResult.Updated>(result)
        assertEquals(2, result.cardCount)
        assertEquals(2, repo.index.value.size)
        assertEquals("Ash Blossom & Joyous Spring", repo.index.value.byId(CardId(14558127))?.name)
    }

    @Test
    fun syncPersistsAlternateArtworkPasscodes() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1_000L })
        repo.sync()

        // The alternate printing must resolve to the same card after a reload.
        val reloaded = repo.loadFromCache()
        assertEquals("Ash Blossom & Joyous Spring", reloaded.byId(CardId(99999999))?.name)
    }

    @Test
    fun syncMapsBanlistAndLinkData() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1_000L })
        repo.sync()
        val index = repo.loadFromCache()

        val ash = assertNotNull(index.byId(CardId(14558127)))
        assertEquals(com.kaiharimoto.mastertool.core.model.BanStatus.LIMITED, ash.tcgBanStatus)

        val accesscode = assertNotNull(index.byId(CardId(86066372)))
        assertEquals(4, accesscode.linkValue)
        assertEquals(listOf("Left", "Right"), accesscode.linkMarkers)
        assertTrue(accesscode.isExtraDeck)
        assertNull(accesscode.def)
    }

    @Test
    fun secondSyncWithinMaxAgeDoesNotRefetch() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1_000L })
        repo.sync()

        val second = repo.sync()
        assertIs<SyncResult.UpToDate>(second)
        assertEquals(2, second.cardCount)
    }

    @Test
    fun forcedSyncRefetchesEvenWhenFresh() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1_000L })
        repo.sync()
        assertIs<SyncResult.Updated>(repo.sync(force = true))
    }

    @Test
    fun networkFailureKeepsTheCachedPool() = runTest {
        val db = database()
        CardRepository(db, apiReturning(sampleFeed), clock = { 1_000L }).sync()

        // A later refresh fails; the cards already on device must survive.
        val offline = CardRepository(db, failingApi(), clock = { 999_999_999L })
        val result = offline.sync(force = true)

        assertIs<SyncResult.Failed>(result)
        assertEquals(2, result.cachedCardCount)
        assertEquals(2, offline.loadFromCache().size)
    }

    @Test
    fun emptyResponseDoesNotWipeTheCache() = runTest {
        val db = database()
        CardRepository(db, apiReturning(sampleFeed), clock = { 1_000L }).sync()

        val repo = CardRepository(db, apiReturning("""{"data":[]}"""), clock = { 999_999_999L })
        val result = repo.sync(force = true)

        assertIs<SyncResult.Failed>(result)
        assertEquals(2, repo.loadFromCache().size)
    }

    @Test
    fun statusReportsEmptyPoolBeforeFirstSync() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1L })
        val status = repo.status()
        assertTrue(status.isEmpty)
        assertNull(status.lastSyncEpochMs)
    }

    /** An API that answers the version check with [version] (or fails it) and the card feed with [feed]. */
    private fun versionedApi(version: String?, feed: String = sampleFeed, updated: String = "2026-09-28 00:05:08"): YgoProDeckApi {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("checkDBVer.php") && version == null -> respondError(HttpStatusCode.ServiceUnavailable)
                path.endsWith("checkDBVer.php") -> respond(
                    content = ByteReadChannel("""[{"database_version":"$version","last_update":"$updated"}]"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = ByteReadChannel(feed),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        return YgoProDeckApi(HttpClientFactory.create(engine))
    }

    @Test
    fun syncWritesDownTheVersionAndSizeItFetched() = runTest {
        val db = database()
        val result = CardRepository(db, versionedApi("147.20"), clock = { 1_000L }).sync(force = true)
        assertIs<SyncResult.Updated>(result)
        assertEquals("147.20", result.version)
        val record = assertNotNull(CardRepository(db, versionedApi("147.20"), clock = { 1_000L }).record())
        assertEquals("147.20", record.version)
        assertTrue(record.bytes > 0, "the next download is measured against this one")
    }

    @Test
    fun syncReportsEachStepInOrder() = runTest {
        val steps = mutableListOf<PoolProgress>()
        CardRepository(database(), versionedApi("147.20"), clock = { 1_000L }).sync(force = true) { steps += it }
        assertEquals(PoolProgress.Asking, steps.first())
        assertTrue(steps.any { it is PoolProgress.Downloading }, "$steps")
        assertTrue(PoolProgress.Reading in steps)
        assertEquals(PoolProgress.Saving(2, 2), steps.last())
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b.fraction >= a.fraction, "$a to $b") }
    }

    @Test
    fun checkSaysCurrentForTheSameVersionAndBehindForANewer() = runTest {
        val db = database()
        CardRepository(db, versionedApi("147.20"), clock = { 1_000L }).sync(force = true)

        val same = CardRepository(db, versionedApi("147.20"), clock = { 2_000L }).check()
        assertIs<PoolCheck.Current>(same)
        assertEquals(2, same.cards)
        assertEquals(2_000L, same.checkedAt)

        val newer = CardRepository(db, versionedApi("147.21"), clock = { 3_000L }).check()
        assertIs<PoolCheck.Behind>(newer)
        assertEquals("147.20", newer.local)
        assertEquals("147.21", newer.remote)
    }

    @Test
    fun checkOfflineSaysSoAndKeepsThePool() = runTest {
        val db = database()
        CardRepository(db, versionedApi("147.20"), clock = { 1_000L }).sync(force = true)
        val check = CardRepository(db, versionedApi(null), clock = { 2_000L }).check()
        assertIs<PoolCheck.Unreachable>(check)
        assertEquals(2, check.cards)
    }

    @Test
    fun aPoolFetchedBeforeVersionsWereKeptIsJudgedByItsAgeAndThenAdoptsTheVersion() = runTest {
        val db = database()
        // Fetched with no version check answering: nothing written down.
        CardRepository(db, versionedApi(null), clock = { 1_000L }).sync(force = true)
        val day = 24L * 60 * 60 * 1000
        val changed = com.kaiharimoto.mastertool.core.data.PoolFreshness.parseUtc("2026-09-28 00:05:08")!!

        val old = CardRepository(db, versionedApi("147.20"), clock = { changed }).check()
        assertIs<PoolCheck.Behind>(old)
        assertNull(old.local)

        // Fetched well after the database last changed: current, and from now on compared by version.
        val later = database()
        CardRepository(later, versionedApi(null), clock = { changed + 2 * day }).sync(force = true)
        assertIs<PoolCheck.Current>(CardRepository(later, versionedApi("147.20"), clock = { changed + 2 * day }).check())
        assertEquals("147.20", CardRepository(later, versionedApi("147.20"), clock = { 0L }).record()?.version)
    }

    // ---- DeckRepository ----------------------------------------------------

    @Test
    fun savesAndReloadsADeckExactly() = runTest {
        val repo = DeckRepository(database(), clock = { 5_000L })
        val deck = Deck(
            main = listOf(CardId(1), CardId(1), CardId(2)),
            extra = listOf(CardId(3)),
            side = listOf(CardId(4)),
        )

        repo.save("deck-1", "Snake-Eye", deck)
        val loaded = assertNotNull(repo.byId("deck-1"))

        assertEquals(deck, loaded.entry.deck)
        assertEquals("Snake-Eye", loaded.entry.name)
    }

    @Test
    fun preservesYdkxExtendedPayloadThroughStorage() = runTest {
        val repo = DeckRepository(database(), clock = { 5_000L })
        val source = """
            #main
            14558127
            #extra
            !side
            #ydkx-extended
            {"version":"1.0","sidingPatterns":{"vs Snake-Eye":{"goingFirst":{"in":[1],"out":[2]}}}}
        """.trimIndent()

        val document = YdkCodec.parse(source).document
        repo.saveImported("deck-x", "Imported", document)

        val loaded = assertNotNull(repo.byId("deck-x"))
        assertEquals(document.extended, loaded.extended)

        // And it must still be there when exported back out to a file.
        val exported = assertNotNull(repo.exportText("deck-x"))
        assertEquals(document.extended, YdkCodec.parse(exported).document.extended)
    }

    @Test
    fun saveKeepsOriginalCreationTimeButUpdatesTimestamp() = runTest {
        var now = 1_000L
        val repo = DeckRepository(database(), clock = { now })
        repo.save("d", "First", Deck.EMPTY)

        now = 2_000L
        repo.save("d", "Second", Deck(main = listOf(CardId(7))))

        val loaded = assertNotNull(repo.byId("d"))
        assertEquals(1_000L, loaded.entry.createdAtEpochMs)
        assertEquals(2_000L, loaded.entry.updatedAtEpochMs)
        assertEquals("Second", loaded.entry.name)
    }

    @Test
    fun listsDecksMostRecentlyUpdatedFirst() = runTest {
        var now = 1_000L
        val repo = DeckRepository(database(), clock = { now })
        repo.save("a", "Older", Deck.EMPTY)
        now = 9_000L
        repo.save("b", "Newer", Deck.EMPTY)

        assertEquals(listOf("Newer", "Older"), repo.all().map { it.entry.name })
    }

    @Test
    fun hasAnySaysWhetherTheLibraryHoldsADeck() = runTest {
        val repo = DeckRepository(database(), clock = { 1L })
        assertEquals(repo.all().isNotEmpty(), repo.hasAny())
        repo.save("d", "One", Deck.EMPTY)
        assertTrue(repo.hasAny())
        assertEquals(repo.all().isNotEmpty(), repo.hasAny())
        repo.delete("d")
        assertEquals(false, repo.hasAny())
    }

    @Test
    fun renameAndDelete() = runTest {
        val repo = DeckRepository(database(), clock = { 1L })
        repo.save("d", "Before", Deck.EMPTY)

        repo.rename("d", "After")
        assertEquals("After", repo.byId("d")?.entry?.name)

        repo.delete("d")
        assertNull(repo.byId("d"))
    }

    @Test
    fun emptyDeckRoundTripsWithoutProducingPhantomCards() = runTest {
        val repo = DeckRepository(database(), clock = { 1L })
        repo.save("empty", "Empty", Deck.EMPTY)

        val loaded = assertNotNull(repo.byId("empty"))
        assertTrue(loaded.entry.deck.isEmpty)
        assertEquals(0, loaded.entry.deck.main.size)
    }

    // ---- Phase B: release data ----------------------------------------------

    @Test
    fun syncKeepsEachCardsReleaseData() = runTest {
        val repo = CardRepository(database(), apiReturning(sampleFeed), clock = { 1_000L })
        repo.sync()
        val ash = assertNotNull(repo.index.value.byId(CardId(14558127)))
        assertEquals(12950, ash.konamiId)
        assertEquals("2017-05-04", ash.tcgDate)
        assertEquals("2017-01-14", ash.ocgDate)
        assertEquals(listOf("Common Charity", "TCG", "OCG", "Master Duel"), ash.formats)
        assertEquals(20, ash.genesysPoints)
        // A card the feed sent no misc_info for knows nothing, and says so by its empty fields.
        val talker = assertNotNull(repo.index.value.byId(CardId(86066372)))
        assertEquals(emptyList(), talker.formats)
        assertEquals(null, talker.tcgDate)
    }

    @Test
    fun thePoolIsAskedForWithItsReleaseData() = runTest {
        var asked: String? = null
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("cardinfo.php")) asked = request.url.encodedQuery
            respond(ByteReadChannel(sampleFeed), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        YgoProDeckApi(HttpClientFactory.create(engine)).fetchAllCards()
        assertEquals("misc=yes&format=genesys", asked)
    }

    @Test
    fun aPoolStoredWithoutReleaseDataIsFetchedOnceMoreWhateverItsAge() = runTest {
        val db = database()
        var fetches = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("cardinfo.php")) fetches++
            respond(ByteReadChannel(sampleFeed), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = YgoProDeckApi(HttpClientFactory.create(engine))
        CardRepository(db, api, clock = { 1_000L }).sync(force = true)
        assertEquals(1, fetches)
        // As 1.0.99 left it: a fresh pool, but its record written before `misc` existed.
        db.preferenceQueries.upsert(PoolRecord.KEY, """{"version":"147.20","bytes":10}""")
        val again = CardRepository(db, api, clock = { 2_000L }).sync()
        assertIs<SyncResult.Updated>(again)
        assertEquals(2, fetches)
        // And once it has the data, a young pool is left alone again.
        assertIs<SyncResult.UpToDate>(CardRepository(db, api, clock = { 3_000L }).sync())
        assertEquals(2, fetches)
    }
}
