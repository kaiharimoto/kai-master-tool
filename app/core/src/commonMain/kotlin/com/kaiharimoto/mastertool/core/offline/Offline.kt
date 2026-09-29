package com.kaiharimoto.mastertool.core.offline

import com.kaiharimoto.mastertool.core.data.PoolCheck
import com.kaiharimoto.mastertool.core.data.PoolProgress
import com.kaiharimoto.mastertool.core.data.Sizes

/**
 * How far the library of full-size originals has got (`ArtLibrary`).
 *
 * [unavailable] are cards YGOPRODeck has no original for (a 404, or an error
 * page with a 200 on it): nothing more will come for them, so they count as
 * settled — or the library would sit at 99% for ever.
 */
data class ArtCount(
    val have: Int,
    val unavailable: Int,
    val total: Int,
    val bytes: Long,
    /** Originals arriving a second, lately; 0 when none are. */
    val perSecond: Double = 0.0,
) {
    val settled: Int get() = (have + unavailable).coerceIn(0, total.coerceAtLeast(0))
    val remaining: Int get() = (total - settled).coerceAtLeast(0)
    val complete: Boolean get() = total > 0 && remaining == 0
    val fraction: Float get() = if (total <= 0) 0f else settled.toFloat() / total

    /** A whole percent that reads 100 only when it is: 14,589 of 14,590 is 99%. */
    val percent: Int get() = if (total <= 0) 0 else if (complete) 100 else (settled * 100L / total).toInt().coerceAtMost(99)

    companion object {
        val NONE = ArtCount(0, 0, 0, 0L)
    }
}

/** One piece of background work, as the title bar and Settings read it out: [fraction] null is indeterminate. */
data class WorkReadout(val label: String, val figure: String, val fraction: Float?, val detail: String)

/** Whether this device can build decks with no network, and what is left if not. */
data class Readiness(val ready: Boolean, val words: String)

/**
 * The wording of the card pool and the art library's progress, and whether both
 * are ready for a flight (kai: "so they know when they're ready to use
 * offline"). Pure: the pages pass in what the state holds and draw what comes
 * back.
 */
object Offline {

    /**
     * The card pool's row in Settings: `Up to date · 14,590 cards · checked
     * 12:04`. [clock] is the check's time already written the device's way.
     */
    fun poolLine(check: PoolCheck?, cards: Int, clock: String?, updating: PoolProgress?): String {
        val count = "${Sizes.grouped(cards.toLong())} cards"
        if (updating != null) return "Updating · ${updating.words}"
        val at = clock?.let { " · checked $it" }.orEmpty()
        return when (check) {
            null -> if (cards == 0) "No card pool yet" else "$count · not checked this session"
            is PoolCheck.Current -> "Up to date · $count$at"
            is PoolCheck.Behind -> when {
                cards == 0 -> "No card pool yet · YGOPRODeck is at ${check.remote}"
                check.local == null -> "An update may be available · $count$at"
                else -> "An update is available · ${check.remote}, this pool is ${check.local}$at"
            }
            is PoolCheck.Unreachable -> "Couldn't reach YGOPRODeck · $count on this device$at"
        }
    }

    /** The art library's row in Settings: `6,210 of 14,590 · 1.1 GB · about 12 min left`. */
    fun artLine(art: ArtCount, enabled: Boolean, problem: String?): String {
        val base = "${Sizes.grouped(art.settled.toLong())} of ${Sizes.grouped(art.total.toLong())} · ${Sizes.disk(art.bytes)}"
        val tail = when {
            art.total == 0 -> ""
            art.complete -> " · every card"
            !enabled -> " · paused"
            problem != null -> " · $problem"
            else -> eta(art.remaining, art.perSecond)?.let { " · $it" }.orEmpty()
        }
        return base + tail
    }

    /** How long [remaining] takes at [perSecond], in words a person plans by; null when nothing is arriving to measure. */
    fun eta(remaining: Int, perSecond: Double): String? {
        if (remaining <= 0 || perSecond <= 0.0) return null
        val seconds = remaining / perSecond
        val minutes = kotlin.math.ceil(seconds / 60).toLong()
        return when {
            seconds < 60 -> "under a minute left"
            minutes < 90 -> "about $minutes min left"
            else -> {
                val halves = kotlin.math.round(minutes / 30.0).toLong()
                "about ${halves / 2}${if (halves % 2 == 1L) "½" else ""} h left"
            }
        }
    }

    /**
     * Ready for offline when the pool is known current (checked, or updated,
     * this session) and every card's original is here or known not to exist.
     */
    fun readiness(check: PoolCheck?, cards: Int, art: ArtCount, artEnabled: Boolean): Readiness {
        val pool = check is PoolCheck.Current && cards > 0
        val missing = buildList {
            when {
                cards == 0 -> add("download the card pool")
                check == null || check is PoolCheck.Unreachable -> add("check the card pool")
                check is PoolCheck.Behind -> add("update the card pool")
            }
            if (!art.complete) add(if (artEnabled && art.remaining > 0) "wait for ${Sizes.grouped(art.remaining.toLong())} more pictures" else "download the art")
        }
        return if (pool && art.complete) {
            Readiness(true, "Ready for offline")
        } else {
            Readiness(false, "Not ready for offline yet: " + missing.joinToString(", then "))
        }
    }

    /**
     * What the title bar shows while something is fetched: the pool's update
     * over the art's, since the pool is the one that changes what you search.
     * Null when nothing is.
     */
    fun readout(pool: PoolProgress?, art: ArtCount?, artRunning: Boolean, problem: String?): WorkReadout? {
        if (pool != null) {
            return WorkReadout("Card pool", "${(pool.fraction * 100).toInt()}%", pool.fraction, "Updating the card pool: ${pool.words}")
        }
        if (art != null && artRunning && !art.complete && art.total > 0) {
            val left = eta(art.remaining, art.perSecond)
            val detail = "Downloading every card's full-size picture for offline use: " +
                "${Sizes.grouped(art.settled.toLong())} of ${Sizes.grouped(art.total.toLong())}" +
                (problem?.let { ". $it" } ?: left?.let { ", $it" }.orEmpty())
            return WorkReadout("Card art", "${art.percent}%", art.fraction, detail)
        }
        return null
    }
}
