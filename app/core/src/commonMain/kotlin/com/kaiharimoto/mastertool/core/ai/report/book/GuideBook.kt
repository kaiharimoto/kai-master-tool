package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A deck's guide written for people, as a book (1.0.67, kai: "There should be no limit to the
 * length of the guides, but it must be well organized. A table of contents is crucial … true mastery
 * is way deeper and extensive, which is good, and why we need Ai").
 *
 * Chapters of sections of typed blocks, any length: Ai writes it a chapter at a time from what it
 * knows of the deck (its notes stay its own, `guides/<id>.md`); the app reads it in a reader, and
 * exports it as a PDF, as JSON (this file) and as HTML. Every chapter, section and block has an id,
 * so a reader's notes and a link survive a rewrite. Numbers are never written into it: the odds, the
 * forty cells and the sample hands are worked out from the deck as it is ([GuideFacts]).
 */
@Serializable
data class GuideBook(
    val title: String,
    /** Who played it and where, when it is a list from somewhere. */
    val subtitle: String = "",
    /** The one sentence to remember, under twenty words. */
    val bigIdea: String = "",
    /** The deck by job, every card with its copies: what the cells and the odds are drawn from. */
    val roles: List<ReaderGuide.Role> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val sources: List<String> = emptyList(),
    val updatedAt: Long = 0,
    /** Which version of Ai's notes it was last written from (every write stamps it; each chapter keeps its own, [Chapter.notesHash]). */
    val notesHash: String = "",
    /**
     * The deck its front — the roles the cells and odds are read through — was set on, as `Ledger.fingerprint` (1.0.99).
     * Empty in a book written before the app recorded it: the deck is then unknown, never stale for ever ([BookFreshness]).
     */
    val deckPrint: String = "",
) {
    /**
     * A chapter; planned (its outline set, nothing written yet) while it has no sections. [deckPrint] and [notesHash] are
     * the deck and Ai's notes it was written on (1.0.99), stamped by the app as it is written, never taken from what Ai sends.
     */
    @Serializable
    data class Chapter(
        val id: String = "",
        val title: String,
        val summary: String = "",
        val sections: List<Section> = emptyList(),
        val deckPrint: String = "",
        val notesHash: String = "",
    ) {
        val written: Boolean get() = sections.isNotEmpty()
    }

    @Serializable
    data class Section(val id: String = "", val title: String, val blocks: List<Block> = emptyList())

    val isEmpty: Boolean get() = chapters.none { it.written } && bigIdea.isBlank()

    /** Every card the book names, first mention first: what the pictures fetch art for. */
    fun cards(): List<String> = buildList {
        roles.forEach { r -> r.cards.forEach { add(it.card) } }
        chapters.forEach { c -> c.sections.forEach { s -> s.blocks.forEach { addAll(it.cards()) } } }
    }.filter { it.isNotBlank() }.distinct()

    /** Ids where they are missing: a chapter's from its title, a section's and a block's from their place. */
    fun withIds(): GuideBook {
        val taken = HashSet<String>()
        fun unique(base: String): String {
            var id = base.ifBlank { "x" }
            var n = 2
            while (!taken.add(id)) id = "$base-${n++}"
            return id
        }
        return copy(
            chapters = chapters.map { c ->
                val cid = unique(c.id.ifBlank { slug(c.title) })
                c.copy(
                    id = cid,
                    sections = c.sections.mapIndexed { si, s ->
                        val sid = unique(s.id.ifBlank { "$cid/${slug(s.title).ifBlank { "s${si + 1}" }}" })
                        s.copy(id = sid, blocks = s.blocks.mapIndexed { bi, b -> if (b.id.isBlank()) b.withId(unique("$sid/${bi + 1}")) else b.also { taken += it.id } })
                    },
                )
            },
        )
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            classDiscriminator = "type"
            explicitNulls = false
        }

        private val pretty = Json(json) { prettyPrint = true }

        /** Where a deck's book is kept, beside Ai's notes on it: `guides/<deck>.book.json`, deleted with the deck. */
        fun path(deckId: String): String = "guides/${com.kaiharimoto.mastertool.core.ai.memory.AiMemory.safeId(deckId)}.book.json"

        fun read(text: String?): GuideBook? = text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        fun write(book: GuideBook): String = pretty.encodeToString(serializer(), book)

        /** "Where it breaks" as an id: lower case, words joined by dashes. */
        fun slug(text: String): String =
            text.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").split('-').filter { it.isNotEmpty() }.joinToString("-").take(40)
    }
}

