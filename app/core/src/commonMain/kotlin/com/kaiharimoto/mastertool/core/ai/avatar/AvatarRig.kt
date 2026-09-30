package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** One mark as drawn this frame: its shape, where its origin sits, turned [rot] degrees, [scale] times its size. */
class Mark {
    var shape = MarkShape.DOT
    var x = 0f
    var y = 0f
    var rot = 0f
    var scale = 1f
    var alpha = 1f
}

/** A reusable run of marks, drawn in order, each with its sticker border under the lot. */
class MarkList(capacity: Int) {
    private val marks = Array(capacity) { Mark() }
    var size = 0
        private set

    operator fun get(i: Int): Mark = marks[i]

    fun clear() {
        size = 0
    }

    fun add(shape: MarkShape, x: Float, y: Float, scale: Float, alpha: Float, rot: Float = 0f) {
        if (alpha < .005f || scale <= 0f || size == marks.size) return
        val m = marks[size++]
        m.shape = shape
        m.x = x
        m.y = y
        m.scale = scale
        m.alpha = alpha.coerceAtMost(1f)
        m.rot = rot
    }
}

/**
 * Everything the drawing needs for one frame, in reference pixels. The head, its
 * eyes and [fx] are drawn inside the head's transform (translate by [bx], [by], lean
 * [rot] degrees about the pivot, squash by [sx], [sy] about it); [behind], [front]
 * and [top] are drawn in the view as they are.
 */
class AvatarFrame {
    var bx = 0f
    var by = 0f
    var rot = 0f
    var sx = 1f
    var sy = 1f
    val eyeL = FloatArray(2 * EyeShape.POINTS)
    val eyeR = FloatArray(2 * EyeShape.POINTS)
    var strokeL = 0f
    var strokeR = 0f
    var veins = 1f

    /** The comet's dots on the far side of the head. */
    val behind = MarkList(9)
    val fx = MarkList(24)
    val front = MarkList(9)

    /** Sparkles and z's, above everything. */
    val top = MarkList(9)
}

/**
 * Ai's face, alive: the mockup's rig, frame by frame. [show] picks an expression;
 * [step] eases the face toward it — every eye point, the head's hop and lean, each
 * mark's fade — blinks at random, follows the pointer or lets the eyes drift, and
 * writes the result into [frame]. Pure and allocation-free per frame, so it runs in
 * the draw loop; seeded, so tests see the same blinks every time.
 *
 * [glyph] is the small form (under 40 dp): no net, no marks, the eyes at 1.1×.
 * [still] is reduced motion: each face's still pose, no blinking, no drift.
 */
class AvatarRig(val glyph: Boolean = false, seed: Int = 7, var still: Boolean = false) {
    var expression = Expression.IDLE
        private set

    /** Seconds since the rig began. */
    var clock = 0f
        private set
    private var since = 0f

    private val random = Random(seed)
    private val goal = Pose()
    private val now = Pose()
    private var begun = false
    private val eyes = arrayOf(FloatArray(2 * EyeShape.POINTS), FloatArray(2 * EyeShape.POINTS))
    private val aim = FloatArray(2 * EyeShape.POINTS)
    private val placed = arrayOf(EyeShape.Placed(), EyeShape.Placed())
    private val strokes = FloatArray(2)
    private val blinks = FloatArray(4) { -99f }
    private var nextBlink = 0f
    private var comet = random.nextFloat() * TAU
    private var driftX = 0f
    private var driftY = 0f
    private var driftUntil = 0f
    private var pointerX = Float.NaN
    private var pointerY = Float.NaN
    private var pointerAt = -99f

    val frame = AvatarFrame()

    init {
        nextBlink = between(.8f, 4f)
    }

    /** Wear [e] from now; the same face again changes nothing, so its loop carries on. */
    fun show(e: Expression) {
        if (e == expression) return
        expression = e
        since = clock
    }

    /** How long the current face has been worn. */
    val worn: Float get() = clock - since

