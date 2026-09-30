package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The whole head: where it has hopped to ([bx], [by] in rig units), how far it leans
 * ([rot], degrees about the chin) and squashes ([sx], [sy]), how bright its net is
 * ([vein]), and how much of each manga mark (漫符) is showing, 0 to 1.
 */
class BodyPose {
    var bx = 0f
    var by = 0f
    var rot = 0f
    var sx = 1f
    var sy = 1f
    var vein = 1f
    var comet = 0f
    var zzz = 0f
    var spark = 0f
    var dots = 0f
    var excl = 0f
    var quest = 0f
    var note = 0f
    var sweat = 0f
    var anger = 0f
    var blush = 0f
    var tears = 0f
    var bubble = 0f
    var starpop = 0f
    var hearts = 0f

    fun reset() {
        bx = 0f; by = 0f; rot = 0f; sx = 1f; sy = 1f; vein = 1f
        comet = 0f; zzz = 0f; spark = 0f; dots = 0f; excl = 0f; quest = 0f; note = 0f; sweat = 0f; anger = 0f
        blush = 0f; tears = 0f; bubble = 0f; starpop = 0f; hearts = 0f
    }

    fun set(o: BodyPose) {
        bx = o.bx; by = o.by; rot = o.rot; sx = o.sx; sy = o.sy; vein = o.vein
        comet = o.comet; zzz = o.zzz; spark = o.spark; dots = o.dots; excl = o.excl; quest = o.quest; note = o.note
        sweat = o.sweat; anger = o.anger; blush = o.blush; tears = o.tears; bubble = o.bubble; starpop = o.starpop; hearts = o.hearts
    }

    /** The head's motion eases by [move], the marks and the net by the slower [fade]. */
    fun ease(o: BodyPose, move: Float, fade: Float) {
        bx += (o.bx - bx) * move; by += (o.by - by) * move; rot += (o.rot - rot) * move; sx += (o.sx - sx) * move; sy += (o.sy - sy) * move
        vein += (o.vein - vein) * fade; comet += (o.comet - comet) * fade; zzz += (o.zzz - zzz) * fade; spark += (o.spark - spark) * fade
        dots += (o.dots - dots) * fade; excl += (o.excl - excl) * fade; quest += (o.quest - quest) * fade; note += (o.note - note) * fade
        sweat += (o.sweat - sweat) * fade; anger += (o.anger - anger) * fade; blush += (o.blush - blush) * fade; tears += (o.tears - tears) * fade
        bubble += (o.bubble - bubble) * fade; starpop += (o.starpop - starpop) * fade; hearts += (o.hearts - hearts) * fade
    }
}

/** A whole face: both eyes and the head. */
class Pose {
    val l = EyePose()
    val r = EyePose()
    val b = BodyPose()

    /** Set by an expression that must not follow the pointer this moment (Waking, still drowsy). */
    var noGaze = false

    fun reset() {
        l.reset()
        r.reset()
        b.reset()
        noGaze = false
    }

    fun set(o: Pose) {
        l.set(o.l)
        r.set(o.r)
        b.set(o.b)
        noGaze = o.noGaze
    }

    /** Both eyes, the left one first; [side] is −1 for the left and 1 for the right. */
    inline fun both(f: (e: EyePose, side: Int) -> Unit) {
        f(l, -1)
        f(r, 1)
    }
}

/**
 * Ai's twenty faces, each a kaomoji (顔文字), ported one for one from the approved
 * mockup: a base pose, a loop that plays over it with time, and a still pose for
 * reduced motion. [blinks] and [gazes] say whether the eyes blink and follow the
 * pointer; [lean] is how far the head leans after it.
 */
