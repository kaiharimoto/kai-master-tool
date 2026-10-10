package com.kaiharimoto.mastertool.core.duel.search.ladder

import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.DoorPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.match.MatchTable
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The ladder (Phase E, E.4; `docs/phases/E.md` §4.5): every player plays a fixed set of seeds from both seats on the bench,
 * and a Bradley–Terry fit over every kept game gives each an Elo with its range, anchored on the greedy player at 1,000. A
 * search version replaces its parent only when it beats it on the ladder's seeds by a one-sided sign test. A game is played
 * by counted effort, never by time, so it is the same game on any device: the ladder kept on one is the ladder of all.
 */

/** One of the ladder's fixed seeds ([n] from 1): the duel's seed, and the bench decks seat 0 and seat 1 play. */
data class LadderSeed(val n: Int, val seed: Long, val decks: Pair<String, String>)

object LadderSeeds {
    /** The deals: the bench's nine pairings of decks in turn (each deck against each, mirrors too). */
    const val COUNT = 24

    val all: List<LadderSeed> by lazy {
        val ids = Bench.decks.map { it.id }
        val pairings = ids.flatMap { a -> ids.map { b -> a to b } }
        (0 until COUNT).map { i -> LadderSeed(i + 1, 7_919L * (i + 1) + 104_729L, pairings[i % pairings.size]) }
    }

    fun of(n: Int): LadderSeed? = all.getOrNull(n - 1)
}

/** Two players the ladder sets against each other, on its first [seeds] deals, each from both seats. */
data class LadderPair(val a: String, val b: String, val seeds: Int = LadderSeeds.COUNT)

/** A player on the ladder: [id] never changes meaning (a new version is a new id), [name] in words. */
data class LadderPlayer(val id: String, val name: String, val engine: String, val model: String)

object LadderPlayers {
    const val GREEDY = GreedyPlayer.VERSION

    /** The pre-search version: the same search, assuming the other seat holds nothing (no worlds guessed). */
    const val BLANK_FAST = "${SearchPlayer.VERSION}/blank/fast"

    /** The search in use, at the table's Fast and Strong: worlds guessed from the bench's decks. */
    const val FAST = "${SearchPlayer.VERSION}/field/fast"
    const val STRONG = "${SearchPlayer.VERSION}/field/strong"

    val all: List<LadderPlayer> = listOf(
        LadderPlayer(GREEDY, "Greedy", GreedyPlayer.VERSION, "summon, attack, end"),
        LadderPlayer(BLANK_FAST, "Search, guessing nothing · Fast", SearchPlayer.VERSION, "${SearchPlayer.VERSION} · blank · Fast"),
        LadderPlayer(FAST, "Search · Fast", SearchPlayer.VERSION, "${SearchPlayer.VERSION} · Fast"),
        LadderPlayer(STRONG, "Search · Strong", SearchPlayer.VERSION, "${SearchPlayer.VERSION} · Strong"),
    )

    fun of(id: String): LadderPlayer? = all.firstOrNull { it.id == id }

    /** The search version in use, which a candidate must beat to replace (E.5's tuned versions). */
    const val IN_USE = FAST

    /**
     * The pairs the ladder plays, each on its first [LadderPair.seeds] deals from both seats: the search against greedy and
     * against its pre-search version on every deal (the verdicts are read from those), and Strong beside Fast on half of
     * them, since each of its decisions costs five of Fast's. Enough to join every player to the anchor; never every pair,
     * which would cost the most where it says the least.
     */
    val pairs: List<LadderPair> = listOf(
        LadderPair(FAST, GREEDY),
        LadderPair(BLANK_FAST, GREEDY),
        LadderPair(FAST, BLANK_FAST),
        LadderPair(STRONG, FAST, LadderSeeds.COUNT / 2),
    )

    /** [id] as a seat to play: null for an id this build does not know (a later version's, kept as a number). */
    fun make(id: String): DoorPlayer? = when (id) {
        GREEDY -> GreedyPlayer()
        BLANK_FAST -> SearchPlayer({ Prior.Blank }, SearchPlayer.Strength.FAST)
        FAST -> SearchPlayer({ Bench.prior }, SearchPlayer.Strength.FAST)
        STRONG -> SearchPlayer({ Bench.prior }, SearchPlayer.Strength.STRONG)
        else -> null
    }
}

/**
 * One game on the ladder: the [seed] it was dealt by ([LadderSeed.n]), the [players] in seat order, the seat that won (null:
 * a draw), how and in which turn, the [bench] it was played on ([Bench.print]) and when.
 */
