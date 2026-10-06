package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The mouth a frame shows: the face's own, Chessy's closed smile, or her open mouth (kai chose Flap). */
enum class ChessyMouth { OWN, CLOSED, OPEN }

/** The hair, bows, bell and tongue's swing groups, each a damped pendulum. */
enum class SwingGroup(val k: Float, val c: Float, val g: Float, val maxX: Float, val maxY: Float, val angle: Boolean = false) {
    SIDE(60f, 9f, 26f, 40f, 20f),
    CURL(38f, 6f, 34f, 40f, 20f),
    BACK(50f, 8f, 18f, 40f, 20f),
    BOW(90f, 9f, 7f, 6f, 4f),
    BELL(30f, 4.5f, .5f, .35f, 0f, angle = true),
    TONGUE(55f, 3.5f, .5f, .2f, 0f, angle = true),
}

/**
 * One frame of Chessy (written in place each step, nothing allocated): where her head is turned, its tilt, her
 * breath, each swing group's offset (sheet px, or an angle for the bell and tongue), the ears' twitch, a blink and
 * the mouth.
 */
class ChessyFrame {
    /** The head's turn: yaw and pitch in radians, roll in radians. */
    var yaw = 0f
    var pitch = 0f
    var roll = 0f

    /** The breath's rise in sheet px (each layer takes its share). */
    var bob = 0f
    val swingX = FloatArray(SwingGroup.entries.size)
    val swingY = FloatArray(SwingGroup.entries.size)
    var earL = 0f
    var earR = 0f
    var blink = false
    var mouth = ChessyMouth.OWN

    /** How far the swinging layers are from rest: past a pixel or two, layers under them show their filled rims. */
    var rimMix = 0f

    /** Whether anything is still moving: the frame loop sleeps when this goes false. */
    var moving = false
}

/**
 * Chessy's motion (the mockup's rig, kai-approved): the head eases to where she looks (or drifts when nobody is
 * there); hair, bows, bell and tongue follow as damped pendulums driven by the head's motion; she breathes, blinks
 * (a swap for 130 ms, sometimes twice), twitches an ear now and then, and talks in Flap — her closed smile and open
 * mouth cut to a speech rhythm, with a nod on the stressed beats. [still] (reduced motion) holds every value at rest
 * but the blink and the mouth.
 */
class ChessyRig(seed: Int = 1, private val still: Boolean = false, private val depth: Float = 1f) {
    val frame = ChessyFrame()
    private val random = Random(seed)
    private var tx = 0f
    private var ty = 0f
    private var nod = 0f
    private val vx = FloatArray(SwingGroup.entries.size)
    private val vy = FloatArray(SwingGroup.entries.size)
    private var clock = 0f
    private var nextBlink = 1500f
    private var blinkAt = -1f
    private var double = false
    private var nextEar = 3000f
    private var earAt = -1f
    private var earSide = 0
    private var talkAt = -1f

