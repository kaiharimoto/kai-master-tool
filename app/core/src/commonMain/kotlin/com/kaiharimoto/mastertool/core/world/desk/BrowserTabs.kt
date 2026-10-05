package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlinx.serialization.Serializable

/**
 * One tab of the World's Browser (§4): the page it shows ([address], a [WorldAddress] as text), its own history either
 * way, who opened it ([by], in Ai's [turn]), whether the person [kept] it, and its [mark] — Ai changed it while it was not
 * selected (the 6 dp ink square, until it is).
 */
@Serializable
data class Tab(
    val id: String,
    val address: String = WorldAddress.HOME,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
    val by: String = WorldEvent.YOU,
    val turn: Int = 0,
    val kept: Boolean = false,
    val mark: Boolean = false,
    val opened: Long = 0L,
) {
    val byAi: Boolean get() = by == WorldEvent.AI
    val parsed: WorldAddress get() = WorldAddress.parse(address)
}

/**
 * The Browser's tabs (§4, `BrowserTabsTest`): a pure value kept in `desk.json`. Each tab keeps [MAX_HISTORY] steps back
 * and forward; at most [MAX_TABS] tabs, the oldest of Ai's that is neither kept nor selected closing first — its page
 * stays at `world://home`. A board pinned again under its id updates its tab in place: never a second tab.
 */
@Serializable
data class BrowserTabs(
    val tabs: List<Tab> = emptyList(),
    val selected: String? = null,
    /** The next tab's number, so a closed tab's id is never reused. */
    val next: Int = 1,
) {
    val current: Tab? get() = tabs.firstOrNull { it.id == selected }
    fun tab(id: String): Tab? = tabs.firstOrNull { it.id == id }

    /** The tab showing [address], if one is. */
    fun showing(address: String): Tab? = tabs.firstOrNull { it.address == address }

    /**
     * A new tab on [address]: selected when [select], else opened beside the selected one with its mark (Ai's tab while
     * the person reads another, §4). The 21st closes the oldest that may go.
     */
    fun open(address: String, at: Long, by: String = WorldEvent.YOU, turn: Int = 0, select: Boolean = true): BrowserTabs {
        val t = Tab("t$next", address, by = by, turn = turn, mark = !select && selected != null, opened = at)
        val i = tabs.indexOfFirst { it.id == selected }
        val placed = if (i < 0) tabs + t else tabs.subList(0, i + 1) + t + tabs.subList(i + 1, tabs.size)
        val opened = copy(tabs = placed, next = next + 1, selected = if (select || selected == null) t.id else selected)
        return opened.trim()
    }

    /**
     * `world_show` (§4): a board's page opened in a tab. A tab already on it is updated in place — selected when [raise],
     * else marked — and a new one opens when none is.
     */
    fun show(board: String, at: Long, raise: Boolean, turn: Int = 0, by: String = WorldEvent.AI): BrowserTabs {
        val address = WorldAddress.Board(board).format()
        val there = showing(address)
        return when {
            there == null -> open(address, at, by, turn, select = raise)
            raise -> select(there.id)
            there.id == selected -> this
            else -> copy(tabs = tabs.map { if (it.id == there.id) it.copy(mark = true) else it })
        }
    }

    /** [id] goes to [address]: where it was goes on its back stack, forward is cleared. */
    fun go(id: String, address: String): BrowserTabs = edit(id) { t ->
        if (t.address == address) t else t.copy(address = address, back = (t.back + t.address).takeLast(MAX_HISTORY), forward = emptyList())
    }

    fun back(id: String): BrowserTabs = edit(id) { t ->
        val to = t.back.lastOrNull() ?: return@edit t
        t.copy(address = to, back = t.back.dropLast(1), forward = (listOf(t.address) + t.forward).take(MAX_HISTORY))
    }

    fun forward(id: String): BrowserTabs = edit(id) { t ->
        val to = t.forward.firstOrNull() ?: return@edit t
        t.copy(address = to, forward = t.forward.drop(1), back = (t.back + t.address).takeLast(MAX_HISTORY))
    }

    /** Closes [id]; the tab to its right is selected, else the one to its left. */
    fun close(id: String): BrowserTabs {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return this
        val rest = tabs.filterNot { it.id == id }
        val sel = if (selected == id) (rest.getOrNull(i) ?: rest.getOrNull(i - 1))?.id else selected
        return copy(tabs = rest, selected = sel)
    }

    fun select(id: String): BrowserTabs =
        if (tab(id) == null) this else copy(selected = id, tabs = tabs.map { if (it.id == id) it.copy(mark = false) else it })

    /** The tab [steps] along from the selected one, wrapping (`Ctrl Tab`, `Ctrl Shift Tab`). */
    fun step(steps: Int): BrowserTabs {
        if (tabs.isEmpty()) return this
        val i = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        return select(tabs[((i + steps) % tabs.size + tabs.size) % tabs.size].id)
    }

    /** [id] dragged to stand at [to]. */
    fun move(id: String, to: Int): BrowserTabs {
        val t = tab(id) ?: return this
        val rest = tabs.filterNot { it.id == id }
        val at = to.coerceIn(0, rest.size)
        return copy(tabs = rest.subList(0, at) + t + rest.subList(at, rest.size))
    }

    fun keep(id: String, kept: Boolean): BrowserTabs = edit(id) { it.copy(kept = kept) }

    /** The tab on [address] is marked: Ai changed it (a board pinned again by a run). Selected, it needs no mark. */
    fun changed(address: String): BrowserTabs =
        copy(tabs = tabs.map { if (it.address == address && it.id != selected) it.copy(mark = true) else it })

    private fun edit(id: String, f: (Tab) -> Tab) = copy(tabs = tabs.map { if (it.id == id) f(it) else it })

    /** At most [MAX_TABS]: Ai's oldest unkept, unselected tab closes first, then anyone's. */
    private fun trim(): BrowserTabs {
        var t = this
        while (t.tabs.size > MAX_TABS) {
            val loose = t.tabs.filter { !it.kept && it.id != t.selected }
            val victim = loose.filter { it.byAi }.minByOrNull { it.opened } ?: loose.minByOrNull { it.opened } ?: break
            t = t.close(victim.id)
        }
        return t
    }

    companion object {
        const val MAX_TABS = 20
        const val MAX_HISTORY = 50
    }
}
