package com.kaiharimoto.mastertool.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What one call to a model is asked. */
data class TurnRequest(
    /** The instructions, frozen for the session (persona, memory snapshot, tool map, skills index). */
    val system: String,
    /** Every turn so far, the new user turn last. */
    val history: List<ChatTurn>,
    val tools: List<ToolSpec>,
    /** The model's id, as the provider names it; empty for the provider's default. */
    val model: String = "",
    /** "low", "medium", "high"… where the provider has effort; empty for its default. */
    val effort: String = "",
    /**
     * The provider's own session to carry on (a CLI's session id), when it keeps the
     * history itself; null starts one.
     */
    val resume: String? = null,
)

/** What a backend reports while it answers. */
sealed interface BackendEvent {
    /** Words as they arrive. */
    data class TextDelta(val text: String) : BackendEvent

    /** The model's thinking as it arrives, where the provider shows it: for the chat, not the answer. */
    data class ReasoningDelta(val text: String) : BackendEvent

    /**
     * A tool the provider ran itself (a CLI's web search), or one of ours it called
     * through the app's MCP server — shown as a line in the chat, never run again here.
     */
    data class ToolSeen(val name: String, val summary: String) : BackendEvent

    /** A line of progress that is not the answer ("Reconnecting…"). */
    data class Status(val text: String) : BackendEvent

    /** The provider's session, when it keeps one (a CLI). */
    data class Session(val id: String) : BackendEvent

    /**
     * The answer is complete. [turn] is the assistant's turn to append — with its tool
     * calls when [stop] is [StopReason.TOOL_USE] — or null when the provider kept the
     * turn itself (a CLI) and only [text] is known.
     */
    data class Finished(
        val stop: StopReason,
        val turn: ChatTurn?,
        val usage: Usage? = null,
        val text: String = "",
    ) : BackendEvent

    /** It could not answer. [auth]: the login or key is the problem, which the wizard can fix. */
    data class Failed(val message: String, val auth: Boolean = false, val retryable: Boolean = false) : BackendEvent
}

/**
 * A way to reach a model: an API the app talks to itself, or a CLI that runs its
 * own loop. The app's tools are the same either way — sent as functions to an API,
 * served over MCP to a CLI.
 */
interface ModelBackend {
    /**
     * True for a CLI: it calls the tools itself (through the app's MCP server) and
     * answers with the finished turn, so [AgentLoop] does not run tools for it.
     */
    val runsOwnLoop: Boolean

    fun turn(request: TurnRequest): Flow<BackendEvent>
}

/** Runs one of the app's tools. Suspends while the person is asked to confirm. */
fun interface ToolRunner {
    suspend fun run(call: Part.ToolUse): Part.ToolResult
}

/** What the chat hears while Ai answers. */
sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Reasoning(val delta: String) : AgentEvent
    data class Status(val text: String) : AgentEvent

    /** Something the person should still read once the answer is done: a limit reached, history shortened. */
    data class Notice(val text: String) : AgentEvent
    data class Session(val id: String) : AgentEvent

    /** A turn is final and belongs in the history (Ai's, or the tool results answering it). */
    data class Appended(val turn: ChatTurn) : AgentEvent

    /** A tool is being run. */
    data class ToolRunning(val call: Part.ToolUse) : AgentEvent

    /** A tool the CLI ran or called: its line in the chat. */
    data class ToolSeen(val name: String, val summary: String) : AgentEvent

    data class Done(val stop: StopReason, val usage: Usage) : AgentEvent
    data class Failed(val message: String, val auth: Boolean) : AgentEvent
}

/**
 * The loop every agent harness is: ask the model; if it asked for tools, run them,
 * hand back every result in one turn, and ask again; stop when it answers without
 * one, or after [maxSteps] rounds. A CLI backend runs this loop inside itself, so
 * for it this is a single round.
 */
