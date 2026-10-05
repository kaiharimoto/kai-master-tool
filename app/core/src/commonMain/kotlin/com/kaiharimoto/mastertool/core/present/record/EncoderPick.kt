package com.kaiharimoto.mastertool.core.present.record

/**
 * Which video encoder a render asks FFmpeg for (1.0.72; recording, 1.1.13), best first for each system, and what
 * goes round it. The machine's own H.264 first — Media Foundation on Windows, VideoToolbox on a Mac, a graphics
 * card's where FFmpeg's LGPL build has one — with AAC sound; then **VP9** (libvpx, BSD) with Opus, both royalty-free,
 * which every build carries and YouTube takes — in an MP4 either way ([container]). Never the bundled OpenH264: it
 * is compiled from source, so Cisco's patent licence (which covers only Cisco's own binaries) does not cover it
 * (`docs/present/AUDIT.md`). Nor MPEG-4 Part 2, which looks poor at a YouTube bit rate. The first that opens wins.
 */
object EncoderPick {
    const val MAC = "MAC"
    const val WINDOWS = "WINDOWS"
    const val LINUX = "LINUX"

    /** The free fallback every build carries. */
    const val VP9 = "libvpx-vp9"

    /** Never asked for, whatever a newer list says: the bundled OpenH264 has no patent licence here. */
    val NEVER = setOf("libopenh264", "libx264", "libx265")

    fun order(os: String): List<String> = when (os) {
        MAC -> listOf("h264_videotoolbox", VP9)
        WINDOWS -> listOf("h264_mf", "h264_nvenc", "h264_qsv", "h264_amf", VP9)
        else -> listOf("h264_nvenc", VP9)
    }

    /** The first of [os]'s encoders [opens] says it can start. */
    fun pick(os: String, opens: (String) -> Boolean): String? = order(os).firstOrNull(opens)

    /** Whether [codec] is an H.264 encoder (an MP4), not VP9 (a WebM). */
    fun isH264(codec: String): Boolean = codec.startsWith("h264")

    /**
     * The file the video goes in: an MP4 whichever the encoder — what YouTube asks for. VP9 and Opus go in an MP4 as
     * well as in a WebM, and a WebM written through JavaCV says its sound lasts nearly a second longer than it does
     * (JavaCV hands the muxer sound packets' lengths in 1/48000 s, which WebM reads as milliseconds; an MP4 keeps sound
     * in 1/48000 s, so the lengths are right there).
     */
    fun container(codec: String): String = "mp4"

    /** The sound's encoder with [codec]: FFmpeg's own AAC beside H.264; Opus, royalty-free like VP9, beside VP9. */
    fun audioCodec(codec: String): String = if (isH264(codec)) "aac" else "libopus"

    /** The sound's rate in the video: Opus takes 48 kHz only, and AAC is at home there too. */
    const val AUDIO_RATE = 48_000

    /** The sound's bit rate, in bits a second. */
    const val AUDIO_BITRATE = 160_000

    /** Words for [codec], for a take's line: "H.264 MP4" or "VP9 MP4". */
    fun describe(codec: String): String = if (isH264(codec)) "H.264 MP4" else "VP9 MP4"

    /**
     * The encoder's own options for [codec]. VP9 is set for speed — libvpx's slowest settings take minutes for each
     * second of 1080p — with its rows in parallel.
     */
    fun options(codec: String): Map<String, String> = when (codec) {
        VP9 -> mapOf("deadline" to "realtime", "cpu-used" to "8", "row-mt" to "1", "lag-in-frames" to "0")
        "h264_videotoolbox" -> mapOf("allow_sw" to "1")
        "h264_nvenc" -> mapOf("preset" to "p5")
        else -> emptyMap()
    }

    /**
     * The pictures' layout [codec] is handed: NV12 for the encoders that take only that (Media Foundation's and Intel's),
     * planar 4:2:0 for the rest — what YouTube asks for either way.
     */
    fun pixelFormat(codec: String): String = if (codec == "h264_mf" || codec == "h264_qsv") "nv12" else "yuv420p"

    /** The bit rate for a frame size, in bits a second: about 8 Mb/s at 1080p30, as YouTube asks. */
    fun bitrate(width: Int, height: Int, fps: Int): Int = (width.toLong() * height * fps * 0.13).toInt().coerceIn(2_000_000, 20_000_000)

    /** The system [osName] (`os.name`) names, as [order] reads it. */
    fun osOf(osName: String): String {
        val n = osName.lowercase()
        return when {
            "mac" in n || "darwin" in n -> MAC
            "win" in n -> WINDOWS
            else -> LINUX
        }
    }
}
