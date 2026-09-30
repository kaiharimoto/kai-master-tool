package com.kaiharimoto.mastertool.core.ai.memory

/**
 * Ai's memory: small markdown files it writes itself, as entries — one bullet each —
 * under a title the app writes. Bounded on purpose (Hermes's lesson): a memory that
 * can only grow becomes a transcript, and a transcript is not knowledge. When a
 * file is full, Ai must replace or remove an entry to add one, which is the moment
 * it decides what still matters.
 *
 * ```
 * # What Ai knows about you
 *
 * - Plays Branded in TCG; YCS Paris on 14 November.
 * - Prefers going second, and wants reasons more than lists.
 * ```
 *
 * Anything that is not an entry — the title, a note the person wrote at the top —
 * is kept as it is. The person may edit the file by hand; this reads it back.
 */
enum class MemoryKind(val file: String, val limit: Int, val title: String, val entryLimit: Int = limit / 2) {
    /**
     * What Ai learns about the person — their profile (1.0.54, Learn About You): goals,
     * preferences, workflow, how they play, their decks and events. Always in the prompt, so
     * bounded; room for a profile built over many sessions.
     */
    USER("USER.md", 5000, "What %s knows about you"),

    /** What Ai learns about doing the job. Always in the prompt. */
    AGENT("MEMORY.md", 2000, "%s's notes to self"),

    /** Notes on one deck that is in no web: in the prompt while it is open. */
    DECK("decks/%s.md", 4000, "Notes on %s"),

    /** Notes on one web — the field for an event, every deck in it: in the prompt while it is in scope. */
    WEB("webs/%s.md", 6000, "Notes on %s"),

    /**
     * How one deck plays (1.0.48, Fine Tuning): its game plan, lines, card roles, weak points,
     * side deck — taught by the person or studied by Ai. Read while that deck is open, whether
     * or not it is in a web; the web's file is the field, this is the deck.
     */
    GUIDE("guides/%s.md", UNBOUNDED, "How %s plays", entryLimit = 5000),
    ;

    /** Whether the file may grow without end (1.0.65, the guide: kai "remove the 10k cap for guides"). */
    val bounded: Boolean get() = limit != UNBOUNDED
}

/**
 * No cap on the file's length: a deck's guide grows with everything Ai learns about the deck. It is
 * read into a conversation once, while that deck is open, so its length costs context there alone;
 * one entry is still held to [MemoryKind.entryLimit], so a single note never swallows it.
 */
const val UNBOUNDED = Int.MAX_VALUE

data class MemoryDoc(val preamble: List<String>, val entries: List<String>) {
    val used: Int get() = entries.sumOf { it.length }

    fun render(): String = buildString {
        preamble.dropLastWhile { it.isBlank() }.forEach { appendLine(it) }
        if (entries.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            entries.forEach { appendLine("- $it") }
        }
    }

    companion object {
        fun blank(title: String) = MemoryDoc(listOf("# $title"), emptyList())
    }
}

/** What a memory write did, in words Ai reads back as the tool's answer. */
sealed interface MemoryWrite {
    data class Done(val doc: MemoryDoc, val message: String) : MemoryWrite
    data class Refused(val message: String) : MemoryWrite
}

