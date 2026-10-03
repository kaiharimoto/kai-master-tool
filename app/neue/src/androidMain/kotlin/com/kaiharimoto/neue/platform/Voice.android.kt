package com.kaiharimoto.neue.platform

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kaiharimoto.mastertool.core.ai.voice.CommandClip
import com.kaiharimoto.mastertool.core.ai.voice.Hints
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * A phone's or a tablet's voice (1.0.57): the system's own recogniser — on the device where it
 * has one (Android 12 and later), the words live as they come — and its text-to-speech. No model
 * to download; the microphone is asked for the first time.
 */
actual object Voice {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    actual val canListen: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(Platform.context) }.getOrDefault(false)

    actual val usesModels: Boolean = false

    actual fun needsModel(model: VoiceModel): Boolean = false

    actual suspend fun download(model: VoiceModel, progress: (Long) -> Unit): Result<Unit> = Result.success(Unit)

    /** One listening's own stop (1.0.87, the red team): made as [listen] is called, so a stop before the recogniser exists is kept. */
    private class Ticket {
        @Volatile
        var stopped = false

        @Volatile
        var recognizer: SpeechRecognizer? = null
    }

    @Volatile
    private var current: Ticket? = null

    actual fun listen(model: VoiceModel, hints: String, command: Boolean): Flow<Heard> {
        val ticket = Ticket()
        current?.let { stop(it) }
        current = ticket
        return heard(hints, command, ticket)
    }

    private fun heard(hints: String, command: Boolean, ticket: Ticket): Flow<Heard> = callbackFlow {
        val allowed = Platform.permission?.invoke(Manifest.permission.RECORD_AUDIO) ?: false
        if (!allowed) {
            trySend(Heard.Failed("The microphone was not allowed. Allow it in the app's settings to speak " + (if (command) "commands." else "to Ai.")))
            close()
            return@callbackFlow
        }
        // Let go while the permission was asked: nothing was said.
        if (ticket.stopped) {
            trySend(Heard.Failed("Nothing was said."))
            close()
            return@callbackFlow
        }
        val context = Platform.context
        val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        ticket.recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Android's level is in decibels, about −2 to 10: brought to the desk's 0–1.
                trySend(Heard.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f) * 0.3f))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                trySend(Heard.Transcribing)
            }
            override fun onError(error: Int) {
                trySend(
                    Heard.Failed(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was said."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone was not allowed."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The recogniser needs the network, and could not reach it."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy; try again."
                            else -> "The recogniser stopped ($error)."
                        },
                    ),
                )
                close()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val nothing = if (command) CommandClip.isNothing(text) else Hints.isNothing(text)
                trySend(if (nothing) Heard.Failed("Nothing was said.") else Heard.Final(if (command) CommandClip.unrepeat(text) else text))
                close()
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { trySend(Heard.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // The deck's card names, so they come out spelled right (Android 13).
            if (Build.VERSION.SDK_INT >= 33 && hints.isNotBlank()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.split(", ").take(100)))
            }
            // A duel command (1.0.87) is push-to-talk: the finger's lift ends it, so a pause mid-command must not
            // (recognisers that honour these); and it wants the answer now, from the device where it can.
            if (command) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        r.startListening(intent)
        // Let go before the recogniser was there to tell: it is told now.
        if (ticket.stopped) runCatching { r.stopListening() }
        awaitClose {
            runCatching { r.destroy() }
            if (ticket.recognizer === r) ticket.recognizer = null
        }
    }.flowOn(Dispatchers.Main)

    actual fun stopListening() {
        current?.let { stop(it) }
    }

    private fun stop(t: Ticket) {
        t.stopped = true
        t.recognizer?.let { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { r.stopListening() } } }
    }

    /** The system's recogniser has nothing of ours to load. */
    actual suspend fun prewarm(model: VoiceModel) = Unit

    actual val canSpeak: Boolean = true

    private suspend fun engine(): TextToSpeech? = withContext(Dispatchers.Main) {
        tts?.takeIf { ttsReady } ?: suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var made: TextToSpeech? = null
            made = TextToSpeech(Platform.context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts = made
                if (cont.isActive) cont.resume(made?.takeIf { ttsReady })
            }
        }
    }

    actual suspend fun speak(text: String, rate: Float) {
        val engine = engine() ?: return
        suspendCancellableCoroutine { cont ->
            val id = "neue-" + System.nanoTime()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { runCatching { engine.stop() } }
        }
    }

    actual fun stopSpeaking() {
        runCatching { tts?.stop() }
    }
}