    /**
     * One frame, [dt] seconds after the last. The pointer, if there is one, is at
     * ([px], [py]) pixels from the avatar's middle, drawn [size] pixels wide; NaN when
     * it is out of reach, and the eyes wander by themselves.
     */
    fun step(dt: Float, px: Float = Float.NaN, py: Float = Float.NaN, size: Float = 1f): AvatarFrame {
        val d = dt.coerceIn(0f, .05f)
        clock += d
        val e = expression
        val t = clock - since
        e.pose(if (still) null else t, goal)
        if (!still && e.gazes && !goal.noGaze) gaze(px, py, size)
        val snap = !begun || still || d == 0f
        if (snap) {
            now.set(goal)
            begun = true
        } else {
            val eye = 1 - exp(-d / TAU_EYE)
            now.l.ease(goal.l, eye)
            now.r.ease(goal.r, eye)
            now.b.ease(goal.b, 1 - exp(-d / TAU_MOVE), 1 - exp(-d / TAU_FADE))
        }
        val a = if (snap) 1f else 1 - exp(-d / TAU_EYE)
        val scale = if (glyph) GLYPH_EYES else 1f
        for (i in 0..1) {
            val base = if (i == 0) AvatarGeometry.eyeL else AvatarGeometry.eyeR
            val pose = if (i == 0) goal.l else goal.r
            EyeShape.target(pose, base, scale, aim, placed[i])
            val pts = eyes[i]
            for (j in pts.indices) pts[j] += (aim[j] - pts[j]) * a
            val sw = K * ((if (glyph) 2.4f else 1.6f) + (if (glyph) 3f else 2.6f) * (1 - placed[i].open))
            strokes[i] += (sw - strokes[i]) * a
        }
        val blink = if (!still && e.blinks) blinkAmount() else {
            blinks.fill(-99f)
            0f
        }
        if (!still) comet += d * TAU / AvatarLayout.COMET_PERIOD
        render(blink, if (still) 0f else t)
        return frame
    }

    private fun gaze(px: Float, py: Float, size: Float) {
        val gx: Float
        val gy: Float
        if (!px.isNaN() && (px != pointerX || py != pointerY)) {
            pointerX = px
            pointerY = py
            pointerAt = clock
        }
        if (!px.isNaN() && clock - pointerAt < GAZE_STALE) {
            val dist = hypot(px, py) + 140f + size * .4f
            gx = px / dist
            gy = py / dist
        } else {
            if (clock > driftUntil) {
                if (random.nextFloat() < .35f) {
                    driftX = 0f
                    driftY = 0f
                } else {
                    driftX = between(-.8f, .8f)
                    driftY = between(-.6f, .6f)
                }
                driftUntil = clock + between(GAZE_DRIFT_MIN, GAZE_DRIFT_MAX)
            }
            gx = driftX
            gy = driftY
        }
        goal.both { eye, _ -> eye.dx += gx * GAZE_X; eye.dy += gy * GAZE_Y }
        goal.b.rot += gx * GAZE_LEAN * expression.lean
        goal.b.bx += gx * GAZE_SHIFT
    }

    /** 0 open to 1 shut: a blink every 2.5 to 6 seconds, one in five of them doubled. */
    private fun blinkAmount(): Float {
        if (clock >= nextBlink) {
            start(clock)
            if (random.nextFloat() < BLINK_DOUBLE) start(clock + .28f)
            nextBlink = clock + between(BLINK_MIN, BLINK_MAX)
        }
        var amount = 0f
        for (i in blinks.indices) {
            val u = (clock - blinks[i]) / BLINK_DURATION
            if (u in 0f..1f) amount = max(amount, sin(PI.toFloat() * u))
        }
        return amount
    }

    private fun start(at: Float) {
        // The oldest slot, or one that has finished.
        var slot = 0
        for (i in blinks.indices) if (blinks[i] < blinks[slot]) slot = i
        blinks[slot] = at
    }

    private fun between(a: Float, b: Float) = a + random.nextFloat() * (b - a)

