package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import kotlinx.coroutines.launch

// Voice (1.0.57), on [AiState]: the microphone into the composer, talk mode, replies said aloud, and the speech
// model's download (the duel's push-to-talk asks for it too). The state it moves is kept on [AiState].

val AiState.voiceModel: com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
    get() = com.kaiharimoto.mastertool.core.ai.voice.VoiceModel.of(prefs.voiceModel)

/** The words a transcriber is primed with: the open deck's cards, and the game's. */
private fun AiState.hints(): String {
    val index = h.builder.index
    val deck = h.builder.deck
    val names = (deck.main + deck.extra + deck.side).distinct().mapNotNull { index.byId(it)?.name }
    return com.kaiharimoto.mastertool.core.ai.voice.Hints.prompt(names)
}

/** The microphone on or off: the words go into the composer, to read over before sending. */
fun AiState.toggleVoice() {
    if (hearing || transcribing) com.kaiharimoto.neue.platform.Voice.stopListening() else listen(send = false)
}

/**
 * Listening (1.0.57): the level as it goes, the words into the draft — after whatever was
 * already written — and in talk mode sent as soon as they are written out.
 */
fun AiState.listen(send: Boolean) {
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
    // One microphone (1.0.87): the duel's push-to-talk, if it listens, is stopped first; holding M later takes it back.
    voiceJob = com.kaiharimoto.neue.platform.Mic.listen(scope, "ai", onLost = { micTaken() }) {
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

/** The download dialog, for Ai's voice or ([forDuel]) the duel's commands. */
fun AiState.askVoiceModel(forDuel: Boolean) {
    if (voiceDownload != null) return
    voiceForDuel = forDuel
    voiceAsk = true
}

/** The model downloaded for the duel's commands with no dialog: the setup's "Duel by keys and voice" step said yes. */
fun AiState.downloadForDuel() {
    if (voiceDownload != null) return
    voiceForDuel = true
    downloadVoiceModel()
}

/**
 * Someone else took the microphone (1.0.87, the duel's M held): talk mode would only listen again over them,
 * so it ends, and anything being said aloud stops.
 */
fun AiState.micTaken() {
    if (talkMode) {
        talkMode = false
        notice = "Talk mode ended: the duel's microphone is in use."
    }
    stopSpeaking()
}

/** The speech model, downloaded once with the person's yes; then the microphone opens (not for the duel: see [voiceForDuel]). */
fun AiState.downloadVoiceModel() {
    voiceAsk = false
    val model = voiceModel
    val forDuel = voiceForDuel
    voiceForDuel = false
    voiceDownload = 0f
    scope.launch {
        val done = com.kaiharimoto.neue.platform.Voice.download(model) { bytes -> voiceDownload = (bytes.toFloat() / model.bytes).coerceIn(0f, 1f) }
        voiceDownload = null
        done.fold(
            {
                if (forDuel) {
                    h.neue.note = com.kaiharimoto.neue.Note("Voice is ready. Hold M, or the microphone, to speak a command")
                    h.duelVoice.prewarm()
                } else {
                    listen(send = talkMode)
                }
            },
            {
                talkMode = false
                h.neue.note = com.kaiharimoto.neue.Note("The speech model could not be downloaded: ${it.message ?: "try again"}")
            },
        )
    }
}

/** Talk mode on or off (1.0.57): a conversation out loud. */
fun AiState.toggleTalk() {
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

fun AiState.endTalk() {
    talkMode = false
    com.kaiharimoto.neue.platform.Voice.stopListening()
    stopSpeaking()
}

fun AiState.stopSpeaking() {
    speakJob?.cancel()
    speakJob = null
    com.kaiharimoto.neue.platform.Voice.stopSpeaking()
    aloud = false
}

/** In talk mode, after an answer: say it, then listen again. */
internal fun AiState.answerAloud() {
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

/** The studio's pictures of voice (1.0.57): listening at a level, talk mode, speaking aloud. */
fun AiState.previewVoice(listening: Boolean, level: Float, talk: Boolean, speaking: Boolean) {
    hearing = listening
    voiceLevel = level
    talkMode = talk
    aloud = speaking
}
