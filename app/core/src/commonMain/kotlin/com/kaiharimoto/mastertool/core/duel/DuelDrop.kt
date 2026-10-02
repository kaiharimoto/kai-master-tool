package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition

/** Where a carried card is over, as the table's geometry found it. */
sealed interface DropSpot {
    data class Zone(val zone: Place.Zone) : DropSpot
    data class Pile(val seat: Int, val kind: PileKind) : DropSpot
    /** A hand, before the card at [index] (its length: the right end). */
    data class Hand(val seat: Int, val index: Int) : DropSpot
    data object Chain : DropSpot
}

/**
 * What letting go of a carried card will do — the same answer the highlight under it shows, because
 * the indicator is the intent: what is drawn while carrying is exactly what the release commits.
 *
 * Over an empty zone the card goes there (Alt sets it); over a monster it becomes that monster's
 * material; over a pile it goes onto the pile (Shift: the bottom of a deck; Alt: banished face-down);
 * over a hand it goes into it at that place; over the chain it is activated where it is.
 */
object DuelDrop {
    data class Intent(val actions: List<DuelAction>, val label: String) {
        val none: Boolean get() = actions.isEmpty()
    }

    val NONE = Intent(emptyList(), "")

    fun intent(s: DuelState, uid: Int, spot: DropSpot?, catalog: DuelCatalog, alt: Boolean = false, shift: Boolean = false): Intent {
        val card = s.cards[uid] ?: return NONE
        val from = s.placeOf(uid) ?: return NONE
        val kind = DuelVerbs.kindOf(card, catalog)
        val seat = if (from is Place.Zone) card.controller else card.owner
        return when (spot) {
            null -> NONE
            DropSpot.Chain -> Intent(listOf(DuelAction.ChainAdd(seat, uid)), "Activate")
            is DropSpot.Zone -> {
                val z = spot.zone
                val there = s.at(z)
                when {
                    there == uid -> NONE
                    there != null -> {
                        val host = s.cards.getValue(there)
                        val monsterZone = z.kind == ZoneKind.MONSTER || z.kind == ZoneKind.EMZ
                        if (!monsterZone || uid in host.under) NONE
                        else Intent(listOf(DuelAction.Move(uid, Place.Under(there), how = "attach")), "Attach to ${catalog.nameOf(host)}")
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
                val (pos, at, label) = when (target) {
                    PileKind.DECK -> Triple(null, if (shift) Place.BOTTOM else Place.TOP, if (shift) "Bottom of the Deck" else "Top of the Deck")
                    PileKind.GY -> Triple(null, null, "To the GY")
                    PileKind.BANISHED -> if (alt) Triple(CardPosition.FACE_DOWN_DEF, null, "Banish face-down") else Triple(CardPosition.FACE_UP_ATK, null, "Banish")
                    PileKind.EXTRA -> Triple(if (card.pendulumIn(catalog)) CardPosition.FACE_UP_ATK else null, null, "To the Extra Deck")
                    PileKind.HAND -> Triple(null, null, "To the hand")
                }
                val how = when (target) {
                    PileKind.GY -> if (from is Place.Under) "detach" else "send"
                    PileKind.BANISHED -> "banish"
                    PileKind.HAND -> if (from is Place.Pile && from.kind == PileKind.DECK) "search" else "return"
                    else -> "return"
                }
                Intent(listOf(DuelAction.Move(uid, Place.Pile(card.owner, target, at), pos, how)), label)
            }
            is DropSpot.Hand -> {
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
                Intent(listOf(DuelAction.Move(uid, Place.Pile(card.owner, PileKind.HAND, if (spot.seat == card.owner) spot.index else null), how = how)), "To the hand")
            }
        }
    }

    fun zoneWords(z: Place.Zone): String = when (z.kind) {
        ZoneKind.MONSTER -> "M${z.index + 1}"
        ZoneKind.SPELL -> "S/T ${z.index + 1}"
        ZoneKind.FIELD -> "the Field Zone"
        ZoneKind.EMZ -> "the Extra Monster Zone"
    }

    private fun CardInst.pendulumIn(catalog: DuelCatalog) = catalog.info(code)?.pendulum == true
}
