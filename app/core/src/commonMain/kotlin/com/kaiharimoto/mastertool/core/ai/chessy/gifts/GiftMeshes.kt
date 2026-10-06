package com.kaiharimoto.mastertool.core.ai.chessy.gifts

import com.kaiharimoto.mastertool.core.duel.dice.V3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What a face is made of: the painter gives each its colours and its shading. */
enum class GiftMat {
    PAPER, INK, BOX, RIBBON, CRYSTAL, WRAPPER, FROSTING, CHERRY, STEM,
    SPRINKLE_PINK, SPRINKLE_BLUE, SPRINKLE_YELLOW, SPRINKLE_WHITE,
    CARD_EDGE, PHOTO_BACK, NOTE, WOOD, KNOB,
}

/** A face drawn with a picture: the picture's top-left, top-right and bottom-left land on the face's first, second and fourth corners. */
enum class GiftTex { CARD_FRONT, CARD_BACK, PHOTO, NOTE_OUT, NOTE_IN }

/** One flat face: its corners (indices, counter-clockwise seen from outside), what it is made of, and its picture if any. */
class Face(val idx: IntArray, val mat: GiftMat, val tex: GiftTex? = null)

/**
 * A solid as faces about its middle, about one unit across (x right, y down, z out of the screen toward the person, as
 * the dice and the toys have it). Faces wind counter-clockwise seen from outside, so a face whose turned normal points
 * at the person is a face the person sees.
 */
class Mesh(val v: Array<V3>, val faces: List<Face>) {
    val lo: V3 = V3(v.minOf { it.x }, v.minOf { it.y }, v.minOf { it.z })
    val hi: V3 = V3(v.maxOf { it.x }, v.maxOf { it.y }, v.maxOf { it.z })

    /** The eight corners of its box, for the floor and the walls. */
    val corners: Array<V3> = Array(8) { k -> V3(if (k and 1 == 0) lo.x else hi.x, if (k and 2 == 0) lo.y else hi.y, if (k and 4 == 0) lo.z else hi.z) }

    /** A face's outward normal (not unit), by Newell's method over all its corners. */
    fun normal(f: Face): V3 = GiftMeshes.newell(f.idx.map { v[it] })

    fun centroid(f: Face): V3 {
        var s = V3.ZERO
        for (i in f.idx) s += v[i]
        return s * (1.0 / f.idx.size)
    }
}

/** A gift's solid, in parts that move apart (the chest's drawer slides out of its body). */
class GiftModel(val parts: List<Mesh>) {
    val lo: V3 = V3(parts.minOf { it.lo.x }, parts.minOf { it.lo.y }, parts.minOf { it.lo.z })
    val hi: V3 = V3(parts.maxOf { it.hi.x }, parts.maxOf { it.hi.y }, parts.maxOf { it.hi.z })
    val corners: Array<V3> = Array(8) { k -> V3(if (k and 1 == 0) lo.x else hi.x, if (k and 2 == 0) lo.y else hi.y, if (k and 4 == 0) lo.z else hi.z) }
}

/**
 * Every gift as a real solid (kai: "Everything should be 3D if possible"): the box she makes and its lid, a Maliss card,
 * the polaroid, the folded note, the crystal heart, the cupcake, and the chest of drawers they are kept in. Built once.
 */
object GiftMeshes {
    private class Builder {
        val v = ArrayList<V3>()
        val f = ArrayList<Face>()
        fun p(x: Double, y: Double, z: Double): Int { v += V3(x, y, z); return v.size - 1 }
        fun face(mat: GiftMat, vararg i: Int, tex: GiftTex? = null) { f += Face(i, mat, tex) }

        /** An axis-aligned box about ([cx], [cy], [cz]), half-sizes [hx], [hy], [hz]; its front (+z) and back may carry pictures. */
        fun box(cx: Double, cy: Double, cz: Double, hx: Double, hy: Double, hz: Double, mat: GiftMat, front: GiftTex? = null, back: GiftTex? = null, frontMat: GiftMat = mat, backMat: GiftMat = mat) {
            val c = IntArray(8) { k -> p(cx + (if (k and 1 == 0) -hx else hx), cy + (if (k and 2 == 0) -hy else hy), cz + (if (k and 4 == 0) -hz else hz)) }
            // corners: bit 0 x, bit 1 y (down), bit 2 z (toward the person)
            // a picture's top-left, top-right and bottom-left are a face's first, second and fourth corners
            face(frontMat, c[4], c[5], c[7], c[6], tex = front)            // +z: top-left, top-right, bottom-right, bottom-left
            face(backMat, c[1], c[0], c[2], c[3], tex = back)             // −z, as seen from behind
            face(mat, c[1], c[3], c[7], c[5])                              // +x
            face(mat, c[4], c[6], c[2], c[0])                              // −x
            face(mat, c[1], c[5], c[4], c[0])                              // −y (the top)
            face(mat, c[6], c[7], c[3], c[2])                              // +y (the bottom)
        }

