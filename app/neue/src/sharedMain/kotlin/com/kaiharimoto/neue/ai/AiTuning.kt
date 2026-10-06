package com.kaiharimoto.neue.ai

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.TuneIntensity
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.GuideBudget
import com.kaiharimoto.mastertool.core.ai.memory.MemoryBudget
import com.kaiharimoto.mastertool.core.ai.memory.MemoryChange
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryReview
import com.kaiharimoto.mastertool.core.ai.memory.ProfileCoverage
import com.kaiharimoto.mastertool.core.ai.report.GuideDoc
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.SessionQuestions
import com.kaiharimoto.mastertool.core.ai.report.book.BookReview
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Numbers
import com.kaiharimoto.mastertool.core.ai.evidence.Proof
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.world.Instruments
import com.kaiharimoto.neue.shootout.Shootouts
import com.kaiharimoto.neue.world.WorldSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import java.io.File

// What Ai learns (phase 3), on [AiState]: Fine Tuning and Learn About You, the review of what they changed in memory,
// the reflection after a conversation, the guide and the reader's guide, and memory's folding and forgetting.

/**
 * The memory files as they stand, by path: what a review compares against. The skills Ai wrote are
 * in it too, so a skill written or patched in a session is shown at its end and Undo puts it back —
 * or deletes it, when it was not there before.
 */
internal fun AiState.reviewSnapshot(): Map<String, String?> = snapshot()

private fun AiState.snapshot(): Map<String, String?> {
    val paths = buildSet {
        add(MemoryKind.USER.file)
        add(MemoryKind.AGENT.file)
        files.memoryFiles().forEach { add(it.relativeTo(files.root).invariantSeparatorsPath) }
        addAll(files.skillPaths())
        host.scope()?.path?.let(::add)
        // Every deck's book, not only the open one's: a review compares the same paths before and after, or Undo
        // takes a book that was not in the "before" for one that never existed and deletes it (1.0.98, the red team).
        files.file("guides").listFiles { f -> f.name.endsWith(".book.json") }?.forEach { add("guides/${it.name}") }
        h.builder.deckId?.let { add(GuideBook.path(it)) }
        session?.deckId?.let { add(GuideBook.path(it)) }
        // The Shootout rubric an interview writes (Phase S stage 3): reviewed like the guide.
        if (h.shootoutStarted) h.shootout.interviewing?.let { add(Shootouts.reviewPath(it)) }
    }
    return paths.associateWith { files.read(it) }
}

/**
 * A memory file saved from the brain (1.0.98, the red team): the person's change, [loaded] to [text], made on what is
 * on disk now, so what Ai wrote meanwhile stays; and, while a Fine Tuning run is under review, made on its "before"
 * too, so the review lists Ai's changes alone and Undo never takes the person's back.
 */
fun AiState.saveByHand(path: String, loaded: String?, text: String) {
    val isMemory = path.endsWith(".md") && !Skills.isPath(path)
    val now = files.read(path)
    files.write(path, if (isMemory) MemoryReview.merge(loaded, now, text) else text)
    val baseline = tuneBefore ?: return
    tuneBefore = baseline + (path to if (isMemory) MemoryReview.merge(loaded, baseline[path], text) else text)
}

/**
 * A deck's guide as Ai reads it (1.0.98, the evidence ledger): each entry with a number wears what its proof says now —
 * checked, stale since the deck changed, contradicted, or an estimate — so Ai knows which of its own numbers to trust.
 * Numbers computed on another deck than the builder's are marked stale here, and checked again in the background.
 */
fun AiState.guideForPrompt(deckId: String): String = guideEntries(deckId).joinToString("\n") { "- $it" }

/**
 * The guide as a conversation is given it (1.1.11): within its room on the connection in use ([memoryRoom]) — the
 * entries most relevant to [query] and the deck, and the index line for the rest — each wearing its proof's mark. Null
 * when the guide is empty.
 */
fun AiState.guideShown(deckId: String, deckName: String, query: String = ""): MemoryBudget.Shown? {
    val entries = guideEntries(deckId).takeIf { it.isNotEmpty() } ?: return null
    return MemoryBudget.pick(entries, memoryRoom(MemoryKind.GUIDE), MemoryBudget.Focus(query, listOf(deckName)), MemoryKind.GUIDE, "guide")
}

