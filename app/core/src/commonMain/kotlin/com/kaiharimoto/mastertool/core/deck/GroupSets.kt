package com.kaiharimoto.mastertool.core.deck

/**
 * One way of breaking the deck into groups (kai, 2026-10: "sometimes I want to open a new way
 * of looking at the deck and choose between these sets"): its own groups, assignments and
 * Fitted order, under a name of its own — "Roles", "Combo pieces", "Going second".
 */
data class GroupSet(
    val id: String,
    val name: String,
    /** The set's groups. For the set in use this is a stale copy: the live groups are the builder's. */
    val groups: DeckGroups = DeckGroups.EMPTY,
)

/**
 * The deck's sets of groups, and which one is in use.
 *
 * The set in use is never kept here twice: its groups are the deck's own groups (the payload's
 * `groups` key, which every older build reads and edits), and only the others wait in this list.
 * So switching stashes the live groups into the set being left and hands back the chosen one's,
 * and a deck that never made a second set stores nothing new at all ([isPlain]).
 */
data class GroupSets(
    /** Every set in the order the menu lists them, the one in use among them. */
    val sets: List<GroupSet> = listOf(GroupSet(FIRST_ID, FIRST_NAME)),
    val active: String = FIRST_ID,
) {
    init {
        require(sets.isNotEmpty()) { "A deck always has a set in use" }
    }

    /** The set in use; a stored [active] naming no set falls back to the first. */
    val current: GroupSet get() = sets.firstOrNull { it.id == active } ?: sets.first()

    /** Nothing beyond the deck's one unnamed set: nothing to store. */
    val isPlain: Boolean get() = sets.size == 1 && current.name == FIRST_NAME

    fun byId(id: String): GroupSet? = sets.firstOrNull { it.id == id }

    /**
     * Switches to [id], keeping [live] (the groups as they stand) in the set being left.
     * Returns the sets and the groups to put in use — [live] itself when [id] is already in use.
     */
    fun switchTo(id: String, live: DeckGroups): Pair<GroupSets, DeckGroups> {
        val target = byId(id) ?: return this to live
        if (target.id == current.id) return this to live
        return GroupSets(stashed(live).sets, target.id) to target.groups
    }

    /**
     * A new set, put in use: empty, or a copy of [live] when [copy] (to try another way of
     * grouping from where this one stands). Its name is [name], else the next "Set N".
     */
    fun add(live: DeckGroups, copy: Boolean, name: String? = null): Pair<GroupSets, DeckGroups> {
        val id = nextId()
        val made = GroupSet(id, name?.trim()?.takeIf { it.isNotEmpty() } ?: nextName(if (copy) current.name else null))
        val groups = if (copy) live else DeckGroups.EMPTY
        return GroupSets(stashed(live).sets + made, id) to groups
    }

    /** [id] renamed; a blank name is refused. */
    fun rename(id: String, name: String): GroupSets {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || byId(id) == null) return this
        return copy(sets = sets.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    /**
     * [id] removed. The last set is never removed; removing the set in use puts its neighbour
     * in use (the one after it, else the one before) and hands back that set's groups.
     */
    fun remove(id: String, live: DeckGroups): Pair<GroupSets, DeckGroups> {
        if (sets.size <= 1 || byId(id) == null) return this to live
        if (id != current.id) return copy(sets = sets.filterNot { it.id == id }) to live
        val at = sets.indexOfFirst { it.id == id }
        val next = sets.getOrNull(at + 1) ?: sets[at - 1]
        return GroupSets(sets.filterNot { it.id == id }, next.id) to next.groups
    }

    /** [id] moved to [toIndex] in the menu's order. */
    fun move(id: String, toIndex: Int): GroupSets {
        val list = sets.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return this
        val moved = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), moved)
        return copy(sets = list)
    }

    /** Every set with [live] written into the one in use: what a save of every set needs. */
    fun stashed(live: DeckGroups): GroupSets =
        copy(sets = sets.map { if (it.id == current.id) it.copy(groups = live) else it }, active = current.id)

    private fun nextId(): String {
        var n = sets.size + 1
        val taken = sets.map { it.id }.toSet()
        while ("s$n" in taken) n++
        return "s$n"
    }

    /** "Set N" for a new empty set; "<name> copy" (then "copy 2", …) for a copy. */
    private fun nextName(copyOf: String?): String {
        val taken = sets.map { it.name }.toSet()
        if (copyOf != null) {
            val base = "$copyOf copy"
            if (base !in taken) return base
            var k = 2
            while ("$base $k" in taken) k++
            return "$base $k"
        }
        var n = sets.size + 1
        while ("Set $n" in taken) n++
        return "Set $n"
    }

    companion object {
        const val FIRST_ID = "s1"
        const val FIRST_NAME = "Set 1"
        val PLAIN = GroupSets()
    }
}
