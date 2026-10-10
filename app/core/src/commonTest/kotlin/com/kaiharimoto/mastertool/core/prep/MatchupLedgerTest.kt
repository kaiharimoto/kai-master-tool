package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.ResultSeat
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One ledger of results (Phase G, G.8): sources, versions, earlier lists, lineage, and what decided games. */
class MatchupLedgerTest {
    private var n = 0
    private fun game(
        result: String,
        print: String? = "v1",
        opponent: String = "w2",
        name: String = "Yubel",
        source: String? = null,
        deck: String = "d",
        turn: String = TestGame.FIRST,
        cards: List<Int>? = null,
        reason: String? = null,
        opening: List<Int>? = null,
        keys: List<Int> = emptyList(),
    ) = TestGame("g${n++}", n.toLong(), deck, opponent, name, turn, result = result, deckPrint = print, source = source, opponentCards = cards, reason = reason, opening = opening, keyCards = keys)

    private fun record(kind: String, mine: String, theirs: ResultSeat, winner: Int?, first: Int = 0, id: String = "r${n++}") = DuelResult(
        id = id, ended = n.toLong(), kind = kind, first = first, winner = winner,
        seats = listOf(ResultSeat(name = "kai", deckId = "d", deckName = "Lab", player = mine, deckPrint = "v2"), theirs),
    )

    @Test
    fun peopleOnlyByDefaultTheOthersBeside() {
        val prep = listOf(game(TestGame.WIN), game(TestGame.LOSS, source = TestGame.SOURCE_PERSON), game(TestGame.WIN, source = TestGame.SOURCE_AI), game(TestGame.WIN, source = TestGame.SOURCE_SELF))
        val records = listOf(
            record(DuelResult.LOUNGE, Provenance.PERSON, ResultSeat(name = "Mika", deckName = "Yubel", player = Provenance.GUEST), winner = 0),
            record(DuelResult.LOUNGE, Provenance.PERSON, ResultSeat(name = "Ai", deckName = "Tenpai", player = Provenance.AI), winner = 1, first = 1),
            record(DuelResult.AI_VS_AI, Provenance.AI, ResultSeat(name = "B", deckId = "w2", deckName = "Yubel", player = Provenance.AI), winner = null),
            record(DuelResult.LOUNGE, Provenance.PERSON, ResultSeat(name = "Mika", player = Provenance.GUEST), winner = 0).copy(whatIf = true),
            // A duel at the Duel page is read from Prep, where it is logged, never from its record too.
            record(kind = "", Provenance.PERSON, ResultSeat(name = "Ai", player = Provenance.AI), winner = 0).copy(kind = null),
        )
        val all = MatchupLedger.gather(prep, records, "d")
        assertEquals(7, all.size, "four of Prep's, three records (the what-if and the Duel page's record left out)")
        val people = MatchupLedger.select(all, emptyList())
        assertEquals(3, people.size, "two logged by hand, one Lounge game against a friend")
        assertEquals(listOf(TestGame.SOURCE_LOUNGE), people.mapNotNull { it.source }.filter { it != TestGame.SOURCE_PERSON })
        val counts = MatchupLedger.bySource(all).associate { it.source to it.rate }
        assertEquals(TestStats.Rate(2, 3), counts[MatchupLedger.PEOPLE])
        assertEquals(TestStats.Rate(1, 2), counts[TestGame.SOURCE_AI], "the Duel page's and the Lounge's Ai seat")
        assertEquals(TestStats.Rate(0, 0), counts[TestGame.SOURCE_AI_VS_AI], "a draw decides nothing")
        assertEquals(5, MatchupLedger.select(all, listOf(TestGame.SOURCE_AI)).size)
        // A Lounge record reads the turn from who had turn 1.
        val lost = all.single { it.source == TestGame.SOURCE_AI && it.id.startsWith("record") }
        assertEquals(TestGame.SECOND to TestGame.LOSS, lost.turn to lost.result)
    }

    @Test
    fun gamesBeforeAndAfterAChangeStandApart() {
        val games = listOf(game(TestGame.WIN, "v1"), game(TestGame.WIN, "v1"), game(TestGame.LOSS, "v1"), game(TestGame.LOSS, "v2"), game(TestGame.LOSS, "v2"), game(TestGame.WIN, null))
        val by = MatchupLedger.byVersion(games).associateBy { it.print }
        assertEquals(TestStats.Rate(2, 3), by["v1"]?.all)
        assertEquals(TestStats.Rate(0, 2), by["v2"]?.all)
        assertEquals(TestStats.Rate(1, 1), by[null]?.all, "a game from before versions is an unknown version")
        val d = MatchupLedger.difference(by.getValue("v1").all, by.getValue("v2").all)!!
        assertEquals(-66.7, d.points, 0.1)
        assertTrue(!d.clear, "five games say nothing for sure")
        assertTrue(MatchupLedger.differenceWords(d).startsWith("−67 points ("))
        assertEquals("67% (21–94%) of 3 games", MatchupLedger.rateWords(TestStats.Rate(2, 3)))
    }

