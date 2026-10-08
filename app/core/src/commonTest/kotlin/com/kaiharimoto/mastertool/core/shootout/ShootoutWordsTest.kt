package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrustWords
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.RealWorld
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.Calibration
import com.kaiharimoto.mastertool.core.shootout.teach.HandKind
import com.kaiharimoto.mastertool.core.shootout.teach.KindTrust
import com.kaiharimoto.mastertool.core.shootout.teach.Range
import com.kaiharimoto.mastertool.core.shootout.teach.SeenDrift
import com.kaiharimoto.mastertool.core.shootout.teach.TrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The page's words after the design review (1.1.6): one name per idea, "%" written the app's way, the panel's answer first. */
class ShootoutWordsTest {

    @Test
    fun bandsAreCountsInTenAndPercentHasNoSpace() {
        assertEquals("8+ in 10", ShootoutWords.band(Answer.CLEAR_WIN))
        assertEquals("4–6 in 10", ShootoutWords.band(Answer.COIN_FLIP))
        ShootoutWords.SCALE.forEach { assertFalse("%" in ShootoutWords.band(it), "a band keeps % free for the model's numbers") }
        assertEquals("62%", ShootoutWords.percent(0.62))
        assertEquals("85%", ShootoutTrustWords.pct(0.85))
    }

    @Test
    fun aRatingAsksItsQuestion() {
        assertEquals("How does this game go for you?", ShootoutWords.question(alone = false))
        assertTrue("deck wants" in ShootoutWords.question(alone = true))
    }

    @Test
    fun oneSituationHasOneNameShortAndLong() {
        assertEquals("G1 first", ShootoutWords.stratum(Stratum.G1_FIRST))
        assertEquals("Game 1 · going first", ShootoutWords.situation(Stratum.G1_FIRST, "Yubel"))
        assertEquals("Sided second", ShootoutWords.stratum(Stratum.SIDED_SECOND))
        assertEquals("Sided · going second", ShootoutWords.situation(Stratum.SIDED_SECOND, null))
        assertEquals("Going first", ShootoutWords.situation(Stratum.ALONE_FIRST, null))
        Stratum.entries.forEach { s ->
            val short = ShootoutWords.stratum(s).lowercase().replace("g1", "game 1")
            val long = ShootoutWords.situation(s, null).lowercase()
            assertTrue(short.split(' ').all { it in long || it == "sided" && "sided" in long }, "$short in $long")
        }
    }

    @Test
    fun certaintyAndSteadinessAreWords() {
        assertEquals("sure", ShootoutWords.certainty(0.82))
        assertEquals("fairly sure", ShootoutWords.certainty(0.65))
        assertEquals("unsure", ShootoutWords.certainty(0.4))
        assertEquals("You answer the same hand the same way about 7 in 10 times", ShootoutWords.steadiness(0.68))
        assertEquals("1 hand", ShootoutWords.hands(1))
        assertEquals("60 hands", ShootoutWords.hands(60))
    }

    @Test
    fun tooEarlySaysHowFarToGo() {
        val settled = StopRule.Settled(known = 0, of = 19, halfWidth = 5.0, enough = false)
        assertEquals("Too early to call: 0 of 19 cards known within ±5 points. About 180 more hands.", ShootoutWords.tooEarly(settled, 176))
        assertEquals("Too early to call: 0 of 19 cards known within ±5 points.", ShootoutWords.tooEarly(settled, null))
        assertEquals(1150, ShootoutWords.roundHands(1137))
    }

    private fun kind(first: Boolean, starter: Boolean, open: Boolean, sure: Double, closed: Long? = null, lower: Double = 0.95) = KindTrust(
        kind = HandKind(first, starter, true), agree = sure, pairs = sure, range = Range(lower, 1.0),
        sureAgree = sure, surePairs = sure, sureRange = Range(lower, 1.0), current = sure.toInt(), stale = false,
        open = open, why = if (open) null else "not earned", audits = 0, misses = 0, closedAt = closed, solo = 0,
    )

    private fun state(kinds: List<KindTrust>, solo: Boolean = true, self: Double? = null) = TrustState(
        settings = TrustSettings(bar = 0.85, solo = solo), kinds = kinds, calibration = Calibration(0, 0.0, 0.0, emptyList()),
        audits = emptyList(), seen = SeenDrift(0, null, 0, null), pairs = 0, counted = 0, solo = 0, aiAnswers = 0,
        selfAgree = self, repeats = if (self == null) 0 else 4,
    )

    @Test
    fun theTrustPanelLeadsWithItsAnswer() {
        val kinds = listOf(
            kind(true, true, open = false, sure = 4.0),
            kind(true, false, open = true, sure = 14.0),
            kind(false, true, open = false, sure = 12.0, closed = 5L),
            kind(false, false, open = false, sure = 3.0),
        )
        assertEquals(
            "Ai judges 1 of 4 kinds of hand for you. The other 3 need at least 13 more hands it is sure of.",
            ShootoutTrustWords.headline(state(kinds), "Ai"),
        )
        assertTrue(ShootoutTrustWords.headline(state(kinds, solo = false), "Kiri").startsWith("Kiri has earned 1 of 4"))
        assertEquals(listOf("Open", "Not yet", "Not yet", "Closed"), ShootoutTrustWords.ordered(kinds).map(ShootoutTrustWords::status))
        assertEquals("needs 6 more sure hands", ShootoutTrustWords.reason(kinds[0], 0.85))
        assertTrue(ShootoutTrustWords.reason(kinds[2], 0.85)!!.startsWith("two audits missed"))
    }

    @Test
    fun theCeilingWarnsWhenItCapsTheBar() {
        val capped = ShootoutTrustWords.ceiling(0.5, 4, 0.85)
        assertTrue("about 5 in 10 times" in capped && "85% may never open" in capped, capped)
        assertFalse("may never open" in ShootoutTrustWords.ceiling(0.95, 20, 0.85))
    }

    @Test
    fun theRandomCheckSaysHowFarItRunsFromTheModelNeverARateOfItsOwn() {
        // The red team (2026-10): "Random hands only: 80%" beside a 90% headline read as a disagreement that was only scale.
        fun k(residual: Double, sd: Double, n: Int = 12) = RealWorld.Check(Stratum.G1_FIRST, 70.0, residual, sd, n)
        assertEquals("Random hands only: answers in line with the model (12 hands)", ShootoutWords.randomCheck(k(3.0, 2.0)))
        assertEquals("Random hands only: answers 8.0 points above the model (12 hands)", ShootoutWords.randomCheck(k(8.0, 2.0)))
        assertEquals("Random hands only: answers 6.5 points below the model (2 hands)", ShootoutWords.randomCheck(k(-6.5, 1.0, n = 2)))
        assertTrue("%" !in ShootoutWords.randomCheck(k(20.0, 1.0)))
    }
}
