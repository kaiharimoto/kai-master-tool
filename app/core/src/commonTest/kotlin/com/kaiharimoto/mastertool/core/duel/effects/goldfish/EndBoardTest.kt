package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Targets (D.md §5.2): their file reads forgivingly, and interruptions are counted from the trusted scripts, one a group. */
class EndBoardTest {
    @Test
    fun aTargetRoundTripsAndANewerConditionIsKeptAsWritten() {
        val t = EndBoard(
            "t1", "Two Pond monsters + one negate", "d1",
            listOf(BoardCond.Controls(GoldfishFixtures.pondMonster, 2), BoardCond.AnyOf(listOf(BoardCond.Interruptions(1), BoardCond.SetCards(2)))),
            by = EndBoard.AI,
        )
        val text = GoldfishCodec.encodeTarget(t)
        assertTrue("\"t\":\"controls\"" in text && "\"t\":\"name-has\"" in text, text)
        assertEquals(t, GoldfishCodec.decodeTarget(text))
        val newer = GoldfishCodec.decodeTarget("""{"id":"t2","name":"x","deck":"d1","all":[{"t":"lp-at-least","n":8000},{"t":"set","n":1}],"by":"person","seen":1}""")!!
        assertIs<BoardCond.Unknown>(newer.all[0])
        assertTrue(BoardCheck.unread(newer))
        assertTrue("lp-at-least" in GoldfishCodec.encodeTarget(newer), "written back as it came")
    }

    @Test
    fun interruptionsAreCountedOneAGroupFromTheFieldTheSetCardsAndTheGy() {
        val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(GoldfishFixtures.scripts + FxRef.script(FxRef.SCOUT)))
        val header = DuelHeader(seats = listOf(SeatSetup(main = listOf(SAGE, SAGE, WALL, FxRef.SCOUT, STONE)), SeatSetup()), solo = true, handSize = 0)
        val laid = DuelRules.applyAll(
            DuelSetup.initial(header),
            listOf(
                DuelAction.Move(1, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK),
                DuelAction.Move(2, Place.Zone(0, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_ATK),
                DuelAction.Move(3, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_DEF),
                DuelAction.Move(4, Place.Pile(0, PileKind.GY)),
            ),
            0,
        ).first!!
        val t = FxTable(laid, FxState.at(laid), kit.book, kit.facts)
        // Two Sages share one once-per-turn by name: one. The set Wall: one. The Scout's Quick Effect from the GY: one.
        assertEquals(3, Interruptions.count(t, 0), Interruptions.groups(t, 0).toString())
        val target = EndBoard("t", "three answers", "d", listOf(BoardCond.Interruptions(3), BoardCond.SetCards(1), BoardCond.Controls(Filter.Name(SAGE), 2)))
        assertTrue(BoardCheck.meets(t, 0, target))
        assertTrue(!BoardCheck.meets(t, 0, target.copy(all = listOf(BoardCond.Interruptions(4)))))
        assertEquals("3 interruptions", BoardCheck.words(BoardCond.Interruptions(3)))
    }

    @Test
    fun aDeckFileKeepsItsTargetsAndAtMostTwentyResults() {
        var d = GoldfishDoc(deck = "d1")
        val t = EndBoard("t1", "x", "d1", listOf(BoardCond.SetCards(1)))
        d = GoldfishCodec.putTarget(d, t)
        d = GoldfishCodec.putTarget(d, t.copy(name = "y"))
        assertEquals(listOf("y"), d.targets.map { it.name })
        val r = GoldfishResult(deck = "fp", library = "lib", target = t, first = true, hands = 10, seed = 1, budget = 100, reached = 5, noLine = 5, undecided = 0)
        repeat(25) { d = GoldfishCodec.keep(d, r.copy(seed = it.toLong())) }
        assertEquals(GoldfishDoc.MOST_RESULTS, d.results.size)
        assertEquals(24L, d.results.last().seed)
        assertEquals(d, GoldfishCodec.decode(GoldfishCodec.encode(d)))
        assertEquals("goldfish/d1.json", GoldfishCodec.path("d1"))
        assertEquals("goldfish/a_b.json", GoldfishCodec.path("a/b"))
    }
}
