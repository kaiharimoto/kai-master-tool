package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChessyRigTest {
    private val sphere = Sphere(629.5f, 942.5f, 613.5f, 613.5f)

    @Test
    fun aRigAtRestPutsEveryPointWhereItIs() {
        val f = ChessyFrame()
        val out = FloatArray(2)
        val p = LayerPose()
        for ((x, y) in listOf(100f to 200f, 629f to 900f, 1200f to 1600f)) {
            ChessyWarp.place(x, y, p, f, sphere, 629f, 1525f, out, 0)
            assertEquals(x, out[0], .001f)
            assertEquals(y, out[1], .001f)
        }
        // facing front, her face is lit as painted, within the light's few per cent
        assertEquals(1f, ChessyWarp.shade(629f, 900f, f, sphere), .05f)
    }

    @Test
    fun turningMovesTheMiddleMoreThanTheRimAndARigidPieceAsOne() {
        val f = ChessyFrame().apply { yaw = .3f }
        val out = FloatArray(2)
        val p = LayerPose()
        ChessyWarp.place(629f, 942f, p, f, sphere, 629f, 1525f, out, 0)
        val middle = out[0] - 629f
        ChessyWarp.place(60f, 942f, p, f, sphere, 629f, 1525f, out, 0)
        val rim = out[0] - 60f
        assertTrue(middle > 100f && middle > rim, "middle $middle rim $rim")
        val rigid = LayerPose().apply { this.rigid = true; ax = 629f; ay = 1465f }
        ChessyWarp.place(500f, 1400f, rigid, f, sphere, 629f, 1525f, out, 0)
        val a = out[0] - 500f
        ChessyWarp.place(760f, 1600f, rigid, f, sphere, 629f, 1525f, out, 0)
        assertEquals(a, out[0] - 760f, .01f)
    }

    @Test
    fun hairSwingsFromItsTipsNotItsRoots() {
        val f = ChessyFrame()
        val p = LayerPose().apply { swingX = 30f; rootStart = 400f; rootLen = 600f }
        val out = FloatArray(2)
        ChessyWarp.place(300f, 380f, p, f, sphere, 629f, 1525f, out, 0)
        assertEquals(300f, out[0], .001f)
        ChessyWarp.place(300f, 1000f, p, f, sphere, 629f, 1525f, out, 0)
        assertEquals(330f, out[0], .001f)
    }

    @Test
    fun lookingAsideTurnsHerHeadAndTheHairLagsThenSettles() {
        val rig = ChessyRig(seed = 3)
        repeat(30) { rig.step(16f, 1f, 0f, talking = false, blinks = false) }
        val f = rig.frame
        assertTrue(f.yaw > .1f && f.roll < 0f, "yaw ${f.yaw} roll ${f.roll}")
        assertTrue(abs(f.swingX[SwingGroup.SIDE.ordinal]) > 1f, "the side locks swing while she turns")
        repeat(600) { rig.step(16f, 1f, 0f, talking = false, blinks = false) }
        assertEquals(ChessyRig.YAW, f.yaw, .01f)
        for (g in SwingGroup.entries) {
            assertTrue(abs(f.swingX[g.ordinal]) <= g.maxX && abs(f.swingY[g.ordinal]) <= g.maxY + .0001f)
        }
    }

    @Test
    fun aStillRigNeverMovesButStillBlinksAndTalks() {
        val rig = ChessyRig(seed = 3, still = true)
        var blinked = false
        val mouths = mutableSetOf<ChessyMouth>()
        repeat(800) {
            val f = rig.step(16f, 1f, -1f, talking = true)
            assertEquals(0f, f.yaw); assertEquals(0f, f.bob); assertEquals(0f, f.earL)
            SwingGroup.entries.forEach { g -> assertEquals(0f, f.swingX[g.ordinal]) }
            blinked = blinked || f.blink
            mouths += f.mouth
        }
        assertTrue(blinked)
        assertEquals(setOf(ChessyMouth.OPEN, ChessyMouth.CLOSED), mouths)
    }

    @Test
    fun blinksLast130MsAndComeEveryFewSeconds() {
        val rig = ChessyRig(seed = 5)
        var run = 0
        val runs = mutableListOf<Int>()
        repeat(2000) { if (rig.step(10f, 0f, 0f, false).blink) run += 10 else if (run > 0) { runs += run; run = 0 } }
        assertTrue(runs.size in 3..12, "blinks in 20 s: ${runs.size}")
        assertTrue(runs.all { it in 120..140 }, "$runs")
    }

    @Test
    fun theSpeechRhythmIsTheMockupsOwn() {
        // the mockup's generator, seed 11: its first numbers
        val r = Speech.mulberry32(11)
        assertEquals(listOf(0.5115, 0.5299, 0.6081), List(3) { (r() * 10000).toLong() / 10000.0 }, "first draws (node: 0.51158…, 0.52994…, 0.60811…)")
        assertTrue(Speech.length in 12000f..13500f)
        assertTrue(Speech.at(0f).stress)
    }
}
