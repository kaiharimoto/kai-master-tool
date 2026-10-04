package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.ai.check.FactCheck
import com.kaiharimoto.mastertool.core.ai.evidence.Numbers
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.pow

/**
 * How much to trust a connection (1.0.99, Phase A, `docs/phases/A.md`): questions with known answers, graded by code
 * and never by a model, run against a connection and kept per connection. A score is a number, so a change to the
 * harness, a skill or a prompt can be shown to help — or not.
 */
@Serializable
data class EvalItem(
    val id: String,
    /** What the model is asked, word for word. */
    val prompt: String,
    val grader: Grader,
    /** Where the expected answer comes from: the app's own arithmetic, the game's rules, Konami's FAQ. */
    val source: String = "",
)

/** How an answer is graded: by code, at the precision the question asked for. */
@Serializable
sealed interface Grader {
    /** A probability as a percentage, right to [decimals] places ([expected] is a fraction of one). */
    @Serializable
    data class Percent(val expected: Double, val decimals: Int = 1) : Grader

    /** A ruling or a rule: yes or no. */
    @Serializable
    data class YesNo(val expected: Boolean) : Grader

    /** A decklist read back: every card by its exact name with its count, nothing more, nothing less. */
    @Serializable
    data class Decklist(val expected: Map<String, Int>) : Grader

    /**
     * A fact-check of a planted answer: [hasError] says whether the answer holds a mistake; the checker passes when it
     * marks a claim wrong exactly when there is one ([about] are words the wrong claim's line must share).
     */
    @Serializable
    data class Planted(val hasError: Boolean, val about: List<String> = emptyList()) : Grader
}

/** One graded answer: whether it passed, and the words the grade read. */
data class Graded(val pass: Boolean, val read: String)

object Grading {
    /** The answer's last line that starts "ANSWER:", or the whole answer when it has none. */
    fun answerLine(answer: String): String =
        answer.lines().lastOrNull { it.trim().uppercase().startsWith("ANSWER") }?.substringAfter(':')?.trim() ?: answer.trim()

    fun grade(grader: Grader, answer: String): Graded = when (grader) {
        is Grader.Percent -> percent(grader, answer)
        is Grader.YesNo -> yesNo(grader, answer)
        is Grader.Decklist -> decklist(grader, answer)
        is Grader.Planted -> Graded(false, "a planted answer is graded on the checker's claims")
    }

    /** The checker's claims on a planted answer, graded: a mistake caught, or a clean answer left alone. */
    fun planted(grader: Grader.Planted, claims: List<FactCheck.Claim>): Graded {
        val wrong = claims.filter { it.verdict == FactCheck.Verdict.WRONG }
        return if (grader.hasError) {
            val caught = wrong.any { c -> grader.about.isEmpty() || grader.about.any { it.lowercase() in (c.claim + " " + c.correction).lowercase() } }
            Graded(caught, if (caught) "caught: " + wrong.first().claim.take(120) else "missed (${wrong.size} other claims marked wrong)")
        } else {
            Graded(wrong.isEmpty(), if (wrong.isEmpty()) "left alone" else "false alarm: " + wrong.first().claim.take(120))
        }
    }

    private fun percent(g: Grader.Percent, answer: String): Graded {
        val line = answerLine(answer)
        val said = Numbers.claimed(line).lastOrNull { it.written.endsWith("%") } ?: Numbers.claimed(line).lastOrNull()
            ?: return Graded(false, "no percentage in “${line.take(60)}”")
        val want = g.expected * 100
        val got = said.value * 100
        val ok = abs(got - want) <= 0.5 * 10.0.pow(-g.decimals) + 1e-9
        return Graded(ok, "${said.written} (expected ${fmt(want, g.decimals)}%)")
    }

