package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxSamples
import com.kaiharimoto.mastertool.core.duel.effects.FxSamples.U
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shortcut at the table (Phase D §5½, §5¾, step 2): `U` and the verb strip use a written effect with the person's choices,
 * asked in the Shortcut window; Esc commits nothing; one undo takes the whole use back; the default, the right-click and
 * `Shift Q` are what they were with no written link; a networked table refuses it in words; the log says "(Shortcut)".
 */
class DuelEffectTableTest {

    private fun table(solo: Boolean = true, block: (Duels) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val d = Duels(Files.createTempDirectory("duel").toFile())
            d.catalog = FxSamples.catalog
            d.writtenEffects = { FxSamples.written() }
            d.game = FxSamples.game(solo = solo)
            d.bottom = 0
            block(d)
        }
    }

    private fun question(d: Duels): Decision = assertNotNull(d.shortcutPart.question, "the window asks: ${d.problem}").decision

    @Test
    fun uAndTheStripUseAWrittenEffectWithThePersonsChoices() = table { d ->
        val before = d.game!!
        // The verb strip offers Shortcut on the card that has a written effect, after its default.
        val menu = assertNotNull(verbMenu(d, before.state, U.HERALD, playsBoth = true))
        assertEquals(DuelVerbs.offered(before.state, 0, U.HERALD, d.catalog).first(), menu.verbs.first(), "the default stays first")
        assertTrue(DuelVerb.SHORTCUT in menu.verbs)
        assertFalse(DuelVerb.SHORTCUT in assertNotNull(verbMenu(d, before.state, U.VELL_HAND, playsBoth = true)).verbs, "no written effect, no Shortcut")

        // U on Herald: which Shortcut first.
        assertTrue(d.verb(U.HERALD, DuelVerb.SHORTCUT))
        assertTrue(d.choosing)
        assertIs<Decision.Option>(question(d))
        // 1: Call. Then the picking strip: the GY's Vell, by its digit's place in the strip, then Enter.
        d.shortcutPart.key(DeskAction.SHORTCUT_1)
        val pick = assertIs<Decision.Cards>(question(d))
        d.shortcutPart.togglePick(pick.among.indexOf(U.VELL_GY))
        d.shortcutPart.key(DeskAction.SHORTCUT_CONFIRM)
        // The place step: Defense on its chip, then 4 puts it in M4 — and the position question that follows is answered by it.
        assertIs<Decision.Zone>(question(d))
        d.shortcutPart.key(DeskAction.SHORTCUT_DEFENSE)
        d.shortcutPart.key(DeskAction.SHORTCUT_4)
        assertFalse(d.choosing, "the use is made: ${d.shortcutPart.question}")
        val after = d.game!!
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 3), after.state.placeOf(U.VELL_GY))
        assertEquals(CardPosition.FACE_UP_DEF, after.state.cards.getValue(U.VELL_GY).pos)
        // One group, every entry the engine's own; the log says it was a Shortcut.
        val made = after.entries.subList(before.cursor, after.cursor)
        assertEquals(1, made.map { it.group }.distinct().size)
        assertTrue(made.all { it.fx != null }, "every entry tagged")
        val line = DuelWords.say(before.state, after.state, made.first(), 0, d.catalog, FxSamples.book)
        assertTrue("Gatekeeper Herald · Call (Shortcut, unverified)" in line, line)
        // One undo takes the whole use back.
        d.undo()
        assertEquals(before.state, d.game!!.state)
    }

    @Test
    fun escCommitsNothing() = table { d ->
        val before = d.game!!
        assertTrue(d.useShortcut(U.HERALD, "call"))
        val pick = assertIs<Decision.Cards>(question(d))
        d.shortcutPart.togglePick(pick.among.indexOf(U.VELL_HAND))
        d.shortcutPart.confirm()
        assertIs<Decision.Zone>(question(d))
        // Esc: back to the pick; Ctrl Z is Esc here, back again — the whole use let go.
        assertTrue(dismiss(d))
        assertIs<Decision.Cards>(question(d))
        d.undo()
        assertFalse(d.choosing)
        assertEquals(before, d.game, "nothing was committed")
    }

    /** Esc on the Duel page, as `dismissDuel` takes it: the window's layer first. */
    private fun dismiss(d: Duels): Boolean {
        if (!d.choosing) return false
        d.shortcutPart.back()
        return true
    }

    @Test
    fun theDefaultAndTheRightClickAreUnchanged() = table { d ->
        val s = d.game!!.state
        for (uid in s.cards.keys) {
            val plain = DuelVerbs.offered(s, d.seatFor(uid), uid, d.catalog)
            val written = DuelVerbs.offered(s, d.seatFor(uid), uid, d.catalog, d.shortcuts())
            assertEquals(plain, written - DuelVerb.SHORTCUT, "$uid: nothing else moves")
            assertEquals(plain.firstOrNull(), written.firstOrNull(), "$uid: the default stays first")
        }
        // A right-click is the default verb: on Herald it does what it did, and never opens the window.
        d.verb(U.HERALD, DuelVerb.DEFAULT)
        assertFalse(d.choosing)
    }

    @Test
    fun shiftQIsUnchangedWithNoWrittenLinkAndOffersBothWaysWithOne() = table(solo = false) { d ->
        // A link whose card has no written effect: Shift Q resolves the chain by hand, as before.
        assertTrue(d.act(listOf(DuelAction.ChainAdd(0, U.VELL_HAND)), 0))
        assertFalse(d.writtenLink(d.game!!.state))
        assertTrue(d.resolveAll())
        assertTrue(d.game!!.state.chain.isEmpty())
        assertFalse(d.shortcutPart.resolveStrip)

        // A written link (Toll on a two-seat table waits on the chain): Shift Q asks By hand or By Shortcut.
        assertTrue(d.useShortcut(U.TOLL))
        assertIs<Decision.Zone>(question(d))
        d.shortcutPart.key(DeskAction.SHORTCUT_S1)
        assertFalse(d.choosing, "${d.shortcutPart.question}")
        assertEquals(1, d.game!!.state.chain.size)
        assertTrue(d.writtenLink(d.game!!.state))
        assertFalse(d.resolveAll())
        assertTrue(d.shortcutPart.resolveStrip)
        // By Shortcut: the engine resolves it, asking which two to send.
        assertTrue(d.resolveByShortcut(all = true))
        val send = assertIs<Decision.Cards>(question(d))
        assertEquals(2, send.min)
        send.among.withIndex().filter { it.value == U.ECHO_DECK_1 || it.value == U.ECHO_DECK_2 }.forEach { d.shortcutPart.togglePick(it.index) }
        d.shortcutPart.confirm()
        // Both Echoes' "you can draw": Y, Y, then their order.
        assertIs<Decision.YesNo>(question(d))
        d.shortcutPart.key(DeskAction.SHORTCUT_YES)
        d.shortcutPart.key(DeskAction.SHORTCUT_YES)
        assertIs<Decision.Order>(question(d))
        d.shortcutPart.key(DeskAction.SHORTCUT_CONFIRM)
        assertFalse(d.choosing, "${d.shortcutPart.question}")
        assertEquals(PileKind.GY, (d.game!!.state.placeOf(U.ECHO_DECK_1) as Place.Pile).kind)
    }

    @Test
    fun aNetworkedTableRefusesTheVerbInWords() = table { d ->
        d.role = Duels.NetRole.HOST
        try {
            assertFalse(d.useShortcut(U.HERALD))
            assertEquals(DuelHost.NO_SHORTCUTS, d.problem)
            assertFalse(d.choosing)
        } finally {
            d.role = null
        }
    }

    @Test
    fun theLineOpensTheWindowForWhatItLeavesOut() = table { d ->
        assertTrue(d.run("u m1 call pick=gy1"), d.problem.orEmpty())
        val zone = assertIs<Decision.Zone>(question(d), "the pick was given: the zone is asked")
        assertEquals(U.VELL_GY, zone.card)
        d.shortcutPart.close()
        assertNull(d.shortcutPart.question)
    }

}
