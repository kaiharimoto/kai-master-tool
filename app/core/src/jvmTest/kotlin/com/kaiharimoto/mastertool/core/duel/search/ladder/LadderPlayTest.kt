package com.kaiharimoto.mastertool.core.duel.search.ladder

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The ladder's games played (Phase E, E.4): on the bench, to their end, the same game on every run. */
class LadderPlayTest {
    @Test
    fun aLadderGameIsPlayedToItsEndAndTheSameSeedGivesTheSameGame() = runTest(timeout = 10.minutes) {
        val seed = LadderSeeds.of(2)!!
        val m = LadderMatch(seed, listOf(LadderPlayers.FAST, LadderPlayers.GREEDY))
        val t0 = System.currentTimeMillis()
        val one = LadderRun.play(m)
        val ms = System.currentTimeMillis() - t0
        val two = LadderRun.play(m)
        println("LadderPlayTest: seed ${seed.n} (${seed.decks}) ${m.players}: winner ${one.winner} by ${one.how} in turn ${one.turns}, $ms ms")
        assertEquals(one, two)
        assertEquals(Bench.print, one.bench)
        assertTrue(one.turns >= 2, "$one")
        val g = LadderRun.play(LadderMatch(seed, listOf(LadderPlayers.GREEDY, LadderPlayers.GREEDY)))
        assertTrue(g.how.isNotBlank(), "$g")
    }

    /**
     * The whole ladder, measured for the release notes: only with `NEUE_LADDER=1` (it plays every owed game). Keeps its book
     * in `build/ladder.json`, so a stopped run goes on where it stood.
     */
    @Test
    fun theLadderMeasured() = runTest(timeout = 600.minutes) {
        if (System.getenv("NEUE_LADDER") != "1") return@runTest
        val file = File("build/ladder.json")
        var book = if (file.exists()) LadderCodec.decode(file.readText()) else LadderBook()
        val owed = LadderRun.owed(book)
        println("LadderPlayTest: ${owed.size} games owed")
        val t0 = System.currentTimeMillis()
        owed.forEachIndexed { i, m ->
            val s = System.currentTimeMillis()
            val g = LadderRun.play(m)
            book = book.with(g)
            file.writeText(LadderCodec.encode(book))
            println("LadderPlayTest: ${i + 1}/${owed.size} seed ${m.seed.n} ${m.players}: ${g.winner} ${g.how} t${g.turns} ${System.currentTimeMillis() - s} ms")
        }
        val r = LadderReading.of(book)
        println("LadderPlayTest: ${r.games} games in ${(System.currentTimeMillis() - t0) / 1000} s; bench ${Bench.print}, coverage ${r.coverage}")
        r.ratings.forEach { println("LadderPlayTest: ${it.player} ${it.elo.roundToInt()} (${it.low.roundToInt()}–${it.high.roundToInt()}) n ${it.games} score ${it.score}") }
        r.verdicts.forEach { (q, v) -> println("LadderPlayTest: ${q.label}: ${v.words} — ${v.why}") }
    }
}
