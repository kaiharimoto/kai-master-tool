package com.kaiharimoto.mastertool.core.ai.cli

import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The coding-plan CLIs, driven headlessly: Claude Code (`claude -p`) and Codex
 * (`codex exec`). Each runs its own agent loop and reaches the app's tools through
 * the app's MCP server, named [MCP_NAME]; the app reads the CLI's event stream
 * line by line. Their shell and file tools are never offered — only the app's own
 * tools and web search — because Ai's job is the app, not the computer.
 *
 * The flags are the ones the CLIs print in `--help` (claude 2.1, codex 0.159);
 * the wizard shows the version it found so a mismatch is easy to see.
 */
object CliNames {
    const val MCP_NAME = "neue"

    /** The prefix a CLI puts before the app's tools. */
    const val CLAUDE_PREFIX = "mcp__${MCP_NAME}__"
}

/** One launch of a CLI: the program's arguments, what to write to its stdin, and its environment. */
data class CliLaunch(
    val args: List<String>,
    val stdin: String,
    val env: Map<String, String> = emptyMap(),
)

object ClaudeCli {
    /**
     * `claude -p`, reading the prompt from stdin (a long prompt on the command line
     * breaks on Windows), streaming JSON with partial messages so text arrives as it
     * is written. [systemFile] holds the instructions and [mcpFile] the app's server.
     */
    fun launch(
        program: String,
        prompt: String,
        systemFile: String,
        mcpFile: String,
        model: String,
        effort: String,
        resume: String?,
    ): CliLaunch = CliLaunch(
        args = buildList {
            add(program)
            add("-p")
            addAll(listOf("--output-format", "stream-json", "--verbose", "--include-partial-messages"))
            addAll(listOf("--system-prompt-file", systemFile))
            addAll(listOf("--mcp-config", mcpFile, "--strict-mcp-config"))
            // Only web search and fetch of Claude Code's own tools; the app's through MCP.
            addAll(listOf("--tools", "WebSearch,WebFetch"))
            addAll(listOf("--allowedTools", "mcp__${CliNames.MCP_NAME},WebSearch,WebFetch"))
            // Nobody is at the terminal to answer a prompt: anything not allowed is refused.
            addAll(listOf("--permission-prompts", "none"))
            if (model.isNotBlank()) addAll(listOf("--model", model))
            if (effort.isNotBlank()) addAll(listOf("--effort", effort))
            if (!resume.isNullOrBlank()) addAll(listOf("--resume", resume))
        },
        stdin = prompt,
    )

    /** The MCP configuration file's contents: the app's server over HTTP with its token. */
    fun mcpConfig(url: String, token: String): String =
        """{"mcpServers":{"${CliNames.MCP_NAME}":{"type":"http","url":"$url","headers":{"Authorization":"Bearer $token"}}}}"""

    /** `claude auth status`, read: logged in or not. Null when the output is not what it prints. */
    fun loggedIn(statusOutput: String): Boolean? = runCatching {
        val o = Json.parseToJsonElement(statusOutput.substring(statusOutput.indexOf('{'))).jsonObject
        o["loggedIn"]?.jsonPrimitive?.booleanOrNull
    }.getOrNull()
}

/**
 * Reads Claude Code's `stream-json`: `system` (init, with the session and whether
 * the app's MCP server connected), `stream_event` (text as it is written),
 * `assistant` (whole messages, with tool calls), `user` (tool results) and
 * `result` (the end, with the cost).
 */
class ClaudeStream {
    private val json = Json { ignoreUnknownKeys = true }
    private val text = StringBuilder()
    private var streamedThisMessage = false
    private var needsBreak = false
    private var result: BackendEvent? = null

    val finished: BackendEvent? get() = result

    /** Words arriving: a new message's first words start a new paragraph. */
    private fun words(it: String, out: MutableList<BackendEvent>) {
        if (needsBreak && text.isNotEmpty()) {
            text.append("\n\n")
            out += BackendEvent.TextDelta("\n\n")
        }
        needsBreak = false
        text.append(it)
        out += BackendEvent.TextDelta(it)
    }

