package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.ai.meta.FieldShares
import com.kaiharimoto.mastertool.core.duel.Provenance

/**
 * One ledger of a deck's results (Phase G, G.8; the red team's L2). Prep's games, the Duel page's (logged to Prep as they
 * end), the Lounge's and Ai vs Ai's records were counted in four places, or not at all; here they are one list of
 * [TestGame]s, each with its [TestGame.source], so every reading — the matrix, the event's odds, Ai's tools — takes the same
 * games.
 *
 * **People only by default**: games against a person ([people]: logged by hand, or a Lounge game against a friend). The
 * other sources — against Ai at the Duel page or a Lounge seat ([TestGame.SOURCE_AI]), both seats played by the person
 * ([TestGame.SOURCE_SELF]), Ai vs Ai ([TestGame.SOURCE_AI_VS_AI]) — stand beside them, counted ([bySource]) and added only
 * when asked ([select]).
 *
 * A Duel page game is read from Prep (it is logged there as it ends), never from its record too, so none counts twice.
 */
object MatchupLedger {
    /** A game against a person: logged by hand (or before sources were recorded), or a Lounge game against a friend. */
    fun people(g: TestGame): Boolean = g.source == null || g.source == TestGame.SOURCE_PERSON || g.source == TestGame.SOURCE_LOUNGE

    /** The sources that are not people, in the order they stand beside them. */
    val OTHERS = listOf(TestGame.SOURCE_AI, TestGame.SOURCE_SELF, TestGame.SOURCE_AI_VS_AI)

    /** The people's games, and those of the [extra] sources. */
    fun select(games: List<TestGame>, extra: Collection<String>): List<TestGame> = games.filter { people(it) || it.source in extra }

    /**
     * [deckId]'s games from every source: Prep's, and the Lounge's and Ai vs Ai's [records] (never a what-if). [lineage] is
     * the decks it was duplicated from, each with the print it was duplicated at ([com.kaiharimoto.mastertool.core.deck.DeckVersions.lineage]):
     * their games at that print count as this deck's, since they were played with the list it began as.
     */
    fun gather(prep: List<TestGame>, records: List<DuelResult>, deckId: String, lineage: List<Pair<String, String>> = emptyList()): List<TestGame> {
        val own = prep.filter { it.deckId == deckId } + fromRecords(records, deckId)
        val inherited = lineage.flatMap { (ancestor, print) ->
            (prep.filter { it.deckId == ancestor } + fromRecords(records, ancestor)).filter { it.deckPrint == print }.map { it.copy(deckId = deckId) }
        }
        return (own + inherited).distinctBy { it.id }.sortedBy { it.at }
    }

    /**
     * [deckId]'s Lounge and Ai vs Ai games, read off their records as Prep would log them: going first from who had turn 1,
     * the opponent by its deck's id (else its name), the deck's print as dealt. Game 1 for each, since a record does not
     * say which game of a match it was.
     */
    fun fromRecords(records: List<DuelResult>, deckId: String): List<TestGame> = records.mapNotNull { r ->
        if (r.whatIf || r.seats.size != 2) return@mapNotNull null
        val source = when (r.kind) {
            DuelResult.LOUNGE -> TestGame.SOURCE_LOUNGE
            DuelResult.AI_VS_AI -> TestGame.SOURCE_AI_VS_AI
            else -> return@mapNotNull null
        }
        // The deck's seat: a person's before a guest's when both sat with it, else seat 0 (an Ai vs Ai mirror counted once).
        val seats = r.seats.indices.filter { r.seats[it].deckId == deckId }
        val me = seats.firstOrNull { r.seats[it].player == Provenance.PERSON } ?: seats.firstOrNull() ?: return@mapNotNull null
        val mine = r.seats[me]
        val theirs = r.seats[1 - me]
        TestGame(
            id = "record:${r.id}:$me",
            at = r.ended,
            deckId = deckId,
            opponent = theirs.deckId ?: theirs.deckName.ifBlank { theirs.name },
            opponentName = theirs.deckName.ifBlank { theirs.name },
            turn = if (r.first == me) TestGame.FIRST else TestGame.SECOND,
            result = when (r.winner) {
                null -> TestGame.DRAW
                me -> TestGame.WIN
                else -> TestGame.LOSS
            },
            note = if (source == TestGame.SOURCE_LOUNGE) "In the Lounge against ${theirs.name}" else "Ai vs Ai: ${mine.engine} against ${theirs.engine}",
            deckPrint = mine.deckPrint,
            // A Lounge seat Ai played is a game against Ai, as one at the Duel page is.
            source = if (source == TestGame.SOURCE_LOUNGE && theirs.player == Provenance.AI) TestGame.SOURCE_AI else source,
        )
    }

