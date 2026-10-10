package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable

/*
 * "Without it" (Phase G, G3): a card's worth to the deck as the hands that lose their board when it is gone. Each copy cut is
 * put back as a blank — a card nothing can play or pick ([GoldfishKit.BLANK]) — so the deck keeps its size and only the card
 * is missing, and the deck with it and without it are compared on the same hands ([VersionCompare]). Ablating a card that
 * is itself a blank changes nothing, exactly: the maps are the same maps.
 */
object Ablation {
    enum class Copies { ONE, ALL }

    /**
     * [deck] with [copies] of [card] replaced by [into] (a blank unless said), each in the section it was in; null when the
     * deck does not hold the card.
     */
    fun variant(deck: GoldfishDeck, card: Int, copies: Copies, kit: GoldfishKit, into: Int = GoldfishKit.BLANK): GoldfishDeck? {
        val c = kit.canonical(card)
        val main = deck.main.map(kit::canonical).toMutableList()
        // Each blank is dealt where the copy it replaced was ([GoldfishDeck.keyAs]): the hands are the deck's own hands.
        val keys = (deck.keyAs?.takeIf { it.size == deck.main.size } ?: deck.main).map(kit::canonical)
        val extra = deck.extra.map(kit::canonical).toMutableList()
        val inMain = main.count { it == c }
        val inExtra = extra.count { it == c }
        if (inMain + inExtra == 0) return null
        fun swap(list: MutableList<Int>, n: Int) = repeat(n) { list[list.lastIndexOf(c)] = into }
        when (copies) {
            Copies.ONE -> if (inMain > 0) swap(main, 1) else swap(extra, 1)
            Copies.ALL -> { swap(main, inMain); swap(extra, inExtra) }
        }
        return GoldfishDeck(main, extra, id = deck.id, name = deck.name, keyAs = keys)
    }

    /** One card ablated: the comparison of the deck with it (A) and without it (B), and what was lost. */
    data class Row(val card: Int, val copies: Copies, val comparison: Comparison) {
        /** The share lost without it (positive: the deck is worse without the card). */
        val worth: Double get() = -comparison.paired.difference

        /** Hands that reached the ask with the card and do not without it. */
        val bricks: List<ChangedHand> get() = comparison.changed.filter { it.a == HandAnswer.YES && it.b != HandAnswer.YES }
    }

    /**
     * [card] ablated in [deck] against [ask]: the deck as it is against the deck without [copies] of it, on the same hands.
     * Never sequential: a card's worth is read across the engine, so every card gets the same [hands].
     */
    suspend fun run(
        deck: GoldfishDeck,
        card: Int,
        copies: Copies,
        ask: CompareAsk,
        kit: GoldfishKit,
        cache: MapCache = MapCache(),
        first: Boolean = true,
        seed: Long = 1L,
        hands: Int = 400,
        budget: Int = MapSearch.DEFAULT_BUDGET,
        workers: Int = MapWork.workers(),
        stop: () -> Boolean = { false },
        progress: (CompareProgress) -> Unit = {},
    ): Row? {
        val without = variant(deck, card, copies, kit) ?: return null
        val setup = CompareSetup(deck, without, ask, first, seed, batch = hands, most = hands, budget = budget, recheck = 1, sequential = false)
        return Row(kit.canonical(card), copies, VersionCompare.run(setup, kit, cache, workers, stop, progress))
    }

    /** Every engine card of [deck] ablated in turn, in [StarterTable.engine]'s order; [done] is told after each card. */
    suspend fun engine(
        deck: GoldfishDeck,
        copies: Copies,
        ask: CompareAsk,
        kit: GoldfishKit,
        cache: MapCache = MapCache(),
        first: Boolean = true,
        seed: Long = 1L,
        hands: Int = 400,
        budget: Int = MapSearch.DEFAULT_BUDGET,
        workers: Int = MapWork.workers(),
        stop: () -> Boolean = { false },
        done: (Row, Int, Int) -> Unit = { _, _, _ -> },
    ): List<Row> {
        val cards = StarterTable.engine(deck.main, kit)
        val out = ArrayList<Row>()
        for ((i, c) in cards.withIndex()) {
            if (stop()) break
            val row = run(deck, c, copies, ask, kit, cache, first, seed, hands, budget, workers, stop) ?: continue
            if (row.comparison.stopped) break
            out += row
            done(row, i + 1, cards.size)
        }
        return out
    }
}
