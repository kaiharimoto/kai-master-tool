package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.layout.DeckList.Kind
import com.kaiharimoto.mastertool.core.layout.DeckList.Stack
import kotlin.test.Test
import kotlin.test.assertEquals

class DeckListTest {

    @Test
    fun copiesCollapseInTheOrderEachCardIsFirstMet() {
        val s = DeckList.stacks(listOf(7, 9, 7, 7, 9, 3), List(6) { null })
        assertEquals(listOf(Stack(0, 3, null), Stack(1, 2, null), Stack(5, 1, null)), s)
    }

    @Test
    fun aCardInTwoGroupsIsTwoStacksAndAnUnknownCardIsNeverMerged() {
        val s = DeckList.stacks(listOf(7, 7, null, null), listOf("a", "b", null, null))
        assertEquals(listOf(Stack(0, 1, "a"), Stack(1, 1, "b"), Stack(2, 1, null), Stack(3, 1, null)), s)
    }

    @Test
    fun withoutGroupsCardsAreMonstersSpellsOrTraps() {
        assertEquals(Kind.MONSTERS, DeckList.kindOf("Effect Monster"))
        assertEquals(Kind.MONSTERS, DeckList.kindOf("XYZ Monster"))
        assertEquals(Kind.SPELLS, DeckList.kindOf("Quick-Play Spell Card"))
        assertEquals(Kind.SPELLS, DeckList.kindOf("Spell Card"))
        assertEquals(Kind.TRAPS, DeckList.kindOf("Counter Trap Card"))
    }

    @Test
    fun theColumnsAreBalancedByWholeBlocks() {
        // 10 | 3 + 3 + 3: the big block alone on the left.
        assertEquals(1, DeckList.split(listOf(10f, 3f, 3f, 3f)))
        assertEquals(2, DeckList.split(listOf(3f, 3f, 3f, 3f)))
        assertEquals(1, DeckList.split(listOf(5f)))
        assertEquals(0, DeckList.split(emptyList()))
    }
}
