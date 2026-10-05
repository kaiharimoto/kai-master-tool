package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * The reference cards (Phase D step 1): fictional cards in the reserved passcode range ([FxVocab.RESERVED],
 * 900000000–900000999), their facts as the pool would hold them, and our own scripts for them — enough to exercise the
 * vocabulary, the rules and every procedure. Agents (b) and (c) reuse them for the chain and the steps; nothing here is a
 * real card's text or another engine's script.
 *
 * The "Example" archetype is D.md §2.5½'s. Card 900000001 is D.md's example card, written as its sketch says.
 */
object FxRef {
    const val SCOUT = 900_000_001
    const val SCOUT_ALT = 900_000_101
    const val LAMP = 900_000_002
    const val COLOSSUS = 900_000_003
    const val KNIGHT = 900_000_004
    const val WARDEN = 900_000_005
    const val PAWN = 900_000_006
    const val SPRITE = 900_000_007
    const val TINKER = 900_000_008
    const val SWING = 900_000_009
    const val BRIDGE = 900_000_010
    const val ARCH = 900_000_011
    const val SPIDER = 900_000_012
    const val PALADIN = 900_000_020
    const val REGENT = 900_000_021
    const val CHIMERA = 900_000_022
    const val ORACLE = 900_000_023
    const val FUSION = 900_000_030
    const val RITE = 900_000_031
    const val FLASH = 900_000_032
    const val DENIAL = 900_000_033
    const val CALL = 900_000_034
    const val RALLY = 900_000_035
    const val SEEDS = 900_000_036
    const val EDICT = 900_000_037
    const val CROSSROADS = 900_000_038
    const val SNARE = 900_000_039

    // Agents (b) and (c): the chain, the triggers and the steps.
    const val ECHO = 900_000_040
    const val MANDATE = 900_000_041
    const val EMBER = 900_000_042
    const val PURGE = 900_000_043
    const val MILL = 900_000_044
    const val SIEVE = 900_000_045
    const val OFFERING = 900_000_046
    const val WARD = 900_000_047
    const val OATH = 900_000_048
    const val LORD = 900_000_049
    const val BEACON = 900_000_050

    private fun monster(
        id: Int, name: String, frame: String, type: String, level: Int?, attr: CardAttribute, race: String, atk: Int, def: Int?,
        link: Int? = null, arrows: List<String> = emptyList(), scale: Int? = null, alts: List<Int> = emptyList(),
    ) = Card(
        id = CardId(id), name = name, type = type, frameType = frame, race = race, attribute = attr, atk = atk, def = def,
        level = level, linkValue = link, linkMarkers = arrows, pendulumScale = scale, alternateIds = alts.map(::CardId),
    )

    private fun spell(id: Int, name: String, sub: String) = Card(CardId(id), name, "Spell Card", "spell", race = sub)
    private fun trap(id: Int, name: String, sub: String) = Card(CardId(id), name, "Trap Card", "trap", race = sub)

