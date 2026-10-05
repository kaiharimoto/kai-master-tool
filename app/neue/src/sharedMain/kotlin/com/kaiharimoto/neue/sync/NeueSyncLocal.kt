package com.kaiharimoto.neue.sync

import com.kaiharimoto.mastertool.core.prep.PrepCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.sync.InboundPath
import com.kaiharimoto.mastertool.core.sync.LocalItem
import com.kaiharimoto.mastertool.core.sync.Sha256
import com.kaiharimoto.mastertool.core.sync.Sync
import com.kaiharimoto.mastertool.core.sync.SyncLocal
import com.kaiharimoto.mastertool.core.sync.SyncedDeck
import com.kaiharimoto.mastertool.core.sync.SyncedPrefs
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.mastertool.core.present.record.TakePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * This device's side of a sync (1.0.68): everything that is the person's, as items —
 * - `decks/<id>.json`: every saved deck ([SyncedDeck]);
 * - `prefs/neue.json` and `prefs/format.json`: the settings that travel ([SyncedPrefs]);
 * - `webs/<id>.json`: each web of decks, `prefs/prep.json`: tournament prep;
 * - `ai/…`: Ai's memory, guides, books, reports, skills and conversations — never its keys,
 *   the CLIs' working folder (since 1.0.99 `secrets/` and `cli-run/`, outside `ai/` and never
 *   walked; their pre-1.0.99 places `ai/credentials.*` and `ai/run/` still left out) or its caches;
 * - `art/<passcode>/<file>`: the pictures the person gave cards.
 *
 * Card art downloaded from YGOPRODeck and the card pool are not items: every device fetches its own.
 * What comes in is applied where it lives and the screens that show it are told ([changed]).
 */
class NeueSyncLocal(private val h: NeueHolders, private val seen: SeenTimes) : SyncLocal {
    private val ai get() = h.ai.files.root
    private val art = File(Platform.dataDir, "custom-art")

    /** Present's presentations and their pictures (1.0.70): files, the newer one kept. */
    private val present = File(Platform.dataDir, "present")

    /** Duel's replays (1.0.75) and combos (1.0.76): files, the newer one kept. The duel in play is this device's own. */
    private val duel = File(Platform.dataDir, "duel")

    /** Ai World's worlds (1.0.97): files, the newer one kept; the Python helper's folder is rewritten before every run. */
    private val world = File(Platform.dataDir, "world")

    /** Shootout's trials (1.1.2): one file per deck and target, the newer one kept. */
    private val shootout = File(Platform.dataDir, ShootoutPaths.FOLDER)

    /** What came in this sync, so the screens showing it can be told once at the end. */
    val changed = mutableSetOf<String>()

    override suspend fun snapshot(): Map<String, LocalItem> {
        val out = HashMap<String, LocalItem>()
        h.deps.deckRepository.all().forEach { d ->
            out[SyncedDeck.path(d.entry.id)] = LocalItem.of(SyncedDeck.of(d).bytes(), d.entry.updatedAtEpochMs)
        }
        fun noted(path: String, bytes: ByteArray) {
            out[path] = LocalItem(Sha256.hex(bytes), seen.at(path, Sha256.hex(bytes))) { bytes }
        }
        noted(SyncedPrefs.PATH, SyncedPrefs.extract(h.neue.prefs))
        noted(SyncedPrefs.FORMAT_PATH, SyncedPrefs.extractFormat(h.layout.preferences))
        if (h.webs.loaded) h.webs.library.webs.forEach { w ->
            out[webPath(w.id)] = LocalItem.of(Sync.json.encodeToString(DeckWeb.serializer(), w).encodeToByteArray(), w.updatedAtEpochMs)
        }
        if (h.prep.loaded) noted(PREP, PrepCodec.encode(h.prep.doc).encodeToByteArray())
        withContext(Dispatchers.IO) {
            files(ai, "ai/") { rel -> !privateToDevice(rel) }.forEach { (path, f) -> out[path] = seen.file(path, f) }
            files(art, "art/") { true }.forEach { (path, f) -> out[path] = seen.file(path, f) }
            // Takes (1.1.13) are this device's own: minutes of camera, never synced (`TakePaths.syncs`).
            files(present, "present/") { rel -> !rel.endsWith(".tmp") && TakePaths.syncs(rel) }.forEach { (path, f) -> out[path] = seen.file(path, f) }
            files(duel, "duel/") { rel -> !rel.endsWith(".tmp") && rel.substringAfterLast('/') != Duels.CURRENT }.forEach { (path, f) -> out[path] = seen.file(path, f) }
            files(world, "world/") { rel -> worldSyncs(rel) }.forEach { (path, f) -> out[path] = seen.file(path, f) }
            files(shootout, "${ShootoutPaths.FOLDER}/") { rel -> !rel.endsWith(".tmp") }.forEach { (path, f) -> out[path] = seen.file(path, f) }
        }
        return out
    }

