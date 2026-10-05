package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * A few written cards for the Shortcut window's pictures and the table's tests (Phase D §5¾, step 2): fictional cards in
 * the reserved passcode range ([FxVocab.RESERVED], 900000500–900000599, apart from the step-1 reference cards), their
 * facts, and our own scripts for them — the "Gatekeeper" of the window's mockups. Nothing here is a real card's text or
 * another engine's script; the library of real written effects is the `Effects` holder's (step 2, authoring).
 *
 * Each card shows one shape of the window:
 * - [HERALD]: three Shortcuts (which), a summon from four places (pick), up to two at once (pick2, then place each), and a
 *   target on the field or in either GY or banishment (target);
 * - [GATE]: a Link Monster from the Extra Deck, to an Extra Monster Zone or a zone a Link points to (extra);
 * - [TOLL]: two [ECHO]s sent at once, each "you can draw 1": used or skipped, then their order (order);
 * - [OATH]: declare any card's name (declare).
 */
object FxSamples {
    const val HERALD = 900_000_500
    const val VELL = 900_000_501
    const val ORU = 900_000_502
    const val COLOSSUS = 900_000_503
    const val SENTRY = 900_000_504
    const val ARBITER = 900_000_505
    const val GATE = 900_000_506
    const val ECHO = 900_000_507
    const val TOLL = 900_000_508
    const val OATH = 900_000_509
    const val WARDEN = 900_000_510

    private fun monster(
        id: Int, name: String, frame: String, type: String, level: Int?, attr: CardAttribute, race: String, atk: Int, def: Int?,
        text: String, link: Int? = null, arrows: List<String> = emptyList(),
    ) = Card(
        id = CardId(id), name = name, type = type, frameType = frame, description = text, race = race, attribute = attr,
        atk = atk, def = def, level = level, linkValue = link, linkMarkers = arrows,
    )

    private fun spell(id: Int, name: String, sub: String, text: String) = Card(CardId(id), name, "Spell Card", "spell", text, race = sub)

    /** The cards, as the pool would hold them. Their words are our own, and say what each script does. */
    val cards: List<Card> = listOf(
        monster(
            HERALD, "Gatekeeper Herald", "effect", "Effect Monster", 4, CardAttribute.LIGHT, "Warrior", 1700, 1200,
            "Call: Special Summon 1 Level 4 or lower \"Gatekeeper\" monster from your hand, Deck, GY or banishment. " +
                "Rally: Special Summon up to 2 Level 4 or lower \"Gatekeeper\" monsters from your hand or GY. " +
                "Sweep: target up to 2 monsters your opponent controls or cards in either GY or banishment; shuffle them into the Deck. " +
                "You can use each once per turn.",
        ),
        monster(VELL, "Gatekeeper Vell", "effect", "Effect Monster", 4, CardAttribute.DARK, "Fiend", 1500, 1800, "A keeper of the outer gate."),
        monster(ORU, "Gatekeeper Oru", "effect", "Effect Monster", 3, CardAttribute.EARTH, "Rock", 1200, 2000, "A keeper of the inner gate."),
        monster(COLOSSUS, "Gatekeeper Colossus", "effect", "Effect Monster", 8, CardAttribute.DARK, "Dragon", 2800, 2400, "The gate itself, standing."),
        monster(SENTRY, "Thornveil Sentry", "effect", "Effect Monster", 4, CardAttribute.WIND, "Plant", 1800, 1000, "A sentry of the thorn wall."),
        monster(
            ARBITER, "Gatekeeper Arbiter", "link", "Link Effect Monster", null, CardAttribute.LIGHT, "Cyberse", 2000, null,
            "2 \"Gatekeeper\" monsters.", link = 2, arrows = listOf("Bottom-Left", "Bottom-Right"),
        ),
        spell(GATE, "Gatekeeper Gate", "Normal", "Special Summon 1 \"Gatekeeper\" Link Monster from your Extra Deck."),
        monster(ECHO, "Gatekeeper Echo", "effect", "Effect Monster", 2, CardAttribute.LIGHT, "Fairy", 600, 600, "If this card is sent to the GY: you can draw 1 card."),
        spell(TOLL, "Gatekeeper Toll", "Normal", "Send 2 \"Gatekeeper\" monsters from your Deck to the GY."),
        spell(OATH, "Gatekeeper Oath", "Normal", "Declare 1 card name; add 1 card with that name from your Deck to your hand."),
        monster(WARDEN, "Thornveil Warden", "effect", "Effect Monster", 4, CardAttribute.WIND, "Plant", 1400, 1600, "A warden of the thorn wall."),
    )

