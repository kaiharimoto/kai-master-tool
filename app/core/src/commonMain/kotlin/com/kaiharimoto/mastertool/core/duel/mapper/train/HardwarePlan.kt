package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Weights sized to the machine (M.md §4.4, kai: "weights adjustable based on the user's setup" — setup meaning hardware). The
 * trainer's probe (`python -m mapper_train.hardware`) says what the desk has; [HardwarePlan.of] turns that into a model tier,
 * a batch, the engine's workers and a time budget. The person may override any of it in Settings; Ai may propose a plan and
 * never change the person's override.
 */

/** The network's sizes (`tools/mapper-train`): S d=128 × 2 layers, M d=256 × 4, L d=512 × 8. */
@Serializable
enum class ModelTier { S, M, L }

/** What the trainer's probe reported. Unknown fields from a newer trainer are ignored. */
@Serializable
data class HardwareProbe(
    /** "cuda", "rocm", "mps" or "cpu". */
    val device: String = "cpu",
    @SerialName("device_name") val deviceName: String = "",
    /** VRAM on a GPU; the system's memory on "mps" (unified) and "cpu". */
    @SerialName("memory_bytes") val memoryBytes: Long = 0L,
    @SerialName("cpu_cores") val cpuCores: Int = 1,
    @SerialName("torch_version") val torchVersion: String = "",
    /** Tier S training steps a second on synthetic data: how fast this machine really is. */
    @SerialName("steps_per_s") val stepsPerSecond: Double = 0.0,
) {
    val gpu: Boolean get() = device == "cuda" || device == "rocm" || device == "mps"

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The probe's JSON, or null when it is not a probe. */
        fun parse(text: String): HardwareProbe? = runCatching { JSON.decodeFromString(serializer(), text.trim()) }.getOrNull()
    }
}

/** How training runs on this machine. */
@Serializable
data class HardwarePlan(
    val tier: ModelTier = ModelTier.S,
    val batch: Int = 128,
    /** Engine workers mapping hands while the trainer trains. */
    val workers: Int = 1,
    /** Minutes a training round may take before it saves and stops. */
    val minutes: Int = 30,
    /** Mixed precision (on a CUDA or ROCm GPU). */
    val mixed: Boolean = false,
    /** Why this plan, in words, for the page and for Ai. */
    val why: String = "",
    /** Set by the person: Ai's proposals and a new probe never replace it. */
    val personal: Boolean = false,
) {
    companion object {
        private const val GIB = 1L shl 30

        /** Below this many tier S steps a second a GPU is no faster than it looks, and the tier stays S. */
        const val SLOW_STEPS = 20.0

        /**
         * The plan for [p] with [cores] on the machine:
         * - **tier**: on a CUDA or ROCm GPU, L from 12 GiB of VRAM, M from 6; on Apple's unified memory, L from 64 GiB, M from
         *   32; on the CPU, S. A GPU that measured slower than [SLOW_STEPS] stays S.
         * - **batch**: as large as the tier's memory allows, smaller for larger tiers.
         * - **workers**: the engine's, one per core less one, and on the CPU half of what is left (the trainer needs the rest).
         */
        fun of(p: HardwareProbe, cores: Int = p.cpuCores): HardwarePlan {
            val mem = p.memoryBytes
            val tier = when {
                !p.gpu -> ModelTier.S
                p.stepsPerSecond in 0.0..SLOW_STEPS && p.stepsPerSecond > 0.0 -> ModelTier.S
                p.device == "mps" -> when {
                    mem >= 64 * GIB -> ModelTier.L
                    mem >= 32 * GIB -> ModelTier.M
                    else -> ModelTier.S
                }
                mem >= 12 * GIB -> ModelTier.L
                mem >= 6 * GIB -> ModelTier.M
                else -> ModelTier.S
            }
            val batch = when (tier) {
                ModelTier.S -> if (p.gpu) 256 else 64
                ModelTier.M -> 128
                ModelTier.L -> if (mem >= 20 * GIB) 128 else 64
            }
            val spare = (cores - 1).coerceAtLeast(1)
            val workers = if (p.gpu) spare else (spare / 2).coerceAtLeast(1)
            val mixed = p.device == "cuda" || p.device == "rocm"
            val where = if (p.gpu) "${p.deviceName.ifBlank { p.device.uppercase() }}, ${mem / GIB} GiB" else "the CPU (${p.cpuCores} cores)"
            return HardwarePlan(tier, batch, workers, 30, mixed, "Tier ${tier.name} on $where: the engine maps on $workers workers.")
        }

        /** A phone never trains: it runs the desk's weights, quantised to 8 bits (M.md §4.4). */
        const val PHONE_INT8 = true
    }
}
