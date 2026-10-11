package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import kotlin.math.roundToInt

/*
 * A side deck chosen for the field (Phase G part two, G2.2; `docs/phases/G2.md`): fifteen cards and a plan against each
 * opponent, drawn from the evidence there is — what Shootout's judged hands rate a card against that opponent, and the games
 * logged with it in the opening hand against them — each choice carrying the numbers it rests on. A card no evidence rates is
 * never chosen for a number it does not have ("unrated"); an opponent with no evidence is said, not guessed at. The plans can
 * then be played out (G2.1) before any of it is kept.
 */

/**
 * What a card is worth against one opponent, in points of win chance, with its 95 % range and where the number comes from;
 * [first] is the turn it was read on (null: both, pooled), [n] what stands behind it (trials or games).
 */
data class SideWorth(
    val card: Int,
    val opponent: String,
    val points: Double,
    val low: Double,
    val high: Double,
    val source: String,
    val n: Int,
    val first: Boolean? = null,
) {
    companion object {
        const val SHOOTOUT = "shootout"
        const val GAMES = "games"

        /** A card needs this many games with it in the opening hand against an opponent before they rate it there. */
        const val MIN_GAMES = 4

        /**
         * Shootout's reading of a deck against [opponent]: a card's worth per copy in the sided strata (a Side Deck card is
         * only ever dealt there) and in game one's (what the Main Deck is worth before siding: the outs). Only cards some
         * trial has shown are rated.
         */
        fun fromShootout(results: ShootoutResults, opponent: String): List<SideWorth> = results.cards.flatMap { r ->
            r.cells.filter { (s, cell) -> !s.alone && cell.trials > 0 }.map { (s, cell) ->
                val e = cell.estimate
                SideWorth(r.card, opponent, e.value, e.range95.start, e.range95.endInclusive, SHOOTOUT, cell.trials, s.goingFirst)
                    .let { if (s.sided) it else it.copy(source = SHOOTOUT_G1) }
            }
        }

        /** Shootout's game-one reading of a Main Deck card: what it is worth before siding. */
        const val SHOOTOUT_G1 = "shootout-g1"

        /**
         * The games logged against [opponent] with each card in the opening hand, against those without it: the difference in
         * the rate won, with its range (unpaired, Newcombe). Only games whose opening hand was recorded say anything.
         */
        fun fromGames(games: List<TestGame>, opponent: String, canonical: (Int) -> Int = { it }): List<SideWorth> {
            val decided = games.filter { it.opponent == opponent && it.result != TestGame.DRAW && it.opening != null }
            if (decided.isEmpty()) return emptyList()
            val cards = decided.flatMap { g -> g.opening.orEmpty().map(canonical) }.distinct()
            return cards.mapNotNull { c ->
                val with = decided.filter { g -> g.opening.orEmpty().any { canonical(it) == c } }
                val without = decided - with.toSet()
                if (with.size < MIN_GAMES || without.isEmpty()) return@mapNotNull null
                fun rate(list: List<TestGame>) = TestStats.Rate(list.count { it.result == TestGame.WIN }, list.size)
                val d = MatchupLedger.difference(rate(without), rate(with)) ?: return@mapNotNull null
                SideWorth(c, opponent, d.points, d.low, d.high, GAMES, with.size)
            }
        }
    }
}

object SideChooser {
    /** A Side Deck's size. */
    const val SIZE = 15

    /** The most copies one plan brings in: past this a deck stops being itself. */
    const val PLAN_MAX = 6

    /** An opponent of the field and its share (any scale). */
    data class Opponent(val id: String, val name: String, val share: Double)

    /**
     * A card chosen for the Side Deck, how many copies, the numbers it was chosen on, and its [value] to the field: each
     * opponent's worth times the copies its plan brings, weighted by the opponent's share. [kept]: from the Side Deck as it was.
     */
    data class Choice(val card: Int, val copies: Int, val worth: List<SideWorth>, val kept: Boolean = false, val value: Double = 0.0)

