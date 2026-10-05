package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.present.record.CameraNames
import com.kaiharimoto.mastertool.core.present.record.Chapters
import com.kaiharimoto.mastertool.core.present.record.PresenterView
import com.kaiharimoto.mastertool.core.present.record.RecordPrefs
import com.kaiharimoto.mastertool.core.present.record.RenderPlan
import com.kaiharimoto.mastertool.core.present.record.SyntheticCamera
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeClock
import com.kaiharimoto.mastertool.core.present.record.TakeCodec
import com.kaiharimoto.mastertool.core.present.record.TakeEvent
import com.kaiharimoto.mastertool.core.present.record.TakeLog
import com.kaiharimoto.mastertool.core.present.record.TakeNames
import com.kaiharimoto.mastertool.core.present.record.TakePaths
import com.kaiharimoto.mastertool.core.present.record.TakeTimeline
import com.kaiharimoto.mastertool.core.present.record.Wav
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recording a take (1.1.13): the clock a pause stops, the events written from what the presenter shows, the render
 * plan that replays them frame by frame, and the files round them.
 */
class RecordTest {
    @Test
    fun aPauseStopsTheTakesClock() {
        var now = 1_000L
        val clock = TakeClock { now }
        assertEquals(0L, clock.elapsed)
        clock.start()
        now += 5_000
        assertEquals(5_000L, clock.elapsed)
        clock.pause()
        now += 60_000
        assertEquals(5_000L, clock.elapsed, "nothing passes while paused")
        assertTrue(clock.paused)
        clock.resume()
        now += 2_500
        assertEquals(7_500L, clock.elapsed)
        clock.pause(); clock.pause()
        now += 10
        clock.resume(); clock.resume()
        assertEquals(7_500L, clock.elapsed, "pausing twice is pausing once")
    }

    /** A show as a presenter plays it, observed at each change. */
    private fun played(): Pair<List<Pair<Long, PresenterView>>, Long> {
        val v = ArrayList<Pair<Long, PresenterView>>()
        var view = PresenterView(Cursor(0))
        fun at(t: Long, change: (PresenterView) -> PresenterView) { view = change(view); v += t to view }
        v += 0L to view
        at(2_000) { it.copy(cursor = Cursor(0, 1)) }
        at(12_000) { it.copy(cursor = Cursor(1)) }
        at(13_000) { it.copy(laser = 100f to 100f) }
        at(13_010) { it.copy(laser = 110f to 100f) } // within the laser's limit: held back
        at(13_050) { it.copy(laser = 140f to 120f) }
        at(14_000) { it.copy(laser = null) }
        at(15_000) { it.copy(ink = listOf(listOf(1f to 1f))) }
        at(15_100) { it.copy(ink = listOf(listOf(1f to 1f, 2f to 2f))) }
        at(15_200) { it.copy(ink = listOf(listOf(1f to 1f, 2f to 2f), listOf(9f to 9f))) }
        at(16_000) { it.copy(ink = emptyList()) }
        at(17_000) { it.copy(ink = listOf(listOf(5f to 5f, 6f to 6f))) }
        at(20_000) { it.copy(overview = true) }
        at(22_000) { it.copy(overview = false) }
        at(25_000) { it.copy(cursor = Cursor(2), ink = emptyList()) }
        at(26_000) { it.copy(blank = "B") }
        at(27_000) { it.copy(blank = null) }
        at(30_000) { it.copy(cursor = Cursor(1, 0)) }
        return v to 40_000L
    }

    @Test
    fun theLogReplaysToWhatThePresenterShowed() {
        val (views, _) = played()
        val log = TakeLog()
        views.forEach { (t, v) -> log.observe(t, v) }
        val events = log.events
        for ((t, v) in views) {
            val s = TakeTimeline.stateAt(events, t)
            assertEquals(v.cursor, s.cursor, "at $t")
            assertEquals(v.overview, s.overview, "at $t")
            assertEquals(v.blank, s.blank, "at $t")
            assertEquals(v.ink, s.ink, "at $t")
            if (t != 13_010L) assertEquals(v.laser, s.laser, "at $t")
        }
        // The point inside the limit was kept back, and the next one written.
        assertEquals(100f to 100f, TakeTimeline.stateAt(events, 13_010).laser)
        assertTrue(TakeTimeline.stateAt(events, 30_500).back, "going back plays backwards")
        // The pen: one event a point, not the whole drawing again each time.
        assertEquals(5, events.count { it.kind == TakeEvent.INK_START || it.kind == TakeEvent.INK })
    }

    @Test
    fun theFirstObservationIsWhereTheTakeBegins() {
        val log = TakeLog()
        log.observe(0, PresenterView(Cursor(4, 2), overview = true, laser = 3f to 4f))
        log.mark(9_000, "The combo")
        val s = TakeTimeline.stateAt(log.events, 0)
        assertEquals(Cursor(4, 2), s.cursor)
        assertTrue(s.overview)
        assertEquals(3f to 4f, s.laser)
        assertEquals("The combo", log.events.last().text)
    }

