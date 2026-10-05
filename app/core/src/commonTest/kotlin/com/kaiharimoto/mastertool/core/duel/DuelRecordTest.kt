package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.record.AiPlay
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResultCodec
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.record.ResultSeat
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase C, stage 1: every move says who made it, and a finished duel is a result that can be counted. */
class DuelRecordTest {
    private val person = Provenance(Provenance.PERSON, aiSeat = 1, aiKnows = DuelBrief.SELF, eyes = DuelPrefs.KNOW_SEAT)
    private val ai = Provenance(Provenance.AI, aiSeat = 1, aiKnows = DuelBrief.SELF, eyes = DuelPrefs.KNOW_SEAT)

    private fun acted(g: DuelGame, a: DuelAction, seat: Int, by: Provenance?): DuelGame {
        val r = g.act(a, seat, by = by)
        assertTrue(r.ok, r.problem)
        return r.game
    }

    /**
     * A two-seat duel with the dice, the person at seat 0 and Ai at seat 1, where seat [goes] has turn 1 whoever won:
     * the winner goes first when it is [goes], else second.
     */
    private fun rolled(goes: Int, seed: Long = 42L): DuelGame {
        var g = DuelGame.start(header(seed = seed).copy(openingRoll = true))
        while (g.state.opening?.winner == null) {
            g = acted(g, DuelAction.OpeningRoll(0), 0, person)
            g = acted(g, DuelAction.OpeningRoll(1), 1, ai)
        }
        val w = g.state.opening!!.winner!!
        return acted(g, DuelAction.GoFirst(w, first = w == goes), w, if (w == 1) ai else person)
    }

    @Test
    fun everyMoveCarriesWhoMadeItAndAiMovesTheFingerprintOfWhatItSaw() {
        val g0 = DuelGame.start(header())
        // The deal is the table's own: no one's.
        assertTrue(g0.entries.all { it.by == null })
        val g1 = acted(g0, DuelAction.Lp(0, -100), 0, person.copy(view = "forged", peeks = 9))
        val p = assertNotNull(g1.entries.last().by)
        assertEquals(Provenance.PERSON, p.by)
        assertNull(p.view, "only Ai's moves carry a fingerprint")
        assertNull(p.peeks)
        val before = g1.state
        val g2 = acted(g1, DuelAction.Lp(1, -100), 1, ai)
        val a = assertNotNull(g2.entries.last().by)
        assertTrue(a.byAi)
        // The view Ai acted on — its own seat's eyes on the table before the move — as a hash, never the cards.
        assertEquals(Provenance.viewHash(before, 1, g2.header.seed), a.view)
        assertEquals(16, a.view!!.length)
        assertEquals(0, a.peeks)
        // Full knowledge acts on everything: another view, another fingerprint.
        val full = acted(g1, DuelAction.Lp(1, -100), 1, ai.copy(aiKnows = DuelBrief.FULL)).entries.last().by!!
        assertEquals(Provenance.viewHash(before, null, g2.header.seed), full.view)
        assertTrue(full.view != a.view)
        // A peek is counted for the moves after it.
        val peeked = acted(g2, DuelAction.Note("Ai looked at their hand: Nibiru?", 1), 1, ai.copy(peek = true))
        assertEquals(1, acted(peeked, DuelAction.Lp(1, -50), 1, ai).entries.last().by!!.peeks)
    }

    @Test
    fun provenanceIsWrittenAndReadBackAndSurvivesUndoAndInsertion() {
        var g = acted(DuelGame.start(header()), DuelAction.Lp(1, -100), 1, ai)
        g = acted(g, DuelAction.Chat(0, "hi"), 0, person)
        val back = DuelGame.of(assertNotNull(DuelCodec.decode(DuelCodec.encode(g.record()))))
        assertEquals(g.entries.map { it.by }, back.entries.map { it.by })
        // Undo that keeps talk moves entries about: who made them goes with them.
        val undone = g.undoMove()
        assertEquals(Provenance.PERSON, undone.entries.first { it.action is DuelAction.Chat }.by?.by)
        // A move put into the past is stamped too, on the table it went into.
        val r = Replays.insert(g.record(), g.floor, listOf(DuelAction.Lp(1, -10)), 1, by = ai)
        val put = r.entries[g.floor]
        assertTrue(put.by!!.byAi)
        assertEquals(Provenance.viewHash(g.stateAt(g.floor), 1, g.header.seed), put.by!!.view)
    }

