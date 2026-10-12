package com.kaiharimoto.mastertool.core.ai.predict

import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.prep.Backtest
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.round

/**
 * A chance Ai said a game would go one way (Phase I, I.6; F1: "every probability Ai states about a game is logged, then
 * scored against what happened"). Ai says it through `predict`, about something the app will see end — the duel at the
 * table, or a round at an event in Prep — and it is scored once that is known, never by Ai.
 */
@Serializable
data class Prediction(
    val id: String = "",
    /** When it was said (ms). */
    val at: Long = 0L,
    /** [DUEL] or [ROUND]. */
    val about: String = DUEL,
    /** The duel's header id; for a round, "<event id>#<round>". */
    val target: String = "",
    /** The chance it gave, 0–1, that it goes as [words] say. */
    val chance: Double = 0.5,
    /** For a duel, the seat it said wins; a round is always the person's to win. */
    val seat: Int? = null,
    /** What it said would happen, in words: "Seat 0 (kai) wins". */
    val words: String = "",
    /** The duel's turn when it was said: a call on turn 9 is easier than one on turn 1, and the score says when. */
    val turn: Int? = null,
    /** Who said it: the connection and its model. */
    val connection: String = "",
    val model: String = "",
    /** Whether it went as said; null until the app knows. */
    val happened: Boolean? = null,
    /** When it was known (ms). */
    val settled: Long? = null,
    /** Why it will never be known: a draw, a round left even. Not scored. */
    val void: String? = null,
) {
    val open: Boolean get() = happened == null && void == null

    companion object {
        const val DUEL = "duel"
        const val ROUND = "round"

        fun roundTarget(event: String, round: Int) = "$event#$round"
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PredictionLog(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION, val predictions: List<Prediction> = emptyList()) {
    companion object {
        const val VERSION = 1
    }
}

object Predictions {
    /** Under `<data>/ai`: one file, every prediction ever said, synced with the rest of Ai's files. */
    const val PATH = "predictions.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun read(text: String?): List<Prediction> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(PredictionLog.serializer(), text).predictions }.getOrDefault(emptyList())

    fun write(predictions: List<Prediction>): String = json.encodeToString(PredictionLog.serializer(), PredictionLog(predictions = predictions))

    /** A round's games are known by two days after its event, when it has no third game to wait for. */
    const val ROUND_DAYS = 2L

    /**
     * Every open prediction that can now be known, settled: a duel by its record ([DuelResult.duel], never a what-if), a round
     * by its games at the event — two won or two lost is the match, and two days after the event a round won or lost on
     * fewer games stands (a match to time). A draw, or a round left even after its event, is void: never scored.
     */
    fun settle(predictions: List<Prediction>, duels: List<DuelResult>, games: List<TestGame>, events: List<PrepEvent>, now: Long): List<Prediction> =
        predictions.map { p -> if (!p.open) p else settleOne(p, duels, games, events, now) }

    private fun settleOne(p: Prediction, duels: List<DuelResult>, games: List<TestGame>, events: List<PrepEvent>, now: Long): Prediction = when (p.about) {
        Prediction.DUEL -> {
            val r = duels.filter { it.duel == p.target && !it.whatIf }.maxByOrNull { it.ended }
            when {
                r == null -> p
                r.winner == null -> p.copy(void = "the duel was a draw", settled = r.ended)
                else -> p.copy(happened = r.winner == p.seat, settled = r.ended)
            }
        }
        Prediction.ROUND -> {
            val event = p.target.substringBeforeLast('#')
            val round = p.target.substringAfterLast('#').toIntOrNull()
            val played = games.filter { it.eventId == event && it.round == round }
            val wins = played.count { it.result == TestGame.WIN }
            val losses = played.count { it.result == TestGame.LOSS }
            val last = played.maxOfOrNull { it.at }
            val day = IsoDate.epochDay(events.firstOrNull { it.id == event }?.date)
            val over = day != null && now / MS_DAY > day + ROUND_DAYS
            when {
                wins >= 2 || losses >= 2 -> p.copy(happened = wins > losses, settled = last)
                over && wins != losses -> p.copy(happened = wins > losses, settled = last)
                over && played.isNotEmpty() -> p.copy(void = "the round was left even", settled = last)
                else -> p
            }
        }
        else -> p
    }

    private const val MS_DAY = 86_400_000L

    /**
     * The settled predictions scored as F.2 scores the app's own calls ([Backtest.score]): Brier against a coin's 0.25, the
     * calibration in bins, and each model apart (the score's "matchups" are its models here).
     */
    fun score(predictions: List<Prediction>): Backtest.Score {
        val settled = predictions.filter { it.happened != null }
        val calls = settled.map { p ->
            Backtest.Call(p.about, p.turn ?: 0, p.model, p.model.ifBlank { "a model" }, p.at, p.chance, p.happened == true, 0)
        }
        return Backtest.score(calls, predictions.count { it.void != null })
    }

    /** At least this many settled before a score is called better or worse than a coin. */
    const val ENOUGH = 20

    /** The score in words, for Test scores and for Ai's own `predict` answer. */
    fun words(predictions: List<Prediction>): String {
        val s = score(predictions)
        val open = predictions.count { it.open }
        if (s.n == 0) {
            return if (predictions.isEmpty()) "No prediction said yet." else
                "${predictions.size} said, none settled yet ($open waiting on a duel or a round to end)."
        }
        return buildString {
            append("${s.n} settled: Brier ${dec(s.brier)} against a coin's 0.25")
            append(
                when {
                    s.n < ENOUGH -> " — too few to say whether better than a coin"
                    s.skill > 0 -> " — better than a coin by ${pct(s.skill)}"
                    else -> " — no better than a coin"
                },
            )
            append(".")
            if (open > 0) append(" $open still waiting.")
            if (s.undecided > 0) append(" ${s.undecided} void (a draw, a round left even), not scored.")
            val filled = s.bins.filter { it.n > 0 }
            if (filled.isNotEmpty()) {
                append(" Calibration: ")
                append(filled.joinToString("; ") { b -> "said ${pct(b.from)}–${pct(b.to)}: ${b.n} at ${pct(b.said)}, came true ${pct(b.won)}" })
                append(".")
            }
        }
    }

    private fun pct(x: Double): String = "${round(x * 100).toInt()} %"

    private fun dec(x: Double): String {
        val r = round(x * 1000) / 1000.0
        return r.toString()
    }
}
