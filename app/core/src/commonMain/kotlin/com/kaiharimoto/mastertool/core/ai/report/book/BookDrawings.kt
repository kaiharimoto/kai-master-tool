package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.CARD
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK06
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK12
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK25
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK45
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.INK70
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.PAPER
import com.kaiharimoto.mastertool.core.ai.report.book.Pen.Companion.two

/**
 * A book's blocks as pictures (1.0.67): each picture block laid out for a width as [Drawing]s — the
 * PDF places them on its pages, the app paints them and animates between them. A block that can run
 * long comes as several drawings (a line a step each, its frames one each), so a page can break
 * between them.
 */
class BookArt(
    val faces: Faces,
    val book: GuideBook,
    val facts: GuideFacts,
    val kind: CardKind,
    /** How many copies of a card the deck runs: the deck's own count, or the roles'. */
    val copies: (String) -> Int,
) {
    private val pen = Pen(faces, RecordingInk(), book.title)

    /** One frame of a line's board, with which card each tile is: what an animation moves. */
    data class Frame(val step: Int, val drawing: Drawing)

    /** The block's drawings at [width]; empty for words, which each painter sets itself. */
    fun drawings(block: Block, width: Float): List<Drawing> = when (block) {
        is Block.Text -> emptyList()
        is Block.Lesson -> listOf(pen.record(width) { lessonHead(block, width) })
        is Block.Odds -> listOf(odds(block, width))
        is Block.Cells -> if (facts.deckSize > 0) listOf(pen.record(width) { unitChart(facts, 0f, 0f, width) }) else emptyList()
        is Block.Engine -> if (block.edges.isEmpty()) emptyList() else listOf(pen.record(width) { engineMap(EngineLayout.of(block.edges), 0f, 0f, width) })
        is Block.Line -> lineRows(block.line, width)
        is Block.Lanes -> listOf(pen.record(width) { lanes(block.line, 0f, 0f, width) })
        is Block.Board -> listOf(pen.record(width) {
            val bw = minOf(width, 300f)
            var h = board(block.up, block.down, 0f, 0f, bw)
            if (block.caption.isNotBlank()) h += 8f + para(block.caption, 0f, h + 8f, width, regular, 9f, 13f, INK70)
            h
        })
        is Block.Ledger -> listOf(pen.record(width) {
            ledger(block.side, GuideFacts.Side(block.side.matchup, GuideFacts.counted(block.side.sideIn), GuideFacts.counted(block.side.sideOut)), 0f, 0f, width)
        })
        is Block.Hands -> if (block.hands.isEmpty()) listOf(pen.record(width) { hands(facts, 0f, 0f, width) }) else block.hands.map { h -> pen.record(width) { puzzle(h, width) } }
        is Block.Checklist -> listOf(pen.record(width) { checklistBlock(block, width) })
        is Block.Table -> listOf(pen.record(width) { table(block, width) })
        is Block.CardNotes -> block.cards.map { c -> pen.record(width) { cardNote(c, width) } }
        is Block.Callout -> listOf(pen.record(width) { callout(block, width) })
    }

    /** The board after each play of [line], a frame each, [width] wide. */
    fun frames(line: ReaderGuide.Line, width: Float): List<Frame> =
        frameStates(line, kind).mapIndexed { i, f -> Frame(i, pen.record(width) { frame(line, f, i, width) }) }

    /** A line as rows a step each, its choke points with them: a page can break between steps. */
    fun lineRows(line: ReaderGuide.Line, width: Float): List<Drawing> {
        val rows = mutableListOf<Drawing>()
        var lastPhase = ""
        line.steps.forEachIndexed { i, s ->
            val showPhase = s.phase.isNotBlank() && s.phase != lastPhase
            if (showPhase) lastPhase = s.phase
            rows += pen.record(width) { flowRow(line, i, showPhase, width) }
        }
        return rows
    }

    companion object {
        /**
         * The pictures of [book] worked out from [deck] (its main deck by name) when there is one, else
         * from the book's roles; [kind] from the card pool, else guessed.
         */
        fun of(faces: Faces, book: GuideBook, deck: List<String>? = null, kind: CardKind? = null): BookArt {
            val facts = GuideFacts.of(book.roles, deck)
            val counts: (String) -> Int = if (deck != null) {
                val byName = deck.groupingBy { it.lowercase() }.eachCount()
                ({ byName[it.lowercase()] ?: 0 })
            } else {
                val byName = book.roles.flatMap { it.cards }.associate { it.card.lowercase() to it.copies.coerceAtLeast(1) }
                ({ byName[it.lowercase()] ?: 0 })
            }
            val guessed = guess(book)
            return BookArt(faces, book, facts, { name -> kind?.invoke(name) ?: guessed(name) }, counts)
        }

        /** Without the card pool: a set card, or a card in a role named for traps, is a trap; the rest monsters. */
        fun guess(book: GuideBook): CardKind {
            val sets = book.chapters.flatMap { c -> c.sections.flatMap { s -> s.blocks.filterIsInstance<Block.Line>().flatMap { it.line.endSet } } }.toSet()
            val traps = sets + book.roles.filter { it.name.contains("trap", ignoreCase = true) }.flatMap { r -> r.cards.map { it.card } }
            return { name -> if (name in traps || name.contains("Welcome")) 'T' else 'M' }
        }
    }

    private fun odds(block: Block.Odds, width: Float): Drawing {
        fun count(row: Block.Odds.Row): Int =
            if (row.role.isNotBlank()) facts.roles.firstOrNull { it.name.equals(row.role, ignoreCase = true) }?.count ?: 0
            else row.cards.distinct().sumOf { copies(it) }
        val values = block.rows.map { it.label to GuideFacts.odds(count(it), facts.deckSize, block.hand) }
        return pen.record(width) {
            var y = 0f
            if (block.title.isNotBlank()) {
                micro(block.title, 0f, 7f, INK45)
                y += 14f
            }
            y + if (values.size == 1) bigNumber(values[0].second, values[0].first, 0f, y, width, size = 46f) else compareBars(values, 0f, y, width, bar = 14f)
        }
    }
}

