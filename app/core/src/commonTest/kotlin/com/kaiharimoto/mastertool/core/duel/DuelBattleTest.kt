package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attacking as a verb (1.0.86): the Battle Phase's obvious thing for an attacker, armed and then aimed;
 * the battle chip's arithmetic; and the score column as somewhere to aim a direct attack.
 */
class DuelBattleTest {
    private val BEAST = 301 // 2500 / 2000
    private val WALL = 302 // 1000 / 2500
    private val TWIN = 303 // 2500 / 1000
    private val SPARK = 304 // 1800 / 0
    private val NONE = 305 // no numbers known

    private val catalog = DuelCatalog { code ->
        when (code) {
            BEAST -> DuelCardInfo("Beast", CardKind.MONSTER, atk = 2500, def = 2000)
            WALL -> DuelCardInfo("Wall", CardKind.MONSTER, atk = 1000, def = 2500)
            TWIN -> DuelCardInfo("Twin", CardKind.MONSTER, atk = 2500, def = 1000)
            SPARK -> DuelCardInfo("Spark", CardKind.MONSTER, atk = 1800, def = 0)
            NONE -> DuelCardInfo("Mystery", CardKind.MONSTER)
            else -> DuelFixtures.catalog.info(code)
        }
    }

    private val header = DuelHeader(
        id = "b",
        seed = 3L,
        seats = listOf(
            SeatSetup("Kai", main = listOf(BEAST, SPARK, NONE) + List(37) { DuelFixtures.FILLER }),
            SeatSetup("Rival", main = listOf(WALL, TWIN, SPARK) + List(37) { DuelFixtures.FILLER }),
        ),
    )
    private val beast = uid(0, 0)
    private val spark = uid(0, 1)
    private val mystery = uid(0, 2)
    private val wall = uid(1, 0)
    private val twin = uid(1, 1)
    private val theirSpark = uid(1, 2)

    private fun summon(s: DuelState, uid: Int, seat: Int, zone: Int, pos: CardPosition = CardPosition.FACE_UP_ATK) =
        ok(s, DuelAction.Move(uid, Place.Zone(seat, ZoneKind.MONSTER, zone), pos, "special"), seat)

    /** Beast and Spark on seat 0's field; Wall in Defense, Twin and Spark in Attack on seat 1's; the Battle Phase. */
    private fun battle(): DuelState {
        var s = DuelSetup.initial(header)
        s = summon(s, beast, 0, 1)
        s = summon(s, spark, 0, 2)
        s = summon(s, wall, 1, 1, CardPosition.FACE_UP_DEF)
        s = summon(s, twin, 1, 2)
        s = summon(s, theirSpark, 1, 3)
        return ok(s, DuelAction.Phase(DuelPhase.BATTLE))
    }