/** [guideShown] as the block in front of a message: its heading, the entries marked as memory, the index line. */
fun AiState.guideBlock(deckId: String, deckName: String, query: String = ""): String? {
    val shown = guideShown(deckId, deckName, query) ?: return null
    return "Your guide to how “$deckName” plays (memory scope guide; a mark in brackets says whether its number still holds):\n" +
        MemoryBudget.tagged(AiMemory.path(MemoryKind.GUIDE, deckId), shown.lines())
}

private fun AiState.guideEntries(deckId: String): List<String> {
    val entries = files.read(AiMemory.path(MemoryKind.GUIDE, deckId))?.let { AiMemory.parse(it).entries }.orEmpty()
    if (entries.isEmpty()) return emptyList()
    var ledger = Ledger.read(files.read(Ledger.path(deckId)))
    if (deckId == h.builder.deckId && ledger.isNotEmpty()) {
        val marked = Ledger.staleAgainst(ledger, Ledger.fingerprint(h.builder.deck), library = h.effects.trust().library(deckCodes()))
        if (marked != ledger) {
            ledger = marked
            files.write(Ledger.path(deckId), Ledger.write(marked))
        }
        if (ledger.any { it.status == Proven.Status.STALE }) recheckGuide(deckId)
    }
    return Ledger.annotate(entries, ledger)
}

/**
 * The guide's stale numbers computed again on the deck as it is (1.0.98): each proof by a tool the app can run alone —
 * hand_odds, an instrument — asked the same question; the number found again is checked, a different one contradicted.
 */
fun AiState.recheckGuide(deckId: String) {
    if (rechecking) return
    rechecking = true
    scope.launch {
        try {
            val ledger = Ledger.read(files.read(Ledger.path(deckId)))
            val print = Ledger.fingerprint(h.builder.deck)
            val next = ledger.map entry@{ p ->
                if (p.status != Proven.Status.STALE || p.proofs.any { it.deck.isNotEmpty() && it.tool !in Evidence.RERUNNABLE }) return@entry p
                val again = mutableListOf<String>()
                // A goldfish run is minutes of work at worst: never on the frame thread.
                for (proof in p.proofs.filter { it.deck.isNotEmpty() }) again += withContext(Dispatchers.Default) { rerun(proof, deckId) } ?: return@entry p
                // What it says now, with what never depended on the deck (the person's words, a calculation) as it was.
                val missing = Numbers.unsourced(p.entry, again + p.proofs.filter { it.deck.isEmpty() }.map { it.excerpt })
                val now = System.currentTimeMillis()
                if (missing.isEmpty()) {
                    var k = 0
                    p.copy(
                        status = Proven.Status.CHECKED, checkedAt = now, note = "",
                        proofs = p.proofs.map {
                            if (it.deck.isEmpty()) it
                            else it.copy(deck = print, at = now, library = if (it.library.isEmpty()) "" else Evidence.libraryOf(again.getOrElse(k++) { "" }).ifEmpty { it.library })
                        },
                    )
                } else {
                    p.copy(status = Proven.Status.CONTRADICTED, checkedAt = now, note = "the check now says: " + again.joinToString(" / ") { it.lines().firstOrNull { l -> Numbers.values(l).isNotEmpty() }.orEmpty().take(160) })
                }
            }
            if (next != ledger && deckId == h.builder.deckId) files.write(Ledger.path(deckId), Ledger.write(next))
        } finally {
            rechecking = false
        }
    }
}

