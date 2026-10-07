package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.neue.ai.voiceModel
import com.kaiharimoto.neue.ai.askVoiceModel
import com.kaiharimoto.neue.ai.micTaken
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.voice.Hints
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MuIcon
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.platform.Heard
import com.kaiharimoto.neue.platform.Mic
import com.kaiharimoto.neue.platform.Voice
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Command mode's voice (1.0.87, kai: "hold a key to talk", and "show, then confirm"): hold **M** — or the
 * microphone beside the command line, by finger or mouse — and speak; let go and what was said is written
 * out at once (no pause waited for: `SpeechGate.pushToTalk`, and on the desk Whisper tuned for a short clip,
 * `CommandTuning`). The words go to [onHeard], which shows the move before it is made: Enter (or "yes")
 * commits it. One per window, for the app's lifetime (`NeueHolders.duelVoice`).
 *
 * The microphone is one ([Mic]): holding M while Ai's talk mode listens ends talk mode first, and Ai
 * listening takes it back from here.
 */
class DuelVoice(private val h: NeueHolders) : TableVoice {

    /** Where the microphone is: listening, writing out, what was heard, or why nothing was. */
    override var phase by mutableStateOf(VoicePhase.IDLE)
        private set

    /** How loud the microphone is now, 0–1, for the button's level bar. */
    override var level by mutableStateOf(0f)
        private set

    /** The words so far, where the platform hears them live (a phone's recogniser). */
    override var partial by mutableStateOf("")
        private set

    /** What was heard last, as it was written out. */
    var heard by mutableStateOf<String?>(null)
        private set

    /** Why nothing was heard, the last time. */
    override var failure by mutableStateOf<String?>(null)
        private set

    /** The key or the button is held down now. */
    override var held by mutableStateOf(false)
        private set

    val listening: Boolean get() = phase == VoicePhase.LISTENING
    override val busy: Boolean get() = phase == VoicePhase.LISTENING || phase == VoicePhase.TRANSCRIBING

    /**
     * What is done with the words (the one hook the command language plugs into: `DuelSpeech` into the
     * Line's preview). Since 1.0.87 the Spotlight's (`spotHeard`): normalized, sorted, and a move shown for Enter or "yes".
     *
     * Two rules for whatever replaces it (1.0.87, the red team): a spoken move is only ever shown, never made
     * without a confirm; and the answer to a spoken question ("read my hand") is said or shown to this seat
     * alone — never a Chat or Note action, which the log keeps for both seats. Words never reach Ai's own
     * conversation from here (this is not `AiState.listen`, which sends what it hears).
     */
    override var onHeard: (String) -> Unit = { text -> spotHeard(h.table, text) }

    /** Told when listening begins, so the Line can open in its listening state (the Spotlight: "holding M opens it listening"). */
    override var onListen: () -> Unit = { h.duel.openSpotlight(mode = com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode.LISTENING) }

    /** The words the transcriber is primed with: the table's cards, through the bottom seat's eyes, and the commands. */
    override var hints: () -> String = { tableHints(h) }

    @Composable
    override fun Mic(size: Dp) = DuelMic(h, size)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var speakJob: Job? = null
    private var warmed: VoiceModel? = null

    /** The desk's speech model: the one chosen for Ai's voice too (quick settings, or the setup's step). */
    val model: VoiceModel get() = h.ai.voiceModel

