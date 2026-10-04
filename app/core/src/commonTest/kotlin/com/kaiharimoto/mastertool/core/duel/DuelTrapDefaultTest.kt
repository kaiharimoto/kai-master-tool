package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.FILLER
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import kotlin.test.Test
import kotlin.test.assertEquals

/** kai, 1.0.95: "while a normal trap card is face up in the spell and trap zone have the default action be to set it face down". */
class DuelTrapDefaultTest {
    private fun catalog(sub: String) = DuelCatalog { code -> if (code == FILLER) DuelCardInfo("Trap Hole", CardKind.TRAP, sub = sub) else null }
    private val trap = uid(1, 0)
    private val s1 = Place.Zone(1, ZoneKind.SPELL, 0)
    private val up = ok(bare(), DuelAction.Move(trap, s1, CardPosition.FACE_UP_ATK), by = 1)

    @Test
    fun aNormalTrapFaceUpInItsZoneIsSetAgain() {
        val normal = catalog("Normal")
        assertEquals(DuelVerb.SET, DuelVerbs.default(up, 1, trap, normal))
        assertEquals(DuelVerb.SET, DuelVerbs.offered(up, 1, trap, normal).first())
        // And Set turns it face-down where it lies.
        val r = DuelVerbs.actions(up, 1, trap, DuelVerb.SET, normal)
        assertEquals(listOf(DuelAction.Position(trap, CardPosition.FACE_DOWN_ATK)), r.actions)
    }

    @Test
    fun otherwiseItIsActivatedAsBefore() {
        // A Continuous Trap stays up to be used; a Normal Trap on the chain is resolving, not done; a set one is activated.
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(up, 1, trap, catalog("Continuous")))
        val chained = ok(up, DuelAction.ChainAdd(1, trap), by = 1)
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(chained, 1, trap, catalog("Normal")))
        val set = ok(bare(), DuelAction.Move(trap, s1, CardPosition.FACE_DOWN_ATK), by = 1)
        assertEquals(DuelVerb.ACTIVATE, DuelVerbs.default(set, 1, trap, catalog("Normal")))
    }
}
