package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mapper's runs on several threads give what one thread gives: how many ran never changes a library or a count. */
class MapperParallelTest {
    private val kit = GoldfishFixtures.kit()
    private val main: List<Int> = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(21) { STONE } + listOf(NET)
    private val toy: GoldfishDeck = GoldfishDeck(main, id = "toy", fingerprint = "fp")

    @Test
    fun aRunOnFourWorkersIsTheRunOnOne() = runBlocking {
        val setup = MapperSetup(toy, hands = 80, seed = 9)
        val (one, lib1) = Mapper.runHere(setup, kit, BoardLibrary(deck = "fp"))
        val (four, lib4) = Mapper.run(setup, kit, BoardLibrary(deck = "fp"), workers = 4)
        assertEquals(lib1, lib4)
        assertEquals(one.copy(ms = 0), four.copy(ms = 0))
    }

    @Test
    fun theStarterTableOnFourWorkersIsTheTableOnOne() = runBlocking {
        val one = StarterTable.run(main, emptyList(), kit, BoardLibrary())
        val four = StarterTable.runOn(main, emptyList(), kit, BoardLibrary(), workers = 4)
        assertEquals(one.library, four.library)
        assertEquals(one.rows, four.rows)
    }

    @Test
    fun stopKeepsWhatWasMappedAndSaysSo() = runBlocking {
        val maps = AtomicInteger()
        val (r, lib) = Mapper.run(MapperSetup(toy, hands = 200, seed = 2), kit, BoardLibrary(deck = "fp"), workers = 2, stop = { maps.get() >= 3 }, progress = { maps.set(it.done) })
        assertTrue(r.stopped)
        assertTrue(r.hands in 1 until 200, "${r.hands}")
        assertEquals(r.hands, r.dealt.count { it >= 0 })
        assertTrue(r.added.all { it in lib.byKey })
    }
}
