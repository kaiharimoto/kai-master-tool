package com.kaiharimoto.mastertool.core.pdf

import com.kaiharimoto.mastertool.core.ydk.Zlib

/** A font registered with a [PdfDocument]: set text in it with [PdfPage.text]. */
class PdfFont internal constructor(internal val key: String, val metrics: TrueType) {
    /** Glyph to the code point it was used for: the text a reader copies out, and the widths written. */
    internal val used = LinkedHashMap<Int, Int>()

    fun width(text: String, size: Float): Float = metrics.width(text, size)

    /** [text]'s width with [tracking] points added after every character, as [PdfPage.text] sets it. */
    fun width(text: String, size: Float, tracking: Float): Float =
        metrics.width(text, size) + if (tracking == 0f) 0f else tracking * TrueType.codePoints(text).size

    /** The font's ascent at [size], in points. */
    fun ascent(size: Float): Float = metrics.ascent * size / metrics.unitsPerEm

    fun capHeight(size: Float): Float = metrics.capHeight * size / metrics.unitsPerEm
}

/**
 * A picture for a PDF: 8-bit RGB, row by row from the top, three bytes a pixel — or, when [jpeg]
 * is given, a baseline JPEG passed through as it is (`/DCTDecode`, 1.0.67: a guide shared in a chat
 * must be small, and card art deflates poorly); [rgb] is then unused.
 */
class PdfImage(val width: Int, val height: Int, val rgb: ByteArray, val jpeg: ByteArray? = null) {
    init {
        if (jpeg == null) require(rgb.size == width * height * 3) { "Expected ${width * height * 3} bytes for $width × $height, got ${rgb.size}" }
    }

    companion object {
        /** A JPEG of [width] × [height] pixels, RGB. */
        fun jpeg(width: Int, height: Int, bytes: ByteArray) = PdfImage(width, height, ByteArray(0), bytes)
    }
}

/** How a stroke's ends are drawn. */
enum class LineCap(internal val code: Int) { BUTT(0), ROUND(1), SQUARE(2) }

/**
 * A shape for [PdfPage.fill] and [PdfPage.stroke] (1.0.67: the reader's guide draws its ideas —
 * arrows, curves, rings, the field's zones): points from the page's top left, like every other call.
 */
class PdfPath internal constructor(private val height: Float) {
    internal val ops = StringBuilder()

    private fun n(v: Float): String = PdfPage.num(v)

    fun moveTo(x: Float, top: Float) = apply { ops.append("${n(x)} ${n(height - top)} m ") }

    fun lineTo(x: Float, top: Float) = apply { ops.append("${n(x)} ${n(height - top)} l ") }

    /** A cubic Bézier to ([x], [top]) by the two control points. */
    fun curveTo(x1: Float, top1: Float, x2: Float, top2: Float, x: Float, top: Float) = apply {
        ops.append("${n(x1)} ${n(height - top1)} ${n(x2)} ${n(height - top2)} ${n(x)} ${n(height - top)} c ")
    }

    fun close() = apply { ops.append("h ") }

    /** A circle round ([cx], [cy]) of radius [r], from four Béziers. */
    fun circle(cx: Float, cy: Float, r: Float) = apply {
        val k = 0.5523f * r
        moveTo(cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        close()
    }

    /** A closed polygon through [points], each (x, top). */
    fun polygon(points: List<Pair<Float, Float>>) = apply {
        points.forEachIndexed { i, (x, t) -> if (i == 0) moveTo(x, t) else lineTo(x, t) }
        close()
    }
}

/**
 * A page, drawn in points from its top left (the PDF's own origin is its
 * bottom left; the page turns the one into the other). Grey levels only, 0 ink
 * to 1 paper: the family's two fills and the steps between, as it prints them.
 */
class PdfPage internal constructor(val width: Float, val height: Float, private val doc: PdfDocument) {
    internal val ops = StringBuilder()
    internal val fonts = LinkedHashSet<PdfFont>()
    internal val images = LinkedHashSet<PdfImage>()
    internal val alphas = LinkedHashSet<Int>()
    internal val links = mutableListOf<Link>()

