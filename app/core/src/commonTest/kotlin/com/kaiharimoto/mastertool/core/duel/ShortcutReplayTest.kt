package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxSamples
import com.kaiharimoto.mastertool.core.duel.effects.FxSamples.U
import com.kaiharimoto.mastertool.core.duel.effects.Purpose
import com.kaiharimoto.mastertool.core.duel.effects.StepKind
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAnswers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Shortcut window asks by replay (Phase D §5¾.1, §5¾.13): the use runs with the answers given so far; the first decision
 * they do not reach is the window's; an answer is added and the use runs again from the start. The engine is
 * deterministic, so every run reaches the same next question; Esc drops the last answer and lands on the decision before;
 * with none left it cancels, and nothing is ever committed until the run ends done.
 */
class ShortcutReplayTest {
    private val catalog = FxSamples.catalog

    private fun asking(step: ShortcutStep): ShortcutStep.Asking = assertIs<ShortcutStep.Asking>(step, "a question stands: $step")

    @Test
    fun whichThenPickThenPlaceThenDoneAsOneGroup() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        var a = ShortcutAsking.use(U.HERALD, 0)
        val which = asking(a.run(sc, g.state, catalog))
        assertTrue(which.which, "the card's own Which comes first")
        assertEquals(listOf("Call", "Rally", "Sweep"), (which.decision as Decision.Option).among)

        a = a.answer(listOf(0))
        val pick = asking(a.run(sc, g.state, catalog))
        val cards = assertIs<Decision.Cards>(pick.decision)
        assertEquals(Purpose.SUMMON, cards.purpose)
        assertEquals(StepKind.DOES, cards.stepKind)
        assertTrue(U.VELL_HAND in cards.among && U.VELL_GY in cards.among && U.ORU_BANISHED in cards.among, "${cards.among}")
        assertTrue(U.COLOSSUS_DECK !in cards.among, "a Level 8 is not called")
        assertEquals(listOf(which.decision), pick.asked)

        a = a.answer(listOf(cards.among.indexOf(U.VELL_GY)))
        val zone = asking(a.run(sc, g.state, catalog))
        val z = assertIs<Decision.Zone>(zone.decision)
        assertEquals(U.VELL_GY, z.card)
        assertEquals(4, z.among.size, "M1 holds Herald")
        assertTrue(z.closed.keys.any { it.kind == ZoneKind.MONSTER && it.index == 0 }, "M1 is closed, and says why: ${z.closed}")

