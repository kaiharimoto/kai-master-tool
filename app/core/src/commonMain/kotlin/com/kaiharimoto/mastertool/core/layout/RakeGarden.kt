package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How zen mode's garden is raked — a karesansui, planned and raked the way a
 * gardener does it rather than drawn as a set of figures.
 *
 * **What a rake can draw.** A rake's tines are fixed, so every pass leaves
 * grooves exactly one pitch apart across its whole width, however it turns. The
 * only families of curves that stay a constant distance apart everywhere are
 * the contours of a distance: straight lines are the distance to a line, rings
 * the distance to a stone, and the lines that follow a stream the distance to
 * the stream. So every pattern here is one field — the distance to the
 * garden's *seeds* — and a groove is wherever that distance is a whole number
 * and a half of pitches ([RakeLayer.phase]). Nothing else is drawn, which is
 * why nothing can look like it was not raked.
 *
 * **Where waves meet.** A plain nearest-seed distance meets its neighbour in a
 * crease, grooves colliding at an angle along a straight line — the seams that
 * made the first gardens look wrong. Here the distances are joined with a
 * smooth minimum ([RakeGrain.blend] wide): two stones' rings fold into one
 * envelope with a rounded fillet, the way a gardener takes one pass round both
 * rather than stopping at the halfway line.
 *
 * **Where the seeds go.** [GardenComposer] places them by the old rules of
 * stone setting (石組, ishigumi): odd numbers, one principal stone, the rest in
 * uneven triangles — never three in a line, never mirrored about the middle,
 * never crowding — scoring every candidate against those rules and keeping the
 * best, so each composition is new and every one is balanced.
 *
 * **How it is raked.** Each stone has two rakes that start on opposite sides
 * of it and lap outward a band at a time; a stream has one rake on each bank,
 * pass after pass, alternating direction. All of them work at once, so the
 * garden grows outward from every seed and the waves meet and settle along the
 * lines between them ([RakeLayer.reveal]). The rakes themselves are never
 * drawn — the pattern draws itself — but [RakeLayer.heads] models every one,
 * and `RakeGardenTest` holds the fresh edge to where they are.
 *
 * The compositions ([Samon]):
 * - **Chokusen** (直線), straight lines: still water, and what the wide rake
 *   leaves when it wipes the garden.
 * - **Mizumon** (水紋), ripples: stones whose rings grow until they fill the garden.
 * - **Ryūsui** (流水), flowing water: a stream across the garden, its lines
 *   bending round the stones that stand in it.
 * - **Shima** (島), islands: a few rings round each group of stones, left in the
 *   straight lines — the Ryōan-ji garden.
 */
enum class Samon { CHOKUSEN, MIZUMON, RYUSUI, SHIMA }

/** A point in the garden, in window pixels. */
data class GardenPoint(val x: Float, val y: Float)

/** A stone the garden is raked round: its centre and its radius, in window pixels. */
data class GardenStone(val x: Float, val y: Float, val r: Float)

/**
 * The line flowing water is raked along: two gentle waves summed, so the stream
 * meanders rather than oscillates.
 */
data class GardenRiver(
    val y0: Float,
    val a1: Float,
    val l1: Float,
    val p1: Float,
    val a2: Float,
    val l2: Float,
    val p2: Float,
) {
    fun at(x: Float): Float = y0 + a1 * sin(TAU * x / l1 + p1) + a2 * sin(TAU * x / l2 + p2)

    fun slope(x: Float): Float = a1 * TAU / l1 * cos(TAU * x / l1 + p1) + a2 * TAU / l2 * cos(TAU * x / l2 + p2)

    /** Signed distance from the stream, positive below it: to first order, which on a stream this gentle is within a few per cent. */
    fun distance(x: Float, y: Float): Float {
        val s = slope(x)
        return (y - at(x)) / sqrt(1f + s * s)
    }
}

/**
 * A rake head where it is this instant: its centre, the direction it is being
 * drawn in (radians; its bar lies across that), how long the bar is, and
 * whether it is the wide rake that wipes the garden. The app never draws one —
 * the rakes are invisible and the pattern draws itself — but the rakes are what
 * make the pattern honest: a head is a real rake moving at a real speed, and
 * `RakeGardenTest` holds [RakeLayer.reveal] to the edge each head is cutting.
 */
