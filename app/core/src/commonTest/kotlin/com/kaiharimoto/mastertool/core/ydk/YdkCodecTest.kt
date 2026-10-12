package com.kaiharimoto.mastertool.core.ydk

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YdkCodecTest {

    @Test
    fun parsesCanonicalFile() {
        val text = """
            #created by kai
            #main
            14558127
            14558127
            #extra
            86066372
            !side
            27204311
        """.trimIndent()

        val result = YdkCodec.parse(text)

        assertEquals(listOf(CardId(14558127), CardId(14558127)), result.document.deck.main)
        assertEquals(listOf(CardId(86066372)), result.document.deck.extra)
        assertEquals(listOf(CardId(27204311)), result.document.deck.side)
        assertEquals("kai", result.document.createdBy)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun preservesDuplicateCountsAndOrder() {
        val text = "#main\n1\n2\n1\n1\n#extra\n!side\n"
        val deck = YdkCodec.parse(text).document.deck
        assertContentEquals(
            listOf(CardId(1), CardId(2), CardId(1), CardId(1)),
            deck.main,
        )
    }

    @Test
    fun acceptsSectionsInAnyOrder() {
        // The desktop tool writes side before extra; other editors do the reverse.
        val text = "#main\n1\n!side\n2\n#extra\n3\n"
        val deck = YdkCodec.parse(text).document.deck
        assertEquals(listOf(CardId(1)), deck.main)
        assertEquals(listOf(CardId(2)), deck.side)
        assertEquals(listOf(CardId(3)), deck.extra)
    }

    @Test
    fun acceptsHashSideMarker() {
        val deck = YdkCodec.parse("#main\n1\n#side\n2\n").document.deck
        assertEquals(listOf(CardId(2)), deck.side)
    }

    @Test
    fun handlesCrlfAndBom() {
        val text = "﻿#main\r\n14558127\r\n#extra\r\n!side\r\n"
        val result = YdkCodec.parse(text)
        assertEquals(listOf(CardId(14558127)), result.document.deck.main)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun treatsLeadingIdsAsMainWhenMarkerMissing() {
        val deck = YdkCodec.parse("14558127\n27204311\n").document.deck
        assertEquals(2, deck.main.size)
    }

    @Test
    fun reportsUnreadableLinesWithoutLosingTheDeck() {
        val result = YdkCodec.parse("#main\n14558127\nnot-a-passcode\n27204311\n")
        assertEquals(2, result.document.deck.main.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun ignoresUnknownCommentLines() {
        val result = YdkCodec.parse("#created by someone\n#some-other-tool-marker\n#main\n1\n")
        assertEquals(listOf(CardId(1)), result.document.deck.main)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun readsYdkxExtendedBlock() {
        val text = """
            #main
            14558127
            #extra
            !side
            #ydkx-extended
            {
              "version": "1.0",
              "sidingPatterns": { "vs Snake-Eye": { "goingFirst": { "in": [1], "out": [2] } } }
            }
        """.trimIndent()

        val document = YdkCodec.parse(text).document
        assertTrue(document.isYdkx)
        assertNotNull(document.extended)
        assertTrue(document.extended!!.containsKey("sidingPatterns"))
    }

    @Test
    fun roundTripPreservesExtendedBlockVerbatim() {
        // The whole point: editing a deck on the tablet must not destroy siding
        // patterns authored on the desktop.
        val original = """
            #main
            14558127
            #extra
            86066372
            !side
            27204311
            #ydkx-extended
            {"version":"1.0","sidingPatterns":{"vs Snake-Eye":{"goingFirst":{"in":[1],"out":[2]}}},"notes":{"cards":{}}}
        """.trimIndent()

        val first = YdkCodec.parse(original).document
        val written = YdkCodec.write(first)
        val second = YdkCodec.parse(written).document

        assertEquals(first.deck, second.deck)
        assertEquals(first.extended, second.extended)
    }

    @Test
    fun aPlainYdkReaderGainsNoCardFromTheExtendedBlock() {
        // A simulator reads a .ydk line by line: a line of digits after !side is a Side Deck card to it, and it may not stop at
        // #ydkx-extended (I.4). The payload's own lists of passcodes must never stand alone on a line.
        val deck = Deck(main = listOf(CardId(1), CardId(2)), side = listOf(CardId(3)))
        val payload = Json.parseToJsonElement("""{"groups":{"keys":[{"id":"a","cards":[14558127,27204311]}]},"siding":{"plans":[[1,2,3]]}}""").jsonObject
        val text = YdkCodec.write(deck, extended = payload)
        val side = text.lines().dropWhile { it.trim() != "!side" }.drop(1).count { it.trim().isNotEmpty() && it.trim().all(Char::isDigit) }
        assertEquals(deck.side.size, side, text)
        assertEquals(payload, YdkCodec.parse(text).document.extended)
    }

    @Test
    fun writesCanonicalSectionOrder() {
        val deck = Deck(
            main = listOf(CardId(1)),
            extra = listOf(CardId(2)),
            side = listOf(CardId(3)),
        )
        val text = YdkCodec.write(deck)
        val mainAt = text.indexOf("#main")
        val extraAt = text.indexOf("#extra")
        val sideAt = text.indexOf("!side")
        assertTrue(mainAt < extraAt && extraAt < sideAt, "expected #main, #extra, !side order")
    }

    @Test
    fun writesEmptySectionMarkersSoOtherToolsCanRead() {
        val text = YdkCodec.write(Deck.EMPTY)
        assertTrue(text.contains("#main"))
        assertTrue(text.contains("#extra"))
        assertTrue(text.contains("!side"))
    }

    @Test
    fun deckRoundTripsExactly() {
        val deck = Deck(
            main = List(3) { CardId(14558127) } + CardId(27204311),
            extra = listOf(CardId(86066372), CardId(86066372)),
            side = listOf(CardId(10045474)),
        )
        assertEquals(deck, YdkCodec.parse(YdkCodec.write(deck)).document.deck)
    }

    @Test
    fun malformedExtendedBlockWarnsInsteadOfFailing() {
        val result = YdkCodec.parse("#main\n1\n#ydkx-extended\n{not valid json")
        assertEquals(listOf(CardId(1)), result.document.deck.main)
        assertNull(result.document.extended)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun emptyInputProducesEmptyDeck() {
        val result = YdkCodec.parse("")
        assertTrue(result.document.deck.isEmpty)
        assertTrue(result.warnings.isEmpty())
    }
}
