package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.text.DuelNotation

/**
 * Several cards, one action (1.0.89, kai: "let me select multiple cards on the field, graveyard, hand, and across graveyard
 * and banished and perform an action with them. if put to the bottom of the deck or top of the deck, I can choose the
 * order").
 *
 * A selection is a list of uids **in the order they were picked**: the badges on the table ("1/3"), the bar that lists them
 * and the order they go onto a Deck all read it. What it can do is what every card in it can do ([verbs]), and doing it is
 * one group in the log — one undo ([actions]). Pure: the page keeps the list, this says what it means.
 *
 * Privacy: a card the eyes the table is drawn through cannot see is offered only the verbs that need not know what it is,
 * decided by where it lies alone ([blindVerbs]) — never by the catalog — so a selection's menu says nothing of a face-down
 * card; and it is named by where it is ([label]), never by name.
 */
object DuelSelection {

    /**
     * The verbs that make sense on several cards at once, in the order a menu lists them. Not the default (each card's
     * differs), not an attack (one monster at a time), not a move (each needs its zone), not counters off.
     */
    val MANY: List<DuelVerb> = listOf(
        DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.BANISH_DOWN, DuelVerb.HAND,
        DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.DECK_SHUFFLE, DuelVerb.EXTRA,
        DuelVerb.TARGET, DuelVerb.ATTACH, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.ACTIVATE,
        DuelVerb.FLIP, DuelVerb.POSITION, DuelVerb.REVEAL, DuelVerb.DETACH, DuelVerb.COUNTER_UP,
    )

    /** The verbs that put the selection on a Deck in an order the person chooses ([toDeck]). */
    val ORDERED: Set<DuelVerb> = setOf(DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM)

    /**
     * What a card the eyes cannot see takes, by where it lies alone: the verbs that need not know what it is (the Line's
     * rule for a hidden card, 1.0.87). A face-down card on the field can be flipped; one in a pile cannot be read.
     */
    fun blindVerbs(s: DuelState, uid: Int): Set<DuelVerb> {
        val away = setOf(DuelVerb.TARGET, DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.BANISH_DOWN, DuelVerb.HAND, DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.DECK_SHUFFLE)
        return when (val p = s.placeOf(uid)) {
            is Place.Zone -> away + setOf(DuelVerb.FLIP, DuelVerb.ATTACH, DuelVerb.COUNTER_UP)
            is Place.Pile -> away - when (p.kind) {
                PileKind.HAND -> DuelVerb.HAND
                PileKind.GY -> DuelVerb.GRAVE
                PileKind.BANISHED -> DuelVerb.BANISH
                else -> DuelVerb.TARGET
            }
            is Place.Under -> setOf(DuelVerb.DETACH, DuelVerb.HAND, DuelVerb.BANISH, DuelVerb.DECK_TOP)
            else -> emptySet()
        }
    }

    /**
     * The verbs every card of [uids] takes, in [MANY]'s order. [seatFor] is the seat acting on a card, [plays] whether the
     * person plays it or only points at it (Target alone), [sees] whether the table's eyes see it (a card they do not is
     * offered [blindVerbs] only). Fewer than two cards: nothing — one card's verbs are the verb strip's.
     */
    fun verbs(
        s: DuelState,
        uids: List<Int>,
        catalog: DuelCatalog,
        seatFor: (Int) -> Int,
        plays: (Int) -> Boolean = { true },
        sees: (Int) -> Boolean = { true },
    ): List<DuelVerb> {
        val live = uids.filter { it in s.cards }
        if (live.size < 2) return emptyList()
        var common: Set<DuelVerb> = MANY.toSet()
        live.forEach { u ->
            val own = when {
                !plays(u) -> setOf(DuelVerb.TARGET)
                !sees(u) -> blindVerbs(s, u)
                else -> DuelVerbs.offered(s, seatFor(u), u, catalog).toSet()
            }
            common = common intersect own
        }
        return MANY.filter { it in common }
    }

    /** What [verb] on the selection came to: the actions as one group, and the cards it left alone with why. */
    data class Plan(val actions: List<DuelAction>, val skipped: List<Pair<Int, String>> = emptyList(), val needsHost: Boolean = false) {
        val ok: Boolean get() = actions.isNotEmpty()
    }

