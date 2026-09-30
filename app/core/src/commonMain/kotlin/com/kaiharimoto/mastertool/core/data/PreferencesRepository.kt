package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
import com.kaiharimoto.mastertool.core.web.WebLibrary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persistence for layout settings.
 *
 * Stored in the same SQLite database as everything else rather than reaching for
 * DataStore or a settings library: DataStore is Android-only and would need a
 * separate desktop path, and `:core` deliberately carries no platform code. One
 * row in a table the database already has costs nothing and works everywhere.
 *
 * A settings file that cannot be read is never a reason to fail — the defaults
 * are always a usable answer, so a corrupt document is replaced rather than
 * surfaced.
 */
class PreferencesRepository(
    private val database: MasterToolDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): UiPreferences = withContext(ioDispatcher) {
        val stored = database.preferenceQueries.selectByKey(KEY).executeAsOneOrNull()
            ?: return@withContext UiPreferences.DEFAULT

        runCatching { json.decodeFromString(UiPreferences.serializer(), stored) }
            .getOrElse { UiPreferences.DEFAULT }
            .sanitised()
    }

    suspend fun save(preferences: UiPreferences) {
        withContext(ioDispatcher) {
            database.preferenceQueries.upsert(
                prefKey = KEY,
                prefValue = json.encodeToString(UiPreferences.serializer(), preferences.sanitised()),
            )
        }
    }

    /**
     * The desktop builder's own document, under its own key in the same table —
     * a new row, not a new schema. Unreadable reads as the defaults, as above.
     */
    suspend fun loadNeue(): NeuePreferences = withContext(ioDispatcher) {
        val stored = database.preferenceQueries.selectByKey(NeuePreferences.KEY).executeAsOneOrNull()
            ?: return@withContext NeuePreferences.DEFAULT

        runCatching { json.decodeFromString(NeuePreferences.serializer(), stored) }
            .getOrElse { NeuePreferences.DEFAULT }
            .sanitised()
    }

    suspend fun saveNeue(preferences: NeuePreferences) {
        withContext(ioDispatcher) {
            database.preferenceQueries.upsert(
                prefKey = NeuePreferences.KEY,
                prefValue = json.encodeToString(NeuePreferences.serializer(), preferences.sanitised()),
            )
        }
    }

    /**
     * Every web of decks (Format, 1.0.33), one document under its own key: a new
     * row in the same table, never a schema change. Unreadable reads as none —
     * the decks themselves are safe in the deck table, and show in the library.
     */
    suspend fun loadWebs(): WebLibrary = withContext(ioDispatcher) {
        val stored = database.preferenceQueries.selectByKey(WebLibrary.KEY).executeAsOneOrNull()
            ?: return@withContext WebLibrary.EMPTY
        runCatching { json.decodeFromString(WebLibrary.serializer(), stored) }.getOrElse { WebLibrary.EMPTY }
    }

    suspend fun saveWebs(library: WebLibrary) {
        withContext(ioDispatcher) {
            database.preferenceQueries.upsert(
                prefKey = WebLibrary.KEY,
                prefValue = json.encodeToString(WebLibrary.serializer(), library),
            )
        }
    }

    /**
     * Tournament prep (1.0.50): events, the test games log, drills and the decklist's
     * details, one document under its own key — a row, never a schema change. It reads
     * forgivingly ([PrepCodec]): a broken row opens an empty page, not a crash.
     */
    suspend fun loadPrep(): com.kaiharimoto.mastertool.core.prep.PrepDoc = withContext(ioDispatcher) {
        val stored = database.preferenceQueries.selectByKey(com.kaiharimoto.mastertool.core.prep.PrepDoc.KEY).executeAsOneOrNull()
        com.kaiharimoto.mastertool.core.prep.PrepCodec.decode(stored)
    }

    suspend fun savePrep(doc: com.kaiharimoto.mastertool.core.prep.PrepDoc) {
        withContext(ioDispatcher) {
            database.preferenceQueries.upsert(
                prefKey = com.kaiharimoto.mastertool.core.prep.PrepDoc.KEY,
                prefValue = com.kaiharimoto.mastertool.core.prep.PrepCodec.encode(doc),
            )
        }
    }

    companion object {
        const val KEY = "deckbuilder.ui"
    }
}
