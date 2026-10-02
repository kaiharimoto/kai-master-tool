package com.kaiharimoto.neue.present.paint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill as DrawFill
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.present.Chart
import com.kaiharimoto.mastertool.core.present.DeckSnapshot
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Fill
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideColor
import com.kaiharimoto.mastertool.core.present.SlideFonts
import com.kaiharimoto.mastertool.core.present.Stroke
import com.kaiharimoto.mastertool.core.present.Theme
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.play.ElementState
import com.kaiharimoto.mastertool.core.present.stage.Box as CanvasBox
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import com.kaiharimoto.mastertool.core.present.stage.StageFrame
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.LocalArts
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.qr.QrMatrix
import com.kaiharimoto.neue.qr.QrPicture
import com.kaiharimoto.neue.res.Res
import com.kaiharimoto.neue.res.inter_bold
import com.kaiharimoto.neue.res.inter_medium
import com.kaiharimoto.neue.res.inter_regular
import com.kaiharimoto.neue.res.jetbrainsmono_medium
import com.kaiharimoto.neue.res.jetbrainsmono_regular
import com.kaiharimoto.neue.res.slide_bebas_regular
import com.kaiharimoto.neue.res.slide_marker_regular
import com.kaiharimoto.neue.res.slide_oswald_bold
import com.kaiharimoto.neue.res.slide_oswald_medium
import com.kaiharimoto.neue.res.slide_oswald_regular
import com.kaiharimoto.neue.res.slide_playfair_bold
import com.kaiharimoto.neue.res.slide_playfair_regular
import org.jetbrains.compose.resources.Font
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The slide painter (1.0.70): one way of drawing a slide, used by the editor's canvas, the
 * sorter's pictures, the presenter and the recording. Slides are the creator's content
 * (kai: "slides are content: full colour"), so this file — with [SlideColors] — may draw
 * colour, gradients, rounded shapes and shadows; `MasterUiLawTest` allows them here alone.
 */

/** The faces a slide is set in, by [SlideFonts] id. */
@Immutable
class SlideFontSet(private val families: Map<String, FontFamily>) {
    fun of(id: String?): FontFamily = families[id] ?: families[SlideFonts.INTER] ?: FontFamily.SansSerif
}

@Composable
fun rememberSlideFonts(): SlideFontSet {
    val inter = FontFamily(Font(Res.font.inter_regular, FontWeight.Normal), Font(Res.font.inter_medium, FontWeight.Medium), Font(Res.font.inter_bold, FontWeight.Bold))
    val mono = FontFamily(Font(Res.font.jetbrainsmono_regular, FontWeight.Normal), Font(Res.font.jetbrainsmono_medium, FontWeight.Medium))
    val bebas = FontFamily(Font(Res.font.slide_bebas_regular, FontWeight.Normal))
    val oswald = FontFamily(Font(Res.font.slide_oswald_regular, FontWeight.Normal), Font(Res.font.slide_oswald_medium, FontWeight.Medium), Font(Res.font.slide_oswald_bold, FontWeight.Bold))
    val playfair = FontFamily(Font(Res.font.slide_playfair_regular, FontWeight.Normal), Font(Res.font.slide_playfair_bold, FontWeight.Bold))
    val marker = FontFamily(Font(Res.font.slide_marker_regular, FontWeight.Normal))
    return remember(inter, mono, bebas, oswald, playfair, marker) {
        SlideFontSet(
            mapOf(
                SlideFonts.INTER to inter, SlideFonts.MONO to mono, SlideFonts.BEBAS to bebas,
                SlideFonts.OSWALD to oswald, SlideFonts.PLAYFAIR to playfair, SlideFonts.MARKER to marker,
            ),
        )
    }
}

/** Everything a slide is drawn with besides the slide itself. */
@Immutable
class SlideContext(
    val presentation: Presentation,
    val fonts: SlideFontSet,
    val cards: (Int) -> Card?,
    val bitmap: (String?) -> ImageBitmap?,
    val format: Format = Format.TCG,
    val foil: String = "holo",
) {
    val theme: Theme = Themes.of(presentation)
    val deck: DeckSnapshot? get() = presentation.deck

    fun color(value: String?): Color? = SlideColor.argb(value, theme)?.let { Color(it) }

    fun color(value: String?, fallback: String): Color = color(value) ?: color(fallback) ?: Color.Black

    /** A group's colour, read through the palette the deck was drawn in. */
    fun groupColor(index: Int): Color {
        val palette = GroupMarkers.palettes.getOrElse(deck?.palette ?: 0) { GroupMarkers.palettes.first() }
        return palette.colors[((index % palette.colors.size) + palette.colors.size) % palette.colors.size]
    }
}

/**
 * A slide, letterboxed into whatever box it is given at 16:9.
 *
 * - [deck] is the deck's frame for a deck slide, read every frame in the layout and draw
 *   phases only — a glide never recomposes; [deckKeys] are the copies it may show.
 * - [state] is each element's build state, read in the layer, likewise.
 * - [hidden] are elements not drawn (the one whose words are being edited in place).
 * - [camera] stands in the webcam zone: the live picture once there is one (phase 3).
 */
@Composable
fun SlideView(
    ctx: SlideContext,
    slide: Slide,
    zone: CanvasBox?,
    stage: CanvasBox,
    modifier: Modifier = Modifier,
    deck: (() -> StageFrame?)? = null,
    deckKeys: List<String> = emptyList(),
    state: (Element) -> ElementState = { ElementState.SHOWN },
    hidden: Set<String> = emptySet(),
    editing: Boolean = false,
    drawBackground: Boolean = true,
    drawElements: Boolean = true,
    camera: (@Composable () -> Unit)? = null,
    /** How much of the background and the elements shows — never the deck, which glides on its own. */
    fade: () -> Float = { 1f },
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val s = min(wPx / Presentation.WIDTH, hPx / Presentation.HEIGHT).coerceAtLeast(0.0001f)
        val cw = with(density) { (Presentation.WIDTH * s).toDp() }
        val ch = with(density) { (Presentation.HEIGHT * s).toDp() }
        val arts = LocalArts.current
        val deckArts = ctx.deck?.arts.orEmpty()
        CompositionLocalProvider(LocalArts provides (arts + deckArts)) {
            Box(Modifier.size(cw, ch).clipToBounds()) {
                if (drawBackground) Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade().coerceIn(0f, 1f) }) { Background(ctx, slide, s) }
                if (deck != null) DeckLayer(ctx, deck, deckKeys, s, editing)
                if (drawElements) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade().coerceIn(0f, 1f) }) {
                        slide.elements.forEach { e ->
                            if (e.id in hidden) return@forEach
                            ElementView(ctx, e, stage, s, zone, state, editing, camera)
                        }
                    }
                }
                if (zone != null) CameraZone(ctx, zone, s, editing, camera)
            }
        }
    }
}

