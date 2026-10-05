package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.present.record.Chapters
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeCodec
import com.kaiharimoto.mastertool.core.present.record.TakeEvent
import com.kaiharimoto.mastertool.core.present.record.TakeTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A take (1.0.72): what happened replays to the same stage at any moment; chapters follow YouTube's rules. */
class TakeTest {
    private val events = listOf(
        TakeEvent(4_000, TakeEvent.GO, slide = 0, step = 1),
        TakeEvent(15_000, TakeEvent.GO, slide = 1),
        TakeEvent(16_000, TakeEvent.LASER, x = 100f, y = 200f),
        TakeEvent(17_000, TakeEvent.LASER_OFF),
        TakeEvent(20_000, TakeEvent.INK_START, x = 1f, y = 1f),
        TakeEvent(20_100, TakeEvent.INK, x = 2f, y = 2f),
        TakeEvent(30_000, TakeEvent.OVERVIEW),
        TakeEvent(33_000, TakeEvent.OVERVIEW_OFF),
        TakeEvent(40_000, TakeEvent.GO, slide = 2),
        TakeEvent(41_000, TakeEvent.BLANK, text = "B"),
        TakeEvent(42_000, TakeEvent.BLANK, text = ""),
        TakeEvent(55_000, TakeEvent.GO, slide = 1),
    )

    @Test
    fun anyMomentReplaysAlone() {
        val start = TakeTimeline.stateAt(events, 0)
        assertEquals(Cursor(0), start.cursor)
        assertNull(start.from)

        val build = TakeTimeline.stateAt(events, 5_000)
        assertEquals(Cursor(0, 1), build.cursor)
        assertEquals(Cursor(0), build.from)
        assertEquals(4_000, build.since)

        assertEquals(100f to 200f, TakeTimeline.stateAt(events, 16_500).laser)
        assertNull(TakeTimeline.stateAt(events, 17_500).laser)
        assertEquals(listOf(listOf(1f to 1f, 2f to 2f)), TakeTimeline.stateAt(events, 25_000).ink)
        assertTrue(TakeTimeline.stateAt(events, 31_000).overview)
        assertEquals(30_000, TakeTimeline.stateAt(events, 31_000).overviewSince)

        val third = TakeTimeline.stateAt(events, 41_500)
        assertEquals(2, third.cursor.slide)
        assertEquals("B", third.blank)
        assertTrue(third.ink.isEmpty(), "the pen's lines go with their slide")
        assertNull(TakeTimeline.stateAt(events, 43_000).blank)

        val back = TakeTimeline.stateAt(events, 56_000)
        assertTrue(back.back, "going back runs the transition backwards")
    }

    @Test
    fun framesAndTheirMoments() {
        assertEquals(30, TakeTimeline.frames(1_000, 30))
        assertEquals(31, TakeTimeline.frames(1_001, 30))
        assertEquals(1_000L, TakeTimeline.msOf(30, 30))
        assertEquals(listOf(0L to 0, 15_000L to 1, 40_000L to 2, 55_000L to 1), TakeTimeline.slides(events))
    }

    @Test
    fun chaptersFollowYouTubesRules() {
        val p = Presentation(
            "p", "Test", slides = listOf(
                Slide("a", title = "Intro to the deck"),
                Slide("b", title = "The engine"),
                Slide("c", title = "Quick aside"),
                Slide("d", title = "Siding", section = "Siding"),
            ),
        )
        val ev = listOf(
            TakeEvent(15_000, TakeEvent.GO, slide = 1),
            TakeEvent(40_000, TakeEvent.GO, slide = 2),
            // On screen four seconds: too short for a chapter of its own.
            TakeEvent(44_000, TakeEvent.GO, slide = 3),
        )
        val chapters = Chapters.of(p, ev, 90_000)
        assertEquals(listOf(0L, 15_000L, 44_000L), chapters.map { it.atMs })
        assertEquals("Intro to the deck", chapters.first().title)
        assertEquals("0:00 Intro to the deck\n0:15 The engine\n0:44 Siding", Chapters.text(chapters))
        // Two chapters are not enough for YouTube: none.
        assertTrue(Chapters.of(p, ev.take(1), 30_000).isEmpty())
        assertEquals("1:02:05", Chapters.stamp(3_725_000, long = true))
    }

    @Test
    fun theEncoderIsTheMachinesOwnFirstAndAlwaysSomething() {
        assertEquals("h264_videotoolbox", EncoderPick.pick(EncoderPick.MAC) { true })
        // No machine H.264: VP9, never the bundled OpenH264 (no patent licence for a build from source).
        assertEquals(EncoderPick.VP9, EncoderPick.pick(EncoderPick.WINDOWS) { it == EncoderPick.VP9 || it == "libopenh264" || it == "mpeg4" })
        for (os in listOf(EncoderPick.MAC, EncoderPick.WINDOWS, EncoderPick.LINUX)) {
            val order = EncoderPick.order(os)
            assertEquals(EncoderPick.VP9, order.last(), "$os falls back to VP9")
            assertTrue(order.none { it in EncoderPick.NEVER || it == "mpeg4" }, "$os asks for $order")
        }
        assertEquals("mp4", EncoderPick.container("h264_mf"))
        assertEquals("aac", EncoderPick.audioCodec("h264_videotoolbox"))
        assertEquals("mp4", EncoderPick.container(EncoderPick.VP9))
        assertEquals("libopus", EncoderPick.audioCodec(EncoderPick.VP9))
        assertEquals("realtime", EncoderPick.options(EncoderPick.VP9)["deadline"])
        assertEquals(EncoderPick.MAC, EncoderPick.osOf("Mac OS X"))
        assertEquals(EncoderPick.WINDOWS, EncoderPick.osOf("Windows 11"))
        assertEquals(EncoderPick.LINUX, EncoderPick.osOf("Linux"))
        assertEquals(8_087_040, EncoderPick.bitrate(1920, 1080, 30))
    }

    @Test
    fun aTakeReadsBackAndSkipsWhatItDoesNotKnow() {
        val t = Take("t1", "p1", "Take 1", 5L, 60_000, events, camera = "camera.mp4", audio = "audio.wav", cameraOffsetMs = 120)
        assertEquals(t, TakeCodec.decode(TakeCodec.encode(t)))
        val newer = """{"id":"t2","presentationId":"p","events":[{"at":1,"kind":"ZOOM_FROM_THE_FUTURE"}],"hologram":true}"""
        val read = TakeCodec.decode(newer)!!
        assertEquals("ZOOM_FROM_THE_FUTURE", read.events.single().kind)
        assertEquals(Cursor(0), TakeTimeline.stateAt(read.events, 10).cursor)
    }
}
