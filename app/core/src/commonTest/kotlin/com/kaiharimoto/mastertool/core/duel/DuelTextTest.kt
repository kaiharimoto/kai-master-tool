package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ASH
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.CALLED
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.DROLL
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.POT
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ZEUS
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.duel.text.NameScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DuelTextTest {
    /** Seat 0 holds Ash, Droll, Pot and Called (unshuffled), Zeus in the Extra Deck. */
    private val table: DuelState = ok(DuelFixtures.bare(), DuelAction.Draw(0, 4))
    private val ash = uid(0, 0)
    private val droll = uid(0, 1)
    private val pot = uid(0, 2)
    private val called = uid(0, 3)
    private val zeus = uid(0, 40)

    private fun run(text: String, s: DuelState = table, seat: Int = 0): List<DuelAction> =
        when (val p = DuelCommand.parse(text, s, seat, catalog)) {
            is DuelCommand.Parsed.Actions -> p.actions
            is DuelCommand.Parsed.Problem -> error("“$text”: ${p.text}")
            is DuelCommand.Parsed.Ruling -> error("“$text” is a ruling")
        }

    private fun problem(text: String, s: DuelState = table): String =
        (DuelCommand.parse(text, s, 0, catalog) as DuelCommand.Parsed.Problem).text

    @Test
    fun namesMatchTheWayPlayersShortenThem() {
        assertTrue(NameScore.of("ash", "Ash Blossom & Joyous Spring") > 0)
        assertTrue(NameScore.of("abjs", "Ash Blossom & Joyous Spring") > 0)
        assertTrue(NameScore.of("called by", "Called by the Grave") > 0)
        assertTrue(NameScore.of("zeus", "Divine Arsenal AA-ZEUS - Sky Thunder") > 0)
        assertEquals(0, NameScore.of("xyzzy", "Called by the Grave"))
        assertTrue(NameScore.of("Called by the Grave", "Called by the Grave") > NameScore.of("called", "Called by the Grave"))
    }

    @Test
    fun theLineMovesCardsWhereTheySay() {
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.GY), null, "send")), run("ash to gy"))
        assertEquals(listOf(DuelAction.Move(droll, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal")), run("summon droll to m3"))
        assertEquals(listOf(DuelAction.Move(droll, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_DEF, "normal")), run("summon droll def"))
        assertEquals(listOf(DuelAction.Move(called, Place.Zone(0, ZoneKind.SPELL, 2), CardPosition.FACE_DOWN_ATK, "set")), run("set called by"))
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.DECK, Place.BOTTOM), null, "return")), run("ash to deck bottom"))
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "banish")), run("/banish ash"))
        val activate = run("activate pot")
        assertEquals(DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 2), CardPosition.FACE_UP_ATK, "activate"), activate[0])
        assertEquals(DuelAction.ChainAdd(0, pot), activate[1])
        // A hand trap: discarded as its cost and put on the chain.
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.GY), null, "activate"), DuelAction.ChainAdd(0, ash)), run("chain ash"))
    }

    @Test
    fun theOwnersDeckIsSearchableByNameButNotTheOpponents() {
        val s = ok(table, DuelAction.Move(ash, Place.Pile(0, PileKind.DECK)))
        // A search shows the card to the other player (1.0.79): it is known from then on.
        assertEquals(listOf(DuelAction.Move(ash, Place.Pile(0, PileKind.HAND), null, "search"), DuelAction.Reveal(0, listOf(ash))), run("ash to hand", s))
        // Seat 1 cannot name seat 0's hand or deck.
        assertIs<DuelCommand.Parsed.Problem>(DuelCommand.parse("droll to gy", table, 1, catalog))
    }

    @Test
    fun theLineDoesTheTableToo() {
        assertEquals(listOf(DuelAction.Draw(0, 2)), run("draw 2"))
        assertEquals(3, run("mill 3").size)
        assertEquals(listOf(DuelAction.Lp(0, delta = -1000)), run("lp -1000"))
        assertEquals(listOf(DuelAction.Lp(1, set = 4000)), run("lp opp /2"))
        assertEquals(listOf(DuelAction.Lp(0, set = 4000)), run("lp =4000"))
        assertEquals(listOf(DuelAction.Lp(0, delta = 500)), run("lp +500"))
        assertEquals(listOf(DuelAction.Phase(DuelPhase.BATTLE)), run("bp"))
        assertEquals(listOf(DuelAction.EndTurn), run("end"))
        assertEquals(listOf(DuelAction.Coin(0)), run("coin"))
        assertEquals(2, run("token 2").size)
        assertEquals(listOf(DuelAction.Reveal(0, table.seats[0].deck.take(3), to = 0)), run("look 3"))
    }

    @Test
    fun attachingNamesTheCardUnderneath() {
        val s = ok(table, DuelAction.Move(zeus, Place.Zone(0, ZoneKind.MONSTER, 0)))
        assertEquals(listOf(DuelAction.Move(ash, Place.Under(zeus), null, "attach")), run("attach ash to zeus", s))
    }

    @Test
    fun aProblemIsSaidNotThrown() {
        assertTrue(problem("frobnicate the widget").startsWith("No card of yours you can see"))
        assertTrue(problem("lp banana").contains("not an LP change"))
        val full = (0..4).fold(table) { s, i -> ok(s, DuelAction.Token(0, Place.Zone(0, ZoneKind.MONSTER, i))) }
        assertEquals("No free Monster Zone", problem("summon droll", full))
    }

    @Test
    fun theLogSaysWhatEachSeatSaw() {
        val set = DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK, "set")
        val after = ok(table, set)
        val e = DuelEntry(0, 0, 0, 0, set)
        assertEquals("Kai sets Pot of Prosperity in S/T 2", DuelWords.say(table, after, e, 0, catalog))
        assertEquals("Kai sets a card in S/T 2", DuelWords.say(table, after, e, 1, catalog))

        val summon = DuelAction.Move(ash, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal")
        assertEquals(
            "Kai Normal Summons Ash Blossom & Joyous Spring to M3",
            DuelWords.say(table, ok(table, summon), DuelEntry(0, 0, 0, 0, summon), 1, catalog),
        )
        val draw = DuelAction.Draw(1, 1)
        val drawn = ok(table, draw)
        assertEquals("Rival draws a card", DuelWords.say(table, drawn, DuelEntry(0, 0, 1, 0, draw), 0, catalog))
        val lp = DuelAction.Lp(1, delta = -1000)
        assertEquals("Rival loses 1000 LP (7000)", DuelWords.say(table, ok(table, lp), DuelEntry(0, 0, 0, 0, lp), null, catalog))
    }

    @Test
    fun theDefaultVerbFitsTheCardAndWhereItIs() {
        assertEquals(DuelVerb.SUMMON, DuelVerbs.default(table, 0, ash, catalog))
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(table, 0, pot, catalog))
        assertEquals(DuelVerb.SUMMON, DuelVerbs.default(table, 0, zeus, catalog))
        val set = ok(table, DuelAction.Move(called, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK))
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(set, 0, called, catalog))
        // Activating a set card flips it and opens a chain link.
        assertEquals(
            listOf(DuelAction.Position(called, CardPosition.FACE_UP_ATK), DuelAction.ChainAdd(0, called)),
            DuelVerbs.actions(set, 0, called, DuelVerb.DEFAULT, catalog).actions,
        )
        // The opponent's card on the field is pointed at.
        assertEquals(DuelVerb.TARGET, DuelVerbs.default(set, 1, called, catalog))
        // A free zone nearest the middle.
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 2), DuelVerbs.nearestFree(table, 0, ZoneKind.MONSTER))
        assertTrue(DuelVerbs.offered(table, 0, ash, catalog).first() == DuelVerb.SUMMON)
        assertTrue(listOf(ASH, DROLL, POT, CALLED, ZEUS).all { catalog.info(it) != null })
    }
}
