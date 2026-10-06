package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessyAmieTest {
    @Test
    fun aTouchLandsWhereSheIsDrawn() {
        assertEquals(AmieZone.EAR_L, AmieZones.at(220f, 200f))
        assertEquals(AmieZone.EAR_R, AmieZones.at(1050f, 180f))
        assertEquals(AmieZone.HEAD, AmieZones.at(640f, 450f))
        assertEquals(AmieZone.BELL, AmieZones.at(630f, 1470f))
        assertEquals(AmieZone.CHIN, AmieZones.at(630f, 1300f))
        assertEquals(AmieZone.CHEEK_L, AmieZones.at(400f, 1100f))
        assertEquals(AmieZone.CHEEK_R, AmieZones.at(860f, 1100f))
        assertEquals(AmieZone.FACE, AmieZones.at(630f, 960f))
        assertEquals(AmieZone.NONE, AmieZones.at(5f, 1700f))
    }

    @Test
    fun theFitGoesBothWays() {
        for (head in listOf(true, false)) {
            val f = ChessyFit.of(400f, 300f, head)
            val (sx, sy) = ChessyFit.toSheet(400f, 300f, head, f[1] + 630f * f[0], f[2] + 1100f * f[0])
            assertTrue(abs(sx - 630f) < .01f && abs(sy - 1100f) < .01f)
        }
    }

    @Test
    fun eachPlaceHasItsAnswer() {
        val a = ChessyAmie()
        assertEquals(Expression.WINK, a.tap(AmieZone.BELL, 1.0)!!.mood)
        assertTrue(a.tap(AmieZone.BELL, 3.0)!!.ring)
        assertEquals(-1, a.tap(AmieZone.EAR_L, 5.0)!!.ear)
        assertEquals(1, a.tap(AmieZone.EAR_R, 7.0)!!.ear)
        assertNull(a.tap(AmieZone.NONE, 9.0))
    }

    @Test
    fun aStrokeBackAndForthIsPettingAndItWarmsHer() {
        val a = ChessyAmie()
        var t = 0.0
        var answers = 0
        var last: AmieReaction? = null
        repeat(400) { i ->
            t += .05
            val r = a.stroke(AmieZone.HEAD, if (i % 4 < 2) 18f else -18f, 0f, t)
            if (r != null) { answers++; last = r }
        }
        assertTrue(answers >= 4, "petting is answered, now and then: $answers")
        assertTrue(answers <= (t / ChessyAmie.ANSWER_EVERY).toInt() + 1, "never chattering")
        assertTrue(a.fondness >= ChessyAmie.FOND)
        assertEquals(Expression.LOVE, last!!.mood)
        assertTrue(last!!.hearts > 0)
        // a chin stroked is tickled
        val chin = ChessyAmie()
        var tickled: AmieReaction? = null
        t = 0.0
        repeat(80) { i -> t += .05; chin.stroke(AmieZone.CHIN, if (i % 4 < 2) 20f else -20f, 0f, t)?.let { tickled = it } }
        assertNotNull(tickled)
        assertTrue(tickled!!.line in ChessyAmie.LINES.getValue("tickle"))
    }

    @Test
    fun pokingTooFastMakesHerSulkUntilPetted() {
        val a = ChessyAmie()
        var r: AmieReaction? = null
        for (i in 0 until ChessyAmie.POKES) r = a.tap(AmieZone.FACE, 1.0 + i * .2)
        assertEquals(Expression.ANGRY, r!!.mood)
        assertEquals(Expression.SAD, a.tap(AmieZone.FACE, 5.0)!!.mood)
        var made: AmieReaction? = null
        var t = 6.0
        repeat(60) { i -> t += .05; a.stroke(AmieZone.HEAD, if (i % 4 < 2) 20f else -20f, 0f, t)?.let { if (made == null) made = it } }
        assertTrue(made!!.line in ChessyAmie.LINES.getValue("forgive"))
    }

    @Test
    fun linesNeverRepeatBackToBackAndAllWearKaomoji() {
        val a = ChessyAmie(seed = 3)
        var prev = ""
        var t = 0.0
        repeat(30) {
            t += 3.0
            val line = a.tap(AmieZone.BELL, t)!!.line
            assertNotEquals(prev, line)
            prev = line
        }
        for ((kind, lines) in ChessyAmie.LINES) for (l in lines) assertTrue(l.any { it.code > 0x2000 || it in "()~♪♡" }, "$kind: $l carries a kaomoji")
    }

    @Test
    fun leftAloneSheWondersThenDozes() {
        val a = ChessyAmie()
        a.greet(0.0)
        assertNull(a.idle(2.0))
        assertEquals(Expression.WAITING, a.idle(ChessyAmie.LONELY + .1)!!.mood)
        assertNull(a.idle(ChessyAmie.LONELY + 1))
        assertEquals(Expression.SLEEPING, a.idle(ChessyAmie.SLEEPY + .1)!!.mood)
        // a touch wakes her and the cycle starts again
        a.tap(AmieZone.FACE, 40.0)
        assertEquals(Expression.WAITING, a.idle(40.0 + ChessyAmie.LONELY + .1)!!.mood)
    }

    @Test
    fun particlesRiseFadeAndAreBounded() {
        val p = AmieParticles()
        p.burst(100f, 100f, hearts = 3, sparkles = 2, unit = 20f)
        assertEquals(5, p.live.size)
        assertEquals(3, p.live.count { it.heart })
        val y0 = p.live.map { it.y }.average()
        repeat(10) { p.step(.05f) }
        assertTrue(p.live.map { it.y }.average() < y0, "they rise")
        repeat(100) { p.step(.05f) }
        assertTrue(p.live.isEmpty(), "and are gone after their life")
        repeat(40) { p.burst(0f, 0f, 3, 0, 10f) }
        assertEquals(AmieParticles.MAX, p.live.size)
    }
}
