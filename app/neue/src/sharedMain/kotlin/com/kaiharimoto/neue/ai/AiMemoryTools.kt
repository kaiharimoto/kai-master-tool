package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.Recall
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Numbers
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.GuideBudget
import com.kaiharimoto.mastertool.core.ai.memory.GuideRewrite
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryQuery
import com.kaiharimoto.mastertool.core.ai.memory.MemoryReview
import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.SessionQuestions
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.mastertool.core.ai.report.book.BookWriter
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.ai.skills.Skill
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.ai.vision.ReadCards
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.course.StudyRun
import com.kaiharimoto.neue.prep.ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * Ai's memory and its writing (split out of [AiHost], Phase H, H.3): the memory files read and written in scope, skills,
 * past sessions, recall, the reader's guide and the session report. A course study's own deck and evidence when one runs
 * ([StudyRun]); else the deck in view.
 */
internal class AiMemoryTools(private val h: NeueHolders, private val ai: AiState, private val host: AiHost) {
    private val state get() = h.builder
    private val neue get() = h.neue
    private val webs get() = h.webs
    private val index get() = state.index

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun ok(content: JsonElement, summary: String) = MetaAnswer(content.toString(), summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    private suspend fun stored(id: String): StoredDeck? = h.deps.deckRepository.byId(id)

    suspend fun run(name: String, i: JsonObject, study: StudyRun? = null): MetaAnswer? = when (name) {
        "memory" -> memory(ToolArgs.string(i, "action")!!, ToolArgs.string(i, "scope")!!, ToolArgs.string(i, "text"), ToolArgs.string(i, "old_text"), study)
        "memory_read" -> memoryRead(
            ToolArgs.string(i, "scope")!!, ToolArgs.string(i, "id"), ToolArgs.string(i, "query"), ToolArgs.string(i, "label"),
            ToolArgs.int(i, "from"), ToolArgs.int(i, "count"), study,
        )
        "skill_view" -> skillView(ToolArgs.string(i, "name")!!)
        "skill_manage" -> skillManage(i)
        "session_search" -> sessionSearch(ToolArgs.string(i, "query")!!, ToolArgs.int(i, "limit") ?: 12)
        "session_report" -> sessionReport(i)
        "reader_guide" -> readerGuide(i)
        "resolve_cards" -> resolveCards(ToolArgs.objects(i, "cards"))
        "recall" -> recall(ToolArgs.string(i, "query").orEmpty(), ToolArgs.string(i, "scope") ?: "this", ToolArgs.int(i, "limit") ?: 8)
        else -> null
    }

    /**
     * The deck a guide, report or book write is about (1.0.98, the red team): a Fine Tuning run's own deck to its end,
     * whatever the builder shows meanwhile; else the builder's.
     */
    private val pinned: AiSession? get() = ai.session?.takeIf { it.mode in AiSession.DECK_MODES && it.deckId != null }
    private val aboutId: String? get() = pinned?.deckId ?: state.deckId
    private val aboutName: String get() = pinned?.deckName ?: state.deckName

    private fun memoryTarget(scope: String, id: String? = null, study: StudyRun? = null): Triple<MemoryKind, String?, String>? = when (scope) {
        "user" -> Triple(MemoryKind.USER, null, ai.name)
        "agent" -> Triple(MemoryKind.AGENT, null, ai.name)
        // How the open deck plays (1.0.48): its own file, in a web or not. A course study writes the guide of the deck it
        // was started for, whatever the builder shows meanwhile.
        "guide" -> if (study != null) Triple(MemoryKind.GUIDE, study.deckId, study.deckName) else (id ?: aboutId)?.let { Triple(MemoryKind.GUIDE, it, if (it == aboutId) aboutName else "this deck") }
        "web" -> (id?.let { webs.library.byId(it) } ?: host.scope()?.takeIf { it.kind == MemoryKind.WEB }?.let { webs.library.byId(it.id) })
            ?.let { Triple(MemoryKind.WEB, it.id, it.name) }
        "deck" -> {
            val here = host.scope()
            when {
                id != null -> Triple(MemoryKind.DECK, id, state.deckName)
                // A deck in a web keeps its notes in the web's file.
                here?.kind == MemoryKind.WEB -> Triple(MemoryKind.WEB, here.id, here.name)
                here != null -> Triple(MemoryKind.DECK, here.id, here.name)
                else -> null
            }
        }
        else -> null
    }

    /**
     * The guide's ledger after a write (1.0.98): each new entry judged against what this conversation computed and what
     * the person said ([Evidence.judge]); an entry that kept a number from the one it replaces, or from the guide as it
     * was, keeps that number's proof. A failure is the refusal, in words for Ai.
     */
    private fun proveGuide(deckId: String, before: List<String>, after: List<String>, replaced: String?, study: StudyRun? = null): Result<List<Proven>> = runCatching {
        val was = Ledger.read(ai.files.read(Ledger.path(deckId)))
        val deck = if (deckId == state.deckId) Ledger.fingerprint(state.deck, index::byId) else ""
        val now = System.currentTimeMillis()
        // What the guide already held is a source for the numbers it keeps: their proofs go with them.
        val carried = before.joinToString("\n")
        val sources = Evidence.sources(study?.evidence() ?: ai.session?.turns.orEmpty()) + Evidence.Source(Evidence.CARRIED, "", carried)
        var next = Ledger.prune(was, after)
        // A set: a guide of thousands of entries is compared in one pass (1.1.11).
        val held = before.toHashSet()
        // A number kept from the guide lends the proof and status its own record had — never more (1.1.52: an author's number
        // re-read from the guide came out checked, its "(per …)" dropped).
        val records = was.filter { it.entry == replaced || it.entry in held }
        after.filter { it !in held }.forEach { entry ->
            when (val v = Evidence.judge(entry, sources, deck, now, carried = records)) {
                Evidence.Verdict.Words -> Unit
                is Evidence.Verdict.Refused -> error(v.message)
                is Evidence.Verdict.Proved -> next = Ledger.put(next, v.proven, replaced)
            }
        }
        next
    }

    private fun memory(action: String, scope: String, text: String?, old: String?, study: StudyRun? = null): MetaAnswer {
        // A course study keeps to the deck's guide: no notes on the person, no lessons of Ai's from someone else's words.
        if (study != null && scope != "guide") return fail("A course study writes the deck's guide only.")
        val (kind, id, name) = memoryTarget(scope, study = study) ?: return fail(
            if (scope == "web") "No web is in scope: the open deck is in no web, and none is on screen." else "Save the deck first; a deck never saved has no notes or guide yet.",
        )
        val doc = ai.files.memory(kind, id, name)
        val inWebForDeck = scope == "deck" && kind == MemoryKind.WEB
        val entry = text?.let { if (inWebForDeck && !it.startsWith("[")) "[${state.deckName}] $it" else it }
        val write = when (action) {
            "add" -> AiMemory.add(doc, entry ?: return fail("add needs text."), kind.limit, kind.entryLimit)
            "replace" -> AiMemory.replace(doc, old ?: return fail("replace needs old_text."), entry ?: return fail("replace needs text."), kind.limit, kind.entryLimit)
            "remove" -> AiMemory.remove(doc, old ?: text ?: return fail("remove needs old_text."))
            // The whole guide at once (1.0.66): only in Refactor guide, which ends in the person's review.
            "rewrite" -> when {
                kind != MemoryKind.GUIDE -> return fail("rewrite is for the guide only.")
                // A study is never the person's Refactor guide, whatever the panel shows meanwhile (1.1.52).
                study != null || ai.session?.mode != AiSession.MODE_REFACTOR -> return fail("rewrite is for Refactor guide. Here, use replace and remove.")
                else -> GuideRewrite.rewrite(doc, text ?: return fail("rewrite needs text: the whole guide."), kind.entryLimit)
            }
            else -> return fail("Actions: add, replace, remove.")
        }
        // The guide's numbers carry their proof (1.0.98, the evidence ledger): a percentage or odds nobody computed is refused.
        var ledger: List<Proven>? = null
        if (write is MemoryWrite.Done && kind == MemoryKind.GUIDE && id != null) {
            ledger = proveGuide(id, doc.entries, write.doc.entries, old, study).getOrElse { return fail(it.message ?: "Not written.") }
        }
        // What one Fine Tuning run may add to the guide, by its intensity (1.0.66: Deep, 20,000 characters).
        if (write is MemoryWrite.Done && kind == MemoryKind.GUIDE && action != "rewrite") {
            (if (study != null) study.guideRoom else ai.guideRoom())?.let { (start, budget, label) ->
                GuideBudget.refusal(start, write.doc.used, budget, label)?.let {
                    // The run is full, never the guide (1.1.9): the person is told when the run ends, and the next run carries on.
                    if (study != null) study.filled = GuideBudget.filled(budget, label) else ai.guideFilled = GuideBudget.filled(budget, label)
                    return fail(it)
                }
            }
        }
        return when (write) {
            is MemoryWrite.Done -> {
                ai.files.save(kind, id, write.doc)
                if (ledger != null && id != null) ai.files.write(Ledger.path(id), Ledger.write(ledger))
                // A study's own change to the guide, written down: its review lists what the study wrote, and Undo all takes
                // back that and nothing else (1.1.52: it put back the whole guide as it was days before).
                if (study != null) {
                    val path = AiMemory.path(kind, id)
                    MemoryReview.diff(mapOf(path to doc.render()), mapOf(path to write.doc.render())).forEach { ai.courses.wrote(study.courseId, it) }
                }
                ok(write.message, when (action) {
                    "add" -> "Remembered: ${entry.orEmpty().take(100)}"
                    "replace" -> "Updated a memory"
                    "rewrite" -> "Rewrote the guide"
                    else -> "Forgot: ${old.orEmpty().take(80)}"
                })
            }
            is MemoryWrite.Refused -> fail(write.message)
        }
    }

    /**
     * A memory file read a page at a time (1.1.11): the files have no cap, so this is how Ai reaches what its prompt left
     * out — by [query], [label] or range ([from], [count]), each entry numbered by its place in the file.
     */
    private suspend fun memoryRead(scope: String, id: String?, query: String?, label: String?, from: Int?, count: Int?, study: StudyRun? = null): MetaAnswer {
        val (kind, fid, name) = memoryTarget(scope, id, study) ?: return fail("Nothing in scope for $scope.")
        val doc = withContext(Dispatchers.IO) { ai.files.memory(kind, fid, name) }
        val title = doc.preamble.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: AiMemory.title(kind, name)
        val text = withContext(Dispatchers.Default) { MemoryQuery.read(doc, title, query, label, from, count) }
        val how = when {
            !query.isNullOrBlank() -> " for “${query.trim()}”"
            !label.isNullOrBlank() -> " under ${label.trim()}"
            from != null -> " from entry $from"
            else -> ""
        }
        return ok(text, "Read ${kind.name.lowercase()} memory$how")
    }

    private fun skillView(name: String): MetaAnswer {
        val skill = ai.skills().firstOrNull { it.name == Skills.slug(name) } ?: return fail("No skill $name. Skills: ${ai.skills().joinToString { it.name }}.")
        return ok(skill.body, "Read the skill ${skill.name}")
    }

    private suspend fun skillManage(i: JsonObject): MetaAnswer {
        val name = Skills.slug(ToolArgs.string(i, "name")!!)
        if (name.isEmpty()) return fail("A skill needs a name.")
        val own = ai.files.ownSkills().firstOrNull { it.name == name }
        val action = ToolArgs.string(i, "action")
        // A skill of the app's name stands in for the app's own (`Skills.merge`), changing how Ai does a whole
        // kind of task from then on: the person says yes first, the way a destructive tool asks.
        val builtIn = BuiltInSkills.all.firstOrNull { it.name == name }
        if (builtIn != null && (action == "create" || action == "patch") && !ai.prefs.alwaysAllow) {
            val confirm = Confirm(
                "Change how ${ai.name} does “$name”?",
                "“$name” is one of the app's own skills: ${builtIn.description.trimEnd('.')}. ${ai.name}'s version is used in its place " +
                    "from now on, until it is deleted.",
                action = "skill_manage",
            )
            if (!ai.ask(confirm)) return fail("The person said no: $name stays the app's. Don't change it unless they ask.")
        }
        return when (action) {
            "create" -> {
                val body = ToolArgs.string(i, "body") ?: return fail("create needs body.")
                val description = ToolArgs.string(i, "description") ?: return fail("create needs description.")
                ai.files.saveSkill(Skill(name, description, body))
                ok("Saved the skill $name.", "Wrote the skill $name")
            }
            "patch" -> {
                val base = own ?: ai.skills().firstOrNull { it.name == name }?.copy(builtIn = false) ?: return fail("No skill $name.")
                val old = ToolArgs.string(i, "old_text") ?: return fail("patch needs old_text.")
                val new = ToolArgs.string(i, "new_text") ?: return fail("patch needs new_text.")
                if (old !in base.body) return fail("That text is not in $name.")
                ai.files.saveSkill(base.copy(body = base.body.replace(old, new), description = ToolArgs.string(i, "description") ?: base.description))
                ok("Improved $name.", "Improved the skill $name")
            }
            "delete" -> {
                if (own == null) return fail("Only your own skills can be deleted; $name is the app's.")
                ai.files.deleteSkill(name)
                ok("Deleted $name.", "Deleted the skill $name")
            }
            else -> fail("Actions: create, patch, delete.")
        }
    }

    private suspend fun sessionSearch(query: String, limit: Int): MetaAnswer {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val hits = withContext(Dispatchers.IO) { ai.files.sessions() }.flatMap { s ->
            s.turns.filter { !it.isToolResults }.mapNotNull { t ->
                val text = t.text
                if (words.all { text.lowercase().contains(it) }) {
                    val at = text.lowercase().indexOf(words.first())
                    "${java.time.Instant.ofEpochMilli(t.at).toString().take(10)} “${s.title}” (${if (t.role == com.kaiharimoto.mastertool.core.ai.Role.USER) "person" else "you"}): …${text.substring((at - 80).coerceAtLeast(0), (at + 160).coerceAtMost(text.length)).replace('\n', ' ')}…"
                } else null
            }
        }.take(limit.coerceIn(1, 40))
        return ok(if (hits.isEmpty()) "Nothing found for “$query”." else hits.joinToString("\n"), "Searched past conversations for “$query”")
    }

    /** Fine Tuning's report (1.0.54): kept beside the deck's guide, and a PDF when the session ends. */
    /** Words found in this conversation's saved history, the summarised part too, or in every conversation (1.0.56). */
    private suspend fun recall(query: String, scope: String, limit: Int): MetaAnswer {
        if (query.isBlank()) return fail("Say what to find.")
        // Everything Ai remembers (1.1.11): every memory file, entry by entry — what a prompt's budget left out.
        if (scope == "memory") {
            val hits = withContext(Dispatchers.IO) {
                val files = ai.files.memoryFiles().filter { it.name != Persona.FILE }
                    .associate { it.relativeTo(ai.files.root).invariantSeparatorsPath to it.readText() }
                MemoryQuery.search(files, query, limit.coerceIn(1, 30))
            }
            val said = hits.joinToString("\n") { "${it.path} [${it.number}]: ${it.excerpt}" }
            return ok(
                if (hits.isEmpty()) "Nothing in memory holds “$query”." else "$said\n(memory_read with the file's scope and from = the number reads an entry whole, with its neighbours.)",
                "Recalled “$query” from memory",
            )
        }
        val current = ai.session
        val pool = if (scope == "all") (withContext(Dispatchers.IO) { ai.files.sessions() }.filter { it.id != current?.id } + listOfNotNull(current)) else listOfNotNull(current)
        val hits = Recall.search(pool, query, limit.coerceIn(1, 30))
        val day = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault())
        val said = hits.joinToString("\n") { h ->
            val who = if (h.who == Role.USER) "the person" else "you"
            val where = if (h.session == current?.id) "this conversation" else "“${h.title}”"
            "${day.format(java.time.Instant.ofEpochMilli(h.at))}, $where, $who: …${h.excerpt}…"
        }
        return ok(if (hits.isEmpty()) "Nothing found for “$query”." else said, "Recalled “$query”" + if (scope == "all") " across conversations" else "")
    }

