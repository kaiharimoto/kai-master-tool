package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** 1.0.86: undo that steps over talk, turns that start themselves, and Ai's cues by key. */
class DuelTurnsTest {

    private fun DuelGame.go(a: DuelAction, seat: Int? = 0) = act(a, seat).also { assertTrue(it.ok, it.problem) }.game
    private fun DuelGame.texts() = played.mapNotNull { (it.action as? DuelAction.Chat)?.text }

    // ---- undo skips talk ---------------------------------------------------------------------------

    @Test
    fun undoAfterACueTakesBackTheMoveAndKeepsTheCue() {
        var g = DuelGame.start(header())
        val card = g.state.seats[0].hand[0]
        g = g.go(DuelAction.Move(card, Place.Zone(0, ZoneKind.MONSTER, 2)))
        g = g.go(DuelAction.Chat(0, "Your move"))
        g = g.go(DuelAction.Ping(0, DuelAction.PING_LOOK, uid = card))
        val u = g.undoMove()
        assertTrue(card in u.state.seats[0].hand, "the move is taken back")
        assertEquals(listOf("Your move"), u.texts(), "the cue stays in the log")
        assertTrue(u.played.last().action is DuelAction.Ping, "talk keeps its order")
        // The move is what redo puts back, after the talk.
        val r = u.redoMove()
        assertFalse(card in r.state.seats[0].hand)
        assertEquals(listOf("Your move"), r.texts())
        assertTrue(r.played.last().action is DuelAction.Move)
        assertFalse(r.canRedo)
    }

    @Test
    fun groupsStayDistinctAndRisingAfterTheTalkMovesUp() {
        var g = DuelGame.start(header())
        val hand = g.state.seats[0].hand
        g = g.go(DuelAction.Move(hand[0], Place.Zone(0, ZoneKind.MONSTER, 0)))
        g = g.go(DuelAction.Move(hand[1], Place.Zone(0, ZoneKind.MONSTER, 1)))
        g = g.go(DuelAction.Chat(0, "a"))
        g = g.go(DuelAction.Thinking(0))
        g = g.undoMove()
        val groups = g.entries.drop(g.floor).map { it.group }
        assertEquals(groups.sorted(), groups, "groups rise through the log")
        assertEquals(g.entries.indices.toList(), g.entries.map { it.i }, "entries are numbered by place")
        // Undo again takes the first move, past the same talk.
        g = g.undoMove()
        assertTrue(hand[0] in g.state.seats[0].hand && hand[1] in g.state.seats[0].hand)
        assertEquals(listOf("a"), g.texts())
        // Each group is one run: no number comes back after another.
        val runs = g.entries.map { it.group }.zipWithNext().count { (a, b) -> a != b } + 1
        assertEquals(g.entries.map { it.group }.distinct().size, runs)
        // And both come back, in order.
        g = g.redoMove().redoMove()
        assertFalse(hand[0] in g.state.seats[0].hand || hand[1] in g.state.seats[0].hand)
    }

    @Test
    fun withNoTalkAfterItUndoIsTheOldUndoAndOnlyTalkTakesNothingBack() {
        var g = DuelGame.start(header())
        g = g.go(DuelAction.Shuffle(0))
        assertEquals(g.undo(), g.undoMove())
        var talk = DuelGame.start(header())
        talk = talk.go(DuelAction.Chat(0, "hello"))
        assertFalse(talk.canUndoMove)
        assertSame(talk, talk.undoMove())
        assertEquals(listOf("hello"), talk.texts())
    }

    @Test
    fun asksAndLocksAreMovesNotTalk() {
        var g = DuelGame.start(header())
        g = g.go(DuelAction.Lock(0, "Synchro Monsters only"))
        g = g.go(DuelAction.Chat(0, "locked"))
        val u = g.undoMove()
        assertTrue(u.state.locks.isEmpty())
        assertEquals(listOf("locked"), u.texts())
        assertTrue(DuelGame.isTalk(DuelAction.Note("Ai peeked at a card")))
        assertFalse(DuelGame.isTalk(DuelAction.Propose(1, DuelPhase.BATTLE)))
    }

