package com.kaiharimoto.mastertool.core.data

import kotlinx.serialization.Serializable

/**
 * What YGOPRODeck says its card database is at: `checkDBVer.php`, a few dozen
 * bytes, answered as `[{"database_version":"147.20","last_update":"2026-09-28 00:05:08"}]`.
 */
data class PoolVersion(val database: String, val updated: String)

/**
 * What this device knows about the pool it holds, beside the pool: the database
 * version it was fetched at and how large the download was, so the next one's
 * progress has something to measure against. One JSON row in the preferences
 * table ([KEY]) — a new row, not a new schema.
 */
@Serializable
data class PoolRecord(
    val version: String? = null,
    val bytes: Long = 0,
    /** Whether the pool was fetched with its release data (Phase B, 1.1.0); a pool from before is fetched again once. */
    val misc: Boolean = false,
) {
    companion object {
        const val KEY = "card.pool"
    }
}

/** The answer to "is the card pool current?", asked by hand in Settings (kai, for a flight). */
sealed interface PoolCheck {
    /** When it was asked, epoch milliseconds. */
    val checkedAt: Long

    /** Nothing newer than what is here. */
    data class Current(val version: String?, val cards: Int, override val checkedAt: Long) : PoolCheck

    /** YGOPRODeck has moved on: [local] is null when this pool's version was never written down and its age cannot vouch for it. */
    data class Behind(val local: String?, val remote: String, val cards: Int, override val checkedAt: Long) : PoolCheck

    /** YGOPRODeck did not answer. */
    data class Unreachable(val cards: Int, override val checkedAt: Long) : PoolCheck
}

/**
 * Deciding whether a pool is current. Pure, so the rules are tested rather than
 * trusted.
 */
object PoolFreshness {

    /** A day: `last_update` carries no time zone, so a pool counts as newer than it only by this much. */
    private const val SLACK_MS = 24L * 60 * 60 * 1000

    /**
     * Whether a pool fetched at [local] (and at [syncedAt]) is as new as the
     * database at [remote].
     *
     * The same version is current, and so is a newer one (a CDN answering from a
     * stale copy). A pool whose version was never written down — every pool
     * fetched before the version was kept — is current only if it was fetched more than a day
     * after the database last changed.
     */
    fun isCurrent(local: String?, syncedAt: Long?, remote: PoolVersion): Boolean {
        if (local != null) return compare(local, remote.database) >= 0
        val changed = parseUtc(remote.updated) ?: return false
        return syncedAt != null && syncedAt >= changed + SLACK_MS
    }

    /** Dotted versions compared part by part as numbers (`147.9` < `147.20`), text where a part is not a number. */
    fun compare(a: String, b: String): Int {
        val x = a.trim().split('.')
        val y = b.trim().split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { "0" }
            val q = y.getOrElse(i) { "0" }
            val pn = p.toLongOrNull()
            val qn = q.toLongOrNull()
            val c = if (pn != null && qn != null) pn.compareTo(qn) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }

    /** `yyyy-MM-dd HH:mm:ss`, read as UTC, to epoch milliseconds; null when it is not that. */
    fun parseUtc(text: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?""").find(text.trim()) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val s = m.groupValues[6].ifEmpty { "0" }
        val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
        return ((days * 24 + h.toLong()) * 60 + mi.toLong()) * 60_000 + s.toLong() * 1000
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }
}

/**
 * How far an update of the pool has got, in the order it happens: asking which
 * version is current, downloading the whole database (tens of megabytes, sent
 * with no length, so measured against the last one's size), reading it, and
 * writing it into the database.
 */
sealed interface PoolProgress {
    data object Asking : PoolProgress
    data class Downloading(val bytes: Long, val expected: Long) : PoolProgress
    data object Reading : PoolProgress
    data class Saving(val done: Int, val total: Int) : PoolProgress

    /**
     * 0..1 across the whole update. The download is most of the wait and gets
     * most of the bar; it never reaches its end on an estimate, only when the
     * download does.
     */
    val fraction: Float
        get() = when (this) {
            Asking -> 0.02f
            is Downloading -> 0.05f + 0.75f * if (expected <= 0) 0f else (bytes.toFloat() / expected).coerceIn(0f, 0.97f)
            Reading -> 0.82f
            is Saving -> 0.85f + 0.15f * if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        }

    /** What is happening, in a few words for a status line. */
    val words: String
        get() = when (this) {
            Asking -> "Asking YGOPRODeck"
            is Downloading -> "Downloading · ${Sizes.megabytes(bytes)}" + if (expected > 0) " of about ${Sizes.megabytes(expected)}" else ""
            Reading -> "Reading the cards"
            is Saving -> "Saving ${Sizes.grouped(done.toLong())} of ${Sizes.grouped(total.toLong())}"
        }

    companion object {
        /** A card is about fifteen hundred bytes of JSON. */
        const val BYTES_PER_CARD = 1_500L

        /** When nothing is known: the whole database in 2026, about 21 MB. */
        const val FIRST_GUESS = 21_000_000L

        /** The size to measure a download against: the last one's, else the pool's card count's, else the first guess. */
        fun expected(record: PoolRecord?, cards: Int): Long = when {
            record != null && record.bytes > 0 -> record.bytes
            cards > 0 -> cards * BYTES_PER_CARD
            else -> FIRST_GUESS
        }
    }
}

/** Numbers as the app writes them, without `String.format`, which common code does not have. */
object Sizes {
    /** `14590` → `14,590`. */
    fun grouped(n: Long): String {
        val digits = kotlin.math.abs(n).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, ch ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return if (n < 0) "-$out" else out.toString()
    }

    /** Megabytes to a tenth under ten, whole above: `4.2 MB`, `21 MB`. */
    fun megabytes(bytes: Long): String {
        val tenths = bytes.coerceAtLeast(0) / 100_000
        return if (tenths < 100) "${tenths / 10}.${tenths % 10} MB" else "${tenths / 10} MB"
    }

    /** The art library's size: binary megabytes, gigabytes to a tenth past one. */
    fun disk(bytes: Long): String {
        val gb = 1L shl 30
        return if (bytes >= gb) {
            val tenths = bytes * 10 / gb
            "${tenths / 10}.${tenths % 10} GB"
        } else {
            "${bytes / (1L shl 20)} MB"
        }
    }
}
