package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckRules
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.search.EffectKinds

/**
 * A deck's shape in numbers, for Ai to reason from rather than guess: what its cards
 * are, what they do (read from their text, `EffectKinds`), which archetypes it runs,
 * whether it is legal, and how often it opens each group — five cards going first,
 * six going second (the hypergeometric chance to see at least one).
 */
object DeckAnalysis {
    /** The chance a hand of [hand] from [deckSize] cards holds at least one of [copies]. */
    fun atLeastOne(copies: Int, deckSize: Int, hand: Int): Double {
        if (copies <= 0 || deckSize <= 0) return 0.0
        if (copies >= deckSize) return 1.0
        var none = 1.0
        for (i in 0 until hand.coerceAtMost(deckSize)) {
            none *= (deckSize - copies - i).toDouble() / (deckSize - i)
            if (none <= 0.0) return 1.0
        }
        return 1.0 - none
    }

    /**
     * The deck in words. Legality is checked against [rules] when given — the builder's rules in force, with a chosen day
     * or Genesys — else against [format] alone; the groups are read over every printing the deck holds (Phase B).
     */
    fun describe(
        deck: Deck,
        card: (CardId) -> Card?,
        format: Format,
        groups: DeckGroups = DeckGroups.EMPTY,
        rules: DeckRules? = null,
        today: String = "",
    ): String = buildString {
        val groups = groups.projectedOnto(deck.main + deck.extra + deck.side) { CardIdentity.canonical(it, card) }
        val main = deck.main.mapNotNull(card)
        val n = deck.main.size
        appendLine("Main $n, extra ${deck.extra.size}, side ${deck.side.size}.")
        val byCategory = main.groupingBy { it.category }.eachCount()
        appendLine(
            "Monsters ${byCategory[CardCategory.MONSTER] ?: 0}, spells ${byCategory[CardCategory.SPELL] ?: 0}, traps ${byCategory[CardCategory.TRAP] ?: 0}.",
        )
        val kinds = EffectKind.entries.associateWith { k -> main.count { k in EffectKinds.of(it) } }.filterValues { it > 0 }
        if (kinds.isNotEmpty()) {
            appendLine("What the main deck's cards do (copies): " + kinds.entries.sortedByDescending { it.value }.joinToString { "${it.key.label.lowercase()} ${it.value}" } + ".")
        }
        val handTraps = main.filter { EffectKind.HAND_TRAP in EffectKinds.of(it) }
        if (handTraps.isNotEmpty()) {
            appendLine("Hand traps: ${handTraps.size} (${handTraps.groupingBy { it.name }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}); a hand of 5 sees one ${pct(atLeastOne(handTraps.size, n, 5))} of the time.")
        }
        val archetypes = main.mapNotNull { it.archetype }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
        if (archetypes.isNotEmpty()) appendLine("Archetypes: " + archetypes.joinToString { "${it.key} ${it.value}" } + ".")
        val v = rules?.validate(deck, card, today) ?: DeckValidator.validate(deck, card, format)
        val where = rules?.words() ?: format.name
        appendLine(if (v.isLegal) "Legal in $where." else "Not legal in $where: " + v.errors.joinToString("; ") { it.message } + ".")
        if (v.warnings.isNotEmpty()) appendLine("Warnings: " + v.warnings.joinToString("; ") { it.message } + ".")
        val inMain = groups.ordered().mapNotNull { g ->
            val copies = deck.main.count { groups.groupOf(it) == g.id }
            if (copies == 0) null else Triple(g.name, copies, g)
        }
        if (inMain.isNotEmpty()) {
            appendLine("Opening each group (at least one, going first / second):")
            inMain.forEach { (name, copies, _) ->
                appendLine("- $name ($copies): ${pct(atLeastOne(copies, n, 5))} / ${pct(atLeastOne(copies, n, 6))}")
            }
        }
        val bricks = main.filter { c -> c.category == CardCategory.MONSTER && EffectKinds.of(c).isEmpty() && c.frameType == "normal" }
        if (bricks.isNotEmpty()) appendLine("Vanilla monsters (often bricks unless the engine uses them): " + bricks.map { it.name }.distinct().joinToString() + ".")
    }.trim()

    private fun pct(p: Double) = "${kotlin.math.round(p * 100).toInt()}%"
}
