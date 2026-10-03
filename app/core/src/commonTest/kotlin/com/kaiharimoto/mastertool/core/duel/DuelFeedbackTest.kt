package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every case in Ai's playtest of 1.0.77 that did the wrong thing, or nothing (1.0.79), held here so it
 * never comes back: names reach your own cards where a player would reach, a zone named is where the
 * card goes, and the table can place, resolve, tally and ask.
 */
class DuelFeedbackTest {
    private val E4 = 201
    private val ENDGAME = 202
    private val BACK = 203
    private val ANGEL = 204
    private val ASHENED = 205

    private val catalog = DuelCatalog { code ->
        when (code) {
            E4 -> DuelCardInfo("Emblema Four", CardKind.SPELL, sub = "Continuous")
            ENDGAME -> DuelCardInfo("Endgame Problem", CardKind.FIELD_SPELL, sub = "Field")
            BACK -> DuelCardInfo("Back to Square One", CardKind.SPELL, sub = "Normal")
            ANGEL -> DuelCardInfo("Angelechy", CardKind.MONSTER)
            ASHENED -> DuelCardInfo("Ashened Herald", CardKind.MONSTER)
            else -> DuelFixtures.catalog.info(code)
        }
    }

    /** Seat 0's deck, unshuffled: e4, e4, Endgame, Back to Square One, Angelechy, Ash, Ashened, filler. Seat 1's starts with Endgame. */
    private val table: DuelState = DuelSetup.initial(
        DuelHeader(
            id = "f",
            seed = 7L,
            seats = listOf(
                SeatSetup("Kai", main = listOf(E4, E4, ENDGAME, BACK, ANGEL, DuelFixtures.ASH, ASHENED) + List(33) { DuelFixtures.FILLER }, extra = listOf(DuelFixtures.LINK)),
                SeatSetup("Ai", main = listOf(ENDGAME) + List(39) { DuelFixtures.FILLER }),
            ),
        ),
    )
    private val e4a = uid(0, 0)
    private val e4b = uid(0, 1)
    private val endgame = uid(0, 2)
    private val back = uid(0, 3)
    private val angel = uid(0, 4)
    private val theirEndgame = uid(1, 0)

    private fun parse(text: String, s: DuelState, seat: Int = 0) = DuelCommand.parse(text, s, seat, catalog)

    private fun run(text: String, s: DuelState, seat: Int = 0): List<DuelAction> = when (val p = parse(text, s, seat)) {
        is DuelCommand.Parsed.Actions -> p.actions
        is DuelCommand.Parsed.Problem -> error("“$text”: ${p.text}")
        is DuelCommand.Parsed.Ruling -> error("“$text” is a ruling")
    }

    private fun play(s: DuelState, actions: List<DuelAction>, by: Int = 0): DuelState = actions.fold(s) { st, a -> ok(st, a, by) }

    @Test
    fun aNameReachesYourOwnCardsNotTheirFieldSpell() {
        // Ai: "Endgame Problem to hand" took the opponent's face-up Field Spell.
        val s = ok(table, DuelAction.Move(theirEndgame, Place.Zone(1, ZoneKind.FIELD, 0), CardPosition.FACE_UP_ATK, "activate"), 1)
        val moves = run("endgame problem to hand", s)
        assertEquals(DuelAction.Move(endgame, Place.Pile(0, PileKind.HAND), null, "search"), moves.first())
        // Theirs only when asked for.
        assertEquals(theirEndgame, (run("their endgame problem to gy", s).first() as DuelAction.Move).uid)
    }

    @Test
    fun aSearchReachesTheDeckBeforeTheGy() {
        // Ai: "e4 to hand" took the GY copy, not the Deck's.
        val s = ok(table, DuelAction.Move(e4a, Place.Pile(0, PileKind.GY), how = "send"))
        assertEquals(e4b, (run("emblema four to hand", s).first() as DuelAction.Move).uid)
    }

