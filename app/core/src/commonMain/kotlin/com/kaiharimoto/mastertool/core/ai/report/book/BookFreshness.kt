package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.evidence.Numbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a reader's guide is still about the deck as it is (1.0.99, the red team: "the reader's guide passes for
 * current after any write, never notices deck changes, and freezes Ai-typed numbers next to recomputed ones").
 *
 * Each chapter keeps the deck and the notes it was written on ([GuideBook.Chapter.deckPrint], [GuideBook.Chapter.notesHash]),
 * stamped by the app; the front keeps the deck its roles were set on ([GuideBook.deckPrint]). A write of one chapter
 * makes that chapter current and no other. A book from before 1.0.99 recorded no deck: it reads as "deck unknown" —
 * neither current nor stale — until a chapter is written again. The numbers the app draws ([GuideFacts]) are worked
 * out from the deck as it is; the ones Ai typed into an older chapter ([typedNumbers]) are said to be from an older deck.
 */
object BookFreshness {
    enum class DeckState {
        /** Every recorded deck is the deck as it is. */
        CURRENT,

        /** A chapter, or the front, was written on another deck. */
        CHANGED,

        /** Nothing recorded which deck it was written on (a book from before 1.0.99), or the deck is gone. */
        UNKNOWN,
    }

    data class Status(
        val deck: DeckState,
        /** Written chapters written on a deck other than the one now, by id, in book order. */
        val olderDeck: List<String> = emptyList(),
        /** The front's roles were set on another deck. */
        val frontOnOlderDeck: Boolean = false,
        /** Written chapters written from other notes than Ai's notes now, by id, in book order. */
        val olderNotes: List<String> = emptyList(),
    ) {
        val stale: Boolean get() = deck == DeckState.CHANGED || olderNotes.isNotEmpty()
    }

    /**
     * [book] against the deck as it is ([deckPrint], `Ledger.fingerprint`; null or empty when it is not known) and
     * Ai's notes now ([notesHash], `ReaderGuide.hashOf`; null or empty when there are none). A chapter without its own
     * notes hash is read against the book's, as before 1.0.99.
     */
    fun of(book: GuideBook, deckPrint: String?, notesHash: String?): Status {
        val written = book.chapters.filter { it.written }
        val notes = notesHash?.takeIf { it.isNotBlank() }
        val olderNotes = if (notes == null) emptyList() else written.filter { c ->
            val h = c.notesHash.ifBlank { book.notesHash }
            h.isNotBlank() && h != notes
        }.map { it.id }
        val now = deckPrint?.takeIf { it.isNotBlank() } ?: return Status(DeckState.UNKNOWN, olderNotes = olderNotes)
        val older = written.filter { it.deckPrint.isNotBlank() && it.deckPrint != now }.map { it.id }
        val front = book.deckPrint.isNotBlank() && book.deckPrint != now
        val known = book.deckPrint.isNotBlank() || written.any { it.deckPrint.isNotBlank() }
        val state = when {
            older.isNotEmpty() || front -> DeckState.CHANGED
            known -> DeckState.CURRENT
            else -> DeckState.UNKNOWN
        }
        return Status(state, older, front, olderNotes)
    }

    /**
     * What the reader says of [status], a sentence a reason: the deck first, then the notes. [name] is Ai's.
     * Nothing when the book is current, or its deck unknown.
     */
    fun words(book: GuideBook, status: Status, name: String = "Ai"): List<String> = buildList {
        val written = book.chapters.count { it.written }
        if (status.olderDeck.isNotEmpty()) {
            val all = status.olderDeck.size == written
            val which = if (all) "the guide was" else "${chapters(book, status.olderDeck)} ${if (status.olderDeck.size == 1) "was" else "were"}"
            add(
                "The deck has changed since $which written: the numbers $name typed there are from the older deck. " +
                    "The odds, the cells and the hands are worked out from the deck as it is.",
            )
        } else if (status.frontOnOlderDeck) {
            add("The deck has changed since the guide's roles were set: a card no role names is counted as Other.")
        }
        if (status.olderNotes.isNotEmpty()) {
            val all = status.olderNotes.size == written
            add(
                if (all) "$name's notes on this deck have changed since this was written."
                else "$name's notes on this deck have changed since ${chapters(book, status.olderNotes)} ${if (status.olderNotes.size == 1) "was" else "were"} written.",
            )
        }
    }

    /** "chapter 03", "chapters 02 and 05", "chapters 01, 02 and 05": by their place in the book. */
    fun chapters(book: GuideBook, ids: List<String>): String {
        val numbers = book.chapters.mapIndexedNotNull { i, c -> if (c.id in ids) (i + 1).toString().padStart(2, '0') else null }
        return when (numbers.size) {
            0 -> "no chapter"
            1 -> "chapter ${numbers[0]}"
            else -> "chapters ${numbers.dropLast(1).joinToString(", ")} and ${numbers.last()}"
        }
    }

    /**
     * The numbers Ai typed into [block] that depend on the deck — percentages, odds and probabilities in its words
     * ([Numbers.claimed]), and a lesson's number — as written. The pictures' own numbers are not among them: those
     * are worked out from the deck as it is.
     */
    fun typedNumbers(block: Block): List<String> {
        val texts = strings(GuideBook.json.encodeToJsonElement(Block.serializer(), block))
        val claimed = texts.flatMap { t -> Numbers.claimed(t).map { it.written } }
        val lesson = (block as? Block.Lesson)?.number?.trim()?.takeIf { n -> n.any { it.isDigit() } }
        return (claimed + listOfNotNull(lesson)).distinct()
    }

    /** Every string in a block's JSON but its id and its kind. */
    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.entries.flatMap { (k, v) -> if (k == "id" || k == "type") emptyList() else strings(v) }
        is JsonArray -> e.flatMap { strings(it) }
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
    }
}
