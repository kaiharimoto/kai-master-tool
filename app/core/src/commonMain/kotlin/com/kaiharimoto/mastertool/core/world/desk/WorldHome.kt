package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.WorldEvent

/**
 * The new-tab page, `world://home` (§4): every page of the world, newest first, grouped by the run or turn that made it,
 * with a filter line; then files and apps. And the plate's one mono line (§2.1: `16 pages · 6 files · 1 app`).
 */
object WorldHome {
    /** Pages made together: by one run ([run] is its event's time, for `world://runs/<t>`), or shown on their own. */
    data class Group(val title: String, val run: Long?, val at: Long, val boards: List<Board>)

    /**
     * [w]'s boards grouped by the run that pinned them (from [events], the world's log) and newest first; a board no run
     * pinned stands in a group of the turn's shows, by the time it was last updated. [q] filters by title, note and source.
     */
    fun groups(w: World, events: List<WorldEvent>, q: String? = null): List<Group> {
        val needle = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val boards = w.boards.filter { b ->
            needle == null || needle in b.title.lowercase() || needle in b.note.lowercase() || needle in b.source.orEmpty().lowercase() || needle in b.kind
        }
        val byRun = HashMap<String, WorldEvent>()
        events.filter { it.kind == WorldEvent.Kind.RUN }.forEach { e -> e.run?.boards?.forEach { id -> byRun[id] = e } }
        val grouped = boards.groupBy { b -> byRun[b.id]?.let { "run:${it.t}" } ?: "shown" }
        val out = grouped.map { (key, bs) ->
            val run = key.takeIf { it.startsWith("run:") }?.let { byRun[bs.first().id] }
            val title = run?.let { it.path ?: it.run?.path ?: it.text } ?: "Shown by Ai"
            Group(title, run?.t, bs.maxOf { it.updated }, bs.sortedByDescending { it.updated })
        }
        return out.sortedByDescending { it.at }
    }

    /** `16 pages · 6 files · 1 app`, with nothing said of what there is none of. */
    fun summary(pages: Int, files: Int, apps: Int): String = listOfNotNull(
        count(pages, "page"),
        count(files, "file"),
        count(apps, "app"),
    ).joinToString(" · ")

    private fun count(n: Int, word: String) = if (n <= 0) null else "$n $word" + if (n == 1) "" else "s"
}