/** A proof's question asked again on the builder's deck, its answer's text; null when it cannot be. */
private suspend fun AiState.rerun(proof: Proof, deckId: String): String? {
    val input = runCatching { Json.parseToJsonElement(proof.input).jsonObject }.getOrNull() ?: return null
    val asked = when (proof.tool) {
        "hand_odds" -> JsonObject(input + ("deck_id" to JsonPrimitive(deckId)))
        "world_tool" -> input
        else -> return null
    }
    return when (proof.tool) {
        "world_tool" -> {
            val name = input["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val args = (input["args"] as? JsonObject) ?: JsonObject(emptyMap())
            val withDeck = if ("deck" in args) args else JsonObject(args + ("deck" to JsonPrimitive(deckId)))
            runCatching { Instruments.run(name, withDeck, WorldSnapshot.of(h)).lines.joinToString("\n") }.getOrNull()
        }
        else -> host.run(Part.ToolUse("recheck-${System.nanoTime()}", proof.tool, asked)).takeIf { !it.isError }?.content
    }
}

/** Opens the Fine Tuning launcher, on [mode] when given. */
fun AiState.askTune(mode: String? = null) {
    tuneMode = mode
    tuneAsk = true
}

/**
 * Fine Tuning (1.0.48, kai: "for me to teach it how to play my deck and have it ask me
 * questions about my deck … or have the AI teach itself by reading the cards and going
 * online"): a conversation of its own about the deck open in the builder, [study] or
 * taught, at [intensity]; what it learns goes to the deck's guide.
 */
fun AiState.startTuning(mode: String, intensity: TuneIntensity) {
    if (AiState.PHASE < 3) return
    tuneAsk = false
    val connection = prefs.connection ?: run {
        openWizard()
        return
    }
    val deck = h.builder.deckName
    if (h.builder.deckId == null) {
        h.neue.note = com.kaiharimoto.neue.Note("Save the deck first: Fine Tuning writes a guide to a saved deck")
        return
    }
    stop()
    h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true, tuneIntensity = intensity.id)) }
    wizardOpen = false
    historyOpen = false
    demoOpen = false
    settleTuning()
    tuneBefore = snapshot()
    lastReport = null
    val deckId = h.builder.deckId
    // The guide's size now: the run may add its intensity's room to it, no more (1.0.66).
    guideStart = if (mode in AiSession.DECK_MODES && deckId != null) Triple(files.memory(MemoryKind.GUIDE, deckId, deck).used, intensity.guideBudget, intensity.label) else null
    // The run is about this deck to its end, whatever the builder shows meanwhile (1.0.98, the red team).
    guideFilled = null
    session = begin(connection, mode).copy(deckId = deckId, deckName = deck)
    // A run that filled its room left what it had left for this one (1.1.11): it begins there.
    val carried = deckId?.takeIf { mode == AiSession.MODE_TUNE || mode == AiSession.MODE_STUDY || mode == AiSession.MODE_PRINCIPLES }
        ?.let { GuideBudget.carryOver(files.reports(it).lastOrNull()) }
    val room = GuideBudget.brief(intensity) + (carried?.let { " $it" } ?: "")
    send(
        when (mode) {
            AiSession.MODE_STUDY -> "Study “$deck” yourself, and think out loud so I can learn with you. Intensity: ${intensity.label} — ${intensity.studies} $room"
            AiSession.MODE_PRINCIPLES -> "Learn “$deck” from first principles: its card text and the rules, no guides or lists. Work out its goals and how its cards pair, " +
                "interact and connect, and think out loud so I can learn with you. Intensity: ${intensity.label} — about ${intensity.steps} rounds. $room"
            AiSession.MODE_REFACTOR -> refactorBrief(deck, deckId, intensity)
            AiSession.MODE_WRITE -> writeBrief(deck, deckId, intensity)
            else -> "Let's do Fine Tuning on “$deck”: I'll teach you how I play it. Intensity: ${intensity.label}, about ${intensity.questions} questions. $room"
        },
    )
}

/** The writing session's first message: the intensity's chapters, and what is written already. */
private fun AiState.writeBrief(deck: String, deckId: String?, intensity: TuneIntensity): String {
    val book = deckId?.let { GuideBook.read(files.read(GuideBook.path(it))) }
    val scope = when (intensity) {
        TuneIntensity.QUICK -> "chapters 1 to 4 and 7, the main lines only"
        TuneIntensity.STANDARD -> "every chapter but 9 to 11, every line you know"
        TuneIntensity.DEEP -> "all eleven chapters, every line, every matchup in the field, every card"
    }
    val sofar = when {
        book == null || book.chapters.isEmpty() -> "Nothing is written yet."
        else -> "Written so far: ${book.chapters.count { it.written }} of ${book.chapters.size} chapters; continue from the outline."
    }
    return "Write the reader's guide for “$deck”: the book a player reads to master it. Intensity: ${intensity.label} — $scope. $sofar"
}

fun AiState.bookChanged() {
    bookVersion++
}

