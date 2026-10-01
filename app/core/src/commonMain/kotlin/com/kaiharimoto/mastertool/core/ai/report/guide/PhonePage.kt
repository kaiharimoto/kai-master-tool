package com.kaiharimoto.mastertool.core.ai.report.guide

import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.ArtWindow
import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.Zlib

/**
 * A guide made to be read on a phone (1.0.67, kai: shared guides are read "on phone screens"): a
 * page the shape of one, 400 × 866 points, read at its own width with nothing to zoom. One idea to a
 * screen.
 *
 * The grid is four columns of [COL] with [GUTTER] between them inside [MARGIN]s, every baseline on a
 * [BASE] step; the type is the app's own scale (`MuType`) brought to the page — display, h1, h2, body,
 * micro caps tracked open, and mono for every number. Ink is three weights (Tufte's layering): the
 * one thing to see in full ink, structure in mid grey, detail in hairlines. Card art is the only colour.
 */
class Phone(fonts: GuideFonts, private val art: (String) -> PdfImage?, zlib: Zlib?, title: String, val deck: String) {
    val pdf = PdfDocument(zlib, title = title, author = "Neue Master Tool")
    val regular: PdfFont = pdf.font(fonts.regular)
    val medium: PdfFont = pdf.font(fonts.medium)
    val bold: PdfFont = pdf.font(fonts.bold)
    val mono: PdfFont = pdf.font(fonts.mono)

    private class Screen(val page: PdfPage, val section: String, val head: Boolean)

    private val screens = mutableListOf<Screen>()
    lateinit var page: PdfPage
        private set
    var y = 0f
    var section = ""
        private set

    /** A new screen in [section]; [head] false for a cover, which carries no running head. */
    fun screen(section: String = this.section, head: Boolean = true) {
        this.section = section
        page = pdf.page(W, H)
        screens += Screen(page, section, head)
        y = if (head) TOP else MARGIN
    }

    /** Room for [h] more points on this screen, or a new screen in the same section. */
    fun room(h: Float) {
        if (y + h > BOTTOM) screen()
    }

    /** The running heads, now the screens are counted, and the file. */
    fun finish(): ByteArray {
        val n = screens.size
        screens.forEachIndexed { i, s ->
            if (s.head) {
                val p = s.page
                micro(p, "$deck · ${s.section}", MARGIN, 26f, INK45)
                right(p, mono, 7.5f, W - MARGIN, 26f, "${two(i + 1)}/${two(n)}", INK45)
                p.line(MARGIN, 34f, W - MARGIN, 34f, INK12, 0.5f)
            }
            s.page.text(regular, 6.5f, MARGIN, H - 18f, "Made with Neue Master Tool", INK45)
        }
        return pdf.write()
    }

    // ---- type --------------------------------------------------------------------------------

