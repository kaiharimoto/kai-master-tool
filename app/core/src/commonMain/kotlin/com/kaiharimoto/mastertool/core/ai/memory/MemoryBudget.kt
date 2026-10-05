package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.Compaction
import com.kaiharimoto.mastertool.core.ai.report.GuideDoc
import kotlin.math.ln

/**
 * What of a memory file goes in front of the model (1.1.9, kai: "I don't want there to be a cap to the knowledge"):
 * the files keep everything; the prompt gets a budget of them. A file within its budget goes in whole, as it always
 * did. A larger one gives the entries most relevant to the moment — the person's latest message, the deck or web in
 * scope, the guide's labels, how recent an entry is — up to the budget, in the file's own order, and then one line
 * saying how many more there are and how to read them ([indexLine]): Ai reaches the rest with `memory_read` (a query,
 * a label, a range) and `recall` (scope memory).
 *
 * The budget is a share of the model's window ([ContextWindows][com.kaiharimoto.mastertool.core.ai.ContextWindows], or
 * the connection's own), never below what the old caps put in front of it, never above a ceiling that keeps a
 * conversation affordable on a million-token model.
 */
object MemoryBudget {
    /** A kind's room: [share] of the window, at least [floor] characters, at most [ceiling]. */
    data class Room(val share: Double, val floor: Int, val ceiling: Int)

    /** When no window is known (no connection yet): a 128k model's. */
    const val DEFAULT_WINDOW = 128_000

    /**
     * Each kind's room. The floors are the old caps (2,000 / 4,000 / 6,000), so a file that fitted before still goes in
     * whole on any model; the guide's is what a small local model can spare. At 200k tokens: Ai's notes 20,000
     * characters, a deck's 24,000, a web's 32,000, the guide 80,000 — about a sixth of the window together.
     */
    fun room(kind: MemoryKind): Room = when (kind) {
        MemoryKind.USER -> Room(0.0, kind.limit, kind.limit)
        MemoryKind.AGENT -> Room(0.025, 2_000, 40_000)
        MemoryKind.DECK -> Room(0.03, 4_000, 60_000)
        MemoryKind.WEB -> Room(0.04, 6_000, 80_000)
        MemoryKind.GUIDE -> Room(0.10, 12_000, 200_000)
    }

    /** [kind]'s budget in characters for a model that reads [windowTokens]. */
    fun chars(kind: MemoryKind, windowTokens: Int): Int {
        val r = room(kind)
        val window = windowTokens.takeIf { it > 0 } ?: DEFAULT_WINDOW
        val share = (window.toLong() * Compaction.CHARS_PER_TOKEN * r.share).toLong()
        return share.coerceIn(r.floor.toLong(), r.ceiling.toLong()).toInt()
    }

    /** What the moment is about: the person's latest words, and the names in scope (the open deck, the web). */
    data class Focus(val query: String = "", val names: List<String> = emptyList())

    /** What was picked from a file: the entries shown, in the file's order, and the line about the rest. */
    data class Shown(
        val entries: List<String>,
        /** Each shown entry's place in the file, 0-based. */
        val indices: List<Int>,
        val total: Int,
        val shownChars: Int,
        val totalChars: Int,
        /** The index line, when entries were left out; null when the whole file is shown. */
        val index: String?,
    ) {
        val complete: Boolean get() = index == null

        /** The entries as `- ` lines, the index line last: what the prompt holds. */
        fun lines(): String = (entries.map { "- $it" } + listOfNotNull(index)).joinToString("\n")
    }

    /** Characters an entry costs in the prompt: itself, its "- " and its line break. */
    private fun cost(entry: String) = entry.length + 3