        fun mesh() = Mesh(v.toTypedArray(), f.toList())
    }

    // ---- the box she makes -------------------------------------------------------------------------------------------

    val boxBody: Mesh = Builder().run {
        box(0.0, .08, 0.0, .5, .4, .5, GiftMat.BOX)
        // the ribbon round it, both ways
        box(0.0, .08, 0.0, .1, .405, .505, GiftMat.RIBBON)
        box(0.0, .08, 0.0, .505, .405, .1, GiftMat.RIBBON)
        mesh()
    }

    val boxLid: Mesh = Builder().run {
        box(0.0, -.38, 0.0, .54, .1, .54, GiftMat.BOX)
        box(0.0, -.38, 0.0, .1, .105, .545, GiftMat.RIBBON)
        box(0.0, -.38, 0.0, .545, .105, .1, GiftMat.RIBBON)
        // the bow: two loops leaning apart, and a knot
        loop(-1.0)
        loop(1.0)
        box(0.0, -.51, 0.0, .07, .05, .07, GiftMat.RIBBON)
        mesh()
    }

    /** One loop of the bow, leaning out to [side]: a flattened ring of ribbon. */
    private fun Builder.loop(side: Double) {
        val n = 10
        val ring = (0 until n).map { k ->
            val a = 2 * PI * k / n
            val x = side * (.17 + .15 * cos(a))
            val y = -.6 + .1 * sin(a) - .04 * side * cos(a)
            Pair(p(x, y, -.05), p(x, y, .05))
        }
        for (k in 0 until n) {
            val (a0, a1) = ring[k]
            val (b0, b1) = ring[(k + 1) % n]
            if (side > 0) face(GiftMat.RIBBON, a0, b0, b1, a1) else face(GiftMat.RIBBON, a0, a1, b1, b0)
            if (side > 0) face(GiftMat.RIBBON, a1, b1, b0, a0) else face(GiftMat.RIBBON, a0, b0, b1, a1)
        }
    }

    val box: GiftModel = GiftModel(listOf(boxBody, boxLid))

    // ---- flat things -------------------------------------------------------------------------------------------------

    /** A card, 59 by 86, a sixtieth thick: its face and its back are pictures, its edges paper. */
    val card: GiftModel = GiftModel(listOf(Builder().run {
        box(0.0, 0.0, 0.0, .5 * 59 / 86, .5, 1.0 / 120, GiftMat.CARD_EDGE, front = GiftTex.CARD_FRONT, back = GiftTex.CARD_BACK)
        mesh()
    }))

    /** The polaroid: its front the picture (frame, photo and heart, made in the app), its back plain. */
    val photo: GiftModel = GiftModel(listOf(Builder().run {
        box(0.0, 0.0, 0.0, .42, .5, .012, GiftMat.PAPER, front = GiftTex.PHOTO, backMat = GiftMat.PHOTO_BACK)
        mesh()
    }))

    /**
     * The note, folded like a card and stood half open: its cover ("Thank you ♡") turned 70° toward the person about
     * its hinge on the left, the inside (her words) facing out of the back panel.
     */
    val note: GiftModel = GiftModel(listOf(Builder().run {
        val w = .62
        val h = .44
        val t = .006
        // the back panel, its inside facing the person
        box(w / 2 - w * .2, 0.0, -t, w / 2, h, t, GiftMat.NOTE, front = GiftTex.NOTE_IN)
        // the cover, opened about the hinge (x = −0.2w) by 70°
        val open = 70.0 * PI / 180
        val hx = -w * .2
        fun turned(x: Double, y: Double, z: Double): Int {
            val dx = x - hx
            return p(hx + dx * cos(open) - z * sin(open), y, dx * sin(open) + z * cos(open) + t)
        }
        val c = IntArray(8) { k -> turned(hx + (if (k and 1 == 0) 0.0 else w), if (k and 2 == 0) -h else h, if (k and 4 == 0) -t else t) }
        face(GiftMat.NOTE, c[4], c[5], c[7], c[6], tex = GiftTex.NOTE_OUT)
        face(GiftMat.NOTE, c[1], c[0], c[2], c[3])
        face(GiftMat.NOTE, c[1], c[3], c[7], c[5])
        face(GiftMat.NOTE, c[4], c[6], c[2], c[0])
        face(GiftMat.NOTE, c[1], c[5], c[4], c[0])
        face(GiftMat.NOTE, c[6], c[7], c[3], c[2])
        mesh()
    }))

