package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId

/**
 * A page of search results, plus how many cards matched in total.
 *
 * The count is what lets the UI say "showing 150 of 3,214 matches" honestly.
 * Without it the only number available is the size of the whole pool, which
 * reads as though the other 12,850 cards had been rejected by the query rather
 * than merely not drawn.
 */
data class SearchOutcome(
    val cards: List<Card>,
    val matchCount: Int,
    /**
     * How many of [matchCount] were found by their printed text rather than by
     * their name.
     *
     * Reported separately because the two are different answers. "38 matches"
     * for a query that names one card and is mentioned by thirty-seven others
     * reads as a broken search; "1 by name, 37 by effect" reads as the thing
     * you were actually looking for.
     */
    val effectMatchCount: Int = 0,
) {
    val truncated: Boolean get() = matchCount > cards.size

    companion object {
        val EMPTY = SearchOutcome(emptyList(), 0)
    }
}

/**
 * An immutable, queryable view over the whole card pool.
 *
 * Built once after the database loads and then only read, so it is safe to share
 * across threads and cheap to hold in a view model.
 */
class CardIndex private constructor(
    val cards: List<Card>,
    private val byId: Map<CardId, Card>,
    private val byNormalizedName: Map<String, Card>,
    private val normalizedNames: List<String>,
) {

    val size: Int get() = cards.size

    /**
     * Resolves a passcode, including alternate artwork passcodes.
     *
     * Deck files exported by other tools frequently reference an alternate
     * printing. Looking those up through the same map is what stops an imported
     * deck from showing holes where perfectly ordinary cards should be.
     */
    fun byId(id: CardId): Card? = byId[id]

    fun byName(name: String): Card? = byNormalizedName[TextMatching.normalize(name)]

    /** Distinct monster types present in the pool, for building filter chips. */
    val races: List<String> by lazy {
        cards.mapNotNull { it.race }.distinct().sorted()
    }

    val archetypes: List<String> by lazy {
        cards.mapNotNull { it.archetype }.distinct().sorted()
    }

    val levels: List<Int> by lazy {
        cards.mapNotNull { it.level }.distinct().sorted()
    }

    /**
     * Ranked search.
     *
     * Filtering happens before scoring so an active filter shrinks the work
     * rather than adding to it, and results are capped so a one-letter query
     * cannot hand the UI 13,000 rows to lay out.
     *
     * [scope] decides whether the printed text under the name is searched too.
     * A scope named in the query itself — `text:`, `effect:`, `name:` — wins
     * over this argument, because typing it is a more specific act than leaving
     * a setting where it was. The text tiers all rank below every name tier
     * (see [EffectMatching]), so widening the scope only ever appends.
     */
    fun search(
        query: String,
        filter: CardFilter = CardFilter.NONE,
        scope: SearchScope = SearchScope.NAMES,
        limit: Int = 120,
    ): SearchOutcome {
        val parsed = SearchQuery.parse(query)
        val normalizedQuery = parsed.normalized
        val tokens = parsed.tokens
        val effectiveScope = parsed.scope ?: scope
        val readNames = effectiveScope != SearchScope.TEXT
        val readText = effectiveScope != SearchScope.NAMES

        if (normalizedQuery.isEmpty()) {
            // No query to rank by, so rank by name. Browsing a filter has to show
            // the same cards every time rather than whichever ones the API
            // happened to return first.
            val matches = cards.filter(filter::matches)
            return SearchOutcome(
                cards = matches.sortedBy { it.name }.take(limit),
                matchCount = matches.size,
            )
        }

        val hits = ArrayList<ScoredCard>(minOf(limit * 4, 512))
        var byText = 0
        for (i in cards.indices) {
            val card = cards[i]
            if (!filter.matches(card)) continue
            val nameScore = if (readNames) {
                TextMatching.score(normalizedNames[i], normalizedQuery, tokens)
            } else {
                null
            }
            // Only cards the name did not already claim are read for their
            // text: a card cannot be found twice, and the expensive half of
            // the scan is the half that is skipped for every name hit.
            val textScore = if (readText && nameScore == null) {
                EffectMatching.score(card.description, parsed)
            } else {
                null
            }
            val score = nameScore ?: textScore ?: continue
            if (nameScore == null) byText++
            hits.add(ScoredCard(card, score))
        }

        hits.sortWith(
            compareByDescending<ScoredCard> { it.score }
                .thenBy { it.card.name.length }
                .thenBy { it.card.name }
        )

        // Every match is already in `hits`, so the total costs nothing extra.
        return SearchOutcome(
            cards = hits.take(limit).map { it.card },
            matchCount = hits.size,
            effectMatchCount = byText,
        )
    }

    private class ScoredCard(val card: Card, val score: Int)

    companion object {
        fun build(cards: List<Card>): CardIndex {
            val byId = HashMap<CardId, Card>(cards.size * 2)
            val byName = HashMap<String, Card>(cards.size)
            val normalized = ArrayList<String>(cards.size)

            cards.forEach { card ->
                // Canonical id wins over an alternate id from another card if
                // they ever collide, so register alternates first.
                card.alternateIds.forEach { alt -> if (alt !in byId) byId[alt] = card }
            }
            cards.forEach { card ->
                byId[card.id] = card
                val key = TextMatching.normalize(card.name)
                if (key !in byName) byName[key] = card
                normalized.add(key)
            }

            return CardIndex(cards, byId, byName, normalized)
        }

        val EMPTY = build(emptyList())
    }
}
