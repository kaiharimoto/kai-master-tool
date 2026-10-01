package com.kaiharimoto.mastertool.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a sign-in store needs: a token asked for before each call, and failures worded once. */
abstract class CloudStore(
    protected val http: HttpClient,
    protected val cloud: Cloud,
    /** A current access token; refreshed by the caller when it has run out. */
    private val token: suspend () -> String,
    account: String,
) : SyncStore {
    override val label: String = cloud.label + if (account.isNotBlank()) " · $account" else ""

    protected suspend fun call(method: HttpMethod, url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val t = token()
        return try {
            http.request(url) {
                this.method = method
                header("Authorization", "Bearer $t")
                block()
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("${cloud.label} could not be reached. Check that this device is online.", e)
        }
    }

    protected suspend fun check(r: HttpResponse, doing: String) {
        val code = r.status.value
        if (code in 200..299) return
        throw SyncException(
            when (code) {
                401 -> "${cloud.label} has signed this device out. Sign in again in Settings › Sync."
                403 -> "${cloud.label} would not let the app $doing. Sign in again in Settings › Sync."
                429 -> "${cloud.label} asked the app to slow down. Sync will try again in a few minutes."
                507 -> "Your ${cloud.label} is full."
                else -> "${cloud.label} could not $doing ($code)."
            },
        )
    }

    protected suspend fun json(r: HttpResponse): JsonObject = Sync.json.parseToJsonElement(r.bodyAsText()).jsonObject
}

/**
 * Google Drive's app data folder (`drive.appdata`): hidden from the person's Drive and from every
 * other app. Drive has no paths, so each file's name is its whole path (`blobs/<hash>`) in one flat
 * folder, and the names are read once per sync to find each file's id.
 */
class GoogleDriveStore(http: HttpClient, token: suspend () -> String, account: String = "") : CloudStore(http, Cloud.GOOGLE_DRIVE, token, account) {
    private var ids: MutableMap<String, String>? = null

    private suspend fun index(): MutableMap<String, String> {
        ids?.let { return it }
        val out = HashMap<String, String>()
        var page: String? = null
        do {
            val url = "$API?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name)" + (page?.let { "&pageToken=${it.encodeURLParameter()}" } ?: "")
            val r = call(HttpMethod.Get, url)
            check(r, "list its files")
            val o = json(r)
            o["files"]?.jsonArray?.forEach { f -> out[f.jsonObject["name"]!!.jsonPrimitive.content] = f.jsonObject["id"]!!.jsonPrimitive.content }
            page = o["nextPageToken"]?.jsonPrimitive?.content
        } while (page != null)
        ids = out
        return out
    }

    override suspend fun list(folder: String): List<String> = index().keys.filter { it.startsWith("$folder/") }.map { it.removePrefix("$folder/") }

    override suspend fun read(name: String): ByteArray? {
        val id = index()[name] ?: return null
        val r = call(HttpMethod.Get, "$API/$id?alt=media")
        if (r.status.value == 404) return null
        check(r, "read a file")
        return r.bodyAsBytes()
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        val known = index()[name]
        if (known != null) {
            val r = call(HttpMethod.Patch, "$UPLOAD/$known?uploadType=media") {
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
            check(r, "save a file")
            return
        }
        val boundary = "neue-${Sha256.hex(name).take(16)}"
        val meta = buildJsonObject {
            put("name", name)
            put("parents", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("appDataFolder"))))
        }.toString()
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").encodeToByteArray() +
            bytes + "\r\n--$boundary--\r\n".encodeToByteArray()
        val r = call(HttpMethod.Post, "$UPLOAD?uploadType=multipart&fields=id") {
            setBody(io.ktor.http.content.ByteArrayContent(body, ContentType.parse("multipart/related; boundary=$boundary")))
        }
        check(r, "save a file")
        json(r)["id"]?.jsonPrimitive?.content?.let { index()[name] = it }
    }

    override suspend fun delete(name: String) {
        val id = index()[name] ?: return
        val r = call(HttpMethod.Delete, "$API/$id")
        if (r.status.value != 404) check(r, "delete a file")
        index().remove(name)
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }
}
