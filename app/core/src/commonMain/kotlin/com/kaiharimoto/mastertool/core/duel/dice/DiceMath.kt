package com.kaiharimoto.mastertool.core.duel.dice

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * A point or a direction in the dice's world (1.0.87, the opening roll): x across the seat's field, y from its far
 * edge toward its player, z up off the table; one unit is a die's edge.
 *
 * Only `+`, `−`, `×`, `÷` and `sqrt` — every one correctly rounded on every JVM and on Android — so the same throw
 * simulates to the same frames on the host and on the guest. No `sin` or `cos` in anything the simulation runs.
 */
@Serializable
data class V3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    operator fun unaryMinus() = V3(-x, -y, -z)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(this dot this)
    fun normalized(): V3 = length.let { if (it < 1e-12) this else this * (1.0 / it) }

    /** Rounded to [places] decimals, so a throw written to the log and read back is the same throw. */
    fun rounded(places: Int = 4): V3 = V3(round(x, places), round(y, places), round(z, places))

    companion object {
        val ZERO = V3()
        val UP = V3(0.0, 0.0, 1.0)

        internal fun round(v: Double, places: Int): Double {
            var k = 1.0
            repeat(places) { k *= 10.0 }
            val n = v * k
            val r = if (n >= 0) kotlin.math.floor(n + 0.5) else -kotlin.math.floor(-n + 0.5)
            return r / k
        }
    }
}

/** An orientation: a unit quaternion, body to world. */
@Serializable
data class Quat(val w: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-12) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    /** [v] turned from the body's frame into the world's. */
    fun rotate(v: V3): V3 {
        // v + 2w(q×v) + 2 q×(q×v), q the vector part.
        val q = V3(x, y, z)
        val t = (q cross v) * 2.0
        return v + t * w + (q cross t)
    }

    /** [v] turned from the world's frame into the body's. */
    fun unrotate(v: V3): V3 = conjugate().rotate(v)

    fun conjugate() = Quat(w, -x, -y, -z)

    /** One step of spin [omega] (world frame, radians a second) for [dt] seconds, renormalised. */
    fun integrate(omega: V3, dt: Double): Quat {
        val h = dt * 0.5
        val dq = Quat(0.0, omega.x, omega.y, omega.z) * this
        return Quat(w + dq.w * h, x + dq.x * h, y + dq.y * h, z + dq.z * h).normalized()
    }

    fun rounded(places: Int = 5): Quat =
        Quat(V3.round(w, places), V3.round(x, places), V3.round(y, places), V3.round(z, places)).normalized()

    companion object {
        val IDENTITY = Quat()

        /** The shortest turn taking direction [from] onto direction [to] (both unit). */
        fun between(from: V3, to: V3): Quat {
            val d = from dot to
            if (d < -0.999999) {
                // Half a turn about any axis square to [from].
                val axis = (if (kotlin.math.abs(from.x) < 0.9) V3(1.0, 0.0, 0.0) else V3(0.0, 1.0, 0.0)).cross(from).normalized()
                return Quat(0.0, axis.x, axis.y, axis.z)
            }
            val c = from cross to
            return Quat(1.0 + d, c.x, c.y, c.z).normalized()
        }

        /** Normalised linear blend: close enough between two frames a sixtieth of a second apart. */
        fun nlerp(a: Quat, b: Quat, t: Double): Quat {
            val s = if (a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z < 0) -1.0 else 1.0
            return Quat(
                a.w + (b.w * s - a.w) * t,
                a.x + (b.x * s - a.x) * t,
                a.y + (b.y * s - a.y) * t,
                a.z + (b.z * s - a.z) * t,
            ).normalized()
        }
    }
}
