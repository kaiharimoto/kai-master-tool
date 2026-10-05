package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ShortcutAsking
import com.kaiharimoto.mastertool.core.duel.ShortcutStep
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.Dest
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxSamples
import com.kaiharimoto.mastertool.core.duel.effects.FxSamples.U
import com.kaiharimoto.mastertool.core.duel.effects.Landing
import com.kaiharimoto.mastertool.core.duel.effects.Purpose
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.Spot
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskScope
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.KeyChord
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.layout.FormFactor
import com.kaiharimoto.mastertool.core.layout.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Shortcut window's arithmetic (D.md §5¾.13): its words for every purpose and every destination, the candidates grouped
 * by place in the effect's order, the count and Enter's words, where it stands on the ten table sizes (never over a lit
 * card), and its keys.
 */
class ShortcutWindowTest {
    private val catalog = FxSamples.catalog
    private val g = FxSamples.game()
    private val s = g.state

    private fun question(a: ShortcutAsking): ShortcutStep.Asking =
        assertIs(a.run(FxSamples.written().at(g, resolveAtOnce = true), s, catalog))

    // ---- words ---------------------------------------------------------------------------------------------------

    @Test
    fun everyPurposeAndEveryDestinationHasWords() {
        val among = listOf(U.VELL_HAND, U.VELL_GY)
        for (p in Purpose.entries) for (dest in Dest.entries) {
            val d = Decision.Cards("${p.name.lowercase()} · Gatekeeper Herald (Call)", among, 1, 1, p, Landing(dest, 0), among.map { s.placeOf(it) })
            val sentence = ShortcutWindow.sentence(d, s, 0, catalog)
            val enter = ShortcutWindow.enter(d, listOf("Gatekeeper Vell"))
            assertTrue(sentence.isNotBlank() && "null" !in sentence, "$p → $dest: “$sentence”")
            assertTrue(sentence.first().isUpperCase(), "$p → $dest: “$sentence” begins with a capital")
            assertTrue(enter.isNotBlank() && "null" !in enter, "$p → $dest: Enter “$enter”")
            assertTrue("from your hand or GY" in sentence || "in your hand or GY" in sentence, "$p → $dest names its places: “$sentence”")
            if (p == Purpose.RETURN) assertTrue(ShortcutWindow.destWords(dest) in sentence, "a return says where: “$sentence”")
        }
        // Every destination in words of its own.
        assertEquals(Dest.entries.size, Dest.entries.map(ShortcutWindow::destWords).toSet().size)
    }

    @Test
    fun theSentencesReadAsTheMockupsWriteThem() {
        val call = question(ShortcutAsking.use(U.HERALD, 0, "call"))
        assertEquals("Special Summon 1 monster from your hand, Deck, GY or banishment", ShortcutWindow.sentence(call.decision, s, 0, catalog))
        val sweep = question(ShortcutAsking.use(U.HERALD, 0, "sweep"))
        assertEquals("Target up to 2 monsters they control, or in either GY or banishment", ShortcutWindow.sentence(sweep.decision, s, 0, catalog))
        val which = question(ShortcutAsking.use(U.HERALD, 0))
        assertEquals("Which Shortcut?", ShortcutWindow.sentence(which.decision, s, 0, catalog))
        assertEquals("Gatekeeper Herald · Call · Shortcut", ShortcutWindow.who("Gatekeeper Herald", "Call"))
    }

