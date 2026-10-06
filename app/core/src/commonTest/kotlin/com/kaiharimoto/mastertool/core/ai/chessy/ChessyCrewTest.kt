package com.kaiharimoto.mastertool.core.ai.chessy

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChessyCrewTest {
    private val window = Box(0f, 0f, 1600f, 1000f)

    @Test
    fun theDeckTheBuilderUsesAreItsSpots() {
        assertEquals(ChessyPoint.DECK, ChessyPoint.spotsFor("edit_deck").first())
        assertEquals(ChessyPoint.POOL, ChessyPoint.spotsFor("search_cards").first())
        assertEquals(ChessyPoint.GROUPS, ChessyPoint.spotsFor("set_groups").first())
        assertEquals(listOf("page.format"), ChessyPoint.spotsFor("navigate", buildJsonObject { put("page", JsonPrimitive("FORMAT")) }))
        // her memory and the web have no place on screen: the one in the chat box says it alone
        assertTrue(ChessyPoint.spotsFor("memory").isEmpty())
        assertTrue(ChessyPoint.spotsFor("web_search").isEmpty())
        assertTrue(ChessyPoint.spotsFor("navigate").isEmpty())
    }

    @Test
    fun aCopyStandsBesideHerTargetOnTheSideWithRoom() {
        // a button on the left: she stands to its right, clear of it, inside the window
        val button = Box(20f, 20f, 60f, 60f)
        val p = ChessyPlace.place(button, window, 120f, 8f)
        assertEquals(Side.RIGHT, p.side)
        assertFalse(p.at.overlaps(button))
        assertTrue(p.at.l >= window.l && p.at.r <= window.r && p.at.t >= window.t && p.at.b <= window.b)
        // near the right edge she turns round
        val right = ChessyPlace.place(Box(1500f, 400f, 1580f, 440f), window, 120f, 8f)
        assertEquals(Side.LEFT, right.side)
    }

    @Test
    fun copiesDoNotStandOnEachOther() {
        val target = Box(700f, 400f, 760f, 440f)
        val first = ChessyPlace.place(target, window, 120f, 8f)
        val second = ChessyPlace.place(target, window, 120f, 8f, taken = listOf(first.at))
        assertFalse(first.at.overlaps(second.at))
    }

    @Test
    fun aTargetFillingTheWindowGetsHerInItsCorner() {
        val deck = Box(0f, 0f, 1600f, 1000f)
        val p = ChessyPlace.place(deck, window, 120f, 8f)
        assertEquals(Side.OVER, p.side)
        assertTrue(p.at.r <= deck.r && p.at.l >= deck.l && p.at.t >= deck.t)
    }

    @Test
    fun sheBlinksInAndOut() {
        assertEquals(0f, ChessyCrewPlan.shown(1000, 1000, null))
        assertEquals(1f, ChessyCrewPlan.shown(1000 + ChessyCrewPlan.BLINK_MS, 1000, null))
        val goes = ChessyCrewPlan.leaves(5000)
        assertEquals(1f, ChessyCrewPlan.shown(5000, 1000, goes))
        assertEquals(0f, ChessyCrewPlan.shown(goes, 1000, goes))
    }
}
