package com.kaiharimoto.mastertool.core.ai.wire

import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Where an OpenAI-compatible Chat Completions endpoint is: OpenAI itself, Google's
 * Gemini (its OpenAI-compatible door), OpenRouter, Ollama, LM Studio, or any server
 * that speaks the same shape.
 */
data class OpenAiEndpoint(
    /** Up to and including `/v1` (or the provider's equivalent), no trailing slash. */
    val baseUrl: String,
    val apiKey: String? = null,
    /** Extra headers (OpenRouter's attribution). */
    val headers: Map<String, String> = emptyMap(),
    /** Sends `reasoning_effort` (OpenAI's reasoning models); others reject or ignore it. */
    val sendsEffort: Boolean = false,
    /** Asks for usage in the stream (`stream_options`); a few local servers refuse the field. */
    val streamUsage: Boolean = true,
)

/** The request body and the stream's reading, apart from HTTP, so both are tested on their own. */
object OpenAiWire {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** How the app's context is set apart from the person's words in a user message. */
    fun contextBlock(text: String) = "<app_context>\n$text\n</app_context>"

    fun body(request: TurnRequest, endpoint: OpenAiEndpoint): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        if (endpoint.streamUsage) putJsonObject("stream_options") { put("include_usage", true) }
        if (endpoint.sendsEffort && request.effort.isNotBlank()) put("reasoning_effort", request.effort)
        put("messages", messages(request.system, request.history))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.schema)
                        }
                    })
                }
            }
            put("tool_choice", "auto")
        }
    }

    fun messages(system: String, history: List<ChatTurn>): JsonArray = buildJsonArray {
        if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
        history.forEach { turn ->
            when (turn.role) {
                Role.USER -> {
                    // Tool results are messages of their own, one per call, in order.
                    turn.toolResults.forEach { r ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", r.id)
                            put("content", if (r.isError) "Error: ${r.content}" else r.content)
                        })
                    }
                    val words = turn.parts.mapNotNull {
                        when (it) {
                            is Part.Context -> contextBlock(it.text)
                            is Part.Text -> it.text
                            else -> null
                        }
                    }
                    if (words.isNotEmpty()) add(buildJsonObject { put("role", "user"); put("content", words.joinToString("\n\n")) })
                }
                Role.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    val text = turn.text
                    if (text.isNotEmpty()) put("content", text) else put("content", JsonNull)
                    val calls = turn.toolUses
                    if (calls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            calls.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", c.name)
                                        put("arguments", c.input.toString())
                                    }
                                })
                            }
                        }
                    }
                })
            }
        }
    }

    /** The model ids a `/models` answer lists. */
    fun modelIds(body: String): List<String> = runCatching {
        val root = json.parseToJsonElement(body)
        val list = (root as? JsonObject)?.get("data") ?: (root as? JsonObject)?.get("models") ?: root
        (list as? JsonArray)?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull (item as? JsonPrimitive)?.contentOrNull
            (o["id"] ?: o["name"] ?: o["model"])?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /** The error a failed answer carries, in words. */
    fun errorMessage(status: Int, body: String): String {
        val said = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val e = o["error"]
            when (e) {
                is JsonObject -> e["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> e.contentOrNull
                else -> o["message"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
        return "HTTP $status" + (said?.let { ": $it" } ?: body.take(200).takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }
}

/**
 * Reads a Chat Completions stream line by line (`data: {…}`), putting the text and
 * the tool calls — which arrive in pieces, keyed by index — back together.
 */
class OpenAiStream(private val newId: () -> String = { "call_" + (idCounter++).toString(36) }) {
    private val text = StringBuilder()
    private class Call(var id: String? = null, var name: String = "", val args: StringBuilder = StringBuilder())

    private val calls = LinkedHashMap<Int, Call>()
    private var finish: String? = null
    private var usage: Usage? = null
    private var error: String? = null

    /** What one line of the stream adds, as events to pass on (text as it comes). */
    fun line(raw: String): List<BackendEvent> {
        val line = raw.trim()
        if (!line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return emptyList()
        val chunk = runCatching { OpenAiWire.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        chunk["error"]?.let { e ->
            error = (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
            return emptyList()
        }
        (chunk["usage"] as? JsonObject)?.let { u ->
            usage = Usage(
                input = u["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                output = u["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                cacheRead = (u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
        val out = mutableListOf<BackendEvent>()
        (chunk["choices"] as? JsonArray)?.forEach { choiceEl ->
            val choice = choiceEl as? JsonObject ?: return@forEach
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finish = it }
            val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return@forEach
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                out += BackendEvent.TextDelta(it)
            }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { position, callEl ->
                val call = callEl as? JsonObject ?: return@forEachIndexed
                val index = call["index"]?.jsonPrimitive?.intOrNull ?: position
                val slot = calls.getOrPut(index) { Call() }
                (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { slot.id = it }
                (call["function"] as? JsonObject)?.let { f ->
                    (f["name"] as? JsonPrimitive)?.contentOrNull?.let { slot.name += it }
                    when (val a = f["arguments"]) {
                        is JsonPrimitive -> a.contentOrNull?.let { slot.args.append(it) }
                        is JsonObject -> slot.args.append(a.toString())
                        else -> Unit
                    }
                }
            }
        }
        return out
    }

    /** The whole answer, once the stream has ended. */
    fun finish(at: Long = 0): BackendEvent {
        error?.let { return BackendEvent.Failed(it) }
        val uses = calls.values.filter { it.name.isNotBlank() }.map { c ->
            val input = parseArgs(c.args.toString())
            Part.ToolUse(c.id ?: newId(), c.name, input)
        }
        val parts = buildList {
            if (text.isNotEmpty()) add(Part.Text(text.toString()))
            addAll(uses)
        }
        val stop = when {
            uses.isNotEmpty() -> StopReason.TOOL_USE
            finish == "length" -> StopReason.MAX_TOKENS
            finish == "content_filter" -> StopReason.REFUSAL
            else -> StopReason.END
        }
        return BackendEvent.Finished(stop, ChatTurn(Role.ASSISTANT, parts, at), usage)
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { OpenAiWire.json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            // A model that answered with its arguments cut short: the app says so rather than guess.
            ?: JsonObject(mapOf(BROKEN_ARGS to JsonPrimitive(raw.take(2000))))
    }

    companion object {
        /** The key a tool's input carries when its arguments did not arrive as JSON. */
        const val BROKEN_ARGS = "__unreadable_arguments"
        private var idCounter = 1L
    }
}

/** A backend for any OpenAI-compatible endpoint, over the app's Ktor client. */
class OpenAiChatBackend(
    private val http: HttpClient,
    private val endpoint: OpenAiEndpoint,
    private val now: () -> Long = { 0L },
) : ModelBackend {
    override val runsOwnLoop = false

    override fun turn(request: TurnRequest): Flow<BackendEvent> = flow {
        val body = OpenAiWire.body(request, endpoint).toString()
        try {
            http.preparePost("${endpoint.baseUrl.trimEnd('/')}/chat/completions") {
                timeout {
                    requestTimeoutMillis = STREAM_TIMEOUT_MS
                    socketTimeoutMillis = STREAM_TIMEOUT_MS
                }
                endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                endpoint.headers.forEach { (k, v) -> header(k, v) }
                header("Accept", "text/event-stream")
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val code = response.status.value
                    emit(BackendEvent.Failed(OpenAiWire.errorMessage(code, response.bodyAsText()), auth = code == 401 || code == 403, retryable = code == 429 || code >= 500))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                val stream = OpenAiStream()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    stream.line(line).forEach { emit(it) }
                }
                emit(stream.finish(now()))
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(BackendEvent.Failed("Could not reach ${endpoint.baseUrl}: ${t.message ?: t::class.simpleName}", retryable = true))
        }
    }

    /** The models the endpoint offers, or the reason it would not say. */
    suspend fun models(): Result<List<String>> = runCatching {
        val response = http.get("${endpoint.baseUrl.trimEnd('/')}/models") {
            endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            endpoint.headers.forEach { (k, v) -> header(k, v) }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) error(OpenAiWire.errorMessage(response.status.value, text))
        OpenAiWire.modelIds(text)
    }

    companion object {
        /** A long answer streams for minutes; nothing arriving for this long is a dead connection. */
        const val STREAM_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
