package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RakeGardenTest {

    private val deck = GardenRect(560f, 120f, 1360f, 960f)
    private val pebbles = listOf(GardenPoint(260f, 330f), GardenPoint(1680f, 740f), GardenPoint(280f, 860f))

    private val coarse = RakeGrain(spacing = 30f, tines = 5)

    private fun layer(samon: Samon, variant: Float = 0.4f, grain: RakeGrain = RakeGrain()) = RakeLayer(
        samon, 1920f, 1080f, deck,
        if (samon == Samon.MIZUMON || samon == Samon.UZUMAKI) pebbles else emptyList(),
        variant,
        grain,
    )

    @Test
    fun theRakeIsAlwaysAtTheEdgeOfTheFreshSand() {
        // Where a rake is and what the shader says has just been raked must agree, at any grain.
        for (samon in Samon.entries) for (grain in listOf(RakeGrain(), coarse)) {
            val l = layer(samon, grain = grain)
            var checked = 0
            var t = 0.7f
            while (t < l.duration) {
                l.heads(t).forEach { h ->
                    if (h.x in 1f..1919f && h.y in 1f..1079f) {
                        val r = l.reveal(h.x, h.y)
                        assertTrue(abs(r - t) < 0.4f, "$samon ($grain) at $t: the head at (${h.x}, ${h.y}) is over sand raked at $r")
                        checked++
                    }
                }
                t += l.duration / 37f
            }
            assertTrue(checked > 5, "$samon ($grain): only $checked heads were in the window")
        }
    }

    @Test
    fun everyCompositionRakesTheWholeWindowInAReasonableTime() {
        for (samon in Samon.entries) for (grain in listOf(RakeGrain(), coarse)) {
            val l = layer(samon, grain = grain)
            assertTrue(l.duration in 3f..160f, "$samon takes ${l.duration}s")
            for (i in 0..40) for (j in 0..24) {
                val r = l.reveal(1920f * i / 40, 1080f * j / 24)
                assertTrue(r >= 0f && r <= l.duration, "$samon at ($i, $j): $r")
            }
        }
    }

    @Test
    fun severalRakesWorkAtOnceAndMeet() {
        for (samon in Samon.entries - Samon.CHOKUSEN) {
            val l = layer(samon)
            val busy = (1..20).maxOf { l.heads(l.duration * it / 24f).size }
            assertTrue(busy >= 2, "$samon is raked by one rake at a time")
        }
    }

    @Test
    fun theWideRakeSweepsInOnePassLeftToRight() {
        val l = layer(Samon.CHOKUSEN)
        var last = -1f
        for (i in 0..20) {
            val r = l.reveal(1920f * i / 20, 540f)
            assertTrue(r > last)
            last = r
            // Its lines are straight: the same at every height.
            assertEquals(l.reveal(1920f * i / 20, 0f), l.reveal(1920f * i / 20, 1080f))
        }
        val heads = l.heads(3f)
        assertEquals(1, heads.size)
        assertTrue(heads.single().wide && heads.single().length >= 1080f)
    }

    @Test
    fun ripplesAreRingsRoundTheStoneAndThePebbles() {
        val l = layer(Samon.MIZUMON)
        // Just outside the deck's edge, the rings run parallel to it: the same groove all along the top.
        val a = l.phase(700f, 120f - 25f)
        val b = l.phase(1200f, 120f - 25f)
        assertEquals(a, b, 1e-3f)
        // Round a pebble, the same groove at the same distance.
        val p = pebbles[1]
        assertEquals(l.phase(p.x + 40f, p.y), l.phase(p.x, p.y - 40f), 1e-3f)
    }

    @Test
    fun whirlpoolsAreContinuousWhereTheirTurnWraps() {
        val l = layer(Samon.UZUMAKI)
        val c = pebbles[0]
        // Either side of the angle where a turn wraps, the groove differs by whole lines only
        // (two bands of six tines: twelve).
        val above = l.phase(c.x + 120f, c.y - 0.01f)
        val below = l.phase(c.x + 120f, c.y + 0.01f)
        val d = above - below
        assertTrue(abs(d - round(d)) < 0.02f, "a seam of $d lines")
    }

    @Test
    fun theCheckerboardIsRakedFromEveryCornerWithoutWaiting() {
        val l = layer(Samon.ICHIMATSU)
        val cell = RakeGrain().cell
        // Every block starts on a whole turn, and each corner's turns run 0, 1, 2, … with no gaps.
        val starts = mutableMapOf<Int, MutableSet<Int>>()
        for (i in 0 until 8) for (j in 0 until 5) {
            val x = i * cell + cell / 2
            val y = -60f + j * cell + cell / 2
            if (y < 0f || y > 1080f) continue
            val turn = floor(l.reveal(x, y) / RakeGrain().cellTime).toInt()
            val q = (if (i >= 4) 1 else 0) + (if (j >= 3) 2 else 0)
            starts.getOrPut(q) { mutableSetOf() } += turn
        }
        starts.values.forEach { turns -> assertEquals((0 until turns.size).toSet(), turns) }
        // Alternate blocks run across and down.
        assertNotEquals(l.phase(120f, 60f) - l.phase(120f, 50f), l.phase(360f, 60f) - l.phase(360f, 50f))
    }

    @Test
    fun theProgramRakesThenSweepsThenRakesSomethingElse() {
        val program = RakeProgram(1920f, 1080f, deck, seed = 5)
        assertEquals(Samon.CHOKUSEN, program.at(0f).base.samon)
        var t = 0f
        var last: Samon? = null
        repeat(12) { n ->
            val c = program.composition(n)
            assertNotEquals(last, c.samon, "the same samon twice running at $n")
            assertNotEquals(Samon.CHOKUSEN, c.samon)
            last = c.samon
        }
        // Through the first cycle: raking, holding, sweeping, resting.
        val first = program.composition(0)
        t = RakeProgram.OPENING + first.duration / 2
        assertEquals(first.samon, program.at(t).top?.samon)
        t = RakeProgram.OPENING + first.duration + RakeProgram.HOLD / 2
        assertEquals(first.samon, program.at(t).base.samon)
        assertEquals(null, program.at(t).top)
        t = RakeProgram.OPENING + first.duration + RakeProgram.HOLD + 1f
        val sweep = program.at(t)
        assertEquals(Samon.CHOKUSEN, sweep.top?.samon)
        assertEquals(first.samon, sweep.base.samon)
        assertTrue(sweep.top!!.heads(sweep.topTime).single().wide)
    }

    @Test
    fun theSweepEasesAcrossAndIsNotCutAwayWhileTheRakeIsInView() {
        val w = 1920f
        val sweep = RakeLayer(Samon.CHOKUSEN, w, 1080f)
        val g = RakeGrain()
        val total = g.sweepTime(w)
        // Eased: slow off the left edge, fastest across the middle, slow into the right.
        val early = g.sweepX(total * 0.1f, w) - g.sweepX(0f, w)
        val middle = g.sweepX(total * 0.55f, w) - g.sweepX(total * 0.45f, w)
        assertTrue(middle > 3f * early, "the sweep is not eased: $early then $middle")
        // sweepReveal inverts sweepX.
        for (x in listOf(0f, 300f, 960f, 1500f, 1919f)) {
            assertEquals(x, g.sweepX(g.sweepReveal(x, w), w), 0.5f)
        }
        // The layer lasts until the bar is a band clear of the right edge, not until the last pixel is raked.
        assertEquals(total, sweep.duration, 0.01f)
        assertTrue(sweep.heads(total - 0.05f).isNotEmpty())
        assertTrue(g.sweepX(total, w) >= w + g.band - 0.01f)
    }

    @Test
    fun aCoarserGrainIsTheSameGardenRakedBigger() {
        // Straight lines, waves and the checkerboard are the fine pattern magnified: the groove at a point
        // of the coarse garden is the groove at the corresponding point of the fine one.
        val k = 3f
        val fine = RakeGrain()
        val big = RakeGrain(spacing = fine.spacing * k)
        for (samon in listOf(Samon.CHOKUSEN, Samon.SEIGAIHA)) {
            val f = RakeLayer(samon, 640f, 360f, grain = fine)
            val c = RakeLayer(samon, 1920f, 1080f, grain = big)
            for (i in 1..30) for (j in 1..17) {
                val x = 640f * i / 31f
                val y = 360f * j / 18f
                assertEquals(f.phase(x, y), c.phase(x * k, y * k), 1e-3f, "$samon at ($x, $y)")
            }
        }
        // And the rakes cover it faster in proportion to how much wider they are.
        val ripplesFine = layer(Samon.MIZUMON).duration
        val ripplesCoarse = layer(Samon.MIZUMON, grain = coarse).duration
        assertTrue(ripplesCoarse < ripplesFine / 1.5f, "coarse ripples take ${ripplesCoarse}s against ${ripplesFine}s")
    }
}
