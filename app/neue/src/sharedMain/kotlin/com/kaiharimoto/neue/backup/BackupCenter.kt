package com.kaiharimoto.neue.backup

import com.kaiharimoto.neue.ai.bookChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.backup.BackupDeck
import com.kaiharimoto.mastertool.core.backup.BackupManifest
import com.kaiharimoto.mastertool.core.backup.Backups
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
import com.kaiharimoto.mastertool.core.prep.PrepCodec
import com.kaiharimoto.mastertool.core.web.WebLibrary
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.deliverFile
import com.kaiharimoto.neue.sync.NeueSyncLocal
import com.kaiharimoto.neue.sync.SyncPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backups (1.0.69): made by themselves when a new version first opens — before it changes anything —
 * and once a week, kept in `<data>/backups` (the newest ten), exported as one file, restored from one.
 * What a backup holds is read through the app ([Backups]), so it restores into any later version.
 */
class BackupCenter(private val h: NeueHolders) {
    val dir = File(Platform.dataDir, "backups")
    private val marker = File(dir, "last-version.txt")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var working by mutableStateOf<String?>(null)
        private set

    /** Bumped when the list changes, so Settings reads it again. */
    var revision by mutableStateOf(0)
        private set

    data class Entry(val file: File, val manifest: BackupManifest)

    fun list(): List<Entry> = files().map(::entry)

