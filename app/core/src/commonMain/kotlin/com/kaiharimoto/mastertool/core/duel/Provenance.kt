package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.Serializable

/**
 * Who made a move, and how the table was set when they did (Phase C, `docs/phases/C.md` §1): kept on every entry of
 * the log ([DuelEntry.by]), so a duel is a record that can be measured — "Ai won N of M against kai, with these
 * settings" is read off it ([com.kaiharimoto.mastertool.core.duel.record.DuelResults]).
 *
 * Stamped when the move is committed, as randomness is ([seal], from `DuelGame.act`, `Replays.insert`, `DuelHost.act`):
 * the page says who is moving and how the table is set, and the log adds what only it knows — for Ai's own moves, a
 * fingerprint of the table it was shown ([view]) and how many peeks it had taken. Never a copy of anything hidden: the
 * fingerprint is a hash, so the record proves which view Ai acted on without holding what it could not see.
 *
 * Every field has a default and the whole is optional on an entry, so a duel or replay written before has none and
 * reads as it did (`OldDataTest`); a move with none is counted as no one's.
 */
@Serializable
data class Provenance(
    /** Who moved: [PERSON] at this device, [AI], the network's [GUEST], or the [TABLE] itself (a turn's draw). */
    val by: String = PERSON,
    /** The seat Ai held at that moment; null when no Ai sat at the table. */
    val aiSeat: Int? = null,
    /** Ai's knowledge setting then (`DuelBrief`: self, auto, full, opponent); null when no Ai sat at the table. */
    val aiKnows: String? = null,
    /** The person's eyes on a hot-seat then (`DuelPrefs.knowledge`: all, both hands face-up; seat, their own seat's). */
    val eyes: String? = null,
    /** Ai's own move: the first 16 hex digits of the SHA-256 of the [DuelView] it acted on (its seat through its knowledge). */
    val view: String? = null,
    /** Ai's own move: the peeks it had taken in this duel before it. */
    val peeks: Int? = null,
    /** This entry is one of Ai's peeks (`duel_peek`, knowledge auto). */
    val peek: Boolean = false,
    /** A networked table (host-authoritative): the host's log keeps whose seat was whose. */
    val net: Boolean = false,
) {
    val byAi: Boolean get() = by == AI

    companion object {
        const val PERSON = "person"
        const val AI = "ai"
        const val GUEST = "guest"
        const val TABLE = "table"

        /**
         * [by] as it is written into the log, on the table [state] it was made on: an Ai move gets the fingerprint of the
         * view it acted on and the peeks it had taken; anyone else's carries no fingerprint, whatever it was handed.
         */
        fun seal(by: Provenance?, state: DuelState, header: DuelHeader, played: List<DuelEntry>): Provenance? {
            by ?: return null
            if (!by.byAi) return by.copy(view = null, peeks = null)
            val seat = by.aiSeat ?: return by.copy(view = null, peeks = null)
            val viewer = DuelBrief.viewer(by.aiKnows ?: DuelBrief.SELF, seat)
            return by.copy(view = viewHash(state, viewer, header.seed), peeks = played.count { it.by?.peek == true })
        }

        /** The fingerprint of the table as [viewer] sees it (null: everything): stable for the same view, never the view. */
        fun viewHash(state: DuelState, viewer: Int?, secret: Long): String =
            Sha256.hex(DuelCodec.json.encodeToString(DuelView.serializer(), DuelView.of(state, viewer, secret))).take(16)
    }
}
