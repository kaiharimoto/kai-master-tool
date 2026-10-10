package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.deck.DeckRules
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.math.abs

/**
 * Cards like this one, and more cards for a role (Phase G, G.7; the red team's R1). Search had names, text and facets, but
 * no likeness. A card's [Profile] is what it does and what it is — its effect kinds ([EffectKinds]), its category and frame
 * or Spell/Trap property, its Level, attribute and race, its archetype, and its text as overlapping runs of words — and two
 * profiles are alike by a weighted sum of their parts ([score], 0 to 1).
 *
 * A group is searched by its own members: a candidate is as alike as it is, on average, to the group's cards. It never
 * assigns: what joins a group stays the person's choice. Only cards the rules in force let into the deck are offered —
 * released, not Forbidden, under the Genesys points left — and never a card already at its limit there ([Room]).
 */
object CardLikeness {
    class Profile(
        val kinds: Set<EffectKind>,
        val category: CardCategory,
        /** The monster frame (effect, fusion, link…) or, for a Spell or Trap, its property (Quick-Play, Continuous…). */
        val form: String,
        val level: Int?,
        val attribute: String?,
        val race: String?,
        val archetype: String?,
        val shingles: Set<Long>,
        /** The card's own name, folded, so a text naming it counts as its archetype. */
        val name: String,
    )

    /** Each part's weight in [score]; they sum to one. */
    const val KINDS = 0.32
    const val FORM = 0.14
    const val STATS = 0.12
    const val ARCHETYPE = 0.17
    const val TEXT = 0.25

    fun profile(card: Card): Profile {
        val text = card.description.lowercase()
        return Profile(
            kinds = EffectKinds.of(card),
            category = card.category,
            form = if (card.category == CardCategory.MONSTER) card.frameType.lowercase() else (card.race ?: "").lowercase(),
            level = card.level ?: card.linkValue,
            attribute = card.attribute.name.takeIf { card.category == CardCategory.MONSTER },
            race = card.race?.lowercase()?.takeIf { card.category == CardCategory.MONSTER },
            archetype = card.archetype?.lowercase()?.takeIf { it.isNotBlank() },
            shingles = shingles(text),
            name = card.name.lowercase(),
        )
    }

    /** How alike two cards are, 0 to 1. */
    fun score(a: Profile, b: Profile): Double {
        val kinds = jaccard(a.kinds, b.kinds, empty = if (a.category == b.category) 0.5 else 0.0)
        val form = when {
            a.category != b.category -> 0.0
            a.form == b.form -> 1.0
            else -> 0.4
        }
        val stats = if (a.category != CardCategory.MONSTER || b.category != CardCategory.MONSTER) (if (a.category == b.category) 0.5 else 0.0) else {
            val lv = if (a.level != null && b.level != null && abs(a.level - b.level) <= 1) 1.0 else 0.0
            (lv + (if (a.attribute == b.attribute) 1.0 else 0.0) + (if (a.race == b.race) 1.0 else 0.0)) / 3
        }
        val archetype = when {
            a.archetype != null && a.archetype == b.archetype -> 1.0
            a.archetype != null && b.shingles.isNotEmpty() && names(b, a.archetype) -> 0.6
            b.archetype != null && a.shingles.isNotEmpty() && names(a, b.archetype) -> 0.6
            else -> 0.0
        }
        val text = jaccard(a.shingles, b.shingles, empty = 0.0)
        return KINDS * kinds + FORM * form + STATS * stats + ARCHETYPE * archetype + TEXT * text
    }

    /**
     * What a candidate may be (the rules in force): released and not Forbidden ([rules]), under the [pointsLeft] Genesys
     * points (null: no cap), and not already at its copy limit in the deck ([held]: copies by canonical passcode).
     */
    class Room(
        val rules: DeckRules?,
        val today: String,
        val pointsLeft: Int? = null,
        val held: Map<CardId, Int> = emptyMap(),
    ) {
        fun admits(card: Card): Boolean {
            val r = rules ?: return true
            if (r.standing(card, today).blocked) return false
            if (pointsLeft != null && (card.genesysPoints ?: 0) > pointsLeft) return false
            if ((held[card.id] ?: 0) >= r.copyLimit(card)) return false
            return true
        }
    }

    /** A candidate and how alike it is. */
    data class Like(val card: Card, val score: Double)

    /**
     * The cards of [pool] most like [targets] (one card, or a group's members), best first: each scored by its mean
     * likeness to the targets, the targets themselves and their printings left out, [room] deciding what may be offered.
     */
    fun similar(targets: List<Card>, pool: List<Card>, room: Room, limit: Int = 24, floor: Double = 0.2): List<Like> {
        if (targets.isEmpty()) return emptyList()
        val profiles = targets.map(::profile)
        val skip = targets.flatMap { listOf(it.id) + it.alternateIds }.toSet()
        return pool.asSequence()
            .filter { it.id !in skip && room.admits(it) }
            .map { c -> val p = profile(c); Like(c, profiles.sumOf { score(it, p) } / profiles.size) }
            .filter { it.score >= floor }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** Whether [p]'s text names [word] (an archetype) as a run of its words. */
    private fun names(p: Profile, word: String): Boolean = shingles(word).let { w -> w.isNotEmpty() && w.all { it in p.shingles } }

    private fun <T> jaccard(a: Set<T>, b: Set<T>, empty: Double): Double {
        if (a.isEmpty() && b.isEmpty()) return empty
        val shared = a.count { it in b }
        return shared.toDouble() / (a.size + b.size - shared)
    }

    /** The text's runs of three words, hashed (two for a short text): what two effects say alike, in any order. */
    fun shingles(text: String): Set<Long> {
        val words = text.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() && it !in STOP }
        val n = if (words.size >= 3) 3 else words.size
        if (n == 0) return emptySet()
        val out = HashSet<Long>()
        for (i in 0..words.size - n) {
            var h = 1125899906842597L
            for (j in i until i + n) h = 31 * h + words[j].hashCode()
            out += h
        }
        return out
    }

    private val STOP = setOf("the", "a", "an", "of", "to", "and", "or", "this", "that", "you", "your", "it", "its", "if", "is", "can", "1", "card", "cards")
}
