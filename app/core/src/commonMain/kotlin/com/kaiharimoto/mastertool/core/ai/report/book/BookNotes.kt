package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The person's own marks in a reader's guide (Phase H, H.6): a block highlighted, a note written under it. Kept beside the
 * book as `guides/<deck>.notes.json` — synced and backed up with Ai's notes, deleted with the deck — and keyed by each
 * block's stable id ([GuideBook.withIds]), so a mark survives the book being rewritten around it. A mark whose block a
 * rewrite took out is never dropped: it waits in [orphans] until the person removes it.
 *
 * The person's alone: Ai reads them (what the person underlined and asked is what to write about next) and never writes
 * them.
 */
@Serializable
data class BookNotes(val version: Int = 1, val marks: List<Mark> = emptyList()) {
    /** One block's mark: [highlight], a [note] in the person's words, and when it was last changed. */
    @Serializable
    data class Mark(val block: String, val highlight: Boolean = false, val note: String = "", val at: Long = 0L) {
        val empty: Boolean get() = !highlight && note.isBlank()
    }

    fun of(block: String): Mark? = marks.firstOrNull { it.block == block }

    /** [block] highlighted, or not. */
    fun highlight(block: String, on: Boolean, at: Long): BookNotes = put((of(block) ?: Mark(block)).copy(highlight = on, at = at))

    /** [block]'s note set to [text]; blank removes it. */
    fun note(block: String, text: String, at: Long): BookNotes = put((of(block) ?: Mark(block)).copy(note = text.trim().take(NOTE_MAX), at = at))

    fun remove(block: String): BookNotes = copy(marks = marks.filterNot { it.block == block })

    private fun put(m: Mark): BookNotes = copy(marks = (marks.filterNot { it.block == m.block } + m).filterNot { it.empty })

    /** The marks whose block is no longer in [book]: a rewrite took it out. */
    fun orphans(book: GuideBook): List<Mark> {
        val ids = book.blockIds()
        return marks.filter { it.block !in ids }
    }

    companion object {
        /** The longest note kept: a margin, not a chapter. */
        const val NOTE_MAX = 2_000

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

        /** Where a deck's notes are kept, beside its book. */
        fun path(deckId: String): String = "guides/${AiMemory.safeId(deckId)}.notes.json"

        fun write(n: BookNotes): String = JSON.encodeToString(serializer(), n)

        /** A file read forgivingly: nothing, or nothing readable, is no marks. */
        fun read(text: String?): BookNotes = text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: BookNotes()

        /**
         * The person's marks in words, as Ai reads them: each with where it is (chapter and section) and the start of the
         * block's words; [chapter] for one chapter's only. Empty when there are none.
         */
        fun words(notes: BookNotes, book: GuideBook, chapter: String? = null): String {
            val where = HashMap<String, String>()
            val what = HashMap<String, String>()
            book.chapters.forEach { c ->
                if (chapter != null && c.id != chapter) return@forEach
                c.sections.forEach { s ->
                    s.blocks.forEach { b ->
                        where[b.id] = "${c.title} › ${s.title}"
                        what[b.id] = (b as? Block.Text)?.text?.take(80)?.let { "“$it${if (it.length == 80) "…" else ""}”" } ?: b::class.simpleName.orEmpty().lowercase()
                    }
                }
            }
            val shown = notes.marks.filter { it.block in where }
            val lost = if (chapter == null) notes.orphans(book) else emptyList()
            if (shown.isEmpty() && lost.isEmpty()) return ""
            return buildString {
                append("The person's own marks in the reader (theirs alone: read them, never write them):")
                shown.forEach { m ->
                    append("\n- ").append(where.getValue(m.block)).append(", ").append(what.getValue(m.block)).append(":")
                    if (m.highlight) append(" highlighted.")
                    if (m.note.isNotBlank()) append(" Their note: ").append(m.note)
                }
                if (lost.isNotEmpty()) {
                    append("\n").append(lost.size).append(if (lost.size == 1) " mark is" else " marks are").append(" on parts a rewrite took out")
                    lost.filter { it.note.isNotBlank() }.forEach { append("\n- Their note: ").append(it.note) }
                }
            }
        }
    }
}

/** Every block id in the book. */
fun GuideBook.blockIds(): Set<String> = chapters.flatMap { c -> c.sections.flatMap { s -> s.blocks.map { it.id } } }.filter { it.isNotEmpty() }.toSet()