// ---- background ----------------------------------------------------------------------

@Composable
private fun Background(ctx: SlideContext, slide: Slide, s: Float) {
    val theme = ctx.theme
    val fill = slide.background
    val picture = fill?.media?.let { ctx.bitmap(it) }
    Canvas(Modifier.fillMaxSize()) {
        val brush = when {
            fill != null -> brushOf(ctx, fill, size)
            theme.backgroundTo != null -> Brush.linearGradient(
                listOf(ctx.color("@bg", "#000000"), ctx.color(theme.backgroundTo, "#000000")),
                Offset.Zero, Offset(size.width * 0.4f, size.height),
            )
            else -> Brush.linearGradient(listOf(ctx.color("@bg", "#000000"), ctx.color("@bg", "#000000")))
        }
        drawRect(brush)
        if (picture != null) {
            drawCover(picture, Offset.Zero, size, emptyList())
            if (fill.scrim > 0f) drawRect(Color.Black.copy(alpha = fill.scrim.coerceIn(0f, 0.9f)))
        }
    }
}

private fun brushOf(ctx: SlideContext, fill: Fill, size: Size): Brush {
    val stops = fill.stops.mapNotNull { ctx.color(it) }.ifEmpty { listOf(ctx.color(fill.color, "@surface")) }
    return when (fill.kind) {
        Fill.LINEAR -> if (stops.size < 2) Brush.linearGradient(listOf(stops[0], stops[0])) else {
            val a = fill.angle * PI.toFloat() / 180f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = max(size.width, size.height) / 2f
            Brush.linearGradient(stops, Offset(cx - cos(a) * r, cy - sin(a) * r), Offset(cx + cos(a) * r, cy + sin(a) * r))
        }
        Fill.RADIAL -> if (stops.size < 2) Brush.linearGradient(listOf(stops[0], stops[0])) else {
            Brush.radialGradient(stops, Offset(size.width / 2f, size.height / 2f), max(size.width, size.height) * 0.7f)
        }
        else -> Brush.linearGradient(listOf(stops[0], stops[0]))
    }
}

// ---- placing things on the canvas ----------------------------------------------------

/** Laid out at [box] on the canvas, [s] pixels to the canvas unit; the node spans the canvas. */
internal fun Modifier.onCanvas(s: Float, box: () -> CanvasBox): Modifier = layout { measurable, constraints ->
    val b = box()
    val w = (b.w * s).roundToInt().coerceAtLeast(1)
    val h = (b.h * s).roundToInt().coerceAtLeast(1)
    val p = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { p.place((b.x * s).roundToInt(), (b.y * s).roundToInt()) }
}

@Composable
private fun ElementView(
    ctx: SlideContext,
    e: Element,
    stage: CanvasBox,
    s: Float,
    zone: CanvasBox?,
    state: (Element) -> ElementState,
    editing: Boolean,
    camera: (@Composable () -> Unit)?,
) {
    val box = com.kaiharimoto.mastertool.core.present.Geometry.box(e, stage)
    Box(
        Modifier
            .onCanvas(s) { box }
            .graphicsLayer {
                val st = state(e)
                alpha = (st.alpha * e.opacity).coerceIn(0f, 1f)
                translationX = st.dx * s
                translationY = st.dy * s
                scaleX = st.scale
                scaleY = st.scale
                rotationZ = e.rotation + st.rotation
                transformOrigin = TransformOrigin.Center
            }
            .drawWithContent {
                val st = state(e)
                if (st.glow > 0.01f) {
                    val g = ctx.color("@accent", "#FFFFFF").copy(alpha = 0.55f * st.glow)
                    val pad = 18f * s
                    val p = Path().apply { addRect(Rect(-pad, -pad, size.width + pad, size.height + pad)) }
                    slideShadow(p, g, 28f * s)
                }
                if (st.clip < 1f) clipRect(right = size.width * st.clip) { this@drawWithContent.drawContent() } else drawContent()
            },
    ) {
        when (e.type) {
            Element.TEXT -> TextBlock(ctx, e, box, s, state)
            Element.SHAPE -> ShapeBlock(ctx, e, box, s, state)
            Element.IMAGE -> ImageBlock(ctx, e, s, editing)
            Element.CARD -> CardsBlock(ctx, e, box, s, single = true)
            Element.CARDS -> CardsBlock(ctx, e, box, s, single = false)
            Element.DECK -> DeckElement(ctx, e, box, s, editing)
            Element.CAMERA -> CameraElement(ctx, e, s, editing, camera)
            Element.TABLE -> TableBlock(ctx, e, box, s)
            Element.CHART -> ChartBlock(ctx, e, box, s)
            Element.STAT -> StatBlock(ctx, e, box, s)
            Element.QR -> e.qr?.let { QrMatrix.of(it) }?.let { QrPicture(it, Modifier.fillMaxSize()) }
            else -> if (editing) Placeholder(ctx, s, Element.typeName(e.type))
        }
    }
}

@Composable
private fun Placeholder(ctx: SlideContext, s: Float, words: String) {
    val measurer = rememberTextMeasurer()
    val line = ctx.color("@muted", "#888888")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(line.copy(alpha = 0.12f))
        val step = 18f * s
        var x = -size.height
        while (x < size.width) {
            drawLine(line.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5f * s)
            x += step
        }
        drawRect(line.copy(alpha = 0.6f), style = DrawStroke(2f * s))
        val t = measurer.measure(words, TextStyle(color = line, fontSize = (26f * s / density / fontScale).sp))
        drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (size.height - t.size.height) / 2f))
    }
}

// ---- text ----------------------------------------------------------------------------

/** A role's look from the theme: face, size in canvas units, weight, colour token. */
internal data class RoleLook(val font: String, val size: Float, val weight: Int, val color: String, val caps: Boolean = false, val tracking: Float = 0f)

internal fun roleLook(role: String, theme: Theme): RoleLook = when (role) {
    Element.ROLE_TITLE -> RoleLook(theme.headingFont, 84f, if (theme.headingFont == SlideFonts.BEBAS) 400 else 700, "@text", theme.capsTitles, if (theme.capsTitles) 0.02f else -0.01f)
    Element.ROLE_SUBTITLE -> RoleLook(theme.bodyFont, 44f, 500, "@muted")
    Element.ROLE_CAPTION -> RoleLook(theme.bodyFont, 28f, 400, "@muted")
    else -> RoleLook(theme.bodyFont, 36f, 400, "@text")
}

/**
 * [paras] as one styled string, every size in canvas units times [px] (pixels to the unit)
 * times [fit] (the shrink that makes it fit its box).
 */
