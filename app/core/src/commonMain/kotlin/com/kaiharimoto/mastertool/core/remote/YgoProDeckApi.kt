package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.model.Card
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

    /** Fetches every card. Expect this to be tens of megabytes. */
    suspend fun fetchAllCards(): Result<List<Card>> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/cardinfo.php")
        if (!response.status.isSuccess()) {
            error("Card database request failed with ${response.status}")
        }
        response.body<CardInfoResponse>().data.map { it.toDomain() }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://db.ygoprodeck.com/api/v7"

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