    /** Words broken into lines no wider than [width]; a word longer than the line is cut. */
    fun wrap(text: String, font: PdfFont, size: Float, width: Float, tracking: Float = 0f): List<String> {
        val out = mutableListOf<String>()
        clean(text).split('\n').forEach { para ->
            var line = ""
            para.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                val trial = if (line.isEmpty()) word else "$line $word"
                if (font.width(trial, size, tracking) <= width) {
                    line = trial
                } else {
                    if (line.isNotEmpty()) out += line
                    var rest = word
                    while (font.width(rest, size, tracking) > width && rest.length > 1) {
                        var cut = rest.length - 1
                        while (cut > 1 && font.width(rest.substring(0, cut), size, tracking) > width) cut--
                        out += rest.substring(0, cut)
                        rest = rest.substring(cut)
                    }
                    line = rest
                }
            }
            if (line.isNotEmpty()) out += line
        }
        return out
    }

    /** [text] set in [width] from [top]; the height it took. Never more than [max] lines, the last cut short. */
    fun para(
        text: String, x: Float, top: Float, width: Float,
        font: PdfFont = regular, size: Float = BODY, lead: Float = LEAD, gray: Float = INK, max: Int = Int.MAX_VALUE, tracking: Float = 0f,
        p: PdfPage = page,
    ): Float {
        val lines = wrap(text, font, size, width, tracking).let { if (it.size > max) it.take(max - 1) + fit(it.drop(max - 1).joinToString(" "), font, size, width) else it }
        lines.forEachIndexed { i, l -> p.text(font, size, x, top + lead * i + size * 0.94f, l, gray, tracking) }
        return lead * lines.size
    }

    fun height(text: String, width: Float, font: PdfFont = regular, size: Float = BODY, lead: Float = LEAD, max: Int = Int.MAX_VALUE, tracking: Float = 0f): Float =
        lead * minOf(wrap(text, font, size, width, tracking).size, max)

    /** Micro caps: small, upper case, tracked open (MuType's micro, +0.08 em). */
    fun micro(p: PdfPage, text: String, x: Float, baseline: Float, gray: Float = INK45, size: Float = 7f) =
        p.text(regular, size, x, baseline, text.uppercase(), gray, tracking = size * 0.08f)

    fun microWidth(text: String, size: Float = 7f) = regular.width(text.uppercase(), size, size * 0.08f)

    /** A kicker over a headline: "01 — THE DECK". */
    fun kicker(n: Int, text: String, x: Float = MARGIN, top: Float = y) {
        page.text(mono, 7.5f, x, top + 7f, two(n), INK)
        micro(page, "— $text", x + 14f, top + 7f, INK45)
    }

    /** A headline that makes a claim: display type, tight. The height it took. */
    fun headline(text: String, x: Float = MARGIN, top: Float = y, width: Float = CW, size: Float = 26f, gray: Float = INK): Float =
        para(text, x, top, width, bold, size, size * 1.04f, gray, tracking = -size * 0.025f)

    fun right(p: PdfPage, font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float = INK, tracking: Float = 0f) =
        p.text(font, size, x - font.width(text, size, tracking), baseline, text, gray, tracking)

    fun centre(p: PdfPage, font: PdfFont, size: Float, cx: Float, baseline: Float, text: String, gray: Float = INK, tracking: Float = 0f) =
        p.text(font, size, cx - font.width(text, size, tracking) / 2, baseline, text, gray, tracking)

    fun fit(text: String, font: PdfFont, size: Float, width: Float): String {
        if (font.width(text, size) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…", size) > width) t = t.dropLast(1)
        return "${t.trimEnd()}…"
    }

    // ---- cards -------------------------------------------------------------------------------

    /** The card named [name], [w] wide at the card's shape: its art, or a quiet box with its name. */
    fun card(name: String, x: Float, top: Float, w: Float, p: PdfPage = page, line: Float = 0.5f, gray: Float = INK12) {
        val h = w * CARD
        val picture = art(name)
        if (picture != null) {
            p.image(picture, x, top, w, h)
        } else {
            p.fillRect(x, top, w, h, INK06)
            val size = if (w > 40) 6.5f else 5f
            wrap(name, mono, size, w - 6).take(5).forEachIndexed { i, l -> p.text(mono, size, x + 3, top + 9 + i * (size + 2), l, INK45) }
        }
        p.strokeRect(x, top, w, h, gray, line)
    }

    /**
     * The art of the card named [name] alone, cut from its frame and cropped to fill the box — the
     * picture a cover is built on. Falls back to the whole card, centred, when there is no picture.
     */
    fun artwork(name: String, x: Float, top: Float, w: Float, h: Float, p: PdfPage = page) {
        val picture = art(name)
        if (picture == null) {
            p.fillRect(x, top, w, h, INK06)
            return
        }
        // The card drawn so its art window covers the box, the rest clipped away.
        val win = ArtWindow.of(ArtFrame.STANDARD)
        val winW = win.right - win.left
        val winH = (win.bottom - win.top) * CARD
        val scale = maxOf(w / winW, h / winH)
        val cardW = scale
        val cardH = scale * CARD
        val cx = x + w / 2 - (win.left + winW / 2) * cardW
        val cy = top + h / 2 - (win.top + (win.bottom - win.top) / 2) * cardH
        p.clip(x, top, w, h) { p.image(picture, cx, cy, cardW, cardH) }
    }

    companion object {
        const val W = 400f
        const val H = 866f
        const val MARGIN = 20f
        const val CW = W - 2 * MARGIN
        const val GUTTER = 12f
        const val COL = (CW - 3 * GUTTER) / 4
        const val BASE = 4f
        const val TOP = 52f
        const val BOTTOM = H - 34f

        const val BODY = 10.5f
        const val LEAD = 15f
        const val CARD = 1.4583f

        const val INK = 0f
        const val INK80 = 0.2f
        const val INK70 = 0.3f
        const val INK45 = 0.55f
        const val INK25 = 0.75f
        const val INK12 = 0.88f
        const val INK06 = 0.95f
        const val PAPER = 1f

        /** The left edge of column [i] (0–3). */
        fun col(i: Int): Float = MARGIN + i * (COL + GUTTER)

        /** The width of [n] columns and the gutters between them. */
        fun span(n: Int): Float = n * COL + (n - 1) * GUTTER

        fun two(n: Int) = n.toString().padStart(2, '0')

        /** Markup a reader should not see: `[[Card]]` is the card's name, `**x**` is x. */
        fun clean(text: String): String = text.replace("[[", "").replace("]]", "").replace("**", "")
    }
}
