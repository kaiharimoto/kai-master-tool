package com.kaiharimoto.mastertool.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import io.ktor.util.encodeBase64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The sign-in clouds (1.0.68): Google Drive, Dropbox and OneDrive, each written to its app folder —
 * a folder only this app can see, so signing in never hands it the rest of the person's files.
 *
 * One way in for all three, on the desk and on Android alike: the system's browser opens the service's
 * own sign-in page, and the service sends the browser back to [REDIRECT], where the app is listening for
 * that one request. OAuth with PKCE, so no secret is needed (Google's desktop clients carry one anyway,
 * which Google says is not a secret for an installed app). The refresh token lives in `SecretStore`.
 */
enum class Cloud(
    val id: String,
    val label: String,
    val authorize: String,
    val token: String,
    val scope: String,
    /** Where the files are, said to the person. */
    val where: String,
) {
    GOOGLE_DRIVE(
        SyncPrefs.GOOGLE_DRIVE, "Google Drive",
        "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        "https://www.googleapis.com/auth/drive.appdata",
        "a hidden app folder in your Drive",
    ),
    DROPBOX(
        SyncPrefs.DROPBOX, "Dropbox",
        "https://www.dropbox.com/oauth2/authorize", "https://api.dropboxapi.com/oauth2/token",
        "",
        "Dropbox › Apps › Neue Master Tool",
    ),
    ONEDRIVE(
        SyncPrefs.ONEDRIVE, "OneDrive",
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize", "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "Files.ReadWrite.AppFolder offline_access User.Read",
        "OneDrive › Apps › Neue Master Tool",
    ),
    ;

    /** This app's registration with the service ([CloudClients]); blank until it is registered. */
    val clientId: String get() = CloudClients.id(this)
    val clientSecret: String get() = CloudClients.secret(this)

    /** Offered only once the app is registered with the service. */
    val ready: Boolean get() = clientId.isNotBlank()

    companion object {
        fun of(id: String): Cloud? = entries.firstOrNull { it.id == id }
    }
}

/** A signed-in account's tokens, kept in `SecretStore` under `sync:<cloud>`. */
@Serializable
data class CloudTokens(val refresh: String = "", val access: String = "", val expiresAt: Long = 0, val account: String = "")

object CloudSignIn {
    /** Fixed, because Dropbox matches a redirect exactly: registered as `http://localhost:53682/` with each service. */
    const val PORT = 53682
    const val REDIRECT = "http://localhost:$PORT/"

    /** The PKCE challenge for [verifier]: SHA-256, Base64 URL without padding. */
    fun challenge(verifier: String): String = base64Url(Sha256.digest(verifier.encodeToByteArray()))

    /** The page the browser opens. [verifier] and [state] are random, from the platform's secure source. */
    fun authorizeUrl(cloud: Cloud, verifier: String, state: String): String {
        val params = buildList {
            add("client_id" to cloud.clientId)
            add("redirect_uri" to REDIRECT)
            add("response_type" to "code")
            add("state" to state)
            add("code_challenge" to challenge(verifier))
            add("code_challenge_method" to "S256")
            if (cloud.scope.isNotBlank()) add("scope" to cloud.scope)
            when (cloud) {
                Cloud.GOOGLE_DRIVE -> { add("access_type" to "offline"); add("prompt" to "consent") }
                Cloud.DROPBOX -> add("token_access_type" to "offline")
                Cloud.ONEDRIVE -> add("prompt" to "select_account")
            }
        }
        return cloud.authorize + "?" + params.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
    }

    /** The code from the redirect's query, or why there is none. */
    fun codeFrom(query: String, state: String): Result<String> {
        val q = query.removePrefix("?").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').decodeQuery() }
        q["error"]?.let { return Result.failure(SyncException(if (it == "access_denied") "Signing in was cancelled." else "The service refused the sign-in ($it).")) }
        if (q["state"] != state) return Result.failure(SyncException("The sign-in did not come from this app. Try again."))
        return q["code"]?.let { Result.success(it) } ?: Result.failure(SyncException("The service sent no sign-in code. Try again."))
    }

    suspend fun exchange(http: HttpClient, cloud: Cloud, code: String, verifier: String, now: Long): CloudTokens =
        tokens(http, cloud, now, null) {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", REDIRECT)
            append("code_verifier", verifier)
        }

    /** A fresh access token from [t]'s refresh token; the refresh token is kept when the service does not send a new one. */
    suspend fun refresh(http: HttpClient, cloud: Cloud, t: CloudTokens, now: Long): CloudTokens =
        tokens(http, cloud, now, t) {
            append("grant_type", "refresh_token")
            append("refresh_token", t.refresh)
            if (cloud.scope.isNotBlank() && cloud == Cloud.ONEDRIVE) append("scope", cloud.scope)
        }

    private suspend fun tokens(http: HttpClient, cloud: Cloud, now: Long, old: CloudTokens?, body: io.ktor.http.ParametersBuilder.() -> Unit): CloudTokens {
        val r = try {
            http.submitForm(cloud.token, Parameters.build {
                append("client_id", cloud.clientId)
                if (cloud.clientSecret.isNotBlank()) append("client_secret", cloud.clientSecret)
                body()
            })
        } catch (e: Exception) {
            throw SyncException("${cloud.label} could not be reached. Check that this device is online.", e)
        }
        val text = r.bodyAsText()
        val o = runCatching { Sync.json.parseToJsonElement(text) as JsonObject }.getOrNull()
        if (r.status.value !in 200..299 || o == null) {
            val why = o?.get("error")?.jsonPrimitive?.content
            throw SyncException(
                if (why == "invalid_grant") "${cloud.label} has signed this device out. Sign in again in Settings › Sync."
                else "${cloud.label} would not sign in (${why ?: r.status.value}).",
            )
        }
        val access = o["access_token"]?.jsonPrimitive?.content.orEmpty()
        val expires = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        return CloudTokens(
            refresh = o["refresh_token"]?.jsonPrimitive?.content ?: old?.refresh.orEmpty(),
            access = access,
            // A minute early, so a token never runs out in the middle of a sync.
            expiresAt = now + (expires - 60) * 1000,
            account = old?.account.orEmpty(),
        )
    }

    /** Who signed in, to show under the service's name; empty when the service will not say. */
    suspend fun account(http: HttpClient, cloud: Cloud, access: String): String = runCatching {
        val r = when (cloud) {
            Cloud.GOOGLE_DRIVE -> http.get("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)") { header("Authorization", "Bearer $access") }
            Cloud.DROPBOX -> http.post("https://api.dropboxapi.com/2/users/get_current_account") { header("Authorization", "Bearer $access") }
            Cloud.ONEDRIVE -> http.get(OneDriveStore.ME) { header("Authorization", "Bearer $access") }
        }
        val o = Sync.json.parseToJsonElement(r.bodyAsText()) as JsonObject
        when (cloud) {
            Cloud.GOOGLE_DRIVE -> (o["user"] as? JsonObject)?.get("emailAddress")?.jsonPrimitive?.content
            Cloud.DROPBOX -> o["email"]?.jsonPrimitive?.content
            Cloud.ONEDRIVE -> (o["mail"] ?: o["userPrincipalName"])?.jsonPrimitive?.content
        }.orEmpty()
    }.getOrDefault("")

    private fun base64Url(bytes: ByteArray) = bytes.encodeBase64().replace('+', '-').replace('/', '_').trimEnd('=')

    private fun String.decodeQuery(): String = runCatching { decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(this)
}