data class RakeHead(val x: Float, val y: Float, val heading: Float, val length: Float, val wide: Boolean = false)

/**
 * One raked layer of the garden: a composition over the whole window, and when
 * each point of it is raked. Everything is a pure function of position (and, for
 * the heads, of time), and the garden shader evaluates the same functions per
 * pixel — its SkSL is a line-for-line copy — so the garden remembers nothing
 * between frames and any moment of it can be drawn directly.
 */
data class RakeLayer(
    val samon: Samon,
    val width: Float,
    val height: Float,
    val stones: List<GardenStone> = emptyList(),
    val river: GardenRiver? = null,
    /** Islands only: how many bands of rings each group gets before the straight lines resume. */
    val rings: Int = 0,
    /** How coarse the raking is: every length in the pattern is measured in it. */
    val grain: RakeGrain = RakeGrain(),
) {
    // --- the field -----------------------------------------------------------

    /** The seeds' own distances, in order: every stone, then the stream (unsigned). */
    private fun seedDistance(i: Int, x: Float, y: Float): Float =
        if (i < stones.size) {
            val s = stones[i]
            max(0f, hypot(x - s.x, y - s.y) - s.r)
        } else {
            abs(river!!.distance(x, y))
        }

    private val seeds: Int get() = stones.size + (if (river != null) 1 else 0)

    /**
     * The garden's field at ([x], [y]): the distances to every seed joined by a
     * smooth minimum, in the same order the shader joins them.
     */
    fun field(x: Float, y: Float): Float {
        var f = 0f
        for (i in 0 until seeds) {
            val d = seedDistance(i, x, y)
            f = if (i == 0) d else smin(f, d, grain.blend)
        }
        return f
    }

    /**
     * Whose rake reaches ([x], [y]): the nearest seed, and for the stream the bank
     * the point is on — [stones].size for below it, one more for above.
     */
    fun owner(x: Float, y: Float): Int {
        var best = Float.MAX_VALUE
        var who = 0
        for (i in 0 until seeds) {
            val d = seedDistance(i, x, y)
            if (d < best) {
                best = d
                who = if (i < stones.size) i else if (river!!.distance(x, y) >= 0f) stones.size else stones.size + 1
            }
        }
        return who
    }

    /** Inside a stone: gravel no rake touches, left smooth. */
    fun onStone(x: Float, y: Float): Boolean = stones.any { hypot(x - it.x, y - it.y) < it.r }

    /** Islands: past its rings the garden keeps the straight lines it was swept to. */
    private fun beyondRings(f: Float): Boolean = samon == Samon.SHIMA && f >= rings * grain.band

    /**
     * Which groove is at ([x], [y]), in lines. A groove's trough is at a half
     * line — a tine in the middle of each pitch, as a rake's tines sit in the
     * middle of its bar's segments — and the ridges between are the whole numbers.
     */
    fun phase(x: Float, y: Float): Float {
        if (samon == Samon.CHOKUSEN) return y / grain.spacing
        val f = field(x, y)
        return if (beyondRings(f)) y / grain.spacing else f / grain.spacing
    }

    // --- the rakes -----------------------------------------------------------

    /** When ([x], [y]) is raked, in seconds from the start of the layer. */
    fun reveal(x: Float, y: Float): Float {
        if (samon == Samon.CHOKUSEN) return grain.sweepReveal(x, width)
        val f = field(x, y)
        if (beyondRings(f) || onStone(x, y)) return 0f
        val band = max(0f, floor(f / grain.band))
        val who = owner(x, y)
        if (who >= stones.size) return (band + passProgress(band, x)) * passTime()
        // Two rakes round each stone, from opposite sides: each lap is two half-laps at once.
        val s = stones[who]
        val turn = angleTurn(x - s.x, y - s.y, who)
        return lapTime(band + frac(2f * turn), s.r) * pace[who]
    }

    /**
     * How long each stone's laps take against a full circle's, as a factor on
     * [lapTime]. Mostly it is how much of its laps the stone's rakes actually
     * rake, 0.35..1: a stone in
     * a corner, or crowded by its neighbours, owns only part of every circle it
     * laps, and its rakes step over the rest rather than walking it — so they get
     * round in that fraction of the time. The fraction is the ground the stone
     * owns against the ground a whole circle out to its furthest point would be.
     */
    val pace: List<Float> by lazy {
        // Rings are raked with care — islands most of all, by one rake a stone — so a composition
        // of stones takes about as long to rake as a stream does.
        val care = when (samon) {
            Samon.SHIMA -> ISLAND_CARE
            Samon.MIZUMON -> RIPPLE_CARE
            else -> 1f
        }
        stones.indices.map { i ->
            val reach = survey.reach[i]
            val circle = PI.toFloat() * reach * reach
            care * if (circle <= 0f) 1f else (survey.owned[i] / circle).coerceIn(0.35f, 1f)
        }
    }

    /** What each rake has to rake: the ground it owns, how far out that reaches, and its furthest band. */
    private class Survey(seeds: Int) {
        val owned = FloatArray(seeds)
        val reach = FloatArray(seeds)
        val band = IntArray(seeds) { -1 }
    }

    private val survey: Survey by lazy {
        val s = Survey(stones.size + 2)
        val nx = 96
        val ny = 54
        val cell = (width / nx) * (height / ny)
        for (i in 0..nx) for (j in 0..ny) {
            val x = width * i / nx
            val y = height * j / ny
            if (onStone(x, y)) continue
            val f = field(x, y)
            if (beyondRings(f)) continue
            val who = owner(x, y)
            s.owned[who] += cell
            if (who < stones.size) s.reach[who] = max(s.reach[who], hypot(x - stones[who].x, y - stones[who].y))
            // How many bands out this rake goes: to its furthest sample.
            s.band[who] = max(s.band[who], floor(max(0f, f) / grain.band).toInt() + 1)
        }
        s
    }

    /**
     * Seconds the layer takes: until every point of the window has been raked,
     * and, for the sweep, until the wide rake has left the window — the last
     * pixel is raked while the bar is still on the edge.
     */
    val duration: Float by lazy {
        if (samon == Samon.CHOKUSEN) return@lazy grain.sweepTime(width)
        // Every rake's last band run to its end: exact, where the latest reveal on a grid could fall short.
        var worst = 0f
        stones.forEachIndexed { i, st ->
            var bands = survey.band[i]
            if (samon == Samon.SHIMA) bands = min(bands, rings)
            if (bands > 0) worst = max(worst, lapTime(bands.toFloat(), st.r) * pace[i])
        }
        for (bank in 0..1) {
            val bands = survey.band[stones.size + bank]
            if (bands > 0) worst = max(worst, bands * passTime())
        }
        worst + 0.5f
    }

    /** Where the rakes are, [t] seconds into the layer. A rake that has finished, or is crossing another's ground, is lifted. */
    fun heads(t: Float): List<RakeHead> {
        if (samon == Samon.CHOKUSEN) {
            val x = grain.sweepX(t, width)
            return if (t < 0f || t > grain.sweepTime(width) || x < -grain.band || x > width + grain.band) {
                emptyList()
            } else {
                listOf(RakeHead(x, height / 2f, 0f, height + 2 * grain.band, wide = true))
            }
        }
        val heads = mutableListOf<RakeHead>()
        stones.forEachIndexed { i, s ->
            val laps = lapsAt(t / pace[i], s.r)
            val band = floor(laps)
            if (samon == Samon.SHIMA && band >= rings) return@forEachIndexed
            val f = laps - band
            val target = (band + 0.5f) * grain.band
            for (side in listOf(0f, 0.5f)) {
                val a = directionOf(i) * (side + f / 2f) * TAU
                val dx = cos(a)
                val dy = sin(a)
                val rho = firstCrossing(s.r, s.r + (band + 2f) * grain.band * 2f) { field(s.x + dx * it, s.y + dy * it) - target } ?: continue
                val p = GardenPoint(s.x + dx * rho, s.y + dy * rho)
                if (inside(p) && owner(p.x, p.y) == i) heads += RakeHead(p.x, p.y, a + directionOf(i) * (PI / 2).toFloat(), grain.band)
            }
        }
        val r = river
        if (r != null) {
            val band = floor(t / passTime())
            val progress = t / passTime() - band
            val forward = band.toInt() % 2 == 0
            val x = if (forward) progress * width else (1f - progress) * width
            val target = (band + 0.5f) * grain.band
            for (bank in listOf(1f, -1f)) {
                val y0 = r.at(x)
                val offset = firstCrossing(0f, (band + 2f) * grain.band * 2f) { field(x, y0 + bank * it) - target } ?: continue
                val p = GardenPoint(x, y0 + bank * offset)
                val who = stones.size + if (bank > 0f) 0 else 1
                val sx = if (forward) 1f else -1f
                if (inside(p) && owner(p.x, p.y) == who) heads += RakeHead(p.x, p.y, atan2(r.slope(x) * sx, sx), grain.band)
            }
        }
        return heads
    }

    // --- laps and passes -----------------------------------------------------

    /** Alternate stones' rakes go round alternate ways, so neighbouring rings are not raked in step. */
    private fun directionOf(stone: Int): Float = if (stone % 2 == 0) 1f else -1f

    /** How far round a stone the point ([dx], [dy]) from it is, 0..1, in its rakes' own direction. */
    private fun angleTurn(dx: Float, dy: Float, stone: Int): Float {
        val a = atan2(dy, dx) / TAU
        return frac(if (directionOf(stone) > 0f) a else -a)
    }

    /**
     * Seconds for the rakes round a stone of radius [r] to have gone [laps] laps
     * out: each lap a band further, longer than the last by its larger
     * circumference, and shared by the stone's two rakes.
     */
    private fun lapTime(laps: Float, r: Float): Float {
        val (ring, start) = lapCosts(r)
        return ring * (laps * laps / 2f + laps / 2f) + start * laps
    }

    private fun lapCosts(r: Float): Pair<Float, Float> =
        (TAU * grain.band / grain.speed * 0.5f) to (TAU * r / grain.speed * 0.5f)

    /** The inverse of [lapTime]: how many laps out the rakes are after [t] seconds. */
    private fun lapsAt(t: Float, r: Float): Float {
        val (ring, start) = lapCosts(r)
        val a = ring / 2f
        val b = ring / 2f + start
        return (-b + sqrt(b * b + 4f * a * max(0f, t))) / (2f * a)
    }

    /** Seconds for one pass along the stream, from one side of the window to the other. */
    private fun passTime(): Float = width / grain.speed

    /** How far along its pass a bank's rake is at [x], in pass [band]: the passes alternate direction. */
    private fun passProgress(band: Float, x: Float): Float = if (band.toInt() % 2 == 0) x / width else 1f - x / width

    private fun inside(p: GardenPoint) =
        p.x >= -grain.band && p.y >= -grain.band && p.x <= width + grain.band && p.y <= height + grain.band

    companion object {
        /** How much longer a ripple's laps take than a rake walking them flat out would. */
        const val RIPPLE_CARE = 1.8f

        /** And an island's: one rake instead of two, going slower still. */
        const val ISLAND_CARE = 4.5f

        /** Polynomial smooth minimum: exactly min(a, b) once they differ by [k], a rounded fillet within it. */
        fun smin(a: Float, b: Float, k: Float): Float {
            val h = max(k - abs(a - b), 0f) / k
            return min(a, b) - h * h * k / 4f
        }

        private fun frac(v: Float): Float = v - floor(v)

        /**
         * Where [g] first reaches zero going out from [lo] toward [hi]: marched in
         * small steps, then bisected. Null when it never does — the rake's contour
         * is someone else's ground all the way out.
         */
        private fun firstCrossing(lo: Float, hi: Float, g: (Float) -> Float): Float? {
            val step = (hi - lo) / 96f
            var prev = lo
            var r = lo + step
            while (r <= hi) {
                if (g(r) >= 0f) {
                    var a = prev
                    var b = r
                    repeat(24) {
                        val m = (a + b) / 2f
                        if (g(m) < 0f) a = m else b = m
                    }
                    return (a + b) / 2f
                }
                prev = r
                r += step
            }
            return null
        }
    }
}

