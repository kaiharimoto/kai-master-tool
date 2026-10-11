package com.kaiharimoto.mastertool.core.duel.coach

import com.kaiharimoto.mastertool.core.duel.Provenance

/**
 * Drills from your own mistakes (Phase F, F.3; `docs/phases/F.md`): every mistake and blunder a review found in your
 * decisions is a position to find the better way in, kept on the siding drills' Leitner boxes (`PrepDoc.drills`, keys
 * `misplay:<replay>:<step>`). The choices are the ways the search weighed there, what was played among them; an answer is
 * right when the way chosen is within an inaccuracy of the best — the review's own measure.
 */
object MisplayDrills {
    const val PREFIX = "misplay:"

    fun key(replay: String, at: Int): String = "$PREFIX$replay:$at"

    fun isMisplay(key: String): Boolean = key.startsWith(PREFIX)

    /** A choice in a drill: a way in the search's words and the win chance it gives up against the best. */
    data class Choice(val words: String, val drop: Double) {
        /** Within an inaccuracy of the best: the answer the drill counts right. */
        val right: Boolean get() = drop < Mark.INACCURACY.points
    }

    /** One drill: the position ([replay] at step [at]), how it was marked, and its [choices] in their fixed order. */
    data class Item(
        val key: String,
        val replay: String,
        val at: Int,
        val seat: Int,
        val mark: Mark,
        val turn: Int,
        val phase: String,
        val played: String,
        val best: String,
        val choices: List<Choice>,
        val line: List<String>,
    )

    /**
     * Every mistake and blunder of [who] in [books] that a drill can be made of: two choices at least, one of them right and
     * one not. A review before F.3 kept only the way played and the best, and those two are the choices.
     */
    fun items(books: List<ReviewBook>, who: String = Provenance.PERSON): List<Item> = books.sortedBy { it.played.takeIf { p -> p > 0 } ?: it.at }.flatMap { b ->
        b.decisions.mapNotNull { j ->
            val mark = j.mark?.let { runCatching { Mark.valueOf(it) }.getOrNull() }
            if (!j.judged || j.by != who || (mark != Mark.MISTAKE && mark != Mark.BLUNDER)) return@mapNotNull null
            val ways = j.ways.ifEmpty { listOf(Way(j.best, 0.0), Way(j.played, j.drop)) }.distinctBy { it.words }
            val choices = ways.map { Choice(it.words, it.drop) }
            if (choices.size < 2 || choices.none { it.right } || choices.all { it.right }) return@mapNotNull null
            val key = key(b.replay, j.at)
            Item(key, b.replay, j.at, j.seat, mark, j.turn, j.phase, j.played, j.best, order(key, choices), j.line)
        }
    }

    /** The choices in an order of their own for each drill, the same every time it is asked, so the best is never always first. */
    fun order(key: String, choices: List<Choice>): List<Choice> = choices.sortedBy { (key + "|" + it.words).hashCode() }

    /** The question, in words: where it was, never what was played. */
    fun question(item: Item, replayName: String): String = buildString {
        if (item.turn > 0) append("Turn ${item.turn}") else append("A position")
        if (item.phase.isNotBlank()) append(", ${item.phase}")
        append(", in “").append(replayName).append("”: which way is better?")
    }

    /** What an answer comes to, in words: right or not, and every choice with what it gives up. */
    fun verdict(item: Item, chosen: Choice): String = buildString {
        append(if (chosen.right) "Right" else "Not this one")
        append(": ${chosen.words} gives up ${pts(chosen.drop)} points of win chance. ")
        append("The best way here: ${item.best}")
        if (item.line.isNotEmpty()) append(" (the search's line: ${item.line.joinToString(" · ")})")
        append(". In the duel, ${item.played} was played (${item.mark.sign} ${item.mark.words}).")
    }

    private fun pts(x: Double): String = if (x == x.toInt().toDouble()) "${x.toInt()}" else "$x"
}
