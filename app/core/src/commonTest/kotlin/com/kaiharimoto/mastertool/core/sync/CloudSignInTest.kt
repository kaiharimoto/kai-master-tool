package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSignInTest {
    @Test
    fun pkceChallengeIsSha256InBase64Url() {
        // The same verifier through Python's hashlib and urlsafe_b64encode, padding stripped.
        assertEquals("mMQcRmOojlA7_yAFV9fzLs8IgoNAJJIL6pUl2z-R3QQ", CloudSignIn.challenge("dBjftJeZ4CVP-mJ92C9rG7ksoT8NFdBCQ4rDBwkUu2O"))
    }

    @Test
    fun theRedirectIsReadAndCheckedAgainstItsState() {
        assertEquals("abc", CloudSignIn.codeFrom("?code=abc&state=s1", "s1").getOrNull())
        assertTrue(CloudSignIn.codeFrom("code=abc&state=other", "s1").isFailure)
        val cancelled = CloudSignIn.codeFrom("error=access_denied&state=s1", "s1").exceptionOrNull()
        assertEquals("Signing in was cancelled.", cancelled?.message)
    }

    @Test
    fun theSignInPageAsksForTheAppFolderOnly() {
        val url = CloudSignIn.authorizeUrl(Cloud.GOOGLE_DRIVE, "v".repeat(43), "s")
        assertTrue("drive.appdata" in url && "code_challenge_method=S256" in url && "redirect_uri=http%3A%2F%2Flocalhost%3A53682%2F" in url, url)
        assertTrue("access_type=offline" in url)
    }

    @Test
    fun googleDriveKeepsEachPathAsAName() = runTest {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>() // id -> name, bytes
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val json = headersOf("Content-Type", "application/json")
            when {
                req.method.value == "GET" && path == "/drive/v3/files" ->
                    respond("""{"files":[${files.entries.joinToString(",") { (id, f) -> """{"id":"$id","name":"${f.first}"}""" }}]}""", HttpStatusCode.OK, json)
                req.method.value == "GET" && path.startsWith("/drive/v3/files/") -> files[path.substringAfterLast('/')]?.let { respond(it.second, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
                req.method.value == "POST" && path == "/upload/drive/v3/files" -> {
                    val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val name = Regex(""""name":"([^"]+)"""").find(body)!!.groupValues[1]
                    val content = body.substringAfter("application/octet-stream\r\n\r\n").substringBefore("\r\n--")
                    val id = "id${files.size}"
                    files[id] = name to content.encodeToByteArray()
                    respond("""{"id":"$id"}""", HttpStatusCode.OK, json)
                }
                req.method.value == "PATCH" -> {
                    val id = path.substringAfterLast('/')
                    files[id] = files[id]!!.first to (req.body as OutgoingContent.ByteArrayContent).bytes()
                    respond("""{"id":"$id"}""", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.BadRequest)
            }
        }
        val drive = GoogleDriveStore(HttpClientFactory.create(engine), { "token" })
        drive.write("devices/laptop.json", "{}".encodeToByteArray())
        drive.write("devices/laptop.json", "{\"a\":1}".encodeToByteArray())
        drive.write("blobs/ab", "x".encodeToByteArray())
        assertEquals(2, files.size)
        assertEquals(listOf("laptop.json"), GoogleDriveStore(HttpClientFactory.create(engine), { "token" }).list("devices"))
        assertEquals("{\"a\":1}", drive.read("devices/laptop.json")!!.decodeToString())
        assertNull(drive.read("devices/phone.json"))
    }
}
