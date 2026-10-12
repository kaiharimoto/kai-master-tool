package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Gameplay Mapper's training, Ai's hands (Phase I, I.10; M.md §4.6): Ai sees a run, starts and pauses it, tunes it inside
 * [TrainConfig]'s bounds, puts a candidate through the gate and reads the held-out hands' aggregates — and never switches the
 * helper on (the person's switch), never changes the person's plan, never promotes a network the gate failed, and never sees
 * a held-out hand or its board. Every action it takes is written to the run's log.
 */
object TrainTools {
    private const val DECK = "A deck's id, or 'open' for the builder's deck (the default)"

    val status = ToolSpec(
        "train_status",
        "Gameplay Mapper's training (10, the Training tab; the desk only), read-only: whether the person switched the helper on, " +
            "their Python and whether torch is found (and what to install), this machine's probe and the plan in force (tier, " +
            "batch, engine workers, minutes a round, and whether it is the person's own), the run's step and progress, the " +
            "round's curves (loss, held-out objective, top-1, plateaued, diverged, overfitting), the ladder (each network's search " +
            "rating with its 95 % range, which is in use), the last gate's verdict with its numbers, the settings you may tune, the " +
            "run's log and the library's size.",
        schema { string("deck_id", DECK) },
        ToolGroup.LOOK,
        phase = 3,
    )

    val start = ToolSpec(
        "train_start",
        "Gameplay Mapper's training: starts a round on the builder's deck, or carries a paused one on — deals hands, maps them " +
            "with the network in use (a quarter with none), trains on the person's computer with their own Python, exports the " +
            "network and puts it through the gate. Refused unless the person switched the helper on in the Training tab, which " +
            "only they can do. Runs in the background on page 10; train_status follows it.",
        schema { string("deck_id", DECK) },
        ToolGroup.APP,
        phase = 3,
    )

    val pause = ToolSpec(
        "train_pause",
        "Gameplay Mapper's training: pauses the run between steps — while training, the trainer saves its checkpoint and stops; " +
            "train_start carries it on exactly where it stopped.",
        schema { },
        ToolGroup.APP,
        phase = 3,
    )

    val tune = ToolSpec(
        "train_tune",
        "Gameplay Mapper's training: sets the next round's training settings, each held inside its bounds (${TrainConfig.LIMITS}). " +
            "The batch, the tier, the engine workers and the minutes are the machine's plan, which the person sets: never these. " +
            "Read train_status's curves first: lower the learning rate when a round diverged, add epochs when it was still falling, " +
            "raise weight decay when it overfit. Each change is written to the run's log as yours.",
        schema {
            number("lr", "The learning rate")
            integer("epochs", "Passes over the records a round", min = 1, max = 50)
            number("weight_decay", "AdamW's weight decay")
            integer("warmup_steps", "Steps of linear warmup", min = 0, max = 10_000)
            number("value_weight", "The value loss's weight against the policy's")
            number("policy_weight", "The policy loss's weight")
            number("val_fraction", "The share of hands held out to validate on")
            integer("seed", "The training's seed")
            string("why", "One line: what the curves showed")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val gate = ToolSpec(
        "train_gate",
        "Gameplay Mapper's training: judges the newest candidate network waiting at the gate on the held-out hands — front " +
            "recall at the gate's one budget against the network in use, each value head against it, the person's confirmed " +
            "boards, a sign test with 5 % shared among every candidate since the last promotion. Promotes only on a pass; a " +
            "candidate that fails stays a file. A round ends at the gate by itself: this is for one paused before it.",
        schema { string("deck_id", DECK) },
        ToolGroup.APP,
        phase = 3,
    )

    val heldOut = ToolSpec(
        "eval_heldout",
        "Gameplay Mapper's training, read-only: what the held-out hands say, in aggregate only — how many there are and at what " +
            "budget, the hand-written order's front recall, and each judged network's mean recall, wins and losses and value " +
            "error beside what it was judged against. Never a hand or a board of them: held out is held out from you too.",
        schema { string("deck_id", DECK) },
        ToolGroup.LOOK,
        phase = 3,
    )

    val all: List<ToolSpec> = listOf(status, start, pause, tune, gate, heldOut)

    /** The tools that only read. */
    val reading: Set<String> = setOf(status.name, heldOut.name)

    /**
     * [base] with what [input] gives (`train_tune`'s snake_case keys), held inside [TrainConfig.bounded]; the batch and minutes
     * stay [base]'s, since they are the machine's plan. A key given that is not a number is said in the second list.
     */
    fun tuned(base: TrainConfig, input: JsonObject): Pair<TrainConfig, List<String>> {
        val problems = ArrayList<String>()
        fun d(k: String): Double? = (input[k] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.content.trim().toDoubleOrNull() ?: run { problems += "$k is not a number."; null } }
        fun i(k: String): Int? = d(k)?.toInt()
        val next = base.copy(
            lr = d("lr") ?: base.lr,
            epochs = i("epochs") ?: base.epochs,
            weightDecay = d("weight_decay") ?: base.weightDecay,
            warmupSteps = i("warmup_steps") ?: base.warmupSteps,
            valueWeight = d("value_weight") ?: base.valueWeight,
            policyWeight = d("policy_weight") ?: base.policyWeight,
            valFraction = d("val_fraction") ?: base.valFraction,
            seed = (input["seed"] as? JsonPrimitive)?.longOrNull ?: base.seed,
        ).bounded()
        return next to problems
    }
}
