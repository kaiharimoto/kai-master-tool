package com.kaiharimoto.mastertool.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The red team's compaction findings (1.0.98): a long transcript in pieces, never its middle cut; one count per turn. */
class CompactionChunksTest {
    private fun turn(n: Int) = ChatTurn.user("x".repeat(n))

    @Test
    fun aLongConversationIsSummarisedInPiecesThatEachFit() {
        val turns = List(10) { turn(25_000) }
        val pieces = Compaction.chunks(turns, maxChars = 60_000)
        assertEquals(turns, pieces.flatten(), "every turn, in order, none lost")
        assertTrue(pieces.all { p -> Compaction.transcript(p, Int.MAX_VALUE).length <= 60_000 || p.size == 1 })
        assertEquals(5, pieces.size)
    }

    @Test
    fun aToolResultKeepsMoreThanAShortLineInTheTranscript() {
        val t = ChatTurn(Role.USER, listOf(Part.ToolResult("a", "hand_odds", "P = 0.742 ".repeat(100))))
        assertTrue(Compaction.transcript(listOf(t)).length > 900)
    }

    @Test
    fun aTurnKeptTwiceForAnthropicIsCountedOnce() {
        val text = "y".repeat(4_000)
        val plain = ChatTurn(Role.ASSISTANT, listOf(Part.Text(text)))
        val both = ChatTurn(Role.ASSISTANT, listOf(Part.Text(text), Part.Opaque("anthropic", "[{\"type\":\"text\",\"text\":\"$text\"}]")))
        val once = Compaction.turnSize(both)
        assertTrue(once < Compaction.turnSize(plain) * 1.1, "the opaque copy only, not the text again: $once")
    }
}
