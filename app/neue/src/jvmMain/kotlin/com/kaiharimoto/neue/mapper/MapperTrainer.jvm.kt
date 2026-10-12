package com.kaiharimoto.neue.mapper

import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.async.thread
import com.kaiharimoto.mastertool.core.duel.mapper.train.Exported
import com.kaiharimoto.mastertool.core.duel.mapper.train.HardwareProbe
import com.kaiharimoto.mastertool.core.duel.mapper.train.ModelTier
import com.kaiharimoto.mastertool.core.duel.mapper.train.PriorNet
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainEvent
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainHelper
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainWords
import com.kaiharimoto.mastertool.core.sync.Sha256
import com.kaiharimoto.neue.platform.DiagnosticLog
import com.kaiharimoto.neue.world.Found
import com.kaiharimoto.neue.world.WorldPython
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The trainer on the desk (I.10). The person's own Python runs `tools/mapper-train` — the package copied into the app's
 * resources at build time, laid out by its hash under `<data>/effects/mapper/trainer/` and checked against the app's copy
 * each time it runs — with their environment kept (their CUDA, their site-packages), the folder on `PYTHONPATH`. Every
 * line it prints on stdout is a [TrainEvent]; what it prints on stderr is kept for the words of a failure.
 *
 * **Pausing is the stop file**: the trainer notices it before its next step, saves its checkpoint and exits cleanly, and
 * `--resume` carries on exactly there. A process is killed only as a last resort — when it has not stopped [STOP_GRACE_MS]
 * after the file, or ran past its time. As the app closes, every trainer still running is asked to stop the same way.
 */
