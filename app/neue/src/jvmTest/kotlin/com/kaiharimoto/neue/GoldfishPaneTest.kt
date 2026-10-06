package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.CardScript
import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxEntry
import com.kaiharimoto.mastertool.core.duel.effects.FxRead
import com.kaiharimoto.mastertool.core.duel.effects.FxReport
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.Spot
import com.kaiharimoto.mastertool.core.duel.effects.Step
import com.kaiharimoto.mastertool.core.duel.effects.Where
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCond
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishBrowse
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSetup
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandEnd
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandPick
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.effects.Effects
import com.kaiharimoto.neue.effects.GoldfishRuns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * The goldfish in the Effects app end to end (Phase D step 4, agent (c)): a run started off the frame thread comes back as
 * the result on screen; a hand of it opens on the Duel page as a replay that stands at the deal with its line ahead and is
 * written nowhere until kept; Stop lets a run go and says so; a target that needs a card played as inert starts nothing
 * and names it; a kept result opens its hands only on the deck they were dealt from. The cards are fictional, in the
 * goldfish's reserved range (900000600–900000699), with our own scripts.
 */
class GoldfishPaneTest {
    private fun monster(id: Int, name: String, frame: String, type: String) =
        Card(CardId(id), name, type, frame, race = "Aqua", attribute = CardAttribute.WATER, atk = 1000, def = 1000, level = 3)

    private val frog = monster(FROG, "Pond Frog", "normal", "Normal Monster")
    private val caller = Card(CardId(CALLER), "Pond Caller", "Spell Card", "spell", race = "Normal")
    private val stone = monster(STONE, "Dry Stone", "effect", "Effect Monster")
    private val index = CardIndex.build(listOf(frog, caller, stone))

