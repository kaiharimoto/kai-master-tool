package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.ShowSpec
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.WorldTable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * An app's screen (§8.4, `UiTreeTest`): what its `view` returned, read into [UiNode]s. Reading never fails: a node that
 * will not read is drawn in its place as [UiNode.Broken], one from a newer build as [UiNode.Unknown], and the limits
 * ([AppLimits]) are held here — [AppLimits.NODES] nodes [AppLimits.DEPTH] deep, strings, rows and options cut — so the
 * window only ever draws a bounded tree. One primary button a screen: a second is drawn subtle.
 */
data class UiTree(val root: UiNode, val nodes: Int = 0, val problems: List<String> = emptyList()) {
    companion object {
        /** The widgets this build draws. */
        val KINDS = setOf(
            "col", "row", "grid", "section", "divider", "space",
            "text", "kv", "stat", "note", "markdown",
            "button", "input", "stepper", "slider", "select", "segmented", "toggle", "checks", "cardPicker", "deckPicker",
            "table", "cards", "board", "progress", "empty",
        )

        /** The board kinds an app may draw with `ui.board` (§8.4); tables, stats and cards have widgets of their own. */
        val BOARD_KINDS = setOf(BoardKind.CHART, BoardKind.GRAPH, BoardKind.FLOW, BoardKind.BOARD, BoardKind.LINE, BoardKind.MARKDOWN)

        fun parse(json: String): UiTree {
            val el = try {
                WorldCodec.json.parseToJsonElement(json)
            } catch (e: Exception) {
                return UiTree(UiNode.Broken("view", "the screen is not JSON"), 1, listOf("the screen is not JSON"))
            }
            return parse(el)
        }

        fun parse(el: JsonElement): UiTree {
            val r = Reader()
            val root = r.node(el, 0)
            return UiTree(root, r.count, r.problems)
        }
    }

    private class Reader {
        var count = 0
        val problems = mutableListOf<String>()
        private var primarySeen = false
        private var tooMany = false

        fun node(el: JsonElement, depth: Int): UiNode {
            count++
            if (count > AppLimits.NODES) {
                if (!tooMany) problems += "the screen has more than ${AppLimits.NODES} parts; the rest are not drawn"
                tooMany = true
                return UiNode.Broken("…", "more than ${AppLimits.NODES} parts")
            }
            if (depth >= AppLimits.DEPTH) return broken("…", "nested more than ${AppLimits.DEPTH} deep")
            return when (el) {
                is JsonPrimitive -> if (el is JsonNull) UiNode.Space(0) else UiNode.Text(str(el.contentOrNull))
                is JsonArray -> UiNode.Col(kids(el, depth))
                is JsonObject -> obj(el, depth)
            }
        }

        private fun broken(kind: String, why: String): UiNode.Broken {
            problems += "$kind: $why"
            return UiNode.Broken(kind, why)
        }

        private fun kids(arr: JsonArray?, depth: Int): List<UiNode> {
            val out = ArrayList<UiNode>()
            for (k in arr.orEmpty()) {
                if (tooMany) break
                out += node(k, depth + 1)
            }
            return out
        }

