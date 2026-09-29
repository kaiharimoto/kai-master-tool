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
 * including groups"): the text it holds, what it carries, and what a deck too
 * large for one code had to leave behind.
 */
data class DeckQrCode(val text: String, val carries: List<String>, val leftOut: List<String>)

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
 * A deck whose extras will not fit one code sheds them in order — every
 * extended key but the groups, then the groups — and says what it left out.
 * The cards and the name always fit: 90 passcodes deflate to a few hundred bytes.
 */
object DeckQr {
    const val PREFIX = "NMT1:"

    /** What one QR code holds in alphanumeric mode: version 40 at level L. */
    const val MAX_CHARS = 4296

    /** The most a code may inflate to: a deck file is kilobytes, and a bad code should not be megabytes. */
    private const val INFLATED_LIMIT = 1 shl 20

    private const val NAME = "#name "
    private const val COVERS = "#covers "

    private val compact = Json

    fun write(name: String, document: YdkDocument, covers: List<Int>, zlib: Zlib, maxChars: Int = MAX_CHARS): DeckQrCode? {
        val extended = document.extended
        val onlyGroups = extended?.get("groups")?.let { JsonObject(mapOf("groups" to it)) }
        // Everything; then the groups alone; then the cards, the name and the covers.
        val tries = listOf(extended, onlyGroups, null).distinct()
        for (ext in tries) {
            val text = PREFIX + Base45.encode(zlib.deflate(body(name, document.copy(extended = ext), covers).encodeToByteArray()))
            if (text.length <= maxChars) {
                return DeckQrCode(text, describe(ext, covers), leftOut(extended, ext))
            }
        }
        return null
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
