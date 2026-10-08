package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.effects.FxCodec
import com.kaiharimoto.mastertool.core.duel.effects.FxMarks
import com.kaiharimoto.mastertool.core.duel.effects.FxPlayed
import com.kaiharimoto.mastertool.core.duel.effects.FxPlayedUse
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cards that play themselves (Phase D §5½ 4, `DuelPrefs.autoEffects`): with the switch off every default is as it was; on,
 * a card whose default is Activate uses its Shortcut, only when its script plays itself and the Shortcut is legal now, and
 * every other card keeps its manual default.
 */
class AutoEffectsTest {

    private val catalog = DuelCatalog { code -> FxRef.cards.firstOrNull { CardId(code) in it.passcodes }?.let { DuelCardInfo.of(it) } }

    /** Seat 0: Example Lamp in M1; Scout, Pawn and Flash in hand; a Scout in the GY. Seat 1: a Pawn in M1, a set card in S1. */
    private fun game(solo: Boolean = true): DuelGame {
        val mine = listOf(FxRef.LAMP, FxRef.SCOUT, FxRef.PAWN, FxRef.FLASH, FxRef.SCOUT, FxRef.SCOUT, FxRef.SCOUT, FxRef.TINKER)
        val theirs = listOf(FxRef.PAWN, FxRef.SNARE, FxRef.PAWN)
        val header = DuelHeader(seats = listOf(SeatSetup("Kai", mine), SeatSetup("Rival", theirs)), handSize = 0, solo = solo, first = 0)
        val a = 1
        val b = 1 + DuelState.SEAT_UIDS
        val lay = listOf(
            DuelAction.Move(a, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "place"),
            DuelAction.Move(a + 1, Place.Pile(0, PileKind.HAND), how = "place"),
            DuelAction.Move(a + 2, Place.Pile(0, PileKind.HAND), how = "place"),
            DuelAction.Move(a + 3, Place.Pile(0, PileKind.HAND), how = "place"),
            DuelAction.Move(a + 4, Place.Pile(0, PileKind.GY), how = "place"),
            DuelAction.Move(b, Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "place"),
            DuelAction.Move(b + 1, Place.Zone(1, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"),
            DuelAction.Phase(DuelPhase.MAIN1),
        )
        val dealt = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val r = dealt.act(lay, null)
        assertNull(r.problem, "the table lays out")
        return r.game.copy(floor = r.game.cursor)
    }

    private val lamp = 1
    private val scoutInHand = 2

    private val everything: (Int) -> Boolean = { true }

    @Test
    fun switchedOffEveryDefaultIsAsItWas() {
        for (solo in listOf(true, false)) {
            val g = game(solo)
            val sc = Shortcuts.of(g, FxRef.book, FxRef.facts)
            for (seat in 0..1) for (uid in g.state.cards.keys) {
                val plain = DuelVerbs.default(g.state, seat, uid, catalog)
                assertEquals(plain, DuelVerbs.defaultWith(g.state, seat, uid, catalog, sc, plays = null), "off: $uid for $seat")
                assertEquals(plain, DuelVerbs.defaultWith(g.state, seat, uid, catalog, null, everything), "no scripts: $uid for $seat")
                assertEquals(plain, DuelVerbs.defaultWith(g.state, seat, uid, catalog, sc) { false }, "nothing plays itself: $uid for $seat")
            }
        }
    }

    @Test
    fun switchedOnOnlyAnActivateWithALegalShortcutBecomesTheShortcut() {
        val g = game()
        val s = g.state
        val sc = Shortcuts.of(g, FxRef.book, FxRef.facts)
        var played = 0
        for (seat in 0..1) for (uid in s.cards.keys) {
            val plain = DuelVerbs.default(s, seat, uid, catalog)
            val auto = DuelVerbs.defaultWith(s, seat, uid, catalog, sc, everything)
            val expected = plain == DuelVerb.ACTIVATE && sc.options(s, seat, uid).any { it.legal }
            if (expected) {
                assertEquals(DuelVerb.SHORTCUT, auto, "$uid for $seat plays itself")
                played++
            } else {
                assertEquals(plain, auto, "$uid for $seat keeps its default")
            }
        }
        // Lamp on the field (Activate, its Send legal) plays itself; Scout in hand is a Summon by default and stays one.
        assertEquals(DuelVerb.SHORTCUT, DuelVerbs.defaultWith(s, 0, lamp, catalog, sc, everything))
        assertEquals(DuelVerb.SUMMON, DuelVerbs.defaultWith(s, 0, scoutInHand, catalog, sc, everything))
        assertTrue(played >= 1)
        // Only the cards whose script plays itself: Lamp's alone switched off leaves it its manual Activate.
        val lampCode = FxRef.book.canonical(s.cards.getValue(lamp).code)
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.defaultWith(s, 0, lamp, catalog, sc) { it != lampCode })
        // The other seat never plays seat 0's cards.
        assertEquals(DuelVerbs.default(s, 1, lamp, catalog), DuelVerbs.defaultWith(s, 1, lamp, catalog, sc, everything))
    }

    @Test
    fun aShortcutThatIsNotLegalNowLeavesTheManualDefault() {
        val g = game()
        val s = g.state
        val sc = Shortcuts.of(g, FxRef.book, FxRef.facts)
        // A card on the field whose Activate default has written effects none of which the engine allows now keeps Activate.
        for (uid in s.cards.keys) {
            if (DuelVerbs.default(s, 0, uid, catalog) != DuelVerb.ACTIVATE) continue
            val opts = sc.options(s, 0, uid)
            if (opts.isNotEmpty() && opts.none { it.legal }) {
                assertEquals(DuelVerb.ACTIVATE, DuelVerbs.defaultWith(s, 0, uid, catalog, sc, everything), "$uid")
            }
        }
        // A networked table refuses every Shortcut in words, so nothing plays itself there.
        val net = Shortcuts.of(g, FxRef.book, FxRef.facts, networked = true)
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.defaultWith(s, 0, lamp, catalog, net, everything))
    }