    /** Pond Caller: Special Summon 1 "Pond" monster from your Deck. */
    private val callerScript = CardScript(
        CALLER, name = "Pond Caller",
        effects = listOf(
            Effect(
                "e1", "Call", Kind.ACTIVATION, from = setOf(Where.HAND, Where.SPELL_ZONE),
                does = listOf(Step(Op.SpecialSummon(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), where = Filter.All(listOf(Filter.NameHas("Pond"), Filter.Kind(CardType.MONSTER))))))),
            ),
        ),
    )

    private val trust = FxTrust(mapOf(CALLER to FxEntry(CALLER, compiled = FxRead.Script(callerScript), report = FxReport(CALLER, emptyList()))))
    private val kit = GoldfishKit(trust) { index.byId(CardId(it)) }
    private val main = List(3) { FROG } + List(3) { CALLER } + List(34) { STONE }
    private val deck = GoldfishDeck(main, id = "pond", fingerprint = "fp-pond", name = "Pond")
    private val two = EndBoard("t-two", "Two Pond monsters", "pond", listOf(BoardCond.Controls(Filter.All(listOf(Filter.NameHas("Pond"), Filter.Kind(CardType.MONSTER))), 2)))

    private fun effects(): Effects {
        val data = Files.createTempDirectory("goldfish").toFile()
        return Effects.under(data).also { it.pool = { index } }
    }

    @Test
    fun aRunComesBackAsTheResultAndAHandOpensAsAnUnsavedReplay() = runBlocking {
        val fx = effects()
        val runs = fx.goldfishRuns
        val setup = GoldfishSetup(deck, two, first = true, hands = 400, seed = 7)
        val job = assertNotNull(withContext(Dispatchers.Main) { runs.start(setup, kit, workers = 2) })
        job.join()
        val shown = withContext(Dispatchers.Main) { assertNotNull(runs.shown) }
        assertFalse(withContext(Dispatchers.Main) { runs.busy })
        val r = shown.result
        assertEquals(400, r.hands)
        assertEquals(400, withContext(Dispatchers.Main) { runs.progress?.done }, "the last progress is always posted")
        assertTrue(r.reached in 1 until 400, "${r.reached}")
        assertFalse(shown.kept)
        // Every number opens its hands; a reached hand opens as a replay of its line.
        val reached = GoldfishBrowse.hands(r, HandPick.End(HandEnd.REACHED))
        assertEquals(r.reached, reached.size)
        val k = reached.first().index
        val replay = assertNotNull(runs.replay(k, kit))
        assertEquals(HandEnd.REACHED, replay.end)
        assertNull(replay.problem)
        val duelDir = Files.createTempDirectory("duel").toFile()
        withContext(Dispatchers.Main) {
            val duels = Duels(duelDir)
            duels.openGame(GoldfishBrowse.replayName(r, k), replay.game)
            val open = assertNotNull(duels.replay)
            assertFalse(open.kept, "a hand opened to watch is not in the library")
            assertTrue(open.record.name.contains("hand ${k + 1} of 400"), open.record.name)
            // It stands at the deal, the hand in view, with its line a step at a time ahead.
            val dealt = open.record.entries.indexOfFirst { it.seat != null }
            assertEquals(dealt, open.at)
            assertTrue(open.record.entries.size > open.at, "the line is ahead")
            assertEquals(5, duels.shown?.state?.seats?.get(0)?.hand?.size)
            duels.step(ReplayUnit.GROUP, 1)
            assertTrue(assertNotNull(duels.replay).at > dealt)
            // Edits stay on screen: nothing is written until it is kept.
            duels.note("watched")
            duels.keepOpenReplay()
            assertTrue(assertNotNull(duels.replay).kept)
        }
        var waited = 0
        while (File(duelDir, "replays").listFiles().orEmpty().none { it.name.endsWith(".json") } && waited++ < 200) Thread.sleep(20)
        val written = File(duelDir, "replays").listFiles().orEmpty().filter { it.name.endsWith(".json") }
        assertEquals(1, written.size, "kept: written once")
        assertTrue(written.single().readText().contains("watched"))
        // Kept with the deck, and opened again: its hands open while the deck is the one they were dealt from.
        assertTrue(withContext(Dispatchers.Main) { runs.keep() })
        assertEquals(listOf(r.seed), fx.goldfish("pond").results.map { it.seed })
        withContext(Dispatchers.Main) {
            runs.show(fx.goldfish("pond").results.single(), deck)
            assertNotNull(runs.shown?.setup)
            runs.show(fx.goldfish("pond").results.single(), deck.copy(fingerprint = "fp-changed"))
            assertNull(runs.shown?.setup)
            assertTrue(runs.shown?.why.orEmpty().contains("deck changed"))
        }
    }

    @Test
    fun stopLetsTheRunGoAndSaysSo() = runBlocking {
        val runs = effects().goldfishRuns
        // Every hand searched on its own, against a board no hand reaches: a long run.
        val five = two.copy(id = "t-five", all = listOf(BoardCond.Controls(Filter.NameHas("Pond"), 5)))
        val setup = GoldfishSetup(deck, five, hands = 20_000, seed = 3, reduce = false)
        val job = assertNotNull(withContext(Dispatchers.Main) { runs.start(setup, kit, workers = 2) })
        var waited = 0
        while (withContext(Dispatchers.Main) { (runs.progress?.done ?: 0) == 0 } && waited++ < 2_000) Thread.sleep(5)
        withContext(Dispatchers.Main) { runs.stop() }
        job.join()
        val again = withContext(Dispatchers.Main) {
            assertFalse(runs.busy)
            assertNull(runs.shown, "a stopped run shows nothing")
            assertTrue(runs.said.orEmpty().startsWith("Stopped after"), runs.said)
            // A new run may start at once.
            assertNotNull(runs.start(setup.copy(hands = 10, reduce = true), kit, workers = 1))
        }
        again.join()
        withContext(Dispatchers.Main) { assertEquals(10, runs.shown?.result?.hands) }
    }

    @Test
    fun aTargetThatNeedsAnInertCardStartsNothingAndNamesIt() = runBlocking {
        val runs = effects().goldfishRuns
        val stoneOut = two.copy(id = "t-stone", name = "A Stone", all = listOf(BoardCond.Controls(Filter.Name(STONE), 1)))
        withContext(Dispatchers.Main) {
            assertNull(runs.start(GoldfishSetup(deck, stoneOut, hands = 50), kit, workers = 1))
            assertFalse(runs.busy)
            val nc = assertNotNull(runs.refusal)
            assertEquals(listOf(STONE), nc.cards, "named, for Write these")
            assertTrue(nc.why.contains("Dry Stone"), nc.why)
            // A computable run clears it.
            assertNotNull(runs.start(GoldfishSetup(deck, two, hands = 20), kit, workers = 1))
            assertNull(runs.refusal)
            runs.stop()
        }
    }

    @Test
    fun theSeedAndTheHandsAreReadAsTyped() = runBlocking {
        withContext(Dispatchers.Main) {
            val runs = GoldfishRuns(effects())
            runs.seedText = "42"
            assertEquals(42L, runs.seed)
            runs.seedText = ""
            assertEquals(1L, runs.seed)
            runs.reroll(123456789L)
            assertTrue(runs.seed > 0)
            assertEquals(500, runs.hands(500))
            runs.handsText = "5,000"
            assertEquals(5_000, runs.hands(500))
            runs.handsText = "99999"
            assertEquals(500, runs.hands(500), "beyond the most: the default")
        }
    }

    private companion object {
        const val FROG = 900_000_640
        const val CALLER = 900_000_641
        const val STONE = 900_000_642
    }
}
