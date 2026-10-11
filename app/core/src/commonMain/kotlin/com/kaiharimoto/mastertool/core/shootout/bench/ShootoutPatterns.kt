package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Decks
import com.kaiharimoto.mastertool.core.shootout.model.DeckList
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.HandRates
import com.kaiharimoto.mastertool.core.shootout.model.Pool
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import kotlin.math.abs
import kotlin.math.round

/*
 * What the hands say (Phase S, S4.2; `docs/phases/S.md` §5, §8): which kinds of opening hand win, first and second, and what
 * each siding plan does on the hands it leaves you with — both read off the same fit as every card's number, over hands dealt
 * as a real shuffle would. Nothing here is a new answer or a new parameter: a pattern is a way of sorting the hands the model
 * already values, so every number opens the trials that show such a hand.
 */

/** How a pattern sorts a hand: by what kind of hand it is, how many of one role it opens, or which roles it opens. */
enum class PatternFamily { KIND, ROLE, MIX }

/** One opening pattern: [key] stable within a bench, [words] as the screen says it. */
data class HandPattern(val key: String, val family: PatternFamily, val words: String)

/**
 * A pattern's numbers in one stratum: [rate] the win rate over its hands, [lift] that less the stratum's (the same hands
 * read whole), [share] how often a real hand is one, [trials] the kept hands showing one.
 */
class PatternCell(val rate: Estimate, val lift: Estimate, val share: Double, val trials: Int)

/**
 * One pattern across the strata it is common enough in; [shares] is how often a real hand is one in every stratum read, so a
 * missing cell can say whether it is rare or nearly every hand.
 */
class PatternRow(val pattern: HandPattern, val cells: Map<Stratum, PatternCell>, val shares: Map<Stratum, Double> = emptyMap())

/**
 * A siding plan's worth in one sided stratum: [yours] the win rate on the hands your plan leaves you less on game one's deck,
 * against their sided deck; [theirs] the win rate against their sided deck less against their game-one deck, with yours sided
 * (negative: their plan costs you). Null where the plan changes no Main Deck card. [yourCards] and [theirCards] are what each
 * plan moves (canonical passcodes), for the trials behind each number.
 */
class PlanResult(
    val stratum: Stratum,
    val yours: Estimate?,
    val theirs: Estimate?,
    val yourCards: Set<Int>,
    val theirCards: Set<Int>,
    val yourTrials: Int,
    val theirTrials: Int,
)

/**
 * Sorts hands into [HandPattern]s for one bench: the kind of hand (a starter of yours; interaction of theirs — the same
 * questions as Ai's confidence score, [com.kaiharimoto.mastertool.core.shootout.teach.HandKinds]), how many cards of each
 * role it opens (none, one, two or more), and which roles it opens together. Roles are the deck's groups; with none, only
 * the kind sorts.
 */
class PatternSorter(private val bench: Bench) {

    private val roles: IntArray = bench.spec.roles
    private val starter: BooleanArray = BooleanArray(bench.own.size) { bench.own[it] in bench.kinds.starters }
    private val interaction: BooleanArray = BooleanArray(bench.theirs.size) { bench.theirs[it] in bench.kinds.interaction }

    /** Whether the deck's groups make more than one role: only then do role counts and mixes sort anything. */
    val roleful: Boolean = bench.roleNames.size > 1

    /** The patterns of a hand of the model, by its opening five, against [opp] (their sixth on your turn never counts). */
    fun keys(hand: Hand, opp: Hand?): List<String> {
        val holds = opp?.let { o -> o.cards.any { c -> o.opened(c) > 0 && interaction[c] } }
        return keysWith(hand, if (bench.alone) null else holds)
    }

    /** The patterns of [hand], [interaction] said from outside: whether the opponent's hand held any, null for the deck alone. */
    fun keysWith(hand: Hand, interaction: Boolean?): List<String> {
        val counts = IntArray(bench.roleNames.size)
        var starts = false
        for (c in hand.cards) {
            val opened = hand.opened(c)
            if (opened <= 0) continue
            counts[roles[c]] += opened
            if (starter[c]) starts = true
        }
        val out = ArrayList<String>(counts.size + 2)
        out += kindKey(starts, interaction)
        if (roleful) {
            counts.forEachIndexed { r, n -> out += roleKey(r, n) }
            out += mixKey(counts.indices.filter { counts[it] > 0 })
        }
        return out
    }

