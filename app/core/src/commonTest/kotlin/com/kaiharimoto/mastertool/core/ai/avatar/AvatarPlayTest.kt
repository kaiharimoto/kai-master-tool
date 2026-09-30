package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarPlayTest {
    @Test
    fun tapsAreAnsweredInTurnAndPokingAnnoysIt() {
        val play = AvatarPlay()
        val first = play.tap(0.0, asleep = false)
        val second = play.tap(3.0, asleep = false)
        assertNotEquals(first.face, second.face, "never the same answer twice running")
        val pokes = (0 until AvatarPlay.POKES).map { play.tap(10.0 + it * 0.2, asleep = false) }
        assertEquals(Expression.ANGRY, pokes.last().face)
        assertEquals(Expression.SHY, play.tap(12.0, asleep = false).face, "a gentle tap after makes up")
    }

    @Test
    fun aTapWakesItAndADoubleTapIsLove() {
        val play = AvatarPlay()
        assertEquals(Expression.WAKING, play.tap(0.0, asleep = true).face)
        assertEquals(Expression.LOVE, play.doubleTap(1.0).face)
        assertEquals(Expression.SURPRISED, play.hold(longer = false).face)
        assertEquals(Expression.SHY, play.hold(longer = true).face)
    }

    @Test
    fun strokingBackAndForthIsPettingAndItWarmsUp() {
        val play = AvatarPlay()
        assertNull(play.stroke(40f, 0.0, asleep = false), "one way is not petting")
        // Four seconds of petting, a stroke every twentieth of a second.
        fun pet(from: Double, play: AvatarPlay, asleep: Boolean = false) = (0 until 80).mapNotNull { i ->
            play.stroke(if (i % 2 == 0) 30f else -30f, from + i * 0.05, asleep)
        }
        val faces = pet(1.0, play).map { it.face }
        assertEquals(listOf(Expression.SHY, Expression.DELIGHTED, Expression.LOVE), faces.take(3), "$faces")
        assertTrue(faces.size <= 4, "answered now and then, not at every stroke: $faces")
        // A pause starts over.
        assertEquals(Expression.SHY, pet(20.0, play).first().face)
        assertEquals(Expression.SLEEPING, pet(0.0, AvatarPlay(), asleep = true).first().face, "petted asleep, it stays asleep")
    }

    @Test
    fun restingOnItAWhileMakesItShy() {
        val play = AvatarPlay()
        assertNull(play.dwell(1.0))
        assertEquals(Expression.SHY, play.dwell(AvatarPlay.DWELL)?.face)
        assertTrue(AvatarPlay.TAPS.map { it.line }.none { it.endsWith("!") }, "Master UI: no exclamations")
    }
}
