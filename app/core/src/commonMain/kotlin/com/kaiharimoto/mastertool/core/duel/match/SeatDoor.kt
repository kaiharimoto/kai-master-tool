package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.search.Seen
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * What a seat the app's own search plays is handed with each cue (Phase E, E.3; `docs/phases/E.md` §4.4), and all it is
 * handed: what the cue asks ([kind]), what the seat has seen ([seen]: its view, its own decklist, the engine's state cut to
 * what it sees — never the duel), the written effects ([kit]), and two ways to move, both held where `duel_act` is held:
 * - [engine]: a move of the engine's, which the engine makes legal; committed with its tags through `DuelHost.act`, so the
 *   other seat's windows open as they do for any move. The seat answers its own choices inside it ([Choose], told only the
 *   cards it may see); a choice the other seat makes is the table's first answer, said in the log.
 * - [ops]: the typed words a language model's seat plays — phases, the end of a turn, an attack, a Set, a pass — each
 *   checked by the same table, the same law and the same `DuelReach`.
 */
class SeatDoor internal constructor(private val table: MatchTable, val seat: Int, val kind: CueKind, val kit: GoldfishKit) {
    /** The seat's own choice inside one of its moves: [cards] are the codes of [Decision.Cards.among] it may see, null where hidden. */
    fun interface Choose {
        fun choose(d: Decision, cards: List<Int?>): List<Int>
    }

    /** A response window the seat's move opened waits on the other seat: nothing more until they answer. */
    val waitsOnOther: Boolean get() = table.state.window?.opener == seat

    /** What the seat has seen of the table now. */
    fun seen(): Seen = Seen.of(table.game, seat, kit)

    /** [move] made by the engine, the seat's choices by [choose]; null when made, else why not. */
    suspend fun engine(move: FxMove, choose: Choose): String? = table.engine(seat, move, kit, choose)

    /** [ops] as `duel_act` plays them; the table's words back, and whether one was refused. */
    suspend fun ops(vararg ops: String): Pair<String, Boolean> =
        table.tool(seat, "duel_act", buildJsonObject { putJsonArray("ops") { ops.forEach { add(it) } } })
}
