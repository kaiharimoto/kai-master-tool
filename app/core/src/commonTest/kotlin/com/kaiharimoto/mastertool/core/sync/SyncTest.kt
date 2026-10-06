package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store in memory: what a folder or a server holds. */
class MemoryStore : SyncStore {
    val files = LinkedHashMap<String, ByteArray>()
    override val label = "memory"
    override suspend fun list(folder: String) = files.keys.filter { it.startsWith("$folder/") }.map { it.removePrefix("$folder/") }
    override suspend fun read(name: String) = files[name]
    override suspend fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    override suspend fun delete(name: String) { files.remove(name) }
}

/** A device's own items in memory, each with when it last changed. */
class MemoryLocal(private val clock: () -> Long) : SyncLocal {
    val items = LinkedHashMap<String, Pair<ByteArray, Long>>()
    val copies = mutableListOf<Pair<String, String>>()

    fun put(path: String, text: String) { items[path] = text.encodeToByteArray() to clock() }
    fun text(path: String) = items[path]?.first?.decodeToString()

    override suspend fun snapshot() = items.mapValues { (_, v) -> LocalItem.of(v.first, v.second) }
    override suspend fun apply(path: String, bytes: ByteArray?) {
        if (bytes == null) items.remove(path) else items[path] = bytes to clock()
    }
    override suspend fun keepCopy(path: String, bytes: ByteArray, from: String): Boolean {
        if (!path.startsWith("decks/")) return false
        copies += path to from
        items[path.removeSuffix(".json") + "-copy.json"] = bytes to clock()
        return true
    }
}

class SyncTest {
    private var now = 1_000L
    private val clock = { now++ }

