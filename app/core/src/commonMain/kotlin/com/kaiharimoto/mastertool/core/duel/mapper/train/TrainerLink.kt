package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    /** This config with every field inside [LIMITS]. */
    fun bounded(): TrainConfig = copy(
        lr = lr.coerceIn(1e-6, 1e-2),
        batch = batch.coerceIn(8, 1024),
        epochs = epochs.coerceIn(1, 50),
        weightDecay = weightDecay.coerceIn(0.0, 0.3),
        warmupSteps = warmupSteps.coerceIn(0, 10_000),
        valueWeight = valueWeight.coerceIn(0.0, 10.0),
        policyWeight = policyWeight.coerceIn(0.0, 10.0),
        maxMinutes = maxMinutes.coerceIn(1, 24 * 60),
        valFraction = valFraction.coerceIn(0.05, 0.5),
    )

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
    data class Start(val tier: String, val records: Int) : TrainEvent
    data class Step(val step: Int, val loss: Double, val policyLoss: Double, val valueLoss: Double, val lr: Double, val stepsPerSecond: Double) : TrainEvent
    data class Eval(val step: Int, val valLoss: Double, val top1: Double, val valueError: Map<String, Double>) : TrainEvent
    data class Done(val checkpoint: String) : TrainEvent
    data class Failed(val error: String) : TrainEvent

    /** A line that was not an event (a warning the trainer's libraries printed): kept for the log, never acted on. */
    data class Other(val text: String) : TrainEvent

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(line: String): TrainEvent {
            val o = runCatching { JSON.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return Other(line)
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            return when (s("event")) {
                "start" -> Start(s("tier"), i("records"))
                "step" -> Step(i("step"), d("loss"), d("policy_loss"), d("value_loss"), d("lr"), d("steps_per_s"))
                "eval" -> Eval(
                    i("step"), d("val_loss"), d("val_policy_top1"),
                    (o["val_value_mae"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap().orEmpty(),
                )
                "done" -> Done(s("checkpoint"))
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

    fun add(e: TrainEvent) {
        when (e) {
            is TrainEvent.Step -> steps += e
            is TrainEvent.Eval -> evals += e
            is TrainEvent.Done -> done = e
            is TrainEvent.Failed -> failed = e
            else -> Unit
        }
    }

    /**
     * Whether the validation loss has stopped falling: the best of the last [window] evaluations is no better than the best
     * before them. What Ai reads to stop a round early, or to try other settings.
     */
    fun plateaued(window: Int = 3): Boolean {
        if (evals.size <= window) return false
        val before = evals.dropLast(window).minOf { it.valLoss }
        val recent = evals.takeLast(window).minOf { it.valLoss }
        return recent >= before - 1e-6
    }

    /** Whether the training loss falls while the validation loss rises over the last [window] evaluations: overfitting. */
    fun overfitting(window: Int = 3): Boolean {
        if (evals.size < window + 1) return false
        val e = evals.takeLast(window + 1)
        val rising = e.zipWithNext().all { (a, b) -> b.valLoss > a.valLoss }
        val stepsIn = steps.filter { it.step in e.first().step..e.last().step }
        val falling = stepsIn.size >= 2 && stepsIn.last().loss < stepsIn.first().loss
        return rising && falling
    }
}
