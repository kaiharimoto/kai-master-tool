package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.prep.Policy.Swiss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tables of the KDE-US TCG Tournament Policy v2.5, §III.H and §III.I, at every boundary. */
class PolicyTest {

    @Test
    fun tierOneAndTwoRoundsFollowTheTable() {
        val table = listOf(
            4 to Swiss(3, 3, 0, 0), 8 to Swiss(3, 3, 0, 0),
            9 to Swiss(4, 4, 0, 4), 16 to Swiss(4, 4, 0, 4),
            17 to Swiss(5, 5, 0, 4), 32 to Swiss(5, 5, 0, 4),
            33 to Swiss(6, 6, 0, 8), 64 to Swiss(6, 6, 0, 8),
            65 to Swiss(7, 7, 0, 8), 128 to Swiss(7, 7, 0, 8),
            129 to Swiss(8, 8, 0, 8), 256 to Swiss(8, 8, 0, 8),
            257 to Swiss(9, 9, 0, 8), 512 to Swiss(9, 9, 0, 8),
            513 to Swiss(10, 10, 0, 8), 1024 to Swiss(10, 10, 0, 8),
            1025 to Swiss(11, 11, 0, 8), 2048 to Swiss(11, 11, 0, 8),
            2049 to Swiss(12, 12, 0, 8), 10_000 to Swiss(12, 12, 0, 8),
        )
        table.forEach { (players, swiss) ->
            assertEquals(swiss, Policy.swiss(1, players), "Tier 1, $players players")
            assertEquals(swiss, Policy.swiss(2, players), "Tier 2, $players players")
        }
        // The policy's example: 127 registered is 7 rounds and a Top 8.
        assertEquals(Swiss(7, 7, 0, 8), Policy.swiss(2, 127))
        // Under four cannot be sanctioned; it gets the smallest row rather than nothing.
        assertEquals(Swiss(3, 3, 0, 0), Policy.swiss(1, 2))
    }

    @Test
    fun tierThreeAndFourSplitOverTwoDays() {
        val table = listOf(
            129 to Swiss(11, 7, 4, 8), 256 to Swiss(11, 7, 4, 8),
            257 to Swiss(12, 8, 4, 8), 512 to Swiss(12, 8, 4, 8),
            513 to Swiss(13, 8, 5, 8), 1024 to Swiss(13, 8, 5, 8),
            1025 to Swiss(14, 9, 5, 8), 2048 to Swiss(14, 9, 5, 8),
            2049 to Swiss(15, 9, 6, 8), 5000 to Swiss(15, 9, 6, 8),
        )
        table.forEach { (players, swiss) ->
            assertEquals(swiss, Policy.swiss(3, players), "Tier 3, $players players")
            assertEquals(swiss, Policy.swiss(4, players), "Tier 4, $players players")
            assertTrue(swiss.twoDays)
            assertEquals(swiss.rounds, swiss.day1 + swiss.day2)
        }
        // Below the table's first row, a Tier 3 is run on the Tier 1–2 row, in one day.
        assertEquals(Swiss(7, 7, 0, 8), Policy.swiss(3, 128))
        assertFalse(Policy.swiss(3, 128).twoDays)
    }

    @Test
    fun byesAddToTheCountAsThePolicysExampleSays() {
        assertEquals(1020, Policy.playersForRounds(1000, mapOf(2 to 5)))
        assertEquals(1000 + 2 * 2 + 3 * 8, Policy.playersForRounds(1000, mapOf(1 to 2, 3 to 3)))
        assertEquals(500, Policy.playersForRounds(500))
        // 1,000 in Round 1 is 13 rounds; the byes carry it past 1,024 into 14.
        assertEquals(13, Policy.swiss(3, 1000).rounds)
        assertEquals(14, Policy.swiss(3, Policy.playersForRounds(1000, mapOf(3 to 4))).rounds)
    }

    @Test
    fun requirementsStartAtTierTwo() {
        assertFalse(Policy.decklistRequired(1))
        assertFalse(Policy.sleevesRequired(1))
        (2..4).forEach {
            assertTrue(Policy.decklistRequired(it))
            assertTrue(Policy.sleevesRequired(it))
        }
        assertEquals(50, Policy.ROUND_MINUTES)
        assertEquals(3, Policy.SIDING_MINUTES)
        assertTrue(Policy.rules.size >= 7)
    }

    @Test
    fun theCutRecordReadsTheIdealSwiss() {
        // 100 players, 7 rounds: about 6 players finish 6-1 or better, 16 more at 5-2.
        assertEquals(
            "6-1 or better makes Top 8; some 5-2s make it on tie-breakers",
            Policy.cutRecord(Policy.swiss(2, 100), 100),
        )
        // A full 16 in 4 rounds: one 4-0, four 3-1s for three seats.
        assertEquals(
            "4-0 or better makes Top 4; some 3-1s make it on tie-breakers",
            Policy.cutRecord(Policy.swiss(1, 16), 16),
        )
        // 1,000 at a YCS: 13 rounds, 12-1 is in, 11-2 is on breakers.
        assertEquals(
            "12-1 or better makes Top 8; some 11-2s make it on tie-breakers",
            Policy.cutRecord(Policy.swiss(3, 1000), 1000),
        )
        assertEquals("No top cut: the best record after 3 rounds wins", Policy.cutRecord(Policy.swiss(1, 6), 6))
        assertEquals("Everyone makes Top 8", Policy.cutRecord(Swiss(3, 3, 0, 8), 8))
    }
}
