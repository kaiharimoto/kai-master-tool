package com.kaiharimoto.mastertool.core.search

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory

/**
 * What a card *does*, as the deck builders people already use file it: Neuron's
 * and Master Duel's effect categories — search, special summon, negate, and so on
 * (kai, Neue 1.0.19: "the card database filters need to be more advanced, refer to
 * public and commonly used deckbuilders like duelingbook, master duel, and neuron").
 *
 * Konami files these by hand; YGOPRODeck does not carry them. So they are read
 * off the printed text by phrasing — card text is a formal language, and the
 * phrases that mean "search" or "negate" are few and fixed. Each kind is one
 * pattern, and each pattern errs toward the reading a player would give: "cannot
 * be destroyed" is protection, not destruction; "must be Special Summoned" is a
 * summoning condition, not a Special Summon.
 */
enum class EffectKind(val label: String) {
    SEARCH("Search"),
    SPECIAL_SUMMON("Special Summon"),
    DRAW("Draw"),
    NEGATE("Negate"),
    DESTROY("Destroy"),
    BANISH("Banish"),
    SEND_TO_GY("Send to GY"),
    BOUNCE("Return to hand"),
    SET("Set"),
    RECOVER("Gain LP"),
    BURN("Burn"),
    TOKEN("Token"),
    PROTECTION("Protection"),
    HAND_TRAP("Hand trap"),
    FLOODGATE("Floodgate"),
}

object EffectKinds {

    private val search = Regex("""\badd\b[^.]{0,140}?\bfrom your (deck|deck or gy|deck or banishment|deck and/or gy)\b[^.]{0,40}?\bto your hand""")
    private val summonConditions = Regex("""(cannot|must (first )?be|can only be|must be|can be) special summoned""")
    private val draw = Regex("""\bdraws? (\d|a card|cards|one|two|three|1 card|2 cards)""")
    private val cannotBeDestroyed = Regex("""cannot be destroyed""")
    private val sendToGy = Regex("""\bsend[^.]{0,120}?\bto the gy\b""")
    private val bounce = Regex("""\breturn[^.]{0,100}?\bto the (hand|owner's hand|owners' hands|hands?)\b""")
    private val set = Regex("""\bset (\d |1 |one |a )?[^.]{0,60}?(spell|trap|card)[^.]{0,60}?\bfrom your (deck|gy|hand)""")
    private val recover = Regex("""\bgains? [^.]{0,30}?\blp\b""")
    private val burn = Regex("""\binflict[^.]{0,60}?\bdamage to (your opponent|each player|both players)""")
    private val protection = Regex("""cannot be (destroyed|targeted)|unaffected by|cannot be tributed|cannot be banished""")
    // A lock on what players may do — but not "neither player can target this card" (protection) or "your opponent cannot
    // activate cards or effects in response" (a chain lock on one activation): neither stops a deck from playing (2026-10,
    // the red team's finding 9).
    private val floodgate = Regex("""\b(neither player can|your opponent cannot|players cannot)\b(?!\s+(?:target\b|activate[^.]{0,80}?\bin response\b))""")
    private val cannotBeNegated = Regex("""(cannot|can't|can not) be negated""")
    private val handSummon = Regex("""special summon this card from your hand""")
    private val fromHand = Regex("""activate this card from your hand""")

    /** Every kind [card] does. */
    fun of(card: Card): Set<EffectKind> = EffectKind.entries.filterTo(LinkedHashSet()) { matches(card, it, card.description.lowercase()) }

    /** Whether [card] does every one of [kinds]. */
    fun hasAll(card: Card, kinds: Set<EffectKind>): Boolean {
        if (card.description.isEmpty()) return false
        val text = card.description.lowercase()
        return kinds.all { matches(card, it, text) }
    }

    private fun matches(card: Card, kind: EffectKind, text: String): Boolean = when (kind) {
        EffectKind.SEARCH -> search.containsMatchIn(text)
        EffectKind.SPECIAL_SUMMON -> text.replace(summonConditions, "").contains("special summon")
        EffectKind.DRAW -> draw.containsMatchIn(text)
        // "This card's Normal Summon cannot be negated" is not a negation (Obelisk).
        EffectKind.NEGATE -> text.replace(cannotBeNegated, "").contains("negate")
        EffectKind.DESTROY -> text.replace(cannotBeDestroyed, "").contains("destroy")
        EffectKind.BANISH -> text.replace(Regex("cannot be banished"), "").contains("banish")
        EffectKind.SEND_TO_GY -> sendToGy.containsMatchIn(text)
        EffectKind.BOUNCE -> bounce.containsMatchIn(text)
        EffectKind.SET -> set.containsMatchIn(text)
        EffectKind.RECOVER -> recover.containsMatchIn(text)
        EffectKind.BURN -> burn.containsMatchIn(text)
        EffectKind.TOKEN -> text.contains("token")
        EffectKind.PROTECTION -> protection.containsMatchIn(text)
        // A card that does its work from the hand on the opponent's turn: a monster's Quick Effect paid for by discarding,
        // sending, banishing or revealing itself from the hand, or summoning itself from it (Nibiru, PSY-Framegear Gamma);
        // or a Trap that may be activated from the hand (Infinite Impermanence).
        EffectKind.HAND_TRAP -> when (card.category) {
            CardCategory.MONSTER -> text.contains("quick effect") &&
                (text.contains("discard this card") || text.contains("send this card from your hand") || text.contains("banish this card from your hand") ||
                    text.contains("reveal this card in your hand") || handSummon.containsMatchIn(text))
            CardCategory.TRAP -> fromHand.containsMatchIn(text)
            else -> false
        }
        EffectKind.FLOODGATE -> floodgate.containsMatchIn(text)
    }
}
