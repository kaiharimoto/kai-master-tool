package com.kaiharimoto.mastertool.core.ydk

import kotlin.test.Test
import kotlin.test.assertEquals

class DeckQrGridTest {

    @Test
    fun aWideWindowStandsTheCodesSideBySide() {
        assertEquals(2, DeckQrGrid.columns(2, 1600f, 700f))
        assertEquals(3, DeckQrGrid.columns(3, 1800f, 600f))
    }

    @Test
    fun anUprightWindowStacksThem() {
        assertEquals(1, DeckQrGrid.columns(2, 400f, 900f))
    }

    @Test
    fun fourInASquareAreTwoByTwo() {
        assertEquals(2, DeckQrGrid.columns(4, 800f, 800f))
        assertEquals(390f, DeckQrGrid.side(4, 2, 800f, 800f, gap = 20f))
    }

    @Test
    fun theLabelsTakeTheirRoomFromTheHeight() {
        assertEquals(380f, DeckQrGrid.side(1, 1, 1000f, 400f, label = 20f))
    }
}
