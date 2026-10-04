package com.kaiharimoto.mastertool.core.duel.dice

import kotlin.math.abs

/**
 * The opening roll's dice, thrown for real (1.0.87; kai: "the dice should be real and 3D and actually simulated, not
 * an animation"): two rigid unit cubes falling, bouncing, sliding and tumbling to rest on the seat's field.
 *
 * - **Bodies**: a position, a unit-quaternion orientation, linear and angular velocity; mass 1, the solid cube's
 *   inertia (⅙, the same about every axis, so spin needs no gyroscopic term).
 * - **Contacts**: each cube's eight corners against the table (z = 0) and the field's four walls; a corner of either
 *   die inside the other pushed out through the nearest face; and the two dice's inscribed spheres, so an edge can
 *   never slide through an edge. Every contact is solved by sequential impulses (eight passes a step): restitution on
 *   a real impact, Coulomb friction in two tangent directions bounded by the normal impulse, a little positional
 *   bias to undo penetration; the walls are met a little before a corner reaches them, so a hard fling cannot pass.
 * - **Rolling resistance and air**: spin and slide damped while a die touches the table, a breath of air always.
 * - **Rest**: both dice still and flat for a fifth of a second, or [MAX_TIME] gone by; then each is set exactly flat
 *   on its nearest face and the two pushed apart should their footprints overlap — a few frames of blend, so the
 *   last frame never jumps.
 *
 * A fixed step of [DT] and plain [Double] arithmetic (no `sin`/`cos` in the loop), so the same [DiceThrow] gives the
 * same frames on every device: the host, the guest and a replay all see the same dice.
 */
object DiceSim {
    /** The seat's field, in die edges: x across it, y from its far edge toward the player. */
    const val ARENA_W = 20.0
    const val ARENA_D = 8.0
    /**
     * How far past the field's far edge the table runs before a wall (1.0.95, kai: "don't have it bounce off of the center
     * box, have it roll over it instead"): about the middle row's depth, so a die thrown hard rolls over the Extra Monster
     * Zones and the chain well, never off an edge there. A constant, not the window's: every screen plays the same throw.
     */
    const val INNER = 4.5

    const val DT = 1.0 / 480.0
    /** One frame kept every this many steps: sixty a second. */
    const val FRAME_EVERY = 8
    const val FRAME_DT = DT * FRAME_EVERY
    const val MAX_TIME = 6.0

    const val GRAVITY = 80.0
    private const val TABLE_BOUNCE = 0.42
    private const val WALL_BOUNCE = 0.5
    private const val DICE_BOUNCE = 0.3
    private const val TABLE_FRICTION = 0.6
    private const val WALL_FRICTION = 0.2
    private const val DICE_FRICTION = 0.25
    /** Below this closing speed a contact does not bounce: resting dice do not chatter. */
    private const val BOUNCE_FLOOR = 1.2
    private const val INV_MASS = 1.0
    private const val INV_INERTIA = 6.0
    private const val PASSES = 8
    private const val BIAS = 0.25
    private const val SLOP = 0.004
    private const val ROLL_DAMP = 0.7
    private const val SLIDE_DAMP = 0.25
    private const val AIR_DAMP = 0.08
    private const val QUIET_V = 0.08
    private const val QUIET_W = 0.25
    private const val QUIET_TILT = 0.004
    private const val QUIET_TIME = 0.2
    private const val BLEND_FRAMES = 8
    /**
     * How far out from the walls a corner is met (a die flung at the fastest moves 0.1 in a step). Never the table: a
     * corner met before it lands takes the bounce out of the landing, and the dice slid instead of tumbling.
     */
    private const val AHEAD = 0.2

    /** A die's place and turn. */
    data class Pose(val p: V3, val q: Quat)

    /** The dice at [t] seconds. */
    data class Frame(val t: Double, val dice: List<Pose>)

    /**
     * A throw played out: [frames] sixty a second from the hand to rest, [rest] the poses it ended in (flat), [up]
     * each die's face on top there, [settled] false when [MAX_TIME] stopped it rather than the dice.
     */
    data class Run(val frames: List<Frame>, val up: List<Int>, val settled: Boolean) {
        val rest: List<Pose> get() = frames.last().dice
        val duration: Double get() = frames.last().t