        a = a.answer(listOf(z.among.indexOfFirst { it.index == 3 }))
        val pos = asking(a.run(sc, g.state, catalog))
        val p = assertIs<Decision.Position>(pos.decision)
        assertEquals(listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF), p.among)
        // The card placed so far stands where it was put, dashed, until the last answer.
        assertEquals(listOf(ShortcutAsking.Placement(U.VELL_GY, Place.Zone(0, ZoneKind.MONSTER, 3), null)), a.placed(pos.asked))

        a = a.answer(listOf(1))
        val done = assertIs<ShortcutStep.Done>(a.run(sc, g.state, catalog))
        val r = done.result
        assertTrue(r.tags.all { it != null }, "every entry is the engine's")
        val committed = r.commit(g, 0, Provenance())
        assertNull(committed.problem)
        val after = committed.game.state
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 3), after.placeOf(U.VELL_GY))
        assertEquals(CardPosition.FACE_UP_DEF, after.cards.getValue(U.VELL_GY).pos)
        // One group: one undo takes the whole use back.
        assertEquals(g.state, committed.game.undo().state)
    }

    @Test
    fun everyRunReachesTheSameQuestionAfterTheSameAnswers() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        var a = ShortcutAsking.use(U.HERALD, 0).answer(listOf(0))
        val once = asking(a.run(sc, g.state, catalog))
        repeat(3) { assertEquals(once, a.run(sc, g.state, catalog)) }
        a = a.answer(listOf(0))
        val twice = asking(a.run(sc, g.state, catalog))
        repeat(3) { assertEquals(twice, a.run(sc, g.state, catalog)) }
    }

    @Test
    fun escLandsOnTheDecisionBeforeThenCancelsWithNothingCommitted() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        val steps = mutableListOf<ShortcutStep.Asking>()
        var a = ShortcutAsking.use(U.HERALD, 0)
        // Which, the card, the zone: three questions answered, the fourth (the position) standing.
        listOf(listOf(0), listOf(0), listOf(0)).forEach { ans ->
            steps += asking(a.run(sc, g.state, catalog))
            a = a.answer(ans)
        }
        val standing = asking(a.run(sc, g.state, catalog))
        assertIs<Decision.Position>(standing.decision)
        // Esc: each time, the decision before.
        for (k in steps.indices.reversed()) {
            a = assertNotNull(a.back())
            assertEquals(steps[k], a.run(sc, g.state, catalog), "Esc lands on question ${k + 1}")
        }
        assertNull(a.back(), "with no answer left, Esc cancels the whole use")
        // Nothing was committed on the way: the game is the one we started with.
        assertEquals(FxSamples.game(), g)
    }

    @Test
    fun typedAnswersAreUsedFirstAndTheWindowAsksOnlyWhatTheyLeaveOut() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        val a = ShortcutAsking.use(U.HERALD, 0, "call", ShortcutAnswers(pick = listOf("gy1")))
        val q = asking(a.run(sc, g.state, catalog))
        assertIs<Decision.Zone>(q.decision, "the pick was given; the zone is asked")
        assertEquals(U.VELL_GY, (q.decision as Decision.Zone).card)
        val done = a.answer(listOf(0)).answer(listOf(0)).run(sc, g.state, catalog)
        assertIs<ShortcutStep.Done>(done)
    }

    @Test
    fun aTargetIsAskedForAsATargetOnTheFieldAndInEitherGyAndBanishment() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        val q = asking(ShortcutAsking.use(U.HERALD, 0, "sweep").run(sc, g.state, catalog))
        val d = assertIs<Decision.Cards>(q.decision)
        assertEquals(Purpose.TARGET, d.purpose)
        assertEquals(StepKind.TARGET, d.stepKind)
        listOf(U.SENTRY, U.WARDEN, U.SENTRY_GY, U.WARDEN_BANISHED, U.VELL_GY, U.ORU_BANISHED).forEach { assertTrue(it in d.among, "$it in ${d.among}") }
        assertEquals(1, d.min)
        assertEquals(2, d.max)
    }

    @Test
    fun aNetworkedTableRefusesInWords() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, networked = true)
        val r = ShortcutAsking.use(U.HERALD, 0).run(sc, g.state, catalog)
        assertIs<ShortcutStep.Refused>(r)
        assertEquals(com.kaiharimoto.mastertool.core.duel.net.DuelHost.NO_SHORTCUTS, r.why)
    }

    @Test
    fun triggersSentTogetherAreAskedUseOrSkipThenTheirOrder() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        var a = ShortcutAsking.use(U.TOLL, 0)
        // Its zone (five free Spell & Trap Zones), then the two Gatekeepers sent.
        var q = asking(a.run(sc, g.state, catalog))
        assertIs<Decision.Zone>(q.decision)
        a = a.answer(listOf(0))
        q = asking(a.run(sc, g.state, catalog))
        val pick = assertIs<Decision.Cards>(q.decision)
        val echoes = listOf(pick.among.indexOf(U.ECHO_DECK_1), pick.among.indexOf(U.ECHO_DECK_2))
        assertTrue(echoes.all { it >= 0 }, "both Echoes may be sent: ${pick.among}")
        a = a.answer(echoes)
        q = asking(a.run(sc, g.state, catalog))
        assertIs<Decision.YesNo>(q.decision, "the first Echo: use it?")
        a = a.answer(listOf(1))
        q = asking(a.run(sc, g.state, catalog))
        assertIs<Decision.YesNo>(q.decision, "the second Echo: use it?")
        a = a.answer(listOf(1))
        q = asking(a.run(sc, g.state, catalog))
        val order = assertIs<Decision.Order>(q.decision)
        assertEquals(2, order.triggers.size)
        a = a.answer(listOf(1, 0))
        val done = a.run(sc, g.state, catalog)
        assertIs<ShortcutStep.Done>(done, "$done")
    }

    @Test
    fun anOpenNameIsAnsweredWithAnyCardOfThePool() {
        val g = FxSamples.game()
        val sc = FxSamples.written().at(g, resolveAtOnce = true)
        var a = ShortcutAsking.use(U.OATH, 0)
        a = a.answer(listOf(0))
        val q = asking(a.run(sc, g.state, catalog))
        val d = assertIs<Decision.Declare>(q.decision)
        assertTrue(d.open)
        // Colossus is in the Deck, never seen on the table: declared by its passcode, added.
        val done = assertIs<ShortcutStep.Done>(a.named(FxSamples.COLOSSUS).run(sc, g.state, catalog))
        val after = done.result.commit(g, 0, Provenance()).game.state
        assertEquals(PileKind.HAND, (after.placeOf(U.COLOSSUS_DECK) as Place.Pile).kind)
    }
}