    private val gatekeeper = Filter.NameHas("Gatekeeper")
    private val monster = Filter.Kind(CardType.MONSTER)
    private fun all(vararg f: Filter) = Filter.All(f.toList())
    private fun you(area: Area) = Spot(Rel.YOU, area)
    private fun steps(vararg op: Op) = op.map { Step(it) }
    private val spellFrom = setOf(Where.HAND, Where.SPELL_ZONE)

    /** The scripts: what each card's words say, in the vocabulary. */
    val scripts: List<CardScript> = listOf(
        CardScript(
            HERALD, name = "Gatekeeper Herald",
            effects = listOf(
                Effect(
                    "e1", "Call", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.ByName(),
                    does = steps(
                        Op.SpecialSummon(
                            Pick(
                                from = listOf(you(Area.HAND), you(Area.DECK), you(Area.GY), you(Area.BANISHED)),
                                where = all(gatekeeper, monster, Filter.Level(Span(max = 4))),
                            ),
                        ),
                    ),
                ),
                Effect(
                    "e2", "Rally", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.ByName(),
                    does = steps(
                        Op.SpecialSummon(
                            Pick(n = 2, upTo = true, from = listOf(you(Area.HAND), you(Area.GY)), where = all(gatekeeper, monster, Filter.Level(Span(max = 4)))),
                        ),
                    ),
                ),
                Effect(
                    "e3", "Sweep", Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), opt = Opt.ByName(),
                    targets = listOf(
                        Pick(n = 2, upTo = true, from = listOf(Spot(Rel.THEM, Area.MONSTERS), Spot(Rel.ANY, Area.GY), Spot(Rel.ANY, Area.BANISHED)), bind = "t"),
                    ),
                    does = steps(Op.Return(Pick(ref = "t"), Dest.DECK_SHUFFLED)),
                ),
            ),
        ),
        CardScript(ARBITER, name = "Gatekeeper Arbiter", summon = SummonRule(normal = false, procs = listOf(Proc.Link(2, 2, each = gatekeeper)))),
        CardScript(
            GATE, name = "Gatekeeper Gate",
            effects = listOf(
                Effect(
                    "e1", "Open", Kind.ACTIVATION, from = spellFrom,
                    does = steps(Op.SpecialSummon(Pick(from = listOf(you(Area.EXTRA)), where = all(gatekeeper, Filter.Frame(CardFrame.LINK))))),
                ),
            ),
        ),
        CardScript(
            ECHO, name = "Gatekeeper Echo",
            effects = listOf(
                Effect(
                    "e1", "Draw", Kind.TRIGGER, from = setOf(Where.GY),
                    trigger = Trigger(On(Event.SENT_TO_GY), timing = Timing.IF, optional = true),
                    does = steps(Op.Draw(1)),
                ),
            ),
        ),
        CardScript(
            TOLL, name = "Gatekeeper Toll",
            effects = listOf(
                Effect("e1", "Toll", Kind.ACTIVATION, from = spellFrom, does = steps(Op.Send(Pick(n = 2, from = listOf(you(Area.DECK)), where = all(gatekeeper, monster))))),
            ),
        ),
        CardScript(
            OATH, name = "Gatekeeper Oath",
            effects = listOf(
                Effect(
                    "e1", "Oath", Kind.ACTIVATION, from = spellFrom,
                    does = listOf(
                        Step(Op.Declare(DeclareKind.NAME, bind = "n")),
                        Step(Op.Add(Pick(from = listOf(you(Area.DECK)), where = Filter.Declared("n"))), Join.THEN),
                    ),
                ),
            ),
        ),
    )

    val facts: FxFacts = FxFacts.of(cards)
    val book: ScriptBook = ScriptBook.all(scripts, facts::canonical)

    /** The cards' kinds as the table reads them, for a table that holds them. */
    val catalog: DuelCatalog = DuelCatalog { code -> cards.firstOrNull { it.id.value == code }?.let(DuelCardInfo::of) }

    /** The written effects of these cards, as a table is handed them; [names] looks a declared name up. */
    fun written(names: ((String) -> List<Int>)? = null, facts: FxFacts = this.facts): Shortcuts =
        Shortcuts.written(book, facts, names = names ?: { w -> cards.filter { it.name.equals(w.trim(), ignoreCase = true) }.map { it.id.value } })

    // ---- the table the window's tests and pictures stand on ---------------------------------------------------------

    /** The uids of [game]'s cards, by the order each seat's were dealt (seat 0 from 1, seat 1 from `SEAT_UIDS` + 1). */
    object U {
        const val HERALD = 1
        const val VELL_HAND = 2
        const val ECHO_HAND = 3
        const val VELL_GY = 4
        const val ORU_BANISHED = 5
        const val TOLL = 6
        const val GATE = 7
        const val OATH = 8
        const val ORU_DECK = 9
        const val COLOSSUS_DECK = 10
        const val VELL_DECK = 11
        const val ECHO_DECK_1 = 12
        const val ECHO_DECK_2 = 13
        const val HERALD_DECK = 14
        const val ARBITER = 15
        const val SENTRY = 1 + DuelState.SEAT_UIDS
        const val WARDEN = 2 + DuelState.SEAT_UIDS
        const val SENTRY_GY = 3 + DuelState.SEAT_UIDS
        const val WARDEN_BANISHED = 4 + DuelState.SEAT_UIDS
    }

    /**
     * A table of these cards, laid out by the table's own moves and committed behind undo. Seat 0 ("Kai"): Herald in M1;
     * Vell, Echo, Toll, Gate and Oath in the hand; a Vell in the GY, an Oru banished; a Deck of Oru, Colossus, Vell, two
     * Echoes and Herald; Arbiter in the Extra Deck. Seat 1 ("Rival"), unless [solo]: Thornveil Sentry in M1, Warden in M2
     * in Defense, a Sentry in their GY, a Warden banished. Turn 2, seat 0's Main Phase 1.
     */
    fun game(solo: Boolean = false, seed: Long = 7L): DuelGame {
        val mine = listOf(HERALD, VELL, ECHO, VELL, ORU, TOLL, GATE, OATH, ORU, COLOSSUS, VELL, ECHO, ECHO, HERALD)
        val theirs = listOf(SENTRY, WARDEN, SENTRY, WARDEN, SENTRY, WARDEN)
        val header = DuelHeader(
            id = "shortcut", seed = seed,
            seats = listOf(SeatSetup("Kai", mine, listOf(ARBITER)), if (solo) SeatSetup("Rival") else SeatSetup("Rival", theirs)),
            handSize = 0, solo = solo, first = if (solo) 0 else 1, openingRoll = false,
        )
        val b = 1 + DuelState.SEAT_UIDS
        val lay = buildList {
            if (!solo) add(DuelAction.EndTurn)
            add(DuelAction.Move(U.HERALD, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "place"))
            listOf(U.VELL_HAND, U.ECHO_HAND, U.TOLL, U.GATE, U.OATH).forEach { add(DuelAction.Move(it, Place.Pile(0, PileKind.HAND), how = "place")) }
            add(DuelAction.Move(U.VELL_GY, Place.Pile(0, PileKind.GY), how = "place"))
            add(DuelAction.Move(U.ORU_BANISHED, Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "place"))
            if (!solo) {
                add(DuelAction.Move(b, Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "place"))
                add(DuelAction.Move(b + 1, Place.Zone(1, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_DEF, "place"))
                add(DuelAction.Move(b + 2, Place.Pile(1, PileKind.GY), how = "place"))
                add(DuelAction.Move(b + 3, Place.Pile(1, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "place"))
            }
            add(DuelAction.Phase(DuelPhase.MAIN1))
        }
        val dealt = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val r = dealt.act(lay, null)
        check(r.problem == null) { "the sample table lays out: ${r.problem}" }
        return r.game.copy(floor = r.game.cursor)
    }
}
