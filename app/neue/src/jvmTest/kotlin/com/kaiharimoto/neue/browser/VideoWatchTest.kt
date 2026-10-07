package com.kaiharimoto.neue.browser

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A video chapter watched for real (Phase 2): a clip made here with ffmpeg — a picture and a 440 Hz tone — and its
 * captions, played in a headless, muted Chromium. Its captions are read, and its sound recorded from the page itself and
 * decoded back to samples that carry the tone. Skipped without a Chromium or ffmpeg.
 */
class VideoWatchTest {
    private fun chrome(): File? = System.getenv("NEUE_TEST_CHROME")?.let(::File)?.takeIf { it.canExecute() }
        ?: File("/opt/pw-browsers").listFiles { f -> f.name.startsWith("chromium-") }?.map { File(it, "chrome-linux/chrome") }?.firstOrNull { it.canExecute() }
        ?: ChromeFinder.find("")

    private fun ffmpeg(): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }

    @Test
    fun aVideosCaptionsAreReadAndItsSoundIsRecordedFromThePage() = runBlocking {
        val exe = chrome() ?: return@runBlocking println("No Chromium here: skipped.")
        val ff = ffmpeg() ?: return@runBlocking println("No ffmpeg here: skipped.")
        val dir = Files.createTempDirectory("nmt-video").toFile()
        val clip = File(dir, "clip.webm")
        val made = ProcessBuilder(
            ff.path, "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=6",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
            "-c:v", "libvpx", "-b:v", "200k", "-c:a", "libopus", "-shortest", clip.path,
        ).redirectErrorStream(true).start()
        if (made.waitFor() != 0 || !clip.isFile) return@runBlocking println("ffmpeg could not make the clip: skipped.")
        val vtt = "WEBVTT\n\n00:00:00.500 --> 00:00:02.000\nGoing second, keep Nibiru.\n\n00:00:03.000 --> 00:00:05.000\nThen Branded Fusion.\n"
        val page = """<html><body><h1>Chapter 4: Going second</h1>
            <video width="320" height="240" src="/clip.webm" playsinline><track kind="captions" srclang="en" src="/c.vtt"></video>
            </body></html>"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun serve(path: String, type: String, body: ByteArray) = server.createContext(path) { ex ->
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        serve("/chapter", "text/html; charset=utf-8", page.toByteArray())
        serve("/clip.webm", "video/webm", clip.readBytes())
        serve("/c.vtt", "text/vtt", vtt.toByteArray())
        server.start()
        val surface = ChromeSurface.launch(exe, File(dir, "profile"), "about:blank", visible = false, extra = listOf("--no-sandbox"))
        try {
            surface.open("http://127.0.0.1:${server.address.port}/chapter")
            val video = assertNotNull(surface.video())
            assertEquals("", video.frame)
            assertTrue(!video.protected)
            // The captions, from the player's own track.
            val cues = surface.captions()
            assertEquals(listOf("Going second, keep Nibiru.", "Then Branded Fusion."), cues.map { it.second.trim() })
            assertEquals(0.5, cues.first().first, 0.01)

            // The sound, recorded from the page as it plays (muted: the study is silent).
            assertEquals("ok", surface.listen(2.0))
            val sound = ByteArrayOutputStream()
            val until = System.currentTimeMillis() + 20_000
            var frame: ByteArray? = null
            while (System.currentTimeMillis() < until && surface.video()?.ended != true) {
                if (frame == null) frame = surface.videoFrame(0.5)
                surface.takeSound().forEach(sound::write)
                delay(500)
            }
            surface.stopListening()
            surface.takeSound().forEach(sound::write)
            val bytes = sound.toByteArray()
            assertTrue(bytes.size > 1_000, "recorded ${bytes.size} bytes")
            // A webm file: EBML's magic number first.
            assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), bytes.take(4).map { it.toInt() and 0xff })
            val jpeg = assertNotNull(frame)
            assertEquals(listOf(0xFF, 0xD8), jpeg.take(2).map { it.toInt() and 0xff })

            // Decoded back to samples, the tone is there.
            val samples = VideoAudio.decode(bytes)
            assertTrue(samples.size > VideoAudio.RATE, "decoded ${samples.size} samples")
            val rms = kotlin.math.sqrt(samples.map { it * it.toDouble() }.average())
            assertTrue(rms > 0.05, "the tone's level was $rms")
        } finally {
            surface.close()
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
