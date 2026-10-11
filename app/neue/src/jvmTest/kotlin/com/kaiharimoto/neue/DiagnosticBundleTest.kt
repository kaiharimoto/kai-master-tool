package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.trace.RunTracer
import com.kaiharimoto.mastertool.core.ai.trace.TraceBook
import com.kaiharimoto.neue.platform.DiagnosticBundle
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The diagnostics file (Phase H, H.8): the app's log, a crash and Ai's run traces — and nothing else of the data folder. */
class DiagnosticBundleTest {
    @Test
    fun itHoldsTheLogsTheCrashAndTheTracesAndNothingElse() {
        val data = Files.createTempDirectory("neue-diag").toFile()
        try {
            fun put(rel: String, text: String) = File(data, rel).apply { parentFile.mkdirs(); writeText(text) }
            put("logs/neue.log", "started · memory 200 MB\n")
            put("crash.txt", "java.lang.IllegalStateException: boom")
            put("ai/${TraceBook.FOLDER}/2026-10-11.jsonl", TraceBook.line(RunTracer("r1", "s1", "chat", "anthropic", "claude-opus-5-5", 0).finish(10, false)) + "\n")
            // What must never go: a conversation, Ai's memory, a key, a deck, a puzzle, a replay, a half-written trace.
            put("ai/sessions/s1.json", """{"turns":[{"text":"my secret plan"}]}""")
            put("ai/MEMORY.md", "The person sides 3 Droll")
            put("ai/credentials.json", "sk-ant-secret")
            put("secrets/key", "secret")
            put("duel/puzzles/p1.json", "{}")
            put("duel/replays/r1.json", "{}")
            put("ai/${TraceBook.FOLDER}/.2026-10-11.jsonl.tmp", "half")
            val zip = DiagnosticBundle.zip(data, "Neue Master Tool test")
            val names = ArrayList<String>()
            val all = StringBuilder()
            ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    names += e.name
                    all.append(z.readBytes().decodeToString())
                }
            }
            assertEquals(listOf("about.txt", "logs/neue.log", "crash.txt", "traces/2026-10-11.jsonl"), names)
            assertTrue("boom" in all)
            assertFalse("secret" in all || "Droll" in all, all.toString())
        } finally {
            data.deleteRecursively()
        }
    }
}