    override suspend fun apply(path: String, bytes: ByteArray?) {
        requireNotNull(InboundPath.safe(path)) { "A path this device does not take: $path" }
        when {
            path.startsWith(SyncedDeck.FOLDER) -> {
                val id = SyncedDeck.idOf(path) ?: return
                // The deck open with edits not yet saved: what came in is kept beside it, never under it,
                // so the next save cannot quietly write over another device's work.
                if (id == h.builder.deckId && h.builder.dirty) {
                    if (bytes != null) keepCopy(path, bytes, "another device")
                    return
                }
                if (bytes == null) h.deps.deckRepository.delete(id)
                else SyncedDeck.read(bytes)?.let { d -> h.deps.deckRepository.save(id, d.name, d.deck, d.extended, d.notes) }
                changed += "decks"
                changed += "deck:$id"
            }
            path == SyncedPrefs.PATH -> if (bytes != null) {
                withContext(Dispatchers.Main) { h.neue.update { SyncedPrefs.apply(it, bytes) } }
                seen.agree(path, SyncedPrefs.extract(h.neue.prefs))
            }
            path == SyncedPrefs.FORMAT_PATH -> if (bytes != null) {
                val next = SyncedPrefs.applyFormat(h.layout.preferences, bytes)
                withContext(Dispatchers.Main) {
                    if (next.format != h.layout.preferences.format) h.setFormat(next.format)
                    if (next.searchEffects != h.layout.preferences.searchEffects) h.setSearchEffects(next.searchEffects)
                }
                seen.agree(path, SyncedPrefs.extractFormat(h.layout.preferences))
            }
            path.startsWith(WEBS) -> {
                val id = path.removePrefix(WEBS).removeSuffix(".json")
                val web = bytes?.let { runCatching { Sync.json.decodeFromString(DeckWeb.serializer(), it.decodeToString()) }.getOrNull() }
                withContext(Dispatchers.Main) { if (web != null) h.webs.adopt(web) else h.webs.forget(id) }
                changed += "webs"
            }
            path == PREP -> if (bytes != null) {
                withContext(Dispatchers.Main) { h.prep.commit(PrepCodec.decode(bytes.decodeToString())) }
                seen.agree(path, PrepCodec.encode(h.prep.doc).encodeToByteArray())
            }
            path.startsWith("ai/") -> {
                write(File(ai, path.removePrefix("ai/")), bytes)
                seen.forget(path)
                changed += "ai"
            }
            path.startsWith("art/") -> {
                write(File(art, path.removePrefix("art/")), bytes)
                seen.forget(path)
                changed += "art"
            }
            path.startsWith("present/") && TakePaths.syncs(path.removePrefix("present/")) -> {
                write(File(present, path.removePrefix("present/")), bytes)
                seen.forget(path)
                changed += "present"
            }
            path.startsWith("world/") && worldSyncs(path.removePrefix("world/")) -> {
                write(File(world, path.removePrefix("world/")), bytes)
                seen.forget(path)
                changed += "world"
            }
            path.startsWith("${ShootoutPaths.FOLDER}/") -> {
                write(File(shootout, path.removePrefix("${ShootoutPaths.FOLDER}/")), bytes)
                seen.forget(path)
                changed += "shootout"
            }
            path.startsWith("duel/") && path != "duel/${com.kaiharimoto.neue.duel.Duels.CURRENT}" -> {
                write(File(duel, path.removePrefix("duel/")), bytes)
                seen.forget(path)
                changed += "replays"
            }
        }
    }

