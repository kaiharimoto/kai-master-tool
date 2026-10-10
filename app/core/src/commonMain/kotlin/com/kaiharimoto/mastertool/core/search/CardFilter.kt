package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.deck.DeckRules
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.Format

/**
 * The quick-filter state.
 *
 * Every set is OR within itself and AND across fields, which is how filter chips
 * are read intuitively: "DARK or LIGHT, and a Monster, and level 4".
 * An empty set means the field is not filtering at all.
 */
data class CardFilter(
    val categories: Set<CardCategory> = emptySet(),
    val attributes: Set<Attribute> = emptySet(),
    val races: Set<String> = emptySet(),
    val levels: Set<Int> = emptySet(),
    val archetypes: Set<String> = emptySet(),
    val banStatuses: Set<BanStatus> = emptySet(),
    val atkRange: IntRange? = null,
    val defRange: IntRange? = null,
    /** null shows both, true shows only Extra Deck cards, false only main-deck. */
    val extraDeckOnly: Boolean? = null,
    val format: Format = Format.TCG,
    // The desktop's deeper facets (Neue 1.0.19, after DuelingBook, Master Duel and
    // Neuron). Trailing and empty by default, so the tablet's filter is unchanged.
    /** Monster card types — the frame and its mechanic. OR within. */
    val frames: Set<MonsterFrame> = emptySet(),
    /** Tuner, Flip, Gemini, Spirit, Toon, Union. OR within. */
    val abilities: Set<MonsterAbility> = emptySet(),
    /**
     * A Spell's or Trap's property — Normal, Continuous, Quick-Play, Equip, Field,
     * Ritual, Counter — which YGOPRODeck stores in the same field as a monster's
     * type. Read together with [races] as one choice: a card passes if it is a
     * monster of one of [races] or a Spell/Trap of one of these.
     */
    val properties: Set<String> = emptySet(),
    /** Link ratings. OR within. */
    val linkRatings: Set<Int> = emptySet(),
    /** Pendulum scales. OR within. */
    val scales: Set<Int> = emptySet(),
    /** Link arrows the card must point **all** of — how every deck builder reads them. */
    val linkArrows: Set<String> = emptySet(),
    /** What the card does, read from its text (`EffectKinds`). The card must do **all** of them. */
    val effects: Set<EffectKind> = emptySet(),
    /** The order results are listed in. Not a facet: it never hides a card. */
    val sort: CardSort = CardSort.RELEVANCE,
    /** Turns [sort] around. */
    val reverse: Boolean = false,
    /** Only these passcodes — a list of cards kept for consideration. Not a facet either. */
    val onlyIds: Set<Int>? = null,
    /**
     * The list [banStatuses] reads (2026-10, the red team's finding 8): the builder's rules in force — a chosen day's
     * list, or none under Genesys — handed in by whoever searches; null is the pool's own status in [format].
     */
    val banSource: BanSource? = null,
    // Search that knows the rules in force (Phase G, R2). Trailing and empty by default.
    /** The builder's rules in force, for [legalOnly]; handed in by whoever searches. */
    val rules: DeckRules? = null,
    /** The day [rules] are read on when they name none, `yyyy-MM-dd`. */
    val today: String = "",
    /** Only cards the rules let into a deck: released in the region by the day, not Forbidden, not barred from Genesys. */
    val legalOnly: Boolean = false,
    /** Genesys points, at least and at most. */
    val points: IntRange? = null,
    /** Released in the region on or after this day, `yyyy-MM-dd`: what is new. */
    val releasedAfter: String? = null,
    /** Out in the OCG and not yet in the TCG: what is coming. */
    val notYetInTcg: Boolean = false,
) {
    val isActive: Boolean
        get() = activeFacetCount > 0

    /** Number of distinct facets in use, for the "Filters (3)" badge. */
    val activeFacetCount: Int
        get() = listOf(
            categories.isNotEmpty(), attributes.isNotEmpty(), races.isNotEmpty(),
            levels.isNotEmpty(), archetypes.isNotEmpty(), banStatuses.isNotEmpty(),
            atkRange != null, defRange != null, extraDeckOnly != null,
            frames.isNotEmpty(), abilities.isNotEmpty(), properties.isNotEmpty(),
            linkRatings.isNotEmpty(), scales.isNotEmpty(), linkArrows.isNotEmpty(), effects.isNotEmpty(),
            legalOnly, points != null, releasedAfter != null, notYetInTcg,
        ).count { it }

    /** This filter with every facet cleared, keeping the format, the order and the list. */
    fun cleared(): CardFilter = CardFilter(format = format, sort = sort, reverse = reverse, onlyIds = onlyIds, banSource = banSource, rules = rules, today = today)

    fun matches(card: Card): Boolean {
        if (categories.isNotEmpty() && card.category !in categories) return false
        if (attributes.isNotEmpty() && card.attribute !in attributes) return false
        if (onlyIds != null && card.id.value !in onlyIds) return false
        if (races.isNotEmpty() || properties.isNotEmpty()) {
            val monster = races.isNotEmpty() && card.race in races
            val spellTrap = properties.isNotEmpty() && card.category != CardCategory.MONSTER && card.race in properties
            if (!monster && !spellTrap) return false
        }
        if (frames.isNotEmpty() && frames.none { it.matches(card) }) return false
        if (abilities.isNotEmpty() && abilities.none { it.matches(card) }) return false
        if (linkRatings.isNotEmpty() && card.linkValue !in linkRatings) return false
        if (scales.isNotEmpty() && card.pendulumScale !in scales) return false
        if (linkArrows.isNotEmpty() && !card.linkMarkers.containsAll(linkArrows)) return false
        if (levels.isNotEmpty() && card.level !in levels) return false
        if (archetypes.isNotEmpty() && card.archetype !in archetypes) return false
        if (banStatuses.isNotEmpty() && (banSource?.statusOf(card) ?: card.banStatus(format)) !in banStatuses) return false
        if (extraDeckOnly != null && card.isExtraDeck != extraDeckOnly) return false

        // A monster with no ATK/DEF (Link monsters have no DEF) cannot satisfy a
        // range filter, so treat a missing value as excluded rather than as zero.
        atkRange?.let { range -> if (card.atk == null || card.atk !in range) return false }
        defRange?.let { range -> if (card.def == null || card.def !in range) return false }
        // The rules in force (R2): a card with no points listed is not inside any range of them.
        points?.let { range -> if (card.genesysPoints == null || card.genesysPoints !in range) return false }
        if (notYetInTcg && !(card.ocgDate != null && (card.tcgDate == null || (today.isNotEmpty() && card.tcgDate > today)))) return false
        releasedAfter?.let { day -> val date = releaseDate(card) ?: return false; if (date < day) return false }
        if (legalOnly) {
            val r = rules ?: DeckRules(format = format)
            if (r.standing(card, today.ifEmpty { "9999-12-31" }).blocked) return false
        }

        // Last, because it reads the card's text.
        if (effects.isNotEmpty() && !EffectKinds.hasAll(card, effects)) return false
        return true
    }

    /** The card's release in the region searched (the TCG under Genesys), `yyyy-MM-dd`, or null when not known. */
    fun releaseDate(card: Card): String? = if (format == Format.OCG && rules?.genesys != true) card.ocgDate else card.tcgDate

    companion object {
        val NONE = CardFilter()

        /** The newest date a card was released anywhere, for [CardSort.NEWEST]. */
        fun newest(card: Card): String = listOfNotNull(card.tcgDate, card.ocgDate).maxOrNull().orEmpty()
    }
}

