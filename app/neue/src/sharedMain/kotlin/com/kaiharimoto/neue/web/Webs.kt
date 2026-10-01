package com.kaiharimoto.neue.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import kotlinx.coroutines.sync.withLock
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

    /** The deck being sided on the Siding page (1.0.35; its own page from 1.0.40), or null for the first of yours. */
    var sidingDeckId by mutableStateOf<String?>(null)

    /** Whether the web's page shows its matchups rather than its field; kept, so the editor's Back returns to it. */
    var showMatchups by mutableStateOf(false)

    /** The opponent the siding editor opens on, when it was opened from a matchup. */
    var sidingAgainst by mutableStateOf<String?>(null)

    /** A new opponent to open the siding editor's New opponent on, with this name typed (the studio's `--opponent`). */
    var newOpponent by mutableStateOf<String?>(null)

    /** Opens the siding editor: [deckId] sided, against [against] when given. */
    fun side(deckId: String, against: String? = null) {
        library.webOf(deckId)?.let { selectedId = it.id }
        sidingAgainst = against
        sidingDeckId = deckId
        sidingAsked++
    }

    /** Moves each time siding is asked for, so the app opens the Siding page (1.0.40). */
    var sidingAsked by mutableStateOf(0)
        private set

    /** One write at a time, in the order they were asked for: the last edit is the one kept. */
    private val sidingLock = kotlinx.coroutines.sync.Mutex()

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

    /** A web as another device last saved it (sync, 1.0.68): in place of this device's, or added. */
    fun adopt(web: DeckWeb) {
        commit(library.put(web))
        revision++
    }

    /** Every web as a backup kept them (1.0.69): theirs in place, any made since kept. */
    fun restore(from: WebLibrary) {
        var next = library
        from.webs.forEach { next = next.put(it) }
        commit(next)
        revision++
    }

    /** A web another device deleted (sync): gone here too; its decks stay in the library. */
    fun forget(id: String) {
        if (library.byId(id) == null) return
        commit(library.remove(id))
        revision++
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

    /** One saved deck, by its id: a deck sided on its own (1.0.42). */
    suspend fun stored(id: String): StoredDeck? = deps.deckRepository.byId(id)

    /** Every saved deck, a web's or not: what a matchup made by name may be linked to (1.0.42). */
    suspend fun libraryDecks(): List<StoredDeck> = deps.deckRepository.all()

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

    /** Hears a library deck copied into a web — (from deck, its name, web) — so Ai's notes on it follow (1.0.43). */
    var onJoined: (String, String, String) -> Unit = { _, _, _ -> }

    /** Hears a web deleted, so Ai's notes on it go too (1.0.43). */
    var onDeleted: (String) -> Unit = {}

    /** A library deck copied into the web: the web's copy is its own, the library keeps the original. */
    fun addFromLibrary(webId: String, stored: StoredDeck, then: (String) -> Unit = {}) =
        add(webId, stored.entry.name, stored.toDocument()) { id ->
            onJoined(stored.entry.id, stored.entry.name, webId)
            then(id)
        }

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
            onDeleted(webId)
            revision++
            then()
        }
    }

    /**
     * A deck's extended payload as it stands: the builder's when the deck is open
     * there (its groups and siding may be newer than the saved copy), else the saved one.
     */
    fun extendedOf(stored: StoredDeck, state: DeckBuilderState): kotlinx.serialization.json.JsonObject? =
        if (state.deckId == stored.entry.id) state.extendedNow() else stored.extended

    /** A deck's cards as they stand, by the same rule. */
    fun deckOf(stored: StoredDeck, state: DeckBuilderState): com.kaiharimoto.mastertool.core.model.Deck =
        if (state.deckId == stored.entry.id) state.deck else stored.entry.deck

    /** Every plan written this session, by deck: newer than any copy read before it was written. */
    private val written = androidx.compose.runtime.mutableStateMapOf<String, DeckSiding>()

    /** A deck's siding as it stands: written this session, else the builder's copy or the saved one. */
    fun sidingOf(stored: StoredDeck, state: DeckBuilderState): DeckSiding =
        written[stored.entry.id] ?: SidingCodec.read(extendedOf(stored, state))

    /**
     * [siding] written into deck [deckId]'s saved copy — its cards and every other key
     * left as saved — and into the builder's payload when the deck is open there, so
     * the builder's next save carries it rather than the plan it had before.
     */
    fun saveSiding(deckId: String, siding: DeckSiding, state: DeckBuilderState) {
        written[deckId] = siding
        if (state.deckId == deckId) state.putExtended(SidingCodec.KEY, if (siding.isEmpty) null else SidingCodec.node(siding))
        scope.launch {
            sidingLock.withLock {
                val stored = deps.deckRepository.byId(deckId) ?: return@withLock
                deps.deckRepository.save(deckId, stored.entry.name, stored.entry.deck, SidingCodec.write(stored.extended, siding), stored.entry.notes)
            }
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
            // New ids first, so a siding plan against another deck of the file follows it to its new id.
            val ids = file.decks.associate { it.id to deps.newDeckId() }
            val entries = file.decks.map { deck ->
                val id = ids.getValue(deck.id)
                val extended = SidingCodec.remap(deck.document.extended, ids)
                deps.deckRepository.save(id, deck.name, deck.document.deck, extended)
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
