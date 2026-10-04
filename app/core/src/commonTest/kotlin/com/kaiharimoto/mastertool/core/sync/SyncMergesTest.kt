package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryDoc
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import kotlin.test.Test
import kotlin.test.assertEquals

/** Learning on two devices is kept from both (1.0.98, the red team). */
class SyncMergesTest {
    private fun md(vararg e: String) = MemoryDoc(listOf("# Guide"), e.toList()).render().encodeToByteArray()
    private fun entries(b: ByteArray?) = AiMemory.parse(b!!.decodeToString()).entries

    @Test
    fun whatEachDeviceLearnedIsKept() {
        val base = md("opens with Aluber")
        val phone = md("opens with Aluber", "goes second into Snake-Eye")
        val desk = md("opens with Aluber", "sides Droll against Tenpai")
        assertEquals(listOf("opens with Aluber", "goes second into Snake-Eye", "sides Droll against Tenpai"), entries(SyncMerges.entries(base, phone, desk, mineNewer = true)))
    }

    @Test
    fun anEntryRemovedOnOneDeviceStaysRemoved() {
        val base = md("old line", "keep")
        val phone = md("keep")
        val desk = md("old line", "keep", "new on desk")
        assertEquals(listOf("keep", "new on desk"), entries(SyncMerges.entries(base, phone, desk, mineNewer = false)))
    }

    @Test
    fun aMemoryFileIsMergedAndASkillIsNot() {
        assertEquals(ConflictRule.ENTRIES, Sync.rule("ai/guides/d1.md"))
        assertEquals(ConflictRule.ENTRIES, Sync.rule("ai/MEMORY.md"))
        assertEquals(ConflictRule.NEWER, Sync.rule("ai/skills/x/SKILL.md"))
        assertEquals(ConflictRule.NEWER, Sync.rule("ai/SOUL.md"))
        assertEquals(ConflictRule.REPORTS, Sync.rule("ai/reports/d1.json"))
    }

    @Test
    fun bothDevicesReportsAreKept() {
        fun r(at: Long) = SessionReport(deckId = "d", deckName = "D", at = at, mode = "tune")
        val a = ReportLog.write(listOf(r(1), r(2))).encodeToByteArray()
        val b = ReportLog.write(listOf(r(1), r(3))).encodeToByteArray()
        assertEquals(listOf(1L, 2L, 3L), ReportLog.read(SyncMerges.reports(a, b).decodeToString()).map { it.at })
    }
}
