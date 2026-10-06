package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The mouth a frame shows: the face's own, Chessy's closed smile, or her open mouth (kai chose Flap). */
enum class ChessyMouth { OWN, CLOSED, OPEN }

/**
 * The hair, bows, bell, tongue and ears' swing groups, each a damped pendulum: [k] its stiffness, [c] its damping,
 * [g] how far the head's motion throws it. [BOW] is the ribbons' second stage, riding [SIDE] and thrown by its swing
 * (follow-through, the rig red team's A4); the ears are short stiff pendulums, an angle each.
 */
enum class SwingGroup(val k: Float, val c: Float, val g: Float, val maxX: Float, val maxY: Float, val angle: Boolean = false) {
    SIDE(60f, 9f, 26f, 40f, 20f),
    CURL(38f, 6f, 34f, 40f, 20f),
    BACK(50f, 8f, 18f, 40f, 20f),
    BOW(90f, 7f, 7f, 10f, 6f),
    BELL(30f, 4.5f, .5f, .35f, 0f, angle = true),
    TONGUE(55f, 3.5f, .5f, .2f, 0f, angle = true),
    EAR_L(220f, 12f, .5f, .35f, 0f, angle = true),
    EAR_R(220f, 12f, .5f, .35f, 0f, angle = true),
}

/**
 * One frame of Chessy (written in place each step, nothing allocated): where her head is turned, its tilt, her
 * breath, each swing group's offset (sheet px, or an angle for the bell, tongue and ears), the ears' turn, a blink and
 * the mouth.
 */
class ChessyFrame {
    /** The head's turn: yaw and pitch in radians, roll in radians. Their sines and cosines are kept with them. */
    var yaw = 0f
        set(v) { field = v; cosYaw = cos(v); sinYaw = sin(v) }
    var pitch = 0f
        set(v) { field = v; cosPitch = cos(v); sinPitch = sin(v) }
    var roll = 0f

    /**
     * cos and sin of [yaw] and [pitch], set with them (the performance pass of the rig red team): the warp read them for
     * every vertex of every picture, the same four numbers each time.
     */
    var cosYaw = 1f
        private set
    var sinYaw = 0f
        private set
    var cosPitch = 1f
        private set
    var sinPitch = 0f
        private set

    /** The breath's rise in sheet px (each layer takes its share). */
    var bob = 0f
    val swingX = FloatArray(SwingGroup.entries.size)
    val swingY = FloatArray(SwingGroup.entries.size)

    /** Each ear's turn in radians (her left's sign mirrored, so the same value turns both outward). */
    var earL = 0f
    var earR = 0f
    var blink = false

    /** How shut the blink's lid is drawn, 0 to 1: it snaps shut and opens more slowly, as eyelids do. */
    var lidAlpha = 0f
    var mouth = ChessyMouth.OWN

    /** How far the swinging layers are from rest: past a pixel or two, layers under them show their filled rims. */
    var rimMix = 0f

    /**
     * Whether something quick is happening (a blink, an ear, talking, a swing, her head catching up with a look):
     * every frame is drawn. Otherwise only her drift and breath move, slow enough to step a few times less often
     * (the performance pass, kai: "my hardware was lagging quite badly when chessy was live").
     */
    var lively = false
}

/**
 * Chessy's motion (the mockup's rig, kai-approved, with the rig red team's changes, `docs/chessy/RIG-REDTEAM.md`):
 *
 * - her head follows where she looks on a second-order spring that settles with the faintest overshoot; with nobody
 *   there it drifts on sines of unrelated lengths (phases her own, so two of her never move together) and now and then
 *   glances somewhere new and holds it;
 * - hair, ribbons, bell, tongue and ears follow as damped pendulums thrown by her head's motion and her body's (a hop);
 * - she breathes (a quick in, a slow out, at her mood's pace), blinks on a log-normal clock as people do (more while
 *   talking, less while reading), each blink snapped shut and opened more slowly, now and then twice;
 * - an ear twitches now and then, a flick that settles;
 * - she talks in Flap, her closed smile and open mouth cut to the rhythm of the words she is saying ([SpeechText]), or
 *   to the mockup's own rhythm when there are none, with a nod on the stressed beats.
 *
 * Everything is stepped in pieces of at most [PIECE] ms, so a long calm step moves her as the same frames would have.
 * [still] (reduced motion) holds every value at rest but the blink and the mouth.
 */