    // ---- the crystal heart -------------------------------------------------------------------------------------------

    /** The heart's outline, [n] points from the dip at the top round to the point at the bottom and back, y down, about a unit tall. */
    fun heartOutline(n: Int = 28): List<Pair<Double, Double>> {
        val raw = (0 until n).map { k ->
            val t = 2 * PI * k / n
            val x = 16 * sin(t).pow(3)
            val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
            x to y
        }
        val minY = raw.minOf { it.second }
        val maxY = raw.maxOf { it.second }
        val s = 1.0 / (maxY - minY)
        val my = (minY + maxY) / 2
        return raw.map { (x, y) -> x * s to (y - my) * s }
    }

    /**
     * The crystal heart as a heart brilliant (kai, 1.1.31: "heart facets patterns for crystals … more clear with
     * prismatic diffractions"): a thin girdle on the heart's outline; a crown of a table, 8 stars, 8 kites and 16 upper
     * girdle facets rising at about 34° (steeper into the cleft, as fancy shapes are cut); a pavilion of 8 mains running
     * to the culet and 16 lower girdles reaching three quarters of the way down. The 8 mains fall where the outline's
     * parameter is a multiple of π/4: the cleft, the lobes' tops, the widest points, the flanks and the point. Every face
     * is turned to face out from the middle.
     */
    val heart: GiftModel = GiftModel(listOf(Builder().run {
        val n = 32
        val o = heartOutline(n)
        val cx = 0.0
        val cy = o.sumOf { it.second } / n + .04
        val g = .015
        val h = .17
        val run = h / kotlin.math.tan(34.0 * PI / 180)
        val culetZ = -.40
        val gt = o.map { (x, y) -> p(x, y, g) }
        val gb = o.map { (x, y) -> p(x, y, -g) }
        val mains = (0 until n step 4).toList()
        val m = mains.size
        fun reach(k: Int) = kotlin.math.hypot(o[k].first - cx, o[k].second - cy)
        val table = mains.map { k ->
            val sk = (1 - run / reach(k)).coerceIn(.42, .58)
            p(cx + (o[k].first - cx) * sk, cy + (o[k].second - cy) * sk, g + h)
        }
        // star points: halfway from the table edge's middle to the girdle's half point, on a shallower slope than the kites
        val stars = (0 until m).map { i ->
            val a = v[table[i]]
            val b = v[table[(i + 1) % m]]
            val q = v[gt[mains[i] + 2]]
            p(((a.x + b.x) / 2 + q.x) / 2, ((a.y + b.y) / 2 + q.y) / 2, g + .55 * h)
        }
        val culet = p(cx, cy, culetZ)
        val lows = (0 until m).map { i ->
            val q = v[gb[mains[i] + 2]]
            p(q.x + (cx - q.x) * .77, q.y + (cy - q.y) * .77, -g + .8 * (culetZ + g))
        }
        val faces = ArrayList<IntArray>()
        faces += table.toIntArray()
        for (i in 0 until m) {
            val k = mains[i]
            val prev = (i + m - 1) % m
            faces += intArrayOf(table[i], table[(i + 1) % m], stars[i])
            faces += intArrayOf(table[i], stars[prev], gt[k], stars[i])
            faces += intArrayOf(stars[i], gt[k], gt[k + 1], gt[k + 2])
            faces += intArrayOf(stars[i], gt[k + 2], gt[k + 3], gt[(k + 4) % n])
            faces += intArrayOf(culet, lows[prev], gb[k], lows[i])
            faces += intArrayOf(lows[i], gb[k], gb[k + 1], gb[k + 2])
            faces += intArrayOf(lows[i], gb[k + 2], gb[k + 3], gb[(k + 4) % n])
        }
        for (k in 0 until n) faces += intArrayOf(gt[k], gb[k], gb[(k + 1) % n], gt[(k + 1) % n])
        // every face out from the middle
        val mid = V3(cx, cy, 0.0)
        for (f in faces) {
            var c = V3.ZERO
            for (i in f) c += v[i]
            c = c * (1.0 / f.size)
            if (newell(f.map { v[it] }) dot (c - mid) < 0) f.reverse()
            face(GiftMat.CRYSTAL, *f)
        }
        mesh()
    }))