private const val TAU = (2 * PI).toFloat()

/**
 * How coarse the raking is: the pitch between two grooves, how many tines a
 * rake has, and how fast rakes are drawn. Every length in every composition is
 * measured in it — a lap is a band, stones are a few pitches across, their
 * spacing a few bands — so a coarser grain is the same garden raked bigger,
 * not a different one. The shader is handed the same numbers.
 */
data class RakeGrain(
    /** Window pixels between two grooves: a tine's pitch. */
    val spacing: Float = 32f,
    val tines: Int = 5,
    /** How fast a rake is drawn through the gravel, px/s. */
    val speed: Float = 150f,
    /** The wide rake's top speed, px/s: it gathers up to this across the middle and eases out at the far edge. */
    val sweepSpeed: Float = 300f,
) {
    /** The width of ground one pass rakes. */
    val band: Float get() = spacing * tines

    /** How wide the fillet is where two seeds' waves meet: most of a band, so they fold together rather than crease. */
    val blend: Float get() = band * 0.6f

    /**
     * Seconds the sweep takes across a window [width] wide, from a band off the
     * left edge to a band off the right. It is eased in and out (a smoothstep),
     * whose top speed is one and a half times its mean.
     */
    fun sweepTime(width: Float): Float = (width + 2 * band) / sweepSpeed * 1.5f

    /** Where the wide rake's bar is, [t] seconds into a sweep. */
    fun sweepX(t: Float, width: Float): Float {
        val u = (t / sweepTime(width)).coerceIn(0f, 1f)
        return -band + (width + 2 * band) * u * u * (3f - 2f * u)
    }

    /** When the sweep reaches [x]: [sweepX] inverted, in closed form (the smoothstep's inverse is a sine of a third of an arcsine). */
    fun sweepReveal(x: Float, width: Float): Float {
        val s = ((x + band) / (width + 2 * band)).coerceIn(0f, 1f)
        return sweepTime(width) * (0.5f - sin(asin(1f - 2f * s) / 3f))
    }
}

