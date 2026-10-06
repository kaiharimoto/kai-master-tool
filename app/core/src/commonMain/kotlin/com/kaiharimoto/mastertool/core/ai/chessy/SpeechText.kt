package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The rhythm of what Chessy is saying (the rig red team, `docs/chessy/RIG-REDTEAM.md`): her Flap (kai's pick, her closed
 * smile and open mouth) cut to the reply's own words instead of the mockup's twelve-second loop, so her mouth stops
 * where her words stop, pauses at a comma and longer at a full stop, and keeps still through a code block, a table or a
 * link. Fed the reply as it streams ([feed]); stepped by the rig ([advance]); read as the mockup's [Speech.At].
 *
 * Words become syllables by their vowel groups (a kana or a kanji is one); the first of a phrase and of a long word are
 * stressed. A reply streams far faster than anyone speaks, so the rhythm plays at most [LAG] ms behind the text,
 * hurrying a little as it falls behind ([HURRY]) and skipping the rest: her mouth follows the words being written now,
 * and is still the moment they stop. Pure and deterministic: the same text gives the same rhythm.
 */
class SpeechText {
    private class Syllable(val t0: Float, val d: Float, val peak: Float, val stress: Boolean, val close: Boolean)

    private val syllables = ArrayList<Syllable>()
    private var at = 0
    private var end = 0f
    private var played = 0f
    private var fed: String = ""
    private var cursor = 0
    private var count = 0
    private var phraseStart = true
    private var fence = false
    private var code = false
    private var lineStart = true
    private var skipping = false

    /** The mouth now: how open, whether on a stressed syllable, and how far through it. */
    var now: Speech.At = SILENT
        private set

    /** How far the rhythm has fallen behind the text, in ms. */
    val behind: Float get() = (end - played).coerceAtLeast(0f)

    /** The reply so far: a longer copy of the last carries on; anything else is a new reply, begun afresh. */
    fun feed(text: String) {
        if (text === fed) return
        if (text.length < cursor || !text.startsWith(fed.substring(0, cursor))) reset()
        fed = text
        // only whole tokens: up to the last space or line break, the rest waits for its end
        var last = -1
        for (i in text.length - 1 downTo cursor) if (text[i].isWhitespace()) { last = i; break }
        if (last < 0) return
        read(text, cursor, last + 1)
        cursor = last + 1
    }

    /** Move the rhythm on [ms]. */
    fun advance(ms: Float) {
        val lag = end - played
        if (lag > LAG) played = end - LAG
        val hurry = 1f + ((end - played) / LAG).coerceIn(0f, 1f) * (HURRY - 1f)
        played += ms * hurry
        while (at < syllables.size && syllables[at].t0 + syllables[at].d <= played) at++
        val s = syllables.getOrNull(at)
        now = if (s == null || played < s.t0) SILENT else {
            val u = ((played - s.t0) / s.d).coerceIn(0f, 1f)
            val floor = if (s.close) 0f else .15f
            Speech.At(floor + (s.peak - floor) * sin(PI.toFloat() * u).toDouble().pow(1.1).toFloat(), s.stress, u)
        }
        // what is spoken is let go of, so a long reply never keeps its whole rhythm
        if (at > 512) {
            syllables.subList(0, at).clear()
            at = 0
        }
    }

    private fun reset() {
        syllables.clear()
        at = 0; end = 0f; played = 0f; cursor = 0; count = 0
        phraseStart = true; fence = false; code = false; lineStart = true; skipping = false
        fed = ""
        now = SILENT
    }

