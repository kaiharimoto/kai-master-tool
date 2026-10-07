package com.kaiharimoto.mastertool.core.ai.exam

import com.kaiharimoto.mastertool.core.ai.course.DbReplay
import com.kaiharimoto.mastertool.core.ai.course.ReplayStats
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.world.WorldStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The exam (kai, 2026-10: "beat a human player from the guide"; mastery's measure): the replays a course held out — never
 * studied, never shown — become positions the guide's author really faced, and Ai is asked what it would play. Its answer
 * is graded against what the author did, by the app, card by card: whether its first play is the author's first, and how
 * much of the author's turn its plan holds. Taken again after more study, on the same positions, it says whether Ai
 * learned the deck or only read about it.
 *
 * A position is one of the author's turns: the duel so far as the author saw it — their own moves whole, the other
 * player's in the public words of the log alone — up to the turn's first real play.
 */
object AuthorExam {
    data class Point(
        val replay: Int,
        val game: Int,
        val turn: Int,
        val author: String,
        val opponent: String?,
        /** The duel so far, as the author saw it. */
        val context: String,
        /** The author's plays this turn, in order: the answer key. */
        val target: List<DbReplay.Action>,
    ) {
        val id: String get() = "r$replay-g$game-t$turn"

        /**
         * The cards the author played this turn, each once, in the order first played: each play's own card, the first it
         * names (1.1.47: every name in the author's words was taken — "targeting \"Ash Blossom\"" put the other player's
         * card in the answer key).
         */
        val cards: List<String> get() = target.mapNotNull { it.cards.firstOrNull() }.distinctBy { norm(it) }
    }

    /** The author's plays a point grades: the first few of the turn. */
    const val TARGET = 6

    /** The duel so far, at most this long: its end kept, its start cut. */
    const val CONTEXT = 24_000

    /** Positions a run asks: the same ones every run (chosen by id), so runs compare. */
    const val MAX_POINTS = 40

    /** Every turn of [author]'s in replay [n] ([r]) that plays a card: its position and its answer key. */
    fun points(n: Int, r: DbReplay, author: String): List<Point> {
        if (author !in r.players) return emptyList()
        val opponent = r.opponentOf(author)
        val out = ArrayList<Point>()
        for (g in r.games) {
            val earlier = r.games.filter { it.n < g.n }.joinToString("\n") { e ->
                "Game ${e.n}: " + (e.loser?.let { l -> if (l == author) "you lost" else "you won" } ?: "result not recorded") + (e.first?.let { f -> if (f == author) ", you went first" else ", you went second" } ?: "")
            }
            for ((k, t) in g.turns.withIndex()) {
                if (t.n < 1 || t.player != author) continue
                val plays = t.actions.withIndex().filter { (_, a) -> !a.chat && a.player == author && a.phase.isEmpty() && a.cards.isNotEmpty() && !drawn(a, author) }
                if (plays.isEmpty()) continue
                val firstAt = plays.first().index
                val target = plays.map { it.value }.take(TARGET)
                val before = g.turns.take(k) + DbReplay.Turn(t.n, t.player, t.actions.take(firstAt))
                val context = buildString {
                    before.forEach { turn ->
                        appendLine(if (turn.n == 0) "Before the first turn:" else "Turn ${turn.n} — ${if (turn.player == author) "yours" else (opponent ?: turn.player)}:")
                        turn.actions.forEach { a -> line(a, author)?.let { appendLine("  $it") } }
                    }
                    append("Now: your turn ${t.n}. What do you play?")
                }
                // Who and which game always stand at the top; a long duel loses its oldest moves, never them.
                val head = buildString {
                    appendLine("You are $author" + (opponent?.let { ", playing against $it" } ?: "") + ". Game ${g.n}" + (g.first?.let { f -> if (f == author) ", you went first." else ", you went second." } ?: "."))
                    if (earlier.isNotBlank()) appendLine(earlier)
                }
                out += Point(n, g.n, t.n, author, opponent, head + cut(context), target)
            }
        }
        return out
    }

    /** One action as the author saw it; null for what they could not see at all. */
    private fun line(a: DbReplay.Action, author: String): String? {
        val mine = a.player == author
        return when {
            a.chat -> "${if (mine) "You" else a.player} said: “${a.words.replace('\n', ' ')}”"
            mine -> "You: " + a.words.ifBlank { a.play }
            a.public.isNotBlank() -> "${a.player}: ${a.public}"
            a.play.isNotBlank() -> "${a.player}: ${a.play}"
            else -> null
        }?.replace('\n', ' ')
    }

    /**
     * A draw, which is never a play: the play's own name ("Draw card"), or its words starting with "Drew", the player's
     * name before it or not (1.1.47: "kai drew …" was taken as the turn's first play, and its card as the answer).
     */
    private fun drawn(a: DbReplay.Action, author: String): Boolean =
        DRAW_PLAY.containsMatchIn(a.play) || DRAWN.containsMatchIn(a.words.removePrefix(author).trimStart())

    private val DRAW_PLAY = Regex("""^draw\b""", RegexOption.IGNORE_CASE)
    private val DRAWN = Regex("""^(drew|draws?)\b""", RegexOption.IGNORE_CASE)

    private fun cut(text: String): String = if (text.length <= CONTEXT) text else "(earlier moves left out)\n" + text.takeLast(CONTEXT)

    /** The points a run asks, at most [max]: the same ones every time, spread by their id. */
    fun pick(points: List<Point>, max: Int = MAX_POINTS): List<Point> =
        points.sortedBy { fnv(it.id) }.take(max).sortedWith(compareBy({ it.replay }, { it.game }, { it.turn }))

    private fun fnv(s: String): Long {
        var h = 0x811c9dc5.toInt()
        s.forEach { c -> h = (h xor c.code) * 0x01000193 }
        return h.toLong() and 0xffffffffL
    }

    /** [answer] (the cards Ai would play, in order) graded against the author's [target] cards. */
    fun grade(target: List<String>, answer: List<String>): Graded {
        val t = target.map(::norm).filter { it.isNotBlank() }.distinct()
        val a = answer.map(::norm).filter { it.isNotBlank() }.distinct().take(TARGET + 2)
        if (t.isEmpty()) return Graded(false, 0.0, 0.0)
        val hit = a.count { it in t }
        return Graded(a.firstOrNull() == t.first(), hit.toDouble() / t.size, if (a.isEmpty()) 0.0 else hit.toDouble() / a.size)
    }

    data class Graded(val first: Boolean, val recall: Double, val precision: Double) {
        val f1: Double get() = if (recall + precision == 0.0) 0.0 else 2 * recall * precision / (recall + precision)
    }

    fun norm(s: String): String = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotBlank() }.joinToString(" ")
}

