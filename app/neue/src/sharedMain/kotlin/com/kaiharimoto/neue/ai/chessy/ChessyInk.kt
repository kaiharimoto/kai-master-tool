package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyType
import com.kaiharimoto.neue.theme.LocalMuFonts
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import com.kaiharimoto.mastertool.core.ai.avatar.MarkInk
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import com.kaiharimoto.neue.cards.Holo
import com.kaiharimoto.neue.cards.HoloCache

/**
 * Chessy's manga marks (kai, 2026-10): Ai's shapes ([MarkShape]) placed round her by `core/ai/chessy`'s ChessyMarks,
 * each with the sticker border Ai's marks wear (white and wide, then her line's plum) so it reads on paper and on ink,
 * and **filled with the cards' holographic foil** (kai: "have the effect particles and symbols be the foil texture") —
 * each mark its own small sheet, so every heart and sparkle carries the whole rainbow, its light at [light]. The blush
 * strokes stay pink (they are her cheeks, not a symbol), and where there is no runtime shader the marks keep her flat
 * violet and pink. One of the files `MasterUiLawTest` allows colour, with `AiAvatar.kt`: a character's art is content.
 */
internal object ChessyInk {
    internal val VIOLET = Color(0xFF9A76DA)
    internal val PINK = Color(0xFFF08DB8)
    private val PLUM = Color(0xFF3B2C4D)
    internal val PAPER = Color(0xFFFFFFFF)
    // the takeover's box: lilac on white, plum words
    internal val LILAC = Color(0xFFC6AEF2)
    internal val LILAC_DEEP = Color(0xFFB59CEC)
    internal val WORDS = Color(0xFF2B1E40)

    private class Shape(val parts: List<Pair<Path, Float>>, val bounds: Rect)

    private val shapes: Map<MarkShape, Shape> = MarkShape.entries.associateWith { shape ->
        val parts = shape.parts.map { addPathNodes(it.d).toPath() to it.stroke }
        var b = parts.first().first.getBounds()
        parts.forEach { (p, stroke) -> b = Rect(minOf(b.left, p.getBounds().left), minOf(b.top, p.getBounds().top), maxOf(b.right, p.getBounds().right), maxOf(b.bottom, p.getBounds().bottom)).inflate(stroke) }
        Shape(parts, b)
    }

    private val layer = Paint()

    /**
     * A run of marks placed in sheet units ([MarkList]), drawn on this canvas at [s] pixels per sheet unit from
     * ([ox], [oy]): all of them white and wide, then plum, then each filled with foil. Borders are in screen pixels,
     * so a small Chessy keeps a readable sticker.
     */
    fun DrawScope.marks(list: MarkList, s: Float, ox: Float = 0f, oy: Float = 0f, light: Offset = Offset(-.4f, -.6f), foils: MarkFoils? = null) {
        if (list.size == 0 || s <= 0f) return
        // the light in small steps, so a mark's foil brush is made again a few times a second, not every frame
        val lit = Offset(kotlin.math.round(light.x * LIGHT_STEPS) / LIGHT_STEPS, kotlin.math.round(light.y * LIGHT_STEPS) / LIGHT_STEPS)
        val white = (5.5f * s * 3f).coerceIn(2.5f, 7f)
        val dark = white * .45f
        for (pass in 0..2) {
            val border = when (pass) {
                0 -> white
                1 -> dark
                else -> 0f
            }
            for (i in 0 until list.size) {
                val m = list[i]
                val k = s * m.scale
                val cx = ox + m.x * s
                val cy = oy + m.y * s
                val shape = shapes.getValue(m.shape)
                if (pass == 2 && m.shape != MarkShape.BLUSH && foil(shape, cx, cy, k, m.rot, m.alpha, s, lit, foils?.at(i))) continue
                val colour = when (pass) {
                    0 -> PAPER
                    1 -> PLUM
                    else -> if (m.shape.ink == MarkInk.EYE) VIOLET else PINK
                }
                withTransform({
                    translate(cx, cy)
                    if (m.rot != 0f) rotate(m.rot, Offset.Zero)
                    scale(k, k, Offset.Zero)
                }) { fillOf(shape, colour, m.alpha, s, k, border) }
            }
        }
    }

