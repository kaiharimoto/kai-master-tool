package com.kaiharimoto.mastertool.core.web

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebCodecTest {

    private val snake = YdkDocument(
        Deck(main = listOf(CardId(14558127), CardId(14558127), CardId(89631139)), extra = listOf(CardId(1861629)), side = listOf(CardId(1))),
        extended = buildJsonObject {
            putJsonObject("groups") { put("lens", "ROLES") }
            put("notes", JsonPrimitive("Line one\nline two, with #deck in it"))
        },
    )
    private val yubel = YdkDocument(Deck(main = listOf(CardId(78371393))))

    private val file = WebFile(
        name = "Spring Regional",
        notes = "Top 32 of last month.\nYubel is a third of the room.",
        decks = listOf(
            WebFileDeck("a1", "Snake-Eye Fiendsmith", snake, mine = true, share = 20),
            WebFileDeck("b2", "Yubel", yubel, share = 18),
        ),
    )

    @Test
    fun aWebRoundTrips() {
        val text = WebCodec.write(file)
        assertTrue(text.startsWith("#ydkw 1\n#web {"))
        assertEquals(file, WebCodec.read(text))
    }

    @Test
    fun eachDeckBlockIsADeckFileOnItsOwn() {
        val text = WebCodec.write(file)
        val block = text.substringAfter("#deck a1 Snake-Eye Fiendsmith\n").substringBefore("\n#deck ")
        assertEquals(snake, YdkCodec.parse(block).document)
    }

    @Test
    fun aDeckTheHeaderDoesNotListIsKeptAtTheEnd() {
        val text = WebCodec.write(file) + "\n#deck c3 Tenpai Dragon\n#main\n39931513\n"
        val read = assertNotNull(WebCodec.read(text))
        assertEquals(listOf("a1", "b2", "c3"), read.decks.map { it.id })
        assertEquals("Tenpai Dragon", read.decks.last().name)
        assertFalse(read.decks.last().mine)
    }

    /** A header written elsewhere, with a share as a fraction, keeps its name, notes, stars and other shares. */
    @Test
    fun aShareThatIsNotWholeCostsNothingElse() {
        val original = WebCodec.write(file)
        val header = original.lines()[1]
        val odd = header.replace("\"share\":20", "\"share\":0.2").replace("\"share\":18", "\"share\":18.4")
        assertTrue(odd != header, "the fixture's shares were rewritten: $odd")
        val read = assertNotNull(WebCodec.read(original.replace(header, odd)))
        assertEquals("Spring Regional", read.name)
        assertEquals(file.notes, read.notes)
        assertEquals(listOf(true, false), read.decks.map { it.mine })
        assertEquals(listOf(20, 18), read.decks.map { it.share })
        // Written back, it is whole again.
        assertEquals(file, WebCodec.read(WebCodec.write(read)))
    }

    @Test
    fun oddFieldsReadForgivinglyAndNonsenseIsSkipped() {
        val text = "#ydkw 1\n#web {\"name\":\"Locals\",\"decks\":[{\"id\":\"a1\",\"mine\":\"true\",\"share\":\"55%\"},{\"mine\":true},{\"id\":\"b2\",\"share\":\"lots\"}]}\n\n#deck a1 Mine\n#main\n1\n\n#deck b2 Theirs\n#main\n2\n"
        val read = assertNotNull(WebCodec.read(text))
        assertEquals("Locals", read.name)
        assertEquals(listOf(true to 55, false to null), read.decks.map { it.mine to it.share })
        assertEquals(50, WebCodec.share("0.5"))
        assertEquals(100, WebCodec.share("140"))
        assertEquals(null, WebCodec.share("-3"))
    }

    @Test
    fun onlyAWebIsAWeb() {
        assertTrue(WebCodec.isWeb("﻿  #ydkw 1\n"))
        assertFalse(WebCodec.isWeb("#main\n14558127\n"))
        assertNull(WebCodec.read("#main\n14558127\n"))
        // A broken header still reads the decks.
        assertEquals(1, WebCodec.read("#ydkw 1\n#web {not json\n#deck x One\n#main\n1\n")?.decks?.size)
    }
}