    val cards: List<Card> = listOf(
        monster(SCOUT, "Example Scout", "effect", "Effect Monster", 4, CardAttribute.LIGHT, "Warrior", 1600, 1000, alts = listOf(SCOUT_ALT)),
        monster(LAMP, "Example Lamp", "effect", "Tuner Effect Monster", 3, CardAttribute.LIGHT, "Spellcaster", 1000, 1000),
        monster(COLOSSUS, "Example Colossus", "effect", "Effect Monster", 7, CardAttribute.DARK, "Dragon", 2600, 2000),
        monster(KNIGHT, "Example Knight", "effect", "Effect Monster", 6, CardAttribute.EARTH, "Warrior", 2100, 1500),
        monster(WARDEN, "Example Warden", "effect", "Effect Monster", 4, CardAttribute.DARK, "Fiend", 1800, 0),
        monster(PAWN, "Example Pawn", "normal", "Normal Monster", 4, CardAttribute.EARTH, "Warrior", 1500, 1200),
        monster(SPRITE, "Plain Sprite", "normal", "Normal Tuner Monster", 2, CardAttribute.WIND, "Fairy", 800, 400),
        monster(TINKER, "Example Tinker", "effect", "Effect Monster", 3, CardAttribute.FIRE, "Machine", 1200, 800),
        monster(SWING, "Example Swing", "effect_pendulum", "Pendulum Effect Monster", 4, CardAttribute.WIND, "Spellcaster", 1400, 1400, scale = 4),
        monster(BRIDGE, "Example Bridge", "link", "Link Monster", null, CardAttribute.LIGHT, "Cyberse", 1500, null, link = 2, arrows = listOf("Bottom-Left", "Bottom-Right")),
        monster(ARCH, "Example Arch", "link", "Link Effect Monster", null, CardAttribute.DARK, "Cyberse", 2300, null, link = 3, arrows = listOf("Top", "Left", "Right")),
        monster(SPIDER, "Example Spider", "link", "Link Monster", null, CardAttribute.EARTH, "Insect", 800, null, link = 1, arrows = listOf("Bottom")),
        monster(PALADIN, "Example Paladin", "synchro", "Synchro Effect Monster", 7, CardAttribute.LIGHT, "Warrior", 2500, 2000),
        monster(REGENT, "Example Regent", "xyz", "Xyz Effect Monster", 4, CardAttribute.DARK, "Warrior", 2200, 1800),
        monster(CHIMERA, "Example Chimera", "fusion", "Fusion Effect Monster", 8, CardAttribute.LIGHT, "Beast", 2800, 2400),
        monster(ORACLE, "Example Oracle", "ritual", "Ritual Effect Monster", 6, CardAttribute.LIGHT, "Spellcaster", 2300, 2000),
        spell(FUSION, "Example Fusion", "Normal"),
        spell(RITE, "Example Rite", "Ritual"),
        spell(FLASH, "Example Flash", "Quick-Play"),
        trap(DENIAL, "Example Denial", "Counter"),
        spell(CALL, "Example Call", "Normal"),
        spell(RALLY, "Example Rally", "Normal"),
        spell(SEEDS, "Example Seeds", "Normal"),
        spell(EDICT, "Example Edict", "Continuous"),
        spell(CROSSROADS, "Example Crossroads", "Normal"),
        trap(SNARE, "Example Snare", "Normal"),
        monster(ECHO, "Example Echo", "effect", "Effect Monster", 3, CardAttribute.DARK, "Fiend", 1100, 900),
        monster(MANDATE, "Example Mandate", "effect", "Effect Monster", 2, CardAttribute.EARTH, "Rock", 600, 1500),
        monster(EMBER, "Example Ember", "effect", "Effect Monster", 3, CardAttribute.FIRE, "Pyro", 1300, 500),
        spell(PURGE, "Example Purge", "Normal"),
        spell(MILL, "Example Mill", "Normal"),
        spell(SIEVE, "Example Sieve", "Normal"),
        spell(OFFERING, "Example Offering", "Normal"),
        monster(WARD, "Example Ward", "effect", "Tuner Effect Monster", 3, CardAttribute.LIGHT, "Fairy", 0, 1800),
        spell(OATH, "Example Oath", "Normal"),
        monster(LORD, "Example Lord", "xyz", "Xyz Effect Monster", 4, CardAttribute.LIGHT, "Warrior", 2400, 2000),
        monster(BEACON, "Example Beacon", "effect", "Effect Monster", 4, CardAttribute.WATER, "Aqua", 1500, 1500),
    )

    private fun you(area: Area) = Spot(Rel.YOU, area)
    private fun them(area: Area) = Spot(Rel.THEM, area)
    private val example = Filter.NameHas("Example")
    private val monster = Filter.Kind(CardType.MONSTER)
    private fun all(vararg f: Filter) = Filter.All(f.toList())
    private fun steps(vararg op: Op) = op.map { Step(it) }
    private val fromSpellOrHand = setOf(Where.HAND, Where.SPELL_ZONE)

