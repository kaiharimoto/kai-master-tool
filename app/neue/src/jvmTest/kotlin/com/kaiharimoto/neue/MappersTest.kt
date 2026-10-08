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
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPaths
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.mapper.Mappers
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
 * Gameplay Mapper's holder end to end (Phase M step M1): the starter table and dealt hands mapped off the frame thread and
 * written beside the deck, read back as they were; a share only beside the library it was counted on; a file this build
 * cannot read refuses every run and is never written over; another device's library put together with this one's when
 * the files are read again; presets kept, Ai's as Ai's; a line played again opens on the Duel page; a deleted deck's
 * folder goes with it. The cards are fictional, in the goldfish's reserved range, with our own script.
 */
class MappersTest {
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

    private fun dir(): File = Files.createTempDirectory("mapper").toFile()

    /** A holder with [deck]'s files read. */
    private suspend fun opened(effects: File): Mappers {
        val m = Mappers(effects)
        withContext(Dispatchers.Main) { m.open(deck.id) }?.join()
        waitLoaded(m)
        return m
    }

    private suspend fun waitLoaded(m: Mappers) {
        var waited = 0
        while (!withContext(Dispatchers.Main) { m.loaded } && waited++ < 500) Thread.sleep(10)
        assertTrue(withContext(Dispatchers.Main) { m.loaded }, "the deck's files were read")
    }

    @Test
    fun theStarterTableAndDealtHandsAreWrittenAndReadBack() = runBlocking {
        val effects = dir()
        val m = opened(effects)
        assertNotNull(withContext(Dispatchers.Main) { m.startStarters(deck, kit) }).join()
        val lib = withContext(Dispatchers.Main) {
            assertFalse(m.busy)
            assertTrue(m.said.orEmpty().startsWith("Mapped"), m.said)
            m.side.library
        }
        assertTrue(lib.boards.isNotEmpty())
        assertTrue(File(effects, MapperPaths.library("pond", true)).isFile)
        assertTrue(File(effects, MapperPaths.starters("pond", true)).isFile)
        // A Caller alone reaches a Frog on the field: the table says so.
        val rows = withContext(Dispatchers.Main) { m.starterRows() }
        assertTrue(rows.any { it.cards == listOf(CALLER) && it.ends.isNotEmpty() })

        assertNotNull(withContext(Dispatchers.Main) { m.startHands(deck, kit, hands = 40, seed = 3) }).join()
        withContext(Dispatchers.Main) {
            val run = assertNotNull(m.counted, "the run was counted on this library")
            assertEquals(40, run.hands)
            // At least the empty board: every hand.
            assertEquals(40, run.atLeast(com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits()).hits)
            assertTrue(m.ranked().isNotEmpty())
        }
        // Read again by a new holder (the next launch): the same files.
        val again = opened(effects)
        withContext(Dispatchers.Main) {
            assertEquals(m.side.library, again.side.library)
            assertEquals(m.side.run, again.side.run)
            assertEquals(m.side.starters, again.side.starters)
        }
    }

    @Test
    fun aFileThisBuildCannotReadRefusesEveryRunAndIsNeverWrittenOver() = runBlocking {
        val effects = dir()
        val f = File(effects, MapperPaths.library("pond", true)).apply { parentFile.mkdirs(); writeText("{\"boards\": 7") }
        val m = opened(effects)
        withContext(Dispatchers.Main) {
            assertEquals(listOf("library.json"), m.side.unreadable)
            assertNull(m.startHands(deck, kit, hands = 10))
            assertTrue(m.said.orEmpty().contains("could not be read"), m.said)
            assertNull(m.startStarters(deck, kit))
            // Going second is another file, and maps.
            m.first = false
        }
        assertNotNull(withContext(Dispatchers.Main) { m.startHands(deck, kit, hands = 10, first = false) }).join()
        assertEquals("{\"boards\": 7", f.readText())
        assertTrue(File(effects, MapperPaths.library("pond", false)).isFile)
    }

