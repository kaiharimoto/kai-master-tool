package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.search.SearchScope

/**
 * Cards to put on an opponent's face from nothing but the name typed for it.
 *
 * An opponent made on the Siding page is a name and three cards, and the name
 * is what a player knows first: "Snake-Eye Fire King", "Yubel", "Ryzeal
 * Mitsurugi". Players name decks by their archetypes, so the name is read for
 * the archetypes it spells, and each archetype offers its own cards — the
 * monsters that carry its name first, because those are the faces a player
 * recognises the deck by. A deck that is two archetypes is offered both in
 * turn, so the first three suggestions already show the mix.
 *
 * Some decks are not an archetype at all: kai's example is the deck built out
 * of cards that *mention* "Light and Darkness Ritual". When the name is itself
 * a card's name, that card and the cards whose text quotes it come first; and
 * when the archetypes give too little, the printed text is searched for the
 * name, then the names — the same fallbacks the pool's search has.
 */
object OpponentGuess {

    /** Below this many suggestions the name is searched for as well. */
    private const val ENOUGH = 3

    /**
     * The archetypes [name] spells, most words first, then in the order they
     * are written.
     *
     * An archetype matches when all its words appear, whole and in a row, among
     * the name's words; case, hyphens and apostrophes do not count, so
     * "snake eye", "Snake-Eye" and "SNAKE-EYE" are one. An archetype that is
     * only part of a longer one found in the same words — "Fire King" inside
     * "Fire King Avatar" — is left out: the longer one is what was meant.
     */
    fun archetypesIn(name: String, archetypes: List<String>): List<String> {
        val words = words(name)
        if (words.isEmpty()) return emptyList()
        val found = archetypes.mapNotNull { archetype ->
            val parts = words(archetype)
            val at = find(words, parts)
            if (at < 0) null else Found(archetype, at, at + parts.size)
        }
        val kept = mutableListOf<Found>()
        found.sortedWith(compareByDescending<Found> { it.end - it.start }.thenBy { it.start }.thenBy { it.archetype })
            .forEach { f ->
                val inside = kept.any { k -> f.start >= k.start && f.end <= k.end }
                if (!inside && kept.none { it.archetype.equals(f.archetype, ignoreCase = true) }) kept += f
            }
        return kept.map { it.archetype }
    }

    /**
     * Up to [limit] cards for an opponent called [name], best first.
     *
     * Each archetype's cards are ranked — main-deck effect monsters carrying
     * the archetype's name, then its other main-deck monsters, then its Extra
     * Deck, then its Spells and Traps; inside a rank a name carrying the
     * archetype's, then the alphabet — and the archetypes take turns, so two
     * of them share the first places. A card is offered once.
     */
    fun suggest(name: String, index: CardIndex, limit: Int = 18): List<Card> {
        if (limit <= 0 || name.isBlank()) return emptyList()
        val out = LinkedHashMap<Int, Card>()
        fun add(card: Card) {
            if (out.size < limit && card.isPlayable) out.getOrPut(card.id.value) { card }
        }
        val clean = name.replace('"', ' ').trim()
        val matched = archetypesIn(clean, index.archetypes)

        // A deck named after one card is the cards that quote it — unless the
        // name is also a whole archetype ("Yubel"), whose own ranking is better.
        val wholeArchetype = matched.any { words(it) == words(clean) }
        val named = if (wholeArchetype) null else index.byName(clean)
        if (named != null) {
            add(named)
            index.search("\"$clean\"", scope = SearchScope.TEXT, limit = limit * 2).cards.forEach(::add)
            if (out.size >= ENOUGH) return out.values.toList()
        }

        if (matched.isNotEmpty()) {
            val wanted = matched.toSet()
            val byArchetype = index.cards.filter { it.archetype in wanted }.groupBy { it.archetype!! }
            val ranked = matched.map { a -> rank(a, byArchetype[a].orEmpty()) }
            var i = 0
            while (out.size < limit && ranked.any { i < it.size }) {
                ranked.forEach { list -> list.getOrNull(i)?.let(::add) }
                i++
            }
        }

        if (out.size < ENOUGH) {
            index.search("\"$clean\"", scope = SearchScope.ALL, limit = limit * 2).cards.forEach(::add)
            index.search(clean, scope = SearchScope.NAMES, limit = limit * 2).cards.forEach(::add)
        }
        return out.values.toList()
    }

    /** [cards] of [archetype] in the order a player would pick its faces. */
    private fun rank(archetype: String, cards: List<Card>): List<Card> {
        val parts = words(archetype)
        fun named(card: Card) = find(words(card.name), parts) >= 0
        fun tier(card: Card): Int = when {
            card.category == CardCategory.MONSTER && !card.isExtraDeck ->
                if (card.type.contains("Effect", ignoreCase = true) && named(card)) 0 else 1
            card.category == CardCategory.MONSTER -> 2
            else -> 3
        }
        return cards.sortedWith(
            compareBy<Card> { tier(it) }.thenBy { if (named(it)) 0 else 1 }.thenBy { it.name.lowercase() },
        )
    }

    private class Found(val archetype: String, val start: Int, val end: Int)

    /**
     * [text] as lowercase words: apostrophes dropped ("Dragon's" is "dragons"),
     * every other run of anything but letters and digits a break, so a hyphen
     * splits "Snake-Eye" into two words just as a space does.
     */
    internal fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        for (ch in text) {
            when {
                ch == '\'' || ch == '’' -> Unit
                ch.isLetterOrDigit() -> word.append(ch.lowercaseChar())
                word.isNotEmpty() -> {
                    out += word.toString()
                    word.clear()
                }
            }
        }
        if (word.isNotEmpty()) out += word.toString()
        return out
    }

    /** Where [run] starts among [words], whole and in a row, or -1. */
    private fun find(words: List<String>, run: List<String>): Int {
        if (run.isEmpty() || run.size > words.size) return -1
        for (i in 0..words.size - run.size) {
            if ((run.indices).all { words[i + it] == run[it] }) return i
        }
        return -1
    }
}
