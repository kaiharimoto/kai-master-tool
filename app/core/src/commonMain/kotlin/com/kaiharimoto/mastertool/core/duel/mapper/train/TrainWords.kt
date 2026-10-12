package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The trainer's settings on this computer (M.md §4.4, §7): **the person's alone**. Whether the helper may run the person's
 * Python is their decision, as Python in Ai World is (`AiSettings.INTERNAL`, so Ai can never turn it on), and their plan
 * overrides the probe's and is never replaced by a new probe or by Ai. Device-only (`SyncedPrefs.DEVICE`): another computer
 * has another Python and another GPU.
 */
@Serializable
data class MapperTrainPrefs(
    /** The helper may run: off until the person switches it on in the Training tab. */
    val on: Boolean = false,
    /** The Python with torch; blank uses Ai World's, then the PATH. */
    val python: String = "",
    /** The person's own plan (`personal`); null follows the probe. */
    val plan: HardwarePlan? = null,
    /** The last probe of this machine. */
    val probe: HardwareProbe? = null,
) {
    /** The plan in force: the person's, else the probe's, else tier S on the CPU. */
    fun plan(cores: Int): HardwarePlan = plan?.copy(personal = true) ?: probe?.let { HardwarePlan.of(it) }
        ?: HardwarePlan.of(HardwareProbe(cpuCores = cores)).copy(why = "Not probed yet: tier S, as on the CPU.")
}

/** The training in words: what the page shows and Ai's `train_status` and `eval_heldout` read. */
object TrainWords {
    /** What installs what the trainer needs, in the person's own Python. */
    const val INSTALL = "pip install torch numpy onnx onnxruntime"

    /** The modules the trainer imports. */
    val MODULES = listOf("torch", "numpy", "onnx", "onnxruntime")

    /** The check that asks the person's Python for each of [MODULES]: one JSON object, module to found. */
    val CHECK: String = "import importlib.util as u, json; print(json.dumps({m: u.find_spec(m) is not None for m in " +
        MODULES.joinToString(",", "[", "]") { "'$it'" } + "}))"

    private val JSON = Json { ignoreUnknownKeys = true }

    /** What [CHECK] printed: the modules missing, or null when it printed something else. */
    fun missingOf(text: String): List<String>? = runCatching {
        val o = JSON.parseToJsonElement(text.trim().lines().last { it.isNotBlank() }).jsonObject
        MODULES.filter { (o[it] as? JsonPrimitive)?.booleanOrNull != true }
    }.getOrNull()

    /** [modules] missing, said with what installs them: "torch not found — pip install torch numpy onnx onnxruntime". */
    fun missing(modules: List<String>): String = if (modules.isEmpty()) "" else "${modules.joinToString(", ")} not found — $INSTALL"

    fun probe(p: HardwareProbe): String = buildString {
        append(p.device.uppercase())
        if (p.deviceName.isNotBlank()) append(": ").append(p.deviceName)
        append(", ").append(gib(p.memoryBytes)).append(if (p.gpu && p.device != "mps") " of video memory" else " of memory")
        append(", ").append(p.cpuCores).append(" cores, ").append(one(p.stepsPerSecond)).append(" steps a second at tier S")
        if (p.torchVersion.isNotBlank()) append(" (torch ").append(p.torchVersion).append(")")
    }

    fun plan(p: HardwarePlan): String =
        "Tier ${p.tier.name}, batch ${p.batch}, ${p.workers} engine worker${if (p.workers == 1) "" else "s"}, ${p.minutes} minutes a round" +
            (if (p.mixed) ", mixed precision" else "") + (if (p.personal) " — yours." else " — the probe's.")

    /** A round's curves: the newest step, the newest held-out evaluation, and what they say about the round. */
    fun curves(c: TrainCurves): String {
        val s = c.steps.lastOrNull() ?: return "No training step yet."
        return buildString {
            append("Step ").append(s.step).append(": loss ").append(three(s.loss)).append(" (policy ").append(three(s.policyLoss))
                .append(", value ").append(three(s.valueLoss)).append("), ").append(one(s.stepsPerSecond)).append(" steps a second.")
            c.evals.lastOrNull()?.let { e ->
                append(" Held out: objective ").append(three(e.objective)).append(", the best move top-1 ").append(pct(e.top1)).append('.')
                val worse = e.valueError.filter { (h, v) -> e.baseline[h]?.let { v > it } == true }.keys
                if (worse.isNotEmpty()) append(" Worse than predicting the mean: ").append(worse.joinToString()).append('.')
            }
            when {
                c.diverged() -> append(" A number went bad: the round diverged (lower the learning rate).")
                c.overfitting() -> append(" Overfitting: the training loss falls while the held-out objective rises.")
                c.plateaued() -> append(" The held-out objective has stopped falling.")
            }
            c.failed?.let { append(" Failed: ").append(it.error) }
        }
    }

