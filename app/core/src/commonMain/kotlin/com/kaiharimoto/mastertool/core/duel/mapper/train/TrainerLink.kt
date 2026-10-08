package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/*
 * The app's side of the trainer's process (`tools/mapper-train`, M.md §4.4): the settings it is started with, bounded so no
 * hand — Ai's included — can ask for something unsafe or absurd, and the JSON Lines it reports on, read into the curves the
 * page draws and Ai's `train_status` reads.
 */

/**
 * A training round's settings, `--config`'s JSON. Every field is held inside [LIMITS] by [bounded]: Ai sets these through
 * `train_tune` and never past them.
 */
@Serializable
data class TrainConfig(
    val lr: Double = 3e-4,
    val batch: Int = 128,
    val epochs: Int = 4,
    val weightDecay: Double = 0.01,
    val warmupSteps: Int = 200,
    val valueWeight: Double = 1.0,
    val policyWeight: Double = 1.0,
    val seed: Long = 1L,
    val maxMinutes: Int = 30,
    val valFraction: Double = 0.1,
) {
    /** This config with every field inside [LIMITS]; a number that is not one (NaN, infinite) is the default. */
    fun bounded(): TrainConfig {
        val d = TrainConfig()
        fun Double.inside(lo: Double, hi: Double, default: Double) = if (isFinite()) coerceIn(lo, hi) else default
        return copy(
            lr = lr.inside(1e-6, 1e-2, d.lr),
            batch = batch.coerceIn(8, 1024),
            epochs = epochs.coerceIn(1, 50),
            weightDecay = weightDecay.inside(0.0, 0.3, d.weightDecay),
            warmupSteps = warmupSteps.coerceIn(0, 10_000),
            valueWeight = valueWeight.inside(0.0, 10.0, d.valueWeight),
            policyWeight = policyWeight.inside(0.0, 10.0, d.policyWeight),
            maxMinutes = maxMinutes.coerceIn(1, 24 * 60),
            valFraction = valFraction.inside(0.05, 0.5, d.valFraction),
        )
    }

    /** The trainer's `--config` JSON (its snake_case keys). */
    fun json(): String = with(bounded()) {
        buildJsonObject {
            put("lr", JsonPrimitive(lr)); put("batch", JsonPrimitive(batch)); put("epochs", JsonPrimitive(epochs))
            put("weight_decay", JsonPrimitive(weightDecay)); put("warmup_steps", JsonPrimitive(warmupSteps))
            put("value_weight", JsonPrimitive(valueWeight)); put("policy_weight", JsonPrimitive(policyWeight))
            put("seed", JsonPrimitive(seed)); put("max_minutes", JsonPrimitive(maxMinutes)); put("val_fraction", JsonPrimitive(valFraction))
        }.toString()
    }

    companion object {
        /** The bounds, in words, for Ai's tool description and the page. */
        const val LIMITS = "lr 1e-6–1e-2, batch 8–1024, epochs 1–50, weight decay 0–0.3, warmup 0–10,000 steps, " +
            "value and policy weights 0–10, at most 24 hours a round, validation 5–50 %."
    }
}

/** One line the trainer printed. */
sealed interface TrainEvent {
    /** A round began on [records] records (training and validation), [skipped] refused by the contract. */
    data class Start(val tier: String, val records: Int, val skipped: Int = 0) : TrainEvent
    data class Step(val step: Int, val loss: Double, val policyLoss: Double, val valueLoss: Double, val lr: Double, val stepsPerSecond: Double) : TrainEvent
    /**
     * An evaluation on the held-out split: [valLoss] with the run's own weights, [objective] with the value weight fixed when
     * training began (what runs are compared on), the policy's top-1 agreement, and each value head's error in its own units
     * beside predicting its mean ([baseline]). [heldOut] is false when nothing was held out and the numbers are the training
     * split's: never read as validation.
     */
    data class Eval(
        val step: Int,
        val valLoss: Double,
        val top1: Double,
        val valueError: Map<String, Double>,
        val objective: Double = valLoss,
        val baseline: Map<String, Double> = emptyMap(),
        val heldOut: Boolean = true,
    ) : TrainEvent

