package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.text.NameScore
import com.kaiharimoto.mastertool.core.model.Card

/**
 * What to write next for a deck (D.md §3.1, "Suggestions, never actions"): ranked, never written — the Effects app and the
 * chat show the top ones with one Write these, and nothing happens without the person's go.
 *
 * 1. the cards the deck's saved combos use with no effect yet (or one to repair), by how many combos use each;
 * 2. the cards of the groups that read as the deck's engine (Starters, Extenders, Engine…);
 * 3. the Main Deck's most-played cards with no effect yet, by copies;
 * 4. scripts that are failing, warned or broken, to repair.
 *
 * A card already written (verified, or written and checked) is never suggested, and nor is a Normal Monster. Every printing
 * counts as its card (`CardIdentity`, through [canonical]).
 */
object FxSuggest {
    enum class Why { COMBO, ENGINE, COPIES, REPAIR }

    /**
     * One suggestion: [count] is the combos that use it ([Why.COMBO]) or the copies the deck plays ([Why.COPIES]); [opens] is
     * the share of opening hands of five that hold it (Phase G: written, it would play in that many more hands).
     */
    data class Pick(val card: Int, val why: Why, val count: Int = 0, val opens: Double? = null) {
        fun words(): String = when (why) {
            Why.COMBO -> "used by $count combo${if (count == 1) "" else "s"}"
            Why.ENGINE -> "in the engine's groups"
            Why.COPIES -> "$count ${if (count == 1) "copy" else "copies"} in the Main Deck" + (opens?.let { ": in ${percent(it)} of opening hands" } ?: "")
            Why.REPAIR -> "its script needs repair"
        }

        private fun percent(p: Double): String = "${kotlin.math.round(p * 100).toInt()} %"
    }

    /** The share of opening hands of [hand] cards from a deck of [size] holding at least one of [copies]: exact. */
    fun opens(copies: Int, size: Int, hand: Int = 5): Double {
        if (copies <= 0 || size <= 0) return 0.0
        var none = 1.0
        for (i in 0 until hand.coerceAtMost(size)) none *= (size - copies - i).coerceAtLeast(0).toDouble() / (size - i)
        return 1 - none
    }

    /** Group names that read as a deck's engine: the Groups panel's roles a person draws for the cards that make plays. */
    val ENGINE_WORDS = listOf("starter", "extender", "engine", "combo", "searcher", "enabler", "payoff", "core", "line")

    /** The cards of [groups] whose name reads as the engine ([ENGINE_WORDS]), canonical. */
    fun engine(groups: DeckGroups, canonical: (Int) -> Int = { it }): Set<Int> {
        val ids = groups.groups.filter { g -> ENGINE_WORDS.any { w -> w in g.name.lowercase() } }.map { it.id }.toSet()
        if (ids.isEmpty()) return emptySet()
        return groups.assignments.filterValues { it in ids }.keys.mapTo(LinkedHashSet()) { canonical(it.value) }
    }

    /**
     * The cards [combo] uses, among [cards] (the deck's): its needs, each read as the command line reads a name
     * ([NameScore], the best card at word level or better), and every card whose full name its steps say — as a recorded
     * combo writes them. Canonical passcodes.
     */
    fun comboCards(combo: Combo, cards: Collection<Card>): Set<Int> {
        val out = LinkedHashSet<Int>()
        combo.needs.forEach { need ->
            cards.map { it to NameScore.of(need, it.name) }.filter { it.second >= 70 }.maxByOrNull { it.second }?.let { out += it.first.id.value }
        }
        val steps = combo.steps.joinToString("\n") { it.lowercase() }
        cards.forEach { c -> if (c.name.length >= 3 && c.name.lowercase() in steps) out += c.id.value }
        return out
    }

    /**
     * The ranked suggestions for a deck of [main] and [extra] (any printings; copies counted), [combos] (each the cards one
     * combo uses), [engine] (the engine's cards), at most [limit]. [status] is the library's word on a card.
     */
    fun of(
        main: List<Int>,
        extra: List<Int>,
        combos: List<Collection<Int>>,
        engine: Collection<Int>,
        status: (Int) -> FxStatus,
        canonical: (Int) -> Int = { it },
        limit: Int = 12,
    ): List<Pick> {
        val deckMain = main.map(canonical)
        val deck = (deckMain + extra.map(canonical)).distinct()
        val inDeck = deck.toSet()
        val st = HashMap<Int, FxStatus>()
        fun s(c: Int) = st.getOrPut(c) { status(c) }
        fun missing(c: Int) = s(c) == FxStatus.MISSING
        fun repair(c: Int) = s(c) == FxStatus.FAILING || s(c) == FxStatus.WARNED || s(c) == FxStatus.BROKEN
        fun wanted(c: Int) = missing(c) || repair(c)
        val out = LinkedHashMap<Int, Pick>()
        // 1. The combos' cards, by how many combos use each; ties in the deck's order.
        val uses = HashMap<Int, Int>()
        combos.forEach { combo -> combo.map(canonical).toSet().forEach { c -> if (c in inDeck) uses[c] = (uses[c] ?: 0) + 1 } }
        uses.entries.filter { wanted(it.key) }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { deck.indexOf(it.key) })
            .forEach { (c, n) -> out.getOrPut(c) { Pick(c, Why.COMBO, n) } }
        // 2. The engine's groups.
        engine.map(canonical).distinct().filter { it in inDeck && wanted(it) }.sortedBy { deck.indexOf(it) }
            .forEach { c -> out.getOrPut(c) { Pick(c, Why.ENGINE) } }
        // 3. The Main Deck by copies, cards with nothing written yet.
        deckMain.groupingBy { it }.eachCount().entries.filter { missing(it.key) }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { deck.indexOf(it.key) })
            .forEach { (c, n) -> out.getOrPut(c) { Pick(c, Why.COPIES, n, opens(n, deckMain.size)) } }
        // 4. What is left to repair.
        deck.filter { repair(it) }.forEach { c -> out.getOrPut(c) { Pick(c, Why.REPAIR) } }
        return out.values.take(limit)
    }
}