enum class Expression(
    val id: String,
    val title: String,
    val kaomoji: String,
    val blinks: Boolean = true,
    val gazes: Boolean = true,
    val lean: Float = 1f,
) {
    IDLE("idle", "Idle", "(・_・)"),
    LISTENING("listening", "Listening", "(・ω・)", lean = 2f),
    THINKING("thinking", "Thinking", "(￣ヘ￣)…", gazes = false),
    WORKING("working", "Working", "(｀・ω・´)ゞ", gazes = false),
    READING("reading", "Reading", "(・_・ )…", gazes = false),
    SPEAKING("speaking", "Speaking", "(・o・)"),
    FOUND("found", "Found it", "(ﾟ∀ﾟ)！"),
    DONE("done", "Done", "(＾▽＾)♪", blinks = false),
    WAITING("waiting", "Waiting on you", "(・・？)", gazes = false),
    OOPS("oops", "Oops", "(＞＜)", gazes = false),
    SURPRISED("surprised", "Surprised", "(⊙_⊙)！", blinks = false),
    WINK("wink", "Wink", "(＾_−)☆", blinks = false),
    SAD("sad", "Sad", "(´・ω・｀)", gazes = false),
    SLEEPING("sleeping", "Sleeping", "(－_－)zzZ", blinks = false, gazes = false),
    WAKING("waking", "Waking", "(・_・)！", blinks = false),
    DELIGHTED("delighted", "Delighted", "(★▽★)", blinks = false),
    LOVE("love", "Love", "(♡▽♡)", blinks = false),
    SHY("shy", "Shy", "(〃▽〃)", blinks = false, gazes = false),
    ANGRY("angry", "Angry", "(｀へ´)ﾑｶｯ", gazes = false),
    CRYING("crying", "Crying", "(T_T)", blinks = false, gazes = false),
    ;

    /**
     * This face at [t] seconds into it, written into [p] from rest; a null [t] is the
     * still pose, for reduced motion and for pictures.
     */
    fun pose(t: Float?, p: Pose) {
        p.reset()
        base(p)
        if (t == null) still(p) else loop(t, p)
    }

    private fun base(p: Pose) {
        val b = p.b
        when (this) {
            LISTENING -> { p.both { e, _ -> e.rx = 14.2f; e.ry = 22f }; b.by = -3f }
            THINKING -> {
                p.both { e, _ -> e.ry = 18f; e.lidTop = .14f; e.lidBot = .06f; e.dx = 5f; e.dy = -6f }
                p.r.ry = 19f
                b.rot = 4f; b.dots = 1f
            }
            WORKING -> {
                p.both { e, _ -> e.rx = 12.5f; e.ry = 18.5f; e.lidTop = .16f }
                p.l.slant = .4f; p.r.slant = -.4f
                b.rot = -2f; b.comet = 1f
            }
            READING -> { p.both { e, _ -> e.lidTop = .22f; e.lidBot = .05f; e.ry = 19f }; b.rot = -3f; b.by = 1f }
            DONE -> { p.both { e, _ -> e.lidBot = .3f; e.curve = .95f; e.dy = -1f }; b.note = 1f }
            WAITING -> { p.both { e, _ -> e.rx = 13.6f; e.ry = 21f; e.dy = 1f }; b.rot = 6f; b.by = -1f; b.quest = 1f }
            OOPS -> {
                p.l.glyph = EyeGlyph.CHEVRON_RIGHT; p.r.glyph = EyeGlyph.CHEVRON_LEFT
                p.both { e, _ -> e.rx = 16.5f; e.ry = 19.5f }
                b.rot = 5f; b.vein = .55f; b.sweat = 1f
            }
            SURPRISED -> { p.both { e, _ -> e.rx = 15.5f; e.ry = 23f }; b.by = -5f; b.sy = 1.045f; b.excl = 1f }
            WINK -> b.rot = -2f
            SAD -> {
                p.both { e, _ -> e.rx = 7.5f; e.ry = 9f; e.dy = 2.5f; e.lidTop = .2f }
                p.l.slant = -.9f; p.r.slant = .9f
                b.by = 4f; b.sy = .965f; b.rot = -3f; b.vein = .6f
            }
            SLEEPING -> asleep(p)
            DELIGHTED -> { p.both { e, _ -> e.n = .6f; e.rx = 21f; e.ry = 21f; e.dy = -1f }; b.spark = 1f }
            LOVE -> { p.both { e, _ -> e.glyph = EyeGlyph.HEART; e.rx = 20.5f; e.ry = 18f; e.dy = -1f }; b.hearts = 1f }
            SHY -> {
                p.both { e, _ -> e.lidBot = .3f; e.curve = .95f; e.rx = 15.5f; e.ry = 21.5f; e.dx = -2.5f; e.dy = 2f }
                b.blush = 1f; b.rot = -5f; b.by = 2f; b.sy = .985f
            }
            ANGRY -> {
                p.both { e, _ -> e.ry = 15f; e.lidTop = .3f }
                p.l.slant = .95f; p.r.slant = -.95f
                b.anger = 1f; b.rot = 2f
            }
            CRYING -> { p.both { e, _ -> e.glyph = EyeGlyph.T; e.rx = 13f; e.ry = 15f }; b.tears = 1f; b.by = 2f; b.vein = .7f }
            IDLE, SPEAKING, FOUND, WAKING -> Unit
        }
    }

    private fun loop(t: Float, p: Pose) {
        val b = p.b
        when (this) {
            IDLE -> { val s = sn(t, 3.4f); b.sy += .014f * s; b.by -= 1.3f * s }
            LISTENING -> {
                val u = fract(t / 1.9f)
                val nod = if (u < .3f) sin(PI.toFloat() * u / .3f) else 0f
                b.by += 2.4f * nod; b.rot -= 1.2f * nod
                p.both { e, _ -> e.dy += 1.2f * nod }
            }
            THINKING -> {
                val away = fract(t / 3.2f) < .5f
                p.both { e, _ -> e.dx = if (away) 5f else 1.5f; e.dy = if (away) -6f else -4.5f }
                b.rot += 1.6f * sn(t, 3.2f)
            }
            WORKING -> {
                val x = sin(TAU * t / 1.3f)
                p.both { e, _ -> e.dx = 5f * x; e.dy = 1f }
                b.by += 1.4f * abs(sn(t, 1.3f)) - .7f
            }
            READING -> {
                val per = 1.3f
                val k = floor(t / per).toInt() % 4
                val u = fract(t / per)
                val x = if (u < .84f) -5.5f + 11f * (u / .84f) else 5.5f - 11f * ((u - .84f) / .16f)
                p.both { e, _ -> e.dx = x; e.dy = -1.5f + k * 2.2f }
                b.rot += k * .5f
            }
            SPEAKING -> {
                val phrase = if (fract(t / 3.8f) < .8f) 1f else 0f
                val s = phrase * (max(0f, sin(TAU * t * 2.6f)) * (.6f + .4f * sin(TAU * t * .7f)) + .35f * max(0f, sin(TAU * t * 5.1f + 1f))).coerceIn(0f, 1f)
                p.both { e, _ -> e.ry += 2.6f * s; e.rx -= .5f * s; e.dy -= 1.4f * s }
                b.sy += .025f * s; b.by -= 1.8f * s
            }
            FOUND -> {
                val u = t % 2.8f
                val e = exp(-u * 5f)
                p.both { q, _ -> q.rx += 2.8f * e; q.ry += 4f * e; q.dy -= 2f * e; q.n -= .9f * e }
                b.by -= 7f * e; b.sy += .05f * e; b.rot += 2.5f * sin(u * 10f) * exp(-u * 3.5f)
                b.spark = exp(-u * 1.6f); b.excl = if (u < 1.6f) 1f else 0f
            }
            DONE -> {
                val u = t % 2.2f
                val hop = if (u < .5f) sin(PI.toFloat() * u / .5f) else 0f
                val crouch = if (u > 1.85f) sin(PI.toFloat() * (u - 1.85f) / .35f) else 0f
                b.by -= 6f * hop - 1.5f * crouch; b.sy += .03f * hop - .045f * crouch; b.rot += 2f * sn(t, 1.8f)
            }
            WAITING -> {
                val w = .5f - .5f * cos(TAU * t / 2.4f)
                p.both { e, _ -> e.ry += .8f * w }
                val u = t % 2.4f
                val tap = (if (u > 1.5f && u < 1.7f) sin(PI.toFloat() * (u - 1.5f) / .2f) else 0f) +
                    (if (u > 1.8f && u < 2f) sin(PI.toFloat() * (u - 1.8f) / .2f) else 0f)
                b.by += 1.6f * tap
            }
            OOPS -> {
                val u = t % 3f
                val shake = sin(u * 38f) * exp(-u * 4.5f)
                b.rot += 3f * shake; b.bx += 1.6f * shake
                val sq = exp(-u * 3f)
                p.both { e, _ -> e.ry -= 2f * sq; e.rx += sq }
            }
            SURPRISED -> {
                val j = exp(-(t % 3f) * 6f)
                b.by -= 5f * j; b.sy += .03f * j
                p.both { e, _ -> e.ry += 2.5f * j; e.rx += j }
            }
            WINK -> {
                val u = t % 2.8f
                wink(p, smooth(.2f, .34f, u) * (1 - smooth(1.35f, 1.5f, u)))
                b.starpop = if (u > .28f && u < 1.1f) (u - .28f) / .82f else 0f
            }
            SAD -> { val s = sn(t, 5f); b.sy += .012f * s; b.by -= s; p.both { e, _ -> e.dy += .5f * s } }
            SLEEPING -> { val s = sn(t, 4.2f); b.sy += .03f * s; b.by -= 1.8f * s; b.vein += .08f * s }
            WAKING -> {
                val u = t % 5.2f
                waking(p, u)
                if (u < 3.3f) p.noGaze = true
            }
            DELIGHTED -> {
                p.both { e, side ->
                    e.tilt = 18f * sn(t, 1.4f, side * .1f)
                    val k = abs(sn(t, .8f))
                    e.rx += 1.6f * k; e.ry += 1.6f * k
                }
                b.by -= 4f * abs(sn(t, 1.8f)); b.rot += 3f * sn(t, 1.8f)
            }
            LOVE -> {
                val u = fract(t / .9f)
                val beat = exp(-u * 7f) + if (u > .18f) .6f * exp(-(u - .18f) * 7f) else 0f
                p.both { e, _ -> e.rx += 1.8f * beat; e.ry += 1.6f * beat }
                b.by -= 1.5f * beat; b.rot += 2f * sn(t, 3f)
            }
            SHY -> { b.rot += 1.6f * sn(t, 2.6f); b.bx += .8f * sn(t, 2.6f) }
            ANGRY -> { b.bx += .5f * sin(TAU * t * 9f); b.sy += .012f * max(0f, sin(TAU * t / .7f)) }
            CRYING -> { val s = max(0f, sin(TAU * t / 1.1f)); b.sy -= .022f * s; b.by += 1.6f * s }
        }
    }

    private fun still(p: Pose) {
        when (this) {
            FOUND -> { p.b.spark = .7f; p.b.excl = 1f; p.both { e, _ -> e.rx += 1.5f; e.ry += 2f } }
            WINK -> { wink(p, 1f); p.b.starpop = .5f }
            WAKING -> waking(p, 3.3f)
            else -> Unit
        }
    }

    companion object {
        const val TAU = (2 * PI).toFloat()

        fun byId(id: String): Expression? = entries.firstOrNull { it.id == id }

        internal fun sn(t: Float, period: Float, phase: Float = 0f) = sin(TAU * (t / period + phase))
        internal fun fract(x: Float) = x - floor(x)
        internal fun smooth(a: Float, b: Float, x: Float): Float {
            val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return u * u * (3 - 2 * u)
        }

        /** The right eye closes into Done's arc as [w] goes to 1, and the head tips into it. */
        private fun wink(p: Pose, w: Float) {
            p.r.lidBot = .3f * w
            p.r.curve = .95f * w
            p.b.rot -= 5f * w
            p.b.by -= 1.5f * w
        }

        private fun asleep(p: Pose) {
            p.both { e, _ -> e.lidTop = .5f; e.lidBot = .5f; e.ry = 18f; e.dy = 4f }
            val b = p.b
            b.by = 3f; b.rot = -6f; b.vein = .55f; b.zzz = 1f; b.bubble = 1f
        }

        /** Waking's keyframes: one drowsy look, two blinks, a stretch, then it notices you. */
        private fun waking(p: Pose, u: Float) {
            val b = p.b
            when {
                u < 1.1f -> asleep(p)
                u < 1.9f -> { p.both { e, _ -> e.lidTop = .38f; e.ry = 18f; e.dy = 2f }; b.by = 2f; b.rot = -3f }
                u < 2.05f || (u >= 2.2f && u < 2.35f) -> { p.both { e, _ -> e.lidTop = .5f; e.lidBot = .5f; e.ry = 18f }; b.by = 1f }
                u < 2.55f -> p.both { e, _ -> e.ry = 20f }
                u < 3.3f -> { p.both { e, _ -> e.lidBot = .4f; e.curve = .9f; e.ry = 19f }; b.by = -6f; b.sy = 1.07f }
                else -> { p.both { e, _ -> e.rx = 14f; e.ry = 21f }; b.excl = 1f }
            }
        }
    }
}
