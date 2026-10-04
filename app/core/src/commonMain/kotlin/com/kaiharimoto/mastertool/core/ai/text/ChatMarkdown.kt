package com.kaiharimoto.mastertool.core.ai.text

/**
 * The markdown Ai writes, read into blocks the chat draws with the app's own type —
 * enough of it to read well (headings, lists, emphasis, code, rules, quotes) and no
 * more. Card names Ai wraps in double brackets, `[[Ash Blossom & Joyous Spring]]`,
 * become [Inline.Card]: a chip the person can hover or open.
 *
 * Tolerant by design: a reply is drawn while it streams, so an unclosed `**` or
 * code fence is text until it closes.
 */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class Bullets(val items: List<List<Inline>>) : Block
    data class Numbered(val items: List<List<Inline>>, val start: Int = 1) : Block
    data class Code(val text: String, val lang: String = "") : Block
    data class Quote(val inlines: List<Inline>) : Block
    data class Table(
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        /** Per column, from the rule line's colons: left unless it says otherwise. */
        val align: List<Align> = emptyList(),
    ) : Block
    data object Rule : Block

    /** A ```chart block that read as a chart ([ChatChart]); one that did not is [Code]. */
    data class Chart(val chart: ChatChart.Chart) : Block

    /**
     * A ```cards block: card names, each with its copies, drawn as art (1.0.46). `## Label`
     * lines inside break it into labelled [groups] (1.0.55); [lines] is every card, in order.
     */
    data class Cards(val lines: List<CardLine>, val groups: List<CardGroup> = listOf(CardGroup("", lines))) : Block

    /** A ```deck block (1.0.55): a whole list, `Main:`, `Extra:` and `Side:` sections of counted cards. */
    data class Deck(val sections: List<CardGroup>) : Block {
        val total: Int get() = sections.sumOf { g -> g.lines.sumOf { it.count } }
    }

    /** A ```compare block (1.0.55): cards out and cards in, a change to a deck or a siding plan. */
    data class Compare(val out: CardGroup, val into: CardGroup) : Block

    /** A ```line block (1.0.55): a combo, step by step, each step a card and what it does. */
    data class Line(val steps: List<Step>) : Block

    /** A ```board block (1.0.55): the field as it stands, zone by zone. */
    data class Board(val board: ChatBoard) : Block

    /** A block still being written (a chart whose fence is not closed yet): a quiet line, not raw JSON. */
    data class Pending(val what: String) : Block
}

enum class Align { LEFT, CENTER, RIGHT }

/** One line of a ```cards block: `3 Ash Blossom & Joyous Spring`, `Ash Blossom x2`, or a bare name. */
data class CardLine(val count: Int, val name: String)

/** Cards under a label: a group of a ```cards block, a section of a ```deck, one side of a ```compare. */
data class CardGroup(val label: String, val lines: List<CardLine>) {
    val count: Int get() = lines.sumOf { it.count }
}

/** One step of a ```line: the card it turns on, if it names one, and what happens, in words. */
data class Step(val card: String?, val action: List<Inline>)

/**
 * A field in a ```board: five main monster zones, two extra monster zones, five spell and trap
 * zones and the field zone, each a card or empty; the hand, the graveyard and the banished as
 * lists. A card set face-down is marked.
 */
data class ChatBoard(
    val monsters: List<Slot?>,
    val extraMonsters: List<Slot?>,
    val spells: List<Slot?>,
    val field: Slot?,
    val hand: List<CardLine>,
    val graveyard: List<CardLine>,
    val banished: List<CardLine>,
) {
    data class Slot(val name: String, val set: Boolean = false)

    val isEmpty: Boolean get() = monsters.all { it == null } && extraMonsters.all { it == null } && spells.all { it == null } &&
        this.field == null && hand.isEmpty() && graveyard.isEmpty() && banished.isEmpty()
}

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val text: String) : Inline
    data class Italic(val text: String) : Inline
    data class Code(val text: String) : Inline
    data class Card(val name: String) : Inline
}

object ChatMarkdown {
    /**
     * [streaming]: the reply is still arriving, so what is half-written at its end is
     * held back rather than drawn raw (1.0.46) — an unclosed `**`, `` ` `` or `[[`, a table
     * whose rule line has not come yet, a chart whose fence is still open.
     */
    fun parse(text: String, streaming: Boolean = false): List<Block> = blocks(text, streaming, null)

