package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.pdf.LineCap
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.pdf.TrueType

/**
 * What a guide's pictures are drawn with (1.0.67): one set of marks — rectangles, lines, shapes,
 * text, card art — painted three ways. The PDF paints them on its pages ([PdfInk]); the app and the
 * HTML read them back from a [Drawing] made by [RecordingInk]. A picture is laid out once, in core,
 * so a guide read in the app, shared as a PDF and opened in a browser is the same picture.
 *
 * Points from the top left. Grey levels only, 0 ink to 1 paper; card art the only colour.
 */
interface Ink {
    fun fillRect(x: Float, top: Float, w: Float, h: Float, gray: Float)
    fun strokeRect(x: Float, top: Float, w: Float, h: Float, gray: Float = 0f, line: Float = 1f, dash: Float? = null)
    fun line(x1: Float, top1: Float, x2: Float, top2: Float, gray: Float = 0f, width: Float = 1f, dash: Float? = null)
    fun fill(shape: Shape, gray: Float)
    fun stroke(shape: Shape, gray: Float = 0f, width: Float = 1f, dash: Float? = null, round: Boolean = false)
    fun text(face: Face, size: Float, x: Float, baseline: Float, text: String, gray: Float = 0f, tracking: Float = 0f)

    /** The card named [name], its art filling the box, or a quiet box with its name when there is none. */
    fun card(name: String, x: Float, top: Float, w: Float, h: Float)

    /** The art of the card named [name] alone, cut from its frame and cropped to cover the box. */
    fun artwork(name: String, x: Float, top: Float, w: Float, h: Float)

    fun clip(x: Float, top: Float, w: Float, h: Float, draw: () -> Unit)
    fun faded(alpha: Float, draw: () -> Unit)

    /** A place a reader can touch: a card, a step, a choke point. Nothing is drawn; the app hit-tests it. */
    fun tag(tag: String, x: Float, top: Float, w: Float, h: Float) {}

    fun shape(build: Shape.() -> Unit): Shape = Shape().apply(build)
}

/** One of the guide's four faces: Inter at three weights and JetBrains Mono for numbers. */
enum class Weight { REGULAR, MEDIUM, BOLD, MONO }

/** A face and its metrics, so a picture can be measured the same wherever it is painted. */
class Face(val weight: Weight, val metrics: TrueType) {
    fun width(text: String, size: Float, tracking: Float = 0f): Float =
        metrics.width(text, size) + if (tracking == 0f) 0f else tracking * TrueType.codePoints(text).size
}