// ---- the pieces the blocks are made of -------------------------------------------------------

/** A lesson's head: its maxim in display type, its card beside it, its number under a rule. */
fun Pen.lessonHead(l: Block.Lesson, width: Float): Float {
    val tile = 72f
    val textW = if (l.card.isNotBlank()) width - tile - 16f else width
    val size = if (wrap(l.maxim, bold, 36f, textW, -0.9f).size <= 3) 36f else 30f
    var y = para(l.maxim, 0f, 0f, textW, bold, size, size * 1.04f, INK, tracking = -size * 0.025f)
    if (l.card.isNotBlank()) {
        card(l.card, width - tile, 0f, tile)
        y = maxOf(y, tile * CARD)
    }
    y += 16f
    if (l.number.isNotBlank()) {
        ink.fillRect(0f, y, width, 1.5f, INK)
        val size2 = minOf(46f, 46f * width / mono.width(l.number, 46f, -1.6f))
        ink.text(mono, size2, -1f, y + 8f + size2 * 0.95f, l.number, INK, tracking = -size2 * 0.035f)
        val nw = mono.width(l.number, size2, -size2 * 0.035f)
        val lw = width - nw - 14f
        if (lw > 90f) {
            para(l.label, nw + 14f, y + 22f, lw, regular, 10f, 13f, INK70)
            y += 18f + size2 * 1.1f
        } else {
            y += 14f + size2 * 1.1f
            y += para(l.label, 0f, y, width, regular, 10f, 13f, INK70) + 8f
        }
    }
    return y
}

/** One step of a line: its number on the spine, its card, its words; their turn shaded; its choke point under it. */
fun Pen.flowRow(line: ReaderGuide.Line, i: Int, showPhase: Boolean, w: Float, tile: Float = 28f): Float {
    val s = line.steps[i]
    val th = tile * CARD
    val textX = 26f + tile + 10f
    val textW = w - textX
    val theirs = s.phase.startsWith("Their", ignoreCase = true)
    val phaseH = if (showPhase) 16f else 0f
    val rowH = maxOf(th, height(s.action, textW, medium, 10f, 13.5f) + 4f) + 10f
    val chokeH = if (s.stoppedBy.isNotEmpty()) chokeHeightAt(s, w) else 0f
    val total = phaseH + rowH + chokeH
    if (theirs) ink.fillRect(0f, 0f, w, total, INK06)
    // The spine: through this row, from the first number and to the last.
    val spineTop = if (i == 0) phaseH + 7f else 0f
    val spineBottom = if (i == line.steps.lastIndex) phaseH + 7f else total
    if (spineBottom > spineTop) ink.line(10.5f, spineTop, 10.5f, spineBottom, INK25, 0.8f)
    if (showPhase) micro(s.phase, textX, 11f, if (theirs) INK else INK45)
    val ry = phaseH
    ink.fillRect(2f, ry + 1f, 17f, 13f, if (theirs) INK else PAPER)
    ink.strokeRect(2f, ry + 1f, 17f, 13f, INK, 0.8f)
    centre(mono, 7.5f, 10.5f, ry + 10.5f, two(i + 1), if (theirs) PAPER else INK)
    card(s.card, 26f, ry, tile)
    para(s.action, textX, ry, textW, medium, 10f, 13.5f, INK)
    ink.tag("step:$i", 0f, 0f, w, phaseH + rowH)
    if (chokeH > 0) {
        chokeAt(s, ry + rowH, w)
        ink.tag("choke:$i", 0f, ry + rowH, w, chokeH)
    }
    return total
}

