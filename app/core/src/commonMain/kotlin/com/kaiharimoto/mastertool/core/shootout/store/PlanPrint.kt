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

    private fun counted(ids: List<Int>): List<Pair<Int, Int>> =
        ids.groupingBy { it }.eachCount().entries.sortedBy { it.key }.map { it.key to it.value }
}
