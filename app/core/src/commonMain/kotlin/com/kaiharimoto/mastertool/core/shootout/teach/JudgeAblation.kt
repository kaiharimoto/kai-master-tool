package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.duel.mapper.compare.PairedMath
import com.kaiharimoto.mastertool.core.shootout.math.Normal
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.round

/**
 * Is it better? (Phase S, S4.4; `docs/phases/S.md` §6¾ "Is it better?"): what each thing Ai is handed with a hand is worth,
 * measured. The person's newest blind answers are held out; Ai judges each of those hands afresh four times — with everything
 * it is handed, and once without each of the rubric, the similar hands and the model's prediction — and every answer is held
 * to the person's own. Agreement within one step, calibration (how honest its "sure" is), and each piece's difference read
 * pair by pair on the same hands. **Run only when the person presses Run**, kept apart from the trials: an answer here is
 * never a trial, so it moves no rating and no trust.
 */
object JudgeAblation {

    /** What a brief may leave out. */
    enum class Variant(val words: String) {
        FULL("Everything it is handed"),
        NO_RUBRIC("Without the rubric"),
        NO_EXAMPLES("Without your similar hands"),
        NO_PREDICTION("Without the model's prediction"),
    }

    /** Hands held out per run: enough to see a piece worth a few hands in twenty, cheap enough to run on a whim. */
    const val HANDS = 20

    /** The person's blind answers needed before the held-out ones: the examples and the model need something to read. */
    const val BEFORE = 30

    /** Roughly what one judged hand costs, in tokens, for the estimate shown before a run. */
    const val TOKENS_EACH = 5_000

    /** The person's blind answers, oldest first: what a run may hold out and what it may read. */
    fun blind(trials: List<StoredTrial>): List<StoredTrial> =
        trials.filter { it.judge == StoredTrial.PERSON && !it.sawAi && (it.answer != null || it.prefer != null) }.sortedBy { it.at }

    /** The hands a run holds out: the person's [n] newest blind answers, when at least [BEFORE] came before them; null when too few. */
    fun pick(trials: List<StoredTrial>, n: Int = HANDS): List<StoredTrial>? {
        val blind = blind(trials)
        if (blind.size < BEFORE + n) return null
        return blind.takeLast(n)
    }

    /** How many more blind answers a run needs, or 0. */
    fun short(trials: List<StoredTrial>, n: Int = HANDS): Int = (BEFORE + n - blind(trials).size).coerceAtLeast(0)

    /** One of Ai's answers in a run: the hand it was the person's trial for, the variant, what it said and whether it agreed. */
    @Serializable
    data class Said(
        val trial: String,
        val variant: String,
        val answer: String? = null,
        val prefer: String? = null,
        val sure: Double? = null,
        val agrees: Boolean = false,
    )

    /** One run: when, on which connection and model, the hands held out, and every answer. */
    @Serializable
    data class Run(
        val id: String,
        val at: Long,
        val model: String? = null,
        val connection: String? = null,
        val hands: List<String> = emptyList(),
        val said: List<Said> = emptyList(),
        val failed: Int = 0,
        val deckPrint: String? = null,
    )

    /** Every run for one deck and target, kept as `<deck>/<target>.ablation.json` beside the trials. */
    @Serializable
    data class Log(val version: Int = 1, val deck: String = "", val opponent: String? = null, val runs: List<Run> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }

    fun encode(log: Log): String = json.encodeToString(Log.serializer(), log)

    fun decode(text: String?): Log? = if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(Log.serializer(), text) }.getOrNull()

    /** Ai's answer to the person's [person] trial under [variant], held to the person's. */
    fun said(person: StoredTrial, variant: Variant, answer: String?, prefer: String?, sure: Double?): Said =
        Said(person.id, variant.name, answer, prefer, sure, Agreement.agrees(person, answer, prefer) == true)

    /**
     * What a variant came to: [agreed] of [of] within one step, its 80 % range, the Brier score of its "sure" (lower is more
     * honest; a coin's 0.25), and against everything it is handed on the same hands — [lost] hands it agreed on only with
     * everything, [gained] only without, and the exact McNemar p of that split.
     */
    data class Score(
        val variant: Variant,
        val agreed: Int,
        val of: Int,
        val range: Range,
        val brier: Double?,
        val lost: Int = 0,
        val gained: Int = 0,
        val p: Double = 1.0,
    ) {
        val share: Double get() = if (of == 0) 0.0 else agreed.toDouble() / of
    }

    /** Each variant's score in [run], everything first. */
    fun score(run: Run): List<Score> {
        val byVariant = run.said.groupBy { it.variant }
        val full = byVariant[Variant.FULL.name].orEmpty().associateBy { it.trial }
        return Variant.entries.mapNotNull { v ->
            val said = byVariant[v.name].orEmpty()
            if (said.isEmpty()) return@mapNotNull null
            val k = said.count { it.agrees }
            val sure = said.filter { it.sure != null }
            val brier = if (sure.isEmpty()) null else sure.sumOf { s -> val y = if (s.agrees) 1.0 else 0.0; (s.sure!! - y) * (s.sure - y) } / sure.size
            var lost = 0
            var gained = 0
            if (v != Variant.FULL) said.forEach { s ->
                val f = full[s.trial] ?: return@forEach
                if (f.agrees && !s.agrees) lost++
                if (!f.agrees && s.agrees) gained++
            }
            Score(v, k, said.size, Agreement.wilson(k.toDouble(), said.size.toDouble(), Normal.Z80), brier, lost, gained, PairedMath.mcnemar(lost, gained))
        }
    }

    /** A variant's line in words: "Without the rubric: 14 of 20 (70 %), 3 fewer than with it (p 0.25)". */
    fun words(s: Score): String = buildString {
        append("${s.variant.words}: ${s.agreed} of ${s.of} within a step (${pct(s.share)}; 80 %: ${pct(s.range.lower)} to ${pct(s.range.upper)})")
        s.brier?.let { append(", sureness scored ${two(it)}") }
        if (s.variant != Variant.FULL) {
            val d = s.gained - s.lost
            append(
                when {
                    d < 0 -> ", ${-d} fewer than with everything (p ${two(s.p)})"
                    d > 0 -> ", $d more than with everything (p ${two(s.p)})"
                    else -> ", as many as with everything"
                },
            )
        }
    }

    private fun pct(x: Double) = "${round(x * 100).toInt()} %"

    /** Two decimals, the same on every platform (no `String.format` in common code). */
    private fun two(x: Double): String {
        val h = round(x * 100).toInt()
        return "${h / 100}.${(h % 100).toString().padStart(2, '0')}"
    }
}