actual object MapperTrainer {
    actual val possible: Boolean = true
    actual val why: String = ""

    /** How long a trainer may take to stop after its stop file before it is killed. */
    private const val STOP_GRACE_MS = 5 * 60_000L

    actual fun python(path: String): Found? = WorldPython.find(path)

    actual fun missing(python: Found): Result<List<String>> = runCatching {
        val out = ArrayList<String>()
        val ended = run(python, null, listOf("-c", TrainWords.CHECK), seconds = 60, onOut = { out += it })
        TrainWords.missingOf(out.joinToString("\n")) ?: error("Python could not be asked what it has: ${ended.tail()}")
    }

    actual fun install(home: File): File {
        val names = resource("files.txt").lines().map { it.trim() }.filter { it.endsWith(".py") }
        check(names.isNotEmpty()) { "The trainer is not in this build." }
        val files = names.associateWith { resource("mapper_train/$it") }
        val dir = File(home, Sha256.hex(files.entries.joinToString("\n") { (n, t) -> "$n\n$t" }).take(12))
        files.forEach { (n, t) ->
            val f = File(dir, "mapper_train/$n")
            if (!f.isFile || runCatching { f.readText() }.getOrNull() != t) {
                f.parentFile?.mkdirs()
                f.writeText(t)
            }
        }
        return dir
    }

    actual fun probe(python: Found, dir: File): Result<HardwareProbe> = runCatching {
        val out = ArrayList<String>()
        val ended = run(python, dir, listOf("-m", "mapper_train.hardware"), seconds = 180, onOut = { out += it })
        out.asReversed().firstNotNullOfOrNull { HardwareProbe.parse(it) } ?: error("The probe said nothing it could read: ${ended.tail()}")
    }

    actual fun helper(python: Found, dir: File): TrainHelper = DeskHelper(python, dir)

    actual fun load(file: File, version: Int): Result<PriorNet> = OnnxNet.load(file, version)

    private fun resource(rel: String): String =
        MapperTrainer::class.java.getResourceAsStream("/mapper-trainer/$rel")?.use { it.readBytes().decodeToString() } ?: error("The trainer is not in this build ($rel).")

    private class DeskHelper(private val python: Found, private val dir: File) : TrainHelper {
        override suspend fun train(data: String, out: String, tier: ModelTier, config: TrainConfig, resume: Boolean, stop: () -> Boolean, event: (TrainEvent) -> Unit): TrainEvent? =
            withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                var last: TrainEvent? = null
                val args = listOf("-m", "mapper_train.train", "--data", data, "--out", out, "--tier", tier.name, "--config", config.json()) +
                    if (resume) listOf("--resume") else emptyList()
                DiagnosticLog.event("Mapper trainer: training tier ${tier.name}${if (resume) ", carried on" else ""}")
                val ended = run(python, dir, args, seconds = (config.maxMinutes + 30) * 60L, stopFile = File(out, "stop"), stop = { stop() || job?.isActive == false }) { line ->
                    val e = TrainEvent.parse(line)
                    if (e is TrainEvent.Done || e is TrainEvent.Failed) last = e
                    event(e)
                }
                DiagnosticLog.event("Mapper trainer: training ended (${ended.code ?: ended.why})")
                last ?: if (ended.code != 0) TrainEvent.Failed(ended.tail()) else null
            }

        override suspend fun export(checkpoint: String, out: String): Exported = withContext(Dispatchers.IO) {
            val lines = ArrayList<String>()
            val ended = run(python, dir, listOf("-m", "mapper_train.export", "--checkpoint", checkpoint, "--out", out), seconds = 15 * 60L, onOut = { lines += it })
            val result = lines.asReversed().firstOrNull { it.trimStart().startsWith("{") }?.let(Exported::parse)
            when {
                result != null -> result
                else -> Exported(false, error = ended.tail())
            }
        }
    }

    /** How a process ended: its exit code (null when it was killed: [why]) and the last of what it printed on stderr. */
    private class Ended(val code: Int?, val why: String, val errors: List<String>) {
        fun tail(): String = (errors.takeLast(4).joinToString(" ").ifBlank { why.ifBlank { "exit code $code" } }).take(600)
    }

    /** The trainers running now, by their stop file: what the app's close asks to stop. */
    private val live: MutableSet<File> = Collections.synchronizedSet(LinkedHashSet())

    private val atExit by lazy {
        Runtime.getRuntime().addShutdownHook(Background.thread("the trainer's pause as the app closes", start = false) {
            synchronized(live) { live.toList() }.forEach { f -> runCatching { f.parentFile?.mkdirs(); f.writeText("stop") } }
        })
    }

    /**
     * [args] run by [python] in [dir] (null: wherever), each stdout line to [onOut] as it comes, for at most [seconds]. With a
     * [stopFile], [stop] turning true writes it and the process is given [STOP_GRACE_MS] to stop by itself; without one, it
     * is killed at once. Blocks until the process ends.
     */
    private fun run(
        python: Found,
        dir: File?,
        args: List<String>,
        seconds: Long,
        stopFile: File? = null,
        stop: () -> Boolean = { false },
        onOut: (String) -> Unit,
    ): Ended {
        val pb = ProcessBuilder(python.command + listOf("-u") + args)
        dir?.let { pb.directory(it) }
        val env = pb.environment()
        if (dir != null) env["PYTHONPATH"] = listOfNotNull(dir.absolutePath, env["PYTHONPATH"]?.takeIf { it.isNotBlank() }).joinToString(File.pathSeparator)
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUNBUFFERED"] = "1"
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return Ended(null, "Python could not start: ${e.message}", emptyList())
        }
        p.outputStream.close()
        stopFile?.let { atExit; live += it }
        val errors = Collections.synchronizedList(ArrayList<String>())
        val readers = listOf(
            Background.thread("the trainer's output") { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onOut) },
            Background.thread("the trainer's errors") {
                p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> errors += line; if (errors.size > 60) errors.removeAt(0) }
            },
        )
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var asked = 0L
        var why = ""
        try {
            while (p.isAlive) {
                val now = System.currentTimeMillis()
                if (asked == 0L && stop()) {
                    asked = now
                    if (stopFile != null) runCatching { stopFile.parentFile?.mkdirs(); stopFile.writeText("stop") } else why = "Stopped."
                }
                if (asked > 0L && stopFile != null && now - asked > STOP_GRACE_MS) why = "It did not stop within ${STOP_GRACE_MS / 60_000} minutes of being asked."
                if (now > deadline) why = "Stopped: past ${seconds / 60} minutes."
                if (why.isNotEmpty()) {
                    // The last resort.
                    p.descendants().forEach { it.destroyForcibly() }
                    p.destroyForcibly()
                    break
                }
                p.waitFor(200, TimeUnit.MILLISECONDS)
            }
            p.waitFor(5, TimeUnit.SECONDS)
            readers.forEach { it.join(2_000) }
        } finally {
            stopFile?.let { live -= it }
        }
        return if (why.isNotEmpty()) Ended(null, why, errors.toList()) else Ended(p.exitValue(), "", errors.toList())
    }
}
