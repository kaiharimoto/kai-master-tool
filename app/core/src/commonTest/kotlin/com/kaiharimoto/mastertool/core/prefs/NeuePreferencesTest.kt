package com.kaiharimoto.mastertool.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class NeuePreferencesTest {

    @Test
    fun defaultsAreAlreadySanitised() {
        assertEquals(NeuePreferences.DEFAULT, NeuePreferences.DEFAULT.sanitised())
    }

    /**
     * Auto save is on by default (1.0.22), and on once for everyone: the switch is
     * stored under a new name, so the `false` every document carried from when off
     * was the default is not read back.
     */
    @Test
    fun autoSaveIsOnByDefaultEvenForAnOldDocument() {
        assertTrue(NeuePreferences.DEFAULT.autoSave)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertTrue(json.decodeFromString(NeuePreferences.serializer(), """{"autoSave":false}""").autoSave)
        val off = json.encodeToString(NeuePreferences.serializer(), NeuePreferences.DEFAULT.copy(autoSave = false))
        assertFalse(json.decodeFromString(NeuePreferences.serializer(), off).autoSave)
    }

    /** The groups' names in zen (1.0.24): on unless turned off, and on for a document written before them. */
    @Test
    fun zenLabelsAreOnByDefaultAndKeepTheirSwitch() {
        assertTrue(NeuePreferences.DEFAULT.zenLabels)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertTrue(json.decodeFromString(NeuePreferences.serializer(), """{"autoZen":true}""").zenLabels)
        val off = json.encodeToString(NeuePreferences.serializer(), NeuePreferences.DEFAULT.copy(zenLabels = false))
        assertFalse(json.decodeFromString(NeuePreferences.serializer(), off).zenLabels)
    }

    @Test
    fun thePictureIsTheDefaultScreenshotAndAnUnknownShapeFallsBackToIt() {
        assertEquals(NeuePreferences.SHOT_PICTURE, NeuePreferences.DEFAULT.shotStyle)
        assertEquals(NeuePreferences.SHOT_LIST, NeuePreferences.DEFAULT.copy(shotStyle = NeuePreferences.SHOT_LIST).sanitised().shotStyle)
        assertEquals(NeuePreferences.SHOT_PICTURE, NeuePreferences.DEFAULT.copy(shotStyle = "stacks").sanitised().shotStyle)
    }

    @Test
    fun paperIsTheDefaultTheme() {
        // Master UI §1 law 7: light is default; dark is the exact inversion.
        assertEquals(NeueTheme.PAPER, NeuePreferences.DEFAULT.theme)
    }

    @Test
    fun zoomStepsAlongTheScaleAndStopsAtTheEnds() {
        val start = NeuePreferences.DEFAULT
        assertEquals(1.125f, start.zoomedIn().scale)
        assertEquals(0.875f, start.zoomedOut().scale)
        assertEquals(0.875f, start.zoomedOut().zoomedOut().scale)
        var up = start
        repeat(20) { up = up.zoomedIn() }
        assertEquals(NeuePreferences.SCALES.last(), up.scale)
    }

    @Test
    fun anOffStepScaleSnapsToItsNeighbour() {
        val odd = NeuePreferences.DEFAULT.copy(scale = 1.3f)
        assertEquals(1.5f, odd.zoomedIn().scale)
        assertEquals(1.25f, odd.zoomedOut().scale)
    }

    @Test
    fun sanitisingRepairsWhatADiskCanHoldAndALayoutCannotUse() {
        val broken = NeuePreferences(
            scale = Float.NaN,
            poolWidth = 5f,
            inspectorWidth = Float.POSITIVE_INFINITY,
            poolColumns = -3,
            foil = " ",
            window = WindowBounds(0f, 0f, 200f, 100f),
        ).sanitised()
        assertEquals(1f, broken.scale)
        assertEquals(NeuePreferences.MIN_POOL_WIDTH, broken.poolWidth)
        assertEquals(NeuePreferences.DEFAULT_INSPECTOR_WIDTH, broken.inspectorWidth)
        assertEquals(0, broken.poolColumns)
        assertEquals(NeuePreferences.DEFAULT_FOIL, broken.foil)
        // A window smaller than the minimum is a window that cannot be used, so it is forgotten.
        assertNull(broken.window)
    }

    @Test
    fun coversKeepTheLastThreeAndAnArtThatIsTheCardIsDropped() {
        val p = NeuePreferences(
            covers = mapOf("a" to listOf(1, 2, 2, 3, 4), "b" to emptyList()),
            arts = mapOf(10 to 10, 11 to 12),
        ).sanitised()
        assertEquals(mapOf("a" to listOf(2, 3, 4)), p.covers)
        assertEquals(mapOf(11 to 12), p.arts)
    }

    @Test
    fun theWheelsNumbersStayInTheirRanges() {
        val p = NeuePreferences(deckZoom = 5f, groupGap = 0f, groupPalette = "").sanitised()
        assertEquals(1f, p.deckZoom)
        assertEquals(NeuePreferences.MIN_GAP, p.groupGap)
        assertEquals(NeuePreferences.DEFAULT_PALETTE, p.groupPalette)
        assertEquals(NeuePreferences.MIN_ZOOM, NeuePreferences(deckZoom = 0.01f).sanitised().deckZoom)
    }

    @Test
    fun textSizeSnapsToItsStepsAndATabletReadsOneUp() {
        // touch swarm, rec 26: out-of-range values land on the nearest step; none stored is the platform's.
        assertEquals(1.3f, NeuePreferences(textScale = 4f).sanitised().textScale)
        assertEquals(1f, NeuePreferences(textScale = 0.2f).sanitised().textScale)
        assertEquals(1.15f, NeuePreferences(textScale = 1.12f).sanitised().textScale)
        assertEquals(null, NeuePreferences(textScale = Float.NaN).sanitised().textScale)
        assertEquals(NeuePreferences.TABLET_TEXT_SCALE, NeuePreferences.DEFAULT.textScaleOn(touch = true))
        assertEquals(1f, NeuePreferences.DEFAULT.textScaleOn(touch = false))
        assertEquals(1.3f, NeuePreferences(textScale = 1.3f).textScaleOn(touch = false))
    }
}