    private fun render(blink: Float, t: Float) {
        val b = now.b
        val f = frame
        f.bx = K * b.bx
        f.by = K * b.by
        f.rot = b.rot
        f.sx = b.sx * (1 + (1 - b.sy) * .8f)
        f.sy = b.sy
        // A blink squashes the outline toward the eye's own axis, whatever face it wears.
        for (i in 0..1) {
            val pts = eyes[i]
            val out = if (i == 0) f.eyeL else f.eyeR
            val pose = if (i == 0) now.l else now.r
            val base = if (i == 0) AvatarGeometry.eyeL else AvatarGeometry.eyeR
            val cx = base.cx + K * pose.dx
            val cy = base.cy + K * pose.dy
            val tl = (base.tilt + pose.tilt) * PI.toFloat() / 180f
            val ca = cos(tl)
            val sa = sin(tl)
            val s = 1 - BLINK_SQUASH * blink
            for (j in 0 until EyeShape.POINTS) {
                var x = pts[2 * j] - cx
                var y = pts[2 * j + 1] - cy
                if (blink > 0f) {
                    val u = x * ca + y * sa
                    val v = (-x * sa + y * ca) * s
                    x = u * ca - v * sa
                    y = u * sa + v * ca
                }
                out[2 * j] = cx + x
                out[2 * j + 1] = cy + y
            }
            val w = strokes[i] + if (blink > .5f) K * 2 * blink else 0f
            if (i == 0) f.strokeL = w else f.strokeR = w
        }
        f.veins = b.vein.coerceIn(0f, 1f)
        // The comet: behind the head on the top of its orbit, in front on the bottom.
        f.behind.clear()
        f.front.clear()
        val op = b.comet.coerceIn(0f, 1f)
        if (op >= .01f) {
            val cr = AvatarLayout.COMET_ROT * PI.toFloat() / 180f
            val big = if (glyph) 1.8f else 1f
            for (k in 0 until 9) {
                val a = comet - k * .13f
                val lx = AvatarLayout.COMET_RX * cos(a)
                val ly = AvatarLayout.COMET_RY * sin(a)
                val x = AvatarLayout.COMET_X + lx * cos(cr) - ly * sin(cr)
                val y = AvatarLayout.COMET_Y + lx * sin(cr) + ly * cos(cr)
                val list = if (sin(a) < 0) f.behind else f.front
                list.add(MarkShape.DOT, x, y, 11f * big * (1 - k / 10f), op * (1 - k / 9.5f))
            }
        }
        f.top.clear()
        f.fx.clear()
        if (glyph) return
        val spark = b.spark.coerceIn(0f, 1f)
        if (spark > 0f) {
            for (s in AvatarLayout.sparks) {
                val u = fract(t * .7f + s[3])
                val k = sin(PI.toFloat() * u).pow(2) * spark
                if (k > .01f) f.top.add(MarkShape.STAR, s[0], s[1], s[2] * k, 1f, rot = 45f * u)
            }
        }
        val zzz = b.zzz.coerceIn(0f, 1f)
        if (zzz > 0f) {
            for (i in 0 until 3) {
                val u = fract(t / 2.6f + i / 3f)
                f.top.add(MarkShape.ZED, 165f - u * 70f, 92f - u * 100f, 32f + u * 40f, zzz * sin(PI.toFloat() * u))
            }
        }
        marks(b, t)
    }

