package com.kaiharimoto.mastertool.core.ai.voice

import com.kaiharimoto.mastertool.core.ai.text.Block
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.ai.text.Inline
import kotlin.math.sqrt

/*
 * Talking to Ai (1.0.57, kai: "enable voice input"; and talk mode, a conversation out loud). The
 * arithmetic of it, pure and tested: sound as numbers, when someone has stopped speaking, the words a
 * transcriber is primed with, and what of a reply is worth saying aloud.
 */

/** 16-bit little-endian PCM, as a microphone gives it, into samples from −1 to 1, and how loud they are. */
object Pcm {
    const val RATE = 16_000

    fun samples(bytes: ByteArray, count: Int = bytes.size): FloatArray {
        val n = count / 2
        return FloatArray(n) { i ->
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    /** Root mean square of [s], 0 (silence) to 1. */
    fun rms(s: FloatArray, from: Int = 0, to: Int = s.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += s[i] * s[i]
        return sqrt(sum / (to - from)).toFloat()
    }
}

/**
 * When someone has finished speaking (1.0.57), from the loudness of each slice of sound: speech is
 * a slice louder than the room by [RATIO] (the room's level learnt from the first quiet slices), and
 * a turn ends after [TAIL] seconds quiet once speech was heard — or when nothing was said in
 * [NOTHING] seconds, or it has run [MOST]. Fed one slice at a time, [seconds] from the start.
 */
class SpeechGate(
    private val tail: Double = TAIL,
    private val nothing: Double = NOTHING,
    private val most: Double = MOST,
) {
    enum class State { WAITING, SPEAKING, DONE_SPOKEN, DONE_NOTHING, DONE_TOO_LONG }

    var state = State.WAITING
        private set
    private var room = 0f
    private var roomSlices = 0
    private var lastLoud = 0.0
    private var heard = false

    val done: Boolean get() = state != State.WAITING && state != State.SPEAKING

    fun feed(level: Float, seconds: Double): State {
        if (done) return state
        // The room: the quiet before anyone speaks, a floor under which nothing counts.
        if (!heard && roomSlices < 5) {
            room = (room * roomSlices + level) / (roomSlices + 1)
            roomSlices++
        }
        val loud = level > maxOf(FLOOR, room * RATIO)
        if (loud) {
            heard = true
            lastLoud = seconds
            state = State.SPEAKING
        }
        state = when {
            seconds >= most -> State.DONE_TOO_LONG
            heard && seconds - lastLoud >= tail -> State.DONE_SPOKEN
            !heard && seconds >= nothing -> State.DONE_NOTHING
            else -> state
        }
        return state
    }

    companion object {
        const val TAIL = 1.2
        const val NOTHING = 8.0
        const val MOST = 60.0
        const val RATIO = 2.5f

        /** Below this nothing is speech, however quiet the room. */
        const val FLOOR = 0.015f
    }
}

/**
 * The words a transcriber is primed with (1.0.57): Whisper spells what it has just "heard" in its
 * prompt, so the open deck's card names and the game's words come out right, not as homophones.
 * Kept short — the prompt is at most a couple of hundred tokens.
 */
object Hints {
    private val game = listOf(
        "Yu-Gi-Oh!", "hand trap", "Extra Deck", "Side Deck", "Main Deck", "Special Summon", "Normal Summon",
        "Link", "Xyz", "Synchro", "Fusion", "Pendulum", "banish", "GY", "Ash Blossom", "Maxx \"C\"", "Nibiru",
    )

    fun prompt(cards: List<String>, most: Int = 700): String {
        val out = StringBuilder()
        (cards.distinct() + game).forEach { w ->
            if (out.length + w.length + 2 > most) return out.toString()
            if (out.isNotEmpty()) out.append(", ")
            out.append(w)
        }
        return out.toString()
    }

    /** What a transcriber writes for silence or noise, which is no message at all. */
    fun isNothing(text: String): Boolean {
        val t = text.trim().lowercase().trim('.', ' ', '!', '?')
        return t.isEmpty() || t in setOf("[blank_audio]", "(silence)", "[silence]", "[music]", "(music)", "you", "thank you", "[inaudible]") ||
            (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))
    }
}

/**
 * What of a reply is said aloud in talk mode (1.0.57): its words, plainly — no brackets or marks —
 * and in place of a table, a chart or a layout of cards, a line saying it is on screen. Long
 * replies are said to their first paragraphs; the rest stays on screen.
 */
object Spoken {
    fun of(reply: String, most: Int = 900): String {
        val blocks = ChatMarkdown.parse(reply)
        val said = StringBuilder()
        var shown = 0
        blocks.forEach { b ->
            if (said.length >= most) return@forEach
            val line = when (b) {
                is Block.Heading -> null
                is Block.Paragraph -> words(b.inlines)
                is Block.Bullets -> b.items.joinToString(". ") { words(it) }
                is Block.Numbered -> b.items.joinToString(". ") { words(it) }
                is Block.Quote -> words(b.inlines)
                is Block.Table, is Block.Chart, is Block.Cards, is Block.Deck, is Block.Compare, is Block.Line, is Block.Board, is Block.Code -> {
                    shown++
                    null
                }
                is Block.Pending, Block.Rule -> null
            }
            line?.takeIf { it.isNotBlank() }?.let {
                if (said.isNotEmpty()) said.append(' ')
                said.append(it.trim().let { s -> if (s.last() in ".!?:") s else "$s." })
            }
        }
        var text = said.toString()
        if (text.length > most) text = text.take(most).substringBeforeLast(". ", text.take(most)) + "."
        if (shown > 0) text += if (shown == 1) " I've put it on screen." else " The rest is on screen."
        return text.trim()
    }

    private fun words(inlines: List<Inline>): String = inlines.joinToString("") {
        when (it) {
            is Inline.Text -> it.text
            is Inline.Bold -> it.text
            is Inline.Italic -> it.text
            is Inline.Code -> it.text
            is Inline.Card -> it.name
        }
    }.replace(Regex("\\s+"), " ")
}

/** The speech models the desk can download for Whisper (1.0.57): whisper.cpp's own files, checked by their SHA-256. */
enum class VoiceModel(val id: String, val label: String, val bytes: Long, val sha256: String, val about: String) {
    TINY_EN("tiny.en", "Fast", 77_704_715, "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f", "Quickest, English only; fine for short commands."),
    BASE_EN("base.en", "Standard", 147_964_211, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002", "English, quick and accurate on most machines."),
    SMALL_EN("small.en", "Accurate", 487_614_201, "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d", "English, the most accurate; slower on older machines."),
    BASE("base", "Any language", 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "Many languages, as quick as Standard."),
    ;

    val file: String get() = "ggml-$id.bin"
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
    val megabytes: Int get() = (bytes / 1_000_000).toInt()

    companion object {
        val DEFAULT = BASE_EN
        fun of(id: String?): VoiceModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
