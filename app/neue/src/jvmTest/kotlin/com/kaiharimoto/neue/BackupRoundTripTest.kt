package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.backup.BackupManifest
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.start.StartPrefs
import com.kaiharimoto.neue.backup.BackupCenter
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `.nmtbackup` written and read back (Phase I, I.4; `docs/formats/NMTBACKUP.md`): a backup made by hand, the work
 * changed, the backup restored — the deck back as it was, its payload's unknown keys byte for byte; Ai's notes, the duel's
 * replays and Shootout's trials back; the settings back but this device's own; the keys never in the file.
 */
class BackupRoundTripTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun waitFor(what: String, ms: Long = 30_000, done: () -> Boolean) = runBlocking {
        withTimeout(ms) { while (!done()) delay(25) }
        assertTrue(done(), what)
    }

    /**
     * Only in the tests' own folder (`neue/build.gradle.kts` points the data folder there): a backup keeps ten, so a run on
     * the person's real folder would let one of their backups go.
     */
    private fun inTestsFolder(): Boolean = Platform.dataDir.invariantSeparatorsPath.contains("/build/test-home/").also {
        if (!it) println("BackupRoundTripTest: not run, the data folder ${Platform.dataDir} is not the tests' own")
    }

    @Test
    fun aBackupRestoresTheWorkAsItWasAndNeverCarriesTheKeys() {
        if (!inTestsFolder()) return
        val h = TestHolders.make(scope)
        val b = h.backups
        val id = "backup-" + UUID.randomUUID()
        val extended = buildJsonObject {
            put("groups", JsonPrimitive("kept as written"))
            put("aFutureKey", buildJsonObject { put("n", 1) })
        }
        val deck = Deck(listOf(14558127, 14558127, 27204311).map(::CardId), listOf(86066372).map(::CardId), listOf(10045474).map(::CardId))
        runBlocking { h.deps.deckRepository.save(id, "Round trip", deck, extended, notes = "kept notes") }
        val stamp = UUID.randomUUID().toString()
        val memory = File(h.ai.files.root, "MEMORY.md")
        val memoryWas = memory.takeIf { it.isFile }?.readText()
        memory.apply { parentFile.mkdirs(); writeText("- $stamp\n") }
        val replay = File(Platform.dataDir, "duel/replays/rt-$stamp.json").apply { parentFile.mkdirs(); writeText("""{"stamp":"$stamp"}""") }
        val trials = File(Platform.dataDir, "shootout/$id/alone.json").apply { parentFile.mkdirs(); writeText("""{"stamp":"$stamp"}""") }
        // This device's secrets, the command-line apps' folder and the keys' old place in Ai's folder: never in a backup.
        val secret = File(Platform.dataDir, "secrets/rt-$stamp.bin").apply { parentFile.mkdirs(); writeText("key") }
        val cli = File(Platform.dataDir, "cli-run/rt-$stamp.txt").apply { parentFile.mkdirs(); writeText("run") }
        val oldKey = File(h.ai.files.root, "credentials.rt-$stamp").apply { writeText("key") }
        h.neue.update { it.copy(foil = "rt-$stamp") }
        val before = b.list().size

        b.backUpNow()
        waitFor("a backup was made") { b.list().size > before && b.working == null }
        val made = b.list().first()
        val names = ZipFile(made.file).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.last() == BackupManifest.NAME, "the manifest is the last entry, found through the zip's directory")
        assertTrue("decks/$id.json" in names, names.toString())
        assertTrue(names.none { it.startsWith("secrets/") || it.startsWith("cli-run/") || it.contains("credentials.rt-") }, "no key in the backup: $names")
        assertEquals("By hand", made.manifest.reason)

        // The work changed after the backup: the deck gone, the files rewritten, a setting moved.
        runBlocking { h.deps.deckRepository.delete(id) }
        memory.writeText("- changed\n")
        replay.delete()
        trials.writeText("{}")
        h.neue.update { it.copy(foil = "changed", start = StartPrefs(seen = "after-$stamp")) }

        b.restore(made)
        waitFor("the backup was restored") { runBlocking { h.deps.deckRepository.byId(id) } != null && b.working == null }
        val back = assertNotNull(runBlocking { h.deps.deckRepository.byId(id) })
        assertEquals("Round trip", back.entry.name)
        assertEquals(deck, back.entry.deck)
        assertEquals("kept notes", back.entry.notes)
        assertEquals(extended, back.extended, "the payload's keys, the app's and one it does not know, as they were")
        assertEquals("- $stamp\n", memory.readText())
        assertEquals("""{"stamp":"$stamp"}""", replay.readText())
        assertEquals("""{"stamp":"$stamp"}""", trials.readText())
        waitFor("the settings were restored") { h.neue.prefs.foil == "rt-$stamp" }
        assertEquals("after-$stamp", h.neue.prefs.start.seen, "this device's own setup is never taken from a backup")
        // Restoring made a backup of the work as it stood first, so a restore can be undone.
        assertTrue(b.list().any { it.manifest.reason == "Before restoring" })
        assertFalse(names.any { it.endsWith(".tmp") })

        listOf(secret, cli, oldKey, replay).forEach { it.delete() }
        if (memoryWas != null) memory.writeText(memoryWas) else memory.delete()
        trials.parentFile.deleteRecursively()
        b.list().filter { it.file.lastModified() >= made.file.lastModified() }.forEach { it.file.delete() }
    }

    /**
     * A backup as 1.0.69 wrote one (a manifest without the device or the count of files, a deck without its Side Deck or
     * notes) restores; a name that is no backup's, that climbs out of the folder, or that is a key, a planted setting, a take
     * or a device's own cache is never written; an entry no build knows is passed over.
     */
    @Test
    fun anOldBackupRestoresAndNoUnsafeNameIsWritten() {
        if (!inTestsFolder()) return
        val h = TestHolders.make(scope)
        val b = h.backups
        val id = "old-" + UUID.randomUUID()
        val zip = File(b.dir.apply { mkdirs() }, "hand-built-$id.nmtbackup")
        val unsafe = listOf("../outside-$id", "secrets/k-$id", "ai/credentials.json", ".claude/settings.json", "present/p-$id/takes/a.mkv", "fxcache/v-$id.json")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            fun add(name: String, text: String) { out.putNextEntry(java.util.zip.ZipEntry(name)); out.write(text.encodeToByteArray()); out.closeEntry() }
            add("decks/$id.json", """{"id":"$id","name":"Old shape","main":[14558127,27204311],"extra":[86066372]}""")
            unsafe.forEach { add(it, "planted") }
            add("from-a-newer-build.json", "{}")
            add(BackupManifest.NAME, """{"version":"1.0.69","at":1760000000000,"reason":"By hand","decks":1}""")
        }
        b.restore(BackupCenter.Entry(zip, BackupManifest()))
        waitFor("the old backup was restored") { runBlocking { h.deps.deckRepository.byId(id) } != null && b.working == null }
        val back = assertNotNull(runBlocking { h.deps.deckRepository.byId(id) })
        assertEquals("Old shape", back.entry.name)
        assertEquals(listOf(14558127, 27204311), back.entry.deck.main.map { it.value })
        assertTrue(back.entry.deck.side.isEmpty())
        assertEquals("", back.entry.notes)
        assertFalse(File(Platform.dataDir.parentFile, "outside-$id").exists(), "nothing climbs out of the folder")
        assertFalse(File(Platform.dataDir, "secrets/k-$id").exists(), "no key is planted")
        assertFalse(File(h.ai.files.root, "credentials.json").readTextOrNull() == "planted", "no key is planted in Ai's folder")
        assertFalse(File(Platform.dataDir, ".claude/settings.json").readTextOrNull() == "planted", "no setting a command-line app reads is planted")
        assertFalse(File(Platform.dataDir, "present/p-$id/takes/a.mkv").exists(), "a take is this device's own")
        assertFalse(File(Platform.dataDir, "fxcache/v-$id.json").exists(), "a device's cache is its own")
        assertFalse(File(Platform.dataDir, "from-a-newer-build.json").exists())
        runBlocking { h.deps.deckRepository.delete(id) }
        zip.delete()
    }

    private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()
}
