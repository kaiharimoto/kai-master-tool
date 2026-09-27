package com.kaiharimoto.mastertool.core.model

/**
 * A card's alternate artworks (kai, 1.0.14: "if a card has an alt art or arts,
 * allow me to toggle between them via the inspector").
 *
 * Every artwork of a card has its own passcode — [Card.alternateIds] — and
 * YGOPRODeck serves each at the same path under its own passcode. The pool
 * stores only the first picture's addresses, so another artwork's are derived:
 * the passcode in the last segment of the address swapped for the other one.
 * That is all an artwork is here: which picture to draw. The deck, the rules
 * and the banlist never see it.
 */
object CardArt {
    private const val FULL = "https://images.ygoprodeck.com/images/cards/"
    private const val SMALL = "https://images.ygoprodeck.com/images/cards_small/"

    /** Every artwork of [card], its own first. */
    fun arts(card: Card): List<CardId> = (listOf(card.id) + card.alternateIds).distinct()

    /** The artwork [steps] along from [current], wrapping at either end. */
    fun step(card: Card, current: CardId?, steps: Int): CardId {
        val all = arts(card)
        val at = all.indexOf(current ?: card.id).coerceAtLeast(0)
        return all[((at + steps) % all.size + all.size) % all.size]
    }

    /**
     * [card] as drawn with artwork [art]: the same card with that passcode and
     * that picture's addresses, so anything keyed by a card's id — a cache of
     * originals, a decoded picture — keeps the two artworks apart. [card] itself
     * when [art] is its own, or not one of its artworks.
     */
    fun show(card: Card, art: CardId?): Card {
        if (art == null || art == card.id || art !in card.alternateIds) return card
        return card.copy(
            id = art,
            imageUrl = swap(card.imageUrl, card.id, art, FULL),
            imageUrlSmall = swap(card.imageUrlSmall, card.id, art, SMALL),
        )
    }

    /** [url] with passcode [from] in its last segment replaced by [to]; YGOPRODeck's own address when it has none. */
    fun swap(url: String?, from: CardId, to: CardId, fallback: String): String {
        if (url != null) {
            val cut = url.lastIndexOf('/')
            val name = url.substring(cut + 1)
            val stem = name.substringBefore('.')
            if (stem == from.value.toString()) {
                val ext = name.substringAfter('.', "jpg")
                return url.substring(0, cut + 1) + to.value + "." + ext
            }
        }
        return fallback + to.value + ".jpg"
    }
}
