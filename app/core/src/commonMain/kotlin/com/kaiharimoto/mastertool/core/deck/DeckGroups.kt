package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.CardId

/**
 * One user-defined functional group: "engine", "handtraps", "non-engine
 * starters" — whatever the person building the deck thinks in.
 *
 * [color] is an index into the UI's prismatic ramp, deliberately not a raw
 * colour value: groups keep looking like part of the app when the theme
 * evolves, and the file format never learns what a colour is.
 */
data class DeckGroup(
    val id: String,
    val name: String,
    val color: Int,
    val order: Int,
)

/**
 * The deck's functional breakdown, exactly as the user drew it.
 *
 * Assignment is deliberately fully manual — no heuristics, no auto-tagging.
 * Deciding what counts as a starter *is* deckbuilding; the tool's job is to
 * hold the answer, not to guess it. Assignment is also per passcode, not per
 * copy: all copies of a card share one role, which is how ratios are actually
 * reasoned about ("three starters", never "two copies as starters and one as
 * something else").
 */
data class DeckGroups(
    val groups: List<DeckGroup>,
    val assignments: Map<CardId, String>,
    /**
     * The order the groups' cards are read in when they stand Fitted or Separate (1.0.39):
     * each card once, a group's copy sets in this order in its block. Kept apart from the
     * deck's own order, which is As is, because kai arranges the two for different eyes
     * ("if the user edits fitted, they could be editing it with visual cohesion for fitted
     * only in mind and not as is, and vice versa"). Empty until a card is first moved
     * there; a card not in it follows the deck's order, after the ones that are.
     */
    val fitted: List<CardId> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty() && assignments.isEmpty()

    fun byId(id: String): DeckGroup? = groups.firstOrNull { it.id == id }

    /** Groups in display order. */
    fun ordered(): List<DeckGroup> = groups.sortedWith(compareBy({ it.order }, { it.id }))

    fun upsert(group: DeckGroup): DeckGroups =
        copy(groups = groups.filterNot { it.id == group.id } + group)

    /** Removing a group frees its cards rather than stranding them. */
    fun remove(groupId: String): DeckGroups = DeckGroups(
        groups = groups.filterNot { it.id == groupId },
        assignments = assignments.filterValues { it != groupId },
    )

    /** [groupId] null clears the assignment — the card returns to Ungrouped. */
    fun assign(cardId: CardId, groupId: String?): DeckGroups = when {
        groupId == null -> copy(assignments = assignments - cardId)
        byId(groupId) == null -> this
        else -> copy(assignments = assignments + (cardId to groupId))
    }

    /** Moves a group to [toIndex] in display order, renumbering densely. */
    fun reorder(groupId: String, toIndex: Int): DeckGroups {
        val current = ordered().toMutableList()
        val from = current.indexOfFirst { it.id == groupId }
        if (from < 0) return this
        val moved = current.removeAt(from)
        current.add(toIndex.coerceIn(0, current.size), moved)
        return copy(groups = current.mapIndexed { index, group -> group.copy(order = index) })
    }

    /** Assignments that point at no live group, or into [deck]-absent cards, pruned. */
    fun pruned(deckIds: Collection<CardId>): DeckGroups {
        val live = groups.map { it.id }.toSet()
        val present = deckIds.toSet()
        return copy(
            assignments = assignments.filter { (card, group) -> group in live && card in present }
        )
    }

    /** How many cards of [section] belong to [groupId]. */
    fun countIn(section: List<CardId>, groupId: String): Int =
        section.count { assignments[it] == groupId }

    /** The group a card belongs to, ignoring assignments to deleted groups. */
    fun groupOf(id: CardId): String? = assignments[id]?.takeIf { byId(it) != null }

    /**
     * The assignments spread over every printing in [ids] (Phase B: one card, whatever its printing). A card is assigned
     * by its canonical passcode while the deck may hold it by an alternate one (an imported list, an alternate Ash); read
     * by the cell's passcode, that copy fell out of its group and every count by group came out short. A printing with no
     * assignment of its own takes the one its card was given; one assigned on its own keeps it. Nothing is removed.
     */
    fun projectedOnto(ids: Collection<CardId>, canonical: (CardId) -> CardId): DeckGroups {
        if (assignments.isEmpty() || ids.isEmpty()) return this
        val byCard = HashMap<CardId, String>()
        assignments.forEach { (id, group) -> if (byId(group) != null) byCard.getOrPut(canonical(id)) { group } }
        var out: MutableMap<CardId, String>? = null
        ids.forEach { id ->
            if (id in assignments) return@forEach
            val group = byCard[canonical(id)] ?: return@forEach
            (out ?: LinkedHashMap(assignments).also { out = it })[id] = group
        }
        return out?.let { copy(assignments = it) } ?: this
    }

    /**
     * [cardId] put in [groupId] (null: out of every group) together with every other printing of it among [ids] and the
     * assignments, so a card leaves a group whole — clearing only the passcode clicked left its other printing behind,
     * and [projectedOnto] would have put the card straight back.
     */
    fun assignCard(cardId: CardId, groupId: String?, ids: Collection<CardId>, canonical: (CardId) -> CardId): DeckGroups {
        val card = canonical(cardId)
        val printings = LinkedHashSet<CardId>().apply {
            add(cardId)
            ids.filterTo(this) { canonical(it) == card }
            assignments.keys.filterTo(this) { canonical(it) == card }
        }
        return printings.fold(this) { g, id -> g.assign(id, groupId) }
    }

    /**
     * The hue a new group should take: the least used one.
     *
     * Preference order 2,3,4,5,0,1 spends green, cyan, violet and magenta before
     * red and amber, because those two already mean "illegal" and "warning"
     * everywhere else in the app and a group wearing them reads as a problem.
     */
    fun nextColor(paletteSize: Int = 6): Int {
        val preference = listOf(2, 3, 4, 5, 0, 1).filter { it < paletteSize }
            .ifEmpty { (0 until paletteSize).toList() }
        val used = groups.groupingBy { it.color }.eachCount()
        return preference.minByOrNull { (used[it] ?: 0) * preference.size + preference.indexOf(it) }
            ?: 0
    }

    companion object {
        val EMPTY = DeckGroups(emptyList(), emptyMap())
    }
}