object AiMemory {
    /** Reads a file. Entries are `- ` lines; an indented line under one continues it. */
    fun parse(text: String): MemoryDoc {
        val preamble = mutableListOf<String>()
        val entries = mutableListOf<String>()
        var inEntries = false
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val bullet = line.startsWith("- ") || line.startsWith("* ")
            when {
                bullet -> {
                    inEntries = true
                    entries += line.drop(2).trim()
                }
                inEntries && line.isNotBlank() && (raw.startsWith("  ") || raw.startsWith("\t")) && entries.isNotEmpty() ->
                    entries[entries.lastIndex] = entries.last() + " " + line.trim()
                !inEntries -> preamble += line
                // Words after the entries that are not an entry: kept, as an entry of their own.
                line.isNotBlank() -> entries += line.trim()
            }
        }
        return MemoryDoc(preamble.dropLastWhile { it.isBlank() }, entries.filter { it.isNotBlank() })
    }

    /** One line: an entry never spans lines, so a file stays a list. */
    private fun clean(text: String) = text.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        .removePrefix("- ").trim()

    /** "12,345 characters", with the cap when there is one. */
    private fun size(used: Int, limit: Int) = if (limit == UNBOUNDED) "$used characters" else "$used/$limit characters"

    fun add(doc: MemoryDoc, text: String, limit: Int, entryLimit: Int = limit / 2): MemoryWrite {
        val entry = clean(text)
        if (entry.isEmpty()) return MemoryWrite.Refused("Nothing to add.")
        if (doc.entries.any { it.equals(entry, ignoreCase = true) }) return MemoryWrite.Refused("That is already remembered.")
        if (entry.length > entryLimit) return MemoryWrite.Refused("Too long for one entry (${entry.length} characters). Say it in under $entryLimit, or split it.")
        if (limit != UNBOUNDED && doc.used + entry.length > limit) {
            return MemoryWrite.Refused(
                "Memory is full (${doc.used}/$limit characters). Replace or remove an entry first — keep what will still matter. Entries:\n" +
                    doc.entries.joinToString("\n") { "- $it" },
            )
        }
        val next = doc.copy(entries = doc.entries + entry)
        return MemoryWrite.Done(next, "Remembered (${size(next.used, limit)}).")
    }

    fun replace(doc: MemoryDoc, oldText: String, text: String, limit: Int, entryLimit: Int = limit / 2): MemoryWrite {
        val entry = clean(text)
        if (entry.isEmpty()) return remove(doc, oldText)
        if (entry.length > entryLimit) return MemoryWrite.Refused("Too long for one entry (${entry.length} characters). Say it in under $entryLimit, or split it.")
        val at = find(doc, oldText) ?: return notFound(doc, oldText)
        if (at < 0) return ambiguous(doc, oldText)
        val next = doc.copy(entries = doc.entries.toMutableList().also { it[at] = entry })
        if (limit != UNBOUNDED && next.used > limit) return MemoryWrite.Refused("That would make memory ${next.used}/$limit characters. Shorten it.")
        return MemoryWrite.Done(next, "Replaced (${size(next.used, limit)}).")
    }

    fun remove(doc: MemoryDoc, oldText: String): MemoryWrite {
        val at = find(doc, oldText) ?: return notFound(doc, oldText)
        if (at < 0) return ambiguous(doc, oldText)
        val next = doc.copy(entries = doc.entries.toMutableList().also { it.removeAt(at) })
        return MemoryWrite.Done(next, "Removed.")
    }

    /** The entry holding [part]: its index, -1 when more than one does, null when none. */
    private fun find(doc: MemoryDoc, part: String): Int? {
        val needle = part.trim().removePrefix("- ").trim()
        if (needle.isEmpty()) return null
        val hits = doc.entries.withIndex().filter { it.value.contains(needle, ignoreCase = true) }
        return when (hits.size) {
            0 -> null
            1 -> hits.first().index
            else -> -1
        }
    }

    private fun notFound(doc: MemoryDoc, part: String) =
        MemoryWrite.Refused("No entry holds “$part”. Entries:\n" + doc.entries.joinToString("\n") { "- $it" }.ifEmpty { "(none)" })

    private fun ambiguous(doc: MemoryDoc, part: String) =
        MemoryWrite.Refused("More than one entry holds “$part”; quote more of the one you mean.")

    /**
     * A deck joining a web: its notes become the web's, each entry marked with the
     * deck's name, so nothing learned about it is lost and the web's file is the one
     * that is read from now on. Entries that would overflow the web's file are kept
     * at the end regardless — the next write will ask Ai to make room.
     */
    fun fold(web: MemoryDoc, deck: MemoryDoc, deckName: String): MemoryDoc {
        val marked = deck.entries.map { "[$deckName] $it" }.filter { m -> web.entries.none { it.equals(m, ignoreCase = true) } }
        return web.copy(entries = web.entries + marked)
    }

    /** The file's name under the memory folder, for [kind] and a deck's or web's id. */
    fun path(kind: MemoryKind, id: String? = null): String =
        if (kind == MemoryKind.DECK || kind == MemoryKind.WEB || kind == MemoryKind.GUIDE) kind.file.replace("%s", safeId(id ?: "")) else kind.file

    /** An id made safe for a file name: letters, digits, dashes and underscores only. */
    fun safeId(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(80)

    fun title(kind: MemoryKind, name: String): String = kind.title.replace("%s", name)
}
