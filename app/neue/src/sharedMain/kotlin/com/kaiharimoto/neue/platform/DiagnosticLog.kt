package com.kaiharimoto.neue.platform

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * What the app was doing, written down as it goes (1.1.60, kai: "I left the study on and when I came back the program was
 * closed" — no `crash.txt`, no `hs_err`): `<data>/logs/neue.log`, a line a moment. A crash the app catches writes
 * `crash.txt` as before; this is for the end it cannot catch — the system ending the process for memory, a restart for
 * updates, a crash inside native code. Each launch says where such a crash's own log would go, every minute says how
 * much memory is in use and what is going on ([note] — a course study's step, a video being transcribed), and a clean
 * close says so. A log that stops with no "closed" after it was ended from outside; its last minutes say what was
 * running and how much memory it had.
 *
 * Plain text, the app's own words, never a deck, a key or anything a page said: it is written to be sent.
 */
object DiagnosticLog {
    /** The log, beside `crash.txt`; the one before it is kept as `neue.1.log`. */
    val file: File get() = File(Platform.dataDir, "logs/neue.log")

    /** A file past this is put aside for a new one. */
    private const val LIMIT = 2L * 1024 * 1024

    /** How often the memory and what is going on are written down. */
    private const val EVERY_MS = 60_000L

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val doing = ConcurrentHashMap<String, String>()
    @Volatile private var begun = false

    /**
     * The app started: where things are, then a line a minute until it ends, and "closed" when it ends as a program
     * should (a window closed, a quit, an update, the system asking it to stop).
     */
    @Synchronized
    fun started() {
        if (begun) return
        begun = true
        val tmp = System.getProperty("java.io.tmpdir").orEmpty()
        val cwd = System.getProperty("user.dir").orEmpty()
        write("started · ${Platform.systemLine()}${DiagnosticSystem.process()} · memory ${DiagnosticSystem.memory()}")
        // Where the Java runtime writes its own report of a crash inside native code (an hs_err_pid file).
        write("a native crash's report (hs_err_pid<number>.log) goes to $cwd, else $tmp")
        DiagnosticSystem.onExit { write("closed · memory ${DiagnosticSystem.memory()}") }
        thread(isDaemon = true, name = "neue-diagnostics") {
            while (true) {
                Thread.sleep(EVERY_MS)
                val now = doing.entries.sortedBy { it.key }.joinToString(" · ") { (k, v) -> "$k: $v" }
                write("memory ${DiagnosticSystem.memory()}" + if (now.isNotBlank()) " · $now" else "")
            }
        }
    }

    /** What [what] is doing now, in the minute's line; null when it is done. */
    fun note(what: String, line: String?) {
        if (line.isNullOrBlank()) doing.remove(what) else doing[what] = line.replace('\n', ' ').take(200)
    }

    /** One thing that happened, now. */
    fun event(text: String) = write(text.replace('\n', ' ').take(600))

    @Synchronized
    private fun write(line: String) {
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            if (f.length() > LIMIT) {
                val old = File(f.parentFile, "neue.1.log")
                old.delete()
                f.renameTo(old)
            }
            f.appendText("${LocalDateTime.now().format(stamp)}  $line\n")
        }
    }
}

/** What only the platform can say for [DiagnosticLog]: its memory, the process, and a word when the program ends. */
internal expect object DiagnosticSystem {
    /** The heap in use of its most, and the computer's free memory where the system says. */
    fun memory(): String

    /** " · process N", or nothing. */
    fun process(): String

    /** [block] run as the program ends as a program should. */
    fun onExit(block: () -> Unit)
}
