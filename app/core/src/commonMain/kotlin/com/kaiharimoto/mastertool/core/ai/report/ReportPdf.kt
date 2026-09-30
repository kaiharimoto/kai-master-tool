package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.ai.text.Inline
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.Zlib

/**
 * The deck's living guide and a Fine Tuning session's report as PDFs (1.0.54, kai: "formatted in
 * Master UI and PDF exportable. The PDF should be visually designed and formatted and
 * intuitive"). Master UI on paper: white and ink with its grey ramp, Inter and JetBrains Mono,
 * no radius and no shadow, rules rather than boxes, `01` numerals on the sections, the label
 * over a heavy rule on every page and the footer under it — the siding guide's page.
 *
 * What reads first reads largest: the three confidence scores as numbers with a meter each and
 * their change since the last session, then the key cards as pictures, then the sections. Entries
 * keep their bold and their `[[Card]]` names, set in bold.
 */
object ReportPdf {
    /** A card the guide names, with the picture the app shows for it. */
    data class KeyCard(val id: CardId, val name: String)

    private const val W = PdfDocument.A4_WIDTH
    private const val H = PdfDocument.A4_HEIGHT
    private const val ML = 40f
    private const val CW = W - 2 * ML
    private const val MT = 34f
    private const val TOP = MT + 62f
    private const val BOTTOM = H - 44f

    private const val INK = 0f
    private const val INK70 = 0.3f
    private const val INK45 = 0.55f
    private const val INK25 = 0.75f
    private const val INK12 = 0.88f
    private const val INK06 = 0.95f

    private const val BODY = 9.5f
    private const val LEAD = 13.5f

    /** The living guide: confidence and its history, the key cards, every section. */
    fun guide(
        doc: GuideDoc,
        deckName: String,
        reports: List<SessionReport>,
        keyCards: List<KeyCard>,
        updated: String,
        fonts: GuideFonts,
        image: (CardId) -> PdfImage?,
        zlib: Zlib?,
    ): ByteArray {
        val pdf = PdfDocument(zlib, title = "Deck guide · $deckName", author = "Neue Master Tool")
        val f = Fonts(pdf.font(fonts.regular), pdf.font(fonts.bold), pdf.font(fonts.mono))
        val latest = reports.maxByOrNull { it.at }
        val blocks = buildList {
            add(Space(4f))
            if (latest != null) {
                add(Label(f, "HOW WELL IT KNOWS THIS DECK"))
                add(Scores(f, latest, reports))
                if (reports.size >= 2) {
                    add(History(f, reports.takeLast(8)))
                    add(Space(6f))
                }
                add(Rich(f, listOf(Inline.Text(latest.why)), BODY, INK70).takeIf { latest.why.isNotBlank() } ?: Space(0f))
            } else {
                add(Rich(f, listOf(Inline.Text("No session has scored this deck yet: a Fine Tuning session ends with Ai's confidence, and it shows here.")), BODY, INK45))
            }
            add(Space(10f))
            if (keyCards.isNotEmpty()) {
                add(Label(f, "KEY CARDS"))
                add(Cards(f, keyCards.take(8), image))
                add(Space(8f))
            }
            if (doc.isEmpty) {
                add(Rich(f, listOf(Inline.Text("The guide is empty. Teach Ai the deck, or let it study it, and what it learns is written here.")), BODY + 1, INK45))
            }
            doc.sections.forEachIndexed { i, s ->
                add(Heading(f, i + 1, s.name, s.entries.size, keep = true))
                s.entries.forEach { add(Bullet(f, it)) }
                add(Space(8f))
            }
        }
        val sessions = reports.size
        val meta = listOf("Updated $updated", "$sessions ${if (sessions == 1) "session" else "sessions"}")
        paginate(pdf, f, blocks, "DECK GUIDE", deckName, meta)
        return pdf.write()
    }

