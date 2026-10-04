package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResultCodec
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The finished duels as results (Phase C, `docs/phases/C.md` §2), a part of [Duels], which forwards what outside code
 * reads. One file a duel in `<data>/duel/records/<id>.json` — synced and backed up as replays are, everything under
 * `duel/` but the duel in play — written when the duel on the table ends and taken away again when Undo takes the end
 * back. A what-if played on from a replay is kept under its own id, marked, and left out of the summary unless asked.
 */
internal class DuelRecords(private val d: Duels) {
    /** Every result kept, newest first. */
    var results by mutableStateOf<List<DuelResult>>(emptyList())
        private set
    private var read = false
    /** The reading under way: a duel looked at before it is done waits for it, so a kept result is never written again. */
    private var reading: Job? = null

    private val dir: File get() = File(d.dir, DuelResultCodec.FOLDER)

    /** Reads the results kept, once (again after a sync or a restore: [reload]). */
    fun load() {
        if (read) return
        read = true
        reading = d.scope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f -> runCatching { DuelResultCodec.decode(f.readText()) }.getOrNull() }
            }
            // A result written while these were read stays: the newer of the two for an id.
            val made = results
            results = (made + list.filter { r -> made.none { it.id == r.id } }).sortedByDescending { it.ended }
        }
    }

    /** The results, once they have been read. */
    suspend fun read(): List<DuelResult> {
        load()
        reading?.join()
        return results
    }

    fun reload() {
        read = false
        load()
    }

    /**
     * The duel in play, looked at again (the page calls this when its life points or a concession change): ended, its
     * result is written — again only when it ended differently; no longer ended (Undo took the end back), its result goes.
     * Never a guest's mirror (the host keeps the record), nor a replay on the table.
     */
    fun note(g: DuelGame?) {
        g ?: return
        if (d.network.role == Duels.NetRole.GUEST) return
        load()
        val origin = d.replayer.origin
        d.scope.launch {
            reading?.join()
            noteRead(g, origin)
        }
    }

    private fun noteRead(g: DuelGame, origin: Pair<String, Int>?) {
        val id = if (origin == null) g.header.id else "${g.header.id}-w${origin.first}-${origin.second}"
        val r = DuelResults.of(g, Duels.now(), id, whatIf = origin != null)
        val kept = results.firstOrNull { it.id == id }
        // An Ai vs Ai match's record is the match's own, written by it: the table's reading never replaces it.
        if (kept?.kind == DuelResult.AI_VS_AI) return
        if (r == null) {
            if (kept != null) forget(id)
            return
        }
        // The same end looked at again (a page opened, a chat line): nothing to write.
        if (kept != null && kept.copy(ended = r.ended) == r) return
        write(r)
    }

    /** A result made away from the duel in play — an Ai vs Ai match's (`docs/phases/C.md` §6) — kept as the table's are. */
    fun keep(r: DuelResult) {
        if (r.id.isBlank()) return
        load()
        d.scope.launch {
            reading?.join()
            write(r)
        }
    }

    private fun write(r: DuelResult) {
        val id = r.id
        results = (listOf(r) + results.filterNot { it.id == id })
        d.scope.launch {
            withContext(Dispatchers.IO) {
                d.io.withLock {
                    val target = File(d.dir, DuelResultCodec.path(id))
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, "${target.name}.tmp")
                    temp.writeText(DuelResultCodec.encode(r))
                    if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
                }
            }
        }
    }

    private fun forget(id: String) {
        results = results.filterNot { it.id == id }
        d.scope.launch { withContext(Dispatchers.IO) { d.io.withLock { File(d.dir, DuelResultCodec.path(id)).delete() } } }
    }
}