    @Test
    fun aFinishedDuelIsAResultReadOffItsLog() {
        var g = rolled(goes = 1)
        assertNull(DuelResults.of(g, 1L), "a duel in play is no result yet")
        g = acted(g, DuelAction.Lp(1, -300), 1, ai)
        g = acted(g, DuelAction.Lp(0, -8000), 1, ai)
        val r = assertNotNull(DuelResults.of(g, 99L))
        assertEquals(1, r.winner)
        assertEquals(DuelResult.LP, r.how)
        assertEquals(1, r.first)
        assertEquals(DuelResult.ROLL, r.firstBy)
        assertTrue(r.rolls.isNotEmpty() && r.rolls.all { it.size == 2 })
        assertEquals(g.state.opening!!.winner, r.rollWinner)
        assertEquals(listOf(Provenance.PERSON, Provenance.AI)[r.rollWinner!!], r.chosenBy)
        val play = assertNotNull(r.ai)
        assertEquals(1, play.seat)
        assertEquals(DuelBrief.SELF, play.knows)
        assertTrue(play.clean)
        assertEquals(Provenance.AI, r.player(1))
        assertEquals(Provenance.PERSON, r.player(0))
        assertEquals(DuelPrefs.KNOW_SEAT, r.eyes)
        assertEquals("Kai", r.seats[0].name)
        // Written and read back as a record file.
        assertEquals(r, DuelResultCodec.decode(DuelResultCodec.encode(r)))
        assertEquals("records/t.json", DuelResultCodec.path(r.id))
    }

    @Test
    fun aConcessionAndADrawEndADuel() {
        val g = DuelGame.start(header())
        assertEquals(1 to DuelResult.CONCEDE, DuelResults.ending(acted(g, DuelAction.Concede(0), 0, person).state))
        val both = acted(acted(g, DuelAction.Lp(0, set = 0), 0, person), DuelAction.Lp(1, set = 0), 0, person)
        assertEquals(null to DuelResult.DRAW, DuelResults.ending(both.state))
        // One player's table never ends as a result.
        assertNull(DuelResults.ending(DuelGame.start(header(solo = true)).state.copy(conceded = 0)))
    }

    @Test
    fun prepLogsGoingFirstFromTheRollNotFromTheSeat() {
        // The red team: "Duel games are logged to Prep with the wrong first/second whenever the opening roll decided it" —
        // it said seat 0 always went first. Here the person sits at seat 1 and the dice give seat 1 turn 1.
        val g = acted(rolled(goes = 1), DuelAction.Concede(0), 0, ai.copy(by = Provenance.PERSON))
        assertEquals(1, g.state.active)
        val game = assertNotNull(DuelResults.practice(g, me = 1, id = "g1", at = 5L, opponent = "d0", opponentName = "Rival", deckId = "d1", note = ""))
        assertEquals(TestGame.FIRST, game.turn)
        assertEquals(TestGame.WIN, game.result)
        // And seat 0, going second though it is seat 0.
        assertEquals(TestGame.SECOND, DuelResults.practice(g, me = 0, id = "g2", at = 5L, opponent = "d1", opponentName = "Kai", deckId = "d0", note = "")!!.turn)
        // A duel with no roll: the table's first seat goes first, as before.
        val plain = acted(DuelGame.start(header().copy(first = 1)), DuelAction.Concede(1), 1, person)
        assertEquals(TestGame.FIRST, DuelResults.practice(plain, me = 1, id = "g3", at = 0L, opponent = "x", opponentName = "x", deckId = null, note = "")!!.turn)
        assertEquals(TestGame.LOSS, DuelResults.practice(plain, me = 1, id = "g3", at = 0L, opponent = "x", opponentName = "x", deckId = null, note = "")!!.result)
    }

