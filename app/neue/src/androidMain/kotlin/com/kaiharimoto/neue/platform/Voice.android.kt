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
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    actual val canListen: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(Platform.context) }.getOrDefault(false)

    actual val usesModels: Boolean = false

    actual fun needsModel(model: VoiceModel): Boolean = false

    actual suspend fun download(model: VoiceModel, progress: (Long) -> Unit): Result<Unit> = Result.success(Unit)

    actual fun listen(model: VoiceModel, hints: String): Flow<Heard> = callbackFlow {
        val allowed = Platform.permission?.invoke(Manifest.permission.RECORD_AUDIO) ?: false
        if (!allowed) {
            trySend(Heard.Failed("The microphone was not allowed. Allow it in the app's settings to speak to Ai."))
            close()
            return@callbackFlow
        }
        val context = Platform.context
        val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
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
                trySend(if (Hints.isNothing(text)) Heard.Failed("Nothing was said.") else Heard.Final(text))
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
        }
        r.startListening(intent)
        awaitClose {
            runCatching { r.destroy() }
            if (recognizer === r) recognizer = null
        }
    }.flowOn(Dispatchers.Main)

    actual fun stopListening() {
        recognizer?.let { r -> android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { r.stopListening() } } }
    }

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
