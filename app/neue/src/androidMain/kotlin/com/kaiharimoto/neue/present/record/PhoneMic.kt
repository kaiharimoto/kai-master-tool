package com.kaiharimoto.neue.present.record

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.async.thread
import com.kaiharimoto.mastertool.core.present.record.WavWriter
import com.kaiharimoto.neue.platform.LiveMic
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * A microphone on a phone or tablet (I.9), through `AudioRecord`: 48 kHz mono 16-bit (else 44.1 kHz), read on a thread of
 * its own — its level for the meter, and while a take records its sound written into a WAV ([WavWriter], the desk's own),
 * none of it while paused, so the sound keeps the take's clock. [record] null is the silent microphone (the synthetic
 * camera's): silence at the clock's pace. A write that fails ends the take's sound and says so ([error]); the meter
 * carries on.
 */
internal class PhoneMic(override val name: String, private val record: AudioRecord?, private val rate: Int) : LiveMic {
    private val levelState = mutableFloatStateOf(0f)
    override val level: State<Float> get() = levelState
    private val errorState = mutableStateOf<String?>(null)
    override val error: State<String?> get() = errorState

    @Volatile private var running = true
    private var writer: WavWriter? = null
    private var paused: () -> Boolean = { true }
    private val lock = Any()

    private val thread = Background.thread("the microphone") {
        try {
            loop()
        } catch (e: Exception) {
            // A recorder stopped under its read is the microphone let go of; anything else is said, and written down.
            if (running) {
                errorState.value = "The microphone stopped: ${e.message ?: e::class.simpleName}"
                throw e
            }
        }
    }

    private fun loop() {
        val record = record
        if (record == null) {
            var last = System.nanoTime()
            while (running) {
                Thread.sleep(20)
                val now = System.nanoTime()
                val frames = (now - last) * rate / 1_000_000_000L
                last += frames * 1_000_000_000L / rate
                keep { it.silence(frames) }
            }
            return
        }
        val buffer = ByteArray(rate / 25 * 2)
        record.startRecording()
        while (running) {
            val n = record.read(buffer, 0, buffer.size)
            if (n < 0) {
                if (running) errorState.value = "The microphone stopped (${n})"
                break
            }
            if (n == 0) continue
            levelState.floatValue = rms(buffer, n)
            keep { it.write(buffer, n) }
        }
        runCatching { record.stop() }
    }

    /** [write] into the take's file while it records and is not paused; a write that fails ends the take's sound. */
    private fun keep(write: (WavWriter) -> Unit) {
        synchronized(lock) {
            val w = writer?.takeIf { !paused() } ?: return
            try {
                write(w)
            } catch (e: Exception) {
                errorState.value = "The sound's file: ${e.message ?: e::class.simpleName}"
                writer = null
                w.close()
            }
        }
    }

    private fun rms(b: ByteArray, n: Int): Float {
        val s = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var count = 0
        while (s.hasRemaining()) {
            val v = s.get() / 32768.0
            sum += v * v
            count++
        }
        // A voice at a normal distance sits round 0.05–0.2 RMS: stretched so it fills the meter, as on the desk.
        return if (count == 0) 0f else (sqrt(sum / count) * 4.0).toFloat().coerceIn(0f, 1f)
    }

    override fun startRecording(file: File, paused: () -> Boolean): Boolean = synchronized(lock) {
        runCatching {
            writer?.close()
            this.paused = paused
            writer = WavWriter(file, rate, 1)
            true
        }.getOrDefault(false)
    }

    override fun stopRecording() {
        val w = synchronized(lock) { writer.also { writer = null } }
        w?.close()
    }

    override fun close() {
        stopRecording()
        running = false
        // The read returns once the recorder stops; it is released only after the thread has let go of it.
        runCatching { record?.stop() }
        thread.join(1_000)
        runCatching { record?.release() }
    }

    companion object {
        const val SILENCE = "Silence"
        const val BUILT_IN = "This device's microphone"

        /** The rates tried, best first: the video's own 48 kHz, then CD's. */
        private val RATES = listOf(48_000, 44_100)

        /** A microphone plugged in that can record a take: a headset, or one on USB. Bluetooth's needs a call's link, so it is left out. */
        private fun recordable(type: Int) = type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET

        /** The microphones here by name, the device's own first, then any plugged in. */
        fun inputs(context: Context): List<Pair<String, AudioDeviceInfo?>> {
            val manager = context.getSystemService(AudioManager::class.java)
            val plugged = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
                .filter { recordable(it.type) }
                .map { d ->
                    val kind = if (d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) "headset" else "USB"
                    "${d.productName?.toString()?.trim().orEmpty().ifBlank { "Microphone" }} · $kind" to d
                }
                .distinctBy { it.first }
            return listOf<Pair<String, AudioDeviceInfo?>>(BUILT_IN to null) + plugged
        }

        /** [name]'s microphone (else the device's own) opened, or null when no rate opens. RECORD_AUDIO is held already. */
        @SuppressLint("MissingPermission")
        fun open(context: Context, name: String?): PhoneMic? {
            val (chosen, device) = inputs(context).firstOrNull { it.first == name } ?: (BUILT_IN to null)
            for (rate in RATES) {
                val least = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (least <= 0) continue
                val record = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(least * 2, rate / 5 * 2))
                }.getOrNull() ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                device?.let { runCatching { record.setPreferredDevice(it) } }
                return PhoneMic(chosen, record, rate)
            }
            return null
        }

        fun silent(): PhoneMic = PhoneMic(SILENCE, null, RATES.first())
    }
}
