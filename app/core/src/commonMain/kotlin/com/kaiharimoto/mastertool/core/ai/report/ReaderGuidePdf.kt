package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.Zlib

/**
 * The reader's guide as a PDF to share (1.0.66, kai: "these guides are also meant to be shared so
 * the layout and ease of understanding is very important"), in one of three layouts kai chooses
 * between: a **Primer** (a cover that says what the deck is, then each line as a chain of cards),
 * a **Cheat sheet** (two dense columns, a card beside each thing that names it) and a **Magazine**
 * (one idea to a page, large art). Master UI on paper, as the siding guide: ink and its greys, Inter
 * and JetBrains Mono, rules rather than boxes, `01` numerals; card art is the only colour.
 */
object ReaderGuidePdf {
    enum class Style(val label: String) { PRIMER("Primer"), SHEET("Cheat sheet"), MAGAZINE("Magazine") }

    private const val W = PdfDocument.A4_WIDTH
    private const val H = PdfDocument.A4_HEIGHT
    private const val ML = 40f
    private const val CW = W - 2 * ML
    private const val MT = 34f
    private const val TOP = MT + 62f
    private const val BOTTOM = H - 48f

    private const val INK = 0f
    private const val INK70 = 0.3f
    private const val INK45 = 0.55f
    private const val INK12 = 0.88f
    private const val INK06 = 0.95f

    /** A card's height for its width: the printed card's shape. */
    private const val CARD = 1.4583f

    fun render(guide: ReaderGuide, style: Style, fonts: GuideFonts, image: (String) -> PdfImage?, zlib: Zlib?, updated: String = ""): ByteArray {
        val pdf = PdfDocument(zlib, title = "${guide.deckName} · a guide", author = "Neue Master Tool")
        val pen = Pen(pdf, pdf.font(fonts.regular), pdf.font(fonts.bold), pdf.font(fonts.mono), image, guide.deckName, updated)
        when (style) {
            Style.PRIMER -> primer(pen, guide)
            Style.SHEET -> sheet(pen, guide)
            Style.MAGAZINE -> magazine(pen, guide)
        }
        pen.finish()
        return pdf.write()
    }

    // ---- the pen: pages, words and cards --------------------------------------------------

