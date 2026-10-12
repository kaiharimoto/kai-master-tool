package com.kaiharimoto.neue.present.record

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.Yuv420
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A take's video made on a phone or tablet (I.9): the device's own H.264 encoder through `MediaCodec`, its AAC for the
 * sound, muxed by `MediaMuxer` into an MP4 — never FFmpeg. Each frame is handed in as ARGB pixels and turned into YUV
 * 4:2:0 here ([Yuv420], BT.709, which the stream says), stamped with its own moment (frame n at n / fps s), so the video's
 * time is the take's however long a frame took to draw. Samples written before both tracks are known are held, then
 * written as the muxer starts. One thread drives it; nothing in it is shared.
 */
internal class PhoneEncoder private constructor(
    val file: File,
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val video: MediaCodec,
    /** The encoder chosen, by the device's name for it ("c2.qti.avc.encoder"), for the log. */
    val encoderName: String,
    private val audio: MediaCodec?,
    private val rate: Int,
    private val channels: Int,
) {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var videoEnded = false
    private var audioEnded = audio == null
    private var samplesIn = 0L
    private var frames = 0

    private val y = ByteArray(width * height)
    private val u = ByteArray(Yuv420.chromaWidth(width) * Yuv420.chromaHeight(height))
    private val v = ByteArray(u.size)

    /** An encoded sample waiting for the muxer to start. */
    private class Held(val video: Boolean, val bytes: ByteArray, val ptsUs: Long, val flags: Int)

    private val held = ArrayList<Held>()

    /** Frame [n]: [argb], [width] × [height], row by row. */
    fun picture(argb: IntArray, n: Int) {
        Yuv420.convert(argb, width, height, y, u, v)
        val index = input(video)
        val pts = n * 1_000_000L / fps
        val image = video.getInputImage(index)
        if (image != null) {
            write(image)
            video.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
        } else {
            val buffer = video.getInputBuffer(index) ?: error("the encoder gave no room for a picture")
            video.queueInputBuffer(index, 0, writePacked(buffer), pts, 0)
        }
        frames = n + 1
        drain(video, isVideo = true, toEnd = false)
        audio?.let { drain(it, isVideo = false, toEnd = false) }
    }

    /** [samples] of the sound, interleaved 16-bit, following on from those before. */
    fun sound(samples: ShortArray) {
        val codec = audio ?: return
        var at = 0
        while (at < samples.size) {
            val index = input(codec)
            val buffer = codec.getInputBuffer(index) ?: error("the sound's encoder gave no room")
            buffer.clear()
            val room = (buffer.remaining() / 2 / channels) * channels
            val n = minOf(samples.size - at, room)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, at, n)
            val pts = samplesIn / channels * 1_000_000L / rate
            codec.queueInputBuffer(index, 0, n * 2, pts, 0)
            samplesIn += n
            at += n
            drain(codec, isVideo = false, toEnd = false)
        }
    }

    /** The streams ended, every sample written and the file closed. */
    fun finish() {
        val endUs = frames * 1_000_000L / fps
        video.queueInputBuffer(input(video), 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        audio?.let { a -> a.queueInputBuffer(input(a), 0, 0, samplesIn / channels * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        while (!videoEnded) drain(video, isVideo = true, toEnd = true)
        audio?.let { a -> while (!audioEnded) drain(a, isVideo = false, toEnd = true) }
        if (!started) {
            // Sound that never said its format (none came) leaves the video alone in the file.
            check(videoTrack >= 0) { "the encoder made no pictures" }
            begin()
        }
        muxer.stop()
        release()
    }

    /** Stopped part way: everything let go of and the half-written file deleted. */
    fun abandon() {
        release()
        file.delete()
    }

    private fun release() {
        runCatching { video.stop() }
        runCatching { video.release() }
        audio?.let { a ->
            runCatching { a.stop() }
            runCatching { a.release() }
        }
        runCatching { muxer.release() }
    }

    /** An input buffer of [codec]'s, its output drained while it has none free (it may be waiting on that). */
    private fun input(codec: MediaCodec): Int {
        val giveUp = System.nanoTime() + STALL_NS
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(video, isVideo = true, toEnd = false)
            audio?.let { drain(it, isVideo = false, toEnd = false) }
            check(System.nanoTime() < giveUp) { "the encoder stopped taking ${if (codec === video) "pictures" else "sound"}" }
        }
    }

    /** What [codec] has made, written (or held); with [toEnd] it waits a moment for more, until its end. */
    private fun drain(codec: MediaCodec, isVideo: Boolean, toEnd: Boolean) {
        val giveUp = System.nanoTime() + STALL_NS
        while (true) {
            if (if (isVideo) videoEnded else audioEnded) return
            val index = codec.dequeueOutputBuffer(info, if (toEnd) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!toEnd) return
                    check(System.nanoTime() < giveUp) { "the encoder did not finish" }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "the encoder changed its format after the file began" }
                    val track = muxer.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    if (videoTrack >= 0 && (audio == null || audioTrack >= 0)) begin()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && !config && info.size > 0) put(isVideo, buffer)
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (ended) {
                        if (isVideo) videoEnded = true else audioEnded = true
                        return
                    }
                }
            }
        }
    }

    private fun put(isVideo: Boolean, buffer: ByteBuffer) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        if (started) {
            muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, info)
        } else {
            val bytes = ByteArray(info.size)
            buffer.get(bytes)
            held += Held(isVideo, bytes, info.presentationTimeUs, info.flags)
        }
    }

    private fun begin() {
        muxer.start()
        started = true
        val each = MediaCodec.BufferInfo()
        for (h in held) {
            val track = if (h.video) videoTrack else audioTrack
            if (track < 0) continue
            each.set(0, h.bytes.size, h.ptsUs, h.flags)
            muxer.writeSampleData(track, ByteBuffer.wrap(h.bytes), each)
        }
        held.clear()
    }

    /** The planes written into the encoder's own picture, by its strides (planar or interleaved, whichever it is). */
    private fun write(image: Image) {
        val planes = image.planes
        check(planes.size >= 3) { "the encoder's picture has ${planes.size} planes" }
        plane(planes[0], y, width, height)
        plane(planes[1], u, Yuv420.chromaWidth(width), Yuv420.chromaHeight(height))
        plane(planes[2], v, Yuv420.chromaWidth(width), Yuv420.chromaHeight(height))
    }

    private fun plane(plane: Image.Plane, data: ByteArray, w: Int, h: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.put(data, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = row * rowStride
                val from = row * w
                for (x in 0 until w) buffer.put(base + x * pixelStride, data[from + x])
            }
        }
    }

    /** Where an encoder gives no picture to write into: its buffer, packed as its colour format says. The byte count. */
    private fun writePacked(buffer: ByteBuffer): Int {
        val format = runCatching { video.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) }.getOrDefault(PLANAR)
        buffer.clear()
        buffer.put(y)
        if (format == SEMI_PLANAR) {
            for (i in u.indices) {
                buffer.put(u[i])
                buffer.put(v[i])
            }
        } else {
            buffer.put(u)
            buffer.put(v)
        }
        return buffer.position()
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC

        @Suppress("DEPRECATION")
        private const val PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

        @Suppress("DEPRECATION")
        private const val SEMI_PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        private const val FLEXIBLE = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

        /** How long an encoder may take nothing in or give nothing out before the render says it stopped. */
        private const val STALL_NS = 10_000_000_000L

        /** The device's H.264 encoders that take flexible YUV, its hardware ones first. */
        private fun encoders(): List<MediaCodecInfo> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && MIME in it.supportedTypes.map { t -> t.lowercase() } }
                .filter { info -> runCatching { FLEXIBLE in info.getCapabilitiesForType(MIME).colorFormats }.getOrDefault(false) }
                .sortedBy { info -> if (Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated) 0 else 1 }

        /**
         * The size a video is made at here: [width] × [height] when an encoder takes it, else 1280 × 720, else whatever is
         * the largest 16:9 an encoder takes — a phone too old for 1080p still renders. Always even.
         */
        fun size(width: Int, height: Int): Pair<Int, Int> {
            val wanted = (width / 2 * 2).coerceAtLeast(2) to (height / 2 * 2).coerceAtLeast(2)
            val caps = encoders().mapNotNull { runCatching { it.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() }
            if (caps.isEmpty()) return wanted
            for ((w, h) in listOf(wanted, 1280 to 720, 960 to 540, 640 to 360)) {
                if (caps.any { runCatching { it.isSizeSupported(w, h) }.getOrDefault(false) }) return w to h
            }
            return wanted
        }

        /**
         * An encoder made for [file], [width] × [height] at [fps], with AAC for [channels] at [rate] when there is sound
         * ([channels] 0: none). The first of the device's encoders that starts; it says which failed and why when none does.
         */
        fun open(file: File, width: Int, height: Int, fps: Int, rate: Int, channels: Int): PhoneEncoder {
            file.delete()
            val tried = ArrayList<String>()
            for (info in encoders()) {
                val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
                try {
                    codec.configure(videoFormat(width, height, fps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                } catch (e: Exception) {
                    tried += "${info.name} (${e.message?.take(80) ?: e::class.simpleName})"
                    runCatching { codec.release() }
                    continue
                }
                val sound = if (channels > 0) {
                    try {
                        MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                            configure(audioFormat(rate, channels), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            start()
                        }
                    } catch (e: Exception) {
                        runCatching { codec.stop() }
                        runCatching { codec.release() }
                        throw IllegalStateException("The sound's encoder would not start: ${e.message ?: e::class.simpleName}", e)
                    }
                } else {
                    null
                }
                return try {
                    PhoneEncoder(file, width, height, fps, codec, info.name, sound, rate, channels.coerceAtLeast(1))
                } catch (e: Exception) {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                    sound?.let { runCatching { it.stop() }; runCatching { it.release() } }
                    file.delete()
                    throw e
                }
            }
            error(if (tried.isEmpty()) "This device has no H.264 encoder" else "No H.264 encoder would start: ${tried.joinToString("; ")}")
        }

        private fun videoFormat(width: Int, height: Int, fps: Int): MediaFormat =
            MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, FLEXIBLE)
                setInteger(MediaFormat.KEY_BIT_RATE, EncoderPick.bitrate(width, height, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // What the pictures are (Yuv420): written into the stream, so a player reads the colours as made.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }

        private fun audioFormat(rate: Int, channels: Int): MediaFormat =
            MediaFormat.createAudioFormat(AUDIO_MIME, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, EncoderPick.AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
    }
}
