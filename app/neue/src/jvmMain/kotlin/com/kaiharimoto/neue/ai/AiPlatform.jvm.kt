package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.mcp.McpReply
import com.kaiharimoto.mastertool.core.ai.mcp.McpServerCore
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.platform.Platform
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

actual object SecretStore {
    private val file get() = File(Platform.dataDir, "ai/credentials.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun all(): Map<String, String> = runCatching {
        json.parseToJsonElement(file.readText()).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun write(values: Map<String, String>) {
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, ".credentials.tmp")
        temp.writeText(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())
        ownerOnly(temp)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        ownerOnly(file)
    }

    /** Readable and writable by its owner alone, where the file system can say so. */
    private fun ownerOnly(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }.onFailure {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
        }
    }

    actual fun get(key: String): String? = all()[key]

    actual fun put(key: String, value: String) = write(all() + (key to value))

    actual fun remove(key: String) = write(all() - key)
}

actual object AiDesk {
    actual val canRunCli: Boolean = true

    private val windows = Platform.os == DesktopOs.WINDOWS

    actual fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /**
     * The PATH a terminal would have. An app opened from the Finder or a desktop menu is
     * handed a bare PATH, without Homebrew's, npm's or nvm's folders — where `claude`
     * and `codex` (and the `node` they run on) live — so it is read once from the login
     * shell, as editors do.
     */
    private val loginPath: String by lazy {
        val own = System.getenv("PATH").orEmpty()
        if (windows) return@lazy own
        val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/bash"
        val read = runCatching {
            val p = ProcessBuilder(shell, "-ilc", "echo __PATH__\$PATH").redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            out.lineSequence().firstOrNull { it.startsWith("__PATH__") }?.removePrefix("__PATH__")?.trim()
        }.getOrNull()
        listOfNotNull(read, own).joinToString(File.pathSeparator).split(File.pathSeparator).filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
    }

    private val home = System.getProperty("user.home").orEmpty()

    /** Where the installers put the CLIs, when the PATH does not say. */
    private fun usualFolders(): List<String> = buildList {
        add("$home/.local/bin")
        add("$home/.claude/local")
        add("$home/.npm-global/bin")
        add("$home/.bun/bin")
        add("$home/.volta/bin")
        add("/opt/homebrew/bin")
        add("/usr/local/bin")
        add("/usr/bin")
        if (windows) {
            System.getenv("APPDATA")?.let { add("$it\\npm") }
            System.getenv("LOCALAPPDATA")?.let { add("$it\\Programs\\claude"); add("$it\\Microsoft\\WinGet\\Links") }
            add("$home\\.local\\bin")
        }
        File("$home/.nvm/versions/node").listFiles()?.sortedDescending()?.forEach { add("${it.path}/bin") }
    }

    private fun candidates(program: String): List<String> =
        if (windows) listOf("$program.exe", "$program.cmd", "$program.bat", program) else listOf(program)

    actual suspend fun which(program: String, hint: String?): String? = withContext(Dispatchers.IO) {
        hint?.takeIf { File(it).canExecute() }?.let { return@withContext it }
        val folders = loginPath.split(File.pathSeparator) + usualFolders()
        for (folder in folders.distinct()) {
            for (name in candidates(program)) {
                val f = File(folder, name)
                if (f.isFile && f.canExecute()) return@withContext f.absolutePath
            }
        }
        null
    }

    private fun builder(args: List<String>, workDir: File?, env: Map<String, String>): ProcessBuilder {
        val pb = ProcessBuilder(args)
        workDir?.let { it.mkdirs(); pb.directory(it) }
        val e = pb.environment()
        // The program's own folder first, so a `node` beside an npm-installed CLI is found.
        val programDir = File(args.first()).parentFile?.absolutePath
        e["PATH"] = listOfNotNull(programDir, loginPath).joinToString(File.pathSeparator)
        e.putAll(env)
        return pb
    }

    actual suspend fun run(args: List<String>, stdin: String?, workDir: File?, env: Map<String, String>, timeoutMs: Long): ProcessResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = builder(args, workDir, env).start()
                p.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
                val out = StringBuilder()
                val err = StringBuilder()
                val readers = listOf(
                    Thread { out.append(p.inputStream.bufferedReader().readText()) },
                    Thread { err.append(p.errorStream.bufferedReader().readText()) },
                ).onEach { it.isDaemon = true; it.start() }
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.descendants().forEach { it.destroyForcibly() }
                    p.destroyForcibly()
                    return@runCatching ProcessResult(-1, out.toString(), "Timed out after ${timeoutMs / 1000} s")
                }
                readers.forEach { it.join(2000) }
                ProcessResult(p.exitValue(), out.toString(), err.toString())
            }.getOrElse { ProcessResult(-1, "", it.message ?: it::class.simpleName.orEmpty()) }
        }

    actual fun lines(args: List<String>, stdin: String?, workDir: File?, env: Map<String, String>): Flow<ProcessLine> = callbackFlow {
        val p = try {
            builder(args, workDir, env).start()
        } catch (t: Throwable) {
            trySend(ProcessLine.Exit(-1, t.message ?: "Could not start ${args.first()}"))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val errTail = ArrayDeque<String>()
        val errReader = Thread {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(errTail) {
                        errTail.addLast(line)
                        while (errTail.size > 20) errTail.removeFirst()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val outReader = Thread {
            runCatching {
                p.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
                // Every line, in order, never dropped: a CLI streams one line per delta, and a
                // full buffer that let a line go was the "bits of typos" of 1.0.43 (1.0.46).
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { trySendBlocking(ProcessLine.Out(it)) }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            errReader.join(2000)
            val tail = synchronized(errTail) { errTail.joinToString("\n") }
            trySendBlocking(ProcessLine.Exit(code, tail))
            close()
        }.apply { isDaemon = true; start() }
        awaitClose {
            if (p.isAlive) {
                p.descendants().forEach { it.destroy() }
                p.destroy()
            }
            outReader.interrupt()
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    actual fun openTerminal(command: String): Boolean = runCatching {
        when (Platform.os) {
            DesktopOs.WINDOWS -> ProcessBuilder("cmd", "/c", "start", "\"Neue Master Tool\"", "cmd", "/k", command).start()
            DesktopOs.MAC -> {
                val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
                ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to do script \"$escaped\"", "-e", "tell application \"Terminal\" to activate").start()
            }
            else -> {
                val script = "$command; exec \"\${SHELL:-bash}\""
                val terminals = listOf(
                    listOf("x-terminal-emulator", "-e", "bash", "-lc", script),
                    listOf("gnome-terminal", "--", "bash", "-lc", script),
                    listOf("konsole", "-e", "bash", "-lc", script),
                    listOf("xfce4-terminal", "-x", "bash", "-lc", script),
                    listOf("kitty", "bash", "-lc", script),
                    listOf("alacritty", "-e", "bash", "-lc", script),
                    listOf("xterm", "-e", "bash", "-lc", script),
                )
                terminals.firstNotNullOfOrNull { t -> runCatching { ProcessBuilder(t).start() }.getOrNull() } ?: error("No terminal found")
            }
        }
        true
    }.getOrDefault(false)

    actual fun startMcp(handle: suspend (authorization: String?, body: String) -> McpReply): McpHandle? = runCatching {
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        // Loopback only: nothing off this machine can reach it, and the token keeps other programs on it out.
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "neue-mcp").apply { isDaemon = true } }
        server.executor = pool
        server.createContext("/mcp") { exchange ->
            try {
                val origin = exchange.requestHeaders.getFirst("Origin")
                val reply = when {
                    // A web page's request carries an Origin: refused, the spec's guard against DNS rebinding.
                    origin != null && !origin.contains("127.0.0.1") && !origin.contains("localhost") -> McpReply(403, null)
                    !McpServerCore.authorised(exchange.requestHeaders.getFirst("Authorization"), token) -> McpReply(401, null)
                    exchange.requestMethod == "DELETE" -> McpReply(200, null)
                    exchange.requestMethod != "POST" -> McpReply(405, null)
                    else -> {
                        val body = exchange.requestBody.readBytes().decodeToString()
                        runBlocking(Dispatchers.IO) { handle(exchange.requestHeaders.getFirst("Authorization"), body) }
                    }
                }
                if (reply.status == 405) exchange.responseHeaders.add("Allow", "POST")
                val bytes = reply.body?.toByteArray()
                if (bytes != null) exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.status, bytes?.size?.toLong() ?: -1L)
                bytes?.let { exchange.responseBody.use { out -> out.write(it) } }
            } catch (t: Throwable) {
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val port = server.address.port
        object : McpHandle {
            override val url = "http://127.0.0.1:$port/mcp"
            override val token = token
            override fun stop() {
                server.stop(0)
                pool.shutdownNow()
            }
        }
    }.getOrNull()
}
