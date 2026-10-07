package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.Serializable

/**
 * A room's match (`docs/LOUNGE.md`, round two): one game, or the best of three with siding between games, as at a
 * tournament (`core/prep/Policy.kt`, §VII.C). The score lives on the [Room], so everyone in the Lounge reads it; the
 * decks each player sided to stay on kai's computer. Pure: the host only carries it.
 */
@Serializable
data class MatchScore(
    val bestOf: Int = 1,
    /** Games won by each seat. */
    val wins: List<Int> = listOf(0, 0),
    /** Games played, draws included. */
    val games: Int = 0,
    /** Between games: the players side, and the next game deals once both have. */
    val siding: Boolean = false,
    /** Which seats have sided for the next game (Ai's keeps its deck, and is counted at once). */
    val sided: List<Boolean> = listOf(false, false),
    /** The seat that chooses to go first or second next game: the loser of the last one. */
    val chooser: Int? = null,
    /** The seat going first next game, once the chooser has said. */
    val first: Int? = null,
) {
    val over: Boolean get() = wins.any { it >= LoungeMatch.need(bestOf) }
    val winner: Int? get() = wins.indexOfFirst { it >= LoungeMatch.need(bestOf) }.takeIf { it >= 0 }
    /** The game being played or sided for, from 1. */
    val game: Int get() = if (siding) games + 1 else games.coerceAtLeast(1)
}

object LoungeMatch {
    /** What a room may play: a single game, or the best of three. */
    val BEST_OF = listOf(1, 3)

    fun need(bestOf: Int): Int = bestOf / 2 + 1

    /** A new match for a room playing [bestOf]: game one is about to deal. */
    fun start(bestOf: Int): MatchScore = MatchScore(bestOf = if (bestOf in BEST_OF) bestOf else 1, games = 0)

    /**
     * The match after a game: [winner] (null for a draw) won it, [wentFirst] went first. Unless that settles it, the
     * players side next, and the loser chooses who goes first — after a draw, the player who went second.
     */
    fun after(m: MatchScore, winner: Int?, wentFirst: Int): MatchScore {
        val wins = m.wins.mapIndexed { i, w -> if (i == winner) w + 1 else w }
        val done = m.copy(wins = wins, games = m.games + 1, siding = false, sided = listOf(false, false), first = null)
        if (done.over || m.bestOf <= 1) return done.copy(chooser = null)
        val chooser = if (winner != null) 1 - winner else 1 - wentFirst
        return done.copy(siding = true, chooser = chooser)
    }

    /** [seat] has sided ([first]: its choice, when it is the chooser); the match unchanged when it is not siding. */
    fun sided(m: MatchScore, seat: Int, first: Boolean?): MatchScore {
        if (!m.siding || seat !in 0..1) return m
        val choice = if (seat == m.chooser && first != null) (if (first) seat else 1 - seat) else m.first
        return m.copy(sided = m.sided.mapIndexed { i, s -> s || i == seat }, first = choice)
    }

    /** Both seats have sided: the next game deals, the chooser's choice going first (going first, if it never said). */
    fun ready(m: MatchScore): Boolean = m.siding && m.sided.all { it }

    fun firstNext(m: MatchScore): Int = m.first ?: m.chooser ?: 0

    /** The match as the room shows it: "Game 2 · kai 1 – 0 Mika", or who won it. */
    fun words(m: MatchScore, names: List<String>): String {
        val score = "${names.getOrElse(0) { "Seat 1" }} ${m.wins[0]} – ${m.wins[1]} ${names.getOrElse(1) { "Seat 2" }}"
        return when {
            m.bestOf <= 1 -> score
            m.over -> "${names.getOrElse(m.winner ?: 0) { "A player" }} won the match, $score"
            m.siding -> "Siding for game ${m.game} · $score"
            else -> "Game ${m.game} of ${m.bestOf} · $score"
        }
    }

    /**
     * Whether [proposed] is a legal siding of [kept], the deck the player registered for the match; the problem in words,
     * or null. Siding is card for card (Policy §VII.C): the same cards across Main, Extra and Side, the Side Deck the same
     * size, the Main Deck 40 to 60 (or no smaller than it came, for a test deck under 40), the Extra Deck at most 15,
     * and an Extra Deck card never in the Main Deck ([isExtra]: null when the card is not known, and so not judged).
     */
    fun check(kept: Deck, proposed: Deck, isExtra: (CardId) -> Boolean?): String? {
        val before = (kept.main + kept.extra + kept.side).groupingBy { it }.eachCount()
        val after = (proposed.main + proposed.extra + proposed.side).groupingBy { it }.eachCount()
        if (before != after) return "Siding moves cards between your decks: none may come in or go out"
        if (proposed.side.size != kept.side.size) return "Side card for card: your Side Deck stays at ${kept.side.size}"
        val least = minOf(40, kept.main.size)
        if (proposed.main.size < least) return "Your Main Deck needs at least $least cards"
        if (proposed.main.size > 60) return "Your Main Deck holds at most 60 cards"
        if (proposed.extra.size > 15) return "Your Extra Deck holds at most 15 cards"
        if (proposed.main.any { isExtra(it) == true }) return "An Extra Deck card cannot go in the Main Deck"
        if (proposed.extra.any { isExtra(it) == false }) return "Only Extra Deck cards go in the Extra Deck"
        return null
    }
}