/** One position's result. */
@Serializable
data class ExamAnswer(
    val id: String,
    val replay: Int,
    val game: Int,
    val turn: Int,
    /** The author's cards this turn, in order. */
    val target: List<String>,
    /** Ai's, in order. */
    val answer: List<String> = emptyList(),
    val first: Boolean = false,
    val recall: Double = 0.0,
    val precision: Double = 0.0,
    /** Ai's reason, in its words. */
    val why: String = "",
    /** It did not answer (the step ran out, the model failed): counted as wrong. */
    val missed: Boolean = false,
)

/** One sitting of the exam, with what Ai knew and how hard it thought, so runs compare. */
@Serializable
data class ExamRun(
    val at: Long,
    val deckId: String,
    /** The course whose held-out replays it asked (1.1.47): a deck studied from two courses keeps their sittings apart. */
    val course: String = "",
    val model: String = "",
    val effort: String = "",
    /** The playbook's entries and the guide's characters when it sat. */
    val playbook: Int = 0,
    val guide: Int = 0,
    val answers: List<ExamAnswer> = emptyList(),
) {
    val asked: Int get() = answers.size
    val firsts: Int get() = answers.count { it.first }
    val f1: Double get() = if (answers.isEmpty()) 0.0 else answers.sumOf { AuthorExam.Graded(it.first, it.recall, it.precision).f1 } / answers.size

    /** The first-play agreement with its 80% range. */
    fun range(): Pair<Double, Double> = WorldStats.wilson(firsts, asked, 1.2816)

    fun words(): String {
        if (asked == 0) return "No position was asked."
        val (lo, hi) = range()
        return "Played the author's first play in $firsts of $asked turns (${pct(firsts.toDouble() / asked)}; 80% range ${pct(lo)}–${pct(hi)}); " +
            "its plans held ${pct(answers.sumOf { it.recall } / asked)} of the author's cards."
    }
}