/** A rectangle in window pixels. Core does not know Compose's. */
data class GardenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** Signed distance from the rectangle: negative inside. */
    fun distanceTo(x: Float, y: Float): Float {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val qx = abs(x - cx) - width / 2f
        val qy = abs(y - cy) - height / 2f
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f)
    }
}

/**
 * The gardener's eye: where the stones go. Candidates are drawn at random and
 * each is scored against the rules of stone setting, and the best is kept —
 * so the placement is new every time and balanced every time.
 *
 * - **Room**: never closer to another stone than two bands, or the rings
 *   between them have nowhere to go; and a stone does best about three bands from
 *   its nearest neighbour, near enough to be a group.
 * - **Uneven triangles**: a stone nearly in line with two others is refused —
 *   three in a row is the first thing a gardener avoids.
 * - **No mirror**: a stone near the reflection of another about the middle of
 *   the garden is marked down; symmetry is stillness of the wrong kind.
 * - **Seen**: the first stones go beside the deck rather than under it, so the
 *   rings can be watched leaving them; later ones may sit under the cards, and
 *   their rings come out from beneath.
 * - **Balanced**: their middle stays near the garden's middle, held level by
 *   unequal weights at unequal distances rather than by a mirror.
 * - **Clear of the frame** by most of a band.
 */
