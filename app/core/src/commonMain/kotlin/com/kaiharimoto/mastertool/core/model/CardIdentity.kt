package com.kaiharimoto.mastertool.core.model

/**
 * One card, whatever its printing (Phase B, `docs/phases/B.md` §1).
 *
 * A deck file holds passcodes, and an alternate artwork has its own: Ash Blossom is `14558127` and its alternate
 * `14558128`. Counted by passcode, two of each read as two and two, and four Ash were legal. A card is its
 * **canonical** passcode, the one the pool's index resolves any printing to (`CardIndex.byId`); everything that
 * counts copies, sets of cards or "the same card" goes through here. The deck itself is never rewritten: the
 * printing a person chose is kept.
 *
 * [cards] is a lookup that resolves alternates (the index's `byId`). A passcode it does not know stands for itself,
 * so an unknown card is still counted, once per passcode.
 */
object CardIdentity {

    /** The card's own passcode for any printing of it. */
    fun canonical(id: CardId, cards: (CardId) -> Card?): CardId = cards(id)?.id ?: id

    /** Copies of each card in [ids], keyed by its canonical passcode, in first-seen order. */
    fun counts(ids: Iterable<CardId>, cards: (CardId) -> Card?): Map<CardId, Int> {
        val out = LinkedHashMap<CardId, Int>()
        ids.forEach { id ->
            val c = canonical(id, cards)
            out[c] = (out[c] ?: 0) + 1
        }
        return out
    }

    /** The distinct cards in [ids], as canonical passcodes, in first-seen order. */
    fun distinct(ids: Iterable<CardId>, cards: (CardId) -> Card?): Set<CardId> =
        ids.mapTo(LinkedHashSet()) { canonical(it, cards) }

    /** [ids] with every printing replaced by its card's canonical passcode, order and length kept. */
    fun canonicalised(ids: List<CardId>, cards: (CardId) -> Card?): List<CardId> = ids.map { canonical(it, cards) }

    /** Copies of [card] across every section of [deck], whichever printing each is. */
    fun copiesOf(deck: Deck, card: Card): Int {
        val names = card.passcodes
        return deck.main.count { it in names } + deck.extra.count { it in names } + deck.side.count { it in names }
    }

    /** Copies of the card [id] names, by any printing, across [deck]. */
    fun copiesOf(deck: Deck, id: CardId, cards: (CardId) -> Card?): Int {
        val card = cards(id) ?: return deck.copiesOf(id)
        return copiesOf(deck, card)
    }

    /** Whether two passcodes are the same card. */
    fun same(a: CardId, b: CardId, cards: (CardId) -> Card?): Boolean = a == b || canonical(a, cards) == canonical(b, cards)
}