private fun Pen.chokeHeightAt(s: ReaderGuide.Step, w: Float): Float {
    val tx = 26f + microWidth("Choke point", 6.5f) + 8f + s.stoppedBy.size * 25f
    val h = if (s.ifStopped.isBlank()) 0f else height("If stopped: " + s.ifStopped, w - tx - 4f, regular, 8.5f, 11.5f)
    return maxOf(22f * CARD + 10f, 22f + h) + 8f
}

private fun Pen.chokeAt(s: ReaderGuide.Step, top: Float, w: Float) {
    val t = 22f
    ink.fillRect(0f, top, w, 2.5f, INK)
    micro("Choke point", 26f, top + 13f, INK, 6.5f)
    var tx = 26f + microWidth("Choke point", 6.5f) + 8f
    s.stoppedBy.forEach { c ->
        card(c, tx, top + 6f, t)
        tx += t + 3f
    }
    val names = s.stoppedBy.joinToString(" · ") { short(it) }
    ink.text(bold, 8f, tx + 4f, top + 15f, fit(names, bold, 8f, w - tx - 4f), INK)
    if (s.ifStopped.isNotBlank()) para("If stopped: " + s.ifStopped, tx + 4f, top + 20f, w - tx - 4f, regular, 8.5f, 11.5f, INK70)
}

/** The field after one step, as one frame of a line's small multiples. */
data class FrameState(val up: List<String>, val down: List<String>, val active: List<String>, val changed: Set<String>)

/** The field after each step of [line]: what is face up, what is set, what resolves, what changed. */
fun frameStates(line: ReaderGuide.Line, kind: CardKind): List<FrameState> {
    val up = mutableListOf<String>()
    val down = mutableListOf<String>()
    var before = emptySet<String>()
    return line.steps.mapIndexed { i, s ->
        val trap = kind(s.card).let { it == 'T' || it == 'S' }
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
        val showUp = if (last && line.endBoard.isNotEmpty()) up.filter { it in line.endBoard } + line.endBoard.filter { it !in up } else up.toList()
        val showDown = if (last && line.endSet.isNotEmpty()) down.filter { it in line.endSet } + line.endSet.filter { it !in down } else down.toList()
        val now = (showUp + showDown).toSet()
        val changed = (now - before).ifEmpty { setOf(s.card) }
        before = now
        FrameState(showUp, showDown, if (last) emptyList() else active, changed)
    }
}

/** One frame: its number, the card that moved, the field, the step's words under it. */
fun Pen.frame(line: ReaderGuide.Line, f: FrameState, i: Int, w: Float): Float {
    val s = line.steps[i]
    ink.text(mono, 8f, 0f, 8f, two(i + 1), INK)
    ink.text(regular, 7.5f, 16f, 8f, fit(short(s.card), regular, 7.5f, w - 16f), INK70)
    val bh = board(f.up, f.down, 0f, 14f, w, mark = f.changed, active = f.active)
    val words = para(s.action, 0f, 18f + bh, w, regular, 7.5f, 10f, INK70, max = 3)
    return 18f + bh + words
}