    @Test
    fun aZoneNamedIsWhereTheCardGoes() {
        // Ai: "#1045 to s2" logged an activation and moved nothing.
        val placed = run("#$angel to s2", table)
        assertEquals(listOf(DuelAction.Move(angel, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_UP_ATK, "place")), placed)
        // Ai: "X to fz" logged "activates a card" and moved nothing.
        assertEquals(DuelAction.Move(endgame, Place.Zone(0, ZoneKind.FIELD, 0), CardPosition.FACE_UP_ATK, "place"), run("endgame problem to fz", table).single())
        // Ai: "set #1045 to s2" set it in M3.
        assertEquals(DuelAction.Move(angel, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK, "set"), run("set #$angel to s2", table).single())
        // "place … as continuous", and "place … in field".
        assertEquals(Place.Zone(0, ZoneKind.SPELL, 2), (run("place angelechy in s3 as continuous", table).single() as DuelAction.Move).to)
        assertEquals(Place.Zone(0, ZoneKind.FIELD, 0), (run("place endgame problem in field", table).single() as DuelAction.Move).to)
        // Activated from the Deck into a zone: moved there and chained.
        val act = run("activate endgame problem from deck to fz", table)
        assertEquals(Place.Zone(0, ZoneKind.FIELD, 0), (act[0] as DuelAction.Move).to)
        assertEquals(DuelAction.ChainAdd(0, endgame), act[1])
        // A card on the field moves to the zone named.
        val onField = play(table, placed)
        assertEquals(DuelAction.Move(angel, Place.Zone(0, ZoneKind.SPELL, 3), null, "move"), run("move angelechy to s4", onField).single())
    }

    @Test
    fun theExtraMonsterZonesAreYourOwnLeftAndRight() {
        assertEquals(Place.Zone(0, ZoneKind.EMZ, 0), DuelCommand.zoneOf("emz left", 0))
        assertEquals(Place.Zone(1, ZoneKind.EMZ, 1), DuelCommand.zoneOf("emz left", 1))
        assertEquals(Place.Zone(0, ZoneKind.EMZ, 1), DuelCommand.zoneOf("emzright", 0))
        assertEquals(Place.Zone(1, ZoneKind.EMZ, 0), DuelCommand.zoneOf("right emz", 1))
        // The old words keep their meaning, so combos written before still replay.
        assertEquals(Place.Zone(1, ZoneKind.EMZ, 0), DuelCommand.zoneOf("el", 1))
        assertEquals(Place.Zone(1, ZoneKind.EMZ, 1), DuelCommand.zoneOf("emz2", 1))
        assertTrue(DuelWords.zoneName(Place.Zone(0, ZoneKind.EMZ, 0), table).contains("Kai's left (Ai's right)"))
    }

    @Test
    fun aNameWithToInItStaysWhole() {
        assertEquals(back, (run("back to square one to hand", table).first() as DuelAction.Move).uid)
    }

    @Test
    fun aNameThatCouldMeanTwoCardsIsAskedNotGuessed() {
        val p = parse("ash to hand", table)
        assertIs<DuelCommand.Parsed.Problem>(p)
        assertTrue(p.text.contains("Ash Blossom") && p.text.contains("Ashened Herald"), p.text)
    }

    @Test
    fun aNormalSpellGoesToTheGyAsItResolvesAContinuousOneStays() {
        val hand = play(table, listOf(DuelAction.Draw(0, 4)))
        val normal = play(hand, run("activate back to square one", hand))
        val resolved = run("resolve", normal)
        assertEquals(listOf(DuelAction.ChainResolve, DuelAction.Move(back, Place.Pile(0, PileKind.GY), how = "resolve")), resolved)
        assertEquals(listOf(DuelAction.ChainResolve), run("resolve keep", normal))
        val cont = play(hand, run("activate #$e4a", hand))
        assertEquals(listOf(DuelAction.ChainResolve), run("resolve", cont))
    }