object ExamLog {
    const val DIR = "exams"
    fun path(deckId: String): String = "$DIR/${AiMemory.safeId(deckId)}.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    fun read(text: String?): List<ExamRun> = if (text.isNullOrBlank()) emptyList() else
        runCatching { json.decodeFromString(ListSerializer(ExamRun.serializer()), text) }.getOrDefault(emptyList())

    fun write(runs: List<ExamRun>): String = json.encodeToString(ListSerializer(ExamRun.serializer()), runs)

    /**
     * A sitting not finished yet (1.1.46): every answer is kept as it is given, so a sitting the model's limit, the network
     * or the app closing stopped goes on from the next position, and is logged with the others only once it is whole.
     */
    fun sitting(deckId: String): String = "$DIR/${AiMemory.safeId(deckId)}.sitting.json"

    fun readSitting(text: String?): ExamRun? = if (text.isNullOrBlank()) null else
        runCatching { json.decodeFromString(ExamRun.serializer(), text) }.getOrNull()

    fun writeSitting(run: ExamRun): String = json.encodeToString(ExamRun.serializer(), run)

    /**
     * The answers of [sitting] a new sitting keeps: those to positions it still asks ([ids]), when it was sat with the
     * same [model] and [effort] — else none, and it begins again, so one sitting is never two models' answers.
     */
    fun resume(sitting: ExamRun?, deckId: String, model: String, effort: String, ids: List<String>, course: String = "", playbook: Int = sitting?.playbook ?: 0, guide: Int = sitting?.guide ?: 0): List<ExamAnswer> {
        if (sitting == null || sitting.deckId != deckId || sitting.model != model || sitting.effort != effort) return emptyList()
        // Another course's positions share these ids; and what was learned since makes it another sitting (1.1.47).
        if (sitting.course != course || sitting.playbook != playbook || sitting.guide != guide) return emptyList()
        val asked = ids.toSet()
        return sitting.answers.filter { it.id in asked }.distinctBy { it.id }
    }

    /**
     * [run] beside the one before it, in words: what changed — counted on the positions both asked (1.1.47: as more
     * held-out replays are read the forty asked move, and two sittings on different positions do not compare).
     */
    fun compare(run: ExamRun, before: ExamRun?): String {
        if (before == null || before.asked == 0) return run.words()
        val shared = run.answers.map { it.id }.toSet() intersect before.answers.map { it.id }.toSet()
        if (shared.isEmpty()) return run.words() + " Last time asked other positions, so the two do not compare."
        val now = run.answers.filter { it.id in shared }
        val then = before.answers.filter { it.id in shared }
        return run.words() + " On the ${shared.size} positions both asked: ${pct(now.count { it.first }.toDouble() / now.size)} now, " +
            "${pct(then.count { it.first }.toDouble() / then.size)} last time (${before.playbook} playbook entries then, ${run.playbook} now)."
    }
}

private fun pct(x: Double): String = "${kotlin.math.round(x * 100).toInt()}%"

/** Who the exam is about, when the course does not say under which name its author plays. */
object ExamAuthor {
    /** The player in the most of [entries], only when ahead of every other: a tie is nobody (1.1.47). */
    fun lead(entries: List<ReplayStats.Entry>): String? {
        val counts = entries.flatMap { it.replay.players.distinct() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val top = counts.firstOrNull() ?: return null
        return if (counts.getOrNull(1)?.value == top.value) null else top.key
    }
}
