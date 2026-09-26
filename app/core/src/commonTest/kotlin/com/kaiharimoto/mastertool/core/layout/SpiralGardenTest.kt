package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpiralGardenTest {
    private val g = SpiralGarden(1920f, 1080f, 958f, 539f)
    private fun underDeck(x: Float, y: Float) = x in 470f..1447f && y in 103f..975f

    /** The phase's distance from a whole number: 0 on a ridge, ½ in a trough. */
    private fun offWhole(p: Float) = abs(p - round(p))

    @Test
    fun anArmIsAGoldenSpiral() {
        // Along one arm the radius grows by φ every quarter turn, in the layer's own direction.
        for (n in 0..3) {
            val l = g.layer(n)
            val a0 = 0.7
            val r0 = 300.0
            val p0 = g.phase(l, (g.centreX + r0 * cos(a0)).toFloat(), (g.centreY + r0 * sin(a0)).toFloat())
            for (q in 1..3) {
                val a = a0 + l.hand * q * PI / 2
                val r = r0 * SpiralGarden.PHI.pow(q)
                val p = g.phase(l, (g.centreX + r * cos(a)).toFloat(), (g.centreY + r * sin(a)).toFloat())
                val d = p - p0
                assertTrue(abs(d - round(d)) < 0.02f, "layer $n: a quarter turn out the phase moved by $d, not a whole number")
            }
        }
    }

    @Test
    fun theFamiliesAreASunflowersTakenInTurn() {
        val layers = (0..7).map(g::layer)
        layers.zipWithNext().forEach { (a, b) ->
            assertEquals(setOf(34, 55), setOf(a.arms, b.arms))
            assertEquals(-a.hand, b.hand)
        }
        // Each is set round by the golden angle from the last, plus the turn the flow has given the
        // spiral by the time it begins.
        layers.forEach { l ->
            val turns = l.index * SpiralGarden.GOLDEN_TURN -
                l.hand * SpiralGarden.B * SpiralGarden.FLOW * l.index * g.layerTime / (2 * PI)
            val want = (turns - floor(turns)) * 2 * PI
            val d = abs(l.turn - want)
            assertTrue(d < 1e-3 || abs(d - 2 * PI) < 1e-3, "layer ${l.index}: turned ${l.turn}, not $want")
        }
    }

    @Test
    fun theSandFlowsOutwardAndTheFlowIsAZoom() {
        // A golden spiral zoomed is a golden spiral turned: the pattern flowed by z is the
        // pattern at rest, scaled by e^z about the centre.
        for (n in 0..1) {
            val l = g.layer(n)
            for (z in listOf(0.1f, 0.4f, 1.3f)) for (a in listOf(0.3, 2.0, 4.4)) {
                val r = 320.0
                val x0 = (g.centreX + r * cos(a)).toFloat()
                val y0 = (g.centreY + r * sin(a)).toFloat()
                val s = kotlin.math.exp(z.toDouble())
                val x1 = (g.centreX + r * s * cos(a)).toFloat()
                val y1 = (g.centreY + r * s * sin(a)).toFloat()
                assertEquals(g.phase(l, x0, y0), g.phase(l, x1, y1, z), 2e-3f)
            }
            // A zoom of e^(2π/b) is a whole turn: every groove is back where it started.
            val whole = (2 * PI / SpiralGarden.B).toFloat()
            val d = g.phase(l, 700f, 300f, whole) - g.phase(l, 700f, 300f)
            assertTrue(abs(d - round(d)) < 0.01f, "a whole turn of flow moved the grooves by $d")
        }
    }

    @Test
    fun theFlowStaysExactHoursIntoZen() {
        // The flow a layer is born into is folded into its turn; the unfolded phase, computed
        // from the raw time in doubles, must agree however late the layer.
        for (n in listOf(0, 1, 7, 150, 600)) {
            val l = g.layer(n)
            val tau = 3.7f
            val t = n * g.layerTime.toDouble() + tau
            val x = 1500f
            val y = 250f
            val theta = kotlin.math.atan2((y - g.centreY).toDouble(), (x - g.centreX).toDouble())
            val r = kotlin.math.hypot((x - g.centreX).toDouble(), (y - g.centreY).toDouble())
            val golden = n * SpiralGarden.GOLDEN_TURN * 2 * PI
            val raw = l.arms * (theta - l.hand * SpiralGarden.B * (kotlin.math.ln(r) - SpiralGarden.FLOW * t) - golden) / (2 * PI)
            val folded = g.phase(l, x, y, SpiralGarden.FLOW * tau).toDouble()
            val d = raw - folded
            assertTrue(abs(d - round(d)) < 0.02, "layer $n: folded phase off by ${d - round(d)} grooves")
        }
    }

    @Test
    fun thePhaseIsContinuousWhereTheAngleWrapsRound() {
        for (n in 0..1) {
            val l = g.layer(n)
            for (x in listOf(100f, 300f, 500f)) {
                val above = g.phase(l, x, g.centreY - 0.01f)
                val below = g.phase(l, x, g.centreY + 0.01f)
                val d = above - below
                assertTrue(abs(d - round(d)) < 0.01f, "a seam at x=$x: $d")
            }
        }
    }

    @Test
    fun theArmsSetOffEvenlySpreadRoundTheCircle() {
        // Three-gap property of the golden sequence: however many have set off, the widest gap
        // between them round the circle is never much more than an even share.
        for (n in 0..1) {
            val l = g.layer(n)
            val order = (0 until l.arms).sortedBy { g.start(l, it) }
            for (k in 3..l.arms) {
                val started = order.take(k).map { it.toDouble() / l.arms }.sorted()
                val gaps = started.zipWithNext { a, b -> b - a } + (1 + started.first() - started.last())
                assertTrue(gaps.max() <= 2.7 / k, "layer $n: after $k arms a gap of ${gaps.max()} turns")
            }
            // And they all set off within the stagger.
            assertTrue((0 until l.arms).all { g.start(l, it) in 0f..SpiralGarden.STAGGER })
        }
    }

    @Test
    fun aLayerCoversTheWholeWindowBeforeTheNextBegins() {
        for (n in 0..1) {
            val l = g.layer(n)
            val done = g.drawnBy + SpiralGarden.FADE
            for (i in 0..40) for (j in 0..24) {
                assertEquals(1f, g.weight(l, 1920f * i / 40, 1080f * j / 24, done), 1e-4f)
            }
            assertEquals(0f, g.weight(l, 1900f, 1060f, 0f))
            assertTrue(done < g.layerTime)
        }
    }

    @Test
    fun theWeightOnlyGrowsAndNeverSteps() {
        // Following the sand as it flows out, what has been drawn stays drawn.
        val l = g.layer(1)
        val points = (0 until 64).flatMap { i -> (0 until 36).map { j -> 1920f * (i + 0.5f) / 64 to 1080f * (j + 0.5f) / 36 } }
        val last = FloatArray(points.size)
        var tau = 0f
        while (tau < g.layerTime) {
            val s = kotlin.math.exp(SpiralGarden.FLOW * tau)
            points.forEachIndexed { idx, (x0, y0) ->
                val x = g.centreX + (x0 - g.centreX) * s
                val y = g.centreY + (y0 - g.centreY) * s
                val w = g.weight(l, x, y, tau)
                // A grain exactly on the line between two arms may be counted to either; both sides carry the mean there.
                assertTrue(w >= last[idx] - 2e-3f, "the garden un-drew itself at ($x0, $y0), $tau s in: ${last[idx]} to $w")
                last[idx] = w
            }
            tau += 0.5f
        }
        // Across the edge between a drawn arm and one not yet drawn, both sides agree.
        for (t in listOf(2f, 4f, 6f, 9f)) {
            val z = SpiralGarden.FLOW * t
            val r = 700f
            for (step in 0 until 720) {
                val a = step * 2 * PI / 720
                val x = (g.centreX + r * cos(a)).toFloat()
                val y = (g.centreY + r * sin(a)).toFloat()
                if (offWhole(g.phase(l, x, y, z)) > 0.02f) continue
                val dx = 0.05f * cos(a + 1.2).toFloat()
                val dy = 0.05f * sin(a + 1.2).toFloat()
                assertEquals(g.weight(l, x - dx, y - dy, t), g.weight(l, x + dx, y + dy, t), 0.02f)
            }
        }
    }

    @Test
    fun theGrooveReadsAsAGrainAroundTheDeck() {
        // Out where it can be seen, the grooves are neither a shimmer nor stripes.
        for (n in 0..1) {
            val l = g.layer(n)
            for (i in 0..48) for (j in 0..27) {
                val x = 1920f * i / 48
                val y = 1080f * j / 27
                if (underDeck(x, y)) continue
                val s = g.spacing(l, x, y)
                assertTrue(s in 14f..70f, "layer $n at ($x, $y): grooves $s px apart")
            }
        }
    }

    @Test
    fun theRakesReachTheFarCornerAndTheGardenRunsOnForever() {
        val l = g.layer(0)
        val tip = (0 until l.arms).minOf { g.tip(l, it, g.drawnBy)!! }
        assertTrue(tip >= g.reach - 1f, "an arm stops at $tip of ${g.reach}")
        // A rake opens out as it goes, like the spiral it follows.
        val early = g.tip(l, 0, 2f)!! - g.tip(l, 0, 1f)!!
        val late = g.tip(l, 0, 12f)!! - g.tip(l, 0, 11f)!!
        assertTrue(late > 2f * early)
        val m = g.at(g.layerTime * 7.25f)
        assertEquals(7, m.layer)
        assertEquals(g.layerTime * 0.25f, m.tau, 0.01f)
        assertNotEquals(g.layer(7).hand, g.layer(8).hand)
        assertEquals(0, g.at(-3f).layer)
        assertEquals(0f, floor(g.at(0f).tau))
        assertTrue(g.layerTime in 18f..40f, "a layer takes ${g.layerTime}s")
    }

    @Test
    fun theSunGoesRound() {
        // It starts where the gravel has always been lit from — up and to the left — and comes back.
        val usual = kotlin.math.atan2(-0.66, -0.5).let { if (it < 0) it + 2 * PI else it }
        assertEquals(usual, g.sunAzimuth(0f).toDouble(), 0.01)
        assertEquals(g.sunAzimuth(0f), g.sunAzimuth(SpiralGarden.SUN_TURN.toFloat()), 1e-3f)
        val quarter = (g.sunAzimuth(SpiralGarden.SUN_TURN.toFloat() / 4) - g.sunAzimuth(0f) + 2 * PI.toFloat()) % (2 * PI.toFloat())
        assertEquals((PI / 2).toFloat(), quarter, 1e-3f)
    }
}
