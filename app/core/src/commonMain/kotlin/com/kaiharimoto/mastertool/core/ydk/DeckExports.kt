package com.kaiharimoto.mastertool.core.ydk

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The ways a deck leaves the app (kai, 1.0.15: "give the user a sub option
 * between YDK, YDKX, YDKe Code, and a Copied Text list"). The first two are
 * files ([YdkCodec]); the next two go to the clipboard; the last is the YDKe
 * code drawn as a QR code on the screen, for a phone or a tablet to scan
 * ([DeckCodes] reads it back).
 */
enum class DeckExportFormat(val label: String, val toClipboard: Boolean) {
    YDK("YDK file", false),
    YDKX("YDKX file, with groups", false),
    YDKE("YDKe code", true),
    TEXT("Text list", true),
    QR("QR code to scan", false),
}

/**
 * `ydke://` — the one-line deck code EDOPro, Dueling Book and most deck sites
 * paste: each section's passcodes as unsigned 32-bit little-endian integers,
 * Base64'd, in the order main, extra, side, each followed by `!`.
 */
@OptIn(ExperimentalEncodingApi::class)
object YdkeCodec {
    const val PREFIX = "ydke://"

    fun encode(deck: Deck): String =
        PREFIX + listOf(deck.main, deck.extra, deck.side).joinToString("") { section -> Base64.encode(bytes(section)) + "!" }

    /** The deck in a code, or null when it is not one. */
    fun decode(code: String): Deck? {
        val body = code.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val parts = body.split("!")
        if (parts.size < 3) return null
        val sections = parts.take(3).map { part ->
            val raw = runCatching { Base64.decode(part) }.getOrNull() ?: return null
            if (raw.size % 4 != 0) return null
            List(raw.size / 4) { i ->
                val b = i * 4
                CardId((raw[b].toInt() and 0xFF) or ((raw[b + 1].toInt() and 0xFF) shl 8) or ((raw[b + 2].toInt() and 0xFF) shl 16) or ((raw[b + 3].toInt() and 0xFF) shl 24))
            }
        }
        return Deck(main = sections[0], extra = sections[1], side = sections[2])
    }

    private fun bytes(ids: List<CardId>): ByteArray {
        val out = ByteArray(ids.size * 4)
        ids.forEachIndexed { i, id ->
            val v = id.value
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v shr 8).toByte()
            out[i * 4 + 2] = (v shr 16).toByte()
            out[i * 4 + 3] = (v shr 24).toByte()
        }
        return out
    }
}

/**
 * A deck in text that did not come from a file: what a QR code held, a scan's
 * or a picture's. A `ydke://` code anywhere in it — alone, as Neue's own QR
 * codes carry it, or inside a deck site's link — or the lines of a `.ydk` or a
 * `.ydkx`, groups and all. Null when there is no deck in it, or no card.
 */
object DeckCodes {
    private val base64 = "[A-Za-z0-9+/=]*"
    private val ydke = Regex(Regex.escape(YdkeCodec.PREFIX) + "$base64!$base64!$base64!?")

    /** A deck file's section markers: a bare number is not a deck, a `#main` over it is. */
    private val markers = setOf("#main", "#extra", "!side", "#side", "!main", "!extra")

    fun read(text: String): YdkParseResult? {
        val found = ydke.find(text)?.value
        val parsed = if (found != null) {
            YdkeCodec.decode(found)?.let { YdkParseResult(YdkDocument(it)) }
        } else if (text.lineSequence().any { it.trim().lowercase() in markers }) {
            YdkCodec.parse(text)
        } else {
            null
        }
        return parsed?.takeIf { it.document.deck.totalCards > 0 }
    }
}

/**
 * The deck as a list a person reads — the shape decklists are posted in:
 *
 * ```
 * Main Deck (40)
 * 3 Ash Blossom & Joyous Spring
 * …
 * ```
 *
 * Each card once, with its count, in the order it first appears; an empty
 * section is left out. A card the pool does not know is written by passcode.
 */
object DeckText {
    fun write(deck: Deck, name: (CardId) -> String?): String = buildList {
        listOf("Main Deck" to deck.main, "Extra Deck" to deck.extra, "Side Deck" to deck.side).forEach { (title, ids) ->
            if (ids.isEmpty()) return@forEach
            if (isNotEmpty()) add("")
            add("$title (${ids.size})")
            val counts = LinkedHashMap<CardId, Int>()
            ids.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            counts.forEach { (id, n) -> add("$n ${name(id) ?: id.value.toString()}") }
        }
    }.joinToString("\n")
}
