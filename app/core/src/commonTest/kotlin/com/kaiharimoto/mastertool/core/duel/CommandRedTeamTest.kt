package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.ai.voice.CommandClip
import com.kaiharimoto.mastertool.core.ai.voice.Hints
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.BEWD
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.bare
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.parse
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.play
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.problem
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelComplete
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fourth red-team pass on Command mode (1.0.87): what a line of several moves, a combo, the completion and the voice gave away or got wrong. */
class CommandRedTeamTest {
    private val s = battle()

    private val theirHand = listOf("Kuriboh", "Dark Magician", "Ash Blossom")

    @Test
    fun aLaterMoveNeverReachesACardTheLineBroughtIntoView() {
        // "g oh1" sends a card of their hidden hand to their GY; "b ogy1" would then name it, so the line says only
        // that the second move waits on the first — never what the card is, nor why it could not be read.
        val p = problem("g oh1; b ogy1", s)
        assertTrue(p.text.startsWith("Move 2"), p.text)
        assertTrue(p.text.contains("depends on the moves before it"), p.text)
        theirHand.forEach { assertFalse(p.text.contains(it), p.text) }
        // A card the seat could see at the start is fair to name later in the line.
        assertIs<Parsed.Many>(parse("g om1; b ogy1", s))
    }

    @Test
    fun aDrawEarlierInTheLineHidesWhatItDrew() {
        // Kai holds six: the seventh is whatever the draw brings, unknown as the line is typed.
        val p = problem("draw; s h7 m3", s)
        assertTrue(p.text.contains("depends on the moves before it"), p.text)
        // A later move that fails for any reason says no more than that.
        val q = problem("s h1 m3; s h2 m3", s)
        assertTrue(q.text.startsWith("Move 2"), q.text)
        assertTrue(q.text.contains("depends on the moves before it"), q.text)
        // The first move still says why.
        assertTrue(problem("g m3; s h1 m3", s).text.startsWith("Move 1"))
    }

    @Test
    fun aComboTakesEitherCopyOnTheField() {
        val two = play(
            bare(),
            DuelAction.Move(uid(0, 0), Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"),
            DuelAction.Move(uid(0, 1), Place.Zone(0, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_ATK, "special"),
        )
        assertEquals(BEWD, two.cards.getValue(uid(0, 0)).code)
        // A person is asked which; a combo, written by name, takes one.
        assertTrue(problem("send blue-eyes white dragon to gy", two).text.contains("more than once"))
        val run = ComboRunner.plan(two, 0, listOf("send blue-eyes white dragon to gy"), catalog)
        assertNull(run.problem, run.problem)
        assertEquals(1, run.steps.size)
    }

    @Test
    fun aComboSplitsALineOfSeveralMovesIntoSteps() {
        val run = ComboRunner.plan(s, 0, listOf("s h1 m3; s h2 m4"), catalog)
        assertNull(run.problem, run.problem)
        assertEquals(listOf("s h1 m3", "s h2 m4"), run.steps.map { it.first })
    }

    @Test
    fun theCompletionNeverListsCardsInAnOrderTheSeatCannotSee() {
        // Their hand by the coordinates it is shown with, in order.
        val theirs = DuelComplete.suggest("o", 1, s, 0, catalog, most = 60).map { it.insert }.filter { it.startsWith("oh") }
        assertEquals(4, theirs.size, theirs.toString())
        assertEquals(theirs.sortedBy { it.drop(2).toInt() }, theirs)
        // The Deck by name, never top first (Blue-Eyes, Pot, Mirror Force, Ash Blossom … is the top of Kai's Deck).
        val table = bare()
        val deck = DuelComplete.reach(table, 0, catalog, 0L).filter { table.placeOf(it).let { p -> p is Place.Pile && p.kind == PileKind.DECK } }
        val names = deck.map { catalog.nameOf(table.cards.getValue(it)) }
        assertEquals(40, names.size)
        assertEquals(names.sorted(), names)
    }

    @Test
    fun theTranscribersHintPutsTheLinesWordsFirst() {
        val hints = DuelSpeech.hints(s, 0, catalog, most = 120)
        assertTrue(hints.startsWith("yes, undo, summon"), hints)
        assertTrue(hints.split(", ").contains("yes"), hints)
    }

    @Test
    fun aSpokenEndIsKept() {
        // Whisper writes a lone "end" as "The end."
        assertFalse(CommandClip.isNothing("The end."))
        // The Line's own words, said quickly, are never taken for the priming echoed.
        val hints = Hints.prompt(DuelSpeech.WORDS + "Ash Blossom & Joyous Spring")
        assertFalse(CommandClip.echoes("End turn.", hints, voiced = 0.3, words = DuelSpeech.WORDS))
        assertFalse(CommandClip.echoes("No response.", hints, voiced = 0.3, words = DuelSpeech.WORDS))
        assertTrue(CommandClip.echoes("Ash Blossom & Joyous Spring.", hints, voiced = 0.3, words = DuelSpeech.WORDS))
    }

    @Test
    fun enemyIsNotTheirs() {
        // "Enemy Controller" is a card: "enemy" names no seat.
        assertFalse(DuelSpeech.normalize("activate enemy controller").contains("their"))
    }
}