    /** One session: what it came to, its scores and how they moved, what was asked and answered. */
    fun session(
        report: SessionReport,
        log: List<SessionReport>,
        guide: GuideDoc,
        date: String,
        fonts: GuideFonts,
        zlib: Zlib?,
    ): ByteArray {
        val pdf = PdfDocument(zlib, title = "Session report · ${report.deckName}", author = "Neue Master Tool")
        val f = Fonts(pdf.font(fonts.regular), pdf.font(fonts.bold), pdf.font(fonts.mono))
        val blocks = buildList {
            add(Space(4f))
            if (report.summary.isNotBlank()) {
                add(Rich(f, ChatMarkdown.inline(report.summary), 12.5f, INK, lead = 17f))
                add(Space(12f))
            }
            add(Label(f, "CONFIDENCE"))
            add(Scores(f, report, log))
            if (report.why.isNotBlank()) add(Rich(f, ChatMarkdown.inline(report.why), BODY, INK70))
            add(Space(12f))
            var n = 0
            fun section(name: String, items: List<String>, numbered: Boolean = false) {
                if (items.isEmpty()) return
                n++
                add(Heading(f, n, name, items.size, keep = true))
                items.forEachIndexed { i, t -> add(Bullet(f, t, if (numbered) (i + 1).toString().padStart(2, '0') else null)) }
                add(Space(8f))
            }
            section("What it learned", report.learned)
            section("Insights for your deckbuilding", report.insights, numbered = true)
            if (report.questions.isNotEmpty()) {
                n++
                add(Heading(f, n, "What it asked you", report.questions.size, keep = true))
                report.questions.forEach { add(Asked(f, it)) }
                add(Space(8f))
            }
            section("Still open", report.openQuestions)
            n++
            add(Heading(f, n, "The guide now", guide.entryCount, keep = true))
            add(
                Rich(
                    f,
                    listOf(
                        Inline.Text(
                            if (guide.isEmpty) "The guide is empty."
                            else guide.sections.joinToString(" · ") { "${it.name} ${it.entries.size}" } +
                                ". The whole guide is its own PDF: Guide · PDF in the app.",
                        ),
                    ),
                    BODY,
                    INK70,
                ),
            )
        }
        val meta = listOfNotNull(
            SessionReport.modeWords(report.mode),
            report.intensity.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
            date,
            report.minutes.takeIf { it > 0 }?.let { "$it min" },
        )
        paginate(pdf, f, blocks, "SESSION REPORT · FINE TUNING", report.deckName, meta)
        return pdf.write()
    }

    // ---- the page -------------------------------------------------------------------

    private class Fonts(val regular: PdfFont, val bold: PdfFont, val mono: PdfFont)

    private interface Block {
        val height: Float
        /** A heading goes to the next page with what follows it rather than alone at the bottom. */
        val keep: Boolean get() = false
        fun draw(page: PdfPage, top: Float)
    }

    private fun paginate(pdf: PdfDocument, f: Fonts, blocks: List<Block>, label: String, title: String, meta: List<String>) {
        val pages = mutableListOf<MutableList<Pair<Block, Float>>>(mutableListOf())
        var y = TOP
        blocks.forEachIndexed { i, b ->
            val need = b.height + if (b.keep) blocks.getOrNull(i + 1)?.height ?: 0f else 0f
            if (y + need > BOTTOM && pages.last().isNotEmpty()) {
                pages += mutableListOf<Pair<Block, Float>>()
                y = TOP
            }
            pages.last() += b to y
            y += b.height
        }
        pages.forEachIndexed { i, list ->
            val page = pdf.page()
            chrome(page, f, label, title, meta, i + 1, pages.size)
            list.forEach { (b, top) -> b.draw(page, top) }
        }
    }

    private fun chrome(page: PdfPage, f: Fonts, label: String, title: String, meta: List<String>, number: Int, of: Int) {
        page.text(f.regular, 7.5f, ML, MT + 8f, label, INK70)
        page.text(f.bold, 24f, ML, MT + 38f, fit(title, f.bold, 24f, CW - 190f))
        right(page, f.mono, 7.5f, W - ML, MT + 26f, meta.joinToString(" · "), INK70)
        right(page, f.mono, 7.5f, W - ML, MT + 37f, "page $number of $of", INK70)
        page.fillRect(ML, MT + 46f, CW, 1.5f, INK)
        page.line(ML, H - 34f, W - ML, H - 34f, INK12, 0.5f)
        page.text(f.regular, 7f, ML, H - 22f, "Made with Neue Master Tool", INK45)
        right(page, f.mono, 7f, W - ML, H - 22f, number.toString().padStart(2, '0'), INK45)
    }

