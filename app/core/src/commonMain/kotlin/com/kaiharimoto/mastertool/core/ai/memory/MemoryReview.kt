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
            // Sets, so a guide of thousands of entries is compared in one pass, never entries × entries (1.1.9).
            val wasSet = was.toHashSet()
            val nowSet = now.toHashSet()
            MemoryChange(path, now.filter { it !in wasSet }, was.filter { it !in nowSet })
        }.filterNot { it.isEmpty }

    /**
     * [text] with [change] taken back, entry by entry (1.0.98, the red team): the entries it added removed, the ones it
     * removed put back — and nothing else touched, so what anyone else wrote meanwhile stays. Null when the file ends up
     * holding nothing at all. Memory files only; a skill is put back whole.
     */
    fun revert(text: String?, change: MemoryChange): String? = apply(text, MemoryChange(change.path, change.removed, change.added))

    /** [text] with [change] made: its removed entries gone, its added ones added after the rest. Null when nothing is left. */
    fun apply(text: String?, change: MemoryChange): String? {
        val doc = text?.let { AiMemory.parse(it) } ?: MemoryDoc(emptyList(), emptyList())
        val removed = change.removed.toHashSet()
        val kept = doc.entries.filter { it !in removed }
        val keptSet = kept.toHashSet()
        val added = change.added.filter { keptSet.add(it) }
        val next = doc.copy(entries = kept + added)
        return if (next.preamble.all { it.isBlank() } && next.entries.isEmpty()) null else next.render()
    }

    /**
     * A memory file edited by hand while Ai wrote to it (1.0.98, the red team: the brain editor overwrote Ai's writes):
     * the person's own change — [mine] against what they opened, [base] — made on what is on disk now, [theirs]. Both
     * sides' entries stay; the person's preamble wins.
     */
    fun merge(base: String?, theirs: String?, mine: String): String {
        if (theirs == base) return mine
        val change = diff(mapOf("f" to base), mapOf("f" to mine)).firstOrNull() ?: return theirs ?: mine
        val merged = apply(theirs, change) ?: return mine
        val preamble = AiMemory.parse(mine).preamble
        return AiMemory.parse(merged).copy(preamble = preamble).render()
    }

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
