package com.kaiharimoto.neue.mapper

import com.kaiharimoto.mastertool.core.duel.mapper.train.Exported
import com.kaiharimoto.mastertool.core.duel.mapper.train.HardwareProbe
import com.kaiharimoto.mastertool.core.duel.mapper.train.ModelTier
import com.kaiharimoto.mastertool.core.duel.mapper.train.PriorNet
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainEvent
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainHelper
import com.kaiharimoto.neue.world.Found
import java.io.File

/** A phone or tablet never trains, and runs no network in this release: its maps keep the hand-written order. */
actual object MapperTrainer {
    actual val possible: Boolean = false

    actual val why: String =
        "Training is the desk's alone. A phone or tablet maps in the hand-written order: ONNX Runtime for Android is nearly the app's size again, so it waits."

    actual fun python(path: String): Found? = null

    actual fun missing(python: Found): Result<List<String>> = Result.failure(IllegalStateException(why))

    actual fun install(home: File): File = home

    actual fun probe(python: Found, dir: File): Result<HardwareProbe> = Result.failure(IllegalStateException(why))

    actual fun helper(python: Found, dir: File): TrainHelper = object : TrainHelper {
        override suspend fun train(data: String, out: String, tier: ModelTier, config: TrainConfig, resume: Boolean, stop: () -> Boolean, event: (TrainEvent) -> Unit): TrainEvent =
            TrainEvent.Failed(why)

        override suspend fun export(checkpoint: String, out: String): Exported = Exported(false, error = why)
    }

    actual fun load(file: File, version: Int): Result<PriorNet> = Result.failure(IllegalStateException(why))
}
