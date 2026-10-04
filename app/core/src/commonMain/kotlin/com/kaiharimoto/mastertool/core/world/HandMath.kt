package com.kaiharimoto.mastertool.core.world

import kotlin.random.Random

/**
 * What the hand instruments ask of a deck (1.0.96): a condition on a hand, read once ([Goal]), and its odds counted
 * exactly ([HandCounter]) — any condition, the groups overlapping or not, `or` as well as `and` — with a seeded
 * simulation beside it as the check. The red team found 1.0.95 simulating whenever two groups shared a card, which is
 * the ordinary deck (a starter that is also an extender); exact is cheap at a deck's sizes, so exact is the rule.
 */

/** A condition on a hand: met when all of the clauses of [any] one of its alternatives hold. */
data class Goal(val text: String, val any: List<List<Instruments.Clause>>) {
    /** The groups and cards it names, each once, in the order written. */
    val words: List<String> get() = any.flatten().map { it.word }.distinctBy { it.lowercase() }
}

object Goals {
    private val OPS = Regex("""^(.+?)\s*(>=|<=|==|=|>|<)\s*(\d+)\s*$""")
    private val ENDS = Regex("""(>=|<=|==|=|>|<)\s*\d+\s*$""")

    /** At least: a count no hand reaches, so ">= n" has no ceiling at any hand size. */
    const val NO_MAX = 99

    const val EXAMPLE = "Starters>=1 & Hand traps>=1 | Extenders>=2"

    /**
     * "Starters>=1 & Hand traps>=1", "Starters>=1 | Extenders>=2" (and binds tighter than or), "any(Ash, Imperm)>=1",
     * "Ash Blossom & Joyous Spring>=1" (an `&` or an `and` inside a name is part of it: a clause only ends at its number),
     * "\"Light and Darkness Dragon\"=0", "Bricks<2".
     */
    fun parse(text: String): Goal {
        val alternatives = mutableListOf<MutableList<String>>(mutableListOf())
        val cur = StringBuilder()
        var quoted = false
        var depth = 0
        var i = 0
        fun complete() = ENDS.containsMatchIn(cur)
        fun close(or: Boolean) {
            alternatives.last() += cur.toString()
            cur.clear()
            if (or) alternatives += mutableListOf<String>()
        }
        while (i < text.length) {
            val c = text[i]
            val free = !quoted && depth == 0
            fun at(word: String) = text.regionMatches(i, word, 0, word.length, ignoreCase = true)
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                !quoted && c == '(' -> { depth++; cur.append(c) }
                !quoted && c == ')' -> { depth = maxOf(0, depth - 1); cur.append(c) }
                free && (c == '&' || c == '|') && complete() -> {
                    close(or = c == '|')
                    if (text.getOrNull(i + 1) == c) i++
                }
                free && at(" and ") && complete() -> { close(or = false); i += 4 }
                free && at(" or ") && complete() -> { close(or = true); i += 3 }
                else -> cur.append(c)
            }
            i++
        }
        close(or = false)
        val parts = alternatives.map { a -> a.map { it.trim() }.filter { it.isNotEmpty() } }
        require(parts.any { it.isNotEmpty() }) { "an empty condition: write one like $EXAMPLE" }
        require(parts.all { it.isNotEmpty() }) { "“$text” has an empty side of an or: write one like $EXAMPLE" }
        return Goal(text.trim(), parts.map { all -> all.map(::clause) })
    }

    private fun clause(p: String): Instruments.Clause {
        val m = OPS.find(p) ?: throw IllegalArgumentException(
            "“$p” is not a clause: a group or card, then >=, <=, =, > or <, then a number — like Starters>=1, " +
                "\"Ash Blossom & Joyous Spring\"=0 or any(Ash Blossom & Joyous Spring, Infinite Impermanence)>=1",
        )
        val raw = m.groupValues[1].trim()
        val word = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1).trim() else raw
        require(word.isNotEmpty()) { "“$p” names nothing before its comparison" }
        val n = m.groupValues[3].toInt()
        return when (m.groupValues[2]) {
            ">=" -> Instruments.Clause(word, n, NO_MAX)
            ">" -> Instruments.Clause(word, n + 1, NO_MAX)
            "<=" -> Instruments.Clause(word, 0, n)
            // Fewer than none is no hand at all: max −1 holds for none.
            "<" -> Instruments.Clause(word, 0, n - 1)
            else -> Instruments.Clause(word, n, n)
        }
    }
}

/** A clause of a goal on the [set]th set of cards: between [min] and [max] of them. */
data class Bound(val set: Int, val min: Int, val max: Int)

/**
 * Exact odds over a deck seen as named sets of cards that may overlap. The deck splits into atoms — the cards that
 * belong to exactly the same sets — and a hand is counted atom by atom, keeping for each set only as much of its
 * count as any clause can tell apart. Multivariate hypergeometric, by dynamic programming over those counts: a few
 * thousand states at a deck's sizes, for any condition, `or` included.
 */
