package com.kaiharimoto.mastertool.core.prep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrepCodecTest {

    private val doc = PrepDoc(
        events = listOf(
            PrepEvent("e1", "Spring Regional", "2026-10-17", tier = 2, attendance = 180, webId = "w1", deckId = "d1", deadline = "2026-10-16"),
        ),
        games = listOf(
            TestGame("g1", 1000, "d1", "yubel", "Yubel", TestGame.FIRST, 1, TestGame.WIN, TestGame.REASON_OUTPLAYED, listOf(14558127), 12),
        ),
        profile = PrepProfile("Kai Harimoto", "0123456789", "United States"),
        drills = mapOf("m1:FIRST" to DrillStat(2, 1, 5000, 1)),
        active = "e1",
    )

    @Test
    fun aDocumentRoundTrips() {
        assertEquals(doc, PrepCodec.decode(PrepCodec.encode(doc)))
        assertEquals("neue.prep", PrepDoc.KEY)
    }

    @Test
    fun garbageAndNothingReadAsEmpty() {
        assertEquals(PrepDoc.EMPTY, PrepCodec.decode(null))
        assertEquals(PrepDoc.EMPTY, PrepCodec.decode(""))
        assertEquals(PrepDoc.EMPTY, PrepCodec.decode("not json"))
        assertEquals(PrepDoc.EMPTY, PrepCodec.decode("[1, 2]"))
    }

    @Test
    fun aNewerBuildsKeysAreSkippedAndNullsTakeDefaults() {
        val text = """{"events":[{"id":"e","name":"Locals","date":"2026-10-01","tier":null,"future":true}],"profile":{"name":"K"},"later":{}}"""
        val read = PrepCodec.decode(text)
        assertEquals(1, read.events.first().tier)
        assertEquals(PrepEvent.DECKLIST_PAPER, read.events.first().decklist)
        assertEquals("K", read.profile.name)
    }

    @Test
    fun editsKeepOrder() {
        val later = PrepEvent("e2", "YCS", "2026-12-05", tier = 3)
        val earlier = PrepEvent("e0", "Locals", "2026-10-01")
        val d = doc.put(later).put(earlier)
        assertEquals(listOf("e0", "e1", "e2"), d.events.map { it.id })
        assertEquals("Spring Regional", d.activeEvent?.name)
        val removed = d.removeEvent("e1")
        assertNull(removed.active)
        assertEquals(1, removed.games.size)
        val g = TestGame("g0", 10, null, "x", "X", TestGame.SECOND, result = TestGame.LOSS)
        assertEquals(listOf("g0", "g1"), doc.record(g).games.map { it.id })
        assertEquals(listOf("g1"), doc.record(g).removeGame("g0").games.map { it.id })
    }
}
