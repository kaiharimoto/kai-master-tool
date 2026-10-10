package com.kaiharimoto.neue.versions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.deck.DeckVersion
import com.kaiharimoto.mastertool.core.deck.DeckVersionCodec
import com.kaiharimoto.mastertool.core.deck.DeckVersions
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Every deck's versions (Phase G, G.8): `<data>/deckversions/<deck>/<print>.json`, one immutable file a version
 * ([DeckVersions]). Written when a save changes the Main or Extra Deck by card, read on demand and kept in memory; a sync or
 * a restore that brings files [reload]s. Safe from any thread, and meant to be called off the main one: it reads and
 * writes files.
 */
class DeckVersionStore(private val root: File) {
    private val cache = HashMap<String, List<DeckVersion>>()

    /** Where a save's version is written, off the main thread and the save's own way. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped when a version is written or forgotten, so a page showing versions reads them again. */
    var revision by mutableIntStateOf(0)
        private set

    private fun folder(deckId: String) = File(root, DeckVersions.dir(deckId).removePrefix(DeckVersions.FOLDER + "/"))

    /** [deckId]'s versions, oldest first. */
    fun of(deckId: String): List<DeckVersion> = synchronized(cache) {
        cache.getOrPut(deckId) {
            folder(deckId).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
                .mapNotNull { f -> runCatching { DeckVersionCodec.decode(f.readText()) }.getOrNull() }
                .sortedBy { it.at }
        }
    }

    /**
     * [deck] saved as [deckId] at [at]: a new version when its print is new (null otherwise). A duplicate's first version
     * names its source ([parentDeck], [parentPrint]).
     */
    fun record(
        deckId: String,
        deck: Deck,
        name: String,
        at: Long,
        cards: ((CardId) -> Card?)?,
        parentDeck: String? = null,
        parentPrint: String? = null,
    ): DeckVersion? {
        if (deck.main.isEmpty()) return null
        val made = synchronized(cache) { write(DeckVersions.next(of(deckId), deckId, deck, name, at, cards, parentDeck, parentPrint)) }
        if (made != null) revision++
        return made
    }

    /** [next] written and kept, or null when there is none or the file could not be written. Under the cache's lock. */
    private fun write(next: DeckVersion?): DeckVersion? {
        if (next == null) return null
        val file = File(root, DeckVersions.path(next.deckId, next.print).removePrefix(DeckVersions.FOLDER + "/"))
        val ok = runCatching {
            file.parentFile?.mkdirs()
            // Written whole then moved, so a half-written version is never read.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(DeckVersionCodec.encode(next))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.isSuccess
        if (!ok) return null
        cache[next.deckId] = (cache[next.deckId].orEmpty() + next).sortedBy { it.at }
        return next
    }

    /** [deckId]'s versions deleted with the deck. */
    fun forget(deckId: String) {
        synchronized(cache) {
            folder(deckId).deleteRecursively()
            cache.remove(deckId)
        }
        revision++
    }

    /** What sync or a restore wrote is read again. */
    fun reload() {
        synchronized(cache) { cache.clear() }
        revision++
    }

    /** The decks [deckId] was duplicated from, nearest first, each with the print it was duplicated at. */
    fun lineage(deckId: String): List<Pair<String, String>> = DeckVersions.lineage(deckId, ::of)
}
