package com.kaiharimoto.mastertool.core.input

import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.core.haptics.DeskFeel
import com.kaiharimoto.mastertool.core.haptics.Haptic
import com.kaiharimoto.mastertool.core.haptics.HapticScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FingerTimingTest {

    private fun TapBurst.tap(down: Long, up: Long, x: Float, y: Float, key: Int) = run {
        press(down, x, y, key)
        release(up)
    }

    @Test
    fun aDoubleTapThatDriftsOntoTheNeighbourIsStillTheFirstCards() {
        val b = TapBurst(repeats = false)
        assertEquals(TapBurst.Result(TapBurst.Kind.TAP, 7), b.tap(0, 90, 100f, 100f, 7))
        // 18dp across, onto card 8: within the 24dp slop, so it is card 7's double-tap.
        assertEquals(TapBurst.Result(TapBurst.Kind.DOUBLE_TAP, 7), b.tap(250, 320, 118f, 100f, 8))
    }

    @Test
    fun theWindowRunsFromTheLiftNotThePress() {
        val b = TapBurst(repeats = false)
        // A slow first tap: 260ms down. Counted from the press, the second would be late.
        b.tap(0, 260, 50f, 50f, 1)
        assertEquals(TapBurst.Kind.DOUBLE_TAP, b.tap(480, 540, 52f, 50f, 1).kind)
    }

    @Test
    fun aBounceAndALateTapAreNotDoubles() {
        val b = TapBurst(repeats = false)
        b.tap(0, 60, 0f, 0f, 1)
        assertEquals(TapBurst.Kind.TAP, b.tap(80, 120, 0f, 0f, 1).kind, "20ms after the lift is a bounce")
        val c = TapBurst(repeats = false)
        c.tap(0, 60, 0f, 0f, 1)
        assertEquals(TapBurst.Kind.TAP, c.tap(400, 450, 0f, 0f, 1).kind, "340ms after the lift is a new tap")
    }

    @Test
    fun aTripleTapRepeatsInThePoolAndEndsInTheDeck() {
        val pool = TapBurst(repeats = true)
        pool.tap(0, 60, 0f, 0f, 3)
        assertEquals(TapBurst.Kind.DOUBLE_TAP, pool.tap(160, 220, 0f, 0f, 3).kind)
        assertEquals(TapBurst.Result(TapBurst.Kind.REPEAT, 3), pool.tap(320, 380, 2f, 0f, 3))
        val deck = TapBurst(repeats = false)
        deck.tap(0, 60, 0f, 0f, 3)
        assertEquals(TapBurst.Kind.DOUBLE_TAP, deck.tap(160, 220, 0f, 0f, 3).kind)
        assertEquals(TapBurst.Kind.TAP, deck.tap(320, 380, 0f, 0f, 3).kind, "the deck's burst ends at its removal")
    }

    @Test
    fun aFingersCardRidesAboveItAndLandsWhereItIsDrawn() {
        val density = 2f
        val finger = CarryOffset.carried(500f, 800f, cardWidth = 60f, cardHeight = 87f, finger = true, density = density)
        assertEquals(128f, finger.width, "at least 64dp")
        assertEquals(800f - 12f * density, finger.top + finger.height, "its bottom edge 12dp above the finger")
        assertEquals(500f, finger.centreX)
        val (x, y) = CarryOffset.dropPoint(finger)
        assertEquals(finger.centreX, x)
        assertTrue(y < 800f, "the drop resolves above the finger, where the card is")
        val mouse = CarryOffset.carried(500f, 800f, cardWidth = 60f, cardHeight = 87f, finger = false, density = density)
        assertEquals(500f to 800f, CarryOffset.dropPoint(mouse), "a mouse's card is where the pointer is")
    }

    @Test
    fun onlyTheHandsSuccessesAreFelt() {
        assertNull(DeskFeel.of(DeskEvent.SELECTED))
        assertNull(DeskFeel.of(DeskEvent.ADD_REFUSED))
        assertNull(DeskFeel.of(DeskEvent.DROP_REFUSED))
        assertEquals(HapticScore.landing(false), DeskFeel.of(DeskEvent.DROPPED))
        assertEquals(HapticScore.landing(true), DeskFeel.of(DeskEvent.DROPPED_ON_CARD))
        DeskEvent.entries.mapNotNull(DeskFeel::of).forEach { assertTrue(it in Haptic.entries) }
    }

    @Test
    fun twoFingersAreReadBeforeTheyAreObeyed() {
        // A parallel slide with Groups on sets the gap; without Groups it is nothing.
        assertEquals(TwoFinger.Kind.GAP, TwoFinger.classify(200f, 202f, 3f, 40f, groupsOn = true, zoomAtOne = false, panesHidden = false))
        assertEquals(TwoFinger.Kind.NONE, TwoFinger.classify(200f, 202f, 3f, 40f, groupsOn = false, zoomAtOne = false, panesHidden = false))
        // A spread at full size asks for the panes to go; a pinch at full size with them gone brings them back.
        assertEquals(TwoFinger.Kind.HIDE_PANES, TwoFinger.classify(200f, 260f, 2f, 2f, groupsOn = false, zoomAtOne = true, panesHidden = false))
        assertEquals(TwoFinger.Kind.SHOW_PANES, TwoFinger.classify(200f, 150f, 2f, 2f, groupsOn = false, zoomAtOne = true, panesHidden = true))
        assertEquals(TwoFinger.Kind.ZOOM, TwoFinger.classify(200f, 150f, 2f, 2f, groupsOn = false, zoomAtOne = false, panesHidden = false))
        // Under the decision distance nothing is decided.
        assertEquals(TwoFinger.Kind.NONE, TwoFinger.classify(200f, 205f, 4f, 4f, groupsOn = true, zoomAtOne = true, panesHidden = false))
    }

    @Test
    fun aPinchIsNotATap() {
        assertEquals(TouchGesture.TWO_FINGER_TAP, MultiTap.classify(listOf(0, 40), listOf(120, 150), travel = 3f, slop = 8f))
        assertEquals(TouchGesture.THREE_FINGER_TAP, MultiTap.classify(listOf(0, 30, 60), listOf(150, 160, 170), travel = 2f, slop = 8f))
        assertNull(MultiTap.classify(listOf(0, 40), listOf(120, 150), travel = 40f, slop = 8f), "a pinch travels")
        assertNull(MultiTap.classify(listOf(0, 200), listOf(260, 280), travel = 2f, slop = 8f), "staggered fingers are not one tap")
        assertNull(MultiTap.classify(listOf(0, 40), listOf(500, 520), travel = 2f, slop = 8f), "a rest is not a tap")
    }
}

class TouchMetricsTest {
    @kotlin.test.Test
    fun packedTargetsAreAFingerApart() {
        // Chips pack in rows and columns: the pitch is the chip and its gap.
        kotlin.test.assertTrue(TouchMetrics.CHIP + TouchMetrics.CHIP_GAP >= TouchMetrics.MIN_PITCH)
        kotlin.test.assertTrue(TouchMetrics.MENU_ROW >= TouchMetrics.MIN_PITCH)
        kotlin.test.assertTrue(TouchMetrics.ICON + 8 >= TouchMetrics.MIN_PITCH)
        kotlin.test.assertTrue(TouchMetrics.SETTING_ROW >= TouchMetrics.MIN_PITCH)
    }
}
