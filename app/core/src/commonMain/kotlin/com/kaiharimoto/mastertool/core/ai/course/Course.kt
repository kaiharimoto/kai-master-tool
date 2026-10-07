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
    /** Tokens spent on it so far, all told; the study stops at the person's cap ([cap]). */
    val spent: Long = 0,
    /** The most it may spend, in tokens; 0 is no cap. */
    val cap: Long = 0,
    /** Pages loaded on [loadsDay] (days since the epoch): a person reads only so many a day ([HumanPace.DAILY]). */
    val loadsDay: Long = 0,
    val loads: Int = 0,
    /** The guide as it was when the study began: what the person's review compares the study's writes with. */
    val guideBefore: String? = null,
    /** The person was shown what the study wrote (once: Keep or Undo all). */
    val reviewed: Boolean = false,
) {
    @Serializable
    enum class State {
        /** Going, or ready to go on when the app opens. */
        STUDYING,

        /** The person paused it. */
        PAUSED,

        /** Stopped by something only the person can fix — the cap, the login, a page that will not load. [note] says what. */
        BLOCKED,

        /** Every chapter read and noted, the notes distilled. */
        DONE,
    }

    fun chapter(n: Int): Chapter? = chapters.firstOrNull { it.n == n }

    /** [chapter] put in place of the one with its number. */
    fun with(chapter: Chapter): Course = copy(chapters = chapters.map { if (it.n == chapter.n) chapter else it })

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

/** Where a course's files are, under the assistant's folder (`<data>/ai`). */
object CoursePaths {
    const val ROOT = "courses"

    fun dir(id: String): String = "$ROOT/${AiMemory.safeId(id)}"
    fun course(id: String): String = "${dir(id)}/course.json"
    fun page(id: String, n: Int): String = "${dir(id)}/pages/$n.md"
    fun notes(id: String, n: Int): String = "${dir(id)}/notes/$n.md"

    /** A video chapter's kept pictures, one file each, named by their time in the video in milliseconds (`75000.jpg`). */
    fun frames(id: String, n: Int): String = "${dir(id)}/frames/$n"

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