/** A shape in points from the top left: moves, lines, cubic curves. */
class Shape {
    sealed interface Seg
    data class Move(val x: Float, val y: Float) : Seg
    data class To(val x: Float, val y: Float) : Seg
    data class Curve(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg
    data object Close : Seg

    val segs = mutableListOf<Seg>()

    fun moveTo(x: Float, y: Float) = apply { segs += Move(x, y) }
    fun lineTo(x: Float, y: Float) = apply { segs += To(x, y) }
    fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { segs += Curve(x1, y1, x2, y2, x, y) }
    fun close() = apply { segs += Close }

    fun circle(cx: Float, cy: Float, r: Float) = apply {
        val k = 0.5523f * r
        moveTo(cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        close()
    }

    fun polygon(points: List<Pair<Float, Float>>) = apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
        close()
    }

    /** The same shape moved by ([dx], [dy]). */
    fun moved(dx: Float, dy: Float): Shape = Shape().also { s ->
        segs.forEach { g ->
            s.segs += when (g) {
                is Move -> Move(g.x + dx, g.y + dy)
                is To -> To(g.x + dx, g.y + dy)
                is Curve -> Curve(g.x1 + dx, g.y1 + dy, g.x2 + dx, g.y2 + dy, g.x + dx, g.y + dy)
                Close -> Close
            }
        }
    }
}

/** Paints on a PDF page: [fonts] for each weight, [art] for the pictures. */
class PdfInk(
    private val page: PdfPage,
    private val fonts: Map<Weight, PdfFont>,
    private val art: (String) -> PdfImage?,
    private val hero: (String) -> PdfImage? = art,
    private val placeholder: Face? = null,
) : Ink {
    override fun fillRect(x: Float, top: Float, w: Float, h: Float, gray: Float) = page.fillRect(x, top, w, h, gray)
    override fun strokeRect(x: Float, top: Float, w: Float, h: Float, gray: Float, line: Float, dash: Float?) = page.strokeRect(x, top, w, h, gray, line, dash)
    override fun line(x1: Float, top1: Float, x2: Float, top2: Float, gray: Float, width: Float, dash: Float?) = page.line(x1, top1, x2, top2, gray, width, dash)
    override fun fill(shape: Shape, gray: Float) = page.fill(pdf(shape), gray)
    override fun stroke(shape: Shape, gray: Float, width: Float, dash: Float?, round: Boolean) =
        page.stroke(pdf(shape), gray, width, dash, if (round) LineCap.ROUND else LineCap.BUTT, round)

    override fun text(face: Face, size: Float, x: Float, baseline: Float, text: String, gray: Float, tracking: Float) =
        page.text(fonts.getValue(face.weight), size, x, baseline, text, gray, tracking)

    override fun card(name: String, x: Float, top: Float, w: Float, h: Float) {
        val picture = art(name)
        if (picture != null) {
            page.image(picture, x, top, w, h)
            return
        }
        page.fillRect(x, top, w, h, 0.95f)
        val mono = fonts[Weight.MONO] ?: return
        val size = if (w > 40) 6.5f else 5f
        Pen.wrapWith(name, { s -> mono.width(s, size) }, w - 6).take(5).forEachIndexed { i, l -> page.text(mono, size, x + 3, top + 9 + i * (size + 2), l, 0.55f) }
    }

    override fun artwork(name: String, x: Float, top: Float, w: Float, h: Float) {
        val picture = hero(name) ?: art(name)
        if (picture == null) {
            page.fillRect(x, top, w, h, 0.95f)
            return
        }
        val box = Pen.coverArt(x, top, w, h)
        page.clip(x, top, w, h) { page.image(picture, box[0], box[1], box[2], box[3]) }
    }

    override fun clip(x: Float, top: Float, w: Float, h: Float, draw: () -> Unit) = page.clip(x, top, w, h, draw)
    override fun faded(alpha: Float, draw: () -> Unit) = page.faded(alpha, draw)

    private fun pdf(shape: Shape) = page.path {
        shape.segs.forEach { g ->
            when (g) {
                is Shape.Move -> moveTo(g.x, g.y)
                is Shape.To -> lineTo(g.x, g.y)
                is Shape.Curve -> curveTo(g.x1, g.y1, g.x2, g.y2, g.x, g.y)
                Shape.Close -> close()
            }
        }
    }
}

/**
 * A picture as marks, laid out once: what the app paints on a canvas, the HTML writes as SVG, and a
 * PDF page replays where its layout puts it. [width] and [height] are its size in points.
 */
data class Drawing(val width: Float, val height: Float, val ops: List<Op>) {
    sealed interface Op
    data class FillRect(val x: Float, val top: Float, val w: Float, val h: Float, val gray: Float) : Op
    data class StrokeRect(val x: Float, val top: Float, val w: Float, val h: Float, val gray: Float, val line: Float, val dash: Float?) : Op
    data class Line(val x1: Float, val top1: Float, val x2: Float, val top2: Float, val gray: Float, val width: Float, val dash: Float?) : Op
    data class Fill(val shape: Shape, val gray: Float) : Op
    data class Stroke(val shape: Shape, val gray: Float, val width: Float, val dash: Float?, val round: Boolean) : Op
    data class Text(val weight: Weight, val size: Float, val x: Float, val baseline: Float, val text: String, val gray: Float, val tracking: Float) : Op
    data class Card(val name: String, val x: Float, val top: Float, val w: Float, val h: Float) : Op
    data class Artwork(val name: String, val x: Float, val top: Float, val w: Float, val h: Float) : Op
    data class Clip(val x: Float, val top: Float, val w: Float, val h: Float, val ops: List<Op>) : Op
    data class Faded(val alpha: Float, val ops: List<Op>) : Op
    data class Tag(val tag: String, val x: Float, val top: Float, val w: Float, val h: Float) : Op

    /** Every card drawn, with where: what an animation between two drawings moves. */
    fun cards(): List<Card> = buildList { fun walk(ops: List<Op>) { ops.forEach { o -> when (o) { is Card -> add(o); is Clip -> walk(o.ops); is Faded -> walk(o.ops); else -> Unit } } }; walk(ops) }