class ChessyRig(seed: Int = 1, private val still: Boolean = false, private val depth: Float = 1f) {
    val frame = ChessyFrame()
    private val random = Random(seed)

    /** Her blinks' and ears' own dice, apart from the glances', so stepping her finer or coarser never reorders a draw. */
    private val blinkDice = Random(seed * 31 + 1)
    private val earDice = Random(seed * 31 + 2)
    private var tx = 0f
    private var ty = 0f
    private var tvx = 0f
    private var tvy = 0f
    private var nod = 0f
    private val vx = FloatArray(SwingGroup.entries.size)
    private val vy = FloatArray(SwingGroup.entries.size)
    private var clock = 0f
    private var nextBlink = 1000f + blinkDice.nextFloat() * 2500f
    private var blinkAt = -1f
    private var blinkLong = BLINK_MIN
    private var double = false
    private var nextEar = 2000f + earDice.nextFloat() * 4000f
    private var talkAt = -1f

    /** Her own phases for the idle drift's sines (yaw, yaw's ripple, pitch, roll). */
    private val phase = FloatArray(4) { random.nextFloat() * 2f * PI.toFloat() }

    init {
        // she appears already looking where her drift is, rather than turning to it as she arrives
        if (!still) {
            tx = sin(phase[0]) * .26f + sin(phase[1]) * .1f
            ty = sin(phase[2]) * .16f
        }
    }

    /** Where an idle glance is taking her look (from, to, when it began), and when the next comes. */
    private var glanceFromX = 0f
    private var glanceFromY = 0f
    private var glanceX = 0f
    private var glanceY = 0f
    private var glanceAt = -1e9f
    private var nextGlance = 2000f + random.nextFloat() * 3000f

    /** The breath's place in its cycle (0 to 1, carried across a change of pace) and its eased depth. */
    private var breath = random.nextFloat()
    private var breathDepth = 1f

    /** Her body's place (sheet px) last step, for its velocity, and anything carrying her besides ([carry]). */
    private var bodyX = Float.NaN
    private var bodyY = 0f
    private var carryX = 0f
    private var carryY = 0f

    /** An ear twitches now: −1 her left, 1 her right (a hand on it, in the petting mode). A flick, which settles. */
    fun twitch(side: Int) {
        if (still) return
        if (side < 0) vx[SwingGroup.EAR_L.ordinal] += EAR_FLICK else vx[SwingGroup.EAR_R.ordinal] += EAR_FLICK
    }

    /** Her bell swings as if flicked, by [strength] (1 a good flick), her tongue with it. */
    fun ring(strength: Float = 1f) {
        if (still) return
        vx[SwingGroup.BELL.ordinal] += .9f * strength
        vx[SwingGroup.TONGUE.ordinal] += .4f * strength
    }

    /**
     * Something besides her own body language moves her whole figure ([x], [y] in sheet px; the pet room's hops and
     * walks): her hair and bell feel it as they feel a hop.
     */
    fun carry(x: Float, y: Float) {
        carryX = x
        carryY = y
    }

