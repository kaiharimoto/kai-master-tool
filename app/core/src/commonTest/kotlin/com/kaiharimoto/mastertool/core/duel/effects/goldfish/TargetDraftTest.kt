package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.CardFrame
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The target editor's model (Phase D step 4, agent (c)): a person's end board made without the vocabulary — cards by art,
 * kinds, counts, four places, set cards, interruptions, "any one of these" — written as an [EndBoard] and read back the
 * same, with what it cannot show kept as written.
 */
class TargetDraftTest {
    private val names = mapOf(FROG to "Pond Frog", CALLER to "Pond Caller", SAGE to "Pond Sage", WALL to "Pond Wall")
    private val name = { c: Int -> names[c] ?: "#$c" }

    @Test
    fun aDraftMadeByPickingIsTheBoardItSays() {
        val d = TargetDraft()
            .add(BoardPlace.FIELD, Needed.Card(SAGE))
            .add(BoardPlace.FIELD, Needed.Kind(NeedKind.EXTRA_MONSTER))
            .add(BoardPlace.FIELD, Needed.Kind(NeedKind.EXTRA_MONSTER))
            .add(BoardPlace.HAND, Needed.Card(WALL))
            .copy(set = 1, interruptions = 2)
        val b = d.board("t1", "deck", EndBoard.PERSON, 5L, name)
        assertEquals(
            listOf(
                BoardCond.Controls(Filter.Name(SAGE), 1),
                BoardCond.Controls(Filter.AnyOf(NeedKind.EXTRA_FRAMES.map { Filter.Frame(it) }), 2),
                BoardCond.Holds(Filter.Name(WALL), 1),
                BoardCond.SetCards(1),
                BoardCond.Interruptions(2),
            ),
            b.all,
        )
        assertEquals(EndBoard.PERSON, b.by)
        // Named from what it holds when the person names nothing.
        assertEquals("Pond Sage + 2 Extra Deck monsters + 2 interruptions …", b.name)
        assertEquals(
            "1 face-up Pond Sage on your field and 2 face-up Extra Deck monster(s) on your field and 1 Pond Wall in your hand and 1 set Spell/Trap and 2 interruptions",
            BoardCheck.words(b, name),
        )
        assertNull(d.problem())
        assertNotNull(TargetDraft(name = "Nothing").problem(), "a target needs a condition")
    }

    @Test
    fun aBoardReadAsADraftIsWrittenBackTheSame() {
        val any = TargetDraft(name = "Either")
            .add(BoardPlace.FIELD, Needed.Card(SAGE))
            .add(BoardPlace.FIELD, Needed.Kind(NeedKind.LINK))
            .anyOne(BoardPlace.FIELD, true)
            .add(BoardPlace.GY, Needed.Word("Pond"))
            .count(BoardPlace.GY, Needed.Word("Pond"), 3)
            .add(BoardPlace.BANISHED, Needed.Kind(NeedKind.ANY))
            .copy(interruptions = 1)
        val b = any.board("t2", "deck", EndBoard.AI, 0L, name)
        assertEquals(BoardCond.AnyOf(listOf(BoardCond.Controls(Filter.Name(SAGE), 1), BoardCond.Controls(Filter.Frame(CardFrame.LINK), 1))), b.all.first())
        val back = TargetDraft.of(b)
        assertEquals(any, back)
        assertEquals(b.all, back.board("t2", "deck", EndBoard.AI, 0L, name).all)
        // Through the file too: the vocabulary's own discriminator.
        val read = assertNotNull(GoldfishCodec.decodeTarget(GoldfishCodec.encodeTarget(b)))
        assertEquals(any, TargetDraft.of(read))
    }

    @Test
    fun whatTheEditorCannotShowIsKeptAsWritten() {
        val newer = BoardCond.Unknown(JsonObject(mapOf("t" to JsonPrimitive("future"))))
        val mixed = BoardCond.AnyOf(listOf(BoardCond.Controls(Filter.Name(SAGE)), BoardCond.Holds(Filter.Name(WALL))))
        val twice = BoardCond.Controls(Filter.Name(FROG), 2)
        val odd = BoardCond.Controls(Filter.Level(com.kaiharimoto.mastertool.core.duel.effects.Span(4, 4)), 1)
        val b = EndBoard(
            "t3", "Ai's", "deck",
            listOf(BoardCond.Controls(Filter.Name(FROG), 1), twice, mixed, newer, odd, BoardCond.SetCards(2), BoardCond.SetCards(3)),
            EndBoard.AI,
        )
        val d = TargetDraft.of(b)
        assertEquals(listOf(Need(Needed.Card(FROG), 1)), d.field.needs)
        assertEquals(2, d.set)
        assertEquals(listOf(twice, mixed, newer, odd, BoardCond.SetCards(3)), d.kept)
        // Written back, nothing is lost.
        val again = d.board("t3", "deck", EndBoard.AI, 0L)
        assertEquals(b.all.toSet(), again.all.toSet())
        assertEquals(b.all.size, again.all.size)
    }

    @Test
    fun countsStayInBoundsAndAnyOneNeedsTwo() {
        var d = TargetDraft().add(BoardPlace.FIELD, Needed.Card(SAGE))
        repeat(9) { d = d.add(BoardPlace.FIELD, Needed.Card(SAGE)) }
        assertEquals(TargetDraft.MOST, d.field.needs.single().n)
        d = d.anyOne(BoardPlace.FIELD, true)
        assertTrue(!d.field.anyOne, "any one of a single need is the need itself")
        d = d.add(BoardPlace.FIELD, Needed.Card(FROG)).anyOne(BoardPlace.FIELD, true)
        assertTrue(d.field.anyOne)
        assertEquals(1, d.conditions)
        d = d.remove(BoardPlace.FIELD, Needed.Card(FROG))
        assertTrue(!d.field.anyOne, "back to one need: every one")
        assertEquals(0, TargetDraft().conditions)
    }
}
