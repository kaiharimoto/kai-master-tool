package com.kaiharimoto.mastertool.core.present.record

import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.SlideLayouts

/**
 * YouTube chapters from a take (1.0.72): the description's timestamps, read off when each slide
 * came up. YouTube's rules decide the shape: the first at 0:00, at least three, each at least ten
 * seconds long — so a slide passed through quickly joins the chapter before it, and a take that
 * cannot make three gives none.
 */
object Chapters {
    const val MIN_MS = 10_000L
    const val MIN_COUNT = 3

    data class Chapter(val atMs: Long, val title: String)

    /** The chapters of a take over [p], or empty when YouTube would not take them. */
    fun of(p: Presentation, events: List<TakeEvent>, durationMs: Long): List<Chapter> {
        val marks = events.filter { it.kind == TakeEvent.MARK && it.text.isNotBlank() }.map { it.at to it.text.trim() }
        val starts = TakeTimeline.slides(events).mapNotNull { (at, i) ->
            val s = p.slides.getOrNull(i) ?: return@mapNotNull null
            val title = (s.section ?: s.title).trim().ifBlank { null }
                ?: if (s.layout == SlideLayouts.END_CARD) "Outro" else null
            title?.let { at to it }
        }
        // A slide on screen again later does not start a second chapter of the same name in a row.
        val raw = (starts + marks).sortedBy { it.first }.fold(ArrayList<Pair<Long, String>>()) { acc, c ->
            if (acc.lastOrNull()?.second != c.second) acc += c
            acc
        }
        if (raw.isEmpty()) return emptyList()
        val out = raw.map { Chapter(it.first, it.second) }.toMutableList()
        // YouTube's first chapter is at 0:00.
        if (out.first().atMs > 0L) {
            if (out.first().atMs < MIN_MS) out[0] = out[0].copy(atMs = 0L) else out.add(0, Chapter(0L, "Intro"))
        }
        // A chapter lasts until the next begins: one under ten seconds goes, and the one after takes its
        // place — except the first, which stays at 0:00 and swallows the one after it instead.
        var i = 0
        while (i < out.size - 1) {
            if (out[i + 1].atMs - out[i].atMs < MIN_MS) {
                out.removeAt(if (i == 0) 1 else i)
                if (i > 0) i--
            } else if (out[i + 1].title == out[i].title) {
                out.removeAt(i + 1)
            } else {
                i++
            }
        }
        // The last chapter must run ten seconds too.
        while (out.size > 1 && durationMs - out.last().atMs < MIN_MS) out.removeAt(out.lastIndex)
        return if (out.size >= MIN_COUNT) out else emptyList()
    }

    /** `m:ss` under an hour, `h:mm:ss` from one. */
    fun stamp(ms: Long, long: Boolean): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (long) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    private fun two(n: Long) = n.toString().padStart(2, '0')

    /** The lines to paste into a description. */
    fun text(chapters: List<Chapter>): String {
        val long = (chapters.maxOfOrNull { it.atMs } ?: 0L) >= 3_600_000L
        return chapters.joinToString("\n") { "${stamp(it.atMs, long)} ${it.title}" }
    }
}

/**
 * Which H.264 encoder a render asks FFmpeg for (1.0.72), best first for each system: the
 * machine's own hardware where FFmpeg's LGPL build has it, then OpenH264, then MPEG-4 Part 2,
 * which every build carries. The first one that opens wins.
 */
object EncoderPick {
    const val MAC = "MAC"
    const val WINDOWS = "WINDOWS"
    const val LINUX = "LINUX"

    fun order(os: String): List<String> = when (os) {
        MAC -> listOf("h264_videotoolbox", "libopenh264", "mpeg4")
        WINDOWS -> listOf("h264_mf", "h264_nvenc", "h264_qsv", "h264_amf", "libopenh264", "mpeg4")
        else -> listOf("h264_nvenc", "libopenh264", "mpeg4")
    }

    /** The first of [os]'s encoders [opens] says it can start. */
    fun pick(os: String, opens: (String) -> Boolean): String? = order(os).firstOrNull(opens)

    /** The bit rate for a frame size, in bits a second: about 8 Mb/s at 1080p30, as YouTube asks. */
    fun bitrate(width: Int, height: Int, fps: Int): Int = (width.toLong() * height * fps * 0.13).toInt().coerceIn(2_000_000, 20_000_000)
}