    /** Every pattern a kept trial shows, either hand of a comparison's. */
    fun keysOf(t: StoredTrial): Set<String> {
        val interaction = if (bench.alone) null else bench.kindOf(t)?.interaction
        val shown = if (t.kind == StoredTrial.COMPARE) listOf(t.left to t.leftSixth, t.right to t.rightSixth) else listOf(t.hand to t.sixth)
        return shown.flatMap { (ids, sixth) -> bench.hand(ids, sixth)?.let { keysWith(it, interaction) }.orEmpty() }.toSet()
    }

    /** Whether a kept trial shows a hand of pattern [key] in [stratum]. */
    fun matches(t: StoredTrial, key: String, stratum: Stratum): Boolean = t.stratum == stratum.name && key in keysOf(t)

    /** The words of pattern [key]. */
    fun pattern(key: String): HandPattern? {
        val parts = key.split(':')
        return when (parts.firstOrNull()) {
            KIND -> {
                val bits = parts.getOrNull(1)?.split('·') ?: return null
                val starts = bits[0] == "starter"
                val interaction = when (bits.getOrNull(1)) { "interaction" -> true; "clear" -> false; else -> null }
                HandPattern(key, PatternFamily.KIND, kindWords(starts, interaction))
            }
            ROLE -> {
                val r = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val n = parts.getOrNull(2)?.toIntOrNull() ?: return null
                val name = bench.roleNames.getOrNull(r) ?: return null
                HandPattern(key, PatternFamily.ROLE, "$name: " + when (n) { 0 -> "none"; 1 -> "one"; else -> "two or more" })
            }
            MIX -> {
                val names = parts.getOrNull(1)?.split(',')?.mapNotNull { it.toIntOrNull()?.let(bench.roleNames::getOrNull) } ?: return null
                HandPattern(key, PatternFamily.MIX, names.joinToString(" + "))
            }
            else -> null
        }
    }

    /** Every kind's key in the order they stand: a starter first, interaction from them after none. */
    fun kinds(): List<String> = listOf(true, false).flatMap { starts ->
        if (bench.alone) listOf(kindKey(starts, null)) else listOf(false, true).map { kindKey(starts, it) }
    }

    /** Every role count's key in the order they stand: the groups' order, none to two or more. */
    fun roleCounts(): List<String> = if (!roleful) emptyList() else bench.roleNames.indices.flatMap { r -> (0..2).map { roleKey(r, it) } }

    companion object {
        const val KIND = "kind"
        const val ROLE = "role"
        const val MIX = "mix"

        fun kindKey(starts: Boolean, interaction: Boolean?): String =
            "$KIND:" + (if (starts) "starter" else "none") + (interaction?.let { if (it) "·interaction" else "·clear" } ?: "")

        fun roleKey(role: Int, count: Int): String = "$ROLE:$role:${count.coerceAtMost(2)}"

        fun mixKey(roles: List<Int>): String = "$MIX:" + roles.sorted().joinToString(",")

        fun kindWords(starts: Boolean, interaction: Boolean?): String =
            (if (starts) "With a starter" else "No starter") + when (interaction) {
                true -> ", interaction from them"
                false -> ", no interaction from them"
                null -> ""
            }
    }
}

/**
 * Opening patterns and siding plans, read from a run (S4.2): each pattern's win rate in each stratum it is common in, against
 * the stratum's own, and each sided stratum's plans compared on the hands they leave. One pool of [POOL] hands a stratum,
 * dealt from a fixed seed, so the results are the same twice.
 */
object ShootoutPatterns {
    /** Hands dealt per stratum: a pattern holding a twentieth of them still stands on two hundred. */
    const val POOL = 4000

    /**
     * A pattern's cell is shown only when this share of real hands is one, and this share is not: rarer hands say little and
     * crowd the table, and a pattern nearly every hand is is the situation itself.
     */
    const val MIN_SHARE = 0.03

    /** At most this many mixes of roles: the commonest across the strata. */
    const val MIXES = 6

    private const val SEED = 0x5A4D_2L

    /** Patterns and plans for [run]. */
    fun read(run: ShootoutRun, trials: List<StoredTrial>): Pair<List<PatternRow>, List<PlanResult>> {
        val bench = run.bench
        val sorter = PatternSorter(bench)
        val rates = HandRates(bench.spec)
        val fit = run.fit
        val sorted = bench.strata.associateWith { st ->
            rates.sorted(fit.theta, st, Pool.deal(bench.decks, st, POOL, SEED + st.ordinal), sorter::keys)
        }
        val shown = trials.groupBy { it.stratum }.mapValues { (_, ts) -> ts.map(sorter::keysOf) }
        val rows = rows(sorter, sorted, fit, shown)
        val plans = plans(run, rates, trials)
        return rows to plans
    }

