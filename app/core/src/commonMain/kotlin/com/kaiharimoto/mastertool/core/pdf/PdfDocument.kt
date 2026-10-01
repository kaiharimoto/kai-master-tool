package com.kaiharimoto.mastertool.core.pdf

import com.kaiharimoto.mastertool.core.ydk.Zlib

/** A font registered with a [PdfDocument]: set text in it with [PdfPage.text]. */
class PdfFont internal constructor(internal val key: String, val metrics: TrueType) {
    /** Glyph to the code point it was used for: the text a reader copies out, and the widths written. */
    internal val used = LinkedHashMap<Int, Int>()

    fun width(text: String, size: Float): Float = metrics.width(text, size)

    /** The font's ascent at [size], in points. */
    fun ascent(size: Float): Float = metrics.ascent * size / metrics.unitsPerEm

    fun capHeight(size: Float): Float = metrics.capHeight * size / metrics.unitsPerEm
}

/** A picture for a PDF: 8-bit RGB, row by row from the top, three bytes a pixel. */
class PdfImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(rgb.size == width * height * 3) { "Expected ${width * height * 3} bytes for $width × $height, got ${rgb.size}" }
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

    private fun n(v: Float): String {
        val r = kotlin.math.round(v * 100f) / 100f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

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

    /** [text] set in [font] at [size], its baseline [baseline] points from the top. */
    fun text(font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float = 0f) {
        if (text.isEmpty()) return
        fonts += font
        val hex = StringBuilder()
        TrueType.codePoints(text).forEach { cp ->
            val g = font.metrics.glyph(cp)
            font.used.getOrPut(g) { cp }
            hex.append(g.toString(16).padStart(4, '0'))
        }
        ops.append("BT /${font.key} ${n(size)} Tf ${n(gray)} g ${n(x)} ${n(y(baseline))} Td <$hex> Tj ET\n")
    }

    /** What [draw] puts on the page, see-through: [alpha] 0 to 1, fills, strokes and pictures alike. */
    fun faded(alpha: Float, draw: () -> Unit) {
        val percent = (alpha * 100).toInt().coerceIn(0, 100)
        alphas += percent
        ops.append("q /GA$percent gs\n")
        draw()
        ops.append("Q\n")
    }

    /** [image] drawn into the box at ([x], [top]), [w] × [h] points. */
    fun image(image: PdfImage, x: Float, top: Float, w: Float, h: Float) {
        val key = doc.register(image)
        images += image
        ops.append("q ${n(w)} 0 0 ${n(h)} ${n(x)} ${n(y(top + h))} cm /$key Do Q\n")
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

        out.obj(catalog, "<< /Type /Catalog /Pages $pagesId 0 R >>")
        out.obj(pagesId, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "${it.first} 0 R" }}] /Count ${pages.size} >>")
        out.obj(info, "<< /Title ${pdfString(title)} /Author ${pdfString(author)} /Producer ${pdfString("Neue Master Tool")} >>")
        pages.forEachIndexed { i, page ->
            val (pageId, contentId) = pageIds[i]
            val fontRes = page.fonts.joinToString(" ") { "/${it.key} ${fontIds.getValue(it)[0]} 0 R" }
            val imageRes = page.images.joinToString(" ") { "/${keys.getValue(it)} ${imageIds.getValue(it)} 0 R" }
            val alphaRes = page.alphas.joinToString(" ") { "/GA$it << /ca ${it / 100f} /CA ${it / 100f} >>" }
            out.obj(
                pageId,
                "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${page.width} ${page.height}] " +
                    "/Resources << /Font << $fontRes >> /XObject << $imageRes >> /ExtGState << $alphaRes >> >> /Contents $contentId 0 R >>",
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
            out.stream(
                imageIds.getValue(image),
                "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} /ColorSpace /DeviceRGB /BitsPerComponent 8",
                image.rgb,
            )
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

        fun stream(id: Int, dict: String, data: ByteArray) {
            offsets[id] = size
            val packed = zlib?.deflate(data)
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