    /** Read [text] from [from] to [to] (a run of whole tokens) into syllables and pauses. */
    private fun read(text: String, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\n') {
                pause(PAUSE_LINE)
                lineStart = true
                skipping = false
                i++
                continue
            }
            if (c.isWhitespace()) { i++; continue }
            // a line that opens or closes a fence, a line inside one, and a table's row are not spoken
            if (lineStart) {
                lineStart = false
                if (text.startsWith("```", i)) { fence = !fence; skipping = true } else if (fence || c == '|') skipping = true
            }
            var j = i
            while (j < to && !text[j].isWhitespace()) j++
            if (!skipping) token(text.substring(i, j))
            i = j
        }
    }

    /** One token: its words spoken, then the pause its punctuation asks for. */
    private fun token(raw: String) {
        val ticks = raw.count { it == '`' }
        val quiet = code || ticks > 0 || "://" in raw || raw.startsWith("www.")
        if (ticks % 2 == 1) code = !code
        if (quiet) return
        val word = StringBuilder()
        for (ch in raw) {
            if (ch.isLetterOrDigit() || ch == '\'' || ch == '’') word.append(ch) else {
                if (word.isNotEmpty()) { say(word.toString()); word.clear() }
                when (ch) {
                    ',', ';', ':', '、' -> pause(PAUSE_COMMA)
                    '.', '!', '?', '…', '。', '！', '？' -> pause(PAUSE_STOP)
                    '—', '–' -> pause(PAUSE_COMMA)
                }
            }
        }
        if (word.isNotEmpty()) say(word.toString())
    }

    /** A word's syllables, then the small gap between words. */
    private fun say(word: String) {
        val n = syllablesOf(word)
        if (n == 0) return
        for (k in 0 until n) {
            val r1 = dice(count, 1)
            val r2 = dice(count, 2)
            val r3 = dice(count, 3)
            val stress = phraseStart && k == 0 || n >= 3 && k == 0 || r3 < .12f
            phraseStart = false
            val d = SYLLABLE * (.85f + .3f * r1) * (if (stress) 1.3f else 1f)
            val peak = if (stress) .85f + .15f * r2 else .4f + .45f * r2
            syllables += Syllable(end, d, peak, stress, dice(count, 4) < .55f)
            end += d
            count++
        }
        end += WORD_GAP
    }

    private fun pause(ms: Float) {
        end += ms
        phraseStart = true
    }

    companion object {
        /** Nothing said: the lips together. */
        val SILENT = Speech.At(0f, false, 0f)

        /** A syllable's length (ms, about five a second), the gap between words, and the pauses. */
        const val SYLLABLE = 160f
        const val WORD_GAP = 30f
        const val PAUSE_COMMA = 180f
        const val PAUSE_STOP = 320f
        const val PAUSE_LINE = 260f

        /** How far behind the text the rhythm may fall (ms), and how much faster it plays at that distance. */
        const val LAG = 1500f
        const val HURRY = 1.6f

        /** The syllables in [word]: a number is a syllable a two digits (at most four), a kana or kanji one each, else its vowel groups. */
        fun syllablesOf(word: String): Int {
            if (word.isEmpty()) return 0
            if (word.all { it.isDigit() }) return min(4, (word.length + 1) / 2)
            // a small kana (the ゃ of にゃ) belongs to the syllable before it; っ is a beat of its own
            val wide = word.count { (it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF) && it !in SMALL_KANA }
            if (wide > 0) return min(6, wide)
            val w = word.lowercase()
            var groups = 0
            var inVowel = false
            for (ch in w) {
                val v = ch in VOWELS
                if (v && !inVowel) groups++
                inVowel = v
            }
            if (groups > 1 && w.endsWith('e') && !w.endsWith("le") && !w.endsWith("ee")) groups--
            return groups.coerceIn(1, 6)
        }

        private const val SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ"
        private const val VOWELS = "aeiouyàáâãäåèéêëìíîïòóôõöùúûüýæøœ"

        /** A number from 0 to 1 for syllable [n]'s [k]th choice: mulberry32 on the pair, so the rhythm never depends on time. */
        internal fun dice(n: Int, k: Int): Float {
            var a = n * 4 + k + 0x2F6B
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return (((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0).toFloat()
        }
    }
}
