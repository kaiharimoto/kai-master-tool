package com.kaiharimoto.neue.world.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.library.DiskLibraryFiles
import com.kaiharimoto.mastertool.core.ai.library.LibraryCatalog
import com.kaiharimoto.mastertool.core.ai.library.LibraryDoc
import com.kaiharimoto.mastertool.core.ai.library.LibraryFiles
import com.kaiharimoto.mastertool.core.ai.library.LibraryHit
import com.kaiharimoto.mastertool.core.ai.library.LibraryKnowledge
import com.kaiharimoto.mastertool.core.ai.library.LibraryQuery
import com.kaiharimoto.mastertool.core.ai.library.LibrarySearch
import com.kaiharimoto.mastertool.core.ai.library.LibrarySections
import com.kaiharimoto.mastertool.core.ai.library.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Library as state (`docs/world/DESKTOP.md` §10), an owned part of the World (`world.library`): everything Ai knows,
 * listed from where it lives under the data folder — never copied — opened a document at a time off the frame thread,
 * and searched by walking ([LibrarySearch]), debounced, cancelled by the next keystroke, the shelf on screen first.
 * Read-only: an edit goes through Ai's brain (`MemoryDialog`), so memory has one editor and one review.
 */
class WorldLibrary(val data: File, val files: LibraryFiles = DiskLibraryFiles(data)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** One document as read: its text split into sections of blocks, and its count (`Guide · 41,200 words`). */
    class Opened(val doc: LibraryDoc, val text: String, val sections: List<LibrarySections.Section>, val count: String)

    var catalog by mutableStateOf(LibraryCatalog(emptyList()))
        private set

    /** The catalogue has been read once. */
    var ready by mutableStateOf(false)
        private set

    var shelf by mutableStateOf(Shelf.THIS_DECK)

    /** The deck the This deck shelf is about: the world's scope, else the builder's deck; the picker changes it. */
    var deck by mutableStateOf<String?>(null)

    /** The decks the catalogue names, id → name, for the picker. */
    var decks by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var opened by mutableStateOf<Opened?>(null)
        private set

    /** A document being read. */
    var reading by mutableStateOf<LibraryDoc?>(null)
        private set

    /** Where to bring the reader to in the open document (a search hit's offset), once. */
    var jump by mutableStateOf<Long?>(null)

    var query by mutableStateOf("")
        private set
    val hits = mutableStateListOf<LibraryHit>()
    var searching by mutableStateOf(false)
        private set

    /** The search stopped at [LibrarySearch.MAX_HITS]: *More* asks for the next lot. */
    var capped by mutableStateOf(false)
        private set
    private var limit = LibrarySearch.MAX_HITS
    private var search: Job? = null

    /** Documents read, kept while they are small enough together (32 MB of text, §10.3). */
    private val cache = object : LinkedHashMap<String, Opened>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Opened>?): Boolean =
            values.sumOf { it.text.length.toLong() * 2 } > CACHE_BYTES && size > 1
    }

    /** `ygo.knowledge` for a world's code and apps: read-only, what the catalogue lists (§10.4). */
    val knowledge = LibraryKnowledge(files) { if (ready) catalog else LibraryCatalog.build(files, decks) }

    /** Reads the catalogue again, naming documents from [decks] and [webs] (id → name). Off the frame thread. */
    suspend fun refresh(decks: Map<String, String>, webs: Map<String, String>) {
        val built = withContext(Dispatchers.IO) { LibraryCatalog.build(files, decks, webs) }
        catalog = built
        this.decks = decks
        ready = true
    }

    /** The documents on the shelf in view. */
    fun shelved(): List<LibraryDoc> = catalog.shelf(shelf, deck)

    /** Opens [doc] to read: off the frame thread, kept in the cache; [at] brings the reader to an offset once it is open. */
    fun open(doc: LibraryDoc, at: Long? = null) {
        jump = at
        cache[doc.path]?.let {
            opened = it
            reading = null
            return
        }
        reading = doc
        scope.launch {
            val read = withContext(Dispatchers.Default) {
                val text = files.text(doc.path) ?: return@withContext null
                Opened(doc, text, LibrarySections.split(text), LibraryCatalog.count(doc, text))
            }
            if (reading?.path != doc.path) return@launch
            reading = null
            if (read == null) {
                opened = null
                return@launch
            }
            cache[doc.path] = read
            opened = read
        }
    }

    fun close() {
        opened = null
        reading = null
    }

    /**
     * The search for [q], 150 ms after the last keystroke, cancelled by the next: each document walked in 64 KB windows on
     * a background thread, the shelf in view first; hits come in grouped by document as they are found.
     */
    fun search(q: String) {
        query = q
        limit = LibrarySearch.MAX_HITS
        restart(debounce = true)
    }

    /** *More*: the next 500 hits. */
    fun more() {
        limit += LibrarySearch.MAX_HITS
        restart(debounce = false)
    }

    private fun restart(debounce: Boolean) {
        search?.cancel()
        val parsed = LibraryQuery.parse(query)
        if (parsed == null) {
            hits.clear()
            searching = false
            capped = false
            return
        }
        val order = catalog.ordered(shelved())
        val max = limit
        search = scope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            hits.clear()
            searching = true
            capped = false
            val pending = ArrayList<LibraryHit>()
            fun drain() = synchronized(pending) {
                if (pending.isNotEmpty()) {
                    hits.addAll(pending)
                    pending.clear()
                }
            }
            // Hits stream in as they are found: moved onto the screen a few times a second, never one recomposition a hit.
            val flusher = launch {
                while (true) {
                    delay(FLUSH_MS)
                    drain()
                }
            }
            try {
                val found = withContext(Dispatchers.Default) {
                    LibrarySearch.search(files, order, parsed, max) { hit -> synchronized(pending) { pending += hit } }
                }
                drain()
                capped = found >= max
            } catch (e: CancellationException) {
                throw e
            } finally {
                flusher.cancel()
                searching = false
            }
        }
    }

    /** The hits so far, by document, in the order the documents were walked. */
    fun grouped(): List<Pair<LibraryDoc, List<LibraryHit>>> = hits.groupBy { it.doc.path }.map { (_, hs) -> hs.first().doc to hs }

    companion object {
        const val DEBOUNCE_MS = 150L
        const val FLUSH_MS = 80L
        const val CACHE_BYTES = 32L * 1024 * 1024
    }
}