    /**
     * [verb] on every card of [uids], in order, each on the table the one before left — one group. The Deck's top and
     * bottom go in the selection's order, top first ([toDeck]); shuffled in, every card goes and each Deck is shuffled once;
     * Target is one arrow to each card ([seat]'s); Reveal one reveal; Attach goes under [host] (the selection's own cards
     * stay out of it). A card the verb cannot take is skipped and said in [Plan.skipped]; the rest still go.
     */
    fun actions(
        s: DuelState,
        uids: List<Int>,
        verb: DuelVerb,
        catalog: DuelCatalog,
        seat: Int,
        seatFor: (Int) -> Int = { seat },
        host: Int? = null,
        /** Whether the eyes acting see a card: one they do not is never sorted by what it is. */
        sees: (Int) -> Boolean = { true },
    ): Plan {
        val live = uids.distinct().filter { it in s.cards }
        if (live.isEmpty()) return Plan(emptyList())
        if (verb == DuelVerb.DECK_TOP || verb == DuelVerb.DECK_BOTTOM || verb == DuelVerb.DECK_SHUFFLE) {
            val (fit, not) = deckable(s, live, catalog, sees)
            val skipped = not.map { it to notToDeck(s, it, catalog) }
            return when (verb) {
                DuelVerb.DECK_SHUFFLE -> Plan(shuffledIn(s, fit), skipped)
                else -> Plan(toDeck(s, fit, bottom = verb == DuelVerb.DECK_BOTTOM), skipped)
            }
        }
        when (verb) {
            DuelVerb.TARGET -> return Plan(listOf(DuelAction.Target(seat, null, live)))
            DuelVerb.REVEAL -> return Plan(listOf(DuelAction.Reveal(seat, live)))
            DuelVerb.ATTACH -> {
                val h = host ?: return Plan(emptyList(), needsHost = true)
                return Plan(live.filter { it != h }.map { DuelAction.Move(it, Place.Under(h), how = "attach") })
            }
            else -> Unit
        }
        var st = s
        val out = mutableListOf<DuelAction>()
        val skipped = mutableListOf<Pair<Int, String>>()
        live.forEach { u ->
            val r = DuelVerbs.actions(st, seatFor(u), u, verb, catalog)
            when {
                r.needsTarget || r.needsHost -> skipped += u to "one at a time"
                r.problem != null -> skipped += u to r.problem
                else -> {
                    val next = DuelRules.applyAll(st, r.actions).first
                    if (next == null) skipped += u to "the table refused it" else {
                        out += r.actions
                        st = next
                    }
                }
            }
        }
        return Plan(out, skipped)
    }

    /**
     * The cards of [uids] that go into a Deck, and those that do not: a token (it leaves the duel) and an Extra Deck monster (it
     * goes back to the Extra Deck) — the single card's verbs offer neither the Deck. A card the eyes do not see is never sorted
     * by what it is: it goes with the rest.
     */
    fun deckable(s: DuelState, uids: List<Int>, catalog: DuelCatalog, sees: (Int) -> Boolean = { true }): Pair<List<Int>, List<Int>> =
        uids.partition { u ->
            val c = s.cards[u] ?: return@partition false
            !sees(u) || (!c.token && DuelVerbs.kindOf(c, catalog) != CardKind.EXTRA_MONSTER)
        }

    private fun notToDeck(s: DuelState, uid: Int, catalog: DuelCatalog): String =
        if (s.cards[uid]?.token == true) "a token leaves the duel" else "${catalog.nameOf(s.cards.getValue(uid))} goes to the Extra Deck, not the Deck"

    /**
     * [order] onto the Deck, read **top first** — the order the cards will stand in it, which is how the ordering strip
     * writes it ("Top of the Deck, top first: 1 Ash · 2 Droll"). On top: [order]'s first card ends on top, the second
     * under it. On the bottom: the last card of [order] ends as the bottom card, the first above it. Each card goes to its
     * owner's Deck, face-down.
     */
    fun toDeck(s: DuelState, order: List<Int>, bottom: Boolean): List<DuelAction> {
        val live = order.distinct().filter { it in s.cards }
        val placed = if (bottom) live else live.asReversed()
        return placed.map { u -> DuelAction.Move(u, Place.Pile(s.cards.getValue(u).owner, PileKind.DECK, if (bottom) Place.BOTTOM else Place.TOP), how = "return") }
    }

    /** [order] onto the Deck in an order chance picks (1.0.89's "Random order"), stamped on commit as a [DuelAction.Pick]. */
    fun randomToDeck(s: DuelState, seat: Int, order: List<Int>, bottom: Boolean): DuelAction.Pick {
        val live = order.distinct().filter { it in s.cards }
        return DuelAction.Pick(seat, Place.Pile(seat, PileKind.DECK, if (bottom) Place.BOTTOM else Place.TOP), among = live, n = live.size, how = "return")
    }

