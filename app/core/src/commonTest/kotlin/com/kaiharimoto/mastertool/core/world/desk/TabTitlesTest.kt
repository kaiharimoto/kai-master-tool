package com.kaiharimoto.mastertool.core.world.desk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The title-shortening rule of the readability guidelines (`docs/world/READABILITY.md` §4). */
class TabTitlesTest {
    @Test
    fun aTitleThatFitsIsOnlyCleaned() {
        assertEquals("Starters seen", TabTitles.short("Starters seen", 16))
        assertEquals("Opens a starter", TabTitles.short("“Opens a starter”", 16))
        assertEquals("Starters ≥ 1", TabTitles.clean("“\"Starters\" ≥ 1”"))
    }

    @Test
    fun theLittleWordsGoBeforeAnyWordIsCut() {
        val s = TabTitles.short("What each card is worth to “Starters ≥ 1”", 24)
        assertEquals("Card worth Starters ≥ 1", s)
    }

    @Test
    fun wordsEveryNeighbourSharesGo() {
        val titles = listOf("Opening hands — lab openings", "Each card's access — lab openings", "Seen by turn — lab openings")
        assertEquals("Opening hands", TabTitles.short(titles[0], 16, titles))
        assertEquals("Card's access", TabTitles.short(titles[1], 16, titles))
    }

    @Test
    fun aCutKeepsTheFloorAndSaysItIsCut() {
        val long = "Who finds whom among the Labrynth engine and its extenders"
        listOf(4, 12, 16, 20).forEach { max ->
            val s = TabTitles.short(long, max)
            assertTrue(s.length <= maxOf(max, TabTitles.FLOOR), "$max: “$s”")
            assertTrue(s.length >= TabTitles.FLOOR, "never a stub below the floor: “$s”")
            assertTrue(s.endsWith("…"), s)
        }
        // A word too long for the room is cut inside it.
        val one = TabTitles.short("Supercalifragilisticexpialidocious", 14)
        assertEquals(14, one.length)
        assertTrue(one.endsWith("…"))
    }

    @Test
    fun aTitleOfOnlyLittleWordsKeepsThem() {
        assertEquals("What is it", TabTitles.short("What is it", 16))
        assertTrue(TabTitles.short("What is it and how is it what it is for", 14).length >= TabTitles.FLOOR)
    }
}
