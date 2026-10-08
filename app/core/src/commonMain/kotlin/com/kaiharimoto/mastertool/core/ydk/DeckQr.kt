package com.kaiharimoto.mastertool.core.ydk

import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * zlib, which the platform has and common Kotlin does not: `JvmZlib`, from
 * `java.util.zip`, on the desk and on Android.
 */
interface Zlib {
    fun deflate(bytes: ByteArray): ByteArray

    /** The bytes, or null when [bytes] are no zlib stream or would grow past [limit]. */
    fun inflate(bytes: ByteArray, limit: Int): ByteArray?
}

/**
 * A deck's QR code (kai, 1.0.31: "carry as much information as possible,
 * including groups"): the text of each code — one, or the parts of a deck too
 * large for one (1.0.32: "scan multiple QR codes when we go over the limit") —
 * what they carry, and what a deck too large even for [DeckQr.MAX_PARTS] had
 * to leave behind.
 */
data class DeckQrCode(val parts: List<String>, val carries: List<String>, val leftOut: List<String>)

/** A deck read from a code: the file it held, and the name and covers when it carried them. */
data class DeckRead(val parsed: YdkParseResult, val name: String? = null, val covers: List<Int> = emptyList())

/**
 * The whole deck in one QR code. Its body is the deck's own `.ydkx` — the cards,
 * and the groups, hand goals, notes, siding patterns and any key a future build
 * writes, verbatim — under two lines naming the deck and its covers, which
 * `YdkCodec` reads as comments, so the body is a deck file anything can import.
 * zlib shrinks it; Base45 writes it in the 45 characters a QR code's
 * alphanumeric mode packs tightest: `NMT1:` then the Base45.
 *
 * **A deck too large for one comfortable code is split** (1.0.32) into parts
 * of [PART_CHARS], each `NMT1P:<i>/<n>/<tag>:` and a run of the Base45, where
 * the tag is a hash of the whole: the phone collects them in any order
 * ([DeckQrParts]) and joins them back into the one code. Only a deck past
 * [MAX_PARTS] sheds its extras — every extended key but the groups, then the
 * groups — and says what it left out; the cards and the name always fit, as 90
 * passcodes deflate to a few hundred bytes.
 */
object DeckQr {
    const val PREFIX = "NMT1:"
    const val PART_PREFIX = "NMT1P:"

    /**
     * One code up to this many characters: about version 27, 125 modules, still
     * easy off a screen. Neue's fullest deck with every card grouped is ~1,150.
     */
    const val SINGLE_CHARS = 1600

    /** Past [SINGLE_CHARS], parts of at most this: about version 23 at level M each. */
    const val PART_CHARS = 1200

    /** Enough for any deck: 24 parts hold some twenty kilobytes of compressed deck file. */
    const val MAX_PARTS = 24

    /** The most a code may inflate to: a deck file is kilobytes, and a bad code should not be megabytes. */
    private const val INFLATED_LIMIT = 1 shl 20

    private const val NAME = "#name "
    private const val COVERS = "#covers "

    private val compact = Json

    fun write(
        name: String,
        document: YdkDocument,
        covers: List<Int>,
        zlib: Zlib,
        maxParts: Int = MAX_PARTS,
        singleChars: Int = SINGLE_CHARS,
        partChars: Int = PART_CHARS,
    ): DeckQrCode? {
        val extended = document.extended
        val onlyGroups = extended?.get("groups")?.let { JsonObject(mapOf("groups" to it)) }
        // The groups with the deck's other sets of them (2026-10), before the groups alone.
        val groupsAndSets = extended?.filterKeys { it == "groups" || it == "groupSets" }?.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        // Everything; then the groups and their sets; then the groups alone; then the cards, the name and the covers.
        val tries = listOf(extended, groupsAndSets, onlyGroups, null).distinct()
        for (ext in tries) {
            val payload = Base45.encode(zlib.deflate(body(name, document.copy(extended = ext), covers).encodeToByteArray()))
            val parts = split(payload, singleChars, partChars)
            if (parts.size <= maxParts) {
                return DeckQrCode(parts, describe(ext, covers), leftOut(extended, ext))
            }
        }
        return null
    }

    /** One code, or even parts of at most [partChars], each carrying its place, the count and the whole's tag. */
    private fun split(payload: String, singleChars: Int, partChars: Int): List<String> {
        if (PREFIX.length + payload.length <= singleChars) return listOf(PREFIX + payload)
        // Two at the least: a split exists because one code was too large.
        val count = maxOf(2, (payload.length + partChars - 1) / partChars)
        val chunks = payload.chunked((payload.length + count - 1) / count)
        val tag = tag(payload)
        return chunks.mapIndexed { i, chunk -> "$PART_PREFIX${i + 1}/${chunks.size}/$tag:$chunk" }
    }

