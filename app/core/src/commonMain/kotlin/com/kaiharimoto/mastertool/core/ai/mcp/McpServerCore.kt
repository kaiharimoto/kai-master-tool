package com.kaiharimoto.mastertool.core.ai.mcp

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the HTTP side sends back: a status, and a JSON body or none. */
data class McpReply(val status: Int, val body: String?)

/**
 * The app's own MCP server, apart from its socket: the Model Context Protocol's
 * JSON-RPC over "streamable HTTP", in the plain form where every POST is answered
 * with one JSON body (no event stream, which the spec lets a server decline). It
 * is how a CLI — Claude Code, Codex — reaches the app's tools: `initialize`,
 * `tools/list`, `tools/call`, `ping`, and the notifications, which are
 * acknowledged and otherwise ignored.
 *
 * The tools and their schemas are [ToolSpec]s, the same an API is sent, and a call
 * is run by the same `ToolRunner` the app's own loop uses — so a destructive tool
 * asks the person first here too, and the CLI waits (its tool timeout is long).
 */
class McpServerCore(
    private val tools: () -> List<ToolSpec>,
    private val call: suspend (Part.ToolUse) -> Part.ToolResult,
    private val serverName: String = "neue",
    private val serverVersion: String = "1",
    private val instructions: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var nextCall = 1L

    suspend fun handle(body: String): McpReply {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return McpReply(400, error(JsonNull, PARSE_ERROR, "Not JSON").toString())
        return when (parsed) {
            is JsonArray -> {
                val answers = parsed.mapNotNull { (it as? JsonObject)?.let { m -> one(m) } }
                if (answers.isEmpty()) McpReply(202, null) else McpReply(200, JsonArray(answers).toString())
            }
            is JsonObject -> one(parsed)?.let { McpReply(200, it.toString()) } ?: McpReply(202, null)
            else -> McpReply(400, error(JsonNull, INVALID_REQUEST, "Not a JSON-RPC message").toString())
        }
    }

    /** One message's answer, or null for a notification (no id) or a response. */
    private suspend fun one(message: JsonObject): JsonObject? {
        val id = message["id"] ?: return null
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
                put("protocolVersion", if (asked != null && asked in VERSIONS) asked else VERSIONS.first())
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                putJsonObject("serverInfo") {
                    put("name", serverName)
                    put("version", serverVersion)
                }
                instructions?.let { put("instructions", it) }
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject {
                putJsonArray("tools") {
                    tools().forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("inputSchema", t.schema)
                            if (t.destructive) putJsonObject("annotations") { put("destructiveHint", true) }
                        })
                    }
                }
            })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return error(id, INVALID_PARAMS, "tools/call needs a name")
                if (tools().none { it.name == name }) return error(id, INVALID_PARAMS, "No tool named $name")
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val answer = call(Part.ToolUse("mcp-${nextCall++}", name, args))
                result(id, buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", answer.content)
                        })
                    }
                    put("isError", answer.isError)
                })
            }
            // Lists the server does not have are empty, not errors: some clients ask for all three.
            "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
            "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> error(id, METHOD_NOT_FOUND, "No method $method")
        }
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    companion object {
        /** Protocol versions answered, newest first; an unknown one is answered with the newest. */
        val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /** Whether [header] (an `Authorization` value) carries [token]. */
        fun authorised(header: String?, token: String): Boolean =
            header != null && header.removePrefix("Bearer ").trim() == token && token.isNotEmpty()
    }
}