    @Test
    fun aTokenHasItsStatsPositionAndSide() {
        val t = run("token sheep atk 6500 def 8500 def m2", table).single() as DuelAction.Token
        assertEquals(6500, t.atk)
        assertEquals(8500, t.def)
        assertEquals(CardPosition.FACE_UP_DEF, t.pos)
        assertEquals("Sheep", t.name)
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 1), t.to)
        val theirs = run("token their", table).single() as DuelAction.Token
        assertEquals(1, theirs.to.seat)
        val s = ok(table, t)
        assertEquals(6500, s.cards.values.single { it.token }.atk)
    }

    @Test
    fun theOtherSeatAsksToMoveThePhaseAndTheTurnPlayerAnswers() {
        val asked = run("sp", table, seat = 1)
        assertEquals(listOf(DuelAction.Propose(1, DuelPhase.STANDBY)), asked)
        val s = play(table, asked, by = 1)
        assertEquals(Proposal(1, DuelPhase.STANDBY), s.proposal)
        val yes = play(s, run("accept", s))
        assertEquals(DuelPhase.STANDBY, yes.phase)
        assertNull(yes.proposal)
        assertNull(play(s, run("decline", s)).proposal)
        // From the End Phase, next is the next turn.
        val end = ok(table, DuelAction.Phase(DuelPhase.END))
        assertEquals(listOf(DuelAction.EndTurn), run("next", end))
    }

    @Test
    fun locksAreWrittenDownAndLapse() {
        val s = play(table, run("lock Synchro Monsters only until chain", table))
        assertEquals(listOf(Lock(1, 0, "Synchro Monsters only", Lock.UNTIL_CHAIN)), s.locks)
        val turnLock = play(s, run("lock 1 Trap a turn", s))
        assertEquals(2, turnLock.locks.size)
        assertTrue(ok(ok(turnLock, DuelAction.ChainAdd(0, null)), DuelAction.ChainResolve).locks.none { it.until == Lock.UNTIL_CHAIN })
        assertTrue(ok(turnLock, DuelAction.EndTurn).locks.isEmpty())
        assertEquals(1, play(turnLock, run("unlock 1", turnLock)).locks.size)
    }

    @Test
    fun theTurnIsTallied() {
        val header = DuelHeader(id = "g", seed = 7L, seats = listOf(SeatSetup("Kai", main = listOf(ANGEL, ANGEL, ANGEL) + List(37) { DuelFixtures.FILLER }), SeatSetup("Ai", main = List(40) { DuelFixtures.FILLER })))
        // Unshuffled, so the three Angelechy are the draws.
        var g = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        fun act(text: String) { g = g.act(run(text, g.state), 0).game }
        act("draw 3")
        act("summon angelechy to m1")
        act("ss angelechy to m2")
        act("link angelechy")
        val t = DuelTally.of(g, catalog)
        assertEquals(1, t.normal[0])
        assertEquals(1, t.special[0])
        assertEquals(mapOf("Angelechy" to 1), t.activations[0])
        assertTrue(t.words(g.state).first().contains("2 Summons"))
    }

    @Test
    fun aRulingIsKeptNotPlayed() {
        val p = parse("ruling emblema four: no free zone, can't activate", table)
        assertEquals(DuelCommand.Parsed.Ruling(E4, "Emblema Four", "no free zone, can't activate"), p)
        val book = HouseRulingBook().add(HouseRuling("r1", "no free zone, can't activate", E4, "Emblema Four"))
        assertEquals(book, HouseRulingCodec.decode(HouseRulingCodec.encode(book)))
    }

    @Test
    fun aHandTrapDroppedOnTheChainIsDiscardedAndChained() {
        val hand = play(table, listOf(DuelAction.Draw(0, 6)))
        val ash = uid(0, 5)
        val intent = DuelDrop.intent(hand, ash, DropSpot.Chain, catalog)
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.GY), how = "activate"), DuelAction.ChainAdd(0, ash)), intent.actions)
    }

    @Test
    fun seatsAreNamedAndOldDefaultNamesReadAsPlayers() {
        val s = table.copy(seats = table.seats.mapIndexed { i, st -> st.copy(name = if (i == 0) "You" else "Opponent") })
        assertEquals("Player 1", DuelWords.seatName(s, 0))
        assertEquals("Player 2", DuelWords.seatName(s, 1))
        assertEquals("Seat 1 (Ai)", DuelWords.seatLabel(table, 1))
        assertEquals("Marcus'", DuelWords.possessive("Marcus"))
    }
}
