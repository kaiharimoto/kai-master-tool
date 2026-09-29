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

    @Test
    fun onlyAWebIsAWeb() {
        assertTrue(WebCodec.isWeb("﻿  #ydkw 1\n"))
        assertFalse(WebCodec.isWeb("#main\n14558127\n"))
        assertNull(WebCodec.read("#main\n14558127\n"))
        // A broken header still reads the decks.
        assertEquals(1, WebCodec.read("#ydkw 1\n#web {not json\n#deck x One\n#main\n1\n")?.decks?.size)
    }
}
