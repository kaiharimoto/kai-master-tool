package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The field kept by date (Phase G part two, G2.3): a past day reads the same lists again. */
class FieldArchiveTest {
    @Test
    fun aSnapshotIsKeptByTheDayItStandsFor() {
        // Read on 2026-10-11 (UTC) as the latest: kept under the day it was read.
        val latest = FieldSnapshot(1_791_676_800_000, "TCG", 2, 45)
        assertEquals("2026-10-11", FieldArchive.day(latest))
        assertEquals("2026-10-11-TCG-t2-d45.json", FieldArchive.name(latest))
        // Read as of a past day: kept under that day.
        val past = latest.copy(asOf = "2025-04-07")
        assertEquals("2025-04-07-TCG-t2-d45.json", FieldArchive.name(past))
        assertEquals(FieldArchive.Key("2025-04-07", "TCG", 2, 45), FieldArchive.key("2025-04-07-TCG-t2-d45.json"))
        assertNull(FieldArchive.key("latest.json"))
        assertNull(FieldArchive.key(".2025-04-07-TCG-t2-d45.json.tmp"))
    }

    @Test
    fun theArchiveKeepsEveryListReadNotOnlyTheLegalOnes() {
        // The red team's R12: today's field was archived with its illegal lists already dropped, so reading that day again said
        // nothing was left out. The archive holds every list read; the latest keeps the legal ones.
        fun list(n: Int) = TournamentDeck(n, "List $n", "Event", "Top 8", 64, null, DeckFormat.TCG, 1, 2, Deck(main = listOf(CardId(n))), "", null, null)
        val read = listOf(list(1), list(2), list(3))
        val latest = FieldSnapshot.of(1_791_676_800_000, DeckFormat.TCG, 2, 45, null, read.take(2))
        val archived = FieldArchive.whole(latest, read)
        assertEquals(listOf(1, 2, 3), archived.lists.map { it.number })
        assertEquals(FieldArchive.name(latest), FieldArchive.name(archived))
        assertEquals(listOf(1, 2), latest.lists.map { it.number })
    }

    @Test
    fun aPastDayReadsItsOwnCopyElseTheNewestKeptBeforeIt() {
        val names = listOf(
            "2025-04-07-TCG-t2-d45.json", "2025-04-07-TCG-t3-d45.json", "2025-06-01-TCG-t2-d30.json",
            "2025-07-15-OCG-t2-d45.json", "2025-09-01-TCG-t2-d45.json", "latest.json",
        )
        assertEquals("2025-04-07", FieldArchive.pick(names, "2025-04-07", "TCG", 2, 45)?.day)
        assertEquals(3, FieldArchive.pick(names, "2025-04-07", "TCG", 3, 45)?.tier)
        // No copy for the day: the newest before it, of that format.
        assertEquals("2025-06-01", FieldArchive.pick(names, "2025-08-01", "tcg")?.day)
        assertEquals("2025-07-15", FieldArchive.pick(names, "2025-08-01", "OCG")?.day)
        assertNull(FieldArchive.pick(names, "2025-01-01", "TCG"))
    }
}
