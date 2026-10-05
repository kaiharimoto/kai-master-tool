package com.kaiharimoto.mastertool.core.world

import kotlin.test.Test
import kotlin.test.assertEquals

/** A condition as a page reads it (`Goals.words`): the openings headline once read `“"Starters">=1”`, a tab's `"”…`. */
class GoalWordsTest {
    @Test
    fun theQuotesThatHoldANameGoAndTheSignsAreSpaced() {
        assertEquals("Starters ≥ 1", Goals.words("\"Starters\">=1"))
        assertEquals("Starters ≥ 1 & Hand traps ≥ 1 | Extenders ≥ 2", Goals.words(Goals.EXAMPLE))
        assertEquals("Light and Darkness Dragon = 0", Goals.words("\"Light and Darkness Dragon\"=0"))
        assertEquals("Bricks < 2", Goals.words("Bricks<2"))
        assertEquals("Bricks ≤ 2", Goals.words("Bricks <= 2"))
        assertEquals("any(Ash, Imperm) ≥ 1", Goals.words("any(Ash, Imperm)>=1"))
    }

    @Test
    fun theDefaultConditionsReadAsWords() {
        // What the instruments ask when no condition is given: each group at least once, its name quoted.
        assertEquals("Hand traps ≥ 1", Goals.words("\"Hand traps\">=1"))
        assertEquals("Starters", Goals.words("Starters"), "nothing to change")
    }
}