    /** A polygon's normal by Newell's method: sound for faces a little off flat, as a cut gem's kites are. */
    fun newell(pts: List<V3>): V3 {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (a in pts.indices) {
            val p = pts[a]
            val q = pts[(a + 1) % pts.size]
            x += (p.y - q.y) * (p.z + q.z)
            y += (p.z - q.z) * (p.x + q.x)
            z += (p.x - q.x) * (p.y + q.y)
        }
        return V3(x, y, z)
    }

    // ---- the cupcake -------------------------------------------------------------------------------------------------

    /** A ring of [n] points of radius [r] at height [y], turned by [turn]. */
    private fun Builder.ring(n: Int, r: (Int) -> Double, y: Double, turn: Double = 0.0): IntArray =
        IntArray(n) { k -> val a = 2 * PI * k / n + turn; p(r(k) * cos(a), y, r(k) * sin(a)) }

    /** Faces between two rings of the same count, [lower] below [upper] (y down), wound outward. */
    private fun Builder.band(lower: IntArray, upper: IntArray, mat: GiftMat) {
        val n = lower.size
        for (k in 0 until n) {
            val j = (k + 1) % n
            face(mat, lower[k], lower[j], upper[j], upper[k])
        }
    }

    val cupcake: GiftModel = GiftModel(listOf(Builder().run {
        val n = 32
        // the wrapper: a pleated cup, narrow at its foot
        val foot = ring(n, { k -> .3 * (if (k % 2 == 0) 1.0 else .95) }, .5)
        val lip = ring(n, { k -> .4 * (if (k % 2 == 0) 1.0 else .95) }, .06)
        band(foot, lip, GiftMat.WRAPPER)
        val bottom = p(0.0, .5, 0.0)
        for (k in 0 until n) face(GiftMat.WRAPPER, foot[k], bottom, foot[(k + 1) % n])
        // the frosting: a swirl of three rolls, each smaller and turned a little more, up to a point
        val profile = listOf(.42 to .08, .47 to .01, .43 to -.07, .34 to -.11, .38 to -.17, .3 to -.25, .24 to -.28, .26 to -.33, .16 to -.41, .07 to -.47)
        val m = 24
        var prev = ring(m, { profile[0].first }, profile[0].second)
        for ((k, pr) in profile.drop(1).withIndex()) {
            val next = ring(m, { pr.first }, pr.second, turn = (k + 1) * .13)
            band(prev, next, GiftMat.FROSTING)
            prev = next
        }
        val tip = p(0.0, -.5, 0.0)
        for (k in 0 until m) face(GiftMat.FROSTING, prev[(k + 1) % m], tip, prev[k])
        // the cherry and its stem
        sphere(0.0, -.56, 0.0, .1, GiftMat.CHERRY)
        box(.02, -.7, 0.0, .012, .07, .012, GiftMat.STEM)
        // sprinkles scattered on the rolls
        val mats = listOf(GiftMat.SPRINKLE_PINK, GiftMat.SPRINKLE_BLUE, GiftMat.SPRINKLE_YELLOW, GiftMat.SPRINKLE_WHITE)
        for (k in 0 until 14) {
            val a = k * 2.399
            val level = profile[1 + (k * 3) % 7]
            val r = level.first + .01
            sprinkle(r * cos(a), level.second - .02, r * sin(a), a, mats[k % 4])
        }
        mesh()
    }))

