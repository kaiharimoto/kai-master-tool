package com.kaiharimoto.neue.duel

/**
 * Where the duel keeps its files (`<data>/duel/`): the duel in play, replays, records, combos, house rulings, the
 * command line's history. On the desk and Android, the disk ([FileDuelStore], the same paths as always); in the
 * Lounge's browser table, kai's computer holds the duel, so the page keeps only what it must, in memory.
 *
 * Paths are relative to the duel's folder, `/`-separated. Reads off the frame thread; a write is whole or not at all.
 */
interface DuelStore {
    suspend fun read(path: String): String?

    /** [path] read where the caller stands: a small file read while a prompt is built (a deck's combos). */
    fun readNow(path: String): String?

    /** [text] written whole at [path]: to a temporary file, then put in place. */
    suspend fun write(path: String, text: String)

    suspend fun delete(path: String)

    /** The files directly in [folder] whose names end with [suffix]. */
    suspend fun list(folder: String, suffix: String): List<StoredFile>
}

/** A file in a [DuelStore]: its [name] in its folder, its text, and when it was last written (ms). */
class StoredFile(val name: String, val text: String, val modified: Long)

/** A store that keeps everything in memory: the browser's, and the tests'. */
class MemoryDuelStore : DuelStore {
    private val files = LinkedHashMap<String, Pair<String, Long>>()
    private var clock = 0L

    override suspend fun read(path: String): String? = files[path]?.first
    override fun readNow(path: String): String? = files[path]?.first
    override suspend fun write(path: String, text: String) { files[path] = text to ++clock }
    override suspend fun delete(path: String) { files.remove(path) }
    override suspend fun list(folder: String, suffix: String): List<StoredFile> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return files.entries
            .filter { (p, _) -> p.startsWith(prefix) && '/' !in p.removePrefix(prefix) && p.endsWith(suffix) }
            .map { (p, v) -> StoredFile(p.removePrefix(prefix), v.first, v.second) }
    }
}