    @Test
    fun aJoinedStepGrowsTheLastGroupSoUndoTakesTheWholeOpening() {
        var g = DuelGame.start(header())
        g = g.go(DuelAction.EndTurn)
        g = g.act(DuelAction.Draw(1), 1).game
        g = g.act(listOf(DuelAction.Phase(DuelPhase.STANDBY)), 1, join = true).game
        g = g.act(listOf(DuelAction.Phase(DuelPhase.MAIN1)), 1, join = true).game
        assertEquals(1, g.played.takeLast(3).map { it.group }.distinct().size)
        g = g.undo()
        assertEquals(DuelPhase.DRAW, g.state.phase)
        assertEquals(5, g.state.seats[1].hand.size)
        // Joining never reaches behind the deal.
        val fresh = DuelGame.start(header())
        val joined = fresh.act(listOf(DuelAction.Phase(DuelPhase.STANDBY)), 0, join = true).game
        assertTrue(joined.canUndo)
        assertTrue(joined.played.last().group > fresh.played.last().group)
    }

    // ---- turns that start themselves ---------------------------------------------------------------

    /** What the page does: the next step committed, one group, until there is none. */
    private fun open(start: DuelGame): DuelGame {
        var g = start
        var first = true
        while (true) {
            val step = TurnStart.next(g) ?: return g
            g = g.act(listOf(step), g.state.active, join = !first).also { assertTrue(it.ok, it.problem) }.game
            first = false
        }
    }

    @Test
    fun theNextTurnOpensDrawnInMainPhaseOne() {
        var g = DuelGame.start(header())
        val deck = g.state.seats[1].deck.size
        g = open(g.go(DuelAction.EndTurn))
        assertEquals(2, g.state.turn)
        assertEquals(1, g.state.active)
        assertEquals(DuelPhase.MAIN1, g.state.phase)
        assertEquals(6, g.state.seats[1].hand.size)
        assertEquals(deck - 1, g.state.seats[1].deck.size)
        assertNull(TurnStart.next(g), "an opening is made once")
    }

    @Test
    fun theFirstTurnNeverDraws() {
        val g = open(DuelGame.start(header()))
        assertEquals(DuelPhase.MAIN1, g.state.phase)
        assertEquals(5, g.state.seats[0].hand.size)
    }

    @Test
    fun aDrawAlreadyMadeIsNeverMadeTwice() {
        var g = DuelGame.start(header()).go(DuelAction.EndTurn)
        g = g.act(DuelAction.Draw(1), 1).game
        assertTrue(TurnStart.drewThisTurn(g))
        assertEquals(DuelAction.Phase(DuelPhase.STANDBY), TurnStart.next(g))
        g = open(g)
        assertEquals(6, g.state.seats[1].hand.size)
        // The other seat drawing (an effect) is not the turn player's draw.
        var h = DuelGame.start(header()).go(DuelAction.EndTurn)
        h = h.act(DuelAction.Draw(0), 0).game
        assertEquals(DuelAction.Draw(1, 1), TurnStart.next(h))
    }

    @Test
    fun itStopsWhereItCannotGoOnByItself() {
        val g = DuelGame.start(header()).go(DuelAction.EndTurn)
        // A chain open, an ask waiting, a duel over, an empty deck: the player decides.
        assertNull(TurnStart.next(g.state.copy(chain = listOf(ChainLink(0, null))), drawn = false))
        assertNull(TurnStart.next(g.state.copy(proposal = Proposal(0, DuelPhase.STANDBY)), drawn = false))
        assertNull(TurnStart.next(g.state.copy(conceded = 0), drawn = false))
        assertNull(TurnStart.next(g.state.withSeat(1) { it.copy(deck = emptyList()) }, drawn = false))
        // Past Main 1 it has nothing to do.
        assertNull(TurnStart.next(g.state.copy(phase = DuelPhase.BATTLE), drawn = true))
    }

    @Test
    fun aTurnRecordedAsAComboStartsAfterItsOpening() {
        var g = open(DuelGame.start(header()).go(DuelAction.EndTurn))
        val from = g.played.indexOfLast { it.action == DuelAction.EndTurn } + 1
        val card = g.state.seats[1].hand[0]
        g = g.act(DuelAction.Move(card, Place.Zone(1, ZoneKind.MONSTER, 0)), 1).game
        val start = TurnStart.afterOpening(g.entries, from, g.cursor)
        assertTrue(g.entries[start].action is DuelAction.Move, "the draw and the phases are skipped")
        assertEquals(g.cursor - 1, start)
    }