    /** The key went down, or a finger on the button: listen until [release]. Held keys repeat; only the first press counts. */
    fun press() {
        if (held) return
        if (!Voice.canListen) {
            fail("No microphone could be found")
            return
        }
        if (Voice.needsModel(model)) {
            // The model is downloaded once, after asking (Ai's own dialog, in the duel's words).
            h.ai.askVoiceModel(forDuel = true)
            return
        }
        held = true
        // Talk mode would listen again over this, and the duel's own reading aloud would be heard.
        h.ai.micTaken()
        speakJob?.cancel()
        Voice.stopSpeaking()
        // A press while the last words are still being written out drops them: the newer utterance wins.
        job?.cancel()
        val turn = ++turns
        fun mine() = turn == turns
        phase = VoicePhase.LISTENING
        level = 0f
        partial = ""
        failure = null
        onListen()
        job = Mic.listen(scope, OWNER, onLost = { if (mine()) lost() }) {
            try {
                val heardFlow = Voice.listen(model, hints(), command = true)
                // Let go already, while the microphone changed hands: this listening's own stop is set now.
                if (!held) Voice.stopListening()
                heardFlow.collect { e ->
                    if (mine()) {
                        when (e) {
                            is Heard.Level -> {
                                level = e.rms
                                // The release is said again on every slice, so a stop is never lost.
                                if (!held) Voice.stopListening()
                            }
                            is Heard.Partial -> partial = e.text
                            Heard.Transcribing -> phase = VoicePhase.TRANSCRIBING
                            is Heard.Final -> {
                                heard = e.text
                                phase = VoicePhase.HEARD
                                onHeard(e.text)
                            }
                            is Heard.Failed -> fail(words(e.reason))
                        }
                    }
                }
            } finally {
                if (mine()) {
                    level = 0f
                    held = false
                    if (phase == VoicePhase.LISTENING || phase == VoicePhase.TRANSCRIBING) phase = VoicePhase.IDLE
                }
            }
        }
    }

    /** Each press's number: a listening replaced by a newer one changes nothing on screen. */
    private var turns = 0

    /** The key or the finger let go: what was said is written out now. */
    fun release() {
        if (!held) return
        held = false
        if (phase == VoicePhase.LISTENING && Mic.owner == OWNER) Voice.stopListening()
    }

    /**
     * The Spotlight closed while listening or writing out (Esc, a press outside): this listening ends and its words,
     * when they come, are dropped — never made after the person said no (the red team).
     */
    override fun cancel() {
        if (!busy) return
        turns++
        job?.cancel()
        job = null
        held = false
        level = 0f
        partial = ""
        phase = VoicePhase.IDLE
        if (Mic.owner == OWNER) Voice.stopListening()
    }

    /** From the palette or a menu, where there is no letting go: a press, and the next one sends. */
    fun toggle() {
        if (held || listening) release() else press()
    }

    /** Ai took the microphone back (its own voice, or talk mode): this listening ends without words. */
    private fun lost() {
        held = false
        phase = VoicePhase.IDLE
        level = 0f
    }

    private fun fail(reason: String) {
        failure = reason
        phase = VoicePhase.FAILED
        h.neue.note = Note(reason.removeSuffix("."))
    }

    /** A failure in the duel's words: "nothing was said" usually means the key was let go too soon. */
    private fun words(reason: String): String =
        if (reason.startsWith("Nothing was said")) "Nothing heard. Hold to speak, and let go when you are done." else reason

    /**
     * The model read in before the first command, off the main thread: the Duel page opening with voice set up.
     * Once per model; nothing on a phone or tablet, whose recogniser is the system's.
     */
    fun prewarm() {
        val m = model
        if (!Voice.usesModels || warmed == m || Voice.needsModel(m)) return
        warmed = m
        scope.launch { Voice.prewarm(m) }
    }

    /**
     * [text] said aloud (1.0.87, `DuelPrefs.speak`, off unless asked for): the move understood, the other seat's
     * moves as they land, the answer to a question — never anything this seat cannot see; the caller words it
     * through the seat's own eyes. A new line cuts off the one before.
     */
    override fun say(text: String) {
        if (!h.neue.prefs.duel.speak || text.isBlank() || !Voice.canSpeak) return
        speakJob?.cancel()
        speakJob = scope.launch { Voice.speak(text, h.ai.prefs.speechRate) }
    }

    companion object {
        const val OWNER = "duel"
    }
}

/** The words a command is likely to use, besides card names (until `DuelSpeech.hints` stands in). */
private val COMMAND_WORDS = listOf(
    "summon", "set", "activate", "attack", "monster zone", "spell zone", "battle phase", "main phase", "end turn",
    "graveyard", "banish", "hand", "yes", "no response",
)