    private class Pen(
        val pdf: PdfDocument,
        val regular: PdfFont,
        val bold: PdfFont,
        val mono: PdfFont,
        val art: (String) -> PdfImage?,
        val title: String,
        val updated: String,
    ) {
        private val pages = mutableListOf<Pair<PdfPage, Boolean>>()
        lateinit var page: PdfPage
        var y = TOP

        /** A new page; [chrome] false for a cover that sets its own title. */
        fun next(chrome: Boolean = true) {
            page = pdf.page()
            pages += page to chrome
            y = if (chrome) TOP else MT
        }

        fun room(h: Float) {
            if (y + h > BOTTOM) next()
        }

        fun finish() {
            pages.forEachIndexed { i, (p, chrome) ->
                if (chrome) {
                    p.text(regular, 7.5f, ML, MT + 8f, "DECK GUIDE", INK70)
                    p.text(bold, 22f, ML, MT + 36f, fit(title, bold, 22f, CW - 150f))
                    right(p, mono, 7.5f, W - ML, MT + 26f, updated, INK70)
                    right(p, mono, 7.5f, W - ML, MT + 37f, "page ${i + 1} of ${pages.size}", INK70)
                    p.fillRect(ML, MT + 46f, CW, 1.5f, INK)
                }
                p.line(ML, H - 34f, W - ML, H - 34f, INK12, 0.5f)
                p.text(regular, 7f, ML, H - 22f, "Made with Neue Master Tool", INK45)
                right(p, mono, 7f, W - ML, H - 22f, (i + 1).toString().padStart(2, '0'), INK45)
            }
        }

        fun wrap(text: String, font: PdfFont, size: Float, width: Float): List<String> {
            val words = clean(text).split(' ').filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            var line = ""
            words.forEach { w ->
                val next = if (line.isEmpty()) w else "$line $w"
                if (line.isNotEmpty() && font.width(next, size) > width) {
                    out += line
                    line = w
                } else {
                    line = next
                }
            }
            if (line.isNotEmpty()) out += line
            return out
        }

        /** Words at [x], [width] wide, from [top]; how tall they were. */
        fun para(text: String, x: Float, top: Float, width: Float, font: PdfFont = regular, size: Float = 9.5f, lead: Float = size * 1.42f, gray: Float = INK, max: Int = Int.MAX_VALUE): Float {
            val lines = wrap(text, font, size, width).take(max)
            lines.forEachIndexed { i, l -> page.text(font, size, x, top + lead * (i + 1) - lead * 0.28f, l, gray) }
            return lines.size * lead
        }

        fun height(text: String, width: Float, font: PdfFont = regular, size: Float = 9.5f, lead: Float = size * 1.42f, max: Int = Int.MAX_VALUE): Float =
            wrap(text, font, size, width).take(max).size * lead

        /** A card's art at [x], [top], [w] wide; its name on grey when there is no picture. */
        fun card(name: String, x: Float, top: Float, w: Float) {
            val h = w * CARD
            val img = art(name)
            if (img != null) {
                page.image(img, x, top, w, h)
            } else {
                page.fillRect(x, top, w, h, INK06)
                wrap(name, mono, 5.5f, w - 6f).take(4).forEachIndexed { i, l -> page.text(mono, 5.5f, x + 3f, top + 10f + i * 7f, l, INK45) }
            }
            page.strokeRect(x, top, w, h, INK12, 0.5f)
        }

        /** `01  NAME` over a rule. */
        fun heading(n: Int, name: String, width: Float = CW, x: Float = ML) {
            room(40f)
            page.text(mono, 8f, x, y + 15f, n.toString().padStart(2, '0'), INK45)
            page.text(bold, 12f, x + 20f, y + 15f, name)
            page.line(x, y + 21f, x + width, y + 21f, INK, 0.75f)
            y += 32f
        }

        fun label(text: String, x: Float, top: Float, gray: Float = INK45) = page.text(regular, 7f, x, top + 8f, text.uppercase(), gray)
    }

    private fun clean(s: String) = s.replace("[[", "").replace("]]", "").replace("**", "").replace('\n', ' ')

    private fun right(page: PdfPage, font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float) =
        page.text(font, size, x - font.width(text, size), baseline, text, gray)