    /** A gate's verdict with every number it read. */
    fun gate(r: GateRecord): String = buildString {
        append("Network ").append(r.candidate).append(if (r.promote) " passed" else " did not pass").append(" the gate against ")
        append(if (r.against == 0) "the hand-written order" else "network ${r.against}").append(": ")
        append("more of the front on ").append(r.wins).append(" held-out hands, less on ").append(r.losses).append(", as much on ").append(r.ties)
        append(" (p ").append(p(r.p)).append(", needed below ").append(p(r.bar)).append(", ").append(r.attempts).append(" tried since the last promotion). ")
        append("Front recall ").append(three(r.recall)).append(" against ").append(three(r.againstRecall)).append(" at ").append(GoldfishWords.count(r.budget)).append(" engine moves a hand; ")
        append("search rating ").append(signed(r.elo)).append(" (").append(signed(r.eloLow)).append(" to ").append(signed(r.eloHigh)).append("). ")
        if (r.valueError.isNotEmpty()) {
            append("Value error: ").append(r.valueError.entries.joinToString { (h, v) -> "$h ${three(v)} (${three(r.againstError[h] ?: Double.NaN)})" }).append(". ")
        }
        if (r.regressed > 0) append(r.regressed).append(" confirmed boards lost. ")
        append(r.why)
    }

    /** The ladder: each network's search rating with its range, which is in use, and which failed. */
    fun ladder(l: TrainLadder): String {
        if (l.versions.isEmpty()) return "No network yet: the hand-written order (version 0, rating ${Elo.BASE.roundToInt()}) is in use."
        return buildString {
            append("Version 0, the hand-written order: ").append(Elo.BASE.roundToInt()).append(if (l.inUse == 0) " — in use." else ".")
            l.versions.sortedBy { it.version }.forEach { v ->
                append("\nNetwork ").append(v.version).append(" (tier ").append(v.tier.ifBlank { "?" }).append(", round ").append(v.round).append("): ")
                val r = v.rated
                if (r == null) append("not judged yet") else append(r.elo.roundToInt()).append(" (").append(r.low.roundToInt()).append("–").append(r.high.roundToInt()).append(")")
                append(if (v.version == l.inUse) " — in use" else if (r != null && !v.promoted) " — failed the gate, kept as a file" else "")
            }
            if (l.attempts > 0) append("\n").append(l.attempts).append(" candidate").append(if (l.attempts == 1) "" else "s").append(" tried since the last promotion.")
        }
    }

    /**
     * What the held-out hands say, in aggregate only (M.md §4.6: held out means held out from Ai too): the set's size and
     * budget, and each judged network's mean front recall and value error beside what it was judged against. Never a hand,
     * never a board.
     */
    fun heldOut(l: TrainLadder, set: GateSet?): String = buildString {
        if (set == null) {
            append("The held-out hands are not mapped yet: the first round maps them.")
            return@buildString
        }
        val first = set.hands.count { it.deal.first }
        append(set.hands.size).append(" held-out hands (").append(first).append(" going first, ").append(set.hands.size - first).append(" second), ")
        append("dealt from a seed no round uses and never written as training records; each mapped at ").append(GoldfishWords.count(set.budget)).append(" engine moves. ")
        append("The hand-written order's front recall: ").append(three(set.bare.values.average().takeIf { it.isFinite() } ?: 0.0)).append('.')
        l.gates.takeLast(6).forEach { g ->
            append("\nNetwork ").append(g.candidate).append(": recall ").append(three(g.recall)).append(" against ").append(three(g.againstRecall))
            append(if (g.against == 0) " (hand-written)" else " (network ${g.against})")
            append(", ").append(g.wins).append(" better, ").append(g.losses).append(" worse, ").append(g.ties).append(" level")
            if (g.valueError.isNotEmpty()) {
                val mean = g.valueError.values.average()
                val against = g.againstError.values.takeIf { it.isNotEmpty() }?.average()
                append("; value error ").append(three(mean)).append(" a head on average").append(against?.let { " (against ${three(it)})" }.orEmpty())
            }
            append(if (g.promote) "; promoted." else "; not promoted.")
        }
    }

    private fun gib(bytes: Long) = "${one(bytes / (1L shl 30).toDouble())} GiB"
    private fun one(x: Double) = if (!x.isFinite()) "—" else ((x * 10).roundToInt() / 10.0).toString()
    private fun three(x: Double) = if (!x.isFinite()) "—" else ((x * 1000).roundToInt() / 1000.0).toString()
    private fun pct(x: Double) = if (!x.isFinite()) "—" else "${(x * 100).roundToInt()} %"
    private fun signed(x: Double) = if (!x.isFinite()) "—" else (if (x >= 0) "+" else "−") + abs(x).roundToInt()
    private fun p(x: Double) = if (x < 0.001) "< 0.001" else three(x)
}
