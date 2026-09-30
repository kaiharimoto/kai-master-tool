package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.mcp.McpReply
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Where Ai's keys are kept: outside the database and outside every export. On the
 * desk, a file only its owner can read in the app's data folder (as Hermes keeps
 * `~/.hermes/.env`); on Android, encrypted with a key in the Android Keystore.
 */
expect object SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** A finished command: its exit code and what it printed. */
data class ProcessResult(val exit: Int, val out: String, val err: String) {
    val ok: Boolean get() = exit == 0
}

/** A line a running command printed, or its end. */
sealed interface ProcessLine {
    data class Out(val text: String) : ProcessLine
    data class Exit(val code: Int, val errTail: String) : ProcessLine
}

/** The app's MCP server, while it runs: where the CLIs reach it, and the token they must show. */
interface McpHandle {
    val url: String
    val token: String
    fun stop()
}

/**
 * What only a desktop can do for Ai: run the coding-plan CLIs (`claude`, `codex`)
 * the person installed, open a terminal to sign them in, and serve the app's tools
 * to them over MCP on 127.0.0.1. On Android there are no CLIs to run, and each
 * answers "not here" ([canRunCli] false).
 */
expect object AiDesk {
    val canRunCli: Boolean

    /** An environment variable, where a key may already be (`ANTHROPIC_API_KEY`). */
    fun env(name: String): String?

    /** Where [program] is: [hint] if it runs, else the PATH (the login shell's too), else the usual install folders. */
    suspend fun which(program: String, hint: String? = null): String?

    /** Runs a command to its end (a version, a login status), within [timeoutMs]. */
    suspend fun run(
        args: List<String>,
        stdin: String? = null,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000,
    ): ProcessResult

    /** Runs a command, its standard output line by line; cancelling the flow ends the command. */
    fun lines(args: List<String>, stdin: String?, workDir: File?, env: Map<String, String>): Flow<ProcessLine>

    /** A terminal window running [command], for a sign-in that wants a person at the keyboard. */
    fun openTerminal(command: String): Boolean

    /** Starts the app's MCP server; [handle] answers each POST. Null where it cannot run. */
    fun startMcp(handle: suspend (authorization: String?, body: String) -> McpReply): McpHandle?
}
