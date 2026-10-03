package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.actions
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.bare
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.ok
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.parse
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.play
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.problem
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.text.DuelAnswer
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.QueryKind
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.UiKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Command mode's language (1.0.87): coordinates, verb letters, `;`, forgiving names, the chrome's words, and the red team's cases. */
class CommandModeTest {
    private val s = battle()
    private val bewd = uid(0, 5)
    private val handBewd = uid(0, 0)
    private val pot = uid(0, 2)
    private val ash = uid(0, 4)
    private val mirror = uid(0, 3)
    private val zeus = uid(0, 40)
    private val droll = uid(0, 8)
    private val dm = uid(1, 5)
    private val theirSet = uid(1, 4)

    @Test
    fun coordinatesNameCardsBeforeNames() {
        assertEquals(listOf(DuelAction.Move(handBewd, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal")), actions("summon h1 to m3", s))
        assertEquals(listOf(DuelAction.Move(handBewd, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal")), actions("s h1 m3", s))
        assertEquals(listOf(DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK, "set")), actions("e h3 s2", s))
        assertEquals(listOf(DuelAction.Position(mirror, CardPosition.FACE_UP_ATK), DuelAction.ChainAdd(0, mirror)), actions("activate s1", s))
        assertEquals(listOf(DuelAction.Move(dm, Place.Pile(1, PileKind.GY), null, "send")), actions("g om1", s))
        assertEquals(listOf(DuelAction.Move(droll, Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "banish")), actions("b gy1", s))
        assertEquals(listOf(DuelAction.Move(ash, Place.Under(bewd), how = "attach")), actions("attach h4 to m1", s))
        assertEquals(listOf(DuelAction.Move(ash, Place.Under(bewd), how = "attach")), actions("o h4 m1", s))
        assertEquals(listOf(DuelAction.Counter(bewd, 2)), actions("counter m1 +2", s))
        assertEquals(listOf(DuelAction.Counter(bewd, -1)), actions("c m1 -1", s))
        assertEquals(listOf(DuelAction.Target(0, mirror, listOf(dm))), actions("target om1 with s1", s))
        assertEquals(listOf(DuelAction.Target(0, null, listOf(dm))), actions("t om1", s))
        assertEquals(listOf(DuelAction.Move(bewd, Place.Zone(0, ZoneKind.MONSTER, 3), null, "move")), actions("move m1 to m4", s))
        assertEquals(listOf(DuelAction.Move(uid(0, 1), Place.Pile(0, PileKind.HAND, 0), how = "return")), actions("move h2 to h1", s))
        assertEquals(listOf(DuelAction.Move(zeus, Place.Pile(0, PileKind.EXTRA), null, "return")), actions("x e1", s))
        assertEquals(DuelAction.Move(uid(0, 7), Place.Pile(0, PileKind.GY), how = "detach"), actions("detach m1", s).single())
        // m1 and m2 are phases only alone.
        assertEquals(listOf(DuelAction.Phase(DuelPhase.MAIN2)), actions("m2", s))
        assertEquals(listOf(DuelAction.Move(bewd, Place.Pile(0, PileKind.GY), null, "send")), actions("m1 to gy", s))
        // A whole pile is no single card; an empty zone holds none.
        assertTrue(problem("b gy", s).text.startsWith("Which card of GY"))
        assertTrue(problem("g m3", s).text.contains("Nothing is in m3"))
        // A letter is a verb only before a coordinate.
        assertIs<Parsed.Problem>(parse("g kuriboh", s))
    }

    @Test
    fun attacksGoThroughTheVerb() {
        assertEquals(listOf(DuelAction.Attack(0, bewd, dm)), actions("a m1 om1", s))
        assertEquals(listOf(DuelAction.Attack(0, bewd, dm)), actions("attack om1 with m1", s))
        assertEquals(listOf(DuelAction.Attack(0, bewd, dm)), actions("attack m1 om1", s))
        assertEquals(listOf(DuelAction.Attack(0, bewd, dm)), actions("m1 attacks om1", s))
        assertEquals(listOf(DuelAction.Attack(0, bewd, null)), actions("a m1 direct", s))
        assertEquals(listOf(DuelAction.Attack(0, bewd, null)), actions("attack m1 d", s))
        assertEquals(listOf(DuelAction.Attack(0, bewd, null)), actions("m1 attacks directly", s))
        // Outside the Battle Phase "a m1 om1" never moves a monster into their zone: it says why.
        val main = ok(s, DuelAction.Phase(DuelPhase.MAIN2))
        assertTrue(problem("a m1 om1", main).text.contains("Battle Phase"))
        // Only the turn player attacks (the red team): the line, and the table itself.
        val theirs = play(s, DuelAction.Move(uid(1, 2), Place.Zone(1, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_ATK, "normal"))
        assertIs<Parsed.Problem>(parse("a om2 m1", theirs, seat = 1))
        assertIs<Parsed.Problem>(parse("m1 attacks directly", theirs, seat = 1))
        assertTrue(DuelRules.apply(theirs, DuelAction.Attack(1, dm, null)) is Outcome.Refused)
        // "a s1" is still Activate.
        assertEquals(DuelAction.ChainAdd(0, mirror), actions("a s1", s).last())
    }

    @Test
    fun movesJoinedWithSemicolonsAreStepsBoundAsTheyWereRead() {
        val p = parse("s h1 m3; a m3 om1", s)
        assertIs<Parsed.Many>(p)
        assertEquals(2, p.parts.size)
        assertEquals(listOf(DuelAction.Move(handBewd, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal")), p.parts[0].actions)
        // The second step read the table as the first left it: m3 is the Blue-Eyes just summoned.
        assertEquals(listOf(DuelAction.Attack(0, handBewd, dm)), p.parts[1].actions)
        // A phase change stays its own step.
        val q = parse("m2; s h1 m3", s) as Parsed.Many
        assertEquals(listOf(DuelAction.Phase(DuelPhase.MAIN2)), q.parts[0].actions)
        // One step that fails names itself; a question cannot be joined.
        assertTrue(problem("s h1 m3; s h2 m3", s).text.startsWith("Move 2"))
        assertIs<Parsed.Problem>(parse("hand; draw", s))
        // Free text keeps its semicolon.
        assertEquals(listOf(DuelAction.Chat(0, "wait; what")), actions("say wait; what", s))
    }

    @Test
    fun systemWordsActOnlyAsTheWholeLine() {
        val hand = play(
            bare(),
            DuelAction.Move(uid(0, 9), Place.Pile(0, PileKind.HAND)),
            DuelAction.Move(uid(0, 8), Place.Pile(0, PileKind.HAND)),
            DuelAction.Move(uid(1, 6), Place.Pile(1, PileKind.HAND)),
        )
        // "battle fader" is Battle Fader, "bp" the Battle Phase.
        assertEquals(uid(0, 9), (actions("battle fader to gy", hand).single() as DuelAction.Move).uid)
        assertEquals(listOf(DuelAction.Phase(DuelPhase.BATTLE)), actions("bp", hand))
        assertEquals(listOf(DuelAction.Phase(DuelPhase.BATTLE)), actions("battle", hand))
        // A count is a number or nothing: "draw 2." draws two, "draw banana" is asked.
        assertEquals(listOf(DuelAction.Draw(0, 2)), actions("Draw 2.", hand))
        assertTrue(problem("draw banana", hand).text.startsWith("Draw how many"))
        assertTrue(problem("mill some", hand).text.startsWith("Mill how many"))
        // "effect veiler" is the card (a hand trap activated), not a bare link.
        val veiler = actions("effect veiler", hand, seat = 1)
        assertEquals(DuelAction.ChainAdd(1, uid(1, 6)), veiler.last())
        assertEquals(Place.Pile(1, PileKind.GY), (veiler.first() as DuelAction.Move).to)
        // "to M3." with its full stop still lands in M3.
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 2), (actions("summon droll to M3.", hand).single() as DuelAction.Move).to)
        // lp alone is a question; "lp o" the other seat's.
        assertEquals(Parsed.Query(QueryKind.LP), parse("lp", hand))
        assertEquals(listOf(DuelAction.Lp(1, delta = -1000)), actions("lp o -1000", hand))
        assertEquals(listOf(DuelAction.Lp(0, delta = -1000)), actions("lp me -1000", hand))
        assertEquals(listOf(DuelAction.Lp(1, delta = -1000)), actions("lp opp -1000", hand))
    }

    @Test
    fun aNameIsLookedForTierByTier() {
        // Snake-Eye Ash in hand, Ash Blossom in the Deck: "summon ash" is the hand's (the red team).
        val t = play(bare(), DuelAction.Move(uid(0, 7), Place.Pile(0, PileKind.HAND)))
        assertEquals(uid(0, 7), (actions("summon ash", t).single() as DuelAction.Move).uid)
        // A search reaches the Deck first.
        assertEquals(uid(0, 4), (actions("ash blossom to hand", t).first() as DuelAction.Move).uid)
        // Two copies on the field are two cards: say which.
        val two = play(
            bare(),
            DuelAction.Move(uid(0, 0), Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK),
            DuelAction.Move(uid(0, 1), Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK),
        )
        val which = problem("blue-eyes to gy", two)
        assertEquals(listOf("m1", "m3"), which.choices)
        assertTrue(which.text.contains("m1 or m3"))
        assertEquals(uid(0, 1), (actions("m3 to gy", two).single() as DuelAction.Move).uid)
    }

    @Test
    fun aNameNotFoundIsMatchedForgivingly() {
        val t = play(bare(), DuelAction.Move(uid(0, 8), Place.Pile(0, PileKind.HAND)), DuelAction.Move(uid(0, 4), Place.Pile(0, PileKind.HAND)))
        // By sound: "drawl and lock bird" is Droll & Lock Bird.
        assertEquals(uid(0, 8), (actions("summon drawl and lock bird", t).single() as DuelAction.Move).uid)
        // By spelling: one letter off.
        assertEquals(uid(0, 4), (actions("summon ash blosom and joyous spring", t).single() as DuelAction.Move).uid)
        // Unsure: it asks, numbered, and the fixes are whole lines.
        val asked = problem("summon blossum joy", t)
        assertTrue(asked.text.contains("Did you mean: 1. Ash Blossom & Joyous Spring"), asked.text)
        val preview = DuelCommand.preview("summon blossum joy", t, 0, catalog)
        assertFalse(preview.ok)
        assertEquals("summon ash blossom & joyous spring", preview.fixes.first())
        // Never a card the seat cannot see: Rival's hand holds a Dark Magician.
        val theirs = play(bare(), DuelAction.Move(uid(1, 2), Place.Pile(1, PileKind.HAND)))
        val p = problem("their dark magican to gy", theirs)
        assertTrue(p.choices.isEmpty() && "Dark Magician" !in p.text, p.text)
    }

    @Test
    fun aHiddenCardIsReachableOnlyByWhereItIsAndNeverNamed() {
        // Their set card: target, destroy, flip, bounce — by coordinate.
        assertEquals(listOf(DuelAction.Move(theirSet, Place.Pile(1, PileKind.GY), null, "send")), actions("destroy os1", s))
        assertEquals(DuelAction.Target(0, null, listOf(theirSet)), actions("t os1", s).single())
        // But never played as if its kind were known.
        assertTrue(problem("activate os1", s).text.startsWith("You cannot see that card"))
        assertTrue(problem("s oh1 m3", s).text.startsWith("You cannot see that card"))
        // Nothing the line or its preview says names it.
        listOf("destroy os1", "t os1", "oh2 to gy", "move os1 to os3", "read os1", "?os1", "?oh1", "f os1").forEach { line ->
            val pv = DuelCommand.preview(line, s, 0, catalog)
            listOf("Mirror Force", "Kuriboh", "Dark Magician", "Ash Blossom").forEach { name ->
                assertFalse(name in pv.words || name in (pv.problem ?: ""), "“$line” said $name: ${pv.words} ${pv.problem}")
            }
        }
        // Not by name: their hand is no one's to search.
        assertIs<Parsed.Problem>(parse("their kuriboh to gy", s))
    }

    @Test
    fun theChromeHasWords() {
        assertEquals(Parsed.Ui(UiKind.OPEN, "ogy", seat = 1, pile = PileKind.GY), parse("open ogy", s))
        assertEquals(Parsed.Ui(UiKind.OPEN, "their graveyard", seat = 1, pile = PileKind.GY), parse("open their graveyard", s))
        assertEquals(Parsed.Ui(UiKind.OPEN, "ban", seat = 0, pile = PileKind.BANISHED), parse("look ban", s))
        assertEquals(Parsed.Ui(UiKind.CLOSE), parse("close", s))
        assertEquals(Parsed.Ui(UiKind.READ, "om1", uid = dm), parse("read om1", s))
        assertEquals(Parsed.Ui(UiKind.READ, "m1", uid = bewd), parse("read m1", s))
        assertEquals(Parsed.Ui(UiKind.READ, "e1", uid = zeus), parse("e1", s))
        assertEquals(Parsed.Query(QueryKind.CARD, arg = "m1", uid = bewd), parse("?m1", s))
        assertEquals(AiCue.NO_RESPONSE, (parse("no response", s) as Parsed.Ui).cue)
        assertEquals(AiCue.YOUR_MOVE, (parse("your move", s) as Parsed.Ui).cue)
        assertEquals(AiCue.PASS, (parse("over to you", s) as Parsed.Ui).cue)
        assertEquals(AiCue.DONE, (parse("done", s) as Parsed.Ui).cue)
        assertEquals(DuelCommand.CUE_CATCH_UP, (parse("catch up", s) as Parsed.Ui).arg)
        assertEquals(DuelCommand.CUE_RESPOND, (parse("respond", s) as Parsed.Ui).arg)
        // "pass" passes priority on a chain, and ends the turn without one, as it always has.
        assertEquals(listOf(DuelAction.EndTurn), actions("pass", s))
        val chain = ok(s, DuelAction.ChainAdd(0, mirror))
        assertEquals(AiCue.PASS, (parse("pass", chain) as Parsed.Ui).cue)
        assertEquals(Parsed.Ui(UiKind.SWAP), parse("swap", s))
        assertEquals(Parsed.Ui(UiKind.UNDO), parse("undo", s))
        assertEquals(Parsed.Ui(UiKind.REDO), parse("redo", s))
        // Questions.
        assertEquals(Parsed.Query(QueryKind.HAND), parse("hand", s))
        assertEquals(Parsed.Query(QueryKind.HAND), parse("read my hand", s))
        assertEquals(Parsed.Query(QueryKind.FIELD, theirs = true), parse("their field", s))
        assertEquals(Parsed.Query(QueryKind.GY), parse("gy", s))
        assertEquals(Parsed.Query(QueryKind.GY, theirs = true), parse("ogy", s))
        assertEquals(Parsed.Query(QueryKind.BANISHED), parse("ban", s))
        assertEquals(Parsed.Query(QueryKind.CHAIN), parse("chain", s))
    }

    @Test
    fun answersSeeOnlyWhatTheAskerSees() {
        fun ask(text: String, seat: Int = 0) = DuelAnswer.answer(parse(text, s, seat) as Parsed.Query, s, seat, catalog)
        assertEquals(
            "Your hand (5): h1 Blue-Eyes White Dragon, h2 Blue-Eyes White Dragon, h3 Pot of Prosperity, h4 Ash Blossom & Joyous Spring, h5 Battle Fader.",
            ask("hand"),
        )
        assertEquals("Their hand: 4 cards.", ask("their hand"))
        val field = ask("their field")
        assertTrue(field.contains("om1: Dark Magician") && field.contains("os1: a face-down card"), field)
        assertFalse(field.contains("Mirror Force"), field)
        // My own set card I know.
        assertTrue(ask("field").contains("s1: Mirror Force"), ask("field"))
        assertTrue(ask("field").contains("ATK 3000"), ask("field"))
        assertEquals("You: 8000 LP. Rival: 8000 LP.", ask("lp"))
        assertEquals("There is no chain.", ask("chain"))
        assertTrue(ask("?m1").startsWith("m1: Blue-Eyes White Dragon, Monster, ATK 3000 DEF 2500, in Attack Position"), ask("?m1"))
        assertEquals("os1: a face-down card in Set.", ask("?os1"))
        assertEquals("Their GY (1): ogy1 Kuriboh.", ask("ogy"))
        // Seat 1 asking: Kai's hand is a count and Kai's set card is face-down.
        assertEquals("Their hand: 5 cards.", ask("their hand", 1))
        assertFalse(ask("their field", 1).contains("Mirror Force"))
        // Nothing hidden is named in any answer, from either seat.
        val hiddenFrom0 = listOf(theirSet) + s.seats[1].hand + s.seats[1].deck + s.seats[0].deck
        listOf(0, 1).forEach { seat ->
            listOf("hand", "their hand", "field", "their field", "board", "gy", "ogy", "ban", "oban", "ex", "oex", "dk", "odk", "lp", "chain", "turn").forEach { q ->
                val a = ask(q, seat)
                if (seat == 0) hiddenFrom0.forEach { uid ->
                    val n = catalog.nameOf(s.cards.getValue(uid))
                    // A name may be said only for a copy the seat can see elsewhere.
                    val seenElsewhere = s.cards.values.any { catalog.nameOf(it) == n && DuelSight.sees(s, it.uid, seat) }
                    if (!seenElsewhere) assertFalse(n in a, "“$q” told seat $seat about $n: $a")
                }
            }
        }
    }

    @Test
    fun thePreviewSaysWhatEnterWouldDoAndNothingMore() {
        val p = DuelCommand.preview("s h1 m3", s, 0, catalog)
        assertTrue(p.ok)
        assertEquals("Summon Blue-Eyes White Dragon from h1 to M3", p.words)
        assertEquals(setOf(handBewd), p.touched)
        assertEquals(listOf<Place>(Place.Zone(0, ZoneKind.MONSTER, 2)), p.dest)
        // Committing the preview's actions is what parse + act does.
        val g0 = DuelGame.of(DuelRecord(CommandFixtures.header()))
        val g = g0.copy(state = s)
        val fromPreview = g.act(p.actions, 0).game.state
        val fromParse = g.act(actions("s h1 m3", s), 0).game.state
        assertEquals(fromParse, fromPreview)
        // A mill, a draw, a shuffle say only what they are.
        assertEquals("Mill 3", DuelCommand.preview("mill 3", s, 0, catalog).words)
        assertEquals("Draw 2", DuelCommand.preview("draw 2", s, 0, catalog).words)
        assertEquals("Look 3", DuelCommand.preview("look 3", s, 0, catalog).words)
        assertTrue(DuelCommand.preview("mill 3", s, 0, catalog).touched.isEmpty())
        // An attack, an activation, a refusal.
        assertEquals("Attack Dark Magician (om1) with Blue-Eyes White Dragon (m1)", DuelCommand.preview("a m1 om1", s, 0, catalog).words)
        assertEquals("Turn Mirror Force in s1 face-up, then Chain Link 1", DuelCommand.preview("a s1", s, 0, catalog).words)
        val full = DuelCommand.preview("s h1 m1", s, 0, catalog)
        assertFalse(full.ok)
        assertEquals("That zone is taken", full.problem)
        // A ; line previews each step.
        val two = DuelCommand.preview("s h1 m3; a m3 om1", s, 0, catalog)
        assertTrue(two.ok)
        assertEquals(2, two.parts.size)
        assertEquals("Summon Blue-Eyes White Dragon from h1 to M3; Attack Dark Magician (om1) with Blue-Eyes White Dragon (m3)", two.words)
    }
}
