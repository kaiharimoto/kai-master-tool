package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How Ai writes a book (1.0.67, the `reader_guide` tool): the outline first, then a chapter at a
 * time, each checked before it is kept — a card that is not a card, a step with no card, a block of
 * the wrong shape is refused with what to fix. Pure: the app stores what comes back.
 */
object BookWriter {
    /** What a write did: the book as it is now (unchanged when refused) and the words Ai reads back. */
    data class Result(val book: GuideBook, val message: String, val ok: Boolean = true)

    /**
     * [known] gives a card's printed name for a name as written, or null when there is no such card;
     * [deck] is the open deck's main deck, a name a copy, for the facts. [deckPrint] is that deck's fingerprint
     * (`Ledger.fingerprint`) and [notesHash] Ai's notes' (`ReaderGuide.hashOf`), stamped on what is written (1.0.99):
     * what the reader checks the book against to say a chapter was written on an older deck ([BookFreshness]).
     */
    class Context(
        val known: (String) -> String?,
        val deck: List<String>?,
        val now: Long,
        val deckPrint: String = "",
        val notesHash: String = "",
    )

    /** The book's plan for Ai; with [ctx], each chapter written on an older deck says so, to be written again. */
    fun outline(book: GuideBook, ctx: Context? = null): String = buildString {
        val older = ctx?.let { BookFreshness.of(book, it.deckPrint, it.notesHash).olderDeck.toSet() }.orEmpty()
        appendLine("# ${book.title}" + if (book.subtitle.isNotBlank()) " — ${book.subtitle}" else "")
        if (book.bigIdea.isNotBlank()) appendLine("Big idea: ${book.bigIdea}")
        appendLine("Roles: " + if (book.roles.isEmpty()) "none yet (set_front)" else book.roles.joinToString("; ") { r -> "${r.name} ${r.cards.sumOf { it.copies }}" })
        if (book.chapters.isEmpty()) appendLine("No chapters yet: set_outline first.")
        book.chapters.forEachIndexed { i, c ->
            appendLine(
                "${i + 1}. [${c.id}] ${c.title} — " + (if (c.written) "${c.sections.size} sections, ${c.sections.sumOf { it.blocks.size }} blocks" else "planned") +
                    if (c.id in older) " — written on an older deck: check it and write it again" else "",
            )
            c.sections.forEach { s -> appendLine("   - [${s.id}] ${s.title} (${s.blocks.joinToString(", ") { kind(it) }})") }
        }
    }.trim()

