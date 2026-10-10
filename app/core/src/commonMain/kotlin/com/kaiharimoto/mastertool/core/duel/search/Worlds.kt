package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import kotlin.random.Random

/**
 * What a seat expects the other to hold (Phase E, E.3; `docs/phases/E.md` §4.3): where every world's guess at their hidden
 * cards comes from, in the plan's order of preference — their decklist where the seat knows it, else the field's lists by
 * share, else the field's interaction around blanks, else nothing.
 */
sealed interface Prior {
    /** Their decklist: full knowledge, a deck the person named as the opponent, or a Lounge room's deck shown to both. */
    data class Decklist(val main: List<Int>, val extra: List<Int> = emptyList()) : Prior

    /** The field's lists: a strategy chosen by its share, then one of its lists. */
    data class Field(val strategies: List<Strategy>) : Prior

    /** One strategy of the field: its share and its lists (each a Main Deck). */
    data class Strategy(val share: Double, val lists: List<List<Int>>)

    /** Each hidden card in their hand is a card of [chance] with its chance, or a blank with what is left. */
    data class Interaction(val chance: Map<Int, Double>) : Prior

    /** Nothing known: their hidden cards stay blanks. */
    data object Blank : Prior
}

/**
 * One guess at everything a seat cannot see (E.3): [game] is the fork it was dealt as — the seat's own view, its own Deck in
 * an order of the world's, the other seat's hidden cards filled from the prior — [weight] what the other seat's passes make
 * of it, [heldBack] how many of them it held an answer through, and [guessed] the codes it gave the hidden hand, by the
 * hand's place in the seat's view.
 */
class World(val k: Int, val game: DuelGame, val weight: Double, val heldBack: Int, val guessed: List<Int>)

/**
 * The worlds a search plays in (E.3, E.md §4.3). Every one is made from the seat's [DuelView] alone ([DuelFork.source]): a
 * card it cannot see is never read, only guessed, so two duels that differ only in what the seat cannot see deal the same
 * worlds. Cards they have shown or played are fixed in every world. World k is dealt by `DuelRandom.forRoll(seed, k)`, so
 * the worlds are the same on any device.
 *
 * **Weighted by what they did**: each time this turn they passed where a card the world put in their hand could have been
 * used, the world's weight is multiplied by [HOLD_BACK] — the chance a player keeps a live answer. Never discarded, only
 * weighted, so a bluff stays possible.
 */
object Worlds {
    /** Version 0's chance that a player keeps a live answer rather than use it (E.5 tunes it on won games). */
    const val HOLD_BACK = 0.3

    /** [k] worlds for the seat that [seen] what it has, the other seat's hidden cards from [prior]. */
    fun deal(seen: Seen, prior: Prior, k: Int, kit: GoldfishKit): List<World> = (0 until k).map { n -> one(seen, prior, n, kit) }

    /** The same, read off [game] for [seat] with [knows]: what [Seen.of] hands a search. */
    fun deal(game: DuelGame, seat: Int, prior: Prior, k: Int, kit: GoldfishKit, knows: String = DuelBrief.SELF): List<World> =
        deal(Seen.of(game, seat, kit, knows), prior, k, kit)

