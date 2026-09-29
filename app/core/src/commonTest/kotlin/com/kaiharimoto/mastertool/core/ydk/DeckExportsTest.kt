package com.kaiharimoto.mastertool.core.ydk

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckExportsTest {

    private val deck = Deck(
        main = listOf(CardId(14558127), CardId(14558127), CardId(89631139)),
        extra = listOf(CardId(1861629)),
        side = emptyList(),
    )

    @Test
    fun aCodeIsThreeLittleEndianSectionsInBase64() {
        // 89631139 = 0x0557A9A3 → A3 A9 57 05. One card, one section: "o6lXBQ==".
        assertEquals("ydke://o6lXBQ==!!!", YdkeCodec.encode(Deck(main = listOf(CardId(89631139)))))
    }

    @Test
    fun aCodeRoundTrips() {
        val code = YdkeCodec.encode(deck)
        assertTrue(code.startsWith("ydke://") && code.endsWith("!"))
        assertEquals(deck, YdkeCodec.decode(code))
        assertEquals(Deck(), YdkeCodec.decode(YdkeCodec.encode(Deck())))
    }

    @Test
    fun somethingElseIsNotACode() {
        assertNull(YdkeCodec.decode("hello"))
        assertNull(YdkeCodec.decode("ydke://abc"))
    }

    @Test
    fun aTextListCountsEachCardOnceInOrder() {
        val names = mapOf(CardId(14558127) to "Ash Blossom & Joyous Spring", CardId(89631139) to "Blue-Eyes White Dragon")
        assertEquals(
            "Main Deck (3)\n2 Ash Blossom & Joyous Spring\n1 Blue-Eyes White Dragon\n\nExtra Deck (1)\n1 1861629",
            DeckText.write(deck) { names[it] },
        )
    }
}