/** The transcriber's priming from the table in view: what the seat at the bottom can see, its own decklist, the commands. */
internal fun tableHints(h: NeueHolders): String {
    val duels = h.duel
    val g = duels.shown ?: return Hints.prompt(COMMAND_WORDS)
    return Hints.prompt(tableNames(g.state, duels.bottom, duels.catalog) + COMMAND_WORDS)
}

/**
 * The names [viewer] may say, most likely first: their hand, the field, the graveyards and banished cards and
 * their Extra Deck — each only if [viewer] can see it (`DuelSight`) — then their own Main Deck, which its owner
 * knows by heart. Never a card hidden from them; the words never leave this device.
 */
internal fun tableNames(s: DuelState, viewer: Int, catalog: DuelCatalog): List<String> {
    if (viewer !in s.seats.indices) return emptyList()
    val me = s.seat(viewer)
    val them = s.seats.indices.filter { it != viewer }.map(s::seat)
    val seen = me.hand + s.onField() + me.gy + them.flatMap { it.gy } + me.banished + them.flatMap { it.banished } + me.extra
    fun name(uid: Int): String? = s.card(uid)?.let { c -> catalog.info(c.code)?.name ?: c.name?.takeIf { c.token } }
    return (seen.filter { DuelSight.sees(s, it, viewer) } + me.deck).mapNotNull(::name).distinct()
}

/**
 * The microphone beside the command line (1.0.87): held down — by a finger, or the mouse's button — it is
 * the M key, listening while held and sending when let go. While it listens it is ink, with the level as a
 * bar along its foot.
 */
@Composable
internal fun DuelMic(h: NeueHolders, size: Dp = 28.dp) {
    val voice = h.duelVoice
    val c = Mu.colors
    val canListen = remember { Voice.canListen }
    val downloading = h.ai.voiceDownload
    val enabled = canListen && downloading == null
    val on = voice.held || voice.listening
    val tip = when {
        !canListen -> "No microphone could be found"
        downloading != null -> "Downloading the speech model · ${(downloading * 100).toInt()}%"
        voice.listening -> "Listening · let go to send"
        voice.phase == VoicePhase.TRANSCRIBING -> "Writing out what you said"
        else -> "Hold to speak a command"
    }
    Tip(tip, kbd = DeskShortcuts.chordFor(DeskAction.DUEL_VOICE)?.let { "Hold " + DeskShortcuts.kbd(it) }) {
        Box(
            Modifier
                .size(size)
                .alpha(if (enabled) 1f else 0.3f)
                .background(if (on) c.ink else c.paper)
                .border(1.dp, if (on || voice.phase == VoicePhase.TRANSCRIBING) c.ink else c.ink12)
                .cursorPointer(caption = "Hold to speak", enabled = enabled, reason = tip, holdOnPress = true)
                .pointerInput(voice, enabled) {
                    if (enabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            voice.press()
                            try {
                                waitForUpOrCancellation()?.consume()
                            } finally {
                                voice.release()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            MuIcon(Icons.Mic, if (on) c.paper else c.ink70, Modifier.size(if (size >= 36.dp) 16.dp else 14.dp))
            if (on) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 3.dp, vertical = 3.dp).fillMaxWidth().height(2.dp)) {
                    Box(Modifier.fillMaxWidth((voice.level * 6f).coerceIn(0.04f, 1f)).height(2.dp).background(c.paper))
                }
            }
        }
    }
}

/** The Spotlight's hooks on the duel's voice: holding M opens it listening; the words come here; hints from the table. */
internal fun wireSpotlightVoice(h: NeueHolders) {
    val voice = h.duelVoice
    voice.onListen = { h.duel.openSpotlight(mode = Mode.LISTENING) }
    voice.onHeard = { text -> spotHeard(h.table, text) }
    voice.hints = {
        val d = h.duel
        val g = d.shown
        if (g == null) tableHints(h) else DuelSpeech.hints(g.state, d.bottom, d.catalog)
    }
}