    private fun yesNo(g: Grader.YesNo, answer: String): Graded {
        val line = answerLine(answer).lowercase()
        val word = Regex("""\b(yes|no)\b""").find(line)?.value ?: return Graded(false, "neither yes nor no in “${line.take(60)}”")
        return Graded((word == "yes") == g.expected, "$word (expected ${if (g.expected) "yes" else "no"})")
    }

    private val row = Regex("""^\s*[-•*]?\s*(\d+)\s*[x×]?\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val rowAfter = Regex("""^\s*[-•*]?\s*(.+?)\s+[x×]\s*(\d+)\s*$""", RegexOption.IGNORE_CASE)

    private fun decklist(g: Grader.Decklist, answer: String): Graded {
        val tail = answer.substringAfterLast("ANSWER:", answer)
        val read = mutableMapOf<String, Int>()
        tail.lines().forEach { l ->
            val m = row.matchEntire(l)
            val (count, name) = when {
                m != null -> m.groupValues[1].toInt() to m.groupValues[2]
                else -> rowAfter.matchEntire(l)?.let { it.groupValues[2].toInt() to it.groupValues[1] } ?: return@forEach
            }
            val key = key(name)
            if (key.isNotEmpty()) read[key] = (read[key] ?: 0) + count
        }
        val want = g.expected.mapKeys { key(it.key) }
        val missing = want.filter { (k, n) -> read[k] != n }.keys
        val extra = read.keys - want.keys
        val ok = missing.isEmpty() && extra.isEmpty()
        return Graded(ok, if (ok) "all ${want.size} cards right" else "${missing.size} wrong or missing, ${extra.size} not in the list")
    }

    /** A card name as compared: letters and digits only, lowercase — "Maxx \"C\"" and "maxx c" are one card. */
    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private fun fmt(v: Double, d: Int): String {
        val p = 10.0.pow(d)
        val r = kotlin.math.round(v * p) / p
        return if (d == 0) r.toLong().toString() else r.toString()
    }
}

/** A set of items of one kind, and the instructions every item is asked under. */
data class EvalSet(val id: String, val title: String, val about: String, val items: List<EvalItem>, val checker: Boolean = false)

/** One item's outcome over a run: how many of its tries passed, and the first try's answer and grade. */
@Serializable
data class ItemOutcome(
    val id: String,
    val passes: Int,
    val tries: Int,
    /** Whether the first try passed: pass@1 reads this. */
    val firstPass: Boolean = false,
    val read: String = "",
    val answer: String = "",
    val ms: Long = 0,
)

/** One set run against one connection. */
@Serializable
data class EvalRun(
    val set: String,
    val connection: String,
    val model: String = "",
    val at: Long = 0,
    val tries: Int = 1,
    val items: List<ItemOutcome> = emptyList(),
    /** Tokens read and written over the whole run, as the providers reported them. */
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val stoppedEarly: Boolean = false,
) {
    /** The share of first tries that passed: pass@1. */
    val passAt1: Double get() = if (items.isEmpty()) 0.0 else items.count { it.firstPass }.toDouble() / items.size

    /** The share of items every try of which passed: pass^k, consistency rather than luck. */
    val passAll: Double get() = if (items.isEmpty()) 0.0 else items.count { it.passes == it.tries && it.tries > 0 }.toDouble() / items.size
}

/** Every run kept per connection: `ai/evals/<connection>.json`, the newest last, the last [KEEP]. */
object EvalLog {
    const val KEEP = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val serializer = ListSerializer(EvalRun.serializer())

    fun path(connection: String): String = "evals/${AiMemory.safeId(connection)}.json"

    fun read(text: String?): List<EvalRun> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun write(runs: List<EvalRun>): String = json.encodeToString(serializer, runs.sortedBy { it.at }.takeLast(KEEP))

    /** Each set's newest run in [runs]. */
    fun latest(runs: List<EvalRun>): Map<String, EvalRun> = runs.groupBy { it.set }.mapValues { (_, r) -> r.maxBy { it.at } }
}
