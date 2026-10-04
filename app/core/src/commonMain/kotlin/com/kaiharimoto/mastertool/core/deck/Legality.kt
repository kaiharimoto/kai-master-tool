package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.Format

/**
 * Whether a card has been released where, and by when, a deck is played (Phase B, `docs/phases/B.md` §2).
 *
 * Read off the pool's release data (YGOPRODeck's `misc_info`): the regions it is printed in (`formats`) and its
 * first date in each — a region missing there counts only when Yugipedia agrees (`Card.absentFrom`, `RegionNames`). The list of formats decides **where** — a Speed Duel card carries a TCG date but is no
 * Advanced card, and 400-odd old and new cards were only ever printed in Japan — and the date decides **when**.
 * A pool without that data (stored by a build before 1.1.0, or a card the site has not dated) is [Release.Unknown],
 * never illegal: a missing fact must not fail a deck.
 */
object Legality {

    sealed interface Release {
        /** Released here by the date asked. */
        data object Legal : Release

        /** Never printed in this region ("OCG-only" in the TCG). */
        data class NotReleased(val format: Format) : Release

        /** Printed here, but after the date asked: out on [date] (`yyyy-MM-dd`). */
        data class NotYet(val format: Format, val date: String) : Release

        /** The pool does not say. */
        data object Unknown : Release
    }

    /** YGOPRODeck's word for the region in `formats`. */
    fun word(format: Format): String = if (format == Format.TCG) "TCG" else "OCG"

    /**
     * Where [card] stands in [format] on [asOf] (`yyyy-MM-dd`; ISO dates compare as text). With no [asOf] only
     * the region is checked.
     */
    fun release(card: Card, format: Format, asOf: String? = null): Release {
        if (card.formats.isEmpty()) return Release.Unknown
        // Not printed here only when a second source agrees (`RegionNames`): the pool alone has been a year behind.
        if (word(format) !in card.formats) return if (word(format) in card.absentFrom) Release.NotReleased(format) else Release.Unknown
        val date = if (format == Format.TCG) card.tcgDate else card.ocgDate
        if (asOf != null && date != null && isDate(date) && date > asOf) return Release.NotYet(format, date)
        return Release.Legal
    }

    /** True unless the data says otherwise. */
    fun playable(card: Card, format: Format, asOf: String? = null): Boolean =
        release(card, format, asOf).let { it is Release.Legal || it is Release.Unknown }

    /** The words for a card that is not playable, or null when it is. */
    fun problem(card: Card, format: Format, asOf: String? = null): String? = when (val r = release(card, format, asOf)) {
        is Release.NotReleased -> "${card.name} is not released in the ${word(format)}" +
            (if (format == Format.TCG && card.ocgDate != null) " (OCG only)" else "") + "."
        is Release.NotYet -> "${card.name} is not out in the ${word(format)} until ${readable(r.date)}."
        else -> null
    }

    /** `2026-11-14` as "14 Nov 2026"; anything else as it is. */
    fun readable(date: String): String {
        if (!isDate(date)) return date
        val (y, m, d) = date.split('-')
        val month = MONTHS.getOrNull(m.toInt() - 1) ?: return date
        return "${d.toInt()} $month $y"
    }

    fun isDate(text: String): Boolean = DATE.matches(text)

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
