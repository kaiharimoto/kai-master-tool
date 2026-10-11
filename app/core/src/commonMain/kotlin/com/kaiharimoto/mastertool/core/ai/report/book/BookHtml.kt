package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.two
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/**
 * A book as one HTML page (Phase H, H.6; `docs/NEUE.md` §4k): the cover, the contents with a link to every chapter and
 * section, then each chapter and section in order, words as text a browser sets and searches, pictures as the very
 * [Drawing]s the app and the PDF paint, written out as SVG ([SvgInk]). One file — the fonts and the card art inside it —
 * that opens in any browser, offline, on any screen width: the pictures scale to the reading column.
 */
object BookHtml {
    /** The reading column, in CSS pixels: what the pictures are laid out to, and scale from. */
    const val COLUMN = 600f

    /**
     * [book] as HTML. [fonts] are the app's own (embedded, so the pictures' measured text sets as it was measured); [art] a
     * card's art by name as a `data:` address or a link, [hero] the cover's larger one.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun render(
        book: GuideBook,
        fonts: Map<Weight, ByteArray>,
        faces: Faces,
        art: (String) -> String?,
        updated: String = "",
        deck: List<String>? = null,
        kind: CardKind? = null,
        hero: (String) -> String? = art,
        notes: BookNotes = BookNotes(),
    ): String = buildString {
        val bookArt = BookArt.of(faces, book, deck, kind)
        val svg = SvgInk.Writer(faces.mono, art, hero)
        append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta name=\"generator\" content=\"Neue Master Tool\">")
        append("<title>").append(esc(book.title)).append(" · a guide</title><style>")
        fonts.forEach { (w, bytes) ->
            append("@font-face{font-family:").append(SvgInk.family(w)).append(";font-weight:").append(SvgInk.weight(w))
                .append(";src:url(data:font/ttf;base64,").append(Base64.encode(bytes)).append(") format(\"truetype\")}")
        }
        append(STYLE).append("</style></head><body><main>")

        // The cover: the hub's art, the name, the big idea; then the contents, every chapter and section a link.
        BookPdf.hero(book)?.let { name -> (hero(name) ?: art(name))?.let { append("<img class=\"hero\" alt=\"").append(attr(name)).append("\" src=\"").append(attr(it)).append("\">") } }
        append("<p class=\"micro\">A deck guide</p><h1>").append(esc(book.title)).append("</h1>")
        if (book.subtitle.isNotBlank()) append("<p class=\"sub\">").append(esc(book.subtitle)).append("</p>")
        if (book.bigIdea.isNotBlank()) append("<p class=\"big\">").append(esc(book.bigIdea)).append("</p>")
        append("<nav><p class=\"micro\">Contents</p><ol>")
        book.chapters.forEachIndexed { ci, c ->
            append("<li><a href=\"#").append(attr(c.id)).append("\"><span class=\"n\">").append(two(ci + 1)).append("</span>").append(esc(c.title)).append("</a>")
            if (!c.written) append(" <span class=\"planned\">planned</span>")
            if (c.sections.isNotEmpty()) {
                append("<ol>")
                c.sections.forEach { s -> append("<li><a href=\"#").append(attr(s.id)).append("\">").append(esc(s.title)).append("</a></li>") }
                append("</ol>")
            }
            append("</li>")
        }
        append("</ol></nav>")

        book.chapters.forEachIndexed { ci, c ->
            append("<section class=\"chapter\" id=\"").append(attr(c.id)).append("\"><p class=\"micro\">Chapter</p><p class=\"cn\">").append(two(ci + 1)).append("</p>")
            append("<h2>").append(esc(c.title)).append("</h2>")
            if (c.summary.isNotBlank()) append("<p class=\"summary\">").append(esc(c.summary)).append("</p>")
            if (!c.written) {
                append("<p class=\"planned\">Not written yet. Ai writes it in its next writing session.</p></section>")
                return@forEachIndexed
            }
            c.sections.forEachIndexed { si, s ->
                append("<article id=\"").append(attr(s.id)).append("\"><p class=\"kicker\"><span>").append(two(ci + 1)).append('.').append(si + 1)
                    .append("</span> — ").append(esc(c.title)).append("</p>")
                if (s.blocks.firstOrNull() !is Block.Lesson) append("<h3>").append(esc(s.title)).append("</h3>")
                s.blocks.forEach { b ->
                    // The person's marks (H.6): a highlighted block barred in the margin, their note boxed under it.
                    val mark = notes.of(b.id)
                    if (mark?.highlight == true) append("<div class=\"hl\">")
                    block(b, bookArt, svg)
                    if (mark?.highlight == true) append("</div>")
                    mark?.note?.takeIf { it.isNotBlank() }?.let { append("<aside class=\"note\"><p class=\"micro\">Your note</p><p>").append(esc(it)).append("</p></aside>") }
                }
                append("</article>")
            }
            append("</section>")
        }
        if (book.sources.isNotEmpty() || updated.isNotBlank()) {
            append("<footer><p class=\"micro\">Sources</p>")
            book.sources.forEach { append("<p class=\"source\">").append(esc(it)).append("</p>") }
            if (updated.isNotBlank()) append("<p class=\"sub\">Updated ").append(esc(updated)).append("</p>")
            append("</footer>")
        }
        append("<p class=\"made\">Made with Neue Master Tool</p></main></body></html>")
    }

    private fun StringBuilder.block(b: Block, art: BookArt, svg: SvgInk.Writer) {
        when (b) {
            is Block.Text -> {
                if (b.text.isBlank()) return
                append("<div class=\"text\">")
                if (b.label.isNotBlank()) append("<p class=\"label\">").append(esc(b.label)).append("</p>")
                b.text.split('\n').filter { it.isNotBlank() }.forEach { append("<p>").append(esc(it)).append("</p>") }
                append("</div>")
            }
            is Block.Line -> {
                art.drawings(b, COLUMN).forEach { append(svg.figure(it)) }
                if (b.frames && b.line.steps.isNotEmpty()) {
                    append("<p class=\"micro rule\">The board after each play</p><div class=\"frames\">")
                    art.frames(b.line, (COLUMN - 18f) / 2).forEach { append(svg.figure(it.drawing)) }
                    append("</div>")
                } else if (b.line.endBoard.isNotEmpty() || b.line.endSet.isNotEmpty()) {
                    val end = Block.Board(b.line.endBoard, b.line.endSet, "Ends on: " + (b.line.endBoard + b.line.endSet.map { "$it (set)" }).joinToString(", "))
                    append("<p class=\"micro\">Ends on</p>")
                    art.drawings(end, COLUMN).forEach { append(svg.figure(it)) }
                }
            }
            else -> art.drawings(b, COLUMN).forEach { append(svg.figure(it)) }
        }
    }

    internal fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal fun attr(s: String): String = esc(s).replace("\"", "&quot;")

    /** The book's look: Master UI's paper and ink, the app's own faces, one column that a phone reads too. */
    private val STYLE = """
        :root{color-scheme:light}
        body{margin:0;background:#fff;color:#111;font-family:${SvgInk.family(Weight.REGULAR)},system-ui,sans-serif;font-size:17px;line-height:1.55}
        main{max-width:${COLUMN.roundToInt()}px;margin:0 auto;padding:0 20px 48px}
        .hero{display:block;width:calc(100% + 40px);margin:0 -20px;height:330px;object-fit:cover;border-bottom:4px solid #111}
        h1{font-weight:700;font-size:44px;line-height:1.05;letter-spacing:-0.02em;margin:8px 0 12px}
        h2{font-weight:700;font-size:32px;line-height:1.1;letter-spacing:-0.01em;margin:4px 0 12px}
        h3{font-weight:700;font-size:23px;line-height:1.2;margin:4px 0 14px}
        .micro{font-family:${SvgInk.family(Weight.MONO)},monospace;font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:#737373;margin:28px 0 6px}
        .sub{font-family:${SvgInk.family(Weight.MONO)},monospace;font-size:12px;color:#737373}
        .big{font-weight:500;font-size:21px;line-height:1.35}
        nav{border-top:1.5px solid #111;margin-top:24px}
        nav ol{list-style:none;padding:0;margin:0}
        nav>ol>li{margin:12px 0;font-weight:500;font-size:18px}
        nav ol ol li{font-weight:400;font-size:15px;margin:4px 0 4px 34px}
        nav a{color:inherit;text-decoration:none}
        nav a:hover{text-decoration:underline}
        .n{display:inline-block;width:34px;font-family:${SvgInk.family(Weight.MONO)},monospace}
        .planned{color:#737373;font-size:14px}
        .chapter{border-top:4px solid #111;margin-top:64px;padding-top:8px}
        .cn{font-family:${SvgInk.family(Weight.MONO)},monospace;font-size:72px;line-height:1;letter-spacing:-0.03em;margin:0}
        .summary{font-weight:500;font-size:18px;color:#444}
        article{border-top:1px solid #ddd;margin-top:40px;padding-top:10px}
        .kicker{font-size:12px;color:#737373;margin:0 0 8px}
        .kicker span{font-family:${SvgInk.family(Weight.MONO)},monospace;color:#111}
        .text{margin:12px 0}
        .text p{margin:0 0 10px}
        .label{font-family:${SvgInk.family(Weight.MONO)},monospace;font-size:11px;letter-spacing:0.08em;text-transform:uppercase}
        figure{margin:14px 0}
        figure svg{display:block;width:100%;height:auto}
        .frames{display:grid;grid-template-columns:1fr 1fr;gap:14px 18px}
        .frames figure{margin:0}
        .rule{border-top:1.5px solid #111;padding-top:8px}
        footer{border-top:1px solid #ddd;margin-top:48px}
        .source{font-size:14px;color:#444}
        .made{font-size:12px;color:#737373;margin-top:32px}
        .hl{border-left:3px solid #111;padding-left:13px;margin-left:-16px}
        .note{border:1px solid #111;padding:2px 12px 10px;margin:8px 0 16px}
        .note p{margin:4px 0}
        .note .micro{margin-top:8px}
    """.trimIndent().replace("\n", "")
}

