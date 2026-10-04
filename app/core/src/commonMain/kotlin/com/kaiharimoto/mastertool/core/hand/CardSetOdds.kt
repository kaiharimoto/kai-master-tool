package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.world.Bound
import com.kaiharimoto.mastertool.core.world.Goals
import com.kaiharimoto.mastertool.core.world.HandCounter

/**
 * `hand_odds`'s question (Phase B §1, §4): the chance an opening hand holds at least so many of one set of cards
 * and, if asked, at least so many of a second.
 *
 * Two faults lived in the tool before this:
 * - **Copies were counted by passcode**: a set resolved from names holds canonical passcodes, and an alternate
 *   artwork in the deck has its own, so its copies were never counted. Here the deck and the sets are both read
 *   through [CardIdentity], so a copy counts whatever its printing.
 * - **Overlapping sets were wrong**: the second set lost the cards it shared with the first, so "an Ash, and a hand
 *   trap" (Ash among the hand traps) asked for a hand trap *besides* Ash. Each set is counted as it is here, through
 *   the World's [HandCounter], which is exact with overlap: a card in both sets counts for each.
 */
object CardSetOdds {

    /** At least [atLeast] of [cards] (any printing of each) in the hand. */
    data class Need(val cards: Set<CardId>, val atLeast: Int = 1)

    /**
     * The odds for a hand of five (going first) and six (going second), with what was counted: [copies] of each
     * need's cards in [deckSize], and [shared] copies that are in more than one need.
     */
    data class Answer(val first: Double, val second: Double, val copies: List<Int>, val shared: Int, val deckSize: Int)

    /** P(a hand of [hand] from [main] meets every one of [needs]). */
    fun probability(main: List<CardId>, needs: List<Need>, hand: Int, cards: (CardId) -> Card?): Double {
        if (main.isEmpty() || needs.isEmpty()) return if (needs.all { it.atLeast <= 0 }) 1.0 else 0.0
        val (counter, goal) = counter(main, needs, cards)
        return counter.probability(goal, hand)
    }

    /** Both turns' odds, and the counts behind them, in one reading of the deck. */
    fun of(main: List<CardId>, needs: List<Need>, cards: (CardId) -> Card?): Answer {
        val deck = CardIdentity.canonicalised(main, cards)
        val sets = needs.map { n -> CardIdentity.distinct(n.cards, cards) }
        val copies = sets.map { s -> deck.count { it in s } }
        val shared = deck.count { c -> sets.count { c in it } > 1 }
        if (deck.isEmpty() || needs.isEmpty()) {
            val p = if (needs.all { it.atLeast <= 0 }) 1.0 else 0.0
            return Answer(p, p, copies, shared, deck.size)
        }
        val (counter, goal) = counter(main, needs, cards)
        return Answer(counter.probability(goal, 5), counter.probability(goal, 6), copies, shared, deck.size)
    }

    private fun counter(main: List<CardId>, needs: List<Need>, cards: (CardId) -> Card?): Pair<HandCounter, List<List<Bound>>> {
        val deck = CardIdentity.canonicalised(main, cards).map { it.value.toString() }
        val sets = needs.map { n -> CardIdentity.distinct(n.cards, cards).mapTo(HashSet()) { it.value.toString() } }
        val goal = listOf(needs.mapIndexed { i, n -> Bound(i, n.atLeast.coerceAtLeast(0), Goals.NO_MAX) })
        return HandCounter.of(deck, sets) to goal
    }

    /** A Main Deck cut to a list's limits, and what was taken out (each card's name and how many). */
    data class Legalised(val main: List<CardId>, val removed: List<Pair<String, Int>>)

    /**
     * "Odds as of the March list" (Phase B): [deck]'s Main Deck with the copies that list does not allow taken out,
     * so the odds are those of the deck as it could be played then. A card's allowance is counted across the whole
     * deck by card (any printing); copies in the Extra and Side Decks use theirs first, and the Main Deck's last
     * copies are the ones taken. A card the pool does not know is left as it is.
     */
    fun legalised(deck: Deck, cards: (CardId) -> Card?, limits: BanSource): Legalised {
        val elsewhere = HashMap<CardId, Int>()
        (deck.extra + deck.side).forEach { id -> CardIdentity.canonical(id, cards).let { elsewhere[it] = (elsewhere[it] ?: 0) + 1 } }
        val allowed = HashMap<CardId, Int>()
        val keep = BooleanArray(deck.main.size) { true }
        val removed = LinkedHashMap<String, Int>()
        deck.main.forEachIndexed { i, id ->
            val card = cards(id) ?: return@forEachIndexed
            val left = allowed.getOrPut(card.id) {
                (minOf(Deck.MAX_COPIES, limits.statusOf(card).maxCopies) - (elsewhere[card.id] ?: 0)).coerceAtLeast(0)
            }
            if (left > 0) allowed[card.id] = left - 1
            else {
                keep[i] = false
                removed[card.name] = (removed[card.name] ?: 0) + 1
            }
        }
        return Legalised(deck.main.filterIndexed { i, _ -> keep[i] }, removed.toList())
    }
}
