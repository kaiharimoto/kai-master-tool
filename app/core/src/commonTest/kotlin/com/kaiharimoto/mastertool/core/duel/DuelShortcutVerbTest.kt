package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxLink
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAnswers
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAsk
import com.kaiharimoto.mastertool.core.duel.text.ShortcutLine
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shortcut (Phase D §5½, step 1): the verb's core. The default never changes; `use` is still Activate; a Shortcut is one
 * undo group, every entry tagged and stamped with who chose it; what the engine refuses is listed with its rule; Esc
 * commits nothing.
 *
 * The engine's chain and steps (agents (b) and (c)) are filled in parallel, so the verb is held here to the engine's
 * contract through a seam ([Mini], a small engine of our own for two reference cards); the case through the real engine
 * waits for them ([endToEndThroughTheRealEngine]).
 */
class DuelShortcutVerbTest {

    private val catalog = DuelCatalog { code -> FxRef.cards.firstOrNull { CardId(code) in it.passcodes }?.let { DuelCardInfo.of(it) } }

    /**
     * A table laid out by hand: seat 0 holds Example Lamp in M1, Example Scout and Example Pawn (no script) in its hand,
     * Example Flash (a Quick-Play) in its hand, a Scout in its GY and Example Scouts in its Deck; seat 1 a Pawn in M1 and a
     * set card in S1. Turn 1, seat 0's Main Phase 1.
     */
    private fun game(solo: Boolean = true): DuelGame {
        val mine = listOf(FxRef.LAMP, FxRef.SCOUT, FxRef.PAWN, FxRef.FLASH, FxRef.SCOUT, FxRef.SCOUT, FxRef.SCOUT, FxRef.TINKER)
        val theirs = listOf(FxRef.PAWN, FxRef.SNARE, FxRef.PAWN)
        val header = DuelHeader(seats = listOf(SeatSetup("Kai", mine), SeatSetup("Rival", theirs)), handSize = 0, solo = solo, first = 0)
        val a = 1
        val b = 1 + DuelState.SEAT_UIDS
        val lay = listOf(
            DuelAction.Move(a, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "place"),
            DuelAction.Move(a + 1, Place.Pile(0, PileKind.HAND), how = "place"),
            DuelAction.Move(a + 2, Place.Pile(0, PileKind.HAND), how = "place"),
            DuelAction.Move(a + 3, Place.Pile(0, PileKind.HAND), how = "place"),
            DuelAction.Move(a + 4, Place.Pile(0, PileKind.GY), how = "place"),
            DuelAction.Move(b, Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "place"),
            DuelAction.Move(b + 1, Place.Zone(1, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"),
            DuelAction.Phase(DuelPhase.MAIN1),
        )
        val dealt = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val r = dealt.act(lay, null)
        assertNull(r.problem, "the table lays out")
        return r.game.copy(floor = r.game.cursor)
    }

    private val lamp = 1
    private val scoutInHand = 2
    private val pawnInHand = 3
    private val flashInHand = 4
    private val scoutInGy = 5
    private val deckScouts = listOf(6, 7)

    /** Lamp's "Send" (e1) and Scout's two effects as a small engine of the contract's shape: tags, links, choices. */
    private class Mini(val refuseSearch: String? = "once per turn: used") : ShortcutEngine {
        override fun moves(t: FxTable, seat: Int): List<FxMove> = buildList {
            t.state.cards.values.filter { it.controller == seat && t.book.canonical(it.code) == FxRef.LAMP && t.state.placeOf(it.uid) is Place.Zone }
                .forEach { add(FxMove.Activate(it.uid, "e1")) }
            t.state.cards.values.filter { it.owner == seat && t.book.canonical(it.code) == FxRef.SCOUT && t.state.placeOf(it.uid).let { p -> p is Place.Pile && p.kind == PileKind.HAND } }
                .forEach { if (refuseSearch == null) add(FxMove.Activate(it.uid, "e1")); add(FxMove.Activate(it.uid, "e2")) }
            if (t.fx.links.isNotEmpty()) add(FxMove.Resolve)
        }

        override fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay = when (move) {
            is FxMove.Activate -> activate(t, seat, move, chooser)
            FxMove.Resolve -> resolve(t)
            else -> FxPlay.Refused("not in this engine")
        }

        private fun activate(t: FxTable, seat: Int, m: FxMove.Activate, chooser: Chooser): FxPlay {
            if (m !in moves(t, seat)) return FxPlay.Refused(if (m.effect == "e1" && refuseSearch != null) refuseSearch else "not now")
            val deck = t.state.seats[seat].deck.filter { t.book.canonical(t.state.cards.getValue(it).code) == FxRef.SCOUT }
            val ask = Decision.Cards("Send to the GY", deck, 1, 1)
            val a = chooser.choose(ask)
            if (!Chooser.legal(ask, a)) return FxPlay.Cancelled
            val n = t.state.chain.size + 1
            val actions = listOf<DuelAction>(DuelAction.ChainAdd(seat, m.uid))
            val tags = listOf(FxTag(m.uid, m.effect, FxTag.ACTIVATE, n, "abcdef012345"))
            val state = DuelRules.applyAll(t.state, actions, seat).first ?: return FxPlay.Refused("the table refused it")
            val link = FxLink(n, seat, m.uid, FxRef.LAMP, m.effect, 1, mapOf("pick" to listOf(deck[a.single()])), script = "abcdef012345")
            return FxPlay.Done(actions, tags, state, t.fx.copy(links = t.fx.links + link))
        }

        private fun resolve(t: FxTable): FxPlay {
            val n = t.state.chain.size
            val link = t.fx.links.firstOrNull { it.link == n } ?: return FxPlay.Refused("Chain Link $n is not the engine's")
            val sent = link.bound.getValue("pick").single()
            val actions = listOf(DuelAction.Move(sent, Place.Pile(t.state.cards.getValue(sent).owner, PileKind.GY), how = "send"), DuelAction.ChainResolve)
            val tags = actions.map { FxTag(link.uid, link.effect, FxTag.RESOLVE, n, link.script) }
            val state = DuelRules.applyAll(t.state, actions, link.seat).first ?: return FxPlay.Refused("the table refused it")
            return FxPlay.Done(actions, tags, state, t.fx.copy(links = t.fx.links - link))
        }
    }

    private fun shortcuts(engine: ShortcutEngine = Mini(), book: ScriptBook = FxRef.book, resolveAtOnce: Boolean? = null, networked: Boolean = false) =
        Shortcuts(book, FxRef.facts, engine = engine, resolveAtOnce = resolveAtOnce, networked = networked)

    /** Picks the Deck's first Scout. */
    private val pickFirst = Chooser { d -> if (d is Decision.Cards) listOf(0) else Chooser.CANCEL }

    // ---- the default never changes ---------------------------------------------------------------------------------

    @Test
    fun theDefaultOfEveryCardInEveryPlaceIsUnchangedWithOrWithoutAScript() {
        val g = game(solo = false)
        val s = g.state
        val withBook = shortcuts()
        // Every place: the field, the hand, the GY, the Deck, the other seat's field and set card, from both seats' eyes.
        for (seat in 0..1) for (uid in s.cards.keys) {
            val plain = DuelVerbs.offered(s, seat, uid, catalog)
            assertEquals(plain, DuelVerbs.offered(s, seat, uid, catalog, shortcuts(book = ScriptBook.EMPTY)), "an empty book changes nothing: $uid")
            val offered = DuelVerbs.offered(s, seat, uid, catalog, withBook)
            assertEquals(plain.first(), offered.first(), "the default stays first: $uid for $seat")
            assertEquals(plain, offered - DuelVerb.SHORTCUT, "nothing else moves: $uid for $seat")
            assertTrue(DuelVerb.SHORTCUT !in plain, "never without the scripts")
            assertTrue(plain.first() != DuelVerb.SHORTCUT)
            // The default's actions are the same too.
            val d = DuelVerbs.default(s, seat, uid, catalog)
            assertEquals(DuelVerbs.actions(s, seat, uid, d, catalog), DuelVerbs.actions(s, seat, uid, DuelVerb.DEFAULT, catalog, shortcuts = withBook), "default of $uid")
        }
        // Offered exactly on the seat's own seen cards that have a written effect: Lamp on the field, Scout and Flash in hand, Scout in the GY.
        val offered = s.cards.keys.filter { DuelVerb.SHORTCUT in DuelVerbs.offered(s, 0, it, catalog, withBook) }.toSet()
        assertEquals(setOf(lamp, scoutInHand, flashInHand, scoutInGy), offered)
        // Not Pawn (no script), not the Deck's Scouts (unseen); and never a card of seat 0's to seat 1.
        assertTrue(s.cards.keys.filter { s.cards.getValue(it).owner == 0 }.none { DuelVerb.SHORTCUT in DuelVerbs.offered(s, 1, it, catalog, withBook) })
    }

    @Test
    fun activateStaysTheManualVerbAndUseStillMeansIt() {
        val s = game().state
        // `use` is Activate, as a saved combo's step wrote it.
        val used = DuelCommand.parse("use h3", s, 0, catalog)
        assertIs<Parsed.Actions>(used, "$used")
        assertEquals(DuelVerbs.actions(s, 0, flashInHand, DuelVerb.ACTIVATE, catalog).actions, used.actions)
        assertTrue(used.actions.any { it is DuelAction.ChainAdd })
        assertEquals(DuelVerb.ACTIVATE, DuelCommand.VERB_WORDS["use"])
        // A saved combo's `use` step plans as it did.
        val run = ComboRunner.plan(s, 0, listOf("use example flash"), catalog, shortcuts = shortcuts())
        assertTrue(run.ok, run.problem)
        assertTrue(run.steps.single().second.any { it is DuelAction.ChainAdd })
        assertTrue(run.fx.isEmpty(), "a hand-made step carries no tags")
        // Activate on a scripted card is the manual verb still: a chain link by hand, no engine.
        val act = DuelVerbs.actions(s, 0, lamp, DuelVerb.ACTIVATE, catalog, shortcuts = shortcuts())
        assertEquals(listOf<DuelAction>(DuelAction.ChainAdd(0, lamp)), act.actions)
        assertTrue(act.fx.isEmpty())
    }

    @Test
    fun theLineReadsTheLetterTheWordAndAisChoices() {
        val s = game().state
        assertEquals(Parsed.Shortcut(ShortcutAsk.Use(scoutInHand), "Shortcut: Example Scout"), DuelCommand.parse("u h1", s, 0, catalog))
        assertEquals(ShortcutAsk.Use(scoutInHand, "e2"), (DuelCommand.parse("u h1 e2", s, 0, catalog) as Parsed.Shortcut).ask)
        assertEquals(ShortcutAsk.Use(scoutInHand, "search"), (DuelCommand.parse("u h1 Search", s, 0, catalog) as Parsed.Shortcut).ask)
        assertEquals(ShortcutAsk.Use(lamp, "e1"), (DuelCommand.parse("shortcut example lamp e1", s, 0, catalog) as Parsed.Shortcut).ask)
        assertEquals(ShortcutAsk.Use(lamp), (DuelCommand.parse("shortcut m1", s, 0, catalog) as Parsed.Shortcut).ask)
        val withChoices = DuelCommand.parse("u m1 e1 pick=example scout, gy1 target=om1 zone=m3 declare=Warrior", s, 0, catalog)
        assertEquals(
            ShortcutAsk.Use(lamp, "e1", ShortcutAnswers(pick = listOf("example scout", "gy1"), target = listOf("om1"), zone = listOf("m3"), declare = listOf("warrior"))),
            (withChoices as Parsed.Shortcut).ask,
        )
        // Their card, or one the seat cannot see, is no Shortcut of the seat's.
        assertIs<Parsed.Problem>(DuelCommand.parse("u os1", s, 0, catalog))
        // The chain as written.
        val chained = DuelRules.applyAll(s, listOf(DuelAction.ChainAdd(0, lamp)), 0).first!!
        assertEquals(ShortcutAsk.Resolve(false), (DuelCommand.parse("resolve by shortcut", chained, 0, catalog) as Parsed.Shortcut).ask)
        assertEquals(ShortcutAsk.Resolve(true), (DuelCommand.parse("resolve all by shortcut", chained, 0, catalog) as Parsed.Shortcut).ask)
        assertIs<Parsed.Actions>(DuelCommand.parse("resolve all", chained, 0, catalog), "resolve all is by hand, as it was")
        assertIs<Parsed.Actions>(DuelCommand.parse("resolve", chained, 0, catalog))
    }

    // ---- one undo group, tagged, with provenance --------------------------------------------------------------------

    @Test
    fun aShortcutIsOneUndoGroupTaggedAndStampedWithWhoChoseIt() {
        val g = game()
        val by = Provenance(Provenance.PERSON, eyes = "all")
        val r = DuelVerbs.actions(g.state, 0, lamp, DuelVerb.SHORTCUT, catalog, shortcuts = shortcuts(), chooser = pickFirst)
        assertNull(r.problem)
        // On a one-player table nothing can respond: the activation and its resolution are one result.
        assertEquals(listOf(DuelAction.ChainAdd(0, lamp), DuelAction.Move(deckScouts.first(), Place.Pile(0, PileKind.GY), how = "send"), DuelAction.ChainResolve), r.actions)
        assertEquals(r.actions.size, r.fx.size)
        val done = g.act(r.actions, 0, by = by, fx = r.fx)
        assertNull(done.problem)
        val added = done.game.played.drop(g.cursor)
        assertEquals(3, added.size)
        assertEquals(1, added.map { it.group }.toSet().size, "one group")
        assertTrue(added.all { e -> e.fx.let { f -> f != null && f.uid == lamp && f.effect == "e1" } }, "every entry tagged")
        assertTrue(added.all { it.by == by }, "every entry stamped with who chose it")
        assertEquals(listOf(FxTag.ACTIVATE, FxTag.RESOLVE, FxTag.RESOLVE), added.map { it.fx!!.part })
        // One undo takes back the whole effect.
        val undone = done.game.undo()
        assertEquals(g.cursor, undone.cursor)
        assertEquals(g.state, undone.state)
        // The same through the result's own door.
        val direct = shortcuts().use(g.state, 0, lamp, null, pickFirst).commit(g, 0, by)
        assertEquals(done.game.played, direct.game.played)
    }

    @Test
    fun whereTheOtherSeatMayRespondTheResolutionIsItsOwnGroupByResolveByShortcut() {
        val g = game(solo = false)
        val sc = shortcuts()
        val used = sc.use(g.state, 0, lamp, "send", pickFirst)
        assertEquals(listOf<DuelAction>(DuelAction.ChainAdd(0, lamp)), used.actions, "the link stands for the other seat")
        val activated = used.commit(g, 0, Provenance()).game
        val after = sc.withFx(used.fx)
        assertTrue(after.written(activated.state, 1))
        val parsed = DuelCommand.parse("resolve by shortcut", activated.state, 0, catalog) as Parsed.Shortcut
        val resolved = ShortcutLine.run(parsed.ask, after, activated.state, 0, catalog, Chooser.FIRST)
        assertEquals(2, resolved.actions.size)
        val g2 = resolved.commit(activated, 0, Provenance()).game
        val groups = g2.played.drop(activated.cursor).map { it.group }.toSet()
        assertEquals(1, groups.size, "one group")
        assertTrue(groups.single() > activated.played.last().group, "its own, after the activation's")
        assertTrue(g2.state.chain.isEmpty())
        // A link made by hand does not resolve as written.
        val byHand = g.act(DuelAction.ChainAdd(0, lamp), 0).game
        val refused = sc.resolve(byHand.state, 0, catalog, Chooser.FIRST)
        assertEquals("Chain Link 1 was not made by a Shortcut: resolve it by hand", refused.problem)
        // Resolve all by Shortcut: the written link through the engine, the hand-made one by hand, newest first, one result.
        val both = sc.withFx(used.fx).let { s2 ->
            val two = activated.act(DuelAction.ChainAdd(1, 1 + DuelState.SEAT_UIDS), 1).game
            s2.resolve(two.state, 0, catalog, Chooser.FIRST, all = true)
        }
        assertNull(both.problem)
        assertEquals(listOf<DuelAction>(DuelAction.ChainResolve), both.actions.take(1), "their hand-made link first, by hand")
        assertEquals(listOf(null), both.tags.take(1))
        assertTrue(both.tags.drop(1).all { it?.part == FxTag.RESOLVE })
    }

    // ---- several effects, greyed ones, Esc --------------------------------------------------------------------------

    @Test
    fun aCardsEffectsAreListedByShortNameAndWhatTheEngineRefusesIsGreyedWithItsRule() {
        val s = game().state
        val sc = shortcuts()
        val options = sc.options(s, 0, scoutInHand)
        assertEquals(listOf("Search", "Bounce"), options.map { it.label })
        assertFalse(options[0].legal)
        assertEquals("once per turn: used", options[0].why)
        assertEquals("Search (unverified): once per turn: used", options[0].words)
        assertTrue(options[1].legal)
        // Asked for by name, the greyed one is refused with its rule; nothing to commit.
        val refused = DuelVerbs.actions(s, 0, scoutInHand, DuelVerb.SHORTCUT, catalog, shortcuts = sc, effect = "search", chooser = pickFirst)
        assertEquals("Search: once per turn: used", refused.problem)
        assertTrue(refused.actions.isEmpty())
        // With one legal, it is the one used; with two, the chooser is asked which, by short name.
        val asked = mutableListOf<Decision>()
        val both = shortcuts(engine = Mini(refuseSearch = null))
        both.use(s, 0, scoutInHand, null) { d -> asked += d; if (d is Decision.Option) listOf(1) else listOf(0) }
        assertEquals(Decision.Option(listOf("Search", "Bounce")), asked.first())
        // An effect that is not there says what is.
        assertEquals("Example Scout has no Shortcut called “revive”: Search (e1), Bounce (e2)", sc.use(s, 0, scoutInHand, "revive", pickFirst).problem)
        // A card with no script: the verb says so.
        assertEquals(Shortcuts.NO_SCRIPT, DuelVerbs.actions(s, 0, pawnInHand, DuelVerb.SHORTCUT, catalog, shortcuts = sc).problem)
        assertEquals(Shortcuts.NO_SCRIPT, DuelVerbs.actions(s, 0, lamp, DuelVerb.SHORTCUT, catalog).problem, "no scripts at the table: none has one")
    }

    @Test
    fun escCancelsTheWholeUseAndCommitsNothing() {
        val g = game()
        val r = DuelVerbs.actions(g.state, 0, lamp, DuelVerb.SHORTCUT, catalog, shortcuts = shortcuts(), chooser = { Chooser.CANCEL })
        assertTrue(r.cancelled)
        assertTrue(r.actions.isEmpty())
        assertNull(r.problem)
        val committed = g.act(r.actions, 0, fx = r.fx)
        assertEquals(g, committed.game)
        // Cancelled at the second choice, the first answered: still nothing.
        var n = 0
        val late = shortcuts(engine = Mini(refuseSearch = null)).use(g.state, 0, scoutInHand, null) { d -> if (n++ == 0 && d is Decision.Option) listOf(0) else Chooser.CANCEL }
        assertTrue(late.cancelled)
        assertEquals(g, late.commit(g, 0, Provenance()).game)
    }

    // ---- Ai and the line: answers in the op, asked back without them ------------------------------------------------

    @Test
    fun aisChoicesAreReadFromTheOpOrAskedBackWithTheOptionsListed() {
        val s = game().state
        val sc = shortcuts()
        val asked = ShortcutLine.answered(ShortcutAsk.Use(lamp, "e1"), sc, s, 0, catalog)
        val q = asked.problem
        assertNotNull(q)
        assertTrue("pick=" in q && "Example Scout" in q, q)
        val ask = (DuelCommand.parse("u m1 e1 pick=example scout", s, 0, catalog) as Parsed.Shortcut).ask
        val made = ShortcutLine.answered(ask, sc, s, 0, catalog)
        assertTrue(made.ok, made.problem)
        assertEquals(DuelAction.Move(deckScouts.first(), Place.Pile(0, PileKind.GY), how = "send"), made.actions[1])
    }

    @Test
    fun aComboRecordsAShortcutAsUAndPlaysItBack() {
        val g = game()
        val done = shortcuts().use(g.state, 0, lamp, null, pickFirst).commit(g, 0, Provenance()).game
        val steps = ComboRecorder.steps(g.state, done.played.drop(g.cursor), catalog, 0, g.header.seed)
        assertEquals(listOf("u Example Lamp e1 pick=Example Scout"), steps)
        // Played back on the same table it makes the same moves, tagged.
        val run = ComboRunner.plan(g.state, 0, steps, catalog, g.header.seed, shortcuts = shortcuts())
        assertTrue(run.ok, run.problem)
        assertEquals(done.played.drop(g.cursor).map { it.action }, run.steps.single().second)
        assertEquals(done.played.drop(g.cursor).map { it.fx }, run.tags(0))
        // Without the table's written effects the step stops, in words.
        val none = ComboRunner.plan(g.state, 0, steps, catalog, g.header.seed)
        assertFalse(none.ok)
        assertTrue(ComboRunner.NO_SHORTCUTS in none.problem!!)
        // A resolution made apart is written as its own step.
        val g2 = game(solo = false)
        val used = shortcuts().use(g2.state, 0, lamp, null, pickFirst)
        val a = used.commit(g2, 0, Provenance()).game
        val b = shortcuts().withFx(used.fx).resolve(a.state, 0, catalog, Chooser.FIRST).commit(a, 0, Provenance()).game
        assertEquals(listOf("u Example Lamp e1", "resolve by shortcut"), ComboRecorder.steps(g2.state, b.played.drop(g2.cursor), catalog, 0, g2.header.seed))
    }

    @Test
    fun aisMenuListsItsOwnShortcutsByEffect() {
        val s = game().state
        val plain = DuelMoves.menu(s, 0, catalog, 0L)
        assertTrue(plain.flatMap { it.moves }.none { it.line.startsWith("u ") }, "nothing without the scripts")
        val menu = DuelMoves.menu(s, 0, catalog, 0L, shortcuts = shortcuts())
        val lines = menu.flatMap { it.moves }.filter { it.line.startsWith("u ") }
        // The hand's Scout (its Search used, its Bounce legal), then Lamp on the field; the GY's Scout has none legal now.
        assertEquals(listOf("u h1 e2", "u m1 e1"), lines.map { it.line })
        assertEquals("Shortcut: Send (unverified)", lines.last().what)
    }

    // ---- not at a networked table ----------------------------------------------------------------------------------

    @Test
    fun aNetworkedTableRefusesShortcutsInWords() {
        val g = game(solo = false)
        val sc = shortcuts(networked = true)
        assertEquals(DuelHost.NO_SHORTCUTS, sc.use(g.state, 0, lamp, null, pickFirst).problem)
        assertTrue(sc.options(g.state, 0, lamp).none { it.legal })
        val made = shortcuts().use(g.state, 0, lamp, null, pickFirst)
        val r = DuelHost.act(g, 0, made.actions, emptyMap(), fx = made.tags)
        assertEquals(DuelHost.NO_SHORTCUTS, r.problem)
        assertEquals(g, r.game)
    }

    // ---- the log's words -------------------------------------------------------------------------------------------

    @Test
    fun theLogSaysAShortcutMadeIt() {
        val g = game(solo = false)
        val done = shortcuts().use(g.state, 0, lamp, null, pickFirst).commit(g, 0, Provenance()).game
        val e = done.played.last()
        val said = DuelWords.say(g.state, done.state, e, 0, catalog, FxRef.book)
        assertEquals("Kai activates Example Lamp (Chain Link 1) — Example Lamp · Send (Shortcut, unverified)", said)
        assertTrue(DuelWords.say(g.state, done.state, e.copy(fx = e.fx!!.copy(verified = true)), 0, catalog, FxRef.book).endsWith("Example Lamp · Send (Shortcut)"))
        assertTrue(DuelWords.say(g.state, done.state, e, 0, catalog).endsWith("Example Lamp · Effect 1 (Shortcut, unverified)"), "no book: by its id")
        assertEquals("Kai activates Example Lamp (Chain Link 1)", DuelWords.say(g.state, done.state, e.copy(fx = null), 0, catalog), "a hand-made entry reads as it did")
    }

    // ---- the keys and letters ----------------------------------------------------------------------------------------

    @Test
    fun theLetterIsUAndTheWordIsShortcut() {
        assertEquals(DuelVerb.SHORTCUT, DuelCommand.VERB_WORDS["u"])
        assertEquals(DuelVerb.SHORTCUT, DuelCommand.VERB_WORDS["shortcut"])
        assertEquals("Shortcut", DuelVerb.SHORTCUT.label)
    }

    /**
     * Through the real engine: Lamp's Send on the reference scripts, one group, every entry tagged. Waits for the chain and
     * the steps (agents (b) and (c)): today `FxChain.play` refuses every activation.
     */
    @Test
    fun endToEndThroughTheRealEngine() {
        val g = game()
        val sc = Shortcuts.of(g, FxRef.book, FxRef.facts)
        val options = sc.options(g.state, 0, lamp)
        assertTrue(options.single().legal, options.toString())
        val r = sc.use(g.state, 0, lamp, null) { d -> if (d is Decision.Cards) listOf(0) else Chooser.FIRST.choose(d) }
        assertTrue(r.ok, r.problem)
        val done = r.commit(g, 0, Provenance()).game
        val added = done.played.drop(g.cursor)
        assertEquals(1, added.map { it.group }.toSet().size)
        assertTrue(added.all { it.fx != null && it.by != null })
        assertTrue(added.any { (it.action as? DuelAction.Move)?.to == Place.Pile(0, PileKind.GY) })
    }
}
