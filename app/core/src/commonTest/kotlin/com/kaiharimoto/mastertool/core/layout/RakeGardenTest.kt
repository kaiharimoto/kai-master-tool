package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RakeGardenTest {

    private val w = 1920f
    private val h = 1080f
    private val deck = GardenRect(474f, 108f, 1446f, 974f)
    private val fine = RakeGrain(spacing = 10f, tines = 6, speed = 210f)

    /** Every kind of composition, several times over, as the program deals them. */
    private fun compositions(grain: RakeGrain = RakeGrain(), seeds: IntRange = 1..6): List<RakeLayer> =
        seeds.flatMap { s -> RakeProgram(w, h, deck, s, grain).let { p -> (0 until 3).map(p::composition) } }

    @Test
    fun theProgramDealsEveryKindOfComposition() {
        val kinds = compositions(seeds = 1..10).map { it.samon }.toSet()
        assertEquals(setOf(Samon.MIZUMON, Samon.RYUSUI, Samon.SHIMA), kinds)
    }

    @Test
    fun theRakeIsAlwaysAtTheEdgeOfTheFreshSand() {
        // Where a rake is and what the shader says has just been raked must agree, at any grain.
        for (grain in listOf(RakeGrain(), fine)) for (l in compositions(grain, 1..4)) {
            var checked = 0
            var t = 0.7f
            while (t < l.duration) {
                l.heads(t).forEach { head ->
                    if (head.x in 1f..w - 1f && head.y in 1f..h - 1f) {
                        val r = l.reveal(head.x, head.y)
                        assertTrue(abs(r - t) < 0.4f, "${l.samon} ($grain) at $t: the rake at (${head.x}, ${head.y}) is over sand raked at $r")
                        checked++
                    }
                }
                t += l.duration / 37f
            }
            assertTrue(checked > 5, "${l.samon} ($grain): only $checked rakes were in the window")
        }
    }

    @Test
    fun everyCompositionRakesTheWholeWindowInAReasonableTime() {
        for (l in compositions()) {
            assertTrue(l.duration in 10f..150f, "${l.samon} takes ${l.duration}s")
            for (i in 0..40) for (j in 0..24) {
                val r = l.reveal(w * i / 40, h * j / 24)
                assertTrue(r >= 0f && r <= l.duration, "${l.samon} at ($i, $j): $r")
            }
        }
    }

    @Test
    fun severalRakesWorkAtOnceAndMeet() {
        for (l in compositions()) {
            val busy = (1..20).maxOf { l.heads(l.duration * it / 24f).size }
            assertTrue(busy >= 2, "${l.samon} is raked by one rake at a time")
        }
    }

    @Test
    fun aRakeCanDrawIt() {
        // A rake leaves its grooves a pitch apart however it turns, so the field must be a
        // distance: its slope one pitch per pitch everywhere but in the fillets where waves meet.
        for (l in compositions()) {
            var samples = 0
            var even = 0
            for (i in 1..63) for (j in 1..35) {
                val x = w * i / 64
                val y = h * j / 36
                if (l.onStone(x, y)) continue
                // The islands' rings end where the straight lines resume: an edge, not a groove.
                if (l.samon == Samon.SHIMA && abs(l.field(x, y) - l.rings * l.grain.band) < 3f) continue
                val gx = (l.phase(x + 1f, y) - l.phase(x - 1f, y)) / 2f
                val gy = (l.phase(x, y + 1f) - l.phase(x, y - 1f)) / 2f
                val slope = hypot(gx, gy) * l.grain.spacing
                assertTrue(slope < 1.1f, "${l.samon} at ($x, $y): grooves ${1 / slope} pitches apart")
                samples++
                if (slope > 0.9f) even++
            }
            assertTrue(even > samples * 0.75f, "${l.samon}: only $even of $samples points are evenly raked")
        }
    }

    @Test
    fun wavesMeetWithoutACrease() {
        // Halfway between two stones a plain nearest-distance peaks in a corner — the slope
        // jumps from +1 to −1 — and the grooves collide there. The smooth join rounds it over.
        val grain = RakeGrain()
        val a = GardenStone(600f, 540f, 40f)
        val b = GardenStone(1000f, 540f, 40f)
        val l = RakeLayer(Samon.MIZUMON, w, h, listOf(a, b), grain = grain)
        val mid = 800f
        fun slope(x: Float) = (l.field(x + 0.5f, 540f) - l.field(x - 0.5f, 540f))
        assertTrue(abs(slope(mid + 3f) - slope(mid - 3f)) < 0.2f, "the waves meet in a crease")
        // And away from the fillet the rings are exact distances.
        assertEquals(100f, l.field(740f, 540f) + 0f, 1f)
    }

    @Test
    fun theStonesAreSetByTheRules() {
        val grain = RakeGrain()
        val b = grain.band
        var triangles = 0
        var groups = 0
        for (s in 1..40) {
            val l = RakeProgram(w, h, deck, s, grain).composition(0)
            if (l.samon != Samon.MIZUMON) continue
            val st = l.stones
            // Room between them, and the first two where they can be seen.
            for (i in st.indices) for (j in i + 1 until st.size) {
                assertTrue(hypot(st[i].x - st[j].x, st[i].y - st[j].y) >= b * GardenComposer.ROOM, "seed $s: stones $i and $j crowd")
            }
            assertTrue(st.take(2).all { deck.distanceTo(it.x, it.y) > 0f }, "seed $s: a first stone is under the deck")
            // The principal stone is the largest.
            assertTrue(st.drop(1).all { it.r < st[0].r })
            // Never three in a line.
            groups++
            var worst = 1f
            for (i in st.indices) for (j in i + 1 until st.size) for (k in j + 1 until st.size) {
                worst = min(worst, minSine(st[i], st[j], st[k]))
            }
            if (worst > 0.2f) triangles++
        }
        assertTrue(groups >= 5, "only $groups ripple compositions in 40 seeds")
        assertTrue(triangles == groups, "$triangles of $groups compositions have no three stones in a line")
    }

    private fun minSine(a: GardenStone, b: GardenStone, c: GardenStone): Float {
        fun sine(p: GardenStone, q: GardenStone, r: GardenStone): Float {
            val ux = q.x - p.x
            val uy = q.y - p.y
            val vx = r.x - p.x
            val vy = r.y - p.y
            return abs(ux * vy - uy * vx) / (hypot(ux, uy) * hypot(vx, vy))
        }
        return minOf(sine(a, b, c), sine(b, c, a), sine(c, a, b))
    }

    @Test
    fun islandsKeepTheStraightLinesPastTheirRings() {
        val l = compositions(seeds = 1..20).first { it.samon == Samon.SHIMA }
        var straight = 0
        for (i in 0..40) for (j in 0..24) {
            val x = w * i / 40
            val y = h * j / 24
            if (l.field(x, y) >= l.rings * l.grain.band) {
                assertEquals(y / l.grain.spacing, l.phase(x, y))
                assertEquals(0f, l.reveal(x, y))
                straight++
            }
        }
        assertTrue(straight > 100, "the islands' rings cover the garden")
    }

    @Test
    fun theStreamsLinesFollowIt() {
        val l = compositions(seeds = 1..20).first { it.samon == Samon.RYUSUI }
        val r = l.river!!
        // Away from its stones, a line of the stream is a fixed distance from it all the way across.
        val d = 2.5f * l.grain.band
        for (x0 in listOf(100f, 700f, 1300f, 1850f)) {
            // Out along the stream's normal.
            val s = r.slope(x0)
            val n = kotlin.math.sqrt(1f + s * s)
            val x = x0 - s * d / n
            val y = r.at(x0) + d / n
            if (l.stones.any { hypot(it.x - x, it.y - y) - it.r < d + l.grain.blend }) continue
            assertEquals(d, l.field(x, y), 0.06f * d)
        }
    }

    @Test
    fun theWideRakeSweepsInOnePassLeftToRight() {
        val l = RakeLayer(Samon.CHOKUSEN, w, h)
        var last = -1f
        for (i in 0..20) {
            val r = l.reveal(w * i / 20, 540f)
            assertTrue(r > last)
            last = r
            // Its lines are straight: the same at every height.
            assertEquals(l.reveal(w * i / 20, 0f), l.reveal(w * i / 20, h))
        }
        val heads = l.heads(3f)
        assertEquals(1, heads.size)
        assertTrue(heads.single().wide && heads.single().length >= h)
    }

    @Test
    fun theProgramRakesThenSweepsThenRakesSomethingElse() {
        val program = RakeProgram(w, h, deck, seed = 5)
        assertEquals(Samon.CHOKUSEN, program.at(0f).base.samon)
        var last: Samon? = null
        repeat(12) { n ->
            val c = program.composition(n)
            assertNotEquals(last, c.samon, "the same kind twice running at $n")
            assertNotEquals(Samon.CHOKUSEN, c.samon)
            last = c.samon
        }
        // Through the first cycle: raking, holding, sweeping, resting.
        val first = program.composition(0)
        var t = RakeProgram.OPENING + first.duration / 2
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
        val g = RakeGrain()
        val sweep = RakeLayer(Samon.CHOKUSEN, w, h)
        val total = g.sweepTime(w)
        // Eased: slow off the left edge, fastest across the middle, slow into the right.
        val early = g.sweepX(total * 0.1f, w) - g.sweepX(0f, w)
        val middle = g.sweepX(total * 0.55f, w) - g.sweepX(total * 0.45f, w)
        assertTrue(middle > 3f * early, "the sweep is not eased: $early then $middle")
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
        // The field is a distance, so a garden raked k times coarser with its stones k times further
        // apart is the fine one magnified: the groove at every point is the groove at the matching point.
        val k = 3.2f
        val small = RakeLayer(Samon.MIZUMON, 600f, 340f, listOf(GardenStone(150f, 120f, 15f), GardenStone(420f, 200f, 10f)), grain = fine)
        val big = RakeLayer(
            Samon.MIZUMON, 600f * k, 340f * k,
            small.stones.map { GardenStone(it.x * k, it.y * k, it.r * k) },
            grain = RakeGrain(spacing = fine.spacing * k, tines = fine.tines),
        )
        for (i in 1..30) for (j in 1..17) {
            val x = 600f * i / 31f
            val y = 340f * j / 18f
            assertEquals(small.phase(x, y), big.phase(x * k, y * k), 1e-2f, "at ($x, $y)")
        }
    }
}
