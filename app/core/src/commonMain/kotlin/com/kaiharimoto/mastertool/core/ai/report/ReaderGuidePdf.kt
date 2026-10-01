package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.guide.CardKind
import com.kaiharimoto.mastertool.core.ai.report.guide.EngineLayout
import com.kaiharimoto.mastertool.core.ai.report.guide.GuideFacts
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.CW
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK12
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK45
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.INK70
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.MARGIN
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.W
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.col
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.span
import com.kaiharimoto.mastertool.core.ai.report.guide.Phone.Companion.two
import com.kaiharimoto.mastertool.core.ai.report.guide.board
import com.kaiharimoto.mastertool.core.ai.report.guide.bigNumber
import com.kaiharimoto.mastertool.core.ai.report.guide.checklist
import com.kaiharimoto.mastertool.core.ai.report.guide.compareBars
import com.kaiharimoto.mastertool.core.ai.report.guide.engineMap
import com.kaiharimoto.mastertool.core.ai.report.guide.flow
import com.kaiharimoto.mastertool.core.ai.report.guide.frames
import com.kaiharimoto.mastertool.core.ai.report.guide.hands
import com.kaiharimoto.mastertool.core.ai.report.guide.lanes
import com.kaiharimoto.mastertool.core.ai.report.guide.ledger
import com.kaiharimoto.mastertool.core.ai.report.guide.maxim
import com.kaiharimoto.mastertool.core.ai.report.guide.short
import com.kaiharimoto.mastertool.core.ai.report.guide.strip
import com.kaiharimoto.mastertool.core.ai.report.guide.unitChart
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.Zlib

/**
 * The reader's guide as a PDF for a phone (1.0.67, the second exploration; kai on the first: "None of
 * these designs speak out to me … strong editorial fundamentals, visual hierarchy. Cheat sheet at a
 * glance, strong and memorable lessons/insights, and detailed reasoning … Abstract ideas should be
 * visualized using graphics"). Three directions over one set of pictures ([Phone], `Graphics.kt`), so
 * what differs is the structure and the voice of the page:
 *
 * - [Style.MANUAL] — a field manual: the whole guide on its first screen, then the reference;
 * - [Style.LESSONS] — three lessons: each a maxim, the picture that proves it, and the reasoning;
 * - [Style.TURN] — one turn, annotated: the deck watched playing, board by board.
 *
 * Every screen leads with a claim (the inverted pyramid), draws it, and only then explains.
 */
object ReaderGuidePdf {
    enum class Style(val label: String) { MANUAL("Field manual"), LESSONS("Three lessons"), TURN("One turn, annotated") }

    fun render(
        guide: ReaderGuide,
        style: Style,
        fonts: GuideFonts,
        image: (String) -> PdfImage?,
        zlib: Zlib?,
        updated: String = "",
        kind: CardKind? = null,
    ): ByteArray {
        val ph = Phone(fonts, image, zlib, "${guide.deckName} · a guide", guide.deckName)
        val facts = GuideFacts.of(guide)
        val cards = kind ?: guess(guide)
        val doc = Doc(ph, guide, facts, cards, updated)
        when (style) {
            Style.MANUAL -> doc.manual()
            Style.LESSONS -> doc.lessons()
            Style.TURN -> doc.turn()
        }
        return ph.finish()
    }

    /** Without the card pool: a set card or a card in a role named for traps is a trap, the rest monsters. */
    private fun guess(g: ReaderGuide): CardKind {
        val traps = g.lines.flatMap { it.endSet }.toSet() + g.roles.filter { it.name.contains("trap", ignoreCase = true) }.flatMap { r -> r.cards.map { it.card } }
        return { name -> if (name in traps) 'T' else 'M' }
    }

    private class Doc(val ph: Phone, val g: ReaderGuide, val facts: GuideFacts, val kind: CardKind, val updated: String) {
        var n = 0
        val engine = EngineLayout.of(g.connections)
        /** The hub nearest the top of the engine: the card the deck runs through, the cover's picture. */
        val hub = engine.rows.flatten().firstOrNull { it in engine.hubs }

        // ---- the three directions --------------------------------------------------------------

        fun manual() {
            glance()
            if (g.connections.isNotEmpty()) engineScreen()
            g.lines.forEachIndexed { i, l -> lineScreen(i, l) }
            breaks()
            matchups()
            handsScreen()
            cardsScreen()
            sources()
        }

