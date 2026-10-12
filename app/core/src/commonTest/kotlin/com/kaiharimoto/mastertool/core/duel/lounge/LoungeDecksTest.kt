package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A member's decks in the Lounge: what is read, and what is too large to bring to a table (the red team, 2026-10). */
class LoungeDecksTest {
    private fun ydk(main: Int, extra: Int = 0, side: Int = 0): String =
        "#main\n" + List(main) { "1001" }.joinToString("\n") +
            "\n#extra\n" + List(extra) { "2002" }.joinToString("\n") +
            "\n!side\n" + List(side) { "3003" }.joinToString("\n") + "\n"

    @Test
    fun aDeckPastTheTablesSizesIsRefusedInWords() {
        assertNull(LoungeDecks.tooLarge(assertNotNull(LoungeDecks.read(ydk(60, 15, 15)))))
        // The table's own bounds, the LAN table's too, are reached and not passed.
        assertNull(LoungeDecks.tooLarge(assertNotNull(LoungeDecks.read(ydk(DuelHost.MAX_MAIN, DuelHost.MAX_EXTRA, DuelHost.MAX_SIDE)))))
        val words = "That deck is too large for the table: at most 200 Main Deck, 100 Extra Deck and 100 Side Deck cards."
        for (big in listOf(ydk(DuelHost.MAX_MAIN + 1), ydk(40, extra = DuelHost.MAX_EXTRA + 1), ydk(40, side = DuelHost.MAX_SIDE + 1))) {
            // Still read, so the member's list shows it and they can mend it; never dealt.
            val deck = assertNotNull(LoungeDecks.read(big))
            assertEquals(words, LoungeDecks.tooLarge(deck))
        }
    }

    @Test
    fun theTextsBoundAloneHeldThousandsOfCards() {
        val flood = ydk(8_000)
        assertTrue(flood.length <= LoungeDecks.MAX_TEXT)
        val deck = assertNotNull(LoungeDecks.read(flood))
        assertEquals(8_000, deck.main.size)
        assertNotNull(LoungeDecks.tooLarge(deck))
    }
}