    /** Every card of [order] into its owner's Deck, then each Deck it went into shuffled once — its place never seen. */
    fun shuffledIn(s: DuelState, order: List<Int>): List<DuelAction> {
        val live = order.distinct().filter { it in s.cards }
        val moves = live.map { u -> DuelAction.Move(u, Place.Pile(s.cards.getValue(u).owner, PileKind.DECK, Place.TOP), how = "shuffle") }
        val decks = live.map { s.cards.getValue(it).owner }.distinct()
        return moves + decks.map { DuelAction.Shuffle(it, PileKind.DECK) }
    }

    /** [order] with the card at [from] moved to [to] (both clamped): the ordering strip's drag and Alt ←/→. */
    fun reorder(order: List<Int>, from: Int, to: Int): List<Int> {
        if (order.isEmpty()) return order
        val i = from.coerceIn(0, order.size - 1)
        val j = to.coerceIn(0, order.size - 1)
        if (i == j) return order
        val m = order.toMutableList()
        val u = m.removeAt(i)
        m.add(j, u)
        return m
    }

    /** [selection] with [uid] in it, or out of it when it was: a Ctrl-click, a tap in select mode, Shift Space. */
    fun toggle(selection: List<Int>, uid: Int): List<Int> = if (uid in selection) selection - uid else selection + uid

    /**
     * The cards that share a row with [uid] as the table lays them, in that row's order: a hand (in [hand]'s order for its
     * seat — the order the eyes are shown it), an open pile top first, a seat's Monster Zones or its Spell & Trap Zones left
     * to right. Null for anywhere else.
     */
    fun rowOf(s: DuelState, uid: Int, hand: (Int) -> List<Int> = { s.seats[it].hand }): List<Int>? = when (val p = s.placeOf(uid)) {
        is Place.Pile -> if (p.kind == PileKind.HAND) hand(p.seat) else s.seats[p.seat].pile(p.kind)
        is Place.Zone -> when (p.kind) {
            ZoneKind.MONSTER -> s.seats[p.seat].monsters.filterNotNull()
            ZoneKind.SPELL -> s.seats[p.seat].spells.filterNotNull()
            else -> null
        }
        else -> null
    }

    /**
     * A Shift-click (1.0.89): every card from [anchor] to [uid] in the row they share ([rowOf]) added to [selection], in
     * the row's order from the anchor; when they share none, [uid] toggled.
     */
    fun range(s: DuelState, selection: List<Int>, anchor: Int?, uid: Int, hand: (Int) -> List<Int> = { s.seats[it].hand }): List<Int> {
        val row = anchor?.let { rowOf(s, it, hand) }
        if (anchor == null || row == null || uid !in row || anchor !in row) return toggle(selection, uid)
        val a = row.indexOf(anchor)
        val b = row.indexOf(uid)
        val run = if (a <= b) row.subList(a, b + 1) else row.subList(b, a + 1).asReversed()
        return (selection + run).distinct()
    }

    /**
     * A selected card in words for [viewer]'s eyes: its name when [sees], else where it is — "the face-down card in os2",
     * "the card at oh3" — never what it is. [coord] is the notation's coordinate, or null when it has none.
     */
    fun label(s: DuelState, uid: Int, sees: Boolean, catalog: DuelCatalog, viewer: Int, secret: Long = 0L): Label {
        val c = s.cards[uid] ?: return Label("a card", null, false)
        val coord = DuelNotation.coordOf(s, uid, viewer, secret)
        if (sees) return Label(catalog.nameOf(c), coord, true)
        val words = when (s.placeOf(uid)) {
            is Place.Zone -> "a face-down card"
            is Place.Pile -> "a hidden card"
            else -> "a card"
        }
        return Label(words, coord, false)
    }

    /** A selected card's words: [name] (or "a face-down card"), its [coord], and whether the name is its own. */
    data class Label(val name: String, val coord: String?, val named: Boolean) {
        override fun toString(): String = coord?.let { "$name · $it" } ?: name
    }

    /** "Top of the Deck, top first" or the bottom's: the ordering strip's head. */
    fun orderHead(bottom: Boolean): String = if (bottom) "Bottom of the Deck, top first" else "Top of the Deck, top first"
}