    /** One source's games: how many were decided and won. */
    data class SourceCount(val source: String, val rate: TestStats.Rate) {
        val people: Boolean get() = source == PEOPLE
    }

    /** [bySource]'s word for the people's games together. */
    const val PEOPLE = "people"

    /** The people's games first, then each other source with any games. */
    fun bySource(games: List<TestGame>): List<SourceCount> {
        fun rate(list: List<TestGame>): TestStats.Rate {
            val decided = list.filter { it.result != TestGame.DRAW }
            return TestStats.Rate(decided.count { it.result == TestGame.WIN }, decided.size)
        }
        val people = SourceCount(PEOPLE, rate(games.filter(::people)))
        return listOf(people) + OTHERS.mapNotNull { s -> games.filter { it.source == s }.takeIf { it.isNotEmpty() }?.let { SourceCount(s, rate(it)) } }
    }

    /** A source in words: "People", "Against Ai", "Both seats yourself", "Ai vs Ai". */
    fun sourceName(source: String): String = when (source) {
        PEOPLE -> "People"
        TestGame.SOURCE_AI -> "Against Ai"
        TestGame.SOURCE_SELF -> "Both seats yourself"
        TestGame.SOURCE_AI_VS_AI -> "Ai vs Ai"
        TestGame.SOURCE_LOUNGE -> "Lounge"
        else -> source
    }

    /** A rate as people read it: "62% (48–74%) of 21 games", or "no games". */
    fun rateWords(r: TestStats.Rate): String {
        if (r.games == 0) return "no games"
        val (lo, hi) = r.wilson()
        return "${pct(r.pct)} (${kotlin.math.round(lo * 100).toInt()}–${pct(hi)}) of ${r.games} ${if (r.games == 1) "game" else "games"}"
    }

    /** [b]'s rate less [a]'s in points, with its 95 % range (Newcombe's method 10 for two independent proportions). */
    data class Difference(val points: Double, val low: Double, val high: Double) {
        /** The range clears zero: a real change at 95 %. */
        val clear: Boolean get() = low > 0 || high < 0
    }

    fun difference(a: TestStats.Rate, b: TestStats.Rate): Difference? {
        if (a.games == 0 || b.games == 0) return null
        val (lo, hi) = FieldShares.newcombe(a.wins, a.games, b.wins, b.games)
        return Difference(100 * (b.pct - a.pct), 100 * lo, 100 * hi)
    }

    /** "+12 points (−4 to +27)": the difference and its range, whole points. */
    fun differenceWords(d: Difference): String {
        fun signed(x: Double) = kotlin.math.round(x).toInt().let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }
        return "${signed(d.points)} points (${signed(d.low)} to ${signed(d.high)})" + if (d.clear) "" else ", within chance"
    }

    private fun pct(x: Double) = "${kotlin.math.round(x * 100).toInt()}%"

    /** One version's games: its print (null: an unknown version), the decided games, and going first and second. */
    data class ByVersion(val print: String?, val all: TestStats.Rate, val first: TestStats.Rate, val second: TestStats.Rate, val last: Long)

    /** [games] by the print they were played at, the latest played first. Games before and after a change stand apart. */
    fun byVersion(games: List<TestGame>): List<ByVersion> = games.groupBy { it.deckPrint }.map { (print, list) ->
        fun rate(f: (TestGame) -> Boolean): TestStats.Rate {
            val decided = list.filter { f(it) && it.result != TestGame.DRAW }
            return TestStats.Rate(decided.count { it.result == TestGame.WIN }, decided.size)
        }
        ByVersion(print, rate { true }, rate { it.turn == TestGame.FIRST }, rate { it.turn == TestGame.SECOND }, list.maxOf { it.at })
    }.sortedByDescending { it.last }
}
