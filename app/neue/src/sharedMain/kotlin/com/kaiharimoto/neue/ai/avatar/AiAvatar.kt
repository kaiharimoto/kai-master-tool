package com.kaiharimoto.neue.ai.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarFrame
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarGeometry
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarLayout
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarRig
import com.kaiharimoto.mastertool.core.ai.avatar.EyeShape
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MarkInk
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import com.kaiharimoto.neue.kit.Micro
import kotlinx.coroutines.delay

/**
 * Ai's face (1.0.52), the approved mockup drawn live: a magatama head in flat colour
 * with its net, two yellow eyes that melt from face to face, and the manga marks
 * round it, each with a sticker border so it reads on paper and on ink.
 *
 * The third place colour is allowed in Neue, on kai's word: these five colours, in this
 * file alone (`MasterUiLawTest`). Everything that moves is `core/ai/avatar`'s
 * [AvatarRig]; this file only draws its [AvatarFrame]. One frame loop steps the rig and
 * bumps a counter read in the draw, so nothing recomposes as it moves.
 *
 * Below 40 dp it is the glyph: the head and eyes alone, drawn tighter, the eyes a size up.
 * [pointer] is where the pointer is in window pixels, for the eyes to follow; null lets
 * them wander.
 */
@Composable
fun AiAvatar(
    expression: Expression,
    size: Dp,
    modifier: Modifier = Modifier,
    pointer: () -> Offset? = { null },
    name: String = "Ai",
) {
    // Chessy has taken Ai's place (kai): her face, in the same spot, at the same size
    com.kaiharimoto.neue.ai.chessy.LocalChessy.current?.let { look ->
        com.kaiharimoto.neue.ai.chessy.ChessyAvatar(
            expression, size, modifier, talking = look.talking(), pointer = pointer,
        )
        return
    }
    val glyph = size.value < AvatarRig.GLYPH_BELOW_DP
    val rig = remember(glyph) { AvatarRig(glyph = glyph, seed = (System.nanoTime() % 100_000).toInt()) }
    val tick = remember { mutableIntStateOf(0) }
    val wearing by rememberUpdatedState(expression)
    val look by rememberUpdatedState(pointer)
    // The avatar's middle in window pixels, and its width: plain, read by the loop alone.
    val centre = remember { FloatArray(3) }
    LaunchedEffect(rig) {
        var last = 0L
        var owed = 0f
        // The glyph is small: thirty frames a second are all it can show.
        val every = if (glyph) 1f / 30f else 1f / 60f
        while (true) {
            // Asleep until the next step is nearly due (1.0.92): a frame asked for that steps nothing
            // still redraws the window, and the bar's glyph asked for one every vsync. It wakes early
            // enough to ask for the same vsync the step fell on before, so the face moves as it did.
            if (last != 0L) {
                val since = System.nanoTime() - last
                val wait = (every * 1e9f).toLong() - (owed * 1e9f).toLong() - since.coerceAtLeast(0L) - WAKE_EARLY_NANOS
                if (since in 0L until 1_000_000_000L && wait > 0L) delay(wait / 1_000_000L)
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, .1f)
                last = now
                owed += dt
                if (owed >= every || dt == 0f) {
                    rig.show(wearing)
                    val at = look()
                    if (at != null && centre[2] > 0f) rig.step(owed, at.x - centre[0], at.y - centre[1], centre[2]) else rig.step(owed)
                    owed = 0f
                    tick.intValue++
                }
            }
        }
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer()
            .semantics { contentDescription = "$name, ${expression.title.lowercase()}" }
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                centre[0] = p.x + c.size.width / 2f
                centre[1] = p.y + c.size.height * .55f
                centre[2] = c.size.width.toFloat()
            },
    ) {
        tick.intValue
        drawAvatar(rig.frame, glyph)
    }
}

/**
 * Ai's mark (1.0.63, kai: "whenever Ai is mentioned, it's a chance to input the marquee or some form
 * of the art for flavor"): the same art, drawn once and still — no frame loop, no eyes following the
 * pointer — so it can stand beside every place Ai is named, a reply's label or a section's title,
 * for nothing. The live face is the bar's, the composer's and the greeting's.
 */
@Composable
fun AiMark(size: Dp, modifier: Modifier = Modifier, expression: Expression = Expression.IDLE, name: String = "Ai") {
    val glyph = size.value < AvatarRig.GLYPH_BELOW_DP
    val frame = remember(glyph, expression) { AvatarRig(glyph = glyph, seed = 7).apply { show(expression) }.step(0.4f) }
    Canvas(modifier.size(size).semantics { contentDescription = name }) { drawAvatar(frame, glyph) }
}

/** Ai's name as a label, its mark before it: where a reply begins, in the panel's head. */
@Composable
fun AiName(name: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, mark: Dp = 14.dp) {
    androidx.compose.foundation.layout.Row(
        modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
    ) {
        AiMark(mark, name = name)
        Micro(name, color = color)
    }
}

/**
 * How long before a step is due the face asks for its frame: half a 60 Hz frame, slack for a late
 * timer and for the frame's time standing a little behind the clock.
 */
