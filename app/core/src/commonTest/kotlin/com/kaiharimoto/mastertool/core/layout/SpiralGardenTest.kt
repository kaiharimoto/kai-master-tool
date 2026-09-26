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
            // Each is turned by the golden angle from the last, so it never lies along the one it covers.
            val step = ((b.turn - a.turn) / (2 * PI) % 1.0 + 1.0) % 1.0
            assertEquals(SpiralGarden.GOLDEN_TURN, step, 1e-4)
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
            val done = g.layerTime - SpiralGarden.HOLD + SpiralGarden.FADE
            for (i in 0..40) for (j in 0..24) {
                assertEquals(1f, g.weight(l, 1920f * i / 40, 1080f * j / 24, done), 1e-4f)
            }
            assertEquals(0f, g.weight(l, 1900f, 1060f, 0f))
        }
    }

    @Test
    fun theWeightOnlyGrowsAndNeverSteps() {
        val l = g.layer(1)
        var last = FloatArray(64 * 36)
        var tau = 0f
        while (tau < g.layerTime) {
            var idx = 0
            for (i in 0 until 64) for (j in 0 until 36) {
                val w = g.weight(l, 1920f * (i + 0.5f) / 64, 1080f * (j + 0.5f) / 36, tau)
                assertTrue(w >= last[idx] - 1e-5f, "the garden un-drew itself")
                last[idx++] = w
            }
            tau += 0.5f
        }
        // Across the edge between a drawn arm and one not yet drawn, both sides agree.
        val r = 700f
        for (step in 0 until 720) {
            val a = step * 2 * PI / 720
            val x = (g.centreX + r * cos(a)).toFloat()
            val y = (g.centreY + r * sin(a)).toFloat()
            val p = g.phase(l, x, y)
            if (offWhole(p) > 0.02f) continue
            // A ridge — a strip edge: the weight a hair either side is the same.
            val dx = 0.05f * cos(a + 1.2).toFloat()
            val dy = 0.05f * sin(a + 1.2).toFloat()
            for (t in listOf(2f, 4f, 6f, 9f)) {
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
        val last = (0 until l.arms).maxOf { g.start(l, it) }
        val tip = (0 until l.arms).minOf { g.tip(l, it, g.layerTime - SpiralGarden.HOLD)!! }
        assertTrue(tip >= g.reach - 1f, "an arm stops at $tip of ${g.reach}")
        assertTrue(last < SpiralGarden.STAGGER)
        val m = g.at(g.layerTime * 7.25f)
        assertEquals(7, m.layer)
        assertEquals(g.layerTime * 0.25f, m.tau, 0.01f)
        assertNotEquals(g.layer(7).hand, g.layer(8).hand)
        assertEquals(0, g.at(-3f).layer)
        assertEquals(0f, floor(g.at(0f).tau))
        assertTrue(max(0f, g.layerTime) in 20f..40f, "a layer takes ${g.layerTime}s")
    }
}
