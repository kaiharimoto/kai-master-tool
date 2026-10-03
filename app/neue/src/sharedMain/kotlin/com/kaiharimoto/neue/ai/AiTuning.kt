package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import kotlinx.coroutines.launch
import java.io.File

// What Ai learns (phase 3), on [AiState]: Fine Tuning and Learn About You, the review of what they changed in memory,
// the reflection after a conversation, the guide and the reader's guide, and memory's folding and forgetting.

/** The memory files as they stand, by path: what a review compares against. */
private fun AiState.snapshot(): Map<String, String?> {
    val paths = buildSet {
        add(MemoryKind.USER.file)
        add(MemoryKind.AGENT.file)
        files.memoryFiles().forEach { add(it.relativeTo(files.root).invariantSeparatorsPath) }
        host.scope()?.path?.let(::add)
        h.builder.deckId?.let { add(com.kaiharimoto.mastertool.core.ai.report.book.GuideBook.path(it)) }
    }
    return paths.associateWith { files.read(it) }
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
fun AiState.startTuning(mode: String, intensity: com.kaiharimoto.mastertool.core.ai.TuneIntensity) {
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
    tuneBefore = snapshot()
    lastReport = null
    val deckId = h.builder.deckId
    // The guide's size now: the run may add its intensity's room to it, no more (1.0.66).
    guideStart = if (mode in AiSession.DECK_MODES && deckId != null) Triple(files.memory(MemoryKind.GUIDE, deckId, deck).used, intensity.guideBudget, intensity.label) else null
    session = begin(connection, mode)
    val room = com.kaiharimoto.mastertool.core.ai.memory.GuideBudget.brief(intensity)
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
private fun AiState.writeBrief(deck: String, deckId: String?, intensity: com.kaiharimoto.mastertool.core.ai.TuneIntensity): String {
    val book = deckId?.let { com.kaiharimoto.mastertool.core.ai.report.book.GuideBook.read(files.read(com.kaiharimoto.mastertool.core.ai.report.book.GuideBook.path(it))) }
    val scope = when (intensity) {
        com.kaiharimoto.mastertool.core.ai.TuneIntensity.QUICK -> "chapters 1 to 4 and 7, the main lines only"
        com.kaiharimoto.mastertool.core.ai.TuneIntensity.STANDARD -> "every chapter but 9 to 11, every line you know"
        com.kaiharimoto.mastertool.core.ai.TuneIntensity.DEEP -> "all eleven chapters, every line, every matchup in the field, every card"
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
private fun AiState.refactorBrief(deck: String, deckId: String?, intensity: com.kaiharimoto.mastertool.core.ai.TuneIntensity): String {
    val text = deckId?.let { files.read(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(MemoryKind.GUIDE, it)) }
    val doc = com.kaiharimoto.mastertool.core.ai.report.GuideDoc.parse(text)
    val sections = doc.sections.joinToString(", ") { "${it.name} ${it.entries.size}" }
    val used = deckId?.let { files.memory(MemoryKind.GUIDE, it, deck).used } ?: 0
    val depth = when (intensity) {
        com.kaiharimoto.mastertool.core.ai.TuneIntensity.QUICK -> "a quick pass: drop and merge, check only what looks wrong"
        com.kaiharimoto.mastertool.core.ai.TuneIntensity.STANDARD -> "check the claims that matter against the cards"
        com.kaiharimoto.mastertool.core.ai.TuneIntensity.DEEP -> "check every line and claim against the card text, step by step"
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
fun AiState.startProfile(intensity: com.kaiharimoto.mastertool.core.ai.TuneIntensity) {
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
    tuneBefore = snapshot()
    lastReport = null
    session = begin(connection, AiSession.MODE_PROFILE)
    val profile = files.entries(MemoryKind.USER)
    // Where the profile is thin, so this interview starts there, not at the top of an outline (1.0.65).
    val coverage = com.kaiharimoto.mastertool.core.ai.memory.ProfileCoverage.brief(com.kaiharimoto.mastertool.core.ai.memory.ProfileCoverage.of(profile))
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

val AiState.tuning: Boolean get() = session?.mode.let { it in AiSession.DECK_MODES || it == AiSession.MODE_PROFILE || it == AiSession.MODE_REFACTOR || it == AiSession.MODE_WRITE }

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
        !com.kaiharimoto.mastertool.core.ai.report.SessionQuestions.reported(s.turns) && s.turns.size > 2 && prefs.connection != null
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
    endReport = lastReport?.takeIf { r -> ended != null && ended.mode in AiSession.DECK_MODES && r.at >= ended.createdAt }
    lastReport = null
    val before = tuneBefore ?: snapshot()
    tuneBefore = null
    offerReview(before)
    val connection = prefs.connection
    session = if (connection != null) begin(connection) else null
}

private fun AiState.offerReview(before: Map<String, String?>) {
    val after = snapshot()
    // A book is JSON, not entries: its change is told section by section, and undone as the file it was.
    fun isBook(path: String) = path.endsWith(".book.json")
    val books = (before.keys + after.keys).filter(::isBook).distinct().mapNotNull { path ->
        val (added, removed) = com.kaiharimoto.mastertool.core.ai.report.book.BookReview.diff(before[path], after[path])
        com.kaiharimoto.mastertool.core.ai.memory.MemoryChange(path, added, removed).takeUnless { it.isEmpty }
    }
    val changes = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.diff(before.filterKeys { !isBook(it) }, after.filterKeys { !isBook(it) }) + books
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
 * model reads the conversation back and writes durable facts to memory — and a
 * skill, when it worked out a procedure. Quiet: a note says how many things were
 * remembered, with Undo. Only for conversations long enough to teach something, and
 * only on an API connection (a CLI would run a whole session for it).
 */
internal fun AiState.reflect(finished: AiSession) {
    if (AiState.PHASE < 3 || finished.mode != AiSession.MODE_CHAT) return
    if (finished.unreflected < AiState.REFLECT_AFTER) return
    val connection = prefs.connection?.takeIf { it.id == finished.connection } ?: return
    val model = runCatching { backendFor(connection) }.getOrNull()?.takeIf { !it.runsOwnLoop } ?: return
    // Read once: a conversation reopened and left again is reflected on for what is new.
    files.saveSession(finished.copy(reflected = finished.turns.size))
    val transcript = finished.turns.drop(finished.reflected).filter { !it.isToolResults }.joinToString("\n") { t ->
        (if (t.role == Role.USER) "Person: " else "$name: ") + t.text.take(1200)
    }.takeLast(16_000)
    val before = snapshot()
    val allowed = tools.filter { it.name == "memory" || it.name == "skill_manage" || it.name == "memory_read" }
    val request = TurnRequest(
        finished.system,
        listOf(
            ChatTurn.user(
                "Our conversation just ended. Here it is:\n\n$transcript\n\n" +
                    "Save to memory what will still matter next week about the person or about doing this job for them " +
                    "(memory tool; replace what changed rather than adding duplicates). If you worked out a repeatable " +
                    "procedure, write it as a skill (skill_manage). If nothing is worth keeping, do nothing. Then answer in one word: done.",
            ),
        ),
        allowed,
        connection.model,
        "low",
    )
    scope.launch {
        runCatching { AgentLoop(model, { call -> host.run(call) }, maxSteps = 6).run(request).collect { } }
        val changes = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.diff(before, snapshot())
        if (changes.isNotEmpty()) {
            val n = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.count(changes)
            h.neue.note = com.kaiharimoto.neue.Note("$name remembered $n thing${if (n == 1) "" else "s"} from that conversation", "Undo", lastsMs = 10_000) {
                reviewBefore = before
                review = changes
                undoReview()
            }
        }
    }
}

/** The studio's pictures of Fine Tuning: a question waiting, or a review to keep. */
fun AiState.previewTuning(asking: Question?, changes: List<com.kaiharimoto.mastertool.core.ai.memory.MemoryChange>?) {
    question = asking
    review = changes
}

/**
 * A library deck copied into a web: its notes go with it into the web's file, marked
 * with its name, since from now on the web's notes are the ones read for it.
 */
fun AiState.foldIntoWeb(deckId: String, deckName: String, webId: String) {
    val deckNotes = files.read(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(MemoryKind.DECK, deckId)) ?: return
    val deck = com.kaiharimoto.mastertool.core.ai.memory.AiMemory.parse(deckNotes)
    if (deck.entries.isEmpty()) return
    val webName = h.webs.library.byId(webId)?.name ?: "the web"
    val web = files.memory(MemoryKind.WEB, webId, webName)
    files.save(MemoryKind.WEB, webId, com.kaiharimoto.mastertool.core.ai.memory.AiMemory.fold(web, deck, deckName))
}

/** Memory, skills and conversations deleted; the connections stay. */
fun AiState.forgetEverything() {
    stop()
    files.forgetEverything()
    session = null
}