    private fun rows(sorter: PatternSorter, sorted: Map<Stratum, Map<String, HandRates.Rate>>, fit: Fit, shown: Map<String, List<Set<String>>>): List<PatternRow> {
        fun share(st: Stratum, key: String): Double {
            val m = sorted.getValue(st)
            val all = m.getValue(HandRates.ALL).hands
            return if (all == 0) 0.0 else (m[key]?.hands ?: 0).toDouble() / all
        }
        val mixes = sorted.values.flatMap { it.keys }.filter { it.startsWith(PatternSorter.MIX + ":") }.distinct()
            .filter { k -> sorted.keys.any { share(it, k) >= MIN_SHARE } }
            .sortedByDescending { k -> sorted.keys.sumOf { share(it, k) } }.take(MIXES)
        val keys = sorter.kinds() + sorter.roleCounts() + mixes
        return keys.mapNotNull { key ->
            val pattern = sorter.pattern(key) ?: return@mapNotNull null
            val cells = LinkedHashMap<Stratum, PatternCell>()
            sorted.forEach { (st, m) ->
                val rate = m[key] ?: return@forEach
                val whole = m.getValue(HandRates.ALL)
                val sh = share(st, key)
                // Nearly every hand, like nearly none, says nothing the situation's own rate does not.
                if (sh < MIN_SHARE || sh > 1 - MIN_SHARE) return@forEach
                cells[st] = PatternCell(
                    rate.estimate(fit), HandRates.difference(rate, whole, fit, nested = true), sh,
                    shown[st.name].orEmpty().count { key in it },
                )
            }
            if (cells.isEmpty()) null else PatternRow(pattern, cells, sorted.keys.associateWith { share(it, key) })
        }
    }

    private fun plans(run: ShootoutRun, rates: HandRates, trials: List<StoredTrial>): List<PlanResult> {
        val bench = run.bench
        val fit = run.fit
        return bench.strata.filter { it.sided }.map { st ->
            val game1 = if (st.goingFirst) Stratum.G1_FIRST else Stratum.G1_SECOND
            val ownSided = bench.decks.own(st)
            val ownGame1 = bench.decks.own(game1)
            val theirSided = bench.decks.theirs(st)
            val theirGame1 = bench.decks.theirs(game1)
            fun pool(own: DeckList, theirs: DeckList?) = Pool.deal(Decks(mapOf(st to own), theirs?.let { mapOf(st to it) } ?: emptyMap()), st, POOL, SEED + 17 + st.ordinal)
            val sided = rates.all(fit.theta, st, pool(ownSided, theirSided))
            val yourCards = moved(ownGame1, ownSided, bench.own)
            val theirCards = if (theirSided != null && theirGame1 != null) moved(theirGame1, theirSided, bench.theirs) else emptySet()
            val yours = if (yourCards.isEmpty()) null else HandRates.difference(sided, rates.all(fit.theta, st, pool(ownGame1, theirSided)), fit)
            val theirs = if (theirCards.isEmpty()) null else HandRates.difference(sided, rates.all(fit.theta, st, pool(ownSided, theirGame1)), fit)
            PlanResult(
                st, yours, theirs, yourCards, theirCards,
                trials.count { planShows(it, st, yourCards, theirs = false, bench::canonical) },
                trials.count { planShows(it, st, theirCards, theirs = true, bench::canonical) },
            )
        }
    }

    /** The cards whose copies differ between two decks over one numbering, as passcodes. */
    private fun moved(a: DeckList, b: DeckList, numbering: List<Int>): Set<Int> =
        numbering.indices.filter { a[it] != b[it] }.map { numbering[it] }.toSet()

    /**
     * Whether a kept trial stands behind a plan's number in [stratum]: dealt that turn, in game one or after siding, its hand
     * (or, for [theirs], the opponent's) holding a card the plan moves — the hands that tell the model what those cards are worth.
     */
    fun planShows(t: StoredTrial, stratum: Stratum, cards: Set<Int>, theirs: Boolean, canon: (Int) -> Int = { it }): Boolean {
        val turn = Stratum.entries.firstOrNull { it.name == t.stratum } ?: return false
        if (turn.alone || turn.goingFirst != stratum.goingFirst || cards.isEmpty()) return false
        val held = if (theirs) listOf(t.opponent.orEmpty()) else t.hands
        return held.any { h -> h.any { canon(it) in cards } }
    }