    /** The order of the chapters and the ones still to write. Written chapters keep their sections; none is dropped here. */
    fun setOutline(book: GuideBook, chapters: List<JsonObject>): Result {
        if (chapters.isEmpty()) return Result(book, "set_outline needs chapters: each a title, and a summary of what it will hold.", false)
        val wanted = chapters.mapNotNull { o ->
            val title = o["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GuideBook.Chapter(id = o["id"]?.jsonPrimitive?.content.orEmpty(), title = title, summary = o["summary"]?.jsonPrimitive?.content.orEmpty())
        }
        val placed = wanted.map { w ->
            val had = book.chapters.firstOrNull { (w.id.isNotBlank() && it.id == w.id) || it.title.equals(w.title, ignoreCase = true) }
            had?.copy(title = w.title, summary = w.summary.ifBlank { had.summary }) ?: w
        }
        val kept = book.chapters.filter { c -> c.written && placed.none { it.id == c.id && c.id.isNotBlank() } && placed.none { it.title.equals(c.title, ignoreCase = true) } }
        val next = book.copy(chapters = placed + kept).withIds()
        val note = if (kept.isNotEmpty()) " Written chapters not in the outline were kept at the end: ${kept.joinToString { it.title }} (remove_chapter to drop one)." else ""
        return Result(next, "Outline set: ${next.chapters.size} chapters, ${next.chapters.count { it.written }} written.$note\n\n${outline(next)}")
    }

    /** The front of the book: its title, the line under it, the big idea, the deck by role. */
    fun setFront(book: GuideBook, input: JsonObject, ctx: Context): Result {
        fun str(key: String) = input[key]?.jsonPrimitive?.content
        val roles = (input["roles"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
            runCatching { arr.map { GuideBook.json.decodeFromJsonElement(ReaderGuide.Role.serializer(), it) } }.getOrElse {
                return Result(book, "roles did not read: each is {\"name\": \"Starters\", \"cards\": [{\"card\": \"…\", \"copies\": 3, \"note\": \"…\"}]}. ${it.message}", false)
            }
        }
        val unknown = roles.orEmpty().flatMap { r -> r.cards.map { it.card } }.filter { ctx.known(it) == null }
        if (unknown.isNotEmpty()) return Result(book, "Not cards: ${unknown.joinToString()}. Use names as printed (resolve_cards finds them).", false)
        val next = book.copy(
            title = str("title")?.takeIf { it.isNotBlank() } ?: book.title,
            subtitle = str("subtitle") ?: book.subtitle,
            bigIdea = str("big_idea") ?: book.bigIdea,
            roles = roles?.map { r -> r.copy(cards = r.cards.map { c -> c.copy(card = ctx.known(c.card) ?: c.card) }) } ?: book.roles,
            updatedAt = ctx.now,
            // The roles are what the deck is read through: they were set on this deck.
            deckPrint = if (roles != null) ctx.deckPrint else book.deckPrint,
        )
        return Result(next, "Front set." + mismatch(next, ctx))
    }

    /** One chapter written: replaces the chapter of the same id or title, or fills the planned one. */
    fun writeChapter(book: GuideBook, chapter: JsonElement?, ctx: Context): Result {
        val obj = chapter as? JsonObject ?: return Result(book, "write_chapter needs chapter: {\"title\", \"summary\", \"sections\": [{\"title\", \"blocks\": [...]}]}.", false)
        val read = runCatching { GuideBook.json.decodeFromJsonElement(GuideBook.Chapter.serializer(), obj) }.getOrElse {
            return Result(book, "The chapter did not read: ${it.message?.take(400)}. Each block needs \"type\": one of ${TYPES.joinToString()}.", false)
        }
        val problems = check(read, ctx)
        if (problems.isNotEmpty()) return Result(book, "Not kept — fix these and write it again:\n" + problems.joinToString("\n") { "- $it" }, false)
        // Written on this deck, from these notes: the app's stamp, whatever the chapter Ai sent says.
        val c = canonical(read, ctx).copy(deckPrint = ctx.deckPrint, notesHash = ctx.notesHash)
        val at = book.chapters.indexOfFirst { (c.id.isNotBlank() && it.id == c.id) || it.title.equals(c.title, ignoreCase = true) }
        val chapters = if (at >= 0) book.chapters.toMutableList().also { it[at] = c.copy(id = it[at].id) } else book.chapters + c
        val next = book.copy(chapters = chapters, updatedAt = ctx.now).withIds()
        val written = next.chapters.first { it.title.equals(c.title, ignoreCase = true) }
        val left = next.chapters.filter { !it.written }.map { it.title }
        return Result(
            next,
            "Kept \"${written.title}\": ${written.sections.size} sections, ${written.sections.sumOf { it.blocks.size }} blocks." +
                (if (left.isNotEmpty()) " Still to write: ${left.joinToString()}." else " Every chapter in the outline is written.") + mismatch(next, ctx),
        )
    }

    fun readChapter(book: GuideBook, id: String): Result {
        val c = book.chapters.firstOrNull { it.id == id || it.title.equals(id, ignoreCase = true) } ?: return Result(book, "No chapter $id. ${outline(book)}", false)
        return Result(book, GuideBook.json.encodeToString(GuideBook.Chapter.serializer(), c))
    }

    fun removeChapter(book: GuideBook, id: String): Result {
        val c = book.chapters.firstOrNull { it.id == id || it.title.equals(id, ignoreCase = true) } ?: return Result(book, "No chapter $id.", false)
        return Result(book.copy(chapters = book.chapters - c), "Removed \"${c.title}\".")
    }

    /** The numbers to quote, worked out: never write odds you have not read here. */
    fun facts(book: GuideBook, ctx: Context): String {
        val f = GuideFacts.of(book.roles, ctx.deck)
        if (f.deckSize == 0) return "No deck to count: set_front with roles first (the deck by job, every card with its copies)."
        return buildString {
            appendLine("Deck: ${f.deckSize} cards. " + f.roles.joinToString("; ") { "${it.name} ${it.count}" })
            appendLine("At least one ${f.roles.first().name.lowercase()} card: ${GuideFacts.percent(f.startFirst)} going first (5 cards), ${GuideFacts.percent(f.startSecond)} going second (6).")
            appendLine("No ${f.roles.first().name.lowercase()} card at all: ${GuideFacts.percent(f.brick)} of five-card hands.")
            f.roles.forEach { r -> appendLine("At least one of the ${r.count} ${r.name.lowercase()} in five: ${GuideFacts.percent(GuideFacts.odds(r.count, f.deckSize, 5))}.") }
            append(mismatch(book, ctx).trim())
        }.trim()
    }

    private val TYPES = listOf("text", "lesson", "odds", "cells", "engine", "line", "lanes", "board", "ledger", "hands", "checklist", "table", "cards", "callout")

    private fun kind(b: Block) = when (b) {
        is Block.Text -> "text"
        is Block.Lesson -> "lesson"
        is Block.Odds -> "odds"
        is Block.Cells -> "cells"
        is Block.Engine -> "engine"
        is Block.Line -> "line"
        is Block.Lanes -> "lanes"
        is Block.Board -> "board"
        is Block.Ledger -> "ledger"
        is Block.Hands -> "hands"
        is Block.Checklist -> "checklist"
        is Block.Table -> "table"
        is Block.CardNotes -> "cards"
        is Block.Callout -> "callout"
    }

    /** What is wrong with a chapter: names that are not cards, steps without a card, empty sections. */
    fun check(c: GuideBook.Chapter, ctx: Context): List<String> = buildList {
        if (c.sections.isEmpty()) add("A chapter needs sections.")
        c.sections.forEach { s ->
            if (s.blocks.isEmpty()) add("Section \"${s.title}\" has no blocks.")
            s.blocks.forEach { b ->
                when (b) {
                    is Block.Line -> b.line.steps.forEachIndexed { i, st -> if (st.card.isBlank()) add("Section \"${s.title}\": step ${i + 1} of \"${b.line.name}\" names no card.") }
                    is Block.Lanes -> if (b.line.steps.isEmpty()) add("Section \"${s.title}\": lanes with no steps.")
                    is Block.Table -> if (b.header.isEmpty()) add("Section \"${s.title}\": a table needs a header.")
                    is Block.Odds -> if (b.rows.isEmpty() || b.rows.any { it.cards.isEmpty() && it.role.isBlank() }) add("Section \"${s.title}\": each odds row needs cards or a role.")
                    else -> Unit
                }
            }
            val unknown = s.blocks.flatMap { it.cards() }.filter { it.isNotBlank() }.distinct().filter { ctx.known(it) == null }
            if (unknown.isNotEmpty()) add("Section \"${s.title}\": not cards — ${unknown.joinToString()}. Names as printed (resolve_cards finds them).")
        }
    }

    /** Card names as printed, wherever a block names one. */
    private fun canonical(c: GuideBook.Chapter, ctx: Context): GuideBook.Chapter {
        fun n(name: String) = if (name.isBlank()) name else ctx.known(name) ?: name
        fun line(l: ReaderGuide.Line) = l.copy(
            steps = l.steps.map { it.copy(card = n(it.card), stoppedBy = it.stoppedBy.map(::n)) },
            endBoard = l.endBoard.map(::n), endSet = l.endSet.map(::n),
        )
        return c.copy(sections = c.sections.map { s ->
            s.copy(blocks = s.blocks.map { b ->
                when (b) {
                    is Block.Lesson -> b.copy(card = n(b.card))
                    is Block.Odds -> b.copy(rows = b.rows.map { r -> r.copy(cards = r.cards.map(::n)) })
                    is Block.Engine -> b.copy(edges = b.edges.map { it.copy(from = n(it.from), to = n(it.to)) })
                    is Block.Line -> b.copy(line = line(b.line))
                    is Block.Lanes -> b.copy(line = line(b.line))
                    is Block.Board -> b.copy(up = b.up.map(::n), down = b.down.map(::n))
                    is Block.Ledger -> b.copy(side = b.side.copy(sideIn = b.side.sideIn.map(::n), sideOut = b.side.sideOut.map(::n), theirChoke = n(b.side.theirChoke)))
                    is Block.Hands -> b.copy(hands = b.hands.map { h -> h.copy(cards = h.cards.map(::n)) })
                    is Block.CardNotes -> b.copy(cards = b.cards.map { it.copy(card = n(it.card)) })
                    is Block.Callout -> b.copy(card = n(b.card))
                    else -> b
                }
            })
        })
    }

    /** The roles against the deck: a card the deck runs that no role names, a role's card the deck does not run. */
    private fun mismatch(book: GuideBook, ctx: Context): String {
        val deck = ctx.deck ?: return ""
        if (book.roles.isEmpty()) return ""
        val inRoles = book.roles.flatMap { r -> r.cards.map { it.card.lowercase() } }.toSet()
        val inDeck = deck.map { it.lowercase() }.toSet()
        val missing = deck.distinct().filter { it.lowercase() !in inRoles }
        val extra = book.roles.flatMap { r -> r.cards.map { it.card } }.filter { it.lowercase() !in inDeck }
        return buildString {
            if (missing.isNotEmpty()) append("\nIn the deck but in no role: ${missing.joinToString()} (set_front to place them).")
            if (extra.isNotEmpty()) append("\nIn a role but not in the deck: ${extra.joinToString()}.")
        }
    }
}

/** What changed between two versions of a book, section by section, for the person to keep or undo. */
object BookReview {
    fun diff(before: String?, after: String?): Pair<List<String>, List<String>> {
        val was = GuideBook.read(before)
        val now = GuideBook.read(after)
        fun entries(b: GuideBook?): Map<String, String> = b?.chapters.orEmpty().flatMap { c ->
            if (!c.written) listOf("${c.title} (planned)" to "") else c.sections.map { s -> "${c.title} › ${s.title}" to GuideBook.json.encodeToString(GuideBook.Section.serializer(), s) }
        }.toMap()
        val a = entries(was)
        val b = entries(now)
        val added = b.filter { (k, _) -> a[k] == null }.keys.map { "New: $it" } + b.filter { (k, v) -> a[k] != null && a[k] != v }.keys.map { "Rewritten: $it" }
        val removed = a.keys.filter { it !in b }
        val front = if (was != null && now != null && (was.bigIdea != now.bigIdea || was.roles != now.roles || was.title != now.title)) listOf("The front: title, big idea and roles") else emptyList()
        return (front + added) to removed
    }
}
