package com.kaiharimoto.mastertool.core.duel.mapper.puzzle

import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRecord
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.search.BattlePlan
import com.kaiharimoto.mastertool.core.duel.search.TurnSolve
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A puzzle of the person's own (Phase M, M4; `docs/phases/M.md` §5): a position and a [goal] for the seat whose turn it is.
 *
 * **The position is the duel's own log up to the moment** ([record]: its header with both decklists, and the entries played),
 * never a table copied out of it: the engine's state — once-per-turn uses spent, this turn's summons, a negation still
 * lasting — is folded from the log exactly as the duel in play folds it, and a duel saved from DuelingBook (`DbConvert`), a
 * replay at its step or a table set up by hand all come the same way.
 *
 * What the solver sees is [knows] (`DuelBrief`'s knowledge), decided when it was saved from what the person could see:
 * [DuelBrief.FULL] when the table showed both seats, else the seat's own eyes, the other seat's hidden cards blanks that play
 * nothing. The other seat answers with every card the solver sees ([PuzzleSolve]).
 *
 * Kept as `<data>/duel/puzzles/<id>.json` ([MapPuzzles.path]): synced and backed up with the replays. [answer] is the last
 * solve's, kept with the print of the written effects it was found with, so a change to them says "solve again".
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MapPuzzle(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION,
    val id: String = "",
    val title: String = "",
    val record: DuelRecord = DuelRecord(),
    /** The seat that solves: the turn player at the moment. Written always, as [knows] and [goal] are: none is ever a default's. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val seat: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val knows: String = DuelBrief.SELF,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val goal: BoardGoal = BoardGoal.TheirLp(0),
    /** Where it came from, in words: "The duel in play", "Replay: Labrynth mirror", "DuelingBook". */
    val from: String = "",
    val created: Long = 0L,
    val answer: PuzzleAnswer? = null,
) {
    /** The position as a duel, folded from its log; null when it cannot be read. */
    fun game(): DuelGame? = runCatching { DuelGame.of(record) }.getOrNull()

    companion object {
        const val VERSION = 1
    }
}

/**
 * The last solve of a puzzle (M4): its [verdict] ([PuzzleSolve.Verdict]'s name), the [line] in words, what the goal [read] on
 * the end table, the engine moves and tables it took, whether every line was searched, the written effects' print it was
 * found with ([effects], `FxTrust.library`), and when.
 */
@Serializable
data class PuzzleAnswer(
    val verdict: String = "",
    val line: List<String> = emptyList(),
    val read: String = "",
    val moves: Int = 0,
    val positions: Int = 0,
    val complete: Boolean = false,
    val effects: String = "",
    val at: Long = 0L,
)

/** Puzzles kept and read (M4). */
object MapPuzzles {
    /** The folder under `<data>/duel/`: synced and backed up with everything there but the duel in play. */
    const val FOLDER = "puzzles"

    /** Where [id] is kept, under `<data>/duel/`. */
    fun path(id: String): String = "$FOLDER/${safe(id)}.json"

    /** An id as a file name: letters, digits, '-' and '_' kept, the rest '-'. */
    fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").ifEmpty { "puzzle" }

    /** Written as the duel's own files are ([DuelCodec]), so its moves read the same. */
    fun encode(p: MapPuzzle): String = DuelCodec.json.encodeToString(MapPuzzle.serializer(), p)

    /** Null when the text is not a puzzle, or one a newer build wrote with a goal this one does not know. */
    fun decode(text: String): MapPuzzle? = runCatching { DuelCodec.json.decodeFromString(MapPuzzle.serializer(), text) }.getOrNull()

    /** Why [game] cannot be a puzzle for the seat whose turn it is, or null: a Main Phase of a duel still going. */
    fun refusal(game: DuelGame): String? {
        val s = game.state
        return when {
            s.conceded != null || s.seats.any { it.lp <= 0 } -> "The duel is over: a puzzle begins in a duel still going."
            s.chain.isNotEmpty() -> "A chain is standing: a puzzle begins at an open table."
            else -> TurnSolve.refusal(game, s.active)
        }
    }

    /**
     * A puzzle of [game] as it stands, for the seat whose turn it is, read with [knows]; null when it cannot be ([refusal]). Only
     * the entries played are kept: an undone future is not the position.
     */
    fun of(game: DuelGame, knows: String, goal: BoardGoal, id: String, title: String, from: String, at: Long): MapPuzzle? {
        if (refusal(game) != null) return null
        val kept = game.played
        return MapPuzzle(
            id = id,
            title = title.trim().ifEmpty { "Turn ${game.state.turn}" },
            record = DuelRecord(game.header, kept, kept.size, name = title.trim()),
            seat = game.state.active,
            knows = knows,
            goal = goal,
            from = from,
            created = at,
        )
    }

    /** The cards of the position, both seats': what the written effects' print is taken over. */
    fun cards(p: MapPuzzle): Set<Int> {
        val g = p.game()
        return buildSet {
            p.record.header.seats.forEach { addAll(it.main); addAll(it.extra) }
            g?.state?.cards?.values?.forEach { if (it.code != 0) add(it.code) }
        }
    }

    /** The position in a line: "Turn 3 · Main Phase 1 · 8000 against 3000 · 2 in hand · 1 monster against 2". */
    fun position(p: MapPuzzle): String {
        val s = p.game()?.state ?: return "The position could not be read."
        val me = s.seats[p.seat]
        val them = s.seats[1 - p.seat]
        return listOf(
            "Turn ${s.turn}",
            "${s.phase.label} Phase",
            "${me.lp} LP against ${them.lp}",
            "${me.hand.size} in hand",
            "${BattlePlan.monsters(s, p.seat).size} monster${if (BattlePlan.monsters(s, p.seat).size == 1) "" else "s"} against ${BattlePlan.monsters(s, 1 - p.seat).size}",
        ).joinToString(" · ")
    }
}
