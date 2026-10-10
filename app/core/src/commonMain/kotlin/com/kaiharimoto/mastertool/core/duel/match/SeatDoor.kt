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
 * what it sees — never the duel), the written effects ([kit]), and two ways to move, both held where any player's are:
 * - [engine]: a move of the engine's, which the engine makes legal, committed with its tags as one group. The seat answers
 *   its own choices inside it ([Choose], told only the cards it may see); a choice the other seat makes is that seat's.
 * - [ops]: the typed words a player's seat plays — phases, the end of a turn, an attack, a Set, a pass — each checked by the
 *   table it is played at.
 *
 * Two tables open one: a match of the app's own ([MatchDoor]), and kai's own duel with Search in Ai's seat (E.3b).
 */
interface SeatDoor {
    val seat: Int
    val kind: CueKind
    val kit: GoldfishKit

    /** A response window the seat's move opened waits on the other seat: nothing more until they answer. */
    val waitsOnOther: Boolean

    /**
     * The seat's own choice inside one of its moves: [cards] are the codes of [Decision.Cards.among] it may see, null where
     * hidden. A move the table makes again from the start (to ask the other seat a choice of its own) first [rewind]s it.
     */
    fun interface Choose {
        fun choose(d: Decision, cards: List<Int?>): List<Int>

        fun rewind() {}
    }

    /** What the seat has seen of the table now. */
    fun seen(): Seen

    /** [move] made by the engine, the seat's choices by [choose]; null when made, else why not. */
    suspend fun engine(move: FxMove, choose: Choose): String?

    /** [ops] as the table plays a player's typed words; the table's words back, and whether one was refused. */
    suspend fun ops(vararg ops: String): Pair<String, Boolean>
}

/**
 * The door of a match of the app's own (E.3): engine moves committed through `DuelHost.act` so the other seat's windows open
 * as for any move, the other seat's choices inside them the first the effect allows (a language model's seat is not asked
 * mid-move), typed ops as `duel_act` plays them.
 */
class MatchDoor internal constructor(private val table: MatchTable, override val seat: Int, override val kind: CueKind, override val kit: GoldfishKit) : SeatDoor {
    override val waitsOnOther: Boolean get() = table.state.window?.opener == seat

    override fun seen(): Seen = Seen.of(table.game, seat, kit)

    override suspend fun engine(move: FxMove, choose: SeatDoor.Choose): String? = table.engine(seat, move, kit, choose)

    override suspend fun ops(vararg ops: String): Pair<String, Boolean> =
        table.tool(seat, "duel_act", buildJsonObject { putJsonArray("ops") { ops.forEach { add(it) } } })
}
