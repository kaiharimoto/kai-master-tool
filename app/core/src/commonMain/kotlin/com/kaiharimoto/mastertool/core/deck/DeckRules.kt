package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
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
 * One place decides, so the builder's ✓, its Legality drawer, every copy limit it enforces ([banSource]: adding,
 * dropping, the steppers, the card marks), Prep and Ai read the same answer.
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

    /** The default: the region's list today, as the builder checked before anything could be chosen. */
    val isDefault: Boolean get() = genesysCap == null && asOf == null

    /**
     * Where each card's copy limit comes from (1.1.1): none in Genesys (three of anything), else the dated list, else
     * the pool's. What the editor is handed, so a choice made in the drawer governs every add, drop and stepper too.
     */
    val banSource: BanSource get() = when {
        genesysCap != null -> BanSource.NONE
        else -> limits ?: BanSource.current(format)
    }

    /** [card]'s status under these rules. */
    fun statusOf(card: Card): BanStatus = banSource.statusOf(card)

    /** Copies of [card] these rules allow across the whole deck. */
    fun copyLimit(card: Card): Int = DeckEditor.copyLimit(card, format, banSource)

    /** The deck checked on [today] (`yyyy-MM-dd`) unless [asOf] names another day. */
    fun validate(deck: Deck, cards: (CardId) -> Card?, today: String): DeckValidation {
        val day = asOf ?: today
        val noted = listOfNotNull(note?.let { DeckIssue(IssueSeverity.WARNING, it) })
        val cap = genesysCap ?: return DeckValidator.validate(deck, cards, format, day, limits).let { DeckValidation(it.issues + noted) }
        val base = DeckValidator.validate(deck, cards, Format.TCG, day, BanSource.NONE)
        val points = GenesysRules.check(deck, cards, cap)
        val extra = buildList {
            if (points.points > cap) add(DeckIssue(IssueSeverity.ERROR, "The deck costs ${points.points} Genesys points; the cap is $cap."))
            points.barred.forEach { (id, name) ->
                val kind = cards(id)?.let(GenesysRules::barredKind)
                // The section it is in, so the drawer can show it.
                val section = DeckSection.entries.firstOrNull { id in deck[it] }
                add(DeckIssue(IssueSeverity.ERROR, GenesysRules.barredWords(name, kind), section, id))
            }
        }
        return DeckValidation(base.issues + extra + noted)
    }

    /** The deck's Genesys points and the cap, when this is Genesys. */
    fun points(deck: Deck, cards: (CardId) -> Card?): GenesysRules.Result? = genesysCap?.let { GenesysRules.check(deck, cards, it) }

    /**
     * The rules in words, for the drawer and Ai: "TCG", "TCG on 1 May 2025, by the April 2025 Lists (TCG)",
     * "Genesys, 100 points".
     */
    fun words(): String = when {
        genesysCap != null -> "Genesys, $genesysCap points" + (asOf?.let { " on ${Legality.readable(it)}" } ?: "")
        asOf == null -> format.name
        else -> "${format.name} on ${Legality.readable(asOf)}" + (listName?.let { ", by the $it" } ?: "")
    }

    /** The rules for a bar, no list named (finding 9): "TCG", "TCG · 1 May 2025", "Genesys 100", "Genesys 92/100". */
    fun short(points: Int? = null): String {
        val day = asOf?.let { " · ${Legality.readable(it)}" }.orEmpty()
        return if (genesysCap != null) "Genesys ${points?.let { "$it/" }.orEmpty()}$genesysCap$day" else "${format.name}$day"
    }

    /**
     * What the builder's ✓ says beside itself, null for [isDefault] (the region beside it already says it): "1 May 2025",
     * "Genesys 92/100". A forgotten choice is exactly when the words are needed.
     */
    fun tag(points: Int? = null): String? = when {
        genesysCap != null -> "Genesys ${points?.let { "$it/" }.orEmpty()}$genesysCap" + (asOf?.let { " · ${Legality.readable(it)}" }.orEmpty())
        asOf != null -> Legality.readable(asOf)
        else -> null
    }

    /** "April 2005 list" for "April 2005 Lists (TCG)"; the region when the pool's own list is in force. */
    val listWord: String get() = listName?.let { name -> name.substringBefore(" Lists", name).trim() + " list" } ?: format.name

    /** Where one card stands, in one line: what it says, and whether it keeps the card out of the deck. */
    data class Standing(val words: String, val blocked: Boolean)

    /**
     * [card]'s standing under these rules on [today] unless [asOf] names another day (finding 7): "Not in the TCG
     * until 8 Oct 2026", "Forbidden · April 2005 list", "Limited · TCG", "Genesys · 50 points", "Not in Genesys · Link
     * monster". Whether it is out yet comes first, so a card not released never also reads "Unlimited".
     */
    fun standing(card: Card, today: String): Standing {
        val region = if (genesysCap != null) Format.TCG else format
        when (val release = Legality.release(card, region, asOf ?: today)) {
            is Legality.Release.NotReleased -> return Standing("Not in the ${Legality.word(region)}", true)
            is Legality.Release.NotYet -> return Standing("Not in the ${Legality.word(region)} until ${Legality.readable(release.date)}", true)
            else -> Unit
        }
        if (genesysCap != null) {
            GenesysRules.barredKind(card)?.let { return Standing("Not in Genesys · $it monster", true) }
            val p = card.genesysPoints
            return Standing("Genesys · " + (p?.let { "$it point${if (it == 1) "" else "s"}" } ?: "points unknown"), false)
        }
        val status = statusOf(card)
        return Standing("${statusWord(status)} · $listWord", status == BanStatus.FORBIDDEN)
    }

    companion object {
        /** "Forbidden", "Limited", "Semi-Limited", "Unlimited": the list's own words. */
        fun statusWord(status: BanStatus): String = when (status) {
            BanStatus.FORBIDDEN -> "Forbidden"
            BanStatus.LIMITED -> "Limited"
            BanStatus.SEMI_LIMITED -> "Semi-Limited"
            BanStatus.UNLIMITED -> "Unlimited"
        }
    }
}
