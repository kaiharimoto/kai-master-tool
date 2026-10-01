package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK12
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK45
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK70
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.two
import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.Zlib

/**
 * A book as a PDF for a phone (1.0.67): 400 × 866 points, one section to a screen at least.
 *
 * The cover is the one kai chose from the second exploration — the deck's hub as a picture, its name,
 * the big idea — and the **table of contents** under it, every chapter and section with its page,
 * each a link. Each chapter opens on its own page with its sections listed; each section opens with a
 * numbered kicker and a headline (or a lesson's maxim), then its blocks: words, and the pictures
 * [BookArt] lays out, a line's board after each play two across under it. The PDF's bookmarks are the
 * contents again, so a phone's viewer lists them. Laid out twice: once to learn where each section
 * lands, once to write the contents with those pages.
 */
object BookPdf {
    const val W = 400f
    const val H = 866f
    const val MARGIN = 20f
    const val CW = W - 2 * MARGIN
    private const val TOP = 52f
    private const val BOTTOM = H - 34f

    fun render(
        book: GuideBook,
        fonts: GuideFonts,
        art: (String) -> PdfImage?,
        zlib: Zlib?,
        updated: String = "",
        deck: List<String>? = null,
        kind: CardKind? = null,
        hero: (String) -> PdfImage? = art,
    ): ByteArray {
        val bookArt = BookArt.of(Faces.of(fonts), book, deck, kind)
        // First to learn where each section lands, then for real.
        val first = Paper(fonts, art, hero, null, book.title)
        Layout(first, book, bookArt, updated, null).run()
        val second = Paper(fonts, art, hero, zlib, book.title)
        Layout(second, book, bookArt, updated, first.landed).run()
        return second.finish()
    }

    /** The card the cover sets large: the engine's first hub, else the first lesson's card, else the first role's. */
    fun hero(book: GuideBook): String? =
        book.chapters.asSequence().flatMap { it.sections }.flatMap { it.blocks }.filterIsInstance<Block.Engine>().firstOrNull()
            ?.let { e -> EngineLayout.of(e.edges).let { l -> l.rows.flatten().firstOrNull { it in l.hubs } } }
            ?: book.chapters.asSequence().flatMap { it.sections }.flatMap { it.blocks }.filterIsInstance<Block.Lesson>().firstOrNull()?.card?.takeIf { it.isNotBlank() }
            ?: book.roles.firstOrNull()?.cards?.firstOrNull()?.card

    /** The PDF being written, page by page, and where each chapter and section began. */
    private class Paper(fonts: GuideFonts, art: (String) -> PdfImage?, hero: (String) -> PdfImage?, zlib: Zlib?, title: String) {
        val pdf = PdfDocument(zlib, title = "$title · a guide", author = "Neue Master Tool")
        val fontsBy: Map<Weight, PdfFont> = mapOf(
            Weight.REGULAR to pdf.font(fonts.regular),
            Weight.MEDIUM to pdf.font(fonts.medium),
            Weight.BOLD to pdf.font(fonts.bold),
            Weight.MONO to pdf.font(fonts.mono),
        )
        val faces = Faces.of(fonts)
        private val artFn = art
        private val heroFn = hero
        val pen = Pen(faces, RecordingInk(), title)

        class Screen(val page: PdfPage, val head: String, val running: Boolean)

        val screens = mutableListOf<Screen>()
        lateinit var page: PdfPage
        var y = 0f
        var head = ""

        /** Chapter and section ids to the page (1-based) and the place they begin. */
        val landed = LinkedHashMap<String, Pair<Int, Float>>()
        val links = mutableListOf<Triple<PdfPage, FloatArray, String>>()

        fun screen(head: String = this.head, running: Boolean = true) {
            this.head = head
            page = pdf.page(W, H)
            pen.ink = PdfInk(page, fontsBy, artFn, heroFn)
            screens += Screen(page, head, running)
            y = if (running) TOP else MARGIN
        }

        fun room(h: Float) {
            if (y + h > BOTTOM) screen()
        }

        fun land(id: String) {
            landed[id] = screens.size to y
        }

        fun finish(): ByteArray {
            val n = screens.size
            val faces = faces
            screens.forEachIndexed { i, s ->
                val ink = PdfInk(s.page, fontsBy, artFn, heroFn)
                val p = Pen(faces, ink, "")
                if (s.running) {
                    p.micro(s.head, MARGIN, 26f, INK45)
                    p.right(faces.mono, 7.5f, W - MARGIN, 26f, "${two(i + 1)}/${two(n)}", INK45)
                    ink.line(MARGIN, 34f, W - MARGIN, 34f, INK12, 0.5f)
                }
                ink.text(faces.regular, 6.5f, MARGIN, H - 18f, "Made with Neue Master Tool", INK45)
            }
            // Links and bookmarks now every page exists.
            links.forEach { (from, r, id) -> landed[id]?.let { (pageNo, top) -> from.link(r[0], r[1], r[2], r[3], screens[pageNo - 1].page, top) } }
            return pdf.write()
        }
    }

