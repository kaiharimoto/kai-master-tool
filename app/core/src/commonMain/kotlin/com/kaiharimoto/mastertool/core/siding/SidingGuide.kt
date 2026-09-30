package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.ydk.Zlib
import kotlin.math.max

/** A card as the guide prints it: its picture, its name, how many copies. */
data class GuideCard(val id: CardId, val name: String, val count: Int)

/** One side of a turn's plan, as printed. */
data class GuidePlan(val out: List<GuideCard>, val into: List<GuideCard>, val note: String) {
    val sided: Boolean get() = out.isNotEmpty() || into.isNotEmpty()
    val written: Boolean get() = sided || note.isNotBlank()
}

/** A turn: your plan, and theirs for the turn that answers it, when they have one. */
data class GuideTurn(val turn: Turn, val plan: GuidePlan?, val theirs: GuidePlan?)

/** A matchup, as printed: who, their share, the note, their faces and both turns. */
data class GuideMatchup(
    val name: String,
    val share: Int?,
    val note: String,
    val covers: List<CardId>,
    val turns: List<GuideTurn>,
)

/** Everything the guide prints. */
data class GuideContent(
    val deckName: String,
    val webName: String?,
    /** `40 · 15 · 15`. */
    val counts: String,
    val matchups: List<GuideMatchup>,
    val webNotes: String = "",
)

/**
 * How the guide shows a plan's cards (1.0.49, kai: "it will respect which toggle the user has at
 * the moment"): [ART], a picture per copy and nothing else, as the Siding page's art view; or
 * [LIST], names with their counts, as its list view.
 */
enum class GuideStyle { ART, LIST }

/** The fonts the guide is set in: the app's own, read from its files. */
class GuideFonts(val regular: TrueType, val bold: TrueType, val mono: TrueType)

/**
 * The siding guide (kai, 1.0.36: "export an organized and visually coherent
 * siding guide… The siding guide should also include how they side against you
 * if they have it"), laid out as the mockup kai approved: an A4 page with the
 * deck's name over a rule, then a block per matchup — the opponent's faces and
 * name, the note, and a box for each turn with the cards out and in as pictures
 * with their counts and names, why, and under a dashed line **their plan**: what
 * they bring in, what they drop, and their note.
 *
 * The first page opens with the matchups at a glance. Blocks never break across
 * pages; every page repeats the heading and counts itself (`page 2 of 4`).
 * Measured with the fonts' own widths ([TrueType]), so what is measured is what
 * is drawn. Paper and ink only; the cards keep their colour.
 */
object SidingGuide {
    private const val W = PdfDocument.A4_WIDTH
    private const val H = PdfDocument.A4_HEIGHT
    private const val ML = 33f
    private const val MT = 30f
    private const val MB = 22f
    private const val CW = W - 2 * ML
    private const val CONTENT_TOP = MT + 56f
    private const val CONTENT_BOTTOM = H - MB - 18f
    private const val RATIO = 86f / 59f

    // Greys, 0 ink to 1 paper.
    private const val INK = 0f
    private const val INK70 = 0.3f
    private const val INK45 = 0.55f
    private const val INK25 = 0.75f
    private const val INK12 = 0.88f
    private const val INK06 = 0.95f

    fun write(content: GuideContent, fonts: GuideFonts, image: (CardId) -> PdfImage?, zlib: Zlib?, style: GuideStyle = GuideStyle.ART): ByteArray {
        val doc = PdfDocument(zlib, title = "Siding guide · ${content.deckName}", author = "Neue Master Tool")
        val f = Fonts(doc.font(fonts.regular), doc.font(fonts.bold), doc.font(fonts.mono))
        // One picture per card, however often it is printed: the file carries it once.
        val pictures = HashMap<CardId, PdfImage?>()
        val image = { id: CardId -> if (id in pictures) pictures[id] else image(id).also { pictures[id] = it } }
        val blocks = buildList {
            add(Glance(content, f))
            content.matchups.forEach { add(Section(it, f, image, style)) }
        }
        // Pages: blocks in order, a new page when the next does not fit.
        val pages = mutableListOf<MutableList<Block>>(mutableListOf())
        var y = CONTENT_TOP
        blocks.forEach { b ->
            if (y + b.height > CONTENT_BOTTOM && pages.last().isNotEmpty()) {
                pages += mutableListOf<Block>()
                y = CONTENT_TOP
            }
            pages.last() += b
            y += b.height + GAP
        }
        pages.forEachIndexed { i, list ->
            val page = doc.page()
            chrome(page, f, content, i + 1, pages.size)
            var top = CONTENT_TOP
            list.forEach { b ->
                b.draw(page, top)
                top += b.height + GAP
            }
        }
        return doc.write()
    }

    private const val GAP = 14f

