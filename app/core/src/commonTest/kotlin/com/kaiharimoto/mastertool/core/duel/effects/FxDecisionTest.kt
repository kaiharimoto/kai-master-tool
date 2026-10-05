package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import com.kaiharimoto.mastertool.core.duel.text.AnswerChooser
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAnswers
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every picker is derived from the engine's decisions alone (kai: "a visually intuitive picker … so the Ai doesn't need to
 * worry about that"): each [Decision] carries what a generic window needs — what the cards are for, where each one is and
 * where they go, which effect asks and which of its steps — and every summon asks its zone and its position, never a
 * default (kai: "What zone they summon to, what position, all matters").
 */
class FxDecisionTest {
    private val ATK = CardPosition.FACE_UP_ATK
    private val DEF = CardPosition.FACE_UP_DEF
    private fun m(i: Int) = Place.Zone(0, ZoneKind.MONSTER, i)

    /** Runs [steps] as [code]'s effect e1 for seat 0, recording every decision, answering the first legal answer or [answer]'s. */
    private fun run(t: FxTable, uid: Int, steps: List<Step>, answer: (Decision) -> List<Int>? = { null }): Pair<FxRun, List<Decision>> {
        val asked = mutableListOf<Decision>()
        val r = FxSteps.run(t, FxAct(0, uid, t.code(uid)!!, "e1", FxTag.RESOLVE), steps, Chooser { d -> asked += d; answer(d) ?: Chooser.FIRST.choose(d) })
        return r to asked
    }

    @Test
    fun aSpecialSummonFromEveryPlaceSaysWhatWhereAndForWhat() {
        val t = FxRef.game(
            Side(
                hand = listOf(FxRef.SCOUT), field = listOf(Slot(FxRef.LAMP, 0)), gy = listOf(FxRef.TINKER), banished = listOf(FxRef.WARDEN),
                deck = listOf(FxRef.PAWN), extra = listOf(FxRef.BRIDGE),
            ),
        ).second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val (scout, tinker, warden, pawn, bridge) = listOf(FxRef.SCOUT, FxRef.TINKER, FxRef.WARDEN, FxRef.PAWN, FxRef.BRIDGE).map { FxRef.uid(t, it) }
        val from = listOf(Spot(Rel.YOU, Area.HAND), Spot(Rel.YOU, Area.DECK), Spot(Rel.YOU, Area.GY), Spot(Rel.YOU, Area.BANISHED), Spot(Rel.YOU, Area.EXTRA))
        // Warden must first be summoned by its own procedure, so it is no candidate; the rest are.
        val (r, asked) = run(t, lamp, listOf(Step(Op.SpecialSummon(Pick(from = from)))), answer = { d -> if (d is Decision.Cards) listOf(d.among.indexOf(scout)) else null })
        assertIs<FxRun.Done>(r)
        val d = asked.filterIsInstance<Decision.Cards>().single()
        assertEquals(Purpose.SUMMON, d.purpose)
        assertEquals(Landing(Dest.MONSTER_ZONE, 0, listOf(ATK, DEF)), d.to, "to the field, face-up in Attack or Defense")
        assertEquals(listOf(scout, pawn, tinker, bridge), d.among, "warden was never properly summoned: not offered")
        assertEquals(
            listOf<Place?>(Place.Pile(0, PileKind.HAND, 0), Place.Pile(0, PileKind.DECK, 0), Place.Pile(0, PileKind.GY, 0), Place.Pile(0, PileKind.EXTRA, 0)),
            d.from, "each candidate's place, to group them by",
        )
        assertTrue(d.hidden, "the Deck and the Extra Deck are looked through")
        assertEquals(FxSource(lamp, "e1", "Send"), d.effect)
        assertEquals("1 of 1", d.step)
        assertFalse(warden in d.among)
        // Then its zone (four free), then its position, each naming the card.
        val zone = asked.filterIsInstance<Decision.Zone>().single()
        assertEquals(scout, zone.card)
        assertEquals(listOf(m(1), m(2), m(3), m(4)), zone.among)
        assertEquals(listOf(ATK, DEF), zone.positions)
        assertEquals(Decision.Position(scout, listOf(ATK, DEF), FxSource(lamp, "e1", "Send")), asked.filterIsInstance<Decision.Position>().single())
    }

