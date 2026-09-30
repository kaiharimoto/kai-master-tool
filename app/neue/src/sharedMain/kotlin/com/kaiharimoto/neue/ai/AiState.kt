package com.kaiharimoto.neue.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.mcp.McpServerCore
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.ai.skills.Skill
import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiChatBackend
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiEndpoint
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A destructive step waiting on the person: what it will do, and their answer. */
class Confirm(val title: String, val detail: String, val action: String) {
    internal val answer = CompletableDeferred<Boolean>()
    fun reply(yes: Boolean) = answer.complete(yes)
}

/** A question Ai asked (`ask_user`), waiting on a tap or a typed answer. */
class Question(val question: String, val options: List<String>, val multiple: Boolean) {
    internal val answer = CompletableDeferred<String>()
    fun reply(text: String) = answer.complete(text)
}

/**
 * The assistant, for the whole app (Ai, 1.0.43): the conversation on screen, the
 * model it is held with, the tools it acts through, and what it is waiting on. One
 * per app, not per window — like [NeueHolders]' other app-lifetime state — so a
 * window swapped for immersive mode keeps the conversation mid-sentence.
 */
class AiState(internal val h: NeueHolders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val files = AiFiles(File(Platform.dataDir, "ai"))
    internal val host = AiHost(h, this)
    private val http by lazy { HttpClientFactory.create() }

    /** The conversation on screen. */
    var session by mutableStateOf<AiSession?>(null)
        private set

    /** The answer being written, word by word. */
    var streaming by mutableStateOf("")
        private set

    /** A tool running now, in words. */
    var working by mutableStateOf<String?>(null)
        private set

    /** Lines of what a CLI did this turn (its own tools, and ours through MCP). */
    var activity by mutableStateOf<List<Part.Activity>>(emptyList())
        private set

    var running by mutableStateOf(false)
        private set

    /** What went wrong last, and whether the connection is the problem (the wizard can fix it). */
    var problem by mutableStateOf<Pair<String, Boolean>?>(null)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    var confirm by mutableStateOf<Confirm?>(null)
        private set

    var question by mutableStateOf<Question?>(null)
        private set

    /** The model's thinking as it streams (1.0.47), shown above the words it leads to. */
    var reasoning by mutableStateOf("")
        private set

    /** Ai's plan for the job in hand ([x] done, [>] doing, [ ] to do), from `todo_write` (1.0.47). */
    var todos by mutableStateOf<List<String>>(emptyList())

    /** A line that outlives the answer (a limit reached), until the next message (1.0.46). */
    var notice by mutableStateOf<String?>(null)

    /** "What can you do?" playing in the panel (1.0.46): a scripted show of Ai at work, no model called. */
    var demoOpen by mutableStateOf(false)

    /** The studio's picture of the demo: this scene, written out whole. */
    var demoStill: Int? = null

    /** The setup wizard is open (the panel shows it in place of the chat). */
    var wizardOpen by mutableStateOf(false)

    /** Where the wizard is, kept while the panel closes. */
    var wizard by mutableStateOf(WizardState(AiPrefs.DEFAULT_NAME))

    /** Opens the wizard in the panel, from its start unless it is part-way through. */
    fun openWizard() {
        if (wizard.step == com.kaiharimoto.mastertool.core.ai.providers.SetupStep.NAME) wizard.name = name
        wizardOpen = true
        historyOpen = false
        if (!prefs.panelOpen) h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
    }

    /** The memory file open in "What it knows", by its path under the folder; null when closed. */
    var memoryOpen by mutableStateOf<String?>(null)

    /** "Forget everything" waiting on its confirmation. */
    var forgetAsked by mutableStateOf(false)

    /** The list of past conversations is open. */
    var historyOpen by mutableStateOf(false)

    /** Bumped to ask the composer for the keyboard. */
    var focusTick by mutableStateOf(0)

    /** The composer's draft, kept while the panel is closed. */
    var draft by mutableStateOf("")

    val prefs: AiPrefs get() = h.neue.prefs.ai
    val name: String get() = prefs.name
    val enabled: Boolean get() = prefs.enabled
    val configured: Boolean get() = prefs.connection != null

    /** The release phase this build has shipped: which tools it offers (`AiTools.offered`). */
    val tools: List<ToolSpec> get() = AiTools.offered(PHASE)

    private var job: Job? = null
    private var backend: Pair<String, ModelBackend>? = null
    private var mcp: McpHandle? = null

    // ---- the panel ------------------------------------------------------------

    fun toggle() = setOpen(!prefs.panelOpen)

    fun setOpen(open: Boolean) {
        h.neue.update { it.copy(ai = it.ai.copy(panelOpen = open)) }
        if (open) {
            if (!configured) openWizard()
            focusTick++
        }
    }

    // ---- talking ---------------------------------------------------------------

    /** The person's message, sent; Ai answers, acting through its tools. */
    fun send(text: String) {
        val words = text.trim()
        if (words.isEmpty() || running) return
        val connection = prefs.connection ?: run {
            openWizard()
            return
        }
        problem = null
        status = null
        notice = null
        draft = ""
        val current = session?.takeIf { it.connection == connection.id } ?: begin(connection)
        val scope = host.scope()
        val scopeChanged = scope?.path != current.scopeShown
        val renamed = renamedTo?.let { listOf("The person renamed you: you are $it from now on, whatever the instructions above call you.") }.orEmpty()
        renamedTo = null
        val context = PromptBuilder.context(renamed + host.situation(), scope, scope?.let { host.notes(it) }, scopeChanged)
        val turn = ChatTurn.user(words, context, System.currentTimeMillis())
        val next = current.copy(turns = current.turns + turn, updatedAt = System.currentTimeMillis(), scopeShown = scope?.path ?: current.scopeShown).titled()
        commit(next)
        respond(next, connection)
    }

    private fun respond(start: AiSession, connection: AiConnection) {
        val model = runCatching { backendFor(connection) }.getOrElse {
            problem = (it.message ?: "Could not connect.") to true
            return
        }
        running = true
        streaming = ""
        reasoning = ""
        activity = emptyList()
        val provider = Providers.byId(connection.provider)
        val effort = prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() }
        val budget = if (model.runsOwnLoop) 0 else budgetFor(connection)
        job = scope.launch {
            try {
                // Past most of the model's window, the oldest turns become a summary first (1.0.47).
                val ready = if (budget > 0) summarizedIfLong(start, model, connection, budget) else start
                val request = TurnRequest(ready.system, ready.sent, tools, connection.model, effort, ready.resume)
                AgentLoop(model, { call -> host.run(call) }, now = System::currentTimeMillis, budget = budget).run(request).collect { event ->
                    when (event) {
                        is AgentEvent.Text -> streaming += event.delta
                        is AgentEvent.Reasoning -> reasoning += event.delta
                        is AgentEvent.Status -> status = event.text
                        is AgentEvent.Notice -> notice = event.text
                        is AgentEvent.Session -> session?.let { commit(it.copy(resume = event.id)) }
                        is AgentEvent.ToolRunning -> Unit // the host reports its own line as it runs
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
                                streaming = ""
                                reasoning = ""
                            }
                            activity = emptyList()
                            session?.let { commit(it.copy(turns = it.turns + turn, updatedAt = System.currentTimeMillis())) }
                        }
                        is AgentEvent.Done -> session?.let { commit(it.copy(usage = it.usage + event.usage)) }
                        is AgentEvent.Failed -> problem = event.message to event.auth
                    }
                }
            } finally {
                finish()
            }
        }
    }

    /** After an answer, or Stop: what was written is kept, and no tool call is left without its result. */
    private fun finish() {
        val s = session
        if (s != null) {
            var turns = s.turns
            val partial = streaming.trim()
            if (partial.isNotEmpty()) turns = turns + ChatTurn(Role.ASSISTANT, activity + Part.Text("$partial …"), System.currentTimeMillis())
            val last = turns.lastOrNull()
            if (last != null && last.role == Role.ASSISTANT && last.toolUses.isNotEmpty()) {
                turns = turns + ChatTurn(Role.USER, last.toolUses.map { Part.ToolResult(it.id, it.name, "Stopped by the person before it ran.", isError = true) })
            }
            if (turns != s.turns) commit(s.copy(turns = turns))
        }
        streaming = ""
        reasoning = ""
        activity = emptyList()
        working = null
        running = false
        confirm?.reply(false)
        confirm = null
        question?.reply("(no answer)")
        question = null
        status = null
        job = null
    }

    fun stop() {
        job?.cancel()
    }

    /** A new conversation, with the memory as it stands now. */
    fun newChat(mode: String = AiSession.MODE_CHAT) {
        stop()
        todos = emptyList()
        session?.let(::reflect)
        problem = null
        val connection = prefs.connection
        session = if (connection != null) begin(connection, mode) else null
        historyOpen = false
    }

    // ---- a helper with a fresh mind (1.0.47) --------------------------------------

    /**
     * A big reading job handed to a helper (`delegate`, after DeepSeek Harness's and Claude
     * Code's sub-agents): the same model, a fresh history holding only [task], and only the
     * tools that look — it reads twenty lists or a whole web and hands back one report, so
     * the conversation carries the report, not the reading. API connections only: a CLI
     * runs its own loop and has its own helpers.
     */
    suspend fun delegate(task: String, steps: Int): Result<String> = runCatching {
        val connection = prefs.connection ?: error("No connection is set up.")
        val model = backendFor(connection)
        if (model.runsOwnLoop) error("A helper needs an API connection; on a plan's command-line app, do the reading yourself.")
        val look = tools.filter { it.name in AiTools.readOnly }
        val system = session?.system ?: systemPrompt(connection)
        val ask = ChatTurn.user(
            "You are a helper the assistant sent to do one job and report back. Nothing you say reaches the person directly; " +
                "your final message is your report, so make it complete and plain: the facts, the numbers, the card names, the ids. " +
                "You can only look, never change anything.\n\nThe job: " + task.trim(),
        )
        var report = ""
        val runner = com.kaiharimoto.mastertool.core.ai.ToolRunner { call ->
            if (call.name.removePrefix("mcp__neue__") !in AiTools.readOnly) {
                Part.ToolResult(call.id, call.name, "A helper can only look; ${call.name} is not one of its tools.", isError = true)
            } else {
                host.run(call)
            }
        }
        AgentLoop(model, runner, maxSteps = steps, now = System::currentTimeMillis, budget = budgetFor(connection))
            .run(TurnRequest(system, listOf(ask), look, connection.model, "medium"))
            .collect { e ->
                when (e) {
                    is AgentEvent.Appended -> if (e.turn.role == Role.ASSISTANT && e.turn.text.isNotBlank()) report = e.turn.text
                    is AgentEvent.Failed -> error(e.message)
                    else -> Unit
                }
            }
        report.ifBlank { error("The helper came back without a report.") }
    }

    // ---- a long conversation (1.0.47) --------------------------------------------

    /**
     * How much a connection's model can read, in tokens, near enough: past most of it the
     * oldest turns are summarised. The CLIs keep their own histories and are never asked.
     */
    private fun budgetFor(connection: AiConnection): Int {
        val p = Providers.byId(connection.provider)
        return when {
            p == null -> 32_000
            p.kind == com.kaiharimoto.mastertool.core.ai.providers.ConnectKind.LOCAL -> 32_000
            p.id == "gemini" -> 400_000
            p.wire == Wire.ANTHROPIC -> 200_000
            else -> 128_000
        }
    }

    /**
     * [s] with its oldest turns folded into a summary the model writes, once they no longer
     * fit; the summary is kept with the conversation, so it is written once, not every time.
     */
    private suspend fun summarizedIfLong(s: AiSession, model: ModelBackend, connection: AiConnection, budget: Int): AiSession {
        if (com.kaiharimoto.mastertool.core.ai.Compaction.estimate(s.system, s.sent) <= budget * com.kaiharimoto.mastertool.core.ai.Compaction.SUMMARIZE_AT) return s
        val keep = (budget * 0.3).toInt()
        val cut = com.kaiharimoto.mastertool.core.ai.Compaction.cutAt(s.turns, keep, from = s.summarized) ?: return s
        status = "Summarising the start of this conversation to fit"
        val earlier = buildString {
            if (s.summary.isNotBlank()) appendLine("Summary so far: ${s.summary}\n")
            append(com.kaiharimoto.mastertool.core.ai.Compaction.transcript(s.turns.subList(s.summarized, cut)))
        }
        val ask = ChatTurn.user(com.kaiharimoto.mastertool.core.ai.Compaction.SUMMARY_ASK + "\n\n" + earlier)
        var summary = ""
        runCatching {
            model.turn(TurnRequest(s.system, listOf(ask), emptyList(), connection.model, "low")).collect { e ->
                if (e is com.kaiharimoto.mastertool.core.ai.BackendEvent.Finished) summary = e.turn?.text?.ifBlank { null } ?: e.text
            }
        }
        status = null
        if (summary.isBlank()) return s
        val next = s.copy(summary = summary.trim(), summarized = cut)
        commit(next)
        notice = null
        return next
    }

    // ---- learning (phase 3) ----------------------------------------------------

    /** What Fine Tuning or a reflection changed in memory, waiting on the person's Keep or Undo. */
    var review by mutableStateOf<List<com.kaiharimoto.mastertool.core.ai.memory.MemoryChange>?>(null)
        private set
    private var reviewBefore: Map<String, String?> = emptyMap()

    /** The memory files as they stand, by path: what a review compares against. */
    private fun snapshot(): Map<String, String?> {
        val paths = buildSet {
            add(MemoryKind.USER.file)
            add(MemoryKind.AGENT.file)
            files.memoryFiles().forEach { add(it.relativeTo(files.root).invariantSeparatorsPath) }
            host.scope()?.path?.let(::add)
        }
        return paths.associateWith { files.read(it) }
    }

    /** Fine Tuning (kai): an interview about how the person prepares, written to memory as it goes. */
    fun startTuning() {
        if (PHASE < 3) return
        val connection = prefs.connection ?: run {
            openWizard()
            return
        }
        stop()
        h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
        wizardOpen = false
        historyOpen = false
        tuneBefore = snapshot()
        session = begin(connection, AiSession.MODE_TUNE)
        send("Let's do Fine Tuning.")
    }

    private var tuneBefore: Map<String, String?>? = null

    val tuning: Boolean get() = session?.mode == AiSession.MODE_TUNE

    /** Fine Tuning done: what it learned, to keep or undo, then an ordinary conversation. */
    fun finishTuning() {
        stop()
        val before = tuneBefore ?: snapshot()
        tuneBefore = null
        offerReview(before)
        val connection = prefs.connection
        session = if (connection != null) begin(connection) else null
    }

    private fun offerReview(before: Map<String, String?>) {
        val changes = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.diff(before, snapshot())
        if (changes.isNotEmpty()) {
            reviewBefore = before
            review = changes
        }
    }

    fun keepReview() {
        review = null
        reviewBefore = emptyMap()
    }

    /** Everything the review lists put back as it was. */
    fun undoReview() {
        val changed = review?.map { it.path }.orEmpty()
        changed.forEach { path -> reviewBefore[path]?.let { files.write(path, it) } ?: files.delete(path) }
        review = null
        reviewBefore = emptyMap()
    }

    /**
     * After a conversation, a short pass to keep what will matter (Hermes's nudge): the
     * model reads the conversation back and writes durable facts to memory — and a
     * skill, when it worked out a procedure. Quiet: a note says how many things were
     * remembered, with Undo. Only for conversations long enough to teach something, and
     * only on an API connection (a CLI would run a whole session for it).
     */
    private fun reflect(finished: AiSession) {
        if (PHASE < 3 || finished.mode != AiSession.MODE_CHAT) return
        if (finished.unreflected < REFLECT_AFTER) return
        val connection = prefs.connection?.takeIf { it.id == finished.connection } ?: return
        val model = runCatching { backendFor(connection) }.getOrNull()?.takeIf { !it.runsOwnLoop } ?: return
        // Read once: a conversation reopened and left again is reflected on for what is new.
        files.saveSession(finished.copy(reflected = finished.turns.size))
        val transcript = finished.turns.drop(finished.reflected).filter { !it.isToolResults }.joinToString("\n") { t ->
            (if (t.role == Role.USER) "Person: " else "$name: ") + t.text.take(1200)
        }.takeLast(16_000)
        val before = snapshot()
        val allowed = tools.filter { it.name == "memory" || it.name == "skill_manage" || it.name == "memory_read" }
        val request = TurnRequest(
            finished.system,
            listOf(
                ChatTurn.user(
                    "Our conversation just ended. Here it is:\n\n$transcript\n\n" +
                        "Save to memory what will still matter next week about the person or about doing this job for them " +
                        "(memory tool; replace what changed rather than adding duplicates). If you worked out a repeatable " +
                        "procedure, write it as a skill (skill_manage). If nothing is worth keeping, do nothing. Then answer in one word: done.",
                ),
            ),
            allowed,
            connection.model,
            "low",
        )
        scope.launch {
            runCatching { AgentLoop(model, { call -> host.run(call) }, maxSteps = 6).run(request).collect { } }
            val changes = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.diff(before, snapshot())
            if (changes.isNotEmpty()) {
                val n = com.kaiharimoto.mastertool.core.ai.memory.MemoryReview.count(changes)
                h.neue.note = com.kaiharimoto.neue.Note("$name remembered $n thing${if (n == 1) "" else "s"} from that conversation", "Undo", lastsMs = 10_000) {
                    reviewBefore = before
                    review = changes
                    undoReview()
                }
            }
        }
    }

    /** The studio's pictures of Fine Tuning: a question waiting, or a review to keep. */
    fun previewTuning(asking: Question?, changes: List<com.kaiharimoto.mastertool.core.ai.memory.MemoryChange>?) {
        question = asking
        review = changes
    }

    /** A conversation put on screen as it is, unsaved: the studio's pictures of the panel. */
    fun preview(sample: AiSession) {
        session = sample
    }

    fun open(id: String) {
        if (running) return
        session?.takeIf { it.id != id }?.let(::reflect)
        files.loadSession(id)?.let {
            session = it
            problem = null
        }
        historyOpen = false
    }

    fun delete(id: String) {
        files.deleteSession(id)
        if (session?.id == id) session = null
    }

    private fun begin(connection: AiConnection, mode: String = AiSession.MODE_CHAT): AiSession {
        val now = System.currentTimeMillis()
        val s = AiSession(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            connection = connection.id,
            system = systemPrompt(connection, mode),
            mode = mode,
        )
        session = s
        return s
    }

    /** The instructions a conversation starts with, frozen for its length. */
    fun systemPrompt(connection: AiConnection?, mode: String = AiSession.MODE_CHAT): String {
        val wire = connection?.let { Providers.byId(it.provider)?.wire }
        return PromptBuilder.system(
            PromptBuilder.Setup(
                name = name,
                soul = files.soul(name),
                userMemory = files.entries(MemoryKind.USER),
                agentMemory = files.entries(MemoryKind.AGENT),
                skillsIndex = Skills.index(skills()),
                device = when {
                    h.neue.phone -> "phone"
                    h.neue.touchFirst -> "tablet"
                    else -> "desktop"
                },
                viaMcp = wire == Wire.CLAUDE_CLI || wire == Wire.CODEX_CLI,
                missing = buildList {
                    if (PHASE < 2) addAll(listOf("reading tournament results from YGOPRODeck", "building a web of decks from the meta by itself"))
                    if (PHASE < 3) add("Fine Tuning, the interview about how the person prepares")
                },
                mode = mode,
            ),
        )
    }

    fun skills(): List<Skill> = Skills.merge(BuiltInSkills.upTo(PHASE), files.ownSkills())

    private fun commit(next: AiSession) {
        session = next
        val snapshot = next
        scope.launch(Dispatchers.IO) { runCatching { files.saveSession(snapshot) } }
    }

    // ---- waiting on the person -------------------------------------------------

    suspend fun ask(c: Confirm): Boolean {
        confirm = c
        h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
        return try {
            c.answer.await()
        } finally {
            if (confirm === c) confirm = null
        }
    }

    suspend fun ask(q: Question): String {
        question = q
        h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
        return try {
            q.answer.await()
        } finally {
            if (question === q) question = null
        }
    }

    /** A tool's line while it runs. */
    internal fun working(line: String?) {
        working = line
    }

    /** A tool that ran through MCP, for the chat (the CLI's own history holds its traffic). */
    internal fun activity(line: Part.Activity) {
        if (backend?.second?.runsOwnLoop == true) activity = activity + line
    }

    // ---- connections -----------------------------------------------------------

    private fun backendFor(connection: AiConnection): ModelBackend {
        val key = "${connection.id}:${connection.model}:${connection.baseUrl}:${connection.program}"
        backend?.takeIf { it.first == key }?.let { return it.second }
        val provider = Providers.byId(connection.provider) ?: error("Unknown provider ${connection.provider}")
        val made: ModelBackend = when (provider.wire) {
            Wire.ANTHROPIC -> AnthropicBackend(secret(connection) ?: error("No key saved for ${provider.label}."), connection.baseUrl)
            Wire.OPENAI_COMPAT -> {
                val base = connection.baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl ?: error("No server address.")
                if (!Providers.plainHttpAllowed(base)) error("$base is not encrypted; use https, or a server on this machine or network.")
                OpenAiChatBackend(http, OpenAiEndpoint(base, secret(connection), provider.headers, provider.sendsEffort), System::currentTimeMillis)
            }
            Wire.CLAUDE_CLI, Wire.CODEX_CLI -> {
                if (!AiDesk.canRunCli) error("${provider.label} runs on the desktop app only.")
                val program = connection.program ?: error("Set up ${provider.label} again: the app lost where it is installed.")
                CliBackend(provider.wire, program, files.file("run"), mcpServer() ?: error("The app could not open its tools to ${provider.label}."))
            }
        }
        (backend?.second as? AnthropicBackend)?.close()
        backend = key to made
        return made
    }

    /** The app's MCP server, started on first use by a CLI. */
    private fun mcpServer(): McpHandle? {
        mcp?.let { return it }
        val core = McpServerCore(
            tools = { tools },
            call = { call -> withContext(Dispatchers.Main) { host.run(call) } },
            serverName = "neue",
            serverVersion = Platform.version,
            instructions = "The tools of Neue Master Tool, a Yu-Gi-Oh! deck builder: read and change its decks, webs, siding plans and settings.",
        )
        mcp = AiDesk.startMcp { _, body -> core.handle(body) }
        return mcp
    }

    fun secret(connection: AiConnection): String? = SecretStore.get(secretKey(connection.id))

    fun secretKey(id: String) = "connection:$id"

    /** A connection saved and made the one in use, its key (if any) in the secret store. */
    fun connect(connection: AiConnection, key: String?) {
        if (!key.isNullOrBlank()) SecretStore.put(secretKey(connection.id), key.trim())
        h.neue.update { p ->
            val others = p.ai.connections.filterNot { it.id == connection.id }
            p.copy(ai = p.ai.copy(connections = others + connection, active = connection.id))
        }
        backend = null
        wizardOpen = false
        if (session?.connection != connection.id) newChat()
    }

    fun forget(connectionId: String) {
        SecretStore.remove(secretKey(connectionId))
        h.neue.update { p -> p.copy(ai = p.ai.copy(connections = p.ai.connections.filterNot { it.id == connectionId })) }
        backend = null
    }

    fun use(connectionId: String) {
        h.neue.update { it.copy(ai = it.ai.copy(active = connectionId)) }
        backend = null
        newChat()
    }

    fun rename(to: String) {
        val old = name
        val clean = to.trim().take(AiPrefs.MAX_NAME).ifBlank { AiPrefs.DEFAULT_NAME }
        if (clean == old) return
        files.read(Persona.FILE)?.let { files.write(Persona.FILE, Persona.rename(it, old, clean)) }
        h.neue.update { it.copy(ai = it.ai.copy(name = clean)) }
        // The conversation on screen was begun under the old name, and its instructions are
        // never rewritten (the cache): its next message says the new one (1.0.46).
        if (session?.turns?.isNotEmpty() == true) renamedTo = clean
    }

    /** A rename the conversation on screen has not been told of yet. */
    private var renamedTo: String? = null

    /** Ai off: every trace gone, nothing running, nothing listening. */
    fun shutDown() {
        stop()
        mcp?.stop()
        mcp = null
        (backend?.second as? AnthropicBackend)?.close()
        backend = null
        wizardOpen = false
    }

    /**
     * A library deck copied into a web: its notes go with it into the web's file, marked
     * with its name, since from now on the web's notes are the ones read for it.
     */
    fun foldIntoWeb(deckId: String, deckName: String, webId: String) {
        val deckNotes = files.read(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(MemoryKind.DECK, deckId)) ?: return
        val deck = com.kaiharimoto.mastertool.core.ai.memory.AiMemory.parse(deckNotes)
        if (deck.entries.isEmpty()) return
        val webName = h.webs.library.byId(webId)?.name ?: "the web"
        val web = files.memory(MemoryKind.WEB, webId, webName)
        files.save(MemoryKind.WEB, webId, com.kaiharimoto.mastertool.core.ai.memory.AiMemory.fold(web, deck, deckName))
    }

    /** Memory, skills and conversations deleted; the connections stay. */
    fun forgetEverything() {
        stop()
        files.forgetEverything()
        session = null
    }

    companion object {
        /** The phase this build ships: 1, the harness; 2, the meta; 3, learning. */
        const val PHASE = 3

        /** How many messages a conversation needs before it is reflected on. */
        const val REFLECT_AFTER = 4
    }
}
