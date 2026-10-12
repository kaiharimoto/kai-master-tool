package com.kaiharimoto.neue.ai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.eval.DecklistPicture
import com.kaiharimoto.mastertool.core.ai.eval.DecklistPicture.Look
import com.kaiharimoto.mastertool.core.ai.vision.PictureFit
import com.kaiharimoto.neue.platform.encodePng
import com.kaiharimoto.neue.platform.pictureFonts
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 * The decklist pictures of Test scores (Phase I, I.6), drawn: each [DecklistPicture] laid out in its look and painted off
 * any window. Ink on paper, or paper on ink for the dark look — Master UI's two fills, so a set reads the same whichever
 * theme the app is in. A picture is drawn fresh for every question and never kept: the set is the rows, not files.
 */
internal object EvalPictures {
    private class Words(val layout: TextLayoutResult, val x: Float, val y: Float, val color: Color)
    private class Rule(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: Color, val width: Float)
    private class Band(val x: Float, val y: Float, val w: Float, val h: Float, val color: Color)
    private class Plan(val width: Int, val height: Int, val paper: Color, val words: List<Words>, val rules: List<Rule> = emptyList(), val bands: List<Band> = emptyList())

    private val ink = Color.Black
    private val paper = Color.White

    /** [p] as a picture to send with its question: a PNG, its bytes in the part and nowhere else. */
    fun image(p: DecklistPicture): Part.Image {
        val bitmap = draw(p)
        val bytes = encodePng(bitmap) ?: error("the picture of ${p.title} could not be written")
        return Part.Image("test-scores/${p.title}.png", "image/png", bitmap.width, bitmap.height, data = Base64.getEncoder().encodeToString(bytes))
    }

    fun draw(p: DecklistPicture, fonts: FontFamily.Resolver = pictureFonts()): ImageBitmap {
        val measure = TextMeasurer(fonts, Density(1f), LayoutDirection.Ltr)
        return when (p.look) {
            Look.PHOTO -> photo(p, notes(p, measure, 1f))
            Look.NOTES -> paint(notes(p, measure, 1f))
            Look.SMALL -> paint(notes(p, measure, 0.6f))
            Look.NAME_FIRST -> paint(nameFirst(p, measure))
            Look.COLUMNS -> paint(columns(p, measure))
            Look.DARK -> paint(dark(p, measure))
            Look.SHEET -> paint(sheet(p, measure))
        }
    }

    private fun TextMeasurer.line(text: String, family: FontFamily, size: Float, bold: Boolean = false, wrapAt: Int? = null): TextLayoutResult =
        measure(
            text,
            TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            softWrap = wrapAt != null,
            constraints = if (wrapAt != null) Constraints(maxWidth = wrapAt) else Constraints(),
        )

    private fun total(s: DecklistPicture.Section) = s.rows.sumOf { it.first }

    /** Typed into a notes app: a title, a heading per section with its count, then "3 Name". [k] scales it all. */
    private fun notes(p: DecklistPicture, m: TextMeasurer, k: Float): Plan {
        val pad = 48f * k
        val words = mutableListOf<Words>()
        var y = pad
        val title = m.line(p.title, FontFamily.SansSerif, 36f * k, bold = true)
        words += Words(title, pad, y, ink)
        y += title.size.height + 22f * k
        p.sections.forEach { s ->
            val head = m.line("${s.name} (${total(s)})", FontFamily.SansSerif, 28f * k, bold = true)
            words += Words(head, pad, y, ink)
            y += head.size.height + 8f * k
            s.rows.forEach { (n, name) ->
                val row = m.line("$n $name", FontFamily.SansSerif, 28f * k)
                words += Words(row, pad, y, ink)
                y += row.size.height + 6f * k
            }
            y += 22f * k
        }
        val width = max(600f * k, words.maxOf { it.x + it.layout.size.width } + pad)
        return Plan(ceil(width).toInt(), ceil(y + pad).toInt(), paper, words)
    }

