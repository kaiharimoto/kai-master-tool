package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.skills.Skills

/** What changed in one memory file: the entries added and the entries gone. */
data class MemoryChange(val path: String, val added: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

/**
 * What Ai learned, for the person to keep or undo (Fine Tuning's review, and the
 * reflection after a conversation): the memory files before and after, entry by
 * entry. A replaced entry is one gone and one added, which is how it reads. A skill
 * Ai wrote ([Skills.isPath]) is steps, not entries: it is compared line by line, its
 * description first, so a skill written or patched is shown, and undone, like memory.
 */
object MemoryReview {
    fun diff(before: Map<String, String?>, after: Map<String, String?>): List<MemoryChange> =
        (before.keys + after.keys).distinct().sorted().map { path ->
            val was = lines(path, before[path])
            val now = lines(path, after[path])
            MemoryChange(path, now.filter { it !in was }, was.filter { it !in now })
        }.filterNot { it.isEmpty }

    /** How many entries changed, in all. */
    fun count(changes: List<MemoryChange>): Int = changes.sumOf { it.added.size + it.removed.size }

    private fun lines(path: String, text: String?): List<String> = when {
        text == null -> emptyList()
        Skills.isPath(path) -> Skills.parse(text, path.removePrefix("skills/").substringBefore('/'))?.let { skill ->
            listOf("When to use it: ${skill.description}") + skill.body.lines().map(String::trim).filter(String::isNotEmpty)
        }.orEmpty()
        else -> AiMemory.parse(text).entries
    }
}
