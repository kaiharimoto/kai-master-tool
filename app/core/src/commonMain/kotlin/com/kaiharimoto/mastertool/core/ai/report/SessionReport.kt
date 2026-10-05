package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * What one Fine Tuning session came to (1.0.54, kai: "at the end of teaching sessions, it should
 * output a PDF guide/report … Ai should also report a confidence score of their ability of
 * understanding the deck and the ability to play it itself and win, say, in a mirror match"),
 * filed by Ai with the `session_report` tool as the session's last act.
 *
 * The three scores are Ai's own estimate, 0 to 100, and each is about a different thing:
 * [understanding] is how well it knows what the deck is for and how its cards fit; [playing] is
 * how well it could pilot the deck itself, turn by turn; [mirror] is the share of best-of-three
 * matches it expects to win against a competent player piloting the same deck — 50 is even.
 */
@Serializable
data class SessionReport(
    val deckId: String,
    val deckName: String,
    /** Epoch milliseconds, when it was filed. */
    val at: Long,
    /** [MODES]: taught by the person, studied online, or learned from first principles. */
    val mode: String,
    val intensity: String = "",
    val summary: String = "",
    val learned: List<String> = emptyList(),
    val insights: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val understanding: Int = 0,
    val playing: Int = 0,
    val mirror: Int = 0,
    /** Why the scores are what they are, and what would raise them. */
    val why: String = "",
    /** What Ai asked the person, and what they answered, in order. */
    val questions: List<Asked> = emptyList(),
    /** When the session began, for its length. */
    val startedAt: Long = 0,
) {
    @Serializable
    data class Asked(val question: String, val answer: String)

    val minutes: Int get() = if (startedAt <= 0 || at <= startedAt) 0 else ((at - startedAt) / 60_000L).toInt()

    companion object {
        const val TAUGHT = "tune"
        const val STUDIED = "study"
        const val PRINCIPLES = "principles"
        val MODES = listOf(TAUGHT, STUDIED, PRINCIPLES)

        fun modeWords(mode: String): String = when (mode) {
            TAUGHT -> "Taught by you"
            STUDIED -> "Studied from the cards and online"
            PRINCIPLES -> "Learned from first principles"
            else -> "Fine Tuning"
        }

        /**
         * A score from what a model sent: a number, or text holding one; clamped to 0–100. Only a
         * fraction strictly between 0 and 1 reads as a share (0.62 is 62); a whole number is taken
         * as given, so a 1 sent as the score it means is 1, never 100.
         */
        fun score(raw: Double?): Int {
            val value = raw?.takeUnless { it.isNaN() } ?: 0.0
            val percent = if (value > 0 && value < 1) value * 100 else value
            return percent.coerceIn(0.0, 100.0).roundToInt()
        }
    }
}

/** Every report filed for a deck, oldest first: its own file, `reports/<deckId>.json`, beside the guide. */
object ReportLog {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; isLenient = true }
    private val serializer = ListSerializer(SessionReport.serializer())

    /** The file a deck's reports are kept in, under Ai's own folder. */
    fun path(deckId: String): String = "reports/${AiMemory.safeId(deckId)}.json"

    fun read(text: String?): List<SessionReport> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList()).sortedBy { it.at }

    fun write(reports: List<SessionReport>): String = json.encodeToString(serializer, reports.sortedBy { it.at })

    /**
     * [log] with [report] added. Every report is kept (1.1.9, kai: no cap to what Ai knows of a deck — a report's
     * lessons and questions are part of it; the newest 60 before): [keep] is for a caller that wants fewer.
     */
    fun add(log: List<SessionReport>, report: SessionReport, keep: Int = Int.MAX_VALUE): List<SessionReport> =
        (log + report).sortedBy { it.at }.takeLast(keep)

    /** How a score moved since the report before [latest]; null for the first. */
    fun change(log: List<SessionReport>, latest: SessionReport, of: (SessionReport) -> Int): Int? {
        val before = log.filter { it.at < latest.at }.maxByOrNull { it.at } ?: return null
        return of(latest) - of(before)
    }
}

/**
 * A deck's guide, as the living document shows it (1.0.54): the memory file's entries sorted
 * under their sections by the label each begins with ("Card roles: [[X]] — starter"), in the
 * order a reader wants them, and anything unlabelled under Notes.
 */
data class GuideDoc(val title: String, val sections: List<Section>) {
    data class Section(val name: String, val entries: List<String>)

    val entryCount: Int get() = sections.sumOf { it.entries.size }
    val isEmpty: Boolean get() = entryCount == 0