internal fun styledText(
    ctx: SlideContext,
    paras: List<Para>,
    role: String,
    px: Float,
    fit: Float,
    density: Density,
    reveal: Float = 1f,
): AnnotatedString {
    val look = roleLook(role, ctx.theme)
    val total = paras.sumOf { it.text.length + 1 }
    var budget = if (reveal >= 1f) Int.MAX_VALUE else (total * reveal).toInt()
    var number = 0
    return buildAnnotatedString {
        paras.forEachIndexed { i, p ->
            val lineHeight = p.lineHeight.coerceIn(0.8f, 3f)
            withStyle(
                ParagraphStyle(
                    textAlign = when (p.align) {
                        Para.ALIGN_CENTER -> TextAlign.Center
                        Para.ALIGN_RIGHT -> TextAlign.End
                        else -> TextAlign.Start
                    },
                    lineHeight = lineHeight.em,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                ),
            ) {
                if (p.list != Para.LIST_NONE && p.text.isNotEmpty()) {
                    number++
                    val first = p.runs.firstOrNull()?.style ?: RunStyle()
                    withStyle(spanOf(ctx, first, look, px, fit, density)) {
                        append("    ".repeat(p.indent.coerceIn(0, 4)))
                        append(if (p.list == Para.LIST_NUMBER) "$number.  " else "•  ")
                    }
                } else if (p.list == Para.LIST_NONE) {
                    number = 0
                }
                for (r in p.runs) {
                    if (budget <= 0) break
                    val words = (if (r.style.caps || look.caps) r.text.uppercase() else r.text).let { if (it.length > budget) it.take(budget) else it }
                    budget -= words.length
                    withStyle(spanOf(ctx, r.style, look, px, fit, density)) { append(words) }
                }
                if (p.runs.isEmpty() || p.text.isEmpty()) {
                    withStyle(spanOf(ctx, p.runs.firstOrNull()?.style ?: RunStyle(), look, px, fit, density)) { append("​") }
                }
                budget -= 1
            }
        }
        if (paras.isEmpty()) append("")
    }
}

private fun spanOf(ctx: SlideContext, r: RunStyle, look: RoleLook, px: Float, fit: Float, density: Density): SpanStyle {
    val size = (r.size ?: look.size) * px * fit
    val weight = when (r.weight ?: look.weight) {
        in 0..449 -> FontWeight.Normal
        in 450..599 -> FontWeight.Medium
        else -> FontWeight.Bold
    }
    val deco = listOfNotNull(TextDecoration.Underline.takeIf { r.underline }, TextDecoration.LineThrough.takeIf { r.strike })
    return SpanStyle(
        color = ctx.color(r.color, look.color),
        fontSize = with(density) { size.toSp() },
        fontWeight = weight,
        fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = ctx.fonts.of(r.font ?: look.font),
        letterSpacing = (r.tracking ?: look.tracking).em,
        background = ctx.color(r.highlight) ?: Color.Unspecified,
        textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
    )
}

/**
 * The shrink that fits [e]'s words in a [w] × [h] pixel box: the largest of a few steps down
 * to a third whose laid-out text is no taller than the box.
 */
internal fun fitOf(measurer: TextMeasurer, build: (Float) -> AnnotatedString, w: Int, h: Int, shrink: Boolean): Pair<Float, TextLayoutResult> {
    // A fixed width: measured text otherwise shrink-wraps, and a centred line lands on the left.
    val constraints = Constraints.fixedWidth(w.coerceAtLeast(1))
    val full = measurer.measure(build(1f), constraints = constraints)
    if (!shrink || (full.size.height <= h && !full.hasVisualOverflow)) return 1f to full
    var lo = 0.3f
    var hi = 1f
    var best: Pair<Float, TextLayoutResult>? = null
    repeat(7) {
        val mid = (lo + hi) / 2f
        val r = measurer.measure(build(mid), constraints = constraints)
        if (r.size.height <= h) {
            best = mid to r
            lo = mid
        } else {
            hi = mid
        }
    }
    return best ?: (0.3f to measurer.measure(build(0.3f), constraints = constraints))
}

@Composable
private fun TextBlock(ctx: SlideContext, e: Element, box: CanvasBox, s: Float, state: (Element) -> ElementState) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The typewriter reads its share in composition: a typing build recomposes this block alone.
    val reveal = state(e).reveal
    val pad = e.padding * s
    val w = (box.w * s - pad * 2).roundToInt()
    val h = (box.h * s - pad * 2).roundToInt()
    val fitted = remember(e.paras, e.role, e.fit, ctx.theme, w, h, s) {
        fitOf(measurer, { f -> styledText(ctx, e.paras, e.role, s, f, density) }, w, h, e.fit == Element.FIT_SHRINK).first
    }
    val layout = remember(e.paras, e.role, fitted, ctx.theme, w, s, reveal) {
        measurer.measure(styledText(ctx, e.paras, e.role, s, fitted, density, reveal), constraints = Constraints.fixedWidth(w.coerceAtLeast(1)))
    }
    Canvas(Modifier.fillMaxSize()) {
        e.fill?.let { drawRect(brushOf(ctx, it, size)) }
        val top = when (e.vAlign) {
            Element.V_MIDDLE -> (size.height - layout.size.height) / 2f
            Element.V_BOTTOM -> size.height - pad - layout.size.height
            else -> pad
        }
        drawText(layout, topLeft = Offset(pad, top.coerceAtLeast(0f)))
        e.stroke?.let { drawRect(ctx.color(it.color, "@line"), style = strokeOf(it, s)) }
    }
}

private fun strokeOf(st: Stroke, s: Float): DrawStroke = DrawStroke(
    width = (st.width * s).coerceAtLeast(1f),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = when (st.dash) {
        Stroke.DASH_DASHED -> PathEffect.dashPathEffect(floatArrayOf(st.width * 4f * s, st.width * 3f * s))
        Stroke.DASH_DOTTED -> PathEffect.dashPathEffect(floatArrayOf(st.width * 0.1f * s, st.width * 2.5f * s))
        else -> null
    },
)

// ---- shapes ----------------------------------------------------------------------------