    /** A mark's paths in [colour]; widths in screen pixels over [k], the pixels per mark unit. */
    private fun DrawScope.fillOf(shape: Shape, colour: Color, alpha: Float, s: Float, k: Float, border: Float, blend: BlendMode = BlendMode.SrcOver) {
        shape.parts.forEach { (p, stroke) ->
            if (stroke == 0f) {
                drawPath(p, colour, alpha = alpha, blendMode = blend)
                if (border > 0f) drawPath(p, colour, alpha = alpha, style = Stroke(border / k, join = StrokeJoin.Round), blendMode = blend)
            } else {
                drawPath(p, colour, alpha = alpha, style = Stroke((stroke * 3f * s + border) / k, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
            }
        }
    }

    /**
     * The mark at ([cx], [cy]), [k] pixels per mark unit, turned [rot]: its own foil sheet the size of its bounds, kept
     * only inside the mark. False where there is no runtime shader, and the flat colour is drawn instead.
     */
    private fun DrawScope.foil(shape: Shape, cx: Float, cy: Float, k: Float, rot: Float, alpha: Float, s: Float, light: Offset, cache: HoloCache?): Boolean {
        if (!Holo.available) return false
        // whole pixels, so the sheet's size (one of its inputs) holds still while the mark does
        val r = kotlin.math.ceil(maxOf(shape.bounds.width, shape.bounds.height) * k * .75f + 2f)
        val box = Rect(cx - r, cy - r, cx + r, cy + r)
        var drawn = false
        drawIntoCanvas { canvas ->
            canvas.saveLayer(box, layer)
            // the mark first, then the sheet kept only where the mark is (SrcIn): a sheet masked by a path drawn over
            // it would keep everything the path does not touch
            withTransform({
                translate(cx, cy)
                if (rot != 0f) rotate(rot, Offset.Zero)
                scale(k, k, Offset.Zero)
            }) { fillOf(shape, Color.Black, alpha, s, k, 0f) }
            // the sheet sized to the mark, so its whole rainbow crosses it
            inset(box.left, box.top, size.width - box.right, size.height - box.bottom) {
                drawn = with(Holo) { drawHoloSheet(Rect(Offset.Zero, size), light, cache, blend = BlendMode.SrcIn) }
            }
            canvas.restore()
        }
        return drawn
    }

    /** A steady random in [0, 1) for a frame slot and a salt: the same glitch for the same moment. */
    fun hash(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h.toLong() and 0xFFFFFFFFL) % 100000L) / 100000f
    }

    /** The takeover's box behind [ChessySay]: a soft lilac glow, a lilac sticker edge, white, a lilac line; round. */
    fun DrawScope.sayBox() {
        val r = CornerRadius(18.dp.toPx())
        val small = CornerRadius(6.dp.toPx())
        fun shape(dx: Float, dy: Float) = Path().apply { addRoundRect(RoundRect(Rect(dx, dy, size.width + dx, size.height + dy), r, r, r, small)) }
        val box = shape(0f, 0f)
        for (k in 3 downTo 1) drawPath(box, LILAC.copy(alpha = .1f), style = Stroke(2.dp.toPx() + k * 5.dp.toPx()))
        drawPath(shape(4.dp.toPx(), 5.dp.toPx()), LILAC_DEEP)
        drawPath(box, PAPER)
        drawPath(box, LILAC, style = Stroke(2.dp.toPx()))
    }

    /** Foil hearts and sparkles of the petting mode, in canvas pixels: [list]'s marks with scale in pixels. */
    fun DrawScope.particles(list: MarkList, light: Offset, foils: MarkFoils? = null) = marks(list, 1f, 0f, 0f, light, foils)

