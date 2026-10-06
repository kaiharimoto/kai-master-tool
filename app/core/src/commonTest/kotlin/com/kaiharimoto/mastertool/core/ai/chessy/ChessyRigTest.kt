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
        var swung = 0f
        repeat(30) { rig.step(16f, 1f, 0f, talking = false, blinks = false); swung = maxOf(swung, abs(rig.frame.swingX[SwingGroup.SIDE.ordinal])) }
        val f = rig.frame
        assertTrue(f.yaw > .1f && f.roll < 0f, "yaw ${f.yaw} roll ${f.roll}")
        assertTrue(swung > 1f, "the side locks swing while she turns: $swung")
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
    fun blinksSnapShutOpenSlowlyAndComeAtPeoplesRate() {
        val rig = ChessyRig(seed = 5)
        var run = 0
        val runs = mutableListOf<Int>()
        var opening = 0
        // a minute, blinks timed in 10 ms steps
        repeat(6000) {
            val f = rig.step(10f, 0f, 0f, false)
            if (f.blink) {
                run += 10
                if (f.lidAlpha in .01f..0.99f) opening++
                // shut at once: the first step of a blink shows the lid whole
                if (run == 10) assertEquals(1f, f.lidAlpha, .001f)
            } else if (run > 0) { runs += run; run = 0 }
        }
        // people blink some seventeen times a minute at rest
        assertTrue(runs.size in 9..28, "blinks in a minute: ${runs.size}")
        assertTrue(runs.all { it in 110..160 }, "$runs")
        assertTrue(opening > 0, "the lid opens over a few steps")
    }

    @Test
    fun blinkGapsAreUnevenAndTalkingBlinksMore() {
        fun gaps(talking: Boolean, rate: Float = 1f): List<Float> {
            val rig = ChessyRig(seed = 7)
            val out = mutableListOf<Float>()
            var was = false
            var since = 0f
            repeat(600_000 / 20) {
                val f = rig.step(20f, 0f, 0f, talking, blinkRate = rate)
                if (f.blink && !was) { out += since; since = 0f }
                since += 20f
                was = f.blink
            }
            return out.drop(1)
        }
        val rest = gaps(false)
        val talk = gaps(true)
        val reading = gaps(false, rate = .55f)
        val sorted = rest.sorted()
        // never a metronome: the longest gap is several times the shortest (doubles aside, the bounds hold)
        assertTrue(sorted.last() > sorted[sorted.size / 10] * 3f, "gaps $sorted")
        // a gap is counted blink start to blink start, so its blink's own length rides on the bound
        assertTrue(rest.all { it <= ChessyRig.GAP_MAX + ChessyRig.BLINK_MAX + 40f }, "gaps ${rest.max()}")
        assertTrue(talk.size > rest.size * 1.2f, "talking ${talk.size} against ${rest.size} in ten minutes")
        assertTrue(reading.size < rest.size * .8f, "reading ${reading.size} against ${rest.size}")
    }

    @Test
    fun aLookThatJumpsUsuallyBlinksAndADriftNever() {
        var blinked = 0
        for (seed in 1..40) {
            val rig = ChessyRig(seed = seed)
            // settled on one side, well away from her first idle blink, then the pointer jumps across her
            repeat(40) { rig.step(16f, -.8f, 0f, false, blinks = false) }
            repeat(3) { rig.step(16f, -.8f, 0f, false) }
            if (rig.frame.blink) continue
            if (rig.step(16f, .8f, 0f, false).blink) blinked++
        }
        assertTrue(blinked in 14..38, "a jump across her blinked $blinked times in 40")
        // a pointer that sweeps smoothly across her never jumps, so it never takes one
        val sweep = ChessyRig(seed = 3)
        var extra = 0
        repeat(40) { sweep.step(16f, -.8f, 0f, false, blinks = false) }
        repeat(50) { i -> if (sweep.step(16f, -.8f + 1.6f * i / 50f, 0f, false, blinkRate = .1f).blink) extra++ }
        assertEquals(0, extra, "a smooth sweep blinked")
    }

    @Test
    fun aBlinkAskedForComesAtOnceButNotTwiceInARowNorOnShutEyes() {
        val rig = ChessyRig(seed = 3)
        repeat(30) { rig.step(16f, 0f, 0f, false, blinks = false) }
        rig.blinkNow()
        assertTrue(rig.step(16f, 0f, 0f, false).blink, "asked, she blinks at once")
        repeat(15) { rig.step(16f, 0f, 0f, false) }
        assertTrue(!rig.frame.blink)
        // a moment after one, a second ask is let go
        rig.blinkNow()
        assertTrue(!rig.step(16f, 0f, 0f, false).blink, "too soon after the last")
        // eyes shut (a face that cannot blink): nothing, and nothing saved for later
        repeat(60) { rig.step(16f, 0f, 0f, false, blinks = false) }
        rig.blinkNow()
        assertTrue(!rig.step(16f, 0f, 0f, false, blinks = false).blink)
    }

    @Test
    fun aLookLandsWithTheFaintestOvershootAndSettles() {
        val rig = ChessyRig(seed = 3)
        var peak = 0f
        var at = -1
        repeat(200) { i ->
            val f = rig.step(16f, 1f, 0f, talking = false, blinks = false)
            if (f.yaw > peak) peak = f.yaw
            if (at < 0 && abs(f.yaw - ChessyRig.YAW) < ChessyRig.YAW * .02f) at = i
        }
        assertTrue(peak > ChessyRig.YAW, "a spring lands past its mark: $peak")
        assertTrue(peak < ChessyRig.YAW * 1.04f, "but barely: ${peak / ChessyRig.YAW}")
        assertTrue(at in 1..30, "and arrives within half a second: step $at")
    }

    @Test
    fun twoOfHerNeverDriftInStep() {
        val a = ChessyRig(seed = 1)
        val b = ChessyRig(seed = 2)
        var apart = 0f
        repeat(10_000 / 16) {
            a.step(16f, null, null, false)
            b.step(16f, null, null, false)
            apart = maxOf(apart, abs(a.frame.yaw - b.frame.yaw))
        }
        assertTrue(apart > .05f, "two rigs left alone moved together: $apart")
    }

    @Test
    fun aSwingAtItsLimitComesStraightBack() {
        val rig = ChessyRig(seed = 3)
        val bell = SwingGroup.BELL.ordinal
        repeat(30) { rig.step(16f, 0f, 0f, false, blinks = false) }
        // flicked hard enough to throw the bell to its limit
        rig.ring(5f)
        var held = 0
        repeat(40) {
            rig.step(16f, 0f, 0f, false, blinks = false)
            if (abs(rig.frame.swingX[bell]) >= SwingGroup.BELL.maxX - 1e-4f) held++
        }
        assertTrue(held > 0, "the flick never reached the limit")
        // it stops at the limit and swings back: it never clings there
        assertTrue(held <= 3, "clung to its limit for $held frames")
    }

    @Test
    fun theRibbonsFollowThroughAfterTheLock() {
        val rig = ChessyRig(seed = 3)
        var bow = 0f
        repeat(40) { rig.step(16f, 1f, 0f, false, blinks = false); bow = maxOf(bow, abs(rig.frame.swingX[SwingGroup.BOW.ordinal])) }
        assertTrue(bow > .5f, "the ribbons' own swing: $bow")
    }

    @Test
    fun anEarFlicksAndSettlesAndAHopBouncesHerHair() {
        val rig = ChessyRig(seed = 3)
        repeat(30) { rig.step(16f, 0f, 0f, false, blinks = false) }
        rig.twitch(1)
        var peak = 0f
        repeat(10) { peak = maxOf(peak, rig.step(16f, 0f, 0f, false, blinks = false).earR) }
        assertTrue(peak in .06f..0.16f, "the flick's peak $peak")
        // the twitch timer may fire its own flick meanwhile, so settle with no new ones in view: a fresh rig, held still
        val calm = ChessyRig(seed = 4)
        repeat(40) { calm.step(16f, 0f, 0f, false, blinks = false) }
        val before = calm.frame.swingY[SwingGroup.SIDE.ordinal]
        // a hop: up 40 sheet px in a tenth of a second
        var moved = 0f
        for (i in 1..12) {
            val y = if (i <= 6) -40f * i / 6f else -40f + 40f * (i - 6) / 6f
            calm.step(16f, 0f, 0f, false, blinks = false, bodyY = y)
            moved = maxOf(moved, abs(calm.frame.swingY[SwingGroup.SIDE.ordinal] - before))
        }
        assertTrue(moved > 2f, "her hair felt the hop: $moved")
    }

    @Test
    fun theWarpReadsItsTurnOnceAFrameAndLandsWhereItAlwaysDid() {
        val out = FloatArray(2)
        val p = LayerPose().apply { off = .06f }
        for (yaw in listOf(-.3f, -.1f, 0f, .17f, .31f)) for (pitch in listOf(-.17f, 0f, .12f)) {
            val f = ChessyFrame().apply { this.yaw = yaw; this.pitch = pitch }
            for ((x, y) in listOf(100f to 200f, 629f to 900f, 900f to 1300f, 1200f to 1600f)) {
                ChessyWarp.place(x, y, p, f, sphere, 629f, 1525f, out, 0)
                // the warp as it was written before the pass: its trigonometry per point
                val qx = (x - sphere.cx) / (sphere.rx * ChessyWarp.GROW)
                val qy = (y - sphere.cy) / (sphere.ry * ChessyWarp.GROW)
                var z = (1f - (qx * qx + qy * qy)).coerceIn(0f, 1f)
                var t = ((y - 400f) / 260f).coerceIn(0f, 1f)
                t = t * t * (3f - 2f * t)
                z *= 1f + p.off * t
                val ex = x + sphere.rx * kotlin.math.sin(yaw) * z + (x - sphere.cx) * (kotlin.math.cos(yaw) - 1f)
                val ey = y - sphere.ry * kotlin.math.sin(pitch) * z + (y - sphere.cy) * (kotlin.math.cos(pitch) - 1f)
                assertEquals(ex, out[0], 1e-3f)
                assertEquals(ey, out[1], 1e-3f)
            }
        }
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