/** Opens the reader's guide (1.0.67) on [deckId], the builder's deck when not given. */
fun AiState.openBook(deckId: String? = null) {
    val id = deckId ?: h.builder.deckId ?: run {
        h.neue.note = com.kaiharimoto.neue.Note("Save the deck first: the guide belongs to a saved deck")
        return
    }
    h.neue.reading = id
}

/** What the host checks a guide write against (1.0.66), while a Fine Tuning run is going. */
fun AiState.guideRoom(): Triple<Int, Int, String>? = guideStart?.takeIf { session?.mode in AiSession.DECK_MODES }

/** Refactor guide's first message: what the guide holds now, so Ai knows the size of the job. */
private fun AiState.refactorBrief(deck: String, deckId: String?, intensity: TuneIntensity): String {
    val text = deckId?.let { files.read(AiMemory.path(MemoryKind.GUIDE, it)) }
    val doc = GuideDoc.parse(text)
    val sections = doc.sections.joinToString(", ") { "${it.name} ${it.entries.size}" }
    val used = deckId?.let { files.memory(MemoryKind.GUIDE, it, deck).used } ?: 0
    val depth = when (intensity) {
        TuneIntensity.QUICK -> "a quick pass: drop and merge, check only what looks wrong"
        TuneIntensity.STANDARD -> "check the claims that matter against the cards"
        TuneIntensity.DEEP -> "check every line and claim against the card text, step by step"
    }
    return "Refactor the guide for “$deck”: drop what is not helpful, sharpen what is, and put it in order. " +
        "It has ${doc.entryCount} entries, ${com.kaiharimoto.mastertool.core.ai.memory.GuideBudget.grouped(used)} characters ($sections). " +
        "Intensity: ${intensity.label} — $depth."
}

/**
 * Learn About You (1.0.54, kai: "the AI builds a profile of the user across sessions and
 * interviews them about anything that would help the Ai understand what the user's goals and
 * preferences are, as well as their workflow"): an interview about the person, not a deck, into
 * USER.md — the memory in front of every conversation.
 */
fun AiState.startProfile(intensity: TuneIntensity) {
    if (AiState.PHASE < 3) return
    profileAsk = false
    val connection = prefs.connection ?: run {
        openWizard()
        return
    }
    stop()
    h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true, tuneIntensity = intensity.id)) }
    wizardOpen = false
    historyOpen = false
    demoOpen = false
    settleTuning()
    tuneBefore = snapshot()
    lastReport = null
    session = begin(connection, AiSession.MODE_PROFILE)
    val profile = files.entries(MemoryKind.USER)
    // Where the profile is thin, so this interview starts there, not at the top of an outline (1.0.65).
    val coverage = ProfileCoverage.brief(ProfileCoverage.of(profile))
    send(
        "Let's do Learn About You: interview me so you understand my goals, my preferences and how I work. " +
            (if (profile.isNotBlank()) "Start from what you already know and fill the gaps. " else "") +
            "$coverage Intensity: ${intensity.label}, about ${intensity.questions} questions.",
    )
}

fun AiState.openGuide() {
    val id = h.builder.deckId ?: run {
        h.neue.note = com.kaiharimoto.neue.Note("Save the deck first: the guide belongs to a saved deck")
        return
    }
    docOpen = LivingDoc.Guide(id, h.builder.deckName)
}

fun AiState.openProfile() {
    docOpen = LivingDoc.Profile
}

val AiState.tuning: Boolean get() = session?.mode.let { it in AiSession.DECK_MODES || it == AiSession.MODE_PROFILE || it == AiSession.MODE_REFACTOR || it == AiSession.MODE_WRITE || it == AiSession.MODE_RUBRIC }

/** Writing the reader's guide (1.0.67). */
val AiState.writing: Boolean get() = session?.mode == AiSession.MODE_WRITE

/** Rewriting the deck's guide (1.0.66). */
val AiState.refactoring: Boolean get() = session?.mode == AiSession.MODE_REFACTOR

/** Studying on its own rather than being taught. */
val AiState.studying: Boolean get() = session?.mode == AiSession.MODE_STUDY || session?.mode == AiSession.MODE_PRINCIPLES

/** Learning about the person rather than a deck. */
val AiState.profiling: Boolean get() = session?.mode == AiSession.MODE_PROFILE

