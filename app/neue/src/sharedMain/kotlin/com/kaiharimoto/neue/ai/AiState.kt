package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.chessy.SlashCommand
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyVoice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.LatestWrites
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.ai.WorkNotice
import com.kaiharimoto.mastertool.core.ai.avatar.AiSignals
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker
import com.kaiharimoto.mastertool.core.ai.memory.MemoryChange
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.ai.skills.Skill
import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.ai.skills.Skills
import com.kaiharimoto.mastertool.core.ai.vision.PictureFit
import com.kaiharimoto.mastertool.core.ai.vision.Vision
import com.kaiharimoto.mastertool.core.ai.trace.TraceBook
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.present.ai.PresentBrief
import com.kaiharimoto.mastertool.core.present.ai.RestyleBrief
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckDecks
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    /**
     * What Ai understood so far, shown above the question as "What I heard" (1.0.65, kai: "it keeps
     * asking me just 'Is that right' without giving me a rundown of what I said").
     */
    val heard: List<String> = emptyList(),
) {
    internal val answer = CompletableDeferred<String>()
    fun reply(text: String) = answer.complete(text)

    /**
     * What the person has typed and picked so far (1.0.63, kai: "i was typing it but it refreshed …
     * and my progress of the answer was gone"): kept here, with the question, not in the chat's row,
     * which the list drops and rebuilds as it scrolls, the keyboard opens and lines arrive above it.
     */
    var typed by mutableStateOf("")
    var picked by mutableStateOf(setOf<String>())
}

/**
 * The assistant, for the whole app (Ai, 1.0.43): the conversation on screen, the
 * model it is held with, the tools it acts through, and what it is waiting on. One
 * per app, not per window — like [NeueHolders]' other app-lifetime state — so a
 * window swapped for immersive mode keeps the conversation mid-sentence.
 */
class AiState(internal val h: NeueHolders) {
    internal val scope = Background.scope("Ai", Dispatchers.Main)

    /** Chessy's takeover, when it plays (kai, 2026-10): its clock, its choice, its sound. */
    val takeovers = com.kaiharimoto.neue.ai.chessy.Takeovers(this)
    val files = AiFiles(File(Platform.dataDir, "ai"))

    /** Every run of Ai, traced (H.8): this launch's for the Context panel, two weeks' on this device's disk. */
    val traces: AiTraces by lazy { AiTraces { File(files.root, TraceBook.FOLDER) } }
    internal val host = AiHost(h, this)

    /** Chessy's copies at work, while she is the assistant (kai, 2026-10). */
    val crew = com.kaiharimoto.neue.ai.chessy.ChessyCrew()

    /** Chessy's petting mode, while it is open: held on in the chat box, she comes out large (kai's Easter egg). */
    var amie by mutableStateOf<com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie?>(null)
        private set

    /** For the studio's pictures: a hand to play through the petting mode once it opens (pet, tickle, bell, ear, hug, sulk). */
    var amieDemo: String? = null

    /** The chest's drawer of her gifts is open over her room (1.1.31): Esc and Back close it first. */
    var giftDrawer by mutableStateOf(false)

    fun openAmie() {
        if (amie == null) amie = com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie(seed = (System.nanoTime() % 100_000).toInt())
    }

    /** She goes back to the chat box, waving, and says goodbye there. */
    fun closeAmie() {
        val was = amie ?: return
        giftDrawer = false
        amie = null
        val bye = was.bye()
        touched(AvatarPlay.Reaction(bye.mood, bye.seconds, bye.line))
    }
    internal val http by lazy { HttpClientFactory.create() }

    /** The conversation on screen. */
    var session by mutableStateOf<AiSession?>(null)
        internal set

