package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typing alone wins a duel (1.0.87, kai: "I can win with just typing too and not a mouse"): a whole duel — draw,
 * summon, set, activate and chain, resolve, phases, attacks, damage, the other seat's turns — played from typed lines
 * through the Line's preview and commit, to a win. The typed take of the demo.
 */
class TypedDuelTest {
    private var g = DuelGame.of(DuelRecord(CommandFixtures.header()))
    private val said = mutableListOf<String>()

    /** One line as the Line runs it: preview, then commit — each `;` step its own group, in order. */
    private fun type(line: String, seat: Int) {
        val preview = DuelCommand.preview(line, g.state, seat, catalog)
        assertTrue(preview.ok, "“$line” as seat $seat: ${preview.problem}")
        said += preview.words
        val parts = when (val p = DuelCommand.parse(line, g.state, seat, catalog)) {
            is Parsed.Actions -> listOf(p.actions)
            is Parsed.Many -> p.parts.map { it.actions }
            else -> error("“$line” is not a move: $p")
        }
        // What the preview showed is what Enter commits.
        assertEquals(parts, preview.parts)
        parts.forEach { actions ->
            val r = g.act(actions, seat)
            assertTrue(r.ok, "“$line”: ${r.problem}")
            g = r.game
        }
    }

    private val s get() = g.state
    private val kai = 0
    private val rival = 1

    @Test
    fun aWholeDuelIsWonByTypingAlone() {
        // Turn 1, Kai.
        type("draw 5", kai)
        type("d 5", rival)
        type("m1", kai)
        type("s h1 m3", kai)
        assertEquals("Summon Blue-Eyes White Dragon from h1 to M3", said.last())
        type("e h3 s2", kai)
        type("a h2", kai)
        assertEquals(1, s.chain.size)
        type("chain ash", rival)
        assertEquals(listOf(kai, rival), s.chain.map { it.seat })
        type("resolve", kai)
        type("res", kai)
        assertTrue(s.chain.isEmpty())
        assertTrue(uid(0, 2) in s.seats[kai].gy, "Pot of Prosperity went to the GY with its chain")
        type("ep; end", kai)
        assertEquals(rival, s.active)

        // Turn 2, Rival sets a monster.
        type("draw", rival)
        type("m1", rival)
        type("e h1 m1", rival)
        type("end", rival)
        assertEquals(kai, s.active)

        // Turn 3, Kai attacks.
        type("draw", kai)
        type("m1; s h1 m2", kai)
        type("bp", kai)
        assertEquals(DuelPhase.BATTLE, s.phase)
        type("a m3 om1", kai)
        type("g om1", kai)
        type("a m2 direct; lp o -3000", kai)
        assertEquals(5000, s.seats[rival].lp)
        type("end", kai)

        // Turn 4, Rival.
        type("draw", rival)
        type("m1", rival)
        type("summon dark magician to m1", rival)
        type("set mirror force", rival)
        type("end", rival)

        // Turn 5, Kai wins.
        type("draw", kai)
        type("m1", kai)
        type("s h2 m1", kai)
        type("bp", kai)
        type("a m3 om1", kai)
        type("lp o -500; g om1", kai)
        type("a m2 direct; lp o -3000", kai)
        type("m1 attacks directly", kai)
        type("lp o -3000", kai)
        assertEquals(0, s.seats[rival].lp)
        assertEquals(8000, s.seats[kai].lp)
    }
}