    @Test
    fun aDuplicateInheritsItsParentsGamesAtTheListItBeganAs() {
        val prep = listOf(game(TestGame.WIN, "p1", deck = "a"), game(TestGame.LOSS, "p0", deck = "a"), game(TestGame.WIN, "p2", deck = "b"))
        val b = MatchupLedger.gather(prep, emptyList(), "b", lineage = listOf("a" to "p1"))
        assertEquals(listOf("p1", "p2"), b.map { it.deckPrint })
        assertTrue(b.all { it.deckId == "b" })
    }

    @Test
    fun aReImportedListKeepsItsGames() {
        // Yesterday's web named the opponent w2; today's re-import calls it w9, with a list one card different.
        val yubel = (1..12).toSet()
        val tenpai = (40..52).toSet()
        val games = listOf(
            game(TestGame.WIN, opponent = "w2", cards = yubel.toList()),
            game(TestGame.LOSS, opponent = "w2", cards = yubel.toList()),
            // An older game with no list recorded: the old deck is still kept.
            game(TestGame.WIN, opponent = "w3", name = "Old Tenpai"),
            // A typed name only.
            game(TestGame.LOSS, opponent = "Yubel", name = "Yubel"),
            game(TestGame.WIN, opponent = "w9"),
            // Another strategy entirely stays its own.
            game(TestGame.WIN, opponent = "w7", name = "Snake-Eye", cards = (80..95).toList()),
        )
        val today = listOf(
            OpponentMatch.Opponent("w9", "Yubel", (yubel - 12 + 13).map(::CardId).toSet()),
            OpponentMatch.Opponent("w10", "Tenpai Dragon", tenpai.map(::CardId).toSet(), covers = listOf(CardId(40), CardId(41), CardId(42))),
        )
        val known = mapOf("w3" to (40..45).map(::CardId).toSet())
        val on = OpponentMatch.fold(games, today, { known[it] }, include = true)
        assertEquals(mapOf("w9" to 3, "w10" to 1), on.earlier)
        assertEquals(4, on.games.count { it.opponent == "w9" })
        assertEquals("w7", on.games.last().opponent)
        val rows = TestStats.matrix(on.games).associateBy { it.opponent }
        assertEquals(TestStats.Rate(2, 4), rows["w9"]?.all)
        // Left out, they stay under their own names, and are still counted as there.
        val off = OpponentMatch.fold(games, today, { known[it] }, include = false)
        assertEquals(1, off.games.count { it.opponent == "w9" })
        assertEquals(4, off.earlierTotal)
        assertEquals("3 games from earlier lists left out", OpponentMatch.words(3, false))
    }

    @Test
    fun whatDecidedGames() {
        val games = listOf(
            game(TestGame.WIN, opening = listOf(1, 2, 3, 4, 5), keys = listOf(1)),
            game(TestGame.WIN, opening = listOf(1, 6, 7, 8, 9)),
            game(TestGame.LOSS, opening = listOf(6, 7, 8, 9, 10), reason = TestGame.REASON_BRICK),
            game(TestGame.LOSS, turn = TestGame.SECOND, reason = TestGame.REASON_BRICK, keys = listOf(11)),
            game(TestGame.DRAW, opening = listOf(1, 2, 3, 4, 5)),
        )
        val by = TestStats.byCard(games).associateBy { it.card }
        assertEquals(TestStats.Rate(2, 2), by.getValue(1).opened)
        assertEquals(TestStats.Rate(1, 1), by.getValue(1).decided)
        assertEquals(TestStats.Rate(1, 2), by.getValue(6).opened)
        assertEquals(TestStats.Rate(0, 1), by.getValue(11).decided)
        assertEquals(1, TestStats.byCard(games).first().card, "the most games first")

        val b = TestStats.bricks(games, noStarterFirst = 0.2, noStarterSecond = 0.15)!!
        assertEquals(2, b.bricked)
        assertEquals(4, b.games, "a draw is no game")
        assertEquals(0.75, b.expected, 1e-9)
        assertTrue(!b.tooMany, "two of four is a lot, but four games are few: p = ${b.pAtLeast}")
        val many = TestStats.bricks(List(40) { i -> game(if (i < 10) TestGame.LOSS else TestGame.WIN, reason = if (i < 10) TestGame.REASON_BRICK else null) }, 0.08, 0.05)!!
        assertTrue(many.tooMany)
        assertTrue(TestStats.brickWords(many).contains("more than chance would give"))
        assertEquals(1.0, TestStats.binomialAtLeast(0, 10, 0.3))
        assertEquals(0.3 * 0.3, TestStats.binomialAtLeast(2, 2, 0.3), 1e-12)
    }
}
