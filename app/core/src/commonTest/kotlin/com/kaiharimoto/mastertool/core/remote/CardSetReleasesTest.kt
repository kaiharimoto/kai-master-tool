package com.kaiharimoto.mastertool.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardSetReleasesTest {

    private val sets = listOf(
        CardSetRelease("Chaos Origins", "CORI", 100, "2026-07-02"),
        CardSetRelease("Magnificent Monsters", "MAMO", 126, "2026-09-04"),
        CardSetRelease("A promo on the same day", "PROMO", 3, "2026-09-04"),
        CardSetRelease("Beyond the Brave", "BETB", 8, "2026-10-08"),
        CardSetRelease("Crocs collaboration card", "CRC1", 1, null),
        CardSetRelease("Broken date", "BAD", 1, "soon"),
    )

    @Test
    fun theLatestIsTheNewestAlreadyOut() {
        assertEquals("MAMO", CardSetReleases.latest(sets, "2026-09-26")?.code)
        assertEquals("BETB", CardSetReleases.latest(sets, "2026-10-08")?.code)
        assertEquals("CORI", CardSetReleases.latest(sets, "2026-08-01")?.code)
        assertNull(CardSetReleases.latest(sets, "2020-01-01"))
    }

    @Test
    fun theFeedDecodes() {
        val json = """[{"set_name":"Magnificent Monsters","set_code":"MAMO","num_of_cards":126,"tcg_date":"2026-09-04","set_image":"x"},
            {"set_name":"Crocs collaboration card","set_code":"CRC1","num_of_cards":1}]"""
        val decoded = YgoProDeckApi.json.decodeFromString<List<CardSetRelease>>(json)
        assertEquals(2, decoded.size)
        assertNull(decoded[1].tcgDate)
    }
}