        private fun obj(o: JsonObject, depth: Int): UiNode {
            val kind = o.s("ui") ?: o.s("type") ?: return broken("?", "a widget names its kind: ui.col(…), ui.text(…)")
            val w = o.i("weight")?.coerceIn(1, 12)
            val kidsOf = { kids((o["children"] ?: o["kids"]) as? JsonArray, depth) }
            return try {
                when (kind) {
                    "col" -> UiNode.Col(kidsOf(), gap(o), w)
                    "row" -> UiNode.Row(kidsOf(), gap(o), w)
                    "grid" -> UiNode.Grid(kidsOf(), (o.i("columns") ?: 2).coerceIn(2, 6), gap(o), w)
                    "section" -> UiNode.Section(o.t("title"), kidsOf(), w)
                    "divider" -> UiNode.Divider(w)
                    "space" -> UiNode.Space((o.i("size") ?: 2).coerceIn(0, 6), w)
                    "text" -> UiNode.Text(o.t("text"), tone(o.s("tone")), o.b("mono") ?: false, w)
                    "kv" -> UiNode.Kv(kv(o["rows"] ?: o["items"]), w)
                    "stat" -> UiNode.Stat(o.t("value"), o.t("label"), o.t("note"), w)
                    "note" -> UiNode.Note(o.t("text"), w)
                    "markdown" -> UiNode.Markdown(o.t("text"), w)
                    "button" -> button(o, w)
                    "input" -> UiNode.Input(
                        id(o, kind), o.t("label"), o.t("value"),
                        number = o.s("kind") == "number" || o["value"].let { it is JsonPrimitive && !it.isString && it.doubleOrNull != null },
                        placeholder = o.t("placeholder"), min = o.d("min"), max = o.d("max"), live = o.b("live") ?: false, hint = o.t("hint"), weight = w,
                    )
                    "stepper" -> {
                        val min = o.d("min") ?: 0.0
                        val max = (o.d("max") ?: 100.0).coerceAtLeast(min)
                        UiNode.Stepper(id(o, kind), o.t("label"), (o.d("value") ?: min).coerceIn(min, max), min, max, (o.d("step") ?: 1.0).takeIf { it > 0 } ?: 1.0, o.t("hint"), w)
                    }
                    "slider" -> {
                        val min = o.d("min") ?: 0.0
                        val max = (o.d("max") ?: 1.0).coerceAtLeast(min)
                        UiNode.Slider(id(o, kind), o.t("label"), (o.d("value") ?: min).coerceIn(min, max), min, max, (o.d("step") ?: 0.0).coerceAtLeast(0.0), o.b("live") ?: false, o.t("hint"), w)
                    }
                    "select" -> UiNode.Select(id(o, kind), o.t("label"), o.s("value"), options(o), w)
                    "segmented" -> {
                        val opts = options(o)
                        if (opts.size !in 2..5) broken(kind, "a segmented control has 2 to 5 choices (it had ${opts.size})")
                        else UiNode.Segmented(id(o, kind), o.t("label"), o.s("value"), opts, w)
                    }
                    "toggle" -> UiNode.Toggle(id(o, kind), o.t("label"), o.b("value") ?: false, w)
                    "checks" -> UiNode.Checks(id(o, kind), o.t("label"), options(o), (o["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(AppLimits.OPTIONS), w)
                    "cardPicker" -> UiNode.CardPicker(id(o, kind), o.t("label"), o.d("value")?.toInt(), w)
                    "deckPicker" -> UiNode.DeckPicker(id(o, kind), o.t("label"), o.s("value"), w)
                    "table" -> table(o, w)
                    "cards" -> UiNode.Cards(o.s("id"), (o["cards"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::str) }.take(AppLimits.OPTIONS), o.b("pickable") ?: false, w)
                    "card" -> {
                        val card = (o["card"] as? JsonPrimitive)?.contentOrNull?.let(::str)?.trim().orEmpty()
                            .ifEmpty { (o["name"] as? JsonPrimitive)?.contentOrNull?.let(::str)?.trim().orEmpty() }
                        require(card.isNotEmpty()) { "ui.card needs a card: its name or its passcode" }
                        UiNode.Card(card, o.s("size") == "large" || o.b("large") == true, o.t("label"), o.s("id")?.take(64), o.b("pickable") ?: false, w)
                    }
                    "board" -> board(o, w)
                    "progress" -> UiNode.Progress((o.d("value") ?: 0.0).let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }, o.t("label"), w)
                    "empty" -> UiNode.Empty(o.t("text").ifEmpty { "Nothing here yet." }, w)
                    else -> UiNode.Unknown(kind.take(40), w)
                }
            } catch (e: IllegalArgumentException) {
                broken(kind, e.message ?: "will not read")
            }
        }

        private fun button(o: JsonObject, w: Int?): UiNode {
            val copy = o.s("copy")?.let(::str)
            val open = o.s("open")?.takeIf { AppLinks.allowed(it) }
            val id = o.s("id")?.take(64)
            require(id != null || copy != null || open != null) { "a button needs an id (or copy, or open)" }
            val wants = o.s("kind") == "primary" || o.s("tone") == "primary" || o.b("primary") == true
            val primary = wants && !primarySeen
            if (primary) primarySeen = true
            return UiNode.Button(id.orEmpty(), o.t("label").ifEmpty { o.t("text") }, primary, copy, open, o.b("disabled") ?: false, w)
        }

        private fun table(o: JsonObject, w: Int?): UiNode {
            val cols = (o["columns"] as? JsonArray).orEmpty().map { str((it as? JsonPrimitive)?.contentOrNull ?: it.toString()) }.take(MAX_COLUMNS)
            val raw = (o["rows"] as? JsonArray).orEmpty()
            val columns = cols.ifEmpty { (raw.firstOrNull() as? JsonObject)?.keys?.toList()?.take(MAX_COLUMNS).orEmpty() }
            val rows = raw.take(AppLimits.ROWS).map { r ->
                when (r) {
                    is JsonArray -> r.take(MAX_COLUMNS).map(::cell)
                    is JsonObject -> columns.map { c -> r[c]?.let(::cell).orEmpty() }
                    else -> listOf(cell(r))
                }
            }
            val opens = raw.take(AppLimits.ROWS).map { r -> ((r as? JsonObject)?.get("open") as? JsonPrimitive)?.contentOrNull?.takeIf { AppLinks.allowed(it) } }
            val cards = WorldTable.cardColumns(o["cards"] ?: o["cardColumns"], columns)
            return UiNode.Table(o.s("id"), columns, rows, (raw.size - AppLimits.ROWS).coerceAtLeast(0), o.b("pickable") ?: false, if (opens.any { it != null }) opens else emptyList(), w, cards)
        }

        private fun cell(e: JsonElement): String = when (e) {
            is JsonNull -> ""
            is JsonPrimitive -> str(e.contentOrNull)
            else -> str(e.toString())
        }

        private fun board(o: JsonObject, w: Int?): UiNode {
            val word = o.s("kind") ?: throw IllegalArgumentException("ui.board needs a kind: chart, graph, flow, board, line or markdown")
            val body = when (val b = o["body"]) {
                null, is JsonNull -> throw IllegalArgumentException("ui.board needs a body")
                is JsonPrimitive -> b.contentOrNull.orEmpty()
                else -> b.toString()
            }
            val k = ShowSpec.kindOf(word)
            require(k == null || k in BOARD_KINDS) { "ui.board draws chart, graph, flow, board, line or markdown; use ui.table, ui.stat or ui.cards for a ${k?.id}" }
            val (kind, payload) = ShowSpec.parse(word, body).getOrElse { throw IllegalArgumentException(it.message ?: "will not read") }
            return UiNode.Board(kind, payload, o.t("title"), w)
        }

        private fun options(o: JsonObject): List<UiOption> = (o["options"] as? JsonArray).orEmpty().take(AppLimits.OPTIONS).mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.contentOrNull?.let { UiOption(str(it), str(it)) }
                is JsonObject -> e.s("value")?.let { v -> UiOption(str(v), str(e.s("label") ?: v)) }
                else -> null
            }
        }