    /** The manga marks, in the head's own space, so they ride its hops. */
    private fun marks(b: BodyPose, t: Float) {
        val fx = frame.fx
        fun o(v: Float) = v.coerceIn(0f, 1f)
        fun pop(v: Float) = 1 - exp(-6 * v)
        // … three dots fill in one by one.
        if (b.dots > 0f) {
            val u = fract(t / 1.8f)
            AvatarLayout.dots.forEachIndexed { i, (x, y) -> if (u > i * .22f) fx.add(MarkShape.THINK_DOT, x, y, 10f, o(b.dots)) }
        }
        // A sweat drop slides down the side of the head.
        if (b.sweat > 0f) {
            val u = fract(t / 1.6f)
            fx.add(MarkShape.DROP, AvatarLayout.SWEAT_X, AvatarLayout.SWEAT_Y + 12 * u, 15f, o(b.sweat * (1 - u * .5f)))
        }
        if (b.blush > 0f) {
            val a = o(b.blush * (.85f + .15f * sin(TAU * t / 1.4f)))
            fx.add(MarkShape.BLUSH, AvatarLayout.blushL.first, AvatarLayout.blushL.second, 1f, a)
            fx.add(MarkShape.BLUSH, AvatarLayout.blushR.first, AvatarLayout.blushR.second, 1f, a)
        }
        // Tears fall from the bottom of each eye.
        if (b.tears > 0f) {
            for (i in 0 until 6) {
                val left = i < 3
                val eye = if (left) AvatarGeometry.eyeL else AvatarGeometry.eyeR
                val u = fract(t / 1.1f + (i % 3) / 3f)
                val dir = if (left) -1 else 1
                fx.add(MarkShape.DROP, eye.cx + dir * (10 + 14 * u), eye.cy + eye.ry * .8f + 80 * u, 8 + 3 * u, o(b.tears * (1 - u * .8f)))
            }
        }
        // The sleep bubble swells and shrinks with the breath, from the flat of the ring.
        if (b.bubble > 0f) {
            val breath = .5f + .5f * Expression.sn(t, 4.2f)
            val r = 6 + 30 * breath
            fx.add(MarkShape.BUBBLE, AvatarLayout.mouth.first + r * .9f, AvatarLayout.mouth.second + r * .35f, r * o(b.bubble), o(b.bubble))
        }
        // ! and ? pop in above the head, then bob.
        val bob = 4 * sin(TAU * t / 1.2f)
        if (b.excl > 0f) fx.add(MarkShape.EXCLAIM, AvatarLayout.MARK_X, AvatarLayout.MARK_Y + bob, .4f + .6f * pop(b.excl), o(b.excl), rot = 8f)
        if (b.quest > 0f) {
            fx.add(MarkShape.QUESTION, AvatarLayout.MARK_X, AvatarLayout.MARK_Y + bob, .4f + .6f * pop(b.quest), o(b.quest), rot = 8 * sin(TAU * t / 2.4f))
        }
        // The anger mark throbs.
        if (b.anger > 0f) {
            fx.add(MarkShape.ANGER, AvatarLayout.MARK_X, AvatarLayout.MARK_Y - 10, 3.6f * (1 + .18f * max(0f, sin(TAU * t / .7f))), o(b.anger))
        }
        // ♪ floats up and away.
        if (b.note > 0f) {
            val u = fract(t / 1.8f)
            fx.add(
                MarkShape.NOTE, AvatarLayout.NOTE_X - 16 * u, AvatarLayout.NOTE_Y - 34 * u, 1f,
                o(b.note * min(1f, 2.2f * sin(PI.toFloat() * u))), rot = -12 + 10 * sin(TAU * u),
            )
        }
        // ☆ flies out of the wink.
        val star = o(b.starpop)
        if (star > .02f) {
            fx.add(
                MarkShape.STAR,
                AvatarLayout.STAR_FROM_X + (AvatarLayout.STAR_TO_X - AvatarLayout.STAR_FROM_X) * star,
                AvatarLayout.STAR_FROM_Y + (AvatarLayout.STAR_TO_Y - AvatarLayout.STAR_FROM_Y) * star,
                8 + 16 * star, min(1f, 1.6f - star), rot = 90 * star,
            )
        }
        // ♡ drift up.
        if (b.hearts > 0f) {
            AvatarLayout.hearts.forEachIndexed { i, (x, y) ->
                val u = fract(t / 2.2f + i / 2f)
                fx.add(MarkShape.HEART, x + 8 * sin(TAU * u * 2), y - 64 * u, 12 + 6 * u, o(b.hearts * min(1f, 2 * sin(PI.toFloat() * u))))
            }
        }
    }

    companion object {
        /** Reference pixels per rig unit, so every pose number keeps the first mockup's scale. */
        const val K = 2f
        private const val TAU = (2 * PI).toFloat()
        const val TAU_EYE = .075f
        const val TAU_MOVE = .15f
        const val TAU_FADE = .3f
        const val BLINK_DURATION = .17f
        const val BLINK_MIN = 2.5f
        const val BLINK_MAX = 6f
        const val BLINK_DOUBLE = .2f
        const val BLINK_SQUASH = .94f
        const val GAZE_X = 4.5f
        const val GAZE_Y = 4f
        const val GAZE_LEAN = 2.5f
        const val GAZE_SHIFT = 1.5f
        const val GAZE_STALE = 3f
        const val GAZE_DRIFT_MIN = 1.2f
        const val GAZE_DRIFT_MAX = 3.2f
        const val GLYPH_EYES = 1.1f

        /** Below this many dp the avatar is its glyph. */
        const val GLYPH_BELOW_DP = 40f

        private fun fract(x: Float) = x - kotlin.math.floor(x)

        /**
         * The sticker border's two widths in screen pixels for an avatar drawn at [u]
         * pixels per reference pixel — dark inside, white outside — and the white
         * outline round the head. Each is a whole stroke width, half of it outside.
         */
        fun borders(u: Float): Triple<Float, Float, Float> =
            Triple(2 * (3.2f * u).coerceIn(1.1f, 3.5f), 2 * (6.4f * u).coerceIn(2.2f, 6.5f), 2 * (2.6f * u).coerceIn(1.4f, 3f))
    }
}
