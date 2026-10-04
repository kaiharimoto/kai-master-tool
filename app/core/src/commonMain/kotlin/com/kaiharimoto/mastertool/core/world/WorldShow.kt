package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What a script or Ai asks a world to show, read strictly where a wrong picture would mislead and leniently
 * everywhere else — the chat's ```chart rule, kept. [ShowSpec.parse] takes a kind and its JSON (or a fence's text)
 * and gives the board's payload, normalised, or why not in words a model can act on. The painters read the payload
 * back through the same types, so what was checked is what is drawn.
 */
object ShowSpec {
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A board's kind and payload from [kind] and its [body]: JSON for the drawn kinds, text for the rest. */
    fun parse(kind: String, body: String): Result<Pair<BoardKind, String>> = runCatching {
        val k = kindOf(kind) ?: throw IllegalArgumentException(
            "unknown kind “$kind” — one of markdown, chart, graph, flow, table, stat, cards, board, line, image",
        )
        val payload = when (k) {
            BoardKind.CHART -> WorldChart.parse(body).getOrThrow().let { WorldChart.encode(it) }
            BoardKind.GRAPH, BoardKind.FLOW -> WorldGraph.parse(body).getOrThrow().let { WorldGraph.encode(it) }
            BoardKind.TABLE -> WorldTable.parse(body).getOrThrow().let { WorldTable.encode(it) }
            BoardKind.STAT -> WorldStat.parse(body).getOrThrow().let { WorldStat.encode(it) }
            BoardKind.IMAGE -> WorldPaths.safe(body.trim().removePrefix("out/").let { "out/$it" })
                ?: throw IllegalArgumentException("an image is a path under out/, like out/plot.png")
            else -> body.trim().also { require(it.isNotEmpty()) { "nothing to show" } }.take(MAX_TEXT)
        }
        k to payload
    }

    fun kindOf(name: String): BoardKind? = when (name.trim().lowercase()) {
        "markdown", "md", "text", "note" -> BoardKind.MARKDOWN
        "chart", "bar", "line-chart", "plot" -> BoardKind.CHART
        "graph", "web", "network" -> BoardKind.GRAPH
        "flow", "flowchart", "tree" -> BoardKind.FLOW
        "table" -> BoardKind.TABLE
        "stat", "number" -> BoardKind.STAT
        "cards" -> BoardKind.CARDS
        "board", "field" -> BoardKind.BOARD
        "line", "combo" -> BoardKind.LINE
        "image", "picture", "png" -> BoardKind.IMAGE
        else -> null
    }

    const val MAX_TEXT = 20_000

    internal fun str(o: JsonObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }.orEmpty()

    internal fun num(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.toDoubleOrNull()
    }

    internal fun obj(body: String): JsonObject = try {
        json.parseToJsonElement(body.trim()).jsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("not a JSON object: ${e.message?.take(120)}")
    }
}

/** A world's charts: the chat's bars, lines and stacks ([ChatChart]), and what a study needs beyond them. */
sealed interface WorldChart {
    val title: String

    data class Bars(val chart: ChatChart.Chart) : WorldChart {
        override val title: String get() = chart.title
    }

    data class Point(val x: Double, val y: Double, val label: String = "")

    data class Cloud(val name: String, val points: List<Point>)

    /** Points: a simulation's runs, a deck's cards by two numbers. */
    data class Scatter(override val title: String, val x: String, val y: String, val series: List<Cloud>) : WorldChart

    /** A grid of numbers in shades: a matchup matrix, a pair of cards seen together. */
    data class Heatmap(
        override val title: String,
        val rows: List<String>,
        val cols: List<String>,
        val values: List<List<Double>>,
        val unit: String = "",
    ) : WorldChart {
        val min: Double get() = values.flatten().minOrNull() ?: 0.0
        val max: Double get() = values.flatten().maxOrNull() ?: 0.0
    }

