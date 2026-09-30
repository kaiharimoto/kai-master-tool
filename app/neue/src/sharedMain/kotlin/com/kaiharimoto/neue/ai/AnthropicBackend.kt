package com.kaiharimoto.neue.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.WebFetchTool20250910
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.wire.AnthropicModels
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration

/**
 * Claude through Anthropic's API, with the official Java SDK (`anthropic-java`),
 * on the desk and on the tablet alike.
 *
 * - **Streamed**, so text arrives as it is written and a long answer never times out;
 *   `MessageAccumulator` puts the final message together.
 * - **Cached**: the system prompt carries a cache breakpoint and the request asks for
 *   automatic caching of the conversation, which only grows at the end.
 * - **Replayed exactly**: each assistant turn keeps its content blocks — thinking
 *   blocks and their signatures included — as the SDK's own JSON ([Part.Opaque]), and
 *   is sent back byte for byte; the newest models refuse edited thinking.
 * - **Per model** ([AnthropicModels]): adaptive thinking and `effort` where the model
 *   takes them, and the server-side refusal fallback where Anthropic offers it.
 *
 * Tool inputs are not streamed eagerly: they are small, nothing draws them as they
 * arrive, and the accumulator parses each whole — the app checks every input
 * against its schema before it runs anyway (`ToolArgs.problem`).
 */
