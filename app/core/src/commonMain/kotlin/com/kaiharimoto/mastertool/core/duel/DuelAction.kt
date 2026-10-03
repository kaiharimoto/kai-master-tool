package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Where a card goes. */
@Serializable
sealed interface Place {
    /** A single zone. For [ZoneKind.EMZ] the seat is the monster's controller, the slot shared. */
    @Serializable @SerialName("zone")
    data class Zone(val seat: Int, val kind: ZoneKind, val index: Int = 0) : Place

    /**
     * A pile. [at] null is the pile's natural place — the deck's top, the hand's right end, the
     * graveyard's and banished pile's top; 0 is the top (the hand's left end), -1 the bottom, n before index n.
     */
    @Serializable @SerialName("pile")
    data class Pile(val seat: Int, val kind: PileKind, val at: Int? = null) : Place

    /** Beneath a card on the field, as a material. [index] -1 is the bottom. */
    @Serializable @SerialName("under")
    data class Under(val host: Int, val index: Int = -1) : Place

    /** Out of the duel: where a token goes when it leaves the field. */
    @Serializable @SerialName("void")
    data object Void : Place

    companion object {
        const val TOP = 0
        const val BOTTOM = -1
    }
}

/**
 * Everything a player — or Ai, or the network — can do to the table, as data: the log is a list of
 * these, a replay is that list played, a combo is a list of them with cards named instead of numbered.
 *
 * Small and orthogonal on purpose: attaching a material is a [Move] to [Place.Under], detaching one a
 * [Move] out of it, a token leaving is a [Move] to [Place.Void]. A newer build's action an older one
 * does not know reads as [Unknown] and passes through untouched.
 *
 * Randomness is never rolled when an action is applied: [Shuffle.salt], [Coin.heads] and [Dice.value]
 * are stamped when the action is committed (`DuelGame.act`), so a replay plays the same however it is
 * edited, and the network's host is the only one who rolls.
 */
@Serializable
sealed class DuelAction {
    // ---- cards ---------------------------------------------------------------------------------------

    /**
     * Moves a card. [pos] null keeps the card's face where that makes sense (and turns it face-up in a
     * graveyard, face-down in a deck). [how] says what the move meant — "normal", "special", "set",
     * "activate", "tribute", "search", "send", "banish", "return", "attach" — for the log and for
     * response windows; the table never reads it.
     */
    @Serializable @SerialName("move")
    data class Move(val uid: Int, val to: Place, val pos: CardPosition? = null, val how: String? = null) : DuelAction()

    @Serializable @SerialName("draw")
    data class Draw(val seat: Int, val n: Int = 1) : DuelAction()

    /** Shuffles a pile (the deck, hand or Extra Deck). [salt] is stamped on commit. */
    @Serializable @SerialName("shuffle")
    data class Shuffle(val seat: Int, val pile: PileKind = PileKind.DECK, val salt: Long = 0L) : DuelAction()

    @Serializable @SerialName("pos")
    data class Position(val uid: Int, val pos: CardPosition) : DuelAction()

    /** Adds [delta] counters of [kind] (blank is "a counter"); a count that reaches 0 goes. */
    @Serializable @SerialName("counter")
    data class Counter(val uid: Int, val delta: Int, val kind: String = "") : DuelAction()

    /** Puts a token on the field. Its uid is the table's next; [code] a token's passcode when known. */
    @Serializable @SerialName("token")
    data class Token(
        val seat: Int,
        val to: Place.Zone,
        val pos: CardPosition = CardPosition.FACE_UP_DEF,
        val code: Int = 0,
        val name: String = "Token",
        val atk: Int? = null,
        val def: Int? = null,
        /**
         * Stamped on commit (1.0.86, `DuelIds`), so a move put into the past never renumbers it. A log
         * written before has none, and the token takes the table's next uid as it always did.
         */
        val uid: Int? = null,
    ) : DuelAction()

    // ---- life and flow -------------------------------------------------------------------------------

    /** Changes life points by [delta], or sets them to [set]. */
    @Serializable @SerialName("lp")
    data class Lp(val seat: Int, val delta: Int = 0, val set: Int? = null) : DuelAction()

    @Serializable @SerialName("phase")
    data class Phase(val phase: DuelPhase) : DuelAction()

    /** The turn passes to the other seat, in its Draw Phase. */
    @Serializable @SerialName("end")
    data object EndTurn : DuelAction()

    /**
     * The seat whose turn it is not asks to move on — to [phase], or to end the turn when [end] (1.0.79,
     * Ai: "let the non-turn seat ask to advance the phase, and you confirm"). The turn player answers by
     * moving the phase (yes) or [Decline].
     */
    @Serializable @SerialName("propose")
    data class Propose(val seat: Int, val phase: DuelPhase? = null, val end: Boolean = false) : DuelAction()

