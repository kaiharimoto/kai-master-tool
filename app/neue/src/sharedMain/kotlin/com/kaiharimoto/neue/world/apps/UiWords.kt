package com.kaiharimoto.neue.world.apps

import com.kaiharimoto.mastertool.core.world.apps.UiNode

/**
 * An app's screen in words, for Ai testing its own app (`world_app press`, `world_app open`): each widget a line,
 * indented as it nests, with its id where it has one — what Ai needs to send the next event — and its value.
 */
object UiWords {
    /** At most this many lines; a larger screen says how many more. */
    const val MAX_LINES = 160

    fun describe(root: UiNode): String {
        val out = ArrayList<String>()
        var more = 0
        fun line(depth: Int, s: String) {
            if (out.size >= MAX_LINES) more++ else out += "  ".repeat(depth) + s
        }
        fun id(id: String?) = id?.let { " [$it]" }.orEmpty()
        fun num(d: Double) = if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        fun walk(n: UiNode, depth: Int) {
            when (n) {
                is UiNode.Col -> n.children.forEach { walk(it, depth) }
                is UiNode.Row -> {
                    line(depth, "row:")
                    n.children.forEach { walk(it, depth + 1) }
                }
                is UiNode.Grid -> {
                    line(depth, "grid of ${n.columns}:")
                    n.children.forEach { walk(it, depth + 1) }
                }
                is UiNode.Section -> {
                    line(depth, "section “${n.title}”:")
                    n.children.forEach { walk(it, depth + 1) }
                }
                is UiNode.Divider, is UiNode.Space -> Unit
                is UiNode.Text -> line(depth, "text: ${n.text.take(200)}")
                is UiNode.Kv -> n.rows.take(40).forEach { (k, v) -> line(depth, "$k: $v") }
                is UiNode.Stat -> line(depth, "stat: ${n.value}" + (if (n.label.isNotBlank()) " — ${n.label}" else "") + (if (n.note.isNotBlank()) " (${n.note})" else ""))
                is UiNode.Note -> line(depth, "note: ${n.text.take(200)}")
                is UiNode.Markdown -> line(depth, "markdown: ${n.text.lineSequence().firstOrNull().orEmpty().take(160)}…")
                is UiNode.Button -> line(depth, "button “${n.label}”${id(n.id)}" + (if (n.primary) " (primary)" else "") + (if (n.disabled) " (disabled)" else "") +
                    (n.open?.let { " opens $it" }.orEmpty()) + (n.copy?.let { " copies text" }.orEmpty()))
                is UiNode.Input -> line(depth, "input “${n.label}”${id(n.id)} = “${n.value}”" + (if (n.number) " (number)" else ""))
                is UiNode.Stepper -> line(depth, "stepper “${n.label}”${id(n.id)} = ${num(n.value)} (${num(n.min)}–${num(n.max)})")
                is UiNode.Slider -> line(depth, "slider “${n.label}”${id(n.id)} = ${num(n.value)} (${num(n.min)}–${num(n.max)})")
                is UiNode.Select -> line(depth, "select “${n.label}”${id(n.id)} = ${n.value ?: "none"} of " + n.options.take(12).joinToString { it.value } + if (n.options.size > 12) "…" else "")
                is UiNode.Segmented -> line(depth, "segmented “${n.label}”${id(n.id)} = ${n.value ?: "none"} of " + n.options.joinToString { it.value })
                is UiNode.Toggle -> line(depth, "toggle “${n.label}”${id(n.id)} = ${n.value}")
                is UiNode.Checks -> line(depth, "checks “${n.label}”${id(n.id)} = [${n.values.joinToString()}] of " + n.options.take(12).joinToString { it.value })
                is UiNode.CardPicker -> line(depth, "card picker “${n.label}”${id(n.id)} = ${n.value ?: "none"} (send a passcode)")
                is UiNode.DeckPicker -> line(depth, "deck picker “${n.label}”${id(n.id)} = ${n.value ?: "none"} (send a deck's id)")
                is UiNode.Table -> {
                    line(depth, "table${id(n.id)}: ${n.columns.joinToString(" | ")}" + (if (n.pickable) " (rows pickable: pick with the row's number from 0)" else ""))
                    n.rows.take(12).forEach { r -> line(depth + 1, r.joinToString(" | ")) }
                    val rest = n.rows.size - 12 + n.more
                    if (rest > 0) line(depth + 1, "… $rest more rows")
                }
                is UiNode.Cards -> line(depth, "cards${id(n.id)}: " + n.cards.take(20).joinToString() + if (n.pickable) " (pickable)" else "")
                is UiNode.Card -> line(depth, "card${id(n.id)}: ${n.card}" + (if (n.label.isNotBlank()) " — ${n.label}" else "") + (if (n.large) " (large)" else "") + if (n.pickable) " (pickable)" else "")
                is UiNode.Board -> line(depth, "board ${n.kind.id}" + (if (n.title.isNotBlank()) " “${n.title}”" else ""))
                is UiNode.Progress -> line(depth, "progress ${(n.value * 100).toInt()}%" + if (n.label.isNotBlank()) " — ${n.label}" else "")
                is UiNode.Empty -> line(depth, "empty: ${n.text}")
                is UiNode.Broken -> line(depth, "BROKEN ${n.kind}: ${n.why}")
                is UiNode.Unknown -> line(depth, "unknown widget “${n.kind}” (a newer version's)")
            }
        }
        walk(root, 0)
        if (more > 0) out += "… $more more lines"
        return out.joinToString("\n").ifEmpty { "(an empty screen)" }
    }
}