    private inner class Device(val id: String, val store: SyncStore) {
        val local = MemoryLocal(clock)
        var state = SyncState(id)
        suspend fun sync(): SyncReport {
            val (s, r) = SyncEngine(store, local, id, "$id's device", clock).run(state)
            state = s
            return r
        }
    }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }))
    }

    @Test
    fun eachCaseIsDecidedThreeWays() {
        val x = Version("x", 10, "a")
        val y = Version("y", 20, "b")
        val gone = Version("", 30, "b", deleted = true)
        fun meta(h: String, at: Long = 15) = SyncPlan.LocalMeta(h, at)
        fun one(b: Version?, l: SyncPlan.LocalMeta?, r: Version?) =
            SyncPlan.plan(listOfNotNull(b?.let { "p" to it }).toMap(), listOfNotNull(l?.let { "p" to it }).toMap(), listOfNotNull(r?.let { "p" to it }).toMap()).singleOrNull()

        assertNull(one(x, meta("x"), x), "nothing changed")
        assertIs<SyncPlan.Step.Send>(one(null, meta("x"), null), "new here")
        assertIs<SyncPlan.Step.Send>(one(x, meta("z"), x), "changed here")
        assertIs<SyncPlan.Step.Tombstone>(one(x, null, x), "deleted here")
        assertEquals(SyncPlan.Step.Take("p", y), one(x, meta("x"), y), "changed there")
        assertEquals(SyncPlan.Step.Take("p", gone), one(x, meta("x"), gone), "deleted there")
        assertIs<SyncPlan.Step.Agree>(one(x, meta("y"), y), "both made the same")
        assertIs<SyncPlan.Step.Send>(one(x, meta("x"), null), "the store was emptied")
        val c = assertIs<SyncPlan.Step.Conflict>(one(x, meta("z", at = 25), y))
        assertTrue(c.localWins, "the newer edit wins")
        assertIs<SyncPlan.Step.Take>(one(x, null, y), "an edit there beats a deletion here")
        assertIs<SyncPlan.Step.Send>(one(x, meta("z"), gone), "an edit here beats a deletion there")
        val first = assertIs<SyncPlan.Step.Conflict>(one(null, meta("z", at = 99), y))
        assertTrue(!first.localWins, "a device's first sync takes what the store holds")
    }

    @Test
    fun twoDevicesMeetThroughTheStore() = runTest {
        val store = MemoryStore()
        val laptop = Device("laptop", store)
        val phone = Device("phone", store)
        laptop.local.put("decks/a.json", "{\"name\":\"Labrynth\"}")
        laptop.local.put("ai/MEMORY.md", "notes")
        assertEquals(2, laptop.sync().sent)
        assertEquals(2, phone.sync().received)
        assertEquals("notes", phone.local.text("ai/MEMORY.md"))

        // An edit on the phone reaches the laptop; nothing comes back the other way.
        phone.local.put("ai/MEMORY.md", "notes, more")
        assertEquals(1, phone.sync().sent)
        assertEquals(1, laptop.sync().received)
        assertEquals("notes, more", laptop.local.text("ai/MEMORY.md"))
        assertTrue(laptop.sync().nothing)
        assertTrue(phone.sync().nothing)

        // A deletion travels too.
        laptop.local.items.remove("decks/a.json")
        laptop.sync()
        phone.sync()
        assertNull(phone.local.text("decks/a.json"))

        // The same content is one file in the store.
        phone.local.put("ai/x.md", "same")
        laptop.local.put("ai/y.md", "same")
        phone.sync()
        laptop.sync()
        assertEquals(1, store.files.keys.count { it.startsWith("blobs/") && store.files[it]!!.decodeToString() == "same" })
        // Each device wrote only its own manifest.
        assertEquals(setOf("devices/laptop.json", "devices/phone.json"), store.files.keys.filter { it.startsWith("devices/") }.toSet())
    }

    @Test
    fun aDeckBothChangedIsKeptTwice() = runTest {
        val store = MemoryStore()
        val laptop = Device("laptop", store)
        val phone = Device("phone", store)
        laptop.local.put("decks/a.json", "{\"name\":\"v1\"}")
        laptop.sync()
        phone.sync()
        phone.local.put("decks/a.json", "{\"name\":\"phone\"}")
        laptop.local.put("decks/a.json", "{\"name\":\"laptop\"}")
        phone.sync()
        val r = laptop.sync()
        assertEquals(1, r.conflicts)
        assertEquals(1, r.copies)
        // The laptop's edit came last, so it wins; the phone's is kept beside it.
        assertEquals("{\"name\":\"laptop\"}", laptop.local.text("decks/a.json"))
        assertEquals("{\"name\":\"phone\"}", laptop.local.text("decks/a-copy.json"))
        assertEquals(listOf("decks/a.json" to "phone's device"), laptop.local.copies)
        phone.sync()
        assertEquals("{\"name\":\"laptop\"}", phone.local.text("decks/a.json"))
    }

    @Test
    fun settingsMergeKeyByKey() = runTest {
        val store = MemoryStore()
        val laptop = Device("laptop", store)
        val phone = Device("phone", store)
        laptop.local.put("prefs/neue.json", """{"theme":"PAPER","foil":"holo","covers":{}}""")
        laptop.sync()
        phone.sync()
        laptop.local.put("prefs/neue.json", """{"theme":"INK","foil":"holo","covers":{}}""")
        phone.local.put("prefs/neue.json", """{"theme":"PAPER","foil":"off","covers":{"d":[1]}}""")
        laptop.sync()
        val r = phone.sync()
        assertEquals(1, r.merged)
        val merged = Sync.json.parseToJsonElement(phone.local.text("prefs/neue.json")!!)
        assertEquals(Sync.json.parseToJsonElement("""{"theme":"INK","foil":"off","covers":{"d":[1]}}"""), merged)
        laptop.sync()
        assertEquals(merged, Sync.json.parseToJsonElement(laptop.local.text("prefs/neue.json")!!))
    }

    @Test
    fun aMissingFileStopsTheSyncWithoutAgreeing() = runTest {
        val store = MemoryStore()
        val laptop = Device("laptop", store)
        val phone = Device("phone", store)
        laptop.local.put("ai/a.md", "a")
        laptop.sync()
        store.files.keys.filter { it.startsWith("blobs/") }.forEach { store.files.remove(it) }
        val before = phone.state
        assertFailsWith<SyncException> { phone.sync() }
        assertEquals(before, phone.state)
    }

    @Test
    fun jsonMergeKeepsWhatEachSideChanged() {
        val base = buildJsonObject { put("a", 1); put("b", 1); put("c", 1) }
        val local = buildJsonObject { put("a", 2); put("b", 1); put("c", 3) }
        val remote = buildJsonObject { put("a", 1); put("b", 2); put("c", 4); put("d", 5) }
        val m = JsonMerge.threeWay(base, local, remote, localNewer = false)
        assertEquals(JsonPrimitive(2), m["a"])
        assertEquals(JsonPrimitive(2), m["b"])
        assertEquals(JsonPrimitive(4), m["c"])
        assertEquals(JsonPrimitive(5), m["d"])
    }

    @Test
    fun theEffectsLibraryTravelsAndItsVerdictsNever() {
        // Phase D step 2: `effects/` is synced, newer wins; `fxcache/` (verdicts, test runs) is recomputed on each device, so
        // a planted "verified" can never arrive by sync or in a backup.
        assertEquals("effects/900000001.js", InboundPath.safe("effects/900000001.js"))
        assertEquals("effects/900000001.json", InboundPath.safe("effects/900000001.json"))
        listOf("fxcache/verdicts.json", "fxcache/900000001.json", "fxcache/runs/d1.json").forEach { assertNull(InboundPath.safe(it), it) }
        assertTrue(com.kaiharimoto.mastertool.core.duel.effects.FxPaths.syncs("900000001.review.json"))
        assertTrue(!com.kaiharimoto.mastertool.core.duel.effects.FxPaths.syncs("900000001.verdict.json"))
        // The asked list (FxAsks) travels with the library, newer wins: an ask made on the tablet is an ask on the desk.
        assertEquals("effects/asked.json", InboundPath.safe("effects/asked.json"))
        assertTrue(com.kaiharimoto.mastertool.core.duel.effects.FxPaths.syncs(com.kaiharimoto.mastertool.core.duel.effects.FxPaths.ASKED))
        // Phase D step 4: the "played by you" marks and each deck's goldfish (targets, kept results) travel too, newer wins.
        assertEquals("effects/played.json", InboundPath.safe("effects/played.json"))
        assertTrue(com.kaiharimoto.mastertool.core.duel.effects.FxPaths.syncs(com.kaiharimoto.mastertool.core.duel.effects.FxPaths.PLAYED))
        assertEquals("effects/goldfish/d1.json", InboundPath.safe("effects/goldfish/d1.json"))
        assertTrue(com.kaiharimoto.mastertool.core.duel.effects.FxPaths.syncs(com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec.path("d1")))
        assertTrue(!com.kaiharimoto.mastertool.core.duel.effects.FxPaths.syncs("goldfish/d1.json.tmp"))
    }

    @Test
    fun everySettingIsSortedIntoTravelsOrStays() {
        val loose = SyncedPrefs.fields.filter { it !in SyncedPrefs.SYNCED && it !in SyncedPrefs.DEVICE }
        assertTrue(loose.isEmpty(), "Settings neither synced nor kept on the device: $loose. Add each to SyncedPrefs.SYNCED or DEVICE.")
        assertTrue((SyncedPrefs.SYNCED intersect SyncedPrefs.DEVICE).isEmpty())
        val aiLoose = SyncedPrefs.aiFields.filter { it !in SyncedPrefs.AI_SYNCED && it !in SyncedPrefs.AI_DEVICE }
        assertTrue(aiLoose.isEmpty(), "Ai settings unsorted: $aiLoose.")
    }

    @Test
    fun settingsTravelButTheWindowStays() {
        val laptop = NeuePreferences(theme = NeueTheme.INK, deckZoom = 0.6f, covers = mapOf("d" to listOf(1, 2)))
        val phone = NeuePreferences(theme = NeueTheme.PAPER, deckZoom = 1f, orientation = "portrait")
        val after = SyncedPrefs.apply(phone, SyncedPrefs.extract(laptop))
        assertEquals(NeueTheme.INK, after.theme)
        assertEquals(mapOf("d" to listOf(1, 2)), after.covers)
        assertEquals(1f, after.deckZoom)
        assertEquals("portrait", after.orientation)
        // The same settings are always the same bytes: no change to send when nothing changed.
        assertTrue(SyncedPrefs.extract(after).contentEquals(SyncedPrefs.extract(SyncedPrefs.apply(after, SyncedPrefs.extract(after)))))
    }

    /** A store that stamps each file with its content's hash, as Drive's MD5 does, and counts what is asked of it. */
    private class StampedStore : SyncStore {
        val inner = MemoryStore()
        val reads = mutableListOf<String>()
        val lists = mutableListOf<String>()
        override val label = "stamped"
        override suspend fun list(folder: String): List<String> = inner.list(folder).also { lists += folder }
        override suspend fun stamps(folder: String): Map<String, String> =
            inner.files.filterKeys { it.startsWith("$folder/") }.entries.associate { (k, v) -> k.removePrefix("$folder/") to Sha256.hex(v) }
        override suspend fun read(name: String): ByteArray? = inner.read(name).also { reads += name }
        override suspend fun write(name: String, bytes: ByteArray) = inner.write(name, bytes)
        override suspend fun delete(name: String) = inner.delete(name)
    }

    @Test
    fun anUnchangedManifestIsNotReadAgainAndBlobsAreListedOnlyToSend() = runTest {
        val store = StampedStore()
        val cache = ManifestCache()
        val laptop = MemoryLocal(clock)
        var laptopState = SyncState("laptop")
        suspend fun laptopSync(): SyncReport {
            val (s, r) = SyncEngine(store, laptop, "laptop", "laptop's device", clock, cache).run(laptopState)
            laptopState = s
            return r
        }
        val phone = Device("phone", store)

        laptop.put("decks/a.json", "{\"name\":\"Labrynth\"}")
        assertEquals(1, laptopSync().sent)
        phone.local.put("ai/MEMORY.md", "notes")
        phone.sync()

        // Nothing new anywhere: both manifests are known by their stamps (the laptop's own read once
        // more after it rewrote it), and no blob is listed.
        assertEquals(1, laptopSync().received)
        laptopSync()
        store.reads.clear()
        store.lists.clear()
        val idle = laptopSync()
        assertTrue(idle.nothing)
        assertTrue(store.reads.none { it.startsWith("${Sync.DEVICES}/") }, "read again: ${store.reads}")
        assertTrue(Sync.BLOBS !in store.lists, "blobs listed with nothing to send")

        // The phone changes something: its manifest's stamp moves, so it is read, and what changed arrives.
        phone.local.put("ai/MEMORY.md", "notes, more")
        phone.sync()
        store.reads.clear()
        assertEquals(1, laptopSync().received)
        assertTrue("${Sync.DEVICES}/phone.json" in store.reads)
        assertEquals("notes, more", laptop.text("ai/MEMORY.md"))

        // Something to send lists the blobs once, and an uncached sync then agrees there is nothing left.
        laptop.put("decks/b.json", "{\"name\":\"Yubel\"}")
        store.lists.clear()
        assertEquals(1, laptopSync().sent)
        assertEquals(1, store.lists.count { it == Sync.BLOBS })
        val fresh = SyncEngine(store, laptop, "laptop", "laptop's device", clock).run(laptopState)
        assertTrue(fresh.second.nothing)
        assertEquals(laptopState.items, fresh.first.items)
        phone.sync()
        assertEquals("{\"name\":\"Yubel\"}", phone.local.text("decks/b.json"))
    }

    @Test
    fun aManifestCacheKeepsOnlyWhatHasAStamp() {
        val cache = ManifestCache()
        val m = Manifest("d", "Desk", 1)
        cache.put("d.json", "s1", m)
        assertEquals(m, cache.get("d.json", "s1"))
        assertNull(cache.get("d.json", "s2"), "a moved stamp is a new manifest")
        assertNull(cache.get("d.json", null), "no stamp, no promise")
        cache.put("d.json", null, m)
        assertNull(cache.get("d.json", "s1"), "read without a stamp: forgotten")
    }
}
