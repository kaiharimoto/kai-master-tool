package com.kaiharimoto.mastertool.core.shootout.versus

import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import kotlin.math.abs
import kotlin.math.round

/**
 * Card against card's words (2026-10): a number that is the substitute less the card, said as which card it favours and
 * how sure the trials are. Sure is the 95 % range clear of zero; leaning, the 80 % range; else too close to call, or — once
 * the range is narrow — no real difference.
 */
object VersusWords {

    /** How sure a comparison is. */
    enum class Call { BETTER, LEANS, TOO_CLOSE, EVEN }

    /** Within this many points either way, a settled comparison is no real difference. */
    const val EVEN_POINTS = 2.0

    fun call(e: Estimate): Call = when {
        e.excludesZero -> Call.BETTER
        e.range80.let { it.start > 0 || it.endInclusive < 0 } -> Call.LEANS
        e.halfWidth95 <= EVEN_POINTS -> Call.EVEN
        else -> Call.TOO_CLOSE
    }

    /**
     * "Ash Blossom is better by 6 points", "Ash Blossom leans ahead by 3 points", "Too close to call: Ash Blossom ahead by 1
     * point", "No real difference". [card] and [substitute] are the two names.
     */
    fun verdict(e: Estimate, card: String, substitute: String): String {
        val ahead = if (e.value >= 0) substitute else card
        val by = points(abs(e.value))
        return when (call(e)) {
            Call.BETTER -> "$ahead is better by $by"
            Call.LEANS -> "$ahead leans ahead by $by"
            Call.EVEN -> "No real difference"
            Call.TOO_CLOSE -> if (abs(e.value) < 0.5) "Too close to call" else "Too close to call: $ahead ahead by $by"
        }
    }

    /** The range in words: "± 4 points, 95 %". */
    fun range(e: Estimate): String = "± ${points(e.halfWidth95)}, 95 %"

    /** A number in points of win chance: "1 point", "6 points", "0.4 points". */
    fun points(x: Double): String {
        val r = if (x < 1) round(x * 10) / 10 else round(x)
        val s = if (r == round(r)) r.toLong().toString() else r.toString()
        return if (s == "1") "1 point" else "$s points"
    }

    /** A win chance in percent: "54 %". */
    fun percent(e: Estimate): String = "${round(e.value).toLong()} %"

    /**
     * What a partner changes, in words: "Ash Blossom gains 5 points more beside Called by the Grave". Null when the
     * partner does not tell them apart yet.
     */
    fun partner(p: VersusPartner, card: String, substitute: String, partner: String): String? {
        if (!p.shown) return null
        val who = if (p.synergy.value >= 0) substitute else card
        return "$who gains ${points(abs(p.synergy.value))} more beside $partner"
    }

    /** How far the range must still shrink, for the progress line: "known within ± 6 points". */
    fun known(e: Estimate): String = "known within ± ${points(e.halfWidth95)}"
}