class HandCounter private constructor(val size: Int, private val atoms: List<Pair<Long, Int>>, val sets: Int) {
    /** The state space would not fit a Long's key: too many sets with too high counts; simulate instead. */
    class TooBig : IllegalStateException("too many groups at once to count exactly")

    /** The mask of sets each card of the deck belongs to. */
    private var cards: LongArray = LongArray(0)

    /**
     * P(the hand meets [goal]) for a hand of [hand] (more than the deck draws the deck). [goal] is any of its lists,
     * each all of its bounds.
     */
    fun probability(goal: List<List<Bound>>, hand: Int): Double {
        val h = hand.coerceIn(0, size)
        val cap = IntArray(sets)
        goal.flatten().forEach { b ->
            cap[b.set] = maxOf(cap[b.set], b.min.coerceAtMost(h + 1), if (b.max < h) b.max + 1 else 0).coerceIn(0, h + 1)
        }
        var p = 0.0
        walk(h, cap).forEach { (counts, w) -> if (meets(counts, goal)) p += w }
        return p.coerceIn(0.0, 1.0)
    }

    /** Every hand of [hand] by how many of each set it holds (exact counts, not capped), with its chance. */
    fun distribution(hand: Int): List<Pair<IntArray, Double>> {
        val h = hand.coerceIn(0, size)
        return walk(h, IntArray(sets) { h })
    }

    private fun walk(h: Int, cap: IntArray): List<Pair<IntArray, Double>> {
        // Mixed radix: drawn so far in base h + 1, then each set's capped count in base cap + 1.
        val base = IntArray(sets + 1) { if (it == 0) h + 1 else cap[it - 1] + 1 }
        val mul = LongArray(sets + 1)
        var m = 1L
        for (i in base.indices) {
            mul[i] = m
            if (m > Long.MAX_VALUE / base[i]) throw TooBig()
            m *= base[i]
        }
        fun digit(key: Long, i: Int) = ((key / mul[i]) % base[i]).toInt()
        var states = HashMap<Long, Double>().apply { put(0L, 1.0) }
        for ((mask, copies) in atoms) {
            val next = HashMap<Long, Double>(states.size * 2)
            for ((key, ways) in states) {
                val drawn = digit(key, 0)
                for (k in 0..minOf(copies, h - drawn)) {
                    var nk = key + k * mul[0]
                    if (k > 0) {
                        var bits = mask
                        while (bits != 0L) {
                            val s = bits.countTrailingZeroBits()
                            bits = bits and (bits - 1)
                            val d = digit(key, s + 1)
                            val up = minOf(cap[s], d + k)
                            nk += (up - d) * mul[s + 1]
                        }
                    }
                    val w = ways * binomial(copies, k)
                    next[nk] = (next[nk] ?: 0.0) + w
                }
            }
            states = next
        }
        val all = binomial(size, h)
        return states.mapNotNull { (key, ways) ->
            if (digit(key, 0) != h) null else IntArray(sets) { digit(key, it + 1) } to ways / all
        }
    }

    /** Hands met by [goal] out of [trials] seeded shuffles of the deck: the check on [probability]. */
    fun simulate(goal: List<List<Bound>>, hand: Int, trials: Int, seed: Long): Int {
        val h = hand.coerceIn(0, size)
        val random = Random(seed)
        val deck = cards.copyOf()
        val counts = IntArray(sets)
        var hits = 0
        repeat(trials) {
            counts.fill(0)
            // A partial Fisher–Yates over whatever order the last trial left: each draw is still a fair one.
            for (i in 0 until h) {
                val j = i + random.nextInt(deck.size - i)
                val t = deck[i]
                deck[i] = deck[j]
                deck[j] = t
                // The card drawn is the one now at i (counting the one swapped out was a bias the self-check caught).
                var bits = deck[i]
                while (bits != 0L) {
                    val s = bits.countTrailingZeroBits()
                    bits = bits and (bits - 1)
                    counts[s]++
                }
            }
            if (meets(counts, goal)) hits++
        }
        return hits
    }

    companion object {
        const val MAX_SETS = 62

        /** [deck]'s cards by name, and the named [sets] a condition reads. */
        fun of(deck: List<String>, sets: List<Set<String>>): HandCounter {
            require(sets.size <= MAX_SETS) { "at most $MAX_SETS groups and cards at once" }
            val masks = deck.map { name -> sets.foldIndexed(0L) { i, acc, s -> if (name in s) acc or (1L shl i) else acc } }
            val atoms = masks.groupingBy { it }.eachCount().entries.sortedBy { it.key }.map { it.key to it.value }
            return HandCounter(deck.size, atoms, sets.size).also { it.cards = masks.toLongArray() }
        }

        /** A deck of [counts] cards in disjoint sets and the rest in none, [size] in all: the optimiser's abstract deck. */
        fun ofCounts(counts: List<Int>, size: Int): HandCounter {
            require(counts.size <= MAX_SETS) { "at most $MAX_SETS roles" }
            val rest = size - counts.sum()
            require(rest >= 0) { "the roles hold more cards than the deck" }
            val atoms = counts.mapIndexed { i, n -> (1L shl i) to n }.filter { it.second > 0 } + (0L to rest)
            val cards = counts.flatMapIndexed { i, n -> List(n) { 1L shl i } } + List(rest) { 0L }
            return HandCounter(size, atoms, counts.size).also { it.cards = cards.toLongArray() }
        }

        fun meets(counts: IntArray, goal: List<List<Bound>>): Boolean =
            goal.any { all -> all.all { b -> counts[b.set] >= b.min && counts[b.set] <= b.max } }

        fun binomial(n: Int, k: Int): Double = com.kaiharimoto.mastertool.core.hand.HandOdds.binomial(n, k)
    }
}