    /**
     * The answer being written, word by word. Its words gather in [written] and the screen is told by
     * a number (1.0.92): the old `streaming += delta` copied the whole answer at every word; now it is
     * copied once per read that finds it changed — once a frame — and reads the same at every moment.
     */
    val streaming: String
        get() {
            val v = writtenVersion
            if (v != shownVersion) {
                shown = written.toString()
                shownVersion = v
            }
            return shown
        }
    internal val written = StringBuilder()
    private var writtenVersion by mutableIntStateOf(0)
    private var shown = ""
    private var shownVersion = 0

    internal fun appendWritten(delta: String) {
        if (delta.isEmpty()) return
        written.append(delta)
        writtenVersion++
    }

    internal fun clearWritten() {
        if (written.isEmpty()) return
        written.setLength(0)
        writtenVersion++
    }

    /** A tool running now, in words. */
    var working by mutableStateOf<String?>(null)
        internal set

    /** Lines of what a CLI did this turn (its own tools, and ours through MCP). */
    var activity by mutableStateOf<List<Part.Activity>>(emptyList())
        internal set

    var running by mutableStateOf(false)
        internal set

    /**
     * The connection the latest run began on (H.4): its tools are judged by this one's ceiling to its end, even if Ai
     * switched `ai.active` meanwhile.
     */
    internal var runConnection: AiConnection? = null

    /** What went wrong last, and whether the connection is the problem (the wizard can fix it). */
    var problem by mutableStateOf<Pair<String, Boolean>?>(null)
        internal set

    var status by mutableStateOf<String?>(null)
        internal set

    var confirm by mutableStateOf<Confirm?>(null)
        internal set

    var question by mutableStateOf<Question?>(null)
        internal set

    /** The model's thinking as it streams (1.0.47), shown above the words it leads to. */
    var reasoning by mutableStateOf("")
        internal set

    /** Ai's plan for the job in hand ([x] done, [>] doing, [ ] to do), from `todo_write` (1.0.47). */
    var todos by mutableStateOf<List<String>>(emptyList())

    /** A line that outlives the answer (a limit reached), until the next message (1.0.46). */
    var notice by mutableStateOf<String?>(null)

    /** A video was linked with no key to watch it: the key's box stands in the chat (1.0.62). */
    var videoKeyAsked by mutableStateOf(false)

    /** "What can you do?" playing in the panel (1.0.46): a scripted show of Ai at work, no model called. */
    var demoOpen by mutableStateOf(false)

    /** The studio's picture of the demo: this scene, written out whole. */
    var demoStill: Int? = null

    /** The setup wizard is open (the panel shows it in place of the chat). */
    var wizardOpen by mutableStateOf(false)

    /** Where the wizard is, kept while the panel closes. */
    var wizard by mutableStateOf(WizardState(AiPrefs.DEFAULT_NAME))

    /** The memory file open in "What it knows", by its path under the folder; null when closed. */
    var memoryOpen by mutableStateOf<String?>(null)

    /** "Forget everything" waiting on its confirmation. */
    var forgetAsked by mutableStateOf(false)

    /** The list of past conversations is open. */
    var historyOpen by mutableStateOf(false)

    /** Bumped when a conversation is deleted, so the open list reads its files again (the red team, 2026-10). */
    var sessionsRevision by mutableStateOf(0)
        private set

    /** Bumped to ask the composer for the keyboard. */
    var focusTick by mutableStateOf(0)

    /** The last [focusTick] a composer took focus for (plain, read only when one is drawn). */
    var focusTaken = 0

    /** The composer's draft, kept while the panel is closed. */
    var draft by mutableStateOf("")

    val prefs: AiPrefs get() = h.neue.prefs.ai
    /** The assistant's name as shown and as the model is told it: Chessy while she is the assistant, else Ai's own. */
    val name: String get() = if (prefs.persona == AiPrefs.PERSONA_CHESSY) CHESSY_NAME else prefs.name

    /** Ai's own name, the one the person can change: renaming and setup read and write this, never [name]. */
    val ownName: String get() = prefs.name
    val enabled: Boolean get() = prefs.enabled
    val configured: Boolean get() = prefs.connection != null

