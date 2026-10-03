package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.ASH
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.MIRROR
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.POT
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.ok
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.KeyChord
import com.kaiharimoto.mastertool.core.layout.DuelFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 1.0.89, kai: "consider the chain system and how we can use it better with a keyboard. Also, let me select multiple cards
 * on the field, graveyard, hand, and across graveyard and banished and perform an action with them. if put to the bottom of
 * the deck or top of the deck, I can choose the order".
 */
class DuelSelectionTest {
    private val s = battle()
    private fun at(c: String, st: DuelState = s, seat: Int = 0) = DuelNotation.at(st, c, seat, 0L) ?: error("nothing at $c")
    private fun apply(st: DuelState, actions: List<DuelAction>, by: Int? = 0): DuelState =
        DuelRules.applyAll(st, actions, by).let { (next, why) -> next ?: error("refused: $why") }
    private fun parse(text: String, st: DuelState = s) = DuelCommand.parse(text, st, 0, catalog)
    private fun actions(text: String, st: DuelState = s): List<DuelAction> = when (val p = parse(text, st)) {
        is DuelCommand.Parsed.Actions -> p.actions
        else -> fail("“$text”: $p")
    }

    /** Battle, plus a Pot of Prosperity in Kai's banished pile and a Kuriboh in the GY above Droll. */
    private fun piles(): DuelState {
        var st = s
        st = ok(st, DuelAction.Move(at("h3"), Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "banish"))
        st = ok(st, DuelAction.Move(uid(0, 11), Place.Pile(0, PileKind.GY), how = "send"))
        return st
    }

    // ---- what several cards can do together ------------------------------------------------------------------

    @Test
    fun theVerbsAreWhatEveryCardTakes() {
        val hand = listOf(at("h1"), at("h2"))
        val v = DuelSelection.verbs(s, hand, catalog, { 0 })
        listOf(DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.DECK_SHUFFLE).forEach { assertTrue(it in v, "$it in $v") }
        assertFalse(DuelVerb.HAND in v, "they are in the hand already")
        // Across the GY and the banished pile: neither "to the GY" nor "banish" fits both, the Deck and the hand do.
        val st = piles()
        val across = listOf(at("gy1", st), at("ban1", st))
        val w = DuelSelection.verbs(st, across, catalog, { 0 })
        assertFalse(DuelVerb.GRAVE in w)
        assertFalse(DuelVerb.BANISH in w)
        listOf(DuelVerb.HAND, DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.DECK_SHUFFLE).forEach { assertTrue(it in w, "$it in $w") }
        // One card is the verb strip's, not the selection's.
        assertTrue(DuelSelection.verbs(s, listOf(at("h1")), catalog, { 0 }).isEmpty())
        // Never the default, an attack or a move: each card's differs, or needs its own aim.
        assertTrue(v.none { it == DuelVerb.DEFAULT || it == DuelVerb.ATTACK || it == DuelVerb.MOVE })
    }

    @Test
    fun aHiddenCardsVerbsSayNothingOfIt() {
        // Rival's set card is Mirror Force; the same table with it any other card offers the same verbs.
        val hidden = at("os1")
        val mine = at("m1")
        val sees = { u: Int -> DuelSight.sees(s, u, 0) }
        val v = DuelSelection.verbs(s, listOf(mine, hidden), catalog, { s.cards.getValue(it).controller }, sees = sees)
        assertTrue(v.all { it in DuelSelection.blindVerbs(s, hidden) }, "$v")
        val swapped = s.copy(cards = s.cards + (hidden to s.cards.getValue(hidden).copy(code = CommandFixtures.ZEUS)))
        assertEquals(v, DuelSelection.verbs(swapped, listOf(mine, hidden), catalog, { swapped.cards.getValue(it).controller }, sees = { DuelSight.sees(swapped, it, 0) }))
        // And its words are where it is, never its name.
        val label = DuelSelection.label(s, hidden, sees(hidden), catalog, 0)
        assertFalse(label.named)
        assertFalse("Mirror" in label.toString(), label.toString())
        assertEquals("os1", label.coord)
    }

