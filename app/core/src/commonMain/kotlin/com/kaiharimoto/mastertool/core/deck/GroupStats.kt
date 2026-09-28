package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.hand.LensOdds
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck

/** One group, in numbers: what the Groups column's slides draw (1.0.18). */
data class GroupStat(
    val id: String,
    val name: String,
    val color: Int,
    val main: Int,
    val extra: Int,
    val side: Int,
    val monsters: Int,
    val spells: Int,
    val traps: Int,
    /** The chance of opening at least one of it in a five-card hand. */
    val opening: Double,
    /** How many of it an opening hand holds, on average. */
    val expected: Double,
)

data class GroupReport(val groups: List<GroupStat>, val ungroupedMain: Int, val mainSize: Int)

/**
 * The deck's groups in numbers (kai, 1.0.18: "for groups with the extra space add
 * some data analysis visuals"). One pass, all of it arithmetic the builder already
 * trusts: the opening rates are `LensOdds` over the Roles keying, the same numbers
 * the group rows show, and the expected count is the hypergeometric mean,
 * `count × hand ÷ deck`.
 */
object GroupStats {
    fun of(deck: Deck, groups: DeckGroups, card: (CardId) -> Card?, handSize: Int = LensOdds.DEFAULT_HAND): GroupReport {
        val keying = DeckLenses.key(Lens.ROLES, deck.main, card, groups)
        val odds = LensOdds.atLeastOne(keying, deck.main.size, handSize)
        val stats = groups.ordered().map { group ->
            val mine = deck.main.filter { groups.groupOf(it) == group.id }
            fun kind(k: CardCategory) = mine.count { card(it)?.category == k }
            GroupStat(
                id = group.id,
                name = group.name,
                color = group.color,
                main = mine.size,
                extra = deck.extra.count { groups.groupOf(it) == group.id },
                side = deck.side.count { groups.groupOf(it) == group.id },
                monsters = kind(CardCategory.MONSTER),
                spells = kind(CardCategory.SPELL),
                traps = kind(CardCategory.TRAP),
                opening = odds[group.id] ?: 0.0,
                expected = if (deck.main.isEmpty()) 0.0 else mine.size.toDouble() * handSize / deck.main.size,
            )
        }
        return GroupReport(stats, deck.main.count { groups.groupOf(it) == null }, deck.main.size)
    }
}
