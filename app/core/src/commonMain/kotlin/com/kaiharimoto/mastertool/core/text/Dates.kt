package com.kaiharimoto.mastertool.core.text

import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.prep.IsoDate

/**
 * Dates the way the family writes them (the 1.1.2 design review, finding 12), in one place: a day is `1 May 2025`,
 * a moment this year `3 Oct, 23:46`. Typed and stored dates stay ISO (`yyyy-MM-dd`); these are for reading.
 */
object Dates {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** `2025-05-01` as `1 May 2025`; anything that is not a date as it is. */
    fun day(iso: String): String = Legality.readable(iso)

    /**
     * A moment as `3 Oct, 23:46`, at [offsetMinutes] from UTC (the device's, which the caller knows and core does not);
     * a moment in another year than [nowMillis]'s says it: `3 Oct 2025, 23:46`.
     */
    fun short(atMillis: Long, offsetMinutes: Int = 0, nowMillis: Long? = null): String {
        val local = atMillis + offsetMinutes * 60_000L
        val day = local.floorDiv(86_400_000L)
        val minutes = local.mod(86_400_000L) / 60_000L
        val (y, m, d) = IsoDate.of(day).split('-')
        val year = nowMillis?.let { now -> IsoDate.of((now + offsetMinutes * 60_000L).floorDiv(86_400_000L)).take(4) }
        val shownYear = if (year != null && year != y) " $y" else ""
        val hh = (minutes / 60).toString().padStart(2, '0')
        val mm = (minutes % 60).toString().padStart(2, '0')
        return "${d.toInt()} ${MONTHS[m.toInt() - 1]}$shownYear, $hh:$mm"
    }

    /**
     * What was typed as a day, made ISO, or null: `2025-05-01`, `20250501`, `2025 5 1`, `2025/05/01` and `2025.5.1`
     * are all `2025-05-01` — on a phone's keyboard a dash is a second page away (finding 6).
     */
    fun parseDay(text: String): String? {
        val t = text.trim()
        val parts = when {
            t.length == 8 && t.all(Char::isDigit) -> listOf(t.substring(0, 4), t.substring(4, 6), t.substring(6, 8))
            else -> t.split('-', '/', '.', ' ').filter { it.isNotEmpty() }
        }
        if (parts.size != 3 || parts[0].length != 4 || parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
        val m = parts[1].toInt()
        val d = parts[2].toInt()
        if (m !in 1..12 || d !in 1..31 || parts[1].length > 2 || parts[2].length > 2) return null
        val iso = "${parts[0]}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        // 31 Feb is no day: the calendar's arithmetic must give the same date back.
        return iso.takeIf { IsoDate.epochDay(it)?.let(IsoDate::of) == it }
    }
}
