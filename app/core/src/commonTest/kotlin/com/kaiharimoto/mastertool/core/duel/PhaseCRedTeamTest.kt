package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The red team on Phase C (stage 3, `docs/phases/C.md` §7): can Ai still learn a hidden card through the stage-2 surfaces —
 * the menu of moves, a card asked for by name or by place, the brief's facts given once?
 */
class PhaseCRedTeamTest {
    private val nibiru = 1
    private val secret = 2
    private val decked = 3
    private val filler = 4
    private val magi = 5

    private val catalog = DuelCatalog { code ->
        when (code) {
            nibiru -> DuelCardInfo("Nibiru, the Primal Being", CardKind.MONSTER, atk = 3000, def = 600, level = 11, attribute = "LIGHT", race = "Rock", typeLine = "Effect Monster")
            secret -> DuelCardInfo("Secret Trap", CardKind.TRAP, sub = "Normal")
            decked -> DuelCardInfo("Deck Secret", CardKind.SPELL, sub = "Normal")
            filler -> DuelCardInfo("Filler", CardKind.MONSTER, atk = 100, def = 100, level = 1, attribute = "EARTH", race = "Rock", typeLine = "Normal Monster")
            magi -> DuelCardInfo("Dark Magician", CardKind.MONSTER, atk = 2500, def = 2100, level = 7, attribute = "DARK", race = "Spellcaster", typeLine = "Normal Monster")
            else -> null
        }
    }

    /** Kai (seat 0): Nibiru in hand, Secret Trap set in s1, Deck Secret in the Deck. Ai (seat 1): three Dark Magicians out. */
    private fun table(): Pair<DuelGame, Long> {
        val header = DuelHeader(
            "rt", 31,
            listOf(
                SeatSetup("Kai", listOf(nibiru, secret, filler, filler, filler) + List(5) { decked }),
                SeatSetup("Ai", List(10) { magi }),
            ),
        )
        var g = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        g = g.act(listOf(DuelAction.Draw(0, 5), DuelAction.Draw(1, 5)), null).game
        val set = g.state.seats[0].hand[1]
        g = g.act(DuelAction.Move(set, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"), 0).game
        val ai = g.state.seats[1].hand
        g = g.act(listOf(DuelAction.EndTurn), 0).game
        repeat(3) { k -> g = g.act(DuelAction.Move(ai[k], Place.Zone(1, ZoneKind.MONSTER, k), CardPosition.FACE_UP_ATK, "special"), 1).game }
        g = g.act(DuelAction.Phase(DuelPhase.MAIN1), 1).game
        return g to header.seed
    }

    private val hidden = listOf("Nibiru", "Secret Trap", "Deck Secret")

    @Test
    fun theMenuNeverNamesAHiddenCardWhateverIsAskedOfIt() {
        val (g, seed) = table()
        val s = g.state
        val whole = DuelMoves.words(DuelMoves.menu(s, 1, catalog, seed), 600)
        hidden.forEach { assertFalse(it in whole, "$it in the menu: $whole") }
        assertTrue("os1 a face-down card" in whole, whole)
        // One card by its place: their set card and their hand's first card.
        listOf("os1", "oh1", "oh2").forEach { at ->
            val l = DuelCommand.lookup(at, s, 1, catalog, DuelCommand.Want.TARGET, secret = seed)
            val only = (l as? DuelCommand.Lookup.One)?.uid
            val words = DuelMoves.words(DuelMoves.menu(s, 1, catalog, seed, only), 600)
            hidden.forEach { assertFalse(it in words, "$it through card=$at: $words") }
        }
        // A name is never a probe: their hidden cards are not found by it, so "found" and "not found" say nothing.
        listOf("Nibiru", "their Nibiru", "Secret Trap", "Deck Secret").forEach { q ->
            val l = DuelCommand.lookup(q, s, 1, catalog, DuelCommand.Want.TARGET, secret = seed)
            assertTrue(l is DuelCommand.Lookup.None, "$q found ${(l as? DuelCommand.Lookup.One)?.uid}")
        }
    }

    @Test
    fun theBriefGivesFactsOnceAndNeverForAHiddenCard() {
        val (g, seed) = table()
        val brief = DuelBrief.describe(g.state, 1, catalog, seed, 1)
        hidden.forEach { assertFalse(it in brief, "$it in the brief") }
        assertFalse("Level 11" in brief, "a hidden card's facts would name it")
        // Three Dark Magicians: the facts at the first, then the name alone.
        assertEquals(1, Regex("Level 7 · DARK · Spellcaster").findAll(brief).count(), brief)
        assertEquals(5, Regex("Dark Magician").findAll(brief).count(), brief)
        assertTrue("first mention" in brief)
    }
}