    private class Layout(val paper: Paper, val book: GuideBook, val art: BookArt, val updated: String, val pages: Map<String, Pair<Int, Float>>?) {
        val pen get() = paper.pen
        val faces get() = paper.faces

        fun pageOf(id: String): String = pages?.get(id)?.first?.let(::two) ?: "00"

        fun run() {
            cover()
            book.chapters.forEachIndexed { ci, c -> chapter(ci, c) }
            sources()
            bookmarks()
        }

        private fun bookmarks() {
            if (pages == null) return
            paper.pdf.bookmarks += book.chapters.mapNotNull { c ->
                val (p, top) = paper.landed[c.id] ?: return@mapNotNull null
                PdfDocument.Bookmark(
                    c.title, paper.screens[p - 1].page, top,
                    c.sections.mapNotNull { s -> paper.landed[s.id]?.let { (sp, st) -> PdfDocument.Bookmark(s.title, paper.screens[sp - 1].page, st) } },
                )
            }
        }

        /** The cover: the hub's art, the name, the big idea, and the contents with their pages. */
        private fun cover() {
            paper.screen("Contents", running = false)
            val hub = hero(book)
            val heroH = if (hub != null) 330f else 0f
            if (hub != null) {
                pen.ink.artwork(hub, 0f, 0f, W, heroH)
                pen.ink.fillRect(0f, heroH, W, 4f, INK)
            }
            var y = heroH + 26f
            pen.micro("A deck guide", MARGIN, y, INK45)
            y += 8f
            y += pen.headline(book.title, MARGIN, y, CW, 46f) + 10f
            if (book.subtitle.isNotBlank()) {
                pen.ink.text(faces.mono, 7.5f, MARGIN, y + 8f, book.subtitle, INK45)
                y += 18f
            }
            if (book.bigIdea.isNotBlank()) y += pen.para(book.bigIdea, MARGIN, y, CW, faces.medium, 16f, 21f, INK, tracking = -0.2f) + 16f
            paper.y = y
            contents()
        }

        /** The table of contents: chapters numbered, their sections under them, each with its page and a link. */
        private fun contents() {
            fun rule() {
                paper.page.line(MARGIN, paper.y, W - MARGIN, paper.y, INK, 0.8f)
                paper.y += 10f
            }
            rule()
            pen.micro("Contents", MARGIN, paper.y + 7f, INK45)
            paper.y += 16f
            book.chapters.forEachIndexed { ci, c ->
                val titleH = pen.height(c.title, CW - 60f, faces.medium, 13f, 17f)
                paper.room(titleH + 6f)
                val top = paper.y
                pen.ink.text(faces.mono, 10f, MARGIN, top + 12f, two(ci + 1), INK)
                pen.para(c.title, MARGIN + 26f, top, CW - 60f, faces.medium, 13f, 17f, if (c.written) INK else INK45)
                pen.right(faces.mono, 8f, W - MARGIN, top + 12f, if (c.written) "p. ${pageOf(c.id)}" else "planned", INK45)
                paper.links += Triple(paper.page, floatArrayOf(MARGIN, top, CW, titleH), c.id)
                paper.y += titleH + 2f
                c.sections.forEach { s ->
                    val h = pen.height(s.title, CW - 70f, faces.regular, 9.5f, 13f)
                    paper.room(h + 2f)
                    val st = paper.y
                    pen.para(s.title, MARGIN + 26f, st, CW - 70f, faces.regular, 9.5f, 13f, INK70)
                    pen.right(faces.mono, 7.5f, W - MARGIN, st + 10f, pageOf(s.id), INK45)
                    paper.links += Triple(paper.page, floatArrayOf(MARGIN, st, CW, h), s.id)
                    paper.y += h + 1f
                }
                paper.y += 8f
            }
        }

        /** A chapter's opening page: its number large, its title, what it holds, its sections with their pages. */
        private fun chapter(ci: Int, c: GuideBook.Chapter) {
            paper.screen(c.title)
            paper.land(c.id)
            var y = paper.y + 20f
            pen.micro("Chapter", MARGIN, y + 7f, INK45)
            y += 12f
            pen.ink.text(faces.mono, 72f, MARGIN - 3f, y + 66f, two(ci + 1), INK, tracking = -2.5f)
            y += 84f
            y += pen.headline(c.title, MARGIN, y, CW, 34f) + 10f
            if (c.summary.isNotBlank()) y += pen.para(c.summary, MARGIN, y, CW, faces.medium, 13f, 18f, INK70) + 18f
            paper.y = y
            if (!c.written) {
                pen.para("Not written yet. Ai writes it in its next writing session.", MARGIN, paper.y, CW, faces.regular, 10f, 14f, INK45)
                return
            }
            paper.page.line(MARGIN, paper.y, W - MARGIN, paper.y, INK, 0.8f)
            paper.y += 12f
            c.sections.forEachIndexed { si, s ->
                val h = pen.height(s.title, CW - 70f, faces.regular, 11f, 15f)
                paper.room(h + 4f)
                val st = paper.y
                pen.ink.text(faces.mono, 8f, MARGIN, st + 10f, "${ci + 1}.${si + 1}", INK45)
                pen.para(s.title, MARGIN + 30f, st, CW - 70f, faces.regular, 11f, 15f, INK)
                pen.right(faces.mono, 7.5f, W - MARGIN, st + 10f, pageOf(s.id), INK45)
                paper.links += Triple(paper.page, floatArrayOf(MARGIN, st, CW, h), s.id)
                paper.y += h + 4f
            }
            c.sections.forEachIndexed { si, s -> section(ci, si, c, s) }
        }

