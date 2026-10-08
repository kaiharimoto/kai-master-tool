package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 2026-10, kai: "a way to adjust trials and erase them". The person's answer to a kept trial can be changed and a trial
 * erased, the ratings read again from what is left, and an erasure put back.
 */
class ShootoutEditsTest {

    private val pool: Map<Int, Card> = (1..40).associate { (1000 + it) to Card(CardId(1000 + it), "Card $it", "Effect Monster", "effect") }
    private val mine = Deck(main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId))
    private val bench = Bench.of(BenchInput(mine, { pool[it.value] }))

    private fun rate(id: String, answer: Answer, hand: List<Int> = listOf(1001, 1002, 1003, 1004, 1005)) =
        StoredTrial(id, at = 1, stratum = Stratum.ALONE_FIRST.name, hand = hand, answer = answer.name)

    @Test
    fun anAnswerIsChangedAndItsFirstKept() {
        val log = ShootoutLog(deck = "me", trials = listOf(rate("a", Answer.CLEAR_LOSS), rate("b", Answer.COIN_FLIP)))
        val once = assertNotNull(log.adjusted("a", Answer.LEAN_WIN.name, at = 50))
        val a = once.trials.first()
        assertEquals(Answer.LEAN_WIN.name, a.answer)
        assertEquals(Answer.CLEAR_LOSS.name, a.first)
        assertEquals(50L, a.adjusted)
        assertFalse(a.sawAi, "no Ai answer beside it: still blind")
        assertEquals(log.trials[1], once.trials[1], "the others are untouched")
        // Changed again, the first answer is still the one first given.
        val twice = assertNotNull(once.adjusted("a", Answer.CLEAR_WIN.name, at = 60))
        assertEquals(Answer.CLEAR_LOSS.name, twice.trials.first().first)
        // The same answer is no change; an unknown trial or answer is refused.
        assertSame(once, once.adjusted("a", Answer.LEAN_WIN.name, at = 70))
        assertNull(log.adjusted("nope", Answer.LEAN_WIN.name, at = 1))
        assertNull(log.adjusted("a", "MAYBE", at = 1))
        assertNull(log.adjusted("a", StoredTrial.LEFT, at = 1), "a rating takes a rating's answer")
    }

    @Test
    fun aComparisonIsChangedToTheOtherHand() {
        val t = StoredTrial("c", stratum = Stratum.ALONE_FIRST.name, kind = StoredTrial.COMPARE, left = listOf(1001), right = listOf(1002), prefer = StoredTrial.LEFT)
        val log = ShootoutLog(deck = "me", trials = listOf(t))
        val next = assertNotNull(log.adjusted("c", StoredTrial.RIGHT, at = 9)).trials.single()
        assertEquals(StoredTrial.RIGHT, next.prefer)
        assertEquals(StoredTrial.LEFT, next.first)
        assertNull(log.adjusted("c", Answer.CLEAR_WIN.name, at = 9))
    }

    @Test
    fun onlyThePersonsAnswersChangeAndOneChangedBesideAisIsNoLongerBlind() {
        val ai = rate("a~ai", Answer.CLEAR_WIN).copy(judge = StoredTrial.AI, of = "a")
        val log = ShootoutLog(deck = "me", trials = listOf(rate("a", Answer.CLEAR_LOSS), ai))
        assertNull(log.adjusted("a~ai", Answer.LEAN_WIN.name, at = 1), "Ai's answer is Ai's")
        val next = assertNotNull(log.adjusted("a", Answer.LEAN_WIN.name, at = 1)).trials.first()
        assertTrue(next.sawAi, "changed once Ai had answered the same hand: counted as seen")
        assertEquals(Bench.SEEN, bench.judgeOf(next))
    }

    @Test
    fun erasingTakesItsNotesAndAisAnswersAndPutsThemBack() {
        val ai = rate("b~ai", Answer.CLEAR_WIN).copy(judge = StoredTrial.AI, of = "b")
        val notes = listOf(TrialNote("a", "kept"), TrialNote("b", "goes"))
        val log = ShootoutLog(deck = "me", trials = listOf(rate("a", Answer.CLEAR_LOSS), rate("b", Answer.COIN_FLIP), ai, rate("c", Answer.LEAN_WIN)), notes = notes)
        val e = log.erased(setOf("b"))
        assertEquals(listOf("a", "c"), e.log.trials.map { it.id })
        assertEquals(listOf("kept"), e.log.notes.map { it.text })
        assertEquals(1, e.hands, "Ai's answer goes with it, uncounted")
        // Put back, everything stands where it stood.
        assertEquals(log, e.log.restored(e))
        // Erasing nothing changes nothing.
        assertEquals(log, log.erased(setOf("nope")).log)
    }

    @Test
    fun theRatingsReadAgainFromWhatIsLeft() {
        val run = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_FIRST, seed = 3)
        repeat(30) { i ->
            when (val p = run.next()) {
                is Proposal.Rate -> run.answer(p, if (1001 in bench.ids(p.hand)) Answer.CLEAR_WIN else Answer.LEAN_LOSS, "s-$i", at = 1_000L + i)
                is Proposal.Compare -> run.prefer(p, 1001 in bench.ids(p.left), "s-$i", at = 1_000L + i)
            }
        }
        val before = run.results()
        val holding = ShootoutResults.trialsBehind(run.log.trials, Behind.Card(1001, Stratum.ALONE_FIRST))
        assertTrue(holding.isNotEmpty())
        val full = run.log
        // Erasing every hand that holds the card leaves it with no hands behind it, and the fit reads one fewer each.
        run.rewrite { it.erased(holding.map { t -> t.id }.toSet()).log }
        assertEquals(30 - holding.size, run.fitted)
        assertEquals(30 - holding.size, run.results().kept)
        assertEquals(30 - holding.size, run.trialsRead, "the hands read are counted again, not added to")
        assertTrue(ShootoutResults.trialsBehind(run.log.trials, Behind.Card(1001, Stratum.ALONE_FIRST)).isEmpty())
        // Back, and every answer of them turned to a clear loss: the card that was the deck's best rates lower.
        val worth = { r: ShootoutResults -> r.cards.first { it.card == 1001 }.cells.getValue(Stratum.ALONE_FIRST).estimate.value }
        val back = ShootoutRun(bench, full, seed = 3)
        back.rewrite { log ->
            holding.fold(log) { l, t ->
                val against = if (t.kind == StoredTrial.COMPARE) (if (1001 in t.left) StoredTrial.RIGHT else StoredTrial.LEFT) else Answer.CLEAR_LOSS.name
                l.adjusted(t.id, against, at = 9)!!
            }
        }
        assertEquals(30, back.fitted)
        assertTrue(worth(back.results()) < worth(before), "${worth(back.results())} < ${worth(before)}")
        // And the changes are kept on disk as they are.
        val read = assertNotNull(ShootoutCodec.decode(ShootoutCodec.encode(back.log)))
        assertEquals(back.log.trials, read.trials)
    }

    @Test
    fun aTrialFromBeforeEditsReadsAsGiven() {
        val old = assertNotNull(ShootoutCodec.decode("""{"version":1,"deck":"d","trials":[{"id":"s-1","stratum":"ALONE_FIRST","hand":[1,2,3,4,5],"answer":"LEAN_WIN"}]}"""))
        val t = old.trials.single()
        assertNull(t.adjusted)
        assertNull(t.first)
        assertEquals("LEAN_WIN", t.given)
    }
}