    @Test
    fun aLinkMonsterGoesOnlyToAnExtraMonsterZoneOrALinkedOneAndOnlyInAttack() {
        val t = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), extra = listOf(FxRef.BRIDGE))).second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val bridge = FxRef.uid(t, FxRef.BRIDGE)
        val (r, asked) = run(t, lamp, listOf(Step(Op.SpecialSummon(Pick(from = listOf(Spot(Rel.YOU, Area.EXTRA)))))))
        val done = assertIs<FxRun.Done>(r)
        val zone = asked.filterIsInstance<Decision.Zone>().single()
        assertEquals(listOf(Place.Zone(0, ZoneKind.EMZ, 0), Place.Zone(0, ZoneKind.EMZ, 1)), zone.among, "no Link points anywhere yet")
        assertEquals(listOf(ATK), zone.positions)
        assertTrue(asked.none { it is Decision.Position }, "one position: never asked")
        assertEquals(ATK, done.state.cards.getValue(bridge).pos)
        assertEquals(Purpose.SUMMON, asked.filterIsInstance<Decision.Cards>().firstOrNull()?.purpose ?: Purpose.SUMMON)
    }

    @Test
    fun oneLegalZoneOrPositionIsNeverAsked() {
        // Four zones taken: one left. "In Defense Position": one position.
        val t = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0), Slot(FxRef.PAWN, 1), Slot(FxRef.PAWN, 2), Slot(FxRef.PAWN, 3)), gy = listOf(FxRef.TINKER))).second
        val (r, asked) = run(t, FxRef.uid(t, FxRef.LAMP), listOf(Step(Op.SpecialSummon(Pick(from = listOf(Spot(Rel.YOU, Area.GY))), Pos.DEFENSE))))
        val done = assertIs<FxRun.Done>(r)
        assertTrue(asked.isEmpty(), "one card, one zone, one position: nothing to ask ($asked)")
        assertEquals(DuelAction.Move(FxRef.uid(t, FxRef.TINKER), m(4), DEF, "special"), done.actions.single())
    }

    @Test
    fun twoMonstersSummonedAtOnceEachGetTheirOwnZoneAndPosition() {
        val t = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), gy = listOf(FxRef.TINKER, FxRef.SCOUT))).second
        val (tinker, scout) = listOf(FxRef.TINKER, FxRef.SCOUT).map { FxRef.uid(t, it) }
        val (r, asked) = run(t, FxRef.uid(t, FxRef.LAMP), listOf(Step(Op.SpecialSummon(Pick(n = 2, from = listOf(Spot(Rel.YOU, Area.GY))))))) { d ->
            when (d) {
                is Decision.Zone -> listOf(d.among.size - 1)
                is Decision.Position -> listOf(if (d.card == scout) 1 else 0)
                else -> null
            }
        }
        val done = assertIs<FxRun.Done>(r)
        assertEquals(listOf(scout, tinker), asked.filterIsInstance<Decision.Zone>().map { it.card }, "one card at a time, in the order chosen")
        assertEquals(listOf(scout, tinker), asked.filterIsInstance<Decision.Position>().map { it.card })
        assertEquals(listOf(m(4), m(3)), asked.filterIsInstance<Decision.Zone>().map { it.among.last() })
        assertEquals(DEF, done.state.cards.getValue(scout).pos)
        assertEquals(ATK, done.state.cards.getValue(tinker).pos)
    }

    @Test
    fun theOtherPickersSayWhatTheyAreFor() {
        val g = FxRef.game(Side(hand = listOf(FxRef.ECHO, FxRef.SCOUT), field = listOf(Slot(FxRef.LAMP, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0)), banished = listOf(FxRef.TINKER)))
        val t = g.second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        fun purpose(op: Op): Decision.Cards {
            val (_, asked) = run(t, lamp, listOf(Step(op)))
            return asked.filterIsInstance<Decision.Cards>().first()
        }
        val hand = Pick(n = 1, from = listOf(Spot(Rel.YOU, Area.HAND)))
        assertEquals(Purpose.DISCARD to Landing(Dest.GY, 0), purpose(Op.Discard(hand)).let { it.purpose to it.to })
        assertEquals(Purpose.SEND, purpose(Op.Send(hand)).purpose)
        assertEquals(Purpose.REVEAL, purpose(Op.Reveal(hand)).purpose)
        assertEquals(Purpose.RETURN to Landing(Dest.DECK_BOTTOM, 0), purpose(Op.Return(hand, Dest.DECK_BOTTOM)).let { it.purpose to it.to })
        assertFalse(purpose(Op.Send(hand)).hidden, "the chooser's own hand is no pile to look through")
        // A cost's own pick is a cost, its destination still said.
        val cost = FxSteps.run(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.COST), listOf(Step(Op.Discard(hand))), Chooser { d -> (d as Decision.Cards).let { assertEquals(Purpose.COST, it.purpose); assertEquals(Landing(Dest.GY, 0), it.to) }; listOf(0) })
        assertIs<FxRun.Done>(cost)
        // Targets.
        val target = FxSteps.target(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.ACTIVATE), listOf(Pick(from = listOf(Spot(Rel.ANY, Area.MONSTERS)))), Chooser { d ->
            assertEquals(Purpose.TARGET, (d as Decision.Cards).purpose)
            assertEquals(listOf<Place?>(Place.Zone(0, ZoneKind.MONSTER, 0), Place.Zone(1, ZoneKind.MONSTER, 0)), d.from)
            listOf(1)
        })
        assertIs<FxRun.Done>(target)
    }

    @Test
    fun summonsByRuleAskZoneAndPositionAndNameTheirCard() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.SCOUT, FxRef.LAMP), field = listOf(Slot(FxRef.PAWN, 0), Slot(FxRef.TINKER, 1)), deck = listOf(FxRef.PAWN), extra = listOf(FxRef.REGENT, FxRef.BRIDGE))))
        val scout = p.uid(FxRef.SCOUT)
        p.go(0, FxMove.NormalSummon(scout))
        val ns = p.asked.filterIsInstance<Decision.Zone>().single()
        assertEquals(scout to listOf(ATK), ns.card to ns.positions, "a Normal Summon is face-up Attack")
        assertTrue(p.asked.none { it is Decision.Position })
        val yes = p.asked.filterIsInstance<Decision.YesNo>().single()
        assertEquals(FxSource(scout, "e1", "Search"), yes.effect)
        // A Set is face-down Defense.
        val set = FxPlays(FxRef.game(Side(hand = listOf(FxRef.PAWN))))
        set.go(0, FxMove.NormalSummon(set.uid(FxRef.PAWN), set = true))
        assertEquals(listOf(CardPosition.FACE_DOWN_DEF), set.asked.filterIsInstance<Decision.Zone>().single().positions)
        // An Xyz Summon by procedure: its materials, its zone, then its position.
        val x = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.PAWN, 0), Slot(FxRef.SCOUT, 1), Slot(FxRef.WARDEN, 2)), extra = listOf(FxRef.REGENT))))
        val regent = x.uid(FxRef.REGENT)
        x.go(0, FxMove.Procedure(regent, 0)) { d -> if (d is Decision.Cards) listOf(0, 1) else null }
        val mats = x.asked.filterIsInstance<Decision.Cards>().single()
        assertEquals(Purpose.MATERIAL to null, mats.purpose to mats.to, "Xyz materials go beneath it")
        assertEquals(Decision.Position(regent, listOf(ATK, DEF), FxSource(regent, FxTag.PROC, "Xyz Summon")), x.asked.filterIsInstance<Decision.Position>().single())
    }

    @Test
    fun triggersInOrderAreLabelled() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.PURGE), field = listOf(Slot(FxRef.MANDATE, 0), Slot(FxRef.MANDATE, 1)), deck = listOf(FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.PURGE))
        p.passBoth(1)
        val order = p.asked.filterIsInstance<Decision.Order>().single()
        assertEquals(listOf("Example Mandate's Mend", "Example Mandate's Mend"), order.labels)
    }

    @Test
    fun aLineCarriesZonesAndPositionsAndReadsThemBack() {
        val answers = ShortcutAnswers(pick = listOf("Example Tinker"), zone = listOf("m4", "m2"), pos = listOf("def", "atk"))
        assertEquals(" pick=Example Tinker zone=m4,m2 pos=def,atk", answers.words())
        assertEquals(answers, ShortcutAnswers.split(listOf("u", "m1", "e1") + answers.words().trim().split(' ')).second)
        // The chooser pairs each position with the monster zone given before it.
        val t = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), gy = listOf(FxRef.TINKER, FxRef.SCOUT))).second
        val catalog = DuelCatalog { code -> FxRef.cards.firstOrNull { CardId(code) in it.passcodes }?.let { DuelCardInfo.of(it) } }
        fun at(code: Int) = DuelNotation.coordOf(t.state, FxRef.uid(t, code), 0)!!
        val chooser = AnswerChooser(ShortcutAnswers(pick = listOf(at(FxRef.TINKER), at(FxRef.SCOUT)), zone = listOf("m4", "m2"), pos = listOf("def", "atk")), t.state, 0, catalog)
        val r = FxSteps.run(t, FxAct(0, FxRef.uid(t, FxRef.LAMP), FxRef.LAMP, "e1", FxTag.RESOLVE), listOf(Step(Op.SpecialSummon(Pick(n = 2, from = listOf(Spot(Rel.YOU, Area.GY)))))), chooser)
        val done = assertIs<FxRun.Done>(r, chooser.question)
        val tinker = FxRef.uid(t, FxRef.TINKER)
        val scout = FxRef.uid(t, FxRef.SCOUT)
        // Both cards are taken (no choice of which), in the pile's order; each zone given takes its position with it.
        assertEquals(DuelAction.Move(scout, m(3), DEF, "special"), done.actions[0])
        assertEquals(DuelAction.Move(tinker, m(1), ATK, "special"), done.actions[1])
        // A question asked back lists the positions by their words.
        val bare = AnswerChooser(ShortcutAnswers(), t.state, 0, catalog)
        bare.choose(Decision.Position(scout, listOf(ATK, DEF)))
        assertEquals("Which position? Say pos=…, among: atk · def", bare.question)
    }

    @Test
    fun aRecordedShortcutWritesItsZonesAndPositions() {
        val (g, t) = FxRef.game(Side(hand = listOf(FxRef.SEEDS)))
        // Used as a Shortcut with nothing to respond: its activation and resolution one group, as the table commits it.
        val sc = Shortcuts.of(g, FxRef.book, FxRef.facts, resolveAtOnce = true)
        val r = sc.use(g.state, 0, FxRef.uid(t, FxRef.SEEDS), null) { d -> if (d is Decision.Zone) listOf(d.among.size - 1) else Chooser.FIRST.choose(d) }
        assertTrue(r.ok, r.problem)
        val done = r.commit(g, 0, Provenance()).game
        val start = g.state
        val from = g.cursor
        val p = object { val game = done }
        val catalog = DuelCatalog { code -> FxRef.cards.firstOrNull { CardId(code) in it.passcodes }?.let { DuelCardInfo.of(it) } }
        val steps = ComboRecorder.steps(start, p.game.played.drop(from), catalog, 0, p.game.header.seed)
        assertEquals(listOf("u Example Seeds e1 zone=s5,m5,m4 pos=def,def"), steps)
    }
}
