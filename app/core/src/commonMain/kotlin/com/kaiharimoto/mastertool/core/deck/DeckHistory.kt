package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection

/**
 * One step of the undo history, in words (kai, 1.0.17: "a history button so the
 * user can see the list of changes they've made"). The undo stack keeps whole
 * decks, not edits, so what a step *was* is read back off the two decks either
 * side of it: cards in, cards out, a card moved between sections, a section
 * reordered — or, when the cards did not change, the groups or the deck itself.
 */
object DeckHistory {

    fun describe(
        before: Deck,
        after: Deck,
        groupsChanged: Boolean,
        deckChanged: Boolean,
        name: (CardId) -> String?,
    ): String {
        fun n(id: CardId) = name(id) ?: id.value.toString()
        if (deckChanged) return if (after.totalCards == 0) "Started a new deck" else "Opened a deck of ${after.totalCards}"
        val gained = mutableListOf<Pair<CardId, DeckSection>>()
        val lost = mutableListOf<Pair<CardId, DeckSection>>()
        DeckSection.entries.forEach { s ->
            val b = before[s].groupingBy { it }.eachCount()
            val a = after[s].groupingBy { it }.eachCount()
            (b.keys + a.keys).forEach { id ->
                val d = (a[id] ?: 0) - (b[id] ?: 0)
                repeat(maxOf(d, 0)) { gained += id to s }
                repeat(maxOf(-d, 0)) { lost += id to s }
            }
        }
        // A card out of one section and into another is a move.
        val moves = mutableListOf<Triple<CardId, DeckSection, DeckSection>>()
        lost.toList().forEach { out ->
            val into = gained.firstOrNull { it.first == out.first && it.second != out.second } ?: return@forEach
            moves += Triple(out.first, out.second, into.second)
            lost.remove(out)
            gained.remove(into)
        }
        val parts = buildList {
            moves.groupBy { it }.forEach { (m, all) -> add("${count(all.size)}${n(m.first)} to ${m.third.displayName.lowercase()}") }
            gained.groupBy { it }.forEach { (g, all) -> add("+ ${count(all.size)}${n(g.first)}${where(g.second)}") }
            lost.groupBy { it }.forEach { (l, all) -> add("− ${count(all.size)}${n(l.first)}${where(l.second)}") }
        }
        return when {
            parts.isNotEmpty() && moves.isNotEmpty() && gained.isEmpty() && lost.isEmpty() && parts.size == 1 -> "Moved ${parts[0]}"
            parts.size == 1 -> parts[0]
            parts.size > 1 -> "${parts[0]}, and ${parts.size - 1} more"
            DeckSection.entries.any { before[it] != after[it] } -> "Reordered the ${DeckSection.entries.first { before[it] != after[it] }.displayName.lowercase()} deck"
            groupsChanged -> "Changed the groups"
            else -> "No change"
        }
    }

    private fun count(n: Int) = if (n > 1) "$n× " else ""

    private fun where(s: DeckSection) = if (s == DeckSection.MAIN) "" else " (${s.displayName.lowercase()})"
}