    /** Card names written as `[[Name]]`, most mentioned first: the deck's key cards, by the guide's own account. */
    fun cardMentions(): List<String> =
        sections.flatMap { s -> s.entries.flatMap { e -> CARD.findAll(e).map { it.groupValues[1].trim() }.toList() } }
            .filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    companion object {
        /** The sections, in reading order; Fine Tuning's skills write under these labels. */
        val ORDER = listOf(
            "Goals", "Game plan", "Lines", "Connections", "Card roles", "Weak points", "Side deck", "Insights", "Open questions", "Sources",
        )
        private val ALIASES = mapOf(
            "goal" to "Goals", "win conditions" to "Goals", "plan" to "Game plan",
            "combos" to "Lines", "line" to "Lines", "combo" to "Lines",
            "connection" to "Connections", "pairs" to "Connections", "synergies" to "Connections", "synergy" to "Connections", "interactions" to "Connections",
            "roles" to "Card roles", "card role" to "Card roles", "weaknesses" to "Weak points", "weak point" to "Weak points",
            "siding" to "Side deck", "side" to "Side deck", "insight" to "Insights", "questions" to "Open questions", "open question" to "Open questions",
            "source" to "Sources",
        )
        private val CARD = Regex("""\[\[([^\]]+)]]""")

        /** The person's profile (Learn About You, 1.0.54): USER.md's entries by these labels. */
        val PROFILE = listOf("Goals", "Preferences", "Workflow", "How you play", "Decks", "Events", "Notes")
        private val PROFILE_ALIASES = mapOf(
            "goal" to "Goals", "aims" to "Goals", "preference" to "Preferences", "likes" to "Preferences", "dislikes" to "Preferences",
            "style" to "Preferences", "workflow" to "Workflow", "process" to "Workflow", "habits" to "Workflow", "routine" to "Workflow",
            "play" to "How you play", "playstyle" to "How you play", "play style" to "How you play", "skill" to "How you play",
            "deck" to "Decks", "event" to "Events", "tournaments" to "Events", "schedule" to "Events",
        )

        /** The person's profile from USER.md, sectioned as [PROFILE]. */
        fun profile(text: String?): GuideDoc = parse(text, PROFILE, PROFILE_ALIASES)

        fun parse(text: String?, order: List<String> = ORDER, aliases: Map<String, String> = ALIASES): GuideDoc {
            val doc = AiMemory.parse(text.orEmpty())
            val title = doc.preamble.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()
            val grouped = LinkedHashMap<String, MutableList<String>>()
            doc.entries.forEach { entry ->
                val (section, body) = split(entry, order, aliases)
                grouped.getOrPut(section) { mutableListOf() } += body
            }
            val ordered = order.mapNotNull { name -> grouped[name]?.let { Section(name, it) } } +
                grouped.filterKeys { it !in order }.map { (k, v) -> Section(k, v) }
            return GuideDoc(title, ordered)
        }

        /** An entry's section and what follows its label; an entry with no known label is a note. */
        fun split(entry: String, order: List<String> = ORDER, aliases: Map<String, String> = ALIASES): Pair<String, String> {
            val clean = entry.trim().removePrefix("**")
            val colon = clean.indexOf(':')
            if (colon in 1..24) {
                val label = clean.substring(0, colon).removeSuffix("**").trim()
                val body = clean.substring(colon + 1).trim().removePrefix("**").trim()
                val known = order.firstOrNull { it.equals(label, ignoreCase = true) } ?: aliases[label.lowercase()]
                if (known != null && body.isNotEmpty()) return known to body
            }
            return "Notes" to entry.trim()
        }
    }
}

/** What Ai asked the person in a session with `ask_user`, and what came back, in order. */
object SessionQuestions {
    private const val ANSWERED = "The person answered: "

    fun of(turns: List<ChatTurn>): List<SessionReport.Asked> {
        val parts = turns.flatMap { it.parts }
        val answers = parts.filterIsInstance<Part.ToolResult>()
            .filter { it.name == "ask_user" && !it.isError }
            .associate { it.id to it.content.removePrefix(ANSWERED).trim() }
        return parts.filterIsInstance<Part.ToolUse>()
            .filter { it.name == "ask_user" }
            .mapNotNull { use ->
                val question = (use.input["question"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                if (question.isEmpty()) null else SessionReport.Asked(question, answers[use.id].orEmpty())
            }
    }

    /** Whether Ai has filed its report in [turns] already. */
    fun reported(turns: List<ChatTurn>): Boolean =
        turns.any { t -> t.parts.any { it is Part.ToolUse && it.name == "session_report" } }
}
