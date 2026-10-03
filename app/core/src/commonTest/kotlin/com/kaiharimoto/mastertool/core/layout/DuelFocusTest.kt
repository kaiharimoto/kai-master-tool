package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelFixtures
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.layout.DuelFocus.Dir
import com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The keyboard's walk over the duel table (1.0.87, Command mode). */
class DuelFocusTest {
    private val two = DuelFocus.Shape()
    private val one = DuelFocus.Shape(twoSided = false)

    /** Five cards in each hand, nothing on the field. */
    private fun dealt(solo: Boolean = false): DuelState {
        var s = DuelFixtures.bare(solo)
        s = DuelFixtures.ok(s, DuelAction.Draw(0, 5), 0)
        if (!solo) s = DuelFixtures.ok(s, DuelAction.Draw(1, 5), 1)
        return s
    }

    private fun walk(s: DuelState, from: Slot?, vararg dirs: Dir, viewer: Int = 0, shape: DuelFocus.Shape = two): Slot =
        dirs.fold(from) { at, d -> DuelFocus.step(at, d, s, viewer, shape) }!!

    private fun m(i: Int, seat: Int = 0) = DuelFocus.zone(seat, ZoneKind.MONSTER, i - 1)
    private fun st(i: Int, seat: Int = 0) = DuelFocus.zone(seat, ZoneKind.SPELL, i - 1)

    @Test
    fun theCoordinatesAreTheNotationsFromEitherSeat() {
        assertEquals("m3", DuelFocus.label(m(3), 0))
        assertEquals("om3", DuelFocus.label(m(3), 1))
        assertEquals("s1", DuelFocus.label(st(1, 1), 1))
        assertEquals("os5", DuelFocus.label(st(5, 1), 0))
        assertEquals("fz", DuelFocus.label(DuelFocus.zone(0, ZoneKind.FIELD), 0))
        assertEquals("ofz", DuelFocus.label(DuelFocus.zone(1, ZoneKind.FIELD), 0))
        // The Extra Monster Zones are nobody's: by their absolute index, from either seat.
        assertEquals("e1", DuelFocus.label(DuelFocus.zone(1, ZoneKind.EMZ, 0), 0))
        assertEquals("e2", DuelFocus.label(DuelFocus.zone(0, ZoneKind.EMZ, 1), 1))
        assertEquals("h2", DuelFocus.label(Slot.HandCard(0, 1), 0))
        assertEquals("oh4", DuelFocus.label(Slot.HandCard(1, 3), 0))
        assertEquals("gy", DuelFocus.label(Slot.Pile(0, PileKind.GY), 0))
        assertEquals("oban", DuelFocus.label(Slot.Pile(1, PileKind.BANISHED), 0))
        assertEquals("ex", DuelFocus.label(Slot.Pile(0, PileKind.EXTRA), 0))
        assertEquals("odk", DuelFocus.label(Slot.Pile(0, PileKind.DECK), 1))
        assertEquals("gy3", DuelFocus.label(Slot.PileCard(0, PileKind.GY, 2), 0))
        assertEquals("oex1", DuelFocus.label(Slot.PileCard(1, PileKind.EXTRA, 0), 0))
    }

    @Test
    fun theFirstPressStartsOnTheFirstCardInHandElseTheFirstMonsterZone() {
        val s = dealt()
        assertEquals(Slot.HandCard(0, 0), DuelFocus.step(null, Dir.UP, s, 0, two))
        assertEquals(Slot.HandCard(1, 0), DuelFocus.home(s, 1))
        assertEquals(m(1), DuelFocus.home(DuelFixtures.bare(), 0))
    }

