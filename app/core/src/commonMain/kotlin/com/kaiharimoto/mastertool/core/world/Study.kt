package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.round

/**
 * One instrument's run as it is written up (1.0.97): the lines the terminal shows — the question first, then the
 * method, the findings and every warning — and the boards it pins, each with a note on how it was made. The
 * instruments share it so they read alike, and so Ai's own read like them ([Instruments.GUIDE]).
 */
internal class Study {
    val lines = mutableListOf<String>()
    val boards = mutableListOf<WorldApi.Shown>()
    private val warned = mutableSetOf<String>()

    fun say(text: String) {
        lines += text
    }

    /** What reading the deck found worth saying, once each, marked so it stands out from the findings. */
    fun warn(read: Instruments.DeckRead) = read.notes.forEach(::warn)

    fun warn(text: String) {
        if (warned.add(text)) lines += "  ! $text"
    }

    fun board(id: String, title: String, kind: BoardKind, payload: String, note: String) {
        boards += Instruments.shown(id, title, kind, payload, note)
    }

    fun stat(id: String, title: String, value: String, label: String, detail: String, note: String) =
        board(id, title, BoardKind.STAT, WorldStat.encode(WorldStat(value.take(24), label.take(120), detail.take(400))), note)

    fun table(id: String, title: String, columns: List<String>, rows: List<List<String>>, note: String) =
        board(id, title, BoardKind.TABLE, WorldTable.encode(WorldTable("", columns, rows.take(WorldTable.MAX_ROWS))), note)

    fun chart(id: String, title: String, type: ChatChart.Type, labels: List<String>, series: List<ChatChart.Series>, unit: String, note: String) =
        board(
            id, title, BoardKind.CHART,
            WorldChart.encode(WorldChart.Bars(ChatChart.Chart(type, "", labels.take(ChatChart.MAX_LABELS).map { it.take(40) }, series.take(ChatChart.MAX_SERIES).map { s -> s.copy(values = s.values.take(ChatChart.MAX_LABELS)) }, unit))),
            note,
        )

    fun done(answer: JsonElement) = Instruments.Result(lines.toList(), boards.toList(), answer)

    companion object {
        /** A chance as a chart's percentage, to a tenth. */
        fun pc(p: Double): Double = round(p * 1000) / 10

        fun nums(xs: List<Double>) = JsonArray(xs.map { if (it.isFinite()) JsonPrimitive(it) else kotlinx.serialization.json.JsonNull })

        fun strs(xs: List<String>) = JsonArray(xs.map(::JsonPrimitive))

        /** An id from words: lower case, letters, digits and dashes. */
        fun slug(text: String): String = text.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(32).ifEmpty { "x" }

        fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject = JsonObject(pairs.filter { it.second != null }.associate { it.first to it.second!! })
    }
}
