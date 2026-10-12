package com.kaiharimoto.mastertool.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What of this computer is taken out of the diagnostics file (Phase H, H.8; the red team, 2026-10). */
class PathScrubTest {
    private val mac = PathScrub(
        listOf(
            "/Users/Kai Harimoto/Library/Application Support/Neue Master Tool" to "<data>",
            "/var/folders/xy/abc123/T/" to "<temp>",
            "/Users/Kai Harimoto" to "<home>",
            "/" to "<work>",
        ),
    )

    @Test
    fun eachFolderReadsAsItsPlaceholderTheNearestFirst() {
        val log = "started · a native crash's report goes to /, else /var/folders/xy/abc123/T/\n" +
            "failed · at /Users/Kai Harimoto/Library/Application Support/Neue Master Tool/logs/neue.log\n" +
            "the voice model in /Users/kai harimoto/Downloads/ggml.bin"
        val out = mac.scrub(log)
        assertFalse("Kai" in out || "kai" in out || "abc123" in out, out)
        assertTrue("<data>/logs/neue.log" in out, out)
        assertTrue("<home>/Downloads/ggml.bin" in out, "a folder's name in another case is the same folder: $out")
        assertTrue("else <temp>" in out, out)
        // The root says nothing of anyone, and replacing it would take every path apart.
        assertTrue("goes to /, else" in out, out)
    }

    @Test
    fun windowsFoldersReadEitherWayRoundAndAsJsonEscapesThem() {
        val win = PathScrub(listOf("C:\\Users\\kai\\AppData\\Roaming\\Neue Master Tool" to "<data>", "C:\\Users\\kai" to "<home>", "C:\\" to "<work>"))
        val text = "C:\\Users\\kai\\Desktop\\deck.ydk · C:/Users/kai/AppData/Roaming/Neue Master Tool/crash.txt · " +
            """{"failure":"C:\\Users\\kai\\AppData\\Local\\x"} · C:\Program Files\Neue"""
        val out = win.scrub(text)
        assertFalse("kai" in out, out)
        assertTrue("<home>\\Desktop\\deck.ydk" in out, out)
        assertTrue("<data>/crash.txt" in out, out)
        assertTrue("""{"failure":"<home>\\AppData\\Local\\x"}""" in out, out)
        assertTrue("C:\\Program Files\\Neue" in out, "C:\\ alone is left: $out")
        assertEquals(emptyList(), PathScrub.forms("C:\\"))
        assertEquals(emptyList(), PathScrub.forms("/tmp"))
    }

    @Test
    fun anAddressKeepsOnlyItsSite() {
        assertEquals(
            "could not open https://metafy.gg/… (it asked to log in); see https://example.com",
            PathScrub.addresses("could not open https://metafy.gg/@kai/labrynth-masterclass/chapter-3?x=1 (it asked to log in); see https://example.com"),
        )
        assertTrue("labrynth" !in mac.scrub("Reading https://www.duelingbook.com/replay?id=labrynth-123"))
    }
}