    @Serializable @SerialName("decline")
    data class Decline(val seat: Int) : DuelAction()

    /** Writes a lock down (1.0.79): "Synchro Monsters only", until the end of the turn, the chain or the duel. */
    @Serializable @SerialName("lock")
    data class Lock(
        val seat: Int,
        val text: String,
        val until: String = com.kaiharimoto.mastertool.core.duel.Lock.UNTIL_TURN,
        /** Stamped on commit (1.0.86), as a token's uid is; without one it is the highest held + 1, as before. */
        val id: Int? = null,
    ) : DuelAction()

    @Serializable @SerialName("unlock")
    data class Unlock(val id: Int) : DuelAction()

    // ---- the chain -----------------------------------------------------------------------------------

    @Serializable @SerialName("chain")
    data class ChainAdd(val seat: Int, val uid: Int? = null, val note: String = "", val targets: List<Int> = emptyList()) : DuelAction()

    /** The newest link resolves and leaves the chain. */
    @Serializable @SerialName("resolve")
    data object ChainResolve : DuelAction()

    @Serializable @SerialName("clear")
    data object ChainClear : DuelAction()

    /**
     * [seat]'s [attacker] attacks [target], or the other player directly when [target] is null (1.0.83,
     * kai: "let me declare attacks with monsters by dragging the monster on top of another"). A declaration
     * only: damage is the players' to work out, as every effect is.
     */
    @Serializable @SerialName("attack")
    data class Attack(val seat: Int, val attacker: Int, val target: Int? = null) : DuelAction()

    /** A resolved link's card stays on the field when the chain is over ("resolve keep", 1.0.83). */
    @Serializable @SerialName("keep")
    data class Keep(val uid: Int) : DuelAction()

    // ---- knowledge -----------------------------------------------------------------------------------

    /** Draws (or, with [on] false, clears) [seat]'s arrows from [from] to [to]. */
    @Serializable @SerialName("target")
    data class Target(val seat: Int, val from: Int? = null, val to: List<Int> = emptyList(), val on: Boolean = true) : DuelAction()

    /** Shows cards to [to] (null: both seats) without moving them: a reveal, a look at the top of the deck. */
    @Serializable @SerialName("reveal")
    data class Reveal(val seat: Int, val uids: List<Int>, val to: Int? = null) : DuelAction()

    // ---- chance --------------------------------------------------------------------------------------

    @Serializable @SerialName("coin")
    data class Coin(val seat: Int, val heads: Boolean = true) : DuelAction()

    @Serializable @SerialName("dice")
    data class Dice(val seat: Int, val value: Int = 1) : DuelAction()

    // ---- talk ----------------------------------------------------------------------------------------

    @Serializable @SerialName("chat")
    data class Chat(val seat: Int, val text: String) : DuelAction()

    /** A nudge on a card or zone, for both players to see: "look", "ok", "no", "wait". */
    @Serializable @SerialName("ping")
    data class Ping(val seat: Int, val kind: String = PING_LOOK, val uid: Int? = null, val place: Place? = null) : DuelAction()

    /** "Hold on — I'm thinking." Cleared by the seat's next action. */
    @Serializable @SerialName("thinking")
    data class Thinking(val seat: Int, val on: Boolean = true) : DuelAction()

    /** The responder answers a response window: [respond] true holds play for a response, false passes. */
    @Serializable @SerialName("answer")
    data class Answer(val seat: Int, val respond: Boolean) : DuelAction()

    // ---- the record ----------------------------------------------------------------------------------

    /** A note in the log — an annotation, "Ai peeked at …", a line a replay's editor wrote. */
    @Serializable @SerialName("note")
    data class Note(val text: String, val seat: Int? = null) : DuelAction()

    @Serializable @SerialName("concede")
    data class Concede(val seat: Int) : DuelAction()

    /** An action from a newer build, kept as it was written. */
    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : DuelAction()

    /** True for talk that never changes the table: it never ends a thinking mark or waits on a window. */
    val social: Boolean
        get() = this is Chat || this is Ping || this is Thinking || this is Note || this is Unknown ||
            this is Propose || this is Decline || this is Lock || this is Unlock

    companion object {
        const val PING_LOOK = "look"
        const val PING_OK = "ok"
        const val PING_NO = "no"
        const val PING_WAIT = "wait"
        val PINGS = listOf(PING_LOOK, PING_OK, PING_NO, PING_WAIT)
    }
}
