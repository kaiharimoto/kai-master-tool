package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.math.ln

/**
 * Opponents by strategy, not by id (Phase G, G.8; the red team's L3). A game names its opponent by a web deck's id, so a
 * list re-imported (a `.ydkw` opened again gives its decks new ids) or a new web for the next event left every earlier game
 * behind, under an id nothing shows. Here an earlier game counts for today's opponent when its list is the same strategy:
 *
 * - its list ([TestGame.opponentCards], recorded since G.8, else the old deck if it is still kept) is at least [SAME] alike
 *   to today's by rarity-weighted Jaccard ([FieldBuilder.similarity], the field's own measure of a strategy);
 * - else today's three cards it is known by ([Opponent.covers], `Matchup.covers`) are all in it;
 * - else the names are the same (a typed name, or a list with no cards kept).
 *
 * Counted apart ([Folded.earlier]), so the matrix says "n games from earlier lists" and the person keeps or leaves them out.
 */
object OpponentMatch {
    /** Today's opponent: its id, name, distinct cards and the cards it is known by. */
    data class Opponent(val id: String, val name: String, val cards: Set<CardId>, val covers: List<CardId> = emptyList())

    /** Two lists this alike are one strategy. */
    const val SAME = 0.5

    /** [games] with each earlier list's games under today's opponent (when [Folded.included]), and how many each took. */
    data class Folded(val games: List<TestGame>, val earlier: Map<String, Int>, val included: Boolean) {
        val earlierTotal: Int get() = earlier.values.sum()
    }

    /**
     * [games] read against [current]: a game against an earlier list of today's opponent is put under today's id when
     * [include], else left under its own. [known] reads an old opponent's cards when the game did not record them.
     */
    fun fold(games: List<TestGame>, current: List<Opponent>, known: (String) -> Set<CardId>?, include: Boolean): Folded {
        if (current.isEmpty()) return Folded(games, emptyMap(), include)
        val ids = current.map { it.id }.toSet()
        val weights = weights(current)
        val cache = HashMap<String, Opponent?>()
        val earlier = HashMap<String, Int>()
        val out = games.map { g ->
            if (g.opponent in ids) return@map g
            val to = cache.getOrPut(g.opponent + "|" + g.opponentName + "|" + g.opponentCards?.hashCode()) { match(g, current, known, weights) } ?: return@map g
            earlier[to.id] = (earlier[to.id] ?: 0) + 1
            if (include) g.copy(opponent = to.id, opponentName = to.name) else g
        }
        return Folded(out, earlier, include)
    }

    /** Today's opponent [game] was an earlier list of, or null. */
    fun match(game: TestGame, current: List<Opponent>, known: (String) -> Set<CardId>?, weights: Map<CardId, Double> = weights(current)): Opponent? {
        val cards = game.opponentCards?.map(::CardId)?.toSet()?.takeIf { it.isNotEmpty() } ?: known(game.opponent)?.takeIf { it.isNotEmpty() }
        if (cards != null) {
            val best = current.filter { it.cards.isNotEmpty() }.map { it to FieldBuilder.similarity(it.cards, cards, weights) }.maxByOrNull { it.second }
            if (best != null && best.second >= SAME) return best.first
            current.firstOrNull { o -> o.covers.isNotEmpty() && o.covers.all { it in cards } }?.let { return it }
        }
        val name = fold(game.opponentName.ifBlank { game.opponent })
        return current.firstOrNull { name.isNotEmpty() && fold(it.name) == name }
    }

    /** Each card's weight across today's lists: a card every list plays tells none apart. */
    fun weights(current: List<Opponent>): Map<CardId, Double> {
        val df = HashMap<CardId, Int>()
        current.forEach { o -> o.cards.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = current.size.toDouble()
        return df.mapValues { (_, k) -> ln((n + 1) / (k + 0.5)).coerceAtLeast(0.01) }
    }

    private fun fold(name: String) = name.trim().lowercase()

    /** "4 games from earlier lists" for one opponent; null for none. */
    fun words(n: Int, included: Boolean): String? = n.takeIf { it > 0 }?.let {
        "$it ${if (it == 1) "game" else "games"} from earlier lists" + if (included) " counted" else " left out"
    }
}
