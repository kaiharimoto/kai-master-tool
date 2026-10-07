package com.kaiharimoto.neue.duel

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The browser's own clock and zone, as `[day, month, hour, minute]`: a `Date`, so the page needs no date library. */
@Suppress("UNUSED_PARAMETER")
private fun localParts(ms: Double): JsArray<JsNumber> =
    js("{ const d = new Date(ms); return [d.getDate(), d.getMonth(), d.getHours(), d.getMinutes()]; }")

/** The JVM's `d MMM, HH:mm` by hand. */
actual fun duelStamp(ms: Long): String {
    val p = localParts(ms.toDouble())
    fun at(i: Int) = p[i]!!.toInt()
    return "${at(0)} ${MONTHS[at(1)]}, ${at(2).toString().padStart(2, '0')}:${at(3).toString().padStart(2, '0')}"
}
