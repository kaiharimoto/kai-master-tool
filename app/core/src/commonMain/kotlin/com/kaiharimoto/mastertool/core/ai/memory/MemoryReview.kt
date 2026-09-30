package com.kaiharimoto.mastertool.core.ai.memory

/** What changed in one memory file: the entries added and the entries gone. */
data class MemoryChange(val path: String, val added: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/**
 * What Ai learned, for the person to keep or undo (Fine Tuning's review, and the
 * reflection after a conversation): the memory files before and after, entry by
 * entry. A replaced entry is one gone and one added, which is how it reads.
 */
object MemoryReview {
    fun diff(before: Map<String, String?>, after: Map<String, String?>): List<MemoryChange> =
        (before.keys + after.keys).distinct().sorted().map { path ->
            val was = before[path]?.let { AiMemory.parse(it).entries }.orEmpty()
            val now = after[path]?.let { AiMemory.parse(it).entries }.orEmpty()
            MemoryChange(path, now.filter { it !in was }, was.filter { it !in now })
        }.filterNot { it.isEmpty }

    /** How many entries changed, in all. */
    fun count(changes: List<MemoryChange>): Int = changes.sumOf { it.added.size + it.removed.size }
}
