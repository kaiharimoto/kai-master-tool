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
 * reveal or a search shows a card for that moment only, in the log. A card activated from a hand stays there shown to
 * both seats while the chain stands (1.0.87, kai: "it should just reveal itself until the chain resolves").
 */
object DuelSight {
    fun sees(s: DuelState, uid: Int, viewer: Int?): Boolean {
        // Before the opening roll is decided no hand is looked at, its owner's either (kai, 1.0.93: "have both players'
        // hands hidden until a player chooses first or second"): the roll comes first, and the choice is made blind.
        if (s.beforeTurnOne && s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.HAND }) return false
        if (viewer == null) return true
        val card = s.cards[uid] ?: return false
        val place = s.placeOf(uid) ?: return false
        if (place is Place.Pile && place.kind == PileKind.HAND) return place.seat == viewer || onChain(s, uid)
        if (viewer in (s.seen[uid] ?: emptySet())) return true
        return when (val p = place) {
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

    /** Whether [uid] is the card of a link of the chain standing (or one of its links already resolved). */
    fun onChain(s: DuelState, uid: Int): Boolean = s.chain.isNotEmpty() && (s.chain.any { it.uid == uid } || uid in s.resolved)

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
    val attacks: List<Attack> = emptyList(),
    /** The opening roll (1.0.87): every throw and its dice are both seats' to see. */
    val opening: Opening? = null,
    /** The die and the coin on the table (1.0.96): both seats see them land. */
    val chance: List<Chance> = emptyList(),
) {
    companion object {
        /** The table as [viewer] sees it; null sees everything. [secret] keys the veils (the duel's seed). */
        fun of(s: DuelState, viewer: Int?, secret: Long = 0L): DuelView {
            fun card(uid: Int): ViewCard {
                val c = s.cards.getValue(uid)
                return if (DuelSight.sees(s, uid, viewer)) {
                    ViewCard(uid, c.code, c.pos, c.owner, c.controller, c.counters, c.token, c.name, c.under.map(::card), c.atk, c.def, c.extraDeck)
                } else {
                    ViewCard(veil(secret, uid, s.epoch[uid] ?: 0), null, c.pos, c.owner, c.controller, c.counters, c.token, null, c.under.map(::card))
                }
            }
            fun hide(uid: Int): Int = if (DuelSight.sees(s, uid, viewer)) uid else veil(secret, uid, s.epoch[uid] ?: 0)
            return DuelView(
                viewer = viewer,
                seats = s.seats.mapIndexed { i, seat ->
                    SeatView(
                        name = seat.name,
                        lp = seat.lp,
                        // Another seat's hand in no order of its own: which card came in last is not theirs to see (1.0.85).
                        hand = seat.hand.map(::card).let { h -> if (viewer != null && viewer != i) h.sortedBy { c: ViewCard -> c.ref } else h },
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
                attacks = s.attacks.map { it.copy(attacker = hide(it.attacker), target = it.target?.let(::hide)) },
                opening = s.opening,
                chance = s.chance,
            )
        }

        private const val VEIL_FLOOR = 2_200_001

        /** A hidden card's handle for one epoch: negative, so it never collides with a uid. */
        fun veil(secret: Long, uid: Int, epoch: Int): Int {
            var x = secret xor (uid.toLong() * -0x61c8864680b583ebL) xor (epoch.toLong() * 0x2545F4914F6CDD1DL)
            x = (x xor (x ushr 33)) * -0xae502812aa7333L
            x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
            x = x xor (x ushr 33)
            // Below the guest's deck references (DuelMirror's -2,000,000…-2,199,999), never among them (1.0.85).
            return -((x and 0x3fffffffL).toInt() + VEIL_FLOOR)
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
    /** [CardInst.extraDeck], sent only with a card the viewer sees (1.0.93): where a hidden card was dealt is not said. */
    val extraDeck: Boolean = false,
) {
    val hidden: Boolean get() = code == null
}