        /** The dice at [t] seconds, between the two frames either side. */
        fun at(t: Double): List<Pose> {
            if (t <= 0) return frames.first().dice
            if (t >= duration) return rest
            val f = t / FRAME_DT
            val i = f.toInt().coerceIn(0, frames.size - 2)
            val k = (f - i).coerceIn(0.0, 1.0)
            val a = frames[i].dice
            val b = frames[i + 1].dice
            return a.indices.map { d -> Pose(a[d].p + (b[d].p - a[d].p) * k, Quat.nlerp(a[d].q, b[d].q, k)) }
        }
    }

    private class Body(var p: V3, var q: Quat, var v: V3, var w: V3) {
        var grounded = false
        var quiet = 0.0
    }

    private class Contact(
        val a: Body,
        val b: Body?,
        val ra: V3,
        val rb: V3,
        val n: V3,
        val t1: V3,
        val t2: V3,
        val target: Double,
        val mu: Double,
        val kn: Double,
        val k1: Double,
        val k2: Double,
    ) {
        var jn = 0.0
        var j1 = 0.0
        var j2 = 0.0
    }

    /** The eight corners of a unit cube, in its own frame. */
    private val CORNERS: List<V3> = buildList {
        for (x in listOf(-0.5, 0.5)) for (y in listOf(-0.5, 0.5)) for (z in listOf(-0.5, 0.5)) add(V3(x, y, z))
    }

    /** Plays [toss] out to rest. A throw that is not [DiceThrow.valid] lies where it was put. */
    fun run(toss: DiceThrow): Run {
        if (!toss.valid) {
            val poses = List(2) { i -> Pose(V3(ARENA_W / 2 + (i - 0.5) * 1.6, ARENA_D / 2, 0.5), Quat.IDENTITY) }
            return Run(listOf(Frame(0.0, poses)), List(2) { DieFaces.PZ }, true)
        }
        val bodies = toss.dice.map { Body(it.p, it.q.normalized(), it.v, it.w) }
        val frames = ArrayList<Frame>()
        frames += Frame(0.0, bodies.map { Pose(it.p, it.q) })
        val steps = (MAX_TIME / DT).toInt()
        var settled = false
        var step = 0
        while (step < steps) {
            step(bodies)
            step++
            if (step % FRAME_EVERY == 0) frames += Frame(step * DT, bodies.map { Pose(it.p, it.q) })
            if (bodies.all { it.quiet >= QUIET_TIME }) { settled = true; break }
        }
        if (step % FRAME_EVERY != 0) frames += Frame(frames.last().t + FRAME_DT, bodies.map { Pose(it.p, it.q) })
        // Exactly flat, apart, inside the walls; blended in over a few frames.
        val last = frames.last().dice
        val flat = separate(last.map { flatten(it) })
        for (k in 1..BLEND_FRAMES) {
            val s = k.toDouble() / BLEND_FRAMES
            frames += Frame(
                frames.last().t + FRAME_DT,
                last.indices.map { d -> Pose(last[d].p + (flat[d].p - last[d].p) * s, Quat.nlerp(last[d].q, flat[d].q, s)) },
            )
        }
        // The blend's last frame is the rest pose itself, bit for bit.
        frames[frames.size - 1] = Frame(frames.last().t, flat)
        return Run(frames, flat.map { DieFaces.upFace(it.q) }, settled)
    }

    private fun step(bodies: List<Body>) {
        bodies.forEach { it.v = it.v + V3(0.0, 0.0, -GRAVITY * DT) }
        val contacts = ArrayList<Contact>()
        bodies.forEach { it.grounded = false }
        bodies.forEach { b -> boundaries(b, contacts) }
        if (bodies.size == 2) between(bodies[0], bodies[1], contacts)
        repeat(PASSES) { contacts.forEach(::solve) }
        bodies.forEach { b ->
            val air = 1.0 - AIR_DAMP * DT
            var w = b.w * air
            var v = b.v
            if (b.grounded) {
                w = w * (1.0 - ROLL_DAMP * DT)
                val slide = 1.0 - SLIDE_DAMP * DT
                v = V3(v.x * slide, v.y * slide, v.z)
            }
            b.v = v
            b.w = w
            b.p = b.p + b.v * DT
            b.q = b.q.integrate(b.w, DT)
            val still = b.grounded && b.v.length < QUIET_V && b.w.length < QUIET_W && DieFaces.tilt(b.q) < QUIET_TILT
            b.quiet = if (still) b.quiet + DT else 0.0
        }
    }

