package com.kaiharimoto.mastertool.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckWebTest {

    private val web = DeckWeb(
        id = "w",
        name = "Spring Regional",
        entries = listOf(WebEntry("snake", mine = true, share = 20), WebEntry("yubel", share = 18), WebEntry("tenpai", share = 15)),
    )

    @Test
    fun theSwitcherStepsRoundTheWeb() {
        assertEquals("yubel", web.neighbour("snake", 1))
        assertEquals("tenpai", web.neighbour("snake", -1))
        assertEquals("snake", web.neighbour("tenpai", 1))
        assertNull(web.neighbour("absent", 1))
        assertNull(DeckWeb("w", "One", entries = listOf(WebEntry("a"))).neighbour("a", 1))
        assertEquals(2, web.position("yubel"))
    }

    @Test
    fun aDeckIsAddedOnceAndStarredInPlace() {
        assertEquals(web, web.with(WebEntry("yubel")))
        assertEquals(listOf("snake", "yubel", "tenpai", "maliss"), web.with(WebEntry("maliss")).deckIds)
        val starred = web.starred("yubel", true)
        assertEquals(listOf("snake", "yubel"), starred.mine.map { it.deckId })
        assertEquals(listOf("snake", "yubel", "tenpai"), starred.deckIds)
    }

    @Test
    fun sharesStayInsideAHundredAndAddUp() {
        assertEquals(100, web.shared("yubel", 250).entry("yubel")?.share)
        assertNull(web.shared("yubel", null).entry("yubel")?.share)
        assertEquals(53, web.totalShare)
    }

    @Test
    fun aDeckMovesAndTheRestCloseUp() {
        assertEquals(listOf("tenpai", "snake", "yubel"), web.moved("tenpai", 0).deckIds)
        assertEquals(listOf("yubel", "tenpai", "snake"), web.moved("snake", 99).deckIds)
        assertEquals(listOf("snake", "tenpai"), web.without("yubel").deckIds)
    }

    @Test
    fun aDeckBelongsToOneWeb() {
        val other = DeckWeb("o", "Locals", entries = listOf(WebEntry("yubel"), WebEntry("ryzeal")))
        val library = WebLibrary().put(other).put(web)
        assertEquals("w", library.webOf("yubel")?.id)
        assertEquals(listOf("ryzeal"), library.byId("o")?.deckIds)
        assertEquals(setOf("snake", "yubel", "tenpai", "ryzeal"), library.deckIds)
        val renamed = library.put(web.copy(name = "Spring"))
        assertEquals(listOf("o", "w"), renamed.webs.map { it.id })
        assertEquals("Spring", renamed.byId("w")?.name)
        assertFalse(library.remove("w").deckIds.contains("snake"))
        assertTrue(library.remove("w").deckIds.contains("ryzeal"))
    }
}
