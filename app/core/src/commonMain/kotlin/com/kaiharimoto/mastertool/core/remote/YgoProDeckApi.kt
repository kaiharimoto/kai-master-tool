package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.data.PoolVersion
import com.kaiharimoto.mastertool.core.model.Card
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * Read-only client for the YGOPRODeck card database.
 *
 * The full pool is a single large response, so it is fetched once and mirrored
 * locally rather than queried per search. Failures are returned as [Result]
 * rather than thrown: on a tablet at a venue, "couldn't refresh, using the
 * cached pool" is a normal state, not an exception.
 */
class YgoProDeckApi(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * Fetches every card. Expect this to be tens of megabytes, sent with no
     * length: [onBytes] hears how many have arrived as they do, and [onRead]
     * when the last has and the reading begins.
     */
    suspend fun fetchAllCards(
        onBytes: (suspend (Long) -> Unit)? = null,
        onRead: (() -> Unit)? = null,
    ): Result<List<Card>> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/cardinfo.php?$POOL_QUERY") {
            if (onBytes != null) onDownload { received, _ -> onBytes(received) }
        }
        if (!response.status.isSuccess()) {
            error("Card database request failed with ${response.status}")
        }
        onRead?.invoke()
        response.body<CardInfoResponse>().data.map { it.toDomain() }
    }

    /** Which version the database is at, and when it last changed: a few dozen bytes, for asking "is mine current?". */
    suspend fun checkVersion(): Result<PoolVersion> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/checkDBVer.php")
        if (!response.status.isSuccess()) {
            error("Version request failed with ${response.status}")
        }
        val row = response.body<List<DbVersionDto>>().firstOrNull() ?: error("The version request came back empty")
        PoolVersion(row.databaseVersion.ifBlank { error("The version request named no version") }, row.lastUpdate)
    }

    /** Every set ever printed, with its TCG release date where it has one. A couple of hundred kilobytes. */
    suspend fun fetchCardSets(): Result<List<CardSetRelease>> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/cardsets.php")
        if (!response.status.isSuccess()) {
            error("Card set request failed with ${response.status}")
        }
        response.body<List<CardSetRelease>>()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://db.ygoprodeck.com/api/v7"

        /**
         * The whole pool with its release data (Phase B): `misc=yes` adds each card's formats, TCG and OCG dates and
         * Konami id; `format=genesys` adds its Genesys points and, checked against the live site, keeps every card
         * (14,597 either way). About a fifth larger than the bare pool.
         */
        const val POOL_QUERY = "misc=yes&format=genesys"

        /**
         * JSON configuration the API responses require.
         *
         * The feed carries fields this app does not model and occasionally sends
         * a null where a default belongs, so unknown keys are ignored and null
         * primitives fall back to the declared default rather than failing the
         * whole 13,000-card payload over one row.
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
