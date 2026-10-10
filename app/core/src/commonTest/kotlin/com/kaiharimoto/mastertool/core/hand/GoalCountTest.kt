package com.kaiharimoto.mastertool.core.hand

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deck's questions counted exactly first and second, and a card's ±1 (Phase G, G.3). */
class GoalCountTest {
    private val ash = CardId(1)
    private val imperm = CardId(2)
    private val starter = CardId(3)
    private val filler = CardId(9)
    private val main = List(3) { ash } + List(3) { imperm } + List(8) { starter } + List(26) { filler }
    private val groups = DeckGroups(
        listOf(DeckGroup("t", "Hand traps", 0, 0), DeckGroup("s", "Starters", 1, 1)),
        mapOf(ash to "t", imperm to "t", starter to "s"),
    )
    private val names = mapOf(ash to "Ash Blossom & Joyous Spring", imperm to "Infinite Impermanence", starter to "Starter", filler to "Filler")
    private val name: (CardId) -> String? = { names[it] }

    private fun choose(n: Int, k: Int): Double = (0 until k).fold(1.0) { acc, i -> acc * (n - i) / (i + 1) }
    private fun close(x: Double, y: Double) = assertTrue(abs(x - y) < 1e-12, "$x against $y")

    @Test
    fun theAsksAloneAreWhatTheBuilderAlwaysCounted() {
        val goal = HandGoal("q", "Opens", asks = mapOf("s" to Ask.AT_LEAST_1, "t" to Ask.AT_LEAST_1))
        val odds = GoalCount.odds(goal, main, groups, name)
        close(GoalOdds.probability(goal, main, groups), odds.first)
        close(GoalOdds.probability(goal.copy(handSize = 6), main, groups), odds.second)
        assertTrue(odds.second > odds.first)
    }

    @Test
    fun aConditionReadsGroupsCardsAndOr() {
        // A starter, or two hand traps: 1 − P(no starter and at most one hand trap).
        val goal = HandGoal("q", "Plays", condition = "Starters>=1 | Hand traps>=2")
        val p = GoalCount.odds(goal, main, groups, name).first
        val noStarter = 32
        val atMostOneTrap = choose(noStarter - 6, 5) + 6 * choose(noStarter - 6, 4)
        close(1 - atMostOneTrap / choose(40, 5), p)
        // A card by its name, `&` inside it, and any(…) of two.
        val ash1 = GoalCount.odds(HandGoal("a", "Ash", condition = "Ash Blossom & Joyous Spring>=1"), main, groups, name).first
        close(1 - choose(37, 5) / choose(40, 5), ash1)
        val either = GoalCount.odds(HandGoal("e", "Either", condition = "any(Ash Blossom & Joyous Spring, Infinite Impermanence)>=1"), main, groups, name).first
        close(1 - choose(34, 5) / choose(40, 5), either)
        // Ungrouped: the bricks.
        val bricks = GoalCount.odds(HandGoal("b", "Bricks", condition = "Ungrouped<=1"), main, groups, name).first
        close((choose(14, 5) + 26 * choose(14, 4)) / choose(40, 5), bricks)
    }

    @Test
    fun theAsksAndTheConditionHoldTogether() {
        val both = HandGoal("q", "Both", asks = mapOf("s" to Ask.AT_LEAST_1), condition = "Hand traps>=1")
        val same = HandGoal("q", "Same", condition = "Starters>=1 & Hand traps>=1")
        close(GoalCount.odds(same, main, groups, name).first, GoalCount.odds(both, main, groups, name).first)
    }

    @Test
    fun aCardsStepsAreTheDeckWithOneLessAndOneMore() {
        val goal = HandGoal("q", "Trap", condition = "Hand traps>=1")
        val s = GoalCount.steps(goal, main, ash, groups, name)
        close(1 - choose(34, 5) / choose(40, 5), s.now.first)
        close(1 - choose(34, 5) / choose(39, 5), assertNotNull(s.less).first)
        close(1 - choose(34, 5) / choose(41, 5), assertNotNull(s.more).first)
        assertTrue(s.less!!.first < s.now.first && s.now.first < s.more!!.first)
        assertNull(GoalCount.steps(goal, main, CardId(77), groups, name).less)
    }

    @Test
    fun aConditionThatDoesNotReadSaysWhy() {
        assertNull(GoalCount.problem(HandGoal("q", "Ok", condition = "Starters>=1"), main, groups, name))
        assertTrue(GoalCount.problem(HandGoal("q", "Typo", condition = "Startrs>=1"), main, groups, name)!!.contains("neither a group"))
        assertTrue(GoalCount.problem(HandGoal("q", "Half", condition = "Starters"), main, groups, name) != null)
        // An empty goal is certain, and says nothing.
        assertEquals(GoalCount.Odds(1.0, 1.0), GoalCount.odds(HandGoal("q", ""), main, groups, name))
    }
}