    /** A deck both devices changed: the one that lost is kept beside the other, "Labrynth (from Kai's phone)". */
    override suspend fun keepCopy(path: String, bytes: ByteArray, from: String): Boolean {
        if (SyncedDeck.idOf(path) == null) return false
        val d = SyncedDeck.read(bytes) ?: return false
        h.deps.deckRepository.save(h.deps.newDeckId(), "${d.name} (from $from)", d.deck, d.extended, d.notes)
        changed += "decks"
        return true
    }

    private suspend fun write(target: File, bytes: ByteArray?) = withContext(Dispatchers.IO) {
        // Belt and braces: whatever the path said, the file stays inside the data folder.
        require(target.canonicalPath.startsWith(Platform.dataDir.canonicalPath + File.separator)) { "A path that leaves the data folder: $target" }
        if (bytes == null) {
            target.delete()
            return@withContext
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** Every file under [root] as `prefix + relative path`, hidden and half-written ones left out. */
    private fun files(root: File, prefix: String, keep: (String) -> Boolean): List<Pair<String, File>> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter(keep)
            .map { prefix + it to File(root, it) }
            .toList()
    }

    companion object {
        const val WEBS = "webs/"
        const val PREP = "prefs/prep.json"

        fun webPath(id: String) = "$WEBS$id.json"

        /**
         * What in Ai's folder is this device's alone: its keys (encrypted to this device on Android, and
         * never to leave any device), the command-line apps' working folder, and caches fetched again.
         * Since 1.0.99 the keys and the working folder live outside Ai's folder (`secrets/`, `cli-run/`), which
         * neither a sync nor a backup walks; their old places here stay excluded, for a device not yet migrated.
         */
        fun privateToDevice(rel: String): Boolean = InboundPath.aiPrivate(rel)

        /**
         * What of a world travels (1.0.97), [rel] under `world/`: its record, log, files and pictures; never a half-written
         * file or the Python helper's folder, which every run writes afresh.
         */
        fun worldSyncs(rel: String): Boolean =
            !rel.endsWith(".tmp") && "/.py/" !in "/$rel" && rel.split('/').none { it == ".." }
    }
}

/**
 * When this device first saw each item as it is (1.0.68): what stands in for "last changed" where the
 * thing itself keeps no time (the settings, prep, Ai's files), and a file's hash kept with its size
 * and time so an unchanged file is not read again every sync. Kept on the device, in the sync folder.
 */
class SeenTimes(private val file: File, private val clock: () -> Long) {
    @kotlinx.serialization.Serializable
    data class Seen(val hash: String, val at: Long, val size: Long = -1, val modified: Long = -1)

    private val MAP = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Seen.serializer())

    private val map: MutableMap<String, Seen> = runCatching {
        Sync.json.decodeFromString(MAP, file.readText()).toMutableMap()
    }.getOrDefault(HashMap())

    /** Whether [map] moved since it was last written: an unchanged one is not written again every sync (1.0.92). */
    private var dirty = false

    @Synchronized
    fun at(path: String, hash: String): Long {
        val s = map[path]
        if (s != null && s.hash == hash) return s.at
        val now = clock()
        map[path] = Seen(hash, now)
        dirty = true
        return now
    }

    /** What this device holds after taking another's version: seen as of now. */
    @Synchronized
    fun agree(path: String, bytes: ByteArray) {
        map[path] = Seen(Sha256.hex(bytes), clock())
        dirty = true
    }

    @Synchronized
    fun forget(path: String) {
        if (map.remove(path) != null) dirty = true
    }

    /** A file as an item: its hash read again only when its size or time moved. */
    @Synchronized
    fun file(path: String, f: File): LocalItem {
        val size = f.length()
        val modified = f.lastModified()
        val s = map[path]
        if (s != null && s.size == size && s.modified == modified) return LocalItem(s.hash, modified) { f.readBytes() }
        val bytes = f.readBytes()
        val hash = Sha256.hex(bytes)
        map[path] = Seen(hash, modified, size, modified)
        dirty = true
        return LocalItem(hash, modified) { bytes }
    }

    @Synchronized
    fun save() {
        if (!dirty && file.isFile) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Sync.json.encodeToString(MAP, map))
            dirty = false
        }
    }
}
