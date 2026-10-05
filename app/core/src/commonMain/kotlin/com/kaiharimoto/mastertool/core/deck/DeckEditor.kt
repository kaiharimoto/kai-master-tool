package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import kotlin.math.min

/** Why an edit could not be applied. Each maps to a message the UI can show. */
enum class RejectionReason {
    /** The section is already at its maximum size. */
    SECTION_FULL,

    /** Adding would exceed the copy limit for this card across the whole deck. */
    COPY_LIMIT,

    /** Extra Deck cards cannot go in the Main Deck, and vice versa. */
    WRONG_SECTION,

    /** Tokens and Skill Cards are not deck cards. */
    NOT_PLAYABLE,

    /** The card was not present in the section being removed from. */
    NOT_PRESENT,
}

/**
 * The outcome of an edit. [Applied] always carries a deck that satisfies every
 * rule, so callers can store it without re-validating.
 */
sealed interface DeckEdit {
    data class Applied(val deck: Deck) : DeckEdit
    data class Rejected(val reason: RejectionReason, val card: Card? = null) : DeckEdit
}

/**
 * Every mutation of a decklist goes through here.
 *
 * The functions are pure: they take a deck and return a new one. Keeping the
 * rules in one side-effect-free place is what makes undo/redo, drag-and-drop and
 * tap-to-add provably agree with each other instead of each re-implementing the
 * limits slightly differently.
 */
object DeckEditor {

    /**
     * Copies of [card] allowed across main + extra + side combined: by [limits] when given — the rules the person
     * chose, a dated list or Genesys's none (`DeckRules.banSource`, 1.1.1) — else the pool's list in [format].
     */
    fun copyLimit(card: Card, format: Format, limits: BanSource? = null): Int =
        min(Deck.MAX_COPIES, (limits?.statusOf(card) ?: card.banStatus(format)).maxCopies)

    /** Whether [card] is allowed to live in [section] at all. */
    fun sectionAccepts(card: Card, section: DeckSection): Boolean = when (section) {
        // The side deck holds both main-deck and extra-deck cards.
        DeckSection.SIDE -> true
        DeckSection.EXTRA -> card.isExtraDeck
        DeckSection.MAIN -> !card.isExtraDeck
    }

    fun add(
        deck: Deck,
        card: Card,
        section: DeckSection = card.requiredSection(),
        format: Format = Format.TCG,
        limits: BanSource? = null,
    ): DeckEdit = addAt(deck, card, section, deck[section].size, format, limits)

    /**
     * Adds a copy at an exact position, for a drop onto a particular slot.
     *
     * [add] delegates here so the two cannot drift: a card dropped between two
     * others has to be checked against the banlist and the section's capacity in
     * exactly the same way as one added by tapping.
     */
    fun addAt(
        deck: Deck,
        card: Card,
        section: DeckSection,
        index: Int,
        format: Format = Format.TCG,
        limits: BanSource? = null,
    ): DeckEdit {
        if (!card.isPlayable) return DeckEdit.Rejected(RejectionReason.NOT_PLAYABLE, card)
        if (!sectionAccepts(card, section)) {
            return DeckEdit.Rejected(RejectionReason.WRONG_SECTION, card)
        }

        val contents = deck[section]
        if (contents.size >= section.maxSize) {
            return DeckEdit.Rejected(RejectionReason.SECTION_FULL, card)
        }

        val limit = copyLimit(card, format, limits)
        // By card, not printing: an alternate artwork is the same card (Phase B).
        if (CardIdentity.copiesOf(deck, card) >= limit) {
            return DeckEdit.Rejected(RejectionReason.COPY_LIMIT, card)
        }

        val at = index.coerceIn(0, contents.size)
        return DeckEdit.Applied(
            deck.with(section, contents.toMutableList().also { it.add(at, card.id) })
        )
    }

    /** Removes a single copy. Removes the last one so repeated taps feel stable. */
    fun remove(deck: Deck, id: CardId, section: DeckSection): DeckEdit {
        val contents = deck[section]
        val index = contents.lastIndexOf(id)
        if (index < 0) return DeckEdit.Rejected(RejectionReason.NOT_PRESENT)
        return DeckEdit.Applied(
            deck.with(section, contents.toMutableList().also { it.removeAt(index) })
        )
    }

    /** Removes the copy at an exact position, for drag-out gestures. */
    fun removeAt(deck: Deck, section: DeckSection, index: Int): DeckEdit {
        val contents = deck[section]
        if (index !in contents.indices) return DeckEdit.Rejected(RejectionReason.NOT_PRESENT)
        return DeckEdit.Applied(
            deck.with(section, contents.toMutableList().also { it.removeAt(index) })
        )
    }

    /**
     * Moves one copy between sections, e.g. dragging a card from main to side.
     *
     * Performed as a single transaction so a move that would be rejected at the
     * destination leaves the source untouched.
     */
    fun move(
        deck: Deck,
        card: Card,
        from: DeckSection,
        to: DeckSection,
        format: Format = Format.TCG,
    ): DeckEdit {
        if (from == to) return DeckEdit.Applied(deck)
        if (!sectionAccepts(card, to)) {
            return DeckEdit.Rejected(RejectionReason.WRONG_SECTION, card)
        }
        if (deck[to].size >= to.maxSize) {
            return DeckEdit.Rejected(RejectionReason.SECTION_FULL, card)
        }

        val removed = remove(deck, card.id, from)
        if (removed !is DeckEdit.Applied) return removed

        // The copy count is unchanged by a move, so the banlist cannot reject it.
        val target = removed.deck[to]
        return DeckEdit.Applied(removed.deck.with(to, target + card.id))
    }