    /** One opponent's plan, the same going first and second unless the evidence was read by turn. */
    data class Plan(val opponent: String, val name: String, val first: SidePlan, val second: SidePlan, val why: String)

    data class Suggestion(
        val side: List<Choice>,
        val plans: List<Plan>,
        /** Opponents nothing rates any card against: their plan is the person's to write. */
        val unrated: List<String>,
        /** What the suggestion breaks, in words (a plan that leaves an illegal deck): empty when nothing. */
        val problems: List<String>,
    ) {
        val cards: List<CardId> get() = side.flatMap { c -> List(c.copies) { CardId(c.card) } }
    }

    /**
     * The Side Deck and plans for [deck] against [opponents], from [worth]: against each opponent the [candidates] it rates
     * above zero come in (best first, at most [PLAN_MAX] copies) for as many of the Main Deck's cards it rates below zero
     * (worst first), copy for copy, so every plan keeps the deck its size. The fifteen are the cards brought in, the most
     * copies any plan needs, the field's most valuable first; room left keeps the current Side Deck's cards. [allowed] is a
     * card's copies under the list in force; copies in the Main and Extra Decks count against it.
     */
    fun choose(
        deck: Deck,
        opponents: List<Opponent>,
        worth: List<SideWorth>,
        candidates: Collection<Int>,
        allowed: (Int) -> Int,
        isExtra: (CardId) -> Boolean,
        canonical: (Int) -> Int = { it },
    ): Suggestion {
        val inDeck = (deck.main + deck.extra).groupingBy { canonical(it.value) }.eachCount()
        val mainCards = deck.main.map { canonical(it.value) }.distinct()
        val total = opponents.sumOf { it.share }.takeIf { it > 0 } ?: 1.0
        val pool = candidates.map(canonical).distinct().filter { c -> allowed(c) - (inDeck[c] ?: 0) > 0 }
        fun best(list: List<SideWorth>): SideWorth? = list.maxWithOrNull(compareBy<SideWorth> { it.n }.thenBy { it.source == SideWorth.SHOOTOUT })
        // Per opponent: what comes in and what goes out, copy for copy.
        val wants = HashMap<String, List<Pair<Int, Int>>>()
        val outs = HashMap<String, List<Int>>()
        val unrated = ArrayList<String>()
        for (o in opponents) {
            val mine = worth.filter { it.opponent == o.id }
            val ins = pool.mapNotNull { c -> best(mine.filter { it.card == c && it.source != SideWorth.SHOOTOUT_G1 })?.takeIf { it.points > 0 } }
                .sortedWith(compareByDescending<SideWorth> { it.points }.thenByDescending { it.low })
            val weak = mainCards.mapNotNull { c -> best(mine.filter { it.card == c })?.takeIf { it.points < 0 } }.sortedBy { it.points }
            if (mine.isEmpty()) { unrated += o.id; continue }
            // Copies to bring in, the best first, up to the plan's cap and the cards out there are to make room.
            val outCopies = weak.flatMap { w -> List(inDeck[w.card] ?: 0) { w.card } }
            val room = minOf(PLAN_MAX, outCopies.size)
            val brought = ArrayList<Pair<Int, Int>>()
            var left = room
            for (w in ins) {
                if (left <= 0) break
                val n = minOf(left, allowed(w.card) - (inDeck[w.card] ?: 0))
                if (n > 0) { brought += w.card to n; left -= n }
            }
            val count = brought.sumOf { it.second }
            wants[o.id] = brought
            outs[o.id] = outCopies.take(count)
        }
        // The fifteen: the most copies any plan brings of each card, the field's most valuable first.
        val copies = HashMap<Int, Int>()
        wants.values.flatten().forEach { (c, n) -> copies[c] = maxOf(copies[c] ?: 0, n) }
        val choices = copies.map { (c, n) ->
            val rated = worth.filter { it.card == c && it.source != SideWorth.SHOOTOUT_G1 }
            val value = opponents.sumOf { o -> (o.share / total) * ((wants[o.id]?.firstOrNull { it.first == c }?.second ?: 0) * (best(rated.filter { it.opponent == o.id })?.points ?: 0.0)) }
            Choice(c, n, rated, value = value)
        }.sortedWith(compareByDescending<Choice> { it.value }.thenBy { it.card })
        val chosen = ArrayList<Choice>()
        var room = SIZE
        for (ch in choices) {
            if (room <= 0) break
            val n = minOf(ch.copies, room)
            chosen += ch.copy(copies = n)
            room -= n
        }
        // Room left keeps the current Side Deck's cards, as they are.
        val keptCounts = deck.side.map { canonical(it.value) }.groupingBy { it }.eachCount()
        for ((c, n) in keptCounts.entries.sortedBy { it.key }) {
            if (room <= 0) break
            val have = chosen.firstOrNull { it.card == c }?.copies ?: 0
            val add = minOf(n - have, room, allowed(c) - (inDeck[c] ?: 0) - have)
            if (add > 0) {
                val at = chosen.indexOfFirst { it.card == c }
                if (at >= 0) chosen[at] = chosen[at].copy(copies = chosen[at].copies + add)
                else chosen += Choice(c, add, emptyList(), kept = true)
                room -= add
            }
        }
        // Each plan holds only what the fifteen hold, copy for copy with its outs.
        val side = chosen.associate { it.card to it.copies }
        val newDeck = deck.copy(side = chosen.flatMap { c -> List(c.copies) { CardId(c.card) } })
        val problems = ArrayList<String>()
        val plans = opponents.filter { it.id in wants }.map { o ->
            val ins = wants.getValue(o.id).flatMap { (c, n) -> List(minOf(n, side[c] ?: 0)) { c } }
            val out = outs.getValue(o.id).take(ins.size)
            val plan = SidePlan(out.map(::CardId), ins.take(out.size).map(::CardId))
            SidingMath.legalAfter(newDeck, plan) { isExtra(it) }?.let { problems += "Against ${o.name}: $it" }
            val why = if (plan.sided) "In for what Shootout and your games rate low against them." else "Nothing rated low enough to take out."
            Plan(o.id, o.name, plan, plan, why)
        }
        return Suggestion(chosen, plans, unrated, problems)
    }

