package com.kaiharimoto.mastertool.core.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * A Konami passcode. This is the identifier that appears in `.ydk` files.
 *
 * Alternate artworks carry their own passcodes, so a passcode found in a deck
 * file does not always equal the printing that a card database calls canonical.
 * Resolution of alternate passcodes is the card index's job, not this type's.
 */
@Serializable
@JvmInline
value class CardId(val value: Int) {
    override fun toString(): String = value.toString()

    companion object {
        /** Parses a `.ydk` line into a passcode, or returns null if it is not one. */
        fun parseOrNull(raw: String): CardId? =
            raw.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toIntOrNull()
                ?.let(::CardId)
    }
}

@Serializable
enum class Attribute {
    DARK, LIGHT, EARTH, WATER, FIRE, WIND, DIVINE, UNKNOWN;

    companion object {
        fun fromApi(raw: String?): Attribute =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Broad card category, independent of which deck the card is legal in. */
@Serializable
enum class CardCategory { MONSTER, SPELL, TRAP, OTHER }

/**
 * How many copies of a card a deck may contain, across main + side + extra.
 */
@Serializable
enum class BanStatus(val maxCopies: Int) {
    FORBIDDEN(0),
    LIMITED(1),
    SEMI_LIMITED(2),
    UNLIMITED(3);

    companion object {
        /** Maps ygoprodeck's `banlist_info` strings. A missing entry means unlimited. */
        fun fromApi(raw: String?): BanStatus = when (raw?.trim()?.lowercase()) {
            "banned", "forbidden" -> FORBIDDEN
            "limited" -> LIMITED
            "semi-limited", "semi limited" -> SEMI_LIMITED
            else -> UNLIMITED
        }
    }
}

/** Which regional banlist to validate a deck against. */
@Serializable
enum class Format { TCG, OCG }

@Serializable
data class Card(
    val id: CardId,
    val name: String,
    /** Human-readable type from the API, e.g. "Pendulum Effect Fusion Monster". */
    val type: String,
    /** Machine-readable frame, e.g. "fusion_pendulum". More reliable than [type]. */
    val frameType: String,
    val description: String = "",
    val race: String? = null,
    val attribute: Attribute = Attribute.UNKNOWN,
    val atk: Int? = null,
    val def: Int? = null,
    /** Level for main-deck monsters, Rank for Xyz. Null for Link monsters. */
    val level: Int? = null,
    val linkValue: Int? = null,
    val linkMarkers: List<String> = emptyList(),
    val pendulumScale: Int? = null,
    val archetype: String? = null,
    val imageUrl: String? = null,
    val imageUrlSmall: String? = null,
    val tcgBanStatus: BanStatus = BanStatus.UNLIMITED,
    val ocgBanStatus: BanStatus = BanStatus.UNLIMITED,
    /**
     * Every passcode that can refer to this card, including alternate artworks.
     * Always contains [id].
     */
    val alternateIds: List<CardId> = emptyList(),
    /** Konami's own database id for the card (YGOPRODeck's `misc_info.konami_id`), or null when the pool has none. */
    val konamiId: Int? = null,
    /** First release in the TCG, `yyyy-MM-dd`; null when it has none (OCG-only) or the pool does not say. */
    val tcgDate: String? = null,
    /** First release in the OCG, `yyyy-MM-dd`; null when it has none or the pool does not say. */
    val ocgDate: String? = null,
    /** Where it can be played, in YGOPRODeck's words ("TCG", "OCG", "Master Duel", …); empty when the pool does not say. */
    val formats: List<String> = emptyList(),
    /** Its cost in Konami's Genesys format (most cards 0); null when the pool does not say. */
    val genesysPoints: Int? = null,
) {
    /** Every passcode that names this card: its own and each alternate artwork's. */
    val passcodes: Set<CardId>
        get() = alternateIds.toSet() + id

    val category: CardCategory
        get() = when {
            type.contains("Spell", ignoreCase = true) -> CardCategory.SPELL
            type.contains("Trap", ignoreCase = true) -> CardCategory.TRAP
            type.contains("Monster", ignoreCase = true) -> CardCategory.MONSTER
            type.contains("Token", ignoreCase = true) -> CardCategory.OTHER
            else -> CardCategory.OTHER
        }

    /**
     * True when this card belongs in the Extra Deck.
     *
     * Keyed off [frameType] rather than [type]: the frame carries the summoning
     * mechanic even for hybrids like `fusion_pendulum` or `xyz_pendulum`, which
     * are Extra Deck cards despite reading as Pendulum monsters.
     */
    val isExtraDeck: Boolean
        get() = EXTRA_DECK_FRAMES.any { frameType.contains(it, ignoreCase = true) }

    /** Cards that may never appear in any deck section. */
    val isPlayable: Boolean
        get() = !frameType.equals("token", ignoreCase = true) &&
            !frameType.equals("skill", ignoreCase = true) &&
            !type.contains("Token", ignoreCase = true) &&
            !type.contains("Skill Card", ignoreCase = true)

    fun banStatus(format: Format): BanStatus =
        if (format == Format.TCG) tcgBanStatus else ocgBanStatus

    /** The deck section this card is required to occupy. */
    fun requiredSection(): DeckSection =
        if (isExtraDeck) DeckSection.EXTRA else DeckSection.MAIN

    companion object {
        private val EXTRA_DECK_FRAMES = listOf("fusion", "synchro", "xyz", "link")
    }
}