    /** [text] broken into lines no wider than [width] at [size]: at spaces, and inside a word only when it must. */
    fun wrap(text: String, font: TrueType, size: Float, width: Float): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var line = ""
            para.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (font.width(trial, size) <= width) {
                    line = trial
                } else {
                    if (line.isNotEmpty()) out += line
                    var rest = word
                    while (font.width(rest, size) > width && rest.length > 1) {
                        var cut = rest.length - 1
                        while (cut > 1 && font.width(rest.substring(0, cut), size) > width) cut--
                        out += rest.substring(0, cut)
                        rest = rest.substring(cut)
                    }
                    line = rest
                }
            }
            if (line.isNotEmpty() || para.isEmpty()) out += line
        }
        return out.dropLastWhile { it.isEmpty() }
    }

    private class Fonts(val regular: PdfFont, val bold: PdfFont, val mono: PdfFont)

    private interface Block {
        val height: Float
        fun draw(page: PdfPage, top: Float)
    }

    /** The heading every page repeats, and the footer. */
    private fun chrome(page: PdfPage, f: Fonts, content: GuideContent, number: Int, of: Int) {
        val label = "SIDING GUIDE" + (content.webName?.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" } ?: "")
        page.text(f.regular, 7.5f, ML, MT + 8f, label, INK70)
        val title = fit(content.deckName, f.bold, 22f, CW - 150f)
        page.text(f.bold, 22f, ML, MT + 34f, title)
        right(page, f.mono, 7.5f, W - ML, MT + 22f, content.counts, INK70)
        val count = content.matchups.size
        right(page, f.mono, 7.5f, W - ML, MT + 33f, "$count ${if (count == 1) "matchup" else "matchups"} · page $number of $of", INK70)
        page.fillRect(ML, MT + 42f, CW, 1.5f, INK)
        page.text(f.regular, 7f, ML, H - MB, "Made with Neue Master Tool", INK45)
        right(page, f.mono, 7f, W - ML, H - MB, number.toString().padStart(2, '0'), INK45)
    }

    private fun right(page: PdfPage, font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float) =
        page.text(font, size, x - font.width(text, size), baseline, text, gray)

    /** [text] cut with an ellipsis to fit [width]. */
    private fun fit(text: String, font: PdfFont, size: Float, width: Float): String {
        if (font.width(text, size) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…", size) > width) t = t.dropLast(1)
        return "$t…"
    }

    /** The first page's table: each matchup, its share, and each turn's plan in a line. */
    private class Glance(private val content: GuideContent, private val f: Fonts) : Block {
        private val notes = if (content.webNotes.isBlank()) emptyList() else wrap(content.webNotes, f.regular.metrics, 8.5f, CW)
        private val row = 15f
        override val height: Float = 14f + notes.size * 12f + (if (notes.isEmpty()) 0f else 6f) + 14f + content.matchups.size * row

        override fun draw(page: PdfPage, top: Float) {
            var y = top
            page.text(f.bold, 7.5f, ML, y + 8f, "AT A GLANCE")
            y += 14f
            notes.forEach { line ->
                page.text(f.regular, 8.5f, ML, y + 8f, line, INK70)
                y += 12f
            }
            if (notes.isNotEmpty()) y += 6f
            val c1 = ML + CW * 0.38f
            val c2 = ML + CW * 0.69f
            page.text(f.regular, 6.5f, ML, y + 7f, "AGAINST", INK45)
            page.text(f.regular, 6.5f, c1, y + 7f, "GOING FIRST", INK45)
            page.text(f.regular, 6.5f, c2, y + 7f, "GOING SECOND", INK45)
            y += 10f
            page.fillRect(ML, y, CW, 0.75f, INK)
            y += 4f
            content.matchups.forEach { m ->
                page.text(f.bold, 8.5f, ML, y + 8f, fit(m.name, f.bold, 8.5f, CW * 0.26f))
                m.share?.let { page.text(f.mono, 7f, ML + CW * 0.28f, y + 8f, "$it%", INK45) }
                m.turns.forEach { t ->
                    val x = if (t.turn == Turn.FIRST) c1 else c2
                    val p = t.plan
                    val words = if (p == null || !p.written) "Not sided yet" else "${p.out.sumOf { it.count }} out · ${p.into.sumOf { it.count }} in"
                    page.text(f.regular, 8f, x, y + 8f, words, if (p?.written == true) INK else INK45)
                }
                page.fillRect(ML, y + row - 2f, CW, 0.5f, INK12)
                y += row
            }
        }
    }

    /** A card with its count in the corner, and nothing drawn when its picture is missing but a grey box. */
    private fun card(page: PdfPage, f: Fonts, c: GuideCard, image: (CardId) -> PdfImage?, x: Float, top: Float, w: Float, strong: Boolean, faded: Boolean) {
        val h = w * RATIO
        val draw = {
            val picture = image(c.id)
            if (picture != null) page.image(picture, x, top, w, h) else page.fillRect(x, top, w, h, INK06)
            if (faded) {
                page.strokeRect(x, top, w, h, INK45, 0.6f, dash = 1.5f)
            } else {
                page.strokeRect(x, top, w, h, if (strong) INK else INK25, if (strong) 1.2f else 0.6f)
            }
        }
        if (faded) page.faded(0.55f, draw) else draw()
        if (c.count > 0) {
            val label = "×${c.count}"
            val size = if (faded) 5.5f else 6.5f
            val bw = f.mono.width(label, size) + 3f
            val bh = size + 2f
            if (faded) {
                page.fillRect(x + w - bw, top + h - bh, bw, bh, 1f)
                page.strokeRect(x + w - bw, top + h - bh, bw, bh, INK45, 0.5f)
                page.text(f.mono, size, x + w - bw + 1.5f, top + h - 1.8f, label, INK)
            } else {
                page.fillRect(x + w - bw, top + h - bh, bw, bh, INK)
                page.text(f.mono, size, x + w - bw + 1.5f, top + h - 1.8f, label, 1f)
            }
        }
    }

    /**
     * A labelled run of cards with their names under them, one name a line:
     * measured once, drawn where it is put. The label stands over the cards, so
     * two runs fit side by side in a turn's box — out beside in, what they bring
     * beside what they drop — and two matchups fit on a page.
     */
    private class CardRow(
        private val label: String,
        counted: List<GuideCard>,
        private val width: Float,
        private val cardWidth: Float,
        private val strong: Boolean,
        private val faded: Boolean,
        private val f: Fonts,
        private val image: (CardId) -> PdfImage?,
        private val style: GuideStyle = GuideStyle.ART,
    ) {
        // Art: every copy its own picture, no count and no name — the Siding page's art view.
        private val cards = if (style == GuideStyle.ART) counted.flatMap { c -> List(c.count.coerceAtLeast(1)) { c.copy(count = 0) } } else counted
        private val gap = 3f
        private val labelHeight = 11f
        private val cardHeight = cardWidth * RATIO
        private val perLine = max(1, ((width + gap) / (cardWidth + gap)).toInt())
        private val lines = (cards.size + perLine - 1) / perLine
        private val listSize = if (faded) 7f else 7.5f
        // List: a line a card, its count first, as a player writes a decklist.
        private val names = if (style == GuideStyle.LIST) cards.flatMap { wrap("${it.count}× ${it.name}", f.regular.metrics, listSize, width) } else emptyList()
        val height: Float = labelHeight + when {
            cards.isEmpty() -> 10f
            style == GuideStyle.LIST -> names.size * (listSize + 3f)
            else -> lines * cardHeight + (lines - 1) * gap + 2f
        }

        fun draw(page: PdfPage, x: Float, top: Float) {
            page.text(f.bold, 6f, x, top + 7f, label, if (faded) INK45 else INK)
            val y0 = top + labelHeight
            if (cards.isEmpty()) {
                page.text(f.regular, 7f, x, y0 + 7f, "Nothing", INK45)
                return
            }
            if (style == GuideStyle.LIST) {
                var y = y0
                names.forEach { n ->
                    y += listSize + 3f
                    page.text(if (strong) f.bold else f.regular, listSize, x, y - 2f, n, if (faded) INK45 else INK)
                    if (faded) page.line(x, y - 2f - listSize * 0.3f, x + (if (strong) f.bold else f.regular).width(n, listSize), y - 2f - listSize * 0.3f, INK45, 0.4f)
                }
                return
            }
            cards.forEachIndexed { i, c ->
                val col = i % perLine
                val line = i / perLine
                card(page, f, c, image, x + col * (cardWidth + gap), y0 + line * (cardHeight + gap), cardWidth, strong, faded)
            }
        }
    }

    /** One turn's box: its title, out and in, why, and their plan under a dashed line. */
    private class TurnBox(private val t: GuideTurn, width: Float, private val f: Fonts, image: (CardId) -> PdfImage?, style: GuideStyle) {
        private val pad = 7f
        private val inner = width - 2 * pad
        private val half = (inner - 10f) / 2
        private val plan = t.plan?.takeIf { it.written }
        private val out = plan?.let { CardRow("OUT", it.out, half, 22f, false, false, f, image, style) }
        private val into = plan?.let { CardRow("IN", it.into, half, 22f, true, false, f, image, style) }
        private val swap = if (out == null || into == null) 0f else max(out.height, into.height)
        private val note = plan?.note?.takeIf { it.isNotBlank() }?.let { wrap(it, f.regular.metrics, 8f, inner) }.orEmpty()
        private val theirs = t.theirs?.takeIf { it.written }
        private val bring = theirs?.let { CardRow("THEY BRING", it.into, half, 20f, true, false, f, image, style) }
        private val drop = theirs?.let { CardRow("THEY DROP", it.out, half, 15f, false, true, f, image, style) }
        private val answer = if (bring == null || drop == null) 0f else max(bring.height, drop.height)
        private val quote = theirs?.note?.takeIf { it.isNotBlank() }?.let { wrap("“$it”", f.regular.metrics, 7.5f, inner - 10f) }.orEmpty()

        val height: Float = pad + 12f + 6f +
            (if (plan == null) 12f else swap + (if (note.isEmpty()) 0f else 6f + note.size * 11f)) +
            (if (theirs == null) 0f else 8f + 16f + answer + (if (quote.isEmpty()) 0f else 6f + quote.size * 10.5f + 6f)) +
            pad

        fun draw(page: PdfPage, x: Float, top: Float, boxHeight: Float, width: Float) {
            page.strokeRect(x, top, width, boxHeight, INK25, 0.75f)
            val ix = x + pad
            var y = top + pad
            page.text(f.bold, 7.5f, ix, y + 8f, t.turn.title.uppercase())
            y += 18f
            if (plan == null) {
                page.text(f.regular, 8f, ix, y + 8f, "Not sided yet.", INK45)
                y += 12f
            } else {
                out!!.draw(page, ix, y)
                into!!.draw(page, ix + half + 10f, y)
                y += swap
                if (note.isNotEmpty()) {
                    y += 6f
                    note.forEach { line ->
                        page.text(f.regular, 8f, ix, y + 8f, line, INK70)
                        y += 11f
                    }
                }
            }
            if (theirs != null) {
                y += 8f
                page.line(x, y, x + width, y, INK45, 0.6f, dash = 2f)
                y += 5f
                page.text(f.bold, 6.5f, ix, y + 8f, "THEIR PLAN")
                val chip = if (t.turn.theirs == Turn.FIRST) "They go first" else "They go second"
                val cx = ix + f.bold.width("THEIR PLAN", 6.5f) + 6f
                val cw = f.bold.width(chip, 6.5f) + 6f
                page.strokeRect(cx, y + 1f, cw, 10f, INK, 0.6f)
                page.text(f.bold, 6.5f, cx + 3f, y + 8.2f, chip)
                y += 16f
                bring!!.draw(page, ix, y)
                drop!!.draw(page, ix + half + 10f, y)
                y += answer
                if (quote.isNotEmpty()) {
                    y += 6f
                    val qh = quote.size * 10.5f + 6f
                    page.fillRect(ix, y, inner, qh, INK06)
                    page.fillRect(ix, y, 1.5f, qh, INK)
                    var qy = y + 3f
                    quote.forEach { line ->
                        page.text(f.regular, 7.5f, ix + 7f, qy + 7.5f, line, INK)
                        qy += 10.5f
                    }
                }
            }
        }
    }

    /** A matchup: faces, name, share and note over its two turns, a rule under it. */
    private class Section(private val m: GuideMatchup, private val f: Fonts, private val image: (CardId) -> PdfImage?, style: GuideStyle) : Block {
        private val coverW = 24f
        private val textX = ML + 3 * (coverW + 2.5f) + 6f
        private val note = wrap(m.note, f.regular.metrics, 8.5f, W - ML - textX - 60f)
        private val head = max(coverW * RATIO, 16f + note.size * 12.5f) + 8f
        private val boxWidth = (CW - 12f) / 2
        private val boxes = m.turns.map { TurnBox(it, boxWidth, f, image, style) }
        private val boxHeight = boxes.maxOfOrNull { it.height } ?: 0f
        override val height: Float = head + boxHeight + 12f

        override fun draw(page: PdfPage, top: Float) {
            m.covers.take(3).forEachIndexed { i, id ->
                card(page, f, GuideCard(id, "", 0), image, ML + i * (coverW + 2.5f), top, coverW, strong = false, faded = false)
            }
            page.text(f.bold, 14f, textX, top + 13f, fit("vs ${m.name}", f.bold, 14f, W - ML - textX - 70f))
            m.share?.let { right(page, f.mono, 7.5f, W - ML, top + 10f, "$it% of the field", INK45) }
            var y = top + 16f
            note.forEach { line ->
                y += 12.5f
                page.text(f.regular, 8.5f, textX, y - 2f, line, INK70)
            }
            boxes.forEachIndexed { i, b -> b.draw(page, ML + i * (boxWidth + 12f), top + head, boxHeight, boxWidth) }
            page.fillRect(ML, top + head + boxHeight + 11f, CW, 0.5f, INK12)
        }
    }
}