    @Test
    fun aScriptPlaysItselfOnceTrustedWithNoOpenWarningAndPlayedByYou() {
        val caller = GoldfishFixtures.scripts.first { it.card == CALLER }
        val net = GoldfishFixtures.scripts.first { it.card == NET }
        val entries = mapOf(CALLER to GoldfishFixtures.entry(caller), NET to GoldfishFixtures.entry(net, warnings = 1))
        // Trusted, but never played by you: not yet.
        assertFalse(FxTrust(entries).playsItself(CALLER))
        // Played by you with the script it has now: it plays itself.
        val played = FxMarks.mark(
            FxPlayed(),
            listOf(FxPlayedUse(CALLER, FxCodec.hash(caller), "e1"), FxPlayedUse(NET, FxCodec.hash(net), "e1")),
            at = 1,
        )
        assertTrue(FxTrust(entries, played).playsItself(CALLER))
        // An open warning holds it back, played or not.
        assertFalse(FxTrust(entries, played).playsItself(NET))
        // A changed script loses the mark, and with it the right to play itself.
        val changed = caller.copy(effects = caller.effects.map { it.copy(label = "Call again") })
        assertFalse(FxTrust(mapOf(CALLER to GoldfishFixtures.entry(changed)), played).playsItself(CALLER))
        // A card with no entry never does.
        assertFalse(FxTrust(entries, played).playsItself(900_000_699))
    }

    @Test
    fun theSwitchIsOffByDefaultAndAnOlderDocumentReadsOff() {
        assertFalse(DuelPrefs().autoEffects)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString(DuelPrefs.serializer(), """{"twoSided":true,"autoDraw":false}""").autoEffects)
    }
}
