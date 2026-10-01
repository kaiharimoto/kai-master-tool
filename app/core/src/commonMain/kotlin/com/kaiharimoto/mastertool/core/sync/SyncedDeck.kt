package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A saved deck as a synced item (1.0.68): `decks/<id>.json`, everything the library keeps — name,
 * the three sections in order, notes and the `.ydkx` payload (groups, siding, covers' companions) —
 * but not when it was last saved, so saving an unchanged deck is not a change to send.
 */
@Serializable
data class SyncedDeck(
    val name: String,
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val side: List<Int> = emptyList(),
    val notes: String = "",
    val extended: JsonObject? = null,
) {
    val deck: Deck get() = Deck(main.map(::CardId), extra.map(::CardId), side.map(::CardId))

    fun bytes(): ByteArray = Sync.json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val FOLDER = "decks/"

        fun path(id: String) = "$FOLDER$id.json"

        /** The deck's id from its path, or null for anything else. */
        fun idOf(path: String): String? = path.takeIf { it.startsWith(FOLDER) && it.endsWith(".json") }?.removePrefix(FOLDER)?.removeSuffix(".json")?.takeIf { it.isNotBlank() && '/' !in it }

        fun of(d: StoredDeck) = SyncedDeck(
            d.entry.name,
            d.entry.deck.main.map { it.value },
            d.entry.deck.extra.map { it.value },
            d.entry.deck.side.map { it.value },
            d.entry.notes,
            d.extended,
        )

        fun read(bytes: ByteArray): SyncedDeck? = runCatching { Sync.json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrNull()
    }
}
