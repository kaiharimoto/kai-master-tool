package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords

/** A comparison in words (Phase G, G2): what the page, Ai and the notes say, one list. */
object CompareWords {
    /** An interval of a difference in points: "+1.2 to +6.4". */
    fun interval(i: Pair<Double, Double>): String = "${PairedMath.points(i.first)} to ${PairedMath.points(i.second)}"

    /**
     * The headline: "With the change, 64.1 % of hands reach at least 1 interruption, against 60.3 % as it is: +3.8 points
     * (95 %: +1.2 to +6.4), on 800 hands dealt the same."
     */
    fun headline(c: Comparison, ask: String, change: String = "the change"): String {
        val p = c.paired
        if (p.hands == 0) return "No hands compared."
        return "With $change, ${GoldfishWords.pct(p.b)} of hands reach $ask, against ${GoldfishWords.pct(p.a)} as it is: " +
            "${PairedMath.points(p.difference)} points (95 %: ${interval(p.interval)}), on ${GoldfishWords.count(p.hands)} hands dealt the same."
    }

    /** What the interval says, plainly. */
    fun verdict(p: Paired, change: String = "the change"): String = when (p.verdict) {
        Paired.Verdict.NOTHING -> "No hands compared."
        Paired.Verdict.B_BETTER -> "${change.replaceFirstChar { it.uppercase() }} is better: the interval is above zero."
        Paired.Verdict.A_BETTER -> "The deck as it is is better: the interval is below zero."
        Paired.Verdict.ALIKE -> "Within a point either way: alike, as far as this ask can tell."
        Paired.Verdict.UNSURE -> "Not settled: the interval still holds zero, so more hands would say."
    }

    /** The hands that changed: "86 hands changed: 61 won with the change, 25 lost (a split this uneven by chance: p = 0.0002)." */
    fun changed(p: Paired, change: String = "the change"): String {
        if (p.discordant == 0) return "No hand changed its answer."
        return "${GoldfishWords.count(p.discordant)} hands changed: ${GoldfishWords.count(p.onlyB)} won with $change, " +
            "${GoldfishWords.count(p.onlyA)} lost (a split this uneven by chance: p = ${pValue(p.pValue)})."
    }

    /** Undecided hands counted both ways, when there were any. */
    fun undecided(p: Paired): String? {
        if (p.undecidedA + p.undecidedB == 0) return null
        val (lo, hi) = p.bounds
        return "${GoldfishWords.count(p.undecidedA)} hands as it is and ${GoldfishWords.count(p.undecidedB)} with the change ran out of " +
            "room undecided; counted both ways, the difference is between ${PairedMath.points(lo)} and ${PairedMath.points(hi)} points."
    }

    /** Why a comparison was refused, naming the cards to write. */
    fun refused(g: CoverageGuard.Check, name: (Int) -> String): String = when {
        g.diff.isEmpty -> "The two versions hold the same cards: there is nothing to compare."
        else -> "Not compared: ${g.unwritten.joinToString(", ") { name(it) }} ${if (g.unwritten.size == 1) "has" else "have"} no written " +
            "effect the engine trusts, yet something in the deck could pick ${if (g.unwritten.size == 1) "it" else "them"} — the " +
            "comparison would count ${if (g.unwritten.size == 1) "it" else "them"} as doing nothing. Write ${if (g.unwritten.size == 1) "it" else "these"} first."
    }

    /** The whole answer, for a note or a tool's result. */
    fun all(c: Comparison, ask: String, change: String = "the change", name: (Int) -> String = { "#$it" }): List<String> {
        c.guard?.takeIf { !it.ok }?.let { return listOf(refused(it, name)) }
        return buildList {
            add(headline(c, ask, change))
            add(verdict(c.paired, change))
            add(changed(c.paired, change))
            undecided(c.paired)?.let(::add)
            c.guard?.questions?.forEach(::add)
            if (c.stopped) add("Stopped before it settled; the counts so far are shown.")
        }
    }

    /** A probability as a person reads it: "0.03", "< 0.001". */
    fun pValue(p: Double): String = when {
        p < 0.001 -> "< 0.001"
        p >= 0.995 -> "1"
        else -> (kotlin.math.round(p * 1000) / 1000.0).toString()
    }
}