    @Test
    fun oneVerbOnSeveralIsOneGroup() {
        val st = piles()
        val picked = listOf(at("h1", st), at("gy1", st), at("ban1", st))
        val plan = DuelSelection.actions(st, picked, DuelVerb.DECK_SHUFFLE, catalog, 0)
        assertTrue(plan.ok)
        assertEquals(3, plan.actions.count { it is DuelAction.Move })
        assertEquals(1, plan.actions.count { it is DuelAction.Shuffle }, "each Deck is shuffled once")
        val after = apply(st, plan.actions)
        assertTrue(picked.all { u -> after.placeOf(u).let { it is Place.Pile && it.kind == PileKind.DECK } })
        // As one group of the log: one undo takes all of it back.
        val g0 = DuelGame(DuelHeader(id = "g", seed = 1L, seats = listOf(SeatSetup("Kai"), SeatSetup("Rival"))), emptyList(), 0, st, 0)
        val g1 = g0.act(plan.actions, 0, 1L)
        assertTrue(g1.ok)
        assertEquals(1, g1.game.entries.map { it.group }.distinct().size)
        // A card the verb cannot take is left, and said; the rest go.
        val mixed = DuelSelection.actions(st, listOf(at("gy1", st), at("h1", st)), DuelVerb.GRAVE, catalog, 0)
        assertEquals(1, mixed.actions.size)
        assertEquals(at("gy1", st), mixed.skipped.single().first)
    }

    @Test
    fun targetIsOneArrowToEachAndAttachGoesUnderTheHost() {
        val t = DuelSelection.actions(s, listOf(at("om1"), at("os1")), DuelVerb.TARGET, catalog, 0).actions.single()
        assertEquals(DuelAction.Target(0, null, listOf(at("om1"), at("os1"))), t)
        val host = at("m1")
        val a = DuelSelection.actions(s, listOf(at("h1"), at("h2"), host), DuelVerb.ATTACH, catalog, 0, host = host)
        assertEquals(2, a.actions.size, "the host is not put under itself")
        assertTrue(DuelSelection.actions(s, listOf(at("h1"), at("h2")), DuelVerb.ATTACH, catalog, 0).needsHost)
    }

    // ---- their order on the Deck -----------------------------------------------------------------------------

    @Test
    fun ontoTheDeckTopFirstAsTheyWillStand() {
        val a = at("h1")
        val b = at("h3")
        val c = at("h4")
        val top = apply(s, DuelSelection.toDeck(s, listOf(a, b, c), bottom = false))
        assertEquals(listOf(a, b, c), top.seats[0].deck.take(3), "the first is the Deck's new top card")
        val bottom = apply(s, DuelSelection.toDeck(s, listOf(a, b, c), bottom = true))
        assertEquals(listOf(a, b, c), bottom.seats[0].deck.takeLast(3), "the last is the Deck's bottom card")
        // Zeus (an Extra Deck monster) is left out of the Deck, and said.
        val zeus = at("e1")
        val plan = DuelSelection.actions(s, listOf(a, zeus), DuelVerb.DECK_TOP, catalog, 0)
        assertEquals(listOf(zeus), plan.skipped.map { it.first })
        assertEquals(1, plan.actions.size)
        // Their own Deck each: a card of theirs goes to theirs.
        val theirs = at("om1")
        val mixed = apply(s, DuelSelection.toDeck(s, listOf(theirs, a), bottom = false))
        assertEquals(a, mixed.seats[0].deck.first())
        assertEquals(theirs, mixed.seats[1].deck.first())
    }

    @Test
    fun theOrderIsReorderedAndChanceCanPickIt() {
        assertEquals(listOf(2, 1, 3), DuelSelection.reorder(listOf(1, 2, 3), 1, 0))
        assertEquals(listOf(2, 3, 1), DuelSelection.reorder(listOf(1, 2, 3), 0, 9))
        val order = listOf(at("h1"), at("h3"), at("h4"))
        val pick = DuelSelection.randomToDeck(s, 0, order, bottom = true)
        assertEquals(order, pick.among)
        assertEquals(3, pick.n)
        assertEquals(Place.Pile(0, PileKind.DECK, Place.BOTTOM), pick.to)
        val g = DuelGame(DuelHeader(id = "g", seed = 3L, seats = listOf(SeatSetup("Kai"), SeatSetup("Rival"))), emptyList(), 0, s, 0).act(listOf(pick), 0, 1L)
        assertTrue(g.ok)
        assertEquals(order.toSet(), g.game.state.seats[0].deck.takeLast(3).toSet())
    }

