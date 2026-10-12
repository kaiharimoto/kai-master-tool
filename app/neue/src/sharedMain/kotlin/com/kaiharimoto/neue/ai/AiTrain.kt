package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.TrainTools
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPaths
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateSet
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLadder
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLoop
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainState
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.mapper.FileTrainStore
import com.kaiharimoto.neue.mapper.MapperTrainer
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Gameplay Mapper's training, Ai's hands (Phase I, I.10; M.md §4.6): reading the run, starting and pausing it, tuning it
 * inside its bounds, the gate, and the held-out hands in aggregate. Every action is the page's own ([MapperTraining]) and is
 * written to the run's log as Ai's. Ai never switches the trainer on, never sets the person's plan, never promotes — the gate
 * does — and never reads a held-out hand.
 */
internal class AiTrain(private val h: NeueHolders) {
    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "train_status" -> status(i)
        "train_start" -> start(i)
        "train_pause" -> pause()
        "train_tune" -> tune(i)
        "train_gate" -> gate(i)
        "eval_heldout" -> heldOut(i)
        else -> null
    }

    private fun idOf(i: JsonObject): String? = ToolArgs.string(i, "deck_id")?.takeUnless { it.equals("open", ignoreCase = true) } ?: h.builder.deckId

    /** [id]'s run as kept: its state, ladder and gate set, read off disk (any deck, the open one or not). */
    private suspend fun read(id: String): Triple<TrainState?, TrainLadder?, GateSet?> = withContext(Dispatchers.IO) {
        val store = FileTrainStore(File(Platform.dataDir, "${FxPaths.FOLDER}/${MapperPaths.deck(id)}/${MapperPaths.TRAIN}"))
        Triple(
            store.read(TrainLoop.STATE)?.let(TrainState::decode),
            store.read(TrainLoop.LADDER)?.let(TrainLadder::decode),
            store.read(TrainLoop.GATE)?.let(GateSet::decode),
        )
    }

    private suspend fun status(i: JsonObject): MetaAnswer {
        if (!MapperTrainer.possible) return ok(MapperTrainer.why, "Not on this device")
        val t = h.training
        val id = idOf(i) ?: return fail("Save the deck first: a deck's network is trained on its cards and kept with it.")
        val (state, ladder, set) = read(id)
        val open = id == h.builder.deckId
        val prefs = t.prefs
        val text = buildString {
            append("The trainer: ").append(if (prefs.on) "on" else "off — only the person switches it on, in the Training tab").append(".\n")
            append("Python: ").append(t.python?.version ?: "not found yet")
            t.missing?.let { m -> append("; ").append(if (m.isEmpty()) "torch, numpy, onnx and onnxruntime found" else TrainWords.missing(m)) }
            t.checkSaid?.let { append(" (").append(it).append(")") }
            append(".\nThis machine: ").append(prefs.probe?.let(TrainWords::probe) ?: "not probed yet").append(".\n")
            append("The plan: ").append(TrainWords.plan(t.plan)).append('\n')
            val st = state ?: TrainState()
            append("\nThe run: ")
            if (st.round == 0) append("no round yet.") else {
                append("round ").append(st.round).append(", ").append(TrainState.words(st.phase).lowercase())
                append(if (open && t.running) " (running now)" else if (st.phase != TrainState.IDLE) " (paused: train_start carries it on)" else "").append('.')
                if (st.records > 0) append(" ").append(GoldfishWords.count(st.records)).append(" records this round.")
            }
            if (st.said.isNotBlank()) append(" Last: ").append(st.said)
            if (open && t.running) t.progress?.let { p -> append("\nNow: ").append(TrainState.words(p.phase)).append(if (p.total > 0) " ${p.done} of ${p.total}" else "").append(' ').append(p.words) }
            if (open) append("\nThe round's curves: ").append(TrainWords.curves(t.curves))
            append("\n\nThe ladder:\n").append(TrainWords.ladder(ladder ?: TrainLadder()))
            (ladder?.lastGate)?.let { append("\n\nThe last gate: ").append(TrainWords.gate(it)) }
            append("\n\nWhat train_tune may set (now): ").append(st.config.bounded().json()).append(". Bounds: ").append(TrainConfig.LIMITS)
            append(" The batch, tier, workers and minutes are the plan's.")
            if (st.log.isNotEmpty()) {
                append("\n\nThe run's log, newest last:")
                st.log.takeLast(6).forEach { n -> append("\n- ").append(n.by).append(": ").append(n.what) }
            }
            val sides = withContext(Dispatchers.IO) { h.mapper.read(id) }
            append("\n\nThe library: ").append(sides[true]?.library?.boards?.size ?: 0).append(" boards going first, ")
                .append(sides[false]?.library?.boards?.size ?: 0).append(" going second.")
            set?.let { append(" Held out: ").append(it.hands.size).append(" hands at ").append(it.budget).append(" engine moves (eval_heldout for what they say).") }
        }
        return ok(text, "Read the training")
    }

    private suspend fun start(i: JsonObject): MetaAnswer {
        val t = h.training
        val id = idOf(i)
        if (id != null && id != h.builder.deckId) return fail("Training runs on the builder's deck: open that deck first.")
        if (!t.prefs.on) return fail(withContext(Dispatchers.Main) { t.refusal() } ?: "The trainer is off.")
        // Python looked for first, when it has not been this run; the written effects read, so the round maps what plays.
        if (t.python == null || t.missing == null) withContext(Dispatchers.Main) { t.check(probe = t.prefs.probe == null) }?.join()
        if (!h.effects.loaded) h.effects.reloadNow()
        val refused = withContext(Dispatchers.Main) { t.start(TrainState.AI) }
        if (refused != null) return fail(refused)
        val st = withContext(Dispatchers.Main) { t.state }
        return ok(
            "Started (or carried on) a round on ${h.builder.deckName.ifBlank { "the deck" }}: it maps hands, trains with ${TrainWords.plan(t.plan)} " +
                "exports the network and puts it through the gate. It runs in the background on page 10's Training tab; train_status follows it" +
                (if (st.round > 0) " (round ${st.round} was last)." else "."),
            "Started a training round",
        )
    }

    private suspend fun pause(): MetaAnswer {
        val refused = withContext(Dispatchers.Main) { h.training.pause(TrainState.AI) }
        return if (refused != null) fail(refused) else ok("Pausing: the round stops at its next step (while training, the trainer saves its checkpoint first). train_start carries it on.", "Paused the training")
    }

    private suspend fun tune(i: JsonObject): MetaAnswer {
        val t = h.training
        if (h.builder.deckId == null) return fail("Save the deck first.")
        if (withContext(Dispatchers.Main) { t.running }) return fail("A round is running: its settings are tuned between rounds (train_pause first, or wait for it).")
        val (state, _, _) = read(h.builder.deckId!!)
        val (config, problems) = TrainTools.tuned((state ?: TrainState()).config, i)
        val why = ToolArgs.string(i, "why").orEmpty()
        val set = withContext(Dispatchers.Main) { t.tune(config, TrainState.AI, why) } ?: return fail("The run's state could not be read: nothing was changed.")
        return ok(
            "The next round trains with ${set.json()} (each held inside ${TrainConfig.LIMITS}). The batch and minutes stay the plan's." +
                problems.joinToString("") { " $it" },
            "Tuned the training",
        )
    }

    private suspend fun gate(i: JsonObject): MetaAnswer {
        val t = h.training
        val id = idOf(i)
        if (id != null && id != h.builder.deckId) return fail("The gate runs on the builder's deck: open that deck first.")
        if (!h.effects.loaded) h.effects.reloadNow()
        val refused = withContext(Dispatchers.Main) { t.gate(TrainState.AI) }
        if (refused != null) return fail(refused)
        withContext(Dispatchers.Main) { t.current }?.join()
        val last = withContext(Dispatchers.Main) { t.ladder.lastGate }
        return if (last != null) ok(TrainWords.gate(last), if (last.promote) "Network ${last.candidate} passed the gate" else "Network ${last.candidate} did not pass")
        else fail(t.said ?: "The gate kept nothing.")
    }

    private suspend fun heldOut(i: JsonObject): MetaAnswer {
        val id = idOf(i) ?: return fail("Save the deck first.")
        val (_, ladder, set) = read(id)
        return ok(TrainWords.heldOut(ladder ?: TrainLadder(), set), "Read the held-out hands in aggregate")
    }
}
