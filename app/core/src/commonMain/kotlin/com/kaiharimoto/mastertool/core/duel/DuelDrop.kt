package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition

/** Where a carried card is over, as the table's geometry found it. */
sealed interface DropSpot {
    data class Zone(val zone: Place.Zone) : DropSpot
    /** A pile; on a Deck, [part] is the third of it the card is over: the top, the middle (shuffled in) or the bottom (1.0.87). */
    data class Pile(val seat: Int, val kind: PileKind, val part: DeckPart? = null) : DropSpot
    /** A hand, before the card at [index] (its length: the right end). */
    data class Hand(val seat: Int, val index: Int) : DropSpot
    data object Chain : DropSpot
    /** A player's name and life points in the score column: in the Battle Phase, a direct attack (1.0.86). */
    data class Score(val seat: Int) : DropSpot
}

/**
 * Where on a Deck a card is put (1.0.87, kai: "some place to the top of the deck, some to the bottom, and some shuffle
 * to deck"): the pile's upper third is the top, its middle shuffles the card in, its lower third is the bottom.
 */
enum class DeckPart(val label: String) {
    TOP("Top of the Deck"), SHUFFLE("Shuffle into the Deck"), BOTTOM("Bottom of the Deck");

    companion object {
        /** The part of a Deck at [y] of its height [h] (from its top edge). */
        fun at(y: Float, h: Float): DeckPart = when {
            h <= 0f || y < h / 3f -> TOP
            y < h * 2f / 3f -> SHUFFLE
            else -> BOTTOM
        }
    }
}

/**
 * What letting go of a carried card will do — the same answer the highlight under it shows, because
 * the indicator is the intent: what is drawn while carrying is exactly what the release commits.
 *
 * Over an empty zone the card goes there (Alt sets it); a monster over a monster goes on top of it, the one
 * there becoming its material (1.0.87), and any other card under it as a material; over a pile it goes onto the
 * pile (a Deck by the third it is over, else Shift the bottom and Alt shuffled in; Alt: banished face-down);
 * over a hand it goes into it at that place; over the chain it is activated where it is.
 */
object DuelDrop {
    data class Intent(val actions: List<DuelAction>, val label: String) {
        val none: Boolean get() = actions.isEmpty()
    }

    val NONE = Intent(emptyList(), "")

