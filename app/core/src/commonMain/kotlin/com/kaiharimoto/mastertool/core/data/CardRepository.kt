package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.search.CardIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Outcome of a card pool refresh. */
sealed interface SyncResult {
    /** [version] is the database version the pool was fetched at, when YGOPRODeck said. */
    data class Updated(val cardCount: Int, val version: String? = null) : SyncResult

    /** The cached pool was recent enough that nothing was fetched. */
    data class UpToDate(val cardCount: Int) : SyncResult

    /**
     * The refresh failed. [cachedCardCount] says whether the app is still
     * usable, which is the difference between a warning and a blocking error.
     */
    data class Failed(val message: String, val cachedCardCount: Int) : SyncResult
}

data class CardPoolStatus(
    val cardCount: Int,
    val lastSyncEpochMs: Long?,
) {
    val isEmpty: Boolean get() = cardCount == 0
}

/**
 * Owns the local mirror of the card pool.
 *
 * The pool is fetched once and then read from SQLite, so the app works with no
 * signal. Search runs against an in-memory [CardIndex] rebuilt only when the
 * underlying rows change — scanning 13,000 cards per keystroke is fine in
 * memory and hopeless as a SQL query.
 */
class CardRepository(
    private val database: MasterToolDatabase,
    private val api: YgoProDeckApi,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _index = MutableStateFlow(CardIndex.EMPTY)
    val index: StateFlow<CardIndex> = _index.asStateFlow()

    /** Loads the cached pool into memory. Safe to call on every app start. */
    suspend fun loadFromCache(): CardIndex = withContext(ioDispatcher) {
        val cards = database.cardQueries.selectAll().executeAsList().map(CardMapper::toDomain)
        CardIndex.build(cards).also { _index.value = it }
    }

    suspend fun status(): CardPoolStatus = withContext(ioDispatcher) {
        val state = database.cardQueries.selectSyncState().executeAsOneOrNull()
        CardPoolStatus(
            cardCount = database.cardQueries.countAll().executeAsOne().toInt(),
            lastSyncEpochMs = state?.lastSyncEpochMs,
        )
    }

    /** What was written down about the pool when it was fetched; null for a pool fetched before that was kept. */
    suspend fun record(): PoolRecord? = withContext(ioDispatcher) {
        val stored = database.preferenceQueries.selectByKey(PoolRecord.KEY).executeAsOneOrNull() ?: return@withContext null
        runCatching { recordJson.decodeFromString(PoolRecord.serializer(), stored) }.getOrNull()
    }

    private suspend fun keep(record: PoolRecord) = withContext(ioDispatcher) {
        database.preferenceQueries.upsert(PoolRecord.KEY, recordJson.encodeToString(PoolRecord.serializer(), record))
    }

    /**
     * Asks YGOPRODeck whether the pool here is current, without fetching it:
     * `checkDBVer.php` against the version written down at the last fetch.
     * A pool too old to have one written down is judged by its age
     * ([PoolFreshness.isCurrent]); a current one then adopts the version, so
     * the next check compares versions.
     */
    suspend fun check(): PoolCheck {
        val current = status()
        val remote = api.checkVersion().getOrElse { return PoolCheck.Unreachable(current.cardCount, clock()) }
        val record = record()
        val local = record?.version
        // A pool without release data (stored before 1.1.0) is behind whatever its version says.
        return if (!current.isEmpty && record?.misc == true && PoolFreshness.isCurrent(local, current.lastSyncEpochMs, remote)) {
            if (local == null) keep((record ?: PoolRecord()).copy(version = remote.database))
            PoolCheck.Current(local ?: remote.database, current.cardCount, clock())
        } else {
            // Without release data its version vouches for nothing: "may be available", not "147.20, this is 147.20".
            PoolCheck.Behind(local?.takeIf { record?.misc == true }, remote.database, current.cardCount, clock())
        }
    }

    /**
     * Refreshes the pool from the network.
     *
     * A failure is reported rather than thrown, and never clears the cache: an
     * outdated pool beats no pool when you are standing at a tournament table.
     * [onProgress] hears each step of a fetch ([PoolProgress]), from whichever
     * thread is doing it.
     */
    suspend fun sync(
        force: Boolean = false,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        onProgress: (PoolProgress) -> Unit = {},
    ): SyncResult {
        val current = status()

        // A pool stored before 1.1.0 has no release data (Phase B): fetched again once, whatever its age.
        if (!force && !current.isEmpty && record()?.misc == true) {
            val age = current.lastSyncEpochMs?.let { clock() - it }
            if (age != null && age < maxAgeMs) {
                return SyncResult.UpToDate(current.cardCount)
            }
        }

        // The version first, so what is written down is never newer than what was fetched.
        onProgress(PoolProgress.Asking)
        val version = api.checkVersion().getOrNull()?.database
        val record = record()
        val expected = PoolProgress.expected(record, current.cardCount)
        var received = 0L
        // Off the caller's thread: the response is tens of megabytes of JSON, and Ktor decodes
        // it on whichever thread asks for the body — the main one, from the builder's scope.
        val fetched = withContext(ioDispatcher) {
            api.fetchAllCards(
                onBytes = { bytes ->
                    received = bytes
                    onProgress(PoolProgress.Downloading(bytes, expected))
                },
                onRead = { onProgress(PoolProgress.Reading) },
            )
        }
        val cards = fetched.getOrElse { error ->
            return SyncResult.Failed(
                error.message ?: "Could not reach the card database.",
                current.cardCount,
            )
        }

        if (cards.isEmpty()) {
            // Never let an empty response wipe a working local pool.
            return SyncResult.Failed("The card database returned no cards.", current.cardCount)
        }

        withContext(ioDispatcher) { replaceAll(cards) { done -> onProgress(PoolProgress.Saving(done, cards.size)) } }
        keep(PoolRecord(version = version, bytes = if (received > 0) received else record?.bytes ?: 0, misc = true))
        loadFromCache()
        return SyncResult.Updated(cards.size, version)
    }

    private fun replaceAll(cards: List<Card>, onSaved: (Int) -> Unit = {}) {
        database.transaction {
            database.cardQueries.deleteAll()
            cards.forEachIndexed { i, card ->
                if (i % SAVE_STEP == 0) onSaved(i)
                database.cardQueries.insert(
                    id = card.id.value.toLong(),
                    name = card.name,
                    type = card.type,
                    frameType = card.frameType,
                    description = card.description,
                    race = card.race,
                    attribute = card.attribute.name,
                    atk = card.atk?.toLong(),
                    def = card.def?.toLong(),
                    level = card.level?.toLong(),
                    linkValue = card.linkValue?.toLong(),
                    linkMarkers = CardMapper.joinStrings(card.linkMarkers),
                    pendulumScale = card.pendulumScale?.toLong(),
                    archetype = card.archetype,
                    imageUrl = card.imageUrl,
                    imageUrlSmall = card.imageUrlSmall,
                    tcgBanStatus = card.tcgBanStatus.name,
                    ocgBanStatus = card.ocgBanStatus.name,
                    alternateIds = CardMapper.joinIds(card.alternateIds),
                    konamiId = card.konamiId?.toLong(),
                    tcgDate = card.tcgDate,
                    ocgDate = card.ocgDate,
                    formats = CardMapper.joinStrings(card.formats),
                    genesysPoints = card.genesysPoints?.toLong(),
                )
            }
            database.cardQueries.upsertSyncState(clock(), cards.size.toLong())
        }
        onSaved(cards.size)
    }

    private val recordJson = Json { ignoreUnknownKeys = true }

    companion object {
        /** Banlists move monthly; a week keeps the pool fresh without nagging. */
        const val DEFAULT_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Cards written between two reports of the saving's progress. */
        private const val SAVE_STEP = 500
    }
}
