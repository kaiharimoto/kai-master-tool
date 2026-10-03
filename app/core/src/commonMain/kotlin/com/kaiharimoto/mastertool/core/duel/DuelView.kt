package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import kotlinx.serialization.Serializable

/**
 * Who can see a card's face. A card face-up on the field, in a graveyard, banished face-up, a
 * face-up Pendulum in the Extra Deck or a material is seen by both seats; a hand by its owner; a
 * face-down card on the field by its controller; a face-down Extra Deck or banished card by its owner;
 * a deck by no one. On top of that, a seat that saw a card — revealed, or before it went out of sight —
 * keeps knowing it until it is shuffled away — except in a hand (1.0.82, kai: "once it has gone into the
 * hand it is no longer revealed or treated as public knowledge"): a hand is its owner's alone, and a
 * reveal or a search shows a card for that moment only, in the log.
 */
object DuelSight {
    fun sees(s: DuelState, uid: Int, viewer: Int?): Boolean {
        if (viewer == null) return true
        val card = s.cards[uid] ?: return false
        val place = s.placeOf(uid)
        if (place is Place.Pile && place.kind == PileKind.HAND) return place.seat == viewer
        if (viewer in (s.seen[uid] ?: emptySet())) return true
        return when (val p = s.placeOf(uid) ?: return false) {
            is Place.Zone -> card.faceUp || card.controller == viewer
            is Place.Pile -> when (p.kind) {
                PileKind.HAND -> p.seat == viewer
                PileKind.DECK -> false
                PileKind.GY -> true
                PileKind.EXTRA, PileKind.BANISHED -> card.faceUp || p.seat == viewer
            }
            is Place.Under -> true
            Place.Void -> false
        }
    }

    /** The seats that can see [uid] now. */
    fun knowers(s: DuelState, uid: Int): Set<Int> = s.seats.indices.filter { sees(s, uid, it) }.toSet()
}

/**
 * The table as one seat sees it — what the network sends that seat, what Ai is given when it plays
 * with a player's knowledge, what the hot-seat's "view as" draws. A card the viewer cannot see is a
 * [ViewCard] with no [ViewCard.code] and a negative [ViewCard.ref], a *veil*: stable while the card sits
 * in a hand or on the field, so "that set card" can be followed, and new after every shuffle, so a
 * card cannot be followed through one. A card the viewer can see carries its uid as its ref.
 */
@Serializable
data class DuelView(
    val viewer: Int? = null,
    val seats: List<SeatView>,
    val emz: List<ViewCard?>,
    val turn: Int,
    val active: Int,
    val phase: DuelPhase,
    val chain: List<ChainLink>,
    val arrows: List<Arrow>,
    val thinking: Set<Int>,
    val solo: Boolean,
    val proposal: Proposal? = null,
    val locks: List<Lock> = emptyList(),
    val resolved: List<Int> = emptyList(),
) {
    companion object {
        /** The table as [viewer] sees it; null sees everything. [secret] keys the veils (the duel's seed). */
        fun of(s: DuelState, viewer: Int?, secret: Long = 0L): DuelView {
            fun card(uid: Int): ViewCard {
                val c = s.cards.getValue(uid)
                return if (DuelSight.sees(s, uid, viewer)) {
                    ViewCard(uid, c.code, c.pos, c.owner, c.controller, c.counters, c.token, c.name, c.under.map(::card), c.atk, c.def)
                } else {
                    ViewCard(veil(secret, uid, s.epoch[uid] ?: 0), null, c.pos, c.owner, c.controller, c.counters, c.token, null, c.under.map(::card))
                }
            }
            fun hide(uid: Int): Int = if (DuelSight.sees(s, uid, viewer)) uid else veil(secret, uid, s.epoch[uid] ?: 0)
            return DuelView(
                viewer = viewer,
                seats = s.seats.map { seat ->
                    SeatView(
                        name = seat.name,
                        lp = seat.lp,
                        hand = seat.hand.map(::card),
                        deck = seat.deck.size,
                        deckKnown = seat.deck.mapIndexedNotNull { i, u -> if (DuelSight.sees(s, u, viewer)) i to card(u) else null }.toMap(),
                        extra = seat.extra.map(::card),
                        gy = seat.gy.map(::card),
                        banished = seat.banished.map(::card),
                        monsters = seat.monsters.map { it?.let(::card) },
                        spells = seat.spells.map { it?.let(::card) },
                        field = seat.field?.let(::card),
                    )
                },
                emz = s.emz.map { it?.let(::card) },
                turn = s.turn,
                active = s.active,
                phase = s.phase,
                chain = s.chain.map { l -> l.copy(uid = l.uid?.let(::hide), targets = l.targets.map(::hide)) },
                arrows = s.arrows.map { a -> a.copy(from = a.from?.let(::hide), to = a.to.map(::hide)) },
                thinking = s.thinking,
                solo = s.solo,
                proposal = s.proposal,
                locks = s.locks,
                resolved = s.resolved.map(::hide),
            )
        }

        /** A hidden card's handle for one epoch: negative, so it never collides with a uid. */
        fun veil(secret: Long, uid: Int, epoch: Int): Int {
            var x = secret xor (uid.toLong() * -0x61c8864680b583ebL) xor (epoch.toLong() * 0x2545F4914F6CDD1DL)
            x = (x xor (x ushr 33)) * -0xae502812aa7333L
            x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
            x = x xor (x ushr 33)
            return -((x and 0x3fffffffL).toInt() + 1)
        }
    }
}

@Serializable
data class SeatView(
    val name: String,
    val lp: Int,
    val hand: List<ViewCard>,
    /** How many cards are in the deck; its order is no one's to see. */
    val deck: Int,
    /** Deck positions whose card the viewer knows (a revealed top card put back). */
    val deckKnown: Map<Int, ViewCard> = emptyMap(),
    val extra: List<ViewCard>,
    val gy: List<ViewCard>,
    val banished: List<ViewCard>,
    val monsters: List<ViewCard?>,
    val spells: List<ViewCard?>,
    val field: ViewCard?,
)

@Serializable
data class ViewCard(
    /** The uid when the viewer can see the card, else its veil (negative). */
    val ref: Int,
    /** The passcode, or null when hidden. */
    val code: Int?,
    val pos: CardPosition,
    val owner: Int,
    val controller: Int,
    val counters: Map<String, Int> = emptyMap(),
    val token: Boolean = false,
    val name: String? = null,
    val under: List<ViewCard> = emptyList(),
    val atk: Int? = null,
    val def: Int? = null,
) {
    val hidden: Boolean get() = code == null
}
