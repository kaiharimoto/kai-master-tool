package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.math.round

/**
 * What a Side Deck covers across the field (Phase G, G.6; the red team's M2). Nothing aggregated siding across matchups:
 * each plan was read alone. Here, from every saved plan and each opponent's share of the field:
 *
 * - each side card's share of the field it comes in against, going first and going second, and the copies the plans ask
 *   for against the copies held — so a slot no plan brings in is plainly dead;
 * - Main Deck cards sided out against more than half the field (candidates for the side) and side cards brought in
 *   against more than half (candidates for the main);
 * - the share of the field with no plan at all, per turn;
 * - each plan's post-side legality, by the one rule the Lounge plays by ([SidingMath.legalAfter]).
 *
 * Shares are the web's; a matchup with none counts by [Opponent.share] = 0 and is said apart. With no shares at all, every
 * matchup weighs alike (said so: [equalWeights]).
 */
class SideCoverage(
    val cards: List<SideCard>,
    /** Copies held that no plan brings in. */
    val deadCopies: Int,
    /** Main (and Extra) Deck cards sided out against more than half the field, by turn. */
    val outAgainstMost: List<Moved>,
    /** Side cards brought in against more than half the field, by turn. */
    val inAgainstMost: List<Moved>,
    /** The share of the field (0–1) with no plan, going first and going second, and those opponents by name. */
    val unplannedFirst: Double,
    val unplannedSecond: Double,
    val unplanned: List<String>,
    /** Each plan whose post-side deck is not legal: the opponent, the turn, the problem in words. */
    val illegal: List<Problem>,
    /** No opponent had a share, so every matchup weighed alike. */
    val equalWeights: Boolean,
    val sideSize: Int,
) {
    /** One side card: the [held] copies, the most any plan brings in ([needed]), and the share of the field it meets. */
    data class SideCard(val card: CardId, val held: Int, val needed: Int, val first: Double, val second: Double, val against: List<String>) {
        val dead: Int get() = (held - needed).coerceAtLeast(0)
        val short: Int get() = (needed - held).coerceAtLeast(0)
    }

    /** A card moved against [share] (0–1) of the field going first or second. */
    data class Moved(val card: CardId, val turn: Turn, val share: Double)

    data class Problem(val opponent: String, val turn: Turn, val words: String)

    /** "8 of 15 copies come in against nothing". */
    fun deadWords(): String? = deadCopies.takeIf { it > 0 }?.let { "$it of $sideSize Side Deck ${if (sideSize == 1) "copy" else "copies"} come in against nothing" }

    /** An opponent sided against: its name, share of the field (percent, 0 when none) and its matchup if one is saved. */
    data class Opponent(val name: String, val share: Int, val matchup: Matchup?)

    companion object {
        /** More than this share of the field is "most of it". */
        const val MOST = 0.5

        /**
         * [deck]'s Side Deck read across [opponents]' plans. [isExtra] places a card for the post-side check (null: not
         * known, so not judged).
         */
        fun of(deck: Deck, opponents: List<Opponent>, isExtra: (CardId) -> Boolean?): SideCoverage {
            val total = opponents.sumOf { it.share }
            val equal = total == 0
            fun weight(o: Opponent): Double = if (equal) (if (opponents.isEmpty()) 0.0 else 1.0 / opponents.size) else o.share.toDouble() / total
            val held = deck.side.groupingBy { it }.eachCount()
            val needed = HashMap<CardId, Int>()
            val inFirst = HashMap<CardId, Double>()
            val inSecond = HashMap<CardId, Double>()
            val against = HashMap<CardId, MutableSet<String>>()
            val outShare = HashMap<Pair<CardId, Turn>, Double>()
            val inShare = HashMap<Pair<CardId, Turn>, Double>()
            var unplannedFirst = 0.0
            var unplannedSecond = 0.0
            val unplanned = ArrayList<String>()
            val illegal = ArrayList<Problem>()
            for (o in opponents) {
                val w = weight(o)
                var any = false
                for (turn in Turn.entries) {
                    val plan = o.matchup?.plan(turn)
                    if (plan == null || !plan.sided) {
                        if (turn == Turn.FIRST) unplannedFirst += w else unplannedSecond += w
                        continue
                    }
                    any = true
                    plan.into.groupingBy { it }.eachCount().forEach { (card, n) ->
                        needed[card] = maxOf(needed[card] ?: 0, n)
                        val at = if (turn == Turn.FIRST) inFirst else inSecond
                        at[card] = (at[card] ?: 0.0) + w
                        against.getOrPut(card) { LinkedHashSet() } += o.name
                        inShare[card to turn] = (inShare[card to turn] ?: 0.0) + w
                    }
                    plan.out.distinct().forEach { card -> outShare[card to turn] = (outShare[card to turn] ?: 0.0) + w }
                    val problem = if (plan.out.size != plan.into.size) "not card for card (${SidingMath.balanceWords(plan)})" else SidingMath.legalAfter(deck, plan, isExtra)
                    if (problem != null) illegal += Problem(o.name, turn, problem)
                }
                if (!any) unplanned += o.name
            }
            val sideCards = (deck.side.distinct() + needed.keys.filter { it !in held }).distinct().map { card ->
                SideCard(card, held[card] ?: 0, needed[card] ?: 0, inFirst[card] ?: 0.0, inSecond[card] ?: 0.0, against[card]?.toList().orEmpty())
            }.sortedWith(compareByDescending<SideCard> { it.first + it.second }.thenByDescending { it.held })
            val sideSet = deck.side.toSet()
            return SideCoverage(
                cards = sideCards,
                deadCopies = sideCards.sumOf { it.dead },
                outAgainstMost = outShare.filter { (k, v) -> v > MOST && k.first !in sideSet }.map { (k, v) -> Moved(k.first, k.second, v) }.sortedByDescending { it.share },
                inAgainstMost = inShare.filter { (_, v) -> v > MOST }.map { (k, v) -> Moved(k.first, k.second, v) }.sortedByDescending { it.share },
                unplannedFirst = unplannedFirst,
                unplannedSecond = unplannedSecond,
                unplanned = unplanned,
                illegal = illegal,
                equalWeights = equal && opponents.isNotEmpty(),
                sideSize = deck.side.size,
            )
        }

        fun pct(x: Double): String = "${round(x * 100).toInt()}%"

        /** The guide's first-page lines (Phase G, G.6): the dead copies, the field with no plan, the candidates, the illegal plans. */
        fun guideLines(c: SideCoverage, name: (CardId) -> String): List<String> = listOfNotNull(
            c.deadWords()?.let { w -> "$w: " + c.cards.filter { it.dead > 0 }.joinToString(", ") { "${it.dead} ${name(it.card)}" } + "." },
            c.unplanned.takeIf { it.isNotEmpty() }?.let { "No plan against ${it.joinToString()}: ${pct(c.unplannedFirst)} of the field going first, ${pct(c.unplannedSecond)} going second." },
            c.outAgainstMost.takeIf { it.isNotEmpty() }?.let { m -> "Sided out against most of the field: " + m.joinToString { "${name(it.card)} ${it.turn.title.lowercase()} (${pct(it.share)})" } + "." },
            c.inAgainstMost.takeIf { it.isNotEmpty() }?.let { m -> "Brought in against most of the field: " + m.joinToString { "${name(it.card)} ${it.turn.title.lowercase()} (${pct(it.share)})" } + "." },
            c.illegal.takeIf { it.isNotEmpty() }?.let { p -> "Not a legal deck after siding: " + p.joinToString("; ") { "${it.opponent} ${it.turn.title.lowercase()}" } + "." },
        )

        /** The coverage in words, for Ai and the guide: [name] names a card. */
        fun words(c: SideCoverage, name: (CardId) -> String): String = buildString {
            appendLine("Side Deck coverage across the field" + if (c.equalWeights) " (no shares in the web, so every matchup weighs alike):" else ", by each opponent's share:")
            c.cards.forEach { s ->
                appendLine(
                    "- ${name(s.card)}: ${s.held} held, ${s.needed} asked for at most; in against ${pct(s.first)} of the field going first, ${pct(s.second)} going second" +
                        (if (s.against.isNotEmpty()) " (${s.against.joinToString()})" else "") +
                        (if (s.dead > 0) "; ${s.dead} never come in" else "") + (if (s.short > 0) "; ${s.short} more than the Side Deck holds" else ""),
                )
            }
            c.deadWords()?.let { appendLine("$it.") }
            if (c.outAgainstMost.isNotEmpty()) appendLine("Sided out against most of the field (candidates for the Side Deck): " + c.outAgainstMost.joinToString { "${name(it.card)} ${it.turn.title.lowercase()} (${pct(it.share)})" } + ".")
            if (c.inAgainstMost.isNotEmpty()) appendLine("Brought in against most of the field (candidates for the Main Deck): " + c.inAgainstMost.joinToString { "${name(it.card)} ${it.turn.title.lowercase()} (${pct(it.share)})" } + ".")
            if (c.unplanned.isNotEmpty()) appendLine("No plan yet against: ${c.unplanned.joinToString()} — ${pct(c.unplannedFirst)} of the field going first, ${pct(c.unplannedSecond)} going second.")
            if (c.illegal.isNotEmpty()) appendLine("Plans that do not leave a legal deck: " + c.illegal.joinToString("; ") { "${it.opponent}, ${it.turn.title.lowercase()}: ${it.words}" } + ".")
            else appendLine("Every saved plan leaves a legal deck (card for card, §VII.C).")
        }.trimEnd()
    }
}
