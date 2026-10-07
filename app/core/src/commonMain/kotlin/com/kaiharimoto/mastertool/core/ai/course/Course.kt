package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A guide someone wrote — a Metafy course of text and video chapters — that Ai studies on its own (kai, 2026-10: "have
 * Chessy learn from a Yu-Gi-Oh! Metafy guide … without human intervention"). The person starts it once, logged in, and
 * the study walks it chapter by chapter at a person's pace: each chapter read in the browser and kept as text, notes
 * taken from it, and the notes distilled into the deck's guide, which the person reviews when they come back.
 *
 * Kept as `ai/courses/<id>/course.json` beside the chapters' text (`pages/<n>.md`) and notes (`notes/<n>.md`): every
 * step is saved as it ends, so the study goes on where it stopped after a crash, a restart or the app closed
 * ([StudyQueue]). Unknown keys are ignored, so an older build reads a newer course.
 */
@Serializable
data class Course(
    val id: String,
    /** Where the guide starts: its contents page, as the person pasted it. */
    val start: String,
    val title: String = "",
    val author: String = "",
    /** The hosts the browser may load from; the start's own host always ([BrowseGuard.hosts]). */
    val hosts: List<String> = emptyList(),
    /** The deck the guide is about: its guide is what the study writes. */
    val deckId: String = "",
    val deckName: String = "",
    val chapters: List<Chapter> = emptyList(),
    /** The contents were read: [chapters] is the guide's whole list, even when it is empty. */
    val listed: Boolean = false,
    /** The notes were distilled into the deck's guide. */
    val distilled: Boolean = false,
    val state: State = State.STUDYING,
    /** Why it stopped, in words, when it did. */
    val note: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Tokens spent on it so far, all told: said, never a reason to stop. */
    val spent: Long = 0,
    /**
     * The most it might spend, in tokens, as a course begun before 1.1.46 was given. Read and ignored: a study is never
     * stopped for what it spent (kai, 2026-10: "remove the course spending cap").
     */
    val cap: Long = 0,
    /** Pages loaded on [loadsDay] (days since the epoch): a person reads only so many a day ([HumanPace.DAILY]). */
    val loadsDay: Long = 0,
    val loads: Int = 0,
    /** The guide as it was when the study began: what the person's review compares the study's writes with. */
    val guideBefore: String? = null,
    /** The person was shown what the study wrote (once: Keep or Undo all). */
    val reviewed: Boolean = false,
    /** The DuelingBook replays the chapters link to, in the order they were found ([DbReplays]). */
    val replays: List<ReplayRef> = emptyList(),
    /**
     * The replays' notes are in the deck's guide: distilled with the chapters, or on their own when the chapters were
     * distilled before the replays were studied (a course begun before 1.1.41).
     */
    val replaysDistilled: Boolean = false,
    /**
     * The playbook was put together from every chapter's and replay's notes (merged, cross-referenced, gaps named) since
     * the last notes were taken: notes taken again set it back.
     */
    val consolidated: Boolean = false,
    /** The notes depth ([CourseDepth]) the guide was last distilled at: a deeper study distils again. */
    val distilDepth: Int = 0,
    /** The held-out replays were drawn ([ReplayExam]); a course begun before 1.1.43 draws them once, from what is unstudied. */
    val examDrawn: Boolean = false,
    /** The playbook's parts put together so far ([StudyChunks.consolidateParts]), until [consolidated]. */
    val consolidateDone: List<String> = emptyList(),
    /** The guide's parts distilled so far ([StudyChunks.distilParts]), until the distil is whole. */
    val distilDone: List<String> = emptyList(),
    /** A part of the playbook or the guide begun and not finished: taken up again, told that it was begun. */
    val partBegun: String = "",
    /**
     * When the study tries again after the model or the network stopped it ([StudyRetry]); 0 when nothing waits. A study
     * waiting is still studying: it goes on by itself, from the same part, after a restart too.
     */
    val retryAt: Long = 0,
    /** Tries in a row that failed, for [StudyRetry]'s wait. */
    val tries: Int = 0,
) {
    @Serializable
    enum class State {
        /** Going, or ready to go on when the app opens. */
        STUDYING,

        /** The person paused it. */
        PAUSED,

        /** Stopped by something only the person can fix — the login, the key, a page that will not load. [note] says what. */
        BLOCKED,

        /** Every chapter read and noted, the notes distilled. */
        DONE,
    }

    fun chapter(n: Int): Chapter? = chapters.firstOrNull { it.n == n }

    /**
     * New notes were taken: the playbook is put together again, and the guide distilled again, from their first parts —
     * [distilled] set back too, or notes taken after the first distil never reached the guide.
     */
    fun renoted(): Course = copy(consolidated = false, distilled = false, consolidateDone = emptyList(), distilDone = emptyList(), partBegun = "")

    /** [chapter] put in place of the one with its number. */
    fun with(chapter: Chapter): Course = copy(chapters = chapters.map { if (it.n == chapter.n) chapter else it })

    fun replay(n: Int): ReplayRef? = replays.firstOrNull { it.n == n }

    /** [replay] put in place of the one with its number. */
    fun with(replay: ReplayRef): Course = copy(replays = replays.map { if (it.n == replay.n) replay else it })

    /**
     * Chapter [n] scanned for replays: [found] added (each duel once, by its DuelingBook id — a link with and without
     * `&game=` is the same duel — numbered on from the last), the chapter marked. A new one is held out for the exam
     * ([ReplayExam]) unless Ai has read it already elsewhere: [studied] are those replays' ids.
     */
    fun found(n: Int, found: List<String>, studied: Set<String> = emptySet()): Course {
        val known = replays.map { DbReplays.id(it.url) ?: it.url }.toHashSet()
        var next = (replays.maxOfOrNull { it.n } ?: 0) + 1
        val added = ArrayList<ReplayRef>()
        found.forEach { url ->
            val id = DbReplays.id(url) ?: url
            if (known.add(id)) added += ReplayRef(next++, url, chapter = n, exam = ReplayExam.held(url) && id !in studied)
        }
        val c = chapter(n)?.copy(scanned = true)
        return copy(replays = replays + added).let { if (c != null) it.with(c) else it }
    }

    /** Replays noted (or given up on, or held out and read) of all. */
    val replaysDone: Int get() = replays.count { it.state == Chapter.State.NOTED || it.gaveUp || (it.exam && it.state == Chapter.State.READ) }

    /** The replays a study reads and notes: every one but the exam's. */
    val studied: List<ReplayRef> get() = replays.filter { !it.exam }

    /**
     * The exam drawn, once: a fifth of the replays not yet studied held out ([ReplayExam]); one read or noted already is
     * never held out, since the study has seen it, and nor is one Ai read elsewhere ([studied], DuelingBook ids).
     */
    fun drawExam(studied: Set<String> = emptySet()): Course = if (examDrawn) this else copy(
        examDrawn = true,
        replays = replays.map { r ->
            val unseen = (r.state == Chapter.State.PENDING || r.state == Chapter.State.FAILED) && (DbReplays.id(r.url) ?: r.url) !in studied
            r.copy(exam = unseen && ReplayExam.held(r.url))
        },
    )

    /** How far along: chapters noted (or given up on) of all. */
    val done: Int get() = chapters.count { it.state == Chapter.State.NOTED || it.gaveUp }

    val label: String get() = title.ifBlank { start }
}

@Serializable
data class Chapter(
    /** Its place in the guide, from 1. */
    val n: Int,
    val title: String,
    val url: String,
    val kind: Kind = Kind.UNKNOWN,
    val state: State = State.PENDING,
    /** What went wrong the last time, when it did. */
    val error: String = "",
    val attempts: Int = 0,
    /** Words of its text kept in `pages/<n>.md`. */
    val words: Int = 0,
    /** Its page was looked over for DuelingBook replays (1.1.41); a chapter read before then is looked over again. */
    val scanned: Boolean = false,
    /** How deeply its notes were taken ([CourseDepth]): notes from an earlier, shallower study are taken again. */
    val depth: Int = 0,
    /**
     * Its page was looked over for a video (1.1.44: until then a chapter with text beside its video kept the text alone and
     * the video was never played). A chapter read before is opened once more to look.
     */
    val videoChecked: Boolean = false,
    /** The page holds a video. */
    val hasVideo: Boolean = false,
    /** Its video was watched — or given up on, [videoNote] saying why. */
    val watched: Boolean = false,
    /** What became of its video, in words, when it was not watched (waiting for the voice model, protected, would not play). */
    val videoNote: String = "",
    /** Notes are taken a part at a time ([StudyChunks]): the last section noted, 0 before the first part. */
    val notedThrough: Int = 0,
    /** Its sections, once its notes are begun. */
    val sections: Int = 0,
    /** Where its notes ended when the part going now began, or -1: a part stopped half-way is set back to here. */
    val notesMark: Int = -1,
    /**
     * Its page is kept on this computer whole (1.1.51, [PageSnapshot]): its links, pictures and whether it holds a video —
     * the page is never opened again but to watch its video. A chapter read before is opened once more to keep it.
     */
    val saved: Boolean = false,
    /** Pictures kept from its page. */
    val pictures: Int = 0,
) {
    @Serializable
    enum class Kind {
        UNKNOWN,
        TEXT,

        /** Mostly a video: its words come from the video ([State.WAITING] until the study can watch one). */
        VIDEO,
    }

    @Serializable
    enum class State {
        PENDING,

        /** Its text is kept; notes not yet taken. */
        READ,
        NOTED,
        FAILED,

        /** A video chapter this build cannot watch yet: passed over, and taken up by a build that can. */
        WAITING,
    }

    /** Tried [StudyQueue.ATTEMPTS] times and failed every time: passed over, said in the course's note. */
    val gaveUp: Boolean get() = state == State.FAILED && attempts >= StudyQueue.ATTEMPTS
}

/**
 * A DuelingBook replay a chapter links to: read by the app from the replay page ([DbReplays]), kept as DuelingBook's
 * own document (`replays/<n>.json`) and in words (`replays/<n>.md`), then noted like a chapter (`replay-notes/<n>.md`).
 */
@Serializable
data class ReplayRef(
    /** Its place among the course's replays, from 1. */
    val n: Int,
    /** The replay's page, as [DbReplays.normal] writes it. */
    val url: String,
    /** The chapter that links to it. */
    val chapter: Int,
    /** PENDING, READ (kept in words), NOTED or FAILED; never WAITING. */
    val state: Chapter.State = Chapter.State.PENDING,
    val error: String = "",
    val attempts: Int = 0,
    /** Its players, "A vs B", once read. */
    val players: String = "",
    val games: Int = 0,
    /** Held out of the study: read and kept, never noted or shown to a study, for the exam ([ReplayExam]). */
    val exam: Boolean = false,
    /** How deeply its notes were taken ([CourseDepth]). */
    val depth: Int = 0,
    /** As [Chapter.notedThrough]. */
    val notedThrough: Int = 0,
    val sections: Int = 0,
    /** As [Chapter.notesMark]. */
    val notesMark: Int = -1,
) {
    val gaveUp: Boolean get() = state == Chapter.State.FAILED && attempts >= StudyQueue.ATTEMPTS
}

/**
 * How deeply a course's notes are taken. 1: the first study (1.1.37–1.1.41), notes condensed to what changes play.
 * 2: mastery (1.1.43) — read section by section, every section's teaching kept and cited, the cards checked against
 * their text, and the playbook written as it goes. Notes taken at a shallower depth are taken again from the kept text.
 */
object CourseDepth {
    const val FIRST = 1
    const val MASTERY = 2
    const val CURRENT = MASTERY
}

/** Where a course's files are, under the assistant's folder (`<data>/ai`). */
object CoursePaths {
    const val ROOT = "courses"

    fun dir(id: String): String = "$ROOT/${AiMemory.safeId(id)}"
    fun course(id: String): String = "${dir(id)}/course.json"
    fun page(id: String, n: Int): String = "${dir(id)}/pages/$n.md"
    fun notes(id: String, n: Int): String = "${dir(id)}/notes/$n.md"

    /** Chapter [n]'s page as kept whole: its links, its pictures, whether it held a video ([PageSnapshot]). */
    fun snapshot(id: String, n: Int): String = "${dir(id)}/pages/$n.page.json"

    /** The pictures of chapter [n]'s page, kept as files named by their number in its words (`3.png`). */
    fun pictures(id: String, n: Int): String = "${dir(id)}/pictures/$n"

    /** A video chapter's kept pictures, one file each, named by their time in the video in milliseconds (`75000.jpg`). */
    fun frames(id: String, n: Int): String = "${dir(id)}/frames/$n"

    /** A replay as DuelingBook sent it, kept whole so a later build reads it again without loading the page. */
    fun replayRaw(id: String, n: Int): String = "${dir(id)}/replays/$n.json"

    /** A replay in words: what its notes are taken from. */
    fun replayText(id: String, n: Int): String = "${dir(id)}/replays/$n.md"

    fun replayNotes(id: String, n: Int): String = "${dir(id)}/replay-notes/$n.md"

    /** A course's id from its start and when it began: stable, readable, unique enough for one person's courses. */
    fun idFor(start: String, now: Long): String {
        val slug = start.substringAfter("://").substringBefore('?').trimEnd('/').substringAfterLast('/')
            .lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').take(40)
        return (slug.ifBlank { "course" }) + "-" + (now / 1000).toString(36)
    }
}

object CourseCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true; prettyPrint = true }

    fun read(text: String?): Course? = if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(Course.serializer(), text) }.getOrNull()

    fun write(course: Course): String = json.encodeToString(Course.serializer(), course)
}