    val scripts: List<CardScript> = listOf(
        // D.md §2.5½'s example, as its sketch reads.
        CardScript(
            SCOUT, name = "Example Scout",
            effects = listOf(
                Effect(
                    "e1", "Search", Kind.TRIGGER, from = setOf(Where.MONSTER_ZONE),
                    trigger = Trigger(On(Event.SUMMONED, summon = listOf(ProcKind.NORMAL, ProcKind.SPECIAL)), optional = true),
                    opt = Opt.ByName(),
                    does = steps(Op.Add(Pick(from = listOf(you(Area.DECK)), where = all(example, monster, Filter.Level(Span(1, 4)))))),
                ),
                Effect(
                    "e2", "Bounce", Kind.QUICK, from = setOf(Where.GY), opt = Opt.ByName(), condition = Cond.Turn(Rel.THEM),
                    cost = steps(Op.Banish(Pick(ref = Pick.SELF))),
                    targets = listOf(Pick(from = listOf(them(Area.MONSTERS)), where = Filter.FaceUp, bind = "t")),
                    does = steps(Op.Return(Pick(ref = "t"), Dest.HAND)),
                ),
            ),
        ),
        CardScript(
            LAMP, name = "Example Lamp",
            summon = SummonRule(procs = listOf(Proc.Inherent(Where.HAND, condition = Cond.NoMonsters(Rel.YOU), opt = Opt.ByName()))),
            effects = listOf(
                Effect(
                    "e1", "Send", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.ByName(),
                    does = steps(Op.Send(Pick(from = listOf(you(Area.DECK)), where = example))),
                ),
            ),
        ),
        CardScript(COLOSSUS, name = "Example Colossus"),
        // "Can be Normal Summoned without Tributing": its rule says none.
        CardScript(KNIGHT, name = "Example Knight", summon = SummonRule(tributes = 0)),
        CardScript(
            WARDEN, name = "Example Warden",
            summon = SummonRule(
                normal = false, mustFirstBe = ProcKind.INHERENT, oncePerTurn = true,
                procs = listOf(Proc.Inherent(Where.HAND, condition = Cond.Controls(example), pos = Pos.DEFENSE)),
            ),
        ),
        CardScript(
            TINKER, name = "Example Tinker",
            effects = listOf(
                Effect(
                    "e1", "Tune", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.PerCopy,
                    targets = listOf(Pick(from = listOf(you(Area.MONSTERS)), where = all(Filter.FaceUp, Filter.Level(Span(min = 1))), bind = "t")),
                    does = steps(Op.ChangeLevel(Pick(ref = "t"), by = Num.Const(1))),
                ),
            ),
        ),
        CardScript(SWING, name = "Example Swing"),
        CardScript(BRIDGE, name = "Example Bridge", summon = SummonRule(normal = false, procs = listOf(Proc.Link(2, 2)))),
        CardScript(
            ARCH, name = "Example Arch",
            summon = SummonRule(normal = false, procs = listOf(Proc.Link(2, 3, each = Filter.Frame(CardFrame.EFFECT), also = example))),
        ),
        CardScript(SPIDER, name = "Example Spider", summon = SummonRule(normal = false, procs = listOf(Proc.Link(1, 1, each = Filter.Level(Span(max = 4)))))),
        CardScript(
            PALADIN, name = "Example Paladin",
            summon = SummonRule(normal = false, procs = listOf(Proc.Synchro(Mat(1, Filter.Frame(CardFrame.TUNER)), Mat(1, Filter.Not(Filter.Frame(CardFrame.TUNER)), more = true)))),
        ),
        CardScript(REGENT, name = "Example Regent", summon = SummonRule(normal = false, procs = listOf(Proc.Xyz(2, max = 3)))),
        CardScript(
            CHIMERA, name = "Example Chimera",
            summon = SummonRule(
                normal = false, mustFirstBe = ProcKind.FUSION,
                procs = listOf(Proc.Fusion(listOf(Mat(1, Filter.Name(SCOUT)), Mat(1, Filter.Attribute(setOf(CardAttribute.LIGHT)))))),
            ),
        ),
        CardScript(ORACLE, name = "Example Oracle", summon = SummonRule(normal = false, mustFirstBe = ProcKind.RITUAL, procs = listOf(Proc.Ritual))),
        CardScript(
            FUSION, name = "Example Fusion",
            effects = listOf(Effect("e1", "Fuse", Kind.ACTIVATION, from = fromSpellOrHand, does = steps(Op.FusionSummon(all(Filter.Frame(CardFrame.FUSION), example))))),
        ),
        CardScript(
            RITE, name = "Example Rite",
            effects = listOf(Effect("e1", "Rite", Kind.ACTIVATION, from = fromSpellOrHand, does = steps(Op.RitualSummon(Filter.Name(ORACLE))))),
        ),
        CardScript(
            FLASH, name = "Example Flash",
            effects = listOf(
                Effect(
                    "e1", "Destroy", Kind.ACTIVATION, from = fromSpellOrHand,
                    targets = listOf(Pick(from = listOf(Spot(Rel.ANY, Area.MONSTERS)), where = all(Filter.FaceUp, monster), bind = "t")),
                    does = steps(Op.Destroy(Pick(ref = "t"))),
                ),
            ),
        ),
        CardScript(
            DENIAL, name = "Example Denial",
            effects = listOf(
                Effect(
                    "e1", "Negate", Kind.ACTIVATION, from = setOf(Where.SPELL_ZONE),
                    respond = Respond(Rel.THEM, includes = listOf(Includes.SPECIAL_SUMMON)),
                    cost = steps(Op.PayLp(Num.Const(1000))),
                    does = listOf(Step(Op.Negate(NegWhat.ACTIVATION, bind = "n")), Step(Op.Destroy(Pick(ref = "n")), Join.AND_IF_YOU_DO)),
                ),
            ),
        ),
        CardScript(
            CALL, name = "Example Call",
            effects = listOf(
                Effect(
                    "e1", "Call", Kind.ACTIVATION, from = fromSpellOrHand, opt = Opt.ByName(group = Opt.CARD),
                    does = steps(Op.SpecialSummon(Pick(from = listOf(you(Area.DECK)), where = all(example, monster, Filter.Level(Span(max = 4)))))),
                    leaves = listOf(Restriction(Ban.SPECIAL_SUMMON_FROM_EXTRA, except = Filter.Frame(CardFrame.SYNCHRO))),
                ),
            ),
        ),
        CardScript(RALLY, name = "Example Rally", effects = listOf(Effect("e1", "Rally", Kind.ACTIVATION, from = fromSpellOrHand, does = steps(Op.NormalSummonAgain(example))))),
        CardScript(
            SEEDS, name = "Example Seeds",
            effects = listOf(Effect("e1", "Seeds", Kind.ACTIVATION, from = fromSpellOrHand, does = steps(Op.Token("Seed Token", CardAttribute.EARTH, "Plant", 1, 0, 0, n = 2, pos = Pos.DEFENSE)))),
        ),
        CardScript(
            EDICT, name = "Example Edict",
            effects = listOf(
                Effect("e1", "Activate", Kind.ACTIVATION, from = fromSpellOrHand),
                Effect("e2", "No summons", Kind.CONTINUOUS, from = setOf(Where.SPELL_ZONE), leaves = listOf(Restriction(Ban.SPECIAL_SUMMON, seat = Rel.THEM, except = example))),
            ),
        ),
        CardScript(
            CROSSROADS, name = "Example Crossroads",
            effects = listOf(
                Effect(
                    "e1", "Choose", Kind.ACTIVATION, from = fromSpellOrHand,
                    does = steps(
                        Op.Choose(
                            listOf(steps(Op.Draw(1)), steps(Op.Lp(Rel.YOU, Num.Const(1000)))),
                            labels = listOf("Draw 1", "Gain 1000 LP"),
                        ),
                        Op.If(Cond.Controls(example, Num.Const(2)), then = steps(Op.Draw(1)), otherwise = emptyList()),
                    ),
                ),
            ),
        ),
        CardScript(
            SNARE, name = "Example Snare",
            effects = listOf(
                Effect(
                    "e1", "Snare", Kind.ACTIVATION, from = setOf(Where.SPELL_ZONE),
                    targets = listOf(Pick(from = listOf(them(Area.MONSTERS)), bind = "t")),
                    does = steps(Op.Banish(Pick(ref = "t"))),
                ),
            ),
        ),
    )

