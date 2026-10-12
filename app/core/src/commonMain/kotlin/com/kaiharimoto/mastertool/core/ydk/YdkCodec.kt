package com.kaiharimoto.mastertool.core.ydk

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A parsed deck file.
 *
 * [extended] holds the `#ydkx-extended` payload produced by the desktop tool. It
 * is kept as an opaque [JsonObject] and written back verbatim so that features
 * this app does not implement yet — siding patterns, per-card notes, saved view
 * configurations — survive a round trip through the tablet untouched.
 */
data class YdkDocument(
    val deck: Deck,
    val createdBy: String? = null,
    val extended: JsonObject? = null,
) {
    val isYdkx: Boolean get() = extended != null
}

data class YdkParseResult(
    val document: YdkDocument,
    val warnings: List<String> = emptyList(),
)

/**
 * Reads and writes `.ydk` and `.ydkx` files.
 *
 * Parsing is deliberately permissive because deck files come from many editors:
 * section markers may use `#` or `!`, may appear in any order, may be missing
 * entirely, and files routinely carry CRLF endings or a UTF-8 BOM. Anything
 * unrecognised is reported as a warning rather than failing the import — losing
 * a whole decklist because one line was odd is the worse outcome.
 */
object YdkCodec {

    private const val EXTENDED_MARKER = "ydkx-extended"

    /**
     * The payload on one line (I.4, `docs/formats/YDKX.md`): pretty-printed, every number of a group's or a plan's list stood
     * alone on a line, and a simulator that reads a `.ydk` line by line — and does not stop at `#ydkx-extended` — took each
     * for a Side Deck card. One line of JSON reads the same to every build, this app's older ones too.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val parseJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): YdkParseResult {
        val warnings = mutableListOf<String>()
        val sections = mutableMapOf(
            DeckSection.MAIN to mutableListOf<CardId>(),
            DeckSection.EXTRA to mutableListOf(),
            DeckSection.SIDE to mutableListOf(),
        )

        var createdBy: String? = null
        var extended: JsonObject? = null

        // Files with no marker at all are still decklists; treat leading ids as main.
        var current = DeckSection.MAIN

        val lines = text.removePrefix("﻿").split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            index++

            if (line.isEmpty()) continue

            if (line.startsWith("#") || line.startsWith("!")) {
                val marker = line.drop(1).trim().lowercase()
                when {
                    marker == "main" -> current = DeckSection.MAIN
                    marker == "extra" -> current = DeckSection.EXTRA
                    marker == "side" -> current = DeckSection.SIDE

                    marker == EXTENDED_MARKER -> {
                        // Everything from here on is a single JSON document.
                        val payload = lines.drop(index).joinToString("\n").trim()
                        extended = decodeExtended(payload, warnings)
                        index = lines.size
                    }

                    marker.startsWith("created by") ->
                        createdBy = line.drop(1).trim().removePrefix("created by").trim()
                            .takeIf { it.isNotEmpty() }

                    // Any other `#...` line is a comment and is intentionally ignored.
                    else -> Unit
                }
                continue
            }

            val id = CardId.parseOrNull(line)
            if (id != null) {
                sections.getValue(current).add(id)
            } else {
                warnings += "Skipped unrecognised line: \"${line.take(60)}\""
            }
        }

        val deck = Deck(
            main = sections.getValue(DeckSection.MAIN).toList(),
            extra = sections.getValue(DeckSection.EXTRA).toList(),
            side = sections.getValue(DeckSection.SIDE).toList(),
        )

        return YdkParseResult(YdkDocument(deck, createdBy, extended), warnings.toList())
    }

    private fun decodeExtended(payload: String, warnings: MutableList<String>): JsonObject? {
        if (payload.isEmpty()) return null
        return runCatching { parseJson.parseToJsonElement(payload) as? JsonObject }
            .getOrNull()
            ?: null.also {
                warnings += "The #ydkx-extended block could not be read and was dropped."
            }
    }

    /**
     * Serialises a deck.
     *
     * Sections are written in YGOPro's canonical `#main` / `#extra` / `!side`
     * order so the output imports cleanly into EDOPro and every other editor.
     * Parsing is order-independent, so round trips with the desktop tool — which
     * writes side before extra — remain lossless.
     */
    fun write(
        deck: Deck,
        createdBy: String? = null,
        extended: JsonObject? = null,
    ): String = buildString {
        if (!createdBy.isNullOrBlank()) {
            append("#created by ").append(createdBy).append('\n')
        }

        append("#main\n")
        deck.main.forEach { append(it.value).append('\n') }

        append("#extra\n")
        deck.extra.forEach { append(it.value).append('\n') }

        append("!side\n")
        deck.side.forEach { append(it.value).append('\n') }

        if (extended != null) {
            append("#").append(EXTENDED_MARKER).append('\n')
            append(json.encodeToString(JsonObject.serializer(), extended))
            append('\n')
        }
    }

    /** Convenience wrapper preserving whatever extended payload came in. */
    fun write(document: YdkDocument): String =
        write(document.deck, document.createdBy, document.extended)
}