    /** Names read off a picture, matched to cards (1.0.55). */
    private fun resolveCards(lines: List<JsonObject>): MetaAnswer {
        if (lines.isEmpty()) return fail("Give the cards you read.")
        val read = lines.take(80).mapNotNull { o ->
            val name = ToolArgs.string(o, "name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReadCards.Read(name, ToolArgs.int(o, "count") ?: 1, ToolArgs.string(o, "section"))
        }
        val matches = ReadCards.resolve(read, index)
        val unsure = matches.count { !it.sure }
        return ok(
            ReadCards.describe(matches),
            "Read ${matches.size} cards off the picture" + if (unsure > 0) ", $unsure to check" else "",
        )
    }

    /** The reader's guide (1.0.67): a book about the open deck, written a chapter at a time and checked as it goes. */
    private suspend fun readerGuide(i: JsonObject): MetaAnswer {
        val deckId = aboutId ?: return fail("Save the deck first: the guide belongs to a saved deck.")
        val path = GuideBook.path(deckId)
        val book = GuideBook.read(ai.files.read(path))
            ?: GuideBook(aboutName)
        val deck = if (deckId == state.deckId) state.deck else stored(deckId)?.entry?.deck ?: return fail("The deck this guide is about is gone.")
        val main = deck.main.mapNotNull { state.index.byId(it)?.name }
        // What a write is stamped with (1.0.99): the deck and Ai's notes it was written on, for the reader to check against.
        val notesHash = ReaderGuide.hashOf(ai.files.read(AiMemory.path(MemoryKind.GUIDE, deckId)).orEmpty())
        val ctx = BookWriter.Context({ state.index.byName(it)?.name }, main, System.currentTimeMillis(), Ledger.fingerprint(deck, state.index::byId), notesHash)
        val w = BookWriter
        // A chapter's percentages and odds are the deck's facts or a check's (1.0.98, the evidence ledger), like the guide's.
        if (ToolArgs.string(i, "action") == "write_chapter") {
            val facts = w.facts(book, ctx)
            val said = Evidence.sources(ai.session?.turns.orEmpty())
            val sources = said.filter { it.tool !in Numbers.BARE_TOOLS }.map { it.content } + facts + ai.files.entries(MemoryKind.GUIDE, deckId)
            val calculated = said.filter { it.tool in Numbers.BARE_TOOLS }.map { it.content }
            val unsourced = textsOf(ToolArgs.element(i, "chapter")).filter { !Numbers.isEstimate(it) }.flatMap { Numbers.unsourced(it, sources, calculated) }
            if (unsourced.isNotEmpty()) {
                return fail(
                    "Not written: the book's numbers are the deck's facts (the facts action) or a check's, and nothing computed " +
                        unsourced.distinctBy { it.written }.joinToString { "“${it.written}”" } + ". Compute it first, or leave the number out, or mark the line “(estimate)”.",
                )
            }
        }
        val result = when (ToolArgs.string(i, "action")) {
            "outline" -> BookWriter.Result(book, w.outline(book, ctx))
            "set_outline" -> w.setOutline(book, ToolArgs.objects(i, "chapters"))
            "set_front" -> w.setFront(book, i, ctx)
            "write_chapter" -> w.writeChapter(book, ToolArgs.element(i, "chapter"), ctx)
            "read_chapter" -> w.readChapter(book, ToolArgs.string(i, "id").orEmpty())
            "remove_chapter" -> w.removeChapter(book, ToolArgs.string(i, "id").orEmpty())
            "facts" -> BookWriter.Result(book, w.facts(book, ctx))
            else -> return fail("Actions: outline, set_outline, set_front, write_chapter, read_chapter, remove_chapter, facts.")
        }
        if (!result.ok) return fail(result.message)
        if (result.book != book) {
            // Which version of Ai's notes it was written from, so the app can say when it is out of date.
            ai.files.write(path, GuideBook.write(result.book.copy(notesHash = notesHash)))
            ai.bookChanged()
        }
        // The person's own marks in the reader (H.6): with the outline all of them, with a chapter that chapter's.
        val marks = when (ToolArgs.string(i, "action")) {
            "outline" -> BookNotes.words(BookNotes.read(ai.files.read(BookNotes.path(deckId))), result.book)
            "read_chapter" -> BookNotes.words(BookNotes.read(ai.files.read(BookNotes.path(deckId))), result.book, ToolArgs.string(i, "id"))
            else -> ""
        }
        val summary = when (ToolArgs.string(i, "action")) {
            "write_chapter" -> "Wrote a chapter of the guide"
            "set_outline" -> "Planned the guide's chapters"
            "set_front" -> "Set the guide's front"
            "facts" -> "Read the deck's numbers"
            "remove_chapter" -> "Removed a chapter"
            else -> "Read the guide"
        }
        return ok(if (marks.isBlank()) result.message else result.message + "\n\n" + marks, summary)
    }

    /** Every string in a chapter's JSON, for the number check. */
    private fun textsOf(e: kotlinx.serialization.json.JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonObject -> e.values.flatMap { textsOf(it) }
        is kotlinx.serialization.json.JsonArray -> e.flatMap { textsOf(it) }
        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
    }

    private fun sessionReport(i: JsonObject): MetaAnswer {
        val deckId = aboutId ?: return fail("There is no saved deck open to report on.")
        val s = ai.session ?: return fail("There is no session to report on.")
        fun num(key: String) = (ToolArgs.element(i, key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        val report = SessionReport(
            deckId = deckId,
            deckName = aboutName,
            at = System.currentTimeMillis(),
            mode = s.mode.takeIf { it in AiSession.DECK_MODES } ?: AiSession.MODE_TUNE,
            intensity = neue.prefs.ai.tuneIntensity,
            summary = ToolArgs.string(i, "summary").orEmpty(),
            learned = ToolArgs.strings(i, "learned"),
            insights = ToolArgs.strings(i, "insights"),
            openQuestions = ToolArgs.strings(i, "open_questions"),
            understanding = SessionReport.score(num("understanding")),
            playing = SessionReport.score(num("playing")),
            mirror = SessionReport.score(num("mirror")),
            why = ToolArgs.string(i, "why").orEmpty(),
            questions = SessionQuestions.of(s.turns),
            startedAt = s.createdAt,
            // The deck as it stood (Phase G: record now), when it is the builder's: a study of another deck has no print here.
            deckPrint = if (deckId == state.deckId && state.deck.main.isNotEmpty()) Ledger.fingerprint(state.deck, state.index::byId) else null,
        )
        ai.files.addReport(report)
        ai.lastReport = report
        return ok(
            "Filed. The person gets it as a PDF when the session ends, and the deck's guide shows the scores.",
            "Filed the report: understanding ${report.understanding}, playing ${report.playing}, mirror ${report.mirror}%",
        )
    }
}