    private fun result(
        id: String,
        winner: Int?,
        knows: String = DuelBrief.SELF,
        eyes: String? = DuelPrefs.KNOW_SEAT,
        person: String = "kai",
        whatIf: Boolean = false,
        peeks: Int = 0,
        helped: Int = 0,
        guest: Boolean = false,
    ) = DuelResult(
        id = id,
        duel = id,
        seats = listOf(
            ResultSeat(person, player = if (guest) Provenance.GUEST else Provenance.PERSON),
            ResultSeat("Ai", player = Provenance.AI),
        ),
        first = 1,
        firstBy = DuelResult.ROLL,
        winner = winner,
        how = if (winner == null) DuelResult.DRAW else DuelResult.LP,
        ai = AiPlay(seat = 1, knows = knows, peeks = peeks, moves = 10, helped = helped),
        eyes = eyes,
        net = guest,
        whatIf = whatIf,
    )

    @Test
    fun aiWonNOfMAgainstAPersonWithTheseSettings() {
        val results = listOf(
            result("a", winner = 1),
            result("b", winner = 0),
            result("c", winner = 1),
            result("d", winner = null),
            // Other settings are counted apart: full knowledge, the person seeing both hands, a peek.
            result("e", winner = 1, knows = DuelBrief.FULL),
            result("f", winner = 1, eyes = DuelPrefs.KNOW_ALL),
            result("g", winner = 0, knows = DuelBrief.AUTO, peeks = 2),
            // Not games: a what-if, and a person against a person.
            result("h", winner = 1, whatIf = true),
            DuelResult(id = "i", seats = listOf(ResultSeat("kai", player = Provenance.PERSON), ResultSeat("Rin", player = Provenance.PERSON)), winner = 0),
            // Someone else.
            result("j", winner = 0, person = "Rin"),
        )
        val kai = DuelResults.aiAgainst(results, person = "KAI")
        assertEquals(4, kai.size)
        val plain = kai.first { it.settings.knows == DuelBrief.SELF && it.settings.eyes == DuelPrefs.KNOW_SEAT }
        assertEquals(2, plain.won)
        assertEquals(1, plain.lost)
        assertEquals(1, plain.drawn)
        assertEquals(4, plain.played)
        assertEquals(
            "Ai won 2 of 4 against kai, 1 drawn. Ai saw only its own hand; kai saw only theirs; the dice chose who went first (Ai first in 4).",
            DuelResults.words(plain),
        )
        val auto = kai.first { it.settings.knows == DuelBrief.AUTO }
        assertTrue(DuelResults.words(auto, "Mika").contains("Mika saw its own hand and peeked 2 times, each peek in the log"))
        assertTrue(DuelResults.words(kai.first { it.settings.eyes == DuelPrefs.KNOW_ALL }).contains("kai saw both hands"))
        // What-ifs only when asked for.
        assertEquals(5, DuelResults.aiAgainst(results, "kai", whatIfs = true).first { it.settings.knows == DuelBrief.SELF && it.settings.eyes == DuelPrefs.KNOW_SEAT }.played)
        // Everyone, and no one.
        assertEquals(5, DuelResults.aiAgainst(results).size)
        assertEquals("No finished duels between Ai and Sam yet.", DuelResults.summary(results, "Sam"))
        // A networked guest is a person too; a seat the person moved for Ai is said.
        val net = DuelResults.aiAgainst(listOf(result("k", winner = 1, guest = true, helped = 1)))
        assertEquals(1, net.single().won)
        assertFalse(net.single().settings.clean)
        assertTrue(DuelResults.words(net.single()).contains("over the network"))
    }
}