    fun line(raw: String): List<BackendEvent> {
        val line = raw.trim()
        if (!line.startsWith("{")) return emptyList()
        val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<BackendEvent>()
        when (o.str("type")) {
            "system" -> if (o.str("subtype") == "init") {
                o.str("session_id")?.let { out += BackendEvent.Session(it) }
                val servers = o["mcp_servers"] as? JsonArray
                val ours = servers?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("name") == CliNames.MCP_NAME }
                val status = ours?.str("status")
                if (ours != null && status != "connected") {
                    out += BackendEvent.Status("The app's tools did not connect to Claude Code ($status). Ai can talk but not act.")
                }
            }
            "stream_event" -> {
                val event = o["event"] as? JsonObject
                when (event?.str("type")) {
                    "message_start" -> streamedThisMessage = false
                    "content_block_delta" -> {
                        val delta = event["delta"] as? JsonObject
                        if (delta?.str("type") == "text_delta") {
                            delta.str("text")?.takeIf { it.isNotEmpty() }?.let {
                                streamedThisMessage = true
                                words(it, out)
                            }
                        }
                    }
                }
            }
            "assistant" -> {
                val message = o["message"] as? JsonObject
                val content = message?.get("content") as? JsonArray
                content?.mapNotNull { it as? JsonObject }?.forEach { block ->
                    when (block.str("type")) {
                        // Whole text only when it did not already stream in pieces.
                        "text" -> if (!streamedThisMessage) {
                            block.str("text")?.takeIf { it.isNotEmpty() }?.let { words(it, out) }
                        }
                        "tool_use" -> {
                            val name = block.str("name") ?: ""
                            // The app's own tools are shown by the app as it runs them.
                            if (!name.startsWith(CliNames.CLAUDE_PREFIX)) {
                                out += BackendEvent.ToolSeen(name, summarise(block["input"] as? JsonObject))
                            }
                        }
                    }
                }
                // A new message may follow; its text is a paragraph of its own.
                needsBreak = true
                streamedThisMessage = false
            }
            "result" -> {
                val isError = o["is_error"]?.jsonPrimitive?.booleanOrNull == true || o.str("subtype")?.startsWith("error") == true
                val said = o.str("result").orEmpty()
                val usage = (o["usage"] as? JsonObject)?.let { u ->
                    Usage(
                        input = u.long("input_tokens"),
                        output = u.long("output_tokens"),
                        cacheRead = u.long("cache_read_input_tokens"),
                        cacheWrite = u.long("cache_creation_input_tokens"),
                        costUsd = o["total_cost_usd"]?.jsonPrimitive?.doubleOrNull,
                    )
                }
                result = if (isError) {
                    val message = said.ifBlank { o.str("subtype") ?: "Claude Code stopped with an error." }
                    BackendEvent.Failed(message, auth = looksLikeAuth(message))
                } else {
                    BackendEvent.Finished(StopReason.END, turn = null, usage = usage, text = text.toString().trim().ifBlank { said })
                }
            }
        }
        return out
    }

    companion object {
        fun looksLikeAuth(message: String): Boolean {
            val m = message.lowercase()
            return "/login" in m || "log in" in m || "logged in" in m || "invalid api key" in m ||
                "authentication" in m || "unauthorized" in m || "oauth" in m
        }
    }
}

object CodexCli {
    /**
     * `codex exec --json`, the prompt on stdin (`-`), in a read-only sandbox in the
     * app's own folder, never asking for approval (no one is there to give it), with
     * the app's MCP server and live web search. The token is read from [TOKEN_ENV].
     */
    fun launch(
        program: String,
        prompt: String,
        workDir: String,
        mcpUrl: String,
        token: String,
        model: String,
        effort: String,
        resume: String?,
    ): CliLaunch {
        val config = listOf(
            "approval_policy=\"never\"",
            // `codex exec` has no --search; the setting is how live web search is turned on there.
            "web_search=\"live\"",
            "mcp_servers.${CliNames.MCP_NAME}.url=\"$mcpUrl\"",
            "mcp_servers.${CliNames.MCP_NAME}.bearer_token_env_var=\"$TOKEN_ENV\"",
            "mcp_servers.${CliNames.MCP_NAME}.tool_timeout_sec=900",
        ) + (if (effort.isNotBlank()) listOf("model_reasoning_effort=\"$effort\"") else emptyList())
        return CliLaunch(
            args = buildList {
                add(program)
                add("exec")
                add("--json")
                add("--skip-git-repo-check")
                addAll(listOf("-s", "read-only"))
                addAll(listOf("-C", workDir))
                config.forEach { addAll(listOf("-c", it)) }
                if (model.isNotBlank()) addAll(listOf("-m", model))
                if (!resume.isNullOrBlank()) {
                    add("resume")
                    add(resume)
                }
                add("-")
            },
            stdin = prompt,
            env = mapOf(TOKEN_ENV to token),
        )
    }