    /** A little sphere at ([cx], [cy], [cz]) of radius [r]. */
    private fun Builder.sphere(cx: Double, cy: Double, cz: Double, r: Double, mat: GiftMat) {
        val lat = 6
        val lon = 10
        val top = p(cx, cy - r, cz)
        val bottom = p(cx, cy + r, cz)
        val rings = (1 until lat).map { i ->
            val t = PI * i / lat
            ring(lon, { r * sin(t) }, cy - r * cos(t)).map { idx -> v[idx].let { q -> v[idx] = V3(q.x + cx, q.y, q.z + cz) }; idx }.toIntArray()
        }
        for (k in 0 until lon) face(mat, rings[0][(k + 1) % lon], top, rings[0][k])
        for (i in 0 until rings.size - 1) band(rings[i + 1], rings[i], mat)
        val last = rings.last()
        for (k in 0 until lon) face(mat, last[k], bottom, last[(k + 1) % lon])
    }

    /** One sprinkle: a tiny rod lying round the frosting at angle [a]. */
    private fun Builder.sprinkle(x: Double, y: Double, z: Double, a: Double, mat: GiftMat) {
        val l = .045
        val t = .012
        val dx = -sin(a) * l
        val dz = cos(a) * l
        val c = IntArray(8) { k ->
            val s = if (k and 1 == 0) -1.0 else 1.0
            // across the rod the other way round, so its frame turns as the box's does and its faces wind outward
            p(x + dx * s + (if (k and 4 == 0) t else -t) * cos(a), y + (if (k and 2 == 0) -t else t), z + dz * s + (if (k and 4 == 0) t else -t) * sin(a))
        }
        face(mat, c[4], c[5], c[7], c[6]); face(mat, c[1], c[0], c[2], c[3])
        face(mat, c[1], c[3], c[7], c[5]); face(mat, c[4], c[6], c[2], c[0])
        face(mat, c[1], c[5], c[4], c[0]); face(mat, c[6], c[7], c[3], c[2])
    }

    // ---- the chest -------------------------------------------------------------------------------------------------------

    /** The chest of drawers: its body, and its top drawer as its own part (it slides out toward the person). */
    val chestBody: Mesh = Builder().run {
        // the carcass, open where the top drawer sits
        box(0.0, .12, 0.0, .5, .33, .3, GiftMat.WOOD)
        box(0.0, -.38, 0.0, .5, .03, .3, GiftMat.WOOD)
        box(-.48, -.28, 0.0, .02, .08, .3, GiftMat.WOOD)
        box(.48, -.28, 0.0, .02, .08, .3, GiftMat.WOOD)
        // the lower drawers' fronts and knobs, and its feet
        for (row in 0..1) {
            box(0.0, -.02 + row * .24, .31, .44, .1, .012, GiftMat.WOOD)
            box(0.0, -.02 + row * .24, .335, .05, .025, .015, GiftMat.KNOB)
        }
        for (side in listOf(-1.0, 1.0)) box(side * .42, .49, 0.0, .05, .04, .25, GiftMat.WOOD)
        mesh()
    }

    val chestDrawer: Mesh = Builder().run {
        box(0.0, -.28, .0, .46, .07, .28, GiftMat.PAPER)
        box(0.0, -.28, .29, .47, .085, .012, GiftMat.WOOD)
        box(0.0, -.28, .315, .05, .025, .015, GiftMat.KNOB)
        mesh()
    }

    val chest: GiftModel = GiftModel(listOf(chestBody, chestDrawer))

    /** The solid each kind of gift is. */
    fun of(kind: GiftKind): GiftModel = when (kind) {
        GiftKind.CARD -> card
        GiftKind.HEART -> heart
        GiftKind.NOTE -> note
        GiftKind.PHOTO -> photo
        GiftKind.CUPCAKE -> cupcake
    }

    /** How big each sits in the room, in room units: the card and the polaroid hand-sized, the cupcake small. */
    fun size(kind: GiftKind): Float = when (kind) {
        GiftKind.CARD -> 150f
        GiftKind.HEART -> 104f
        GiftKind.NOTE -> 130f
        GiftKind.PHOTO -> 140f
        GiftKind.CUPCAKE -> 118f
    }

    /** The signed volume of a closed mesh: positive when its faces wind outward. */
    fun volume(m: Mesh): Double {
        var s = 0.0
        for (f in m.faces) {
            val a = m.v[f.idx[0]]
            for (i in 1 until f.idx.size - 1) s += a dot (m.v[f.idx[i]] cross m.v[f.idx[i + 1]])
        }
        return s / 6
    }

    internal fun clamp01(x: Double) = min(1.0, max(0.0, x))
}
