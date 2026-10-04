package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.TournamentDeck

/**
 * Tournament lists the banlist no longer allows, taken out of the field (Phase B §4).
 *
 * The field is read from weeks of results, and a list that topped before the last banlist may play a card that is
 * now Forbidden, or three of one now Limited. Counted, it made a deck that cannot be registered look like part of the
 * field. A list is illegal when it holds more copies of a card — **counted by card**, every printing together
 * ([CardIdentity]), across Main, Extra and Side — than the card's limit allows. Each dropped list is kept with why, so
 * the answer can say how many were dropped and for what.
 *
 * The limit is [limitOf], today's list by default ([DeckEditor.copyLimit]); a dated banlist plugs in there. A card the
 * pool does not know cannot be judged and never drops a list.
 */
object FieldLegality {

    /** [deck] dropped: it holds [copies] of [card], over the [limit] its banlist allows. */
    data class Dropped(val deck: TournamentDeck, val card: Card, val copies: Int, val limit: Int)

    /** The lists kept and the ones dropped. */
    data class Reading(val kept: List<TournamentDeck>, val dropped: List<Dropped>)

    /** The banlist's region for a list's game: none for Genesys, which has no Forbidden & Limited list. */
    fun formatOf(format: DeckFormat): Format? = when (format) {
        DeckFormat.TCG -> Format.TCG
        DeckFormat.OCG -> Format.OCG
        DeckFormat.GENESYS -> null
    }

    /** [decks] split into those legal under [limitOf] (by default [format]'s list today) and those not. */
    fun check(
        decks: List<TournamentDeck>,
        cards: (CardId) -> Card?,
        format: Format,
        limitOf: (Card) -> Int = { DeckEditor.copyLimit(it, format) },
    ): Reading {
        val kept = mutableListOf<TournamentDeck>()
        val dropped = mutableListOf<Dropped>()
        decks.forEach { d ->
            val over = CardIdentity.counts(d.deck.main + d.deck.extra + d.deck.side, cards).entries.firstNotNullOfOrNull { (id, n) ->
                val card = cards(id) ?: return@firstNotNullOfOrNull null
                val limit = limitOf(card)
                if (n > limit) Dropped(d, card, n, limit) else null
            }
            if (over == null) kept += d else dropped += over
        }
        return Reading(kept, dropped)
    }

    /**
     * Why lists were dropped, in words: "3 lists illegal under today's TCG list were left out: 2 play Maxx "C"
     * (Forbidden), 1 plays 3 Ash Blossom & Joyous Spring (Limited)". Empty when none were. [list] names the banlist.
     */
    fun words(dropped: List<Dropped>, list: String): String {
        if (dropped.isEmpty()) return ""
        val why = dropped.groupBy { it.card.id to it.limit }.entries
            .sortedByDescending { it.value.size }
            .take(5)
            .joinToString("; ") { (key, lists) ->
                val card = lists.first().card
                val n = lists.size
                val status = when (key.second) {
                    0 -> "Forbidden"
                    1 -> "Limited"
                    2 -> "Semi-Limited"
                    else -> "at most ${key.second}"
                }
                val what = if (key.second == 0) card.name else "more than ${key.second} ${card.name}"
                "$n ${if (n == 1) "plays" else "play"} $what ($status)"
            }
        val more = dropped.groupBy { it.card.id to it.limit }.size - 5
        return "${dropped.size} ${if (dropped.size == 1) "list" else "lists"} illegal under $list ${if (dropped.size == 1) "was" else "were"} left out: $why" +
            (if (more > 0) "; and $more other cards" else "") + "."
    }
}