    private fun one(seen: Seen, prior: Prior, n: Int, kit: GoldfishKit): World {
        val src = seen.source
        val random = DuelRandom.forRoll(seen.seed, n)
        val fork = DuelFork.table(src, random.nextLong(), "world-$n")
        val them = 1 - src.seat
        val s = fork.state
        val cards = HashMap(s.cards)
        val hidden = s.cards.values.filter { it.owner == them && it.code == DuelFork.UNKNOWN && !it.token }.map { it.uid }.toHashSet()
        fun kindOf(code: Int) = kit.catalog.info(code)?.kind
        val inHand = s.seats[them].hand.filter { it in hidden }
        when (val p = resolve(prior, random)) {
            is Prior.Decklist -> {
                val pool = p.main.toMutableList()
                // What they have shown is theirs in every world: out of the pool once each.
                s.cards.values.filter { it.owner == them && it.code != DuelFork.UNKNOWN && !it.token && !it.extraDeck }.sortedBy { it.uid }.forEach { pool.remove(it.code) }
                pool.shuffle(random)
                fun take(fits: (Int) -> Boolean): Int? = pool.indexOfFirst(fits).takeIf { it >= 0 }?.let { pool.removeAt(it) }
                // A face-down card in a Spell & Trap Zone is a Spell or Trap; one in a Monster Zone is a monster.
                for (u in hidden.sorted()) {
                    val at = s.placeOf(u) as? Place.Zone ?: continue
                    val code = when (at.kind) {
                        ZoneKind.SPELL, ZoneKind.FIELD -> take { kindOf(it) == CardKind.SPELL || kindOf(it) == CardKind.TRAP || kindOf(it) == CardKind.FIELD_SPELL }
                        ZoneKind.MONSTER -> take { kindOf(it) == CardKind.MONSTER }
                        else -> null
                    } ?: continue
                    cards[u] = cards.getValue(u).copy(code = code)
                }
                for (u in inHand) take { true }?.let { cards[u] = cards.getValue(u).copy(code = it) }
                for (u in s.seats[them].deck) if (u in hidden) take { true }?.let { cards[u] = cards.getValue(u).copy(code = it) }
                val extra = p.extra.toMutableList()
                s.cards.values.filter { it.owner == them && it.code != DuelFork.UNKNOWN && it.extraDeck }.sortedBy { it.uid }.forEach { extra.remove(it.code) }
                extra.shuffle(random)
                for (u in s.seats[them].extra) if (u in hidden && extra.isNotEmpty()) cards[u] = cards.getValue(u).copy(code = extra.removeAt(0), extraDeck = true)
            }
            is Prior.Interaction -> {
                val entries = p.chance.entries.filter { it.value > 0 }.sortedBy { it.key }
                val total = entries.sumOf { it.value }
                val scale = if (total > 1.0) 1.0 / total else 1.0
                for (u in inHand) {
                    var r = random.nextDouble()
                    val code = entries.firstOrNull { e -> r -= e.value * scale; r < 0 }?.key ?: continue
                    cards[u] = cards.getValue(u).copy(code = code)
                }
            }
            else -> Unit
        }
        val state = s.copy(cards = cards)
        val dealt = DuelGame(fork.header.copy(start = state), emptyList(), 0, state, 0)
        val guessed = s.seats[them].hand.map { u -> cards[u]?.code ?: DuelFork.UNKNOWN }
        val held = heldBack(seen, guessed, kit)
        var weight = 1.0
        repeat(held) { weight *= HOLD_BACK }
        return World(n, dealt, weight, held, guessed)
    }

    /** A field prior's list chosen by share, then uniformly; any other prior as it is. */
    private fun resolve(prior: Prior, random: Random): Prior {
        if (prior !is Prior.Field) return prior
        val strategies = prior.strategies.filter { it.share > 0 && it.lists.isNotEmpty() }
        if (strategies.isEmpty()) return Prior.Blank
        var r = random.nextDouble() * strategies.sumOf { it.share }
        val pick = strategies.firstOrNull { r -= it.share; r < 0 } ?: strategies.last()
        return Prior.Decklist(pick.lists[random.nextInt(pick.lists.size)])
    }

    /**
     * How many of the other seat's passes this turn ([Seen.passes]) a card [guessed] for its hand could have been used at: each
     * read from the table as the seat saw it then, the guess put into the hand by its place in the view (a hand's veils hold
     * through a turn), and the engine asked whether that card had a move.
     */
    fun heldBack(seen: Seen, guessed: List<Int>, kit: GoldfishKit): Int {
        if (guessed.none { it != DuelFork.UNKNOWN }) return 0
        val seat = seen.seat
        val them = 1 - seat
        val code = seen.view.seats[them].hand.map { it.ref }.zip(guessed).toMap()
        var n = 0
        for ((i, m) in seen.passes.withIndex()) {
            val past = DuelFork.table(seen.source.copy(view = m.view), 0L, "held-$i")
            val hand = past.state.seats[them].hand
            val cards = HashMap(past.state.cards)
            var any = false
            m.view.seats[them].hand.forEachIndexed { j, c ->
                val g = code[c.ref]?.takeIf { it != DuelFork.UNKNOWN } ?: return@forEachIndexed
                val u = hand.getOrNull(j) ?: return@forEachIndexed
                cards[u] = cards.getValue(u).copy(code = g)
                any = true
            }
            if (!any) continue
            val t = FxTable(past.state.copy(cards = cards), m.fx, kit.book, kit.facts, seen.seed)
            val usable = runCatching { FxEngine.moves(t, them) }.getOrDefault(emptyList())
                .any { mv -> mv is FxMove.Activate && mv.uid in hand && cards[mv.uid]?.code != DuelFork.UNKNOWN && !kit.inertUid(mv.uid, t) }
            if (usable) n++
        }
        return n
    }
}