    @Test
    fun anotherDevicesLibraryIsPutTogetherWithThisOnesWhenReadAgain() = runBlocking {
        val effects = dir()
        val m = opened(effects)
        assertNotNull(withContext(Dispatchers.Main) { m.startStarters(deck, kit) }).join()
        val mine = withContext(Dispatchers.Main) { m.side.library }
        // The other device's file: one board of ours, and one of its own (another field), as a sync would leave it.
        val theirs = mine.copy(boards = listOf(mine.boards.first(), mine.boards.first().copy(key = "zz-their-board")))
        val file = File(effects, MapperPaths.library("pond", true))
        file.writeText(theirs.encode())
        withContext(Dispatchers.Main) { m.reload() }
        var waited = 0
        while (withContext(Dispatchers.Main) { m.side.library.byKey["zz-their-board"] == null } && waited++ < 500) Thread.sleep(10)
        withContext(Dispatchers.Main) {
            val merged = m.side.library
            assertTrue("zz-their-board" in merged.byKey, "theirs kept")
            assertTrue(mine.boards.all { it.key in merged.byKey }, "ours kept")
        }
        // And written back: the file holds both.
        val written = assertNotNull(BoardLibrary.decode(file.readText()))
        assertTrue("zz-their-board" in written.byKey && mine.boards.all { it.key in written.byKey })
    }

    @Test
    fun presetsAreKeptAndAisAreMarkedAsAis() = runBlocking {
        val effects = dir()
        val m = opened(effects)
        withContext(Dispatchers.Main) {
            m.weigh("negates", 2.0)
            m.bound("interruptions", 1)
            m.savePreset("Negates")?.join()
            m.putAiPreset(BoardPreset(name = "Ash-proof", weights = mapOf("handInterruptions" to 1.0), why = "Keeps a hand trap"))?.join()
        }
        val back = opened(effects)
        withContext(Dispatchers.Main) {
            val saved = back.presets.presets
            assertEquals(listOf("Negates", "Ash-proof"), saved.map { it.name })
            assertEquals(BoardPreset.PERSON, saved[0].by)
            assertEquals(mapOf("negates" to 2.0, "interruptions" to 1.0), saved[0].weights)
            assertEquals(1.0, saved[0].filters.single { it.head == "interruptions" }.min)
            assertEquals(BoardPreset.AI, saved[1].by)
            assertEquals("Keeps a hand trap", saved[1].why)
            // The last chosen is the one on screen when the deck is opened again.
            assertEquals("Ash-proof", back.query.name)
            back.deletePreset(saved[1].id)?.join()
            assertEquals(listOf("Negates"), back.presets.presets.map { it.name })
        }
    }

    @Test
    fun aLinePlayedAgainOpensOnTheDuelPageAndADeletedDecksFolderGoes() = runBlocking {
        val effects = dir()
        val m = opened(effects)
        assertNotNull(withContext(Dispatchers.Main) { m.startStarters(deck, kit) }).join()
        val line = withContext(Dispatchers.Main) { m.side.library.boards.first { it.lines.isNotEmpty() && it.cards.monsters.isNotEmpty() }.lines.first() }
        var played: com.kaiharimoto.mastertool.core.duel.mapper.MapReplay.Replay? = null
        assertNotNull(withContext(Dispatchers.Main) { m.replay(line, deck.main, deck.extra, kit) { played = it } }).join()
        val r = assertNotNull(played)
        assertNull(r.problem)
        withContext(Dispatchers.Main) {
            val duels = Duels(Files.createTempDirectory("duel").toFile())
            duels.openGame("Gameplay Mapper", assertNotNull(r.game))
            assertFalse(assertNotNull(duels.replay).kept, "opened to watch, not kept")
        }
        withContext(Dispatchers.Main) { m.forgetDeck("pond") }
        var waited = 0
        while (File(effects, MapperPaths.deck("pond")).exists() && waited++ < 500) Thread.sleep(10)
        assertFalse(File(effects, MapperPaths.deck("pond")).exists())
        withContext(Dispatchers.Main) { assertNull(m.deckId) }
    }

    private companion object {
        const val FROG = 900_000_650
        const val CALLER = 900_000_651
        const val STONE = 900_000_652
    }
}
