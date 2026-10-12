package com.kaiharimoto.neue.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.trace.TraceBook
import com.kaiharimoto.mastertool.core.ai.trace.TraceWords
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.files.PathScrub
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.NeueHolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The diagnostics file (Phase H, H.8; `docs/ROADMAP.md` F5): one zip to send when something goes wrong — the app's own log
 * and the one before it, a crash it caught, two weeks of Ai's run traces, and a page of what this is (the version, the
 * system, the memory, which kind of Ai connection). **Never** a deck, a conversation, a note, a key or a path to the
 * person's files: every file in it is one the app wrote to be read by someone else ([DiagnosticBundle.files]).
 */
class Diagnostics internal constructor(private val h: NeueHolders) {
    private val scope = Background.scope("the diagnostics file", Dispatchers.Main)

    /** What it is doing, while it does it. */
    var working by mutableStateOf<String?>(null)
        private set

    /** Builds the file and hands it to the person: saved and shown on the desk, shared on a phone or tablet. */
    fun save() {
        if (working != null) return
        working = "Gathering"
        scope.launch {
            try {
                val about = about()
                val bytes = withContext(Dispatchers.IO) { DiagnosticBundle.zip(Platform.dataDir, about) }
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
                val where = deliverFile("neue-diagnostics-$stamp.zip", "application/zip", bytes)
                h.neue.note = Note(if (where != null) "Diagnostics saved: $where" else "Diagnostics ready to share.")
            } finally {
                working = null
            }
        }
    }

    /** The page of what this is: no names, no paths, no keys. */
    private suspend fun about(): String {
        val ai = h.ai
        val connection = ai.prefs.connection
        val runs = ai.traces.read()
        return buildString {
            appendLine("Neue Master Tool ${Platform.version}")
            appendLine("System: ${Platform.systemLine()}")
            appendLine("Memory: ${DiagnosticSystem.memory()}")
            appendLine("Written: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
            appendLine("Ai: " + if (!ai.prefs.enabled) "off" else connection?.let { "${it.provider} · ${it.model.ifBlank { "its default model" }}" } ?: "no connection")
            appendLine("Ai's runs, two weeks: ${TraceWords.total(runs)}")
            TraceWords.slowest(runs).takeIf { it.isNotEmpty() }?.let { appendLine("Slowest tools: " + it.joinToString(" · ")) }
            appendLine("Ai's runs that failed: ${runs.count { it.failure.isNotEmpty() }}")
            appendLine()
            appendLine("Files: logs/ is the app's own log (a line a minute, every launch and close); crash.txt a crash it caught;")
            appendLine("traces/ one line per run of Ai — rounds, tools, time, tokens and cost, never a word of the conversation.")
            appendLine("This computer's folders read <data>, <home>, <temp> and <work>; a web address keeps only its site.")
        }
    }
}

/** What the diagnostics file holds, and the zip of it (H.8): the one list of files a person is asked to send. */
object DiagnosticBundle {
    /** The files under [dataDir] that go in, by the name they take in the zip. Only what the app wrote to be read. */
    fun files(dataDir: File): List<Pair<String, File>> = buildList {
        listOf("logs/neue.log", "logs/neue.1.log", "crash.txt", "last-crash.txt").forEach { rel ->
            File(dataDir, rel).takeIf { it.isFile }?.let { add(rel to it) }
        }
        File(dataDir, "ai/${TraceBook.FOLDER}").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jsonl") }.sortedBy { it.name }
            .forEach { add("traces/${it.name}" to it) }
    }

    /**
     * What is taken out of every file on its way in ([PathScrub]): this computer's data, temporary, home and working folders
     * — the log says where the runtime writes a native crash's report, a crash's trace names files, and those paths hold
     * the person's name more often than not (the red team, 2026-10) — each as written and as the system resolves it.
     */
    fun scrubFor(
        dataDir: File,
        home: String = System.getProperty("user.home").orEmpty(),
        temp: String = System.getProperty("java.io.tmpdir").orEmpty(),
        work: String = System.getProperty("user.dir").orEmpty(),
    ): PathScrub {
        fun both(path: String): List<String> =
            if (path.isBlank()) emptyList() else listOf(path, runCatching { File(path).canonicalPath }.getOrDefault(path)).distinct()
        return PathScrub(
            both(dataDir.path).plus(dataDir.absolutePath).distinct().map { it to "<data>" } +
                both(temp).map { it to "<temp>" } + both(home).map { it to "<home>" } + both(work).map { it to "<work>" },
        )
    }

    /** The zip: [about] as `about.txt`, then [files], each read as text with [scrub]'s folders and addresses taken out. */
    fun zip(dataDir: File, about: String, scrub: PathScrub = scrubFor(dataDir)): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("about.txt"))
            z.write(scrub.scrub(about).encodeToByteArray())
            z.closeEntry()
            files(dataDir).forEach { (name, f) ->
                z.putNextEntry(ZipEntry(name))
                z.write(scrub.scrub(f.readText()).encodeToByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
