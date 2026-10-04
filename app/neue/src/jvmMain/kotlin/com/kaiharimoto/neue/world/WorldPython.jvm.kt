package com.kaiharimoto.neue.world

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Python on the desk (1.0.97). It runs with the person's own permissions — no sandbox can be promised for a
 * process — which is why it is off until they allow it. What the World does do:
 * - `-I`, isolated: no user site-packages, no PYTHON* variables, the script's folder not on the path ahead of the
 *   standard library;
 * - a stripped environment (the PATH and what Windows needs to start a process; HOME is the world's own folder);
 * - the world's folder as the working directory;
 * - a time limit, after which the process and every process it started are killed;
 * - the output read line by line and capped by the caller.
 */
actual object WorldPython {
    actual val possible: Boolean = true

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    actual fun find(path: String): Found? {
        val candidates = if (path.isNotBlank()) {
            listOf(listOf(path.trim()))
        } else if (windows) {
            listOf(listOf("py", "-3"), listOf("python"), listOf("python3"))
        } else {
            listOf(listOf("python3"), listOf("python"))
        }
        for (c in candidates) {
            val version = runCatching {
                val p = ProcessBuilder(c + "--version").redirectErrorStream(true).start()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    null
                } else if (p.exitValue() != 0) {
                    null
                } else {
                    p.inputStream.bufferedReader().readText().trim()
                }
            }.getOrNull()
            if (version != null && version.startsWith("Python 3")) return Found(c, version)
        }
        return null
    }

    actual fun run(python: Found, dir: File, args: List<String>, seconds: Int, onLine: (String, Boolean) -> Unit, stop: () -> Boolean): Ended {
        val pb = ProcessBuilder(python.command + listOf("-I", "-u") + args).directory(dir)
        val env = pb.environment()
        val keep = setOf("PATH", "SYSTEMROOT", "SystemRoot", "COMSPEC", "PATHEXT", "TEMP", "TMP", "WINDIR", "LANG", "LC_ALL")
        env.keys.retainAll { it in keep }
        env["HOME"] = dir.absolutePath
        env["USERPROFILE"] = dir.absolutePath
        env["PYTHONIOENCODING"] = "utf-8"
        env["MPLBACKEND"] = "Agg"
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return Ended(null, "Python could not start: ${e.message}")
        }
        p.outputStream.close()
        val readers = listOf(p.inputStream to false, p.errorStream to true).map { (stream, err) ->
            thread(isDaemon = true, name = "world-py-${if (err) "err" else "out"}") {
                runCatching { stream.bufferedReader(Charsets.UTF_8).forEachLine { onLine(it, err) } }
            }
        }
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var why = ""
        while (p.isAlive) {
            when {
                stop() -> why = "Stopped."
                System.currentTimeMillis() > deadline -> why = "Stopped: past $seconds seconds."
            }
            if (why.isNotEmpty()) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
                break
            }
            p.waitFor(100, TimeUnit.MILLISECONDS)
        }
        p.waitFor(2, TimeUnit.SECONDS)
        readers.forEach { it.join(1_000) }
        return if (why.isNotEmpty()) Ended(null, why) else Ended(p.exitValue())
    }
}