class AnthropicBackend(
    apiKey: String,
    baseUrl: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ModelBackend {
    override val runsOwnLoop = false

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (!baseUrl.isNullOrBlank()) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(15))
        .maxRetries(2)
        .build()

    /** Only for reading JSON text into a tree; the SDK's own mapper turns trees into its types ([JsonValue.convert]). */
    private val reader = com.fasterxml.jackson.databind.ObjectMapper()
    private val json = Json { ignoreUnknownKeys = true }

    /** JSON text as an SDK type, through the SDK's own mapper. */
    private fun <T : Any> sdk(text: String, type: Class<T>): T =
        JsonValue.fromJsonNode(reader.readTree(text)).convert(type) ?: error("Not a ${type.simpleName}: $text")

    /** An SDK value as JSON text. */
    internal fun text(value: Any): String = JsonValue.from(value).convert(com.fasterxml.jackson.databind.JsonNode::class.java)?.toString() ?: "null"

    override fun turn(request: TurnRequest): Flow<BackendEvent> = flow {
        val model = request.model.ifBlank { AnthropicModels.DEFAULT }
        val params = params(request, model)
        try {
            val response = client.messages().createStreaming(params)
            // Stop pressed: closing the stream is what ends the blocking read below.
            val handle = currentCoroutineContext().job.invokeOnCompletion { runCatching { response.close() } }
            val accumulator = MessageAccumulator.create()
            try {
                val events = response.stream().iterator()
                // Text blocks are paragraphs of their own once committed (ChatTurn.text), so
                // they are while streaming too, or the words jump when the turn lands (1.0.46).
                var lastBlock = -1L
                val thought = StringBuilder()
                while (events.hasNext()) {
                    val event = events.next()
                    accumulator.accumulate(event)
                    val delta = event.contentBlockDelta()
                    // Its thinking, summarised, as it thinks (1.0.47): for the person to follow along.
                    delta.flatMap { it.delta().thinking() }.ifPresent { t -> t.thinking().takeIf { it.isNotEmpty() }?.let { thought.append(it) } }
                    if (thought.isNotEmpty()) {
                        emit(BackendEvent.ReasoningDelta(thought.toString()))
                        thought.clear()
                    }
                    val words = delta.flatMap { it.delta().text() }
                    if (words.isPresent) {
                        val block = delta.get().index()
                        if (lastBlock >= 0 && block != lastBlock) emit(BackendEvent.TextDelta("\n\n"))
                        lastBlock = block
                        emit(BackendEvent.TextDelta(words.get().text()))
                    }
                }
            } finally {
                handle.dispose()
                runCatching { response.close() }
            }
            val message = accumulator.message()
            val parts = mutableListOf<Part>()
            message.content().forEach { block ->
                block.text().ifPresent { parts += Part.Text(it.text()) }
                // Its own web search or fetch, run on Anthropic's side: a line in the chat (1.0.47).
                if (block.serverToolUse().isPresent) {
                    val use = block.serverToolUse().get()
                    val input = runCatching { json.parseToJsonElement(text(use._input())) as? JsonObject }.getOrNull()
                    val what = input?.get("query")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        ?: input?.get("url")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    emit(BackendEvent.ToolSeen(use.name().asString(), if (what != null) "“$what”" else ""))
                }
                block.toolUse().ifPresent { use ->
                    val input = runCatching { json.parseToJsonElement(text(use._input())) as? JsonObject }.getOrNull()
                    parts += Part.ToolUse(use.id(), use.name(), input ?: JsonObject(emptyMap()))
                }
            }
            // The whole content, thinking and all, exactly as it came: what the next request sends back.
            parts += Part.Opaque(PROVIDER, text(message.content().map { it.toParam() }))
            val reason = message.stopReason().map { it.asString() }.orElse("end_turn")
            if (reason == "model_context_window_exceeded" && parts.none { it is Part.Text || it is Part.ToolUse }) {
                emit(BackendEvent.Failed("The conversation is over the model's context window."))
                return@flow
            }
            val stop = when (reason) {
                "tool_use" -> StopReason.TOOL_USE
                "pause_turn" -> StopReason.PAUSED
                "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
                "refusal" -> StopReason.REFUSAL
                else -> StopReason.END
            }
            if (stop == StopReason.REFUSAL && parts.none { it is Part.Text }) {
                parts.add(0, Part.Text("I can't help with that one."))
            }
            val u = message.usage()
            emit(
                BackendEvent.Finished(
                    stop,
                    ChatTurn(Role.ASSISTANT, parts, now()),
                    Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L)),
                ),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: UnauthorizedException) {
            emit(BackendEvent.Failed("Anthropic did not accept the key: ${e.message}", auth = true))
        } catch (e: PermissionDeniedException) {
            emit(BackendEvent.Failed("The key cannot use $model: ${e.message}", auth = true))
        } catch (e: RateLimitException) {
            emit(BackendEvent.Failed("Anthropic is rate-limiting this key; try again in a moment.", retryable = true))
        } catch (e: AnthropicServiceException) {
            emit(BackendEvent.Failed("Anthropic answered ${e.statusCode()}: ${e.message}", retryable = e.statusCode() >= 500))
        } catch (t: Throwable) {
            emit(BackendEvent.Failed("Could not reach Anthropic: ${t.message ?: t::class.simpleName}", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    internal fun params(request: TurnRequest, model: String): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(AnthropicModels.maxTokens(model))
            .systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(request.system).cacheControl(CacheControlEphemeral.builder().build()).build()),
            )
            .messages(messages(request.history))
            // Automatic caching of the conversation: it only ever grows at the end.
            .cacheControl(CacheControlEphemeral.builder().build())
        // web_search and web_fetch are Anthropic's own tools, run on its side, when the model has them (1.0.47).
        request.tools.forEach { spec ->
            when (spec.name) {
                "web_search" -> if (AnthropicModels.adaptive(model)) {
                    b.addTool(WebSearchTool20260209.builder().maxUses(5).build())
                } else {
                    b.addTool(WebSearchTool20250305.builder().maxUses(5).build())
                }
                "web_fetch" -> if (AnthropicModels.adaptive(model)) {
                    b.addTool(WebFetchTool20260209.builder().maxUses(5).build())
                } else {
                    b.addTool(WebFetchTool20250910.builder().maxUses(5).build())
                }
                else -> b.addTool(tool(spec))
            }
        }
        if (AnthropicModels.adaptive(model)) {
            // Summarised thinking, streamed: the person can read how it got there (1.0.47).
            b.thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
        }
        AnthropicModels.effort(model, request.effort)?.let { b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(it)).build()) }
        if (AnthropicModels.fallbacks(model)) {
            b.putAdditionalHeader("anthropic-beta", AnthropicModels.FALLBACK_BETA)
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b.build()
    }

    private fun tool(spec: ToolSpec): Tool = Tool.builder()
        .name(spec.name)
        .description(spec.description)
        .inputSchema(sdk(spec.schema.toString(), Tool.InputSchema::class.java))
        .build()

    /** The history as Anthropic messages; consecutive turns of one role are merged, as the API wants them alternating. */
    private fun messages(history: List<ChatTurn>): List<MessageParam> {
        val out = mutableListOf<Pair<Role, MutableList<ContentBlockParam>>>()
        history.forEach { turn ->
            val blocks = blocks(turn)
            if (blocks.isEmpty()) return@forEach
            if (out.lastOrNull()?.first == turn.role) out.last().second += blocks else out += turn.role to blocks.toMutableList()
        }
        return out.map { (role, blocks) ->
            MessageParam.builder()
                .role(if (role == Role.USER) MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build()
        }
    }

    internal fun blocks(turn: ChatTurn): List<ContentBlockParam> {
        if (turn.role == Role.ASSISTANT) {
            turn.parts.filterIsInstance<Part.Opaque>().firstOrNull { it.provider == PROVIDER }?.let { opaque ->
                runCatching {
                    val node = reader.readTree(opaque.json)
                    return node.map { JsonValue.fromJsonNode(it).convert(ContentBlockParam::class.java)!! }
                }
            }
        }
        return turn.parts.mapNotNull { part ->
            when (part) {
                is Part.Text -> part.text.takeIf { it.isNotBlank() }?.let { ContentBlockParam.ofText(TextBlockParam.builder().text(it).build()) }
                is Part.Context -> ContentBlockParam.ofText(TextBlockParam.builder().text(OpenAiWire.contextBlock(part.text)).build())
                is Part.ToolUse -> ContentBlockParam.ofToolUse(
                    com.anthropic.models.messages.ToolUseBlockParam.builder()
                        .id(part.id)
                        .name(part.name)
                        .input(sdk(part.input.toString(), com.anthropic.models.messages.ToolUseBlockParam.Input::class.java))
                        .build(),
                )
                is Part.ToolResult -> ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder().toolUseId(part.id).content(part.content).isError(part.isError).build(),
                )
                is Part.Opaque, is Part.Activity, is Part.Reasoning -> null
            }
        }
    }

    /** The models this key can use, newest first as Anthropic lists them. */
    suspend fun models(): Result<List<String>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { client.models().list().autoPager().map { it.id() }.toList() }
    }

    fun close() = runCatching { client.close() }

    companion object {
        const val PROVIDER = "anthropic"
    }
}