    @Test
    fun aSoloTableOpensItsOwnNextTurn() {
        val g = open(DuelGame.start(header(solo = true)).go(DuelAction.EndTurn))
        assertEquals(0, g.state.active)
        assertEquals(6, g.state.seats[0].hand.size)
        assertEquals(DuelPhase.MAIN1, g.state.phase)
    }

    @Test
    fun aiIsToldItsTurnStartsDrawn() {
        assertTrue("Main Phase 1" in TurnStart.FOR_AI && "never" in TurnStart.FOR_AI)
        assertTrue(DuelPrefs().autoDraw, "on by default")
        val old = DuelCodecForPrefs.decode("""{"aiSeat":0}""")
        assertTrue(old.autoDraw, "an older document reads with it on")
    }

    // ---- Ai's cues by key ----------------------------------------------------------------------------

    @Test
    fun theKeyDoesWhatTheFirstButtonDoes() {
        fun cue(waiting: Boolean = false, running: Boolean = false, responding: Boolean = false, top: Int? = null, solo: Boolean = false) =
            AiCue.primary(waiting, running, responding, top, aiSeat = 1, solo = solo)
        assertEquals(AiCue.YOUR_MOVE, cue())
        assertEquals(AiCue.NO_RESPONSE, cue(top = 1))
        assertEquals(AiCue.PASS, cue(top = 0))
        assertEquals(AiCue.DONE, cue(responding = true, top = 1))
        assertEquals(AiCue.BUSY, cue(running = true, responding = true))
        assertEquals(AiCue.DONT_WAIT, cue(waiting = true, running = true))
        assertEquals(AiCue.YOUR_MOVE, cue(top = 0, solo = true))
    }

    @Test
    fun aiKeysAreLiveOnTheDuelAloneAndNeverAis() {
        val duel = DeskContext(onBuilder = false, onDuel = true)
        assertEquals(DeskAction.DUEL_AI_ANSWER, DeskShortcuts.resolve(KeyChord("y"), duel))
        assertEquals(DeskAction.DUEL_AI_CATCH_UP, DeskShortcuts.resolve(KeyChord("y", shift = true), duel))
        // With Ai off, Y is No response across the hot-seat (1.0.89, DUEL_PASS) — no trace of Ai, the key the table's own.
        assertEquals(DeskAction.DUEL_PASS, DeskShortcuts.resolve(KeyChord("y"), duel.copy(ai = false)), "Y passes while Ai is off")
        assertNull(DeskShortcuts.resolve(KeyChord("y", shift = true), duel.copy(ai = false)), "Catch up is dead while Ai is off")
        assertNull(DeskShortcuts.resolve(KeyChord("y"), duel.copy(textInputFocused = true)), "typing a y types it")
        assertNull(DeskShortcuts.resolve(KeyChord("y"), DeskContext()))
        assertTrue(DeskAction.DUEL_AI_ANSWER in DeskAction.AI && DeskAction.DUEL_AI_CATCH_UP in DeskAction.AI, "Ai never cues itself")
        // Esc stays the dismiss key on the duel, and the digits stay the zones' (the page lends them to a question).
        assertEquals(DeskAction.DISMISS, DeskShortcuts.resolve(KeyChord("escape"), duel))
        assertEquals(DeskAction.DUEL_ZONE_1, DeskShortcuts.resolve(KeyChord("1"), duel))
        for (context in listOf(duel, duel.copy(replaying = true), duel.copy(textInputFocused = true))) {
            DeskShortcuts.live(context).groupBy { it.chord }.forEach { (chord, rows) ->
                if (rows.map { it.action }.toSet().size > 1) fail("${DeskShortcuts.kbd(chord)} means ${rows.map { it.action }} on the duel")
            }
        }
    }
}

/** DuelPrefs read the way the preferences document is: unknown keys ignored, missing ones defaulted. */
private object DuelCodecForPrefs {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun decode(text: String): DuelPrefs = json.decodeFromString(DuelPrefs.serializer(), text)
}