    @Test
    fun upFromTheHandClimbsTheColumnsToTheirHand() {
        val s = dealt()
        // h1 of five stands over the first column: s1, m1, then the left Extra Monster Zone (nearer the middle
        // than their Banished pile), their m4 above it, their s4, and their hand.
        assertEquals(st(1), walk(s, Slot.HandCard(0, 0), Dir.UP))
        assertEquals(m(1), walk(s, Slot.HandCard(0, 0), Dir.UP, Dir.UP))
        assertEquals(DuelFocus.zone(0, ZoneKind.EMZ, 0), walk(s, Slot.HandCard(0, 0), Dir.UP, Dir.UP, Dir.UP))
        assertEquals(m(4, 1), walk(s, Slot.HandCard(0, 0), Dir.UP, Dir.UP, Dir.UP, Dir.UP))
        assertEquals(st(4, 1), walk(s, Slot.HandCard(0, 0), Dir.UP, Dir.UP, Dir.UP, Dir.UP, Dir.UP))
        assertTrue(walk(s, Slot.HandCard(0, 0), Dir.UP, Dir.UP, Dir.UP, Dir.UP, Dir.UP, Dir.UP) is Slot.HandCard)
        // The middle card of the hand is under m3.
        assertEquals(st(3), walk(s, Slot.HandCard(0, 2), Dir.UP))
        assertEquals(Slot.HandCard(0, 2), walk(s, m(3), Dir.DOWN, Dir.DOWN))
    }

    @Test
    fun nothingWraps() {
        val s = dealt()
        assertEquals(Slot.HandCard(0, 4), walk(s, Slot.HandCard(0, 4), Dir.RIGHT))
        assertEquals(Slot.HandCard(0, 0), walk(s, Slot.HandCard(0, 0), Dir.LEFT))
        assertEquals(Slot.HandCard(0, 2), walk(s, Slot.HandCard(0, 2), Dir.DOWN))
        assertEquals(Slot.HandCard(1, 0), walk(s, Slot.HandCard(1, 0), Dir.UP))
        assertEquals(Slot.Pile(0, PileKind.GY), walk(s, Slot.Pile(0, PileKind.GY), Dir.RIGHT))
    }

    @Test
    fun aRowIsWalkedAcrossEmptyZonesFromThePileToThePile() {
        val s = dealt()
        val row = generateSequence(DuelFocus.zone(0, ZoneKind.FIELD) as Slot) { at ->
            DuelFocus.step(at, Dir.RIGHT, s, 0, two).takeIf { it != at }
        }.toList()
        assertEquals(listOf("fz", "m1", "m2", "m3", "m4", "m5", "gy"), row.map { DuelFocus.label(it, 0) })
        // Their side runs the other way across the screen, as it is drawn turned round.
        val theirs = generateSequence(Slot.Pile(1, PileKind.GY) as Slot) { at ->
            DuelFocus.step(at, Dir.RIGHT, s, 0, two).takeIf { it != at }
        }.toList()
        assertEquals(listOf("ogy", "om5", "om4", "om3", "om2", "om1", "ofz"), theirs.map { DuelFocus.label(it, 0) })
        assertEquals(DuelFocus.zone(0, ZoneKind.FIELD), DuelFocus.rowStart(m(4), s, 0, two))
        assertEquals(Slot.Pile(0, PileKind.DECK), DuelFocus.rowEnd(st(2), s, 0, two))
        assertEquals(Slot.HandCard(0, 4), DuelFocus.rowEnd(Slot.HandCard(0, 1), s, 0, two))
    }

    @Test
    fun theOtherSeatAtTheBottomSeesItsOwnSideNear() {
        val s = dealt()
        // Seat 1 at the bottom: its own zones are m1… and the left Extra Monster Zone is index 1.
        assertEquals(st(1, 1), walk(s, Slot.HandCard(1, 0), Dir.UP, viewer = 1))
        assertEquals(DuelFocus.zone(0, ZoneKind.EMZ, 1), walk(s, Slot.HandCard(1, 0), Dir.UP, Dir.UP, Dir.UP, viewer = 1))
        assertEquals("e2", DuelFocus.label(walk(s, Slot.HandCard(1, 0), Dir.UP, Dir.UP, Dir.UP, viewer = 1), 1))
    }

    @Test
    fun oneSidedIsTheNearHalfWithTheExtraMonsterZonesOnTop() {
        val s = dealt()
        val rows = DuelFocus.rows(s, 0, one)
        assertEquals(4, rows.size)
        assertEquals(listOf("e1", "e2", "ban"), rows[0].map { DuelFocus.label(it.slot, 0) })
        assertEquals(DuelFocus.zone(0, ZoneKind.EMZ, 0), walk(s, m(1), Dir.UP, Dir.UP, shape = one))
        // A solo table is one-sided whatever the shape says.
        val solo = dealt(solo = true)
        assertEquals(4, DuelFocus.rows(solo, 0, two).size)
        // A far-side place on a one-sided table goes home.
        assertEquals(Slot.HandCard(0, 0), DuelFocus.settle(m(2, 1), s, 0, one))
    }

