package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.mcp.McpServerCore
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.neue.ai.AiDesk
import com.kaiharimoto.neue.ai.CliBackend
import com.kaiharimoto.neue.ai.CliRun
import com.kaiharimoto.neue.ai.McpHandle
import com.kaiharimoto.neue.ai.SecretFileStore
import com.kaiharimoto.neue.ai.SecretFiles
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The red team's security findings, closed (1.0.99): the keys moved out of Ai's folder (and an older build's file
 * moved with them), the CLIs run in an empty folder of their own, and the MCP token is on disk only while a turn runs,
 * owner-only, behind an exact Origin check.
 */
class PrivateFoldersTest {
    private fun data(): File = Files.createTempDirectory("neue-private").toFile()

    private val posix = runCatching { Files.getPosixFilePermissions(File(System.getProperty("java.io.tmpdir")).toPath()) }.isSuccess

    private fun mode(f: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun aPre1099KeysFileIsMovedOutOfAisFolderAndRead() {
        val data = data()
        // Exactly what 1.0.98 wrote: one JSON object of names to keys, at <data>/ai/credentials.json, beside the memory.
        val old = File(data, "ai/credentials.json").apply { parentFile.mkdirs() }
        val written = """{"connection:c1":"sk-ant-old","video:gemini":"g-key"}"""
        old.writeText(written)
        File(data, "ai/MEMORY.md").writeText("memory")
        File(data, "ai/.credentials.tmp").writeText("half")

        val file = SecretFiles.file(data, "credentials.json")
        assertEquals(File(data, "secrets/credentials.json"), file)
        assertFalse(old.exists(), "the old file is deleted once moved")
        assertFalse(File(data, "ai/.credentials.tmp").exists())
        assertEquals(written, file.readText(), "its format and bytes are kept")
        assertEquals("memory", File(data, "ai/MEMORY.md").readText(), "nothing else of Ai's moves")

        val store = SecretFileStore(file)
        assertEquals("sk-ant-old", store.get("connection:c1"))
        assertEquals("g-key", store.get("video:gemini"))
        store.put("connection:c2", "sk-new")
        assertEquals("sk-new", SecretFileStore(SecretFiles.file(data, "credentials.json")).get("connection:c2"))
        assertFalse(File(data, "secrets/.credentials.tmp").exists())
        assertFalse(old.exists())
        if (posix) {
            assertEquals("rw-------", mode(file))
            assertEquals("rwx------", mode(File(data, "secrets")))
        }
    }

    @Test
    fun aLeftoverOldFileNeverWinsAndNeverStays() {
        val data = data()
        File(data, "secrets").mkdirs()
        File(data, "secrets/credentials.json").writeText("""{"connection:c1":"new"}""")
        File(data, "ai").mkdirs()
        File(data, "ai/credentials.json").writeText("""{"connection:c1":"stale"}""")
        val file = SecretFiles.file(data, "credentials.json")
        assertEquals("new", SecretFileStore(file).get("connection:c1"))
        assertFalse(File(data, "ai/credentials.json").exists())
        // Android's encrypted file moves the same way, bytes untouched.
        val sealed = byteArrayOf(1, 2, 3, 0, -1)
        File(data, "ai/credentials.bin").writeBytes(sealed)
        assertTrue(SecretFiles.file(data, "credentials.bin").readBytes().contentEquals(sealed))
        assertFalse(File(data, "ai/credentials.bin").exists())
        // No keys at all: nothing is made but the folder.
        val fresh = data()
        assertFalse(SecretFiles.file(fresh, "credentials.json").exists())
        assertEquals(null, SecretFileStore(SecretFiles.file(fresh, "credentials.json")).get("x"))
    }

    @Test
    fun theClisFolderIsEmptyAndOutsideAisAndTheKeys() {
        val data = data()
        File(data, "ai/run").mkdirs()
        File(data, "ai/run/mcp.json").writeText("""{"headers":{"Authorization":"Bearer old"}}""")
        File(data, "ai/run/system.md").writeText("instructions")
        File(data, "cli-run").mkdirs()
        File(data, "cli-run/mcp-crashed.json").writeText("Bearer dead")
        val run = CliRun.prepare(data)
        assertEquals(File(data, "cli-run"), run)
        assertFalse(File(data, "ai/run").exists(), "the old folder, with its token in a plain file, is gone")
        assertEquals(0, run.listFiles()!!.size, "what a crash left is swept")
        val keys = SecretFiles.file(data, "credentials.json").parentFile
        listOf(File(data, "ai"), keys).forEach { other ->
            assertFalse(run.canonicalPath.startsWith(other.canonicalPath + File.separator), "$run is inside $other")
            assertFalse(other.canonicalPath.startsWith(run.canonicalPath + File.separator), "$other is inside $run")
        }
        val f = CliRun.turnFile(run, "mcp", "json", "Bearer t")
        assertEquals("Bearer t", f.readText())
        if (posix) {
            assertEquals("rwx------", mode(run))
            assertEquals("rw-------", mode(f))
        }
    }

    @Test
    fun claudeCodeRunsInTheEmptyFolderAndItsTokenFileLastsTheTurnOnly() = runBlocking {
        if (!posix) return@runBlocking
        val data = data()
        val out = File(data, "out").apply { mkdirs() }
        val program = File(data, "claude").apply {
            writeText(
                """
                |#!/bin/sh
                |cfg=""; prev=""; resume=""
                |for a in "$@"; do
                |  [ "${'$'}prev" = "--mcp-config" ] && cfg="${'$'}a"
                |  [ "${'$'}prev" = "--resume" ] && resume="${'$'}a"
                |  prev="${'$'}a"
                |done
                |cat > "${out.path}/stdin"
                |if [ -n "${'$'}resume" ]; then
                |  echo "No conversation found with session ID: ${'$'}resume" >&2
                |  exit 1
                |fi
                |pwd > "${out.path}/cwd"
                |ls -l "${'$'}cfg" | cut -c1-10 > "${out.path}/mode"
                |cat "${'$'}cfg" > "${out.path}/cfg"
                |ls -A > "${out.path}/listing"
                |echo '{"type":"system","subtype":"init","session_id":"s-new"}'
                |echo '{"type":"result","subtype":"success","result":"ok","is_error":false}'
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
        val run = CliRun.prepare(data)
        val mcp = object : McpHandle {
            override val url = "http://127.0.0.1:1/mcp"
            override val token = "tok-in-memory"
            override fun stop() {}
        }
        val backend = CliBackend(Wire.CLAUDE_CLI, program.path, run, File(data, "ai"), mcp)
        val history = listOf(
            ChatTurn.user("Build a deck"),
            ChatTurn(Role.ASSISTANT, listOf(Part.Text("Built."))),
            ChatTurn.user("Side it"),
        )
        // A conversation begun in the old folder: Claude Code cannot find it here, so its words go to a new session.
        val events = backend.turn(TurnRequest("system words", history, AiTools.all.take(1), resume = "old-session")).toList()
        assertEquals("s-new", events.filterIsInstance<BackendEvent.Session>().single().id)
        assertIs<BackendEvent.Finished>(events.last())
        val stdin = File(out, "stdin").readText()
        assertTrue("Person: Build a deck" in stdin && "Ai: Built." in stdin && stdin.endsWith("Side it"), stdin)

        assertEquals(run.canonicalPath, File(File(out, "cwd").readText().trim()).canonicalPath)
        assertEquals("-rw-------", File(out, "mode").readText().trim())
        assertTrue("Bearer tok-in-memory" in File(out, "cfg").readText())
        // While it ran: this turn's instructions and configuration, nothing else.
        val listing = File(out, "listing").readLines().filter { it.isNotBlank() }
        assertEquals(2, listing.size, listing.toString())
        assertTrue(listing.all { it.startsWith("system-") || it.startsWith("mcp-") }, listing.toString())
        // After: nothing on disk carries the token.
        assertEquals(0, run.listFiles()!!.size, run.listFiles()!!.toList().toString())
        assertFalse(data.walkTopDown().filter { it.isFile && it.parentFile != out }.any { "tok-in-memory" in it.readText() })
    }

    @Test
    fun theMcpServerRefusesAPageWhoseOriginOnlyContainsLoopback() {
        val core = McpServerCore(tools = { AiTools.all.take(1) }, call = { Part.ToolResult(it.id, it.name, "ok") })
        val server = AiDesk.startMcp { _, body -> core.handle(body) }!!
        try {
            val http = HttpClient.newHttpClient()
            fun post(origin: String?): Int = http.send(
                HttpRequest.newBuilder(URI(server.url)).POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer ${server.token}")
                    .apply { if (origin != null) header("Origin", origin) }
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode()
            assertEquals(200, post(null))
            assertEquals(200, post("http://127.0.0.1:5173"))
            assertEquals(403, post("http://127.0.0.1.evil.com"))
            assertEquals(403, post("http://localhost.attacker"))
            assertEquals(403, post("null"))
        } finally {
            server.stop()
        }
    }
}
