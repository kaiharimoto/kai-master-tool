package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.net.WireCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Lounge's messages, over a WebSocket from a friend's browser to kai's computer (`docs/LOUNGE.md`). The lobby
 * is its own: who is here, the rooms, sitting and standing, the decks kept for each member. A room's duel travels
 * inside [Table] as the two-player [Wire] it always was, so the table's rules for hidden cards are the same ones —
 * this is R5, "the same Wire over websockets", with rooms around it.
 *
 * Getting in is not here: the passcode is checked over HTTP before the socket opens ([LoungeAuth]), and the socket
 * carries the session that check gave.
 */
@Serializable
sealed class LoungeWire {
    // ---- guest to host ------------------------------------------------------------------------------

    /** Arrives with a nickname, or comes back under the [token] an earlier connection was given. */
    @Serializable @SerialName("hi")
    data class Hi(val proto: Int = PROTO, val nick: String = "", val token: String? = null) : LoungeWire()

    @Serializable @SerialName("create")
    data class Create(val name: String) : LoungeWire()

    /** Into a room, or back to the lobby with null. */
    @Serializable @SerialName("enter")
    data class Enter(val room: String?) : LoungeWire()

    @Serializable @SerialName("sit")
    data class Sit(val seat: Int) : LoungeWire()

    @Serializable @SerialName("stand")
    data object Stand : LoungeWire()

    /** Ready to duel with one of the member's own decks. */
    @Serializable @SerialName("ready")
    data class Ready(val deck: String) : LoungeWire()

    /** Asks the other seated player to trade seats; with [yes], answers such an ask. */
    @Serializable @SerialName("swap")
    data class Swap(val yes: Boolean? = null) : LoungeWire()

    @Serializable @SerialName("ai-seat")
    data class AiSeat(val seat: Int, val on: Boolean = true, val deck: String? = null) : LoungeWire()

    /**
     * A room's settings: Ai allowed, watchers kept to the public table, only legal decks (kai's); one game or the best of
     * three (the room's maker's, or kai's).
     */
    @Serializable @SerialName("room")
    data class RoomSet(
        val room: String,
        val ai: Boolean? = null,
        val publicOnly: Boolean? = null,
        val bestOf: Int? = null,
        val legalOnly: Boolean? = null,
        /** How hard Ai thinks here: `DuelPrefs.FAST`, `STRONG` or `MAX` (kai's). */
        val aiStrength: String? = null,
    ) : LoungeWire()

    @Serializable @SerialName("close")
    data class Close(val room: String) : LoungeWire()

    @Serializable @SerialName("kick")
    data class Kick(val who: String) : LoungeWire()

    /** A word in the lobby, or in the room the member is in. */
    @Serializable @SerialName("say")
    data class Say(val text: String) : LoungeWire()

    @Serializable @SerialName("decks")
    data object Decks : LoungeWire()

    /** One of the member's decks, whole, to edit. */
    @Serializable @SerialName("deck-get")
    data class DeckGet(val id: String) : LoungeWire()

    /** A deck saved as `.ydk`/`.ydkx` text (uploaded, pasted from `ydke://`, or edited); a new one without [id]. */
    @Serializable @SerialName("deck-save")
    data class DeckSave(
        val id: String? = null,
        val name: String,
        val text: String,
        /** kai's library deck it was brought from (kai's own saves only): Ai playing it knows its guide. */
        val library: String? = null,
    ) : LoungeWire()

    /** Is this deck (`.ydk`/`ydke://` text, being edited) legal under kai's rules? Answered with [Checked]. */
    @Serializable @SerialName("check")
    data class Check(val text: String) : LoungeWire()

    @Serializable @SerialName("deck-delete")
    data class DeckDelete(val id: String) : LoungeWire()

    /**
     * Between a match's games, the deck sided for the next one: the same cards as the deck registered for the match
     * (`LoungeMatch.check`), by passcode; [first], from the player who chooses, whether they go first.
     */
    @Serializable @SerialName("side")
    data class Side(val main: List<Int>, val extra: List<Int>, val side: List<Int>, val first: Boolean? = null) : LoungeWire()

    /** The room's duel over, by a player at it or by kai: kept as a replay on kai's computer, the seats ready again. */
    @Serializable @SerialName("end")
    data object End : LoungeWire()

    /** The duel's own messages, both ways, for the room the member is in. */
    @Serializable @SerialName("table")
    data class Table(val wire: Wire) : LoungeWire()