        private fun kv(e: JsonElement?): List<Pair<String, String>> = when (e) {
            is JsonArray -> e.take(AppLimits.ROWS).mapNotNull { r ->
                when (r) {
                    is JsonArray -> str(r.getOrNull(0)?.let(::cell)) to str(r.getOrNull(1)?.let(::cell))
                    is JsonObject -> str(r.s("label") ?: r.s("k")) to str(r["value"]?.let(::cell) ?: r.s("v"))
                    else -> null
                }
            }
            is JsonObject -> e.entries.take(AppLimits.ROWS).map { (k, v) -> str(k) to cell(v) }
            else -> emptyList()
        }

        private fun id(o: JsonObject, kind: String): String =
            o.s("id")?.take(64)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("a $kind needs an id, the name its events carry")

        private fun gap(o: JsonObject) = (o.i("gap") ?: 2).coerceIn(0, 6)

        private fun tone(s: String?) = when (s?.lowercase()) {
            "muted" -> Tone.MUTED
            "strong" -> Tone.STRONG
            else -> Tone.BODY
        }

        private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        private fun JsonObject.t(k: String): String = str(this[k]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() })
        private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it.isFinite() }
        private fun JsonObject.i(k: String): Int? = d(k)?.toInt()
        private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    }
}

private const val MAX_COLUMNS = 24

/** A string as a widget may hold it: at most [AppLimits.STRING] characters, cut with an ellipsis. */
internal fun str(s: String?): String {
    if (s == null) return ""
    return if (s.length <= AppLimits.STRING) s else s.take(AppLimits.STRING - 1) + "…"
}
