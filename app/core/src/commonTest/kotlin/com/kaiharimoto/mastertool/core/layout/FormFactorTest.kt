package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormFactorTest {

    @Test
    fun aPhoneIsAPhoneWhicheverWayRound() {
        // A Pixel 7: 412 x 915 dp, upright and lying down.
        assertEquals(FormFactor.PHONE, FormFactor.of(412f, 915f, touch = true))
        assertEquals(FormFactor.PHONE, FormFactor.of(915f, 412f, touch = true))
        assertEquals(FormFactor.PHONE, FormFactor.ofSmallestWidth(412f, touch = true))
    }

    @Test
    fun aTabletStaysATabletInPortrait() {
        assertEquals(FormFactor.TABLET, FormFactor.of(1280f, 800f, touch = true))
        assertEquals(FormFactor.TABLET, FormFactor.of(800f, 1280f, touch = true))
        assertEquals(FormFactor.TABLET, FormFactor.of(600f, 960f, touch = true))
    }

    @Test
    fun theDeskIsNeverAPhone() {
        // A small desktop window is a window to make bigger.
        assertEquals(FormFactor.DESK, FormFactor.of(420f, 700f, touch = false))
        assertEquals(FormFactor.DESK, FormFactor.of(1920f, 1080f, touch = false))
    }

    @Test
    fun aPhoneStandsUpAndATabletLiesDown() {
        assertEquals(ScreenOrientation.PORTRAIT, ScreenOrientation.resolve(null, FormFactor.PHONE))
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.resolve(null, FormFactor.TABLET))
        assertEquals(ScreenOrientation.AUTO, ScreenOrientation.resolve("auto", FormFactor.PHONE))
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.resolve("landscape", FormFactor.PHONE))
        // Something this version does not know is the device's default, not a crash.
        assertEquals(ScreenOrientation.PORTRAIT, ScreenOrientation.resolve("sideways", FormFactor.PHONE))
    }

    @Test
    fun theToggleWalksAllThreeAndComesBack() {
        var o = ScreenOrientation.PORTRAIT
        val seen = mutableListOf(o)
        repeat(3) { o = o.next(); seen += o }
        assertEquals(listOf(ScreenOrientation.PORTRAIT, ScreenOrientation.LANDSCAPE, ScreenOrientation.AUTO, ScreenOrientation.PORTRAIT), seen)
        // Every key round-trips through the stored preference.
        ScreenOrientation.entries.forEach { assertEquals(it.key, NeuePreferences(orientation = it.key).sanitised().orientation) }
    }

    @Test
    fun thePreferencesKeepOnlyWhatTheyKnow() {
        assertNull(NeuePreferences(orientation = "sideways").sanitised().orientation)
        assertEquals("HALF", NeuePreferences(phoneDockStop = "HIGH").sanitised().phoneDockStop)
        PoolStop.entries.forEach { assertEquals(it.name, NeuePreferences(phoneDockStop = it.name).sanitised().phoneDockStop) }
    }

    @Test
    fun aPhonesDeckIsFiveAcrossUprightAndMoreLyingDown() {
        // An upright phone's deck, 412 less its edges.
        assertEquals(5, DeckFitter.phoneColumns(380f))
        // A 360dp phone, four; the smallest still four; lying down, seven or more.
        assertEquals(4, DeckFitter.phoneColumns(328f))
        assertEquals(4, DeckFitter.phoneColumns(240f))
        assertTrue(DeckFitter.phoneColumns(540f) in 7..10)
        assertEquals(10, DeckFitter.phoneColumns(2000f))
        assertEquals(4, DeckFitter.phoneColumns(Float.NaN))
        // Every card at least a finger wide.
        for (w in 240..1000 step 20) {
            val cols = DeckFitter.phoneColumns(w.toFloat())
            if (cols > 4) assertTrue(w / cols >= DeckFitter.PHONE_CARD_MIN_DP, "at $w, $cols across")
        }
    }

    @Test
    fun aPhonesTapWaitsOutTheDoubleTap() {
        assertTrue(DeskTouch.PHONE_VIEW_MS > DeskTouch.DOUBLE_TAP_MS)
    }

    @Test
    fun aPhonesDeckIsFittedToItsWidthAndScrolls() {
        val width = 396f
        val cols = DeckFitter.phoneColumns(width)
        val requests = listOf(
            SectionFitRequest(count = 40, columns = cols, baselineCount = 40, spacing = 0f, chromeHeight = 13f),
            SectionFitRequest(count = 15, columns = cols, baselineCount = 15, spacing = 0f, chromeHeight = 13f),
        )
        val placed = DeckLabels.stack(width, 500f, 0.686f, rowHeight = 24f, requests = requests, labelled = listOf(false, true))
        assertEquals(LabelPlace.ROWS, placed.place)
        // Every card the width's share, whatever the height: a phone's deck scrolls rather than shrinks.
        placed.fit.sections.forEach { assertEquals(width / cols, it.cardWidth, 0.01f) }
        assertTrue(!placed.fit.fits)
        // The labelled section pays for its name's row, and the main deck does not.
        assertEquals(13f, placed.fit.sections[0].paneHeight - placed.fit.sections[0].gridHeight, 0.01f)
        assertEquals(37f, placed.fit.sections[1].paneHeight - placed.fit.sections[1].gridHeight, 0.01f)
    }

    @Test
    fun presentLiesDownWhateverWasChosen() {
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.resolve("portrait", FormFactor.PHONE, forceLandscape = true))
        assertEquals(ScreenOrientation.PORTRAIT, ScreenOrientation.resolve("portrait", FormFactor.PHONE, forceLandscape = false))
    }
}
