package com.kaiharimoto.neue.mapper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetBatch
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetOut
import com.kaiharimoto.mastertool.core.duel.mapper.train.PriorNet
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A trained network in ONNX Runtime (MIT; the desk's only, I.10): the graph `mapper_train.export` wrote, run on the CPU, one
 * thread a call — the map's workers are the parallelism, each asking for its own table. What it was trained on is read
 * from the file's metadata (`vocab_hash`, `heads`, `head_mean`), and the loop refuses it with any other run
 * (`NetPrior.refusal`). ONNX Runtime has no build for every computer (none for an Intel Mac in the version shipped): there a load says
 * so, and the mapper keeps the hand-written order.
 */
internal class OnnxNet private constructor(
    private val session: OrtSession,
    override val version: Int,
    override val vocabHash: String,
    override val heads: List<String>,
    override val headMean: List<Double>,
) : PriorNet {
    override fun run(batch: NetBatch): NetOut {
        val env = OrtEnvironment.getEnvironment()
        val b = batch.size.toLong()
        val t = batch.tokenCount
        val m = batch.moveCount
        val inputs = linkedMapOf(
            "tokens" to OnnxTensor.createTensor(env, LongBuffer.wrap(batch.tokens), longArrayOf(b, t.toLong(), 5)),
            "token_mask" to OnnxTensor.createTensor(env, Array(batch.size) { i -> BooleanArray(t) { j -> batch.tokenMask[i * t + j] } }),
            "step" to OnnxTensor.createTensor(env, FloatBuffer.wrap(batch.step), longArrayOf(b)),
            "moves" to OnnxTensor.createTensor(env, LongBuffer.wrap(batch.moves), longArrayOf(b, m.toLong(), 4)),
            "move_mask" to OnnxTensor.createTensor(env, Array(batch.size) { i -> BooleanArray(m) { j -> batch.moveMask[i * m + j] } }),
        )
        try {
            session.run(inputs).use { r ->
                fun floats(name: String): FloatArray {
                    val buffer = (r.get(name).orElseThrow() as OnnxTensor).floatBuffer
                    return FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                return NetOut(floats("policy_logits"), floats("value"))
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    companion object {
        /** [file] loaded as [version], or why not (no ONNX Runtime for this computer, a file it cannot read, no metadata). */
        fun load(file: File, version: Int): Result<PriorNet> {
            val env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: LinkageError) {
                return Result.failure(IllegalStateException("ONNX Runtime does not run on this computer (${e.message}), so the mapper keeps the hand-written order."))
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("ONNX Runtime could not start: ${e.message}"))
            }
            return runCatching {
                check(file.isFile) { "Network $version's file is missing: ${file.name}." }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                val session = env.createSession(file.absolutePath, options)
                try {
                    val meta = session.metadata.customMetadata
                    val heads = meta["heads"].orEmpty().split(',').filter { it.isNotEmpty() }
                    val hash = meta["vocab_hash"].orEmpty()
                    check(hash.isNotEmpty() && heads.isNotEmpty()) { "Network $version says nothing of what it was trained on." }
                    val mean = meta["head_mean"].orEmpty().split(',').mapNotNull { it.trim().toDoubleOrNull() }
                    OnnxNet(session, version, hash, heads, mean.takeIf { it.size == heads.size } ?: heads.map { 0.0 })
                } catch (e: Exception) {
                    session.close()
                    throw e
                }
            }
        }
    }
}
