package com.kaiharimoto.neue.browser

import com.kaiharimoto.mastertool.core.ai.course.BrowseGuard
import com.kaiharimoto.mastertool.core.ai.course.Chapters
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.web.HtmlText
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The study's browser driven for real: a Chromium found on this machine (`NEUE_TEST_CHROME`, Playwright's, or the
 * person's own), headless, over a guide served here. Skipped where there is no Chromium at all.
 */
class ChromeSurfaceTest {
    private companion object {
        const val REPLAY = "{\"player1\":{\"username\":\"Joe\"},\"plays\":[{\"play\":\"Enter M1\",\"username\":\"Joe\"}]}"
    }

    private fun chrome(): File? = System.getenv("NEUE_TEST_CHROME")?.let(::File)?.takeIf { it.canExecute() }
        ?: File("/opt/pw-browsers").listFiles { f -> f.name.startsWith("chromium-") }?.map { File(it, "chrome-linux/chrome") }?.firstOrNull { it.canExecute() }
        ?: ChromeFinder.find("")

    private val pages = mapOf(
        "/guide" to """<html><head><title>Branded Masterclass</title></head><body>
            <h1>Branded Masterclass</h1><p>By Joe.</p>
            <a href="/guide/welcome">Welcome</a> <a href="/guide/going-first">Going first</a>
            <a href="/checkout">Buy the guide</a></body></html>""",
        "/replay" to """<html><head><title>Replay</title></head><body><p>Loading the replay.</p>
            <script>setTimeout(function () {
              var xhr = new XMLHttpRequest(); xhr.open("POST", "/view-replay?id=1-11", true);
              xhr.onreadystatechange = function () { if (xhr.readyState == 4) document.body.insertAdjacentHTML('beforeend', '<p>Loaded.</p>') };
              xhr.send(new FormData());
            }, 1500)</script></body></html>""",
        "/guide/going-first" to """<html><head><title>Going first</title></head><body>
            <h1>Going first</h1><p>Open with Aluber.</p>
            <div id="more" style="display:none"><p>Then Branded Fusion for Mirrorjade.</p></div>
            <button onclick="document.getElementById('more').style.display='block'">Show more</button>
            <script>document.body.insertAdjacentHTML('beforeend', '<p>Drawn by a script: Albion.</p>')</script>
            </body></html>""",
    )

    @Test
    fun theStudysBrowserReadsAGuideAndPressesWhatRevealsIt() = runBlocking {
        val exe = chrome() ?: return@runBlocking println("No Chromium here: skipped.")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        pages.forEach { (path, body) ->
            server.createContext(path) { ex ->
                val bytes = (if (ex.requestURI.path == path) body else "<html><body>Nothing</body></html>").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/view-replay") { ex ->
            val bytes = REPLAY.toByteArray()
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val profile = Files.createTempDirectory("nmt-browser").toFile()
        val surface = ChromeSurface.launch(exe, File(profile, "profile"), "about:blank", visible = false, extra = listOf("--no-sandbox"))
        try {
            val loaded = surface.open("$base/guide")
            assertEquals("Branded Masterclass", loaded.title)
            // The contents, read off the guide's own links.
            val chapters = Chapters.fromLinks(surface.links(), "$base/guide")
            assertEquals(listOf("Welcome", "Going first"), chapters.map { it.title })

            surface.open(chapters[1].url)
            // What a script drew is read; what is hidden is not, until pressed.
            val before = HtmlText.text(surface.html(), 50_000)
            assertTrue("Drawn by a script: Albion." in before, before)
            val shown = surface.elements()
            val more = assertNotNull(shown.firstOrNull { it.text == "Show more" })
            val course = Course("c", "https://metafy.gg/@joe/guides/x")
            assertNull(BrowseGuard.clickRefusal(more, course))
            surface.click(more.ref)
            val after = surface.html()
            assertTrue(Regex("""id="more" style="display: ?block""").containsMatchIn(after), after.take(600))

            // A replay page asks for its record itself, after a moment (DuelingBook's check): what it is sent is kept.
            val received = surface.openReceiving("$base/replay", "view-replay", timeoutMs = 20_000)
            assertEquals(REPLAY, received.body)
            assertTrue(received.loaded.url.endsWith("/replay"))
            // Nothing asked for: nothing kept, and the wait ends.
            assertNull(surface.openReceiving("$base/guide", "view-replay", timeoutMs = 1_500).body)

            // A picture of the page, and the element at a point of it.
            val png = surface.screenshot()
            assertTrue(png.size > 1_000 && png[1] == 'P'.code.toByte())
            assertTrue(surface.alive)
            assertTrue(!surface.hasVideo())
        } finally {
            surface.close()
            server.stop(0)
            profile.deleteRecursively()
        }
    }
}