        fun lessons() {
            cover()
            g.lessons.forEachIndexed { i, l -> lessonScreen(i, l) }
            if (g.connections.isNotEmpty()) engineScreen()
            matchups()
            glance(title = "The guide on one screen", reserve = 80f)
            sources()
        }

        fun turn() {
            turnCover()
            g.lines.firstOrNull()?.let { line ->
                lanesScreen(line)
                framesScreen(line)
                lineScreen(0, line, title = "Where it breaks")
            }
            g.lines.drop(1).forEachIndexed { i, l -> lineScreen(i + 1, l) }
            handsScreen()
            matchups()
            glance(title = "Before the next game", reserve = 80f)
            sources()
        }

        // ---- screens --------------------------------------------------------------------------

        /** A new section: a fresh screen, its kicker, and its headline. The y under the headline. */
        fun open(section: String, headline: String, size: Float = 26f) {
            ph.screen(section)
            n++
            ph.kicker(n, section)
            ph.y += 18f
            ph.y += ph.headline(headline, size = size) + 10f
        }

        fun lede(text: String, gray: Float = INK70) {
            if (text.isBlank()) return
            ph.y += ph.para(text, MARGIN, ph.y, CW, ph.regular, 10.5f, 15f, gray) + 14f
        }

        /** The whole guide at a glance: the big idea, the two numbers, the forty cards, the rules, the checklist. */
        fun glance(title: String = "At a glance", reserve: Float = 0f) {
            ph.screen(title)
            n++
            ph.kicker(n, title)
            ph.y += 18f
            ph.y += ph.headline(g.deckName, size = 36f) + 6f
            if (g.subtitle.isNotBlank()) {
                ph.page.text(ph.mono, 7.5f, MARGIN, ph.y + 8f, g.subtitle, INK45)
                ph.y += 16f
            }
            if (g.bigIdea.isNotBlank()) ph.y += ph.para(g.bigIdea, MARGIN, ph.y, CW, ph.medium, 14f, 18.5f, INK, tracking = -0.15f) + 14f
            ph.page.line(MARGIN, ph.y, W - MARGIN, ph.y, INK, 1.2f)
            ph.y += 10f
            if (facts.deckSize > 0) {
                val a = ph.bigNumber(facts.startFirst, "Opens a starter · first", col(0), ph.y, span(2) - 6f, size = 40f)
                val b = ph.bigNumber(facts.startSecond, "Opens a starter · second", col(2), ph.y, span(2) - 6f, size = 40f)
                ph.y += maxOf(a, b) + 12f
                ph.micro(ph.page, "The ${facts.deckSize} cards, by role", MARGIN, ph.y + 7f, INK45)
                ph.y += 14f
                ph.y += ph.unitChart(facts, MARGIN, ph.y, CW) + 10f
            }
            if (g.lessons.isNotEmpty()) {
                ph.page.line(MARGIN, ph.y, W - MARGIN, ph.y, INK12, 0.5f)
                ph.y += 8f
                ph.micro(ph.page, "Remember", MARGIN, ph.y + 7f, INK45)
                ph.y += 14f
                g.lessons.forEachIndexed { i, l ->
                    ph.page.text(ph.mono, 9f, MARGIN, ph.y + 11f, two(i + 1), INK)
                    ph.y += ph.para(l.maxim, MARGIN + 22f, ph.y, CW - 22f, ph.medium, 12f, 16f, INK) + 6f
                }
            }
            if (g.checklist.isNotEmpty()) {
                val h = 22f + g.checklist.sumOf { ph.height(it, CW - 18f, size = 9f, lead = 13f).toDouble() + 6 }.toFloat()
                ph.room(h)
                ph.page.line(MARGIN, ph.y + 4f, W - MARGIN, ph.y + 4f, INK12, 0.5f)
                ph.y += 12f
                ph.micro(ph.page, "Before you pass", MARGIN, ph.y + 7f, INK45)
                ph.y += 14f
                ph.y += ph.checklist(g.checklist, MARGIN, ph.y, CW, size = 9f)
            }
            // The best turn as a strip of cards, when the screen has room for it.
            val best = g.lines.firstOrNull()
            if (best != null && ph.y + 130f + reserve < Phone.BOTTOM) {
                ph.page.line(MARGIN, ph.y + 4f, W - MARGIN, ph.y + 4f, INK12, 0.5f)
                ph.y += 12f
                ph.micro(ph.page, "Your best turn · ${best.name}", MARGIN, ph.y + 7f, INK45)
                ph.y += 12f
                ph.y += ph.strip(best, MARGIN, ph.y, CW)
            }
        }

