package com.kaiharimoto.mastertool.core.ai.report.guide

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.CARD
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK06
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK12
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK25
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK45
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK70
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK80
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.PAPER
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.two
import com.kaiharimoto.mastertool.core.pdf.LineCap
import com.kaiharimoto.mastertool.core.pdf.PdfPage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reader's guide's pictures of its ideas (1.0.67), each drawn from data on a [Phone] at a place
 * and a width, each returning the height it took. Lines, rectangles, curves and type; card art the
 * only colour. What each shows:
 *
 * - [unitChart] — the forty cards as forty cells, one fill a role (Isotype: more is more cells);
 * - [bigNumber] — one number to remember, on a 0–100 bar with its target marked (Karsten);
 * - [engineMap] — how the cards find each other, rows down the page, hubs heavier;
 * - [flow] — a line step by step, its choke points a bar across it with what to do instead;
 * - [lanes] — a turn in two lanes, yours and theirs: where the deck's plays really happen;
 * - [board] — the field: zones, the cards face up, the sets face down; small multiples of it;
 * - [ledger] — a matchup's siding as signed counts, their key card, your plan;
 * - [hands] — sample opening hands, each judged;
 * - [checklist] — boxes to tick before you pass (Gawande's do-confirm).
 */

/** Which kind of card a name is, when the guide draws a field: M, S or T; null when unknown. */
typealias CardKind = (String) -> Char?

/** A card's name short enough to sit under its tile: "Arianna", "Lady Labrynth", "Stovie Torbie". */
fun Phone.short(name: String): String {
    // Cut at the first " the " or " of ": "Lady Labrynth of the Silver Castle" is Lady Labrynth.
    val at = listOf(" the ", " of ").map { name.indexOf(it) }.filter { it > 0 }.minOrNull()
    var s = if (at != null) name.substring(0, at) else name
    // The archetype's own word in front is said by the page already: "Labrynth Chandraglier" is Chandraglier.
    val words = s.split(' ')
    if (words.size >= 2 && words.first().equals(deck, ignoreCase = true)) s = words.drop(1).joinToString(" ")
    return s
}

// ---- marks ----------------------------------------------------------------------------------

/** A straight arrow from (x1, y1) to (x2, y2), its head filled. */
fun Phone.arrow(p: PdfPage, x1: Float, y1: Float, x2: Float, y2: Float, gray: Float = INK, width: Float = 0.9f, head: Float = 5f, dash: Float? = null) {
    val a = atan2(y2 - y1, x2 - x1)
    val bx = x2 - head * cos(a)
    val by = y2 - head * sin(a)
    p.line(x1, y1, bx, by, gray, width, dash)
    arrowHead(p, x2, y2, a, head, gray)
}

/** A curved arrow: down from (x1, y1) and into (x2, y2) from above, its head filled. */
fun Phone.curve(p: PdfPage, x1: Float, y1: Float, x2: Float, y2: Float, gray: Float = INK, width: Float = 0.9f, head: Float = 5f) {
    val bend = (y2 - y1) * 0.5f
    // The curve stops at the head's base, coming straight down into it.
    val endY = y2 - head
    p.stroke(p.path { moveTo(x1, y1); curveTo(x1, y1 + bend, x2, endY - bend, x2, endY) }, gray, width)
    arrowHead(p, x2, y2, (kotlin.math.PI / 2).toFloat(), head, gray)
}

private fun arrowHead(p: PdfPage, x: Float, y: Float, a: Float, head: Float, gray: Float) {
    val spread = 0.42f
    val l = x - head * cos(a - spread) to y - head * sin(a - spread)
    val r = x - head * cos(a + spread) to y - head * sin(a + spread)
    p.fill(p.path { polygon(listOf(x to y, l, r)) }, gray)
}

/** A note on the annotation layer: mono words, a hairline to what they are about, a dot on it. */
fun Phone.note(p: PdfPage, text: String, x: Float, top: Float, width: Float, toX: Float? = null, toY: Float? = null): Float {
    val h = para(text, x, top, width, mono, 7f, 10f, INK70, p = p)
    if (toX != null && toY != null) {
        val fromX = if (toX < x) x - 3 else x + width + 3
        p.line(fromX, top + 4, toX, toY, INK45, 0.4f)
        p.fill(p.path { circle(toX, toY, 1.6f) }, INK)
    }
    return h
}

/** A small framed label: [text] in micro caps, ink on paper or, [solid], paper on ink. */
fun Phone.chip(p: PdfPage, text: String, x: Float, top: Float, solid: Boolean = false): Float {
    val w = microWidth(text, 6.5f) + 10f
    if (solid) p.fillRect(x, top, w, 13f, INK) else p.strokeRect(x, top, w, 13f, INK, 0.7f)
    micro(p, text, x + 5f, top + 9f, if (solid) PAPER else INK, 6.5f)
    return w
}

// ---- the deck at a glance -------------------------------------------------------------------

/** The forty cards as forty card-shaped cells, filled by role, with the key under them. */
fun Phone.unitChart(facts: GuideFacts, x: Float, top: Float, w: Float, p: PdfPage = page, key: Boolean = true): Float {
    val across = if (facts.cells.size > 20) 20 else 10
    val gap = 2f
    val cw = (w - (across - 1) * gap) / across
    val ch = cw * CARD
    facts.cells.forEachIndexed { i, role ->
        val cx = x + (i % across) * (cw + gap)
        val cy = top + (i / across) * (ch + gap)
        cell(p, facts.roles.getOrNull(role)?.fill ?: GuideFacts.Fill.DOT, cx, cy, cw, ch)
    }
    val rows = (facts.cells.size + across - 1) / across
    var y = top + rows * (ch + gap) + 6
    if (key && facts.roles.isNotEmpty()) {
        val per = if (facts.roles.size <= 4) facts.roles.size else 3
        val kw = w / per
        facts.roles.forEachIndexed { i, r ->
            val kx = x + (i % per) * kw
            val ky = y + (i / per) * 18f
            cell(p, r.fill, kx, ky, 8f, 11f)
            p.text(mono, 8f, kx + 13f, ky + 9f, r.count.toString(), INK)
            p.text(regular, 8f, kx + 13f + mono.width(r.count.toString(), 8f) + 4f, ky + 9f, fit(r.name, regular, 8f, kw - 34f), INK70)
        }
        y += ((facts.roles.size + per - 1) / per) * 18f
    }
    return y - top
}

private fun Phone.cell(p: PdfPage, fill: GuideFacts.Fill, x: Float, y: Float, w: Float, h: Float) {
    when (fill) {
        GuideFacts.Fill.SOLID -> p.fillRect(x, y, w, h, INK)
        GuideFacts.Fill.HATCH -> {
            p.clip(x, y, w, h) {
                var i = -h
                while (i < w) {
                    p.line(x + i, y + h, x + i + h, y, INK, 0.7f)
                    i += 3.2f
                }
            }
            p.strokeRect(x, y, w, h, INK, 0.7f)
        }
        GuideFacts.Fill.OUTLINE -> p.strokeRect(x, y, w, h, INK, 1.1f)
        GuideFacts.Fill.CROSS -> {
            p.strokeRect(x, y, w, h, INK45, 0.6f)
            p.line(x + 2, y + 2, x + w - 2, y + h - 2, INK45, 0.6f)
            p.line(x + w - 2, y + 2, x + 2, y + h - 2, INK45, 0.6f)
        }
        GuideFacts.Fill.DOT -> {
            p.strokeRect(x, y, w, h, INK25, 0.5f)
            p.fill(p.path { circle(x + w / 2, y + h / 2, minOf(w, h) / 6) }, INK45)
        }
    }
}

/** One number to remember: its label, the numeral, a 0–100 bar with [target] marked. */
fun Phone.bigNumber(value: Double, label: String, x: Float, top: Float, w: Float, target: Double? = 0.75, p: PdfPage = page, size: Float = 46f): Float {
    micro(p, label, x, top + 7f, INK45)
    val text = GuideFacts.percent(value)
    p.text(mono, size, x - size * 0.04f, top + 14f + size * 0.78f, text, INK, tracking = -size * 0.04f)
    val by = top + 14f + size * 0.9f
    p.fillRect(x, by, w, 4f, INK12)
    p.fillRect(x, by, (w * value.toFloat()).coerceIn(0f, w), 4f, INK)
    if (target != null) {
        val tx = x + w * target.toFloat()
        p.line(tx, by - 4, tx, by + 8, INK, 0.8f)
        p.text(mono, 6.5f, tx + 2f, by + 13f, "target ${GuideFacts.percent(target)}", INK45)
    }
    return by + 18f - top
}

/** Two bars to compare — "9 starters 74%", "14 starters 90%" — with a target line through both. */
fun Phone.compareBars(rows: List<Pair<String, Double>>, x: Float, top: Float, w: Float, target: Double? = 0.75, p: PdfPage = page, bar: Float = 10f): Float {
    val labelW = 110f
    val barW = w - labelW - 50f
    val step = bar + 18f
    var y = top
    rows.forEachIndexed { i, (label, v) ->
        val strong = i == rows.lastIndex
        p.text(if (strong) medium else regular, 10f, x, y + bar / 2 + 9f, label, if (strong) INK else INK70)
        p.fillRect(x + labelW, y + 5f, barW, bar, INK06)
        p.fillRect(x + labelW, y + 5f, barW * v.toFloat(), bar, if (strong) INK else INK45)
        p.text(mono, 13f, x + labelW + barW + 8f, y + bar / 2 + 10f, GuideFacts.percent(v), if (strong) INK else INK70)
        y += step
    }
    if (target != null) {
        val tx = x + labelW + barW * target.toFloat()
        p.line(tx, top, tx, y + 2f, INK, 0.7f, dash = 2f)
        p.text(mono, 6.5f, tx + 3f, y + 9f, "target ${GuideFacts.percent(target)}", INK45)
        y += 14f
    }
    return y - top
}

// ---- the engine -----------------------------------------------------------------------------

/** How the cards find each other: rows of tiles down the page, arrows with their verbs, hubs heavier. */
fun Phone.engineMap(layout: EngineLayout, x: Float, top: Float, w: Float, p: PdfPage = page, tile: Float = 40f): Float {
    val th = tile * CARD
    val rowH = th + 50f
    val at = HashMap<String, Pair<Float, Float>>()
    layout.rows.forEachIndexed { r, row ->
        val slot = w / row.size
        row.forEachIndexed { i, name ->
            val cx = x + slot * i + slot / 2
            at[name] = cx to top + r * rowH
        }
    }
    // Arrows under the tiles, verbs on paper over the arrows.
    layout.edges.forEach { e ->
        val (fx, fy) = at[e.from] ?: return@forEach
        val (tx, ty) = at[e.to] ?: return@forEach
        if (ty > fy) curve(p, fx, fy + th + 14f, tx, ty - 2f, INK45, 0.8f, 4.5f)
    }
    layout.edges.forEach { e ->
        val (fx, fy) = at[e.from] ?: return@forEach
        val (tx, ty) = at[e.to] ?: return@forEach
        if (ty <= fy || e.verb.isBlank()) return@forEach
        val mx = (fx + tx) / 2
        val my = (fy + th + 14f + ty) / 2
        val vw = mono.width(e.verb, 6.5f)
        p.fillRect(mx - vw / 2 - 2, my - 6f, vw + 4, 9f, PAPER)
        centre(p, mono, 6.5f, mx, my + 1f, e.verb, INK70)
    }
    at.forEach { (name, pos) ->
        val (cx, cy) = pos
        val hub = name in layout.hubs
        card(name, cx - tile / 2, cy, tile, p, line = if (hub) 2f else 0.5f, gray = if (hub) INK else INK12)
        val label = short(name)
        val lines = wrap(label, if (hub) bold else regular, 7f, w / maxOf(1, layout.rows.maxOf { it.size }) - 6f).take(2)
        lines.forEachIndexed { i, l -> centre(p, if (hub) bold else regular, 7f, cx, cy + th + 9f + i * 8.5f, l, INK) }
    }
    return layout.rows.size * rowH - 40f
}

// ---- a line -----------------------------------------------------------------------------------

/**
 * A line down the page: each step its number, card and words; their turn shaded, so where the
 * deck plays shows at a glance; a choke point a heavy bar across the line with what stops it and
 * what to do then. The height it took.
 */
fun Phone.flow(line: ReaderGuide.Line, x: Float, top: Float, w: Float, p: PdfPage = page, chokes: Boolean = true, tile: Float = 28f): Float {
    val th = tile * CARD
    val textX = x + 26f + tile + 10f
    val textW = w - (textX - x)
    // Measured first, so the spine can be drawn under everything in one stroke.
    class Row(val step: ReaderGuide.Step, val top: Float, val phaseH: Float, val height: Float, val chokeH: Float, val theirs: Boolean)
    val rows = mutableListOf<Row>()
    var y = top
    var lastPhase = ""
    line.steps.forEach { s ->
        val theirs = s.phase.startsWith("Their", ignoreCase = true)
        val phaseH = if (s.phase.isNotBlank() && s.phase != lastPhase) 16f else 0f
        if (phaseH > 0) lastPhase = s.phase
        val rowH = maxOf(th, height(s.action, textW, medium, 10f, 13.5f) + 4f) + 10f
        val chokeH = if (chokes && s.stoppedBy.isNotEmpty()) chokeHeight(s, x, w) else 0f
        rows += Row(s, y, phaseH, rowH, chokeH, theirs)
        y += phaseH + rowH + chokeH
    }
    rows.forEach { r -> if (r.theirs) p.fillRect(x, r.top, w, r.phaseH + r.height + r.chokeH, INK06) }
    if (rows.size > 1) p.line(x + 10.5f, rows.first().top + rows.first().phaseH + 7f, x + 10.5f, rows.last().top + rows.last().phaseH + 7f, INK25, 0.8f)
    rows.forEachIndexed { i, r ->
        if (r.phaseH > 0) micro(p, r.step.phase, textX, r.top + 11f, if (r.theirs) INK else INK45)
        val ry = r.top + r.phaseH
        p.fillRect(x + 2f, ry + 1f, 17f, 13f, if (r.theirs) INK else PAPER)
        p.strokeRect(x + 2f, ry + 1f, 17f, 13f, INK, 0.8f)
        centre(p, mono, 7.5f, x + 10.5f, ry + 10.5f, two(i + 1), if (r.theirs) PAPER else INK)
        card(r.step.card, x + 26f, ry, tile, p)
        para(r.step.action, textX, ry, textW, medium, 10f, 13.5f, INK, p = p)
        if (r.chokeH > 0) choke(p, r.step, x, ry + r.height, w)
    }
    return y - top
}

private fun Phone.chokeHeight(s: ReaderGuide.Step, x: Float, w: Float): Float {
    val tx = x + 26f + microWidth("Choke point", 6.5f) + 8f + s.stoppedBy.size * 25f
    val h = if (s.ifStopped.isBlank()) 0f else height("If stopped: " + s.ifStopped, x + w - tx - 4f, regular, 8.5f, 11.5f)
    return maxOf(22f * CARD + 10f, 22f + h) + 8f
}

/** A choke point under its step: a heavy bar across the line, what stops it, and what to do then. */
private fun Phone.choke(p: PdfPage, s: ReaderGuide.Step, x: Float, top: Float, w: Float): Float {
    val t = 22f
    val tileH = t * CARD
    p.fillRect(x, top, w, 2.5f, INK)
    micro(p, "Choke point", x + 26f, top + 13f, INK, 6.5f)
    var tx = x + 26f + microWidth("Choke point", 6.5f) + 8f
    s.stoppedBy.forEach { c ->
        card(c, tx, top + 6f, t, p)
        tx += t + 3f
    }
    val names = s.stoppedBy.joinToString(" · ") { short(it) }
    p.text(bold, 8f, tx + 4f, top + 15f, fit(names, bold, 8f, x + w - tx - 4f), INK)
    val ifW = x + w - tx - 4f
    val h = if (s.ifStopped.isBlank()) 0f else para("If stopped: " + s.ifStopped, tx + 4f, top + 20f, ifW, regular, 8.5f, 11.5f, INK70, p = p)
    return maxOf(tileH + 10f, 22f + h) + 8f
}

/**
 * A turn in two lanes, yours and theirs: each step in its lane, joined in order. The lanes' weight
 * is the lesson — this deck plays on their turn.
 */
fun Phone.lanes(line: ReaderGuide.Line, x: Float, top: Float, w: Float, p: PdfPage = page, tile: Float = 30f): Float {
    val laneW = (w - 12f) / 2
    val th = tile * CARD
    micro(p, "Your turn", x, top + 8f, INK)
    micro(p, "Their turn", x + laneW + 12f, top + 8f, INK)
    p.fillRect(x, top + 13f, laneW, 1.5f, INK)
    p.fillRect(x + laneW + 12f, top + 13f, laneW, 1.5f, INK)
    var y = top + 24f
    val shadeTop = y
    var prev: Pair<Float, Float>? = null
    val heights = line.steps.map { s -> maxOf(th, height(s.action, laneW - tile - 10f, regular, 8.5f, 11.5f)) + 14f }
    val total = heights.sum()
    p.fillRect(x + laneW + 12f, shadeTop, laneW, total, INK06)
    line.steps.forEachIndexed { i, s ->
        val theirs = s.phase.startsWith("Their", ignoreCase = true)
        val lx = if (theirs) x + laneW + 12f else x
        val cx = lx + 6f
        val cy = y + 4f
        prev?.let { (px, py) -> arrow(p, px, py, cx + tile / 2, cy - 1f, INK45, 0.7f, 4f) }
        card(s.card, cx, cy, tile, p)
        p.text(mono, 7f, cx + tile + 6f, cy + 7f, two(i + 1), INK45)
        para(s.action, cx + tile + 6f, cy + 10f, laneW - tile - 16f, regular, 8.5f, 11.5f, INK, p = p)
        prev = cx + tile / 2 to cy + th
        y += heights[i]
    }
    return y - top
}

// ---- the field --------------------------------------------------------------------------------

/**
 * The field: two Extra Monster Zones over five Monster Zones over five Spell & Trap Zones. [up] are
 * placed face up in the Monster Zones from the middle out, [down] set in the Spell & Trap Zones;
 * [mark] is drawn in full ink and the rest quieter, so a frame of a sequence shows what changed.
 */
fun Phone.board(
    up: List<String>, down: List<String>, x: Float, top: Float, w: Float, p: PdfPage = page,
    mark: Set<String>? = null, labels: Boolean = false, active: List<String> = emptyList(),
): Float {
    val gap = w * 0.025f
    val zw = (w - 4 * gap) / 5
    val zh = zw * CARD
    val order = listOf(2, 1, 3, 0, 4)
    fun zone(i: Int, y: Float) = x + i * (zw + gap) to y
    val rows = listOf(top, top + zh + gap * 1.6f, top + 2 * (zh + gap * 1.6f))
    // Extra Monster Zones (2nd and 4th columns), then the main rows.
    listOf(1, 3).forEach { i -> val (zx, zy) = zone(i, rows[0]); p.strokeRect(zx, zy, zw, zh, INK25, 0.5f, dash = 1.5f) }
    (0 until 5).forEach { i ->
        val (mx, my) = zone(i, rows[1])
        p.strokeRect(mx, my, zw, zh, INK25, 0.5f)
        val (sx, sy) = zone(i, rows[2])
        p.strokeRect(sx, sy, zw, zh, INK25, 0.5f)
    }
    fun quiet(name: String) = mark != null && name !in mark
    up.take(5).forEachIndexed { k, name ->
        val (mx, my) = zone(order[k], rows[1])
        if (quiet(name)) p.faded(0.35f) { card(name, mx, my, zw, p) } else card(name, mx, my, zw, p, line = if (mark != null) 1.6f else 0.5f, gray = if (mark != null) INK else INK12)
    }
    down.take(5).forEachIndexed { k, name ->
        val (sx, sy) = zone(order[k], rows[2])
        // Set: the card's art dimmed under a face-down sign, so the reader still knows what it is.
        val draw = {
            card(name, sx, sy, zw, p)
            p.faded(0.55f) { p.fillRect(sx, sy, zw, zh, INK80) }
            centre(p, mono, maxOf(5f, zw * 0.14f), sx + zw / 2, sy + zh / 2 + 2f, "SET", PAPER)
        }
        if (quiet(name)) p.faded(0.35f) { draw() } else draw()
        if (!quiet(name) && mark != null) p.strokeRect(sx, sy, zw, zh, INK, 1.6f)
    }
    // A trap activated this step: face up in its zone, as it resolves.
    active.take(5 - minOf(5, down.size)).forEachIndexed { k, name ->
        val (sx, sy) = zone(order[down.size + k], rows[2])
        card(name, sx, sy, zw, p, line = 1.6f, gray = INK)
        p.fillRect(sx, sy + zh - 9f, zw, 9f, INK)
        centre(p, mono, 5f, sx + zw / 2, sy + zh - 2.5f, "RESOLVES", PAPER)
    }
    var h = rows[2] + zh - top
    if (labels) {
        micro(p, "Extra Monster Zones dashed", x, rows[2] + zh + 12f, INK45, 5.5f)
        h += 14f
    }
    return h
}

/** The field after each step of [line]: small multiples, two across, what changed inked. */
fun Phone.frames(line: ReaderGuide.Line, kind: CardKind, x: Float, top: Float, w: Float, p: PdfPage = page): Float {
    val across = 2
    val gap = 18f
    val fw = (w - gap) / across
    val up = mutableListOf<String>()
    val down = mutableListOf<String>()
    var y = top
    var rowH = 0f
    var before = emptySet<String>()
    line.steps.forEachIndexed { i, s ->
        val trap = kind(s.card).let { it == 'T' || it == 'S' }
        // A set trap named again is activated: face up this frame, gone the next.
        var active = emptyList<String>()
        if (trap) {
            if (s.card in down) {
                down.remove(s.card)
                active = listOf(s.card)
            } else {
                down += s.card
            }
        } else if (s.card !in up) {
            up += s.card
        }
        val last = i == line.steps.lastIndex
        // The last frame is the end board, each card where the frames before put it.
        val showUp = if (last && line.endBoard.isNotEmpty()) up.filter { it in line.endBoard } + line.endBoard.filter { it !in up } else up.toList()
        val showDown = if (last && line.endSet.isNotEmpty()) down.filter { it in line.endSet } + line.endSet.filter { it !in down } else down.toList()
        val now = (showUp + showDown).toSet()
        val changed = (now - before).ifEmpty { setOf(s.card) }
        before = now
        val fx = x + (i % across) * (fw + gap)
        if (i % across == 0 && i > 0) y += rowH + 16f
        p.text(mono, 8f, fx, y + 8f, two(i + 1), INK)
        p.text(regular, 7.5f, fx + 16f, y + 8f, fit(short(s.card), regular, 7.5f, fw - 16f), INK70)
        val bh = board(showUp, showDown, fx, y + 14f, fw, p, mark = changed, active = if (last) emptyList() else active)
        val words = para(s.action, fx, y + 18f + bh, fw, regular, 7.5f, 10f, INK70, max = 3, p = p)
        rowH = 18f + bh + words
    }
    return y + rowH - top
}

// ---- siding, hands, the checklist -------------------------------------------------------------

/** A matchup's siding: its name, the signed counts in and out, their key card, your plan in a line. */
fun Phone.ledger(side: ReaderGuide.Side, facts: GuideFacts.Side, x: Float, top: Float, w: Float, p: PdfPage = page): Float {
    p.fillRect(x, top, w, 1.5f, INK)
    p.text(bold, 15f, x, top + 20f, side.matchup, INK, tracking = -0.3f)
    right(p, mono, 9f, x + w, top + 19f, "±${facts.moved}", INK45)
    val base = top + 30f
    var y = base
    val colW = (w - 12f) / 2
    listOf("In" to facts.ins, "Out" to facts.outs).forEachIndexed { k, (label, cards) ->
        val cx = x + k * (colW + 12f)
        micro(p, label, cx, base + 7f, INK)
        var cy = base + 14f
        cards.forEach { (name, n) ->
            val sign = if (k == 0) "+$n" else "−$n"
            p.text(mono, 9f, cx, cy + 9f, sign, if (k == 0) INK else INK45)
            cy += para(name, cx + 22f, cy, colW - 22f, regular, 8.5f, 11f, if (k == 0) INK else INK70, p = p) + 3f
        }
        y = maxOf(y, cy)
    }
    y += 4f
    if (side.theirChoke.isNotBlank() || side.plan.isNotBlank()) {
        p.line(x, y, x + w, y, INK12, 0.5f)
        y += 8f
        if (side.theirChoke.isNotBlank()) {
            card(side.theirChoke, x, y, 26f, p)
            micro(p, "Their key card", x + 34f, y + 8f, INK45, 6.5f)
            p.text(medium, 9.5f, x + 34f, y + 21f, fit(side.theirChoke, medium, 9.5f, w - 34f), INK)
        }
        val planTop = y + if (side.theirChoke.isNotBlank()) 28f else 0f
        val ph = if (side.plan.isNotBlank()) para(side.plan, x + 34f, planTop, w - 34f, regular, 9.5f, 13f, INK, p = p) else 0f
        y = maxOf(y + if (side.theirChoke.isNotBlank()) 26f * CARD else 0f, planTop + ph) + 6f
    }
    if (side.why.isNotBlank()) y += para(side.why, x, y, w, regular, 8.5f, 12f, INK70, p = p) + 4f
    return y - top
}

/** Sample opening hands, each with its verdict. */
fun Phone.hands(facts: GuideFacts, x: Float, top: Float, w: Float, p: PdfPage = page): Float {
    val tile = (w - 4 * 4f - 92f) / 5
    var y = top
    facts.hands.forEach { hand ->
        chip(p, hand.verdict.label, x, y, solid = hand.verdict == GuideFacts.Verdict.BRICK)
        hand.cards.forEachIndexed { i, c ->
            val cx = x + 92f + i * (tile + 4f)
            if (hand.verdict != GuideFacts.Verdict.BRICK && c == hand.starter) card(c, cx, y, tile, p, line = 1.6f, gray = INK) else card(c, cx, y, tile, p)
        }
        val verdict = when (hand.verdict) {
            GuideFacts.Verdict.STARTS -> "Starts with ${short(hand.starter.orEmpty())}."
            GuideFacts.Verdict.SECOND -> "No starter: it plays going second."
            GuideFacts.Verdict.BRICK -> "Nothing starts. ${GuideFacts.percent(facts.brick)} of hands."
        }
        para(verdict, x, y + 18f, 84f, regular, 8f, 10.5f, INK70, p = p)
        y += tile * CARD + 14f
    }
    return y - top
}

/** "Before you pass": boxes to tick, a hairline between them. */
fun Phone.checklist(items: List<String>, x: Float, top: Float, w: Float, p: PdfPage = page, size: Float = 9.5f): Float {
    var y = top
    items.take(7).forEachIndexed { i, item ->
        p.strokeRect(x, y + 2f, 9f, 9f, INK, 0.9f)
        val h = para(item, x + 18f, y, w - 18f, regular, size, size + 4f, INK, p = p)
        y += h + 6f
        if (i < items.lastIndex) p.line(x + 18f, y - 3f, x + w, y - 3f, INK12, 0.4f)
    }
    return y - top
}

/** A lesson as a pull quote: its number, the maxim in display type, the card beside it. */
fun Phone.maxim(n: Int, lesson: ReaderGuide.Lesson, x: Float, top: Float, w: Float, p: PdfPage = page, size: Float = 30f, tile: Float = 64f): Float {
    p.text(mono, 9f, x, top + 9f, two(n), INK)
    p.fillRect(x + 18f, top + 5f, 24f, 1.2f, INK)
    val textW = if (lesson.card.isNotBlank()) w - tile - 16f else w
    val h = para(lesson.maxim, x, top + 22f, textW, bold, size, size * 1.04f, INK, tracking = -size * 0.025f, p = p)
    if (lesson.card.isNotBlank()) card(lesson.card, x + w - tile, top + 20f, tile, p)
    return maxOf(22f + h, if (lesson.card.isNotBlank()) 20f + tile * CARD else 0f)
}

/** A line in one row: its steps' cards left to right, numbered, an arrow between each — the whole turn at a glance. */
fun Phone.strip(line: ReaderGuide.Line, x: Float, top: Float, w: Float, p: PdfPage = page): Float {
    val n = line.steps.size
    if (n == 0) return 0f
    val gap = 14f
    val tile = minOf(56f, (w - (n - 1) * gap) / n)
    line.steps.forEachIndexed { i, s ->
        val cx = x + i * (tile + gap)
        val theirs = s.phase.startsWith("Their", ignoreCase = true)
        card(s.card, cx, top + 14f, tile, p, line = if (theirs) 1.4f else 0.5f, gray = if (theirs) INK else INK12)
        p.text(mono, 7f, cx, top + 8f, two(i + 1), if (theirs) INK else INK45)
        if (i < n - 1) arrow(p, cx + tile + 2f, top + 14f + tile * CARD / 2, cx + tile + gap - 2f, top + 14f + tile * CARD / 2, INK45, 0.7f, 3.5f)
    }
    val bottom = top + 14f + tile * CARD + 6f
    micro(p, "Framed: on their turn", x, bottom + 8f, INK45, 6f)
    return bottom + 12f - top
}
