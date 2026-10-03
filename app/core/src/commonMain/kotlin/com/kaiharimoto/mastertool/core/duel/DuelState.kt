package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import kotlinx.serialization.Serializable

/**
 * The duel simulator's table: two seats, each with its zones and piles, the Extra Monster Zones they
 * share, and what is going on between them — whose turn, the phase, a chain written down by hand,
 * the arrows players draw at each other's cards, who is thinking.
 *
 * Manual, as DuelingBook is: nothing here knows what a card's text allows. [DuelRules] refuses only
 * what the table itself cannot hold (two cards in one zone, a draw from an empty deck), never a play.
 *
 * Not `core/board`'s `PlayField`, which is one player's freeform mat, ported to the 3DS and frozen by
 * golden vectors. This is a second model beside it, with its own words for the same table.
 *
 * Every card is a [CardInst] keyed by a uid it keeps for the whole duel, so a gesture, a log entry, a
 * combo step and a network message all name a card the same way however often it moves. The lists
 * hold uids: a pile's index 0 is its top (the deck's next draw, the graveyard's newest), and a hand's
 * index 0 is its leftmost card.
 */
@Serializable
data class DuelState(
    val cards: Map<Int, CardInst> = emptyMap(),
    val seats: List<SeatState> = listOf(SeatState(), SeatState()),
    /** The two Extra Monster Zones, left then right as seat 0 sees them; either seat's monster may sit in one. */
    val emz: List<Int?> = listOf(null, null),
    val turn: Int = 1,
    /** The seat whose turn it is. */
    val active: Int = 0,
    val phase: DuelPhase = DuelPhase.DRAW,
    /** Chain Link 1 first. */
    val chain: List<ChainLink> = emptyList(),
    val arrows: List<Arrow> = emptyList(),
    /** uid -> the seats that know its face although it is hidden from them now (a reveal, a card seen go back). */
    val seen: Map<Int, Set<Int>> = emptyMap(),
    /** uid -> how many times it has been shuffled out of sight: a hidden card's veil changes with it. */
    val epoch: Map<Int, Int> = emptyMap(),
    /** A response window held open for the other seat, when the duel asks for them. */
    val window: ResponseWindow? = null,
    val thinking: Set<Int> = emptySet(),
    /** Who has conceded. */
    val conceded: Int? = null,
    /** One player alone at the table: the turn never passes, the second seat stays empty. */
    val solo: Boolean = false,
    val nextUid: Int = TOKEN_UIDS,
    /** The highest lock id written in this duel, lifted or not (1.0.86): a stamped id is never reused. */
    val lastLock: Int = 0,
    /** The non-turn seat's ask to move the phase on, waiting for the turn player's answer (1.0.79). */
    val proposal: Proposal? = null,
    /** What players have said is locked for now — "Synchro Monsters only from the Extra Deck" (1.0.79). */
    val locks: List<Lock> = emptyList(),
    /**
     * The cards of this chain's links that have resolved, waiting on the field for the whole chain
     * (1.0.83, kai: "spells and traps should stay on field until the whole chain has resolved").
     */
    val resolved: List<Int> = emptyList(),
    /** The attacks declared this turn, the newest last (1.0.83). */
    val attacks: List<Attack> = emptyList(),
) {
    fun seat(i: Int): SeatState = seats[i]

    fun card(uid: Int): CardInst? = cards[uid]

    /** Where the card is now, or null if it has left the duel (a token gone). */
    fun placeOf(uid: Int): Place? {
        val card = cards[uid] ?: return null
        emz.forEachIndexed { i, u -> if (u == uid) return Place.Zone(card.controller, ZoneKind.EMZ, i) }
        seats.forEachIndexed { s, seat ->
            seat.monsters.forEachIndexed { i, u -> if (u == uid) return Place.Zone(s, ZoneKind.MONSTER, i) }
            seat.spells.forEachIndexed { i, u -> if (u == uid) return Place.Zone(s, ZoneKind.SPELL, i) }
            if (seat.field == uid) return Place.Zone(s, ZoneKind.FIELD, 0)
            PileKind.entries.forEach { k ->
                val i = seat.pile(k).indexOf(uid)
                if (i >= 0) return Place.Pile(s, k, i)
            }
        }
        cards.values.forEach { host -> val i = host.under.indexOf(uid); if (i >= 0) return Place.Under(host.uid, i) }
        return null
    }

    /** The uid in a single zone, or null when it is empty. EMZ ignores the seat: the zones are shared. */
    fun at(zone: Place.Zone): Int? = when (zone.kind) {
        ZoneKind.EMZ -> emz.getOrNull(zone.index)
        ZoneKind.MONSTER -> seats[zone.seat].monsters.getOrNull(zone.index)
        ZoneKind.SPELL -> seats[zone.seat].spells.getOrNull(zone.index)
        ZoneKind.FIELD -> seats[zone.seat].field
    }

    /** Every uid on the field, both seats, EMZ included. */
    fun onField(): List<Int> =
        emz.filterNotNull() + seats.flatMap { it.monsters.filterNotNull() + it.spells.filterNotNull() + listOfNotNull(it.field) }

    /** The free zones of [kind] on [seat]'s side, in index order. */
    fun freeZones(seat: Int, kind: ZoneKind): List<Place.Zone> = when (kind) {
        ZoneKind.EMZ -> emz.indices.filter { emz[it] == null }.map { Place.Zone(seat, ZoneKind.EMZ, it) }
        ZoneKind.FIELD -> if (seats[seat].field == null) listOf(Place.Zone(seat, ZoneKind.FIELD, 0)) else emptyList()
        else -> (0 until ZONES).filter { at(Place.Zone(seat, kind, it)) == null }.map { Place.Zone(seat, kind, it) }
    }

    companion object {
        const val ZONES = 5
        const val START_LP = 8000
        /** Seat 0's cards are 1.., seat 1's are 1001.., tokens from here on. */
        const val SEAT_UIDS = 1000
        const val TOKEN_UIDS = 100_000
    }
}

