package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.LearnTools
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.course.Chapter
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.DbReplays
import com.kaiharimoto.mastertool.core.ai.course.CoursePaths
import com.kaiharimoto.mastertool.core.ai.course.CourseSearch
import com.kaiharimoto.mastertool.core.ai.course.CourseText
import com.kaiharimoto.mastertool.core.ai.course.Sections
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.playbook.Play
import com.kaiharimoto.mastertool.core.ai.playbook.Playbook
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookCodec
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookEdits
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookPaths
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookSearch
import com.kaiharimoto.mastertool.core.ai.playbook.Source
import com.kaiharimoto.mastertool.core.ai.playbook.Step
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.NeueHolders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** [deckId]'s playbook as kept; an empty one when none is, null when its file cannot be read. */
internal fun AiState.playbook(deckId: String): Playbook? = PlaybookCodec.read(files.read(PlaybookPaths.of(deckId)), deckId)

/**
 * The learning tools (mastery, 1.1.43, `LearnTools`): the deck's playbook searched, read and written, and every course
 * studied for the deck searched and opened as a reference — in a conversation, in Fine Tuning, in a course study and at
 * the table. The deck is the one asked for, else the study's, else the one Ai plays at the table, else the builder's.
 * A course's held-out replays are never shown.
 */
internal class AiLearn(private val h: NeueHolders, private val ai: AiState) {
    private val files get() = ai.files

    /**
     * Answers [name] for [deckId] (the deck it is about, already chosen), or null when it is not a learning tool.
     * [sources] are what a number in a playbook entry is proven against ([Evidence.judge]); [sourced] makes every entry
     * say where it was learned.
     */
    suspend fun run(name: String, i: JsonObject, deckId: String?, sources: () -> List<Evidence.Source>, sourced: Boolean = true): MetaAnswer? {
        if (name !in NAMES) return null
        // The replay library is every deck's: no deck needed (1.1.48).
        if (name == "replay_library") return replayLibrary(ToolArgs.string(i, "query").orEmpty(), ToolArgs.string(i, "open"), ToolArgs.string(i, "what") ?: "text", ToolArgs.int(i, "from") ?: 0)
        val deck = ToolArgs.string(i, "deck_id")?.trim()?.takeIf { it.isNotEmpty() } ?: deckId
            ?: return fail("No deck in view: open one, or name it with deck_id.")
        return when (name) {
            "playbook_search" -> search(deck, i)
            "playbook_read" -> read(deck, ToolArgs.strings(i, "ids"))
            "playbook_write" -> write(deck, i, sources, sourced)
            "playbook_gaps" -> gaps(deck)
            "course_search" -> courseSearch(deck, ToolArgs.string(i, "query").orEmpty(), ToolArgs.int(i, "limit") ?: 20)
            "course_open" -> courseOpen(deck, ToolArgs.string(i, "ref").orEmpty(), ToolArgs.int(i, "from") ?: 0, ToolArgs.string(i, "course"))
            else -> null
        }
    }

    // ---- the playbook -------------------------------------------------------------------------------------------

    fun book(deckId: String): Playbook? = ai.playbook(deckId)

    private fun save(book: Playbook) {
        files.write(PlaybookPaths.of(book.deckId), PlaybookCodec.write(book))
        // The playbook in words beside it: what the Library shows the person, and what `recall` searches.
        files.write(PlaybookPaths.of(book.deckId).removeSuffix(".json") + ".md", words(book))
    }

    private fun words(book: Playbook): String = buildString {
        appendLine("# Playbook · ${deckName(book.deckId)}")
        Play.Kind.entries.forEach { kind ->
            val of = book.entries.filter { it.kind == kind }
            if (of.isEmpty()) return@forEach
            appendLine()
            appendLine("## ${kind.word.replaceFirstChar { it.uppercase() }}s")
            of.forEach { appendLine(); appendLine(PlaybookSearch.render(it)) }
        }
    }