    /** Reorders within a section, for drag-to-arrange. */
    fun reorder(deck: Deck, section: DeckSection, fromIndex: Int, toIndex: Int): DeckEdit {
        val contents = deck[section]
        if (fromIndex !in contents.indices || toIndex !in contents.indices) {
            return DeckEdit.Rejected(RejectionReason.NOT_PRESENT)
        }
        val mutable = contents.toMutableList()
        mutable.add(toIndex, mutable.removeAt(fromIndex))
        return DeckEdit.Applied(deck.with(section, mutable))
    }

    /**
     * Moves the copy at [fromIndex] so it lands before whatever currently sits at
     * [insertBefore].
     *
     * This is the form a drop position actually arrives in: the resolver reports
     * a gap in the list as it looks right now, before anything has been picked
     * up. Removing the dragged card first shifts everything after it down one, so
     * a drop to the right needs that accounted for — doing it here means the
     * caller never has to know, and the off-by-one has one home instead of one
     * per call site.
     */
    fun reorderTo(
        deck: Deck,
        section: DeckSection,
        fromIndex: Int,
        insertBefore: Int,
    ): DeckEdit {
        val contents = deck[section]
        if (fromIndex !in contents.indices) return DeckEdit.Rejected(RejectionReason.NOT_PRESENT)
        if (insertBefore !in 0..contents.size) {
            return DeckEdit.Rejected(RejectionReason.NOT_PRESENT)
        }

        val target = if (insertBefore > fromIndex) insertBefore - 1 else insertBefore
        if (target == fromIndex) return DeckEdit.Applied(deck)

        val mutable = contents.toMutableList()
        mutable.add(target, mutable.removeAt(fromIndex))
        return DeckEdit.Applied(deck.with(section, mutable))
    }

    /**
     * Moves one copy to an exact position in another section.
     *
     * [move] appends, which is right for a button but wrong for a drag: a card
     * dropped onto a particular slot has to land there. Performed as a single
     * transaction, so a move the destination rejects leaves the source untouched.
     */
    fun moveAt(
        deck: Deck,
        card: Card,
        from: DeckSection,
        fromIndex: Int,
        to: DeckSection,
        insertBefore: Int,
        format: Format = Format.TCG,
    ): DeckEdit {
        if (from == to) return reorderTo(deck, from, fromIndex, insertBefore)

        val source = deck[from]
        if (fromIndex !in source.indices || source[fromIndex] != card.id) {
            return DeckEdit.Rejected(RejectionReason.NOT_PRESENT)
        }
        if (!sectionAccepts(card, to)) {
            return DeckEdit.Rejected(RejectionReason.WRONG_SECTION, card)
        }
        if (deck[to].size >= to.maxSize) {
            return DeckEdit.Rejected(RejectionReason.SECTION_FULL, card)
        }

        val removed = removeAt(deck, from, fromIndex)
        if (removed !is DeckEdit.Applied) return removed

        // The copy count is unchanged by a move, so the banlist cannot reject it.
        val target = removed.deck[to]
        val at = insertBefore.coerceIn(0, target.size)
        return DeckEdit.Applied(
            removed.deck.with(to, target.toMutableList().also { it.add(at, card.id) })
        )
    }

    /**
     * Sets the number of copies in a section directly, which is what a stepper
     * control on a tablet drives. Clamps to whatever the rules permit rather
     * than rejecting, so dragging a stepper never dead-ends.
     */
    fun setCount(
        deck: Deck,
        card: Card,
        section: DeckSection,
        count: Int,
        format: Format = Format.TCG,
        limits: BanSource? = null,
    ): DeckEdit {
        if (!card.isPlayable) return DeckEdit.Rejected(RejectionReason.NOT_PLAYABLE, card)
        if (!sectionAccepts(card, section)) {
            return DeckEdit.Rejected(RejectionReason.WRONG_SECTION, card)
        }

        val contents = deck[section]
        val currentHere = contents.count { it == card.id }
        val elsewhere = CardIdentity.copiesOf(deck, card) - currentHere
        val roomInSection = section.maxSize - (contents.size - currentHere)
        val allowedByBanlist = copyLimit(card, format, limits) - elsewhere

        val target = count.coerceIn(0, minOf(roomInSection, allowedByBanlist).coerceAtLeast(0))

        val without = contents.filterNot { it == card.id }
        // Re-insert at the original position so the grid does not jump around.
        val insertAt = contents.indexOfFirst { it == card.id }.takeIf { it >= 0 } ?: without.size
        val rebuilt = without.toMutableList().apply {
            addAll(insertAt.coerceAtMost(size), List(target) { card.id })
        }

        return DeckEdit.Applied(deck.with(section, rebuilt))
    }

    /** Removes every copy of a card from every section. */
    fun removeAll(deck: Deck, id: CardId): DeckEdit =
        DeckEdit.Applied(
            DeckSection.entries.fold(deck) { acc, section ->
                acc.with(section, acc[section].filterNot { it == id })
            }
        )

    /** Convenience for showing a "3 / 3" style badge next to a search result. */
    fun remainingCopies(deck: Deck, card: Card, format: Format = Format.TCG, limits: BanSource? = null): Int =
        (copyLimit(card, format, limits) - CardIdentity.copiesOf(deck, card)).coerceAtLeast(0)

    /** True when the card is banned outright and should be shown as unusable. */
    fun isForbidden(card: Card, format: Format = Format.TCG, limits: BanSource? = null): Boolean =
        (limits?.statusOf(card) ?: card.banStatus(format)) == BanStatus.FORBIDDEN
}