    /**
     * Advance [dtMs]. [aimX], [aimY] are where she looks, -1 to 1 across her (null: nobody near, she drifts); [talking]
     * starts and stops her speech; [blinks] off holds her eyes open (a face with its eyes shut has no blink).
     */
    fun step(dtMs: Float, aimX: Float?, aimY: Float?, talking: Boolean, blinks: Boolean = true): ChessyFrame {
        val dt = dtMs.coerceIn(0f, 64f)
        clock += dt
        val t = clock / 1000f
        val idle = aimX == null || aimY == null
        val gx = if (still) 0f else if (idle) (sin(t * .37f) * .32f + sin(t * .91f) * .12f) else aimX!!.coerceIn(-1f, 1f)
        val gy = if (still) 0f else if (idle) sin(t * .29f + 1f) * .18f else aimY!!.coerceIn(-1f, 1f)
        val e = 1f - exp(-dt / 140f)
        val px = tx
        val py = ty
        tx += (gx - tx) * e
        ty += (gy - ty) * e
        val sec = max(dt / 1000f, 1e-3f)
        val hvx = (tx - px) / sec
        val hvy = (ty - py) / sec
        val f = frame
        f.bob = if (still) 0f else sin(t * PI.toFloat() * 2f / 4f) * 2.2f
        // each group: pulled to rest, pushed against the head's motion (lag) and by its turn (it hangs)
        val h = min(dt, 40f) / 1000f
        var swing = 0f
        for (g in SwingGroup.entries) {
            val i = g.ordinal
            if (still) { f.swingX[i] = 0f; f.swingY[i] = 0f; vx[i] = 0f; vy[i] = 0f; continue }
            val targetX = when (g) {
                SwingGroup.BELL -> -tx * .12f
                SwingGroup.TONGUE -> -tx * .06f - hvx * .05f + sin(t * 2.3f) * .025f
                else -> -hvx * g.g * .35f + tx * g.g * .25f
            }
            val targetY = if (g.angle) 0f else -hvy * g.g * .2f + f.bob * .4f
            vx[i] += (-g.k * (f.swingX[i] - targetX) - g.c * vx[i]) * h
            vy[i] += (-g.k * (f.swingY[i] - targetY) - g.c * vy[i]) * h
            f.swingX[i] = (f.swingX[i] + vx[i] * h).coerceIn(-g.maxX, g.maxX)
            f.swingY[i] = if (g.angle) 0f else (f.swingY[i] + vy[i] * h).coerceIn(-g.maxY, g.maxY)
            swing = max(swing, if (g == SwingGroup.BELL) abs(f.swingX[i]) * 90f else if (g.angle) 0f else kotlin.math.hypot(f.swingX[i], f.swingY[i]))
        }
        f.rimMix = ((swing - .5f) / 2.5f).coerceIn(0f, 1f)
        // the blink: the shut eyes swapped in for 130 ms, now and then twice
        if (blinks && clock > nextBlink && blinkAt < 0) { blinkAt = clock; double = random.nextFloat() < .2f }
        if (blinkAt >= 0) {
            val b = clock - blinkAt
            f.blink = b < 130f
            if (b >= 130f) { blinkAt = -1f; nextBlink = clock + if (double) 180f else 2000f + random.nextFloat() * 4000f; double = false }
        } else f.blink = false
        // the ears: a twitch every few seconds
        if (!still && clock > nextEar && earAt < 0) { earAt = clock; earSide = if (random.nextFloat() < .5f) 0 else 1 }
        if (earAt >= 0) {
            val u = (clock - earAt) / 260f
            val a = if (u < 1f) sin(u * PI.toFloat()) * .1f else 0f
            f.earL = if (earSide == 0) a else 0f
            f.earR = if (earSide == 1) a else 0f
            if (u >= 1f) { earAt = -1f; nextEar = clock + 3000f + random.nextFloat() * 4000f }
        } else { f.earL = 0f; f.earR = 0f }
        // talking: Flap on the speech rhythm, a nod on the stressed beats
        if (talking) {
            if (talkAt < 0) talkAt = clock
            val sp = Speech.at(clock - talkAt)
            f.mouth = if (sp.open > .38f) ChessyMouth.OPEN else ChessyMouth.CLOSED
            val target = if (still) 0f else (if (sp.stress) .05f * sin(PI.toFloat() * min(1f, sp.u * 1.4f)) else 0f) + sp.open * .012f
            nod += (target - nod) * (1f - exp(-dt / 70f))
        } else {
            talkAt = -1f
            f.mouth = ChessyMouth.OWN
            nod *= .85f
        }
        f.yaw = tx * YAW * depth
        f.pitch = -(ty + nod) * PITCH * depth
        f.roll = -tx * ROLL * depth
        f.moving = talking || f.blink || earAt >= 0 || abs(gx - tx) > 1e-3f || abs(gy - ty) > 1e-3f || swing > .05f || abs(nod) > 1e-3f || (!still)
        return f
    }

    companion object {
        val YAW = (18 * PI / 180).toFloat()
        val PITCH = (10 * PI / 180).toFloat()
        val ROLL = (4 * PI / 180).toFloat()
    }
}

/**
 * One speech rhythm (the mockup's, from a fixed seed, a twelve-second loop): phrases of three to eight syllables,
 * the first and now and then another stressed (longer, wider); between syllables the lips part a little, or meet.
 */
object Speech {
    class At(val open: Float, val stress: Boolean, val u: Float)

    private class Syllable(val t0: Float, val d: Float, val peak: Float, val stress: Boolean, val close: Boolean)

    private val syllables: List<Syllable>
    val length: Float

    init {
        val r = mulberry32(11)
        val out = ArrayList<Syllable>()
        var t = 0f
        while (t < 12000f) {
            val n = 3 + (r() * 6).toInt()
            for (i in 0 until n) {
                val stress = i == 0 || r() < .25
                val d = ((130 + r() * 110) * (if (stress) 1.3 else 1.0)).toFloat()
                out += Syllable(t, d, (if (stress) .85 + r() * .15 else .4 + r() * .45).toFloat(), stress, r() < .55)
                t += d
            }
            t += (280 + r() * 480).toFloat()
        }
        syllables = out
        length = t
    }

    fun at(ms: Float): At {
        val tt = ((ms % length) + length) % length
        for (s in syllables) if (tt >= s.t0 && tt < s.t0 + s.d) {
            val u = (tt - s.t0) / s.d
            val floor = if (s.close) 0f else .15f
            return At(floor + (s.peak - floor) * sin(PI.toFloat() * u).toDouble().pow(1.1).toFloat(), s.stress, u)
        }
        return At(0f, false, 0f)
    }

    /** The mockup's generator, bit for bit, so the rhythm is the one kai approved. */
    internal fun mulberry32(seed: Int): () -> Double {
        var a = seed
        return {
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }
}
