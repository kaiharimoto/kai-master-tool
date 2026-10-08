package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.dice.Toss
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /** The opening roll for who goes first (1.0.87): null when the duel has none, as every duel before it. */
    val opening: Opening? = null,
    /**
     * The die and the coin lying on the table where they landed (1.0.96), each seat's latest of each: put back by the
     * next move that is not talk or chance.
     */
    val chance: List<Chance> = emptyList(),
) {
    /** The opening roll is still to be decided: turn 1 has not begun. */
    val beforeTurnOne: Boolean get() = opening != null && !opening.decided

    fun seat(i: Int): SeatState = seats[i]

    /** The card [uid], or null when it has left the duel: by uid, without boxing it, once `DuelRules` keeps the cards ([CardMap]). */
    fun card(uid: Int): CardInst? {
        val held = cards
        return if (held is CardMap) held.byUid(uid) else held[uid]
    }

    /**
     * How many times [placeOf] was asked of this table: the first few walk it, and from then on an index of every card's
     * place is built once and read (1.0.92). Never part of the table: not in its equality, its copy or its file.
     */
    @Transient
    private var asked: Int = 0

    /**
     * Every card's place, as [scan] finds it — the first place a uid stands, walked in [scan]'s order (1.0.92) — built the
     * first time it is read ([places]). Never part of the table, like [asked]. Kept by uid in an [IntTable] (2026-10): the
     * `HashMap` it replaces boxed every uid past 127 and was built behind a lock (`lazy`) every table paid for.
     */
    @Transient
    @kotlin.concurrent.Volatile
    private var index: IntTable<Place>? = null

    private fun places(): IntTable<Place> = index ?: indexed().also { index = it }

    private fun indexed(): IntTable<Place> {
        val out = IntTable.Builder<Place>(cards.size)
        emz.forEachIndexed { i, u -> if (u != null) cards[u]?.let { out.putIfAbsent(u, Places.zone(it.controller, ZoneKind.EMZ, i)) } }
        seats.forEachIndexed { s, seat ->
            seat.monsters.forEachIndexed { i, u -> if (u != null) out.putIfAbsent(u, Places.zone(s, ZoneKind.MONSTER, i)) }
            seat.spells.forEachIndexed { i, u -> if (u != null) out.putIfAbsent(u, Places.zone(s, ZoneKind.SPELL, i)) }
            seat.field?.let { u -> out.putIfAbsent(u, Places.zone(s, ZoneKind.FIELD, 0)) }
            PileKind.entries.forEach { k -> seat.pile(k).forEachIndexed { i, u -> out.putIfAbsent(u, Places.pile(s, k, i)) } }
        }
        cards.values.forEach { host -> host.under.forEachIndexed { i, u -> out.putIfAbsent(u, Place.Under(host.uid, i)) } }
        return out.build()
    }

    /** Where the card is now, or null if it has left the duel (a token gone). */
    fun placeOf(uid: Int): Place? {
        val card = card(uid) ?: return null
        // A table asked once or twice (most tables a fold makes) is walked; one asked more, the table drawn, is indexed.
        if (asked < SCANS) {
            asked++
            return scan(uid, card)
        }
        return places()[uid]
    }

    /** [placeOf] by walking the table: the EMZ, then each seat's zones and piles, then every card's materials. */
    internal fun scan(uid: Int, card: CardInst): Place? {
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

    /** Every uid on the field, both seats, EMZ included: the EMZ, then each seat's monsters, Spells & Traps and Field Spell. */
    fun onField(): List<Int> {
        val out = ArrayList<Int>()
        emz.forEach { if (it != null) out += it }
        seats.forEach { seat ->
            seat.monsters.forEach { if (it != null) out += it }
            seat.spells.forEach { if (it != null) out += it }
            seat.field?.let { out += it }
        }
        return out
    }

    /** The free zones of [kind] on [seat]'s side, in index order (each zone the one place [Places] keeps for it). */
    fun freeZones(seat: Int, kind: ZoneKind): List<Place.Zone> = when (kind) {
        ZoneKind.EMZ -> emz.indices.filter { emz[it] == null }.map { Places.zone(seat, ZoneKind.EMZ, it) }
        ZoneKind.FIELD -> if (seats[seat].field == null) listOf(Places.zone(seat, ZoneKind.FIELD, 0)) else emptyList()
        else -> {
            // What [at] reads for each zone of the kind, without making a place to ask it with.
            val zones = if (kind == ZoneKind.MONSTER) seats[seat].monsters else seats[seat].spells
            val out = ArrayList<Place.Zone>(ZONES)
            for (i in 0 until ZONES) if (zones.getOrNull(i) == null) out += Places.zone(seat, kind, i)
            out
        }
    }

    companion object {
        /** Asks of [placeOf] answered by walking the table before it is indexed. */
        private const val SCANS = 2
        const val ZONES = 5
        const val START_LP = 8000
        /** Seat 0's cards are 1.., seat 1's are 1001.., tokens from here on. */
        const val SEAT_UIDS = 1000
        const val TOKEN_UIDS = 100_000
    }
}

/**
 * The places a table's index and its free zones hand out, made once (2026-10): a place is a value, equal to any other naming
 * the same zone or pile position, so every table can share these rather than make each card's place again on every new
 * table.
 */
internal object Places {
    private const val SEATS = 2
    private const val DEPTH = 128
    private val zones = Array(SEATS * ZoneKind.entries.size * DuelState.ZONES) { j ->
        Place.Zone(j / (ZoneKind.entries.size * DuelState.ZONES), ZoneKind.entries[j / DuelState.ZONES % ZoneKind.entries.size], j % DuelState.ZONES)
    }
    private val piles = Array(SEATS * PileKind.entries.size * DEPTH) { j ->
        Place.Pile(j / (PileKind.entries.size * DEPTH), PileKind.entries[j / DEPTH % PileKind.entries.size], j % DEPTH)
    }

    fun zone(seat: Int, kind: ZoneKind, index: Int): Place.Zone =
        if (seat in 0 until SEATS && index in 0 until DuelState.ZONES) zones[(seat * ZoneKind.entries.size + kind.ordinal) * DuelState.ZONES + index]
        else Place.Zone(seat, kind, index)

    fun pile(seat: Int, kind: PileKind, index: Int): Place.Pile =
        if (seat in 0 until SEATS && index in 0 until DEPTH) piles[(seat * PileKind.entries.size + kind.ordinal) * DEPTH + index]
        else Place.Pile(seat, kind, index)
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
    /**
     * Dealt into the Extra Deck (1.0.93): the only cards that go back there face-down. A Main Deck card goes to the Extra
     * Deck only face-up — a Pendulum Monster (kai: "I am able to put maindeck monsters in the extra deck, which should never
     * happen unless a pendulum monster is in the extra deck face up"). Set by the deal, which every table is folded from,
     * so a duel saved before it reads right too.
     */
    val extraDeck: Boolean = false,
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
    /** Negated (1.0.90): it stays on the chain and resolves doing nothing. */
    val negated: Boolean = false,
)

/** [attacker] attacks [target], or directly when there is none (1.0.83). */
@Serializable
data class Attack(val seat: Int, val attacker: Int, val target: Int? = null)

/** [seat] asks to go to [phase], or to end the turn when [end] (1.0.79). */
@Serializable
data class Proposal(val seat: Int, val phase: DuelPhase? = null, val end: Boolean = false)

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

/**
 * A die or a coin thrown on the table (1.0.96): whose, which, what it reads ([value]: the die's number; the coin's 1 for
 * heads, 0 for tails) and the [toss] it was thrown with, which every screen plays out the same.
 */
@Serializable
data class Chance(val seat: Int, val coin: Boolean, val value: Int, val toss: Toss) {
    val heads: Boolean get() = coin && value == 1
}