/** [kind]'s outline in a [w] × [h] box, [corner] its rounding in pixels. */
internal fun shapePath(kind: String, w: Float, h: Float, corner: Float): Path = Path().apply {
    when (kind) {
        Element.SHAPE_ROUNDED -> addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(corner.coerceAtMost(min(w, h) / 2f))))
        Element.SHAPE_ELLIPSE -> addOval(Rect(0f, 0f, w, h))
        Element.SHAPE_TRIANGLE -> { moveTo(w / 2f, 0f); lineTo(w, h); lineTo(0f, h); close() }
        Element.SHAPE_DIAMOND -> { moveTo(w / 2f, 0f); lineTo(w, h / 2f); lineTo(w / 2f, h); lineTo(0f, h / 2f); close() }
        Element.SHAPE_STAR -> {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.5f else 0.21f
                val a = -PI / 2 + i * PI / 5
                val x = w / 2f + (cos(a) * r * w).toFloat()
                val y = h / 2f + (sin(a) * r * h).toFloat() + h * 0.04f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        Element.SHAPE_CHEVRON -> {
            val d = min(w * 0.35f, h * 0.5f)
            moveTo(0f, 0f); lineTo(w - d, 0f); lineTo(w, h / 2f); lineTo(w - d, h); lineTo(0f, h); lineTo(d, h / 2f); close()
        }
        Element.SHAPE_ARROW -> {
            val head = min(w * 0.4f, h)
            val shaft = h * 0.28f
            moveTo(0f, shaft); lineTo(w - head, shaft); lineTo(w - head, 0f); lineTo(w, h / 2f)
            lineTo(w - head, h); lineTo(w - head, h - shaft); lineTo(0f, h - shaft); close()
        }
        Element.SHAPE_CALLOUT -> {
            val tail = h * 0.22f
            val body = h - tail
            addRoundRect(RoundRect(Rect(0f, 0f, w, body), CornerRadius(min(corner.coerceAtLeast(16f), body / 2f))))
            moveTo(w * 0.16f, body - 1f); lineTo(w * 0.1f, h); lineTo(w * 0.32f, body - 1f); close()
        }
        Element.SHAPE_LINE, Element.SHAPE_ARROW_LINE -> { moveTo(0f, h / 2f); lineTo(w, h / 2f) }
        else -> addRect(Rect(0f, 0f, w, h))
    }
}

@Composable
private fun ShapeBlock(ctx: SlideContext, e: Element, box: CanvasBox, s: Float, state: (Element) -> ElementState) {
    val line = e.shape == Element.SHAPE_LINE || e.shape == Element.SHAPE_ARROW_LINE
    Canvas(Modifier.fillMaxSize()) {
        val path = shapePath(e.shape, size.width, size.height, e.corner * s)
        if (!line) {
            e.shadow?.let { sh ->
                withTransform({ translate(sh.dx * s, sh.dy * s) }) { slideShadow(path, ctx.color(sh.color, "#66000000"), sh.blur * s) }
            }
            e.fill?.let { drawPath(path, brushOf(ctx, it, size), style = DrawFill) }
            e.stroke?.let { drawPath(path, ctx.color(it.color, "@line"), style = strokeOf(it, s)) }
        } else {
            val st = e.stroke ?: Stroke("@line", 6f)
            val color = ctx.color(st.color, "@line")
            drawPath(path, color, style = strokeOf(st, s))
            if (e.shape == Element.SHAPE_ARROW_LINE) {
                val head = (st.width * 4f * s).coerceAtLeast(10f)
                val y = size.height / 2f
                val tip = Path().apply { moveTo(size.width, y); lineTo(size.width - head * 1.4f, y - head); lineTo(size.width - head * 1.4f, y + head); close() }
                drawPath(tip, color)
            }
        }
    }
    if (e.paras.isNotEmpty() && !line) TextBlock(ctx, e.copy(fill = null, stroke = null, padding = max(e.padding, 24f)), box, s, state)
}

// ---- pictures --------------------------------------------------------------------------

/** [image] drawn to cover [size] at [at], cut to [crop] (fractions left, top, right, bottom) first. */
private fun DrawScope.drawCover(image: ImageBitmap, at: Offset, size: Size, crop: List<Float>, contain: Boolean = false) {
    val l = (crop.getOrNull(0) ?: 0f).coerceIn(0f, 0.95f)
    val t = (crop.getOrNull(1) ?: 0f).coerceIn(0f, 0.95f)
    val r = (crop.getOrNull(2) ?: 1f).coerceIn(l + 0.01f, 1f)
    val b = (crop.getOrNull(3) ?: 1f).coerceIn(t + 0.01f, 1f)
    val sx = image.width * l
    val sy = image.height * t
    val sw = image.width * (r - l)
    val sh = image.height * (b - t)
    val boxAspect = size.width / size.height
    val srcAspect = sw / sh
    if (contain) {
        val dw = if (srcAspect > boxAspect) size.width else size.height * srcAspect
        val dh = dw / srcAspect
        drawImage(
            image, IntOffset(sx.roundToInt(), sy.roundToInt()), IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
            IntOffset((at.x + (size.width - dw) / 2f).roundToInt(), (at.y + (size.height - dh) / 2f).roundToInt()), IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
        )
        return
    }
    var cx = sx
    var cy = sy
    var cw = sw
    var chh = sh
    if (srcAspect > boxAspect) {
        cw = sh * boxAspect
        cx = sx + (sw - cw) / 2f
    } else {
        chh = sw / boxAspect
        cy = sy + (sh - chh) / 2f
    }
    drawImage(
        image, IntOffset(cx.roundToInt(), cy.roundToInt()), IntSize(cw.roundToInt().coerceAtLeast(1), chh.roundToInt().coerceAtLeast(1)),
        IntOffset(at.x.roundToInt(), at.y.roundToInt()), IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
    )
}

@Composable
private fun ImageBlock(ctx: SlideContext, e: Element, s: Float, editing: Boolean) {
    val image = ctx.bitmap(e.media)
    if (image == null) {
        if (editing || e.media == null) Placeholder(ctx, s, if (e.media == null) "Picture" else "…")
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        val path = shapePath(if (e.shape == Element.SHAPE_RECT) Element.SHAPE_RECT else e.shape, size.width, size.height, e.corner * s)
        e.shadow?.let { sh -> withTransform({ translate(sh.dx * s, sh.dy * s) }) { slideShadow(path, ctx.color(sh.color, "#66000000"), sh.blur * s) } }
        clipPath(path) { drawCover(image, Offset.Zero, size, e.crop, contain = e.imageFit == Element.FIT_CONTAIN) }
        e.stroke?.let { drawPath(path, ctx.color(it.color, "@line"), style = strokeOf(it, s)) }
    }
}

// ---- cards ----------------------------------------------------------------------------

