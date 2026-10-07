package com.kaiharimoto.neue.duel

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The JVM's `d MMM, HH:mm` by hand: the browser's own clock and zone. */
actual fun duelStamp(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.day} ${MONTHS[t.month.ordinal]}, ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