    /**
     * [parse], noting in [marks] each line where a block may begin afresh — the line after a blank
     * one, nothing half-read before it — as (line, blocks so far). What follows such a line parses
     * the same alone, which is what lets [MarkdownMemo] keep the blocks before it while a reply streams.
     */
    internal fun blocks(text: String, streaming: Boolean, marks: MutableList<IntArray>?): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = (if (streaming) settled(text) else text).replace("\r\n", "\n").lines()
        var i = 0
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inline(paragraph.joinToString(" ")))
                paragraph.clear()
            }
        }
        while (i < lines.size) {
            if (marks != null && i > 0 && paragraph.isEmpty() && lines[i - 1].isBlank()) marks += intArrayOf(i, blocks.size)
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim().lowercase()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    val closed = i < lines.size
                    val body = code.joinToString("\n")
                    blocks += when {
                        streaming && !closed && lang in DRAWN -> Block.Pending(PENDING.getValue(lang))
                        lang == "chart" -> ChatChart.parse(body).fold({ Block.Chart(it) }, { Block.Code(body, lang) })
                        lang == "cards" -> cardGroups(body).takeIf { g -> g.any { it.lines.isNotEmpty() } }
                            ?.let { g -> Block.Cards(g.flatMap { it.lines }, g.filter { it.lines.isNotEmpty() }) } ?: Block.Code(body, lang)
                        lang == "deck" -> deck(body) ?: Block.Code(body, lang)
                        lang == "compare" -> compare(body) ?: Block.Code(body, lang)
                        lang == "line" || lang == "combo" -> line(body) ?: Block.Code(body, lang)
                        lang == "board" -> board(body)?.let { Block.Board(it) } ?: Block.Code(body, lang)
                        else -> Block.Code(body, lang)
                    }
                }
                t.isEmpty() -> flush()
                t.matches(HEADING) -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, inline(t.drop(level).trim()))
                }
                t == "---" || t == "***" || t == "___" -> {
                    flush()
                    blocks += Block.Rule
                }
                t.startsWith("> ") || t == ">" -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quoted += lines[i++].trim().removePrefix(">").trim()
                    blocks += Block.Quote(inline(quoted.joinToString(" ")))
                    continue
                }
                bullet(t) != null -> {
                    flush()
                    val items = mutableListOf<List<Inline>>()
                    while (i < lines.size) {
                        val b = bullet(lines[i].trim()) ?: break
                        var item = b
                        i++
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() && bullet(lines[i].trim()) == null) item += " " + lines[i++].trim()
                        items += inline(item)
                    }
                    blocks += Block.Bullets(items)
                    continue
                }
                numbered(t) != null -> {
                    flush()
                    val start = numbered(t)!!.first
                    val items = mutableListOf<List<Inline>>()
                    while (i < lines.size) {
                        val n = numbered(lines[i].trim()) ?: break
                        var item = n.second
                        i++
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() && numbered(lines[i].trim()) == null) item += " " + lines[i++].trim()
                        items += inline(item)
                    }
                    blocks += Block.Numbered(items, start)
                    continue
                }
                '|' in t && i + 1 < lines.size && isRule(lines[i + 1].trim()) -> {
                    flush()
                    val header = cells(t)
                    val align = split(lines[i + 1].trim()).map { c ->
                        val x = c.trim()
                        when {
                            x.startsWith(":") && x.endsWith(":") -> Align.CENTER
                            x.endsWith(":") -> Align.RIGHT
                            else -> Align.LEFT
                        }
                    }
                    i += 2
                    val rows = mutableListOf<List<List<Inline>>>()
                    while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) rows += cells(lines[i++].trim())
                    blocks += Block.Table(header, rows, align)
                    continue
                }
                else -> paragraph += t
            }
            i++
        }
        flush()
        return blocks
    }

    private val PENDING = mapOf(
        "chart" to "Drawing a chart",
        "cards" to "Laying out cards",
        "deck" to "Laying out the deck",
        "compare" to "Laying out the changes",
        "line" to "Drawing the line",
        "combo" to "Drawing the line",
        "board" to "Setting the board",
    )
    private val DRAWN = PENDING.keys

    // Compiled once: parse runs on every frame of a streaming reply.
    private val HEADING = Regex("#{1,6} .*")
    private val RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val BOLD = Regex("\\*\\*")
    private val NUMBERED = Regex("^(\\d{1,3})[.)] (.*)")

    /** `| --- | :---: | ---: |`, with or without the outer pipes. */
    private fun isRule(t: String): Boolean =
        '-' in t && t.matches(RULE)

    private fun cells(row: String): List<List<Inline>> = split(row).map { inline(it.trim()) }

    /**
     * A row's cells: split on `|`, but not on one inside `code` or written `\|`, so a
     * cell can show a pipe. The outer pipes are optional.
     */
    private fun split(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.drop(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var code = false
        var i = 0
        while (i < r.length) {
            val c = r[i]
            when {
                c == '\\' && i + 1 < r.length && r[i + 1] == '|' -> { cell.append('|'); i++ }
                c == '`' -> { code = !code; cell.append(c) }
                c == '|' && !code -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    /** A ```cards block's lines: `3 Name`, `3x Name`, `Name x2`, `[[Name]]`, or a bare name. */
    fun cardLines(body: String): List<CardLine> = body.lines().mapNotNull { cardLine(it) }

    /**
     * A block's lines under their labels: a `## Label` or `Label:` line starts a group
     * (`Main Deck (40):` is "Main Deck"); the lines before any label are a group with none.
     */
    fun cardGroups(body: String): List<CardGroup> {
        val out = mutableListOf<CardGroup>()
        var label = ""
        var lines = mutableListOf<CardLine>()
        fun close() {
            if (lines.isNotEmpty() || label.isNotEmpty()) out += CardGroup(label, lines)
            lines = mutableListOf()
        }
        body.lines().forEach { raw ->
            val t = raw.trim()
            val heading = Regex("^#{1,4}\\s+(.+)$").find(t)?.groupValues?.get(1)
            val colon = Regex("^([A-Za-z][A-Za-z /&'-]{0,30}?)\\s*(\\(\\s*\\d+\\s*\\))?\\s*:\\s*(.*)$").find(t)
            when {
                heading != null -> { close(); label = heading.trim() }
                colon != null && !t.startsWith("[[") -> {
                    close()
                    label = colon.groupValues[1].trim()
                    // `Hand: A, B` — the cards on the label's own line.
                    colon.groupValues[3].takeIf { it.isNotBlank() }?.let { rest -> lines += list(rest) }
                }
                else -> cardLine(raw)?.let { lines += it }
            }
        }
        close()
        return out
    }

    /** A comma-separated run of cards (`A, 2 B, [[C]]`), as lines. */
    private fun list(text: String): List<CardLine> = text.split(',', ';').mapNotNull { cardLine(it) }

    private fun cardLine(raw: String): CardLine? {
        val t = raw.trim().removePrefix("- ").removePrefix("* ").removePrefix("+ ").trim()
        if (t.isEmpty() || t == "-" || t.equals("none", true) || t.equals("empty", true)) return null
        val lead = Regex("^(\\d{1,2})\\s*[x×]?\\s+(.+)$").find(t)
        val tail = Regex("^(.+?)\\s*[x×]\\s*(\\d{1,2})$").find(t)
        val (count, name) = when {
            lead != null -> lead.groupValues[1].toInt() to lead.groupValues[2]
            tail != null -> tail.groupValues[2].toInt() to tail.groupValues[1]
            else -> 1 to t
        }
        val clean = name.trim().removePrefix("[[").removeSuffix("]]").trim()
        return if (clean.isEmpty()) null else CardLine(count.coerceIn(1, 3), clean)
    }

    private fun key(label: String) = label.lowercase().filter { it.isLetter() }

    /** A ```deck: its sections in the order Main, Extra, Side, whatever order they came in. */
    fun deck(body: String): Block.Deck? {
        val groups = cardGroups(body).filter { it.lines.isNotEmpty() }
        if (groups.isEmpty()) return null
        fun rank(g: CardGroup) = key(g.label).let {
            when {
                it.startsWith("main") || it.isEmpty() -> 0
                it.startsWith("extra") -> 1
                it.startsWith("side") -> 2
                else -> 3
            }
        }
        return Block.Deck(groups.map { if (it.label.isEmpty()) it.copy(label = "Main") else it }.sortedBy(::rank))
    }

    /** A ```compare: `Out:`/`In:` (or `-`/`+` lines, or `Before:`/`After:`); the first two groups otherwise. */
    fun compare(body: String): Block.Compare? {
        val signed = body.lines().map { it.trim() }.filter { it.startsWith("+") || it.startsWith("-") && !it.startsWith("- ") || it.startsWith("−") }
        if (signed.isNotEmpty() && cardGroups(body).none { it.label.isNotEmpty() }) {
            val out = signed.filter { it.startsWith("-") || it.startsWith("−") }.mapNotNull { cardLine(it.drop(1)) }
            val into = signed.filter { it.startsWith("+") }.mapNotNull { cardLine(it.drop(1)) }
            return if (out.isEmpty() && into.isEmpty()) null else Block.Compare(CardGroup("Out", out), CardGroup("In", into))
        }
        val groups = cardGroups(body).filter { it.label.isNotEmpty() }
        if (groups.isEmpty()) return null
        val out = groups.firstOrNull { key(it.label).let { k -> k.startsWith("out") || k.startsWith("remove") || k.startsWith("cut") || k.startsWith("before") } } ?: groups.first()
        val into = groups.firstOrNull { it !== out && key(it.label).let { k -> k.startsWith("in") || k.startsWith("add") || k.startsWith("after") } }
            ?: groups.firstOrNull { it !== out } ?: CardGroup("In", emptyList())
        return Block.Compare(out, into)
    }

    /** A ```line: numbered or bulleted steps; each names the card it turns on first, in brackets. */
    fun line(body: String): Block.Line? {
        val steps = body.lines().mapNotNull { raw ->
            val t = raw.trim().replace(Regex("^(\\d{1,2}[.)]|[-*•→])\\s*"), "").trim()
            if (t.isEmpty()) return@mapNotNull null
            val first = Regex("\\[\\[([^\\]]+)]]").find(t)
            val card = first?.takeIf { it.range.first <= 2 }?.groupValues?.get(1)?.trim()
            val rest = if (card != null) t.removeRange(first.range).trimStart(' ', ':', '—', '–', '-', ',', '→').trim() else t
            Step(card, inline(rest))
        }
        return if (steps.isEmpty()) null else Block.Line(steps)
    }

    /**
     * A ```board: `Monsters:`, `Extra Monster:`, `Spells/Traps:`, `Field:`, `Hand:`, `GY:` and
     * `Banished:` lines of comma-separated cards; `-` is an empty zone and `(set)` a face-down card.
     */
    fun board(body: String): ChatBoard? {
        val zones = mutableMapOf<String, List<String>>()
        body.lines().forEach { raw ->
            val t = raw.trim().removePrefix("- ").trim()
            val i = t.indexOf(':')
            if (i <= 0) return@forEach
            val k = key(t.take(i))
            val cards = t.drop(i + 1).split(',', ';').map { it.trim() }
            val zone = when {
                k.startsWith("extramonster") || k == "emz" || k.startsWith("extrazone") -> "emz"
                k.startsWith("monster") || k == "mmz" || k.startsWith("mainmonster") -> "monsters"
                k.startsWith("spell") || k.startsWith("st") || k.startsWith("backrow") || k.startsWith("trap") -> "spells"
                k.startsWith("field") -> "field"
                k.startsWith("hand") -> "hand"
                k == "gy" || k.startsWith("grave") -> "gy"
                k.startsWith("banish") || k.startsWith("removed") -> "banished"
                else -> return@forEach
            }
            zones[zone] = cards
        }
        if (zones.isEmpty()) return null
        fun slot(s: String): ChatBoard.Slot? {
            val set = Regex("\\((set|face-?down)\\)", RegexOption.IGNORE_CASE).containsMatchIn(s)
            val name = s.replace(Regex("\\((set|face-?down)\\)", RegexOption.IGNORE_CASE), "").trim().removePrefix("[[").removeSuffix("]]").trim()
            return if (name.isEmpty() || name == "-" || name == "—" || name.equals("empty", true)) null else ChatBoard.Slot(name, set)
        }
        fun row(zone: String, n: Int): List<ChatBoard.Slot?> {
            val given = zones[zone].orEmpty().map(::slot).take(n)
            return given + List(n - given.size) { null }
        }
        fun pile(zone: String) = zones[zone].orEmpty().mapNotNull { cardLine(it) }
        val b = ChatBoard(
            monsters = row("monsters", 5),
            extraMonsters = row("emz", 2),
            spells = row("spells", 5),
            field = zones["field"]?.firstNotNullOfOrNull(::slot),
            hand = pile("hand"),
            graveyard = pile("gy"),
            banished = pile("banished"),
        )
        return if (b.isEmpty) null else b
    }

    /**
     * The part of a reply still arriving that is safe to draw: the last line's unclosed
     * `[[`, `**` or `` ` `` held back, and a table that has not got its rule line yet —
     * they come out whole a moment later instead of flickering raw.
     */
    fun settled(text: String): String {
        val lines = text.replace("\r\n", "\n").split("\n").toMutableList()
        // A table's lines at the end, until its rule line is in, are held back whole.
        var end = lines.size
        while (end > 0 && lines[end - 1].trim().startsWith("|")) end--
        val tail = lines.subList(end, lines.size)
        val fence = lines.take(end).count { it.trim().startsWith("```") } % 2 == 1
        if (!fence && tail.isNotEmpty() && tail.none { isRule(it.trim()) }) {
            while (lines.size > end) lines.removeAt(lines.size - 1)
        }
        if (fence || lines.isEmpty()) return lines.joinToString("\n")
        var last = lines.removeAt(lines.size - 1)
        fun cut(open: String, close: String) {
            val at = last.lastIndexOf(open)
            if (at >= 0 && last.indexOf(close, at + open.length) < 0) last = last.substring(0, at)
        }
        cut("[[", "]]")
        if (BOLD.findAll(last).count() % 2 == 1) last = last.substring(0, last.lastIndexOf("**"))
        if (last.count { it == '`' } % 2 == 1) last = last.substring(0, last.lastIndexOf('`'))
        lines += last
        return lines.joinToString("\n")
    }

    private fun bullet(t: String): String? =
        if ((t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) && t.length > 2) t.drop(2).trim() else null

    private fun numbered(t: String): Pair<Int, String>? {
        val m = NUMBERED.find(t) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2]
    }

    /** Inline marks: `[[card]]`, `**bold**`, `*italic*`/`_italic_`, `` `code` ``. */
    fun inline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        fun push(i: Inline) {
            if (plain.isNotEmpty()) {
                out += Inline.Text(plain.toString())
                plain.clear()
            }
            out += i
        }
        var i = 0
        // Read in place: a copy of the rest at every character made long paragraphs quadratic.
        while (i < text.length) {
            val c = text[i]
            val mark = when {
                text.startsWith("[[", i) -> close(text, i + 2, "]]")?.let { end -> Inline.Card(text.substring(i + 2, end).trim()) to end + 2 }
                text.startsWith("**", i) -> close(text, i + 2, "**")?.let { end -> Inline.Bold(text.substring(i + 2, end)) to end + 2 }
                c == '`' -> close(text, i + 1, "`")?.let { end -> Inline.Code(text.substring(i + 1, end)) to end + 1 }
                (c == '*' || c == '_') && text.length - i > 1 && !text[i + 1].isWhitespace() &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) ->
                    close(text, i + 1, c.toString())?.takeIf { end -> end > i + 1 && !text[end - 1].isWhitespace() }
                        ?.let { end -> Inline.Italic(text.substring(i + 1, end)) to end + 1 }
                else -> null
            }
            if (mark != null && (mark.first !is Inline.Card || (mark.first as Inline.Card).name.isNotEmpty())) {
                push(mark.first)
                i = mark.second
            } else {
                plain.append(text[i])
                i++
            }
        }
        if (plain.isNotEmpty()) out += Inline.Text(plain.toString())
        return out
    }

    private fun close(text: String, from: Int, marker: String): Int? =
        text.indexOf(marker, from).takeIf { it >= from }

    /** The card names a reply mentions, in order, once each. */
    fun cards(text: String): List<String> =
        Regex("\\[\\[([^\\]]+)]]").findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** A reply as plain words: marks gone, card brackets gone (for a toast or a search). */
    fun plain(text: String): String =
        text.replace(Regex("\\[\\[([^\\]]+)]]"), "$1").replace("**", "").replace("`", "")
}