    /** [actor]: the seat dragging, when it is known to be another than the card's (a guest); null for the card's own. */
    fun intent(s: DuelState, uid: Int, spot: DropSpot?, catalog: DuelCatalog, alt: Boolean = false, shift: Boolean = false, actor: Int? = null): Intent {
        val card = s.cards[uid] ?: return NONE
        val from = s.placeOf(uid) ?: return NONE
        val kind = DuelVerbs.kindOf(card, catalog)
        val seat = if (from is Place.Zone) card.controller else card.owner
        return when (spot) {
            null -> NONE
            // A card from the hand dropped on the chain is activated as the verb would: a hand trap goes to
            // the GY and onto the chain (1.0.79, Ai: "your two Fuwalos went straight to the GY").
            DropSpot.Chain -> if (from is Place.Pile && from.kind == PileKind.HAND) {
                val r = DuelVerbs.actions(s, seat, uid, DuelVerb.ACTIVATE, catalog)
                if (r.problem != null || r.actions.isEmpty()) NONE else Intent(r.actions, "Activate")
            } else if (from is Place.Zone && !card.faceUp && from.kind != ZoneKind.MONSTER && from.kind != ZoneKind.EMZ && (actor == null || actor == card.controller)) {
                // A Set card is activated face-up, as the verb does: never a chain link with a hidden name (1.0.85).
                Intent(listOf(DuelAction.Position(uid, CardPosition.FACE_UP_ATK), DuelAction.ChainAdd(seat, uid)), "Activate")
            } else Intent(listOf(DuelAction.ChainAdd(seat, uid)), "Activate")
            is DropSpot.Zone -> {
                val z = spot.zone
                val there = s.at(z)
                when {
                    there == uid -> NONE
                    // In the Battle Phase, a monster dropped on one the other player controls attacks it (1.0.83).
                    there != null && attacks(s, uid, seat) && s.cards[there]?.controller != seat &&
                        (z.kind == ZoneKind.MONSTER || z.kind == ZoneKind.EMZ) -> {
                        val target = s.cards.getValue(there)
                        Intent(listOf(DuelAction.Attack(seat, uid, there)), "Attack ${if (target.faceUp) catalog.nameOf(target) else "the set monster"}")
                    }
                    there != null -> {
                        val host = s.cards.getValue(there)
                        val monsterZone = z.kind == ZoneKind.MONSTER || z.kind == ZoneKind.EMZ
                        val monster = kind == CardKind.MONSTER || kind == CardKind.EXTRA_MONSTER
                        val hostName = if (host.faceUp) catalog.nameOf(host) else "the set monster"
                        when {
                            !monsterZone || uid in host.under -> NONE
                            // A monster put on a monster goes on top: the one there, and its materials, beneath it (kai, 1.0.87).
                            monster && from !is Place.Under -> Intent(
                                listOf(DuelAction.Move(uid, z, if (from is Place.Zone) null else CardPosition.FACE_UP_ATK, if (from is Place.Zone) null else "special", over = true)),
                                "On top of $hostName",
                            )
                            else -> Intent(listOf(DuelAction.Move(uid, Place.Under(there), how = "attach")), "Attach to $hostName")
                        }
                    }
                    else -> {
                        val monsterZone = z.kind == ZoneKind.MONSTER || z.kind == ZoneKind.EMZ
                        val pos = when {
                            from is Place.Zone && !alt -> null
                            monsterZone -> if (alt) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_ATK
                            else -> if (alt) CardPosition.FACE_DOWN_ATK else CardPosition.FACE_UP_ATK
                        }
                        val how = when {
                            from is Place.Zone -> null
                            alt -> "set"
                            monsterZone && (kind == CardKind.SPELL || kind == CardKind.TRAP) -> null
                            monsterZone && from is Place.Pile && from.kind == PileKind.HAND -> null
                            monsterZone -> "special"
                            z.kind == ZoneKind.SPELL && card.pendulumIn(catalog) && (z.index == 0 || z.index == 4) -> "pendulum"
                            else -> "activate"
                        }
                        val name = zoneWords(z)
                        val label = when {
                            from is Place.Zone -> "Move to $name"
                            alt -> "Set in $name"
                            monsterZone && how == "special" -> "Special Summon to $name"
                            monsterZone -> "Summon to $name"
                            how == "pendulum" -> "Place in $name"
                            else -> "Activate in $name"
                        }
                        // A spell or trap played face-up from the hand onto its zone is activated: a chain link too.
                        val chain = !monsterZone && !alt && from !is Place.Zone && how == "activate"
                        Intent(listOfNotNull(DuelAction.Move(uid, z, pos, how), if (chain) DuelAction.ChainAdd(seat, uid) else null), label)
                    }
                }
            }
            is DropSpot.Pile -> {
                val target = spot.kind
                if (from is Place.Pile && from.kind == target && target != PileKind.DECK) return NONE
                val part = spot.part ?: when {
                    shift -> DeckPart.BOTTOM
                    alt -> DeckPart.SHUFFLE
                    else -> DeckPart.TOP
                }
                val (pos, at, label) = when (target) {
                    PileKind.DECK -> Triple(null, if (part == DeckPart.BOTTOM) Place.BOTTOM else Place.TOP, part.label)
                    PileKind.GY -> Triple(null, null, "To the GY")
                    PileKind.BANISHED -> if (alt) Triple(CardPosition.FACE_DOWN_DEF, null, "Banish face-down") else Triple(CardPosition.FACE_UP_ATK, null, "Banish")
                    PileKind.EXTRA -> Triple(if (card.pendulumIn(catalog)) CardPosition.FACE_UP_ATK else null, null, "To the Extra Deck")
                    PileKind.HAND -> Triple(null, null, "To the hand")
                }
                if (target == PileKind.DECK && part == DeckPart.SHUFFLE) {
                    // Shuffled in: onto the Deck and the Deck shuffled, one gesture — its place is never seen.
                    if (from is Place.Pile && from.kind == PileKind.DECK) return Intent(listOf(DuelAction.Shuffle(card.owner, PileKind.DECK)), "Shuffle the Deck")
                    return Intent(listOf(DuelAction.Move(uid, Place.Pile(card.owner, PileKind.DECK, Place.TOP), null, "shuffle"), DuelAction.Shuffle(card.owner, PileKind.DECK)), label)
                }
                val how = when (target) {
                    PileKind.GY -> if (from is Place.Under) "detach" else "send"
                    PileKind.BANISHED -> "banish"
                    PileKind.HAND -> if (from is Place.Pile && from.kind == PileKind.DECK) "search" else "return"
                    else -> "return"
                }
                Intent(listOfNotNull(DuelAction.Move(uid, Place.Pile(card.owner, target, at), pos, how), searched(how, uid, card.owner)), label)
            }
            // Dropped on the other player's life points in the Battle Phase: a direct attack — the far hand folds
            // away on a short window, so the score column is always there to aim at (1.0.86).
            is DropSpot.Score -> if (spot.seat != seat && attacks(s, uid, seat)) Intent(listOf(DuelAction.Attack(seat, uid, null)), "Attack directly") else NONE
            // Dropped on the other player's hand in the Battle Phase: a direct attack (1.0.83).
            is DropSpot.Hand -> if (spot.seat != seat && attacks(s, uid, seat)) {
                Intent(listOf(DuelAction.Attack(seat, uid, null)), "Attack directly")
            } else {
                // The deck's top card carried to its owner's hand is a draw.
                if (from is Place.Pile && from.kind == PileKind.DECK && from.at == 0 && spot.seat == card.owner) {
                    return Intent(listOf(DuelAction.Draw(card.owner)), "Draw")
                }
                if (spot.seat != card.owner && from !is Place.Pile) return Intent(listOf(DuelAction.Move(uid, Place.Pile(card.owner, PileKind.HAND))), "To the hand")
                if (from is Place.Pile && from.kind == PileKind.HAND) {
                    val now = from.at ?: return NONE
                    // Taking it out first shifts everything after it one along.
                    val index = if (spot.index > now) spot.index - 1 else spot.index
                    if (index == now) return NONE
                    return Intent(listOf(DuelAction.Move(uid, Place.Pile(card.owner, PileKind.HAND, index))), "Move in the hand")
                }
                val how = if (from is Place.Pile && from.kind == PileKind.DECK) "search" else "return"
                Intent(listOfNotNull(DuelAction.Move(uid, Place.Pile(card.owner, PileKind.HAND, if (spot.seat == card.owner) spot.index else null), how = how), searched(how, uid, card.owner)), "To the hand")
            }
        }
    }

    /** Whether [uid] could attack now: the Battle Phase, a face-up Attack Position monster [seat] controls. */
    private fun attacks(s: DuelState, uid: Int, seat: Int): Boolean = DuelVerbs.canAttack(s, seat, uid)

    /** A card searched from the Deck is shown to the other player (1.0.79): it is known from then on. */
    private fun searched(how: String, uid: Int, owner: Int): DuelAction? = if (how == "search") DuelAction.Reveal(owner, listOf(uid)) else null

    fun zoneWords(z: Place.Zone): String = when (z.kind) {
        ZoneKind.MONSTER -> "M${z.index + 1}"
        ZoneKind.SPELL -> "S/T ${z.index + 1}"
        ZoneKind.FIELD -> "the Field Zone"
        ZoneKind.EMZ -> "the Extra Monster Zone"
    }

    private fun CardInst.pendulumIn(catalog: DuelCatalog) = catalog.info(code)?.pendulum == true
}
