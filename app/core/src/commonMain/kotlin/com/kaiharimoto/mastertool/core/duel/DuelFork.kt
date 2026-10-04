package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import kotlin.random.Random

/**
 * A fork of the duel in play for a self-play table (Phase C stage 3, Ai World, `docs/phases/C.md` §6): the position as the
 * seat Ai would hold sees it, and nothing more. Built only from that seat's [DuelView] and its own decklist, so a fork can
 * never hold a card that seat could not see — the live table is read, never changed, and never handed over whole.
 *
 * What the fork keeps: every card the seat sees, where it stands, with its counters and materials; life points, the turn,
 * the phase, the chain, locks and attacks. What it cannot know becomes an unknown card ([UNKNOWN], kept by count and place)
 * — the other seat's hand, Deck and set cards — except the seat's own Deck and other own cards out of its sight, which it
 * fills from its own decklist less every own card it sees (a player knows what is left in their Deck, not its order).
 * Both Decks are shuffled by the fork's seed, the known positions kept (a revealed top card stays on top). With full
 * knowledge the view is everything and the fork is the table itself, its Decks shuffled.
 */
object DuelFork {
    /** The passcode of a card the forking seat could not see. */
    const val UNKNOWN = 0

    /** What a fork is read from: the seat's view of the live duel, its own decklist, and how the live duel began. */
    data class Source(
        val view: DuelView,
        /** The live header with only the forking seat's decklist kept (both with full knowledge). */
        val header: DuelHeader,
        /** The seat Ai would hold. */
        val seat: Int,
        /** Ai's knowledge setting (`DuelBrief`): what the view was read with. */
        val knows: String,
    )

    /** The source for [seat] reading the live [game] with [knows]; the other seat's decklist dropped unless [knows] is full. */
    fun source(game: DuelGame, seat: Int, knows: String): Source {
        val viewer = DuelBrief.viewer(knows, seat)
        val view = DuelView.of(game.state, viewer, game.header.seed)
        val header = game.header.copy(
            seats = game.header.seats.mapIndexed { i, s -> if (viewer == null || i == seat) s else s.copy(main = emptyList(), extra = emptyList()) },
        )
        return Source(view, header, seat, knows)
    }

    /** The fork as a table of its own: dealt by no one, its Decks shuffled by [seed], a new duel's [id]. */
    fun table(src: Source, seed: Long, id: String): DuelGame {
        val v = src.view
        val known = HashMap<Int, Int>() // a view's ref (a uid, or a veil) -> the fork's uid
        val cards = HashMap<Int, CardInst>()
        val free = IntArray(2) { s -> 1 + s * DuelState.SEAT_UIDS + PLACEHOLDERS }
        val unknownOwn = mutableListOf<Int>()
        val random = Random(seed)

        fun uid(c: ViewCard): Int = known.getOrPut(c.ref) {
            if (c.ref > 0) c.ref else free[c.owner.coerceIn(0, 1)]++
        }
        fun card(c: ViewCard): Int {
            val u = uid(c)
            val under = c.under.map(::card)
            cards[u] = CardInst(u, c.code ?: UNKNOWN, c.owner, c.controller, c.pos, c.counters, c.token, c.name, under, c.atk, c.def, c.extraDeck)
            if (c.code == null && c.owner == src.seat && !c.token) unknownOwn += u
            return u
        }
        fun unknown(owner: Int): Int {
            val u = free[owner.coerceIn(0, 1)]++
            cards[u] = CardInst(u, UNKNOWN, owner)
            if (owner == src.seat) unknownOwn += u
            return u
        }

        val seats = v.seats.mapIndexed { i, sv ->
            val hand = sv.hand.map(::card)
            val extra = sv.extra.map(::card)
            val gy = sv.gy.map(::card)
            val banished = sv.banished.map(::card)
            val monsters = sv.monsters.map { it?.let(::card) }
            val spells = sv.spells.map { it?.let(::card) }
            val field = sv.field?.let(::card)
            // The Deck: its known positions kept, the rest shuffled (unknown cards, filled below for the seat's own).
            val fixed = sv.deckKnown.mapValues { (_, c) -> card(c) }
            val rest = (0 until sv.deck).filter { it !in fixed }.map { unknown(i) }.shuffled(random).toMutableList()
            val deck = (0 until sv.deck).map { k -> fixed[k] ?: rest.removeAt(0) }
            SeatState(sv.name, sv.lp, hand, deck, extra, gy, banished, monsters, spells, field)
        }
        val emz = v.emz.map { it?.let(::card) }

        // The seat's own unseen cards from its decklist, less every own Main Deck card it sees: what a player knows is left.
        val list = src.header.seats.getOrNull(src.seat)?.main.orEmpty().toMutableList()
        cards.values.filter { it.owner == src.seat && it.code != UNKNOWN && !it.token && !it.extraDeck }.forEach { list.remove(it.code) }
        val fill = list.shuffled(random)
        unknownOwn.forEachIndexed { k, u -> fill.getOrNull(k)?.let { code -> cards[u] = cards.getValue(u).copy(code = code) } }

        fun ref(r: Int): Int = known[r] ?: r
        val state = DuelState(
            cards = cards,
            seats = seats,
            emz = emz,
            turn = v.turn,
            active = v.active,
            phase = v.phase,
            chain = v.chain.map { l -> l.copy(uid = l.uid?.let(::ref), targets = l.targets.map(::ref)) },
            arrows = emptyList(),
            solo = v.solo,
            proposal = v.proposal,
            locks = v.locks,
            lastLock = v.locks.maxOfOrNull { it.id } ?: 0,
            resolved = v.resolved.map(::ref),
            attacks = v.attacks.map { it.copy(attacker = ref(it.attacker), target = it.target?.let(::ref)) },
            opening = v.opening,
            nextUid = maxOf(DuelState.TOKEN_UIDS, (cards.keys.maxOrNull() ?: 0) + 1),
        )
        val header = src.header.copy(
            id = id,
            seed = seed,
            // Its deal is the fork itself: no decklist is dealt again.
            seats = src.header.seats.map { it.copy(main = emptyList(), extra = emptyList()) },
            first = src.header.first,
        )
        return DuelGame(header, emptyList(), 0, state, 0)
    }

    /** Fork uids start this far into each seat's range, clear of every dealt card. */
    private const val PLACEHOLDERS = 600

    /** How an unknown card reads: a card the forking seat never saw. */
    val UNKNOWN_INFO = DuelCardInfo("An unknown card", CardKind.MONSTER)
}