    private fun search(deck: String, i: JsonObject): MetaAnswer {
        val book = book(deck) ?: return fail(UNREADABLE)
        if (book.entries.isEmpty()) return ok("The playbook for ${deckName(deck)} is empty: nothing has been learned into it yet.", "Searched the playbook")
        val kind = ToolArgs.string(i, "kind")?.let { Play.Kind.of(it) }
        val page = PlaybookSearch.page(
            book, ToolArgs.string(i, "query").orEmpty(), kind, ToolArgs.strings(i, "cards"), ToolArgs.string(i, "source"),
            ToolArgs.int(i, "from") ?: 0, ToolArgs.int(i, "limit") ?: 20,
        )
        val hits = page.hits
        if (hits.isEmpty()) {
            return ok(
                if (page.total > 0) "No more: the ${page.total} matches end before ${page.from + 1}." else "Nothing in the playbook (${book.size} entries) matches. playbook_search with no query lists them.",
                "Searched the playbook",
            )
        }
        val counts = Play.Kind.entries.mapNotNull { k -> book.entries.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.word}s" } }.joinToString()
        val end = page.from + hits.size
        // Every match is reachable: the rest a page at a time, said, never silently left out.
        val more = if (end < page.total) "\n(${page.total - end} more: playbook_search again with from = $end.)" else ""
        return ok("Playbook for ${deckName(deck)} — $counts. Matches ${page.from + 1}–$end of ${page.total}:\n" + hits.joinToString("\n") { PlaybookSearch.line(it.play) } + more,
            "Searched the playbook: ${page.total} found")
    }

    private fun read(deck: String, ids: List<String>): MetaAnswer {
        val book = book(deck) ?: return fail(UNREADABLE)
        if (ids.isEmpty()) return fail("Name the entries to read (ids from playbook_search).")
        val found = ids.take(20).map { id -> book.entry(id)?.let(PlaybookSearch::render) ?: "No entry $id." }
        return ok(found.joinToString("\n\n"), "Read ${ids.size} playbook ${if (ids.size == 1) "entry" else "entries"}")
    }