/**
 * Exact odds over a deck of disjoint roles and the rest (the optimiser's question, 1.0.97): which hands meet a goal
 * depends only on how many of each role they hold, so the hands that meet it are listed once ([hits]) and every deck
 * the optimiser tries is a sum over that list — ∏ C(nᵢ, kᵢ) · C(rest, h − Σk) / C(N, h). [words] are the goal's words
 * as sets of role indices (a role, or `any(…)` of several).
 */
class RoleOdds(val roles: Int, private val words: List<Set<Int>>, goal: List<List<Bound>>, val hand: Int) {
    /** Every count of each role a hand of [hand] can hold that meets the goal. */
    val hits: List<IntArray>

    init {
        val out = mutableListOf<IntArray>()
        val k = IntArray(roles)
        fun rec(i: Int, left: Int) {
            if (i == roles) {
                val counts = IntArray(words.size) { w -> words[w].sumOf { k[it] } }
                if (HandCounter.meets(counts, goal)) out += k.copyOf()
                return
            }
            for (x in 0..left) {
                k[i] = x
                rec(i + 1, left - x)
            }
            k[i] = 0
        }
        rec(0, hand)
        hits = out
    }

    /** P(a hand of [hand] from a deck of [size] holding [counts] of each role meets the goal). */
    fun probability(counts: IntArray, size: Int): Double {
        val rest = size - counts.sum()
        if (rest < 0 || size < hand) return 0.0
        var p = 0.0
        for (k in hits) {
            var w = HandCounter.binomial(rest, hand - k.sum())
            var i = 0
            while (w != 0.0 && i < k.size) {
                w *= HandCounter.binomial(counts[i], k[i])
                i++
            }
            p += w
        }
        return (p / HandCounter.binomial(size, hand)).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Compositions of at most [hand] into [roles] parts: the size of [hits] at worst. */
        fun compositions(roles: Int, hand: Int): Long = (1..roles).fold(1L) { a, i -> a * (hand + i) / i }
    }
}

/**
 * Whether a hand holds what a combo needs, each need filled by a different card ([fits], the simulation's check), and
 * the same question as clauses on counts ([hall]) so it can be counted exactly: by Hall's theorem a hand fills every
 * need exactly when, for each set of needs, it holds at least as many cards that could fill one of them as there are
 * needs in the set.
 */
object Matching {
    fun fits(hand: List<String>, needs: List<Set<String>>): Boolean {
        val order = needs.sortedBy { s -> hand.count { it in s } }
        val used = BooleanArray(hand.size)
        fun go(i: Int): Boolean {
            if (i == order.size) return true
            for (j in hand.indices) {
                if (!used[j] && hand[j] in order[i]) {
                    used[j] = true
                    if (go(i + 1)) return true
                    used[j] = false
                }
            }
            return false
        }
        return go(0)
    }

    /**
     * [needs] (each a set of cards that would do, with how many of it) as at-least clauses on unions of them: one per
     * need when no two overlap, else one per subset of the distinct needs (Hall's condition), the same union kept once
     * at its largest count. At most [MAX_OVERLAPPING] distinct needs that overlap, or the clauses outgrow the counter.
     */
    fun hall(needs: List<Pair<Set<String>, Int>>): List<Pair<Set<String>, Int>> {
        val disjoint = needs.indices.all { i -> (i + 1 until needs.size).all { j -> needs[i].first.intersect(needs[j].first).isEmpty() } }
        if (disjoint) return needs
        require(needs.size <= MAX_OVERLAPPING) {
            "a combo with more than $MAX_OVERLAPPING different needs that share cards cannot be counted exactly: name fewer, or name cards rather than groups"
        }
        val out = LinkedHashMap<Set<String>, Int>()
        for (mask in 1 until (1 shl needs.size)) {
            val union = needs.indices.filter { mask and (1 shl it) != 0 }.flatMap { needs[it].first }.toSet()
            val n = needs.indices.filter { mask and (1 shl it) != 0 }.sumOf { needs[it].second }
            out[union] = maxOf(out[union] ?: 0, n)
        }
        return out.entries.map { it.key to it.value }
    }

    const val MAX_OVERLAPPING = 5
}