    fun tags(): List<Tag> = buildList { fun walk(ops: List<Op>) { ops.forEach { o -> when (o) { is Tag -> add(o); is Clip -> walk(o.ops); is Faded -> walk(o.ops); else -> Unit } } }; walk(ops) }

    /** These marks painted with [ink], moved by ([dx], [dy]). */
    fun paint(ink: Ink, faces: Map<Weight, Face>, dx: Float = 0f, dy: Float = 0f) {
        fun go(ops: List<Op>) {
            ops.forEach { o ->
                when (o) {
                    is FillRect -> ink.fillRect(o.x + dx, o.top + dy, o.w, o.h, o.gray)
                    is StrokeRect -> ink.strokeRect(o.x + dx, o.top + dy, o.w, o.h, o.gray, o.line, o.dash)
                    is Line -> ink.line(o.x1 + dx, o.top1 + dy, o.x2 + dx, o.top2 + dy, o.gray, o.width, o.dash)
                    is Fill -> ink.fill(o.shape.moved(dx, dy), o.gray)
                    is Stroke -> ink.stroke(o.shape.moved(dx, dy), o.gray, o.width, o.dash, o.round)
                    is Text -> ink.text(faces.getValue(o.weight), o.size, o.x + dx, o.baseline + dy, o.text, o.gray, o.tracking)
                    is Card -> ink.card(o.name, o.x + dx, o.top + dy, o.w, o.h)
                    is Artwork -> ink.artwork(o.name, o.x + dx, o.top + dy, o.w, o.h)
                    is Clip -> ink.clip(o.x + dx, o.top + dy, o.w, o.h) { go(o.ops) }
                    is Faded -> ink.faded(o.alpha) { go(o.ops) }
                    is Tag -> ink.tag(o.tag, o.x + dx, o.top + dy, o.w, o.h)
                }
            }
        }
        go(ops)
    }
}

/** Records marks instead of painting them: the [Drawing] a picture becomes. */
class RecordingInk : Ink {
    private val stack = ArrayDeque<MutableList<Drawing.Op>>().apply { addLast(mutableListOf()) }
    private val ops get() = stack.last()

    fun drawing(width: Float, height: Float) = Drawing(width, height, stack.first().toList())

    override fun fillRect(x: Float, top: Float, w: Float, h: Float, gray: Float) { ops += Drawing.FillRect(x, top, w, h, gray) }
    override fun strokeRect(x: Float, top: Float, w: Float, h: Float, gray: Float, line: Float, dash: Float?) { ops += Drawing.StrokeRect(x, top, w, h, gray, line, dash) }
    override fun line(x1: Float, top1: Float, x2: Float, top2: Float, gray: Float, width: Float, dash: Float?) { ops += Drawing.Line(x1, top1, x2, top2, gray, width, dash) }
    override fun fill(shape: Shape, gray: Float) { ops += Drawing.Fill(shape, gray) }
    override fun stroke(shape: Shape, gray: Float, width: Float, dash: Float?, round: Boolean) { ops += Drawing.Stroke(shape, gray, width, dash, round) }
    override fun text(face: Face, size: Float, x: Float, baseline: Float, text: String, gray: Float, tracking: Float) {
        if (text.isNotEmpty()) ops += Drawing.Text(face.weight, size, x, baseline, text, gray, tracking)
    }
    override fun card(name: String, x: Float, top: Float, w: Float, h: Float) { ops += Drawing.Card(name, x, top, w, h) }
    override fun artwork(name: String, x: Float, top: Float, w: Float, h: Float) { ops += Drawing.Artwork(name, x, top, w, h) }
    override fun clip(x: Float, top: Float, w: Float, h: Float, draw: () -> Unit) {
        stack.addLast(mutableListOf())
        draw()
        val inner = stack.removeLast()
        ops += Drawing.Clip(x, top, w, h, inner)
    }
    override fun faded(alpha: Float, draw: () -> Unit) {
        stack.addLast(mutableListOf())
        draw()
        val inner = stack.removeLast()
        ops += Drawing.Faded(alpha, inner)
    }
    override fun tag(tag: String, x: Float, top: Float, w: Float, h: Float) { ops += Drawing.Tag(tag, x, top, w, h) }
}
