package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.core.ai.voice.CommandClip
import com.kaiharimoto.mastertool.core.ai.voice.CommandTuning
import com.kaiharimoto.mastertool.core.ai.voice.Hints
import com.kaiharimoto.mastertool.core.ai.voice.Pcm
import com.kaiharimoto.mastertool.core.ai.voice.SpeechGate
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.mastertool.core.update.DesktopOs
import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The desk's voice (1.0.57, kai chose it on-device): the microphone through Java Sound at 16 kHz
 * mono, the end of speech found by [SpeechGate], and the words written out by Whisper
 * (whisper.cpp, through whisper-jni's bundled natives for Windows, macOS and Linux) on this
 * computer — nothing heard leaves it. The model is a file under `<data>/voice`, downloaded once
 * from whisper.cpp's own repository and checked by its SHA-256.
 */
actual object Voice {
    private val format = AudioFormat(Pcm.RATE.toFloat(), 16, 1, true, false)

    @Volatile
    private var speaking: Process? = null

    actual val canListen: Boolean
        get() = runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format)) }.getOrDefault(false)

    actual val usesModels: Boolean = true

    private fun fileOf(model: VoiceModel) = File(Platform.dataDir, "voice/${model.file}")

    actual fun needsModel(model: VoiceModel): Boolean = fileOf(model).let { !it.isFile || it.length() != model.bytes }

    actual suspend fun download(model: VoiceModel, progress: (Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = fileOf(model)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
            val response = http.send(HttpRequest.newBuilder(URI(model.url)).header("User-Agent", "NeueMasterTool").build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) error("The download was refused (${response.statusCode()}).")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            response.body().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (!currentCoroutineContext().isActive) error("Stopped.")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        progress(done)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != model.sha256) {
                part.delete()
                error("The download came out damaged; try again.")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("The model could not be put in place.")
        }
    }

    // ---- Whisper, loaded once --------------------------------------------------

    private var whisper: WhisperJNI? = null
    private var context: Pair<File, WhisperContext>? = null

    @Synchronized
    private fun contextFor(file: File): Pair<WhisperJNI, WhisperContext> {
        val w = whisper ?: run {
            WhisperJNI.loadLibrary()
            WhisperJNI.setLibraryLogger(null)
            WhisperJNI().also { whisper = it }
        }
        context?.let { (f, c) -> if (f == file) return w to c else w.free(c) }
        val c = w.init(file.toPath())
        context = file to c
        return w to c
    }

    private fun transcribe(model: VoiceModel, samples: FloatArray, hints: String, command: Boolean): String =
        transcribe(fileOf(model), model == VoiceModel.BASE, samples, hints, command)

    /**
     * [samples] (16 kHz mono) written out by the model in [file]; `internal` so a test can hand it any model.
     * A [command] (1.0.87) is a clip of a few seconds wanted at once: one segment, no carried context, and
     * the encoder told the clip's length (`CommandTuning`) rather than reading Whisper's whole 30 s window.
     */
    @Synchronized
    internal fun transcribe(file: File, anyLanguage: Boolean, samples: FloatArray, hints: String, command: Boolean = false): String {
        val (w, ctx) = contextFor(file)
        val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
            nThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            if (hints.isNotBlank()) initialPrompt = hints
            if (anyLanguage) detectLanguage = true
            suppressNonSpeechTokens = true
            noTimestamps = true
            if (command) {
                singleSegment = true
                noContext = true
                audioCtx = CommandTuning.audioCtx(samples.size.toDouble() / Pcm.RATE)
            }
        }
        val result = w.full(ctx, params, samples, samples.size)
        if (result != 0) error("The speech model could not read it ($result).")
        return (0 until w.fullNSegments(ctx)).joinToString("") { w.fullGetSegmentText(ctx, it) }.trim()
    }

    // ---- listening -----------------------------------------------------------

    actual fun listen(model: VoiceModel, hints: String, command: Boolean): Flow<Heard> {
        // Its own stop, made now — before the flow runs on its thread — so a key let go at once is never lost,
        // and a newer listening stops this one rather than re-arming a shared flag (1.0.87, the red team).
        val ticket = Ticket()
        current?.stopped = true
        current = ticket
        return heard(model, hints, command, ticket)
    }

    private fun heard(model: VoiceModel, hints: String, command: Boolean, ticket: Ticket): Flow<Heard> = flow {
        val line = runCatching {
            (AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine).apply {
                open(format, Pcm.RATE / 5)
                start()
            }
        }.getOrElse {
            emit(Heard.Failed("No microphone could be opened. Check that one is plugged in, and that this app may use it."))
            return@flow
        }
        // A command (1.0.87) is push-to-talk: letting go of the key ends it, never a pause.
        val gate = if (command) SpeechGate.pushToTalk() else SpeechGate()
        val audio = ByteArrayOutputStream()
        val slice = ByteArray(Pcm.RATE / 10 * 2) // 100 ms
        var seconds = 0.0
        var loudest = 0f
        try {
            while (currentCoroutineContext().isActive && !ticket.stopped && !gate.done) {
                val n = runInterruptible { line.read(slice, 0, slice.size) }
                if (n <= 0) continue
                audio.write(slice, 0, n)
                val level = Pcm.rms(Pcm.samples(slice, n))
                loudest = maxOf(loudest, level)
                seconds += n / 2.0 / Pcm.RATE
                gate.feed(level, seconds)
                emit(Heard.Level(level))
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
        if (gate.state == SpeechGate.State.DONE_NOTHING || loudest < SpeechGate.FLOOR) {
            emit(
                Heard.Failed(
                    if (loudest < 0.002f) "The microphone heard nothing at all. It may be muted, or the system may not let this app use it (on Windows: Settings → Privacy → Microphone; on a Mac: System Settings → Privacy & Security → Microphone)."
                    else "Nothing was said.",
                ),
            )
            return@flow
        }
        var samples = Pcm.samples(audio.toByteArray())
        // A command's clip (1.0.87): the key's clicks cut off, and too little voice never handed to Whisper,
        // which invents words for near-silence.
        val voiced = if (command) CommandClip.voiced(CommandClip.trim(samples).also { samples = it }) else Double.MAX_VALUE
        if (voiced < CommandClip.VOICED_LEAST) {
            emit(Heard.Failed("Nothing was said."))
            return@flow
        }
        emit(Heard.Transcribing)
        val written = runCatching { transcribe(model, samples, hints, command) }.getOrElse {
            emit(Heard.Failed("The speech model failed: ${it.message ?: it::class.simpleName}"))
            return@flow
        }
        val text = if (command) CommandClip.unrepeat(written) else written
        val nothing = if (command) CommandClip.isNothing(text) || CommandClip.echoes(text, hints, voiced, DuelSpeech.WORDS) else Hints.isNothing(text)
        if (nothing) emit(Heard.Failed("Nothing was said.")) else emit(Heard.Final(text))
    }.flowOn(Dispatchers.IO)

    /** One listening's stop flag (1.0.87): its own, so listenings never stop or re-arm each other. */
    private class Ticket {
        @Volatile
        var stopped = false
    }

    @Volatile
    private var current: Ticket? = null

    actual fun stopListening() {
        current?.stopped = true
    }

    /**
     * The model read into memory now, off the main thread, so the first command spoken is written out as
     * fast as the tenth (1.0.87: the Duel page opening with voice set up). Half a second of silence is then
     * written out once, so whisper.cpp's own buffers are made too.
     */
    actual suspend fun prewarm(model: VoiceModel) {
        if (needsModel(model)) return
        withContext(Dispatchers.IO) {
            runCatching { transcribe(fileOf(model), model == VoiceModel.BASE, FloatArray(Pcm.RATE / 2), "", command = true) }
        }
    }

    // ---- speaking --------------------------------------------------------------

    private fun which(name: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath

    private val speaker: ((String, Float) -> List<String>)? by lazy {
        when (Platform.os) {
            DesktopOs.MAC -> { text, rate -> listOf("say", "-r", (185 * rate).toInt().toString(), text) }
            DesktopOs.WINDOWS -> { text, rate ->
                // The text goes in base64, so no quote in it can break out of the command.
                val words = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                val speed = ((rate - 1f) * 10f).toInt().coerceIn(-10, 10)
                listOf(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.Rate = $speed; " +
                        "\$s.Speak([System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('$words')))",
                )
            }
            else -> when {
                which("spd-say") != null -> { text, rate -> listOf("spd-say", "--wait", "-r", ((rate - 1f) * 60).toInt().coerceIn(-100, 100).toString(), text) }
                which("espeak-ng") != null -> { text, rate -> listOf("espeak-ng", "-s", (175 * rate).toInt().toString(), text) }
                which("espeak") != null -> { text, rate -> listOf("espeak", "-s", (175 * rate).toInt().toString(), text) }
                else -> null
            }
        }
    }

    actual val canSpeak: Boolean get() = speaker != null

    actual suspend fun speak(text: String, rate: Float) {
        val command = speaker?.invoke(text, rate) ?: return
        withContext(Dispatchers.IO) {
            val process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.getOrNull() ?: return@withContext
            speaking = process
            try {
                runInterruptible { process.inputStream.readAllBytes(); process.waitFor() }
            } finally {
                if (process.isAlive) process.destroy()
                if (speaking === process) speaking = null
            }
        }
    }

    actual fun stopSpeaking() {
        speaking?.let { p -> runCatching { p.descendants().forEach { it.destroy() }; p.destroy() } }
        speaking = null
    }
}
