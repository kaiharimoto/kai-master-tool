package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
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
    suspend fun loadNeue(seed: NeuePreferences = NeuePreferences.DEFAULT): NeuePreferences = withContext(ioDispatcher) {
        val stored = database.preferenceQueries.selectByKey(NeuePreferences.KEY).executeAsOneOrNull()
            ?: return@withContext seed

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

    companion object {
        const val KEY = "deckbuilder.ui"
    }
}