/** A monster's card type, as the frame reads. */
enum class MonsterFrame(val label: String) {
    NORMAL("Normal"), EFFECT("Effect"), RITUAL("Ritual"), FUSION("Fusion"),
    SYNCHRO("Synchro"), XYZ("Xyz"), PENDULUM("Pendulum"), LINK("Link");

    fun matches(card: Card): Boolean {
        if (card.category != CardCategory.MONSTER) return false
        val frame = card.frameType.lowercase()
        return when (this) {
            NORMAL -> frame == "normal" || frame == "normal_pendulum"
            EFFECT -> card.type.contains("Effect", ignoreCase = true)
            RITUAL -> frame.startsWith("ritual")
            FUSION -> frame.startsWith("fusion")
            SYNCHRO -> frame.startsWith("synchro")
            XYZ -> frame.startsWith("xyz")
            PENDULUM -> frame.endsWith("pendulum")
            LINK -> frame == "link"
        }
    }
}

/** The monster abilities printed in a card's type line. */
enum class MonsterAbility(val label: String) {
    TUNER("Tuner"), FLIP("Flip"), GEMINI("Gemini"), SPIRIT("Spirit"), TOON("Toon"), UNION("Union");

    fun matches(card: Card): Boolean =
        card.category == CardCategory.MONSTER && card.type.split(' ').any { it.equals(label, ignoreCase = true) }
}

/** The orders a result list can be read in; each has its natural direction. */
enum class CardSort(val label: String) {
    RELEVANCE("Best match"), NAME("Name"), ATK("ATK"), DEF("DEF"), LEVEL("Level"),

    /** Genesys points, the most first (Phase G, R2): under Genesys, points are the trade-off. */
    POINTS("Points"),

    /** The newest release anywhere first: what is new, and what is coming. */
    NEWEST("Newest");

    /** Sorts [cards] (already in relevance order) by this; stable, so ties keep relevance. */
    fun apply(cards: List<Card>, reverse: Boolean): List<Card> {
        val sorted = when (this) {
            RELEVANCE -> cards
            NAME -> cards.sortedBy { it.name }
            ATK -> cards.sortedByDescending { it.atk ?: -1 }
            DEF -> cards.sortedByDescending { it.def ?: -1 }
            LEVEL -> cards.sortedByDescending { it.level ?: it.linkValue ?: -1 }
            POINTS -> cards.sortedByDescending { it.genesysPoints ?: -1 }
            NEWEST -> cards.sortedByDescending { CardFilter.newest(it) }
        }
        return if (reverse) sorted.asReversed() else sorted
    }
}

/** Spell and Trap properties, as YGOPRODeck spells them. */
object CardProperties {
    val SPELL = listOf("Normal", "Continuous", "Quick-Play", "Equip", "Field", "Ritual")
    val TRAP = listOf("Normal", "Continuous", "Counter")
    val ALL = (SPELL + TRAP).distinct()
}

/** Link arrows, as YGOPRODeck spells them, in reading order round the card. */
val LINK_ARROWS = listOf("Top-Left", "Top", "Top-Right", "Left", "Right", "Bottom-Left", "Bottom", "Bottom-Right")
