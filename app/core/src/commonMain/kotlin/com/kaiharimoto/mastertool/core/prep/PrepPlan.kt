package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.deck.DeckValidation
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.SideCoverage
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn

/**
 * Dates as the prep page keeps them — ISO `yyyy-mm-dd` strings — turned into
 * days, so a countdown needs no date library in `:core`. The arithmetic is the
 * proleptic Gregorian calendar's (Howard Hinnant's days-from-civil).
 */
object IsoDate {
    /** Days since 1970-01-01 for [iso] (`yyyy-mm-dd`, anything after the tenth character ignored), or null. */
    fun epochDay(iso: String?): Long? {
        val s = iso?.trim()?.take(10) ?: return null
        val parts = s.split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val d = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** The ISO date [day] days after 1970-01-01. */
    fun of(day: Long): String {
        val z = day + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** Days from [today] to [date]: negative once it has passed, null when either is not a date. */
    fun daysBetween(today: String?, date: String?): Long? {
        val a = epochDay(today) ?: return null
        val b = epochDay(date) ?: return null
        return b - a
    }

    /** "today", "tomorrow", "in 12 days", "3 days ago". */
    fun words(days: Long): String = when {
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days == -1L -> "yesterday"
        days > 1 -> "in $days days"
        else -> "${-days} days ago"
    }
}

/**
 * The weeks before an event as dates to meet, from the event's own date and
 * deadline: when to settle the Main Deck and the Side Deck (a week and three days
 * out, so the last practice is on the list that will be registered), when the
 * list is due, when to print it (two days out: there is no printer at the event),
 * and the day itself.
 */
object Countdown {
    data class Milestone(val id: String, val label: String, val date: String, val days: Long?)

    fun milestones(event: PrepEvent, today: String): List<Milestone> {
        val day = IsoDate.epochDay(event.date) ?: return emptyList()
        fun at(offset: Int) = IsoDate.of(day + offset)
        val list = buildList {
            add(Milestone("main", "Settle the Main Deck", at(-7), null))
            add(Milestone("side", "Settle the Side Deck and its plans", at(-3), null))
            event.deadline?.takeIf { IsoDate.epochDay(it) != null }?.let { add(Milestone("deadline", "Decklist due", it.take(10), null)) }
            if (Policy.decklistRequired(event.tier) && event.decklist == PrepEvent.DECKLIST_PAPER) {
                add(Milestone("print", "Print the decklist", at(-2), null))
            }
            add(Milestone("day", event.name.ifBlank { "The event" }, event.date.take(10), null))
        }
        return list.map { it.copy(days = IsoDate.daysBetween(today, it.date)) }.sortedBy { IsoDate.epochDay(it.date) }
    }
}

/**
 * Whether the deck is ready to register, in the order a judge would find out:
 * the list itself (the validator's errors, the format's Forbidden and Limited
 * List as the pool knows it today), then every siding plan — card for card,
 * naming only cards the deck holds, and short enough to make in three minutes.
 */
object EventCheck {
    data class Item(val ok: Boolean, val title: String, val detail: String = "", val warning: Boolean = false)

    /** Swaps past this many are hard to make, counted, in the three minutes siding allows (§VII.C). */
    const val SWAPS = 6

    /**
     * [isExtra] says which cards are Extra Deck cards: given, every plan is checked for the legal deck it leaves
     * ([SidingMath.legalAfter]); without it, only for its balance.
     */
    fun check(deck: Deck, validation: DeckValidation, siding: DeckSiding, tier: Int, isExtra: ((CardId) -> Boolean?)? = null): List<Item> = buildList {
        val errors = validation.errors
        add(
            Item(
                errors.isEmpty(),
                if (errors.isEmpty()) "The deck is legal" else "The deck is not legal",
                errors.joinToString("\n") { it.message }.ifEmpty { "${deck.main.size} Main · ${deck.extra.size} Extra · ${deck.side.size} Side, checked against today's list." },
            ),
        )
        validation.warnings.takeIf { it.isNotEmpty() }?.let { w ->
            add(Item(true, "Worth a look", w.joinToString("\n") { it.message }, warning = true))
        }
        val plans = siding.matchups.flatMap { m -> Turn.entries.map { t -> Triple(m, t, m.plan(t)) } }.filter { it.third.sided }
        if (plans.isEmpty()) {
            add(Item(true, "No siding plans yet", "Plan each matchup on the Siding page, then drill it here.", warning = true))
        } else {
            val uneven = plans.mapNotNull { (m, t, p) ->
                val problem = if (p.out.size != p.into.size) SidingMath.balanceWords(p) else isExtra?.let { SidingMath.legalAfter(deck, p, it) }
                problem?.let { "${m.name}, ${t.title.lowercase()}: $it" }
            }
            add(
                Item(
                    uneven.isEmpty(),
                    if (uneven.isEmpty()) "Every plan is card for card" else "Plans that are not card for card",
                    uneven.joinToString("\n").ifEmpty { "Siding is card for card and leaves a legal deck: the Main Deck 40 to 60, the Extra Deck at most 15 (§VII.C)." },
                ),
            )
            val stale = plans.filter { SidingMath.stale(deck, it.third).isNotEmpty() }
            add(
                Item(
                    stale.isEmpty(),
                    if (stale.isEmpty()) "Every plan names cards the deck holds" else "Plans naming cards the deck no longer holds",
                    stale.joinToString("\n") { (m, t, _) -> "${m.name}, ${t.title.lowercase()}" },
                ),
            )
            val long = plans.filter { it.third.out.size > SWAPS }
            if (long.isNotEmpty()) {
                add(
                    Item(
                        true,
                        "Long plans for three minutes",
                        long.joinToString("\n") { (m, t, p) -> "${m.name}, ${t.title.lowercase()}: ${p.out.size} swaps" },
                        warning = true,
                    ),
                )
            }
        }
        if (Policy.decklistRequired(tier)) add(Item(true, "A decklist is required", "Tier $tier: hand it in before the deadline; it cannot change after.", warning = true))
        if (Policy.sleevesRequired(tier)) add(Item(true, "Sleeves are required", "Identical across the Main and Side Decks.", warning = true))
    }

    /**
     * The Side Deck across the field as one line (Phase G, G.6): dead copies and the field with no plan, a warning; null when
     * every copy comes in against something and every opponent has a plan.
     */
    fun coverage(c: SideCoverage, name: (CardId) -> String): Item? {
        val dead = c.cards.filter { it.dead > 0 }
        if (dead.isEmpty() && c.unplanned.isEmpty()) return null
        val lines = listOfNotNull(
            c.deadWords()?.let { w -> "$w: " + dead.joinToString(", ") { "${it.dead} ${name(it.card)}" } + "." },
            c.unplanned.takeIf { it.isNotEmpty() }?.let { "No plan against ${it.joinToString()}: ${SideCoverage.pct(c.unplannedFirst)} of the field going first, ${SideCoverage.pct(c.unplannedSecond)} going second." },
        )
        return Item(true, "Side Deck coverage", lines.joinToString("\n"), warning = true)
    }
}

/** The deck as a decklist's lines: Monster, Spell and Trap columns, then Side and Extra. */
object Decklists {
    /** [ids] as lines, each card once with its count, in the order it first appears. */
    fun lines(ids: List<CardId>, card: (CardId) -> Card?): List<DecklistSheet.Line> =
        SidingMath.counted(ids).map { (id, n) -> DecklistSheet.Line(n, card(id)?.name ?: "#${id.value}") }

    fun content(
        deck: Deck,
        deckName: String,
        card: (CardId) -> Card?,
        profile: PrepProfile,
        event: PrepEvent?,
    ): DecklistSheet.Content {
        fun of(category: CardCategory) = lines(deck.main.filter { (card(it)?.category ?: CardCategory.MONSTER) == category }, card)
        // A card the pool does not know goes with the monsters: the list stays whole, and the sheet shows its passcode.
        val unknown = deck.main.filter { card(it) == null || card(it)?.category == CardCategory.OTHER }
        return DecklistSheet.Content(
            playerName = profile.name,
            cardGameId = profile.cardGameId,
            country = profile.country,
            eventName = event?.name.orEmpty(),
            eventDate = event?.date.orEmpty(),
            deckName = deckName,
            monsters = lines(deck.main.filter { card(it)?.category == CardCategory.MONSTER } + unknown, card),
            spells = of(CardCategory.SPELL),
            traps = of(CardCategory.TRAP),
            side = lines(deck.side, card),
            extra = lines(deck.extra, card),
        )
    }
}

/** What to bring and do on the day, by tier; each item has an id the event's `checked` list keeps. */
object Checklist {
    data class Entry(val id: String, val label: String)

    fun of(event: PrepEvent): List<Entry> = buildList {
        if (Policy.decklistRequired(event.tier)) {
            add(
                Entry(
                    "decklist",
                    when (event.decklist) {
                        PrepEvent.DECKLIST_NEURON -> "Decklist registered in NEURON"
                        PrepEvent.DECKLIST_ONLINE -> "Decklist submitted online"
                        else -> "Decklist printed and filled in"
                    },
                ),
            )
        }
        add(Entry("id", "Photo ID and your CARD GAME ID"))
        add(Entry("deck", "The deck, matching the registered list"))
        if (Policy.sleevesRequired(event.tier)) add(Entry("sleeves", "Sleeves, identical across Main and Side, and spares"))
        add(Entry("dice", "A die, a coin and counters"))
        add(Entry("tokens", "Tokens, clearly labelled"))
        add(Entry("paper", "Pen and paper for Life Points"))
        add(Entry("errata", "Printouts of any errata you rely on"))
        add(Entry("food", "Water and something to eat between rounds"))
        add(Entry("checkin", "Checked in on time"))
    }
}
