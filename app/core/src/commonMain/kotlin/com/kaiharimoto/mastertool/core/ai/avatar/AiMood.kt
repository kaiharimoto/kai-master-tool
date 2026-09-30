package com.kaiharimoto.mastertool.core.ai.avatar

/** What the assistant is doing this moment, as its face reads it. */
data class AiSignals(
    /** A turn is under way. */
    val running: Boolean = false,
    /** Its reply is arriving. */
    val streaming: Boolean = false,
    /** The tool it is running, by name. */
    val tool: String? = null,
    /** A confirm or a question is waiting on the person. */
    val waiting: Boolean = false,
    /** What went wrong, while it is shown. */
    val problem: String? = null,
    /** The person is writing to it. */
    val drafting: Boolean = false,
    /** A Fine Tuning conversation: the person teaching, or Ai studying. */
    val tuning: Boolean = false,
    val studying: Boolean = false,
    /** The microphone is open: the person is speaking to it (1.0.57). */
    val hearing: Boolean = false,
    /** It is speaking its answer aloud (1.0.57, talk mode). */
    val aloud: Boolean = false,
)

/**
 * Which face Ai wears (the mood table, `NEUE.md` §4k): read off [AiSignals] each
 * tick, with the moments that pass — Found it, Done, Sad, Shy, Oops — held for a
 * few seconds from the event that set them, a face Ai chose itself ([express]) over
 * those, and sleep after a minute of nobody doing anything, woken by the next input.
 * Pure: time comes in as seconds, so the table is tested without a clock.
 */
class MoodTracker {
    private var passing: Expression? = null
    private var passingUntil = 0.0
    private var chosen: Expression? = null
    private var chosenUntil = 0.0
    private var asleep = false
    private var asleepAt = 0.0
    private var lastProblem: String? = null

    /** Asleep now, for the hand that pets it (1.0.54, `AvatarPlay`). */
    val sleeping: Boolean get() = asleep

    /** A face that passes: [e] for [seconds] from [now]. */
    fun moment(e: Expression, seconds: Double, now: Double) {
        passing = e
        passingUntil = now + seconds
    }

    /** Ai chose a face (the `express` tool): it wears it for [seconds], over everything but a question. */
    fun express(e: Expression, seconds: Double, now: Double) {
        chosen = e
        chosenUntil = now + seconds.coerceIn(1.0, MAX_EXPRESS)
    }

    /** A search came back with something. */
    fun found(now: Double) = moment(Expression.FOUND, FOUND, now)

    /** A turn ended well. */
    fun done(now: Double) = moment(Expression.DONE, DONE, now)

    /** The person pressed Stop. */
    fun stopped(now: Double) = moment(Expression.SAD, SAD, now)

    /** The person thanked it. */
    fun thanked(now: Double) = moment(Expression.SHY, SHY, now)

    /**
     * The face now, [now] seconds on any clock; [lastInput] is when the person last
     * touched the app on the same clock.
     */
    fun at(s: AiSignals, now: Double, lastInput: Double): Expression {
        if (s.problem != null && s.problem != lastProblem) moment(if (isLimit(s.problem)) Expression.CRYING else Expression.OOPS, PROBLEM, now)
        lastProblem = s.problem
        if (s.waiting) return awake(Expression.WAITING)
        chosen?.let { if (now < chosenUntil) return awake(it) else chosen = null }
        passing?.let { if (now < passingUntil) return it else passing = null }
        val busy = when {
            s.hearing -> Expression.LISTENING
            s.aloud -> Expression.SPEAKING
            s.running && s.streaming -> Expression.SPEAKING
            s.running && s.tool != null -> if (s.tool.removePrefix("mcp__neue__") in reading) Expression.READING else Expression.WORKING
            s.running -> if (s.studying) Expression.READING else Expression.THINKING
            s.drafting -> Expression.LISTENING
            s.tuning && !s.studying -> Expression.LISTENING
            else -> null
        }
        if (busy != null) return awake(busy)
        if (asleep) {
            if (lastInput > asleepAt) {
                asleep = false
                moment(Expression.WAKING, WAKING, now)
                return Expression.WAKING
            }
            return Expression.SLEEPING
        }
        if (now - lastInput >= SLEEP_AFTER) {
            asleep = true
            asleepAt = now
            return Expression.SLEEPING
        }
        return Expression.IDLE
    }

    private fun awake(e: Expression): Expression {
        asleep = false
        return e
    }

    companion object {
        const val FOUND = 1.2
        const val DONE = 2.5
        const val SAD = 2.0
        const val SHY = 2.5
        const val PROBLEM = 4.0
        const val WAKING = 4.2
        const val SLEEP_AFTER = 60.0
        const val MAX_EXPRESS = 8.0

        /** The tools that read: a card, a ruling, a page, a list someone pasted. The rest are work. */
        val reading = setOf(
            "card_info", "rulings", "web_fetch", "watch_video", "import_deck", "import_ygopro_deck", "ygopro_deck", "archetype_guide",
            "skill_view", "memory_read", "get_deck", "get_web", "get_siding", "session_search",
        )

        /** The tools whose results are something found. */
        val finding = setOf("search_cards", "web_search", "ygopro_tournament_decks", "ygopro_player", "list_decks")

        /** The faces Ai may choose for itself: the ones nothing in the app sets. */
        val expressible = listOf(Expression.WINK, Expression.SURPRISED, Expression.DELIGHTED, Expression.LOVE, Expression.ANGRY)

        /** A problem that is a limit reached — a quota, a rate, the steps of a turn — is a loss, not a mistake. */
        fun isLimit(problem: String): Boolean {
            val p = problem.lowercase()
            return listOf("limit", "quota", "429", "too many requests", "credit", "usage").any { it in p }
        }

        /** The person said thank you, in a few of the ways people do. */
        fun isThanks(words: String): Boolean {
            val w = words.lowercase()
            return Regex("""\b(thanks|thank you|thank u|thx|ty|cheers|arigato|arigatou)\b""").containsMatchIn(w) || "ありがと" in w
        }
    }
}
