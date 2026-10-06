package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.world.WorldStats
import kotlin.math.roundToLong

/**
 * A goldfish result in words (D.md §5.5, §5.6, §11): the headline, the lines, the cards played as inert and what the trust
 * rule asks to be said. Pure: the pane, the instrument and Ai read the same sentences.
 *
 * > Gets there in 63.1 % of 2,000 hands (95 %: 61.0–65.2 %), seed 7, going first; no line in 30.4 %; undecided in 6.5 %.
 *
 * Reached hands out of N, with Wilson's 95 % interval as the instruments give it ([WorldStats.wilson]). A **lower bound**:
 * undecided hands count as not reached, and the sentence says how many there were; with cards played as inert it says
 * "at least" and adds the two shares of §5.5.
 */
object GoldfishWords {
    /** One decimal, with a space before the sign: "63.1 %". */
    fun pct(p: Double): String = "${oneDecimal(p * 100)} %"

    private fun oneDecimal(x: Double): String {
        val r = (x * 10).roundToLong()
        val sign = if (r < 0) "-" else ""
        val a = kotlin.math.abs(r)
        return "$sign${a / 10}.${a % 10}"
    }

    /** "2,000". */
    fun count(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

    /** The 95 % interval of [reached] of [hands]: "61.0–65.2 %". */
    fun interval(reached: Int, hands: Int): String {
        val (lo, hi) = WorldStats.wilson(reached, hands)
        return "${oneDecimal(lo * 100)}–${oneDecimal(hi * 100)} %"
    }

    /**
     * The headline. [name] names a card by passcode; [copies] how many of it the deck holds (0: not said). A recorded line
     * reads "This line gets there …"; the search "Gets there …".
     */
    fun headline(r: GoldfishResult, name: (Int) -> String = { "#$it" }, copies: (Int) -> Int = { 0 }): String = buildString {
        val n = r.hands.coerceAtLeast(1)
        val atLeast = r.unknown.isNotEmpty() && r.heldUnknown > 0
        append(if (r.combo != null) "This line gets there in " else "Gets there in ")
        if (atLeast) append("at least ")
        append(pct(r.reached.toDouble() / n)).append(" of ").append(count(r.hands)).append(" hands")
        append(" (95 %: ").append(interval(r.reached, r.hands)).append(")")
        append(", seed ").append(r.seed).append(", going ").append(if (r.first) "first" else "second")
        append("; no line in ").append(pct(r.noLine.toDouble() / n))
        append("; undecided in ").append(pct(r.undecided.toDouble() / n)).append(".")
        if (atLeast) {
            append(" ").append(pct(r.heldUnknown.toDouble() / n)).append(" of hands held a card with no trusted effect (")
            append(r.unknown.joinToString { c -> name(c) + copies(c).takeIf { it > 0 }?.let { " ×$it" }.orEmpty() })
            append("), played as inert: the true number may be higher.")
            if (r.touchedUnknown > 0) {
                append(" ").append(pct(r.touchedUnknown.toDouble() / n)).append(" of hands reached the board through a line that moves an unknown card.")
            }
        }
    }

    /** The lines found: "The search found 4 lines; the commonest in 41.0 % of hands: A → B → C." */
    fun lines(r: GoldfishResult): String {
        if (r.lines.isEmpty()) return if (r.combo != null) "The line never got there." else "The search found no line."
        val top = r.lines.first()
        val share = pct(top.count.toDouble() / r.hands.coerceAtLeast(1))
        return if (r.combo != null) "This line got there in ${count(top.count)} hands." else
            "The search found ${r.lines.size} line${if (r.lines.size == 1) "" else "s"}; the commonest in $share of hands: ${top.skeleton}."
    }

    /**
     * What the trust rule asks to be said (D.md §11): the effects the lines used — so a wrong number traces to a wrong script —
     * those with open warnings, card by card, and how many were played by you.
     */
    fun trust(r: GoldfishResult, name: (Int) -> String = { "#$it" }): String = buildString {
        if (r.used.isEmpty()) {
            append("No written effect was used.")
            return@buildString
        }
        append("Uses the written effects of ${r.used.size} card${if (r.used.size == 1) "" else "s"}: ")
        append(r.used.joinToString { name(it) }).append(".")
        if (r.warned.isNotEmpty()) {
            append(" Uses ${r.warned.size} effect${if (r.warned.size == 1) "" else "s"} with open warnings: ")
            append(r.warned.joinToString { name(it) }).append(".")
        }
        append(" ${r.playedByYou} of them played by you.")
    }

    /** The recorded lines and targets that were not computable, each with why. */
    fun notComputable(r: GoldfishResult): List<String> = r.notComputable.map { it.why }

    /** Every line of the study: the headline, the lines, the trust, what was not computable. */
    fun all(r: GoldfishResult, name: (Int) -> String = { "#$it" }, copies: (Int) -> Int = { 0 }): List<String> =
        listOf(headline(r, name, copies), lines(r), trust(r, name)) + notComputable(r)

    /**
     * A line's skeleton (§5.4): its activations and summons in order by card name ("Aluber → Branded Fusion → Mirrorjade"),
     * a Set as "Set X", the Spells and Traps Set at its end; "as dealt" when the hand met the target as it was.
     */
    fun skeleton(found: GoldfishSearch.Found, kit: GoldfishKit): String {
        val parts = ArrayList<String>()
        found.line.forEach { step ->
            val c = step.card ?: return@forEach
            when (val m = step.move) {
                is FxMove.Activate, is FxMove.Procedure -> parts += kit.name(c)
                is FxMove.NormalSummon -> parts += if (m.set) "Set ${kit.name(c)}" else kit.name(c)
                else -> {}
            }
        }
        val board = found.board
        found.sets.forEach { u -> board?.state?.cards?.get(u)?.let { parts += "Set ${kit.name(kit.canonical(it.code))}" } }
        return parts.joinToString(" → ").ifEmpty { "as dealt" }
    }
}
