package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import kotlinx.coroutines.flow.Flow

/**
 * What the microphone heard, as it goes (1.0.57): how loud, the words so far where the platform
 * hears them live, that it is being written out, then the words — or why there are none.
 */
sealed interface Heard {
    data class Level(val rms: Float) : Heard
    data class Partial(val text: String) : Heard
    data object Transcribing : Heard
    data class Final(val text: String) : Heard
    data class Failed(val reason: String) : Heard
}

/**
 * Speaking to Ai and hearing it (1.0.57, kai: "enable voice input"; talk mode). Each platform's
 * own: on the desk the microphone through Java Sound and Whisper on the computer itself, with a
 * speech model downloaded once; on a phone or tablet the system's recogniser. Replies are spoken
 * by the system's own voice: `say` on a Mac, Windows' speech synthesiser, speech-dispatcher or
 * eSpeak on Linux, Android's text-to-speech.
 */
expect object Voice {
    /** Whether there is a way to listen here at all. */
    val canListen: Boolean

    /** Whether this platform writes speech out with a model of its own, downloaded (the desk), rather than the system's. */
    val usesModels: Boolean

    /** Whether listening needs a speech model downloaded first (the desk), and it is not there yet. */
    fun needsModel(model: VoiceModel): Boolean

    /** [model] downloaded and checked, [progress] told the bytes so far; an error in words. */
    suspend fun download(model: VoiceModel, progress: (Long) -> Unit): Result<Unit>

    /**
     * Listening, until the person stops speaking (or [stopListening]): the level as it goes, the
     * words when they are ready. [hints] prime the transcriber with the words likely to be said.
     */
    fun listen(model: VoiceModel, hints: String): Flow<Heard>

    /** Ends the listening now; what was heard so far is still written out. */
    fun stopListening()

    /** Whether replies can be spoken aloud here. */
    val canSpeak: Boolean

    /** [text] spoken aloud at [rate] (1 is the voice's own pace); returns when it is done or stopped. */
    suspend fun speak(text: String, rate: Float)

    fun stopSpeaking()
}
