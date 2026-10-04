package com.kaiharimoto.mastertool.core.sync

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.report.ReportLog

/**
 * How two devices' copies of what Ai learned become one (1.0.98, the red team: newest-file-wins erased one device's
 * learning with the other's). Memory files are lists of entries, reports a list of filings: both merge naturally.
 */
object SyncMerges {
    /**
     * A memory file three ways: [mine]'s entries in their order, then [theirs]' new ones; an entry either side removed
     * since [base] is gone. The newer side's preamble (its heading and words above the entries). Null when a side is
     * not text.
     */
    fun entries(base: ByteArray?, mine: ByteArray, theirs: ByteArray, mineNewer: Boolean): ByteArray? {
        val b = base?.decodeToString()?.let(AiMemory::parse)?.entries.orEmpty().toSet()
        val m = AiMemory.parse(mine.decodeToString())
        val t = AiMemory.parse(theirs.decodeToString())
        val removedThere = if (base == null) emptySet() else b - t.entries.toSet()
        val removedHere = if (base == null) emptySet() else b - m.entries.toSet()
        val kept = m.entries.filter { it !in removedThere }
        val added = t.entries.filter { it !in m.entries && it !in removedHere && it !in kept }
        val preamble = (if (mineNewer) m else t).preamble
        return m.copy(preamble = preamble, entries = kept + added).render().encodeToByteArray()
    }

    /** A deck's reports: every filing on either side, once each (by when it was filed), oldest first. */
    fun reports(mine: ByteArray, theirs: ByteArray): ByteArray {
        val all = (ReportLog.read(mine.decodeToString()) + ReportLog.read(theirs.decodeToString())).distinctBy { it.at to it.startedAt }
        return ReportLog.write(all).encodeToByteArray()
    }
}