    /** A tappable box on this page that opens [target] at [targetTop] points from its top. */
    internal class Link(val x: Float, val top: Float, val w: Float, val h: Float, val target: PdfPage, val targetTop: Float)

    /** The box ([x], [top], [w], [h]) taps through to [target], [targetTop] points down it (1.0.67: a guide's contents). */
    fun link(x: Float, top: Float, w: Float, h: Float, target: PdfPage, targetTop: Float = 0f) {
        links += Link(x, top, w, h, target, targetTop)
    }

    private fun n(v: Float): String = num(v)

    /** The character spacing in force, as the content stream has set it. */
    private var tc = 0f

    private fun y(top: Float) = height - top

    fun fillRect(x: Float, top: Float, w: Float, h: Float, gray: Float) {
        ops.append("${n(gray)} g ${n(x)} ${n(y(top + h))} ${n(w)} ${n(h)} re f\n")
    }

    fun strokeRect(x: Float, top: Float, w: Float, h: Float, gray: Float = 0f, line: Float = 1f, dash: Float? = null) {
        dashed(dash)
        ops.append("${n(gray)} G ${n(line)} w ${n(x + line / 2)} ${n(y(top + h - line / 2))} ${n(w - line)} ${n(h - line)} re S\n")
        if (dash != null) ops.append("[] 0 d\n")
    }

    fun line(x1: Float, top1: Float, x2: Float, top2: Float, gray: Float = 0f, width: Float = 1f, dash: Float? = null) {
        dashed(dash)
        ops.append("${n(gray)} G ${n(width)} w ${n(x1)} ${n(y(top1))} m ${n(x2)} ${n(y(top2))} l S\n")
        if (dash != null) ops.append("[] 0 d\n")
    }

    private fun dashed(dash: Float?) {
        if (dash != null) ops.append("[${n(dash)} ${n(dash)}] 0 d\n")
    }

    /** A new shape on this page: give it to [fill], [stroke] or [fillStroke]. */
    fun path(build: PdfPath.() -> Unit): PdfPath = PdfPath(height).apply(build)

    fun fill(path: PdfPath, gray: Float) {
        ops.append("${n(gray)} g ").append(path.ops).append("f\n")
    }

    fun stroke(path: PdfPath, gray: Float = 0f, width: Float = 1f, dash: Float? = null, cap: LineCap = LineCap.BUTT, round: Boolean = false) {
        dashed(dash)
        ops.append("${n(gray)} G ${n(width)} w ${cap.code} J ${if (round) 1 else 0} j ").append(path.ops).append("S\n")
        if (dash != null) ops.append("[] 0 d\n")
        if (cap != LineCap.BUTT || round) ops.append("0 J 0 j\n")
    }

    fun fillStroke(path: PdfPath, fill: Float, stroke: Float, width: Float = 1f) {
        ops.append("${n(fill)} g ${n(stroke)} G ${n(width)} w ").append(path.ops).append("B\n")
    }

    /** What [draw] puts on the page, cut to the box: nothing outside it shows. */
    fun clip(x: Float, top: Float, w: Float, h: Float, draw: () -> Unit) {
        ops.append("q ${n(x)} ${n(y(top + h))} ${n(w)} ${n(h)} re W n\n")
        val saved = tc
        draw()
        ops.append("Q\n")
        tc = saved
    }

    /**
     * [text] set in [font] at [size], its baseline [baseline] points from the top; [tracking] adds
     * that many points after every character (micro caps open up, display type closes in).
     */
    fun text(font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float = 0f, tracking: Float = 0f) {
        if (text.isEmpty()) return
        fonts += font
        val hex = StringBuilder()
        TrueType.codePoints(text).forEach { cp ->
            val g = font.metrics.glyph(cp)
            font.used.getOrPut(g) { cp }
            hex.append(g.toString(16).padStart(4, '0'))
        }
        // Character spacing is part of the graphics state, not the text object: it outlives ET, so it is
        // set whenever it changes (and q … Q in faded and clip restore it, which they track too).
        val spacing = if (tracking != tc) "${n(tracking)} Tc " else ""
        tc = tracking
        ops.append("BT /${font.key} ${n(size)} Tf $spacing${n(gray)} g ${n(x)} ${n(y(baseline))} Td <$hex> Tj ET\n")
    }

