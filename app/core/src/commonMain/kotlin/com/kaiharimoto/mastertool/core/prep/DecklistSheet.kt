package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.pdf.PdfDocument
import com.kaiharimoto.mastertool.core.pdf.PdfFont
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.Zlib

/**
 * A decklist to hand in: our own sheet, not a copy of Konami's form, carrying
 * every field a KDE-US event asks for — first and middle name, last name, CARD
 * GAME ID, the event's name and date, country of residency — and the deck as
 * Monster, Spell and Trap columns of count and full English name with a total
 * each, then the Side and Extra Decks, and the Main Deck's total.
 *
 * The policy allows it: a Deck List may be "printed from a" deck tool as long as
 * it is legible and complete (§IV.D), and there are no printers at the event,
 * so it is printed before. It is set on US Letter, since KDE-US is the body the
 * fields are Konami's for, in paper and ink like the siding guide, with the same
 * fonts (`GuideFonts`) and the same PDF writer. Names are never abbreviated —
 * shorthand on a decklist is a Deck Error waiting to happen — so a long one is
 * set smaller until it fits. A section with more names than rows goes on to a
 * second page, which repeats the heading and the fields, as the policy asks of
 * a list that runs over ("continue writing on the back").
 */
object DecklistSheet {

    data class Line(val count: Int, val name: String)

    data class Content(
        val playerName: String,
        val cardGameId: String,
        val country: String,
        val eventName: String,
        val eventDate: String,
        val deckName: String,
        val monsters: List<Line>,
        val spells: List<Line>,
        val traps: List<Line>,
        val side: List<Line>,
        val extra: List<Line>,
    ) {
        val mainTotal: Int get() = monsters.total + spells.total + traps.total
    }

    /** First and middle names, and the last name: [full] split at its last space. */
    fun splitName(full: String): Pair<String, String> {
        val name = full.trim().replace(Regex("\\s+"), " ")
        val at = name.lastIndexOf(' ')
        return if (at < 0) name to "" else name.substring(0, at) to name.substring(at + 1)
    }

    // US Letter, in points.
    const val WIDTH = 612f
    const val HEIGHT = 792f
    private const val ML = 36f
    private const val MT = 36f
    private const val CW = WIDTH - 2 * ML
    private const val GUTTER = 12f
    private const val ROW = 13f

    /** Rows per column: 20 for each Main Deck column, 15 for Side and Extra — as many as either may hold. */
    const val MAIN_ROWS = 20
    const val SIDE_ROWS = 15

    // Greys, 0 ink to 1 paper.
    private const val INK = 0f
    private const val INK70 = 0.3f
    private const val INK45 = 0.55f
    private const val INK12 = 0.88f

    private const val NAME_SIZE = 8.5f
    private const val NAME_MIN = 4.5f

    fun write(content: Content, fonts: GuideFonts, zlib: Zlib?): ByteArray {
        val doc = PdfDocument(zlib, title = "Decklist · ${content.deckName}", author = "Neue Master Tool")
        val f = Fonts(doc.font(fonts.regular), doc.font(fonts.bold), doc.font(fonts.mono))
        val pages = maxOf(
            1,
            pagesFor(content.monsters, MAIN_ROWS), pagesFor(content.spells, MAIN_ROWS), pagesFor(content.traps, MAIN_ROWS),
            pagesFor(content.side, SIDE_ROWS), pagesFor(content.extra, SIDE_ROWS),
        )
        for (p in 0 until pages) {
            val page = doc.page(WIDTH, HEIGHT)
            drawPage(page, f, content, p, pages)
        }
        return doc.write()
    }

    /**
     * The list as text, for NEURON and online registration that take a pasted
     * list: each section's name with its total, then a line per card.
     * Empty sections are left out.
     */
    fun plainText(content: Content): String = listOf(
        "Monsters" to content.monsters,
        "Spells" to content.spells,
        "Traps" to content.traps,
        "Extra Deck" to content.extra,
        "Side Deck" to content.side,
    ).filter { it.second.isNotEmpty() }.joinToString("\n\n") { (title, lines) ->
        "$title (${lines.total})\n" + lines.joinToString("\n") { "${it.count} ${it.name}" }
    }

    private val List<Line>.total: Int get() = sumOf { it.count }

    private fun pagesFor(lines: List<Line>, rows: Int) = (lines.size + rows - 1) / rows

    private class Fonts(val regular: PdfFont, val bold: PdfFont, val mono: PdfFont)