        fun engineScreen() {
            open("The engine", hub?.let { "Every line runs through ${ph.short(it)}." } ?: "How the cards find each other.")
            lede("Starting cards on top. Each arrow is a card finding another; the heavier frames are the cards every route passes through — protect them, and know what stops them.")
            ph.y += ph.engineMap(engine, MARGIN, ph.y, CW) + 4f
        }

        fun lineScreen(i: Int, line: ReaderGuide.Line, title: String = "Line ${i + 1}") {
            open(title, line.name)
            lede(line.note)
            ph.y += ph.flow(line, MARGIN, ph.y, CW) + 14f
            if (line.endBoard.isNotEmpty() || line.endSet.isNotEmpty()) {
                val bw = 170f
                val bh = bw / 5 * 1.4583f * 3 + 30f
                ph.room(bh + 30f)
                ph.page.fillRect(MARGIN, ph.y, CW, 1.5f, INK)
                ph.micro(ph.page, "Ends on", MARGIN, ph.y + 14f, INK)
                ph.y += 22f
                val top = ph.y
                val h = ph.board(line.endBoard, line.endSet, MARGIN, top, bw)
                val names = (line.endBoard.map { it } + line.endSet.map { "${it} (set)" })
                var ny = top
                names.forEach { nm -> ny += ph.para(nm, MARGIN + bw + 14f, ny, CW - bw - 14f, ph.regular, 8.5f, 11.5f, INK70) + 3f }
                ph.y += maxOf(h, ny - top) + 10f
            }
        }

        fun breaks() {
            if (g.chokePoints.isEmpty()) return
            open("Where it breaks", "${g.chokePoints.size} cards stop this deck. Know them before you sit down.")
            g.chokePoints.forEach { c ->
                val textW = CW - 64f
                val h = maxOf(48f * 1.4583f, ph.height(c.card, textW, ph.bold, 13f, 17f) + ph.height(c.text, textW) + 6f) + 16f
                ph.room(h)
                ph.page.fillRect(MARGIN, ph.y, CW, 1.2f, INK)
                ph.y += 10f
                if (c.card.isNotBlank()) ph.card(c.card, MARGIN, ph.y, 48f)
                var t = ph.y
                if (c.card.isNotBlank()) t += ph.para(c.card, MARGIN + 64f, t, textW, ph.bold, 13f, 17f, INK) + 4f
                ph.para(c.text, MARGIN + 64f, t, textW, ph.regular, 10f, 14f, INK70)
                ph.y += h - 10f
            }
        }

        fun matchups() {
            if (g.siding.isEmpty()) return
            open("Matchups", "Side for what they do, not for what they are.")
            g.siding.forEachIndexed { i, s ->
                val f = facts.sides[i]
                val h = 150f + ph.height(s.why, CW, size = 8.5f, lead = 12f)
                ph.room(h)
                ph.y += ph.ledger(s, f, MARGIN, ph.y, CW) + 18f
            }
        }

        fun handsScreen() {
            if (facts.hands.isEmpty()) return
            open("Your opening hands", "${GuideFacts.percent(facts.startFirst)} of hands start. Here is what the rest look like.")
            lede("Three hands dealt from the list. The framed card is the one that starts; a brick hand has none, and going second it still has its hand traps.")
            ph.y += ph.hands(facts, MARGIN, ph.y, CW)
        }

        fun cardsScreen() {
            if (g.roles.isEmpty()) return
            open("The cards", "Forty cards, four jobs.")
            g.roles.forEach { r ->
                ph.room(30f + 50f)
                ph.page.fillRect(MARGIN, ph.y, CW, 1.5f, INK)
                ph.page.text(ph.bold, 12f, MARGIN, ph.y + 16f, r.name, INK)
                ph.right(ph.page, ph.mono, 9f, W - MARGIN, ph.y + 16f, r.cards.sumOf { it.copies }.toString(), INK45)
                ph.y += 26f
                r.cards.forEach { c ->
                    val textW = CW - 50f
                    val h = maxOf(34f * 1.4583f, ph.height(c.card, textW - 24f, ph.medium, 10f, 13f) + ph.height(c.note, textW, size = 9f, lead = 12.5f)) + 8f
                    ph.room(h)
                    ph.card(c.card, MARGIN, ph.y, 34f)
                    var t = ph.y
                    t += ph.para(c.card, MARGIN + 46f, t, textW - 24f, ph.medium, 10f, 13f, INK)
                    if (c.copies > 0) ph.right(ph.page, ph.mono, 9f, W - MARGIN, ph.y + 10f, "×${c.copies}", INK)
                    ph.para(c.note, MARGIN + 46f, t, textW, ph.regular, 9f, 12.5f, INK70)
                    ph.y += h
                }
                ph.y += 8f
            }
        }