    /** What [draw] puts on the page, see-through: [alpha] 0 to 1, fills, strokes and pictures alike. */
    fun faded(alpha: Float, draw: () -> Unit) {
        val percent = (alpha * 100).toInt().coerceIn(0, 100)
        alphas += percent
        ops.append("q /GA$percent gs\n")
        val saved = tc
        draw()
        ops.append("Q\n")
        tc = saved
    }

    /** [image] drawn into the box at ([x], [top]), [w] × [h] points. */
    fun image(image: PdfImage, x: Float, top: Float, w: Float, h: Float) {
        val key = doc.register(image)
        images += image
        ops.append("q ${n(w)} 0 0 ${n(h)} ${n(x)} ${n(y(top + h))} cm /$key Do Q\n")
    }

    internal companion object {
        fun num(v: Float): String {
            val r = kotlin.math.round(v * 100f) / 100f
            return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
        }
    }
}

/**
 * A PDF, written from nothing (1.0.36, the siding guide): Skia's PDF backend is
 * not in Skiko and Android's is a second copy of the drawing, so the pages are
 * drawn here once, in core, and tested like the rest of it.
 *
 * Text is real text — each font embedded whole as a TrueType CID font with an
 * identity encoding, its widths, and a `ToUnicode` map — so a guide can be
 * searched and copied from, in any language the font covers. Pictures are
 * RGB, deflated. Streams are compressed through [zlib] when one is given.
 */
class PdfDocument(private val zlib: Zlib? = null, val title: String = "", val author: String = "") {
    private val pages = ArrayList<PdfPage>()
    private val fonts = ArrayList<PdfFont>()
    private val images = ArrayList<PdfImage>()

    /** A bookmark: what a reader's sidebar lists, opening [page] at [top]; [children] under it. */
    class Bookmark(val title: String, val page: PdfPage, val top: Float = 0f, val children: List<Bookmark> = emptyList())

    /** The bookmarks, in order (1.0.67): a phone's PDF viewer lists them as the document's outline. */
    val bookmarks = mutableListOf<Bookmark>()

    fun font(metrics: TrueType): PdfFont = PdfFont("F${fonts.size + 1}", metrics).also { fonts += it }

    /** A new page, A4 unless told otherwise. */
    fun page(width: Float = A4_WIDTH, height: Float = A4_HEIGHT): PdfPage = PdfPage(width, height, this).also { pages += it }

    val pageCount: Int get() = pages.size

    /** The picture's name in this document. Kept here, not on the picture, so one picture can go into many documents. */
    private val keys = HashMap<PdfImage, String>()

    internal fun register(image: PdfImage): String = keys.getOrPut(image) {
        images += image
        "Im${images.size}"
    }

