package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.playbook.Play

/**
 * A study done in parts (kai, 2026-10: "perform the task in chunks, if it gets stopped, it has the ability to pick up
 * where it left off"). Each part is a conversation of its own, and what it finished is saved before the next begins, so
 * a study stopped anywhere — the model's limit, the network, Pause, the app closed — loses at most the part it was in,
 * and takes that part up again from where it began:
 *
 * - a chapter's or a replay's notes, a run of its sections at a time ([parts]): [Chapter.notedThrough] is the last
 *   section noted, and [Chapter.notesMark] where the notes ended when the part going now began;
 * - the playbook put together a kind of entry at a time, then whole ([consolidateParts]);
 * - the guide distilled a few chapters at a time, a few replays at a time, then whole ([distilParts]).
 */
object StudyChunks {
    /** Words a part of a chapter or a replay holds, about: what one conversation reads and notes whole. */
    const val WORDS = 3_000

    /** Chapters' notes distilled into the guide at once. */
    const val DISTIL_CHAPTERS = 6

    /** Replays' notes distilled into the guide at once. */
    const val DISTIL_REPLAYS = 12

    /** The last part of the playbook and the guide: everything at once, tied together. */
    const val WHOLE = "whole"

    /** Sections [first] to [last] of [of]. */
    data class Part(val first: Int, val last: Int, val of: Int) {
        val label: String get() = if (first == last) "§$first of $of" else "§$first–§$last of $of"
    }

    /** [text]'s sections in parts of about [WORDS] words, each at least one section, in order. */
    fun parts(text: String, words: Int = WORDS): List<Part> {
        val sections = Sections.of(text)
        if (sections.isEmpty()) return emptyList()
        val out = ArrayList<Part>()
        var first = sections.first().n
        var count = 0
        sections.forEachIndexed { i, s ->
            count += s.words
            val last = i == sections.lastIndex
            if (count >= words || last) {
                out += Part(first, s.n, sections.size)
                count = 0
                if (!last) first = sections[i + 1].n
            }
        }
        return out
    }

    /** The part after section [through]; null when every part is noted. */
    fun next(text: String, through: Int): Part? = parts(text).firstOrNull { it.last > through }

    /** Where notes [notes] are set back to, at [mark]: what the part going when it stopped had not finished goes. */
    fun setBack(notes: String, mark: Int): String = if (mark in 0 until notes.length) notes.take(mark).trimEnd().let { if (it.isEmpty()) "" else it + "\n" } else notes

    /** The playbook put together a kind at a time, then whole: its links, the gaps, the open questions. */
    val consolidateParts: List<String> = Play.Kind.entries.map { it.word } + WHOLE

    /** The next part of the playbook to put together, or null when it is whole. */
    fun nextConsolidate(course: Course): String? = consolidateParts.firstOrNull { it !in course.consolidateDone }

    /** The guide distilled from [course]: its noted chapters a few at a time ("ch:1-6"), its replays ("replays:1-12"), then whole. */
    fun distilParts(course: Course): List<String> {
        val chapters = course.chapters.filter { it.state == Chapter.State.NOTED }.map { it.n }.sorted()
        val replays = course.replays.filter { it.state == Chapter.State.NOTED && !it.exam }.map { it.n }.sorted()
        return chapters.chunked(DISTIL_CHAPTERS).map { "ch:${it.first()}-${it.last()}" } +
            replays.chunked(DISTIL_REPLAYS).map { "replays:${it.first()}-${it.last()}" } + WHOLE
    }

    /** The next part of the guide to distil, or null when the guide has them all. */
    fun nextDistil(course: Course): String? = distilParts(course).firstOrNull { it !in course.distilDone }

    /** A distil part's numbers: chapters or replays, first to last; null for [WHOLE]. */
    fun range(part: String): Triple<String, Int, Int>? {
        val kind = part.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return null
        val (a, b) = part.substringAfter(':').split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return Triple(kind, a, b)
    }

    /** A part of the playbook or the guide, in words, for the person. */
    fun words(part: String): String = when {
        part == WHOLE -> "as a whole"
        range(part) != null -> range(part)!!.let { (kind, a, b) -> (if (kind == "ch") "chapters" else "replays") + if (a == b) " $a" else " $a–$b" }
        else -> "its ${part}s"
    }
}

/**
 * What a study does when a step fails: the model or the network stopped it — a limit reached, the provider busy, the
 * connection gone — and it waits and tries again, as long as it takes, from the same part; or something only the person
 * can fix stopped it (the key, the account, no connection), and it waits for them. Anything else is tried a few times,
 * with a wait between, before it waits for the person.
 */
object StudyRetry {
    enum class Kind {
        /** A limit or a stumble: tried again after a wait, with no end. */
        WAIT,

        /** Not known: tried again [TRIES] times. */
        RETRY,

        /** Only the person can fix it. */
        BLOCK,
    }

    /** Tries of a failure not known before the study waits for the person. */
    const val TRIES = 4

    /** The longest wait between tries: an hour. */
    const val LONGEST_MS = 60 * 60_000L

    private val BLOCKS = listOf(
        "credit balance", "billing", "payment required", "insufficient_quota", "insufficient quota", "api key", "did not accept the key",
        "cannot use", "no connection set up", "unauthorized", "authentication", "not logged in", "please log in", "/login",
    )

    private val WAITS = listOf(
        "rate limit", "rate-limit", "rate_limit", "usage limit", "limit reached", "hit your limit", "limit will reset", "resets",
        "overloaded", "too many requests", "try again", "temporarily", "unavailable", "capacity", "busy", "timeout", "timed out",
        "connection", "network", "unreachable", "could not reach", "stumbled", "429", "500", "502", "503", "504", "529",
        "stopped without an answer", "socket", "reset by peer", "broken pipe",
    )

    fun kind(message: String, auth: Boolean = false): Kind {
        if (auth) return Kind.BLOCK
        val m = message.lowercase()
        return when {
            BLOCKS.any { it in m } -> Kind.BLOCK
            WAITS.any { it in m } -> Kind.WAIT
            else -> Kind.RETRY
        }
    }

    /** The wait before try [tries] (from 1): a minute, then doubling, never past [LONGEST_MS]. */
    fun waitMs(tries: Int): Long = (60_000L shl (tries - 1).coerceIn(0, 10)).coerceAtMost(LONGEST_MS)

    /** Whether a failure of [kind] on try [tries] waits for the person rather than trying again. */
    fun givesUp(kind: Kind, tries: Int): Boolean = kind == Kind.BLOCK || (kind == Kind.RETRY && tries > TRIES)

    /** What the person reads while it waits: why, and when it goes on. */
    fun note(why: String, minutes: Long): String =
        "Stopped: ${why.trim().take(200)}. It goes on by itself from where it stopped in " +
            (if (minutes <= 1) "a minute" else if (minutes < 60) "$minutes minutes" else "an hour") + "."
}