        fun sources() {
            if (g.sources.isEmpty() && g.tips.isEmpty()) return
            ph.room(70f)
            ph.y += 10f
            ph.page.line(MARGIN, ph.y, W - MARGIN, ph.y, INK12, 0.5f)
            ph.y += 10f
            ph.micro(ph.page, "Sources", MARGIN, ph.y + 7f, INK45)
            ph.y += 14f
            g.sources.forEach { ph.y += ph.para(it, MARGIN, ph.y, CW, ph.regular, 8f, 11f, INK45) + 3f }
            if (updated.isNotBlank()) ph.page.text(ph.mono, 7f, MARGIN, ph.y + 10f, "Updated $updated", INK45)
        }

        // ---- the lessons direction --------------------------------------------------------------

        fun cover() {
            ph.screen("Cover", head = false)
            val heroCard = hub ?: g.lessons.firstOrNull()?.card ?: g.roles.firstOrNull()?.cards?.firstOrNull()?.card.orEmpty()
            val heroH = 430f
            ph.artwork(heroCard, 0f, 0f, W, heroH)
            ph.page.fillRect(0f, heroH, W, 4f, INK)
            var y = heroH + 26f
            ph.micro(ph.page, "A deck guide", MARGIN, y, INK45)
            y += 8f
            y += ph.headline(g.deckName, top = y, size = 48f) + 10f
            if (g.subtitle.isNotBlank()) {
                ph.page.text(ph.mono, 7.5f, MARGIN, y + 8f, g.subtitle, INK45)
                y += 18f
            }
            if (g.bigIdea.isNotBlank()) y += ph.para(g.bigIdea, MARGIN, y, CW, ph.medium, 17f, 22f, INK, tracking = -0.2f) + 18f
            ph.page.line(MARGIN, y, W - MARGIN, y, INK, 0.8f)
            y += 10f
            ph.micro(ph.page, "Three lessons", MARGIN, y + 7f, INK45)
            y += 16f
            g.lessons.forEachIndexed { i, l ->
                ph.page.text(ph.mono, 10f, MARGIN, y + 12f, two(i + 1), INK)
                val h = ph.para(l.maxim, MARGIN + 26f, y, CW - 60f, ph.medium, 13f, 17f, INK)
                ph.right(ph.page, ph.mono, 8f, W - MARGIN, y + 12f, "p. ${two(3 + i)}", INK45)
                y += h + 8f
            }
            ph.y = y
        }

