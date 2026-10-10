package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.world.CardText
import com.kaiharimoto.mastertool.core.world.Bound
import com.kaiharimoto.mastertool.core.world.Goals
import com.kaiharimoto.mastertool.core.world.HandCounter

/**
 * A deck's questions answered where it is built (Phase G, G.3; mockup C): each [HandGoal] counted exactly going first and
 * going second — its asks and its condition together — and a card's −1 and +1 beside the deck as it is, so the copy count
 * is decided by what it does to the questions the deck carries.
 *
 * A condition's words are the instruments' ([Goals.parse]): a group by name, "Ungrouped", a card by name, `any(a, b)`.
 * Counted by [HandCounter] over the deck's cards, the groups overlapping or not.
 */
object GoalCount {
    /** A goal's chance going first (five cards) and going second (six). */
    data class Odds(val first: Double, val second: Double)

    /** −1, the deck as it is, +1: null where the step is not one (no copy to cut). */
    data class Steps(val less: Odds?, val now: Odds, val more: Odds?)

    /** The word for cards in no group. */
    const val UNGROUPED = "Ungrouped"

    /**
     * [goal] over the Main Deck [main] with [groups], [name] naming a card: going first and second. A condition that does not
     * read, or names a word that is no group or card, throws with the reason ([problem] says it without throwing).
     */
    fun odds(
        goal: HandGoal,
        main: List<CardId>,
        groups: DeckGroups,
        name: (CardId) -> String?,
        searchers: ((Set<CardId>) -> Set<CardId>)? = null,
    ): Odds {
        val counted = Counted(goal, main, groups, name, searchers)
        return Odds(counted.at(5), counted.at(6))
    }

    /** [goal]'s odds with one copy of [card] cut and one more added, beside the deck as it is. */
    fun steps(goal: HandGoal, main: List<CardId>, card: CardId, groups: DeckGroups, name: (CardId) -> String?): Steps {
        val now = odds(goal, main, groups, name)
        val i = main.lastIndexOf(card)
        val less = if (i < 0) null else odds(goal, main.toMutableList().also { it.removeAt(i) }, groups, name)
        val more = odds(goal, main + card, groups, name)
        return Steps(less, now, more)
    }

    /**
     * What searches what, read off the cards' text as `card_web` reads it (B3): for a set of targets, the cards of [deck] whose
     * text searches one of them from the Deck. For [odds]' `searchers`, so `reach(X)` can be counted.
     */
    fun searchersIn(deck: List<CardId>, cards: (CardId) -> Card?): (Set<CardId>) -> Set<CardId> = { targets ->
        val wanted = targets.mapNotNull(cards)
        deck.distinct().filterTo(HashSet()) { id ->
            id !in targets && cards(id)?.let { a ->
                CardText.links(a).any { l -> l.verb == "searches" && l.filter.specific && wanted.any { b -> l.filter.matches(b) } }
            } == true
        }
    }

    /** Why [goal]'s condition cannot be counted on this deck, or null when it can. */
    fun problem(
        goal: HandGoal,
        main: List<CardId>,
        groups: DeckGroups,
        name: (CardId) -> String?,
        searchers: ((Set<CardId>) -> Set<CardId>)? = null,
    ): String? =
        if (goal.condition.isBlank()) null
        else runCatching { Counted(goal, main, groups, name, searchers) }.exceptionOrNull()?.let { it.message ?: "The condition does not read." }

    /** The goal laid over the deck once: the sets its words name, the branches as bounds on them, and the counter. */
    private class Counted(
        goal: HandGoal,
        main: List<CardId>,
        groups: DeckGroups,
        private val name: (CardId) -> String?,
        private val searchers: ((Set<CardId>) -> Set<CardId>)? = null,
    ) {
        private val counter: HandCounter
        private val branches: List<List<Bound>>

        init {
            // The asks: each a bound on its group's cards (or the ungrouped ones), held in every branch.
            val asks = goal.asks.filterValues { it.constrains }.map { (group, ask) -> Word.Group(group) to (ask.min to ask.max) }
            val alternatives: List<List<Pair<Word, Pair<Int, Int>>>> =
                if (goal.condition.isBlank()) listOf(emptyList())
                else Goals.parse(goal.condition).any.map { all -> all.map { c -> Word.Named(c.word) to (c.min to c.max) } }
            val words = (asks.map { it.first } + alternatives.flatten().map { it.first }).distinct()
            val sets = words.map { w -> members(w, main, groups) }
            val index = words.withIndex().associate { it.value to it.index }
            branches = alternatives.map { alt -> (asks + alt).map { (w, b) -> Bound(index.getValue(w), b.first, b.second) } }
            counter = HandCounter.of(main.map { it.value.toString() }, sets.map { s -> s.mapTo(HashSet()) { it.value.toString() } })
        }

        fun at(hand: Int): Double = if (branches.all { it.isEmpty() }) 1.0 else counter.probability(branches, hand)

        private fun members(w: Word, main: List<CardId>, groups: DeckGroups): Set<CardId> = when (w) {
            is Word.Group ->
                if (w.id == HandGoal.UNGROUPED) main.filterTo(HashSet()) { groups.groupOf(it) == null }
                else main.filterTo(HashSet()) { groups.groupOf(it) == w.id }
            is Word.Named -> named(w.text, main, groups)
        }

        private fun named(text: String, main: List<CardId>, groups: DeckGroups): Set<CardId> {
            val t = text.trim()
            Regex("""^any\s*\((.*)\)$""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
                return m.groupValues[1].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.flatMapTo(HashSet()) { named(it, main, groups) }
            }
            // reach(X) (B3): X, or a card of the deck that searches it — when the caller can tell what searches what.
            Regex("""^reach\s*\((.*)\)$""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
                val find = searchers ?: throw IllegalArgumentException("reach(…) needs the cards' text, which is not read here.")
                val targets = named(m.groupValues[1], main, groups)
                return targets + find(targets).filterTo(HashSet()) { it in main }
            }
            if (t.equals(UNGROUPED, ignoreCase = true)) return main.filterTo(HashSet()) { groups.groupOf(it) == null }
            groups.groups.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { g -> return main.filterTo(HashSet()) { groups.groupOf(it) == g.id } }
            val cards = main.filterTo(HashSet()) { name(it)?.equals(t, ignoreCase = true) == true }
            if (cards.isNotEmpty()) return cards
            throw IllegalArgumentException(
                "“$t” is neither a group (${(groups.ordered().map { it.name } + UNGROUPED).joinToString()}) nor a card in the Main Deck.",
            )
        }
    }

    private sealed class Word {
        data class Group(val id: String) : Word()
        data class Named(val text: String) : Word()
    }
}
