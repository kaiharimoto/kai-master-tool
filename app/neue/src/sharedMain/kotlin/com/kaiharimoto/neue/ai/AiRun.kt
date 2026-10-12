package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.ToolTiers
import com.kaiharimoto.mastertool.core.ai.TuneIntensity
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.WorkNotice
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.vision.Vision
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.launch

// Ai's run, from a message sent to the answer's end (H.3, moved out of `AiState.kt` unchanged): the loop started on the
// conversation and its connection, and everything settled once it ends or is stopped. The state stays in `AiState`.

/**
 * Starts the answer; false when it could not (no backend), so a caller never counts a cue that went nowhere. [correction]:
 * the fact-check's correction (1.0.58) — at the duel table a word to the player, offered and answered only
 * [AiTools.DUEL_WORDS] (the red team's R11): it moves, peeks and watches nothing.
 */
internal fun AiState.respond(start: AiSession, connection: AiConnection, correction: Boolean = false): Boolean {
    val model = runCatching { backendFor(connection) }.getOrElse {
        problem = (it.message ?: "Could not connect.") to true
        return false
    }
    running = true
    runConnection = connection
    clearWritten()
    reasoning = ""
    activity = emptyList()
    val provider = Providers.byId(connection.provider)
    val intensity = TuneIntensity.of(prefs.tuneIntensity)
    // A study runs as long and thinks as hard as its intensity says; an interview needs rounds for its questions.
    val studies = start.mode == AiSession.MODE_STUDY || start.mode == AiSession.MODE_PRINCIPLES || start.mode == AiSession.MODE_REFACTOR || start.mode == AiSession.MODE_WRITE
    val effort = when {
        studies -> intensity.effort
        // At the table Ai plays at the table's strength (mastery, 1.1.43; kai: strong by default — it was low, 1.0.85).
        start.mode == AiSession.MODE_DUEL -> DuelPrefs.effort(h.neue.prefs.duel.aiStrength, provider?.efforts.orEmpty(), prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() })
        else -> prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() }
    }
    val steps = when (start.mode) {
        AiSession.MODE_STUDY, AiSession.MODE_PRINCIPLES, AiSession.MODE_REFACTOR -> intensity.steps
        // A book is written a chapter at a time, each read up on first: twice a study's rounds.
        AiSession.MODE_WRITE -> intensity.steps * 2
        AiSession.MODE_TUNE, AiSession.MODE_PROFILE -> intensity.questions * 3 + 8
        // An experiment is write, run, read, fix, show — several rounds a question (1.0.97).
        AiSession.MODE_WORLD -> AgentLoop.MAX_STEPS * 2
        // Writing effects (Phase D step 2): read, write, check and repair, a card at a time.
        AiSession.MODE_EFFECTS -> AgentLoop.MAX_STEPS * 3
        // Thinking hard at the table is reading more before moving: the playbook, the course, the cards.
        AiSession.MODE_DUEL -> DuelPrefs.steps(h.neue.prefs.duel.aiStrength, AgentLoop.MAX_STEPS)
        else -> AgentLoop.MAX_STEPS
    }
    val budget = if (model.runsOwnLoop) 0 else budgetFor(connection)
    // A correction at the table looks and talks; what it was not offered is refused if it is called anyway.
    val wordsOnly = correction && start.mode == AiSession.MODE_DUEL
    val runner = ToolRunner { call ->
        if (wordsOnly && call.name.removePrefix("mcp__neue__") !in AiTools.DUEL_WORDS) {
            Part.ToolResult(call.id, call.name, "A correction is a word to the player: it moves, peeks and watches nothing on the table.", isError = true)
        } else {
            host.run(call)
        }
    }
    // This run's own token and conversation (1.0.98, the red team): a Stop ends it at once, and its coroutine's
    // late end can then touch neither a newer run nor a newer conversation.
    val token = ++runToken
    val runSession = start.id
    ranResults = emptyMap()
    runningCall = null
    job = scope.launch {
        try {
            // Past most of the model's window, the oldest turns become a summary first (1.0.47).
            val ready = if (budget > 0) summarizedIfLong(start, model, connection, budget) else start
            // What the mode closes is never offered (`AiTools.barredIn`): the decks while Ai learns one or the person,
            // and from first principles (1.0.54) the web and the community's lists too.
            // A tier the person turned off is never offered either (H.4, `ToolTiers`).
            val offered = ToolTiers.offered(
                when (start.mode) {
                    // At the table, the table's tools only (1.0.85): the rest cost every round thousands of tokens.
                    AiSession.MODE_DUEL -> tools.filter { it.name in (if (wordsOnly) AiTools.DUEL_WORDS else AiTools.DUEL) }
                    else -> AiTools.barredIn(start.mode).let { barred -> tools.filter { it.name !in barred } }
                },
                prefs,
                connection,
            )
            // The pictures' bytes, read from their files just now: they are never kept in the conversation.
            val request = TurnRequest(ready.system, files.hydrate(ready.sent), offered, connection.model, effort, ready.resume)
            AgentLoop(model, runner, maxSteps = steps, now = System::currentTimeMillis, budget = budget).run(request).tracedFor(this@respond, start.mode.ifEmpty { "chat" }, start.id, connection).collect { event ->
                when (event) {
                    is AgentEvent.Text -> appendWritten(event.delta)
                    is AgentEvent.Reasoning -> reasoning += event.delta
                    is AgentEvent.Status -> status = event.text
                    is AgentEvent.Retrying -> reasoning = ""
                    is AgentEvent.Notice -> notice = event.text
                    is AgentEvent.Session -> session?.let { commit(it.copy(resume = event.id)) }
                    // The host reports its own line as it runs; the call and its result are kept for a Stop between calls.
                    is AgentEvent.ToolRunning -> runningCall = event.call.id
                    is AgentEvent.ToolDone -> {
                        ranResults = ranResults + (event.result.id to event.result)
                        runningCall = null
                    }
                    is AgentEvent.ToolSeen -> activity = activity + Part.Activity(event.name, event.summary)
                    is AgentEvent.Appended -> {
                        // What it thought, kept in front of what it said, for the chat alone (1.0.47).
                        val thought = reasoning.trim().takeIf { it.isNotEmpty() && event.turn.role == Role.ASSISTANT }?.let { listOf(Part.Reasoning(it)) }.orEmpty()
                        // Lines of what it did on the provider's side (a CLI's tools, Anthropic's web search).
                        val turn = if (activity.isNotEmpty() && event.turn.role == Role.ASSISTANT) {
                            event.turn.copy(parts = thought + activity + event.turn.parts)
                        } else if (thought.isNotEmpty()) {
                            event.turn.copy(parts = thought + event.turn.parts)
                        } else {
                            event.turn
                        }
                        if (turn.role == Role.ASSISTANT) {
                            clearWritten()
                            reasoning = ""
                        }
                        activity = emptyList()
                        session?.let { commit(it.copy(turns = it.turns + turn, updatedAt = System.currentTimeMillis())) }
                    }
                    // What the model read this round is how full the window is now (1.0.56).
                    is AgentEvent.Round -> {
                        // Every round's tokens, all told: what a written effect cost is read off this (Phase D step 2).
                        spent += event.usage
                        if (event.measured) session?.let { commit(it.copy(context = event.usage.read)) }
                    }
                    is AgentEvent.Done -> session?.let { commit(it.copy(usage = it.usage + event.usage)) }
                    is AgentEvent.Failed -> problem = (
                        // A model that cannot see, sent a picture, says so in its own words; say it plainly.
                        if (start.turns.any { t -> t.images.isNotEmpty() } && Vision.refused(event.message)) {
                            Vision.REFUSED
                        } else {
                            event.message
                        }
                        ) to event.auth
                }
            }
        } finally {
            finish(token, runSession)
        }
    }
    return true
}