@Composable
private fun CardsBlock(ctx: SlideContext, e: Element, box: CanvasBox, s: Float, single: Boolean) {
    val ids = if (single) e.cards.take(1) else e.cards
    if (ids.isEmpty()) {
        Placeholder(ctx, s, if (single) "Card" else "Cards")
        return
    }
    val density = LocalDensity.current
    val labels = e.cardLabels && !single
    val labelRoom = if (labels) 52f else 0f
    val n = ids.size
    val gap = 24f
    val area = CanvasBox(0f, 0f, box.w, box.h - labelRoom)
    val places: List<Pair<CanvasBox, Float>> = when {
        single -> listOf(area.fitted(CARD_RATIO) to 0f)
        e.cardLayout == Element.CARDS_FAN -> {
            val h = min(area.h * 0.86f, area.w / (1f + (n - 1) * 0.42f) / CARD_RATIO)
            val w = h * CARD_RATIO
            val step = if (n > 1) min(w * 0.55f, (area.w - w) / (n - 1)) else 0f
            val total = w + step * (n - 1)
            List(n) { i ->
                val t = if (n > 1) i / (n - 1f) - 0.5f else 0f
                CanvasBox((area.w - total) / 2f + i * step, (area.h - h) / 2f + kotlin.math.abs(t) * h * 0.12f, w, h) to t * 24f
            }
        }
        e.cardLayout == Element.CARDS_GRID -> {
            val fit = com.kaiharimoto.mastertool.core.layout.GridFitter.fit(n, area.w, area.h, gap, CARD_RATIO, 1, n)
            val cols = fit.columns
            val rows = (n + cols - 1) / cols
            val w = min((area.w - gap * (cols - 1)) / cols, (area.h - gap * (rows - 1)) / rows * CARD_RATIO)
            val h = w / CARD_RATIO
            val totalH = rows * h + (rows - 1) * gap
            List(n) { i ->
                val r = i / cols
                val inRow = if (r == rows - 1) n - r * cols else cols
                val left = (area.w - (inRow * w + (inRow - 1) * gap)) / 2f
                CanvasBox(left + (i % cols) * (w + gap), (area.h - totalH) / 2f + r * (h + gap), w, h) to 0f
            }
        }
        else -> {
            val w = min((area.w - gap * (n - 1)) / n, area.h * CARD_RATIO)
            val h = w / CARD_RATIO
            val total = n * w + (n - 1) * gap
            List(n) { i -> CanvasBox((area.w - total) / 2f + i * (w + gap), (area.h - h) / 2f, w, h) to 0f }
        }
    }
    Box(Modifier.fillMaxSize()) {
        ids.forEachIndexed { i, id ->
            val card = ctx.cards(id) ?: return@forEachIndexed
            val (b, tilt) = places[i]
            Box(Modifier.onCanvas(s) { b }.graphicsLayer { rotationZ = tilt }) {
                NeueCard(card, Modifier.fillMaxSize(), format = ctx.format, foil = ctx.foil)
            }
        }
        if (labels) {
            val measurer = rememberTextMeasurer()
            val look = roleLook(Element.ROLE_CAPTION, ctx.theme)
            Canvas(Modifier.fillMaxSize()) {
                ids.forEachIndexed { i, id ->
                    val name = ctx.cards(id)?.name ?: return@forEachIndexed
                    val b = places[i].first
                    val style = TextStyle(
                        color = ctx.color(look.color, "#888888"),
                        fontSize = with(density) { (22f * s).toSp() },
                        fontFamily = ctx.fonts.of(look.font),
                        textAlign = TextAlign.Center,
                    )
                    val t = measurer.measure(name, style, constraints = Constraints.fixedWidth((b.w * s + gap * s).roundToInt().coerceAtLeast(1)), maxLines = 2)
                    drawText(t, topLeft = Offset(b.cx * s - t.size.width / 2f, (area.bottom + 8f) * s))
                }
            }
        }
    }
}

@Composable
private fun DeckElement(ctx: SlideContext, e: Element, box: CanvasBox, s: Float, editing: Boolean) {
    val deck = ctx.deck
    if (deck == null || deck.isEmpty) {
        Placeholder(ctx, s, "Deck")
        return
    }
    val focus = e.focus
    val frame = remember(deck, focus, box.w, box.h, ctx.theme) {
        val local = CanvasBox(0f, 0f, box.w, box.h)
        val whole = DeckStage.whole(deck, local, sections = focus?.sections?.takeIf { it.isNotEmpty() })
        if (focus == null || focus.all || focus.isEmpty) whole else {
            val lit = DeckStage.focused(deck, focus)
            whole.copy(cards = whole.cards.map { c -> if (c.key in lit) c.copy(emphasis = 1f) else c.copy(alpha = ctx.theme.dim) })
        }
    }
    DeckLayer(ctx, { frame }, frame.cards.map { it.key }, s, editing)
}

/**
 * The deck on its stage: every copy a real card, placed from [frame] in the layout phase and
 * lit in the layer, with the groups' outlines, their names and the note drawn round them. A
 * glide from one frame to the next moves boxes, never recomposes.
 */