    @Test
    fun theCountAndEntersWords() {
        val one = Decision.Cards("Special Summon · X", listOf(1, 2), 1, 1, Purpose.SUMMON)
        assertEquals("0 / 1", ShortcutWindow.count(one, 0))
        assertEquals("Pick 1 more", ShortcutWindow.waiting(one, 0))
        assertNull(ShortcutWindow.waiting(one, 1))
        assertEquals("Summon Gatekeeper Vell", ShortcutWindow.enter(one, listOf("Gatekeeper Vell")))
        val upTo = Decision.Cards("Special Summon · X", listOf(1, 2, 3), 1, 2, Purpose.SUMMON)
        assertEquals("2 / up to 2", ShortcutWindow.count(upTo, 2))
        assertEquals("Summon these 2", ShortcutWindow.enter(upTo, listOf("A", "B")))
        val target = Decision.Cards("Target · X", listOf(1, 2, 3), 1, 2, Purpose.TARGET)
        assertEquals("1 of 2", ShortcutWindow.count(target, 1))
        assertEquals("Confirm 2 targets", ShortcutWindow.enter(target, listOf("A", "B")))
        assertEquals("Target 1 more", ShortcutWindow.waiting(target, 0))
        val cost = Decision.Cards("Discard · X", listOf(1, 2), 1, 1, Purpose.COST)
        assertEquals("Pay: discard Pawn", ShortcutWindow.enter(cost, listOf("Pawn")))
        val zone = Decision.Zone(listOf(Place.Zone(0, ZoneKind.MONSTER, 2)), 1, listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF))
        assertEquals("Place Oru in M3 · Attack", ShortcutWindow.enter(zone, listOf("Oru"), "M3", CardPosition.FACE_UP_ATK))
        // A pick that fills the count leaves nothing more to pick; the rest stay offered until it does.
        assertEquals(listOf(1, 2), FxEngine.stillLegal(upTo, listOf(0)))
        assertEquals(emptyList(), FxEngine.stillLegal(upTo, listOf(0, 2)))
    }

    @Test
    fun escSaysWhatItTakesBack() {
        var a = ShortcutAsking.use(U.HERALD, 0, "call")
        assertEquals("Cancel", ShortcutWindow.back(a))
        val pick = question(a)
        a = a.answer(listOf((pick.decision as Decision.Cards).among.indexOf(U.VELL_GY)))
        val zone = question(a)
        val z = zone.decision as Decision.Zone
        a = a.answer(listOf(z.among.indexOfFirst { it.index == 3 }))
        val pos = question(a)
        assertEquals("Back: take Gatekeeper Vell out of M4", ShortcutWindow.back(a, ShortcutWindow.lastWords(a, pos.asked, s, 0, catalog)))
    }

    @Test
    fun theCrumbsNameEachPartOfTheStep() {
        val which = question(ShortcutAsking.use(U.HERALD, 0))
        assertEquals(listOf("Which"), ShortcutWindow.crumbs(which).map { it.word })
        val pick = question(ShortcutAsking.use(U.HERALD, 0).answer(listOf(0)))
        val crumbs = ShortcutWindow.crumbs(pick)
        assertEquals(listOf("Which", "Pick", "Place"), crumbs.map { it.word })
        assertEquals(listOf(ShortcutWindow.Crumb.State.DONE, ShortcutWindow.Crumb.State.NOW, ShortcutWindow.Crumb.State.LATER), crumbs.map { it.state })
    }

    // ---- grouping -------------------------------------------------------------------------------------------------

    @Test
    fun candidatesAreGroupedByPlaceInTheEffectsOwnOrder() {
        val pick = question(ShortcutAsking.use(U.HERALD, 0, "call"))
        val d = pick.decision as Decision.Cards
        val groups = ShortcutWindow.groups(d, s, 0)
        assertEquals(listOf("Hand", "Deck", "GY", "Banished"), groups.map { it.head })
        assertEquals(listOf("h", "dk", "gy", "ban"), groups.map { it.coord })
        // Every candidate is in exactly one group, and each group holds only its own place's.
        assertEquals(d.among.indices.toSet(), groups.flatMap { it.indices }.toSet())
        assertEquals(d.among.size, groups.sumOf { it.indices.size })
        groups.forEach { gr -> gr.indices.forEach { i -> assertEquals((gr.place as Place.Pile).kind, (s.placeOf(d.among[i]) as Place.Pile).kind) } }
        // Each card wears its coordinate: the GY's first card, the Deck's own name.
        assertEquals("gy1", ShortcutWindow.coord(s, U.VELL_GY, 0))
        assertEquals("dk", ShortcutWindow.coord(s, U.ORU_DECK, 0))
    }

    @Test
    fun aPlaceLookedInWithNothingLegalIsNamed() {
        // Looking in the hand and the Extra Deck for a Level 4 or lower: the Extra Deck holds only a Link, nothing to call.
        val d = Decision.Cards(
            "Special Summon · X", listOf(U.VELL_HAND), 1, 1, Purpose.SUMMON,
            from = listOf(s.placeOf(U.VELL_HAND)), looked = listOf(Spot(Rel.YOU, Area.HAND), Spot(Rel.YOU, Area.EXTRA)),
        )
        val groups = ShortcutWindow.groups(d, s, 0)
        assertEquals(listOf("Hand", "Extra Deck"), groups.map { it.head })
        assertTrue(groups[1].indices.isEmpty())
        assertTrue(groups[1].none!!.startsWith("none"))
    }

    @Test
    fun aTargetsGroupsSpanBothSeatsFieldGyAndBanishment() {
        val sweep = question(ShortcutAsking.use(U.HERALD, 0, "sweep"))
        val heads = ShortcutWindow.groups(sweep.decision as Decision.Cards, s, 0).map { it.head }
        assertEquals(listOf("Their monsters", "GY", "Their GY", "Banished", "Their banished"), heads)
    }

    @Test
    fun positionChipsSayWhyARuledOutPositionIsOut() {
        val either = ShortcutWindow.chips(listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF))
        assertEquals(listOf(true, true, false), either.map { it.allowed })
        assertEquals("This effect summons face-up", either[2].why)
        val link = ShortcutWindow.chips(listOf(CardPosition.FACE_UP_ATK), link = true)
        assertEquals("A Link is never in Defense", link[1].why)
        assertEquals(listOf("A", "D", "E"), link.map { it.key })
    }

    @Test
    fun whatEachShortcutNeedsIsSaidFromItsScript() {
        val opts = FxSamples.written().at(g).options(s, 0, U.HERALD)
        assertEquals(listOf("no target", "no target", "up to 2 targets · they control, either GY or banishment"), opts.map { it.needs })
    }

    // ---- where it stands ------------------------------------------------------------------------------------------

    private val cases = listOf(
        Triple(390f, 844f, FormFactor.PHONE), Triple(844f, 390f, FormFactor.PHONE), Triple(412f, 915f, FormFactor.PHONE),
        Triple(1280f, 800f, FormFactor.TABLET), Triple(800f, 1280f, FormFactor.TABLET), Triple(1024f, 700f, FormFactor.DESK),
        Triple(1366f, 720f, FormFactor.DESK), Triple(1920f, 1032f, FormFactor.DESK), Triple(2560f, 1392f, FormFactor.DESK),
        Triple(3840f, 2112f, FormFactor.DESK),
    )

    private fun zones(l: DuelLayout, seat: Int) = (0 until 5).mapNotNull { l.zone(Place.Zone(seat, ZoneKind.MONSTER, it)) }

    @Test
    fun onTheTenTableSizesTheWindowNeverCoversALitCard() {
        for ((w, h, form) in cases) for (bottom in 0..1) {
            val l = DuelLayouter.solve(w, h, true, form, bottom)
            val near = l.bottom
            val far = 1 - near
            val card = l.zone(Place.Zone(near, ZoneKind.MONSTER, 0))!!
            val tall = minOf(150f, l.field.height * 0.38f)
            val band = minOf(88f, l.field.height * 0.2f)
            // Your piles and Extra Deck lit (a summon from them): over the far half.
            val piles = listOf(PileKind.DECK, PileKind.GY, PileKind.BANISHED, PileKind.EXTRA).mapNotNull { l.pile(near, it) } + card
            val a = ShortcutWindow.place(l, piles, tall, table = false)
            piles.forEach { lit -> assertEquals(0f, ShortcutWindow.overlap(lit, a.slot), "$w×$h ($bottom): the window over $lit (${a.band})") }
            // Your zones lit (a place step): a band over the far hand.
            val mine = zones(l, near) + card
            val b = ShortcutWindow.place(l, mine, band, table = true)
            mine.forEach { lit -> assertEquals(0f, ShortcutWindow.overlap(lit, b.slot), "$w×$h ($bottom): the band over $lit (${b.band})") }
            // Their side lit (a target): a band over the near hand.
            val theirs = zones(l, far) + listOfNotNull(l.pile(far, PileKind.GY), l.pile(far, PileKind.BANISHED)) + card
            val c = ShortcutWindow.place(l, theirs, band, table = true)
            theirs.forEach { lit -> assertEquals(0f, ShortcutWindow.overlap(lit, c.slot), "$w×$h ($bottom): the band over $lit (${c.band})") }
            // The field's width: the score column and the rails stay readable.
            listOf(a, b, c).forEach { p -> assertTrue(p.slot.left >= l.field.left - 0.01f && p.slot.right <= l.field.right + 0.01f, "$w×$h: within the field") }
        }
    }

    @Test
    fun onAPhoneItIsASheetOnTheBottomEdgeAndStandsAboveALitHand() {
        val l = DuelLayouter.solve(412f, 915f, true, FormFactor.PHONE, 0)
        val sheet = ShortcutWindow.place(l, emptyList(), 220f, table = false, phone = true)
        assertEquals(ShortcutWindow.Band.SHEET, sheet.band)
        assertEquals(l.height, sheet.slot.bottom, 0.01f)
        assertEquals(l.width, sheet.slot.width, 0.01f)
        val hand = l.pile(0, PileKind.HAND)!!
        val high = ShortcutWindow.place(l, listOf(hand), 220f, table = false, phone = true)
        assertEquals(ShortcutWindow.Band.SHEET_HIGH, high.band)
        assertEquals(0f, ShortcutWindow.overlap(hand, high.slot))
    }

    @Test
    fun whichStandsBesideTheCard() {
        val l = DuelLayouter.solve(1920f, 1032f, true)
        val card = l.zone(Place.Zone(0, ZoneKind.MONSTER, 0))!!
        val at = ShortcutWindow.beside(l, card, 280f, 160f, inHand = false)
        assertEquals(0f, ShortcutWindow.overlap(card, at))
        assertTrue(at.left >= card.right || at.right <= card.left)
        assertTrue(l[DuelSpot.Chain] != null)
    }

    // ---- keys -----------------------------------------------------------------------------------------------------

    private val choosing = DeskContext(onBuilder = false, onDuel = true, choosing = true)
    private val duelling = DeskContext(onBuilder = false, onDuel = true)

    @Test
    fun theWindowsKeysStandInForTheDuelsWhileItIsOpen() {
        assertEquals(DeskAction.SHORTCUT_CONFIRM, DeskShortcuts.resolve(KeyChord("enter"), choosing))
        assertEquals(DeskAction.SHORTCUT_TOGGLE, DeskShortcuts.resolve(KeyChord("space"), choosing))
        assertEquals(DeskAction.SHORTCUT_3, DeskShortcuts.resolve(KeyChord("3"), choosing))
        assertEquals(DeskAction.SHORTCUT_S3, DeskShortcuts.resolve(KeyChord("3", shift = true), choosing))
        assertEquals(DeskAction.SHORTCUT_DEFENSE, DeskShortcuts.resolve(KeyChord("d"), choosing))
        assertEquals(DeskAction.DISMISS, DeskShortcuts.resolve(KeyChord("escape"), choosing))
        // No duel key acts under an open choice: D does not draw, U does not start another.
        assertTrue(DeskShortcuts.live(choosing).none { it.scope == DeskScope.DUEL })
        assertEquals(DeskAction.DUEL_DRAW, DeskShortcuts.resolve(KeyChord("d"), duelling))
        assertEquals(DeskAction.DUEL_SHORTCUT, DeskShortcuts.resolve(KeyChord("u"), duelling))
        assertTrue(DeskShortcuts.live(duelling).none { it.scope == DeskScope.SHORTCUT_WINDOW })
        // One meaning a chord, while it is open.
        DeskShortcuts.live(choosing).groupBy { it.chord }.forEach { (chord, rows) ->
            assertEquals(1, rows.map { it.action }.toSet().size, "${DeskShortcuts.kbd(chord)} means ${rows.map { it.action }}")
        }
    }

    @Test
    fun everyBodyWritesItsKeysAndEveryGestureHasATypedForm() {
        ShortcutWindow.Body.entries.forEach { assertTrue(ShortcutWindow.keys(it).contains("Esc"), "$it") }
        // Every Shortcut window gesture is typed too (DuelCoverage), as the line gives the same answers.
        val rows = DuelCoverage.ROWS.filter { it.gesture.startsWith("Shortcut window") }
        listOf("pick=", "target=", "zone=", "pos=", "option=yes", "option=2", "order=", "declare=").forEach { key ->
            assertTrue(rows.any { key in it.typed }, "no typed form with $key")
        }
        rows.forEach { row ->
            val p = DuelCommand.parse(row.typed, s, 0, catalog)
            assertIs<DuelCommand.Parsed.Shortcut>(p, "“${row.typed}” is a Shortcut line: $p")
        }
        assertTrue(DuelCoverage.forVerb(DuelVerb.SHORTCUT) != null)
        assertFalse(DuelCoverage.ROWS.isEmpty())
    }
}
