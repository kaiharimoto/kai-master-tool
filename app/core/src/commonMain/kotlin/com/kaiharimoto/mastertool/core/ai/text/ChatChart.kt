package com.kaiharimoto.mastertool.core.ai.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.pow

/**
 * The charts Ai draws (1.0.46, kai: "I want the AI to draw tables and charts"). A chart is
 * a fenced block of small JSON, so every connection can write one — an API, a CLI, a model
 * on the person's own machine — and the app draws it in its own ink:
 *
 * ```chart
 * {"type": "bar", "title": "Opening a starter", "labels": ["1", "2", "3"],
 *  "series": [{"name": "Going first", "values": [42, 67, 81]}], "unit": "%"}
 * ```
 *
 * Read leniently (a missing type is a bar, a missing name is blank) and checked strictly
 * where a wrong chart would mislead: every series has one value per label, and every value
 * is a number. What does not read is drawn as the code it is, with why.
 */
object ChatChart {
    enum class Type { BAR, HBAR, LINE, STACKED }

    data class Series(val name: String, val values: List<Double>)

    data class Chart(
        val type: Type,
        val title: String,
        val labels: List<String>,
        val series: List<Series>,
        val unit: String = "",
    ) {
        /** The top of the value axis: the largest bar, or the largest stack. */
        val max: Double
            get() = if (type == Type.STACKED) {
                labels.indices.maxOfOrNull { i -> series.sumOf { it.values[i].coerceAtLeast(0.0) } } ?: 0.0
            } else {
                series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0.0 } ?: 0.0
            }

        /** The bottom of the value axis: zero, unless a value is below it. */
        val min: Double get() = minOf(0.0, series.minOfOrNull { s -> s.values.minOrNull() ?: 0.0 } ?: 0.0)
    }

    const val MAX_LABELS = 30
    const val MAX_SERIES = 4

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): Result<Chart> = runCatching {
        val o = json.parseToJsonElement(text.trim()).jsonObject
        val type = when ((o["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()) {
            null, "", "bar", "column", "columns" -> Type.BAR
            "hbar", "barh", "horizontal", "horizontal bar", "row", "rows" -> Type.HBAR
            "line", "lines" -> Type.LINE
            "stacked", "stack", "stacked bar" -> Type.STACKED
            "pie", "donut", "doughnut" -> Type.HBAR // shares read better as bars, and a pie needs colour
            else -> throw IllegalArgumentException("unknown chart type ${o["type"]}")
        }
        val labels = (o["labels"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            ?: throw IllegalArgumentException("a chart needs labels")
        require(labels.isNotEmpty()) { "a chart needs labels" }
        require(labels.size <= MAX_LABELS) { "at most $MAX_LABELS labels" }
        val series = when (val raw = o["series"]) {
            is JsonArray -> raw.map { el ->
                val s = el as? JsonObject ?: throw IllegalArgumentException("each series is an object")
                Series(
                    name = (s["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    values = values(s["values"] ?: s["data"]),
                )
            }
            // One series written flat: {"labels": [...], "values": [...]}.
            null -> listOf(Series((o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), values(o["values"] ?: o["data"])))
            else -> throw IllegalArgumentException("series is a list")
        }
        require(series.isNotEmpty()) { "a chart needs a series" }
        require(series.size <= MAX_SERIES) { "at most $MAX_SERIES series" }
        series.forEach { s ->
            require(s.values.size == labels.size) { "“${s.name.ifBlank { "values" }}” has ${s.values.size} values for ${labels.size} labels" }
        }
        Chart(
            type = type,
            title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            labels = labels,
            series = series,
            unit = (o["unit"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    private fun values(el: kotlinx.serialization.json.JsonElement?): List<Double> {
        val arr = el as? JsonArray ?: throw IllegalArgumentException("values are a list of numbers")
        return arr.map { v ->
            val p = v as? JsonPrimitive ?: throw IllegalArgumentException("values are numbers")
            val d = p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.toDoubleOrNull()
                ?: throw IllegalArgumentException("“${p.contentOrNull}” is not a number")
            require(d.isFinite()) { "values are finite numbers" }
            d
        }
    }

    /** A value as the axis and the bars write it: 42, 4.2, 0.42, with the chart's unit. */
    fun label(value: Double, unit: String): String {
        val r = kotlin.math.round(value * 100) / 100
        val text = if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
        return when {
            unit.isEmpty() -> text
            unit == "%" -> "$text%"
            else -> "$text $unit"
        }
    }

    /** Round axis steps: 1, 2, 5 × 10ⁿ, about [ticks] of them up to [max]. */
    fun ticks(max: Double, ticks: Int = 4): List<Double> {
        if (max <= 0.0) return listOf(0.0)
        val raw = max / ticks
        val mag = 10.0.pow(kotlin.math.floor(kotlin.math.log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * mag >= raw } * mag
        val out = mutableListOf<Double>()
        var v = 0.0
        while (v <= max + step * 0.001) {
            out += v
            v += step
        }
        if (out.last() < max) out += out.last() + step
        return out
    }

}