    /**
     * The petting mode's cursor (kai, 2026-10: "design a new cursor instead of the crop in this mode because it would
     * make more sense for sensitive interaction"): a soft paw, paper with an ink edge so it reads on her and on paper,
     * her pink in its beans, centred on the point it touches; pressed, it squashes onto her a little. [ink] and [paper]
     * are the theme's; [s] pixels a dp.
     */
    fun DrawScope.drawPaw(at: Offset, s: Float, pressed: Boolean, ink: Color, paper: Color) {
        val squash = if (pressed) .86f else 1f
        val spread = if (pressed) 1.08f else 1f
        withTransform({
            translate(at.x, at.y)
            scale(spread * s, squash * s, Offset.Zero)
        }) {
            val edge = Stroke(1.4f)
            // the pad: wide at the heel, three soft lobes along its top
            val pad = Path().apply {
                moveTo(-6.6f, 4.2f)
                cubicTo(-7.6f, 1.2f, -4.6f, -1.6f, -2.2f, -.9f)
                cubicTo(-1.2f, -1.8f, 1.2f, -1.8f, 2.2f, -.9f)
                cubicTo(4.6f, -1.6f, 7.6f, 1.2f, 6.6f, 4.2f)
                cubicTo(5.8f, 7.4f, 2.6f, 8.2f, 0f, 7.0f)
                cubicTo(-2.6f, 8.2f, -5.8f, 7.4f, -6.6f, 4.2f)
                close()
            }
            drawPath(pad, paper)
            drawPath(pad, ink, style = edge)
            drawOval(PINK.copy(alpha = .9f), Offset(-3.8f, 1.2f), Size(7.6f, 4.4f))
            // four toes, fanned over it
            for ((x, y, r) in TOES) {
                withTransform({ rotate(r, Offset(x, y)) }) {
                    drawOval(paper, Offset(x - 2.4f, y - 3f), Size(4.8f, 6f))
                    drawOval(ink, Offset(x - 2.4f, y - 3f), Size(4.8f, 6f), style = edge)
                    drawOval(PINK.copy(alpha = .9f), Offset(x - 1.25f, y - 1.6f), Size(2.5f, 3.2f))
                }
            }
        }
    }

    /** The paw's toes: where each sits (dp from the touch) and how it leans. */
    private val TOES = listOf(Triple(-8.4f, -3.4f, -24f), Triple(-3.1f, -7.6f, -8f), Triple(3.1f, -7.6f, 8f), Triple(8.4f, -3.4f, 24f))

    /** How finely the foil's light moves: twenty steps a unit. */
    private const val LIGHT_STEPS = 20f
}

// ---- her box, her aura and her name (kai, 2026-10) -----------------------------------------------------------------

/**
 * What she says in the petting mode, set as the takeover sets it (kai: "apply the same text box styling and animations"):
 * white and lilac, her name on top, a little tilted, round where Master UI is square: the box is hers, not the app's
 * (kai's word; `MasterUiLawTest` names this file for its corners, and the lilac block behind it is a sticker's edge,
 * not a shadow). The whole line is laid out from its first letter ([ChessyType.layout]) and only what is typed is
 * coloured, so nothing reflows as it types and an emoticon arrives whole, never split across lines. Set to be read at a
 * glance (kai, 1.1.28: "the chat boxes are also hard to read"): [textSize] large, medium weight, near-black plum.
 */
@Composable
internal fun ChessySay(line: String, typed: Int, name: String, tilt: Float, caretOn: () -> Boolean, modifier: Modifier = Modifier, jitter: () -> Float = { 0f }, textSize: TextUnit = 17.sp) {
    val laid = remember(line) { ChessyType.layout(line) }
    val shownTo = laid.typedTo(typed)
    val text = remember(laid, shownTo) {
        buildAnnotatedString {
            append(laid.text)
            if (shownTo < laid.text.length) addStyle(SpanStyle(color = Color.Transparent), shownTo, laid.text.length)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fonts = LocalMuFonts.current
    Column(
        modifier
            .graphicsLayer { rotationZ = tilt; translationX = jitter() }
            .drawBehind { with(ChessyInk) { sayBox() } }
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 15.dp),
    ) {
        BasicText(
            name.uppercase() + " ♡",
            style = TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = .14.em, color = ChessyInk.VIOLET),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            text,
            style = TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.Medium, fontSize = textSize, lineHeight = textSize * 1.42f, color = ChessyInk.WORDS),
            onTextLayout = { layout = it },
            modifier = Modifier.drawWithContent {
                drawContent()
                // the caret where the next letter goes; it takes no room
                val l = layout
                if (l != null && shownTo < laid.text.length && caretOn()) {
                    val r = l.getCursorRect(shownTo)
                    drawRect(ChessyInk.LILAC, Offset(r.left + 1.dp.toPx(), r.top + r.height * .08f), Size(6.dp.toPx(), r.height * .84f))
                }
            },
        )
    }
}

/**
 * Her aura (kai: "give her a cute aura similar to the takeover"): her own violet and pink, as two copies of her that
 * slip to either side and now and then tear, over a glow that breathes. The copies are her drawing recorded once and
 * drawn again tinted, so they blink and turn exactly as she does. [clock] is read when drawn, in seconds.
 */