    @Test
    fun aFoldedFarHandHasNoRow() {
        val s = dealt()
        val folded = DuelFocus.Shape(farHand = false)
        assertEquals(6, DuelFocus.rows(s, 0, folded).size)
        assertEquals(7, DuelFocus.rows(s, 0, two).size)
        assertEquals(st(4, 1), walk(s, st(4, 1), Dir.UP, shape = folded))
    }

    @Test
    fun theHandRowShrinksAndGrowsWithTheHand() {
        var s = dealt()
        assertEquals(5, DuelFocus.rows(s, 0, two).last().size)
        val h5 = s.seats[0].hand[4]
        s = DuelFixtures.ok(s, DuelAction.Move(h5, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK))
        assertEquals(4, DuelFocus.rows(s, 0, two).last().size)
        assertEquals(Slot.HandCard(0, 3), DuelFocus.settle(Slot.HandCard(0, 4), s, 0, two))
        assertEquals(Slot.HandCard(0, 3), walk(s, Slot.HandCard(0, 4), Dir.RIGHT))
        s = DuelFixtures.ok(s, DuelAction.Draw(0, 3), 0)
        assertEquals(7, DuelFocus.rows(s, 0, two).last().size)
    }

    @Test
    fun theFocusFollowsTheCardItWasOn() {
        var s = dealt()
        val h2 = s.seats[0].hand[1]
        val before = Slot.HandCard(0, 1)
        assertEquals(h2, DuelFocus.uidAt(s, before))
        s = DuelFixtures.ok(s, DuelAction.Move(h2, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK))
        assertEquals(m(3), DuelFocus.follow(before, h2, s, 0, two))
        // Sent to the GY: the focus is on the GY; laid open, on the card in it.
        s = DuelFixtures.ok(s, DuelAction.Move(h2, Place.Pile(0, PileKind.GY)))
        assertEquals(Slot.Pile(0, PileKind.GY), DuelFocus.follow(m(3), h2, s, 0, two))
        assertEquals(Slot.PileCard(0, PileKind.GY, 0), DuelFocus.follow(m(3), h2, s, 0, two, strip = 0 to PileKind.GY))
        // A place with no card to follow stays, made good.
        assertEquals(m(2), DuelFocus.follow(m(2), null, s, 0, two))
        assertNull(DuelFocus.follow(null, h2, s, 0, two))
    }

    @Test
    fun theCardAtTheFocus() {
        var s = dealt()
        assertNull(DuelFocus.uidAt(s, m(1)))
        assertNull(DuelFocus.uidAt(s, Slot.Pile(0, PileKind.GY)))
        assertEquals(s.seats[0].deck.first(), DuelFocus.uidAt(s, Slot.Pile(0, PileKind.DECK)))
        assertEquals(s.seats[1].hand[3], DuelFocus.uidAt(s, Slot.HandCard(1, 3)))
        val zeus = s.seats[0].extra.first()
        s = DuelFixtures.ok(s, DuelAction.Move(zeus, Place.Zone(0, ZoneKind.EMZ, 1), CardPosition.FACE_UP_ATK))
        assertEquals(zeus, DuelFocus.uidAt(s, DuelFocus.zone(0, ZoneKind.EMZ, 1)))
        assertEquals(DuelFocus.zone(0, ZoneKind.EMZ, 1), DuelFocus.slotOf(s, zeus))
    }

    @Test
    fun anOpenPileIsWalkedByItsRows() {
        var s = dealt()
        s.seats[0].deck.take(10).forEach { u -> s = DuelFixtures.ok(s, DuelAction.Move(u, Place.Pile(0, PileKind.GY))) }
        val shape = DuelFocus.Shape(stripPerRow = 4)
        fun at(i: Int) = Slot.PileCard(0, PileKind.GY, i)
        assertEquals(at(1), DuelFocus.step(at(0), Dir.RIGHT, s, 0, shape))
        assertEquals(at(3), DuelFocus.step(at(3), Dir.RIGHT, s, 0, shape), "a row's end stays")
        assertEquals(at(7), DuelFocus.step(at(3), Dir.DOWN, s, 0, shape))
        assertEquals(at(9), DuelFocus.step(at(7), Dir.DOWN, s, 0, shape), "the short last row's last card")
        assertEquals(at(9), DuelFocus.step(at(9), Dir.DOWN, s, 0, shape))
        assertEquals(at(1), DuelFocus.step(at(1), Dir.UP, s, 0, shape))
        assertEquals(at(4), DuelFocus.rowStart(at(6), s, 0, shape))
        assertEquals(at(9), DuelFocus.rowEnd(at(8), s, 0, shape))
        assertEquals(at(9), DuelFocus.settle(at(14), s, 0, shape))
    }

