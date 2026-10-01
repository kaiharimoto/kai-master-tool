package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.encodeBase64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A WebDAV server in memory, answering as Nextcloud does: folders made by MKCOL, listings by PROPFIND. */
class WebDavStoreTest {
    private val files = LinkedHashMap<String, ByteArray>()
    private val folders = mutableSetOf("/remote.php/dav/files/kai/")
    private var password = "app-password"

    private val engine = MockEngine { req ->
        val path = req.url.encodedPath
        if (req.headers["Authorization"] != "Basic " + "kai:$password".encodeToByteArray().encodeBase64()) {
            return@MockEngine respond("", HttpStatusCode.Unauthorized)
        }
        when (req.method.value) {
            "MKCOL" -> if (path in folders) respond("", HttpStatusCode.MethodNotAllowed) else { folders += path; respond("", HttpStatusCode.Created) }
            "PUT" -> {
                val parent = path.substringBeforeLast('/') + "/"
                if (parent !in folders) respond("", HttpStatusCode.Conflict)
                else {
                    files[path] = (req.body as OutgoingContent.ByteArrayContent).bytes()
                    respond("", HttpStatusCode.Created)
                }
            }
            "GET" -> files[path]?.let { respond(it, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
            "DELETE" -> if (files.remove(path) != null) respond("", HttpStatusCode.NoContent) else respond("", HttpStatusCode.NotFound)
            "PROPFIND" -> if (path !in folders) respond("", HttpStatusCode.NotFound) else {
                val children = files.keys.filter { it.substringBeforeLast('/') + "/" == path }
                val body = buildString {
                    append("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
                    append("<d:response><d:href>$path</d:href></d:response>")
                    children.forEach { append("<d:response><d:href>$it</d:href></d:response>") }
                    append("</d:multistatus>")
                }
                respond(body, HttpStatusCode.MultiStatus)
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun store() = WebDavStore(HttpClientFactory.create(engine), "https://cloud.example/remote.php/dav/files/kai", "kai", password)

    @Test
    fun writesReadsListsAndDeletes() = runTest {
        val s = store()
        assertEquals(emptyList(), s.list("devices"))
        s.write("devices/laptop.json", "{}".encodeToByteArray())
        s.write("blobs/ab12", byteArrayOf(1, 2, 3))
        assertTrue("/remote.php/dav/files/kai/NeueMasterTool/blobs/" in folders)
        assertEquals(listOf("laptop.json"), s.list("devices"))
        assertEquals(listOf("ab12"), s.list("blobs"))
        assertEquals(listOf<Byte>(1, 2, 3), s.read("blobs/ab12")!!.toList())
        s.delete("blobs/ab12")
        assertNull(s.read("blobs/ab12"))
    }

    @Test
    fun twoDevicesSyncThroughIt() = runTest {
        val a = MemoryLocal { 1L }.apply { put("ai/MEMORY.md", "notes") }
        val b = MemoryLocal { 2L }
        SyncEngine(store(), a, "a", "A", { 10L }).run(SyncState("a"))
        SyncEngine(store(), b, "b", "B", { 20L }).run(SyncState("b"))
        assertEquals("notes", b.text("ai/MEMORY.md"))
    }

    @Test
    fun aWrongPasswordIsSaidInWords() = runTest {
        password = "other"
        val e = assertFailsWith<SyncException> { WebDavStore(HttpClientFactory.create(engine), "https://cloud.example/remote.php/dav/files/kai", "kai", "wrong").test() }
        assertTrue("app password" in e.message.orEmpty())
    }
}