@Composable
internal fun Modifier.chessyAura(clock: () -> Float): Modifier {
    val layer = rememberGraphicsLayer()
    val tints = remember { listOf(ChessyInk.VIOLET, ChessyInk.PINK).map { Paint().apply { colorFilter = ColorFilter.tint(it, BlendMode.SrcIn) } } }
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        val t = clock()
        val breath = .75f + .25f * kotlin.math.sin(t * 2.4f)
        val radius = size.minDimension * .62f
        drawCircle(
            Brush.radialGradient(0f to ChessyInk.VIOLET.copy(alpha = .5f * breath), .6f to ChessyInk.PINK.copy(alpha = .24f * breath), 1f to Color.Transparent, center = center, radius = radius),
            radius,
            center,
        )
        val slot = kotlin.math.floor(t * 12f).toInt()
        for (i in 0..1) {
            val tear = ChessyInk.hash(slot, 40 + i) < .45f
            val off = (if (i == 0) 1f else -1f) * size.width * (.022f + ChessyInk.hash(slot, 50 + i) * (if (tear) .06f else .022f))
            val paint = tints[i]
            paint.alpha = if (tear) .9f else .62f
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size).inflate(size.width * .2f), paint)
                translate(off, 0f) {
                    if (tear) {
                        val top = ChessyInk.hash(slot, 60 + i) * .75f * size.height
                        val tall = (.12f + ChessyInk.hash(slot, 70 + i) * .3f) * size.height
                        clipRect(0f, top, size.width, top + tall) { drawLayer(layer) }
                    } else {
                        drawLayer(layer)
                    }
                }
                canvas.restore()
            }
        }
        drawLayer(layer)
    }
}

/**
 * Her name with a glitch, for flavour (kai: "a glitchy font"), where a new chat meets her, large: a pink and a violet
 * copy split either side of it, and every few seconds a short tear, a slice of it thrown sideways for a few frames.
 * Between tears nothing redraws. [style] sets it (the mono face at [size] when none).
 */
@Composable
internal fun ChessyGlitchName(name: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 12.sp, style: TextStyle? = null) {
    var tear by remember { mutableIntStateOf(0) }
    LaunchedEffect(name) {
        val r = kotlin.random.Random(name.hashCode())
        while (true) {
            delay(1800L + r.nextLong(3200L))
            repeat(5) { tear = r.nextInt(1, 1000); delay(55L) }
            tear = 0
        }
    }
    val tints = remember { listOf(ChessyInk.PINK, ChessyInk.VIOLET).map { Paint().apply { colorFilter = ColorFilter.tint(it, BlendMode.SrcIn); alpha = .85f } } }
    val set = style?.copy(color = color) ?: TextStyle(fontFamily = LocalMuFonts.current.mono, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = .04.em, color = color)
    // the split grows with the letters: a pixel at 12 sp
    val reach = (if (set.fontSize.isSp) set.fontSize.value else 12f) / 12f
    BasicText(
        name,
        style = set,
        maxLines = 1,
        modifier = modifier.drawWithContent {
            val t = tear
            val split = 1.dp.toPx() * reach * (if (t == 0) 1f else 2.6f)
            for ((i, paint) in tints.withIndex()) {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, this.size).inflate(8.dp.toPx()), paint)
                    translate(if (i == 0) split else -split, 0f) { this@drawWithContent.drawContent() }
                    canvas.restore()
                }
            }
            drawContent()
            if (t != 0) {
                val top = ChessyInk.hash(t, 1) * this.size.height * .7f
                val tall = this.size.height * (.18f + ChessyInk.hash(t, 2) * .3f)
                val dx = (ChessyInk.hash(t, 3) - .5f) * 8.dp.toPx() * reach
                clipRect(-8.dp.toPx(), top, this.size.width + 8.dp.toPx(), top + tall) { translate(dx, 0f) { this@drawWithContent.drawContent() } }
            }
        },
    )
}

/**
 * Each mark's foil brush, kept between frames by its place in its run (the performance pass, kai: "my hardware was
 * lagging quite badly when chessy was live, especially if left on for long periods"): a brush is a native shader, and
 * one made for every mark on every frame was garbage the size of the hours she was on screen. Kept, it is made again
 * only when its light or size changes. One per run of marks, drawn on the thread that draws it.
 */
class MarkFoils {
    private val caches = ArrayList<HoloCache>()

    fun at(i: Int): HoloCache {
        while (caches.size <= i) caches += HoloCache()
        return caches[i]
    }
}