/**
 * One block of a section. Text blocks are words; the rest are pictures, laid out by the app from
 * their data and the deck's facts. Ai writes them as JSON, `"type"` naming the kind.
 */
@Serializable
sealed interface Block {
    val id: String

    fun withId(id: String): Block
    fun cards(): List<String> = emptyList()

    /** Words, with `[[Card]]` and `**bold**`; [label] in the margin ("Why", "In practice"). */
    @Serializable
    @SerialName("text")
    data class Text(val text: String, val label: String = "", override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = CARD.findAll(text).map { it.groupValues[1] }.toList()
    }

    /** A lesson: the maxim in display type, the card it is about, one number that proves it. */
    @Serializable
    @SerialName("lesson")
    data class Lesson(val maxim: String, val card: String = "", val number: String = "", val label: String = "", override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = listOf(card)
    }

    /** The chance to open at least one of each row's cards (or a role's), worked out: one row a big number, more a comparison. */
    @Serializable
    @SerialName("odds")
    data class Odds(val rows: List<Row>, val hand: Int = 5, val title: String = "", override val id: String = "") : Block {
        @Serializable
        data class Row(val label: String, val cards: List<String> = emptyList(), val role: String = "")

        override fun withId(id: String) = copy(id = id)
        override fun cards() = rows.flatMap { it.cards }
    }

    /** The deck as cells, one a card, filled by role. */
    @Serializable
    @SerialName("cells")
    data class Cells(override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
    }

    /** How the cards find each other. */
    @Serializable
    @SerialName("engine")
    data class Engine(val edges: List<ReaderGuide.Edge>, override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = edges.flatMap { listOf(it.from, it.to) }
    }

    /** A line step by step, with its choke points; [frames] draws the board after each play under it. */
    @Serializable
    @SerialName("line")
    data class Line(val line: ReaderGuide.Line, val frames: Boolean = true, override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = line.steps.flatMap { listOf(it.card) + it.stoppedBy } + line.endBoard + line.endSet
    }

    /** A line as two lanes, your turn and theirs. */
    @Serializable
    @SerialName("lanes")
    data class Lanes(val line: ReaderGuide.Line, override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = line.steps.map { it.card }
    }

    /** A field: [up] face up in the Monster Zones, [down] set. */
    @Serializable
    @SerialName("board")
    data class Board(val up: List<String> = emptyList(), val down: List<String> = emptyList(), val caption: String = "", override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = up + down
    }

    /** One matchup's siding as signed counts, their key card, your plan. */
    @Serializable
    @SerialName("ledger")
    data class Ledger(val side: ReaderGuide.Side, override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = side.sideIn + side.sideOut + side.theirChoke
    }

    /** Opening hands: [hands] written out (a puzzle, with its answer), or none for sample hands dealt from the deck. */
    @Serializable
    @SerialName("hands")
    data class Hands(val hands: List<Hand> = emptyList(), override val id: String = "") : Block {
        @Serializable
        data class Hand(val cards: List<String>, val verdict: String = "", val answer: String = "")

        override fun withId(id: String) = copy(id = id)
        override fun cards() = hands.flatMap { it.cards }
    }

    @Serializable
    @SerialName("checklist")
    data class Checklist(val items: List<String>, val title: String = "", override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
    }

    @Serializable
    @SerialName("table")
    data class Table(val header: List<String>, val rows: List<List<String>>, override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
    }

    /** Cards as museum labels: the card, its copies, what it is for. */
    @Serializable
    @SerialName("cards")
    data class CardNotes(val cards: List<ReaderGuide.RoleCard>, override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = cards.map { it.card }
    }

    /** A boxed aside: a tip, a misplay, a ruling, a note; about [card] when there is one. */
    @Serializable
    @SerialName("callout")
    data class Callout(val text: String, val kind: String = "tip", val card: String = "", override val id: String = "") : Block {
        override fun withId(id: String) = copy(id = id)
        override fun cards() = listOf(card) + CARD.findAll(text).map { it.groupValues[1] }
    }

    companion object {
        private val CARD = Regex("""\[\[([^\]]+)]]""")
    }
}