/**
 * Fine Tuning done: first, when the session taught Ai something and it has not filed its report,
 * it is asked to (1.0.54) — the scores and the PDF come from that — then what it learned, to keep
 * or undo, and an ordinary conversation.
 */
fun AiState.finishTuning() {
    val s = session
    val asksReport = s != null && s.mode in AiSession.DECK_MODES && !running && !wrapping &&
        !SessionQuestions.reported(s.turns) && s.turns.size > 2 && prefs.connection != null
    if (asksReport) {
        wrapping = true
        send("We're finishing here. File your session report now with session_report — your honest scores and why — then say goodbye in one line.")
        return
    }
    completeTuning()
}

internal fun AiState.completeTuning() {
    wrapping = false
    guideStart = null
    val ended = session
    stop()
    sayFilled()
    endReport = lastReport?.takeIf { r -> ended != null && ended.mode in AiSession.DECK_MODES && r.at >= ended.createdAt }
    lastReport = null
    val before = tuneBefore ?: snapshot()
    tuneBefore = null
    offerReview(before)
    val connection = prefs.connection
    session = if (connection != null) begin(connection) else null
}

/**
 * A Fine Tuning run left by anything but Finish — a new conversation, Start fresh, another from the history, a switch of
 * connection, another run begun — still hands what it learned to the review (1.0.97, the red team): nothing is learned
 * behind the person's back.
 */
internal fun AiState.settleTuning() {
    val before = tuneBefore ?: return
    wrapping = false
    guideStart = null
    tuneBefore = null
    sayFilled()
    offerReview(before)
}

/** A run that used all of its room in the guide says so when it ends (1.1.11): the run was full, never the guide. */
private fun AiState.sayFilled() {
    val words = guideFilled ?: return
    guideFilled = null
    notice = words
}

private fun AiState.offerReview(before: Map<String, String?>) {
    val after = snapshot()
    // A book is JSON, not entries: its change is told section by section, and undone as the file it was.
    fun isBook(path: String) = path.endsWith(".book.json")
    val books = (before.keys + after.keys).filter(::isBook).distinct().mapNotNull { path ->
        val (added, removed) = BookReview.diff(before[path], after[path])
        MemoryChange(path, added, removed).takeUnless { it.isEmpty }
    }
    val changes = MemoryReview.diff(before.filterKeys { !isBook(it) }, after.filterKeys { !isBook(it) }) + books
    if (changes.isNotEmpty()) {
        reviewBefore = before
        review = changes
    }
}

fun AiState.keepReview() {
    review = null
    reviewBefore = emptyMap()
    endReport = null
}

/** Everything the review lists put back as it was. */
fun AiState.undoReview() {
    val changed = review?.map { it.path }.orEmpty()
    changed.forEach { path -> reviewBefore[path]?.let { files.write(path, it) } ?: files.delete(path) }
    if (changed.any { it.endsWith(".book.json") }) bookChanged()
    review = null
    reviewBefore = emptyMap()
    endReport = null
}

/**
 * After a conversation, a short pass to keep what will matter (Hermes's nudge): the
 * model reads the conversation back and writes durable facts to memory. Quiet: a note
 * says how many things were remembered, with Undo. No skills: the pass runs with no
 * one watching, and a skill changes how Ai does a whole kind of task, so skills are
 * written in a conversation, where the person sees it (and is asked over one of the
 * app's). Only for conversations long enough to teach something, and only on an API
 * connection (a CLI would run a whole session for it).
 */