    /**
     * Advance [dtMs]. [aimX], [aimY] are where she looks, -1 to 1 across her (null: nobody near, or a mood that looks
     * inward; she drifts about [restX], [restY]); [talking] starts and stops her speech, and [words], when given, is
     * what she is saying; [blinks] off holds her eyes open (a face with its eyes shut has no blink) and [blinkRate]
     * scales how often she blinks; [breathPeriod] (seconds) and [breathDepth] are her mood's breath; [bodyX], [bodyY]
     * are where her body language has put her head (sheet px), so her hair feels a hop.
     */
    fun step(
        dtMs: Float,
        aimX: Float?,
        aimY: Float?,
        talking: Boolean,
        blinks: Boolean = true,
        blinkRate: Float = 1f,
        restX: Float = 0f,
        restY: Float = 0f,
        breathPeriod: Float = 4f,
        breathDepth: Float = 1f,
        bodyX: Float = 0f,
        bodyY: Float = 0f,
        words: SpeechText? = null,
    ): ChessyFrame {
        // up to a calm step's length (the performance pass steps her less often while she is calm), never a pause's
        val dt = dtMs.coerceIn(0f, MAX_STEP)
        val f = frame
        val idle = aimX == null || aimY == null
        // her body's velocity over this step (sheet px a second), the same through each of its pieces
        val bx = bodyX + carryX * CARRY_SHARE
        val by = bodyY + carryY * CARRY_SHARE
        val bvx: Float
        val bvy: Float
        if (bodyX.isNaN() || this.bodyX.isNaN() || dt <= 0f || still) { bvx = 0f; bvy = 0f } else {
            bvx = (bx - this.bodyX) / (dt / 1000f)
            bvy = (by - this.bodyY) / (dt / 1000f)
        }
        this.bodyX = bx
        this.bodyY = by
        if (!talking) talkAt = -1f
        val pieces = max(1, ceil(dt / PIECE).toInt())
        val h = dt / pieces
        var gx = 0f
        var gy = 0f
        var swing = 0f
        var sway = 0f
        repeat(pieces) {
            clock += h
            val t = clock / 1000f
            val s = h / 1000f
            // where she is looking: the pointer, or her drift and the glance she holds
            if (!still && idle && clock > nextGlance) {
                val g = glide()
                glanceFromX = glanceFromX + (glanceX - glanceFromX) * g
                glanceFromY = glanceFromY + (glanceY - glanceFromY) * g
                glanceX = (random.nextFloat() * 2f - 1f) * GLANCE_X
                glanceY = (random.nextFloat() * 2f - 1f) * GLANCE_Y
                glanceAt = clock
                nextGlance = clock + GLANCE_MIN + random.nextFloat() * GLANCE_SPAN
            }
            val g = glide()
            val lookX = glanceFromX + (glanceX - glanceFromX) * g
            val lookY = glanceFromY + (glanceY - glanceFromY) * g
            gx = if (still) 0f else if (idle) restX + lookX + sin(t * .37f + phase[0]) * .26f + sin(t * .91f + phase[1]) * .1f else aimX!!.coerceIn(-1f, 1f)
            gy = if (still) 0f else if (idle) restY + lookY + sin(t * .29f + phase[2]) * .16f else aimY!!.coerceIn(-1f, 1f)
            // the head: a spring a touch under critical damping, so a look lands and settles rather than easing in
            tvx += (HEAD_W * HEAD_W * (gx - tx) - 2f * HEAD_Z * HEAD_W * tvx) * s
            tvy += (HEAD_W * HEAD_W * (gy - ty) - 2f * HEAD_Z * HEAD_W * tvy) * s
            tx += tvx * s
            ty += tvy * s
            // the breath: in quickly, out slowly, at the mood's pace and depth (eased, so a new mood never jumps it)
            breath = (breath + s / breathPeriod.coerceIn(1.5f, 12f)) % 1f
            this.breathDepth += (breathDepth - this.breathDepth) * (1f - exp(-s / .6f))
            f.bob = if (still) 0f else breathCurve(breath) * BREATH * this.breathDepth
            // each group: pulled to rest, pushed against the head's and the body's motion (lag) and by the turn (it hangs)
            swing = 0f
            sway = 0f
            for (g in SwingGroup.entries) {
                val i = g.ordinal
                if (still) { f.swingX[i] = 0f; f.swingY[i] = 0f; vx[i] = 0f; vy[i] = 0f; continue }
                val targetX = when (g) {
                    SwingGroup.BELL -> -tx * .12f - bvx * BODY_ANGLE
                    SwingGroup.TONGUE -> -tx * .06f - tvx * .05f + sin(t * 2.3f) * .025f
                    // the ribbons ride the side lock: its swing throws them, their own small pendulum on top
                    SwingGroup.BOW -> -vx[SwingGroup.SIDE.ordinal] * BOW_THROW
                    // the ears lag a turn in mirror (her left's sign is turned), and flop a little with a hop
                    SwingGroup.EAR_L -> tvx * EAR_LAG + bvy * EAR_HOP
                    SwingGroup.EAR_R -> -tvx * EAR_LAG + bvy * EAR_HOP
                    else -> -tvx * g.g * .35f + tx * g.g * .25f - bvx * BODY_LAG * g.g
                }
                val targetY = if (g.angle || g == SwingGroup.BOW) (if (g == SwingGroup.BOW) -vy[SwingGroup.SIDE.ordinal] * BOW_THROW else 0f)
                else -tvy * g.g * .2f + f.bob * .4f - bvy * BODY_LAG * g.g
                vx[i] += (-g.k * (f.swingX[i] - targetX) - g.c * vx[i]) * s
                vy[i] += (-g.k * (f.swingY[i] - targetY) - g.c * vy[i]) * s
                f.swingX[i] = f.swingX[i] + vx[i] * s
                f.swingY[i] = if (g.angle) 0f else f.swingY[i] + vy[i] * s
                // at its limit it stops there: velocity still pushing outward is spent, or it would cling to the limit
                if (f.swingX[i] > g.maxX) { f.swingX[i] = g.maxX; if (vx[i] > 0f) vx[i] = 0f }
                if (f.swingX[i] < -g.maxX) { f.swingX[i] = -g.maxX; if (vx[i] < 0f) vx[i] = 0f }
                if (!g.angle) {
                    if (f.swingY[i] > g.maxY) { f.swingY[i] = g.maxY; if (vy[i] > 0f) vy[i] = 0f }
                    if (f.swingY[i] < -g.maxY) { f.swingY[i] = -g.maxY; if (vy[i] < 0f) vy[i] = 0f }
                }
                val ear = g == SwingGroup.EAR_L || g == SwingGroup.EAR_R
                swing = max(swing, if (g == SwingGroup.BELL) abs(f.swingX[i]) * 90f else if (g.angle) 0f else hypot(f.swingX[i], f.swingY[i]))
                // how fast it moves (sheet px a second; the bell's, tongue's and ears' angles as the bell's offset counts them)
                sway = max(sway, if (ear) abs(vx[i]) * 15f else if (g.angle) abs(vx[i]) * 90f else hypot(vx[i], vy[i]))
            }
            // the blink: shut at once, held, opened over its last [BLINK_OPEN] ms; now and then twice
            if (blinks && clock > nextBlink && blinkAt < 0) {
                blinkAt = clock
                blinkLong = BLINK_MIN + blinkDice.nextFloat() * (BLINK_MAX - BLINK_MIN)
            }
            if (blinkAt >= 0) {
                val b = clock - blinkAt
                f.blink = b < blinkLong
                f.lidAlpha = if (!f.blink) 0f else ((blinkLong - b) / BLINK_OPEN).coerceIn(0f, 1f)
                if (b >= blinkLong) {
                    blinkAt = -1f
                    nextBlink = clock + if (double) DOUBLE_MIN + blinkDice.nextFloat() * DOUBLE_SPAN else blinkGap(blinkRate * if (talking) TALK_BLINKS else 1f)
                    double = !double && blinkDice.nextFloat() < DOUBLE
                }
            } else { f.blink = false; f.lidAlpha = 0f }
            // the ears: a flick every few seconds
            if (!still && clock > nextEar) {
                twitch(if (earDice.nextFloat() < .5f) -1 else 1)
                nextEar = clock + 3000f + earDice.nextFloat() * 4000f
            }
            // talking: Flap on the rhythm of her words (or the mockup's), a nod on the stressed beats
            if (talking) {
                if (talkAt < 0) talkAt = clock
                val sp = words?.let { it.advance(h); it.now } ?: Speech.at(clock - talkAt)
                f.mouth = if (sp.open > .38f) ChessyMouth.OPEN else ChessyMouth.CLOSED
                val target = if (still) 0f else (if (sp.stress) .05f * sin(PI.toFloat() * min(1f, sp.u * 1.4f)) else 0f) + sp.open * .012f
                nod += (target - nod) * (1f - exp(-h / 70f))
            } else {
                f.mouth = ChessyMouth.OWN
                nod *= exp(-h / 100f)
            }
        }
        f.rimMix = ((swing - .5f) / 2.5f).coerceIn(0f, 1f)
        f.earL = f.swingX[SwingGroup.EAR_L.ordinal]
        f.earR = f.swingX[SwingGroup.EAR_R.ordinal]
        f.yaw = tx * YAW * depth
        f.pitch = -(ty + nod) * PITCH * depth
        // her tilt follows the turn, drifts a little of its own, and leans into a stressed word
        val t = clock / 1000f
        f.roll = if (still) 0f else (-tx + sin(t * .23f + phase[3]) * ROLL_DRIFT + nod * ROLL_NOD) * ROLL * depth
        // her idle drift keeps her head close behind where it is going; a look (or a glance) moves it further
        f.lively = talking || f.blink || blinkSoon() || abs(gx - tx) > LIVELY_TURN || abs(gy - ty) > LIVELY_TURN || sway > LIVELY_SWAY || abs(nod) > 1e-3f
        return f
    }