        /** A section: a page of its own, its kicker, its headline or lesson, then its blocks. */
        private fun section(ci: Int, si: Int, c: GuideBook.Chapter, s: GuideBook.Section) {
            paper.screen(c.title)
            paper.land(s.id)
            pen.ink.text(faces.mono, 7.5f, MARGIN, paper.y + 7f, "${two(ci + 1)}.${si + 1}", INK)
            pen.micro("— ${c.title}", MARGIN + 26f, paper.y + 7f, INK45)
            paper.y += 18f
            if (s.blocks.firstOrNull() !is Block.Lesson) paper.y += pen.headline(s.title, MARGIN, paper.y, CW, 24f) + 12f
            s.blocks.forEach { b -> block(b) }
        }

        private fun block(b: Block) {
            when (b) {
                is Block.Text -> text(b)
                is Block.Line -> {
                    art.drawings(b, CW).forEach { place(it, 0f) }
                    if (b.frames && b.line.steps.isNotEmpty()) frames(b)
                    else if (b.line.endBoard.isNotEmpty() || b.line.endSet.isNotEmpty()) endBoard(b)
                    paper.y += 10f
                }
                else -> {
                    art.drawings(b, CW).forEach { place(it, 6f) }
                    paper.y += 8f
                }
            }
        }

        /** A drawing where the page has room for it, else at the top of the next. */
        private fun place(d: Drawing, gap: Float) {
            paper.room(d.height)
            d.paint(pen.ink, faces.byWeight, MARGIN, paper.y)
            paper.y += d.height + gap
        }

        /** Words, broken across pages where they must; a label sits in the margin to their left. */
        private fun text(t: Block.Text) {
            if (t.text.isBlank()) return
            val labelled = t.label.isNotBlank()
            val x = if (labelled) MARGIN + 86f else MARGIN
            val w = W - MARGIN - x
            val size = if (labelled) 11f else 11.5f
            val lead = size * 1.5f
            var first = true
            t.text.split('\n').filter { it.isNotBlank() }.forEach { para ->
                pen.wrap(para, faces.regular, size, w).forEach { line ->
                    paper.room(lead)
                    if (first && labelled) pen.micro(t.label, MARGIN, paper.y + 9f, INK)
                    first = false
                    pen.ink.text(faces.regular, size, x, paper.y + size * 0.94f, line, INK)
                    paper.y += lead
                }
                paper.y += 5f
            }
            paper.y += 8f
        }

        /** A line's board after each play, two across, what changed framed. */
        private fun frames(b: Block.Line) {
            val fw = (CW - 18f) / 2
            val frames = art.frames(b.line, fw)
            paper.room(30f + (frames.firstOrNull()?.drawing?.height ?: 0f))
            paper.page.fillRect(MARGIN, paper.y, CW, 1.5f, INK)
            pen.micro("The board after each play", MARGIN, paper.y + 14f, INK)
            paper.y += 24f
            frames.chunked(2).forEach { pair ->
                val h = pair.maxOf { it.drawing.height }
                paper.room(h)
                pair.forEachIndexed { k, f -> f.drawing.paint(pen.ink, faces.byWeight, MARGIN + k * (fw + 18f), paper.y) }
                paper.y += h + 14f
            }
        }

        private fun endBoard(b: Block.Line) {
            val d = art.drawings(Block.Board(b.line.endBoard, b.line.endSet, "Ends on: " + (b.line.endBoard + b.line.endSet.map { "$it (set)" }).joinToString(", ")), CW).first()
            paper.room(d.height + 20f)
            pen.micro("Ends on", MARGIN, paper.y + 7f, INK)
            paper.y += 14f
            place(d, 6f)
        }

        private fun sources() {
            if (book.sources.isEmpty() && updated.isBlank()) return
            paper.screen("Sources")
            pen.micro("Sources", MARGIN, paper.y + 7f, INK45)
            paper.y += 16f
            book.sources.forEach { paper.y += pen.para(it, MARGIN, paper.y, CW, faces.regular, 9f, 13f, INK70) + 4f }
            if (updated.isNotBlank()) pen.ink.text(faces.mono, 7f, MARGIN, paper.y + 10f, "Updated $updated", INK45)
        }
    }
}