    @Test
    fun aHiddenHandIsWalkedInTheOrderItIsShownNeverItsOwn() {
        var s = dealt()
        val secret = 42L
        val mine = DuelFocus.Eyes(setOf(0), secret)
        // Their hand as seat 0 is shown it: DuelView's order, sorted by veil, not the order the cards came in.
        val shown = DuelView.of(s, 0, secret).seats[1].hand.map { it.ref }
        val walked = (0 until 5).map { i -> DuelFocus.uidAt(s, Slot.HandCard(1, i), mine)!! }
        assertEquals(shown, walked.map { DuelView.veil(secret, it, s.epoch[it] ?: 0) })
        // The card drawn last is wherever its veil puts it: oh5 is not "the newest".
        s = DuelFixtures.ok(s, DuelAction.Draw(1, 1), 1)
        val newest = s.seats[1].hand.last()
        val at = DuelFocus.slotOf(s, newest, eyes = mine) as Slot.HandCard
        assertEquals(newest, DuelFocus.uidAt(s, at, mine))
        assertEquals(DuelFocus.Eyes(setOf(0), secret).hand(s, 1).indexOf(newest), at.index)
        // Your own hand, and a hot-seat seeing both, keep the true order.
        assertEquals(s.seats[0].hand, mine.hand(s, 0))
        assertEquals(s.seats[1].hand, DuelFocus.Eyes(setOf(0, 1), secret).hand(s, 1))
        // The table draws their hand in that same order, so the ring lands on the card the label names.
        val l = DuelLayouter.solve(1920f, 984f, true)
        val frames = DuelFrames.of(s, l, setOf(0), secret = secret)
        val drawn = s.seats[1].hand.sortedBy { u -> frames.first { it.uid == u }.x }
        assertEquals(mine.hand(s, 1), drawn)
    }

    /** The logical columns read in the same order as the zones drawn, on real layouts, from both seats. */
    @Test
    fun theGridAgreesWithTheTableAsDrawn() {
        val s = dealt()
        listOf(Triple(1920f, 984f, FormFactor.DESK), Triple(1280f, 752f, FormFactor.TABLET), Triple(844f, 340f, FormFactor.PHONE)).forEach { (w, h, form) ->
            listOf(true, false).forEach { twoSided ->
                listOf(0, 1).forEach { bottom ->
                    val l = DuelLayouter.solve(w, h, twoSided, form, bottom)
                    val shape = DuelFocus.Shape.of(l)
                    val rows = DuelFocus.rows(s, bottom, shape)
                    rows.forEach { row ->
                        val drawn = row.mapNotNull { cell ->
                            when (val slot = cell.slot) {
                                is Slot.Zone -> l.zone(slot.place)
                                is Slot.Pile -> l.pile(slot.seat, slot.kind)
                                else -> null
                            }?.let { cell to it }
                        }
                        drawn.zipWithNext().forEach { (a, b) ->
                            assertTrue(a.second.centerX < b.second.centerX, "$w×$h two=$twoSided bottom=$bottom: ${a.first} drawn right of ${b.first}")
                            assertEquals(a.second.top, b.second.top, 0.5f * l.cardHeight, "$w×$h: ${a.first} and ${b.first} on one row")
                        }
                        if (drawn.isNotEmpty()) assertEquals(row.count { it.slot !is Slot.HandCard }, drawn.size, "every cell is drawn")
                    }
                    // Rows run down the screen.
                    val tops = rows.mapNotNull { row ->
                        row.firstNotNullOfOrNull { c -> (c.slot as? Slot.Zone)?.let { l.zone(it.place) } ?: (c.slot as? Slot.Pile)?.let { l.pile(it.seat, it.kind) } }?.top
                    }
                    assertEquals(tops.sorted(), tops)
                }
            }
        }
    }
}