/** A [Drawing] written out as SVG (H.6): the same marks the app and the PDF paint, for a browser. */
object SvgInk {
    fun family(w: Weight): String = if (w == Weight.MONO) "BookMono" else "BookSans"

    fun weight(w: Weight): Int = when (w) {
        Weight.REGULAR, Weight.MONO -> 400
        Weight.MEDIUM -> 500
        Weight.BOLD -> 700
    }

    /** A grey level, 0 ink to 1 paper, as a colour. */
    fun gray(g: Float): String {
        val v = (g.coerceIn(0f, 1f) * 255).roundToInt()
        val h = v.toString(16).padStart(2, '0')
        return "#$h$h$h"
    }

    /**
     * Writes drawings for one document: [mono] measures a missing card's name as the PDF does, [art] and [hero] give each
     * card's picture by name; clip paths are numbered across the whole document so they never collide.
     */
    class Writer(private val mono: Face, private val art: (String) -> String?, private val hero: (String) -> String? = art) {
        private var clips = 0

        /** [d] as an SVG in a figure, scaling to the width it is given. */
        fun figure(d: Drawing): String = "<figure>" + svg(d) + "</figure>"

        fun svg(d: Drawing): String = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n(d.width)).append(' ').append(n(d.height))
                .append("\" width=\"").append(n(d.width)).append("\" height=\"").append(n(d.height)).append("\">")
            ops(d.ops)
            append("</svg>")
        }

        private fun StringBuilder.ops(list: List<Drawing.Op>) {
            list.forEach { o ->
                when (o) {
                    is Drawing.FillRect -> append("<rect x=\"").append(n(o.x)).append("\" y=\"").append(n(o.top)).append("\" width=\"").append(n(o.w))
                        .append("\" height=\"").append(n(o.h)).append("\" fill=\"").append(gray(o.gray)).append("\"/>")
                    is Drawing.StrokeRect -> append("<rect x=\"").append(n(o.x)).append("\" y=\"").append(n(o.top)).append("\" width=\"").append(n(o.w))
                        .append("\" height=\"").append(n(o.h)).append("\" fill=\"none\"").append(stroke(o.gray, o.line, o.dash, false)).append("/>")
                    is Drawing.Line -> append("<line x1=\"").append(n(o.x1)).append("\" y1=\"").append(n(o.top1)).append("\" x2=\"").append(n(o.x2))
                        .append("\" y2=\"").append(n(o.top2)).append('"').append(stroke(o.gray, o.width, o.dash, false)).append("/>")
                    is Drawing.Fill -> append("<path d=\"").append(path(o.shape)).append("\" fill=\"").append(gray(o.gray)).append("\"/>")
                    is Drawing.Stroke -> append("<path d=\"").append(path(o.shape)).append("\" fill=\"none\"").append(stroke(o.gray, o.width, o.dash, o.round)).append("/>")
                    is Drawing.Text -> text(o.weight, o.size, o.x, o.baseline, o.text, o.gray, o.tracking)
                    is Drawing.Card -> card(o)
                    is Drawing.Artwork -> artwork(o)
                    is Drawing.Clip -> {
                        val id = "c${++clips}"
                        append("<clipPath id=\"").append(id).append("\"><rect x=\"").append(n(o.x)).append("\" y=\"").append(n(o.top))
                            .append("\" width=\"").append(n(o.w)).append("\" height=\"").append(n(o.h)).append("\"/></clipPath><g clip-path=\"url(#").append(id).append(")\">")
                        ops(o.ops)
                        append("</g>")
                    }
                    is Drawing.Faded -> {
                        append("<g opacity=\"").append(n(o.alpha)).append("\">")
                        ops(o.ops)
                        append("</g>")
                    }
                    is Drawing.Tag -> Unit
                }
            }
        }

        private fun StringBuilder.text(w: Weight, size: Float, x: Float, baseline: Float, text: String, g: Float, tracking: Float) {
            append("<text x=\"").append(n(x)).append("\" y=\"").append(n(baseline)).append("\" font-family=\"").append(family(w))
                .append("\" font-weight=\"").append(weight(w)).append("\" font-size=\"").append(n(size)).append('"')
            if (tracking != 0f) append(" letter-spacing=\"").append(n(tracking)).append('"')
            append(" fill=\"").append(gray(g)).append("\" xml:space=\"preserve\">").append(BookHtml.esc(text)).append("</text>")
        }

        /** A card's art filling its box; with none, a quiet box with its name, as the PDF draws it. */
        private fun StringBuilder.card(o: Drawing.Card) {
            val picture = art(o.name)
            if (picture != null) {
                image(picture, o.x, o.top, o.w, o.h)
                return
            }
            append("<rect x=\"").append(n(o.x)).append("\" y=\"").append(n(o.top)).append("\" width=\"").append(n(o.w)).append("\" height=\"").append(n(o.h))
                .append("\" fill=\"").append(gray(0.95f)).append("\"/>")
            val size = if (o.w > 40) 6.5f else 5f
            Pen.wrapWith(o.name, { s -> mono.width(s, size) }, o.w - 6).take(5).forEachIndexed { i, l ->
                text(Weight.MONO, size, o.x + 3, o.top + 9 + i * (size + 2), l, 0.55f, 0f)
            }
        }

        /** A card's art alone, cropped to cover its box, as the PDF crops it. */
        private fun StringBuilder.artwork(o: Drawing.Artwork) {
            val picture = hero(o.name) ?: art(o.name)
            if (picture == null) {
                append("<rect x=\"").append(n(o.x)).append("\" y=\"").append(n(o.top)).append("\" width=\"").append(n(o.w)).append("\" height=\"").append(n(o.h))
                    .append("\" fill=\"").append(gray(0.95f)).append("\"/>")
                return
            }
            val box = Pen.coverArt(o.x, o.top, o.w, o.h)
            val id = "c${++clips}"
            append("<clipPath id=\"").append(id).append("\"><rect x=\"").append(n(o.x)).append("\" y=\"").append(n(o.top)).append("\" width=\"").append(n(o.w))
                .append("\" height=\"").append(n(o.h)).append("\"/></clipPath><g clip-path=\"url(#").append(id).append(")\">")
            image(picture, box[0], box[1], box[2], box[3])
            append("</g>")
        }

        private fun StringBuilder.image(href: String, x: Float, top: Float, w: Float, h: Float) {
            append("<image href=\"").append(BookHtml.attr(href)).append("\" x=\"").append(n(x)).append("\" y=\"").append(n(top))
                .append("\" width=\"").append(n(w)).append("\" height=\"").append(n(h)).append("\" preserveAspectRatio=\"none\"/>")
        }

        private fun stroke(g: Float, width: Float, dash: Float?, round: Boolean): String = buildString {
            append(" stroke=\"").append(gray(g)).append("\" stroke-width=\"").append(n(width)).append('"')
            if (dash != null) append(" stroke-dasharray=\"").append(n(dash)).append(' ').append(n(dash)).append('"')
            if (round) append(" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
        }

        private fun path(s: Shape): String = s.segs.joinToString(" ") { g ->
            when (g) {
                is Shape.Move -> "M${n(g.x)} ${n(g.y)}"
                is Shape.To -> "L${n(g.x)} ${n(g.y)}"
                is Shape.Curve -> "C${n(g.x1)} ${n(g.y1)} ${n(g.x2)} ${n(g.y2)} ${n(g.x)} ${n(g.y)}"
                Shape.Close -> "Z"
            }
        }
    }

    /** A number short: two places at most, no trailing zeros, never an exponent. */
    fun n(x: Float): String {
        val r = (x * 100).roundToInt()
        val neg = r < 0
        val a = if (neg) -r else r
        val whole = a / 100
        val frac = a % 100
        val tail = when {
            frac == 0 -> ""
            frac % 10 == 0 -> "." + (frac / 10)
            else -> "." + frac.toString().padStart(2, '0')
        }
        return (if (neg && a != 0) "-" else "") + whole + tail
    }
}
