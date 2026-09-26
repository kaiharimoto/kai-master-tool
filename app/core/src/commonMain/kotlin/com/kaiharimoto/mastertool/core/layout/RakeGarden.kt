package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The raking patterns of a karesansui — samon (砂紋) — that zen mode's garden
 * is raked in. Each is what a gardener draws with a many-tined wooden rake in
 * white gravel, and each stands for water:
 *
 * - **Chokusen** (直線), straight lines: still water. It is also what the wide
 *   rake leaves when it wipes the garden, so every composition starts from it.
 * - **Mizumon** (水紋), ripples: rings round each stone, as a pebble breaks a
 *   pond. The deck is the garden's great stone.
 * - **Ryūsui** (流水), flowing water: parallel lines that meander together.
 * - **Seigaiha** (青海波), blue-sea waves: overlapping scales of concentric arcs.
 * - **Uzumaki** (渦巻), whirlpools: spirals.
 * - **Ichimatsu** (市松), the checkerboard: blocks of lines, alternately across
 *   and down, like a weave.
 */
enum class Samon { CHOKUSEN, MIZUMON, RYUSUI, SEIGAIHA, UZUMAKI, ICHIMATSU }

/** A point in the garden, in window pixels. */
data class GardenPoint(val x: Float, val y: Float)

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
 * One raked layer of the garden: a samon over the whole window, and when each
 * point of it is raked.
 *
 * Everything is a pure function of position (and, for the heads, of time): the
 * pattern at a point is [phase] — which groove, counted in lines — and the
 * moment it is raked is [reveal], in seconds from the layer's start. The garden
 * shader evaluates the same two functions per pixel (its SkSL is a line-for-line
 * copy), so the garden needs no memory of what was drawn and any moment of it can
 * be drawn directly. The rakes that do the work ([heads]) are never shown, but
 * they are always exactly at the edge of the fresh sand — `RakeGardenTest`
 * holds them to it — so the edge moves the way a raked garden grows.
 *
 * Every layer is raked by several rakes at once, from different places, whose
 * work meets: rings from each stone meet along the line halfway between; the
 * flowing lines and the scales are raked from the top and the bottom and meet in
 * the middle; the checkerboard is raked from all four corners inward.
 */