    private fun fit(text: String, font: PdfFont, size: Float, width: Float): String {
        if (font.width(text, size) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…", size) > width) t = t.dropLast(1)
        return "$t…"
    }

    // ---- A · Primer --------------------------------------------------------------------------

    private fun primer(p: Pen, g: ReaderGuide) {
        p.next()
        if (g.subtitle.isNotBlank()) {
            p.page.text(p.mono, 8f, ML, p.y + 8f, g.subtitle, INK70)
            p.y += 18f
        }
        p.y += p.para(g.pitch, ML, p.y, CW * 0.86f, size = 13f, lead = 18.5f) + 18f
        var n = 1
        // Key cards, a row a role: the role on the left, its cards with a line each.
        if (g.roles.isNotEmpty()) {
            p.heading(n++, "The cards")
            g.roles.forEach { role ->
                val per = 4
                role.cards.chunked(per).forEach { row ->
                    val cell = (CW - 92f) / per
                    val cw = 50f
                    val rowH = row.maxOf { c -> cw * CARD + 6f + p.height(c.card, cell - 8f, p.bold, 7.5f, 10f, 2) + p.height(c.note, cell - 8f, size = 7.5f, lead = 10f, max = 4) } + 14f
                    p.room(rowH)
                    if (row === role.cards.chunked(per).first()) p.page.text(p.bold, 9f, ML, p.y + 10f, role.name)
                    row.forEachIndexed { i, c ->
                        val x = ML + 92f + i * cell
                        p.card(c.card, x, p.y, cw)
                        if (c.copies > 0) p.page.text(p.mono, 7f, x + cw + 4f, p.y + 8f, "×${c.copies}", INK45)
                        var t = p.y + cw * CARD + 6f
                        t += p.para(c.card, x, t, cell - 8f, p.bold, 7.5f, 10f, max = 2)
                        p.para(c.note, x, t, cell - 8f, size = 7.5f, lead = 10f, gray = INK70, max = 4)
                    }
                    p.y += rowH
                }
                p.page.line(ML, p.y - 6f, W - ML, p.y - 6f, INK12, 0.5f)
            }
            p.y += 8f
        }
        // The plan, going first and second side by side.
        if (g.goingFirst.isNotEmpty() || g.goingSecond.isNotEmpty()) {
            p.heading(n++, "The plan")
            val col = (CW - 24f) / 2
            val need = maxOf(listHeight(p, g.goingFirst, col), listHeight(p, g.goingSecond, col)) + 20f
            p.room(need)
            p.label("Going first", ML, p.y)
            p.label("Going second", ML + col + 24f, p.y)
            numbered(p, g.goingFirst, ML, p.y + 16f, col)
            numbered(p, g.goingSecond, ML + col + 24f, p.y + 16f, col)
            p.y += need + 10f
        }
        // Each line as a chain of cards, the action under each, then the board it ends on.
        if (g.lines.isNotEmpty()) {
            p.heading(n++, "The lines")
            g.lines.forEachIndexed { li, line -> chain(p, li + 1, line, perRow = 5, cw = 58f) }
        }
        chokes(p, n++, g.chokePoints)
        siding(p, n++, g.siding)
        tips(p, n, g)
    }

    private fun listHeight(p: Pen, items: List<String>, width: Float) = items.sumOf { p.height(it, width - 18f).toDouble() + 4.0 }.toFloat()

    private fun numbered(p: Pen, items: List<String>, x: Float, top: Float, width: Float, size: Float = 9.5f): Float {
        var t = top
        items.forEachIndexed { i, it ->
            p.page.text(p.mono, 7.5f, x, t + size * 1.42f * 0.72f, (i + 1).toString().padStart(2, '0'), INK45)
            t += p.para(it, x + 18f, t, width - 18f, size = size) + 4f
        }
        return t - top
    }

    /** A line: its name, a note, the steps as cards with arrows and their actions, the end board. */
    private fun chain(p: Pen, n: Int, line: ReaderGuide.Line, perRow: Int, cw: Float) {
        val cell = CW / perRow
        val actionW = cell - 12f
        val head = 18f + if (line.note.isNotBlank()) p.height(line.note, CW, size = 9f) + 4f else 0f
        val firstRow = line.steps.take(perRow)
        val rowH = { steps: List<ReaderGuide.Step> -> cw * CARD + 8f + steps.maxOf { p.height(it.action, actionW, size = 8f, lead = 11f, max = 5) } + 12f }
        p.room(head + rowH(firstRow))
        p.page.text(p.mono, 8f, ML, p.y + 11f, "LINE ${n.toString().padStart(2, '0')}", INK45)
        p.page.text(p.bold, 11.5f, ML + 52f, p.y + 11f, line.name)
        p.y += 18f
        if (line.note.isNotBlank()) p.y += p.para(line.note, ML, p.y, CW, size = 9f, gray = INK70) + 4f
        line.steps.chunked(perRow).forEachIndexed { r, steps ->
            val h = rowH(steps)
            p.room(h)
            steps.forEachIndexed { i, s ->
                val x = ML + i * cell
                p.page.text(p.mono, 7f, x, p.y + 7f, (r * perRow + i + 1).toString(), INK45)
                p.card(s.card, x + 10f, p.y, cw)
                val last = r * perRow + i == line.steps.lastIndex
                if (!last && i < steps.lastIndex) p.page.text(p.regular, 12f, x + 10f + cw + (cell - cw - 10f) / 2 - 5f, p.y + cw * CARD / 2 + 4f, "→", INK45)
                p.para(s.action, x + 10f, p.y + cw * CARD + 6f, actionW, size = 8f, lead = 11f, max = 5)
            }
            p.y += h
        }
        if (line.endBoard.isNotEmpty()) {
            p.room(56f)
            p.label("Ends on", ML, p.y)
            line.endBoard.forEachIndexed { i, c -> p.card(c, ML + 60f + i * 34f, p.y - 2f, 28f) }
            p.para(line.endBoard.joinToString(" · "), ML + 60f + line.endBoard.size * 34f + 6f, p.y + 6f, CW - 70f - line.endBoard.size * 34f, size = 7.5f, lead = 10f, gray = INK70, max = 4)
            p.y += 28f * CARD + 10f
        }
        p.page.line(ML, p.y, W - ML, p.y, INK12, 0.5f)
        p.y += 14f
    }

    private fun chokes(p: Pen, n: Int, items: List<ReaderGuide.Choke>) {
        if (items.isEmpty()) return
        p.heading(n, "What stops it")
        items.forEach { c ->
            val textW = CW - 50f
            val h = maxOf(34f * CARD, p.height(c.card, textW, p.bold, 9.5f) + p.height(c.text, textW)) + 10f
            p.room(h)
            if (c.card.isNotBlank()) p.card(c.card, ML, p.y, 34f)
            var t = p.y
            if (c.card.isNotBlank()) t += p.para(c.card, ML + 46f, t, textW, p.bold)
            p.para(c.text, ML + 46f, t, textW, gray = INK70)
            p.y += h
        }
        p.y += 6f
    }

    private fun siding(p: Pen, n: Int, sides: List<ReaderGuide.Side>) {
        if (sides.isEmpty()) return
        p.heading(n, "Siding")
        sides.forEach { s ->
            val cw = 30f
            val whyH = if (s.why.isNotBlank()) p.height(s.why, CW - 20f, size = 9f) + 6f else 0f
            val h = 22f + 2 * (cw * CARD + 8f) + whyH + 14f
            p.room(h)
            p.page.strokeRect(ML, p.y, CW, h - 8f, INK12, 0.75f)
            p.page.text(p.bold, 11f, ML + 10f, p.y + 16f, "vs ${s.matchup}")
            var t = p.y + 24f
            listOf("In" to s.sideIn, "Out" to s.sideOut).forEach { (word, cards) ->
                p.label(word, ML + 10f, t + 14f, if (word == "In") INK else INK45)
                cards.forEachIndexed { i, c -> p.card(c, ML + 44f + i * (cw + 5f), t, cw) }
                val names = cards.groupingBy { it }.eachCount().entries.joinToString(" · ") { (k, v) -> if (v > 1) "$v× $k" else k }
                p.para(names, ML + 44f + cards.size * (cw + 5f) + 8f, t + 4f, CW - 60f - cards.size * (cw + 5f), size = 8f, lead = 11f, gray = INK70, max = 4)
                t += cw * CARD + 8f
            }
            if (s.why.isNotBlank()) p.para(s.why, ML + 10f, t + 2f, CW - 20f, size = 9f)
            p.y += h
        }
    }

    private fun tips(p: Pen, n: Int, g: ReaderGuide) {
        if (g.tips.isNotEmpty()) {
            p.heading(n, "Tips")
            p.room(listHeight(p, g.tips, CW))
            p.y += numbered(p, g.tips, ML, p.y, CW) + 10f
        }
        if (g.sources.isNotEmpty()) {
            val h = g.sources.sumOf { p.height(it, CW, size = 7.5f, lead = 10f).toDouble() }.toFloat() + 18f
            p.room(h)
            p.label("Sources", ML, p.y)
            var t = p.y + 14f
            g.sources.forEach { t += p.para(it, ML, t, CW, size = 7.5f, lead = 10f, gray = INK45) }
            p.y = t + 6f
        }
    }

    // ---- B · Cheat sheet ------------------------------------------------------------------------

    /** Two columns of short items, a small card beside each that names one; the columns flow on. */
    private fun sheet(p: Pen, g: ReaderGuide) {
        p.next()
        if (g.subtitle.isNotBlank()) {
            p.page.text(p.mono, 8f, ML, p.y + 8f, g.subtitle, INK70)
            p.y += 16f
        }
        p.y += p.para(g.pitch, ML, p.y, CW, size = 10f, lead = 14f) + 12f
        val gap = 20f
        val col = (CW - gap) / 2
        var c = 0
        var top = p.y
        var y = top
        fun x() = ML + c * (col + gap)
        fun take(h: Float) {
            if (y + h > BOTTOM) {
                if (c == 0) {
                    c = 1
                    y = top
                } else {
                    p.next()
                    c = 0
                    top = p.y
                    y = top
                }
            }
        }
        fun head(name: String) {
            take(34f)
            p.page.text(p.bold, 10f, x(), y + 12f, name)
            p.page.line(x(), y + 17f, x() + col, y + 17f, INK, 0.75f)
            y += 24f
        }
        val thumb = 22f
        fun item(card: String?, bold: String?, text: String) {
            val tw = col - thumb - 8f
            val h = maxOf(if (card != null) thumb * CARD else 0f, (if (bold != null) p.height(bold, tw, p.bold, 8.5f, 11.5f) else 0f) + p.height(text, tw, size = 8.5f, lead = 11.5f)) + 6f
            take(h) // first: it may move to the next column, and the text goes where the card does
            val tx = x() + thumb + 8f
            if (card != null) p.card(card, x(), y, thumb)
            var t = y
            if (bold != null) t += p.para(bold, tx, t, tw, p.bold, 8.5f, 11.5f)
            p.para(text, tx, t, tw, size = 8.5f, lead = 11.5f, gray = INK70)
            y += h
        }
        g.roles.forEach { r ->
            head(r.name)
            r.cards.forEach { item(it.card, (if (it.copies > 0) "${it.copies}× " else "") + it.card, it.note) }
        }
        if (g.goingFirst.isNotEmpty()) {
            head("Going first")
            g.goingFirst.forEachIndexed { i, s -> item(null, null, "${i + 1}. $s") }
        }
        if (g.goingSecond.isNotEmpty()) {
            head("Going second")
            g.goingSecond.forEachIndexed { i, s -> item(null, null, "${i + 1}. $s") }
        }
        g.lines.forEachIndexed { li, l ->
            head("Line ${li + 1} · ${l.name}")
            if (l.note.isNotBlank()) item(null, null, l.note)
            l.steps.forEachIndexed { i, s -> item(s.card, "${i + 1}  ${s.card}", s.action) }
            if (l.endBoard.isNotEmpty()) item(null, "Ends on", l.endBoard.joinToString(" · "))
        }
        if (g.chokePoints.isNotEmpty()) {
            head("What stops it")
            g.chokePoints.forEach { item(it.card.ifBlank { null }, it.card.ifBlank { null }, it.text) }
        }
        g.siding.forEach { s ->
            head("vs ${s.matchup}")
            item(s.sideIn.firstOrNull(), "In", s.sideIn.groupingBy { it }.eachCount().entries.joinToString(" · ") { (k, v) -> if (v > 1) "$v× $k" else k })
            item(s.sideOut.firstOrNull(), "Out", s.sideOut.groupingBy { it }.eachCount().entries.joinToString(" · ") { (k, v) -> if (v > 1) "$v× $k" else k })
            if (s.why.isNotBlank()) item(null, null, s.why)
        }
        if (g.tips.isNotEmpty()) {
            head("Tips")
            g.tips.forEachIndexed { i, t -> item(null, null, "${i + 1}. $t") }
        }
        if (g.sources.isNotEmpty()) {
            head("Sources")
            g.sources.forEach { item(null, null, it) }
        }
    }

    // ---- C · Magazine -----------------------------------------------------------------------------

    /** One idea to a page, the cards large: a cover, the cards and the plan, a page a line, the rest. */
    private fun magazine(p: Pen, g: ReaderGuide) {
        // The cover: the title large, three cards across, the pitch under them.
        p.next(chrome = false)
        p.page.text(p.regular, 8f, ML, MT + 10f, "DECK GUIDE", INK70)
        p.page.text(p.bold, 46f, ML, MT + 80f, fit(g.deckName, p.bold, 46f, CW))
        if (g.subtitle.isNotBlank()) p.page.text(p.mono, 9f, ML, MT + 100f, g.subtitle, INK70)
        p.page.fillRect(ML, MT + 112f, CW, 2f, INK)
        val cover = g.roles.mapNotNull { it.cards.firstOrNull()?.card }.distinct().take(3).ifEmpty { g.cards().take(3) }
        val big = (CW - 2 * 14f) / 3
        cover.forEachIndexed { i, c -> p.card(c, ML + i * (big + 14f), MT + 132f, big) }
        p.para(g.pitch, ML, MT + 150f + big * CARD, CW, size = 15f, lead = 21f)
        // The cards by role, larger, and the plan.
        p.next()
        var n = 1
        p.heading(n++, "The cards")
        g.roles.forEach { role ->
            val cw = 64f
            val cell = CW / 4
            role.cards.chunked(4).forEachIndexed { r, row ->
                val h = cw * CARD + 8f + row.maxOf { p.height(it.note, cell - 10f, size = 8f, lead = 11f, max = 4) } + 26f
                p.room(h + if (r == 0) 16f else 0f)
                if (r == 0) {
                    p.label(role.name, ML, p.y)
                    p.y += 14f
                }
                row.forEachIndexed { i, c ->
                    val x = ML + i * cell
                    p.card(c.card, x, p.y, cw)
                    var t = p.y + cw * CARD + 6f
                    t += p.para(c.card, x, t, cell - 10f, p.bold, 8f, 11f, max = 2)
                    p.para(c.note, x, t, cell - 10f, size = 8f, lead = 11f, gray = INK70, max = 4)
                }
                p.y += h
            }
        }
        if (g.goingFirst.isNotEmpty() || g.goingSecond.isNotEmpty()) {
            p.heading(n++, "The plan")
            val col = (CW - 24f) / 2
            val need = maxOf(listHeight(p, g.goingFirst, col), listHeight(p, g.goingSecond, col)) + 20f
            p.room(need)
            p.label("Going first", ML, p.y)
            p.label("Going second", ML + col + 24f, p.y)
            numbered(p, g.goingFirst, ML, p.y + 16f, col, 10.5f)
            numbered(p, g.goingSecond, ML + col + 24f, p.y + 16f, col, 10.5f)
            p.y += need + 10f
        }
        // What stops it, under the plan: the two belong together, and it fills the plan's page.
        if (g.chokePoints.isNotEmpty()) chokes(p, n++, g.chokePoints)
        // A page a line, three large steps across (four when that saves a lone card on a row of its own).
        g.lines.forEachIndexed { li, line ->
            p.next()
            p.page.text(p.mono, 8f, ML, p.y + 8f, "LINE ${(li + 1).toString().padStart(2, '0')}", INK45)
            p.page.text(p.bold, 24f, ML, p.y + 36f, fit(line.name, p.bold, 24f, CW))
            p.y += 46f
            if (line.note.isNotBlank()) p.y += p.para(line.note, ML, p.y, CW, size = 11f, gray = INK70) + 12f
            val per = if (line.steps.size > 1 && line.steps.size % 3 == 1) 4 else 3
            val cell = CW / per
            val cw = cell - 40f
            line.steps.chunked(per).forEachIndexed { r, row ->
                val h = cw * CARD + 12f + row.maxOf { p.height(it.action, cell - 16f, size = 10f, lead = 14f, max = 6) } + 18f
                p.room(h)
                row.forEachIndexed { i, s ->
                    val x = ML + i * cell
                    p.page.text(p.bold, 18f, x, p.y + 16f, (r * per + i + 1).toString())
                    p.card(s.card, x + 22f, p.y, cw)
                    p.para(s.action, x + 22f, p.y + cw * CARD + 8f, cell - 30f, size = 10f, lead = 14f, max = 6)
                }
                p.y += h
            }
            if (line.endBoard.isNotEmpty()) {
                val end = 64f
                p.room(14f + end * CARD + 10f)
                p.label("Ends on", ML, p.y)
                line.endBoard.forEachIndexed { i, c -> p.card(c, ML + i * (end + 8f), p.y + 14f, end) }
                p.y += 14f + end * CARD + 10f
            }
        }
        // The rest: siding and tips.
        p.next()
        siding(p, n++, g.siding)
        tips(p, n, g)
    }
}
