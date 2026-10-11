package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeAblation
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeAblation.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase S, S4.4: what each thing Ai is handed is worth, on the person's held-out blind answers. */
class JudgeAblationTest {

    private fun person(i: Int, answer: String = "LEAN_WIN", sawAi: Boolean = false) =
        StoredTrial("p$i", stratum = "ALONE_FIRST", hand = listOf(1, 2, 3, 4, 5), answer = answer, at = i.toLong(), sawAi = sawAi)

    @Test
    fun theNewestBlindAnswersAreHeldOutWhenEnoughCameBefore() {
        val few = (1..40).map { person(it) }
        assertNull(JudgeAblation.pick(few), "30 before and 20 held out need 50")
        assertEquals(10, JudgeAblation.short(few))
        // Ai's answers and the person's after seeing Ai's are never held out, nor counted before.
        val trials = (1..60).map { person(it, sawAi = it % 10 == 0) } + StoredTrial("a", stratum = "ALONE_FIRST", hand = listOf(1), answer = "CLEAR_WIN", judge = StoredTrial.AI, at = 99)
        val held = assertNotNull(JudgeAblation.pick(trials))
        assertEquals(JudgeAblation.HANDS, held.size)
        assertTrue(held.none { it.sawAi || it.judge == StoredTrial.AI })
        assertEquals("p59", held.last().id, "the newest")
    }

    @Test
    fun eachVariantIsScoredAgainstEverythingOnTheSameHands() {
        val hands = (1..10).map { person(it, answer = "LEAN_WIN") }
        val said = buildList {
            hands.forEach { t ->
                // With everything Ai agrees on all ten, sure 0.9.
                add(JudgeAblation.said(t, Variant.FULL, "LEAN_WIN", null, 0.9))
                // Without the rubric it misses three (two steps off), and is still sure.
                val i = t.id.drop(1).toInt()
                add(JudgeAblation.said(t, Variant.NO_RUBRIC, if (i <= 3) "LEAN_LOSS" else "CLEAR_WIN", null, 0.9))
                // Without the prediction it agrees on all; without the examples on one fewer and one more is not possible.
                add(JudgeAblation.said(t, Variant.NO_PREDICTION, "COIN_FLIP", null, null))
            }
        }
        val run = JudgeAblation.Run("r", at = 1, hands = hands.map { it.id }, said = said)
        val scores = JudgeAblation.score(run).associateBy { it.variant }
        assertEquals(setOf(Variant.FULL, Variant.NO_RUBRIC, Variant.NO_PREDICTION), scores.keys, "a variant not run has no score")
        val full = scores.getValue(Variant.FULL)
        assertEquals(10, full.agreed)
        assertEquals(0.01, full.brier!!, 1e-9, "sure 0.9 and right every time")
        val rubric = scores.getValue(Variant.NO_RUBRIC)
        assertEquals(7, rubric.agreed)
        assertEquals(3, rubric.lost)
        assertEquals(0, rubric.gained)
        assertEquals(0.25, rubric.p, 1e-9, "three of three discordant one way: 2 × 1/8")
        assertTrue(rubric.range.lower < 0.7 && rubric.range.upper > 0.7)
        assertNull(scores.getValue(Variant.NO_PREDICTION).brier, "no sureness given, none scored")
        assertTrue("3 fewer than with everything (p 0.25)" in JudgeAblation.words(rubric), JudgeAblation.words(rubric))
        assertTrue("10 of 10" in JudgeAblation.words(full) && "sureness scored 0.01" in JudgeAblation.words(full), JudgeAblation.words(full))
    }

    @Test
    fun aRunIsKeptBesideTheTrialsAndNeverReadAsALog() {
        val log = JudgeAblation.Log(deck = "d", opponent = "o", runs = listOf(JudgeAblation.Run("r", at = 5, model = "m", hands = listOf("p1"), said = listOf(JudgeAblation.Said("p1", "FULL", "LEAN_WIN", agrees = true)))))
        assertEquals(log, JudgeAblation.decode(JudgeAblation.encode(log)))
        assertNull(JudgeAblation.decode("nope"))
        assertEquals("shootout/d/o.ablation.json", ShootoutPaths.ablation("d", "o"))
        assertFalse(ShootoutPaths.isLog("o.ablation.json"))
    }
}