    /**
     * The table and the four walls, against each corner — the walls reaching [AHEAD] out, so a corner flung at one is
     * met before it crosses (a speculative contact: it may close the gap, and bounces if it would cross this step).
     */
    private fun boundaries(b: Body, out: MutableList<Contact>) {
        CORNERS.forEach { c ->
            val r = b.q.rotate(c)
            val at = b.p + r
            if (at.z < 0.0) {
                b.grounded = true
                out += contact(b, null, r, V3.ZERO, V3.UP, -at.z, TABLE_BOUNCE, TABLE_FRICTION)
            }
            if (at.x < AHEAD) out += contact(b, null, r, V3.ZERO, V3(1.0, 0.0, 0.0), -at.x, WALL_BOUNCE, WALL_FRICTION)
            if (at.x > ARENA_W - AHEAD) out += contact(b, null, r, V3.ZERO, V3(-1.0, 0.0, 0.0), at.x - ARENA_W, WALL_BOUNCE, WALL_FRICTION)
            if (at.y < -INNER + AHEAD) out += contact(b, null, r, V3.ZERO, V3(0.0, 1.0, 0.0), -INNER - at.y, WALL_BOUNCE, WALL_FRICTION)
            if (at.y > ARENA_D - AHEAD) out += contact(b, null, r, V3.ZERO, V3(0.0, -1.0, 0.0), at.y - ARENA_D, WALL_BOUNCE, WALL_FRICTION)
        }
    }

    /** The two dice against each other: corners inside the other cube, and the inscribed spheres. */
    private fun between(a: Body, b: Body, out: MutableList<Contact>) {
        if ((a.p - b.p).length > 1.8) return
        corners(a, b, out)
        corners(b, a, out)
        val d = a.p - b.p
        val dist = d.length
        if (dist < 1.0) {
            val n = if (dist > 1e-9) d * (1.0 / dist) else V3.UP
            val mid = b.p + n * (dist / 2)
            out += contact(a, b, mid - a.p, mid - b.p, n, 1.0 - dist, DICE_BOUNCE, DICE_FRICTION)
        }
    }

    /** [a]'s corners inside [b], each pushed out through [b]'s nearest face. */
    private fun corners(a: Body, b: Body, out: MutableList<Contact>) {
        CORNERS.forEach { c ->
            val ra = a.q.rotate(c)
            val at = a.p + ra
            val local = b.q.unrotate(at - b.p)
            val px = 0.5 - abs(local.x)
            val py = 0.5 - abs(local.y)
            val pz = 0.5 - abs(local.z)
            if (px <= 0 || py <= 0 || pz <= 0) return@forEach
            val (depth, normalLocal) = when {
                px <= py && px <= pz -> px to V3(if (local.x >= 0) 1.0 else -1.0, 0.0, 0.0)
                py <= pz -> py to V3(0.0, if (local.y >= 0) 1.0 else -1.0, 0.0)
                else -> pz to V3(0.0, 0.0, if (local.z >= 0) 1.0 else -1.0)
            }
            out += contact(a, b, ra, at - b.p, b.q.rotate(normalLocal), depth, DICE_BOUNCE, DICE_FRICTION)
        }
    }

    private fun relative(a: Body, b: Body?, ra: V3, rb: V3): V3 {
        val va = a.v + (a.w cross ra)
        val vb = if (b == null) V3.ZERO else b.v + (b.w cross rb)
        return va - vb
    }

    private fun mass(a: Body, b: Body?, ra: V3, rb: V3, d: V3): Double {
        var k = INV_MASS + INV_INERTIA * (ra cross d).let { it dot it }
        if (b != null) k += INV_MASS + INV_INERTIA * (rb cross d).let { it dot it }
        return k
    }

    private fun contact(a: Body, b: Body?, ra: V3, rb: V3, n: V3, depth: Double, bounce: Double, mu: Double): Contact {
        val vn = relative(a, b, ra, rb) dot n
        val bouncing = if (vn < -BOUNCE_FLOOR) -bounce * vn else 0.0
        // Apart by -[depth]: it may close that gap this step and no more — unless it would cross it this step, when it
        // is an impact now and bounces as one. Touching: the bounce, or the push out.
        val arriving = depth < 0 && vn * DT < depth
        val target = when {
            depth >= 0 -> maxOf(bouncing, BIAS / DT * (depth - SLOP).coerceAtLeast(0.0))
            arriving -> maxOf(bouncing, depth / DT)
            else -> depth / DT
        }
        // Two directions along the surface.
        val t1 = (if (abs(n.z) < 0.9) V3.UP else V3(1.0, 0.0, 0.0)).cross(n).normalized()
        val t2 = n cross t1
        return Contact(a, b, ra, rb, n, t1, t2, target, if (depth < 0 && !arriving) 0.0 else mu, mass(a, b, ra, rb, n), mass(a, b, ra, rb, t1), mass(a, b, ra, rb, t2))
    }