    /**
     * A lift smaller than this, in points, is never called however sure: a pattern holding nine hands in ten sits within a
     * point of its situation and is "clear of zero" by its sheer number of hands, which says nothing a player can use.
     */
    const val MIN_LIFT = 3.0

    /**
     * The patterns the hands have called: lifts clear of zero at 95 % with Holm over every cell, so a table of forty calls none
     * by chance, and at least [MIN_LIFT] points either way.
     */
    fun called(rows: List<PatternRow>, alpha: Double = ShootoutResults.CALL_ALPHA): Set<Pair<String, Stratum>> {
        val tests = rows.flatMap { r -> r.cells.map { (st, c) -> Triple(r.pattern.key, st, c.lift) } }
        val kept = ShootoutResults.holm(tests.map { ShootoutResults.pValue(it.third) }, alpha)
        return tests.filterIndexed { i, t -> kept[i] && abs(t.third.value) >= MIN_LIFT }.map { it.first to it.second }.toSet()
    }
}

/** The patterns' and plans' words (S4.2), for the screen and for Ai. */
object PatternWords {

    /** A pattern's line: "With a starter, no interaction from them: 64% (95 %: 55 to 73), 12 points over the matchup's 52%; 31% of hands". */
    fun cell(pattern: HandPattern, c: PatternCell, alone: Boolean): String =
        "${pattern.words}: ${whole(c.rate.value)}% (95 %: ${whole(c.rate.range95.start)} to ${whole(c.rate.range95.endInclusive)}), " +
            lift(c, alone) + "; ${ShootoutWords.percent(c.share)} of hands"

    /** How a pattern stands against its stratum, in words. */
    fun lift(c: PatternCell, alone: Boolean): String {
        val by = abs(round(c.lift.value)).toInt()
        val base = whole(c.rate.value - c.lift.value)
        val of = if (alone) "the deck's" else "the matchup's"
        val points = if (by == 1) "1 point" else "$by points"
        return when {
            by == 0 -> "level with $of $base%"
            c.lift.value > 0 -> "$points over $of $base%"
            else -> "$points under $of $base%"
        }
    }

    /**
     * The headline of one stratum: the called patterns that win most and least ("Going first — wins most: With a starter, no
     * interaction from them, 71%, 14 points over the matchup's 57%"), or null with none called.
     */
    fun headline(rows: List<PatternRow>, stratum: Stratum, called: Set<Pair<String, Stratum>>, alone: Boolean): String? {
        val mine = rows.mapNotNull { r -> r.cells[stratum]?.takeIf { (r.pattern.key to stratum) in called }?.let { r.pattern to it } }
        val best = mine.filter { it.second.lift.value > 0 }.maxByOrNull { it.second.lift.value }
        val worst = mine.filter { it.second.lift.value < 0 }.minByOrNull { it.second.lift.value }
        val parts = listOfNotNull(
            best?.let { (p, c) -> "wins most: ${p.words}, ${whole(c.rate.value)}%, ${lift(c, alone)}" },
            worst?.let { (p, c) -> "wins least: ${p.words}, ${whole(c.rate.value)}%, ${lift(c, alone)}" },
        )
        return if (parts.isEmpty()) null else ShootoutWords.stratum(stratum) + " — " + parts.joinToString("; ") + "."
    }

    /** A plan's two numbers, a sentence each: in points of your win rate, signed, with their 95 % ranges. */
    fun plan(p: PlanResult, opponent: String): List<String> {
        val turn = ShootoutWords.where(p.stratum).removePrefix("after siding, ")
        return listOfNotNull(
            p.yours?.let { "Your plan $turn: ${ShootoutWords.range(it)} on the hands it leaves you, against keeping game one's deck" },
            p.theirs?.let { "$opponent's plan, you $turn: ${ShootoutWords.range(it)} on your win rate, against their game one's deck" },
        )
    }

    /** Everything in words, for Ai (`shootout_state`): the called patterns' headlines and each plan. Empty with nothing. */
    fun describe(rows: List<PatternRow>, plans: List<PlanResult>, strata: List<Stratum>, alone: Boolean, opponent: String?): String = buildString {
        val called = ShootoutPatterns.called(rows)
        strata.forEach { st -> headline(rows, st, called, alone)?.let { appendLine("- $it") } }
        if (opponent != null) plans.forEach { p -> plan(p, opponent).forEach { appendLine("- $it") } }
    }.trim()

    private fun whole(x: Double): Int = round(x).toInt()
}
