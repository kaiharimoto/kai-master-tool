package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.Format

/**
 * What the builder checks a deck against (Phase B, 1.1.1): the region, the day, the list in force that day, or Genesys.
 *
 * - **Advanced** ([genesysCap] null): the [format]'s Forbidden & Limited list — the pool's own ([limits] null) or a dated
 *   one from the banlist history ([limits], named [listName]) — and each card released in that region by [asOf]
 *   (today when null).
 * - **Genesys** ([genesysCap] set): Konami's points format. No Forbidden & Limited list (three of anything), TCG cards
 *   only, no Link or Pendulum monsters, and the whole deck's points at most the cap ([GenesysRules]).
 *
 * One place decides, so the builder's ✓, its Issues drawer, Prep and Ai read the same answer.
 */
data class DeckRules(
    val format: Format = Format.TCG,
    /** The day, `yyyy-MM-dd`; null is today. */
    val asOf: String? = null,
    val limits: BanSource? = null,
    /** The dated list's name ("April 2025 Lists (TCG)"); null for the pool's own. */
    val listName: String? = null,
    val genesysCap: Int? = null,
    /** Why the check is not quite what was asked, said beside its issues ("the lists could not be read …"). */
    val note: String? = null,
) {
    val genesys: Boolean get() = genesysCap != null

    /** The deck checked on [today] (`yyyy-MM-dd`) unless [asOf] names another day. */
    fun validate(deck: Deck, cards: (CardId) -> Card?, today: String): DeckValidation {
        val day = asOf ?: today
        val noted = listOfNotNull(note?.let { DeckIssue(IssueSeverity.WARNING, it) })
        val cap = genesysCap ?: return DeckValidator.validate(deck, cards, format, day, limits).let { DeckValidation(it.issues + noted) }
        val base = DeckValidator.validate(deck, cards, Format.TCG, day, BanSource { BanStatus.UNLIMITED })
        val points = GenesysRules.check(deck, cards, cap)
        val extra = buildList {
            if (points.points > cap) add(DeckIssue(IssueSeverity.ERROR, "The deck costs ${points.points} Genesys points; the cap is $cap."))
            points.barred.forEach { (id, name) ->
                add(DeckIssue(IssueSeverity.ERROR, "$name is a Link or Pendulum monster, which Genesys does not allow.", cardId = id))
            }
        }
        return DeckValidation(base.issues + extra + noted)
    }

    /** The deck's Genesys points and the cap, when this is Genesys. */
    fun points(deck: Deck, cards: (CardId) -> Card?): GenesysRules.Result? = genesysCap?.let { GenesysRules.check(deck, cards, it) }

    /**
     * The rules in words, for "Legal in …": "TCG", "TCG on 1 May 2025 (the April 2025 Lists (TCG))", "Genesys, 100 points".
     */
    fun words(): String = when {
        genesysCap != null -> "Genesys, $genesysCap points" + (asOf?.let { " on ${Legality.readable(it)}" } ?: "")
        asOf == null -> format.name
        else -> "${format.name} on ${Legality.readable(asOf)}" + (listName?.let { " (the $it)" } ?: "")
    }
}