    @Test
    fun theWalkIsTheSameAsReplayingEachFrameAlone() {
        val (views, duration) = played()
        val log = TakeLog()
        views.forEach { (t, v) -> log.observe(t, v) }
        val take = Take("t", "p", durationMs = duration, events = log.events, fps = 30)
        val frames = RenderPlan.walk(take).toList()
        assertEquals(1_200, frames.size)
        assertEquals(1_200, RenderPlan.frames(take))
        for (f in frames.filter { it.n % 7 == 0 } + frames.last()) {
            assertEquals(RenderPlan.frame(take, f.n).state, f.state, "frame ${f.n}")
        }
        val arrived = frames.first { it.state.cursor == Cursor(1) }
        assertEquals(360, arrived.n, "slide 2 arrives on the frame at 12 s")
        assertEquals(0L, arrived.sinceMove)
        assertEquals(1_000L, frames[390].sinceMove)
        // Sixty frames a second is the same take, twice the frames.
        assertEquals(2_400, RenderPlan.walk(take, fps = 60).count())
    }

    @Test
    fun theCameraFrameIsTheNewestOneSoFar() {
        val stamps = listOf(5L, 38L, 71L, 104L)
        assertNull(RenderPlan.cameraFrame(stamps, 0))
        assertEquals(0, RenderPlan.cameraFrame(stamps, 5))
        assertEquals(1, RenderPlan.cameraFrame(stamps, 70))
        assertEquals(3, RenderPlan.cameraFrame(stamps, 10_000))
        assertTrue(RenderPlan.advanceCamera(33, 33))
        assertFalse(RenderPlan.advanceCamera(34, 33))
        assertFalse(RenderPlan.advanceCamera(null, 33))
    }

    @Test
    fun soundKeepsStepWithThePictures() {
        // 48 kHz at 30 fps is 1600 samples a frame, and the total never drifts.
        assertEquals(1_600L, RenderPlan.samplesThrough(0, 30, 48_000))
        assertEquals(48_000L, RenderPlan.samplesThrough(29, 30, 48_000))
        assertEquals(44_100L * 600, RenderPlan.samplesThrough(30 * 600 - 1, 30, 44_100))
        assertEquals(1_000L, RenderPlan.lengthMs(30, 30))
    }

    @Test
    fun takesNeverTravel() {
        assertTrue(TakePaths.syncs("p123.json"))
        assertTrue(TakePaths.syncs("media/abcd.png"))
        assertFalse(TakePaths.syncs("p123/takes/t1/camera.mkv"))
        assertFalse(TakePaths.syncs("p123/takes/t1/take.json"))
        assertFalse(TakePaths.syncs("p123\\takes\\t1\\audio.wav"))
        assertEquals("p1/takes/t9", TakePaths.folder("p1", "t9"))
        assertEquals("Deck profile take 2.mp4", TakePaths.fileName("Deck profile: take 2", "mp4"))
        assertEquals("Take.webm", TakePaths.fileName("///", "webm"))
    }

    @Test
    fun takeNamesAndSizes() {
        assertEquals("Take 1", TakeNames.next(emptyList()))
        assertEquals("Take 4", TakeNames.next(listOf("Take 1", "Take 3", "The good one")))
        assertEquals("2:05", TakeNames.length(125_000))
        assertEquals("1:00:00", TakeNames.length(3_600_000))
        assertEquals("61 MB", TakeNames.size(61_400_000))
        assertEquals("1.2 GB", TakeNames.size(1_234_000_000))
        assertEquals("1 KB", TakeNames.size(12))
    }

    @Test
    fun recordingSettingsHoldToWhatARenderMakes() {
        assertEquals(30, RecordPrefs(fps = 24).renderFps)
        assertEquals(60, RecordPrefs(fps = 60).renderFps)
        assertEquals(10, RecordPrefs(countdown = 99).countIn)
        assertEquals(0, RecordPrefs(countdown = -3).countIn)
    }

    @Test
    fun aTakeWrittenMidRecordingSaysSo() {
        val t = Take("t1", "p", "Take 1", events = listOf(TakeEvent(0, TakeEvent.GO)), finished = false, camera = Take.CAMERA, audio = Take.AUDIO)
        val back = TakeCodec.decode(TakeCodec.encode(t))!!
        assertFalse(back.finished)
        assertEquals(Take.CAMERA, back.camera)
        assertTrue(Chapters.of(Presentation("p", "x"), back.events, 0).isEmpty())
    }

