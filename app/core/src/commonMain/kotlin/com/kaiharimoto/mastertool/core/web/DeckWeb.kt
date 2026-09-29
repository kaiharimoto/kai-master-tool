package com.kaiharimoto.mastertool.core.web

import kotlinx.serialization.Serializable

/**
 * A deck in a web: which deck, whether it is one of yours — starred, first in
 * the web's lists and the decks you side as — and how much of the field it is
 * expected to be, in percent, when you know.
 */
@Serializable
data class WebEntry(
    val deckId: String,
    val mine: Boolean = false,
    val share: Int? = null,
)

/**
 * A web (kai, 1.0.33: "web multiple YDKX decks together as one exportable
 * group… expected decks at a tournament"): the field you expect at an event —
 * the decks you will face, and yours among them — with notes about the room.
 *
 * The decks themselves are ordinary decks in the database, so the builder edits
 * them as it edits any; a web holds only their ids, in its own order, and each
 * deck belongs to one web (`WebLibrary`). The page is called Format; the type is
 * not, because `Format` is the TCG/OCG banlist everywhere else.
 */
@Serializable
data class DeckWeb(
    val id: String,
    val name: String,
    val notes: String = "",
    val entries: List<WebEntry> = emptyList(),
    val updatedAtEpochMs: Long = 0,
) {
    val deckIds: List<String> get() = entries.map { it.deckId }

    /** Your decks, in the web's order. */
    val mine: List<WebEntry> get() = entries.filter { it.mine }

    fun has(deckId: String): Boolean = entries.any { it.deckId == deckId }

    fun entry(deckId: String): WebEntry? = entries.firstOrNull { it.deckId == deckId }

    /** Where [deckId] stands, from 1, or null when it is not in the web. */
    fun position(deckId: String): Int? = entries.indexOfFirst { it.deckId == deckId }.takeIf { it >= 0 }?.plus(1)

    /** [entry] at the end; a deck already in the web stays where it is. */
    fun with(entry: WebEntry): DeckWeb = if (has(entry.deckId)) this else copy(entries = entries + entry)

    fun without(deckId: String): DeckWeb = copy(entries = entries.filterNot { it.deckId == deckId })

    fun starred(deckId: String, mine: Boolean): DeckWeb = edit(deckId) { it.copy(mine = mine) }

    /** Its share of the field, 0 to 100, or null for unknown. */
    fun shared(deckId: String, share: Int?): DeckWeb = edit(deckId) { it.copy(share = share?.coerceIn(0, 100)) }

    /** [deckId] moved to [index] (from 0), the others closing up round it. */
    fun moved(deckId: String, index: Int): DeckWeb {
        val entry = entry(deckId) ?: return this
        val rest = entries.filterNot { it.deckId == deckId }
        return copy(entries = rest.toMutableList().apply { add(index.coerceIn(0, rest.size), entry) })
    }

    /**
     * The deck [step] along from [deckId] — the builder's ‹ and › — round from the
     * last to the first. Null when [deckId] is not in the web, or it is alone.
     */
    fun neighbour(deckId: String, step: Int): String? {
        val at = entries.indexOfFirst { it.deckId == deckId }
        if (at < 0 || entries.size < 2) return null
        return entries[(at + step).mod(entries.size)].deckId
    }

    /** The shares written down, added up: a field whose shares pass 100 is worth a second look. */
    val totalShare: Int get() = entries.sumOf { it.share ?: 0 }

    private fun edit(deckId: String, change: (WebEntry) -> WebEntry): DeckWeb =
        copy(entries = entries.map { if (it.deckId == deckId) change(it) else it })
}

/**
 * Every web, kept as one JSON document in the preferences table (key [KEY]) — a
 * row, not a schema change, so the database stays at version 3. A deck belongs
 * to at most one web: [put] takes a deck out of any other web it was in.
 */
@Serializable
data class WebLibrary(val webs: List<DeckWeb> = emptyList()) {

    fun byId(id: String?): DeckWeb? = id?.let { wanted -> webs.firstOrNull { it.id == wanted } }

    /** The web [deckId] belongs to, if any. */
    fun webOf(deckId: String?): DeckWeb? = deckId?.let { id -> webs.firstOrNull { it.has(id) } }

    /** Every deck some web holds: the Decks page leaves them to their webs. */
    val deckIds: Set<String> get() = webs.flatMapTo(mutableSetOf()) { it.deckIds }

    /** [web] in place of the one with its id, else last; its decks leave any other web. */
    fun put(web: DeckWeb): WebLibrary {
        val owned = web.deckIds.toSet()
        val others = webs.map { other ->
            if (other.id == web.id) web else other.copy(entries = other.entries.filterNot { it.deckId in owned })
        }
        return copy(webs = if (webs.any { it.id == web.id }) others else others + web)
    }

    fun remove(id: String): WebLibrary = copy(webs = webs.filterNot { it.id == id })

    companion object {
        const val KEY = "neue.webs"
        val EMPTY = WebLibrary()
    }
}
