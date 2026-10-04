package com.kaiharimoto.mastertool.core.ai.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The red team's memory findings (1.0.98): an undo takes back its own entries only; a hand edit never loses Ai's. */
class MemoryMergeTest {
    private fun doc(vararg e: String) = MemoryDoc(listOf("# Notes"), e.toList()).render()

    @Test
    fun anUndoTakesBackOnlyItsOwnEntries() {
        val change = MemoryChange("MEMORY.md", added = listOf("likes going second"), removed = listOf("plays Branded"))
        // Someone else wrote "drafts on Fridays" after the reflection did.
        val now = doc("likes going second", "drafts on Fridays")
        val back = AiMemory.parse(MemoryReview.revert(now, change)!!).entries
        assertEquals(listOf("drafts on Fridays", "plays Branded"), back)
    }

    @Test
    fun undoingTheOnlyEntryOfANewFileDeletesIt() {
        val change = MemoryChange("guides/d.md", added = listOf("one"), removed = emptyList())
        assertNull(MemoryReview.revert("- one\n", change))
    }

    @Test
    fun aHandEditKeepsWhatAiWroteMeanwhile() {
        val opened = doc("a", "b")
        val disk = doc("a", "b", "Ai's new line")
        val mine = doc("a", "b edited")
        val merged = AiMemory.parse(MemoryReview.merge(opened, disk, mine)).entries
        assertTrue("Ai's new line" in merged && "b edited" in merged && "b" !in merged, merged.toString())
    }

    @Test
    fun anUntouchedFileIsSavedExactlyAsTyped() {
        val opened = doc("a")
        val mine = "# Notes\n\nfree text the person wrote\n- a\n"
        assertEquals(mine, MemoryReview.merge(opened, opened, mine))
    }
}
