package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.ReaderGuideSample

/**
 * A guide of the earlier shape (`ReaderGuide`, the 1.0.66 exploration) as a book, chapter by chapter:
 * the deck on one page, the lessons, how it works, the lines, where it breaks, matchups, hands, the
 * cards. What a book from Ai is laid out like; the sample book is the Las Vegas Labrynth written so.
 */
object BookSample {
    val labrynth: GuideBook by lazy { from(ReaderGuideSample.labrynth) }

    fun from(g: ReaderGuide, kind: CardKind? = null): GuideBook {
        val traps = g.lines.flatMap { it.endSet }.toSet() + g.roles.filter { it.name.contains("trap", ignoreCase = true) }.flatMap { r -> r.cards.map { it.card } }
        val isMonster: (String) -> Boolean = { name -> kind?.invoke(name)?.let { it == 'M' } ?: (name !in traps && !name.contains("Welcome") && !name.contains("Trap")) }
        val starters = g.roles.firstOrNull()
        val chapters = mutableListOf<GuideBook.Chapter>()

        chapters += GuideBook.Chapter(
            title = "The deck on one page",
            summary = "What it does, how often it starts, what it is made of, and what to check before you pass.",
            sections = listOf(
                GuideBook.Section(
                    title = g.bigIdea.ifBlank { "What the deck does" },
                    blocks = listOfNotNull(
                        Block.Text(g.pitch),
                        starters?.let { Block.Odds(listOf(Block.Odds.Row("Opens a starter", role = it.name)), title = "Going first, five cards") },
                        Block.Cells(),
                    ),
                ),
                GuideBook.Section(title = "Before you pass", blocks = listOf(Block.Checklist(g.checklist))).takeIf { g.checklist.isNotEmpty() },
            ).filterNotNull(),
        )

        if (g.lessons.isNotEmpty()) chapters += GuideBook.Chapter(
            title = "Lessons",
            summary = "The few things that decide games with this deck, each with the number or the picture that proves it.",
            sections = g.lessons.map { l ->
                val picture: Block? = when {
                    l.show == "odds" && starters != null -> {
                        val monsters = starters.cards.filter { isMonster(it.card) }
                        Block.Odds(
                            listOf(
                                Block.Odds.Row("${monsters.sumOf { it.copies }} monsters", cards = monsters.flatMap { c -> List(c.copies) { c.card } }),
                                Block.Odds.Row("${starters.cards.sumOf { it.copies }} with the traps", role = starters.name),
                            ),
                            title = "At least one starter in five cards",
                        )
                    }
                    l.show.startsWith("turn:") -> g.lines.getOrNull(l.show.substringAfter(':').toIntOrNull() ?: 0)?.let { Block.Lanes(it) }
                    l.show.startsWith("line:") -> g.lines.getOrNull(l.show.substringAfter(':').toIntOrNull() ?: 0)?.let { Block.Line(it, frames = false) }
                    l.show == "engine" -> Block.Engine(g.connections)
                    else -> null
                }
                GuideBook.Section(
                    title = l.maxim,
                    blocks = listOfNotNull(Block.Lesson(l.maxim, l.card, l.number, l.numberLabel), picture, Block.Text(l.why, label = "Why").takeIf { l.why.isNotBlank() }),
                )
            },
        )

        chapters += GuideBook.Chapter(
            title = "How it works",
            summary = "How the cards find each other, the plan going first and second, and every card's job.",
            sections = listOfNotNull(
                GuideBook.Section(
                    title = EngineLayout.of(g.connections).let { e -> e.rows.flatten().firstOrNull { it in e.hubs } }?.let { "Every line runs through ${shortName(it, g.deckName)}" } ?: "How the cards find each other",
                    blocks = listOf(
                        Block.Engine(g.connections),
                        Block.Text("Starting cards on top. Each arrow is a card finding another; the heavier frames are the cards every route passes through — protect them, and know what stops them.", label = "Read it"),
                    ),
                ).takeIf { g.connections.isNotEmpty() },
                GuideBook.Section(
                    title = "The plan, first and second",
                    blocks = listOfNotNull(
                        Block.Text(g.goingFirst.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"), label = "Going first").takeIf { g.goingFirst.isNotEmpty() },
                        Block.Text(g.goingSecond.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"), label = "Going second").takeIf { g.goingSecond.isNotEmpty() },
                    ),
                ).takeIf { g.goingFirst.isNotEmpty() || g.goingSecond.isNotEmpty() },
            ) + g.roles.map { r -> GuideBook.Section(title = "${r.name}: ${r.cards.sumOf { it.copies }} cards", blocks = listOf(Block.CardNotes(r.cards))) },
        )

        if (g.lines.isNotEmpty()) chapters += GuideBook.Chapter(
            title = "Lines",
            summary = "Every opening worth knowing, step by step, with where it can be stopped and the board after each play.",
            sections = g.lines.map { l -> GuideBook.Section(title = l.name, blocks = listOfNotNull(Block.Text(l.note).takeIf { l.note.isNotBlank() }, Block.Line(l))) },
        )

        if (g.chokePoints.isNotEmpty()) chapters += GuideBook.Chapter(
            title = "Where it breaks",
            summary = "The cards that stop the deck, where they land, and what to do instead.",
            sections = g.chokePoints.map { c ->
                val stoppedAt = g.lines.flatMap { l -> l.steps.filter { c.card in it.stoppedBy }.map { s -> "In ${l.name}: ${s.action} If stopped: ${s.ifStopped}" } }
                GuideBook.Section(
                    title = c.card.ifBlank { "A choke point" },
                    blocks = listOfNotNull(
                        Block.Callout(c.text, kind = "choke", card = c.card),
                        Block.Text(stoppedAt.joinToString("\n"), label = "In the lines").takeIf { stoppedAt.isNotEmpty() },
                    ),
                )
            },
        )

        if (g.siding.isNotEmpty()) chapters += GuideBook.Chapter(
            title = "Matchups",
            summary = "Each deck in the field: what to side, their key card, your plan.",
            sections = g.siding.map { s -> GuideBook.Section(title = "vs ${s.matchup}", blocks = listOf(Block.Ledger(s))) },
        )

        chapters += GuideBook.Chapter(
            title = "Hands",
            summary = "What opening hands look like, and what to do with them.",
            sections = listOf(GuideBook.Section(title = "Three hands from the list", blocks = listOf(Block.Hands()))),
        )

        if (g.tips.isNotEmpty()) chapters += GuideBook.Chapter(
            title = "Tips",
            sections = listOf(GuideBook.Section(title = "Small things that win games", blocks = g.tips.map { Block.Callout(it) })),
        )

        return GuideBook(
            title = g.deckName,
            subtitle = g.subtitle,
            bigIdea = g.bigIdea,
            roles = g.roles,
            chapters = chapters,
            sources = g.sources,
            updatedAt = g.updatedAt,
            notesHash = g.notesHash,
        ).withIds()
    }

    /** "Lady Labrynth of the Silver Castle" as Lady Labrynth, as a tile's label has it. */
    fun shortName(name: String, deck: String): String {
        val at = listOf(" the ", " of ").map { name.indexOf(it) }.filter { it > 0 }.minOrNull()
        var s = if (at != null) name.substring(0, at) else name
        val words = s.split(' ')
        if (words.size >= 2 && words.first().equals(deck, ignoreCase = true)) s = words.drop(1).joinToString(" ")
        return s
    }
}
