package com.kaiharimoto.mastertool.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.util.encodeBase64

/**
 * A WebDAV folder as a sync store (1.0.68): Nextcloud, ownCloud, pCloud, Koofr, Synology, a NAS —
 * any server that speaks WebDAV, with an address, a user and an app password. The files go in a
 * `NeueMasterTool` folder under the address given, made the first time something is written there.
 */
class WebDavStore(
    private val http: HttpClient,
    url: String,
    private val user: String,
    password: String,
    override val label: String = "WebDAV",
) : SyncStore {
    private val root = url.trim().trimEnd('/') + "/" + ROOT + "/"
    private val auth = "Basic " + "$user:$password".encodeToByteArray().encodeBase64()
    private var made = false

    override suspend fun list(folder: String): List<String> {
        val url = root + folder.trim('/') + "/"
        val r = call(PROPFIND, url) {
            header("Depth", "1")
            contentType(ContentType.Application.Xml)
            setBody(PROPFIND_BODY)
        }
        if (r.status.value == 404) return emptyList()
        check(r, "list the folder")
        val self = path(url)
        return HREF.findAll(r.bodyAsText()).map { it.groupValues[1].trim() }
            .map { path(it) }
            .filter { it != self && it.trimEnd('/') != self.trimEnd('/') && !it.endsWith("/") }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toList()
    }

    override suspend fun read(name: String): ByteArray? {
        val r = call(HttpMethod.Get, root + encode(name))
        if (r.status.value == 404) return null
        check(r, "read a file")
        return r.bodyAsBytes()
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        if (!made) makeFolders()
        var r = call(HttpMethod.Put, root + encode(name)) {
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        if (r.status.value == 409 || r.status.value == 404) {
            // The folder went missing since: make it again and try once more.
            made = false
            makeFolders()
            r = call(HttpMethod.Put, root + encode(name)) {
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
        }
        check(r, "write a file")
    }

    override suspend fun delete(name: String) {
        val r = call(HttpMethod.Delete, root + encode(name))
        if (r.status.value != 404) check(r, "delete a file")
    }

    /** Signs in and reads the folder: what Settings runs before saving a connection. */
    suspend fun test() {
        makeFolders()
        list(Sync.DEVICES)
    }

    private suspend fun makeFolders() {
        listOf(root, root + Sync.DEVICES + "/", root + Sync.BLOBS + "/").forEach { folder ->
            val r = call(MKCOL, folder)
            // 201 made, 405 already there; some servers answer 301 or 200 for a folder that exists.
            if (r.status.value !in setOf(200, 201, 204, 301, 405)) check(r, "make the folder")
        }
        made = true
    }

    private suspend fun call(method: HttpMethod, url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        try {
            http.request(url) {
                this.method = method
                header("Authorization", auth)
                block()
            }
        } catch (e: Exception) {
            throw SyncException("The server could not be reached. Check the address and that this device is online.", e)
        }

    private suspend fun check(r: HttpResponse, doing: String) {
        val code = r.status.value
        if (code in 200..299 || code == 207) return
        throw SyncException(
            when (code) {
                401 -> "The server refused the sign-in. Check the user name, and use an app password where the service has them."
                403 -> "The server would not let this account $doing. Check its permissions."
                404 -> "The address was not found. It should be the WebDAV address of a folder."
                507 -> "The server is out of space."
                else -> "The server could not $doing ($code)."
            },
        )
    }

    /** A href as a decoded path, without scheme and host. */
    private fun path(href: String): String {
        val noHost = if ("://" in href) "/" + href.substringAfter("://").substringAfter('/', "") else href
        return noHost.decodeURLPart()
    }

    private fun encode(name: String) = name.split('/').joinToString("/") { it.encodeURLPathPart() }

    companion object {
        const val ROOT = "NeueMasterTool"
        private val PROPFIND = HttpMethod("PROPFIND")
        private val MKCOL = HttpMethod("MKCOL")
        private val HREF = Regex("""<(?:[A-Za-z0-9]+:)?href>([^<]+)</(?:[A-Za-z0-9]+:)?href>""")
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

        /** Where the common services keep their WebDAV, for the address field's hints. */
        val PRESETS = listOf(
            "Nextcloud" to "https://your.server/remote.php/dav/files/USER/",
            "ownCloud" to "https://your.server/remote.php/webdav/",
            "Koofr" to "https://app.koofr.net/dav/Koofr/",
            "pCloud" to "https://webdav.pcloud.com/",
            "Synology" to "https://your.nas:5006/home/",
        )
    }
}
