package com.kaiharimoto.mastertool.core.ai.predict

import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.sync.ConflictRule
import com.kaiharimoto.mastertool.core.sync.Sync
import com.kaiharimoto.mastertool.core.sync.SyncMerges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ai's stated chances (Phase I, I.6): settled by what the app saw end, scored as the app's own calls are, never by Ai. */
class PredictionsTest {
    private val day = 86_400_000L

    private fun duel(id: String, chance: Double, seat: Int) = Prediction("p-$id-$seat", 1L, Prediction.DUEL, id, chance, seat, model = "m")
    private fun round(event: String, n: Int, chance: Double) = Prediction("r-$event-$n", 1L, Prediction.ROUND, Prediction.roundTarget(event, n), chance, model = "m")
    private fun game(event: String, n: Int, result: String, at: Long) =
        TestGame("g$at", at, "d", "o", "Opponent", TestGame.FIRST, result = result, eventId = event, round = n)

    @Test
    fun aDuelIsSettledByItsRecordAndADrawIsVoid() {
        val ps = listOf(duel("a", 0.7, 0), duel("a", 0.4, 1), duel("b", 0.6, 0), duel("c", 0.5, 0), duel("w", 0.5, 0))
        val records = listOf(
            DuelResult(id = "a", duel = "a", ended = 50L, winner = 0),
            DuelResult(id = "b", duel = "b", ended = 60L, winner = null),
            // A what-if played on from a replay is not the duel that was said about.
            DuelResult(id = "w-w3-1", duel = "w", ended = 70L, winner = 0, whatIf = true),
        )
        val settled = Predictions.settle(ps, records, emptyList(), emptyList(), now = 100L)
        assertEquals(true, settled[0].happened, "seat 0 won, as said")
        assertEquals(false, settled[1].happened, "seat 1 was said, seat 0 won")
        assertEquals(50L, settled[0].settled)
        assertNull(settled[2].happened)
        assertEquals("the duel was a draw", settled[2].void)
        assertTrue(settled[3].open, "no record yet")
        assertTrue(settled[4].open, "a what-if never settles the duel")
        // Settled once, never again: a later record does not move it.
        val again = Predictions.settle(settled, records + DuelResult(id = "a2", duel = "a", ended = 90L, winner = 1), emptyList(), emptyList(), now = 200L)
        assertEquals(true, again[0].happened)
    }

    @Test
    fun aRoundIsTheMatchTwoWinsOrTheEventsEnd() {
        val date = "2026-10-10"
        val start = IsoDate.epochDay(date)!! * day
        val events = listOf(PrepEvent("e", "Regional", date))
        val ps = listOf(round("e", 1, 0.6), round("e", 2, 0.6), round("e", 3, 0.6), round("e", 4, 0.6))
        val games = listOf(
            game("e", 1, TestGame.WIN, start + 1), game("e", 1, TestGame.LOSS, start + 2), game("e", 1, TestGame.WIN, start + 3),
            game("e", 2, TestGame.LOSS, start + 4),
            game("e", 3, TestGame.WIN, start + 5), game("e", 3, TestGame.LOSS, start + 6),
        )
        val during = Predictions.settle(ps, emptyList(), games, events, now = start + day / 2)
        assertEquals(true, during[0].happened, "2–1: won")
        assertTrue(during[1].open, "one game lost: a second may follow")
        assertTrue(during[2].open)
        assertTrue(during[3].open, "nothing logged")
        val after = Predictions.settle(ps, emptyList(), games, events, now = start + 3 * day + 1)
        assertEquals(false, after[1].happened, "lost 0–1 and the event long over: a match to time")
        assertEquals("the round was left even", after[2].void)
        assertTrue(after[3].open, "a round with no games is waited on, never guessed")
    }

    @Test
    fun scoredAsTheAppsOwnCallsAreAgainstACoin() {
        val sure = (1..10).map { duel("s$it", 0.9, 0).copy(happened = true) } + (1..10).map { duel("t$it", 0.1, 0).copy(happened = false) }
        val s = Predictions.score(sure)
        assertEquals(20, s.n)
        assertEquals(0.01, s.brier, 1e-9)
        assertTrue(s.skill > 0.9)
        assertTrue(Predictions.words(sure).contains("better than a coin"), Predictions.words(sure))
        val coin = (1..20).map { duel("c$it", 0.5, 0).copy(happened = it % 2 == 0) }
        assertEquals(0.25, Predictions.score(coin).brier, 1e-9)
        assertTrue(Predictions.words(coin).contains("no better than a coin"), Predictions.words(coin))
        val few = sure.take(3)
        assertTrue(Predictions.words(few).contains("too few"), Predictions.words(few))
        val void = listOf(duel("v", 0.6, 0).copy(void = "the duel was a draw"), duel("o", 0.6, 0))
        assertTrue(Predictions.words(void).contains("none settled"), Predictions.words(void))
    }

    @Test
    fun theLogRoundTripsAndReadsForgivingly() {
        val ps = listOf(duel("a", 0.7, 0).copy(words = "Seat 0 (kai) wins", turn = 3, happened = true, settled = 9L), round("e", 2, 0.55))
        assertEquals(ps, Predictions.read(Predictions.write(ps)))
        assertTrue(Predictions.write(ps).startsWith("{\"version\":1,\"predictions\":"), "the version always written")
        assertEquals(emptyList(), Predictions.read("not json"))
        assertEquals(emptyList(), Predictions.read(null))
        assertEquals(1, Predictions.read("""{"version":2,"later":true,"predictions":[{"id":"x","chance":0.3,"novel":1}]}""").size)
    }

    @Test
    fun twoDevicesPredictionsMergeAndASettledCopyWins() {
        assertEquals(ConflictRule.PREDICTIONS, Sync.rule("ai/" + Predictions.PATH))
        val shared = duel("a", 0.7, 0).copy(at = 1L)
        val phone = listOf(shared, duel("b", 0.6, 1).copy(at = 3L))
        val desk = listOf(shared.copy(happened = true, settled = 9L), duel("c", 0.4, 0).copy(at = 2L))
        val merged = Predictions.read(SyncMerges.predictions(Predictions.write(phone).encodeToByteArray(), Predictions.write(desk).encodeToByteArray()).decodeToString())
        assertEquals(listOf("p-a-0", "p-c-0", "p-b-1"), merged.map { it.id }, "every one kept once, in the order said")
        assertEquals(true, merged.first().happened, "the desk settled it")
    }
}
