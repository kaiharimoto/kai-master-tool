package com.kaiharimoto.mastertool.core.ai.course

import kotlin.math.abs

/**
 * What a video chapter said, with when (Phase 2 of the course study): from the player's own captions when it has them
 * ([CaptionCues]), else from its sound transcribed on the computer. Kept as the chapter's text, `[mm:ss] words` a line,
 * so its notes can say where in the video each thing is.
 */
data class Transcript(val lines: List<Line>) {
    data class Line(val atMs: Long, val text: String)

    /** The chapter's text: a line a stretch of words, its time first. */
    fun render(title: String = ""): String = buildString {
        if (title.isNotBlank()) append("# ").append(title).append("\n\n")
        lines.forEach { append('[').append(clock(it.atMs)).append("] ").append(it.text).append('\n') }
    }

    val words: Int get() = lines.sumOf { CourseText.words(it.text) }

    companion object {
        /** `m:ss`, or `h:mm:ss` past an hour. */
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
        }

        private fun two(n: Long) = if (n < 10) "0$n" else "$n"

        /**
         * Short pieces joined into lines of about [target] characters, each keeping its first piece's time; a piece that
         * repeats the one before (captions roll up their last line) is dropped.
         */
        fun of(pieces: List<Line>, target: Int = 240): Transcript {
            val out = ArrayList<Line>()
            var at = -1L
            val text = StringBuilder()
            var last = ""
            for (p in pieces.sortedBy { it.atMs }) {
                val t = p.text.replace(Regex("\\s+"), " ").trim()
                if (t.isEmpty() || t == last) continue
                // A rolled-up caption repeats what was just said: keep only what is new.
                val fresh = if (last.isNotEmpty() && t.startsWith(last)) t.removePrefix(last).trim() else t
                last = t
                if (fresh.isEmpty()) continue
                if (at < 0) at = p.atMs
                if (text.isNotEmpty()) text.append(' ')
                text.append(fresh)
                if (text.length >= target || fresh.endsWith('.') && text.length >= target / 2) {
                    out += Line(at, text.toString())
                    text.setLength(0)
                    at = -1
                }
            }
            if (text.isNotEmpty()) out += Line(at.coerceAtLeast(0), text.toString())
            return Transcript(out)
        }
    }
}

/** A player's captions as WebVTT or SRT read into timed pieces: cue settings, tags and numbering dropped. */
object CaptionCues {
    private val TIME = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val TAG = Regex("""<[^>]*>""")

    fun parse(text: String): List<Transcript.Line> {
        val out = ArrayList<Transcript.Line>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.lines()
            val i = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (i < 0) continue
            val start = ms(TIME.find(lines[i])!!.groupValues[1])
            val words = lines.drop(i + 1).joinToString(" ") { TAG.replace(it, "").trim() }
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()
            if (words.isNotEmpty()) out += Transcript.Line(start, words)
        }
        return out
    }

    /** `hh:mm:ss.mmm`, `mm:ss.mmm` or SRT's comma, in milliseconds. */
    fun ms(t: String): Long {
        val parts = t.replace(',', '.').split(':')
        val sec = parts.last().toDouble()
        val min = parts.getOrNull(parts.size - 2)?.toLong() ?: 0
        val hour = if (parts.size == 3) parts[0].toLong() else 0
        return ((hour * 3600 + min * 60) * 1000 + sec * 1000).toLong()
    }
}

/**
 * Which frames of a video to keep, so its notes can see a board or a decklist it shows (Phase 2): each frame a small grey
 * thumbnail, a candidate when it differs enough from the last candidate — a new scene — and no closer than [minGapMs];
 * of more than [max] candidates, [max] spread evenly over the video's time, so a long video's pictures come from all of
 * it and not from its first minutes.
 */
object KeyFrames {
    const val MIN_GAP_MS = 20_000L
    const val MAX = 40

    /** How different two thumbnails of the same size are, 0 (same) to 1, as the mean of their pixels' differences. */
    fun difference(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size || a.isEmpty()) return 1.0
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
        return sum / (a.size * 255.0)
    }

    /** The frames to keep among [frames] (time and thumbnail, in time order): their indices, in order, the first always. */
    fun pick(frames: List<Pair<Long, ByteArray>>, threshold: Double = 0.08, minGapMs: Long = MIN_GAP_MS, max: Int = MAX): List<Int> {
        val found = ArrayList<Int>()
        for ((i, f) in frames.withIndex()) {
            val last = found.lastOrNull()?.let { frames[it] }
            if (last == null || (f.first - last.first >= minGapMs && difference(last.second, f.second) >= threshold)) found += i
        }
        if (found.size <= max || max <= 0) return if (max <= 0) emptyList() else found
        // One candidate nearest each of [max] times spread from the video's start to its end, in order, never the same twice.
        val from = frames.first().first
        val to = frames.last().first
        val kept = ArrayList<Int>(max)
        var lo = 0
        for (k in 0 until max) {
            val target = if (max == 1) from else from + (to - from) * k / (max - 1)
            val hi = found.size - (max - k)
            var best = lo
            for (j in lo..hi) if (abs(frames[found[j]].first - target) < abs(frames[found[best]].first - target)) best = j
            kept += found[best]
            lo = best + 1
        }
        return kept
    }
}
