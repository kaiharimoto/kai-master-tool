package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ContextWindows
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryBudget
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.prefs.AiConnection

// Memory with no cap, read within a budget (1.1.11, kai: "I don't want there to be a cap to the knowledge"), on
// [AiState]: each kind's room on the model in use, the part of a file that goes in front of the model, and what of it
// is in reach, for context_status.

/** [kind]'s room in the prompt, in characters, on [on]'s model (the connection in use when not given). */
fun AiState.memoryRoom(kind: MemoryKind, on: AiConnection? = prefs.connection): Int =
    MemoryBudget.chars(kind, on?.let(::windowOf) ?: 0)

/** What the moment is about, for choosing entries: the person's [query] and the names in scope. */
fun AiState.memoryFocus(query: String): MemoryBudget.Focus =
    MemoryBudget.Focus(query, listOfNotNull(h.builder.deckName.takeIf { h.builder.deckId != null }, host.scope()?.name).distinct())

/**
 * A memory file as the prompt holds it: its entries as `- ` lines within [kind]'s room, the most relevant to [query]
 * first chosen, and the index line when some were left out. [scope] is the memory tool's word for it.
 */
fun AiState.promptMemory(kind: MemoryKind, id: String?, scope: String, query: String = "", on: AiConnection? = prefs.connection): String {
    val entries = files.read(AiMemory.path(kind, id))?.let { AiMemory.parse(it).entries }.orEmpty()
    if (entries.isEmpty()) return ""
    return MemoryBudget.pick(entries, memoryRoom(kind, on), memoryFocus(query), kind, scope).lines()
}

/** One line on a memory file for context_status: how much it holds, and how much of it goes in front of Ai. */
private fun AiState.reach(label: String, kind: MemoryKind, id: String?, scope: String): String? {
    val doc = files.read(AiMemory.path(kind, id))?.let(AiMemory::parse) ?: return null
    if (doc.entries.isEmpty()) return null
    val chars = doc.entries.sumOf { it.length + 3 }
    val room = memoryRoom(kind)
    val words = { n: Int -> ContextWindows.words((n / 4).toLong()) + " tokens" }
    return if (chars <= room) {
        "- $label: ${doc.entries.size} entries, about ${words(chars)}, all in front of you."
    } else {
        "- $label: ${doc.entries.size} entries, about ${words(chars)}; its room here is about ${words(room)}, so you are shown " +
            "the most relevant and the index line — the rest through memory_read scope $scope or recall scope memory."
    }
}

/** What memory is in reach (1.1.11), for context_status: each file in scope, its size, and how much of it is shown. */
fun AiState.memoryReport(): List<String> = buildList {
    reach("Your profile (user)", MemoryKind.USER, null, "user")?.let(::add)
    reach("Your notes (agent)", MemoryKind.AGENT, null, "agent")?.let(::add)
    host.scope()?.let { s -> reach("Notes on ${s.name} (${if (s.kind == MemoryKind.WEB) "web" else "deck"})", s.kind, s.id, if (s.kind == MemoryKind.WEB) "web" else "deck")?.let(::add) }
    val deckId = session?.deckId ?: h.builder.deckId
    val deckName = session?.deckName ?: h.builder.deckName
    deckId?.let { reach("The guide to $deckName (guide)", MemoryKind.GUIDE, it, "guide")?.let(::add) }
}
