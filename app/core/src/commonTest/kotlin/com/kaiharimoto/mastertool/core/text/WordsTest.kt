package com.kaiharimoto.mastertool.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One formatter each (the 1.1.2 design review, finding 12). */
class WordsTest {
    @Test
    fun percentIsWholeAndUnspaced() {
        assertEquals("58%", Words.percent(0.577))
        assertEquals("85%", Words.percent(0.85))
        assertEquals("<1%", Words.percent(0.004))
        assertEquals(">99%", Words.percent(0.996))
        assertEquals("0%", Words.percent(0.0))
        assertEquals("100%", Words.percent(1.0))
        assertEquals("--", Words.percent(Double.NaN))
    }

    @Test
    fun aHintIsProseOnlyWhenItReadsAsWords() {
        assertTrue(Words.isProse("100 unless the event sets another"))
        assertTrue(Words.isProse("At the limit"))
        assertTrue(Words.isProse("Hands judged, cards rated"))
        assertFalse(Words.isProse("Ctrl Shift F"))
        assertFalse(Words.isProse("Enter · Shift Enter side"))
        assertFalse(Words.isProse("yyyy-mm-dd"))
        assertFalse(Words.isProse("1 May 2025"))
        assertFalse(Words.isProse("Redo 2"))
    }

    @Test
    fun aiIsCalledByItsName() {
        assertEquals("How far Kiri is trusted on this matchup", Words.named("How far Ai is trusted on this matchup", "Kiri"))
        assertEquals("Open Ai World", Words.named("Open Ai World", "Kiri"))
        assertEquals("Said nothing", Words.named("Said nothing", "Kiri"))
        assertEquals("Paint the wall", Words.named("Paint the wall", "Kiri"))
        assertEquals("How far Ai is trusted", Words.named("How far Ai is trusted", " "))
    }

    @Test
    fun daysReadAndAreTypedEitherWay() {
        assertEquals("1 May 2025", Dates.day("2025-05-01"))
        assertEquals("today", Dates.day("today"))
        assertEquals("2025-05-01", Dates.parseDay("2025-05-01"))
        assertEquals("2025-05-01", Dates.parseDay("20250501"))
        assertEquals("2025-05-01", Dates.parseDay("2025 5 1"))
        assertEquals("2025-05-01", Dates.parseDay("2025/05/01"))
        assertEquals("2025-05-01", Dates.parseDay("2025.5.1"))
        assertNull(Dates.parseDay("2025-02-31"))
        assertNull(Dates.parseDay("2025-13-01"))
        assertNull(Dates.parseDay("2025-05"))
        assertNull(Dates.parseDay(""))
    }

    @Test
    fun aMomentIsDayMonthAndClock() {
        // 2026-10-03T23:46Z
        val at = 1_791_071_160_000L
        assertEquals("3 Oct, 23:46", Dates.short(at))
        assertEquals("4 Oct, 01:46", Dates.short(at, offsetMinutes = 120))
        assertEquals("3 Oct, 23:46", Dates.short(at, nowMillis = at + 86_400_000L))
        assertEquals("3 Oct 2026, 23:46", Dates.short(at, nowMillis = at + 400L * 86_400_000L))
    }
}
