package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format

enum class IssueSeverity {
    /** The deck is not tournament legal as it stands. */
    ERROR,

    /** Legal, but worth flagging before a tournament. */
    WARNING,
}

data class DeckIssue(
    val severity: IssueSeverity,
    val message: String,
    val section: DeckSection? = null,
    val cardId: CardId? = null,
)

data class DeckValidation(val issues: List<DeckIssue>) {
    val errors: List<DeckIssue> get() = issues.filter { it.severity == IssueSeverity.ERROR }
    val warnings: List<DeckIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }
    val isLegal: Boolean get() = errors.isEmpty()
}

/**
 * Checks a decklist against tournament rules.
 *
 * Takes a card lookup rather than a card list so it can also report on decks
 * containing passcodes the local database has not seen — that case is a real
 * error worth surfacing, not something to silently drop.
 */
object DeckValidator {

    /**
     * [asOf] (`yyyy-MM-dd`) checks each card's release against that day as well as its region (Phase B); without it
     * only the region is checked, so an OCG-only card is still caught.
     *
     * [limits] is the list the copies are held to (Phase B §3): by default the pool's own status in [format], or a
     * dated list ([BanSource], `LimitationList.match`), whose name goes into the words.
     */
    fun validate(
        deck: Deck,
        cards: (CardId) -> Card?,
        format: Format = Format.TCG,
        asOf: String? = null,
        limits: BanSource? = null,
    ): DeckValidation {
        val issues = mutableListOf<DeckIssue>()

        DeckSection.entries.forEach { section ->
            val contents = deck[section]
            if (contents.size < section.minSize) {
                issues += DeckIssue(
                    IssueSeverity.ERROR,
                    "${section.displayName} Deck has ${contents.size} cards, minimum is ${section.minSize}.",
                    section,
                )
            }
            if (contents.size > section.maxSize) {
                issues += DeckIssue(
                    IssueSeverity.ERROR,
                    "${section.displayName} Deck has ${contents.size} cards, maximum is ${section.maxSize}.",
                    section,
                )
            }
        }

        // Copy limits are counted across the whole deck and by card, not printing: an alternate artwork is the
        // same card (Phase B). Each card is walked once, from the first printing the deck holds.
        val all = deck.main + deck.extra + deck.side
        val seen = HashSet<CardId>()
        all.distinct().forEach { id ->
            val card = cards(id)

            if (card == null) {
                issues += DeckIssue(
                    IssueSeverity.ERROR,
                    "Passcode $id is not in the card database.",
                    cardId = id,
                )
                return@forEach
            }
            if (!seen.add(card.id)) return@forEach
            val copies = CardIdentity.copiesOf(deck, card)

            val limit = DeckEditor.copyLimit(card, format, limits)
            if (copies > limit) {
                val label = if (limit == 0) "is Forbidden" else "is limited to $limit"
                val on = limits?.label?.let { " on the $it" }.orEmpty()
                issues += DeckIssue(
                    IssueSeverity.ERROR,
                    "${card.name} $label$on, deck has $copies.",
                    cardId = id,
                )
            }

            if (card.isPlayable) Legality.problem(card, format, asOf)?.let {
                issues += DeckIssue(IssueSeverity.ERROR, it, cardId = id)
            }

            if (!card.isPlayable) {
                issues += DeckIssue(
                    IssueSeverity.ERROR,
                    "${card.name} cannot be included in a deck.",
                    cardId = id,
                )
            }

            // A card sitting in a section it is not allowed to occupy.
            DeckSection.entries.forEach { section ->
                if (deck[section].any { it in card.passcodes } && !DeckEditor.sectionAccepts(card, section)) {
                    issues += DeckIssue(
                        IssueSeverity.ERROR,
                        "${card.name} cannot be in the ${section.displayName} Deck.",
                        section,
                        id,
                    )
                }
            }
        }

        if (deck.main.size > 40 && deck.main.isNotEmpty()) {
            issues += DeckIssue(
                IssueSeverity.WARNING,
                "Main Deck is ${deck.main.size} cards; 40 maximises consistency.",
                DeckSection.MAIN,
            )
        }

        return DeckValidation(issues)
    }
}