/**
 * After an answer, or Stop: what was written is kept, and no tool call is left without its result — the real one
 * for a call that ran, "interrupted" for the one running, "not run" for the rest (1.0.98). Once per run, for that
 * run's own conversation.
 */
internal fun AiState.finish(token: Int, runSession: String) {
    if (token <= finishedToken) return
    finishedToken = token
    val s = session?.takeIf { it.id == runSession }
    if (s != null) {
        var turns = s.turns
        val partial = streaming.trim()
        if (partial.isNotEmpty()) turns = turns + ChatTurn(Role.ASSISTANT, activity + Part.Text("$partial …"), System.currentTimeMillis())
        val last = turns.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && last.toolUses.isNotEmpty()) {
            turns = turns + ChatTurn(Role.USER, last.toolUses.map { AgentLoop.unanswered(it, ranResults[it.id], it.id == runningCall, stopping) })
        }
        if (turns != s.turns) commit(s.copy(turns = turns))
    }
    ranResults = emptyMap()
    runningCall = null
    clearWritten()
    reasoning = ""
    activity = emptyList()
    // Nobody is working in Ai World now (1.0.97): its "Ai is here" comes off.
    if (h.worldStarted) h.world.leave()
    // An effects session's run is over: the cards it never reached stay asked, not started (Phase D step 2).
    if (h.effectsStarted) h.effects.ended(runSession)
    working = null
    tool = null
    running = false
    // The run is over before anything below starts another (completeTuning stops, then begins).
    job = null
    // Finish asked Ai for its report first (1.0.54): now the session ends for real.
    if (wrapping) {
        wrapping = false
        completeTuning()
    }
    // The face: sad at a Stop, happy at an answer; the answer's first line for the bar.
    if (stopping) {
        mood.stopped(clock())
    } else if (problem == null) {
        mood.done(clock())
        // Another reply finished for the person (Chessy, kai): her takeover comes after a few (TakeoverGate).
        if (s?.mode == AiSession.MODE_CHAT) {
            h.neue.update { it.copy(ai = it.ai.copy(uses = it.ai.uses + 1)) }
            // the fifth: she breaks in, a moment after the answer is there to read
            if (com.kaiharimoto.mastertool.core.ai.chessy.TakeoverGate.due(prefs, busy = takeovers.run != null)) {
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    if (takeovers.run == null && !running) takeovers.start()
                }
            }
        }
        session?.turns?.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.let {
            lastReply = AiState.firstLine(it.text)
            repliedAt = System.currentTimeMillis()
        }
    }
    // Answered out of sight, on a phone or a tablet: a notification says so (1.0.61).
    if (!stopping) {
        val said = session?.turns?.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text?.let { AiState.firstLine(it) }
        val (title, line) = WorkNotice.answered(name, said, problem?.first)
        Platform.answered(title, line)
    }
    val wasStopped = stopping
    stopping = false
    confirm?.reply(false)
    confirm = null
    question?.let { keepUnsent(it) }
    question?.reply("(no answer)")
    question = null
    status = null
    job = null
    // Talk mode (1.0.57): the answer said aloud, then the microphone again; a Stop or a problem ends it.
    if (talkMode) {
        if (problem == null && !wasStopped) answerAloud() else endTalk()
    } else if (problem == null && !wasStopped && pendingCompact == null) {
        // The fact-check pass (1.0.58): the answer's claims checked against the card text.
        checkLastAnswer()
    }
    // A summary asked for while it answered (1.0.56): made now the answer is done.
    pendingCompact?.let { focus ->
        pendingCompact = null
        if (problem == null) compactNow(focus.ifBlank { null })
    }
}
