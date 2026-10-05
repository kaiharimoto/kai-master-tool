package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.SyntheticCamera
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeEvent
import com.kaiharimoto.mastertool.core.present.record.Wav
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import com.kaiharimoto.neue.platform.RenderProgress
import com.kaiharimoto.neue.platform.RenderRequest
import com.kaiharimoto.neue.platform.RenderResult
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideFontSet
import com.kaiharimoto.neue.present.record.Bgra
import com.kaiharimoto.neue.present.record.CameraWriter
import com.kaiharimoto.neue.present.record.DeskCamera
import com.kaiharimoto.neue.present.record.DeskMic
import com.kaiharimoto.neue.present.record.SyntheticFrames
import com.kaiharimoto.neue.present.record.TakeRenderer
import com.kaiharimoto.neue.present.record.WavWriter
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Present's recording, end to end on a machine with no camera (1.1.13): a synthetic camera's frames written as the
 * live camera writes them, silent sound as a WAV, a take's events — then the take rendered into a real video by the
 * slide painter and FFmpeg, and the file read back (by ffprobe when the machine has it). `NEUE_RECORD_OUT` keeps the
 * video in a folder of one's choosing.
 */
class TakeRenderTest {
    private val out: File = (System.getenv("NEUE_RECORD_OUT")?.let(::File) ?: File("build/take-test")).apply { mkdirs() }

    private fun presentation() = Presentation(
        "p-render", "Render test",
        webcam = WebcamZone(enabled = true, preset = WebcamZone.BOTTOM_RIGHT, shape = WebcamZone.SHAPE_RECT),
        slides = listOf(
            Slide("s1", title = "Snake-Eye, explained", elements = listOf(
                Element("t1", Element.TEXT, 0.05f, 0.1f, 0.9f, 0.3f, anchor = Element.ANCHOR_STAGE, paras = listOf(Para.of("Snake-Eye, explained"))),
            )),
            Slide("s2", title = "The engine", elements = listOf(
                Element("t2", Element.TEXT, 0.05f, 0.1f, 0.9f, 0.3f, anchor = Element.ANCHOR_STAGE, paras = listOf(Para.of("The engine"))),
                Element("b2", Element.SHAPE, 200f, 500f, 600f, 300f, fill = com.kaiharimoto.mastertool.core.present.Fill(color = "@accent")),
            )),
        ),
    )