    /** FNV-1a over the whole code, in base 36: which parts belong together, and that they joined whole. */
    internal fun tag(payload: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in payload) {
            h = h xor c.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xFFFFFFFFL).toString(36).uppercase()
    }

    /** The deck in a code this writes, or null when [text] is not one. */
    fun read(text: String, zlib: Zlib): DeckRead? {
        val start = text.indexOf(PREFIX).takeIf { it >= 0 && text.substring(0, it).isBlank() } ?: return null
        // Not trimmed at the end: a space is a Base45 digit.
        val payload = text.substring(start + PREFIX.length).trimEnd('\n', '\r')
        val bytes = Base45.decode(payload) ?: return null
        val body = zlib.inflate(bytes, INFLATED_LIMIT)?.decodeToString() ?: return null
        val lines = body.lineSequence().take(3).toList()
        return DeckRead(
            parsed = YdkCodec.parse(body),
            name = lines.firstOrNull { it.startsWith(NAME) }?.removePrefix(NAME)?.trim()?.takeIf { it.isNotEmpty() },
            covers = lines.firstOrNull { it.startsWith(COVERS) }?.removePrefix(COVERS)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }.orEmpty(),
        )
    }

    private fun body(name: String, document: YdkDocument, covers: List<Int>): String = buildString {
        append(NAME).append(name.replace('\n', ' ').replace('\r', ' ')).append('\n')
        // An own picture (a negative choice) is a file on this machine only.
        val shared = covers.filter { it > 0 }
        if (shared.isNotEmpty()) append(COVERS).append(shared.joinToString(",")).append('\n')
        append(YdkCodec.write(document.deck))
        document.extended?.let { append("#ydkx-extended\n").append(compact.encodeToString(JsonObject.serializer(), it)).append('\n') }
    }

    /** In words, for the dialog: "the cards", "the name", "5 groups", … */
    private fun describe(extended: JsonObject?, covers: List<Int>): List<String> = buildList {
        add("the cards")
        add("the name")
        val stored = DeckGroupsCodec.read(extended)
        val groups = stored.groups.groups.size
        if (groups > 0) add(if (groups == 1) "1 group" else "$groups groups")
        val goals = stored.goals.goals.size
        if (goals > 0) add(if (goals == 1) "1 hand goal" else "$goals hand goals")
        val shared = covers.count { it > 0 }
        if (shared > 0) add(if (shared == 1) "the cover" else "the $shared covers")
        extended?.keys?.filter { it != "groups" && it != "version" }?.forEach { add(words(it)) }
    }

    private fun leftOut(full: JsonObject?, carried: JsonObject?): List<String> =
        full?.keys.orEmpty().filter { it != "version" && carried?.containsKey(it) != true }.map(::words)

    /** `sidingPatterns` → "siding patterns". */
    private fun words(key: String): String = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
}

/**
 * The parts of a split code (1.0.32), as a camera or a run of pictures meets
 * them: in any order, each as often as it passes. A part of another deck starts
 * over with that deck. The joined code comes back once every part is in, and
 * only if it hashes to the tag the parts carried.
 */
class DeckQrParts {
    private val header = Regex("""NMT1P:(\d{1,3})/(\d{1,3})/([0-9A-Z]{1,8}):(.*)""")
    private val parts = mutableMapOf<Int, String>()
    private var tag: String? = null

    /** How many parts the code being collected has; 0 before the first. */
    var of: Int = 0
        private set

    /** How many of them are in. */
    val have: Int get() = parts.size

    sealed interface Offer {
        /** A whole code: [text] as it was, or the parts joined. */
        data class Whole(val text: String) : Offer

        /** A part: [have] of [of] are in; [new] when this one was not before. */
        data class Part(val have: Int, val of: Int, val new: Boolean) : Offer
    }

    fun offer(text: String): Offer {
        val m = header.matchEntire(text.trim('\n', '\r').trimStart()) ?: return Offer.Whole(text)
        val (at, count, partTag, chunk) = m.destructured
        val i = at.toInt()
        val n = count.toInt()
        if (n < 1 || i !in 1..n) return Offer.Part(have, of, false)
        if (partTag != tag || n != of) {
            parts.clear()
            tag = partTag
            of = n
        }
        val new = parts.put(i, chunk) == null
        if (parts.size < of) return Offer.Part(have, of, new)
        val payload = (1..of).joinToString("") { parts.getValue(it) }
        val whole = payload.takeIf { DeckQr.tag(it) == tag }
        reset()
        return if (whole != null) Offer.Whole(DeckQr.PREFIX + whole) else Offer.Part(0, n, false)
    }

    fun reset() {
        parts.clear()
        tag = null
        of = 0
    }
}

/**
 * How a split code's parts stand on the screen (1.0.32, kai: "don't have it
 * show one at a time… the user would need to click every time"): every part at
 * once, in the grid that makes each as large as the space allows, so the camera
 * is swept across them with nothing to press.
 */
object DeckQrGrid {
    /** The columns for [count] square codes in [width] by [height], [gap] apart, each with [label] under it. */
    fun columns(count: Int, width: Float, height: Float, gap: Float = 0f, label: Float = 0f): Int =
        (1..count.coerceAtLeast(1)).maxBy { cols -> side(count, cols, width, height, gap, label) }

    /** The side of each code at [cols] columns. */
    fun side(count: Int, cols: Int, width: Float, height: Float, gap: Float = 0f, label: Float = 0f): Float {
        val rows = (count + cols - 1) / cols
        val across = (width - gap * (cols - 1)) / cols
        val down = (height - gap * (rows - 1)) / rows - label
        return minOf(across, down).coerceAtLeast(0f)
    }
}
