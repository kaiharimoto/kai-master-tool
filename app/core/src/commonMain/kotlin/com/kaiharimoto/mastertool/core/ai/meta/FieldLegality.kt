package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.deck.Legality
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

    /** [deck] dropped: it holds [card], which was not out in the region by the day ([Legality.Release.NotYet]) or never was. */
    data class Unreleased(val deck: TournamentDeck, val card: Card, val release: Legality.Release)

    /** The lists of a past day: kept, holding cards not out yet ([unreleased]), or over that day's list ([dropped]). */
    data class Dated(val kept: List<TournamentDeck>, val unreleased: List<Unreleased>, val dropped: List<Dropped>)

    /**
     * [decks] as the field stood on [day] (`yyyy-MM-dd`; 1.1.1): a list holding a card not yet released in [format] that
     * day — or never released there — cannot have been played then, so it is set aside first (a list from a later
     * format, read into the window by the site's rough dates); the rest are held to the list in force that day,
     * [limits] (a `BanlistMatch`), at most three of a card. A card the pool does not know, or whose release it does not
     * know, drops nothing.
     */
    fun asOf(decks: List<TournamentDeck>, cards: (CardId) -> Card?, format: Format, day: String, limits: BanSource): Dated {
        val kept = mutableListOf<TournamentDeck>()
        val unreleased = mutableListOf<Unreleased>()
        decks.forEach { d ->
            val early = (d.deck.main + d.deck.extra + d.deck.side).distinct().firstNotNullOfOrNull { id ->
                val card = cards(id) ?: return@firstNotNullOfOrNull null
                when (val r = Legality.release(card, format, day)) {
                    is Legality.Release.NotYet, is Legality.Release.NotReleased -> Unreleased(d, card, r)
                    else -> null
                }
            }
            if (early == null) kept += d else unreleased += early
        }
        val legal = check(kept, cards, format) { minOf(3, limits.statusOf(it).maxCopies) }
        return Dated(legal.kept, unreleased, legal.dropped)
    }

    /**
     * Lists set aside for cards not out by [day], in words: "3 lists held cards not out in the TCG until later and were
     * left out: 2 play Fire King Island (out 8 Oct 2026), 1 plays Snake-Eye Ash (never released in the TCG)." Empty when
     * none were.
     */
    fun unreleasedWords(unreleased: List<Unreleased>, format: Format, day: String): String {
        if (unreleased.isEmpty()) return ""
        val groups = unreleased.groupBy { it.card.id }.entries.sortedByDescending { it.value.size }
        val why = groups.take(5).joinToString("; ") { (_, lists) ->
            val n = lists.size
            val r = lists.first().release
            val note = if (r is Legality.Release.NotYet) "out ${Legality.readable(r.date)}" else "never released in the ${Legality.word(format)}"
            "$n ${if (n == 1) "plays" else "play"} ${lists.first().card.name} ($note)"
        }
        val more = groups.size - 5
        val n = unreleased.size
        return "$n ${if (n == 1) "list" else "lists"} held cards not out in the ${Legality.word(format)} on ${Legality.readable(day)} and " +
            "${if (n == 1) "was" else "were"} left out: $why" + (if (more > 0) "; and $more other cards" else "") + "."
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