    private fun drawPage(page: PdfPage, f: Fonts, c: Content, index: Int, of: Int) {
        // Heading.
        val label = if (index == 0) "DECKLIST" else "DECKLIST · CONTINUED"
        page.text(f.bold, 7.5f, ML, MT + 8f, label)
        right(page, f.mono, 7.5f, WIDTH - ML, MT + 8f, "page ${index + 1} of $of", INK70)
        page.text(f.bold, 18f, ML, MT + 30f, fit(c.deckName.ifBlank { "Untitled deck" }, f.bold, 18f, CW))
        page.fillRect(ML, MT + 37f, CW, 1.5f, INK)

        // The fields, two rows of three.
        val (first, last) = splitName(c.playerName)
        var y = MT + 48f
        fields(page, f, y, listOf(0.38f to ("First & middle name" to first), 0.28f to ("Last name" to last), 0.34f to ("CARD GAME ID" to c.cardGameId)))
        y += 32f
        fields(page, f, y, listOf(0.48f to ("Event name" to c.eventName), 0.2f to ("Event date" to c.eventDate), 0.32f to ("Country of residency" to c.country)))
        y += 40f

        // The Main Deck: its total, then three columns.
        page.text(f.bold, 9f, ML, y + 10f, "MAIN DECK")
        val total = c.mainTotal.toString()
        right(page, f.bold, 14f, WIDTH - ML, y + 12f, total, INK)
        right(page, f.regular, 8f, WIDTH - ML - f.bold.width(total, 14f) - 6f, y + 11f, "Main Deck total", INK70)
        y += 18f
        page.fillRect(ML, y, CW, 1f, INK)
        y += 8f
        val third = (CW - 2 * GUTTER) / 3
        column(page, f, ML, y, third, "Monster cards", c.monsters, MAIN_ROWS, index)
        column(page, f, ML + third + GUTTER, y, third, "Spell cards", c.spells, MAIN_ROWS, index)
        column(page, f, ML + 2 * (third + GUTTER), y, third, "Trap cards", c.traps, MAIN_ROWS, index)
        y += columnHeight(MAIN_ROWS) + 18f

        // Side and Extra.
        val half = (CW - GUTTER) / 2
        column(page, f, ML, y, half, "Side Deck", c.side, SIDE_ROWS, index)
        column(page, f, ML + half + GUTTER, y, half, "Extra Deck", c.extra, SIDE_ROWS, index)

        page.text(f.regular, 7f, ML, HEIGHT - 24f, "Made with Neue Master Tool", INK45)
        right(page, f.regular, 7f, WIDTH - ML, HEIGHT - 24f, "Full English card names, one language throughout", INK45)
    }

    /** A row of labelled fields, each [width share] of the line, a rule under each value. */
    private fun fields(page: PdfPage, f: Fonts, top: Float, items: List<Pair<Float, Pair<String, String>>>) {
        val usable = CW - GUTTER * (items.size - 1)
        var x = ML
        items.forEach { (share, field) ->
            val w = usable * share
            page.text(f.regular, 6.5f, x, top + 7f, field.first, INK45)
            page.text(f.bold, 10.5f, x, top + 21f, fit(field.second, f.bold, 10.5f, w))
            page.fillRect(x, top + 25f, w, 0.6f, INK)
            x += w + GUTTER
        }
    }

    private fun columnHeight(rows: Int) = 14f + rows * ROW + 20f

    /**
     * A section's column: its heading, [rows] ruled rows of count and name (the
     * [index]th page's slice of [lines]), and the section's total under them.
     */
    private fun column(page: PdfPage, f: Fonts, x: Float, top: Float, width: Float, title: String, lines: List<Line>, rows: Int, index: Int) {
        page.text(f.bold, 7.5f, x, top + 8f, title.uppercase())
        page.fillRect(x, top + 12f, width, 0.75f, INK)
        val qty = 18f
        val nameX = x + qty + 6f
        val nameWidth = width - qty - 8f
        val slice = lines.drop(index * rows).take(rows)
        var y = top + 14f
        for (r in 0 until rows) {
            val line = slice.getOrNull(r)
            if (line != null) {
                val count = line.count.toString()
                page.text(f.mono, 8.5f, x + (qty - f.mono.width(count, 8.5f)) / 2, y + 9.5f, count)
                val size = sizeToFit(line.name, f.regular, nameWidth)
                page.text(f.regular, size, nameX, y + 9.5f, fit(line.name, f.regular, size, nameWidth))
            }
            page.fillRect(x + qty, y + 1f, 0.5f, ROW - 2f, INK12)
            page.fillRect(x, y + ROW, width, 0.4f, INK12)
            y += ROW
        }
        // The total: the whole section's, on every page.
        y += 4f
        page.fillRect(x, y, width, 16f, 0.95f)
        page.text(f.regular, 7.5f, x + 4f, y + 11f, "Total $title", INK70)
        right(page, f.bold, 10f, x + width - 4f, y + 11.5f, lines.total.toString(), INK)
    }

    /** The largest size from [NAME_SIZE] down to [NAME_MIN] at which [text] fits [width]. */
    private fun sizeToFit(text: String, font: PdfFont, width: Float): Float {
        var size = NAME_SIZE
        while (size > NAME_MIN && font.width(text, size) > width) size -= 0.25f
        return size.coerceAtLeast(NAME_MIN)
    }

    private fun right(page: PdfPage, font: PdfFont, size: Float, x: Float, baseline: Float, text: String, gray: Float) =
        page.text(font, size, x - font.width(text, size), baseline, text, gray)

    /** [text] cut with an ellipsis to fit [width]: the last resort, past the smallest size. */
    private fun fit(text: String, font: PdfFont, size: Float, width: Float): String {
        if (font.width(text, size) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…", size) > width) t = t.dropLast(1)
        return "$t…"
    }
}
