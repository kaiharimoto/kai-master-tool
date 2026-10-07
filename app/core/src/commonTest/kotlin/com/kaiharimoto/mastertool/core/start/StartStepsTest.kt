package com.kaiharimoto.mastertool.core.start

import com.kaiharimoto.mastertool.core.backup.Backups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartStepsTest {
    private val nothing = StartState(hasDecks = false, syncOn = false, aiEnabled = true, aiConnected = false, artSettled = false, voiceReady = false, worldReady = false, recordReady = false, loungeReady = false)
    private val someone = nothing.copy(hasDecks = true)

    @Test
    fun someoneNewIsWalkedThroughEverything() {
        assertEquals(StartStep.entries, StartSteps.pending("1.0.69", StartPrefs(), nothing, android = false))
        assertTrue(StartSteps.isNew(StartPrefs(), nothing))
    }

    @Test
    fun aiWorldAsksAboutPythonOnTheDeskOnly() {
        // 1.0.97: the desk asks whether Python may run; a phone or tablet has none, so nothing is asked there.
        assertEquals(listOf(StartStep.WORLD), StartSteps.pending("1.0.97", StartPrefs(seen = "1.0.96"), someone.copy(voiceReady = true), android = false))
        assertEquals(emptyList(), StartSteps.pending("1.3.75", StartPrefs(seen = "1.3.74"), someone.copy(voiceReady = true, worldReady = true), android = true))
        // With Ai off there is no World to set up.
        assertEquals(emptyList(), StartSteps.pending("1.0.97", StartPrefs(seen = "1.0.96"), someone.copy(voiceReady = true, aiEnabled = false), android = false))
    }

    @Test
    fun recordingAsksForTheCameraOnTheDeskOnly() {
        // 1.1.13: the desk asks which camera and microphone; a phone or tablet cannot record a take yet, so it is ready.
        val ready = someone.copy(voiceReady = true, worldReady = true)
        assertEquals(listOf(StartStep.RECORD), StartSteps.pending("1.1.13", StartPrefs(seen = "1.1.12"), ready.copy(recordReady = false), android = false))
        assertEquals(emptyList(), StartSteps.pending("1.3.91", StartPrefs(seen = "1.3.90"), ready.copy(recordReady = true), android = true))
        assertEquals(emptyList(), StartSteps.pending("1.1.13", StartPrefs(seen = "1.1.12", done = listOf("record")), ready.copy(recordReady = false), android = false))
    }

    @Test
    fun theLoungeAsksForAPasscodeOnTheDeskOnly() {
        // 1.1.43 (docs/LOUNGE.md): the desk asks for the passcode friends will type; the APK has no door, so it is ready.
        val ready = someone.copy(voiceReady = true, worldReady = true, recordReady = true)
        assertEquals(listOf(StartStep.LOUNGE), StartSteps.pending("1.1.43", StartPrefs(seen = "1.1.42"), ready.copy(loungeReady = false), android = false))
        assertEquals(emptyList(), StartSteps.pending("1.4.23", StartPrefs(seen = "1.4.22"), ready.copy(loungeReady = true), android = true))
        assertEquals(emptyList(), StartSteps.pending("1.1.43", StartPrefs(seen = "1.1.42"), ready.copy(loungeReady = true), android = false))
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
    fun duellingByVoiceIsOfferedOnceItArrives() {
        // 1.0.87 on the desk, v1.3.64 on the APK: someone updating from 1.0.86 is offered the speech model.
        assertEquals(listOf(StartStep.VOICE), StartSteps.pending("1.0.87", StartPrefs(seen = "1.0.86"), someone, android = false))
        assertEquals(listOf(StartStep.VOICE), StartSteps.pending("1.3.64", StartPrefs(seen = "1.3.63"), someone, android = true))
        // Not before it arrives, and never once the model is here (or the recogniser is the system's).
        assertEquals(emptyList(), StartSteps.pending("1.0.86", StartPrefs(seen = "1.0.85"), someone, android = false))
        assertEquals(emptyList(), StartSteps.pending("1.0.87", StartPrefs(seen = "1.0.86"), someone.copy(voiceReady = true), android = false))
        assertEquals(emptyList(), StartSteps.pending("1.0.87", StartPrefs(seen = "1.0.86", done = listOf("voice")), someone, android = false))
    }

    @Test
    fun whatYouPlayIsAskedOfSomeoneNewAndOfPeopleUpdating() {
        // Someone new: right after the look, before their decks.
        val fresh = StartSteps.pending("1.1.8", StartPrefs(), nothing, android = false)
        assertEquals(listOf(StartStep.LOOK, StartStep.PLAY, StartStep.DECKS), fresh.take(3))
        // Someone updating, on each track: the step arrived in Neue 1.1.8 and APK v1.3.86.
        val settled = someone.copy(voiceReady = true, worldReady = true)
        assertEquals(listOf(StartStep.PLAY), StartSteps.pending("1.1.8", StartPrefs(seen = "1.1.7"), settled, android = false))
        assertEquals(listOf(StartStep.PLAY), StartSteps.pending("1.3.86", StartPrefs(seen = "1.3.85"), settled, android = true))
        // Not before it arrives, not once answered, and not to someone who chose Genesys or a day already.
        assertEquals(emptyList(), StartSteps.pending("1.1.7", StartPrefs(seen = "1.1.6"), settled, android = false))
        assertEquals(emptyList(), StartSteps.pending("1.1.8", StartPrefs(seen = "1.1.7", done = listOf("play")), settled, android = false))
        assertEquals(emptyList(), StartSteps.pending("1.1.8", StartPrefs(seen = "1.1.7"), settled.copy(rulesChosen = true), android = false))
        assertEquals(StartStep.PLAY, StartStep.of("play"))
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
