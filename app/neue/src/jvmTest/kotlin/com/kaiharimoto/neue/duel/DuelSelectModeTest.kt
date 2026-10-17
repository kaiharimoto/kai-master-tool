package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The red team on the duel table by a finger (2026-10): a press and hold is select mode, and nothing ended it once a verb
 * ran — the card just played stayed picked, so the next card's double-tap ran its default on both. A move made from select
 * mode ends it, and the next card is acted on alone.
 */
class DuelSelectModeTest {
    private fun table(block: (Duels) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val d = Duels(Files.createTempDirectory("duel").toFile())
            d.start(DuelHeader("sel", 3, listOf(SeatSetup("Kai", List(40) { 1 }), SeatSetup("Rival", List(40) { 2 }))))
            block(d)
        }
    }

    @Test
    fun aVerbEndsAFingersSelectMode() = table { d ->
        val (a, b) = d.game!!.state.seats[0].hand.take(2)
        // A finger's hold on A: select mode, A picked.
        d.selection = setOf(a)
        d.selecting = true
        assertTrue(d.verb(a, DuelVerb.SUMMON, seat = 0), d.problem.orEmpty())
        assertFalse(d.selecting, "the move ends select mode")
        assertTrue(d.selection.isEmpty(), "and lets the card go")
        val aWhere = d.game!!.state.placeOf(a)

        // B tapped after it: a tap with select mode off picks B alone, and its verb moves B alone.
        d.selection = setOf(b)
        assertTrue(d.verb(b, DuelVerb.SUMMON, seat = 0), d.problem.orEmpty())
        assertEquals(aWhere, d.game!!.state.placeOf(a), "A, played already, stays where it is")
    }

    @Test
    fun theStripsItemsEndSelectModeToo() = table { d ->
        val a = d.game!!.state.seats[0].hand.first()
        d.selection = setOf(a)
        d.selecting = true
        // Point at it, the item past the verbs, moves nothing, and still ends select mode.
        val menu = VerbMenu(a, emptyList(), mine = true)
        runVerbItem(d, menu, menu.verbs.size)
        assertFalse(d.selecting)
        assertTrue(d.selection.isEmpty())
    }

    @Test
    fun aDeskSelectionIsLeftAsItWas() = table { d ->
        val a = d.game!!.state.seats[0].hand.first()
        // A click picks A with select mode off, as a mouse does: its verb leaves the selection as it always has.
        d.selection = setOf(a)
        assertTrue(d.verb(a, DuelVerb.SUMMON, seat = 0), d.problem.orEmpty())
        assertEquals(setOf(a), d.selection)
    }

    @Test
    fun onlyAFingersHoldOffersClearTheChain() = table { d ->
        assertTrue(d.act(DuelAction.ChainAdd(0, note = "an effect"), 0), d.problem.orEmpty())
        val s = d.game!!.state
        val i = s.chain.size - 1
        assertFalse(LinkItem.CLEAR in linkItems(s, i), "Enter's menu is as it was")
        assertEquals(LinkItem.CLEAR, linkItems(s, i, clear = true).last())
    }
}