@Composable
internal fun DeckLayer(ctx: SlideContext, frame: () -> StageFrame?, keys: List<String>, s: Float, editing: Boolean) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val theme = ctx.theme
    val accent = ctx.color("@accent", "#FFFFFF")
    // Under the cards: glows, lifts and the groups' outlines.
    Canvas(Modifier.fillMaxSize()) {
        val f = frame() ?: return@Canvas
        for (c in f.cards) {
            if (c.alpha <= 0.01f) continue
            val b = c.box
            val r = Rect(b.x * s, b.y * s, b.right * s, b.bottom * s)
            if (c.emphasis > 0.01f) {
                val groupTint = c.group?.let { f.colors[it] }?.let(ctx::groupColor)
                val glow = (if (theme.highlight == Theme.HIGHLIGHT_OUTLINE) accent else groupTint ?: accent)
                val grow = 1f + 0.05f * c.emphasis
                val gr = Rect(r.center.x - r.width * grow / 2f, r.center.y - r.height * grow / 2f, r.center.x + r.width * grow / 2f, r.center.y + r.height * grow / 2f)
                when (theme.highlight) {
                    Theme.HIGHLIGHT_OUTLINE -> drawRect(glow.copy(alpha = c.emphasis * c.alpha), gr.topLeft - Offset(4f * s, 4f * s), Size(gr.width + 8f * s, gr.height + 8f * s), style = DrawStroke(5f * s))
                    Theme.HIGHLIGHT_LIFT -> {
                        val p = Path().apply { addRect(gr.translate(Offset(0f, 14f * s * c.emphasis))) }
                        slideShadow(p, Color.Black.copy(alpha = 0.55f * c.emphasis * c.alpha), 26f * s)
                    }
                    else -> {
                        val p = Path().apply { addRect(gr.inflate(10f * s)) }
                        slideShadow(p, glow.copy(alpha = 0.85f * c.emphasis * c.alpha), 30f * s)
                    }
                }
            }
            val g = c.group ?: continue
            if (c.outer == 0) continue
            val color = ctx.groupColor(f.colors[g] ?: 0).copy(alpha = c.alpha * 0.95f)
            val o = 5f * s
            val w = 4f * s
            if (c.outer and 1 != 0) drawLine(color, Offset(r.left - o, r.top - o), Offset(r.left - o, r.bottom + o), w)
            if (c.outer and 2 != 0) drawLine(color, Offset(r.left - o, r.top - o), Offset(r.right + o, r.top - o), w)
            if (c.outer and 4 != 0) drawLine(color, Offset(r.right + o, r.top - o), Offset(r.right + o, r.bottom + o), w)
            if (c.outer and 8 != 0) drawLine(color, Offset(r.left - o, r.bottom + o), Offset(r.right + o, r.bottom + o), w)
        }
    }
    keys.forEach { key ->
        val id = DeckStage.idOf(key) ?: return@forEach
        val card = ctx.cards(id) ?: return@forEach
        Box(
            Modifier
                .layout { m, cons ->
                    val c = frame()?.card(key)
                    val b = c?.box
                    if (b == null || c.alpha <= 0.005f) {
                        val p = m.measure(Constraints.fixed(1, 1))
                        layout(cons.maxWidth, cons.maxHeight) { p.place(-10, -10) }
                    } else {
                        val w = (b.w * s).roundToInt().coerceAtLeast(1)
                        val h = (b.h * s).roundToInt().coerceAtLeast(1)
                        val p = m.measure(Constraints.fixed(w, h))
                        layout(cons.maxWidth, cons.maxHeight) { p.place((b.x * s).roundToInt(), (b.y * s).roundToInt()) }
                    }
                }
                .graphicsLayer {
                    val c = frame()?.card(key)
                    alpha = (c?.alpha ?: 0f).coerceIn(0f, 1f)
                    val grow = 1f + 0.05f * (c?.emphasis ?: 0f)
                    scaleX = grow
                    scaleY = grow
                },
        ) {
            NeueCard(card, Modifier.fillMaxSize(), format = ctx.format, copies = 0, foil = ctx.foil)
        }
    }
    // Over the cards: copies badges, the groups' names and the note.
    Canvas(Modifier.fillMaxSize()) {
        val f = frame() ?: return@Canvas
        for (c in f.cards) {
            if (c.badge <= 1 || c.alpha <= 0.05f) continue
            val b = c.box
            val t = measurer.measure("×${c.badge}", TextStyle(color = Color.White, fontSize = with(density) { (max(28f, b.w * 0.09f) * s).toSp() }, fontWeight = FontWeight.Bold, fontFamily = ctx.fonts.of(SlideFonts.INTER)))
            val pad = 12f * s
            val bw = t.size.width + pad * 2
            val bh = t.size.height + pad
            val x = b.right * s - bw - 10f * s
            val y = b.bottom * s - bh - 10f * s
            drawRoundRect(Color.Black.copy(alpha = 0.82f * c.alpha), Offset(x, y), Size(bw, bh), CornerRadius(8f * s))
            drawText(t, topLeft = Offset(x + pad, y + pad / 2f))
        }
        for (l in f.labels) {
            if (l.alpha <= 0.02f) continue
            val color = ctx.groupColor(l.color)
            val b = l.box
            val fontPx = (b.h * 0.62f).coerceIn(12f, 40f) * s
            val ink = if (SlideColor.luminance(color.toArgbLong()) > 0.45) Color.Black else Color.White
            val t = measurer.measure(
                l.text,
                TextStyle(color = ink, fontSize = with(density) { fontPx.toSp() }, fontWeight = FontWeight.Bold, fontFamily = ctx.fonts.of(theme.bodyFont)),
                constraints = Constraints(maxWidth = (b.w * s).roundToInt().coerceAtLeast(1)),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val tabW = t.size.width + 16f * s
            drawRect(color.copy(alpha = l.alpha), Offset(b.x * s - 5f * s, b.y * s), Size(tabW, b.h * s - 4f * s))
            drawText(t, topLeft = Offset(b.x * s + 3f * s, b.y * s + (b.h * s - 4f * s - t.size.height) / 2f), alpha = l.alpha)
        }
        f.note?.let { n -> drawNote(ctx, measurer, n.title, n.text, n.box, s, density) }
    }
}

private fun Color.toArgbLong(): Long = ((alpha * 255).roundToInt().toLong() shl 24) or
    ((red * 255).roundToInt().toLong() shl 16) or ((green * 255).roundToInt().toLong() shl 8) or (blue * 255).roundToInt().toLong()

/** The note beside the deck: a panel in the theme's surface, its title and words fitted. */
private fun DrawScope.drawNote(ctx: SlideContext, measurer: TextMeasurer, title: String, text: String, box: CanvasBox, s: Float, density: Density) {
    if (title.isBlank() && text.isBlank()) return
    val theme = ctx.theme
    val r = Rect(box.x * s, box.y * s, box.right * s, box.bottom * s)
    val pad = 32f * s
    val inner = (r.width - pad * 2 - 10f * s).roundToInt().coerceAtLeast(1)
    val heading = roleLook(Element.ROLE_TITLE, theme)
    fun build(f: Float): AnnotatedString = buildAnnotatedString {
        if (title.isNotBlank()) {
            withStyle(ParagraphStyle(lineHeight = 1.1.em)) {
                withStyle(SpanStyle(color = ctx.color("@text", "#000000"), fontSize = with(density) { (54f * s * f).toSp() }, fontWeight = if (heading.weight >= 700) FontWeight.Bold else FontWeight.Normal, fontFamily = ctx.fonts.of(heading.font))) {
                    append(if (heading.caps) title.uppercase() else title)
                }
            }
        }
        if (text.isNotBlank()) {
            withStyle(ParagraphStyle(lineHeight = 1.35.em)) {
                withStyle(SpanStyle(color = ctx.color("@text", "#000000").copy(alpha = 0.86f), fontSize = with(density) { (32f * s * f).toSp() }, fontFamily = ctx.fonts.of(theme.bodyFont))) {
                    if (title.isNotBlank()) append("\n")
                    append(text)
                }
            }
        }
    }
    val (_, layout) = fitOf(measurer, ::build, inner, (r.height - pad * 2).roundToInt(), shrink = true)
    val panelH = (layout.size.height + pad * 2).coerceAtMost(r.height)
    val top = r.top + (r.height - panelH) / 2f
    drawRoundRect(ctx.color("@surface", "#FFFFFF").copy(alpha = 0.92f), Offset(r.left, top), Size(r.width, panelH), CornerRadius(18f * s))
    drawRect(ctx.color("@accent", "#000000"), Offset(r.left, top + 18f * s), Size(8f * s, panelH - 36f * s))
    drawText(layout, topLeft = Offset(r.left + pad + 10f * s, top + pad))
}

// ---- tables, charts and numbers -----------------------------------------------------

