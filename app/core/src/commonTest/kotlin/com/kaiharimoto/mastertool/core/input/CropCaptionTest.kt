package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropCaptionTest {
    private val button = CursorTarget(CursorMode.POINTER, CursorBox(100f, 100f, 80f, 32f))

    @Test
    fun theFrameOpensFivePixelsOutsideATarget() {
        val (box, wide) = CropCaption.geometry(CursorMode.POINTER, button, 120f, 110f, 1920f, 1080f)
        assertEquals(CursorBox(95f, 95f, 90f, 42f), box)
        assertTrue(!wide)
    }

    @Test
    fun aWideTargetIsFramedSixPixelsInsideAndItsCaptionFollowsThePointer() {
        val row = CursorTarget(CursorMode.POINTER, CursorBox(0f, 200f, 600f, 56f), caption = "Open")
        val (box, wide) = CropCaption.geometry(CursorMode.POINTER, row, 300f, 220f, 1920f, 1080f)
        assertEquals(CursorBox(6f, 206f, 588f, 44f), box)
        assertTrue(wide)
        assertEquals(292f, CropCaption.captionAt(box, wide, 300f, 60f, 20f, 1920f, 1080f).first)
    }

    @Test
    fun atRestTheMarksAreSixteenPixelsAroundThePoint() {
        val (box, _) = CropCaption.geometry(CursorMode.DEFAULT, null, 500f, 400f, 1920f, 1080f)
        assertEquals(CursorBox(492f, 392f, 16f, 16f), box)
    }

    @Test
    fun aTextCaretIsAsTallAsItsTypeAndCentredOnALine() {
        val field = CursorTarget(CursorMode.TEXT, CursorBox(0f, 0f, 300f, 36f), fontSize = 14f)
        val (box, _) = CropCaption.geometry(CursorMode.TEXT, field, 40f, 5f, 1920f, 1080f)
        assertEquals(10f, box.w)
        assertEquals(25f, box.h) // 14 × 1.8, rounded
        assertEquals(18f, box.y + box.h / 2f, 0.5f)
        // Capped at 56, away from the window's edge (which would clip it).
        val huge = field.copy(fontSize = 96f, bounds = CursorBox(0f, 400f, 300f, 80f))
        assertEquals(56f, CropCaption.geometry(CursorMode.TEXT, huge, 40f, 420f, 1920f, 1080f).first.h)
    }

    @Test
    fun aDragBarLiesAlongItsTarget() {
        val rule = CursorTarget(CursorMode.DRAG, CursorBox(440f, 0f, 7f, 900f))
        val (tall, _) = CropCaption.geometry(CursorMode.DRAG, rule, 443f, 300f, 1920f, 1080f)
        assertEquals(14f to 36f, tall.w to tall.h)
        val slider = CursorTarget(CursorMode.DRAG, CursorBox(100f, 100f, 200f, 20f), slider = true)
        val (flat, _) = CropCaption.geometry(CursorMode.DRAG, slider, 150f, 103f, 1920f, 1080f)
        assertEquals(36f to 14f, flat.w to flat.h)
        assertEquals(110f, flat.y + flat.h / 2f)
    }

    @Test
    fun theMarksStayOnScreen() {
        val (box, _) = CropCaption.geometry(CursorMode.DEFAULT, null, 2f, 2f, 1920f, 1080f)
        assertTrue(box.x >= 1f && box.y >= 1f)
    }

    @Test
    fun captionsFollowTheVoiceRules() {
        // A button that shows its words needs none; an icon-only one says its label.
        assertNull(CropCaption.caption(CursorMode.POINTER, button.copy(label = "Save", showsWords = true), null))
        assertEquals("Undo", CropCaption.caption(CursorMode.POINTER, button.copy(label = "Undo"), null)?.text)
        assertEquals("Open", CropCaption.caption(CursorMode.POINTER, button.copy(caption = "Open", showsWords = true), null)?.text)
        // A text field says Edit until it has focus.
        val field = CursorTarget(CursorMode.TEXT, CursorBox(0f, 0f, 100f, 30f))
        assertEquals("Edit", CropCaption.caption(CursorMode.TEXT, field, null)?.text)
        assertNull(CropCaption.caption(CursorMode.TEXT, field.copy(focused = true), null))
        // A disabled control says why.
        val off = CursorTarget(CursorMode.NO, CursorBox(0f, 0f, 10f, 10f), reason = "Nothing to undo")
        assertEquals("Nothing to undo", CropCaption.caption(CursorMode.NO, off, null)?.text)
        // A drag says its name and its value.
        val rule = CursorTarget(CursorMode.DRAG, CursorBox(0f, 0f, 7f, 100f), caption = "Pool", value = "440 px")
        assertEquals(CursorCaption("Pool", "440 px"), CropCaption.caption(CursorMode.DRAG, rule, null))
        // Busy says Working, or its label, and its percent.
        assertEquals(CursorCaption("Working", "57%"), CropCaption.caption(CursorMode.BUSY, null, CursorBusy(pct = 57.2f)))
    }

    @Test
    fun busyOverridesEverything() {
        assertEquals(CursorMode.BUSY, CropCaption.modeOf(button, CursorBusy()))
        assertEquals(CursorMode.POINTER, CropCaption.modeOf(button, null))
    }

    @Test
    fun theInnermostTargetWins() {
        val outer = CursorTarget(CursorMode.BUSY, CursorBox(0f, 0f, 500f, 800f))
        val inner = CursorTarget(CursorMode.POINTER, CursorBox(10f, 10f, 100f, 140f))
        assertEquals(inner, CropCaption.innermost(listOf(outer, inner), 50f, 50f) { it.bounds })
        assertEquals(outer, CropCaption.innermost(listOf(outer, inner), 300f, 300f) { it.bounds })
        assertNull(CropCaption.innermost(listOf(outer, inner), 900f, 900f) { it.bounds })
    }

    @Test
    fun theCaptionFlipsAboveNearTheBottomAndNeverLeavesTheWindow() {
        val box = CursorBox(1880f, 1040f, 30f, 30f)
        val (cx, cy) = CropCaption.captionAt(box, false, 1890f, 80f, 20f, 1920f, 1080f)
        assertEquals(1040f - 6f - 20f, cy)
        assertEquals(1920f - 80f - 4f, cx)
    }

    @Test
    fun busyStepsClockwiseAQuarterTurnAt300ms() {
        assertEquals(listOf(0, 1, 2, 3, 0), listOf(0L, 300L, 600L, 900L, 1200L).map(CropCaption::litMark))
    }

    @Test
    fun theEasingRunsZeroToOne() {
        assertEquals(0f, CropCaption.ease(0f), 1e-3f)
        assertEquals(1f, CropCaption.ease(1f), 1e-3f)
        assertTrue(CropCaption.ease(0.5f) > 0.5f) // front-loaded, as (0.2, 0, 0, 1) is
    }
}