    /** The natives load here, or the test says why it cannot run and stops. */
    private fun ffmpegHere(): Boolean = try {
        org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avcodec::class.java)
        true
    } catch (e: Throwable) {
        println("[take-render] FFmpeg's natives do not load here (${e.message}); skipped")
        false
    }

    @Test
    fun aTakeRendersIntoARealVideo() {
        if (!ffmpegHere()) return
        val dir = File(out, "take").apply { deleteRecursively(); mkdirs() }
        // The camera, as the live camera writes it: three seconds of synthetic frames on the take's clock.
        val cam = CameraWriter(File(dir, Take.CAMERA), SyntheticCamera.WIDTH, SyntheticCamera.HEIGHT)
        cam.start()
        for (n in 0 until 90) {
            val px = SyntheticCamera.frame(n)
            cam.write(Bgra(SyntheticCamera.WIDTH, SyntheticCamera.HEIGHT, SyntheticCamera.WIDTH * 4, SyntheticCamera.bgra(px)), n * 1000L / 30)
        }
        cam.close()
        // Three seconds of silence at 48 kHz, mono.
        WavWriter(File(dir, Take.AUDIO), 48_000, 1).apply { silence(3L * 48_000); close() }
        val take = Take(
            "t-render", "p-render", "Take 1", durationMs = 3_000, camera = Take.CAMERA, audio = Take.AUDIO, fps = 30,
            events = listOf(
                TakeEvent(0, TakeEvent.GO),
                TakeEvent(1_200, TakeEvent.GO, slide = 1),
                TakeEvent(1_600, TakeEvent.LASER, x = 900f, y = 500f),
                TakeEvent(2_000, TakeEvent.LASER, x = 1000f, y = 520f),
                TakeEvent(2_200, TakeEvent.LASER_OFF),
                TakeEvent(2_300, TakeEvent.INK_START, x = 300f, y = 300f),
                TakeEvent(2_400, TakeEvent.INK, x = 600f, y = 320f),
            ),
        )
        val p = presentation()
        val ctx = SlideContext(p, SlideFontSet(emptyMap()), { null }, { null })
        var last: RenderProgress? = null
        val started = System.nanoTime()
        val result = runBlocking {
            TakeRenderer.render(RenderRequest(take, dir, p, width = 640, height = 360, fps = 30), ctx, null, { last = it })
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val done = result as? RenderResult.Done ?: fail("the render failed: $result")
        println("[take-render] ${done.file.name}, ${done.file.length()} bytes, ${EncoderPick.describe(done.codec)} (${done.codec}), in %.1f s".format(seconds))
        assertTrue(done.file.isFile && done.file.length() > 10_000, "a video was written")
        assertEquals(90, last?.done, "every frame was made")
        // The encoder asked for is this machine's own H.264 or VP9: never OpenH264.
        assertTrue(done.codec !in EncoderPick.NEVER)
        // Read back by FFmpeg itself: 90 pictures, and sound.
        val g = FFmpegFrameGrabber(done.file)
        g.start()
        var pictures = 0
        while (true) {
            val f = g.grabImage() ?: break
            if (f.image != null) pictures++
        }
        val hasAudio = g.audioChannels > 0
        g.stop(); g.release()
        assertEquals(90, pictures, "the video holds every frame")
        assertTrue(hasAudio, "the video has its sound")
        ffprobe(done.file)?.let { report ->
            println("[take-render] ffprobe: $report")
            assertTrue("codec_type=video" in report && "codec_type=audio" in report, report)
        }
    }

    /** ffprobe's account of [f], when the machine has ffprobe. */
    private fun ffprobe(f: File): String? = runCatching {
        val proc = ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-show_entries",
            "stream=codec_name,codec_type,width,height,nb_read_frames,sample_rate:format=duration,format_name", "-of", "default=nw=1", f.absolutePath)
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) null else text.lines().filter { it.isNotBlank() }.joinToString(", ")
    }.getOrNull()

    @Test
    fun theLiveCameraWritesAFileTheRendererReads() {
        if (!ffmpegHere()) return
        val dir = File(out, "live").apply { deleteRecursively(); mkdirs() }
        val frames = SyntheticFrames()
        val camera = DeskCamera("Synthetic camera", { frames.next() }, {})
        val mic = DeskMic.silent()
        try {
            val t0 = System.currentTimeMillis()
            var paused = false
            assertTrue(camera.startRecording(File(dir, Take.CAMERA), { System.currentTimeMillis() - t0 }, { paused }))
            assertTrue(mic.startRecording(File(dir, Take.AUDIO)) { paused })
            Thread.sleep(1_000)
            // A pause writes nothing.
            paused = true
            Thread.sleep(400)
            paused = false
            Thread.sleep(300)
            camera.stopRecording()
            mic.stopRecording()
            assertTrue(camera.picture.value != null, "the preview had pictures")
        } finally {
            camera.close()
            mic.close()
        }
        val g = FFmpegFrameGrabber(File(dir, Take.CAMERA))
        g.start()
        val stamps = ArrayList<Long>()
        while (g.grabImage() != null) stamps += g.timestamp / 1000
        g.stop(); g.release()
        assertTrue(stamps.size in 25..60, "about 1.3 s of frames at 30 a second, not ${stamps.size}")
        assertEquals(stamps.sorted(), stamps, "the frames stand in order on the take's clock")
        val wav = File(dir, Take.AUDIO)
        val info = Wav.read(wav.readBytes().copyOf(4096), wav.length())!!
        assertTrue(info.durationMs in 1_000..1_500, "the sound skipped the pause: ${info.durationMs} ms")
    }
}
