package com.kaiharimoto.mastertool.core.start

import com.kaiharimoto.mastertool.core.backup.Backups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartStepsTest {
    private val nothing = StartState(hasDecks = false, syncOn = false, aiEnabled = true, aiConnected = false, artSettled = false)
    private val someone = nothing.copy(hasDecks = true)

    @Test
    fun someoneNewIsWalkedThroughEverything() {
        assertEquals(StartStep.entries, StartSteps.pending("1.0.69", StartPrefs(), nothing, android = false))
        assertTrue(StartSteps.isNew(StartPrefs(), nothing))
    }

    @Test
    fun someoneUpdatingSeesOnlyWhatArrivedSince() {
        // From 1.0.60: sync arrived after it; Ai and the art were there already.
        assertEquals(listOf(StartStep.SYNC), StartSteps.pending("1.0.69", StartPrefs(seen = "1.0.60"), someone, android = false))
        // The APK counts on its own track.
        assertEquals(listOf(StartStep.SYNC), StartSteps.pending("1.3.46", StartPrefs(seen = "1.3.40"), someone, android = true))
        // Before 1.0.69 nothing was written down: each step is asked about once, never the ones for someone new.
        assertEquals(listOf(StartStep.SYNC, StartStep.AI, StartStep.ART), StartSteps.pending("1.0.69", StartPrefs(), someone, android = false))
    }

    @Test
    fun stepsDoneSkippedOrSettledAreNeverOffered() {
        val settled = someone.copy(syncOn = true, aiConnected = true, artSettled = true)
        assertEquals(emptyList(), StartSteps.pending("1.0.69", StartPrefs(), settled, android = false))
        assertEquals(listOf(StartStep.AI, StartStep.ART), StartSteps.pending("1.0.69", StartPrefs(done = listOf("sync")), someone, android = false))
        // Ai turned off is a choice made.
        assertEquals(listOf(StartStep.SYNC, StartStep.ART), StartSteps.pending("1.0.69", StartPrefs(), someone.copy(aiEnabled = false), android = false))
        // The same version opening again offers nothing.
        assertEquals(emptyList(), StartSteps.pending("1.0.69", StartPrefs(seen = "1.0.69"), someone, android = false))
    }

    @Test
    fun aBackupComesFirstWhenAVersionIsNewHere() {
        assertEquals("Before 1.0.69 (from 1.0.68)", Backups.due("1.0.68", "1.0.69", null, 0, hasData = true))
        // The first version that keeps a record: work already there is backed up before anything changes.
        assertEquals("Before 1.0.69", Backups.due(null, "1.0.69", null, 0, hasData = true))
        assertNull(Backups.due(null, "1.0.69", null, 0, hasData = false))
        assertNull(Backups.due("1.0.69", "1.0.69", 1_000, 2_000, hasData = true))
        assertEquals("Weekly", Backups.due("1.0.69", "1.0.69", 0, Backups.WEEK, hasData = true))
    }

    @Test
    fun theNewestTenAreKept() {
        val names = (1..12).map { Backups.fileName("2026-10-${it.toString().padStart(2, '0')} 0900", "Weekly") }.shuffled()
        val gone = Backups.toDelete(names)
        assertEquals(listOf("2026-10-01 0900 Weekly.nmtbackup", "2026-10-02 0900 Weekly.nmtbackup"), gone)
        assertEquals("2026-10-01 0900 Before 1.0.69 from 1.0.68.nmtbackup", Backups.fileName("2026-10-01 0900", "Before 1.0.69 (from 1.0.68)"))
    }
}
