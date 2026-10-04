package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The red team on Phase C (stage 3): the brief's size and the menu's speed on a very full table — both fields full,
 * materials, a chain, long GYs and banished piles, full Extra Decks, big hands, a turn of forty moves.
 */
class DuelScaleTest {
    private val names = listOf(
        "Snake-Eye Ash", "Snake-Eye Oak", "Diabellstar the Black Witch", "Fiendsmith Engraver", "Ash Blossom & Joyous Spring",
        "Infinite Impermanence", "Called by the Grave", "Original Sinful Spoils - Snake-Eye", "Promethean Princess, Bestower of Flames",
        "Bystial Druiswurm", "Effect Veiler", "Mulcharmy Fuwalos", "Nibiru, the Primal Being", "Triple Tactics Talent",
        "Sinful Spoils of Subversion - Snake-Eye", "Divine Arsenal AA-ZEUS - Sky Thunder", "Accesscode Talker", "S:P Little Knight",
        "Fiendsmith's Requiem", "Knightmare Unicorn",
    )

    private val catalog = DuelCatalog { code ->
        val i = (code - 1) % names.size
        when {
            code in 1..14 -> DuelCardInfo(names[i], CardKind.MONSTER, atk = 1000 + i * 100, def = 1500, level = 1 + i % 8, attribute = "FIRE", race = "Pyro", typeLine = "Effect Monster")
            code in 15..17 -> DuelCardInfo(names[i], CardKind.SPELL, sub = "Quick-Play")
            code in 18..20 -> DuelCardInfo(names[i], CardKind.TRAP, sub = "Normal")
            code in 21..30 -> DuelCardInfo(names[(code - 21) % 5 + 15] + " Mk$code", CardKind.EXTRA_MONSTER, atk = 2800, def = 2000, level = 6, xyz = code % 2 == 0, attribute = "DARK", race = "Fiend", typeLine = "Xyz Effect Monster")
            else -> null
        }
    }

    private fun main(seat: Int) = (0 until 60).map { 1 + (it + seat * 3) % 20 }
    private fun extra() = (0 until 15).map { 21 + it % 10 }

    /** Both seats as full as a long game gets. */
    private fun full(): DuelGame {
        val header = DuelHeader("scale", 7, listOf(SeatSetup("Kai", main(0), extra()), SeatSetup("Ai", main(1), extra())))
        var g = DuelGame.start(header)
        fun act(seat: Int, vararg a: DuelAction) { val r = g.act(a.toList(), seat); check(r.ok) { "${r.problem} for ${a.toList()}" }; g = r.game }
        g.act(DuelAction.Phase(DuelPhase.MAIN1), 0).game.also { g = it }
        for (seat in 0..1) {
            val deck = { g.state.seats[seat].deck }
            act(seat, DuelAction.Draw(seat, 3))
            repeat(5) { z -> act(seat, DuelAction.Move(deck().first(), Place.Zone(seat, ZoneKind.MONSTER, z), CardPosition.FACE_UP_ATK, "special")) }
            repeat(5) { z -> act(seat, DuelAction.Move(deck().first(), Place.Zone(seat, ZoneKind.SPELL, z), if (z % 2 == 0) CardPosition.FACE_DOWN_ATK else CardPosition.FACE_UP_ATK, "set")) }
            act(seat, DuelAction.Move(deck().first(), Place.Zone(seat, ZoneKind.FIELD, 0), CardPosition.FACE_UP_ATK, "place"))
            repeat(25) { act(seat, DuelAction.Move(deck().first(), Place.Pile(seat, PileKind.GY), how = "send")) }
            repeat(10) { act(seat, DuelAction.Move(deck().first(), Place.Pile(seat, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "banish")) }
            // Two materials under each monster in m1, m2.
            repeat(2) { z -> repeat(2) { act(seat, DuelAction.Move(g.state.seats[seat].gy.first(), Place.Under(g.state.seats[seat].monsters[z]!!), how = "attach")) } }
            act(seat, DuelAction.Move(g.state.seats[seat].extra.first(), Place.Zone(seat, ZoneKind.EMZ, seat), CardPosition.FACE_UP_ATK, "special"))
        }
        repeat(4) { k -> act(k % 2, DuelAction.ChainAdd(k % 2, g.state.seats[k % 2].spells[1])) }
        repeat(20) { k -> act(k % 2, DuelAction.Chat(k % 2, "a word $k")) }
        return g
    }

    @Test
    fun theBriefOnAFullTableAndTheMenusSpeed() {
        val g = full()
        val s = g.state
        val history = DuelBrief.turnLines(g, 1, catalog)
        val brief = DuelBrief.describe(s, 1, catalog, g.header.seed, 1, history = history)
        println("brief on a full table: ${brief.length} chars, ${brief.lines().size} lines; history ${history.size} lines")
        val clock = TimeSource.Monotonic
        DuelMoves.menu(s, 1, catalog, g.header.seed) // warm
        val t0 = clock.markNow()
        val menu = DuelMoves.menu(s, 1, catalog, g.header.seed)
        val ms = t0.elapsedNow().inWholeMilliseconds
        val words = DuelMoves.words(menu)
        println("menu on a full table: ${menu.sumOf { it.moves.size }} moves in ${menu.size} groups, $ms ms; ${words.length} chars shown")
        assertTrue(brief.length < BRIEF_MOST, "the brief stays within its budget: ${brief.length}")
        assertTrue(ms < MENU_MS, "the menu is read in a moment: $ms ms")
    }

    companion object {
        const val BRIEF_MOST = 10_000
        const val MENU_MS = 3_000L
    }
}