    // ---- picking: a toggle, a run ----------------------------------------------------------------------------

    @Test
    fun aRunIsOneRowFromTheLastPicked() {
        val h = s.seats[0].hand
        assertEquals(listOf(h[1], h[2], h[3]), DuelSelection.range(s, listOf(h[1]), h[1], h[3]))
        assertEquals(listOf(h[3], h[2], h[1]), DuelSelection.range(s, listOf(h[3]), h[3], h[1]), "from the anchor, its way")
        // Not in one row: a toggle.
        assertEquals(listOf(h[0], at("m1")), DuelSelection.range(s, listOf(h[0]), h[0], at("m1")))
        assertEquals(listOf(h[0]), DuelSelection.toggle(listOf(h[0], h[2]), h[2]))
    }

    // ---- typed: one verb, several coordinates ----------------------------------------------------------------

    @Test
    fun severalCoordinatesAfterAVerb() {
        val st = piles()
        val g = actions("g h1 h2", st)
        assertEquals(2, g.size)
        assertTrue(g.all { it is DuelAction.Move && (it.to as Place.Pile).kind == PileKind.GY })
        // Across the GY and banished, to the hand.
        val h = actions("h gy1 ban1", st)
        assertEquals(setOf(at("gy1", st), at("ban1", st)), h.filterIsInstance<DuelAction.Move>().map { it.uid }.toSet())
        // "k gy1 h1": gy1 ends on top, h1 under it — top first, as written.
        val k = apply(st, actions("k gy1 h1", st))
        assertEquals(listOf(at("gy1", st), at("h1", st)), k.seats[0].deck.take(2))
        val kb = apply(st, actions("kb gy1 h1", st))
        assertEquals(listOf(at("gy1", st), at("h1", st)), kb.seats[0].deck.takeLast(2))
        // An arrow to each of their cards.
        assertEquals(listOf(DuelAction.Target(0, null, listOf(at("om1"), at("os1")))), actions("t om1 os1"))
        // Materials by the handful: the last coordinate is the host.
        assertEquals(2, actions("o h1 h2 m1").count { it is DuelAction.Move && it.to is Place.Under })
        // The one-card lines are as they were.
        assertEquals(1, actions("h gy1 h2", st).count { it is DuelAction.Move })
        assertTrue(actions("s h1 m3").any { it is DuelAction.Move && it.to == Place.Zone(0, ZoneKind.MONSTER, 2) })
        // The Line's preview of an order says it top first, by name only where seen.
        val p = DuelCommand.preview("k os1 h1", s, 0, catalog)
        assertTrue(p.ok, p.problem ?: "")
        assertTrue(p.words.startsWith("Top of the Deck, top first: 1 the face-down card in os1"), p.words)
        assertFalse("Mirror" in p.words, p.words)
    }

    @Test
    fun aHiddenCardTakesOnlyTheVerbsThatNeedNoName() {
        // Extra Deck asks what the card is: refused for their set card.
        assertIs<DuelCommand.Parsed.Problem>(parse("x e1 os1"))
        assertIs<DuelCommand.Parsed.Actions>(parse("b om1 os1"))
    }

    // ---- the chain by keys -----------------------------------------------------------------------------------

    /** Kai's Pot (a Normal Spell) activated, Rival's Ash chained from the hand, Kai's set Mirror Force flipped and chained. */
    private fun chain(): DuelState {
        var st = s
        st = apply(st, DuelVerbs.actions(st, 0, at("h3"), DuelVerb.ACTIVATE, catalog).actions)
        st = apply(st, DuelVerbs.actions(st, 1, uid(1, 3), DuelVerb.ACTIVATE, catalog).actions, 1)
        st = apply(st, DuelVerbs.actions(st, 0, at("s1"), DuelVerb.ACTIVATE, catalog).actions)
        assertEquals(3, st.chain.size)
        return st
    }