    /** The backups in [dir], newest first, unread. */
    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.extension == Backups.EXTENSION }.orEmpty()
        .sortedByDescending { it.name }

    /** What each backup file said about itself, by name, while its size and time are what they were. */
    private class Read(val length: Long, val modified: Long, val manifest: BackupManifest?)
    private val manifests = ConcurrentHashMap<String, Read>()

    private fun entry(f: File): Entry {
        val length = f.length()
        val modified = f.lastModified()
        val known = manifests[f.name]?.takeIf { it.length == length && it.modified == modified }
        val manifest = known?.manifest ?: readManifest(f).also { manifests[f.name] = Read(length, modified, it) }
        return Entry(f, manifest ?: BackupManifest(at = modified, reason = f.nameWithoutExtension.substringAfter(' ')))
    }

    /** As the app opens: a backup first, when this is a new version over existing work, or a week has passed. */
    fun onOpen() {
        scope.launch {
            lock.withLock {
                val last = runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
                val hasData = h.deps.deckRepository.hasAny() || File(h.ai.files.root, "MEMORY.md").isFile
                // Only the newest is read: the others' manifests are not needed to decide.
                val lastAt = files().firstOrNull()?.let(::entry)?.manifest?.at
                val reason = Backups.due(last, Platform.version, lastAt, h.deps.now(), hasData)
                if (reason != null) runCatching { write(reason) }
                dir.mkdirs()
                runCatching { marker.writeText(Platform.version) }
            }
        }
    }

    fun backUpNow() {
        scope.launch {
            lock.withLock {
                val made = runCatching { write("By hand") }
                h.neue.note = Note(if (made.isSuccess) "Backed up" else "The backup could not be made")
            }
        }
    }

    /** The newest backup, or a fresh one, handed to the person to keep elsewhere. */
    fun export() {
        scope.launch {
            val file = lock.withLock { runCatching { write("Export") }.getOrNull() }
            if (file == null) {
                h.neue.note = Note("The backup could not be made")
                return@launch
            }
            withContext(Dispatchers.Main) { deliverFile(file.name, "application/zip", file.readBytes())?.let { h.neue.note = Note(it) } }
        }
    }

    /** A backup file the person picks, restored. */
    fun restoreFromFile() {
        scope.launch {
            val picked = withContext(Dispatchers.Main) { Platform.pick("Choose a backup to restore", setOf(Backups.EXTENSION, "zip")) } ?: return@launch
            restore(picked.bytes)
        }
    }

    fun restore(entry: Entry) {
        scope.launch { restore(entry.file.readBytes()) }
    }

    /**
     * Puts a backup's work back: every deck in it as it was there, its settings, webs, prep, Ai's notes
     * and pictures. Nothing made since is deleted — a deck made after the backup stays — and a backup of
     * how things are now is made first, so a restore can itself be undone.
     */
    private suspend fun restore(bytes: ByteArray) = lock.withLock {
        working = "Restoring"
        try {
            val entries = unzip(bytes)
            val manifest = entries[BackupManifest.NAME]?.let { readManifest(it) } ?: run {
                h.neue.note = Note("That file is not a Neue Master Tool backup")
                return@withLock
            }
            write("Before restoring")
            var decks = 0
            entries.forEach { (name, data) ->
                when {
                    name.startsWith("decks/") -> runCatching { Backups.json.decodeFromString(BackupDeck.serializer(), data.decodeToString()) }.getOrNull()?.let { d ->
                        h.deps.deckRepository.save(d.id, d.name, Deck(d.main.map(::CardId), d.extra.map(::CardId), d.side.map(::CardId)), d.extended, d.notes)
                        decks++
                    }
                    name.startsWith("ai/") && safe(name) -> put(File(h.ai.files.root, name.removePrefix("ai/")), data)
                    name.startsWith("custom-art/") && safe(name) -> put(File(Platform.dataDir, name), data)
                    name.startsWith("present/") && safe(name) -> put(File(Platform.dataDir, name), data)
                    name.startsWith("duel/") && safe(name) -> put(File(Platform.dataDir, name), data)
                }
            }
            val neue = entries[NEUE]?.let { runCatching { Backups.json.decodeFromString(NeuePreferences.serializer(), it.decodeToString()) }.getOrNull() }
            val ui = entries[LAYOUT]?.let { runCatching { Backups.json.decodeFromString(UiPreferences.serializer(), it.decodeToString()) }.getOrNull() }
            val webs = entries[WEBS]?.let { runCatching { Backups.json.decodeFromString(WebLibrary.serializer(), it.decodeToString()) }.getOrNull() }
            val prep = entries[PREP]?.let { PrepCodec.decode(it.decodeToString()) }
            withContext(Dispatchers.Main) {
                // This device's own sync and setup stay: a backup from another device must not move them.
                neue?.let { restored -> h.neue.update { restored.copy(sync = it.sync, start = it.start, window = it.window) } }
                ui?.let { restored ->
                    h.layout.update { restored }
                    h.setFormat(restored.format)
                    h.setSearchEffects(restored.searchEffects)
                }
                webs?.let { h.webs.restore(it) }
                prep?.let { h.prep.commit(it) }
                h.decksReload++
                h.builder.deckId?.let { id -> if (!h.builder.dirty) h.builder.load(id) }
                h.ai.bookChanged()
                h.customArt.reload()
                h.present.reload()
                h.duel.reload()
                h.duel.reloadRulings()
                h.neue.note = Note("Restored $decks decks from ${date(manifest.at)}")
            }
        } catch (e: Exception) {
            h.neue.note = Note("The backup could not be restored: ${e.message ?: e::class.simpleName}")
        } finally {
            working = null
            revision++
        }
    }

    /** One backup written into [dir], the oldest let go past ten; the file. */
    private suspend fun write(reason: String): File {
        working = "Backing up"
        try {
            val now = h.deps.now()
            dir.mkdirs()
            val target = File(dir, Backups.fileName(STAMP.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())), reason))
            val temp = File(dir, ".${target.name}.tmp")
            var decks = 0
            var files = 0
            // Prep's last typed change, written before the stored document is read.
            h.prep.settle()
            // Written straight to the temporary file — the same entries in the same order as when the
            // whole zip was built in memory first, without holding it (twice) as it was copied out.
            withContext(Dispatchers.IO) {
                try {
                    ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                        fun add(name: String, data: ByteArray) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(data)
                            zip.closeEntry()
                        }
                        h.deps.deckRepository.all().forEach { d ->
                            val e = d.entry
                            add(
                                "decks/${e.id}.json",
                                Backups.json.encodeToString(
                                    BackupDeck.serializer(),
                                    BackupDeck(e.id, e.name, e.deck.main.map { it.value }, e.deck.extra.map { it.value }, e.deck.side.map { it.value }, e.notes, d.extended, e.createdAtEpochMs, e.updatedAtEpochMs),
                                ).encodeToByteArray(),
                            )
                            decks++
                        }
                        add(NEUE, Backups.json.encodeToString(NeuePreferences.serializer(), h.neue.prefs).encodeToByteArray())
                        add(LAYOUT, Backups.json.encodeToString(UiPreferences.serializer(), h.layout.preferences).encodeToByteArray())
                        add(WEBS, Backups.json.encodeToString(WebLibrary.serializer(), h.deps.preferencesRepository.loadWebs()).encodeToByteArray())
                        add(PREP, PrepCodec.encode(h.deps.preferencesRepository.loadPrep()).encodeToByteArray())
                        tree(h.ai.files.root).filter { !NeueSyncLocal.privateToDevice(it.first) }.forEach { (rel, f) -> add("ai/$rel", f.readBytes()); files++ }
                        tree(File(Platform.dataDir, "custom-art")).forEach { (rel, f) -> add("custom-art/$rel", f.readBytes()); files++ }
                        tree(File(Platform.dataDir, "present")).filter { !it.first.endsWith(".tmp") }.forEach { (rel, f) -> add("present/$rel", f.readBytes()); files++ }
                        // The duel in play (1.0.74), and later its replays and combos.
                        tree(File(Platform.dataDir, "duel")).filter { !it.first.endsWith(".tmp") }.forEach { (rel, f) -> add("duel/$rel", f.readBytes()); files++ }
                        add(
                            BackupManifest.NAME,
                            Backups.json.encodeToString(
                                BackupManifest.serializer(),
                                BackupManifest(version = Platform.version, at = now, reason = reason, device = SyncPlatform.deviceName, decks = decks, files = files),
                            ).encodeToByteArray(),
                        )
                    }
                } catch (e: Throwable) {
                    temp.delete()
                    throw e
                }
            }
            return withContext(Dispatchers.IO) {
                temp.renameTo(target)
                Backups.toDelete(dir.listFiles { f -> f.isFile && f.extension == Backups.EXTENSION }.orEmpty().map { it.name }).forEach { File(dir, it).delete() }
                revision++
                target
            }
        } finally {
            working = null
        }
    }

    private fun tree(root: File): List<Pair<String, File>> =
        if (!root.isDirectory) emptyList() else root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) put(e.name, zip.readBytes())
            }
        }
    }

    /**
     * The manifest is a backup's last entry, so it is found through the zip's central directory —
     * one seek — rather than by inflating every entry before it; a file whose directory cannot be
     * read is walked as before.
     */
    private fun readManifest(f: File): BackupManifest? = runCatching {
        ZipFile(f).use { zip -> zip.getEntry(BackupManifest.NAME)?.let { e -> zip.getInputStream(e).use { readManifest(it.readBytes()) } } }
    }.getOrElse { walkManifest(f) }

    private fun walkManifest(f: File): BackupManifest? = runCatching {
        ZipInputStream(f.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == BackupManifest.NAME }?.let { readManifest(zip.readBytes()) }
        }
    }.getOrNull()

    private fun readManifest(bytes: ByteArray): BackupManifest? = runCatching { Backups.json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }.getOrNull()

    private suspend fun put(target: File, data: ByteArray) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        target.writeBytes(data)
    }

    /** A name inside its own folder: nothing in a backup may write outside the app's. */
    private fun safe(name: String) = ".." !in name.split('/') && !name.startsWith("/") && !NeueSyncLocal.privateToDevice(name.substringAfter('/'))

    companion object {
        private const val NEUE = "prefs/neue.ui.json"
        private const val LAYOUT = "prefs/deckbuilder.ui.json"
        private const val WEBS = "prefs/neue.webs.json"
        private const val PREP = "prefs/neue.prep.json"
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
        private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH)

        fun date(at: Long): String = DAY.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    }
}