    /** The chain's and the steps' reference cards (agents (b) and (c)): triggers of each timing, costs, a hand trap, a declaration. */
    private val chainScripts: List<CardScript> = listOf(
        // "When this card is sent to the GY: you can draw 1." An optional WHEN trigger: it can miss the timing.
        CardScript(
            ECHO, name = "Example Echo",
            effects = listOf(
                Effect(
                    "e1", "Draw", Kind.TRIGGER, from = setOf(Where.GY), opt = Opt.ByName(),
                    trigger = Trigger(On(Event.SENT_TO_GY), timing = Timing.WHEN, optional = true),
                    does = steps(Op.Draw(1)),
                ),
            ),
        ),
        // "If this card is sent to the GY: gain 500 LP." Mandatory, IF: never misses the timing.
        CardScript(
            MANDATE, name = "Example Mandate",
            effects = listOf(
                Effect(
                    "e1", "Mend", Kind.TRIGGER, from = setOf(Where.GY),
                    trigger = Trigger(On(Event.SENT_TO_GY), timing = Timing.IF, optional = false),
                    does = steps(Op.Lp(Rel.YOU, Num.Const(500))),
                ),
            ),
        ),
        // "If this card is sent to the GY by a card effect: you can inflict 300 damage." Not by a cost.
        CardScript(
            EMBER, name = "Example Ember",
            effects = listOf(
                Effect(
                    "e1", "Rekindle", Kind.TRIGGER, from = setOf(Where.GY),
                    trigger = Trigger(On(Event.SENT_TO_GY, cause = Cause.EFFECT), timing = Timing.IF, optional = true),
                    does = steps(Op.Lp(Rel.THEM, Num.Const(-300))),
                ),
            ),
        ),
        // Destroy every monster on the field.
        CardScript(
            PURGE, name = "Example Purge",
            effects = listOf(
                Effect(
                    "e1", "Purge", Kind.ACTIVATION, from = fromSpellOrHand, condition = Cond.Controls(monster, seat = Rel.ANY),
                    does = steps(Op.Destroy(Pick(all = true, from = listOf(Spot(Rel.ANY, Area.MONSTERS))))),
                ),
            ),
        ),
        // Send 1 Example monster from the Deck to the GY, then gain 100 LP: the send is no longer last.
        CardScript(
            MILL, name = "Example Mill",
            effects = listOf(
                Effect(
                    "e1", "Mill", Kind.ACTIVATION, from = fromSpellOrHand,
                    does = listOf(Step(Op.Send(Pick(from = listOf(you(Area.DECK)), where = all(example, monster)))), Step(Op.Lp(Rel.YOU, Num.Const(100)), Join.THEN)),
                ),
            ),
        ),
        // The same, at the same time: the send stays last.
        CardScript(
            SIEVE, name = "Example Sieve",
            effects = listOf(
                Effect(
                    "e1", "Sieve", Kind.ACTIVATION, from = fromSpellOrHand,
                    does = listOf(Step(Op.Send(Pick(from = listOf(you(Area.DECK)), where = all(example, monster)))), Step(Op.Lp(Rel.YOU, Num.Const(100)), Join.AND)),
                ),
            ),
        ),
        // Cost: discard 1 monster; draw 1.
        CardScript(
            OFFERING, name = "Example Offering",
            effects = listOf(
                Effect(
                    "e1", "Offer", Kind.ACTIVATION, from = fromSpellOrHand,
                    cost = steps(Op.Discard(Pick(from = listOf(you(Area.HAND)), where = monster))),
                    does = steps(Op.Draw(1)),
                ),
            ),
        ),
        // From the hand, when the opponent activates an effect that adds from the Deck: discard this card; negate that effect.
        CardScript(
            WARD, name = "Example Ward",
            effects = listOf(
                Effect(
                    "e1", "Ward", Kind.QUICK, from = setOf(Where.HAND), opt = Opt.ByName(),
                    respond = Respond(Rel.THEM, includes = listOf(Includes.SEARCH)),
                    cost = steps(Op.Discard(Pick(ref = Pick.SELF))),
                    does = steps(Op.Negate(NegWhat.EFFECT)),
                ),
            ),
        ),
        // Declare an Attribute; add 1 Example monster of it from the Deck.
        CardScript(
            OATH, name = "Example Oath",
            effects = listOf(
                Effect(
                    "e1", "Oath", Kind.ACTIVATION, from = fromSpellOrHand,
                    does = listOf(
                        Step(Op.Declare(DeclareKind.ATTRIBUTE, bind = "a")),
                        Step(Op.Add(Pick(from = listOf(you(Area.DECK)), where = all(example, monster, Filter.Declared("a")))), Join.THEN),
                    ),
                ),
            ),
        ),
        // An Xyz: detach 1 to draw 1; attach an Example monster from your GY.
        CardScript(
            LORD, name = "Example Lord",
            summon = SummonRule(normal = false, procs = listOf(Proc.Xyz(2))),
            effects = listOf(
                Effect("e1", "Study", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.PerCopy, cost = steps(Op.Detach(1)), does = steps(Op.Draw(1))),
                Effect("e2", "Gather", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.PerCopy, does = steps(Op.Attach(Pick(from = listOf(you(Area.GY)), where = all(example, monster))))),
            ),
        ),
        // "If an Example monster is Special Summoned to your field: you can Special Summon this card from your hand." About another card.
        CardScript(
            BEACON, name = "Example Beacon",
            effects = listOf(
                Effect(
                    "e1", "Answer", Kind.TRIGGER, from = setOf(Where.HAND), opt = Opt.ByName(),
                    trigger = Trigger(On(Event.SPECIAL_SUMMONED), self = false, about = all(example, monster, Filter.Controller(Rel.YOU)), timing = Timing.IF),
                    does = steps(Op.SpecialSummon(Pick(ref = Pick.SELF))),
                ),
            ),
        ),
    )

