package com.kaiharimoto.mastertool.core.ai.chessy.gifts

import com.kaiharimoto.mastertool.core.ai.chessy.toys.PetRoom
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyEvent
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyHit
import com.kaiharimoto.mastertool.core.ai.chessy.toys.Yarn
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A gift as a body in her room (kai: "these gifts are 3D and can be taken out and played with"): its solid ([model],
 * [size] pixels across), where it is and how it turns. Thrown, it flies and tumbles, bounces off the walls and the floor
 * (the lowest of its turned corners is what touches), slides to a stop and settles into the way it rests facing the
 * person ([rest]): the card and the polaroid leaning back, the cupcake tipped to show its frosting. Held, it hangs from the
 * hand, swinging with the way the hand goes. [item] is null for the box she makes and its lid.
 */
class GiftBody(val item: GiftItem?, val model: GiftModel, var size: Float, val rest: Quat = Quat.IDENTITY) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var q: Quat = rest
    var w: V3 = V3.ZERO
    var held = false

    /** Seconds since it came into the room. */
    var age = 0f
        private set

    private var handX = Float.NaN
    private var handVx = 0f
    private var sway = 0.0
    private var cool = 0f

    fun place(px: Float, py: Float) { x = px; y = py; vx = 0f; vy = 0f; w = V3.ZERO }

    /** How far below its middle its lowest turned corner reaches, pixels. */
    fun lowest(): Float = model.corners.maxOf { q.rotate(it).y }.toFloat() * size

    /** How far above its middle its highest turned corner reaches, pixels (negative: above). */
    fun highest(): Float = model.corners.minOf { q.rotate(it).y }.toFloat() * size

    /** Half its width as turned now, pixels. */
    fun halfWidth(): Float = model.corners.maxOf { abs(q.rotate(it).x) }.toFloat() * size

    /** Whether a press at ([px], [py]) lands on it: inside its turned box, a little generous. */
    fun hit(px: Float, py: Float): Boolean {
        val slack = size * .08f
        return abs(px - x) < halfWidth() + slack && py > y + highest() - slack && py < y + lowest() + slack
    }

    val moving: Boolean get() = held || abs(vx) > 2f || abs(vy) > 2f || w.length > .03 || age < SETTLE

    fun onFloor(room: PetRoom): Boolean = y + lowest() >= room.floor - 1f * room.unit

    /** Thrown from the hand at ([tvx], [tvy]) pixels a second, given a tumble to match. */
    fun release(tvx: Float, tvy: Float, room: PetRoom, random: Random) {
        held = false
        handX = Float.NaN
        val cap = Yarn.THROW_CAP * room.unit
        val s = hypot(tvx, tvy)
        val k = if (s > cap) cap / s else 1f
        vx = tvx * k
        vy = tvy * k
        val r = size * .5
        w = V3(vy / r * .5 + random.nextDouble(-1.5, 1.5), -vx / r * .4 + random.nextDouble(-1.5, 1.5), vx / r * .6)
    }

    fun step(dt: Float, room: PetRoom, random: Random, events: MutableList<ToyEvent>) {
        val u = room.unit
        age += dt
        cool = max(0f, cool - dt)
        sway += dt
        if (held) {
            // hanging from the hand: it swings toward the way the hand goes, and comes back to rest facing you
            if (!handX.isNaN() && dt > 0f) handVx += ((x - handX) / dt - handVx) * min(1f, dt * 12f)
            handX = x
            val lean = (handVx / u * .0006).coerceIn(-.6, .6)
            val target = Quat(cos(lean / 2), 0.0, 0.0, sin(lean / 2)) * rest
            q = Quat.nlerp(q, target, 1.0 - exp(-dt * 10.0))
            w = V3.ZERO
            return
        }
        vy += Yarn.GRAVITY * u * dt
        x += vx * dt
        y += vy * dt
        val hw = halfWidth()
        if (x < room.left + hw) { x = room.left + hw; if (vx < 0f) { bounced(-vx, u, events); vx = -vx * .45f } }
        if (x > room.right - hw) { x = room.right - hw; if (vx > 0f) { bounced(vx, u, events); vx = -vx * .45f } }
        val top = highest()
        if (y + top < room.top) { y = room.top - top; if (vy < 0f) vy = -vy * .4f }
        val low = lowest()
        if (y + low > room.floor) {
            y = room.floor - low
            if (vy > 0f) {
                bounced(vy, u, events)
                if (vy > 160f * u) {
                    vy = -vy * .32f
                    w = V3(w.x * .6, w.y * .6, w.z * .6 + vx / (size * .5) * .3)
                } else vy = 0f
            }
        }
        if (onFloor(room) && abs(vy) < 1f) {
            vx *= exp(-5f * dt)
            if (abs(vx) < 2f * u) vx = 0f
            w = w * exp(-7.0 * dt)
            // settling into the way it rests, with a breath of sway
            val s = sin(sway * 1.3) * .03
            val target = Quat(cos(s / 2), 0.0, sin(s / 2), 0.0) * rest
            q = Quat.nlerp(q, target, 1.0 - exp(-dt * 4.0))
        } else {
            w = w * exp(-.4 * dt)
        }
        q = q.integrate(w, dt.toDouble())
        // turning can swing a corner below the floor: it stands back on it
        val after = lowest()
        if (y + after > room.floor) y = room.floor - after
    }

    private fun bounced(speed: Float, u: Float, events: MutableList<ToyEvent>) {
        if (speed < 200f * u || cool > 0f) return
        cool = .08f
        events += ToyEvent(null, ToyHit.BOUNCE, x, y, (speed / (2200f * u)).coerceIn(.1f, .8f))
    }

    companion object {
        /** A just-made thing counts as moving this long, so the room's clock runs while it lands. */
        const val SETTLE = 1.5f

        /** About the x axis by [deg] degrees (a lean back), then about y by [yaw] (turned a little), as a gift rests. */
        fun pose(deg: Double, yaw: Double = 0.0, roll: Double = 0.0): Quat {
            val a = deg * PI / 180 / 2
            val b = yaw * PI / 180 / 2
            val c = roll * PI / 180 / 2
            return Quat(cos(b), 0.0, sin(b), 0.0) * Quat(cos(a), sin(a), 0.0, 0.0) * Quat(cos(c), 0.0, 0.0, sin(c))
        }

        /** How each kind of gift rests facing the person. */
        fun restOf(kind: GiftKind): Quat = when (kind) {
            GiftKind.CARD -> pose(12.0, 14.0)
            GiftKind.PHOTO -> pose(12.0, -12.0, 3.0)
            GiftKind.NOTE -> pose(6.0, 18.0)
            GiftKind.HEART -> pose(0.0, 0.0)
            GiftKind.CUPCAKE -> pose(-20.0, 10.0)
        }

        /** How the box she makes rests: tipped to show its lid, turned to show two sides. */
        val BOX_REST: Quat = pose(-16.0, 32.0)

        /** How the chest stands at the end of the room. */
        val CHEST_REST: Quat = pose(-10.0, -28.0)
    }
}

/**
 * Where the chest stands at the end of her room (the layout's: its middle [x] and its [w] and [h] in pixels, its foot on
 * the floor), and how far its top drawer is out ([drawer], 0 to 1): open while a gift is held over it, and as one goes in.
 */
class Chest {
    var x = 0f
    var w = 0f
    var h = 0f
    var drawer = 0f
        private set
    private var want = 0f
    private var openFor = 0f

    fun over(px: Float, py: Float, room: PetRoom): Boolean =
        w > 0f && abs(px - x) < w * .62f && py > room.floor - h * 1.6f && py < room.floor + h * .1f

    /** A gift just went in: the drawer stays out a moment. */
    fun took() { openFor = .7f }

    fun step(dt: Float, hovering: Boolean) {
        openFor = max(0f, openFor - dt)
        want = if (hovering || openFor > 0f) 1f else 0f
        drawer += (want - drawer) * (1f - exp(-dt * 9f))
        if (abs(want - drawer) < .002f) drawer = want
    }

    val moving: Boolean get() = abs(want - drawer) > 0f || openFor > 0f
}