    private fun apply(c: Contact, j: V3) {
        c.a.v = c.a.v + j * INV_MASS
        c.a.w = c.a.w + (c.ra cross j) * INV_INERTIA
        val b = c.b ?: return
        b.v = b.v - j * INV_MASS
        b.w = b.w - (c.rb cross j) * INV_INERTIA
    }

    private fun solve(c: Contact) {
        // Along the normal: never pulling, at least the bounce or the push out.
        val vn = relative(c.a, c.b, c.ra, c.rb) dot c.n
        val old = c.jn
        c.jn = (old + (c.target - vn) / c.kn).coerceAtLeast(0.0)
        val dn = c.jn - old
        if (dn != 0.0) apply(c, c.n * dn)
        // Along the surface: friction, bounded by the normal impulse.
        val limit = c.mu * c.jn
        val v1 = relative(c.a, c.b, c.ra, c.rb) dot c.t1
        val o1 = c.j1
        c.j1 = (o1 - v1 / c.k1).coerceIn(-limit, limit)
        if (c.j1 != o1) apply(c, c.t1 * (c.j1 - o1))
        val v2 = relative(c.a, c.b, c.ra, c.rb) dot c.t2
        val o2 = c.j2
        c.j2 = (o2 - v2 / c.k2).coerceIn(-limit, limit)
        if (c.j2 != o2) apply(c, c.t2 * (c.j2 - o2))
    }

    /** [pose] set down exactly on its nearest face, keeping which way it faces. */
    fun flatten(pose: Pose): Pose {
        val up = DieFaces.upFace(pose.q)
        val turn = Quat.between(pose.q.rotate(DieFaces.NORMALS[up]).normalized(), V3.UP)
        val q = (turn * pose.q).normalized()
        return Pose(V3(pose.p.x, pose.p.y, 0.5), q)
    }

    /** A flat die's footprint: its centre and two half-edge directions along the table. */
    fun footprint(pose: Pose): Triple<V3, V3, V3> {
        val up = DieFaces.upFace(pose.q)
        val (u, v) = DieFaces.axes(up)
        val hu = pose.q.rotate(u) * 0.5
        val hv = pose.q.rotate(v) * 0.5
        return Triple(V3(pose.p.x, pose.p.y, 0.0), V3(hu.x, hu.y, 0.0), V3(hv.x, hv.y, 0.0))
    }

    /**
     * How deep two flat dice's footprints overlap, and along which direction to part them (separating axes); null
     * when they do not.
     */
    fun overlap(a: Pose, b: Pose): Pair<Double, V3>? {
        val (ca, ua, va) = footprint(a)
        val (cb, ub, vb) = footprint(b)
        var best = Double.MAX_VALUE
        var axis = V3.ZERO
        for (raw in listOf(ua, va, ub, vb)) {
            val n = raw.normalized()
            val ha = abs(ua dot n) + abs(va dot n)
            val hb = abs(ub dot n) + abs(vb dot n)
            val d = (cb - ca) dot n
            val o = ha + hb - abs(d)
            if (o <= 0) return null
            if (o < best) { best = o; axis = if (d >= 0) n else -n }
        }
        return best to axis
    }

    /** Flat dice moved apart (and inside the walls) until neither footprint overlaps the other. */
    private fun separate(poses: List<Pose>): List<Pose> {
        var ps = poses.map { inside(it) }
        if (ps.size != 2) return ps
        repeat(12) {
            val (depth, axis) = overlap(ps[0], ps[1]) ?: return ps
            val half = axis * ((depth + 0.02) / 2)
            ps = listOf(inside(ps[0].copy(p = ps[0].p - half)), inside(ps[1].copy(p = ps[1].p + half)))
        }
        // Against a wall both may be pushed back together: part them along the wall instead.
        val dx = if (ps[0].p.x <= ps[1].p.x) -1.0 else 1.0
        return listOf(inside(ps[0].copy(p = ps[0].p + V3(dx * 0.8, 0.0, 0.0))), inside(ps[1].copy(p = ps[1].p - V3(dx * 0.8, 0.0, 0.0))))
    }

    private fun inside(pose: Pose): Pose {
        val (_, u, v) = footprint(pose)
        val hx = abs(u.x) + abs(v.x)
        val hy = abs(u.y) + abs(v.y)
        return pose.copy(p = V3(pose.p.x.coerceIn(hx, ARENA_W - hx), pose.p.y.coerceIn(-INNER + hy, ARENA_D - hy), 0.5))
    }
}
