package com.kaiharimoto.mastertool.core.pdf

/**
 * What a PDF needs to know about a TrueType font to set text in it: which glyph
 * draws each character, how wide each glyph is, and the numbers for the font's
 * descriptor. Read from the font file itself — `cmap` (formats 4 and 12),
 * `hmtx`, `hhea`, `head`, `maxp`, `OS/2`, `post` — so a PDF made from it shows
 * the same letters the app draws, in the same widths. No kerning: the siding
 * guide is set in short lines where it does not show.
 */
class TrueType(val bytes: ByteArray) {
    val unitsPerEm: Int
    val ascent: Int
    val descent: Int
    val capHeight: Int
    val bbox: IntArray
    val italicAngle: Float
    val fixedPitch: Boolean
    val glyphCount: Int
    private val advances: IntArray
    private val cmap: Map<Int, Int>

    init {
        val tables = HashMap<String, Int>()
        val count = u16(4)
        for (i in 0 until count) {
            val at = 12 + 16 * i
            tables[CharArray(4) { k -> (bytes[at + k].toInt() and 0xFF).toChar() }.concatToString()] = u32(at + 8).toInt()
        }
        fun table(tag: String) = tables[tag] ?: throw IllegalArgumentException("Not a TrueType font: no $tag table")
        val head = table("head")
        unitsPerEm = u16(head + 18)
        bbox = intArrayOf(s16(head + 36), s16(head + 38), s16(head + 40), s16(head + 42))
        val hhea = table("hhea")
        ascent = s16(hhea + 4)
        descent = s16(hhea + 6)
        val metrics = u16(hhea + 34)
        glyphCount = u16(table("maxp") + 4)
        val hmtx = table("hmtx")
        advances = IntArray(glyphCount) { g -> u16(hmtx + 4 * minOf(g, metrics - 1)) }
        capHeight = tables["OS/2"]?.let { os2 -> if (u16(os2) >= 2) s16(os2 + 88) else null } ?: (ascent * 7 / 10)
        val post = tables["post"]
        italicAngle = post?.let { s16(it + 4) + u16(it + 6) / 65536f } ?: 0f
        fixedPitch = post?.let { u32(it + 12) != 0L } ?: false
        cmap = readCmap(table("cmap"))
    }

    /** The glyph that draws [codePoint], or 0 (the font's "missing" box). */
    fun glyph(codePoint: Int): Int = cmap[codePoint] ?: 0

    /** A glyph's advance, in the font's units. */
    fun advance(glyph: Int): Int = advances.getOrElse(glyph) { advances.lastOrNull() ?: 0 }

    /** A glyph's advance in thousandths of the size, as PDF widths are written. */
    fun advance1000(glyph: Int): Int = advance(glyph) * 1000 / unitsPerEm

    /** How wide [text] is set at [size] points. */
    fun width(text: String, size: Float): Float {
        var units = 0L
        codePoints(text).forEach { units += advance(glyph(it)) }
        return units * size / unitsPerEm
    }

    private fun readCmap(at: Int): Map<Int, Int> {
        val count = u16(at + 2)
        var best: Int? = null
        var bestFormat = 0
        for (i in 0 until count) {
            val platform = u16(at + 4 + 8 * i)
            val encoding = u16(at + 6 + 8 * i)
            val sub = at + u32(at + 8 + 8 * i).toInt()
            val format = u16(sub)
            val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
            if (!unicode) continue
            // Format 12 reaches past the BMP; take it over format 4 where both are there.
            if (format == 12 && bestFormat != 12 || format == 4 && bestFormat == 0) {
                best = sub
                bestFormat = format
            }
        }
        val sub = best ?: return emptyMap()
        val map = HashMap<Int, Int>()
        if (bestFormat == 4) {
            val segments = u16(sub + 6) / 2
            val ends = sub + 14
            val starts = ends + 2 * segments + 2
            val deltas = starts + 2 * segments
            val offsets = deltas + 2 * segments
            for (s in 0 until segments) {
                val end = u16(ends + 2 * s)
                val start = u16(starts + 2 * s)
                val delta = s16(deltas + 2 * s)
                val offset = u16(offsets + 2 * s)
                if (start == 0xFFFF) continue
                for (c in start..end) {
                    val g = if (offset == 0) {
                        (c + delta) and 0xFFFF
                    } else {
                        val at2 = offsets + 2 * s + offset + 2 * (c - start)
                        val raw = u16(at2)
                        if (raw == 0) 0 else (raw + delta) and 0xFFFF
                    }
                    if (g != 0) map[c] = g
                }
            }
        } else {
            val groups = u32(sub + 12).toInt()
            for (i in 0 until groups) {
                val g = sub + 16 + 12 * i
                val start = u32(g).toInt()
                val end = u32(g + 4).toInt()
                val first = u32(g + 8).toInt()
                for (c in start..end) map[c] = first + (c - start)
            }
        }
        return map
    }

    private fun u8(at: Int) = bytes[at].toInt() and 0xFF
    private fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun s16(at: Int) = u16(at).toShort().toInt()
    private fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

    companion object {
        /** [text]'s code points, pairing surrogates. */
        fun codePoints(text: String): List<Int> {
            val out = ArrayList<Int>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    out += ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
                    i += 2
                } else {
                    out += c.code
                    i++
                }
            }
            return out
        }
    }
}
