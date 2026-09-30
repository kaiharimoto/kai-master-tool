package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.web.WebLibrary

/**
 * Which deck's or web's notes Ai reads now (kai: "each deck, if it's not in a web,
 * and web has its own markdown, so the AI doesn't waste time reading about decks
 * that aren't relevant"). One file at a time, never the library:
 *
 * - on Format or Siding, the web being looked at;
 * - elsewhere, the deck open in the builder — its web's notes when it is in one,
 *   its own when it is not;
 * - nothing, while the builder's deck has never been saved.
 */
data class MemoryScope(val kind: MemoryKind, val id: String, val name: String) {
    val path: String get() = AiMemory.path(kind, id)

    companion object {
        fun of(
            page: String,
            openDeckId: String?,
            openDeckName: String,
            selectedWebId: String?,
            webs: WebLibrary,
        ): MemoryScope? {
            if (page == "FORMAT" || page == "SIDING") {
                webs.byId(selectedWebId)?.let { return MemoryScope(MemoryKind.WEB, it.id, it.name) }
            }
            val id = openDeckId ?: return null
            webs.webOf(id)?.let { return MemoryScope(MemoryKind.WEB, it.id, it.name) }
            return MemoryScope(MemoryKind.DECK, id, openDeckName.ifBlank { "this deck" })
        }
    }
}