    /** A forum post: the name, then "x3", in a serif, sections ended by a colon. */
    private fun nameFirst(p: DecklistPicture, m: TextMeasurer): Plan {
        val pad = 56f
        val words = mutableListOf<Words>()
        var y = pad
        val title = m.line("My ${p.title} list", FontFamily.Serif, 34f, bold = true)
        words += Words(title, pad, y, ink)
        y += title.size.height + 26f
        p.sections.forEach { s ->
            val head = m.line("${s.name}:", FontFamily.Serif, 28f, bold = true)
            words += Words(head, pad, y, ink)
            y += head.size.height + 6f
            s.rows.forEach { (n, name) ->
                val row = m.line("$name x$n", FontFamily.Serif, 28f)
                words += Words(row, pad + 24f, y, ink)
                y += row.size.height + 4f
            }
            y += 26f
        }
        val width = max(640f, words.maxOf { it.x + it.layout.size.width } + pad)
        return Plan(ceil(width).toInt(), ceil(y + pad).toInt(), paper, words)
    }

    /** The Main Deck on the left, the rest on the right, monospaced, "3x Name". */
    private fun columns(p: DecklistPicture, m: TextMeasurer): Plan {
        val pad = 44f
        val gap = 64f
        val words = mutableListOf<Words>()
        val title = m.line(p.title.uppercase(), FontFamily.Monospace, 26f, bold = true)
        words += Words(title, pad, pad, ink)
        val top = pad + title.size.height + 28f
        fun column(sections: List<DecklistPicture.Section>, x: Float): Pair<Float, Float> {
            var y = top
            var right = x
            sections.forEach { s ->
                val head = m.line("${s.name.uppercase()} — ${total(s)}", FontFamily.Monospace, 22f, bold = true)
                words += Words(head, x, y, ink)
                right = max(right, x + head.size.width)
                y += head.size.height + 10f
                s.rows.forEach { (n, name) ->
                    val row = m.line("${n}x $name", FontFamily.Monospace, 22f)
                    words += Words(row, x, y, ink)
                    right = max(right, x + row.size.width)
                    y += row.size.height + 4f
                }
                y += 24f
            }
            return right to y
        }
        val (leftEnd, leftBottom) = column(p.sections.take(1), pad)
        val (rightEnd, rightBottom) = column(p.sections.drop(1), leftEnd + gap)
        val rules = listOf(Rule(leftEnd + gap / 2, top, leftEnd + gap / 2, max(leftBottom, rightBottom) - 24f, ink.copy(alpha = 0.3f), 1.5f))
        return Plan(ceil(max(rightEnd, leftEnd) + pad).toInt(), ceil(max(leftBottom, rightBottom) + pad).toInt(), paper, words, rules)
    }

    /** A phone in dark mode: narrow, light words on black, long names wrapping onto a second line. */
    private fun dark(p: DecklistPicture, m: TextMeasurer): Plan {
        val width = 720f
        val pad = 40f
        val wrap = (width - 2 * pad).toInt()
        val words = mutableListOf<Words>()
        var y = 56f
        val title = m.line(p.title, FontFamily.SansSerif, 44f, bold = true, wrapAt = wrap)
        words += Words(title, pad, y, paper)
        y += title.size.height + 30f
        p.sections.forEach { s ->
            val head = m.line("${s.name.uppercase()}  ${total(s)}", FontFamily.SansSerif, 24f, bold = true, wrapAt = wrap)
            words += Words(head, pad, y, paper.copy(alpha = 0.6f))
            y += head.size.height + 12f
            s.rows.forEach { (n, name) ->
                val row = m.line("$n  $name", FontFamily.SansSerif, 34f, wrapAt = wrap)
                words += Words(row, pad, y, paper.copy(alpha = 0.92f))
                y += row.size.height + 10f
            }
            y += 30f
        }
        return Plan(width.toInt(), ceil(y + pad).toInt(), ink, words)
    }