    /**
     * Words to Ai in the room's log (L5): everyone in the room reads the question and the answer — or, [private], the
     * asker alone, answered with what their seat sees.
     */
    @Serializable @SerialName("ask-ai")
    data class AskAi(val text: String, val private: Boolean = false) : LoungeWire()

    @Serializable @SerialName("bye")
    data object Bye : LoungeWire()

    // ---- host to guest ------------------------------------------------------------------------------

    /** Who the member is here, and the token to come back with. */
    @Serializable @SerialName("welcome")
    data class Welcome(val you: String, val token: String, val proto: Int = PROTO) : LoungeWire()

    /** The Lounge now: sent to everyone after every change. */
    @Serializable @SerialName("state")
    data class State(val lounge: Lounge) : LoungeWire()

    /** Where the member is at the table they are in: [seat], or watching with null. */
    @Serializable @SerialName("seated")
    data class Seated(val room: String?, val seat: Int?, val publicOnly: Boolean = false) : LoungeWire()

    @Serializable @SerialName("refused")
    data class Refused(val reason: String) : LoungeWire()

    /** Turned away for good: a wrong version, the Lounge full, sent away by kai. */
    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : LoungeWire()

    @Serializable @SerialName("said")
    data class Said(
        val from: String,
        val nick: String,
        val text: String,
        val room: String? = null,
        /** When, kai's clock: at a room's table the line stands among the moves by it. */
        val at: Long = 0,
    ) : LoungeWire()

    /** What was said lately where the member has just arrived — a room, or the lobby ([room] null). */
    @Serializable @SerialName("chat")
    data class Chat(val room: String?, val lines: List<Said>) : LoungeWire()

    /** The room's conversation with Ai as this member may read it: everyone's, and their own private asks; [thinking] while Ai answers. */
    @Serializable @SerialName("talk")
    data class Talk(
        val room: String,
        val entries: List<TalkEntry>,
        val thinking: Boolean = false,
        /** Ai's answer as far as it has written it, while it writes (round three): the log shows it live. */
        val streaming: String? = null,
    ) : LoungeWire()

    /**
     * Side for [game] of the match in [room]: the deck the player registered for it ([main], [extra], [side], as they
     * last sided it), and whether they [choose] who goes first.
     */
    @Serializable @SerialName("siding")
    data class Siding(
        val room: String,
        val game: Int,
        val main: List<Int>,
        val extra: List<Int>,
        val side: List<Int>,
        val choose: Boolean = false,
    ) : LoungeWire()

    /** The member's decks, each checked against kai's [rules] (their words: "TCG", "Genesys, 100 points"). */
    @Serializable @SerialName("deck-list")
    data class DeckList(val decks: List<DeckInfo>, val rules: String = "") : LoungeWire()

    /** A deck being edited, checked against kai's rules: what is wrong with it ([issues], empty when legal). */
    @Serializable @SerialName("checked")
    data class Checked(val issues: List<String>, val rules: String = "") : LoungeWire()

    @Serializable @SerialName("deck")
    data class Deck(val id: String, val name: String, val text: String) : LoungeWire()

    companion object {
        /** Bumped when a message changes shape so an old page cannot read it. */
        const val PROTO = 2
    }
}

/** One line of a room's conversation with Ai: who said it, when (kai's clock, as the log's lines), and for whom. */
@Serializable
data class TalkEntry(
    val at: Long,
    /** The member's nickname, or Ai's name. */
    val who: String,
    val ai: Boolean,
    val text: String,
    /** Asked or answered for one member alone (their id): never sent to anyone else. */
    val to: String? = null,
)

/** A deck kept for a member: its name and counts, to choose from. */
@Serializable
data class DeckInfo(
    val id: String,
    val name: String,
    val main: Int,
    val extra: Int,
    val side: Int,
    val legal: Boolean = true,
    /** Why it is not legal under kai's rules (`DeckList.rules`), one line a problem; empty when it is. */
    val issues: List<String> = emptyList(),
)

object LoungeCodec {
    /** The same settings as the duel's [WireCodec], so a [Wire] inside reads as it does alone. */
    val json: Json = Json(WireCodec.json) {}

    /** The most a guest may send in one message: a deck's text is the largest, and a few kilobytes. */
    const val MAX_IN = 256 * 1024

    fun encode(w: LoungeWire): String = json.encodeToString(LoungeWire.serializer(), w)
    fun decode(text: String): LoungeWire? =
        if (text.length > MAX_IN) null else runCatching { json.decodeFromString(LoungeWire.serializer(), text) }.getOrNull()
}