    @Test
    fun theWholeChainResolvesNewestFirstAndItsSpellsGoTogether() {
        val st = chain()
        val all = DuelVerbs.resolveAll(st, catalog)
        assertEquals(3, all.count { it == DuelAction.ChainResolve })
        // Pot and Mirror Force wait for the last link, then go to the GY together.
        val lastResolve = all.lastIndexOf(DuelAction.ChainResolve)
        val sent = all.withIndex().filter { (_, a) -> a is DuelAction.Move && a.how == "resolve" }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.index > lastResolve })
        val after = apply(st, all)
        assertTrue(after.chain.isEmpty())
        assertEquals(PileKind.GY, (after.placeOf(at("s1")) as Place.Pile).kind)
        // Typed.
        assertEquals(all, actions("resolve all", st))
        assertIs<DuelCommand.Parsed.Problem>(parse("resolve all"))
    }

    @Test
    fun aLinkIsNegatedWhereItStands() {
        val st = chain()
        // Link 1, Pot of Prosperity: negated, it goes to the GY; the chain stays three links long.
        val r = DuelVerbs.negate(st, 1, 1, catalog)
        assertNull(r.problem)
        val after = apply(st, r.actions, 1)
        assertEquals(3, after.chain.size)
        assertTrue(after.chain[0].negated)
        assertEquals(PileKind.GY, (after.placeOf(at("h3", s)) as Place.Pile).kind)
        // A monster's link is marked only: the monster stays where it is.
        val ash = DuelVerbs.negate(st, 0, 2, catalog).actions
        assertEquals(listOf(DuelAction.Negate(0, 2)), ash)
        // Twice is refused; so is a link that is not there.
        assertTrue(DuelVerbs.negate(after, 1, 1, catalog).problem != null)
        assertTrue(DuelVerbs.negate(after, 1, 9, catalog).problem != null)
        // Typed: "negate" is the newest link, "negate 2" the second.
        assertEquals(DuelAction.Negate(0, 3), actions("negate", st).first())
        assertEquals(DuelAction.Negate(0, 2), actions("negate 2", st).first())
        assertEquals(DuelAction.Negate(0, 1), actions("negate link 1", st).first())
        // The log says it, and a resolve after it still resolves the chain whole.
        val words = DuelWords.say(st, after, DuelEntry(0, 0L, 1, 0, DuelAction.Negate(1, 1)), null, catalog)
        assertTrue("negates Chain Link 1" in words, words)
        assertTrue(apply(after, DuelVerbs.resolveAll(after, catalog)).chain.isEmpty())
    }

    @Test
    fun chainingIsActivatingWhileAChainStands() {
        val st = chain()
        // A response by its coordinate: the card flipped (or played) and a new link on top.
        val withBlue = ok(st, DuelAction.Move(at("h1"), Place.Zone(0, ZoneKind.SPELL, 3), CardPosition.FACE_DOWN_ATK, "set"))
        val chained = actions("chain s4", withBlue)
        assertEquals(DuelAction.ChainAdd(0, at("h1")), chained.last())
        assertEquals(4, apply(withBlue, chained).chain.size)
    }

    @Test
    fun theFocusWalksTheChainWell() {
        val st = chain()
        val shape = DuelFocus.Shape()
        val rows = DuelFocus.rows(st, 0, shape)
        val shared = rows.first { row -> row.any { it.slot is DuelFocus.Slot.Link } }
        assertEquals(DuelFocus.Slot.Link(2), shared.first { it.slot is DuelFocus.Slot.Link }.slot, "the well stands for its newest link")
        val emzLeft = DuelFocus.zone(0, ZoneKind.EMZ, 0)
        val into = DuelFocus.step(emzLeft, DuelFocus.Dir.RIGHT, st, 0, shape)
        assertEquals(DuelFocus.Slot.Link(2), into)
        assertEquals(DuelFocus.Slot.Link(1), DuelFocus.step(into, DuelFocus.Dir.UP, st, 0, shape))
        assertEquals(DuelFocus.Slot.Link(0), DuelFocus.step(DuelFocus.Slot.Link(1), DuelFocus.Dir.UP, st, 0, shape))
        // Past link 1, up leaves the well; past the newest, down; sideways, the Extra Monster Zones.
        assertFalse(DuelFocus.step(DuelFocus.Slot.Link(0), DuelFocus.Dir.UP, st, 0, shape) is DuelFocus.Slot.Link)
        assertFalse(DuelFocus.step(DuelFocus.Slot.Link(2), DuelFocus.Dir.DOWN, st, 0, shape) is DuelFocus.Slot.Link)
        assertEquals(DuelFocus.zone(0, ZoneKind.EMZ, 1), DuelFocus.step(DuelFocus.Slot.Link(0), DuelFocus.Dir.RIGHT, st, 0, shape))
        assertEquals("link 2", DuelFocus.label(DuelFocus.Slot.Link(1), 0))
        assertEquals(st.chain[1].uid, DuelFocus.uidAt(st, DuelFocus.Slot.Link(1)))
        // The chain over, a link left standing settles in the shared row.
        val over = apply(st, DuelVerbs.resolveAll(st, catalog))
        assertFalse(DuelFocus.settle(DuelFocus.Slot.Link(2), over, 0, shape) is DuelFocus.Slot.Link)
        assertTrue(DuelFocus.rows(over, 0, shape).flatten().none { it.slot is DuelFocus.Slot.Link })
    }

    // ---- the keys ----------------------------------------------------------------------------------------------

    @Test
    fun theKeys() {
        val duelling = DeskContext(onBuilder = false, onDuel = true)
        assertEquals(DeskAction.DUEL_RESOLVE, DeskShortcuts.resolve(KeyChord("q"), duelling))
        assertEquals(DeskAction.DUEL_RESOLVE_ALL, DeskShortcuts.resolve(KeyChord("q", shift = true), duelling))
        assertEquals(DeskAction.DUEL_SELECT, DeskShortcuts.resolve(KeyChord("space", shift = true), duelling))
        assertEquals(DeskAction.DUEL_ORDER_EARLIER, DeskShortcuts.resolve(KeyChord("left", alt = true), duelling))
        assertEquals(DeskAction.DUEL_ORDER_LATER, DeskShortcuts.resolve(KeyChord("right", alt = true), duelling))
        // Y answers Ai while Ai is on, and passes while it is off: never both at once.
        assertEquals(DeskAction.DUEL_AI_ANSWER, DeskShortcuts.resolve(KeyChord("y"), duelling))
        assertEquals(DeskAction.DUEL_PASS, DeskShortcuts.resolve(KeyChord("y"), duelling.copy(ai = false)))
        for (ctx in listOf(duelling, duelling.copy(ai = false), duelling.copy(textInputFocused = true))) {
            DeskShortcuts.live(ctx).groupBy { it.chord }.forEach { (chord, rows) ->
                val acts = rows.map { it.action }.toSet()
                assertTrue(acts.size == 1, "${DeskShortcuts.kbd(chord)} means $acts in $ctx")
            }
        }
        // Typing never selects or resolves.
        val typing = duelling.copy(textInputFocused = true)
        assertNull(DeskShortcuts.resolve(KeyChord("space", shift = true), typing))
        assertNull(DeskShortcuts.resolve(KeyChord("q", shift = true), typing))
    }

    @Test
    fun aNegatedLinkIsKeptInTheViewAndTheWire() {
        val st = apply(chain(), DuelVerbs.negate(chain(), 1, 2, catalog).actions, 1)
        val v = DuelView.of(st, 0, 0L)
        assertTrue(v.chain[1].negated)
        assertEquals(MIRROR, s.cards.getValue(at("s1")).code)
        assertEquals(POT, s.cards.getValue(at("h3")).code)
        assertEquals(ASH, s.cards.getValue(uid(1, 3)).code)
    }
}