    @Test
    fun inTheBattlePhaseTheObviousThingForAnAttackerIsToAttack() {
        val s = battle()
        assertEquals(DuelVerb.ATTACK, DuelVerbs.default(s, 0, beast, catalog))
        assertEquals(DuelVerb.ATTACK, DuelVerbs.offered(s, 0, beast, catalog).first())
        // Armed, it waits for what it attacks; aimed, it is the declaration a drag makes.
        assertTrue(DuelVerbs.actions(s, 0, beast, DuelVerb.DEFAULT, catalog).needsTarget)
        assertEquals(listOf(DuelAction.Attack(0, beast, twin)), DuelVerbs.actions(s, 0, beast, DuelVerb.ATTACK, catalog, host = twin).actions)
        assertEquals(listOf(DuelAction.Attack(0, beast, null)), DuelVerbs.actions(s, 0, beast, DuelVerb.ATTACK, catalog, direct = true).actions)
        // Not the turn player's monster: its obvious thing is still to activate; theirs is still a target.
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(s, 1, twin, catalog))
        assertEquals(DuelVerb.TARGET, DuelVerbs.default(s, 0, twin, catalog))
        // Outside the Battle Phase, or in Defense Position, it does not attack.
        val main2 = ok(s, DuelAction.Phase(DuelPhase.MAIN2))
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(main2, 0, beast, catalog))
        assertFalse(DuelVerb.ATTACK in DuelVerbs.offered(main2, 0, beast, catalog))
        assertTrue(DuelVerbs.actions(main2, 0, beast, DuelVerb.ATTACK, catalog).problem != null)
        val guarding = ok(s, DuelAction.Position(beast, CardPosition.FACE_UP_DEF))
        assertFalse(DuelVerb.ATTACK in DuelVerbs.offered(guarding, 0, beast, catalog))
        // The line asks what it attacks rather than doing nothing.
        val asked = DuelCommand.parse("beast", s, 0, catalog)
        assertIs<DuelCommand.Parsed.Problem>(asked)
        assertTrue(asked.text.startsWith("Attack what"))
    }

    @Test
    fun theScoreColumnTakesADirectAttack() {
        val s = battle()
        assertEquals(listOf(DuelAction.Attack(0, beast, null)), DuelDrop.intent(s, beast, DropSpot.Score(1), catalog).actions)
        assertEquals("Attack directly", DuelDrop.intent(s, beast, DropSpot.Score(1), catalog).label)
        // Your own life points, or outside the Battle Phase, take nothing.
        assertTrue(DuelDrop.intent(s, beast, DropSpot.Score(0), catalog).none)
        assertTrue(DuelDrop.intent(ok(s, DuelAction.Phase(DuelPhase.MAIN2)), beast, DropSpot.Score(1), catalog).none)
    }

    @Test
    fun attackAgainstAttackDamagesTheWeakersController() {
        val s = battle()
        // Beast 2500 into Spark 1800: 700 to the Rival, Spark destroyed.
        val win = DuelBattle.outcome(s, Attack(0, beast, theirSpark), catalog)!!
        assertEquals(1, win.damaged)
        assertEquals(700, win.damage)
        assertEquals(listOf(theirSpark), win.destroyed)
        assertEquals(listOf("Apply 700 to Rival", "Destroy Spark"), DuelBattle.words(s, win, catalog))
        // Spark 1800 into Twin 2500: 700 to Kai, Spark destroyed.
        val lose = DuelBattle.outcome(s, Attack(0, spark, twin), catalog)!!
        assertEquals(0, lose.damaged)
        assertEquals(700, lose.damage)
        assertEquals(listOf(spark), lose.destroyed)
        // Beast 2500 into Twin 2500: both destroyed, no damage.
        val tie = DuelBattle.outcome(s, Attack(0, beast, twin), catalog)!!
        assertNull(tie.damaged)
        assertEquals(setOf(beast, twin), tie.destroyed.toSet())
    }

    @Test
    fun attackAgainstDefenseNeverPierces() {
        var s = battle()
        // Beast 2500 into Wall's 2500 DEF: nothing happens.
        assertTrue(DuelBattle.outcome(s, Attack(0, beast, wall), catalog)!!.nothing)
        // Spark 1800 into it: 700 back to Kai, nothing destroyed.
        val bounced = DuelBattle.outcome(s, Attack(0, spark, wall), catalog)!!
        assertEquals(0, bounced.damaged)
        assertEquals(700, bounced.damage)
        assertTrue(bounced.destroyed.isEmpty())
        // Twin in Defense (1000) under Beast: destroyed, no damage — piercing is the players' to apply.
        s = ok(s, DuelAction.Position(twin, CardPosition.FACE_UP_DEF), 1)
        val through = DuelBattle.outcome(s, Attack(0, beast, twin), catalog)!!
        assertNull(through.damaged)
        assertEquals(listOf(twin), through.destroyed)
    }

    @Test
    fun aDirectAttackIsTheAttackersAtk() {
        val o = DuelBattle.outcome(battle(), Attack(0, beast, null), catalog)!!
        assertEquals(1, o.damaged)
        assertEquals(2500, o.damage)
        assertTrue(o.destroyed.isEmpty())
    }

    @Test
    fun unknownNumbersSuggestNothing() {
        var s = battle()
        // A face-down monster attacked.
        s = ok(s, DuelAction.Position(wall, CardPosition.FACE_DOWN_DEF), 1)
        assertNull(DuelBattle.outcome(s, Attack(0, beast, wall), catalog))
        // An attacker the catalog has no numbers for.
        s = summon(s, mystery, 0, 3)
        assertNull(DuelBattle.outcome(s, Attack(0, mystery, null), catalog))
        // A token's own numbers count.
        val token = ok(s, DuelAction.Token(0, Place.Zone(0, ZoneKind.MONSTER, 4), CardPosition.FACE_UP_ATK, name = "Sheep Token", atk = 0, def = 0), 0)
        val sheep = token.onField().first { token.cards.getValue(it).token }
        assertEquals(0, DuelBattle.outcome(token, Attack(0, sheep, null), catalog)!!.damage)
        // A monster gone from the field: no suggestion.
        val gone = ok(s, DuelAction.Move(beast, Place.Pile(0, PileKind.GY), how = "send"))
        assertNull(DuelBattle.outcome(gone, Attack(0, beast, twin), catalog))
    }

    @Test
    fun theChipStandsUntilTheNextMoveAndAppliesAsOneGroup() {
        var g = DuelGame(header, emptyList(), 0, battle(), 0)
        assertNull(DuelBattle.pending(g, catalog))
        g = g.act(DuelAction.Attack(0, beast, theirSpark), 0).game
        val o = assertNotNull(DuelBattle.pending(g, catalog))
        // Talk does not put it away.
        g = g.act(DuelAction.Chat(1, "ouch"), 1).game
        assertNotNull(DuelBattle.pending(g, catalog))
        val before = g.state
        g = g.act(DuelBattle.actions(g.state, o), 0).game
        assertEquals(before.seats[1].lp - 700, g.state.seats[1].lp)
        assertTrue(theirSpark in g.state.seats[1].gy)
        assertEquals(1, g.played.map { it.group }.takeLast(2).toSet().size)
        assertNull(DuelBattle.pending(g, catalog))
        val said = DuelWords.say(before, g.state, g.played.last(), 0, catalog)
        assertTrue(said.contains("destroyed by battle"), said)
        // A new attack declared, then anything else: gone.
        g = g.act(DuelAction.Attack(0, spark, twin), 0).game
        assertNotNull(DuelBattle.pending(g, catalog))
        g = g.act(DuelAction.ChainAdd(1, twin), 1).game
        assertNull(DuelBattle.pending(g, catalog))
    }

    @Test
    fun anOpenPileOfTheirsIsTargetOnlyUnlessYouPlayBothSeats() {
        val s = battle()
        val hotSeat = DuelPrefs()
        assertTrue(DuelSeats.playsBoth(hotSeat, networked = false, aiSeated = false))
        assertFalse(DuelSeats.playsBoth(hotSeat, networked = true, aiSeated = false))
        assertFalse(DuelSeats.playsBoth(hotSeat, networked = false, aiSeated = true))
        assertFalse(DuelSeats.playsBoth(hotSeat.copy(knowledge = DuelPrefs.KNOW_SEAT), networked = false, aiSeated = false))
        assertTrue(DuelSeats.stripPlays(s, pileSeat = 0, bottom = 0, playsBoth = false))
        assertFalse(DuelSeats.stripPlays(s, pileSeat = 1, bottom = 0, playsBoth = false))
        assertTrue(DuelSeats.stripPlays(s, pileSeat = 1, bottom = 0, playsBoth = true))
    }
}