    /** The release phase this build has shipped: which tools it offers (`AiTools.offered`). */
    val tools: List<ToolSpec> get() = AiTools.offered(PHASE)

    internal var job: Job? = null
    internal var backend: Pair<String, ModelBackend>? = null
    internal var mcp: McpHandle? = null

    /** Where the meta tools read tournament lists: null is YGOPRODeck itself; a test sets a fake one here. */
    internal var tournaments: YgoProDeckDecks? = null

    // ---- the face (1.0.52) ------------------------------------------------------

    /** The face Ai wears now, beside the chat and in the bar alike: the mood table's, ticked by [AiFaceClock]. */
    var face by mutableStateOf(Expression.IDLE)
        internal set

    internal val mood = MoodTracker()

    /** How the face answers a hand (1.0.54): taps, petting, poking, holding. */
    internal val play = AvatarPlay()

    /** What the face said to the hand, beside it for as long as the face is worn. */
    var playLine by mutableStateOf<String?>(null)
        private set
    private var playLineUntil = 0.0

    /** The face answered a hand: worn for its moment, its line beside it. */
    fun touched(r: AvatarPlay.Reaction?) {
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
        internal set
    var repliedAt by mutableStateOf(0L)
        internal set

    /** Whether the empty conversation has winked hello yet, this run. */
    internal var greeted = false

    /** Stop was pressed: the turn's end is a sad face, not a done one. */
    internal var stopping = false

    /** The face's clock: seconds on `System.nanoTime`, the clock [NeueHolders.lastInput] keeps. */
    internal fun clock(): Double = System.nanoTime() / 1e9

    /** For the studio's pictures: a turn at work on [line], with no model behind it. */
    fun pretendWorking(line: String) {
        running = true
        working = line
    }

    /** For the studio's pictures: a question waiting on the person, with no model behind it. */
    fun pretendAsking(words: String, options: List<String> = emptyList()) {
        question = Question(words, options, multiple = false)
    }

    /** A face Ai chose for itself (the `express` tool). */
    fun express(e: Expression, seconds: Int) = mood.express(e, seconds.toDouble(), clock())

    /** The face now, from what Ai is doing. */
    fun tickFace() {
        face = mood.at(
            AiSignals(
                running = running,
                streaming = written.isNotEmpty(),
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

    // ---- the fact-check pass (1.0.58) ------------------------------------------

    /** An answer's claims are being checked. */
    var checking by mutableStateOf(false)
        internal set

    /** The next answer is the correction a check asked for, and is not checked itself. */
    internal var correcting = false

    /** The duel answer being written is to the person's own words, not a cue (F.2): coaching, checked when it lands. */
    internal var duelAsked = false

    // ---- voice (1.0.57) ---------------------------------------------------------

    /** The microphone is open. */
    var hearing by mutableStateOf(false)
        internal set

    /** What was said is being written out. */
    var transcribing by mutableStateOf(false)
        internal set

    /** How loud the microphone is now, 0–1, for the composer's meter. */
    var voiceLevel by mutableStateOf(0f)
        internal set

    /** Talk mode: listen, answer aloud, listen again, until it is ended. */
    var talkMode by mutableStateOf(false)
        internal set

    /** A reply is being spoken aloud. */
    var aloud by mutableStateOf(false)
        internal set

    /** The desk's speech model is missing: the dialog asking to download it is open. */
    var voiceAsk by mutableStateOf(false)

    /** The speech model downloading, 0–1; null when not. */
    var voiceDownload by mutableStateOf<Float?>(null)
        internal set

    internal var voiceJob: Job? = null
    internal var speakJob: Job? = null

    /**
     * The model asked for by the duel's push-to-talk (1.0.87): the same dialog, in the duel's words, and once it is
     * here nothing starts listening — the key was let go long ago — it is only read in, ready for the next hold.
     */
    var voiceForDuel by mutableStateOf(false)
        internal set

    // ---- pictures (1.0.55) -----------------------------------------------------

    /** Pictures waiting in the composer for the next message. */
    var attached by mutableStateOf<List<Attachment>>(emptyList())
        private set

    /** A picture being read into the composer, so the composer can say so. */
    var attaching by mutableStateOf(false)
        private set

    /** [file] added to the next message, when it is a picture and there is room; a note says why not. */
    fun attach(file: PickedFile) {
        if (attached.size >= PictureFit.MOST) {
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

    /** The studio's picture of pictures waiting in the composer. */
    fun previewAttached(list: List<Attachment>) {
        attached = list
    }

    fun detach(a: Attachment) {
        attached = attached - a
    }

    /** Whether the model in use can see a picture: yes, no, or it cannot be told. */
    val sight: Vision.Sight
        get() = prefs.connection?.let { Vision.of(it.provider, it.model) }
            ?: Vision.Sight.MAYBE

    /**
     * A command typed in the message box (Chessy, kai): `/chessy` makes her the assistant, `/ai` brings Ai back,
     * `/catmode` turns her full cat voice on or off. The conversation's next message tells the model who it is now.
     */
    private fun command(c: SlashCommand) {
        draft = ""
        when (c) {
            // She takes over the chat at once, no cinematic (kai: "have /Chessy just switch to Chessy and /takeover be the
            // dedicated cinematic trigger"); the next message tells the model who it is (ChessyVoice.switched, in send).
            // Her break-in is counted as seen, so it does not play by itself later.
            SlashCommand.CHESSY -> if (prefs.persona == AiPrefs.PERSONA_CHESSY) {
                h.neue.note = com.kaiharimoto.neue.Note("$CHESSY_NAME is already here. Type /takeover to watch her break in")
            } else {
                h.neue.update { it.copy(ai = it.ai.copy(persona = AiPrefs.PERSONA_CHESSY, takeover = AiPrefs.TAKEOVER_SEEN)) }
                h.neue.note = com.kaiharimoto.neue.Note("$CHESSY_NAME is your assistant now. Type /ai for $ownName, /takeover for her break-in")
            }
            SlashCommand.TAKEOVER -> takeovers.start()
            SlashCommand.AI -> {
                h.neue.update { it.copy(ai = it.ai.copy(persona = AiPrefs.PERSONA_AI)) }
                h.neue.note = com.kaiharimoto.neue.Note("$ownName is back. Type /chessy for $CHESSY_NAME")
            }
            SlashCommand.CAT_MODE -> {
                val on = !prefs.catMode
                h.neue.update { it.copy(ai = it.ai.copy(catMode = on)) }
                h.neue.note = com.kaiharimoto.neue.Note(
                    when {
                        prefs.persona != AiPrefs.PERSONA_CHESSY -> if (on) "Cat mode on: it is $CHESSY_NAME's voice, for when she is your assistant (/chessy)" else "Cat mode off"
                        on -> "Cat mode on: nya~ from her next answer"
                        else -> "Cat mode off: her next answer is plain again"
                    },
                )
            }
        }
    }

    /** The person's message, sent; Ai answers, acting through its tools. */
    fun send(text: String) {
        val words = text.trim()
        val pictures = attached
        if ((words.isEmpty() && pictures.isEmpty()) || running) return
        // A whole message that is a command is the app's, never the model's (/chessy, /catmode, /ai).
        if (pictures.isEmpty()) SlashCommand.parse(words)?.let { command(it); return }
        val connection = prefs.connection ?: run {
            openWizard()
            return
        }
        problem = null
        status = null
        notice = null
        draft = ""
        attached = emptyList()
        if (MoodTracker.isThanks(words)) mood.thanked(clock())
        // A seat's conversation of an Ai vs Ai match is read, never carried on: a message starts a new one.
        val current = session?.takeIf { it.connection == connection.id && it.mode != AiSession.MODE_MATCH } ?: begin(connection, query = words)
        val images = pictures.map { files.putImage(current.id, it.bytes, it.mime, it.width, it.height) }
        val scope = host.scope()
        val scopeChanged = scope?.path != current.scopeShown
        val renamed = renamedTo?.let { listOf("The person renamed you: you are $it from now on, whatever the instructions above call you.") }.orEmpty()
        renamedTo = null
        // Chessy, Ai or cat mode changed since the conversation began (its instructions are frozen): say so now.
        val voice = voiceNow
        val revoiced = if (voice != (current.voiceShown ?: ChessyVoice.AI)) listOf(ChessyVoice.switched(voice, ownName)) else emptyList()
        // The open deck's guide (how it plays), once per deck: taught or studied in Fine Tuning (1.0.48).
        // A Fine Tuning run reads its own deck's guide, whatever the builder shows (1.0.98).
        val pinned = current.takeIf { it.mode in AiSession.DECK_MODES && it.deckId != null }
        val deckId = pinned?.deckId ?: h.builder.deckId
        val deckName = pinned?.deckName ?: h.builder.deckName
        // Within its room on this model (1.1.11): the entries most relevant to these words, and a line for the rest.
        val guide = if (deckId != null && deckId != current.guideShown) {
            guideBlock(deckId, deckName, words)?.let { listOf("", it) }.orEmpty()
        } else {
            emptyList()
        }
        val context = PromptBuilder.context(revoiced + renamed + host.situation() + guide, scope, scope?.takeIf { scopeChanged }?.let { host.notes(it, words) }, scopeChanged)
        val turn = ChatTurn.user(words, context, System.currentTimeMillis(), images)
        val next = current.copy(
            turns = current.turns + turn,
            updatedAt = System.currentTimeMillis(),
            scopeShown = scope?.path ?: current.scopeShown,
            guideShown = deckId ?: current.guideShown,
            voiceShown = voice,
        ).titled()
        commit(next)
        respond(next, connection)
    }

    /**
     * A word at the duel table (1.0.80, kai: "operate and communicate with the AI using the log chat as
     * the main one"): sent in the duel's own conversation ([AiSession.MODE_DUEL], begun when there is none
     * or another is open), with [context] — what the person did on the table since Ai last read, its seat
     * and knowledge — in front, where the model reads it and the chat does not show it. The person's
     * moves never reach Ai except through a cue like this. Returns the conversation's id, or null when
     * nothing was sent (no connection, or Ai still answering).
     */
    fun sendDuel(
        words: String,
        context: List<String>,
        sessionId: String?,
        fresh: Boolean = false,
        /**
         * Ai's guide at the table (Phase C stage 2): its key — the decks it is for — and the block, made only when the
         * conversation has not been given that key yet, so the guide is read once a conversation, not every cue.
         */
        guide: Pair<String, () -> String>? = null,
        /** The person's own words, not a cue: the answer is coaching, and is checked (F.2). */
        asked: Boolean = false,
    ): String? {
        val text = words.trim()
        if (text.isEmpty() || running) return null
        duelAsked = asked
        val connection = prefs.connection ?: run {
            openWizard()
            return null
        }
        problem = null
        status = null
        notice = null
        val current = (if (fresh) null else session?.takeIf { it.connection == connection.id && it.mode == AiSession.MODE_DUEL && (sessionId == null || it.id == sessionId) })
            ?: sessionId?.takeUnless { fresh }?.let { id -> stored(id)?.takeIf { it.connection == connection.id && it.mode == AiSession.MODE_DUEL } }?.also { session = it }
            ?: begin(connection, AiSession.MODE_DUEL)
        val guideBlock = guide?.takeIf { it.first != current.guideShown }?.second?.invoke()?.takeIf { it.isNotBlank() }
        val block = PromptBuilder.context(listOfNotNull(guideBlock) + context + host.situation(), null, null, false)
        val turn = ChatTurn.user(text, block, System.currentTimeMillis())
        val next = current.copy(turns = current.turns + turn, updatedAt = System.currentTimeMillis(), guideShown = guide?.first ?: current.guideShown).titled()
        commit(next)
        // An answer that never started is no cue (1.0.85): the turn is asked again once the connection works.
        if (!respond(next, connection)) return null
        return next.id
    }

    /**
     * Passes that run with no one watching — the reflection after a conversation, the fact-check (1.0.98, the red team):
     * Forget everything and turning Ai off cancel them, or they write memory back after it was cleared.
     */
    internal val backgroundJobs = mutableListOf<Job>()

    /** Test scores (1.0.99; Trust in the code): its dialog, the run under way (set, done, of), a word when a run could not start, and a count of runs kept. */
    var trustOpen by mutableStateOf(false)
    /** The sets opened out to their details and misses in the Test scores table (the design review, finding 12); none at first. */
    var trustExpanded by mutableStateOf<Set<String>>(emptySet())
    /** The set whose Run is asking first what it will spend, in tokens and money (finding 4); null when none is. */
    var trustAsking by mutableStateOf<String?>(null)
    var evalProgress by mutableStateOf<Triple<String, Int, Int>?>(null)
    var evalNote by mutableStateOf<String?>(null)
    var evalVersion by mutableStateOf(0)

    /** Bumped when a proposal is kept, applied or put aside (Phase G, G.9), so its card reads its state again. */
    var proposalsRevision by mutableIntStateOf(0)
    internal var evalJob: Job? = null

    /** The guide's stale numbers are being computed again (1.0.98). */
    internal var rechecking = false

    internal fun cancelBackground() {
        backgroundJobs.forEach { it.cancel() }
        backgroundJobs.clear()
    }

    /** The run under way's token; 0 when none is (1.0.98). */
    internal var runToken = 0
    internal var finishedToken = 0

    /** What the run's tools answered so far, by call id, and the call running now: kept for a Stop mid-round. */
    internal var ranResults: Map<String, Part.ToolResult> = emptyMap()
    internal var runningCall: String? = null

    /**
     * Stops the answer under way and ends its run now (1.0.98, the red team): what follows a Stop — a new conversation,
     * Fine Tuning's first message — finds Ai idle, instead of racing the cancelled run's end.
     */
    fun stop() {
        val j = job ?: return
        stopping = true
        j.cancel()
        finish(runToken, session?.id.orEmpty())
    }

    /**
     * Every model round's tokens since the app started, all conversations together (Phase D step 2): an effects session
     * reads what a card cost as the difference between two of these (`FxMeter`). Never stored.
     */
    internal var spent: Usage = Usage()

    /** A new conversation, with the memory as it stands now. */
    fun newChat(mode: String = AiSession.MODE_CHAT) {
        stop()
        settleTuning()
        todos = emptyList()
        session?.let(::reflect)
        problem = null
        val connection = prefs.connection
        session = if (connection != null) begin(connection, mode) else null
        historyOpen = false
    }

    /**
     * Build with Ai on Present (1.0.71): a fresh Present conversation, opened with what the
     * launcher learned, so the deck-profile skill begins with the answers it would ask for.
     */
    /**
     * Ai World (1.0.97): a question answered by experiment — a fresh world conversation, opened with the person's words,
     * worked in the World where they watch.
     */
    fun startWorld(question: String) {
        setOpen(true)
        if (prefs.connection == null) return
        newChat(AiSession.MODE_WORLD)
        send(question.trim())
    }

    fun buildPresentation(brief: PresentBrief) {
        setOpen(true)
        if (prefs.connection == null) return
        newChat(AiSession.MODE_PRESENT)
        send(brief.message())
    }

    /**
     * Restyle on Present (1.0.72): a fresh restyle conversation opened with the person's words, and
     * the picture they attached if any — the look changes, the content does not.
     */
    fun restyle(brief: RestyleBrief) {
        setOpen(true)
        if (prefs.connection == null) return
        newChat(AiSession.MODE_RESTYLE)
        send(brief.message())
    }

    // ---- a long conversation (1.0.47) --------------------------------------------

    /** The Context panel is open (1.0.56). */
    var contextOpen by mutableStateOf(false)

    /** A summary asked for (by Ai's `compact`, or while it was answering), made once the answer is done. */
    internal var pendingCompact: String? = null

    // ---- learning (phase 3) ----------------------------------------------------

    /** What Fine Tuning or a reflection changed in memory, waiting on the person's Keep or Undo. */
    var review by mutableStateOf<List<MemoryChange>?>(null)
        internal set
    internal var reviewBefore: Map<String, String?> = emptyMap()

    /** The Fine Tuning launcher is open: which way, and how hard (1.0.48). */
    var tuneAsk by mutableStateOf(false)

    /** The way the launcher opens on, when something asked for one (Refactor, from the guide). */
    var tuneMode by mutableStateOf<String?>(null)

    /** Bumped when the open deck's book is written: the reader reads it again. */
    var bookVersion by mutableStateOf(0)
        internal set

    /** Where the reader was in each deck's book: kept while the app runs (the boxes ticked are the person's marks, H.6). */
    val bookPlaces = HashMap<String, Int>()

    /** The guide's size at the start of a Fine Tuning run, the run's room and its intensity's name; null outside one. */
    internal var guideStart: Triple<Int, Int, String>? = null

    /** What the person is told when this run ends, once it used all of its room in the guide (1.1.11); null while it has room. */
    internal var guideFilled: String? = null

    /** Learn About You's launcher is open. */
    var profileAsk by mutableStateOf(false)

    /** Finish asked Ai to file its report; the session ends when that answer does. */
    internal var wrapping = false

    /** The report Ai filed in this session, when it did (the host sets it). */
    var lastReport by mutableStateOf<SessionReport?>(null)

    /** The report shown at the session's end, beside what it learned (1.0.54). */
    var endReport by mutableStateOf<SessionReport?>(null)

    /** Library decks by id, for naming their guides and notes in the brain. */
    var deckNames by mutableStateOf<Map<String, String>>(emptyMap())

    /** Quick settings, from the model's name in the panel's head (1.0.54). */
    var quickOpen by mutableStateOf(false)

    /** The living document open over the page: a deck's guide or the person's profile (1.0.54). */
    var docOpen by mutableStateOf<LivingDoc?>(null)

    internal var tuneBefore: Map<String, String?>? = null

    /** Study a course: a guide someone wrote, studied unattended in the person's browser (made when first asked for). */
    val courses by lazy { com.kaiharimoto.neue.ai.course.CourseStudies(this) }

    /** The exam on a course's held-out replays (mastery's measure): sat on the person's word, its results kept per deck. */
    val exams by lazy { com.kaiharimoto.neue.ai.course.CourseExams(this) }

    /** Every DuelingBook replay kept on this computer, the courses' and the person's own (1.1.51). */
    val replays by lazy { com.kaiharimoto.neue.ai.course.ReplayShelf(this) }

    /** A conversation put on screen as it is, unsaved: the studio's pictures of the panel. */
    fun preview(sample: AiSession) {
        session = sample
    }

    fun open(id: String) {
        if (running) return
        if (session?.id != id) settleTuning()
        session?.takeIf { it.id != id }?.let(::reflect)
        stored(id)?.let {
            session = it
            problem = null
        }
        historyOpen = false
    }

    fun delete(id: String) {
        saves.drop(id)
        files.deleteSession(id)
        // A save already on its way would bring the file back: the deletion goes in after it.
        if (saves.has(id)) saves.put(id, null)
        if (session?.id == id) session = null
        sessionsRevision++
    }

    /** A new conversation; [query], the person's first words, says which of Ai's own notes are most relevant (1.1.9). */
    internal fun begin(connection: AiConnection, mode: String = AiSession.MODE_CHAT, query: String = ""): AiSession {
        val now = System.currentTimeMillis()
        val s = AiSession(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            connection = connection.id,
            system = systemPrompt(connection, mode, query),
            mode = mode,
            voiceShown = voiceNow,
        )
        session = s
        return s
    }

    /** The instructions a conversation starts with, frozen for its length. */
    fun systemPrompt(connection: AiConnection?, mode: String = AiSession.MODE_CHAT, query: String = ""): String {
        val wire = connection?.let { Providers.byId(it.provider)?.wire }
        return PromptBuilder.system(
            PromptBuilder.Setup(
                name = name,
                soul = files.soul(name),
                userMemory = files.entries(MemoryKind.USER),
                // Ai's own notes have no cap (1.1.11): the prompt holds what fits their room on this connection's model.
                agentMemory = promptMemory(MemoryKind.AGENT, null, "agent", query, on = connection),
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
                voice = ChessyVoice.section(voiceNow),
            ),
        )
    }

    /** Who answers, and how: Ai, Chessy, or Chessy with cat mode (`/catmode`). */
    internal val voiceNow: String get() = ChessyVoice.key(prefs.persona, prefs.catMode)

    fun skills(): List<Skill> = Skills.merge(BuiltInSkills.upTo(PHASE), files.ownSkills())

    internal fun commit(next: AiSession) {
        session = next
        save(next)
    }

    /**
     * Conversations saved one at a time, each one's newest last (1.0.92): saves launched side by side
     * could land out of order, an older turn written over a newer one. A null is a deletion, in turn.
     */
    private val saves = LatestWrites<String, AiSession?>(scope, Dispatchers.IO) { id, s -> if (s == null) files.deleteSession(id) else files.saveSession(s) }

    /** Saves [s] behind whatever is being saved now. */
    internal fun save(s: AiSession) = saves.put(s.id, s)

    /** A saved conversation, the newest copy: one still on its way to the disk before the file. */
    internal fun stored(id: String): AiSession? = if (saves.has(id)) saves.pending(id) else files.loadSession(id)

    /** Nothing waiting to be saved is saved after all (everything is being forgotten). */
    internal fun forgetSaves() = saves.dropAll()

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

    /**
     * A question that ended before its answer was sent — the turn stopped, a connection dropped —
     * gives what was typed to the message box, so none of it is lost (1.0.63).
     */
    internal fun keepUnsent(q: Question) {
        if (q.answer.isCompleted) return
        val unsent = (q.picked + listOfNotNull(q.typed.trim().takeIf { it.isNotEmpty() })).joinToString("; ")
        if (unsent.isBlank()) return
        draft = listOf(draft.trim(), unsent).filter { it.isNotEmpty() }.joinToString("\n")
        notice = "Your answer was not sent before the question closed: it is in the box below, to send."
        focusTick++
    }

    suspend fun ask(q: Question): String {
        question = q
        // At the duel table the question stands in the log's foot, not the side panel (1.0.80).
        if (session?.mode != AiSession.MODE_DUEL || h.neue.page != com.kaiharimoto.neue.Page.DUEL) h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
        return try {
            q.answer.await()
        } finally {
            keepUnsent(q)
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

    /** A rename the conversation on screen has not been told of yet. */
    internal var renamedTo: String? = null

    init {
        // While it works, on a phone or a tablet, a foreground service keeps the app's process and
        // its connection alive out of sight (1.0.61): Android freezes a background app within
        // seconds, and some phones cut its network, which ended the answer mid-sentence.
        scope.launch {
            androidx.compose.runtime.snapshotFlow { (running || checking) to (working ?: status) }
                .collect { (on, doing) ->
                    val (title, line) = WorkNotice.working(name, doing)
                    Platform.working(on, title, line)
                }
        }
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