private const val WAKE_EARLY_NANOS = 8_000_000L

// ---- the palette: the reference's own colours, flat ------------------------------------

private val BODY = Color(0xFF362E42)
private val VEIN = Color(0xFF8747C2)
private val EYE = Color(0xFFFBF539)
private val LIGHT = Color(0xFFB98CF0)
private val RING = Color(0xFFFFFFFF)

// ---- the fixed paths, in reference pixels, built once ----------------------------------

private fun cubics(p: FloatArray, close: Boolean): Path = Path().apply {
    moveTo(p[0], p[1])
    var i = 2
    while (i + 5 < p.size) {
        cubicTo(p[i], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5])
        i += 6
    }
    if (close) close()
}

private object Paths {
    val body: Path = cubics(AvatarGeometry.body, close = true)
    val veins: List<Pair<Path, Float>> = AvatarGeometry.veins.map { cubics(it.path, it.closed) to it.width }
    val marks: Map<MarkShape, List<Pair<Path, Float>>> = MarkShape.entries.associateWith { shape ->
        shape.parts.map { addPathNodes(it.d).toPath() to it.stroke }
    }
}

private class EyePaths {
    val l = Path()
    val r = Path()
}

private val eyePaths = EyePaths()

private fun Path.polygon(p: FloatArray) {
    reset()
    moveTo(p[0], p[1])
    for (i in 1 until EyeShape.POINTS) lineTo(p[2 * i], p[2 * i + 1])
    close()
}

private fun DrawScope.drawAvatar(f: AvatarFrame, glyph: Boolean) {
    val view = if (glyph) AvatarLayout.glyph else AvatarLayout.full
    val u = size.minDimension / view.size
    if (u <= 0f) return
    val (dark, white, outline) = AvatarRig.borders(u)
    withTransform({
        translate((size.width - view.size * u) / 2f, (size.height - view.size * u) / 2f)
        scale(u, u, Offset.Zero)
        translate(-view.x, -view.y)
    }) {
        marks(f.behind, u, dark, white)
        withTransform({
            translate(f.bx, f.by)
            rotate(f.rot, Offset(AvatarGeometry.PIVOT_X, AvatarGeometry.PIVOT_Y))
            scale(f.sx, f.sy, Offset(AvatarGeometry.PIVOT_X, AvatarGeometry.PIVOT_Y))
        }) {
            // The white ring round the head: half of it shows outside the body.
            drawPath(Paths.body, RING, style = Stroke(outline / u, join = StrokeJoin.Round))
            drawPath(Paths.body, BODY)
            if (!glyph && f.veins > 0f) {
                clipPath(Paths.body) {
                    Paths.veins.forEach { (p, w) -> drawPath(p, VEIN, alpha = f.veins, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                    AvatarGeometry.cheeks.forEach { o ->
                        withTransform({ rotate(o.tilt, Offset(o.cx, o.cy)) }) {
                            drawOval(VEIN, Offset(o.cx - o.rx, o.cy - o.ry), Size(2 * o.rx, 2 * o.ry), alpha = f.veins)
                        }
                    }
                }
            }
            eyePaths.l.polygon(f.eyeL)
            eyePaths.r.polygon(f.eyeR)
            for ((p, w) in listOf(eyePaths.l to f.strokeL, eyePaths.r to f.strokeR)) {
                drawPath(p, EYE)
                drawPath(p, EYE, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            marks(f.fx, u, dark, white)
        }
        marks(f.front, u, dark, white)
        marks(f.top, u, dark, white)
    }
}

/** A run of marks with its sticker border: all of them white and wide, then dark, then in their colours. */
private fun DrawScope.marks(list: MarkList, u: Float, dark: Float, white: Float) {
    if (list.size == 0) return
    for (pass in 0..2) {
        val border = when (pass) {
            0 -> white
            1 -> dark
            else -> 0f
        }
        for (i in 0 until list.size) {
            val m = list[i]
            val colour = when (pass) {
                0 -> RING
                1 -> BODY
                else -> if (m.shape.ink == MarkInk.EYE) EYE else LIGHT
            }
            withTransform({
                translate(m.x, m.y)
                if (m.rot != 0f) rotate(m.rot, Offset.Zero)
                scale(m.scale, m.scale, Offset.Zero)
            }) {
                // Widths in screen pixels become the mark's own units: over the view's scale, then the mark's.
                val k = u * m.scale
                Paths.marks.getValue(m.shape).forEach { (p, stroke) ->
                    if (stroke == 0f) {
                        drawPath(p, colour, alpha = m.alpha)
                        if (border > 0f) drawPath(p, colour, alpha = m.alpha, style = Stroke(border / k, join = StrokeJoin.Round))
                    } else {
                        drawPath(p, colour, alpha = m.alpha, style = Stroke((stroke * u + border) / k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

/** The bar's glyph size, and the chat's. */
object AvatarSizes {
    val bar = 28.dp
    val composer = 44.dp
    val composerPhone = 40.dp
    val greeting = 120.dp
}
