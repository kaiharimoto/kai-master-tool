package com.kaiharimoto.mastertool.core.shootout.store

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.siding.SidePlan

/**
 * A siding plan's fingerprint (S.md §1½, "the plan is part of the data"): which cards out and which in, by canonical
 * passcode and count, sorted, so two plans that side the same cards read the same whatever their order or printing.
 *
 * `-14558127x2 +9822220` reads as two Ash Blossom out, one card in. The note is not part of it: rewording why a plan is
 * right does not change the hands it deals. An empty plan ("we keep the deck as it is") is `=`.
 */
object PlanPrint {

    fun of(plan: SidePlan, canonical: (CardId) -> CardId = { it }): String {
        val outs = counted(plan.out.map { canonical(it).value })
        val ins = counted(plan.into.map { canonical(it).value })
        if (outs.isEmpty() && ins.isEmpty()) return "="
        return (outs.map { (id, n) -> "-$id" + if (n > 1) "x$n" else "" } + ins.map { (id, n) -> "+$id" + if (n > 1) "x$n" else "" })
            .joinToString(" ")
    }

    /**
     * What a print does to a main deck, card by card (S4.5): copies taken out negative, brought in positive; "=" and an empty
     * print do nothing, and a token that will not read is skipped.
     */
    fun delta(print: String): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        print.split(' ').filter { it.length > 1 && (it[0] == '-' || it[0] == '+') }.forEach { token ->
            val sign = if (token[0] == '-') -1 else 1
            val body = token.substring(1)
            val id = body.substringBefore('x').toIntOrNull() ?: return@forEach
            val n = if ('x' in body) body.substringAfter('x').toIntOrNull() ?: return@forEach else 1
            out[id] = (out[id] ?: 0) + sign * n
        }
        return out
    }

    private fun counted(ids: List<Int>): List<Pair<Int, Int>> =
        ids.groupingBy { it }.eachCount().entries.sortedBy { it.key }.map { it.key to it.value }
}