    fun write(): ByteArray {
        val out = Out()
        out.raw("%PDF-1.7\n%âãÏÓ\n".encodeToByteArray())
        // Numbers first, so objects can name each other before they are written.
        var next = 1
        val catalog = next++
        val pagesId = next++
        val info = next++
        val pageIds = pages.map { next++ to next++ }
        val fontIds = fonts.associateWith { IntArray(5) { next++ } }
        val imageIds = images.associateWith { next++ }
        val linkIds = pages.associateWith { p -> p.links.map { next++ } }
        // Every bookmark, depth first, numbered before anything is written.
        val marks = ArrayList<Pair<Bookmark, Int>>()
        fun number(list: List<Bookmark>) {
            list.forEach { b ->
                marks += b to next++
                number(b.children)
            }
        }
        number(bookmarks)
        val markIds = marks.toMap()
        val outlines = if (bookmarks.isNotEmpty()) next++ else 0

        val outlineRef = if (outlines > 0) " /Outlines $outlines 0 R" else ""
        out.obj(catalog, "<< /Type /Catalog /Pages $pagesId 0 R$outlineRef >>")
        out.obj(pagesId, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "${it.first} 0 R" }}] /Count ${pages.size} >>")
        out.obj(info, "<< /Title ${pdfString(title)} /Author ${pdfString(author)} /Producer ${pdfString("Neue Master Tool")} >>")
        pages.forEachIndexed { i, page ->
            val (pageId, contentId) = pageIds[i]
            val fontRes = page.fonts.joinToString(" ") { "/${it.key} ${fontIds.getValue(it)[0]} 0 R" }
            val imageRes = page.images.joinToString(" ") { "/${keys.getValue(it)} ${imageIds.getValue(it)} 0 R" }
            val alphaRes = page.alphas.joinToString(" ") { "/GA$it << /ca ${it / 100f} /CA ${it / 100f} >>" }
            val annots = linkIds.getValue(page).let { ids -> if (ids.isEmpty()) "" else " /Annots [${ids.joinToString(" ") { "$it 0 R" }}]" }
            out.obj(
                pageId,
                "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${page.width} ${page.height}] " +
                    "/Resources << /Font << $fontRes >> /XObject << $imageRes >> /ExtGState << $alphaRes >> >> /Contents $contentId 0 R$annots >>",
            )
            out.stream(contentId, "", page.ops.toString().encodeToByteArray())
        }
        fonts.forEach { font ->
            val (type0, cid, descriptor, file, toUnicode) = fontIds.getValue(font).toList()
            val m = font.metrics
            val scale = 1000f / m.unitsPerEm
            val name = "NMT${font.key}+${font.key}"
            out.obj(type0, "<< /Type /Font /Subtype /Type0 /BaseFont /$name /Encoding /Identity-H /DescendantFonts [$cid 0 R] /ToUnicode $toUnicode 0 R >>")
            val widths = font.used.keys.sorted().joinToString(" ") { g -> "$g [${m.advance1000(g)}]" }
            out.obj(
                cid,
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$name /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/FontDescriptor $descriptor 0 R /CIDToGIDMap /Identity /DW 500 /W [$widths] >>",
            )
            val flags = (if (m.fixedPitch) 1 else 0) or 32
            out.obj(
                descriptor,
                "<< /Type /FontDescriptor /FontName /$name /Flags $flags " +
                    "/FontBBox [${m.bbox.joinToString(" ") { (it * scale).toInt().toString() }}] /ItalicAngle ${m.italicAngle} " +
                    "/Ascent ${(m.ascent * scale).toInt()} /Descent ${(m.descent * scale).toInt()} /CapHeight ${(m.capHeight * scale).toInt()} " +
                    "/StemV 80 /FontFile2 $file 0 R >>",
            )
            out.stream(file, "/Length1 ${m.bytes.size}", m.bytes)
            out.stream(toUnicode, "", toUnicodeMap(font).encodeToByteArray())
        }
        images.forEach { image ->
            val dict = "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} /ColorSpace /DeviceRGB /BitsPerComponent 8"
            val jpeg = image.jpeg
            if (jpeg != null) out.stream(imageIds.getValue(image), "$dict /Filter /DCTDecode", jpeg, deflate = false) else out.stream(imageIds.getValue(image), dict, image.rgb)
        }
        fun dest(page: PdfPage, top: Float): String {
            val id = pageIds[pages.indexOf(page)].first
            return "[$id 0 R /XYZ 0 ${PdfPage.num(page.height - top)} null]"
        }
        pages.forEach { page ->
            page.links.zip(linkIds.getValue(page)).forEach { (l, id) ->
                val y1 = page.height - l.top - l.h
                out.obj(
                    id,
                    "<< /Type /Annot /Subtype /Link /Rect [${PdfPage.num(l.x)} ${PdfPage.num(y1)} ${PdfPage.num(l.x + l.w)} ${PdfPage.num(y1 + l.h)}] " +
                        "/Border [0 0 0] /Dest ${dest(l.target, l.targetTop)} >>",
                )
            }
        }
        if (outlines > 0) {
            fun count(list: List<Bookmark>): Int {
                var n = 0
                list.forEach { n += 1 + count(it.children) }
                return n
            }
            fun write(list: List<Bookmark>, parent: Int) {
                list.forEachIndexed { i, b ->
                    val id = markIds.getValue(b)
                    val links = buildString {
                        append("/Parent $parent 0 R")
                        if (i > 0) append(" /Prev ${markIds.getValue(list[i - 1])} 0 R")
                        if (i < list.lastIndex) append(" /Next ${markIds.getValue(list[i + 1])} 0 R")
                        if (b.children.isNotEmpty()) {
                            append(" /First ${markIds.getValue(b.children.first())} 0 R /Last ${markIds.getValue(b.children.last())} 0 R /Count -${count(b.children)}")
                        }
                    }
                    out.obj(id, "<< /Title ${pdfString(b.title)} $links /Dest ${dest(b.page, b.top)} >>")
                    write(b.children, id)
                }
            }
            out.obj(outlines, "<< /Type /Outlines /First ${markIds.getValue(bookmarks.first())} 0 R /Last ${markIds.getValue(bookmarks.last())} 0 R /Count ${count(bookmarks)} >>")
            write(bookmarks, outlines)
        }
        val xref = out.size
        val total = next
        out.raw(buildString {
            append("xref\n0 $total\n0000000000 65535 f \n")
            for (id in 1 until total) append(out.offset(id).toString().padStart(10, '0')).append(" 00000 n \n")
            append("trailer\n<< /Size $total /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }.encodeToByteArray())
        return out.bytes()
    }

    private fun toUnicodeMap(font: PdfFont): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        font.used.entries.sortedBy { it.key }.chunked(100).forEach { chunk ->
            append("${chunk.size} beginbfchar\n")
            chunk.forEach { (g, cp) -> append("<${g.toString(16).padStart(4, '0')}> <${utf16Hex(cp)}>\n") }
            append("endbfchar\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    private fun utf16Hex(cp: Int): String = if (cp < 0x10000) {
        cp.toString(16).padStart(4, '0')
    } else {
        val v = cp - 0x10000
        (0xD800 + (v shr 10)).toString(16) + (0xDC00 + (v and 0x3FF)).toString(16)
    }

    /** A text string as PDF writes one: UTF-16 in hex, so any name survives. */
    private fun pdfString(s: String): String =
        "<FEFF" + s.map { it.code.toString(16).padStart(4, '0') }.joinToString("") + ">"

    private inner class Out {
        private val chunks = ArrayList<ByteArray>()
        var size = 0
            private set
        private val offsets = HashMap<Int, Int>()

        fun raw(b: ByteArray) {
            chunks += b
            size += b.size
        }

        fun offset(id: Int): Int = offsets[id] ?: 0

        fun obj(id: Int, body: String) {
            offsets[id] = size
            raw("$id 0 obj\n$body\nendobj\n".encodeToByteArray())
        }

        fun stream(id: Int, dict: String, data: ByteArray, deflate: Boolean = true) {
            offsets[id] = size
            val packed = if (deflate) zlib?.deflate(data) else null
            val body = packed ?: data
            val filter = if (packed != null) " /Filter /FlateDecode" else ""
            raw("$id 0 obj\n<< $dict /Length ${body.size}$filter >>\nstream\n".encodeToByteArray())
            raw(body)
            raw("\nendstream\nendobj\n".encodeToByteArray())
        }

        fun bytes(): ByteArray {
            val all = ByteArray(size)
            var at = 0
            chunks.forEach { c ->
                c.copyInto(all, at)
                at += c.size
            }
            return all
        }
    }

    companion object {
        const val A4_WIDTH = 595.28f
        const val A4_HEIGHT = 841.89f
    }
}