    @Test
    fun directShowsCamerasInBothItsWords() {
        val now = """
            [dshow @ 000001e2] "Integrated Camera" (video)
            [dshow @ 000001e2]   Alternative name "@device_pnp_\\?\usb#vid_04f2"
            [dshow @ 000001e2] "OBS Virtual Camera" (video)
            [dshow @ 000001e2] "Microphone (Realtek(R) Audio)" (audio)
            [dshow @ 000001e2]   Alternative name "@device_cm_{33D9A762}"
            dummy: Immediate exit requested
        """.trimIndent()
        assertEquals(listOf("Integrated Camera", "OBS Virtual Camera"), CameraNames.dshow(now).map { it.name })
        assertEquals("video=Integrated Camera", CameraNames.dshow(now).first().input)
        assertEquals(listOf("Microphone (Realtek(R) Audio)"), CameraNames.dshow(now, audio = true).map { it.name })
        val before = """
            [dshow @ 0x1] DirectShow video devices (some may be both video and audio devices)
            [dshow @ 0x1]  "USB2.0 HD UVC WebCam"
            [dshow @ 0x1]     Alternative name "@device_pnp_x"
            [dshow @ 0x1] DirectShow audio devices
            [dshow @ 0x1]  "Microphone Array"
        """.trimIndent()
        assertEquals(listOf("USB2.0 HD UVC WebCam"), CameraNames.dshow(before).map { it.name })
        assertEquals(listOf("Microphone Array"), CameraNames.dshow(before, audio = true).map { it.name })
    }

    @Test
    fun aMacsCamerasLeaveItsScreensOut() {
        val log = """
            [AVFoundation indev @ 0x7f8] AVFoundation video devices:
            [AVFoundation indev @ 0x7f8] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x7f8] [1] kai's iPhone Camera
            [AVFoundation indev @ 0x7f8] [2] Capture screen 0
            [AVFoundation indev @ 0x7f8] AVFoundation audio devices:
            [AVFoundation indev @ 0x7f8] [0] MacBook Pro Microphone
        """.trimIndent()
        val cams = CameraNames.avfoundation(log)
        assertEquals(listOf("FaceTime HD Camera", "kai's iPhone Camera"), cams.map { it.name })
        assertEquals("1:none", cams[1].input)
    }

    @Test
    fun linuxNamesEachCameraOnce() {
        val cams = CameraNames.video4linux(mapOf("video1" to "Integrated Camera\n", "video0" to "Integrated Camera", "video10" to "Elgato Facecam"))
        assertEquals(listOf("Integrated Camera", "Elgato Facecam"), cams.map { it.name })
        assertEquals("/dev/video0", cams.first().input)
        assertEquals("Elgato Facecam", CameraNames.choose(cams, "Elgato Facecam")?.name)
        assertEquals("Integrated Camera", CameraNames.choose(cams, "Unplugged")?.name, "a camera gone falls back")
        assertNull(CameraNames.choose(emptyList(), null))
    }

    @Test
    fun wavHeadersReadBackAndACrashLosesNoSound() {
        val h = Wav.header(48_000, 1, 96_000)
        val info = Wav.read(h, Wav.HEADER + 96_000L)!!
        assertEquals(48_000, info.rate)
        assertEquals(1, info.channels)
        assertEquals(48_000L, info.frames)
        assertEquals(1_000L, info.durationMs)
        // A take cut short: the header says nothing, the file holds two seconds.
        val cut = Wav.read(Wav.header(48_000, 1, 0), Wav.HEADER + 192_001L)!!
        assertEquals(96_000L, cut.frames, "the odd byte is left off")
        assertNull(Wav.read(ByteArray(44), 44))
    }

    @Test
    fun theCameraFillsItsZoneCroppedNeverStretched() {
        // A 16:9 camera in a square zone (a circle): the middle square.
        assertEquals(listOf(280, 0, 720, 720), com.kaiharimoto.mastertool.core.present.record.CameraFit.cover(1280, 720, 300f, 300f).toList())
        // In a tall column: a narrow middle strip.
        val col = com.kaiharimoto.mastertool.core.present.record.CameraFit.cover(1280, 720, 400f, 1000f).toList()
        assertEquals(288, col[2])
        assertEquals((1280 - 288) / 2, col[0])
        // A 4:3 camera in a 16:9 zone: the top and bottom go.
        assertEquals(listOf(0, 60, 640, 360), com.kaiharimoto.mastertool.core.present.record.CameraFit.cover(640, 480, 1600f, 900f).toList())
        // The same shape: the whole picture.
        assertEquals(listOf(0, 0, 1280, 720), com.kaiharimoto.mastertool.core.present.record.CameraFit.cover(1280, 720, 512f, 288f).toList())
    }

    @Test
    fun theSyntheticCameraCountsItsFrames() {
        val f = SyntheticCamera.frame(5, width = 160, height = 90)
        assertEquals(160 * 90, f.size)
        val cell = 160 / 16
        // 5 is 00000101: the sixth and eighth cells lit.
        fun lit(i: Int) = (f[i * cell + cell / 2] and 0xFF) > 0x80
        assertEquals(listOf(false, false, false, false, false, true, false, true), (0 until 8).map(::lit))
        val bgra = SyntheticCamera.bgra(intArrayOf(0x11223344))
        assertEquals(listOf(0x44, 0x33, 0x22, 0x11), bgra.map { it.toInt() and 0xFF })
    }
}