    companion object {
        const val MAX_POINTS = 5000
        const val MAX_CLOUDS = 6
        const val MAX_CELLS = 60

        fun parse(body: String): Result<WorldChart> = runCatching {
            val o = ShowSpec.obj(body)
            when (ShowSpec.str(o, "type").lowercase().trim()) {
                "scatter", "points", "xy" -> scatter(o)
                "heatmap", "heat", "matrix", "grid" -> heatmap(o)
                "histogram", "hist" -> histogram(o)
                else -> Bars(ChatChart.parse(body).getOrThrow())
            }
        }

        private fun scatter(o: JsonObject): Scatter {
            fun cloud(name: String, arr: JsonArray?): Cloud {
                val pts = arr.orEmpty().map { e ->
                    when (e) {
                        is JsonArray -> Point(
                            ShowSpec.num(e.getOrNull(0)) ?: throw IllegalArgumentException("a point is [x, y]"),
                            ShowSpec.num(e.getOrNull(1)) ?: throw IllegalArgumentException("a point is [x, y]"),
                            (e.getOrNull(2) as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        )
                        is JsonObject -> Point(
                            ShowSpec.num(e["x"]) ?: throw IllegalArgumentException("a point needs x"),
                            ShowSpec.num(e["y"]) ?: throw IllegalArgumentException("a point needs y"),
                            ShowSpec.str(e, "label", "name"),
                        )
                        else -> throw IllegalArgumentException("a point is [x, y] or {x, y}")
                    }
                }
                require(pts.all { it.x.isFinite() && it.y.isFinite() }) { "points are finite numbers" }
                return Cloud(name, pts)
            }
            val clouds = when (val s = o["series"]) {
                is JsonArray -> s.map { e ->
                    val so = e as? JsonObject ?: throw IllegalArgumentException("each series is an object")
                    cloud(ShowSpec.str(so, "name"), (so["points"] ?: so["data"]) as? JsonArray)
                }
                else -> listOf(cloud(ShowSpec.str(o, "name"), (o["points"] ?: o["data"]) as? JsonArray))
            }
            require(clouds.isNotEmpty() && clouds.any { it.points.isNotEmpty() }) { "a scatter needs points" }
            require(clouds.size <= MAX_CLOUDS) { "at most $MAX_CLOUDS series" }
            require(clouds.sumOf { it.points.size } <= MAX_POINTS) { "at most $MAX_POINTS points — sample them" }
            return Scatter(ShowSpec.str(o, "title"), ShowSpec.str(o, "x", "xLabel"), ShowSpec.str(o, "y", "yLabel"), clouds)
        }

        private fun heatmap(o: JsonObject): Heatmap {
            val rows = (o["rows"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                ?: throw IllegalArgumentException("a heatmap needs rows")
            val cols = (o["cols"] ?: o["columns"]) as? JsonArray
                ?: throw IllegalArgumentException("a heatmap needs cols")
            val colNames = cols.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val values = ((o["values"] ?: o["data"]) as? JsonArray ?: throw IllegalArgumentException("a heatmap needs values"))
                .map { r -> (r as? JsonArray ?: throw IllegalArgumentException("values are rows of numbers")).map { v -> ShowSpec.num(v) ?: Double.NaN } }
            require(rows.isNotEmpty() && colNames.isNotEmpty()) { "a heatmap needs rows and cols" }
            require(rows.size <= MAX_CELLS && colNames.size <= MAX_CELLS) { "at most $MAX_CELLS rows and cols" }
            require(values.size == rows.size && values.all { it.size == colNames.size }) {
                "values are ${rows.size} rows of ${colNames.size} numbers"
            }
            return Heatmap(ShowSpec.str(o, "title"), rows, colNames, values, ShowSpec.str(o, "unit"))
        }

        /** Raw values binned into a bar chart, so a histogram is drawn and checked as one. */
        private fun histogram(o: JsonObject): Bars {
            val xs = ((o["values"] ?: o["data"]) as? JsonArray ?: throw IllegalArgumentException("a histogram needs values"))
                .map { ShowSpec.num(it) ?: throw IllegalArgumentException("values are numbers") }
            require(xs.isNotEmpty()) { "a histogram needs values" }
            val bins = (ShowSpec.num(o["bins"])?.toInt() ?: 10).coerceIn(1, ChatChart.MAX_LABELS)
            val (edges, counts) = WorldStats.histogram(xs, bins)
            val width = if (edges.size > 1) edges[1] - edges[0] else 1.0
            val labels = edges.map { ChatChart.label(it, "") + "–" + ChatChart.label(it + width, "") }
            return Bars(
                ChatChart.Chart(
                    ChatChart.Type.BAR,
                    ShowSpec.str(o, "title"),
                    labels,
                    listOf(ChatChart.Series(ShowSpec.str(o, "name").ifBlank { "count" }, counts.map { it.toDouble() })),
                ),
            )
        }

        fun encode(c: WorldChart): String = when (c) {
            is Bars -> buildJsonObject {
                put("type", c.chart.type.name.lowercase())
                put("title", c.chart.title)
                put("unit", c.chart.unit)
                put("labels", buildJsonArray { c.chart.labels.forEach { add(JsonPrimitive(it)) } })
                put("series", buildJsonArray {
                    c.chart.series.forEach { s ->
                        add(buildJsonObject {
                            put("name", s.name)
                            put("values", buildJsonArray { s.values.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                })
            }
            is Scatter -> buildJsonObject {
                put("type", "scatter")
                put("title", c.title)
                put("x", c.x)
                put("y", c.y)
                put("series", buildJsonArray {
                    c.series.forEach { s ->
                        add(buildJsonObject {
                            put("name", s.name)
                            put("points", buildJsonArray {
                                s.points.forEach { p ->
                                    add(buildJsonArray { add(JsonPrimitive(p.x)); add(JsonPrimitive(p.y)); if (p.label.isNotEmpty()) add(JsonPrimitive(p.label)) })
                                }
                            })
                        })
                    }
                })
            }
            is Heatmap -> buildJsonObject {
                put("type", "heatmap")
                put("title", c.title)
                put("unit", c.unit)
                put("rows", buildJsonArray { c.rows.forEach { add(JsonPrimitive(it)) } })
                put("cols", buildJsonArray { c.cols.forEach { add(JsonPrimitive(it)) } })
                put("values", buildJsonArray { c.values.forEach { r -> add(buildJsonArray { r.forEach { add(JsonPrimitive(it)) } }) } })
            }
        }.toString()
    }
}

/** A web of cards (or of anything): who leads to whom. Drawn as a [BoardKind.GRAPH] web or a [BoardKind.FLOW] chart. */
data class WorldGraph(val title: String, val nodes: List<Node>, val edges: List<Edge>) {
    /** [group] sorts nodes into colours; [weight] sizes them (1 is plain); [card] draws a card's art in it. */
    data class Node(val id: String, val label: String = id, val group: String = "", val weight: Double = 1.0, val card: Boolean = false)

    data class Edge(val from: String, val to: String, val label: String = "", val weight: Double = 1.0)

    companion object {
        const val MAX_NODES = 150
        const val MAX_EDGES = 400

        fun parse(body: String): Result<WorldGraph> = runCatching {
            val o = ShowSpec.obj(body)
            val edges = (o["edges"] ?: o["links"]) as? JsonArray ?: JsonArray(emptyList())
            val es = edges.map { e ->
                when (e) {
                    is JsonArray -> Edge(
                        (e.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("an edge is [from, to, label]"),
                        (e.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("an edge is [from, to, label]"),
                        (e.getOrNull(2) as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    )
                    is JsonObject -> Edge(
                        ShowSpec.str(e, "from", "source").ifEmpty { throw IllegalArgumentException("an edge needs from") },
                        ShowSpec.str(e, "to", "target").ifEmpty { throw IllegalArgumentException("an edge needs to") },
                        ShowSpec.str(e, "label", "verb"),
                        ShowSpec.num(e["weight"]) ?: 1.0,
                    )
                    else -> throw IllegalArgumentException("an edge is [from, to, label] or {from, to, label}")
                }
            }.filter { it.from.isNotBlank() && it.to.isNotBlank() }
            val given = ((o["nodes"]) as? JsonArray).orEmpty().map { e ->
                when (e) {
                    is JsonPrimitive -> Node(e.contentOrNull.orEmpty())
                    is JsonObject -> {
                        val id = ShowSpec.str(e, "id", "name").ifEmpty { throw IllegalArgumentException("a node needs an id") }
                        Node(
                            id = id,
                            label = ShowSpec.str(e, "label").ifEmpty { id },
                            group = ShowSpec.str(e, "group", "role"),
                            weight = (ShowSpec.num(e["weight"]) ?: ShowSpec.num(e["size"]) ?: 1.0).coerceIn(0.2, 5.0),
                            card = (e["card"] as? JsonPrimitive)?.contentOrNull == "true",
                        )
                    }
                    else -> throw IllegalArgumentException("a node is a name or {id, label, group}")
                }
            }.filter { it.id.isNotBlank() }.distinctBy { it.id }
            val known = given.map { it.id }.toSet()
            val implied = (es.map { it.from } + es.map { it.to }).distinct().filter { it !in known }.map { Node(it) }
            val nodes = given + implied
            require(nodes.isNotEmpty()) { "a graph needs nodes or edges" }
            require(nodes.size <= MAX_NODES) { "at most $MAX_NODES nodes" }
            require(es.size <= MAX_EDGES) { "at most $MAX_EDGES edges" }
            WorldGraph(ShowSpec.str(o, "title"), nodes, es)
        }

        fun encode(g: WorldGraph): String = buildJsonObject {
            put("title", g.title)
            put("nodes", buildJsonArray {
                g.nodes.forEach { n ->
                    add(buildJsonObject {
                        put("id", n.id)
                        if (n.label != n.id) put("label", n.label)
                        if (n.group.isNotEmpty()) put("group", n.group)
                        if (n.weight != 1.0) put("weight", n.weight)
                        if (n.card) put("card", "true")
                    })
                }
            })
            put("edges", buildJsonArray {
                g.edges.forEach { e ->
                    add(buildJsonObject {
                        put("from", e.from)
                        put("to", e.to)
                        if (e.label.isNotEmpty()) put("label", e.label)
                        if (e.weight != 1.0) put("weight", e.weight)
                    })
                }
            })
        }.toString()
    }
}

data class WorldTable(val title: String, val columns: List<String>, val rows: List<List<String>>) {
    companion object {
        const val MAX_ROWS = 300
        const val MAX_COLUMNS = 20

        fun parse(body: String): Result<WorldTable> = runCatching {
            val o = ShowSpec.obj(body)
            val rowsRaw = (o["rows"] ?: o["data"]) as? JsonArray ?: throw IllegalArgumentException("a table needs rows")
            val given = (o["columns"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            // Rows of objects name their own columns, in the order first seen.
            val columns = given ?: rowsRaw.filterIsInstance<JsonObject>().flatMap { it.keys }.distinct()
            fun cell(e: JsonElement?): String = when (e) {
                null -> ""
                is JsonPrimitive -> e.contentOrNull.orEmpty()
                else -> e.toString()
            }
            val rows = rowsRaw.map { r ->
                when (r) {
                    is JsonArray -> r.map(::cell)
                    is JsonObject -> columns.map { cell(r[it]) }
                    else -> listOf(cell(r))
                }
            }
            require(columns.isNotEmpty() || rows.isNotEmpty()) { "a table needs columns or rows" }
            require(rows.size <= MAX_ROWS) { "at most $MAX_ROWS rows" }
            val width = maxOf(columns.size, rows.maxOfOrNull { it.size } ?: 0)
            require(width <= MAX_COLUMNS) { "at most $MAX_COLUMNS columns" }
            WorldTable(
                ShowSpec.str(o, "title"),
                columns + List(width - columns.size) { "" },
                rows.map { it + List(width - it.size) { "" } },
            )
        }

        fun encode(t: WorldTable): String = buildJsonObject {
            put("title", t.title)
            put("columns", buildJsonArray { t.columns.forEach { add(JsonPrimitive(it)) } })
            put("rows", buildJsonArray { t.rows.forEach { r -> add(buildJsonArray { r.forEach { add(JsonPrimitive(it)) } }) } })
        }.toString()
    }
}

/** One number worth a board of its own: "63 %", "Opens a starter", "of 100,000 seeded hands". */
data class WorldStat(val value: String, val label: String, val detail: String = "") {
    companion object {
        fun parse(body: String): Result<WorldStat> = runCatching {
            val o = ShowSpec.obj(body)
            val value = ShowSpec.str(o, "value").ifEmpty { throw IllegalArgumentException("a stat needs a value") }
            WorldStat(value.take(24), ShowSpec.str(o, "label", "title").take(120), ShowSpec.str(o, "detail").take(400))
        }

        fun encode(s: WorldStat): String = buildJsonObject {
            put("value", s.value)
            put("label", s.label)
            if (s.detail.isNotEmpty()) put("detail", s.detail)
        }.toString()
    }
}