data class RakeLayer(
    val samon: Samon,
    val width: Float,
    val height: Float,
    /** The deck, in window pixels — the great stone the ripples go round. */
    val stone: GardenRect? = null,
    /** The small stones: ripple centres, whirlpool centres. */
    val pebbles: List<GardenPoint> = emptyList(),
    /** 0..1: how this layer's pattern varies from the last time it was raked. */
    val variant: Float = 0f,
    /** How coarse the raking is: every length in the pattern is measured in it. */
    val grain: RakeGrain = RakeGrain(),
) {
    // --- the pattern ---------------------------------------------------------

    /**
     * Which groove is at ([x], [y]), in lines. A groove's trough is at a half
     * line — a tine in the middle of each pitch, as a rake's tines sit in the
     * middle of its bar's segments — and the ridges between are the whole numbers.
     */
    fun phase(x: Float, y: Float): Float = when (samon) {
        Samon.CHOKUSEN -> y / grain.spacing
        // The rings are raked round the deck, not under it: under the cards the straight lines stay.
        Samon.MIZUMON -> if (stone != null && stone.distanceTo(x, y) < 0f) y / grain.spacing else ripple(x, y).first / grain.spacing
        Samon.RYUSUI -> flow(x, y) / grain.spacing
        Samon.SEIGAIHA -> scale(x, y).distance / grain.spacing
        // Two arms: a groove moves out two bands a turn.
        Samon.UZUMAKI -> whirl(x, y).let { (d, turn) -> (d - 2f * turn * grain.band) / grain.spacing }
        Samon.ICHIMATSU -> if (checkAcross(x, y)) y / grain.spacing else x / grain.spacing
    }

    /** When ([x], [y]) is raked, in seconds from the start of the layer. */
    fun reveal(x: Float, y: Float): Float = when (samon) {
        Samon.CHOKUSEN -> grain.sweepReveal(x, width)
        // Under the deck nothing is raked: the straight lines there were never disturbed.
        Samon.MIZUMON -> if (stone != null && stone.distanceTo(x, y) < 0f) 0f else {
            // Two rakes round each stone, from opposite sides: each lap is two half-laps at once.
            val (d, source) = ripple(x, y)
            val c = centreOf(source)
            val turn = angleTurn(x - c.x, y - c.y, source)
            val band = floor(d / grain.band)
            val half = 2f * turn - floor(2f * turn)
            lapTime(band + half, source)
        }
        Samon.RYUSUI -> {
            val (order, fromLeft) = flowPass(x, y)
            pass(order, if (fromLeft) x / width else 1f - x / width)
        }
        Samon.SEIGAIHA -> {
            val (order, fromLeft) = rowPass(scaleBand(y))
            pass(order, if (fromLeft) x / width else 1f - x / width)
        }
        Samon.UZUMAKI -> {
            // Two rakes spiral out from each centre, half a turn apart; the pass over a
            // point is whichever arm's band it is in, and both arms are 2u bands out after u turns.
            val (d, turn, _) = whirlFull(x, y)
            // The second arm starts at the centre half a turn round, so in its first
            // half-turn its pass is −1: the lowest pass is the one that keeps it at or past the start.
            val pass = max(ceil(-2f * turn), ((d / grain.band) - 2f * turn).roundToInt().toFloat())
            spiralTime((pass + 2f * turn) / 2f)
        }
        Samon.ICHIMATSU -> {
            val (order, progress) = checkPass(x, y)
            order * grain.cellTime + progress * grain.cellTime
        }
    }

    /**
     * Seconds the layer takes: until every point of the window has been raked,
     * and, for the sweep, until the wide rake has left the window — the last
     * pixel is raked while the bar is still on the edge.
     */
    val duration: Float by lazy {
        if (samon == Samon.CHOKUSEN) return@lazy grain.sweepTime(width)
        var worst = 0f
        val nx = 48
        val ny = 28
        for (i in 0..nx) for (j in 0..ny) {
            worst = max(worst, reveal(width * i / nx, height * j / ny))
        }
        worst + 0.5f
    }

    /** Where the rakes are, [t] seconds into the layer. A rake that has finished, or is crossing another's ground, is lifted. */
    fun heads(t: Float): List<RakeHead> = when (samon) {
        Samon.CHOKUSEN -> {
            val x = grain.sweepX(t, width)
            if (t < 0f || t > grain.sweepTime(width) || x < -grain.band || x > width + grain.band) emptyList() else listOf(RakeHead(x, height / 2f, 0f, height + 2 * grain.band, wide = true))
        }
        Samon.MIZUMON -> sources().indices.flatMap { s ->
            val tau = lapsAt(t, s)
            val band = floor(tau)
            val f = tau - band
            val c = centreOf(s)
            val r = (band + 0.5f) * grain.band
            listOf(0f, 0.5f).mapNotNull { side ->
                val a = directionOf(s) * (side + f / 2f) * 2f * PI.toFloat()
                val p = if (s == 0 && stone != null) onRing(stone, a, r) else GardenPoint(c.x + cos(a) * (r + grain.pebble), c.y + sin(a) * (r + grain.pebble))
                val round = atan2(p.y - c.y, p.x - c.x) + directionOf(s) * (PI / 2).toFloat()
                p.takeIf { inside(it) && ripple(it.x, it.y).second == s }?.let { RakeHead(it.x, it.y, round, grain.band) }
            }
        }
        Samon.RYUSUI -> listOf(true, false).mapNotNull { fromTop ->
            val order = floor(t / passTime())
            val f = t / passTime() - order
            val band = if (fromTop) order.toInt() else flowBands() - 1 - order.toInt()
            if (band < 0 || band >= flowBands() || (fromTop && band > (flowBands() - 1) / 2) || (!fromTop && band <= (flowBands() - 1) / 2)) return@mapNotNull null
            val fromLeft = order.toInt() % 2 == 0
            val x = if (fromLeft) f * width else (1f - f) * width
            val y = (band + 0.5f) * grain.band - amplitude() - amplitude() * sin(x * 2f * PI.toFloat() / wavelength() + wavePhase())
            val slope = -amplitude() * 2f * PI.toFloat() / wavelength() * cos(x * 2f * PI.toFloat() / wavelength() + wavePhase())
            val sx = if (fromLeft) 1f else -1f
            RakeHead(x, y, atan2(slope * sx, sx), grain.band)
        }
        Samon.SEIGAIHA -> listOf(true, false).mapNotNull { fromTop ->
            val order = floor(t / passTime())
            val f = t / passTime() - order
            val rows = scaleRows()
            val row = if (fromTop) order.toInt() else rows - 1 - order.toInt()
            if (row < 0 || row >= rows || (fromTop && row > (rows - 1) / 2) || (!fromTop && row <= (rows - 1) / 2)) return@mapNotNull null
            val fromLeft = order.toInt() % 2 == 0
            val x = if (fromLeft) f * width else (1f - f) * width
            RakeHead(x, row * grain.scale - grain.scale / 2f, if (fromLeft) 0f else PI.toFloat(), grain.scale)
        }
        Samon.UZUMAKI -> sources().indices.flatMap { s ->
            val u = spiralTurns(t)
            val c = centreOf(s)
            val r = 2f * u * grain.band
            listOf(0f, 0.5f).mapNotNull { side ->
                val a = directionOf(s) * (u + side) * 2f * PI.toFloat()
                val p = GardenPoint(c.x + cos(a) * r, c.y + sin(a) * r)
                p.takeIf { inside(it) && whirlFull(it.x, it.y).third == s }?.let { RakeHead(it.x, it.y, a + directionOf(s) * (PI / 2).toFloat(), grain.band) }
            }
        }
        Samon.ICHIMATSU -> (0 until 4).mapNotNull { q ->
            val i = floor(t / grain.cellTime).toInt()
            val cell = cellAt(q, i) ?: return@mapNotNull null
            val (cx, cy) = cell
            val f = t / grain.cellTime - i
            val passes = (grain.cell / grain.band).roundToInt()
            val lane = min(passes - 1, floor(f * passes).toInt())
            val g = f * passes - lane
            val left = cellLeft() + cx * grain.cell
            val top = cellTop() + cy * grain.cell
            val across = (cx + cy) % 2 == 0
            val forward = lane % 2 == 0
            val along = if (forward) g else 1f - g
            if (across) {
                RakeHead(left + along * grain.cell, top + (lane + 0.5f) * grain.band, if (forward) 0f else PI.toFloat(), grain.band)
            } else {
                RakeHead(left + (lane + 0.5f) * grain.band, top + along * grain.cell, if (forward) (PI / 2).toFloat() else -(PI / 2).toFloat(), grain.band)
            }
        }
    }

    // --- ripples and whirlpools: stones and their laps -------------------------

    /** Every centre a rake circles: the great stone first (when there is one), then the pebbles. */
    private fun sources(): List<GardenPoint> = buildList {
        if (samon == Samon.MIZUMON && stone != null) add(GardenPoint((stone.left + stone.right) / 2f, (stone.top + stone.bottom) / 2f))
        addAll(pebbles)
    }

    private fun centreOf(source: Int): GardenPoint = sources()[source]

    /** Alternate rakes go round alternate ways, so neighbouring rings are not raked in step. */
    private fun directionOf(source: Int): Float = if (source % 2 == 0) 1f else -1f

    /** Distance to the nearest centre's rings, and which centre: the rings meet where two are equally far. */
    fun ripple(x: Float, y: Float): Pair<Float, Int> {
        var best = Float.MAX_VALUE
        var who = 0
        sources().forEachIndexed { i, c ->
            val d = if (i == 0 && stone != null) max(0f, stone.distanceTo(x, y)) else max(0f, hypot(x - c.x, y - c.y) - grain.pebble)
            if (d < best) {
                best = d
                who = i
            }
        }
        return best to who
    }

    /** How far round centre [source] the point ([dx], [dy]) from it is, 0..1, in that rake's own direction. */
    private fun angleTurn(dx: Float, dy: Float, source: Int): Float {
        val a = atan2(dy, dx) / (2f * PI.toFloat())
        val t = if (directionOf(source) > 0f) a else -a
        return t - floor(t)
    }

    /**
     * Seconds for a rake circling [source] to have gone [laps] laps out: each lap
     * one band further, and longer than the last by its larger circumference —
     * and round the great stone by the stone's own perimeter too.
     */
    private fun lapTime(laps: Float, source: Int): Float {
        val (ring, perimeter, start) = lapCosts(source)
        return ring * (laps * laps / 2f + laps / 2f) + (perimeter + start) * laps
    }

    /**
     * What a lap costs round [source], in seconds: the part that grows with each
     * ring, the stone's own perimeter, and a pebble's. Ripples are raked by two
     * rakes at once, so each pays half.
     */
    private fun lapCosts(source: Int): Triple<Float, Float, Float> {
        val share = if (samon == Samon.MIZUMON) 0.5f else 1f
        val ring = 2f * PI.toFloat() * grain.band / grain.speed * share
        val perimeter = if (samon == Samon.MIZUMON && source == 0 && stone != null) 2f * (stone.width + stone.height) / grain.speed * share else 0f
        val start = if (samon == Samon.MIZUMON && source > 0) 2f * PI.toFloat() * grain.pebble / grain.speed * share else 0f
        return Triple(ring, perimeter, start)
    }

    /** The inverse of [lapTime]: how many laps out the rake is after [t] seconds. */
    private fun lapsAt(t: Float, source: Int): Float {
        val (ring, perimeter, start) = lapCosts(source)
        val a = ring / 2f
        val b = ring / 2f + perimeter + start
        return (-b + sqrt(b * b + 4f * a * max(0f, t))) / (2f * a)
    }

    /**
     * Seconds for a whirlpool's rakes to have turned [turns] times: at 2u bands
     * out the circumference is 4πuB, so the time grows as the square.
     */
    private fun spiralTime(turns: Float): Float = grain.spiral * turns * turns + SPIRAL_START * turns

    private fun spiralTurns(t: Float): Float = (-SPIRAL_START + sqrt(SPIRAL_START * SPIRAL_START + 4f * grain.spiral * max(0f, t))) / (2f * grain.spiral)

    /** Distance from the nearest whirlpool centre, how far round it (0..1), and which centre. */
    private fun whirlFull(x: Float, y: Float): Triple<Float, Float, Int> {
        var best = Float.MAX_VALUE
        var who = 0
        sources().forEachIndexed { i, c ->
            val d = hypot(x - c.x, y - c.y)
            if (d < best) {
                best = d
                who = i
            }
        }
        val c = centreOf(who)
        return Triple(best, angleTurn(x - c.x, y - c.y, who), who)
    }

    private fun whirl(x: Float, y: Float): Pair<Float, Float> = whirlFull(x, y).let { it.first to it.second }

    /** The point at angle [a] from the stone's centre that is [r] from the stone. */
    private fun onRing(stone: GardenRect, a: Float, r: Float): GardenPoint {
        val cx = (stone.left + stone.right) / 2f
        val cy = (stone.top + stone.bottom) / 2f
        var lo = 0f
        var hi = stone.width + stone.height + r * 2f
        repeat(30) {
            val mid = (lo + hi) / 2f
            if (stone.distanceTo(cx + cos(a) * mid, cy + sin(a) * mid) < r) lo = mid else hi = mid
        }
        return GardenPoint(cx + cos(a) * lo, cy + sin(a) * lo)
    }

    // --- flowing water -------------------------------------------------------

    // The meander grows with the grain: its swing in proportion, its length more slowly, so a coarse garden still bends on the screen.
    private fun amplitude() = (26f + 22f * variant) * grain.band / 60f
    private fun wavelength() = (460f + 260f * variant) * sqrt(grain.band / 60f)
    private fun wavePhase() = variant * 2f * PI.toFloat()

    /** The warped height that makes the lines meander: a line of flowing water is where this is constant. */
    private fun flow(x: Float, y: Float): Float = y + amplitude() * sin(x * 2f * PI.toFloat() / wavelength() + wavePhase())

    private fun flowBands(): Int = ceil((height + 2f * amplitude()) / grain.band).toInt()

    /** Which pass of which rake rakes ([x], [y]) in flowing water, and whether that pass goes left to right. */
    private fun flowPass(x: Float, y: Float): Pair<Float, Boolean> {
        val band = floor((flow(x, y) + amplitude()) / grain.band).toInt().coerceIn(0, flowBands() - 1)
        val fromTop = band <= (flowBands() - 1) / 2
        val order = if (fromTop) band else flowBands() - 1 - band
        return order.toFloat() to (order % 2 == 0)
    }

    // --- the waves of the blue sea -------------------------------------------

    private fun scaleRows(): Int = ceil(height / grain.scale).toInt() + 2

    data class Scale(val distance: Float, val row: Int)

    /**
     * The scale over ([x], [y]): the circle of the lowest row that contains it.
     * Rows sit a radius apart and every other one is shifted by a radius, and
     * each laps over the row above — so what shows of each circle is its upper
     * part, the scale, and the whole plane is covered.
     */
    fun scale(x: Float, y: Float): Scale {
        val base = floor(y / grain.scale).toInt()
        for (row in base + 2 downTo base - 1) {
            val cy = row * grain.scale
            val offset = if (row % 2 == 0) 0f else grain.scale
            val i = ((x - offset) / (2f * grain.scale)).roundToInt()
            val cx = offset + i * 2f * grain.scale
            val d = hypot(x - cx, y - cy)
            if (d < grain.scale) return Scale(d, row)
        }
        return Scale(0f, base)
    }

    /** The band of the scales a rake pass covers: one scale-radius of height, numbered so its middle is at (row − ½) radii. */
    private fun scaleBand(y: Float): Int = (floor(y / grain.scale).toInt() + 1).coerceIn(0, scaleRows() - 1)

    private fun rowPass(row: Int): Pair<Float, Boolean> {
        val rows = scaleRows()
        val fromTop = row <= (rows - 1) / 2
        val order = if (fromTop) row else rows - 1 - row
        return order.toFloat() to (order % 2 == 0)
    }

    // --- passes, shared by the two raked in rows -----------------------------

    private fun passTime(): Float = width / grain.speed

    private fun pass(order: Float, progress: Float): Float = (order + progress) * passTime()

    // --- the checkerboard ----------------------------------------------------

    private fun cellsAcross(): Int = ceil(width / grain.cell).toInt()
    private fun cellsDown(): Int = ceil(height / grain.cell).toInt()
    private fun cellLeft(): Float = (width - cellsAcross() * grain.cell) / 2f
    private fun cellTop(): Float = (height - cellsDown() * grain.cell) / 2f

    private fun cellOf(x: Float, y: Float): Pair<Int, Int> =
        floor((x - cellLeft()) / grain.cell).toInt().coerceIn(0, cellsAcross() - 1) to floor((y - cellTop()) / grain.cell).toInt().coerceIn(0, cellsDown() - 1)

    private fun checkAcross(x: Float, y: Float): Boolean = cellOf(x, y).let { (i, j) -> (i + j) % 2 == 0 }

    /** How many columns (rows) of cells the left (top) corners' rakes have: the rest belong to the right (bottom). */
    private fun leftCells(): Int = (cellsAcross() + 1) / 2
    private fun topCells(): Int = (cellsDown() + 1) / 2

    /**
     * Which corner's rake a cell belongs to — the quadrant it is in — and its
     * distance from that corner, across and down, in cells.
     */
    private fun quadrant(i: Int, j: Int): Triple<Int, Int, Int> {
        val right = i >= leftCells()
        val down = j >= topCells()
        val dx = if (right) cellsAcross() - 1 - i else i
        val dy = if (down) cellsDown() - 1 - j else j
        return Triple((if (right) 1 else 0) + (if (down) 2 else 0), dx, dy)
    }

    private fun quadrantSize(q: Int): Pair<Int, Int> =
        (if (q % 2 == 1) cellsAcross() - leftCells() else leftCells()) to (if (q >= 2) cellsDown() - topCells() else topCells())

    /**
     * The turn a corner's rake reaches a cell in: ring by ring out from its
     * corner (a ring is every cell as far from the corner, across or down, as
     * the farthest), and along each ring down its far column and then back along
     * its far row — closed form, so the shader can ask it too.
     */
    private fun cellOrder(q: Int, dx: Int, dy: Int): Int {
        val (qa, qd) = quadrantSize(q)
        val r = max(dx, dy)
        val before = min(r, qa) * min(r, qd)
        val column = if (r < qa) min(r, qd - 1) + 1 else 0
        val index = if (dx == r) dy else column + dx
        return before + index
    }

    /** The cell corner [q]'s rake is working on in its [n]th turn, or null once it has finished. */
    private fun cellAt(q: Int, n: Int): Pair<Int, Int>? {
        for (i in 0 until cellsAcross()) for (j in 0 until cellsDown()) {
            val (qq, dx, dy) = quadrant(i, j)
            if (qq == q && cellOrder(q, dx, dy) == n) return i to j
        }
        return null
    }

    private fun checkPass(x: Float, y: Float): Pair<Float, Float> {
        val (i, j) = cellOf(x, y)
        val (q, dx, dy) = quadrant(i, j)
        val order = cellOrder(q, dx, dy)
        val left = cellLeft() + i * grain.cell
        val top = cellTop() + j * grain.cell
        val passes = (grain.cell / grain.band).roundToInt()
        val across = (i + j) % 2 == 0
        val lane = floor(((if (across) y - top else x - left) / grain.band)).toInt().coerceIn(0, passes - 1)
        val along = ((if (across) x - left else y - top) / grain.cell).coerceIn(0f, 1f)
        val g = if (lane % 2 == 0) along else 1f - along
        return order.toFloat() to (lane + g) / passes
    }

    private fun inside(p: GardenPoint) = p.x >= -grain.band && p.y >= -grain.band && p.x <= width + grain.band && p.y <= height + grain.band

    companion object {
        /** Turns' worth of seconds a whirlpool's rakes take to get going: the tight middle is slow. */
        private const val SPIRAL_START = 0.8f
    }
}

