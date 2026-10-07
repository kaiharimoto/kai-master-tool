package com.kaiharimoto.mastertool.core.ai.course

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoChapterTest {
    @Test
    fun captionsAreReadIntoTimedLinesWithoutTagsOrNumbers() {
        val vtt = """WEBVTT

1
00:00:01.000 --> 00:00:03.500 align:start position:0%
<c.yellow>Welcome back.</c> Today: going second.

2
00:01:05.250 --> 00:01:08.000
Ash Blossom &amp; Joyous Spring
first, always.
"""
        val cues = CaptionCues.parse(vtt)
        assertEquals(listOf(1_000L, 65_250L), cues.map { it.atMs })
        assertEquals("Welcome back. Today: going second.", cues[0].text)
        assertEquals("Ash Blossom & Joyous Spring first, always.", cues[1].text)
        // SRT's comma, and an hour.
        assertEquals(3_723_400L, CaptionCues.ms("01:02:03,400"))
    }

    @Test
    fun rolledUpCaptionsAreJoinedIntoLinesThatKeepTheirTime() {
        val pieces = listOf(
            Transcript.Line(0, "Going second you"),
            Transcript.Line(1_000, "Going second you want"),
            Transcript.Line(1_500, "Going second you want"),
            Transcript.Line(2_000, "to keep Nibiru."),
        )
        val t = Transcript.of(pieces, target = 400)
        assertEquals(1, t.lines.size)
        assertEquals("Going second you want to keep Nibiru.", t.lines.single().text)
        assertTrue(t.render("Going second").startsWith("# Going second\n\n[0:00] Going second"))
        assertEquals("1:01:05", Transcript.clock(3_665_000))
        assertEquals("2:05", Transcript.clock(125_000))
    }

    @Test
    fun aFrameIsKeptForANewSceneAndNeverTooOften() {
        val dark = ByteArray(64) { 10 }
        val light = ByteArray(64) { 200.toByte() }
        val frames = listOf(0L to dark, 5_000L to light, 25_000L to light, 30_000L to dark, 60_000L to dark, 90_000L to light, 120_000L to light)
        // The light frame at 5 s is too soon; at 25 s it is a new scene; the same scene again is not kept.
        assertEquals(listOf(0, 2, 4, 5), KeyFrames.pick(frames))
        assertEquals(0.0, KeyFrames.difference(dark, dark))
        assertEquals(2, KeyFrames.pick(frames, max = 2).size)
    }
}
