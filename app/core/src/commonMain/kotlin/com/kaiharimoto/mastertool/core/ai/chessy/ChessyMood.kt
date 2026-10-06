package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.BodyPose
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import com.kaiharimoto.mastertool.core.ai.avatar.Pose
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One of Chessy's eyes, from her three sheet faces (kai: "I build from parts only"): the Grin's sly eye (her face
 * layer's own), the Fangs' wide one, the Tongue's happy shut one, or a lid shut over the Grin's (asleep).
 */
enum class ChessyEye { SLY, WIDE, SHUT, CLOSED }

/** Her mouths: the Grin's teeth (the face layer's own), the Fangs, the Tongue, the closed smile, or a small frown. */
enum class ChessyLips { GRIN, FANGS, TONGUE, SMILE, FROWN }

/**
 * A mood as Chessy wears it: an eye a side, a mouth, a sheet face's brows tilted [browTilt] degrees (inner ends
 * down, the anger's; up, the worry's) and lifted per side (sheet px, up is negative), and her ears turned [ears]
 * degrees (up pricked, down drooped).
 */
class ChessyMood(
    val eyeL: ChessyEye,
    val eyeR: ChessyEye,
    val lips: ChessyLips,
    val brows: String = ChessyFaces.GRIN,
    val browTilt: Float = 0f,
    val browLiftL: Float = 0f,
    val browLiftR: Float = 0f,
    val ears: Float = 0f,
) {
    /** Only an open eye blinks, and only one that has a lid to swap in (the Grin's and the Fangs'). */
    val blinks: Boolean get() = eyeL.blinks && eyeR.blinks

    /** The sheet face whose blink lid covers [eye]. */
    fun lidOf(eye: ChessyEye): String = if (eye == ChessyEye.WIDE) ChessyFaces.FANGS else ChessyFaces.GRIN
}

private val ChessyEye.blinks: Boolean get() = this == ChessyEye.SLY || this == ChessyEye.WIDE

/**
 * Each of Ai's twenty moods in Chessy's parts. The tracker that picks a mood is Ai's, unchanged ([Expression]); this
 * is only how she shows it. Her mischief sits in the Grin (working, a wink), her starts in the Fangs, her pleasure in
 * the Tongue's shut eyes.
 */
object ChessyMoods {
    private val SLY = ChessyEye.SLY
    private val WIDE = ChessyEye.WIDE
    private val SHUT = ChessyEye.SHUT
    private val CLOSED = ChessyEye.CLOSED

    fun of(e: Expression): ChessyMood = when (e) {
        Expression.IDLE -> ChessyMood(SLY, SLY, ChessyLips.SMILE)
        Expression.LISTENING -> ChessyMood(WIDE, WIDE, ChessyLips.SMILE, ChessyFaces.FANGS, browLiftL = -6f, browLiftR = -6f, ears = 7f)
        Expression.THINKING -> ChessyMood(SLY, SLY, ChessyLips.SMILE, browLiftL = -16f, browLiftR = 4f)
        Expression.WORKING -> ChessyMood(SLY, SLY, ChessyLips.GRIN, browTilt = 7f)
        Expression.READING -> ChessyMood(SLY, SLY, ChessyLips.SMILE, browLiftL = 4f, browLiftR = 4f)
        Expression.SPEAKING -> ChessyMood(WIDE, WIDE, ChessyLips.SMILE, ChessyFaces.FANGS)
        Expression.FOUND -> ChessyMood(WIDE, WIDE, ChessyLips.FANGS, ChessyFaces.FANGS, browLiftL = -12f, browLiftR = -12f, ears = 10f)
        Expression.DONE -> ChessyMood(SHUT, SHUT, ChessyLips.SMILE, ChessyFaces.TONGUE)
        Expression.WAITING -> ChessyMood(WIDE, WIDE, ChessyLips.SMILE, ChessyFaces.FANGS, browLiftR = -16f, ears = 4f)
        // tehepero: a wink and the tongue
        Expression.OOPS -> ChessyMood(WIDE, SHUT, ChessyLips.TONGUE, ChessyFaces.FANGS, browTilt = -8f, ears = -8f)
        Expression.SURPRISED -> ChessyMood(WIDE, WIDE, ChessyLips.FANGS, ChessyFaces.FANGS, browLiftL = -20f, browLiftR = -20f, ears = 14f)
        Expression.WINK -> ChessyMood(SLY, SHUT, ChessyLips.GRIN)
        Expression.SAD -> ChessyMood(WIDE, WIDE, ChessyLips.FROWN, ChessyFaces.FANGS, browTilt = -12f, ears = -16f)
        Expression.SLEEPING -> ChessyMood(CLOSED, CLOSED, ChessyLips.SMILE, browLiftL = 6f, browLiftR = 6f, ears = -12f)
        Expression.WAKING -> ChessyMood(WIDE, WIDE, ChessyLips.SMILE, ChessyFaces.FANGS, browLiftL = -8f, browLiftR = -8f, ears = 8f)
        Expression.DELIGHTED -> ChessyMood(SHUT, SHUT, ChessyLips.FANGS, ChessyFaces.TONGUE, browLiftL = -8f, browLiftR = -8f, ears = 10f)
        Expression.LOVE -> ChessyMood(SHUT, SHUT, ChessyLips.TONGUE, ChessyFaces.TONGUE)
        Expression.SHY -> ChessyMood(SHUT, SHUT, ChessyLips.SMILE, ChessyFaces.TONGUE, browTilt = -8f, ears = -10f)
        Expression.ANGRY -> ChessyMood(SLY, SLY, ChessyLips.FANGS, browTilt = 14f, ears = -14f)
        Expression.CRYING -> ChessyMood(CLOSED, CLOSED, ChessyLips.FROWN, ChessyFaces.FANGS, browTilt = -14f, ears = -18f)
    }
}

/**
 * Chessy's body language and manga marks (漫符) for the mood she wears: Ai's own choreography ([Expression.pose],
 * eased as Ai's rig eases it) read onto her. [bx], [by] (sheet px), [rot] (degrees about her neck), [sx] and [sy]
 * move her whole head; the marks are placed round *her* features in [fx] (riding the head) and [top] (over it), on
 * her 1320 x 1740 sheet. Ai's comet and net are Ai's alone and are left out.
 */
class ChessyMarks(var still: Boolean = false) {
    private val goal = Pose()
    private val now = Pose()
    private var begun = false
    private var mood = Expression.IDLE
    private var clock = 0f
    private var since = 0f

    var bx = 0f
        private set
    var by = 0f
        private set
    var rot = 0f
        private set
    var sx = 1f
        private set
    var sy = 1f
        private set
    val fx = MarkList(24)
    val top = MarkList(12)

    /** Wear [e] from now; the same mood again changes nothing, so its loop carries on. */
    fun show(e: Expression) {
        if (e == mood) return
        mood = e
        since = clock
    }

    /** One frame, [dt] seconds after the last. */
    fun step(dt: Float) {
        val d = dt.coerceIn(0f, .05f)
        clock += d
        val t = clock - since
        mood.pose(if (still) null else t, goal)
        if (!begun || still || d == 0f) {
            now.set(goal)
            begun = true
        } else {
            now.b.ease(goal.b, 1 - exp(-d / TAU_MOVE), 1 - exp(-d / TAU_FADE))
        }
        val b = now.b
        bx = b.bx * UNIT
        by = b.by * UNIT
        rot = b.rot
        sx = b.sx
        sy = b.sy
        place(b, t)
    }

    private fun place(b: BodyPose, t: Float) {
        fx.clear()
        top.clear()
        fun o(v: Float) = v.coerceIn(0f, 1f)
        fun pop(v: Float) = 1 - exp(-6 * v)
        val spark = o(b.spark)
        if (spark > 0f) {
            for (s in SPARKS) {
                val u = fract(t * .7f + s[3])
                val k = sin(PI.toFloat() * u).pow(2) * spark
                if (k > .01f) top.add(MarkShape.STAR, s[0], s[1], s[2] * k, 1f, rot = 45f * u)
            }
        }
        val zzz = o(b.zzz)
        if (zzz > 0f) {
            for (i in 0 until 3) {
                val u = fract(t / 2.6f + i / 3f)
                top.add(MarkShape.ZED, ZZZ_X - u * 220f, ZZZ_Y - u * 300f, 100f + u * 120f, zzz * sin(PI.toFloat() * u))
            }
        }
        if (b.dots > 0f) {
            val u = fract(t / 1.8f)
            DOTS.forEachIndexed { i, (x, y) -> if (u > i * .22f) fx.add(MarkShape.THINK_DOT, x, y, 30f, o(b.dots)) }
        }
        if (b.sweat > 0f) {
            val u = fract(t / 1.6f)
            fx.add(MarkShape.DROP, SWEAT_X, SWEAT_Y + 40 * u, 46f, o(b.sweat * (1 - u * .5f)))
        }
        if (b.blush > 0f) {
            val a = o(b.blush * (.85f + .15f * sin(TAU * t / 1.4f)))
            fx.add(MarkShape.BLUSH, BLUSH_L.first, BLUSH_L.second, 3f, a)
            fx.add(MarkShape.BLUSH, BLUSH_R.first, BLUSH_R.second, 3f, a)
        }
        if (b.tears > 0f) {
            for (i in 0 until 6) {
                val left = i < 3
                val eye = if (left) EYE_L else EYE_R
                val u = fract(t / 1.1f + (i % 3) / 3f)
                val dir = if (left) -1 else 1
                fx.add(MarkShape.DROP, eye.first + dir * (30 + 44 * u), eye.second + 60 + 250 * u, 24 + 9 * u, o(b.tears * (1 - u * .8f)))
            }
        }
        if (b.bubble > 0f) {
            val breath = .5f + .5f * sin(TAU * t / 4.2f)
            val r = 18 + 90 * breath
            fx.add(MarkShape.BUBBLE, MOUTH_X + 120 + r * .9f, MOUTH_Y + r * .35f, r * o(b.bubble), o(b.bubble))
        }
        val bob = 12 * sin(TAU * t / 1.2f)
        if (b.excl > 0f) fx.add(MarkShape.EXCLAIM, MARK_X, MARK_Y + bob, 3f * (.4f + .6f * pop(b.excl)), o(b.excl), rot = 8f)
        if (b.quest > 0f) fx.add(MarkShape.QUESTION, MARK_X, MARK_Y + bob, 3f * (.4f + .6f * pop(b.quest)), o(b.quest), rot = 8 * sin(TAU * t / 2.4f))
        if (b.anger > 0f) fx.add(MarkShape.ANGER, MARK_X, MARK_Y - 60, 11f * (1 + .18f * max(0f, sin(TAU * t / .7f))), o(b.anger))
        if (b.note > 0f) {
            val u = fract(t / 1.8f)
            fx.add(MarkShape.NOTE, NOTE_X - 50 * u, NOTE_Y - 110 * u, 3f, o(b.note * min(1f, 2.2f * sin(PI.toFloat() * u))), rot = -12 + 10 * sin(TAU * u))
        }
        val star = o(b.starpop)
        if (star > .02f) {
            fx.add(MarkShape.STAR, STAR_FROM.first + (STAR_TO.first - STAR_FROM.first) * star, STAR_FROM.second + (STAR_TO.second - STAR_FROM.second) * star, 24 + 48 * star, min(1f, 1.6f - star), rot = 90 * star)
        }
        if (b.hearts > 0f) {
            HEARTS.forEachIndexed { i, (x, y) ->
                val u = fract(t / 2.2f + i / 2f)
                fx.add(MarkShape.HEART, x + 24 * sin(TAU * u * 2), y - 200 * u, 38 + 18 * u, o(b.hearts * min(1f, 2 * sin(PI.toFloat() * u))))
            }
        }
    }

    companion object {
        private const val TAU = (2 * PI).toFloat()
        private const val TAU_MOVE = .15f
        private const val TAU_FADE = .3f

        /** Sheet px per Ai rig unit: Ai's head is about 350 reference px wide at 2 a unit, hers 1100 sheet px. */
        const val UNIT = 6.3f

        /** Her eyes' middles, her mouth's, and where each mark sits round her head, on the sheet. */
        val EYE_L = 443f to 966f
        val EYE_R = 820f to 966f
        const val MOUTH_X = 630f
        const val MOUTH_Y = 1215f
        const val MARK_X = 1110f
        const val MARK_Y = 330f
        const val NOTE_X = 250f
        const val NOTE_Y = 360f
        const val SWEAT_X = 215f
        const val SWEAT_Y = 640f
        const val ZZZ_X = 380f
        const val ZZZ_Y = 380f
        val BLUSH_L = 380f to 1085f
        val BLUSH_R = 790f to 1085f
        val STAR_FROM = 900f to 960f
        val STAR_TO = 1110f to 760f
        val DOTS = listOf(300f to 360f, 390f to 320f, 480f to 280f)
        val HEARTS = listOf(1090f to 420f, 200f to 450f)

        /** Six sparkles round her head: x, y, size, phase. */
        val SPARKS: List<FloatArray> = listOf(-150f to .9f, -105f to 1f, -40f to .85f, 15f to .95f, 150f to .8f, 200f to .9f)
            .mapIndexed { i, (deg, k) ->
                val a = deg * PI.toFloat() / 180f
                floatArrayOf(630f + 640f * k * kotlin.math.cos(a), 760f + 600f * k * sin(a), 46f + 18f * (i % 3), i / 6f)
            }

        private fun fract(x: Float) = x - floor(x)
    }
}
