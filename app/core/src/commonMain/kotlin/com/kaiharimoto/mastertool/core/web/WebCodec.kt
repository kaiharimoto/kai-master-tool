package com.kaiharimoto.mastertool.core.web

import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A deck as a `.ydkw` carries it: its id in the file, its name, the whole `.ydkx`, and its place in the web. */
data class WebFileDeck(
    val id: String,
    val name: String,
    val document: YdkDocument,
    val mine: Boolean = false,
    val share: Int? = null,
)

/** A web as a file: its name, its notes and its decks, in order. */
data class WebFile(
    val name: String,
    val notes: String = "",
    val decks: List<WebFileDeck> = emptyList(),
)

/**
 * `.ydkw` (kai, 1.0.33: one text file, "sharable among people so they can use it
 * to learn and simulate tournament environments"):
 *
 * ```
 * #ydkw 1
 * #web {"name":"Spring Regional","notes":"…","decks":[{"id":"…","mine":true,"share":20}]}
 *
 * #deck <id> Snake-Eye Fiendsmith
 * #main
 * …
 * #extra
 * …
 * !side
 * …
 * #ydkx-extended
 * {…}
 *
 * #deck <id> Yubel
 * …
 * ```
 *
 * Every `#deck` block is a complete `.ydkx`, groups and all: cut one out and any
 * tool that reads a deck file opens it. No line inside a block can begin
 * `#deck `: the cards are numbers, the markers are fixed, and the extended block
 * is JSON, which never starts a line with `#`. The ids are the decks' own; a web
 * read back into the app is given new ones ([read] keeps them so a siding
 * pattern that names another deck of the web can be pointed at its new id).
 */
object WebCodec {
    const val MAGIC = "#ydkw"
    const val VERSION = 1

    private const val WEB = "#web "
    private const val DECK = "#deck "

    @Serializable
    private data class Header(val name: String = "", val notes: String = "", val decks: List<HeaderDeck> = emptyList())

    @Serializable
    private data class HeaderDeck(val id: String, val mine: Boolean = false, val share: Int? = null)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * The `#web` line, read field by field so one odd value never costs the rest: a share
     * written as a fraction (`0.5`, by hand or by another tool) is that fraction of the field,
     * a share over 1 is a percent rounded to a whole one, a `mine` written as a string reads
     * as one, and a deck with no id is skipped. The strict decode threw on any of these, and
     * the whole header — name, notes, every star and every share — was lost with it.
     */
    private fun header(text: String): Header {
        val obj = runCatching { json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return Header()
        fun str(o: kotlinx.serialization.json.JsonObject, key: String): String? = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        val decks = (obj["decks"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = str(d, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mine = str(d, "mine")?.lowercase() == "true"
            HeaderDeck(id, mine, share(str(d, "share")))
        }
        return Header(str(obj, "name").orEmpty(), str(obj, "notes").orEmpty(), decks)
    }

    /** A share as the app keeps it, a whole percent 0–100, from whatever number was written; null when none. */
    internal fun share(raw: String?): Int? {
        val v = raw?.trim()?.removeSuffix("%")?.toDoubleOrNull() ?: return null
        if (v.isNaN() || v < 0) return null
        val percent = if (v > 0 && v < 1) v * 100 else v
        return kotlin.math.round(percent).toInt().coerceIn(0, 100)
    }

    /** Whether [text] is a `.ydkw`, by its first line: a web opened through a deck's Import is sent to Format. */
    fun isWeb(text: String): Boolean = text.removePrefix("﻿").trimStart().startsWith(MAGIC)

    fun write(file: WebFile): String = buildString {
        append(MAGIC).append(' ').append(VERSION).append('\n')
        val header = Header(file.name, file.notes, file.decks.map { HeaderDeck(it.id, it.mine, it.share) })
        append(WEB).append(json.encodeToString(Header.serializer(), header)).append('\n')
        file.decks.forEach { deck ->
            append('\n')
            append(DECK).append(oneLine(deck.id).replace(' ', '-')).append(' ').append(oneLine(deck.name)).append('\n')
            append(YdkCodec.write(deck.document.deck, extended = deck.document.extended))
        }
    }

    /** The web in [text], or null when it is not a `.ydkw`. A deck the header does not list is kept, at the end. */
    fun read(text: String): WebFile? {
        if (!isWeb(text)) return null
        val lines = text.removePrefix("﻿").lines()
        var header = Header()
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in lines) {
            when {
                blocks.isEmpty() && line.startsWith(WEB) -> header = header(line.removePrefix(WEB))
                line.startsWith(DECK) -> blocks += line.removePrefix(DECK) to mutableListOf()
                blocks.isNotEmpty() -> blocks.last().second += line
            }
        }
        val listed = header.decks.associateBy { it.id }
        val decks = blocks.mapIndexed { i, (title, body) ->
            val id = title.substringBefore(' ').ifBlank { "deck${i + 1}" }
            val name = title.substringAfter(' ', "").trim().ifBlank { "Deck ${i + 1}" }
            val placed = listed[id]
            WebFileDeck(id, name, YdkCodec.parse(body.joinToString("\n")).document, placed?.mine ?: false, placed?.share)
        }
        val order = header.decks.map { it.id }
        return WebFile(
            name = header.name.ifBlank { "Imported web" },
            notes = header.notes,
            decks = decks.sortedBy { deck -> order.indexOf(deck.id).let { if (it < 0) Int.MAX_VALUE else it } },
        )
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()
}