internal fun AiState.reflect(finished: AiSession) {
    if (AiState.PHASE < 3 || finished.mode != AiSession.MODE_CHAT) return
    if (finished.unreflected < AiState.REFLECT_AFTER) return
    val connection = prefs.connection?.takeIf { it.id == finished.connection } ?: return
    val model = runCatching { backendFor(connection) }.getOrNull()?.takeIf { !it.runsOwnLoop } ?: return
    // Read once: a conversation reopened and left again is reflected on for what is new.
    save(finished.copy(reflected = finished.turns.size))
    val transcript = finished.turns.drop(finished.reflected).filter { !it.isToolResults }.joinToString("\n") { t ->
        (if (t.role == Role.USER) "Person: " else "$name: ") + t.text.take(1200)
    }.takeLast(16_000)
    val allowed = tools.filter { it.name == "memory" || it.name == "memory_read" }
    val request = TurnRequest(
        finished.system,
        listOf(
            ChatTurn.user(
                "Our conversation just ended. Here it is:\n\n$transcript\n\n" +
                    "Save to memory what will still matter next week about the person or about doing this job for them " +
                    "(memory tool; replace what changed rather than adding duplicates). If you worked out a repeatable " +
                    "procedure, note it in your own notes (memory scope agent) in a line or two; skills are not written here. " +
                    "If nothing is worth keeping, do nothing. Then answer in one word: done.",
            ),
        ),
        allowed,
        connection.model,
        "low",
    )
    backgroundJobs.removeAll { it.isCompleted }
    backgroundJobs += scope.launch {
        // Only what it was offered answers: a tool it names anyway is refused, not run.
        val names = allowed.map { it.name }.toSet()
        // Only what its own calls changed is its (1.0.98, the red team): each memory write measured on its own, so a
        // conversation, a Fine Tuning run, a brain edit or a sync writing meanwhile is never counted as the reflection's.
        var changes = emptyList<MemoryChange>()
        val run = ToolRunner { call ->
            if (call.name.removePrefix("mcp__neue__") in names) {
                val pre = snapshot()
                host.run(call).also { changes = changes + MemoryReview.diff(pre, snapshot()) }
            } else {
                Part.ToolResult(call.id, call.name, "${call.name} is not used after a conversation: only memory.", isError = true)
            }
        }
        runCatching { AgentLoop(model, run, maxSteps = 6).run(request).collect { } }
        if (changes.isNotEmpty()) {
            val n = MemoryReview.count(changes)
            h.neue.note = com.kaiharimoto.neue.Note("$name remembered $n thing${if (n == 1) "" else "s"} from that conversation", "Undo", lastsMs = 10_000) {
                // Its own entries taken back, one by one: never a whole file put back, never a waiting review touched.
                changes.forEach { c -> MemoryReview.revert(files.read(c.path), c)?.let { files.write(c.path, it) } ?: files.delete(c.path) }
            }
        }
    }
}

/** The studio's pictures of Fine Tuning: a question waiting, or a review to keep. */
fun AiState.previewTuning(asking: Question?, changes: List<MemoryChange>?) {
    question = asking
    review = changes
}

/**
 * A library deck copied into a web: its notes go with it into the web's file, marked
 * with its name, since from now on the web's notes are the ones read for it.
 */
fun AiState.foldIntoWeb(deckId: String, deckName: String, webId: String) {
    val deckNotes = files.read(AiMemory.path(MemoryKind.DECK, deckId)) ?: return
    val deck = AiMemory.parse(deckNotes)
    if (deck.entries.isEmpty()) return
    val webName = h.webs.library.byId(webId)?.name ?: "the web"
    val web = files.memory(MemoryKind.WEB, webId, webName)
    files.save(MemoryKind.WEB, webId, AiMemory.fold(web, deck, deckName))
}

/**
 * A deck copied — duplicated, put in a web, copied out of one (1.0.98, the red team): what Ai learned about it goes
 * with it, its guide, reader's guide and reports, so the copy does not start knowing nothing. Never over a copy that
 * has its own already.
 */
fun AiState.carryLearning(from: String, to: String) {
    if (from == to) return
    fun copy(fromPath: String, toPath: String, change: (String) -> String = { it }) {
        val text = files.read(fromPath) ?: return
        if (files.read(toPath) == null) files.write(toPath, change(text))
    }
    copy(AiMemory.path(MemoryKind.GUIDE, from), AiMemory.path(MemoryKind.GUIDE, to))
    copy(GuideBook.path(from), GuideBook.path(to))
    copy(ReportLog.path(from), ReportLog.path(to)) { text -> ReportLog.write(ReportLog.read(text).map { it.copy(deckId = to) }) }
    copy(Ledger.path(from), Ledger.path(to))
}

/** Memory, skills and conversations deleted; the connections stay. */
fun AiState.forgetEverything() {
    stop()
    cancelBackground()
    forgetSaves()
    files.forgetEverything()
    session = null
}

/** The builder's deck's Main and Extra Deck passcodes: what the goldfish's library fingerprint is taken over. */
private fun AiState.deckCodes(): List<Int> = (h.builder.deck.main + h.builder.deck.extra).map { it.value }
