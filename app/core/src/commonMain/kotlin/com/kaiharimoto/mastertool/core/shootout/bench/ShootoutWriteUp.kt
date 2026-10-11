package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.ai.evidence.Proof
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.round

/** One line for the deck's guide, and the proof of its numbers (S4.3): what Shootout computed, on the deck as it stood. */
class WriteUpEntry(val text: String, val proof: Proof) {
    /** The ledger's record of it: computed, so checked. */
    fun proven(now: Long): Proven = Proven(text, listOf(proof), Proven.Status.CHECKED, checkedAt = now)
}

/**
 * The results written into the deck's guide (Phase S, S4.3; `docs/phases/S.md` §6 4): **from the computed numbers only** —
 * the app writes each line from the results, never Ai's words around them — each with its proof in the evidence ledger
 * (`Proof(tool = "shootout")`, the deck's print), so a change to the deck marks it stale like every other number. Only what
 * the results call is written: cards clear of zero with every card counted, the roll when its range clears zero, the
 * patterns called, the plans clear of zero, and each situation's win rate. Every line ends with [mark], so writing again
 * replaces the lines of the same target and never piles them up.
 */
object ShootoutWriteUp {
    /** The evidence ledger's name for Shootout's numbers. */
    const val TOOL = "shootout"

    /** At most this many cards: the clearest calls. */
    const val CARDS = 6

    /** The words every line of [opponent]'s target ends with: the deck alone, or against one opponent. */
    fun mark(opponent: String?): String = "(Shootout, " + (opponent?.let { "against $it" } ?: "the deck alone") + ")"

    /** Whether a guide entry is one of these for [opponent]'s target. */
    fun isOurs(entry: String, opponent: String?): Boolean = entry.trimEnd().endsWith(mark(opponent))

    /**
     * The lines for [r]: [name] names a card (written `[[Name]]`, the guide's card link), [deckId] and [opponentId] say what was
     * read, [deckPrint] the deck it was read on, [at] when.
     */
    fun entries(
        r: ShootoutResults,
        opponent: String?,
        name: (Int) -> String,
        deckId: String,
        opponentId: String?,
        deckPrint: String,
        at: Long,
    ): List<WriteUpEntry> {
        val alone = opponent == null
        val tail = " " + mark(opponent)
        fun proof(what: String, excerpt: String) = Proof(
            tool = TOOL,
            input = buildJsonObject {
                put("deck", deckId)
                opponentId?.let { put("opponent", it) }
                put("number", what)
                put("hands", r.fitted)
            }.toString(),
            excerpt = excerpt,
            at = at,
            deck = deckPrint,
        )
        val out = ArrayList<WriteUpEntry>()
        // Each situation's rate, one line: what every other number is read against.
        if (r.winRates.isNotEmpty()) {
            val rates = r.strata.mapNotNull { st -> r.winRates[st]?.let { st to it } }
            val words = rates.joinToString("; ") { (st, e) -> "${whole(e.value)}% ${ShootoutWords.where(st)} (95 %: ${whole(e.range95.start)} to ${whole(e.range95.endInclusive)})" }
            val what = if (alone) "A real hand does what the deck wants" else "The matchup is won"
            out += WriteUpEntry("Game plan: $what $words, over real hands by their real odds, from ${r.fitted} of your judged hands.$tail", proof("win-rates", words))
        }
        r.roll()?.takeIf { it.choice != null }?.let { roll ->
            val words = ShootoutWords.roll(roll)
            out += WriteUpEntry("Game plan: $words.$tail", proof("roll", words))
        }
        r.calls().distinctBy { it.card }.take(CARDS).forEach { call ->
            val e = call.estimate
            val trials = r.cards.firstOrNull { it.card == call.card }?.cells?.get(call.stratum)?.trials ?: 0
            val range = "95 %: ${ShootoutWords.points(e.range95.start)} to ${ShootoutWords.points(e.range95.endInclusive)}"
            val worth = if (call.gains) "is worth about +${whole(abs(e.value))} points" else "costs about ${whole(abs(e.value))} points"
            val text = "Card roles: [[${name(call.card)}]] $worth of win chance per copy in the opening hand ${ShootoutWords.where(call.stratum)} ($range), " +
                "against the card the deck would deal instead, from $trials hands.$tail"
            out += WriteUpEntry(text, proof("card:${call.card}:${call.stratum.name}", "${ShootoutWords.points(e.value)} ($range)"))
        }
        val called = ShootoutPatterns.called(r.patterns)
        r.strata.forEach { st ->
            r.patterns.mapNotNull { row -> row.cells[st]?.takeIf { (row.pattern.key to st) in called }?.let { row.pattern to it } }
                .sortedByDescending { abs(it.second.lift.value) }.take(2).forEach { (p, c) ->
                    val words = "${whole(c.rate.value)}% (95 %: ${whole(c.rate.range95.start)} to ${whole(c.rate.range95.endInclusive)}), ${PatternWords.lift(c, alone)}"
                    out += WriteUpEntry(
                        "Game plan: ${ShootoutWords.where(st).replaceFirstChar { it.uppercase() }}, opening hands of “${p.words}” ${if (alone) "do what the deck wants" else "win"} $words; ${ShootoutWords.percent(c.share)} of hands are.$tail",
                        proof("pattern:${p.key}:${st.name}", words),
                    )
                }
        }
        r.plans.forEach { plan ->
            plan.yours?.takeIf { it.excludesZero }?.let { e ->
                out += WriteUpEntry(
                    "Side deck: Your plan ${turn(plan.stratum)} ${if (e.value > 0) "adds" else "costs"} ${points(e)} on the hands it leaves you, against keeping game one's deck.$tail",
                    proof("plan:mine:${plan.stratum.name}", points(e)),
                )
            }
            plan.theirs?.takeIf { it.excludesZero }?.let { e ->
                out += WriteUpEntry(
                    "Side deck: ${opponent ?: "Their"}'s plan, you ${turn(plan.stratum)}, ${if (e.value < 0) "costs you" else "gives you"} ${points(e)}, against their game one's deck.$tail",
                    proof("plan:theirs:${plan.stratum.name}", points(e)),
                )
            }
        }
        return out
    }

    private fun turn(st: Stratum) = if (st.goingFirst) "going first" else "going second"

    private fun points(e: Estimate) = "${whole(abs(e.value))} points (95 %: ${ShootoutWords.points(e.range95.start)} to ${ShootoutWords.points(e.range95.endInclusive)})"

    private fun whole(x: Double): Int = round(x).toInt()
}
