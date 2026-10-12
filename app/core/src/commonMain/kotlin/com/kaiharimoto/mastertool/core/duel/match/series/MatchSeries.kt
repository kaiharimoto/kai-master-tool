package com.kaiharimoto.mastertool.core.duel.match.series

import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.DoorPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.match.MatchTable
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingMath
import kotlinx.serialization.Serializable

/*
 * Simulated series (Phase G part two, G2.1; `docs/phases/G2.md`): two decks play best-of-three matches as a tournament
 * does — game 1's first player set by the seed and swapped in its pair, the loser of each game going first in the next,
 * each deck's siding plan for the turn it plays put into its deck before games 2 and 3 — every seat played by the app's
 * own player. What one match comes to is a pure function of its seed, the decks, their plans and the players: the same
 * series on any device.
 */

/** One deck in a series: its lists, and the plan it sides with against the other going first and going second. */
data class SeriesDeck(
    val id: String,
    val name: String,
    val deck: Deck,
    val first: SidePlan = SidePlan(),
    val second: SidePlan = SidePlan(),
) {
    /** The plan for a game this deck plays going first ([goesFirst]) or second. */
    fun plan(goesFirst: Boolean): SidePlan = if (goesFirst) first else second
}

/** One game of a match: which deck went first (0 or 1), which won (null: a draw), how, and in how many turns. */
@Serializable
data class SeriesGame(val first: Int, val winner: Int? = null, val how: String = "", val turns: Int = 0)

/**
 * One match, best of three: its [seed], the deck that went first in game 1 ([opener]), its games, and the match's winner
 * (null: a draw — the games split, or a game left undecided).
 */
@Serializable
data class SeriesMatch(val seed: Long, val opener: Int, val games: List<SeriesGame> = emptyList(), val winner: Int? = null)

object MatchSeries {
    const val BEST_OF = 3

    /** A game past this turn is a draw by limit, its winner the one ahead on life points (`MatchReferee.limit`). */
    const val TURNS = 10

    /** The series' first seed; the rest follow from it. */
    const val SEED = 1_000_003L

    /** A cue's time: none a search could reach, so a slow device never loses a game by its clock (the ladder's). */
    private const val UNTIMED = 24L * 60 * 60 * 1000

    /** The seeds of a series of [pairs]: each a seed played twice, each deck going first in game 1 once. */
    fun seeds(pairs: Int, base: Long = SEED): List<Pair<Long, Int>> =
        (0 until pairs).flatMap { i -> val s = mix(base, i.toLong()); listOf(s to 0, s to 1) }

    /** Game [game]'s own seed in the match dealt from [seed]: its deal, its shuffles. */
    fun gameSeed(seed: Long, game: Int): Long = mix(seed, 1_000L + game)

    /** Who goes first in the game after [last]: its loser chooses, and goes first; after a draw, the same player again. */
    fun nextFirst(last: SeriesGame): Int = last.winner?.let { 1 - it } ?: last.first

    /** The match's winner from its games: the first to two wins; after three games without, the one with more, else none. */
    fun winner(games: List<SeriesGame>): Int? {
        val wins = IntArray(2)
        games.forEach { g -> g.winner?.let { wins[it]++ } }
        return when {
            wins[0] >= 2 -> 0
            wins[1] >= 2 -> 1
            games.size < BEST_OF -> null
            wins[0] > wins[1] -> 0
            wins[1] > wins[0] -> 1
            else -> null
        }
    }

    /** Whether the match is over after [games]. */
    fun over(games: List<SeriesGame>): Boolean = games.size >= BEST_OF || games.count { it.winner == 0 } >= 2 || games.count { it.winner == 1 } >= 2

    /** [d]'s lists for a game: game 1 as registered, games 2 and 3 with its plan for the turn it plays put in. */
    fun decksFor(d: SeriesDeck, game: Int, goesFirst: Boolean, isExtra: (CardId) -> Boolean): Deck =
        if (game == 1) d.deck else SidingMath.postSide(d.deck, d.plan(goesFirst), isExtra)