    private fun right(page: PdfPage, font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float) =
        page.text(font, size, x - font.width(text, size), baseline, text, gray)

    private fun fit(text: String, font: PdfFont, size: Float, width: Float): String {
        if (font.width(text, size) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…", size) > width) t = t.dropLast(1)
        return "$t…"
    }

    // ---- rich text ------------------------------------------------------------------

    private data class Run(val text: String, val font: PdfFont)
    private data class Piece(val text: String, val font: PdfFont, val x: Float)

    private fun runs(f: Fonts, inlines: List<Inline>): List<Run> = inlines.map {
        when (it) {
            is Inline.Text -> Run(it.text, f.regular)
            is Inline.Bold -> Run(it.text, f.bold)
            is Inline.Italic -> Run(it.text, f.regular)
            is Inline.Code -> Run(it.text, f.mono)
            is Inline.Card -> Run(it.name, f.bold)
        }
    }

    /** Runs broken into lines no wider than [width], each word in its own font. */
    private fun lines(runs: List<Run>, size: Float, width: Float): List<List<Piece>> {
        val out = mutableListOf<MutableList<Piece>>(mutableListOf())
        var x = 0f
        // A run that ended in a space owes the next run's first word one.
        var owed = false
        runs.forEach { run ->
            val text = run.text.replace('\n', ' ')
            // Words keep the space in front of them, so a bold name glues to its comma.
            Regex("""\s*\S+""").findAll(text).forEachIndexed { i, m ->
                val word = if (i == 0 && owed && !m.value.first().isWhitespace()) " " + m.value else m.value
                val bare = word.trimStart()
                val w = run.font.width(if (x == 0f) bare else word, size)
                if (x > 0f && x + w > width) {
                    out += mutableListOf<Piece>()
                    x = 0f
                    val bw = run.font.width(bare, size)
                    out.last() += Piece(bare, run.font, 0f)
                    x = bw
                } else {
                    out.last() += Piece(if (x == 0f) bare else word, run.font, x)
                    x += w
                }
            }
            owed = text.isNotEmpty() && text.last().isWhitespace() || (owed && text.isBlank())
        }
        return out.filter { it.isNotEmpty() }.ifEmpty { listOf(mutableListOf()) }
    }

    private class Rich(private val f: Fonts, inlines: List<Inline>, private val size: Float, private val gray: Float, private val lead: Float = LEAD, private val indent: Float = 0f) : Block {
        private val lines = lines(runs(f, inlines), size, CW - indent)
        override val height: Float = lines.size * lead + 4f
        override fun draw(page: PdfPage, top: Float) {
            lines.forEachIndexed { i, line ->
                line.forEach { p -> page.text(p.font, size, ML + indent + p.x, top + lead * (i + 1) - 3f, p.text, gray) }
            }
        }
    }

    private class Space(override val height: Float) : Block {
        override fun draw(page: PdfPage, top: Float) = Unit
    }

    private class Label(private val f: Fonts, private val text: String) : Block {
        override val height = 16f
        override val keep = true
        override fun draw(page: PdfPage, top: Float) {
            page.text(f.regular, 7f, ML, top + 9f, text, INK70)
        }
    }

    /** `01  Goals` over a rule, and how many entries on the right. */
    private class Heading(private val f: Fonts, private val n: Int, private val name: String, private val count: Int, override val keep: Boolean) : Block {
        override val height = 26f
        override fun draw(page: PdfPage, top: Float) {
            page.text(f.mono, 8f, ML, top + 15f, n.toString().padStart(2, '0'), INK45)
            page.text(f.bold, 12f, ML + 20f, top + 15f, name)
            right(page, f.mono, 7.5f, W - ML, top + 15f, count.toString(), INK45)
            page.line(ML, top + 21f, W - ML, top + 21f, INK, 0.75f)
        }
    }

    /** One entry: a dash (or a number) and its words, hanging. */
    private class Bullet(private val f: Fonts, text: String, private val mark: String? = null) : Block {
        private val lines = lines(runs(f, ChatMarkdown.inline(text)), BODY, CW - 20f)
        override val height: Float = lines.size * LEAD + 3f
        override fun draw(page: PdfPage, top: Float) {
            if (mark != null) page.text(f.mono, 7.5f, ML, top + LEAD - 3f, mark, INK45) else page.text(f.regular, BODY, ML + 2f, top + LEAD - 3f, "–", INK45)
            lines.forEachIndexed { i, line -> line.forEach { p -> page.text(p.font, BODY, ML + 20f + p.x, top + LEAD * (i + 1) - 3f, p.text, INK) } }
        }
    }

    /** A question Ai asked, and the answer, under it, indented. */
    private class Asked(private val f: Fonts, private val a: SessionReport.Asked) : Block {
        // The question is set in bold, and measured in bold.
        private val q = lines(runs(f, ChatMarkdown.inline(a.question)).map { if (it.font == f.regular) it.copy(font = f.bold) else it }, BODY, CW - 20f)
        private val ans = lines(runs(f, ChatMarkdown.inline(a.answer.ifBlank { "No answer" })), BODY, CW - 32f)
        override val height: Float = (q.size + ans.size) * LEAD + 8f
        override fun draw(page: PdfPage, top: Float) {
            page.text(f.mono, 7.5f, ML, top + LEAD - 3f, "Q", INK45)
            q.forEachIndexed { i, line -> line.forEach { p -> page.text(p.font, BODY, ML + 20f + p.x, top + LEAD * (i + 1) - 3f, p.text, INK) } }
            val at = top + q.size * LEAD
            page.fillRect(ML + 20f, at + 3f, 1.5f, ans.size * LEAD, INK25)
            ans.forEachIndexed { i, line -> line.forEach { p -> page.text(p.font, BODY, ML + 32f + p.x, at + LEAD * (i + 1) - 3f, p.text, INK70) } }
        }
    }

    /** The three scores side by side: the number large, ten cells of meter, and the change since the session before. */
    private class Scores(private val f: Fonts, private val r: SessionReport, private val log: List<SessionReport>) : Block {
        override val height = 92f
        override fun draw(page: PdfPage, top: Float) {
            val gap = 16f
            val w = (CW - 2 * gap) / 3
            val items = listOf(
                Triple("UNDERSTANDING", r.understanding, "What the deck is for, and how its cards fit") to ReportLog.change(log, r) { it.understanding },
                Triple("PLAYING IT", r.playing, "Piloting it, turn by turn") to ReportLog.change(log, r) { it.playing },
                Triple("MIRROR MATCH", r.mirror, "Matches it expects to win against the same deck") to ReportLog.change(log, r) { it.mirror },
            )
            items.forEachIndexed { i, (t, delta) ->
                val (label, value, caption) = t
                val x = ML + i * (w + gap)
                page.fillRect(x, top, w, 1.5f, INK)
                page.text(f.regular, 7f, x, top + 13f, label, INK70)
                val number = if (label == "MIRROR MATCH") "$value%" else value.toString()
                page.text(f.mono, 30f, x, top + 46f, number)
                if (label != "MIRROR MATCH") page.text(f.mono, 9f, x + f.mono.width(number, 30f) + 3f, top + 46f, "/100", INK45)
                delta?.let {
                    val words = if (it == 0) "±0 since last" else "${if (it > 0) "+" else "−"}${kotlin.math.abs(it)} since last"
                    right(page, f.mono, 7.5f, x + w, top + 46f, words, if (it < 0) INK45 else INK)
                }
                // Ten cells, filled to the score: a meter, not a gradient.
                val cell = (w - 9 * 2f) / 10
                repeat(10) { k ->
                    val cx = x + k * (cell + 2f)
                    if (k < (value + 5) / 10) page.fillRect(cx, top + 54f, cell, 7f, INK) else page.strokeRect(cx, top + 54f, cell, 7f, INK25, 0.5f)
                }
                page.text(f.regular, 7f, x, top + 74f, fit(caption, f.regular, 7f, w), INK45)
            }
        }
    }

    /** The scores session by session: three bars a session, ink, mid grey and light grey. */
    private class History(private val f: Fonts, private val reports: List<SessionReport>) : Block {
        override val height = 96f
        override fun draw(page: PdfPage, top: Float) {
            val chartTop = top + 14f
            val chartH = 56f
            page.text(f.regular, 7f, ML, top + 8f, "SESSION BY SESSION", INK70)
            listOf(0, 50, 100).forEach { v ->
                val y = chartTop + chartH - chartH * v / 100f
                page.line(ML + 18f, y, W - ML, y, if (v == 0) INK else INK12, if (v == 0) 0.75f else 0.5f)
                page.text(f.mono, 6f, ML, y + 2f, v.toString(), INK45)
            }
            val slot = (CW - 18f) / reports.size
            val bar = minOf(10f, (slot - 10f) / 3)
            reports.forEachIndexed { i, r ->
                val x0 = ML + 18f + i * slot + (slot - 3 * bar - 4f) / 2
                listOf(r.understanding to INK, r.playing to INK45, r.mirror to INK25).forEachIndexed { k, (v, g) ->
                    val h = chartH * v / 100f
                    page.fillRect(x0 + k * (bar + 2f), chartTop + chartH - h, bar, h, g)
                }
                val tag = (i + 1).toString().padStart(2, '0')
                page.text(f.mono, 6.5f, ML + 18f + i * slot + slot / 2 - f.mono.width(tag, 6.5f) / 2, chartTop + chartH + 10f, tag, INK45)
            }
            var lx = ML + 18f
            listOf("Understanding" to INK, "Playing it" to INK45, "Mirror match" to INK25).forEach { (name, g) ->
                page.fillRect(lx, top + height - 10f, 7f, 7f, g)
                page.text(f.regular, 7f, lx + 10f, top + height - 4f, name, INK70)
                lx += 16f + f.regular.width(name, 7f) + 12f
            }
        }
    }

    /** The key cards as pictures, named under them. */
    private class Cards(private val f: Fonts, private val cards: List<KeyCard>, private val image: (CardId) -> PdfImage?) : Block {
        private val gap = 8f
        private val w = (CW - 7 * gap) / 8
        private val h = w * 86f / 59f
        override val height: Float = h + 26f
        override fun draw(page: PdfPage, top: Float) {
            cards.forEachIndexed { i, c ->
                val x = ML + i * (w + gap)
                val pic = image(c.id)
                if (pic != null) page.image(pic, x, top, w, h) else page.fillRect(x, top, w, h, INK06)
                page.strokeRect(x, top, w, h, INK12, 0.5f)
                val words = SidingNames.wrap2(c.name, f.regular, 6.5f, w)
                words.forEachIndexed { k, line -> page.text(f.regular, 6.5f, x, top + h + 9f + k * 8f, line, INK) }
            }
        }
    }

    private object SidingNames {
        /** A name in at most two lines, the second cut with an ellipsis. */
        fun wrap2(name: String, font: PdfFont, size: Float, width: Float): List<String> {
            val words = name.split(' ')
            var first = ""
            var i = 0
            while (i < words.size && font.width(if (first.isEmpty()) words[i] else "$first ${words[i]}", size) <= width) {
                first = if (first.isEmpty()) words[i] else "$first ${words[i]}"
                i++
            }
            if (first.isEmpty()) return listOf(fit(name, font, size, width))
            val rest = words.drop(i).joinToString(" ")
            return if (rest.isEmpty()) listOf(first) else listOf(first, fit(rest, font, size, width))
        }
    }
}