    private fun write(deck: String, i: JsonObject, sources: () -> List<Evidence.Source>, sourced: Boolean): MetaAnswer {
        val book = book(deck) ?: return fail(UNREADABLE)
        val now = System.currentTimeMillis()
        val out = when (ToolArgs.string(i, "op")) {
            "add" -> {
                val entries = (i["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?: (i["entry"] as? JsonObject)?.let(::listOf) ?: return fail("op add needs entries: [{kind, title, …}].")
                PlaybookEdits.add(book, entries.map(::draft), now, sourced)
            }
            "update" -> {
                val id = ToolArgs.string(i, "id") ?: return fail("op update needs the id.")
                val e = i["entry"] as? JsonObject ?: return fail("op update needs entry: the fields to change.")
                PlaybookEdits.update(book, id, draft(e), now, sourced)
            }
            "merge" -> PlaybookEdits.merge(book, ToolArgs.string(i, "keep") ?: return fail("op merge needs keep."), ToolArgs.strings(i, "fold"), now)
            "remove" -> PlaybookEdits.remove(book, ToolArgs.string(i, "id") ?: return fail("op remove needs the id."))
            else -> return fail("op is add, update, merge or remove.")
        }
        if (out.changed.isEmpty()) return fail(out.message)
        // A number in an entry carries its proof, as in the guide (1.0.98): one nobody computed or said is refused.
        val pool by lazy { sources() }
        // Only words that are new are judged: an update that adds a source, and a merge, keep what was already proven.
        val refused = out.changed.mapNotNull { out.book.entry(it) }
            .filter { p -> ToolArgs.string(i, "op") != "merge" && book.entry(p.id)?.let(::text) != text(p) }
            .mapNotNull { p -> (Evidence.judge(text(p), pool, "", now) as? Evidence.Verdict.Refused)?.let { p.id to it.message } }
        if (refused.isNotEmpty()) {
            return fail("Not written — " + refused.joinToString("; ") { (id, why) -> "$id: $why" })
        }
        save(out.book)
        return ok(out.message, out.said.firstOrNull()?.let { if (out.said.size > 1) "Playbook: ${out.changed.size} entries written" else it } ?: "Wrote the playbook")
    }

    /** An entry's words, every field: what its numbers are judged in. */
    private fun text(p: Play) = listOf(p.title, p.body, p.situation, p.choice, p.why, p.endBoard, p.through.joinToString(" "), p.weakTo.joinToString(" "),
        p.steps.joinToString(" ") { it.action + " " + it.result }).joinToString("\n")

    private fun draft(o: JsonObject): PlaybookEdits.Draft {
        fun str(k: String) = ToolArgs.string(o, k)
        fun list(k: String) = if (o[k] != null) ToolArgs.strings(o, k) else null
        val steps = (o["steps"] as? JsonArray)?.mapNotNull { e ->
            when (e) {
                is JsonObject -> Step(str2(e, "card"), str2(e, "action"), str2(e, "result"))
                is JsonPrimitive -> e.contentOrNull?.let { t -> Step(t.substringBefore(':').trim(), t.substringAfter(':', t).substringBefore('→').trim(), t.substringAfter('→', "").trim()) }
                else -> null
            }
        }
        val sources = (o["sources"] as? JsonArray)?.mapNotNull { e ->
            when (e) {
                is JsonObject -> Source(str2(e, "ref"), str2(e, "note"))
                is JsonPrimitive -> e.contentOrNull?.let { Source(it) }
                else -> null
            }
        } ?: str("source")?.let { listOf(Source(it)) }
        return PlaybookEdits.Draft(
            kind = str("kind")?.let { Play.Kind.of(it) },
            title = str("title"), body = str("body"), cards = list("cards"), needs = list("needs"), steps = steps,
            endBoard = str("end_board") ?: str("endBoard"), through = list("through"), weakTo = list("weak_to") ?: list("weakTo"),
            situation = str("situation"), choice = str("choice"), why = str("why"), against = str("against"),
            going = str("going")?.let { Play.Going.of(it) }, sources = sources, confidence = str("confidence")?.let { Play.Confidence.of(it) },
        )
    }

    private fun str2(o: JsonObject, k: String) = ToolArgs.string(o, k).orEmpty()

    private suspend fun gaps(deck: String): MetaAnswer {
        val book = book(deck) ?: return fail(UNREADABLE)
        val cards = deckCards(deck)
        val known = book.entries.filter { it.kind == Play.Kind.CARD }.flatMap { it.cards + it.title }.map(PlaybookSearch::norm).toSet()
        val noEntry = cards.filter { PlaybookSearch.norm(it) !in known }
        val lines = book.entries.filter { it.kind == Play.Kind.LINE }
        val decisions = book.entries.filter { it.kind == Play.Kind.DECISION }
        val text = buildString {
            appendLine("The playbook for ${deckName(deck)}: " + Play.Kind.entries.joinToString { k -> "${book.entries.count { it.kind == k }} ${k.word}s" } + ".")
            if (cards.isNotEmpty()) appendLine("Cards in the deck with no card entry (${noEntry.size} of ${cards.size}): " + noEntry.joinToString().ifBlank { "none" })
            lines.filter { it.through.isEmpty() }.takeIf { it.isNotEmpty() }?.let { appendLine("Lines saying nothing on what they play through: " + it.joinToString { l -> l.id }) }
            lines.filter { it.weakTo.isEmpty() }.takeIf { it.isNotEmpty() }?.let { appendLine("Lines saying nothing on what stops them: " + it.joinToString { l -> l.id }) }
            decisions.filter { it.sources.size <= 1 }.takeIf { it.isNotEmpty() }?.let { appendLine("Decisions resting on one source: " + it.joinToString { d -> d.id }) }
            book.entries.filter { it.confidence == Play.Confidence.INFERRED && it.sources.size <= 1 }.takeIf { it.isNotEmpty() }
                ?.let { appendLine("Inferred and confirmed by nothing yet: " + it.joinToString { e -> e.id }) }
            if (book.entries.none { it.kind == Play.Kind.MATCHUP }) appendLine("No matchup entries yet.")
            if (lines.none { it.going == Play.Going.SECOND } && decisions.none { it.going == Play.Going.SECOND }) appendLine("Nothing yet on playing going second.")
        }
        return ok(text.trim(), "Counted the playbook's gaps")
    }

    /** The names of the cards in [deck], each once. */
    private suspend fun deckCards(deck: String): List<String> {
        val d: Deck = if (deck == h.builder.deckId) h.builder.deck else h.deps.deckRepository.byId(deck)?.entry?.deck ?: return emptyList()
        return (d.main + d.extra + d.side).distinct().mapNotNull { h.builder.index.byId(it)?.name }.distinct()
    }

    private fun deckName(deck: String): String =
        if (deck == h.builder.deckId) h.builder.deckName.ifBlank { "this deck" } else ai.courses.courses().firstOrNull { it.deckId == deck }?.deckName?.ifBlank { null } ?: "this deck"

    // ---- the courses, as a reference ----------------------------------------------------------------------------------

    private fun coursesFor(deck: String): List<Course> = ai.courses.courses().filter { it.deckId == deck && it.listed }

    /**
     * The DuelingBook replays any course of [deck] holds out for its exam: never read through another course that links to
     * the same duel (1.1.47).
     */
    private fun heldOut(deck: String): Set<String> =
        coursesFor(deck).flatMap { c -> c.replays.filter { it.exam }.mapNotNull { DbReplays.id(it.url) } }.toSet()

    /** Every document of [c] a reader may see: chapters and their notes, replays (never the exam's) and their notes. */
    private fun docs(c: Course, prefix: String, held: Set<String> = emptySet()): List<CourseSearch.Doc> = buildList {
        c.chapters.forEach { ch ->
            files.read(CoursePaths.page(c.id, ch.n))?.let { add(CourseSearch.Doc("${prefix}ch. ${ch.n}", ch.title, it)) }
            files.read(CoursePaths.notes(c.id, ch.n))?.let { add(CourseSearch.Doc("${prefix}ch. ${ch.n} notes", ch.title, it)) }
        }
        c.replays.filter { !it.exam && DbReplays.id(it.url) !in held }.forEach { r ->
            files.read(CoursePaths.replayText(c.id, r.n))?.let { add(CourseSearch.Doc("${prefix}replay ${r.n}", r.players, it)) }
            files.read(CoursePaths.replayNotes(c.id, r.n))?.let { add(CourseSearch.Doc("${prefix}replay ${r.n} notes", r.players, it)) }
        }
    }

    private fun courseSearch(deck: String, query: String, limit: Int): MetaAnswer {
        if (query.isBlank()) return fail("Say what to find.")
        val courses = coursesFor(deck)
        if (courses.isEmpty()) return ok("No course has been studied for ${deckName(deck)}.", "Searched the courses")
        val many = courses.size > 1
        val held = heldOut(deck)
        val docs = courses.flatMap { c -> docs(c, if (many) "${c.id}: " else "", held) }
        val hits = CourseSearch.search(docs, query, limit)
        if (hits.isEmpty()) return ok("Nothing in ${courses.joinToString { it.label }} matches “$query”.", "Searched the course")
        val text = hits.joinToString("\n") { "${it.ref}${if (it.title.isNotBlank()) " (${it.title.take(60)})" else ""}, line ${it.line}: ${it.excerpt}" } +
            "\n(course_open with the ref reads it whole.)"
        return ok(Untrusted.wrap(courses.first().label, text), "Searched the course: ${hits.size} found")
    }

    private fun courseOpen(deck: String, ref: String, from: Int, courseId: String?): MetaAnswer {
        val courses = coursesFor(deck)
        val c = courseId?.let { id -> courses.firstOrNull { it.id == id } } ?: courses.firstOrNull { ref.startsWith(it.id + ":") } ?: courses.firstOrNull()
            ?: return fail("No course has been studied for ${deckName(deck)}.")
        val r = ref.substringAfter("${c.id}:").trim().lowercase()
        if (r == "contents" || r.isBlank()) {
            return ok(buildString {
                appendLine("${c.label}${if (c.author.isNotBlank()) " by ${c.author}" else ""} — ${c.chapters.size} chapters, ${c.studied.size} replays.")
                c.chapters.forEach { appendLine("ch. ${it.n} ${it.title}" + if (it.state == Chapter.State.NOTED) " (noted)" else "") }
                c.studied.forEach { appendLine("replay ${it.n} (ch. ${it.chapter}) ${it.players}") }
            }.trim(), "Read the course's contents")
        }
        val notes = r.endsWith("notes")
        val n = Regex("""\d+""").find(r)?.value?.toIntOrNull() ?: return fail("Name a chapter (ch. 4) or a replay (replay 12), or contents.")
        val (path, label) = when {
            r.startsWith("replay") -> {
                val rp = c.replay(n) ?: return fail("No replay $n.")
                if (rp.exam || DbReplays.id(rp.url) in heldOut(deck)) return fail("Replay $n is held out: it is the exam, and is never read before it.")
                (if (notes) CoursePaths.replayNotes(c.id, n) else CoursePaths.replayText(c.id, n)) to "replay $n"
            }
            else -> {
                c.chapter(n) ?: return fail("No chapter $n.")
                (if (notes) CoursePaths.notes(c.id, n) else CoursePaths.page(c.id, n)) to "ch. $n"
            }
        }
        val text = files.read(path) ?: return fail("Nothing kept for $label${if (notes) " notes" else ""} yet.")
        val shown = if (notes) text else Sections.numbered(text)
        return ok(Untrusted.wrap("${c.label}, $label${if (notes) " (notes)" else ""}", CourseText.part(shown, from)), "Read ${c.label}, $label")
    }

    // ---- the replay library (1.1.48) ------------------------------------------------------------------------------

    private fun replayLibrary(query: String, open: String?, what: String, from: Int): MetaAnswer {
        val shelf = ai.replays
        // Never a replay held out for an exam: what Ai is asked stays unread.
        val all = shelf.entries().filter { !it.heldOut }
        if (open.isNullOrBlank()) {
            val found = shelf.search(all, query)
            if (found.isEmpty()) return ok(if (all.isEmpty()) "No replay is kept yet." else "No kept replay matches “$query”.", "Searched the replay library")
            val text = found.take(60).joinToString("\n") { e ->
                "[${e.id}] ${e.label}" + (if (e.games > 0) ", ${e.games} game${if (e.games == 1) "" else "s"}" else "") +
                    (if (e.added) " — added by the person" + (if (e.note.isNotBlank()) ": ${e.note}" else "") else " — ${e.courseLabel}, replay ${e.n} (ch. ${e.chapter}), ${e.state}")
            } + (if (found.size > 60) "\n(${found.size - 60} more: narrow the query.)" else "") + "\n(replay_library with open = the id reads one.)"
            return ok(Untrusted.wrap("the replay library", text), "Searched the replay library: ${found.size} found")
        }
        val e = all.firstOrNull { it.id == open.trim().removePrefix("[").removeSuffix("]") } ?: return fail("No replay ${open.trim()} in the library (or it is held out for an exam).")
        val notes = what == "notes"
        val text = (if (notes) shelf.notes(e) else shelf.words(e)) ?: return fail(if (notes) "No notes were taken on that replay." else "That replay could not be read.")
        return ok(Untrusted.wrap("DuelingBook replay ${e.id} (${e.url})" + if (notes) " (notes)" else "", CourseText.part(text, from)), "Read replay ${e.label}")
    }

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    companion object {
        val NAMES = LearnTools.names
        const val UNREADABLE = "The playbook file could not be read: it is left as it is, never written over. Tell the person."
    }
}
