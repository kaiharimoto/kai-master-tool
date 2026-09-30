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
class Question(
    val question: String,
    val options: List<String>,
    val multiple: Boolean,
    /** The cards it is about, shown as their art above it (1.0.48). */
    val cards: List<com.kaiharimoto.mastertool.core.model.Card> = emptyList(),
) {
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

    // ---- the face (1.0.52) ------------------------------------------------------

    /** The face Ai wears now, beside the chat and in the bar alike: the mood table's, ticked by [AiFaceClock]. */
    var face by mutableStateOf(com.kaiharimoto.mastertool.core.ai.avatar.Expression.IDLE)
        internal set

    internal val mood = com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker()

    /** How the face answers a hand (1.0.54): taps, petting, poking, holding. */
    internal val play = com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay()

    /** What the face said to the hand, beside it for as long as the face is worn. */
    var playLine by mutableStateOf<String?>(null)
        private set
    private var playLineUntil = 0.0

    /** The face answered a hand: worn for its moment, its line beside it. */
    fun touched(r: com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay.Reaction?) {
        r ?: return
        val now = clock()
        mood.moment(r.face, r.seconds, now)
        playLine = r.line
        playLineUntil = now + r.seconds
        face = r.face
    }

    /** The hand's line, while its moment lasts. */
    val handLine: String? get() = playLine?.takeIf { clock() < playLineUntil }

    /** The tool running now, by name, for the face: reading or working. */
    var tool by mutableStateOf<String?>(null)
        internal set

    /** The first line of the last answer, and when it came: the bar's marquee shows it for a while. */
    var lastReply by mutableStateOf<String?>(null)
        private set
    var repliedAt by mutableStateOf(0L)
        private set

    /** Whether the empty conversation has winked hello yet, this run. */
    internal var greeted = false

    /** Stop was pressed: the turn's end is a sad face, not a done one. */
    private var stopping = false

    /** The face's clock: seconds on `System.nanoTime`, the clock [NeueHolders.lastInput] keeps. */
    internal fun clock(): Double = System.nanoTime() / 1e9

    /** For the studio's pictures: a turn at work on [line], with no model behind it. */
    fun pretendWorking(line: String) {
        running = true
        working = line
    }

    /** A face Ai chose for itself (the `express` tool). */
    fun express(e: com.kaiharimoto.mastertool.core.ai.avatar.Expression, seconds: Int) = mood.express(e, seconds.toDouble(), clock())

    /** The face now, from what Ai is doing. */
    fun tickFace() {
        face = mood.at(
            com.kaiharimoto.mastertool.core.ai.avatar.AiSignals(
                running = running,
                streaming = streaming.isNotEmpty(),
                tool = tool,
                waiting = confirm != null || question != null,
                problem = problem?.first,
                drafting = draft.isNotBlank(),
                tuning = tuning,
                studying = studying,
                hearing = hearing,
                aloud = aloud,
            ),
            clock(),
            h.lastInput / 1e9,
        )
    }

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

    // ---- voice (1.0.57) ---------------------------------------------------------

    /** The microphone is open. */
    var hearing by mutableStateOf(false)
        private set

    /** What was said is being written out. */
    var transcribing by mutableStateOf(false)
        private set

    /** How loud the microphone is now, 0–1, for the composer's meter. */
    var voiceLevel by mutableStateOf(0f)
        private set

    /** Talk mode: listen, answer aloud, listen again, until it is ended. */
    var talkMode by mutableStateOf(false)
        private set

    /** A reply is being spoken aloud. */
    var aloud by mutableStateOf(false)
        private set

    /** The desk's speech model is missing: the dialog asking to download it is open. */
    var voiceAsk by mutableStateOf(false)

    /** The speech model downloading, 0–1; null when not. */
    var voiceDownload by mutableStateOf<Float?>(null)
        private set

    private var voiceJob: Job? = null
    private var speakJob: Job? = null

    val voiceModel: com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
        get() = com.kaiharimoto.mastertool.core.ai.voice.VoiceModel.of(prefs.voiceModel)

    /** The words a transcriber is primed with: the open deck's cards, and the game's. */
    private fun hints(): String {
        val index = h.builder.index
        val deck = h.builder.deck
        val names = (deck.main + deck.extra + deck.side).distinct().mapNotNull { index.byId(it)?.name }
        return com.kaiharimoto.mastertool.core.ai.voice.Hints.prompt(names)
    }

    /** The microphone on or off: the words go into the composer, to read over before sending. */
    fun toggleVoice() {
        if (hearing || transcribing) com.kaiharimoto.neue.platform.Voice.stopListening() else listen(send = false)
    }

    /**
     * Listening (1.0.57): the level as it goes, the words into the draft — after whatever was
     * already written — and in talk mode sent as soon as they are written out.
     */
    fun listen(send: Boolean) {
        val voice = com.kaiharimoto.neue.platform.Voice
        if (hearing || transcribing || voiceJob?.isActive == true) return
        if (!voice.canListen) {
            h.neue.note = com.kaiharimoto.neue.Note("No microphone could be found")
            if (talkMode) talkMode = false
            return
        }
        if (voice.needsModel(voiceModel)) {
            voiceAsk = true
            return
        }
        stopSpeaking()
        val before = draft.trimEnd()
        fun joined(words: String) = if (before.isEmpty()) words.trim() else "$before ${words.trim()}"
        hearing = true
        voiceJob = scope.launch {
            try {
                voice.listen(voiceModel, hints()).collect { heard ->
                    when (heard) {
                        is com.kaiharimoto.neue.platform.Heard.Level -> voiceLevel = heard.rms
                        is com.kaiharimoto.neue.platform.Heard.Partial -> draft = joined(heard.text)
                        com.kaiharimoto.neue.platform.Heard.Transcribing -> {
                            hearing = false
                            transcribing = true
                        }
                        is com.kaiharimoto.neue.platform.Heard.Final -> {
                            draft = joined(heard.text)
                            if (send) send(draft) else focusTick++
                        }
                        is com.kaiharimoto.neue.platform.Heard.Failed -> {
                            if (talkMode) {
                                talkMode = false
                                notice = "Talk mode ended: ${heard.reason.replaceFirstChar { it.lowercase() }}"
                            } else {
                                h.neue.note = com.kaiharimoto.neue.Note(heard.reason.removeSuffix("."))
                            }
                        }
                    }
                }
            } finally {
                hearing = false
                transcribing = false
                voiceLevel = 0f
            }
        }
    }

    /** The speech model, downloaded once with the person's yes; then the microphone opens. */
    fun downloadVoiceModel() {
        voiceAsk = false
        val model = voiceModel
        voiceDownload = 0f
        scope.launch {
            val done = com.kaiharimoto.neue.platform.Voice.download(model) { bytes -> voiceDownload = (bytes.toFloat() / model.bytes).coerceIn(0f, 1f) }
            voiceDownload = null
            done.fold(
                { listen(send = talkMode) },
                {
                    talkMode = false
                    h.neue.note = com.kaiharimoto.neue.Note("The speech model could not be downloaded: ${it.message ?: "try again"}")
                },
            )
        }
    }

    /** Talk mode on or off (1.0.57): a conversation out loud. */
    fun toggleTalk() {
        if (talkMode) {
            endTalk()
        } else {
            if (!configured) {
                openWizard()
                return
            }
            talkMode = true
            notice = if (com.kaiharimoto.neue.platform.Voice.canSpeak || prefs.speakReplies == com.kaiharimoto.mastertool.core.prefs.AiPrefs.SPEAK_NEVER) null
            else "This computer has no voice to speak with, so replies stay on screen."
            listen(send = true)
        }
    }

    fun endTalk() {
        talkMode = false
        com.kaiharimoto.neue.platform.Voice.stopListening()
        stopSpeaking()
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        com.kaiharimoto.neue.platform.Voice.stopSpeaking()
        aloud = false
    }

    /** In talk mode, after an answer: say it, then listen again. */
    private fun answerAloud() {
        val reply = session?.turns?.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text
        speakJob = scope.launch {
            val voice = com.kaiharimoto.neue.platform.Voice
            if (reply != null && voice.canSpeak && prefs.speakReplies != com.kaiharimoto.mastertool.core.prefs.AiPrefs.SPEAK_NEVER) {
                aloud = true
                try {
                    voice.speak(com.kaiharimoto.mastertool.core.ai.voice.Spoken.of(reply), prefs.speechRate)
                } finally {
                    aloud = false
                }
            }
            if (talkMode) listen(send = true)
        }
    }

    // ---- pictures (1.0.55) -----------------------------------------------------

    /** Pictures waiting in the composer for the next message. */
    var attached by mutableStateOf<List<Attachment>>(emptyList())
        private set

    /** A picture being read into the composer, so the composer can say so. */
    var attaching by mutableStateOf(false)
        private set

    /** [file] added to the next message, when it is a picture and there is room; a note says why not. */
    fun attach(file: com.kaiharimoto.neue.platform.PickedFile) {
        if (attached.size >= com.kaiharimoto.mastertool.core.ai.vision.PictureFit.MOST) {
            h.neue.note = com.kaiharimoto.neue.Note("${com.kaiharimoto.mastertool.core.ai.vision.PictureFit.MOST} pictures at most in one message")
            return
        }
        attaching = true
        scope.launch {
            val ready = Attachments.prepare(file)
            attaching = false
            if (ready == null) {
                h.neue.note = com.kaiharimoto.neue.Note("That is not a picture ${name} can read")
            } else {
                attached = attached + ready
                focusTick++
            }
        }
    }

    /** A picture of the chat opened large. */
    var pictureOpen by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** The system prompt a conversation on the connection in use would start with, for the studio's pictures. */
    fun previewSystem(): String = prefs.connection?.let { systemPrompt(it) }.orEmpty()

    /** The studio's pictures of voice (1.0.57): listening at a level, talk mode, speaking aloud. */
    fun previewVoice(listening: Boolean, level: Float, talk: Boolean, speaking: Boolean) {
        hearing = listening
        voiceLevel = level
        talkMode = talk
        aloud = speaking
    }

    /** The studio's picture of pictures waiting in the composer. */
    fun previewAttached(list: List<Attachment>) {
        attached = list
    }

    fun detach(a: Attachment) {
        attached = attached - a
    }

    /** Whether the model in use can see a picture: yes, no, or it cannot be told. */
    val sight: com.kaiharimoto.mastertool.core.ai.vision.Vision.Sight
        get() = prefs.connection?.let { com.kaiharimoto.mastertool.core.ai.vision.Vision.of(it.provider, it.model) }
            ?: com.kaiharimoto.mastertool.core.ai.vision.Vision.Sight.MAYBE

    /** The person's message, sent; Ai answers, acting through its tools. */
    fun send(text: String) {
        val words = text.trim()
        val pictures = attached
        if ((words.isEmpty() && pictures.isEmpty()) || running) return
        val connection = prefs.connection ?: run {
            openWizard()
            return
        }
        problem = null
        status = null
        notice = null
        draft = ""
        attached = emptyList()
        if (com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker.isThanks(words)) mood.thanked(clock())
        val current = session?.takeIf { it.connection == connection.id } ?: begin(connection)
        val images = pictures.map { files.putImage(current.id, it.bytes, it.mime, it.width, it.height) }
        val scope = host.scope()
        val scopeChanged = scope?.path != current.scopeShown
        val renamed = renamedTo?.let { listOf("The person renamed you: you are $it from now on, whatever the instructions above call you.") }.orEmpty()
        renamedTo = null
        // The open deck's guide (how it plays), once per deck: taught or studied in Fine Tuning (1.0.48).
        val deckId = h.builder.deckId
        val guide = if (deckId != null && deckId != current.guideShown) {
            files.entries(MemoryKind.GUIDE, deckId).takeIf { it.isNotBlank() }?.let { listOf("", "Your guide to how “${h.builder.deckName}” plays (memory scope guide):", it) }.orEmpty()
        } else {
            emptyList()
        }
        val context = PromptBuilder.context(renamed + host.situation() + guide, scope, scope?.let { host.notes(it) }, scopeChanged)
        val turn = ChatTurn.user(words, context, System.currentTimeMillis(), images)
        val next = current.copy(
            turns = current.turns + turn,
            updatedAt = System.currentTimeMillis(),
            scopeShown = scope?.path ?: current.scopeShown,
            guideShown = deckId ?: current.guideShown,
        ).titled()
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
        val intensity = com.kaiharimoto.mastertool.core.ai.TuneIntensity.of(prefs.tuneIntensity)
        // A study runs as long and thinks as hard as its intensity says; an interview needs rounds for its questions.
        val studies = start.mode == AiSession.MODE_STUDY || start.mode == AiSession.MODE_PRINCIPLES
        val effort = if (studies) intensity.effort else prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() }
        val steps = when (start.mode) {
            AiSession.MODE_STUDY, AiSession.MODE_PRINCIPLES -> intensity.steps
            AiSession.MODE_TUNE, AiSession.MODE_PROFILE -> intensity.questions * 3 + 8
            else -> AgentLoop.MAX_STEPS
        }
        val budget = if (model.runsOwnLoop) 0 else budgetFor(connection)
        job = scope.launch {
            try {
                // Past most of the model's window, the oldest turns become a summary first (1.0.47).
                val ready = if (budget > 0) summarizedIfLong(start, model, connection, budget) else start
                // From first principles (1.0.54) the model is never offered the web or the community's lists.
                val offered = if (start.mode == AiSession.MODE_PRINCIPLES) tools.filter { it.name !in AiTools.FIRST_PRINCIPLES_BARRED } else tools
                // The pictures' bytes, read from their files just now: they are never kept in the conversation.
                val request = TurnRequest(ready.system, files.hydrate(ready.sent), offered, connection.model, effort, ready.resume)
                AgentLoop(model, { call -> host.run(call) }, maxSteps = steps, now = System::currentTimeMillis, budget = budget).run(request).collect { event ->
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
                        // What the model read this round is how full the window is now (1.0.56).
                        is AgentEvent.Round -> if (event.measured) session?.let { commit(it.copy(context = event.usage.read)) }
                        is AgentEvent.Done -> session?.let { commit(it.copy(usage = it.usage + event.usage)) }
                        is AgentEvent.Failed -> problem = (
                            // A model that cannot see, sent a picture, says so in its own words; say it plainly.
                            if (start.turns.any { t -> t.images.isNotEmpty() } && com.kaiharimoto.mastertool.core.ai.vision.Vision.refused(event.message)) {
                                com.kaiharimoto.mastertool.core.ai.vision.Vision.REFUSED
                            } else {
                                event.message
                            }
                            ) to event.auth
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
        tool = null
        running = false
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
            session?.turns?.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.let {
                lastReply = firstLine(it.text)
                repliedAt = System.currentTimeMillis()
            }
        }
        val wasStopped = stopping
        stopping = false
        confirm?.reply(false)
        confirm = null
        question?.reply("(no answer)")
        question = null
        status = null
        job = null
        // Talk mode (1.0.57): the answer said aloud, then the microphone again; a Stop or a problem ends it.
        if (talkMode) {
            if (problem == null && !wasStopped) answerAloud() else endTalk()
        }
        // A summary asked for while it answered (1.0.56): made now the answer is done.
        pendingCompact?.let { focus ->
            pendingCompact = null
            if (problem == null) compactNow(focus.ifBlank { null })
        }
    }

    fun stop() {
        if (job != null) stopping = true
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
     * How much a connection's model can read, in tokens (1.0.56: read off the model's name,
     * `ContextWindows`, unless the person said): past most of it the oldest turns are summarised.
     */
    fun windowOf(connection: AiConnection): Int = connection.window?.takeIf { it > 0 } ?: com.kaiharimoto.mastertool.core.ai.ContextWindows.of(
        connection.provider,
        connection.model,
        local = Providers.byId(connection.provider)?.kind == com.kaiharimoto.mastertool.core.ai.providers.ConnectKind.LOCAL,
    )

    private fun budgetFor(connection: AiConnection): Int = windowOf(connection)

    /** Whether the connection in use keeps its own history — a plan's command-line app — so the app cannot compact it. */
    val ownsContext: Boolean
        get() = prefs.connection?.let { Providers.byId(it.provider)?.wire.let { w -> w == Wire.CLAUDE_CLI || w == Wire.CODEX_CLI } } == true

    /** The model's window for the connection in use, in tokens. */
    val window: Int get() = prefs.connection?.let(::windowOf) ?: 0

    /** What fills the conversation now, in tokens: the provider's count when it gave one, else an estimate. */
    val contextUsed: Long
        get() = session?.let { com.kaiharimoto.mastertool.core.ai.ContextBreakdown.total(it, tools) } ?: 0

    /** Whether [contextUsed] is the provider's own count rather than an estimate. */
    val contextMeasured: Boolean get() = (session?.context ?: 0) > 0

    /** The Context panel is open (1.0.56). */
    var contextOpen by mutableStateOf(false)

    /** A summary asked for (by Ai's `compact`, or while it was answering), made once the answer is done. */
    private var pendingCompact: String? = null

    /**
     * [s] with its oldest turns folded into a summary the model writes, once they no longer
     * fit — or now, when [force]d, keeping only the last few exchanges; [focus] says what the
     * summary must keep. The summary is kept with the conversation, so it is written once.
     */
    private suspend fun summarizedIfLong(
        s: AiSession,
        model: ModelBackend,
        connection: AiConnection,
        budget: Int,
        force: Boolean = false,
        focus: String? = null,
    ): AiSession {
        val used = com.kaiharimoto.mastertool.core.ai.Compaction.estimate(s.system, s.sent, tools)
        if (!force && used <= budget * com.kaiharimoto.mastertool.core.ai.Compaction.SUMMARIZE_AT) return s
        val keep = if (force) minOf((budget * 0.3).toInt(), used / 4) else (budget * 0.3).toInt()
        val cut = com.kaiharimoto.mastertool.core.ai.Compaction.cutAt(s.turns, keep, from = s.summarized) ?: return s
        if (cut <= s.summarized) return s
        val summary = summarise(s, model, connection, cut, focus) ?: return s
        val next = s.copy(summary = summary, summarized = cut, context = 0)
        commit(next)
        notice = null
        return next
    }

    /** The conversation's turns before [cut], with what was summarised before, in the model's own summary. */
    private suspend fun summarise(s: AiSession, model: ModelBackend, connection: AiConnection, cut: Int, focus: String?): String? {
        status = "Summarising the start of this conversation to fit"
        val earlier = buildString {
            if (s.summary.isNotBlank()) appendLine("Summary so far: ${s.summary}\n")
            append(com.kaiharimoto.mastertool.core.ai.Compaction.transcript(s.turns.subList(s.summarized.coerceAtMost(cut), cut)))
        }
        val keep = focus?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\nThe person asked that the summary keep: $it" }.orEmpty()
        val ask = ChatTurn.user(com.kaiharimoto.mastertool.core.ai.Compaction.SUMMARY_ASK + keep + "\n\n" + earlier)
        var summary = ""
        runCatching {
            model.turn(TurnRequest(s.system, listOf(ask), emptyList(), connection.model, "low")).collect { e ->
                if (e is com.kaiharimoto.mastertool.core.ai.BackendEvent.Finished) {
                    summary = e.turn?.text?.ifBlank { null } ?: e.text
                    e.usage?.let { u -> session?.let { commit(it.copy(usage = it.usage + u)) } }
                }
            }
        }
        status = null
        return summary.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The start of the conversation summarised now (1.0.56, the Context panel's Compact now, or
     * Ai's own `compact`): all but the last few exchanges, keeping [focus]. Waits for an answer
     * under way to finish first.
     */
    fun compactNow(focus: String? = null) {
        if (running) {
            pendingCompact = focus.orEmpty()
            return
        }
        val s = session ?: return
        val connection = prefs.connection ?: return
        if (ownsContext) {
            notice = "${Providers.byId(connection.provider)?.label ?: "The command-line app"} keeps its own history and compacts it itself."
            return
        }
        val model = runCatching { backendFor(connection) }.getOrElse {
            problem = (it.message ?: "Could not connect.") to true
            return
        }
        running = true
        job = scope.launch {
            try {
                val next = summarizedIfLong(s, model, connection, budgetFor(connection), force = true, focus = focus)
                notice = if (next.summarized > s.summarized) "The start of the conversation was summarised: ${next.summarized} messages in a few paragraphs." else "There was not enough to summarise yet."
            } finally {
                running = false
                status = null
                job = null
            }
        }
    }

    /** Every old tool result sent cut short from now on (1.0.56): the fastest room there is, and it costs nothing. */
    fun clearToolResults() {
        val s = session ?: return
        commit(s.copy(clearedBefore = s.turns.size, context = 0))
        notice = "Old tool results are sent cut short from now on; the conversation keeps them whole."
    }

    /**
     * A new conversation that carries this one's summary (1.0.56): the room of a fresh start
     * without losing the thread.
     */
    fun startFresh() {
        val s = session ?: return
        val connection = prefs.connection ?: return
        if (s.turns.isEmpty() || running) return
        if (ownsContext) {
            newChat()
            return
        }
        val model = runCatching { backendFor(connection) }.getOrElse {
            problem = (it.message ?: "Could not connect.") to true
            return
        }
        running = true
        job = scope.launch {
            try {
                val summary = summarise(s, model, connection, s.turns.size, null)
                running = false
                newChat()
                if (summary != null) session?.let { commit(it.copy(summary = summary, carriedFrom = s.id)) }
                notice = if (summary != null) "A fresh conversation, carrying a summary of the last one." else "A fresh conversation; the summary could not be written."
            } finally {
                running = false
                status = null
                job = null
            }
        }
    }

    /** What `context_status` tells Ai, and the Context panel shows in words. */
    fun contextReport(): String {
        val s = session ?: return "No conversation yet."
        val used = contextUsed
        val w = window
        val words = com.kaiharimoto.mastertool.core.ai.ContextWindows::words
        return buildString {
            if (ownsContext) {
                appendLine("This connection's app keeps its own history and compacts it itself; the numbers below are the app's estimate.")
            }
            appendLine(
                "Context: ${words(used)} of ${words(w.toLong())} tokens" + (if (w > 0) " (${used * 100 / w}%)" else "") +
                    if (contextMeasured) ", as the provider counted last round." else ", estimated.",
            )
            com.kaiharimoto.mastertool.core.ai.ContextBreakdown.of(s, tools, s.context).forEach { appendLine("- ${it.label}: ${words(it.tokens)}") }
            if (s.summarized > 0) appendLine("The first ${s.summarized} messages are summarised (${s.summary.length} characters); recall finds their words.")
            if (s.carriedFrom != null) appendLine("This conversation carries on from an earlier one, whose summary it holds.")
            if (s.clearedBefore > 0) appendLine("Tool results before message ${s.clearedBefore} are sent cut short.")
            appendLine("Spent in this conversation: ${words(s.usage.read)} read, ${words(s.usage.output)} written" + (s.usage.costUsd?.let { ", about $" + "%.2f".format(it) }.orEmpty()) + ".")
        }.trimEnd()
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

    /** The Fine Tuning launcher is open: which way, and how hard (1.0.48). */
    var tuneAsk by mutableStateOf(false)

    /**
     * Fine Tuning (1.0.48, kai: "for me to teach it how to play my deck and have it ask me
     * questions about my deck … or have the AI teach itself by reading the cards and going
     * online"): a conversation of its own about the deck open in the builder, [study] or
     * taught, at [intensity]; what it learns goes to the deck's guide.
     */
    fun startTuning(mode: String, intensity: com.kaiharimoto.mastertool.core.ai.TuneIntensity) {
        if (PHASE < 3) return
        tuneAsk = false
        val connection = prefs.connection ?: run {
            openWizard()
            return
        }
        val deck = h.builder.deckName
        if (h.builder.deckId == null) {
            h.neue.note = com.kaiharimoto.neue.Note("Save the deck first: Fine Tuning writes a guide to a saved deck")
            return
        }
        stop()
        h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true, tuneIntensity = intensity.id)) }
        wizardOpen = false
        historyOpen = false
        demoOpen = false
        tuneBefore = snapshot()
        lastReport = null
        session = begin(connection, mode)
        send(
            when (mode) {
                AiSession.MODE_STUDY -> "Study “$deck” yourself, and think out loud so I can learn with you. Intensity: ${intensity.label} — ${intensity.studies}"
                AiSession.MODE_PRINCIPLES -> "Learn “$deck” from first principles: its card text and the rules, no guides or lists. Work out its goals and how its cards pair, " +
                    "interact and connect, and think out loud so I can learn with you. Intensity: ${intensity.label} — about ${intensity.steps} rounds."
                else -> "Let's do Fine Tuning on “$deck”: I'll teach you how I play it. Intensity: ${intensity.label}, about ${intensity.questions} questions."
            },
        )
    }

    /**
     * Learn About You (1.0.54, kai: "the AI builds a profile of the user across sessions and
     * interviews them about anything that would help the Ai understand what the user's goals and
     * preferences are, as well as their workflow"): an interview about the person, not a deck, into
     * USER.md — the memory in front of every conversation.
     */
    fun startProfile(intensity: com.kaiharimoto.mastertool.core.ai.TuneIntensity) {
        if (PHASE < 3) return
        profileAsk = false
        val connection = prefs.connection ?: run {
            openWizard()
            return
        }
        stop()
        h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true, tuneIntensity = intensity.id)) }
        wizardOpen = false
        historyOpen = false
        demoOpen = false
        tuneBefore = snapshot()
        lastReport = null
        session = begin(connection, AiSession.MODE_PROFILE)
        val known = files.entries(MemoryKind.USER).isNotBlank()
        send(
            "Let's do Learn About You: interview me so you understand my goals, my preferences and how I work. " +
                (if (known) "Start from what you already know and fill the gaps. " else "") +
                "Intensity: ${intensity.label}, about ${intensity.questions} questions.",
        )
    }

    /** Learn About You's launcher is open. */
    var profileAsk by mutableStateOf(false)

    /** Finish asked Ai to file its report; the session ends when that answer does. */
    private var wrapping = false

    /** The report Ai filed in this session, when it did (the host sets it). */
    var lastReport by mutableStateOf<com.kaiharimoto.mastertool.core.ai.report.SessionReport?>(null)

    /** The report shown at the session's end, beside what it learned (1.0.54). */
    var endReport by mutableStateOf<com.kaiharimoto.mastertool.core.ai.report.SessionReport?>(null)

    /** Library decks by id, for naming their guides and notes in the brain. */
    var deckNames by mutableStateOf<Map<String, String>>(emptyMap())

    /** Quick settings, from the model's name in the panel's head (1.0.54). */
    var quickOpen by mutableStateOf(false)

    /** The living document open over the page: a deck's guide or the person's profile (1.0.54). */
    var docOpen by mutableStateOf<LivingDoc?>(null)

    fun openGuide() {
        val id = h.builder.deckId ?: run {
            h.neue.note = com.kaiharimoto.neue.Note("Save the deck first: the guide belongs to a saved deck")
            return
        }
        docOpen = LivingDoc.Guide(id, h.builder.deckName)
    }

    fun openProfile() {
        docOpen = LivingDoc.Profile
    }

    private var tuneBefore: Map<String, String?>? = null

    val tuning: Boolean get() = session?.mode.let { it in AiSession.DECK_MODES || it == AiSession.MODE_PROFILE }

    /** Studying on its own rather than being taught. */
    val studying: Boolean get() = session?.mode == AiSession.MODE_STUDY || session?.mode == AiSession.MODE_PRINCIPLES

    /** Learning about the person rather than a deck. */
    val profiling: Boolean get() = session?.mode == AiSession.MODE_PROFILE

    /**
     * Fine Tuning done: first, when the session taught Ai something and it has not filed its report,
     * it is asked to (1.0.54) — the scores and the PDF come from that — then what it learned, to keep
     * or undo, and an ordinary conversation.
     */
    fun finishTuning() {
        val s = session
        val asksReport = s != null && s.mode in AiSession.DECK_MODES && !running && !wrapping &&
            !com.kaiharimoto.mastertool.core.ai.report.SessionQuestions.reported(s.turns) && s.turns.size > 2 && prefs.connection != null
        if (asksReport) {
            wrapping = true
            send("We're finishing here. File your session report now with session_report — your honest scores and why — then say goodbye in one line.")
            return
        }
        completeTuning()
    }

    private fun completeTuning() {
        wrapping = false
        val ended = session
        stop()
        endReport = lastReport?.takeIf { r -> ended != null && ended.mode in AiSession.DECK_MODES && r.at >= ended.createdAt }
        lastReport = null
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
        endReport = null
    }

    /** Everything the review lists put back as it was. */
    fun undoReview() {
        val changed = review?.map { it.path }.orEmpty()
        changed.forEach { path -> reviewBefore[path]?.let { files.write(path, it) } ?: files.delete(path) }
        review = null
        reviewBefore = emptyMap()
        endReport = null
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

    /** A connection changed in place — its model, its label (quick settings, 1.0.54) — the conversation kept. */
    fun tweak(connectionId: String, change: (AiConnection) -> AiConnection) {
        h.neue.update { p -> p.copy(ai = p.ai.copy(connections = p.ai.connections.map { if (it.id == connectionId) change(it) else it })) }
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
        /** A reply's first line in plain words, for the bar: no markdown, no card brackets. */
        fun firstLine(text: String): String = text.lineSequence()
            .map { it.trim().trimStart('#', '-', '*', '>', ' ').replace("[[", "").replace("]]", "").replace("**", "").replace("`", "").trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("|") }
            .orEmpty()
            .take(160)

        /** The phase this build ships: 1, the harness; 2, the meta; 3, learning. */
        const val PHASE = 3

        /** How many messages a conversation needs before it is reflected on. */
        const val REFLECT_AFTER = 4
    }
}

/** A living document Ai keeps and the person reads (1.0.54): a deck's guide, or the person's profile. */
sealed interface LivingDoc {
    data class Guide(val deckId: String, val deckName: String) : LivingDoc
    data object Profile : LivingDoc
}