/**
 * How coarse the raking is: the pitch between two grooves, how many tines a
 * rake has, and how fast rakes are drawn. Every length in every samon is
 * measured in it — a ripple ring is a band, a wave scale's radius is a band, a
 * checkerboard block is four — so a coarser grain is the same garden raked
 * bigger, not a different one. The shader is handed the same numbers.
 */
data class RakeGrain(
    /** Window pixels between two grooves: a tine's pitch. */
    val spacing: Float = 10f,
    val tines: Int = 6,
    /** How fast a rake is drawn through the gravel, px/s. */
    val speed: Float = 210f,
    /** The wide rake's top speed, px/s: it gathers up to this across the middle and eases out at the far edge. */
    val sweepSpeed: Float = 300f,
) {
    /** The width of ground one pass rakes. */
    val band: Float get() = spacing * tines

    /** A pebble's own radius: the first ring starts clear of it. */
    val pebble: Float get() = spacing

    /** A wave scale's radius. */
    val scale: Float get() = band

    /** A checkerboard block's side: four passes of the rake. */
    val cell: Float get() = band * 4f

    /** Seconds one rake takes over one block: four passes along it. */
    val cellTime: Float get() = (cell / band) * (cell / speed)

    /** Seconds per turn², for a whirlpool's rakes: at 2u bands out the circumference is 4πuB. */
    val spiral: Float get() = 2f * PI.toFloat() * band / speed

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
 * The garden over time: raked straight to begin with, then a composition raked
 * over it until its rakes meet, a pause to look at it, the wide rake sweeping it
 * back to straight lines, a pause, and the next — a different samon each time,
 * never the one just wiped, with its stones and its flow placed afresh.
 */
class RakeProgram(
    val width: Float,
    val height: Float,
    private val stone: GardenRect?,
    private val seed: Int = 0,
    val grain: RakeGrain = RakeGrain(),
) {
    /** What the garden is doing at a moment: the layer underneath, the one being raked over it, and how far in. */
    data class Frame(val base: RakeLayer, val top: RakeLayer?, val topTime: Float)

    private val straight = RakeLayer(Samon.CHOKUSEN, width, height, grain = grain)
    private val cycles = mutableListOf<RakeLayer>()

    /** The [n]th composition. */
    fun composition(n: Int): RakeLayer {
        while (cycles.size <= n) cycles += compose(cycles.size, cycles.lastOrNull()?.samon)
        return cycles[n]
    }

    private fun compose(n: Int, previous: Samon?): RakeLayer {
        val kinds = Samon.entries.filter { it != Samon.CHOKUSEN && it != previous }
        val h = hash(seed, n)
        val kind = kinds[h % kinds.size]
        val variant = ((h ushr 8) % 1000) / 1000f
        val pebbles = when (kind) {
            Samon.MIZUMON -> placePebbles(h, 3)
            Samon.UZUMAKI -> placePebbles(h, 3) + listOfNotNull(stone?.let { GardenPoint((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) })
            else -> emptyList()
        }
        return RakeLayer(kind, width, height, stone, pebbles, variant, grain)
    }

    /**
     * Small stones for ripples and whirlpools: either side of the deck and above
     * or below it, nudged by the hash, never on the deck — so rings from each meet
     * rings from the deck across the whole window.
     */
    private fun placePebbles(h: Int, count: Int): List<GardenPoint> {
        val s = stone
        val spots = if (s == null) {
            listOf(GardenPoint(width * 0.2f, height * 0.3f), GardenPoint(width * 0.8f, height * 0.7f), GardenPoint(width * 0.5f, height * 0.5f))
        } else {
            listOf(
                GardenPoint(s.left / 2f, height * 0.32f),
                GardenPoint((s.right + width) / 2f, height * 0.68f),
                GardenPoint(if (h % 2 == 0) s.left / 2f else (s.right + width) / 2f, if (h % 2 == 0) height * 0.8f else height * 0.2f),
            )
        }
        return spots.take(count).mapIndexed { i, p ->
            val jx = (((h ushr (4 + i * 3)) % 100) / 100f - 0.5f) * 0.12f * width
            val jy = (((h ushr (7 + i * 3)) % 100) / 100f - 0.5f) * 0.14f * height
            GardenPoint((p.x + jx).coerceIn(40f, width - 40f), (p.y + jy).coerceIn(40f, height - 40f))
        }
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
        const val HOLD = 8f

        /** Seconds of fresh straight lines after a wipe. */
        const val REST = 2.5f

        private fun hash(a: Int, b: Int): Int {
            var h = a * 0x27D4EB2D xor b * 0x165667B1
            h = h xor (h ushr 15)
            h *= 0x2C1B3C6D
            h = h xor (h ushr 12)
            return h and 0x7FFFFFFF
        }
    }
}
