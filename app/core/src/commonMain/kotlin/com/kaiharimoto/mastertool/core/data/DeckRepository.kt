package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.DeckEntity
import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A saved deck together with the opaque YDKX payload it was imported with. */
data class StoredDeck(
    val entry: DeckEntry,
    val extended: JsonObject? = null,
) {
    fun toDocument(): YdkDocument = YdkDocument(entry.deck, createdBy = null, extended = extended)
}

/**
 * Persistence for saved decks.
 *
 * Ids are supplied by the caller rather than generated here so the same
 * repository works with a UUID on device and a fixed id in tests.
 */
class DeckRepository(
    private val database: MasterToolDatabase,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun all(): List<StoredDeck> = withContext(ioDispatcher) {
        database.deckQueries.selectAll().executeAsList().map(::toStored)
    }

    /** Whether the library holds any deck, without reading one: what opening asks of someone new. */
    suspend fun hasAny(): Boolean = withContext(ioDispatcher) {
        database.deckQueries.countAll().executeAsOne() > 0
    }

    suspend fun byId(id: String): StoredDeck? = withContext(ioDispatcher) {
        database.deckQueries.selectById(id).executeAsOneOrNull()?.let(::toStored)
    }

    suspend fun save(
        id: String,
        name: String,
        deck: Deck,
        extended: JsonObject? = null,
        notes: String = "",
    ): StoredDeck = withContext(ioDispatcher) {
        val now = clock()
        val existing = database.deckQueries.selectById(id).executeAsOneOrNull()
        val createdAt = existing?.createdAtEpochMs ?: now

        database.deckQueries.upsert(
            id = id,
            name = name,
            main = CardMapper.joinIds(deck.main),
            extra = CardMapper.joinIds(deck.extra),
            side = CardMapper.joinIds(deck.side),
            notes = notes,
            extendedJson = extended?.let { json.encodeToString(JsonObject.serializer(), it) },
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = now,
        )

        StoredDeck(
            DeckEntry(id, name, deck, createdAt, now, notes),
            extended,
        )
    }

    /** Saves the result of importing a `.ydk` / `.ydkx` file. */
    suspend fun saveImported(id: String, name: String, document: YdkDocument): StoredDeck =
        save(id, name, document.deck, document.extended)

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        database.deckQueries.deleteById(id)
    }

    suspend fun rename(id: String, name: String) = withContext(ioDispatcher) {
        database.deckQueries.rename(name, clock(), id)
    }

    /** Serialises a saved deck back out to file text for export or sharing. */
    suspend fun exportText(id: String): String? =
        byId(id)?.let { YdkCodec.write(it.toDocument()) }

    private fun toStored(row: DeckEntity): StoredDeck {
        val extended = row.extendedJson
            ?.let { raw -> runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }

        return StoredDeck(
            entry = DeckEntry(
                id = row.id,
                name = row.name,
                deck = Deck(
                    main = CardMapper.splitIds(row.main),
                    extra = CardMapper.splitIds(row.extra),
                    side = CardMapper.splitIds(row.side),
                ),
                createdAtEpochMs = row.createdAtEpochMs,
                updatedAtEpochMs = row.updatedAtEpochMs,
                notes = row.notes,
            ),
            extended = extended,
        )
    }
}
