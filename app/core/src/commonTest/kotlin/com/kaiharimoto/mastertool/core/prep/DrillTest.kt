package com.kaiharimoto.mastertool.core.prep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrillTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun anExactAnswerIsPerfectInAnyOrder() {
        val s = Drill.score(listOf(1, 1, 2), listOf(3, 4), listOf(2, 1, 1), listOf(4, 3))
        assertTrue(s.perfect)
        assertEquals(1.0, s.fraction)
        assertEquals(Drill.Score(3, 0, 0, 2, 0, 0), s)
    }

    @Test
    fun copiesAreCountedOneByOne() {
        // The plan takes two copies of 1 out; one picked is one right and one missed.
        val one = Drill.score(listOf(1, 1), emptyList(), listOf(1), emptyList())
        assertEquals(Drill.Score(1, 0, 1, 0, 0, 0), one)
        // Three picked is two right and one wrong.
        val three = Drill.score(listOf(1, 1), emptyList(), listOf(1, 1, 1), emptyList())
        assertEquals(Drill.Score(2, 1, 0, 0, 0, 0), three)
        // In apart from out: the right card in, the wrong one out.
        val mixed = Drill.score(listOf(1), listOf(9), listOf(2), listOf(9))
        assertEquals(Drill.Score(0, 1, 1, 1, 0, 0), mixed)
        assertFalse(mixed.perfect)
        assertEquals(1.0 / 3, mixed.fraction)
        // Nothing to side and nothing sided is right.
        assertTrue(Drill.score(emptyList(), emptyList(), emptyList(), emptyList()).perfect)
    }

    @Test
    fun theBoxesClimbAndFall() {
        val perfect = Drill.Score(2, 0, 0, 2, 0, 0)
        val near = Drill.Score(3, 0, 0, 3, 1, 0) // 6 of 7
        val bad = Drill.Score(0, 2, 2, 0, 2, 2)
        var s = DrillStat()
        repeat(6) { s = Drill.update(s, perfect, it.toLong()) }
        assertEquals(DrillStat(seen = 6, correct = 6, lastAt = 5, box = Drill.TOP_BOX), s)
        s = Drill.update(s, near, 10)
        assertEquals(3, s.box)
        assertEquals(6, s.correct)
        s = Drill.update(s, bad, 20)
        assertEquals(DrillStat(seen = 8, correct = 6, lastAt = 20, box = 0), s)
        assertEquals(0, Drill.update(DrillStat(), near, 0).box)
    }

    @Test
    fun theNextDrillIsTheLeastKnownAndTheLongestAgo() {
        val now = 100 * day
        assertNull(Drill.next(emptyList(), emptyMap(), now))
        val a = Drill.key("m1", "FIRST")
        val b = Drill.key("m1", "SECOND")
        val c = Drill.key("m2", "FIRST")
        assertEquals("m1:FIRST", a)
        // Never asked comes first.
        assertEquals(c, Drill.next(listOf(a, b, c), mapOf(a to DrillStat(1, 1, now - 5 * day, 2), b to DrillStat(1, 0, now - day, 0)), now))
        // Lowest box, then the oldest.
        val stats = mapOf(
            a to DrillStat(3, 3, now - 10 * day, 3),
            b to DrillStat(2, 1, now - 2 * day, 1),
            c to DrillStat(2, 1, now - 3 * day, 1),
        )
        assertEquals(c, Drill.next(listOf(a, b, c), stats, now))
        // One that is due goes before one that is not, whatever its box.
        val notDue = mapOf(
            a to DrillStat(3, 3, now - 8 * day, 4), // a week in box 4: due
            b to DrillStat(2, 1, now - 1, 1), // an hour in box 1: not yet
        )
        assertEquals(a, Drill.next(listOf(a, b), notDue, now))
        // Nothing due: still the best of the rest.
        val nothingDue = mapOf(a to DrillStat(3, 3, now - 1, 4), b to DrillStat(2, 1, now - 2, 2))
        assertEquals(b, Drill.next(listOf(a, b), nothingDue, now))
        // The one just answered wrong waits while another is due.
        val justWrong = mapOf(a to DrillStat(1, 0, now, 0), b to DrillStat(2, 2, now - 2 * day, 1))
        assertEquals(b, Drill.next(listOf(a, b), justWrong, now))
        // …but alone, it is asked again.
        assertEquals(a, Drill.next(listOf(a), justWrong, now))
    }
}
