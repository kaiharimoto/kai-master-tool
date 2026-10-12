package com.kaiharimoto.neue.mapper

import com.kaiharimoto.mastertool.core.duel.mapper.train.HardwareProbe
import com.kaiharimoto.mastertool.core.duel.mapper.train.PriorNet
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainHelper
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainStore
import com.kaiharimoto.neue.world.Found
import java.io.File

/**
 * Gameplay Mapper's trainer (Phase I, I.10; M.md §4.4): on the desk, the person's own Python running `tools/mapper-train`
 * (shipped inside the app and laid out under `<data>/effects/mapper/trainer/<hash>/`) and ONNX Runtime running what it
 * trains as the search's move prior. **The desk's alone**: a phone or tablet never trains, and in this release runs no
 * network either — ONNX Runtime for Android is nearly the APK's size again — so its maps keep the hand-written order, and
 * the seam says so in words.
 */
expect object MapperTrainer {
    /** Whether this device trains. */
    val possible: Boolean

    /** Why not, in words, when it does not. */
    val why: String

    /** The Python at [path] (blank: on the PATH), with its version; null with none. */
    fun python(path: String): Found?

    /** The modules the trainer needs that [python] lacks, or why it could not be asked. */
    fun missing(python: Found): Result<List<String>>

    /** The trainer's files laid out under [home] (by their hash, written again when they differ): the folder it runs from. */
    fun install(home: File): File

    /** `python -m mapper_train.hardware`: what this machine trains on, measured. */
    fun probe(python: Found, dir: File): Result<HardwareProbe>

    /** The trainer's process for a run: training and export, each a process of [python] in [dir]. */
    fun helper(python: Found, dir: File): TrainHelper

    /** A network file loaded into ONNX Runtime as [version], or why not. */
    fun load(file: File, version: Int): Result<PriorNet>
}

/** A deck's `train/` folder on disk: every file written whole, through a file beside it. */
class FileTrainStore(private val dir: File) : TrainStore {
    override fun read(rel: String): String? = File(dir, rel).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    override fun write(rel: String, text: String) {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    override fun delete(rel: String) {
        File(dir, rel).deleteRecursively()
    }

    override fun list(rel: String): List<String> = File(dir, rel).list()?.filterNot { it.startsWith(".") }?.sorted().orEmpty()

    override fun path(rel: String): String = File(dir, rel).absolutePath
}