    /** How far the current glance has carried her look, 0 to 1: eased, so it stays within her calm steps' reach. */
    private fun glide(): Float {
        val u = ((clock - glanceAt) / GLANCE_GLIDE).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** A blink about to start: stepped every frame just before, so it is never caught late. */
    private fun blinkSoon() = nextBlink - clock < 70f || nextEar - clock < 70f

    /**
     * The wait to the next blink, in ms: log-normal about [BLINK_MEDIAN] (people blink in runs of short gaps with now and
     * then a long one, never on a metronome), [rate] times as often, within [GAP_MIN] and [GAP_MAX].
     */
    private fun blinkGap(rate: Float): Float {
        // a standard normal, Box–Muller
        val u1 = blinkDice.nextFloat().coerceAtLeast(1e-6f)
        val u2 = blinkDice.nextFloat()
        val n = sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
        return (BLINK_MEDIAN / rate.coerceAtLeast(.1f) * exp(BLINK_SPREAD * n)).coerceIn(GAP_MIN, GAP_MAX)
    }

    companion object {
        /**
         * How far her head may lag where it is going and still be calm: her idle drift moves at most about 0.23 a second,
         * which her follow trails by about 0.03; a look (the pointer moved) puts her far behind at once.
         */
        const val LIVELY_TURN = .045f

        /** The longest step she takes in one go, in ms: a sleeping calm step's, with room; a longer pause is cut to it. */
        const val MAX_STEP = 160f

        /** The longest piece a step is cut into, in ms, so long and short steps move her alike. */
        const val PIECE = 20f

        /** How fast a swinging layer may move and still be calm, in sheet px a second: her breath sways the hair slowly. */
        const val LIVELY_SWAY = 12f
        val YAW = (18 * PI / 180).toFloat()
        val PITCH = (10 * PI / 180).toFloat()
        val ROLL = (4 * PI / 180).toFloat()

        /** The head's spring: its natural frequency (rad/s) and damping ratio, settling in about a quarter second, overshooting about 1 %. */
        const val HEAD_W = 17f
        const val HEAD_Z = .8f

        /** Her tilt's own drift and its lean into a stressed word, as shares of [ROLL]. */
        const val ROLL_DRIFT = .25f
        const val ROLL_NOD = 3f

        /** An idle glance: how far it may look (across, up and down), and the wait between glances, in ms. */
        const val GLANCE_X = .2f
        const val GLANCE_Y = .12f
        const val GLANCE_MIN = 3000f
        const val GLANCE_SPAN = 5000f

        /**
         * How long a glance takes to arrive, in ms: slow enough that her head never falls [LIVELY_TURN] behind it, so a
         * glance is drawn at her calm pace (a jump cost a tenth of her idle time in full frames).
         */
        const val GLANCE_GLIDE = 900f

        /** The breath's rise in sheet px at depth 1. */
        const val BREATH = 2.2f

        /** A blink: shut between [BLINK_MIN] and [BLINK_MAX] ms, the last [BLINK_OPEN] opening. */
        const val BLINK_MIN = 110f
        const val BLINK_MAX = 150f
        const val BLINK_OPEN = 60f

        /** The gap between blinks: its median (about 17 a minute), spread, and bounds, all in ms; talking blinks half as often again. */
        const val BLINK_MEDIAN = 3400f
        const val BLINK_SPREAD = .45f
        const val GAP_MIN = 1200f
        const val GAP_MAX = 9000f
        const val TALK_BLINKS = 1.45f

        /** A double blink's chance and the gap to its second, in ms. */
        const val DOUBLE = .15f
        const val DOUBLE_MIN = 150f
        const val DOUBLE_SPAN = 150f

        /** An ear's flick (rad/s, peaking about 0.1 rad), its lag behind a turn and its flop with a hop. */
        const val EAR_FLICK = 2.4f
        const val EAR_LAG = .012f
        const val EAR_HOP = .0004f

        /** How much the body's motion (sheet px a second) throws the hair (per unit of a group's throw) and the bell. */
        const val BODY_LAG = .0016f
        const val BODY_ANGLE = .0005f

        /** The share of a carry ([carry]) her hair feels: the pet room's leaps are far bigger than a hop. */
        const val CARRY_SHARE = .4f

        /** How much the side lock's swing (sheet px a second) throws the ribbons. */
        const val BOW_THROW = .03f

        /** The breath's shape over one cycle [u] (0 to 1): a quick in over its first two fifths, a slow out; -1 to 1. */
        fun breathCurve(u: Float): Float =
            if (u < .4f) -cos(PI.toFloat() * u / .4f) else cos(PI.toFloat() * (u - .4f) / .6f)
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
