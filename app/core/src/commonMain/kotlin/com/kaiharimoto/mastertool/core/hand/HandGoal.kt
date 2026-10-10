package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.CardId

/**
 * What one role has to do in the opener, in the words a deckbuilder uses.
 *
 * Deliberately four positions rather than a pair of spinners. Nobody has ever
 * asked for "between two and four handtraps"; they ask whether they see one,
 * whether they see two, and whether they brick — and a control that offers the
 * other several hundred combinations makes those four harder to reach.
 */
enum class Ask(val label: String, val min: Int, val max: Int) {
    ANY("Any", 0, NO_CEILING),
    AT_LEAST_1("≥ 1", 1, NO_CEILING),
    AT_LEAST_2("≥ 2", 2, NO_CEILING),
    AT_MOST_1("≤ 1", 0, 1),
    NONE("= 0", 0, 0),
    ;

    val constrains: Boolean get() = this != ANY
}

/** Larger than any legal hand, so "at least one" means "and no ceiling". */
private const val NO_CEILING = 60

/**
 * A question about the opening hand, stated once and kept.
 *
 * This is the piece the consistency calculator was missing. The maths has been
 * exact since the day it was written, but the *question* lived in a sheet's
 * transient state: you described your deck to it, read a number, closed it, and
 * the description was gone. Next time you wanted to know whether cutting a card
 * had cost you anything, you rebuilt the whole thing from memory — and a
 * question you have to rebuild is one you stop asking, which is how a deck ends
 * up tuned by feel next to a calculator nobody opens.
 *
 * So a goal travels with the deck, like the groups it is written in terms of.
 * It is keyed by group id rather than by name, so renaming a role does not
 * silently detach the question from it.
 */
data class HandGoal(
    val id: String,
    val name: String,
    /** Five going first, six going second (the turn's draw made). */
    val handSize: Int = LensOdds.DEFAULT_HAND,
    /** Group id to ask. Groups absent from the map are unconstrained. */
    val asks: Map<String, Ask> = emptyMap(),
    /**
     * A condition in the instruments' grammar (Phase G, `Goals.parse`): overlap, `or`, at most — "Starters>=1 & Hand
     * traps>=1 | Extenders>=2", a card by name, `any(a, b)>=1`. Held together with [asks] (both must hold); blank asks
     * nothing more. Written in the payload's `goals` key only when set, so an older build reads the asks as before.
     */
    val condition: String = "",
) {
    val isEmpty: Boolean get() = asks.none { it.value.constrains } && condition.isBlank()

    fun with(groupId: String, ask: Ask): HandGoal =
        copy(asks = if (ask == Ask.ANY) asks - groupId else asks + (groupId to ask))

    fun ask(groupId: String): Ask = asks[groupId] ?: Ask.ANY

    /** Drops asks about groups that no longer exist, so a deleted role cannot haunt it. */
    fun pruned(groups: DeckGroups): HandGoal =
        copy(asks = asks.filterKeys { it == UNGROUPED || groups.byId(it) != null })

    companion object {
        /**
         * The pseudo-group for cards no role claims.
         *
         * "And no more than one card that does nothing" is half of what anyone
         * wants to ask, and it cannot be said in terms of groups the user drew,
         * because the whole point is the cards they have not drawn one for.
         * Leading space so it can never collide with a minted group id.
         */
        const val UNGROUPED = " ungrouped"

        fun blank(id: String, name: String = "") = HandGoal(id, name)
    }
}

/**
 * The goals a deck carries, in the order they were made.
 *
 * A list rather than one, because two questions are held at once and they pull
 * against each other: "do I open my combo" and "do I brick". A deck tuned to
 * only the first is the one that loses to its own opening hand.
 */
data class HandGoals(val goals: List<HandGoal> = emptyList()) {
    val isEmpty: Boolean get() = goals.isEmpty()

    fun byId(id: String): HandGoal? = goals.firstOrNull { it.id == id }

    fun upsert(goal: HandGoal): HandGoals =
        if (byId(goal.id) == null) {
            HandGoals(goals + goal)
        } else {
            HandGoals(goals.map { if (it.id == goal.id) goal else it })
        }

    fun remove(id: String): HandGoals = HandGoals(goals.filterNot { it.id == id })

    fun pruned(groups: DeckGroups): HandGoals =
        HandGoals(goals.map { it.pruned(groups) })

    companion object {
        val EMPTY = HandGoals()
    }
}

/** Answers a stated goal against the deck as it currently stands. */
object GoalOdds {

    /**
     * The chance a [goal]'s opener satisfies every one of its asks at once.
     *
     * Joint, not per-role: the asks are joined by AND, which is what makes it a
     * question rather than a list of facts. A goal that asks nothing is
     * certain, which is both true and the reason an empty goal shows no number.
     */
    fun probability(goal: HandGoal, section: List<CardId>, groups: DeckGroups): Double {
        val constraints = goal.asks.filterValues { it.constrains }
        if (constraints.isEmpty()) return 1.0

        val sizes = buildMap {
            groups.groups.forEach { put(it.id, groups.countIn(section, it.id)) }
            put(HandGoal.UNGROUPED, section.count { groups.groupOf(it) == null })
        }

        return HandOdds.probability(
            groupSizes = sizes,
            deckSize = section.size,
            handSize = goal.handSize,
            query = HandQuery(
                constraints.map { (groupId, ask) ->
                    HandConstraint(groupId, ask.min, minOf(ask.max, goal.handSize))
                }
            ),
        )
    }
}