/** A hand written out: its cards, its verdict, and the answer to "what do you do with it?". */
fun Pen.puzzle(h: Block.Hands.Hand, w: Float): Float {
    val tile = minOf(56f, (w - 4 * 6f) / 5)
    h.cards.take(6).forEachIndexed { i, c -> card(c, i * (tile + 6f), 0f, tile) }
    var y = tile * CARD + 10f
    if (h.verdict.isNotBlank()) {
        chip(h.verdict, 0f, y, solid = h.verdict.equals("brick", ignoreCase = true))
        y += 20f
    }
    if (h.answer.isNotBlank()) {
        micro("The answer", 0f, y + 7f, INK)
        y += 12f
        y += para(h.answer, 0f, y, w, regular, 10f, 14.5f, INK)
    }
    return y + 6f
}

/** "Before you pass": boxes to tick, a hairline between them, its title over them. */
fun Pen.checklistBlock(c: Block.Checklist, w: Float): Float {
    var y = 0f
    if (c.title.isNotBlank()) {
        micro(c.title, 0f, 7f, INK45)
        y += 14f
    }
    c.items.forEachIndexed { i, item ->
        ink.strokeRect(0f, y + 2f, 9f, 9f, INK, 0.9f)
        ink.tag("check:$i", 0f, y, w, 14f)
        val h = para(item, 18f, y, w - 18f, regular, 10f, 14f, INK)
        y += h + 6f
        if (i < c.items.lastIndex) ink.line(18f, y - 3f, w, y - 3f, INK12, 0.4f)
    }
    return y
}

/** A table: its header in micro caps over a rule, its cells wrapped to columns sized by what they hold. */
fun Pen.table(t: Block.Table, w: Float): Float {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return 0f
    val gap = 10f
    val natural = (0 until cols).map { c -> (listOf(t.header.getOrElse(c) { "" }) + t.rows.map { it.getOrElse(c) { "" } }).maxOf { regular.width(Pen.clean(it), 9f) } + 2f }
    val room = w - gap * (cols - 1)
    val widths = if (natural.sum() <= room) natural.map { it * room / natural.sum() } else natural.map { maxOf(40f, it * room / natural.sum()) }.let { ws -> ws.map { it * room / ws.sum() } }
    fun x(c: Int) = widths.take(c).sum() + gap * c
    var y = 0f
    t.header.forEachIndexed { c, h -> micro(h, x(c), y + 7f, INK45, 6.5f) }
    y += 12f
    ink.fillRect(0f, y, w, 1f, INK)
    y += 5f
    t.rows.forEach { row ->
        val h = (0 until cols).maxOf { c -> height(row.getOrElse(c) { "" }, widths[c], regular, 9f, 12.5f) }
        (0 until cols).forEach { c -> para(row.getOrElse(c) { "" }, x(c), y, widths[c], if (c == 0) medium else regular, 9f, 12.5f, INK) }
        y += h + 5f
        ink.line(0f, y - 2.5f, w, y - 2.5f, INK12, 0.5f)
    }
    return y
}

/** A card as a museum label: its tile, its name and copies, what it is for. */
fun Pen.cardNote(c: ReaderGuide.RoleCard, w: Float): Float {
    val tile = 34f
    val textW = w - tile - 12f - 24f
    var t = para(c.card, tile + 12f, 0f, textW, medium, 10f, 13f, INK)
    if (c.copies > 0) right(mono, 9f, w, 10f, "×${c.copies}", INK)
    t += para(c.note, tile + 12f, t, w - tile - 12f, regular, 9f, 12.5f, INK70)
    card(c.card, 0f, 0f, tile)
    return maxOf(tile * CARD, t) + 8f
}

/** A boxed aside: a thick rule on its left, its kind in micro caps, its card beside it. */
fun Pen.callout(c: Block.Callout, w: Float): Float {
    val tile = if (c.card.isNotBlank()) 34f else 0f
    val textX = 14f + if (tile > 0) tile + 12f else 0f
    val label = when (c.kind.lowercase()) {
        "misplay" -> "Common misplay"
        "ruling" -> "Ruling"
        "choke" -> "Choke point"
        "note" -> "Note"
        else -> "Tip"
    }
    var y = 4f
    micro(label, textX, y + 7f, INK)
    y += 14f
    if (c.card.isNotBlank()) {
        y += para(c.card, textX, y, w - textX, bold, 11f, 14f, INK) + 2f
    }
    y += para(c.text, textX, y, w - textX, regular, 10f, 14.5f, INK70) + 6f
    if (tile > 0) {
        card(c.card, 14f, 4f, tile)
        y = maxOf(y, 4f + tile * CARD + 6f)
    }
    ink.fillRect(0f, 0f, 3f, y, INK)
    return y + 4f
}
