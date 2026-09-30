package com.kaiharimoto.mastertool.core.ai.avatar

/** Which of the avatar's two mark colours a mark takes: the eyes' yellow, or the light purple. */
enum class MarkInk { EYE, LIGHT }

/**
 * One piece of a mark: an SVG path in the mark's own units, filled, or stroked
 * [stroke] reference pixels wide (a width that does not grow with the mark's scale).
 */
class MarkPart(val d: String, val stroke: Float = 0f)

/**
 * The manga marks (漫符) and the little shapes around the head, drawn as paths rather
 * than type, so no font can leave one out. Each is drawn with a sticker border — white
 * and wide, then dark, then its colour — so it reads on paper and on ink alike.
 */
enum class MarkShape(val ink: MarkInk, val parts: List<MarkPart>) {
    /** A comet dot, a thinking dot: a circle of radius 1. */
    DOT(MarkInk.EYE, listOf(MarkPart(circle(0f, 0f, 1f)))),
    THINK_DOT(MarkInk.LIGHT, listOf(MarkPart(circle(0f, 0f, 1f)))),
    STAR(MarkInk.EYE, listOf(MarkPart("M0,-1Q0,0 1,0Q0,0 0,1Q0,0 -1,0Q0,0 0,-1Z"))),
    HEART(MarkInk.LIGHT, listOf(MarkPart("M0,.9C-.2,.7 -1,.25 -1,-.25C-1,-.8 -.35,-1 0,-.55C.35,-1 1,-.8 1,-.25C1,.25 .2,.7 0,.9Z"))),
    DROP(MarkInk.LIGHT, listOf(MarkPart("M0,-1.6C.6,-.6 1,0 1,.5A1,1 0 1,1 -1,.5C-1,0 -.6,-.6 0,-1.6Z"))),

    /** Three strokes under an eye, from the blush's anchor. */
    BLUSH(MarkInk.LIGHT, listOf(MarkPart("M-6,10L6,-10M9,10L21,-10M24,10L36,-10", stroke = 6f))),

    /** The anger mark (怒りマーク), four bent strokes. */
    ANGER(MarkInk.LIGHT, listOf(MarkPart("M-12,-4Q-4,-4 -4,-12M4,-12Q4,-4 12,-4M12,4Q4,4 4,12M-4,12Q-4,4 -12,4", stroke = 14f))),

    /** The sleep bubble (鼻ちょうちん): a ring of radius 1. */
    BUBBLE(MarkInk.LIGHT, listOf(MarkPart(circle(0f, 0f, 1f), stroke = 4.5f))),

    /** An exclamation mark on its baseline, 104 units tall as type. */
    EXCLAIM(MarkInk.EYE, listOf(MarkPart("M-10,-76L10,-76L7,-24L-7,-24Z"), MarkPart(circle(0f, -9f, 9f)))),

    /** A question mark on its baseline, 96 units tall as type. */
    QUESTION(MarkInk.EYE, listOf(MarkPart("M-19,-52C-19,-74 19,-76 19,-52C19,-37 0,-37 0,-21", stroke = 15f), MarkPart(circle(0f, -8f, 8.5f)))),

    /** A quaver on its baseline. */
    NOTE(MarkInk.EYE, listOf(MarkPart(ellipse(-9f, -9f, 11f, 8.5f)), MarkPart("M1.5,-9L1.5,-52C8,-46 19,-42 16,-27", stroke = 6f))),

    /** A lower-case z on its baseline, one unit to the em. */
    ZED(MarkInk.LIGHT, listOf(MarkPart("M.03,-.55L.5,-.55L.5,-.45L.17,-.1L.51,-.1L.51,0L.02,0L.02,-.1L.35,-.45L.03,-.45Z"))),
}

/** A circle as a path. */
internal fun circle(cx: Float, cy: Float, r: Float): String = ellipse(cx, cy, r, r)

/** An axis-aligned ellipse as a path. */
internal fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): String =
    "M${cx - rx},${cy}A$rx,$ry 0 1,0 ${cx + rx},${cy}A$rx,$ry 0 1,0 ${cx - rx},${cy}Z"

/**
 * Where everything around the head sits, in reference pixels: the views it is framed
 * in, the comet's orbit, the sparkles and each mark's anchor.
 */
object AvatarLayout {
    /** A square view of the reference: its left, top and side. */
    class View(val x: Float, val y: Float, val size: Float)

    /** The whole avatar with room for its marks. */
    val full = View(36f, -60f, 470f)

    /** The head alone, tight, for the glyph below 40 dp. */
    val glyph = View(93f, 0f, 346f)

    /** The comet's orbit: an ellipse turned [COMET_ROT] degrees, once round in [COMET_PERIOD] seconds. */
    const val COMET_X = 262f
    const val COMET_Y = 190f
    const val COMET_RX = 200f
    const val COMET_RY = 52f
    const val COMET_ROT = -16f
    const val COMET_PERIOD = 1.5f

    /** Six sparkles round the head: x, y, radius, phase. */
    val sparks: List<FloatArray> = listOf(-150f to .9f, -105f to 1f, -40f to .85f, 15f to .95f, 150f to .8f, 200f to .9f)
        .mapIndexed { i, (deg, k) ->
            val a = deg * kotlin.math.PI.toFloat() / 180f
            floatArrayOf(262f + 212f * k * kotlin.math.cos(a), 178f + 186f * k * kotlin.math.sin(a), 15f + 6f * (i % 3), i / 6f)
        }

    val dots = listOf(140f to 72f, 170f to 62f, 200f to 52f)
    const val MARK_X = 330f
    const val MARK_Y = 46f
    const val NOTE_X = 172f
    const val NOTE_Y = 62f
    const val SWEAT_X = 112f
    const val SWEAT_Y = 120f
    val blushL = 118f to 252f
    val blushR = 314f to 258f
    const val STAR_FROM_X = 300f
    const val STAR_FROM_Y = 168f
    const val STAR_TO_X = 362f
    const val STAR_TO_Y = 108f
    val hearts = listOf(342f to 80f, 168f to 86f)

    /** The middle of the mask ring's flat bottom, where the sleep bubble grows from. */
    val mouth: Pair<Float, Float> = run {
        val t = AvatarGeometry.RING_TILT * kotlin.math.PI.toFloat() / 180f
        AvatarGeometry.RING_X - kotlin.math.sin(t) * AvatarGeometry.RING_DEPTH to AvatarGeometry.RING_Y + kotlin.math.cos(t) * AvatarGeometry.RING_DEPTH
    }
}
