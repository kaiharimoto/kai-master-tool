package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.ai.playbook.Play
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck

/**
 * What a cut breaks (Phase G, G.9; the red team's A4). A deck's lines live in three places — the Duel page's combos, the
 * playbook's lines and the Mapper's starter table — and no edit checked any of them. When an edit takes a card's last copy
 * out of the Main or Extra Deck, [of] names what used it: "− Engraver: used by 3 combos, 5 mapped boards, 2 playbook lines".
 * Combos and the playbook name cards in words, so a card is found by its exact name (in a combo's steps, as a run of its
 * words); the starter table by its canonical passcode.
 */
object DeckDependents {
    /** One card gone and what used it: combos and playbook lines by name, and the mapped boards its starters reached. */
    data class Of(val card: Card, val combos: List<String>, val plays: List<String>, val boards: Int) {
        val any: Boolean get() = combos.isNotEmpty() || plays.isNotEmpty() || boards > 0
    }

    /** The cards whose last copy left the Main and Extra Decks between [before] and [after], by card. */
    fun lastCopiesGone(before: Deck, after: Deck, cards: (CardId) -> Card?): List<Card> {
        val had = CardIdentity.distinct(before.main + before.extra, cards)
        val has = CardIdentity.distinct(after.main + after.extra, cards)
        return (had - has).mapNotNull(cards)
    }

    /** What each of [gone] was used by, those used by nothing left out. */
    fun of(gone: List<Card>, combos: List<Combo>, plays: List<Play>, starters: List<StarterTable.Row>): List<Of> = gone.map { card ->
        val name = card.name.trim().lowercase()
        val usedBy = combos.filter { c -> c.needs.any { it.trim().lowercase() == name } || c.steps.any { s -> s.lowercase().contains(name) } }.map { it.name }
        val inPlays = plays.filter { p -> p.allCards.any { it.lowercase() == name } }.map { it.title }
        val passcodes = (card.alternateIds + card.id).map { it.value }.toSet()
        val boards = starters.filter { r -> r.cards.any { it in passcodes } }.flatMap { it.ends }.distinct().size
        Of(card, usedBy, inPlays, boards)
    }.filter { it.any }

    /** "− Engraver: used by 3 combos, 5 mapped boards, 2 playbook lines". */
    fun words(d: Of): String {
        fun n(k: Int, one: String, many: String) = if (k == 1) "1 $one" else "$k $many"
        val parts = listOfNotNull(
            d.combos.size.takeIf { it > 0 }?.let { n(it, "combo", "combos") },
            d.boards.takeIf { it > 0 }?.let { n(it, "mapped board", "mapped boards") },
            d.plays.size.takeIf { it > 0 }?.let { n(it, "playbook line", "playbook lines") },
        )
        return "− ${d.card.name}: used by ${parts.joinToString(", ")}"
    }

    /** Every card's line, and the combos and lines by name, for Ai: what to check before the cut is made. */
    fun report(list: List<Of>): String = list.joinToString("\n") { d ->
        words(d) + listOfNotNull(
            d.combos.takeIf { it.isNotEmpty() }?.let { " (combos: ${it.take(6).joinToString()}${if (it.size > 6) ", …" else ""})" },
            d.plays.takeIf { it.isNotEmpty() }?.let { " (playbook: ${it.take(6).joinToString()}${if (it.size > 6) ", …" else ""})" },
        ).joinToString("")
    }
}