    /** The suggestion in words (Ai's `side_choose`, the dialog's lines): each card and why, each plan, what is unrated. */
    fun words(s: Suggestion, name: (Int) -> String, opponent: (String) -> String): String = buildString {
        if (s.side.isEmpty() && s.plans.none { it.first.sided }) {
            append("Nothing rates a card for the field yet: judge hands against each opponent in Shootout, or play games on the Duel page, and the side deck follows the evidence.")
            return@buildString
        }
        appendLine("A Side Deck of ${s.side.sumOf { it.copies }}:")
        s.side.forEach { c ->
            append("- ${c.copies}× ${name(c.card)}")
            if (c.kept) append(" (kept from your Side Deck)")
            c.worth.groupBy { it.opponent }.forEach { (o, ws) ->
                val w = ws.maxBy { it.n }
                append("; ${opponent(o)} ${pts(w.points)} (${pts(w.low)} to ${pts(w.high)}) over ${w.n} ${if (w.source == SideWorth.GAMES) "games" else "judged hands"}")
            }
            appendLine()
        }
        s.plans.forEach { p ->
            fun counted(cards: List<CardId>) = SidingMath.counted(cards).joinToString(", ") { (id, n) -> "$n× ${name(id.value)}" }
            appendLine("Against ${p.name}: " + if (p.first.sided) "out ${counted(p.first.out)}; in ${counted(p.first.into)}." else p.why)
        }
        if (s.unrated.isNotEmpty()) appendLine("Nothing rates a card against ${s.unrated.joinToString(", ") { opponent(it) }}: their plans are yours to write.")
        s.problems.forEach { appendLine(it) }
        append("Numbers are points of win chance with their 95 % ranges; a range across zero is not a verdict. Play the plans out (simulated matches) before keeping them.")
    }

    private fun pts(x: Double): String = x.roundToInt().let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }
}
