package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelLayoutTest {
    private data class Case(val w: Float, val h: Float, val form: FormFactor)

    private val cases = listOf(
        Case(390f, 844f, FormFactor.PHONE),
        Case(844f, 390f, FormFactor.PHONE),
        Case(412f, 915f, FormFactor.PHONE),
        Case(1280f, 800f, FormFactor.TABLET),
        Case(800f, 1280f, FormFactor.TABLET),
        Case(1024f, 700f, FormFactor.DESK),
        Case(1366f, 720f, FormFactor.DESK),
        Case(1920f, 1032f, FormFactor.DESK),
        Case(2560f, 1392f, FormFactor.DESK),
        Case(3840f, 2112f, FormFactor.DESK),
    )

    private fun every(block: (Case, Boolean, Int, DuelLayout) -> Unit) {
        cases.forEach { c -> listOf(true, false).forEach { two -> listOf(0, 1).forEach { bottom ->
            block(c, two, bottom, DuelLayouter.solve(c.w, c.h, two, c.form, bottom))
        } } }
    }

    private fun overlaps(a: Slot, b: Slot) =
        a.left < b.right - 0.01f && b.left < a.right - 0.01f && a.top < b.bottom - 0.01f && b.top < a.bottom - 0.01f

    @Test
    fun nothingOverlapsAndEverythingIsInsideTheWindow() = every { c, two, bottom, l ->
        val all = l.spots.values + l.score.values + listOfNotNull(l.turn, l.phases, l.inspector, l.log)
        all.forEachIndexed { i, a ->
            assertTrue(a.left >= -0.01f && a.top >= -0.01f && a.right <= c.w + 0.01f && a.bottom <= c.h + 0.5f, "$c two=$two bottom=$bottom: $a outside")
            all.drop(i + 1).forEach { b -> assertFalse(overlaps(a, b), "$c two=$two: $a overlaps $b") }
        }
    }

    @Test
    fun theCardIsReadableAndCappedAndTheLaneIsATenth() = every { c, two, _, l ->
        assertTrue(l.fits, "$c two=$two: card ${l.card}")
        assertTrue(l.card <= DuelLayouter.capFor(c.form) + 0.01f)
        assertTrue(l.gap in 4f..14f)
        assertTrue(abs(l.gap - (l.card * 0.1f).coerceIn(4f, 14f)) < 0.01f)
    }

    @Test
    fun everyZoneAndPileHasASpot() = every { _, two, bottom, l ->
        val seats = if (two) listOf(0, 1) else listOf(bottom)
        seats.forEach { s ->
            (0 until 5).forEach { i ->
                assertNotNull(l.zone(Place.Zone(s, ZoneKind.MONSTER, i)))
                assertNotNull(l.zone(Place.Zone(s, ZoneKind.SPELL, i)))
            }
            assertNotNull(l.zone(Place.Zone(s, ZoneKind.FIELD, 0)))
            PileKind.entries.forEach { k ->
                // A folded far hand is a count by its name, not a spot.
                if (k == PileKind.HAND && l.farHandFolded && s != bottom) assertNull(l.pile(s, k))
                else assertNotNull(l.pile(s, k), "$k of $s")
            }
        }
        assertNotNull(l.zone(Place.Zone(1, ZoneKind.EMZ, 0)))
        assertNotNull(l[DuelSpot.Chain])
        if (!two) assertNull(l.zone(Place.Zone(1 - bottom, ZoneKind.MONSTER, 0)))
    }

    @Test
    fun theFarSideIsTheNearSideTurnedRound() = every { _, two, bottom, l ->
        if (!two) return@every
        val near = bottom
        val far = 1 - bottom
        (0 until 5).forEach { i ->
            val mine = l.zone(Place.Zone(near, ZoneKind.MONSTER, i))!!
            val theirs = l.zone(Place.Zone(far, ZoneKind.MONSTER, 4 - i))!!
            assertEquals(mine.centerX, theirs.centerX, 0.01f)
            assertTrue(theirs.bottom < mine.top)
        }
        // Their deck is on our left, ours on our right.
        assertTrue(l.pile(far, PileKind.DECK)!!.centerX < l.pile(near, PileKind.DECK)!!.centerX)
        // Their spells are further from the middle than their monsters.
        assertTrue(l.zone(Place.Zone(far, ZoneKind.SPELL, 2))!!.top < l.zone(Place.Zone(far, ZoneKind.MONSTER, 2))!!.top)
        // The Extra Monster Zones keep their sides as seat 0 sees them.
        val left = l.zone(Place.Zone(0, ZoneKind.EMZ, 0))!!.centerX
        val right = l.zone(Place.Zone(0, ZoneKind.EMZ, 1))!!.centerX
        if (bottom == 0) assertTrue(left < right) else assertTrue(left > right)
    }

    @Test
    fun aBigWindowGrowsTheRailsAndMarginsNotTheField() {
        val hd = DuelLayouter.solve(1920f, 1032f, true)
        val uhd = DuelLayouter.solve(3840f, 2112f, true)
        assertEquals(DuelLayouter.CAP_DESK, uhd.card, 0.01f)
        assertEquals(DuelLayouter.INSPECTOR_MAX, uhd.inspector!!.width, 0.01f)
        assertEquals(DuelLayouter.LOG_MAX, uhd.log!!.width, 0.01f)
        assertTrue(hd.inspector != null && hd.log != null)
        // The grid is centred between the rails.
        val leftRoom = uhd.field.left - uhd.inspector!!.right
        val rightRoom = uhd.log!!.left - (uhd.phases.right)
        assertEquals(leftRoom, rightRoom, 1f)
    }

    @Test
    fun narrowWindowsFoldTheRails() {
        assertTrue(DuelLayouter.solve(390f, 844f, true, FormFactor.PHONE).drawers)
        val mid = DuelLayouter.solve(1100f, 1032f, true)
        assertTrue(mid.logInInspector || mid.drawers || mid.log != null)
        val desk = DuelLayouter.solve(1920f, 1032f, true)
        assertFalse(desk.drawers)
    }

    @Test
    fun aShortWindowShrinksTheFarSideFirst() {
        // A phone lying down, under the bar and the tabs.
        val l = DuelLayouter.solve(844f, 340f, true, FormFactor.PHONE)
        assertTrue(l.farScale < 1f)
        assertTrue(l.fits)
        val roomy = DuelLayouter.solve(1920f, 1032f, true)
        assertEquals(1f, roomy.farScale)
        assertFalse(roomy.farHandFolded)
    }

    @Test
    fun theOnePlayerTableIsBiggerThanTheTwoPlayerOne() {
        val one = DuelLayouter.solve(1366f, 720f, false)
        val two = DuelLayouter.solve(1366f, 720f, true)
        assertTrue(one.card > two.card)
    }

    @Test
    fun aPointBetweenZonesStillFindsOne() {
        val l = DuelLayouter.solve(1920f, 1032f, true)
        val a = l.zone(Place.Zone(0, ZoneKind.MONSTER, 1))!!
        val between = a.right + l.gap / 3f
        assertEquals(DuelSpot.Zone(Place.Zone(0, ZoneKind.MONSTER, 1)), l.spotAt(between, a.centerY))
    }

    @Test
    fun theScoreColumnHoldsBothSeatsAndThePhasesBetween() = every { _, two, bottom, l ->
        val near = l.score.getValue(bottom)
        assertEquals(l.field.bottom, near.bottom, 0.01f)
        assertTrue(near.left > l.field.right)
        assertTrue(l.phases.top >= l.turn.bottom && l.phases.bottom <= near.top)
        if (two) {
            val far = l.score.getValue(1 - bottom)
            assertEquals(l.field.top, far.top, 0.01f)
            assertTrue(far.bottom <= l.turn.top)
        } else {
            assertNull(l.score[1 - bottom])
        }
    }

    @Test
    fun everyPhaseControlIsAFingersWidthTallAndInsideItsColumn() = every { c, two, _, l ->
        val boxes = l.phaseBoxes()
        val top = if (l.phasesCompact) l.turn.top else l.phases.top
        boxes.forEachIndexed { i, b ->
            assertTrue(b.slot.height >= DuelLayouter.PHASE_MIN - 0.01f, "$c two=$two: ${b.kind} ${b.phase} is ${b.slot.height} tall")
            assertTrue(b.slot.top >= top - 0.01f && b.slot.bottom <= l.phases.bottom + 0.01f, "$c two=$two: ${b.kind} outside the column")
            boxes.drop(i + 1).forEach { o -> assertFalse(overlaps(b.slot, o.slot), "$c two=$two: ${b.kind} overlaps ${o.kind}") }
        }
        if (l.phasesCompact) assertEquals(listOf(PhaseBox.Kind.NOW, PhaseBox.Kind.NEXT, PhaseBox.Kind.END), boxes.map { it.kind })
        else assertEquals(7, boxes.size)
    }

    @Test
    fun aPhoneLyingDownGetsOneLargeNextPhase() {
        // 915 × 412 under the phone's bar and tabs (1.0.86: each phase box was about 12 dp here).
        listOf(340f, 312f).forEach { h ->
            val l = DuelLayouter.solve(915f, h, true, FormFactor.PHONE)
            assertTrue(l.phasesCompact, "915×$h")
            val boxes = l.phaseBoxes().associateBy { it.kind }
            val next = boxes.getValue(PhaseBox.Kind.NEXT).slot
            assertTrue(boxes.values.all { it.slot.height >= DuelLayouter.PHASE_MIN - 0.01f }, "915×$h: ${boxes.values.map { it.slot.height }}")
            assertTrue(next.height >= boxes.getValue(PhaseBox.Kind.NOW).slot.height)
            // The turn is folded into the phase now: the column starts where the turn stood.
            assertEquals(l.turn.top, boxes.getValue(PhaseBox.Kind.NOW).slot.top, 0.01f)
        }
        // A desk keeps every phase a click away.
        val desk = DuelLayouter.solve(1366f, 720f, true)
        assertFalse(desk.phasesCompact)
        assertEquals(com.kaiharimoto.mastertool.core.board.DuelPhase.entries, desk.phaseBoxes().mapNotNull { it.phase })
    }

    @Test
    fun theHandsSitRightAgainstTheField() {
        val l = DuelLayouter.solve(1920f, 984f, true)
        // No seat bars between (1.0.78): one lane from the field to each hand.
        assertEquals(l.gap, l.pile(l.near, PileKind.HAND)!!.top - l.field.bottom, 0.01f)
        assertEquals(l.gap, l.field.top - l.pile(l.far, PileKind.HAND)!!.bottom, 0.01f)
    }
}