    /**
     * [entries] within [budget] characters, the most relevant first chosen ([focus]), shown in the file's order. [scope]
     * is the memory tool's word for the file (guide, deck, web, agent), named in the index line.
     */
    fun pick(entries: List<String>, budget: Int, focus: Focus = Focus(), kind: MemoryKind, scope: String): Shown {
        val totalChars = entries.sumOf(::cost)
        if (totalChars <= budget) return Shown(entries, entries.indices.toList(), entries.size, totalChars, totalChars, null)
        val scores = scores(entries, focus, kind)
        val order = entries.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenByDescending { it })
        val chosen = BooleanArray(entries.size)
        var used = 0
        // Room for the index line itself.
        val room = (budget - INDEX_ROOM).coerceAtLeast(budget / 2)
        for (i in order) {
            val c = cost(entries[i])
            if (used + c > room) continue
            chosen[i] = true
            used += c
            if (room - used < MIN_ENTRY) break
        }
        val indices = entries.indices.filter { chosen[it] }
        val left = entries.indices.filter { !chosen[it] }
        val index = indexLine(left.map { entries[it] }, entries.size, kind, scope)
        return Shown(indices.map { entries[it] }, indices, entries.size, used, totalChars, index)
    }

    /** What the index line may take, held back from the entries. */
    private const val INDEX_ROOM = 400

    /** Below this much room left, no entry will fit: stop looking. */
    private const val MIN_ENTRY = 24

    /**
     * Each entry's relevance: the latest words it holds (rarer words count more), the scope's names, its label's
     * place in the guide's reading order, and how recent it is (entries are added at the end).
     */
    fun scores(entries: List<String>, focus: Focus, kind: MemoryKind): DoubleArray {
        val n = entries.size
        val lower = entries.map { it.lowercase() }
        val words = MemoryQuery.terms(focus.query)
        // How rare each word is in this file: one that every entry holds tells nothing.
        val weight = words.associateWith { w ->
            val df = lower.count { MemoryQuery.wordAt(it, w) >= 0 }
            if (df == 0) 0.0 else ln((n + 1.0) / df)
        }.filterValues { it > 0 }
        val names = focus.names.map { it.lowercase().trim() }.filter { it.length >= 2 }.distinct()
        return DoubleArray(n) { i ->
            val e = lower[i]
            var s = 0.0
            weight.forEach { (w, idf) -> if (MemoryQuery.wordAt(e, w) >= 0) s += 4.0 * idf }
            names.forEach { name -> if (e.startsWith("[$name]") || e.contains(name)) s += 6.0 }
            if (kind == MemoryKind.GUIDE) s += labelWeight(entries[i])
            // Recency breaks ties within a label: the newest of equals first, never an old game plan behind a new note.
            s + (i + 1.0) / n
        }
    }

    /** The guide's labels by how much an entry under each matters when only some can be shown. */
    private val LABELS = mapOf(
        "Goals" to 5.0, "Game plan" to 5.0, "Lines" to 4.0, "Card roles" to 3.5, "Weak points" to 3.5,
        "Connections" to 2.5, "Side deck" to 2.5, "Insights" to 2.5, "Open questions" to 1.5, "Notes" to 1.0, "Sources" to 0.0,
    )

    private fun labelWeight(entry: String): Double = LABELS[GuideDoc.split(entry).first] ?: 1.0

    /**
     * The line that stands for the entries left out: how many, how long, under which labels, and how to read them.
     * Compaction keeps it — it is part of the block put back after a summary — so Ai always knows the rest is there.
     */
    fun indexLine(left: List<String>, total: Int, kind: MemoryKind, scope: String): String {
        val chars = left.sumOf { it.length }
        val labels = left.groupingBy { MemoryQuery.labelOf(it) ?: "unlabelled" }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "${it.key} ${it.value}" }
        val what = when (kind) {
            MemoryKind.GUIDE -> "the deck's guide"
            MemoryKind.WEB -> "the web's notes"
            MemoryKind.DECK -> "the deck's notes"
            MemoryKind.AGENT -> "your notes"
            MemoryKind.USER -> "the profile"
        }
        return "($INDEX_MARK ${left.size} more of $total entries in $what are not shown here, ${grouped(chars)} characters" +
            (if (labels.isNotEmpty()) "; by label: $labels" else "") +
            ". Read them with memory_read scope $scope — a query, a label, or from and count — or recall scope memory.)"
    }

    /** What every index line begins with, so it can be found again (a test, a summary, the context gauge). */
    const val INDEX_MARK = "Memory index:"

    /** 20000 as "20,000". */
    fun grouped(n: Int): String = GuideBudget.grouped(n)

    // ---- the prompt's memory, marked so the context gauge can count it -------------------------------------------

    private const val OPEN = "<memory file=\""
    private const val CLOSE = "</memory>"

    /** [body] marked as memory from [file], for the context block: what `ContextBreakdown` counts as Memory. */
    fun tagged(file: String, body: String): String = "$OPEN$file\">\n${body.trim()}\n$CLOSE"

    /** How many of [text]'s characters are memory blocks ([tagged]). */
    fun taggedChars(text: String): Int {
        var at = text.indexOf(OPEN)
        var sum = 0
        while (at >= 0) {
            val end = text.indexOf(CLOSE, at)
            if (end < 0) return sum + (text.length - at)
            sum += end + CLOSE.length - at
            at = text.indexOf(OPEN, end)
        }
        return sum
    }
}