@Composable
private fun TableBlock(ctx: SlideContext, e: Element, box: CanvasBox, s: Float) {
    val rows = e.table
    if (rows.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cols = rows.maxOf { it.size }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxSize()) {
        val rh = size.height / rows.size
        val cw = size.width / cols
        val fontPx = (rh * 0.42f).coerceAtMost(34f * s)
        rows.forEachIndexed { r, row ->
            val header = r == 0
            if (header) drawRect(ctx.color("@accent", "#000000"), Offset(0f, 0f), Size(size.width, rh))
            else if (r % 2 == 0) drawRect(ctx.color("@surface", "#FFFFFF").copy(alpha = 0.55f), Offset(0f, r * rh), Size(size.width, rh))
            row.forEachIndexed { c, cell ->
                val color = if (header) contrastOn(ctx.color("@accent", "#000000")) else ctx.color("@text", "#000000")
                val t = measurer.measure(
                    cell,
                    TextStyle(color = color, fontSize = with(density) { fontPx.toSp() }, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, fontFamily = ctx.fonts.of(ctx.theme.bodyFont), textAlign = if (c == 0) TextAlign.Start else TextAlign.Center),
                    constraints = Constraints(maxWidth = (cw - 24f * s).roundToInt().coerceAtLeast(1)),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val x = c * cw + if (c == 0) 12f * s else (cw - t.size.width) / 2f
                drawText(t, topLeft = Offset(x, r * rh + (rh - t.size.height) / 2f))
            }
        }
        drawRect(ctx.color("@line", "#000000").copy(alpha = 0.4f), style = DrawStroke(2f * s))
    }
}

private fun contrastOn(bg: Color): Color = if (SlideColor.luminance(bg.toArgbLong()) > 0.45) Color.Black else Color.White

@Composable
private fun ChartBlock(ctx: SlideContext, e: Element, box: CanvasBox, s: Float) {
    val chart = e.chart ?: return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val palette = listOf("@accent", "@accent2", "@accent3", "@accent4").map { ctx.color(it, "#888888") }
    Canvas(Modifier.fillMaxSize()) {
        val label = TextStyle(color = ctx.color("@muted", "#888888"), fontSize = with(density) { (24f * s).toSp() }, fontFamily = ctx.fonts.of(ctx.theme.bodyFont))
        val series = chart.series
        if (series.isEmpty()) return@Canvas
        val values = series.flatMap { it.values }
        val top = chart.max ?: (values.maxOrNull() ?: 1f).coerceAtLeast(0.0001f)
        fun colorOf(i: Int, sr: com.kaiharimoto.mastertool.core.present.ChartSeries) = ctx.color(sr.color) ?: palette[i % palette.size]
        when (chart.kind) {
            Chart.PIE, Chart.DONUT -> {
                val v = series.first().values
                val total = v.sum().coerceAtLeast(0.0001f)
                val d = min(size.width * 0.6f, size.height)
                val tl = Offset((size.width * 0.6f - d) / 2f, (size.height - d) / 2f)
                var start = -90f
                v.forEachIndexed { i, x ->
                    val sweep = 360f * x / total
                    val color = palette[i % palette.size]
                    if (chart.kind == Chart.DONUT) drawArc(color, start, sweep - 1f, false, tl + Offset(d * 0.12f, d * 0.12f), Size(d * 0.76f, d * 0.76f), style = DrawStroke(d * 0.2f))
                    else drawArc(color, start, sweep, true, tl, Size(d, d))
                    start += sweep
                }
                chart.labels.forEachIndexed { i, name ->
                    val y = size.height * 0.15f + i * 46f * s
                    drawRect(palette[i % palette.size], Offset(size.width * 0.66f, y), Size(26f * s, 26f * s))
                    val pct = v.getOrNull(i)?.let { if (chart.percent) " ${it.roundToInt()}%" else " ${(100 * it / total).roundToInt()}%" } ?: ""
                    drawText(measurer.measure(name + pct, label.copy(color = ctx.color("@text", "#000000"))), topLeft = Offset(size.width * 0.66f + 38f * s, y - 4f * s))
                }
            }
            Chart.LINE -> {
                val n = chart.labels.size.coerceAtLeast(series.maxOf { it.values.size })
                val bottom = size.height - 40f * s
                series.forEachIndexed { si, sr ->
                    val p = Path()
                    sr.values.forEachIndexed { i, x ->
                        val px = if (n > 1) i * size.width / (n - 1) else size.width / 2f
                        val py = bottom - bottom * x / top
                        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                        drawCircle(colorOf(si, sr), 7f * s, Offset(px, py))
                    }
                    drawPath(p, colorOf(si, sr), style = DrawStroke(5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawLine(ctx.color("@line", "#000000").copy(alpha = 0.4f), Offset(0f, bottom), Offset(size.width, bottom), 2f * s)
            }
            else -> {
                val n = chart.labels.size.coerceAtLeast(series.maxOf { it.values.size }).coerceAtLeast(1)
                val horizontal = chart.kind == Chart.BAR
                val groupSpan = (if (horizontal) size.height else size.width) / n
                val barSpan = groupSpan * 0.72f / series.size
                val labelRoom = 44f * s
                val extent = (if (horizontal) size.width * 0.75f else size.height - labelRoom)
                for (i in 0 until n) {
                    series.forEachIndexed { si, sr ->
                        val x = sr.values.getOrNull(i) ?: return@forEachIndexed
                        val len = extent * (x / top).coerceIn(0f, 1f)
                        val start = i * groupSpan + groupSpan * 0.14f + si * barSpan
                        if (horizontal) drawRect(colorOf(si, sr), Offset(size.width * 0.25f, start), Size(len, barSpan * 0.92f))
                        else drawRect(colorOf(si, sr), Offset(start, extent - len), Size(barSpan * 0.92f, len))
                        val v = measurer.measure(if (chart.percent) "${x.roundToInt()}%" else trimNumber(x), label.copy(color = ctx.color("@text", "#000000")))
                        if (horizontal) drawText(v, topLeft = Offset(size.width * 0.25f + len + 8f * s, start + (barSpan - v.size.height) / 2f))
                        else drawText(v, topLeft = Offset(start + (barSpan - v.size.width) / 2f, (extent - len - v.size.height - 4f * s).coerceAtLeast(0f)))
                    }
                    val name = chart.labels.getOrNull(i) ?: continue
                    val t = measurer.measure(name, label, constraints = Constraints(maxWidth = (if (horizontal) size.width * 0.24f else groupSpan).roundToInt().coerceAtLeast(1)), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (horizontal) drawText(t, topLeft = Offset(0f, i * groupSpan + (groupSpan - t.size.height) / 2f))
                    else drawText(t, topLeft = Offset(i * groupSpan + (groupSpan - t.size.width) / 2f, size.height - t.size.height))
                }
            }
        }
    }
}

private fun trimNumber(x: Float): String = if (x == x.roundToInt().toFloat()) x.roundToInt().toString() else "%.1f".format(x)

@Composable
private fun StatBlock(ctx: SlideContext, e: Element, box: CanvasBox, s: Float) {
    val stat = e.stat ?: return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val heading = roleLook(Element.ROLE_TITLE, ctx.theme)
    Canvas(Modifier.fillMaxSize()) {
        val valueStyle = TextStyle(color = ctx.color("@accent", "#000000"), fontFamily = ctx.fonts.of(heading.font), fontWeight = if (heading.weight >= 700) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
        var px = size.height * 0.62f
        var t = measurer.measure(stat.value, valueStyle.copy(fontSize = with(density) { px.toSp() }), maxLines = 1)
        if (t.size.width > size.width) {
            px *= size.width / t.size.width * 0.95f
            t = measurer.measure(stat.value, valueStyle.copy(fontSize = with(density) { px.toSp() }), maxLines = 1)
        }
        drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, 0f))
        val label = measurer.measure(
            listOf(stat.label, stat.sub).filter { it.isNotBlank() }.joinToString("\n"),
            TextStyle(color = ctx.color("@text", "#000000"), fontSize = with(density) { (38f * s).toSp() }, fontFamily = ctx.fonts.of(ctx.theme.bodyFont), textAlign = TextAlign.Center),
            constraints = Constraints.fixedWidth(size.width.roundToInt().coerceAtLeast(1)),
        )
        drawText(label, topLeft = Offset((size.width - label.size.width) / 2f, t.size.height.toFloat()))
    }
}

// ---- the camera ------------------------------------------------------------------------

/** A zone's outline: square, rounded, a circle or a pill. */
internal fun zonePath(shape: String, w: Float, h: Float, s: Float): Path = Path().apply {
    when (shape) {
        WebcamZone.SHAPE_RECT -> addRect(Rect(0f, 0f, w, h))
        WebcamZone.SHAPE_CIRCLE -> addOval(Rect(0f, 0f, w, h))
        WebcamZone.SHAPE_PILL -> addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(min(w, h) / 2f)))
        else -> addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(28f * s)))
    }
}