@Serializable
data class SeatState(
    val name: String = "",
    val lp: Int = DuelState.START_LP,
    val hand: List<Int> = emptyList(),
    val deck: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val gy: List<Int> = emptyList(),
    val banished: List<Int> = emptyList(),
    val monsters: List<Int?> = List(DuelState.ZONES) { null },
    /** Spell & Trap Zones; 0 and 4 are the Pendulum Zones. */
    val spells: List<Int?> = List(DuelState.ZONES) { null },
    val field: Int? = null,
) {
    fun pile(kind: PileKind): List<Int> = when (kind) {
        PileKind.HAND -> hand
        PileKind.DECK -> deck
        PileKind.EXTRA -> extra
        PileKind.GY -> gy
        PileKind.BANISHED -> banished
    }

    fun withPile(kind: PileKind, list: List<Int>): SeatState = when (kind) {
        PileKind.HAND -> copy(hand = list)
        PileKind.DECK -> copy(deck = list)
        PileKind.EXTRA -> copy(extra = list)
        PileKind.GY -> copy(gy = list)
        PileKind.BANISHED -> copy(banished = list)
    }
}

/**
 * One physical card (or token) in the duel. [code] is its passcode, 0 for a token without one.
 * [pos] says face and battle position; in a pile only its face counts (a face-down banish, a face-up
 * Pendulum in the Extra Deck). [under] are its materials, top first, each still a card of its own.
 */
@Serializable
data class CardInst(
    val uid: Int,
    val code: Int,
    val owner: Int,
    val controller: Int = owner,
    val pos: CardPosition = CardPosition.FACE_DOWN_DEF,
    val counters: Map<String, Int> = emptyMap(),
    val token: Boolean = false,
    /** A token's name, when it has no passcode to look one up by. */
    val name: String? = null,
    val under: List<Int> = emptyList(),
    /** A token's ATK and DEF, when its maker gave them (1.0.79). */
    val atk: Int? = null,
    val def: Int? = null,
) {
    val faceUp: Boolean get() = pos.faceUp
    val defense: Boolean get() = pos == CardPosition.FACE_UP_DEF || pos == CardPosition.FACE_DOWN_DEF
}

@Serializable
enum class ZoneKind { MONSTER, SPELL, FIELD, EMZ }

@Serializable
enum class PileKind(val label: String) {
    HAND("Hand"), DECK("Deck"), EXTRA("Extra Deck"), GY("GY"), BANISHED("Banished");
}

@Serializable
data class ChainLink(
    val seat: Int,
    val uid: Int? = null,
    val note: String = "",
    val targets: List<Int> = emptyList(),
)

/** [attacker] attacks [target], or directly when there is none (1.0.83). */
@Serializable
data class Attack(val seat: Int, val attacker: Int, val target: Int? = null)

/** [seat] asks to go to [phase], or to end the turn when [end] (1.0.79). */
@Serializable
data class Proposal(val seat: Int, val phase: com.kaiharimoto.mastertool.core.board.DuelPhase? = null, val end: Boolean = false)

/**
 * A lock written down: [text] in the player's words, lasting until the end of the turn ([UNTIL_TURN]),
 * the chain ([UNTIL_CHAIN]) or the duel ([UNTIL_DUEL]). The table never enforces it; it reminds.
 */
@Serializable
data class Lock(val id: Int, val seat: Int, val text: String, val until: String = UNTIL_TURN) {
    companion object {
        const val UNTIL_TURN = "turn"
        const val UNTIL_CHAIN = "chain"
        const val UNTIL_DUEL = "duel"
    }
}

/** A line one seat draws from a card (or from itself) to others: a target, a "this one". */
@Serializable
data class Arrow(val seat: Int, val from: Int? = null, val to: List<Int> = emptyList())

/** [opener] did something [responder] may answer before play goes on; [entry] is the log entry that opened it. */
@Serializable
data class ResponseWindow(val opener: Int, val responder: Int, val entry: Int, val what: String = "")