    /** A form: a quantity column and a name column, ruled, each section a black band with its name. */
    private fun sheet(p: DecklistPicture, m: TextMeasurer): Plan {
        val pad = 40f
        val qty = 84f
        val rowH = 46f
        val size = 24f
        val names = p.sections.flatMap { it.rows }.map { m.line(it.second, FontFamily.SansSerif, size) }
        val nameW = max(560f, names.maxOf { it.size.width } + 40f)
        val words = mutableListOf<Words>()
        val rules = mutableListOf<Rule>()
        val bands = mutableListOf<Band>()
        val title = m.line("Decklist · ${p.title}", FontFamily.SansSerif, 32f, bold = true)
        words += Words(title, pad, pad, ink)
        var y = pad + title.size.height + 24f
        val left = pad
        val right = pad + qty + nameW
        val faint = ink.copy(alpha = 0.35f)
        val header = m.line("Qty", FontFamily.SansSerif, 18f, bold = true)
        val nameHeader = m.line("Card name", FontFamily.SansSerif, 18f, bold = true)
        words += Words(header, left + (qty - header.size.width) / 2, y, ink)
        words += Words(nameHeader, left + qty + 16f, y, ink)
        y += header.size.height + 8f
        val tableTop = y
        p.sections.forEach { s ->
            bands += Band(left, y, right - left, rowH, ink)
            val head = m.line("${s.name} (${total(s)})", FontFamily.SansSerif, size, bold = true)
            words += Words(head, left + 16f, y + (rowH - head.size.height) / 2, paper)
            y += rowH
            s.rows.forEach { (n, name) ->
                val count = m.line("$n", FontFamily.SansSerif, size)
                val row = m.line(name, FontFamily.SansSerif, size)
                words += Words(count, left + (qty - count.size.width) / 2, y + (rowH - count.size.height) / 2, ink)
                words += Words(row, left + qty + 16f, y + (rowH - row.size.height) / 2, ink)
                y += rowH
                rules += Rule(left, y, right, y, faint, 1.5f)
            }
        }
        rules += Rule(left, tableTop, right, tableTop, ink, 2f)
        rules += Rule(left, y, right, y, ink, 2f)
        rules += Rule(left, tableTop, left, y, ink, 2f)
        rules += Rule(right, tableTop, right, y, ink, 2f)
        rules += Rule(left + qty, tableTop, left + qty, y, faint, 1.5f)
        return Plan(ceil(right + pad).toInt(), ceil(y + pad).toInt(), paper, words, rules, bands)
    }

    private fun DrawScope.lay(plan: Plan) {
        plan.bands.forEach { drawRect(it.color, Offset(it.x, it.y), Size(it.w, it.h)) }
        plan.rules.forEach { drawLine(it.color, Offset(it.x0, it.y0), Offset(it.x1, it.y1), it.width) }
        plan.words.forEach { drawText(it.layout, it.color, Offset(it.x, it.y)) }
    }

    private fun canvas(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat()), block)
        return bitmap
    }

    private fun paint(plan: Plan): ImageBitmap = canvas(plan.width, plan.height) {
        drawRect(plan.paper, Offset.Zero, size)
        lay(plan)
    }

    /**
     * The notes photographed: on a grey desk, turned 3.5°, a band of glare across, and specks over everything — the same
     * specks every time, seeded by the deck's name.
     */
    private fun photo(p: DecklistPicture, plan: Plan): ImageBitmap {
        val margin = 96f
        val w = plan.width + 2 * margin
        val h = plan.height + 2 * margin
        return canvas(w.toInt(), h.toInt()) {
            drawRect(paper, Offset.Zero, size)
            drawRect(ink.copy(alpha = 0.45f), Offset.Zero, size)
            rotate(-3.5f, Offset(w / 2, h / 2)) {
                translate(margin, margin) {
                    drawRect(paper, Offset.Zero, Size(plan.width.toFloat(), plan.height.toFloat()))
                    drawRect(ink.copy(alpha = 0.05f), Offset.Zero, Size(plan.width.toFloat(), plan.height.toFloat()))
                    lay(plan)
                }
            }
            drawRect(paper.copy(alpha = 0.22f), Offset(0f, h * 0.38f), Size(w, h * 0.09f))
            val random = Random(p.title.hashCode())
            repeat((w * h / 900f).toInt()) {
                drawCircle(ink.copy(alpha = 0.06f + random.nextFloat() * 0.18f), 0.6f + random.nextFloat() * 1.4f, Offset(random.nextFloat() * w, random.nextFloat() * h))
            }
        }
    }

    /** The longest side a picture of the set may have: a model is sent nothing it would shrink. */
    val LONG_SIDE = PictureFit.LONG_SIDE
}