@Serializable
data class LadderGame(
    val seed: Int,
    val players: List<String>,
    val winner: Int? = null,
    val how: String = "",
    val turns: Int = 0,
    val bench: String = "",
    val at: Long = 0L,
) {
    /** [player]'s points: 1 a win, ½ a draw, 0 a loss; null when it did not play. */
    fun score(player: String): Double? {
        val seat = players.indexOf(player).takeIf { it >= 0 } ?: return null
        return when (winner) {
            null -> 0.5
            seat -> 1.0
            else -> 0.0
        }
    }
}

/** What `<data>/duel/ladder.json` keeps: every game played, on any bench; ratings are worked out from them, never kept. */
@Serializable
data class LadderBook(val version: Int = 1, val games: List<LadderGame> = emptyList()) {
    /**
     * The games on [bench] and the ladder's deals: the only ones a rating or a verdict reads, so the ladder reads the same
     * wherever it was played. Others stay kept.
     */
    fun on(bench: String = Bench.print): List<LadderGame> = games.filter { it.bench == bench && it.seed in 1..LadderSeeds.COUNT }

    /** [g] kept, replacing a game of the same seed, players and bench. */
    fun with(g: LadderGame): LadderBook =
        copy(games = games.filterNot { it.seed == g.seed && it.players == g.players && it.bench == g.bench } + g)
}

object LadderCodec {
    /** Under `<data>/duel/`, beside the replays and records: synced and backed up with them. */
    const val FILE = "ladder.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(b: LadderBook): String = json.encodeToString(LadderBook.serializer(), b)

    /** Forgiving: a file that does not read is an empty ladder, never an error. */
    fun decode(text: String): LadderBook = runCatching { json.decodeFromString(LadderBook.serializer(), text) }.getOrDefault(LadderBook())
}

/** A game the ladder owes: [seed] with [players] in seat order. */
data class LadderMatch(val seed: LadderSeed, val players: List<String>)

object LadderRun {
    /** The turn a ladder game is capped at: past it, the seat ahead on life points wins (a tournament's rule), else a draw. */
    const val TURNS = 10

    /** The games [book] lacks for [pairs], each on its deals from both seats, on the bench as it is, in a fixed order. */
    fun owed(book: LadderBook, pairs: List<LadderPair> = LadderPlayers.pairs): List<LadderMatch> {
        val have = book.on().map { it.seed to it.players }.toSet()
        return pairs.flatMap { p ->
            LadderSeeds.all.take(p.seeds).flatMap { s -> listOf(listOf(p.a, p.b), listOf(p.b, p.a)).map { LadderMatch(s, it) } }
        }.filter { (it.seed.n to it.players) !in have && it.players.all { p -> LadderPlayers.make(p) != null } }
    }

    /**
     * [m] played to its end on a match table of its own: the referee's rules with no pace and no clock to beat (a cue's
     * time is effort, counted), the bench's written effects, each seat told only what it sees.
     */
    suspend fun play(m: LadderMatch, now: () -> Long = { 0L }): LadderGame {
        val decks = listOf(m.seed.decks.first, m.seed.decks.second).map { Bench.deck(it) ?: error("no bench deck ${it}") }
        val seats = m.players.mapIndexed { i, id ->
            val p = LadderPlayers.of(id) ?: error("no ladder player $id")
            MatchSeat("${p.name} (${i + 1})", null, decks[i].name, decks[i].main, decks[i].extra, connection = p.name, model = p.model)
        }
        val header = MatchTable.header("ladder-${m.seed.n}-${m.players.joinToString("-")}", m.seed.seed, seats, now())
        val rules = MatchRules(turnCap = TURNS, paceMs = 0, cueMillis = UNTIMED)
        val table = MatchTable(DuelGame.start(header), Bench.kit.catalog, rules)
        val players = m.players.map { LadderPlayers.make(it) ?: error("no ladder player $it") }
        val engines = seats.map { DuelResults.Engine(it.connection, it.model) }
        val end = AiMatch(table, players, engines, kit = Bench.kit).run()
        val r = end.result
        return LadderGame(m.seed.n, m.players, r?.winner, r?.how.orEmpty(), r?.turns ?: table.state.turn, Bench.print, now())
    }

    /** A cue's time limit on the ladder: none that a search could reach, so a slow device never loses a game by its clock. */
    private const val UNTIMED = 24L * 60 * 60 * 1000
}