class AgentLoop(
    private val backend: ModelBackend,
    private val tools: ToolRunner,
    private val maxSteps: Int = MAX_STEPS,
    private val now: () -> Long = { 0L },
    /** Waits before trying a failed call again that the provider says may work (1.0.47). */
    private val retryDelays: List<Long> = listOf(1_000, 4_000),
    /** The model's window in tokens: past most of it, old tool results are shortened. 0: never. */
    private val budget: Int = 0,
) {
    fun run(request: TurnRequest): Flow<AgentEvent> = flow {
        var history = request.history
        var usage = Usage()
        var resume = request.resume
        var pruned = false
        for (step in 0 until maxSteps) {
            // A long run of tools grows the history fast; old results are the first to go (1.0.47).
            if (budget > 0 && !backend.runsOwnLoop && Compaction.estimate(request.system, history) > budget * 0.8) {
                val shorter = Compaction.prune(history)
                if (shorter != history) {
                    history = shorter
                    if (!pruned) emit(AgentEvent.Notice("Old tool results were shortened to fit the model's memory."))
                    pruned = true
                }
            }
            var finished: BackendEvent.Finished? = null
            var failed: BackendEvent.Failed? = null
            var attempt = 0
            var overflowRetried = false
            while (true) {
                finished = null
                failed = null
                var said = false
                backend.turn(request.copy(history = history, resume = resume)).collect { event ->
                    when (event) {
                        is BackendEvent.TextDelta -> {
                            said = true
                            emit(AgentEvent.Text(event.text))
                        }
                        is BackendEvent.ReasoningDelta -> emit(AgentEvent.Reasoning(event.text))
                        is BackendEvent.Status -> emit(AgentEvent.Status(event.text))
                        is BackendEvent.ToolSeen -> emit(AgentEvent.ToolSeen(event.name, event.summary))
                        is BackendEvent.Session -> {
                            resume = event.id
                            emit(AgentEvent.Session(event.id))
                        }
                        is BackendEvent.Finished -> finished = event
                        is BackendEvent.Failed -> failed = event
                    }
                }
                val f = failed ?: break
                // Too long: the history, shortened hard, once — and only if it really got shorter.
                if (!overflowRetried && !said && !backend.runsOwnLoop && Compaction.overflowed(f.message)) {
                    val shorter = Compaction.prune(history, keep = 2, max = 300)
                    overflowRetried = true
                    if (shorter != history) {
                        history = shorter
                        emit(AgentEvent.Notice("The conversation was too long for the model; old tool results were shortened."))
                        continue
                    }
                }
                // A busy or broken moment on the provider's side: try again, but never after words went out.
                if (f.retryable && !said && attempt < retryDelays.size) {
                    emit(AgentEvent.Status("The provider stumbled; trying again…"))
                    delay(retryDelays[attempt])
                    attempt++
                    continue
                }
                break
            }
            failed?.let {
                emit(AgentEvent.Failed(it.message, it.auth))
                return@flow
            }
            val done = finished ?: run {
                emit(AgentEvent.Failed("The model stopped without an answer.", auth = false))
                return@flow
            }
            done.usage?.let { usage += it }
            val turn = done.turn ?: done.text.takeIf { it.isNotBlank() }?.let { ChatTurn.assistant(it, now()) }
            if (turn != null) {
                history = history + turn
                emit(AgentEvent.Appended(turn))
            }
            // Paused mid-way through the provider's own tools: sent back as it is, it carries on (1.0.47).
            if (done.stop == StopReason.PAUSED && !backend.runsOwnLoop) continue
            val calls = turn?.toolUses.orEmpty()
            if (backend.runsOwnLoop || done.stop != StopReason.TOOL_USE || calls.isEmpty()) {
                emit(AgentEvent.Done(done.stop, usage))
                return@flow
            }
            // One after another, on purpose: tools change the one deck on screen, and
            // the order the model wrote them in is the order it meant.
            val results = calls.map { call ->
                emit(AgentEvent.ToolRunning(call))
                try {
                    // No one result may crowd out the conversation: head and tail kept (1.0.47).
                    tools.run(call).let { r -> if (r.content.length > Compaction.RESULT_CAP) r.copy(content = Compaction.cut(r.content, Compaction.RESULT_CAP)) else r }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Part.ToolResult(call.id, call.name, "The app failed running ${call.name}: ${t.message ?: t::class.simpleName}", isError = true)
                }
            }
            // Every result in one turn: splitting them teaches a model to stop calling tools together.
            val answer = ChatTurn(Role.USER, results, now())
            history = history + answer
            emit(AgentEvent.Appended(answer))
        }
        emit(AgentEvent.Notice("Stopped after $maxSteps rounds of tools. Say “go on” to continue."))
        emit(AgentEvent.Done(StopReason.MAX_TOKENS, usage))
    }

    companion object {
        const val MAX_STEPS = 24
    }
}
