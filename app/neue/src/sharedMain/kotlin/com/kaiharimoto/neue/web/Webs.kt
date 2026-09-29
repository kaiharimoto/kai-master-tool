package com.kaiharimoto.neue.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.core.web.WebCodec
import com.kaiharimoto.mastertool.core.web.WebEntry
import com.kaiharimoto.mastertool.core.web.WebFile
import com.kaiharimoto.mastertool.core.web.WebFileDeck
import com.kaiharimoto.mastertool.core.web.WebLibrary
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.mastertool.ui.AppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The webs of decks (Format, 1.0.33), for the whole app: the library of webs,
 * read once and written on every change, and everything done to a web that also
 * touches the deck table — a deck imported into a web, copied in from the
 * library or out to it, removed with it.
 *
 * A web's decks are ordinary saved decks, each belonging to one web: the builder
 * edits them as any other, and the Decks page leaves them to their web.
 */
class Webs(private val deps: AppDependencies, private val scope: CoroutineScope) {

    var library by mutableStateOf(WebLibrary.EMPTY)
        private set

    var loaded by mutableStateOf(false)
        private set

    /** The web open on the Format page. */
    var selectedId by mutableStateOf<String?>(null)

    /** Moves whenever a web's decks change in the deck table, so the page reads them again. */
    var revision by mutableStateOf(0)
        private set

    val selected: DeckWeb? get() = library.byId(selectedId) ?: library.webs.firstOrNull()

    fun webOf(deckId: String?): DeckWeb? = library.webOf(deckId)

    fun load() {
        scope.launch {
            library = deps.preferencesRepository.loadWebs()
            loaded = true
        }
    }

    private fun commit(next: WebLibrary) {
        library = next
        scope.launch { deps.preferencesRepository.saveWebs(next) }
    }

    private fun edit(webId: String, change: (DeckWeb) -> DeckWeb) {
        val web = library.byId(webId) ?: return
        commit(library.put(change(web).copy(updatedAtEpochMs = deps.now())))
    }

    fun create(name: String = "New web"): DeckWeb {
        val web = DeckWeb(id = deps.newDeckId(), name = name, updatedAtEpochMs = deps.now())
        commit(library.put(web))
        selectedId = web.id
        return web
    }

    fun rename(webId: String, name: String) = edit(webId) { it.copy(name = name) }

    fun setNotes(webId: String, notes: String) = edit(webId) { it.copy(notes = notes) }

    fun star(webId: String, deckId: String, mine: Boolean) = edit(webId) { it.starred(deckId, mine) }

    fun share(webId: String, deckId: String, share: Int?) = edit(webId) { it.shared(deckId, share) }

    fun move(webId: String, deckId: String, index: Int) = edit(webId) { it.moved(deckId, index) }

    /** Its decks, in its order; a deck missing from the table (deleted elsewhere) is left out. */
    suspend fun decks(web: DeckWeb): List<StoredDeck> = web.deckIds.mapNotNull { deps.deckRepository.byId(it) }

    /** [document] saved as a new deck and put at the end of the web; its id, when [then] hears it. */
    fun add(webId: String, name: String, document: YdkDocument, then: (String) -> Unit = {}) {
        scope.launch {
            val id = deps.newDeckId()
            deps.deckRepository.save(id, name.ifBlank { "Imported deck" }, document.deck, document.extended)
            edit(webId) { it.with(WebEntry(id)) }
            revision++
            then(id)
        }
    }

    /** A library deck copied into the web: the web's copy is its own, the library keeps the original. */
    fun addFromLibrary(webId: String, stored: StoredDeck, then: (String) -> Unit = {}) =
        add(webId, stored.entry.name, stored.toDocument(), then)

    /** A web's deck copied out to the library, as a deck of its own. */
    fun copyToLibrary(stored: StoredDeck, then: (String) -> Unit = {}) {
        scope.launch {
            val id = deps.newDeckId()
            deps.deckRepository.save(id, stored.entry.name, stored.entry.deck, stored.extended, stored.entry.notes)
            then(id)
        }
    }

    /** The deck out of the web, and out of the deck table: a web's deck is nowhere else. */
    fun remove(webId: String, deckId: String, then: () -> Unit = {}) {
        scope.launch {
            edit(webId) { it.without(deckId) }
            deps.deckRepository.delete(deckId)
            revision++
            then()
        }
    }

    /** The web, and every deck in it. */
    fun delete(webId: String, then: () -> Unit = {}) {
        val web = library.byId(webId) ?: return
        scope.launch {
            commit(library.remove(webId))
            if (selectedId == webId) selectedId = null
            web.deckIds.forEach { deps.deckRepository.delete(it) }
            revision++
            then()
        }
    }

    /** The web as a `.ydkw`: every deck as its `.ydkx`, groups and all. */
    suspend fun fileText(web: DeckWeb): String {
        val decks = decks(web).map { stored ->
            val entry = web.entry(stored.entry.id)
            WebFileDeck(stored.entry.id, stored.entry.name, stored.toDocument(), entry?.mine ?: false, entry?.share)
        }
        return WebCodec.write(WebFile(web.name, web.notes, decks))
    }

    /**
     * A `.ydkw` opened: a new web, each of its decks saved as a new deck (new ids, so
     * opening the same file twice makes two webs, never one web fighting over decks).
     * The web it made, or null when [text] is not a web.
     */
    fun open(text: String, then: (DeckWeb?) -> Unit = {}) {
        val file = WebCodec.read(text)
        if (file == null) {
            then(null)
            return
        }
        scope.launch {
            val entries = file.decks.map { deck ->
                val id = deps.newDeckId()
                deps.deckRepository.save(id, deck.name, deck.document.deck, deck.document.extended)
                WebEntry(id, deck.mine, deck.share)
            }
            val web = DeckWeb(deps.newDeckId(), file.name, file.notes, entries, deps.now())
            commit(library.put(web))
            selectedId = web.id
            revision++
            then(web)
        }
    }
}
