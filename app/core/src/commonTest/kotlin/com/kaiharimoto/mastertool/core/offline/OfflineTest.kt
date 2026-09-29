package com.kaiharimoto.mastertool.core.offline

import com.kaiharimoto.mastertool.core.data.PoolCheck
import com.kaiharimoto.mastertool.core.data.PoolFreshness
import com.kaiharimoto.mastertool.core.data.PoolProgress
import com.kaiharimoto.mastertool.core.data.PoolRecord
import com.kaiharimoto.mastertool.core.data.PoolVersion
import com.kaiharimoto.mastertool.core.data.Sizes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineTest {

    // ---- the pool's version ------------------------------------------------

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(PoolFreshness.compare("147.20", "147.9") > 0)
        assertEquals(0, PoolFreshness.compare("147.20", "147.20"))
        assertTrue(PoolFreshness.compare("147.20", "148.0") < 0)
        assertEquals(0, PoolFreshness.compare("147", "147.0"))
    }

    @Test
    fun theSameOrANewerVersionIsCurrent() {
        val remote = PoolVersion("147.20", "2026-09-28 00:05:08")
        assertTrue(PoolFreshness.isCurrent("147.20", 0L, remote))
        assertTrue(PoolFreshness.isCurrent("147.21", 0L, remote), "a CDN answering from a stale copy")
        assertFalse(PoolFreshness.isCurrent("147.19", Long.MAX_VALUE, remote), "the version decides, not the age")
    }

    @Test
    fun aPoolWithNoVersionIsJudgedByItsAgeWithADaysSlack() {
        val remote = PoolVersion("147.20", "2026-09-28 00:05:08")
        val changed = PoolFreshness.parseUtc(remote.updated)!!
        val day = 24L * 60 * 60 * 1000
        assertTrue(PoolFreshness.isCurrent(null, changed + day + 1, remote))
        assertFalse(PoolFreshness.isCurrent(null, changed + day / 2, remote), "the time zone is unknown")
        assertFalse(PoolFreshness.isCurrent(null, null, remote))
        assertFalse(PoolFreshness.isCurrent(null, Long.MAX_VALUE, PoolVersion("1", "whenever")))
    }

    @Test
    fun lastUpdateIsReadAsUtc() {
        assertEquals(0L, PoolFreshness.parseUtc("1970-01-01 00:00:00"))
        // 2026-09-28T00:05:08Z
        assertEquals(1790553908000L, PoolFreshness.parseUtc("2026-09-28 00:05:08"))
        assertEquals(951782400000L, PoolFreshness.parseUtc("2000-02-29 00:00"))
        assertNull(PoolFreshness.parseUtc("yesterday"))
    }

    // ---- the pool's progress -----------------------------------------------

    @Test
    fun progressOnlyEverRisesThroughTheSteps() {
        val steps = listOf(
            PoolProgress.Asking,
            PoolProgress.Downloading(0, 21_000_000),
            PoolProgress.Downloading(10_000_000, 21_000_000),
            PoolProgress.Downloading(21_000_000, 21_000_000),
            PoolProgress.Reading,
            PoolProgress.Saving(0, 14_590),
            PoolProgress.Saving(7_000, 14_590),
            PoolProgress.Saving(14_590, 14_590),
        )
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b.fraction >= a.fraction, "$a to $b") }
        assertEquals(1f, steps.last().fraction)
    }

    @Test
    fun aDownloadLargerThanGuessedDoesNotRunPastTheBar() {
        val over = PoolProgress.Downloading(40_000_000, 21_000_000)
        assertTrue(over.fraction < PoolProgress.Reading.fraction)
    }

    @Test
    fun theDownloadIsMeasuredAgainstTheLastOne() {
        assertEquals(21_327_367L, PoolProgress.expected(PoolRecord("147.20", 21_327_367L), 100))
        assertEquals(14_590L * PoolProgress.BYTES_PER_CARD, PoolProgress.expected(null, 14_590))
        assertEquals(PoolProgress.FIRST_GUESS, PoolProgress.expected(PoolRecord(), 0))
    }

    @Test
    fun progressInWords() {
        assertEquals("Downloading · 4.2 MB of about 21 MB", PoolProgress.Downloading(4_200_000, 21_327_367).words)
        assertEquals("Saving 7,000 of 14,590", PoolProgress.Saving(7_000, 14_590).words)
    }

    @Test
    fun numbersAreGroupedAndSized() {
        assertEquals("0", Sizes.grouped(0))
        assertEquals("999", Sizes.grouped(999))
        assertEquals("14,590", Sizes.grouped(14_590))
        assertEquals("1,234,567", Sizes.grouped(1_234_567))
        assertEquals("812 MB", Sizes.disk(812L shl 20))
        assertEquals("1.9 GB", Sizes.disk((19L shl 30) / 10 + 1))
    }

    // ---- Settings' wording -------------------------------------------------

    @Test
    fun thePoolRowSaysWhatTheCheckFound() {
        assertEquals("Up to date · 14,590 cards · checked 12:04", Offline.poolLine(PoolCheck.Current("147.20", 14_590, 0), 14_590, "12:04", null))
        assertEquals(
            "An update is available · 147.21, this pool is 147.20 · checked 12:04",
            Offline.poolLine(PoolCheck.Behind("147.20", "147.21", 14_590, 0), 14_590, "12:04", null),
        )
        assertEquals("14,590 cards · not checked this session", Offline.poolLine(null, 14_590, null, null))
        assertEquals("Couldn't reach YGOPRODeck · 14,590 cards on this device · checked 12:04", Offline.poolLine(PoolCheck.Unreachable(14_590, 0), 14_590, "12:04", null))
        assertEquals("Updating · Reading the cards", Offline.poolLine(null, 14_590, null, PoolProgress.Reading))
    }

    @Test
    fun theArtRowCountsWhatIsSettled() {
        val art = ArtCount(have = 6_000, unavailable = 210, total = 14_590, bytes = 1L shl 30, perSecond = 10.0)
        assertEquals("6,210 of 14,590 · 1.0 GB · about 14 min left", Offline.artLine(art, enabled = true, problem = null))
        assertEquals("6,210 of 14,590 · 1.0 GB · paused", Offline.artLine(art, enabled = false, problem = null))
        assertEquals("6,210 of 14,590 · 1.0 GB · Waiting for the network", Offline.artLine(art, enabled = true, problem = "Waiting for the network"))
    }

    @Test
    fun missingPicturesDoNotHoldTheLibraryAt99() {
        val art = ArtCount(have = 14_580, unavailable = 10, total = 14_590, bytes = 0)
        assertTrue(art.complete)
        assertEquals(100, art.percent)
        assertEquals(99, ArtCount(14_589, 0, 14_590, 0).percent)
    }

    @Test
    fun etaInWordsAPersonPlansBy() {
        assertNull(Offline.eta(100, 0.0))
        assertNull(Offline.eta(0, 10.0))
        assertEquals("under a minute left", Offline.eta(100, 10.0))
        assertEquals("about 2 min left", Offline.eta(700, 10.0))
        assertEquals("about 2½ h left", Offline.eta(14_000, 1.6))
    }

    @Test
    fun readyForOfflineNeedsACurrentPoolAndEveryPicture() {
        val done = ArtCount(14_590, 0, 14_590, 0)
        val half = ArtCount(7_000, 0, 14_590, 0)
        val current = PoolCheck.Current("147.20", 14_590, 0)
        assertEquals(Readiness(true, "Ready for offline"), Offline.readiness(current, 14_590, done, artEnabled = true))
        assertFalse(Offline.readiness(null, 14_590, done, true).ready)
        assertEquals(
            "Not ready for offline yet: update the card pool, then wait for 7,590 more pictures",
            Offline.readiness(PoolCheck.Behind("1", "2", 14_590, 0), 14_590, half, true).words,
        )
        assertEquals("Not ready for offline yet: download the art", Offline.readiness(current, 14_590, half, false).words)
        assertEquals("Not ready for offline yet: download the card pool, then download the art", Offline.readiness(null, 0, ArtCount.NONE, false).words)
        // On, but not yet counting (the pool not yet handed to the library): never "wait for 0 more".
        assertEquals("Not ready for offline yet: download the art", Offline.readiness(current, 14_590, ArtCount.NONE, true).words)
    }

    // ---- the title bar -----------------------------------------------------

    @Test
    fun theTitleBarShowsThePoolOverTheArtAndNothingWhenIdle() {
        val art = ArtCount(7_000, 0, 14_590, 0, perSecond = 10.0)
        val pool = Offline.readout(PoolProgress.Saving(7_000, 14_000), art, artRunning = true, problem = null)!!
        assertEquals("Card pool", pool.label)
        assertEquals("92%", pool.figure)
        val pictures = Offline.readout(null, art, artRunning = true, problem = null)!!
        assertEquals("Card art", pictures.label)
        assertEquals("47%", pictures.figure)
        assertTrue(pictures.detail.endsWith("7,000 of 14,590, about 13 min left"), pictures.detail)
        assertNull(Offline.readout(null, art, artRunning = false, problem = null))
        assertNull(Offline.readout(null, ArtCount(14_590, 0, 14_590, 0), artRunning = true, problem = null))
    }
}