class GardenComposer(
    private val width: Float,
    private val height: Float,
    private val deck: GardenRect?,
    private val grain: RakeGrain,
) {
    /**
     * [count] stones. Each is the best of many candidates given the ones before
     * it — but a greedy placement can corner itself by the last stone, so the
     * whole arrangement is tried a few times and the one whose flattest triangle
     * is least flat is kept.
     */
    fun stones(random: Random, count: Int, near: GardenRiver? = null, spacing: Float = ROOM, seen: Int = 2): List<GardenPoint> {
        var best: List<GardenPoint> = emptyList()
        var bestFlat = -1f
        repeat(ATTEMPTS) {
            val placed = arrange(random, count, near, spacing, seen)
            val flat = flattest(placed)
            if (flat > bestFlat) {
                bestFlat = flat
                best = placed
            }
        }
        return best
    }

    private fun arrange(random: Random, count: Int, near: GardenRiver?, spacing: Float, seen: Int): List<GardenPoint> {
        val placed = mutableListOf<GardenPoint>()
        repeat(count) { k ->
            var best: GardenPoint? = null
            var bestScore = Float.NEGATIVE_INFINITY
            repeat(CANDIDATES) {
                val p = GardenPoint(random.nextFloat() * width, random.nextFloat() * height)
                val score = score(p, placed, near, spacing, mustBeSeen = k < seen)
                if (score > bestScore) {
                    bestScore = score
                    best = p
                }
            }
            placed += best!!
        }
        return placed
    }

    /** The smallest |sine| of any triangle three of [points] make: 1 with fewer than three. */
    private fun flattest(points: List<GardenPoint>): Float {
        var worst = 1f
        for (i in points.indices) for (j in i + 1 until points.size) for (k in j + 1 until points.size) {
            worst = min(worst, minSine(points[i], points[j], points[k]))
        }
        return worst
    }

    /** How well [p] obeys the rules, given the stones already [placed]. Higher is better. */
    fun score(p: GardenPoint, placed: List<GardenPoint>, near: GardenRiver? = null, spacing: Float = ROOM, mustBeSeen: Boolean = false): Float {
        val b = grain.band
        var score = 0f
        val edge = min(min(p.x, width - p.x), min(p.y, height - p.y))
        if (edge < b * 0.7f) score -= 40f
        if (mustBeSeen && deck != null && deck.distanceTo(p.x, p.y) < b * 0.4f) score -= 60f
        if (placed.isNotEmpty()) {
            val nearest = placed.minOf { hypot(it.x - p.x, it.y - p.y) }
            if (nearest < b * spacing) score -= 1000f
            score -= abs(nearest / b - spacing * 1.4f) * 3f
        }
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            // How far from a line the three are: the smallest |sine| of the triangle's angles.
            val flat = minSine(p, placed[i], placed[j])
            if (flat < 0.25f) score -= 100f else if (flat < 0.4f) score -= 20f
        }
        for (q in placed) {
            val mirrors = listOf(GardenPoint(width - q.x, q.y), GardenPoint(q.x, height - q.y), GardenPoint(width - q.x, height - q.y))
            if (mirrors.any { hypot(it.x - p.x, it.y - p.y) < b * 0.8f }) score -= 25f
        }
        if (placed.isNotEmpty()) {
            // Balance: the stones' middle stays near the garden's middle — held level
            // the way a scale is, by unequal weights at unequal distances, never by a mirror.
            val cx = (placed.sumOf { it.x.toDouble() }.toFloat() + p.x) / (placed.size + 1)
            val cy = (placed.sumOf { it.y.toDouble() }.toFloat() + p.y) / (placed.size + 1)
            score -= hypot(cx - width / 2f, (cy - height / 2f) * 0.5f) / b * 6f
        }
        if (near != null) {
            // Stones in a stream stand in it: between one and two and a half bands from its line.
            val d = abs(near.distance(p.x, p.y)) / b
            if (d < 1f || d > 2.5f) score -= 50f
        }
        return score
    }

    private fun minSine(a: GardenPoint, b: GardenPoint, c: GardenPoint): Float {
        fun sine(p: GardenPoint, q: GardenPoint, r: GardenPoint): Float {
            val ux = q.x - p.x
            val uy = q.y - p.y
            val vx = r.x - p.x
            val vy = r.y - p.y
            return abs(ux * vy - uy * vx) / (hypot(ux, uy) * hypot(vx, vy) + 1e-3f)
        }
        return minOf(sine(a, b, c), sine(b, c, a), sine(c, a, b))
    }

    companion object {
        /** Bands between two stones, at the least. */
        const val ROOM = 2f
        private const val CANDIDATES = 64
        private const val ATTEMPTS = 6
    }
}