    val facts: FxFacts = FxFacts.of(cards)
    val book: ScriptBook = ScriptBook.all(scripts + chainScripts, facts::canonical)

    fun card(code: Int): Card = cards.first { code in it.passcodes.map(CardId::value) }

    /** A card in a zone: [index] 0 is m1 (or the EMZ's left), in [pos]. */
    data class Slot(val code: Int, val index: Int, val pos: CardPosition = CardPosition.FACE_UP_ATK, val kind: ZoneKind = ZoneKind.MONSTER)

    /** One seat's cards for a test table. [extra] are the Extra Deck's; a slot of an Extra Deck monster comes from there too. */
    data class Side(
        val hand: List<Int> = emptyList(),
        val field: List<Slot> = emptyList(),
        val gy: List<Int> = emptyList(),
        val banished: List<Int> = emptyList(),
        val deck: List<Int> = emptyList(),
        val extra: List<Int> = emptyList(),
    )

    /**
     * A table laid out by the table's own moves: [you] seat 0, [them] seat 1, turn [turn] (seat [active]'s), in [phase].
     * The Deck keeps its written order, top first.
     */
    fun table(you: Side, them: Side = Side(), turn: Int = 2, active: Int = 0, phase: DuelPhase = DuelPhase.MAIN1, book: ScriptBook = this.book): FxTable {
        val (header, lay) = layout(you, them, turn, active, phase)
        val (next, problem) = DuelRules.applyAll(DuelSetup.initial(header), lay)
        val state = next ?: error("the test table does not lay out: $problem")
        return FxTable(state, FxState.at(state), book, facts)
    }