        fun lessonScreen(i: Int, l: ReaderGuide.Lesson) {
            ph.screen("Lesson ${two(i + 1)}")
            n++
            ph.y += 8f
            // The maxim as large as it can be in three lines: a long one steps down a size.
            val maximSize = if (ph.wrap(l.maxim, ph.bold, 40f, CW - 88f, -1f).size <= 3) 40f else 32f
            ph.y += ph.maxim(i + 1, l, MARGIN, ph.y, CW, size = maximSize, tile = 72f) + 18f
            if (l.number.isNotBlank()) {
                ph.page.fillRect(MARGIN, ph.y, CW, 1.5f, INK)
                // The number at 46 points, smaller only as far as it must be to fit the width.
                val size = minOf(46f, 46f * CW / ph.mono.width(l.number, 46f, -1.6f))
                ph.page.text(ph.mono, size, MARGIN - 1f, ph.y + 8f + size * 0.95f, l.number, INK, tracking = -size * 0.035f)
                val nw = ph.mono.width(l.number, size, -size * 0.035f)
                val lw = CW - nw - 14f
                if (lw > 90f) {
                    ph.para(l.numberLabel, MARGIN + nw + 14f, ph.y + 22f, lw, ph.regular, 10f, 13f, INK70)
                    ph.y += 18f + size * 1.1f
                } else {
                    ph.y += 14f + size * 1.1f
                    ph.y += ph.para(l.numberLabel, MARGIN, ph.y, CW, ph.regular, 10f, 13f, INK70) + 8f
                }
            }
            // The picture that proves it.
            ph.y += 6f
            when {
                l.show == "odds" -> {
                    val starters = g.roles.firstOrNull()
                    if (starters != null) {
                        val all = starters.cards.sumOf { it.copies }
                        val monsters = starters.cards.filter { kind(it.card) == 'M' }.sumOf { it.copies }
                        val rows = listOf(
                            "$monsters monsters" to GuideFacts.atLeastOne(monsters, facts.deckSize, 5),
                            "$all with the traps" to GuideFacts.atLeastOne(all, facts.deckSize, 5),
                        )
                        ph.micro(ph.page, "At least one starter in five cards", MARGIN, ph.y + 7f, INK45)
                        ph.y += 14f
                        ph.y += ph.compareBars(rows, MARGIN, ph.y, CW, bar = 16f) + 14f
                    }
                }
                l.show.startsWith("turn:") -> g.lines.getOrNull(l.show.substringAfter(':').toIntOrNull() ?: 0)?.let { line ->
                    ph.y += ph.lanes(line, MARGIN, ph.y, CW, tile = 30f) + 14f
                }
                l.show.startsWith("line:") -> g.lines.getOrNull(l.show.substringAfter(':').toIntOrNull() ?: 0)?.let { line ->
                    ph.y += ph.flow(line, MARGIN, ph.y, CW, tile = 22f) + 10f
                }
                l.show == "engine" -> ph.y += ph.engineMap(engine, MARGIN, ph.y, CW, tile = 32f) + 10f
            }
            if (l.why.isNotBlank()) {
                val h = ph.height(l.why, CW - (col(1) - 30f - MARGIN), size = 12f, lead = 18f) + 20f
                ph.room(h)
                ph.page.line(MARGIN, ph.y, W - MARGIN, ph.y, INK12, 0.5f)
                ph.y += 10f
                ph.micro(ph.page, "Why", MARGIN, ph.y + 7f, INK)
                ph.y += ph.para(l.why, col(1) - 30f, ph.y, CW - (col(1) - 30f - MARGIN), ph.regular, 12f, 18f, INK) + 10f
            }
        }

        // ---- the turn direction ------------------------------------------------------------------

        fun turnCover() {
            ph.screen("Cover", head = false)
            var y = 40f
            ph.micro(ph.page, "One turn, annotated", MARGIN, y, INK45)
            y += 10f
            y += ph.headline(g.deckName, top = y, size = 48f) + 10f
            if (g.subtitle.isNotBlank()) {
                ph.page.text(ph.mono, 7.5f, MARGIN, y + 8f, g.subtitle, INK45)
                y += 18f
            }
            if (g.bigIdea.isNotBlank()) y += ph.para(g.bigIdea, MARGIN, y, CW, ph.medium, 17f, 22f, INK, tracking = -0.2f) + 22f
            val line = g.lines.firstOrNull()
            if (line != null) {
                ph.page.fillRect(MARGIN, y, CW, 1.5f, INK)
                ph.micro(ph.page, "Where you want to be when they pass back", MARGIN, y + 14f, INK)
                y += 26f
                val bw = 250f
                y += ph.board(line.endBoard, line.endSet, MARGIN, y, bw) + 18f
                ph.page.fillRect(MARGIN, y, CW, 1.5f, INK)
                ph.micro(ph.page, "How you get there · ${line.name}", MARGIN, y + 14f, INK)
                y += 22f
                y += ph.strip(line, MARGIN, y, CW) + 8f
                ph.para("One card on your turn; the rest on theirs. The next screens take it a step at a time, and show where it breaks.", MARGIN, y, CW, ph.regular, 10f, 14f, INK70)
            }
        }

        fun lanesScreen(line: ReaderGuide.Line) {
            val theirs = GuideFacts.theirTurn(line)
            open("The turn", "$theirs of ${line.steps.size} plays happen on their turn.")
            lede("Your turn on the left, theirs on the right, in order. Going first you summon one card and pass; the deck does its work while they play.")
            ph.y += ph.lanes(line, MARGIN, ph.y, CW) + 8f
        }

        fun framesScreen(line: ReaderGuide.Line) {
            open("Step by step", "The board after each play.")
            lede("Each frame is the field after one step: the card that moved is framed, the rest are dimmed. Set cards show what they are under the face-down mark.")
            ph.y += ph.frames(line, kind, MARGIN, ph.y, CW)
        }
    }
}