/**
 * The garden over time: raked straight to begin with, then a composition raked
 * over it until its rakes meet, a pause to look at it, the wide rake sweeping it
 * back to straight lines, a pause, and the next — a different composition each
 * time, never the kind just wiped, its stones placed afresh.
 */
class RakeProgram(
    val width: Float,
    val height: Float,
    private val deck: GardenRect?,
    private val seed: Int = 0,
    val grain: RakeGrain = RakeGrain(),
) {
    /** What the garden is doing at a moment: the layer underneath, the one being raked over it, and how far in. */
    data class Frame(val base: RakeLayer, val top: RakeLayer?, val topTime: Float)

    private val straight = RakeLayer(Samon.CHOKUSEN, width, height, grain = grain)
    private val cycles = mutableListOf<RakeLayer>()
    private val composer = GardenComposer(width, height, deck, grain)

    /** The [n]th composition. */
    fun composition(n: Int): RakeLayer {
        while (cycles.size <= n) cycles += compose(cycles.size, cycles.lastOrNull()?.samon)
        return cycles[n]
    }

    private fun compose(n: Int, previous: Samon?): RakeLayer {
        val kinds = listOf(Samon.MIZUMON, Samon.RYUSUI, Samon.SHIMA).filter { it != previous }
        val random = Random(hash(seed, n))
        val kind = kinds[random.nextInt(kinds.size)]
        val s = grain.spacing
        return when (kind) {
            Samon.MIZUMON -> {
                val points = composer.stones(random, if (random.nextBoolean()) 3 else 5)
                RakeLayer(kind, width, height, sized(points, random), grain = grain)
            }
            Samon.RYUSUI -> {
                val b = grain.band
                val river = GardenRiver(
                    y0 = height * (0.35f + 0.3f * random.nextFloat()),
                    // Gentle, so the lines a long way out still bend without folding: a
                    // bend's radius has to stay longer than the furthest line is from it.
                    a1 = b * (0.25f + 0.15f * random.nextFloat()),
                    l1 = width * (1f + 0.4f * random.nextFloat()),
                    p1 = random.nextFloat() * TAU,
                    a2 = b * 0.06f,
                    l2 = width * 0.45f,
                    p2 = random.nextFloat() * TAU,
                )
                val points = composer.stones(random, 2, near = river)
                RakeLayer(kind, width, height, sized(points, random), river = river, grain = grain)
            }
            else -> {
                // Islands: two or three groups, each a tight triangle of up to three stones.
                val groups = composer.stones(random, 2 + random.nextInt(2), spacing = 3.5f)
                val stones = groups.flatMapIndexed { g, c ->
                    val members = if (g == 0) 3 else 1 + random.nextInt(3)
                    val start = random.nextFloat() * TAU
                    (0 until members).map { m ->
                        if (m == 0) {
                            GardenStone(c.x, c.y, s * (if (g == 0) 1.5f else 1.1f))
                        } else {
                            val a = start + m * 2.3f + random.nextFloat() * 0.5f
                            val d = grain.band * (0.6f + 0.3f * random.nextFloat())
                            GardenStone(c.x + cos(a) * d, c.y + sin(a) * d, s * (0.45f + 0.3f * random.nextFloat()))
                        }
                    }
                }
                RakeLayer(kind, width, height, stones, rings = 2 + random.nextInt(2), grain = grain)
            }
        }
    }

    /** One principal stone, then smaller ones: the first is the largest, as in any grouping. */
    private fun sized(points: List<GardenPoint>, random: Random): List<GardenStone> = points.mapIndexed { i, p ->
        val r = grain.spacing * when (i) {
            0 -> 1.5f
            1 -> 1.1f
            else -> 0.55f + 0.35f * random.nextFloat()
        }
        GardenStone(p.x, p.y, r)
    }

    /** The frame [t] seconds after zen began. */
    fun at(t: Float): Frame {
        var base = straight
        var clock = OPENING
        if (t < clock) return Frame(base, null, 0f)
        var n = 0
        while (true) {
            val layer = composition(n)
            // Raked over the straight lines.
            if (t < clock + layer.duration) return Frame(base, layer, t - clock)
            clock += layer.duration
            if (t < clock + HOLD) return Frame(layer, null, 0f)
            clock += HOLD
            // Swept back to straight.
            if (t < clock + straight.duration) return Frame(layer, straight, t - clock)
            clock += straight.duration
            base = straight
            if (t < clock + REST) return Frame(base, null, 0f)
            clock += REST
            n++
        }
    }

    companion object {
        /** Seconds of straight lines before the first composition begins. */
        const val OPENING = 1.5f

        /** Seconds a finished composition is left to be looked at before it is wiped. */
        const val HOLD = 10f

        /** Seconds of fresh straight lines after a wipe. */
        const val REST = 3f

        private fun hash(a: Int, b: Int): Int {
            var h = a * 0x27D4EB2D xor b * 0x165667B1
            h = h xor (h ushr 15)
            h *= 0x2C1B3C6D
            h = h xor (h ushr 12)
            return h and 0x7FFFFFFF
        }
    }
}