    /**
     * The same table as a duel in play: its layout committed as the table's own entries (`seat` null, behind undo), and the
     * engine's state folded from that log ([FxFold.fold]) with the duel's [seed] — what the fold and physics tests start from.
     */
    fun game(you: Side, them: Side = Side(), turn: Int = 2, active: Int = 0, phase: DuelPhase = DuelPhase.MAIN1, book: ScriptBook = this.book, seed: Long = 7L): Pair<DuelGame, FxTable> {
        val (h, lay) = layout(you, them, turn, active, phase)
        val header = h.copy(seed = seed)
        val laid = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0).act(lay, null)
        check(laid.ok) { "the test table does not lay out: ${laid.problem}" }
        val g = laid.game.copy(floor = laid.game.cursor)
        return g to FxTable(g.state, FxFold.fold(header, g.played, book, facts, g.state), book, facts, seed)
    }

    /** The deal and the table's own moves that lay [you] and [them] out. */
    private fun layout(you: Side, them: Side, turn: Int, active: Int, phase: DuelPhase): Pair<DuelHeader, List<DuelAction>> {
        val sides = listOf(you, them)
        fun ed(code: Int) = facts[code]?.extraDeck == true
        val setups = sides.map { s ->
            val fieldMain = s.field.filter { !ed(it.code) }.map { it.code }
            val fieldExtra = s.field.filter { ed(it.code) }.map { it.code }
            SeatSetup(main = s.hand + fieldMain + s.gy + s.banished + s.deck, extra = fieldExtra + s.extra)
        }
        // Whoever went first, seat [active] has turn [turn].
        val header = DuelHeader(seats = setups, handSize = 0, first = (active + turn - 1) % 2)
        val lay = ArrayList<DuelAction>()
        repeat(turn - 1) { lay += DuelAction.EndTurn }
        sides.forEachIndexed { seat, s ->
            val base = 1 + seat * 1000
            var k = 0
            s.hand.forEach { _ -> lay += DuelAction.Move(base + k++, Place.Pile(seat, PileKind.HAND), how = "place") }
            val mainSlots = s.field.filter { !ed(it.code) }
            mainSlots.forEach { slot -> lay += DuelAction.Move(base + k++, Place.Zone(seat, slot.kind, slot.index), slot.pos, "place") }
            s.gy.forEach { _ -> lay += DuelAction.Move(base + k++, Place.Pile(seat, PileKind.GY), how = "place") }
            s.banished.forEach { _ -> lay += DuelAction.Move(base + k++, Place.Pile(seat, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "place") }
            var e = base + setups[seat].main.size
            s.field.filter { ed(it.code) }.forEach { slot -> lay += DuelAction.Move(e++, Place.Zone(seat, slot.kind, slot.index), slot.pos, "place") }
        }
        lay += DuelAction.Phase(phase)
        return header to lay
    }

    /** The uids of [seat]'s copies of [code] (any printing), in uid order. */
    fun uids(t: FxTable, code: Int, seat: Int = 0): List<Int> =
        t.state.cards.values.filter { it.owner == seat && t.book.canonical(it.code) == t.book.canonical(code) }.map { it.uid }.sorted()

    fun uid(t: FxTable, code: Int, seat: Int = 0): Int = uids(t, code, seat).first()

    /** A chooser that answers from a queue, failing loudly when asked what it was not told. */
    class Answers(vararg answers: (Decision) -> List<Int>) : Chooser {
        private val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<Decision>()
        override fun choose(d: Decision): List<Int> {
            asked += d
            val next = queue.removeFirstOrNull() ?: error("not expecting $d")
            return next(d)
        }
    }
}