    /**
     * The round ended and saved [checkpoint]: [finished] when it ran its epochs, [stopped] when it was paused (the stop file)
     * or ran out of minutes. Only a finished round goes to the gate.
     */
    data class Done(val checkpoint: String, val finished: Boolean = true, val stopped: Boolean = false) : TrainEvent
    data class Failed(val error: String) : TrainEvent

    /**
     * A line that was not an event this side reads (a warning the trainer's libraries printed; a population member's own
     * events, tagged `member`, which the page reads apart): kept for the log, never acted on.
     */
    data class Other(val text: String) : TrainEvent

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(line: String): TrainEvent {
            val o = runCatching { JSON.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return Other(line)
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            if (o.containsKey("member")) return Other(line)
            fun b(k: String, default: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: default
            fun m(k: String) = (o[k] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap().orEmpty()
            return when (s("event")) {
                "start" -> Start(s("tier"), i("records_train") + i("records_val"), i("skipped"))
                "step" -> Step(i("step"), d("loss"), d("policy_loss"), d("value_loss"), d("lr"), d("steps_per_s"))
                "eval" -> Eval(
                    i("step"), d("val_loss"), d("val_policy_top1"), m("val_value_mae"),
                    (o["val_objective"] as? JsonPrimitive)?.doubleOrNull ?: d("val_loss"), m("val_value_baseline_mae"),
                    heldOut = s("split") != "train",
                )
                "done" -> Done(s("checkpoint"), b("finished", true), b("stopped", false))
                "error" -> Failed(s("error").ifBlank { s("message") })
                else -> Other(line)
            }
        }
    }
}

/** A round's curves as the events arrive: what the page draws and `train_status` summarises. */
class TrainCurves {
    val steps = ArrayList<TrainEvent.Step>()
    val evals = ArrayList<TrainEvent.Eval>()
    var done: TrainEvent.Done? = null
        private set
    var failed: TrainEvent.Failed? = null
        private set

    /** Every held-out evaluation added, whatever its numbers; [plateaued] and [overfitting] read the finite ones. */
    fun add(e: TrainEvent) {
        when (e) {
            is TrainEvent.Step -> steps += e
            is TrainEvent.Eval -> if (e.heldOut) evals += e
            is TrainEvent.Done -> done = e
            is TrainEvent.Failed -> failed = e
            else -> Unit
        }
    }

    /**
     * Whether the validation objective has stopped falling: the best of the last [window] evaluations is no better than the
     * best before them. What Ai reads to stop a round early, or to try other settings. The objective, not the loss: its value
     * weight is fixed, so a setting change cannot make the curve fall by itself.
     */
    fun plateaued(window: Int = 3): Boolean {
        val e = finite()
        if (e.size <= window) return false
        val before = e.dropLast(window).minOf { it.objective }
        val recent = e.takeLast(window).minOf { it.objective }
        return recent >= before - 1e-6
    }

    /** Whether a number went bad (NaN or infinite): the round diverged, and Ai stops it and lowers the learning rate. */
    fun diverged(): Boolean = evals.any { !it.objective.isFinite() } || steps.any { !it.loss.isFinite() }

    private fun finite() = evals.filter { it.objective.isFinite() }

    /** Whether the training loss falls while the validation objective rises over the last [window] evaluations: overfitting. */
    fun overfitting(window: Int = 3): Boolean {
        val all = finite()
        if (all.size < window + 1) return false
        val e = all.takeLast(window + 1)
        val rising = e.zipWithNext().all { (a, b) -> b.objective > a.objective }
        val stepsIn = steps.filter { it.step in e.first().step..e.last().step }
        val falling = stepsIn.size >= 2 && stepsIn.last().loss < stepsIn.first().loss
        return rising && falling
    }
}