    const val TOKEN_ENV = "NEUE_MCP_TOKEN"

    /** `codex login status`, read: its exit code is the answer, and "Logged in" the words. */
    fun loggedIn(exitCode: Int, output: String): Boolean = exitCode == 0 && "not logged in" !in output.lowercase()
}

/**
 * Reads Codex's `--json` lines: `thread.started` (the session), `item.*` (messages,
 * reasoning, tool calls, errors), `turn.completed` (with usage) and `turn.failed`.
 */
class CodexStream {
    private val json = Json { ignoreUnknownKeys = true }
    private val text = StringBuilder()
    private var result: BackendEvent? = null
    private var lastError: String? = null

    val finished: BackendEvent? get() = result ?: lastError?.let { BackendEvent.Failed(it, auth = ClaudeStream.looksLikeAuth(it)) }

    fun line(raw: String): List<BackendEvent> {
        val line = raw.trim()
        if (!line.startsWith("{")) return emptyList()
        val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<BackendEvent>()
        when (o.str("type")) {
            "thread.started" -> o.str("thread_id")?.let { out += BackendEvent.Session(it) }
            "item.started", "item.updated", "item.completed" -> {
                val item = o["item"] as? JsonObject ?: return out
                val done = o.str("type") == "item.completed"
                when (item.str("type")) {
                    "agent_message" -> if (done) item.str("text")?.takeIf { it.isNotBlank() }?.let {
                        if (text.isNotEmpty()) {
                            text.append("\n\n")
                            out += BackendEvent.TextDelta("\n\n")
                        }
                        text.append(it)
                        out += BackendEvent.TextDelta(it)
                    }
                    "reasoning" -> if (!done) out += BackendEvent.Status("Thinking")
                    "web_search" -> if (!done) out += BackendEvent.ToolSeen("web_search", item.str("query") ?: "")
                    "mcp_tool_call" -> if (!done && item.str("server") != CliNames.MCP_NAME) {
                        out += BackendEvent.ToolSeen(item.str("tool") ?: "tool", item.str("server") ?: "")
                    }
                    "command_execution" -> if (!done) out += BackendEvent.ToolSeen("command", item.str("command") ?: "")
                    "error" -> item.str("message")?.let { out += BackendEvent.Status(it.take(200)) }
                }
            }
            "error" -> o.str("message")?.let { message ->
                lastError = message
                if (message.startsWith("Reconnecting")) out += BackendEvent.Status(message.substringBefore(" (").trim())
            }
            "turn.completed" -> {
                val u = o["usage"] as? JsonObject
                result = BackendEvent.Finished(
                    StopReason.END,
                    turn = null,
                    usage = u?.let { Usage(input = it.long("input_tokens"), output = it.long("output_tokens"), cacheRead = it.long("cached_input_tokens")) },
                    text = text.toString().trim(),
                )
            }
            "turn.failed" -> {
                val message = (o["error"] as? JsonObject)?.str("message") ?: lastError ?: "Codex stopped with an error."
                result = BackendEvent.Failed(message, auth = ClaudeStream.looksLikeAuth(message) || "401" in message)
            }
        }
        return out
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

/** A tool call's input in a few words, for its line in the chat. */
internal fun summarise(input: JsonObject?): String {
    if (input == null) return ""
    val first = listOf("query", "url", "prompt", "command").firstNotNullOfOrNull { (input[it] as? JsonPrimitive)?.contentOrNull }
    return (first ?: input.toString()).take(120)
}