    /**
     * What a seat knows of the other deck's lists in game [game] (the red team, R7): its lists as registered, never the lists
     * it sided into, which no real player is shown — game 1 its Main and Extra Decks; games 2 and 3 any card of its Main and
     * Side Decks as registered, each side card counted where it would go ([isExtra]), since any of them may be in.
     */
    fun known(registered: Deck, game: Int, isExtra: (CardId) -> Boolean): Deck =
        if (game == 1) {
            registered.copy(side = emptyList())
        } else {
            registered.copy(
                main = registered.main + registered.side.filterNot(isExtra),
                extra = registered.extra + registered.side.filter(isExtra),
                side = emptyList(),
            )
        }

    /**
     * One match of [a] (deck 0, seat 0) against [b] (deck 1, seat 1), dealt from [seed], deck [opener] going first in game 1.
     * Each game's seats are made by [players] (seat and the lists it knows the other deck by, [known] — never its post-side
     * lists → its player, which sees only its own view); [kit] the written effects.
     */
    suspend fun play(
        a: SeriesDeck,
        b: SeriesDeck,
        seed: Long,
        opener: Int,
        kit: GoldfishKit,
        players: (seat: Int, against: Deck) -> DoorPlayer,
        isExtra: (CardId) -> Boolean,
        turns: Int = TURNS,
    ): SeriesMatch {
        val decks = listOf(a, b)
        val games = ArrayList<SeriesGame>()
        var first = opener
        while (!over(games)) {
            val n = games.size + 1
            val lists = decks.mapIndexed { i, d -> decksFor(d, n, goesFirst = i == first, isExtra) }
            val seats = decks.mapIndexed { i, d ->
                MatchSeat(d.name, d.id, d.name, lists[i].main.map(CardId::value), lists[i].extra.map(CardId::value), connection = "Series")
            }
            val header = MatchTable.header("series-$seed-$opener-$n", gameSeed(seed, n), seats, 0L).copy(openingRoll = false, first = first)
            val table = MatchTable(DuelGame.start(header), kit.catalog, MatchRules(turnCap = turns, paceMs = 0, cueMillis = UNTIMED))
            // Each seat is handed the other's registered lists, never the ones it sided into for this game (R7).
            val seatPlayers = (0..1).map { seat -> players(seat, known(decks[1 - seat].deck, n, isExtra)) }
            val engines = seatPlayers.map { DuelResults.Engine("Series", it.engine) }
            val end = AiMatch(table, seatPlayers, engines, kit = kit).run()
            val r = end.result
            val g = SeriesGame(first, r?.winner, r?.how.orEmpty(), r?.turns ?: table.state.turn)
            games += g
            first = nextFirst(g)
        }
        return SeriesMatch(seed, opener, games.toList(), winner(games))
    }

    /**
     * [deck] with [change] made, each copy cut from where it is and each added where it belongs ([isExtra]), the Side Deck
     * kept; null when it cuts more copies than the deck holds. The comparison's version B of a change.
     */
    fun changed(deck: Deck, change: DeckChange, isExtra: (CardId) -> Boolean, canonical: (Int) -> Int = { it }): Deck? {
        val main = deck.main.toMutableList()
        val extra = deck.extra.toMutableList()
        // A copy is cut by card, never by passcode: an alternate artwork is the same card.
        fun cut(list: MutableList<CardId>, code: Int): Boolean {
            val i = list.indexOfFirst { canonical(it.value) == canonical(code) }
            if (i < 0) return false
            list.removeAt(i)
            return true
        }
        change.out?.let { code ->
            repeat(change.copies) { if (!cut(main, code) && !cut(extra, code)) return null }
        }
        change.into?.let { code ->
            val card = CardId(code)
            repeat(change.copies) { if (isExtra(card)) extra += card else main += card }
        }
        return deck.copy(main = main, extra = extra)
    }

    /** A 64-bit mix (splitmix64's finaliser) of [a] and [b]: seeds that differ in one bit deal nothing alike. */
    internal fun mix(a: Long, b: Long): Long {
        var z = a + b * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}