@Composable
private fun CameraZone(ctx: SlideContext, zone: CanvasBox, s: Float, editing: Boolean, camera: (@Composable () -> Unit)?) {
    val z = ctx.presentation.webcam
    CameraFrame(ctx, z, Modifier.onCanvas(s) { zone }, s, editing, camera)
}

@Composable
private fun CameraElement(ctx: SlideContext, e: Element, s: Float, editing: Boolean, camera: (@Composable () -> Unit)?) {
    CameraFrame(ctx, ctx.presentation.webcam, Modifier.fillMaxSize(), s, editing, camera)
}

@Composable
private fun CameraFrame(ctx: SlideContext, z: WebcamZone, modifier: Modifier, s: Float, editing: Boolean, camera: (@Composable () -> Unit)?) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(
        modifier.drawWithContent {
            val path = zonePath(z.shape, size.width, size.height, s)
            when {
                camera != null -> clipPath(path) { this@drawWithContent.drawContent() }
                z.fill == WebcamZone.FILL_CHROMA -> drawPath(path, ctx.color(WebcamZone.CHROMA, "#00B140"))
                z.fill == WebcamZone.FILL_THEME -> {
                    drawPath(path, ctx.color("@surface", "#222222"))
                    if (editing) {
                        val t = measurer.measure("Camera", TextStyle(color = ctx.color("@muted", "#888888"), fontSize = with(density) { (30f * s).toSp() }, fontFamily = ctx.fonts.of(SlideFonts.INTER)))
                        drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (size.height - t.size.height) / 2f))
                    }
                }
                editing -> drawPath(path, ctx.color("@muted", "#888888"), style = DrawStroke(3f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f * s, 10f * s))))
            }
            val border = z.border?.let { ctx.color(it) }
            if (border != null && z.borderWidth > 0f) drawPath(path, border, style = DrawStroke(z.borderWidth * s))
        },
    ) {
        if (camera != null) camera()
    }
}

/** A soft shadow or glow of [path] in [color], [blur] pixels: the platform's blur, here alone. */
internal expect fun DrawScope.slideShadow(path: Path, color: Color, blur: Float)

/** The presenter's laser at [x], [y] (canvas units): a bright dot in the theme's accent with a soft halo. */
internal fun DrawScope.drawLaser(ctx: SlideContext, x: Float, y: Float, s: Float) {
    val c = ctx.color("@accent", "#FFFFFF")
    val p = Path().apply { addOval(Rect(x * s - 22f * s, y * s - 22f * s, x * s + 22f * s, y * s + 22f * s)) }
    slideShadow(p, c.copy(alpha = 0.6f), 24f * s)
    drawCircle(c, 11f * s, Offset(x * s, y * s))
    drawCircle(Color.White.copy(alpha = 0.85f), 4f * s, Offset(x * s, y * s))
}

/** The presenter's pen: each stroke a round line in the theme's accent. */
internal fun DrawScope.drawInk(ctx: SlideContext, strokes: List<List<Pair<Float, Float>>>, s: Float) {
    val c = ctx.color("@accent", "#FFFFFF")
    for (stroke in strokes) {
        if (stroke.size < 2) {
            stroke.firstOrNull()?.let { (x, y) -> drawCircle(c, 6f * s, Offset(x * s, y * s)) }
            continue
        }
        val p = Path()
        stroke.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x * s, y * s) else p.lineTo(x * s, y * s) }
        drawPath(p, c, style = DrawStroke(12f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The theme's background, for a layer drawn over a slide (the presenter's whole-deck view). */
internal fun DrawScope.drawThemeBackground(ctx: SlideContext, alpha: Float) {
    val theme = ctx.theme
    val bg = ctx.color("@bg", "#000000")
    val to = theme.backgroundTo?.let { ctx.color(it) }
    if (to != null) {
        drawRect(Brush.linearGradient(listOf(bg, to), Offset.Zero, Offset(size.width * 0.4f, size.height)), alpha = alpha)
    } else {
        drawRect(bg, alpha = alpha)
    }
}

/**
 * [paras] for the text field that edits them in place: the same spans as the slide draws,
 * over exactly the field's text — paragraphs joined by `\n`, no list marks — so a caret lands
 * where the words are.
 */
internal fun editingText(ctx: SlideContext, paras: List<Para>, role: String, px: Float, fit: Float, density: Density): AnnotatedString {
    val look = roleLook(role, ctx.theme)
    return buildAnnotatedString {
        paras.forEachIndexed { i, p ->
            if (i > 0) withStyle(spanOf(ctx, p.runs.firstOrNull()?.style ?: RunStyle(), look, px, fit, density)) { append("\n") }
            for (r in p.runs) {
                withStyle(spanOf(ctx, r.style, look, px, fit, density)) { append(if (r.style.caps || look.caps) r.text.uppercase() else r.text) }
            }
        }
    }
}
