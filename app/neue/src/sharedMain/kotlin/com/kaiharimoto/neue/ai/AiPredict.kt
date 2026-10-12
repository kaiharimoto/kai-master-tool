package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.predict.Prediction
import com.kaiharimoto.mastertool.core.ai.predict.Predictions
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.neue.NeueHolders
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.math.round

/**
 * Ai's stated chances (Phase I, I.6; F1): `predict` keeps one about something the app will see end — the duel in play, a
 * round at the event in Prep — and the record is settled against what happened each time it is read ([predictions]).
 * Ai never marks its own: the duel's record and the round's logged games do.
 */
internal class AiPredict(private val h: NeueHolders, private val ai: AiState) {
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = if (name == "predict") predict(i) else null

    private suspend fun predict(i: JsonObject): MetaAnswer {
        val raw = ToolArgs.double(i, "chance") ?: return fail("Say the chance, from 0 to 1.")
        // A percentage is read as one: 70 is 0.7. Exactly 1 stays 1.
        val chance = if (raw > 1.0 && raw <= 100.0) raw / 100 else raw
        if (chance.isNaN() || chance < 0.0 || chance > 1.0) return fail("A chance is from 0 to 1, or a percentage: $raw is neither.")
        val connection = ai.runConnection ?: ai.prefs.connection
        val now = System.currentTimeMillis()
        val said = ToolArgs.string(i, "words")?.trim().orEmpty().take(200)
        val id = "p-" + UUID.randomUUID().toString().take(12)
        val p = when (ToolArgs.string(i, "about")) {
            Prediction.DUEL -> {
                val game = h.duel.game ?: return fail("No duel is in play to say anything about.")
                val seat = ToolArgs.int(i, "seat")?.takeIf { it in 0..1 } ?: return fail("Say the seat you say wins: 0 or 1, as duel_state numbers them.")
                val duel = game.header.id
                if (h.duel.readResults().any { it.duel == duel && !it.whatIf }) return fail("That duel is over: a chance is said before it is decided.")
                Prediction(
                    id, now, Prediction.DUEL, duel, chance, seat, said.ifBlank { DuelWords.seatLabel(game.state, seat) + " wins" },
                    game.state.turn, connection?.id.orEmpty(), connection?.model.orEmpty(),
                )
            }
            Prediction.ROUND -> {
                val event = ToolArgs.string(i, "event_id")?.let { h.prep.doc.event(it) } ?: h.prep.active
                    ?: return fail("There is no event in Prep to say a round of: set one first (set_event).")
                val n = ToolArgs.int(i, "round")?.takeIf { it >= 1 } ?: return fail("Say the round's number.")
                val p = Prediction(
                    id, now, Prediction.ROUND, Prediction.roundTarget(event.id, n), chance, null,
                    said.ifBlank { "the person wins round $n at ${event.name}" }, null, connection?.id.orEmpty(), connection?.model.orEmpty(),
                )
                val known = Predictions.settle(listOf(p), emptyList(), h.prep.doc.games, h.prep.doc.events, now).single()
                if (!known.open) return fail("Round $n at ${event.name} is already decided by the games logged: a chance is said before.")
                p
            }
            else -> return fail("about is duel or round.")
        }
        val all = ai.predictions() + p
        ai.files.write(Predictions.PATH, Predictions.write(all))
        val mine = all.filter { it.model == p.model }
        val record = Predictions.words(mine)
        return MetaAnswer(
            "Kept: ${percent(chance)} that ${p.words}. The app scores it when it sees how it went.\nYour record${if (p.model.isBlank()) "" else " on ${p.model}"}: $record",
            "Predicted ${percent(chance)}: ${p.words}",
        )
    }

    private fun percent(x: Double): String = "${round(x * 100).toInt()} %"
}

/**
 * Every prediction Ai has said, each open one settled against what the app has seen end — the duel's record, the round's
 * games — and written back when any settled.
 */
internal suspend fun AiState.predictions(): List<Prediction> {
    val read = Predictions.read(files.read(Predictions.PATH))
    if (read.none { it.open }) return read
    val settled = Predictions.settle(read, h.duel.readResults(), h.prep.doc.games, h.prep.doc.events, System.currentTimeMillis())
    if (settled != read) files.write(Predictions.PATH, Predictions.write(settled))
    return settled
}
