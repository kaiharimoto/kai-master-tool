package com.kaiharimoto.mastertool.core.duel.lounge

import kotlinx.serialization.Serializable

/**
 * The Lounge (`docs/LOUNGE.md`; kai: friends "join and spectate, with a nickname and room system that lets us swap
 * around and play or spectate as we choose"): who is here, the rooms, and who sits where. kai's computer holds it;
 * every guest's browser is sent it ([LoungeView]) after each change and asks for changes in words ([LoungeAsk]).
 *
 * Pure: every change is [LoungeRules.apply] from one lounge to the next, or a refusal in words, so the rules are
 * tested here and the server only carries messages. Nothing secret lives in it: a member's token is the server's,
 * kept beside it, never in what is sent.
 */
@Serializable
data class Lounge(
    val members: List<Member> = emptyList(),
    val rooms: List<Room> = emptyList(),
) {
    fun member(id: String): Member? = members.firstOrNull { it.id == id }
    fun room(id: String?): Room? = id?.let { r -> rooms.firstOrNull { it.id == r } }

    /** The room [memberId] is in, and the seat they hold there, if any. */
    fun seatOf(memberId: String): Pair<Room, Int>? =
        rooms.firstNotNullOfOrNull { r -> r.seats.indexOfFirst { it.member == memberId }.takeIf { it >= 0 }?.let { r to it } }

    internal fun withMember(m: Member): Lounge = copy(members = members.map { if (it.id == m.id) m else it })
    internal fun withRoom(r: Room): Lounge = copy(rooms = rooms.map { if (it.id == r.id) r else it })
}

/** Someone in the Lounge: kai (the [host]) or a friend, by the nickname they chose. */
@Serializable
data class Member(
    val id: String,
    val nick: String,
    /** The room they are in, or null in the lobby. */
    val room: String? = null,
    val online: Boolean = true,
    /** kai, at the computer the Lounge runs on: closes rooms, turns Ai on, sends people away. */
    val host: Boolean = false,
)

/** One of a room's two places at the table: a person, Ai, or no one. */
@Serializable
data class Seat(
    val member: String? = null,
    /** Ai sits here, on kai's connection (the room must allow it: [Room.ai]). */
    val ai: Boolean = false,
    /** The deck this seat plays, once chosen: the member's own, kept on kai's computer. */
    val deck: String? = null,
    val deckName: String = "",
    val ready: Boolean = false,
    /** A dropped player's seat, kept for them until this moment while a duel is on (ms); then anyone may sit. */
    val heldUntil: Long? = null,
    /** Whose kept deck Ai plays here: the member who sat it down (`docs/LOUNGE.md`, L5). */
    val aiDeckOf: String? = null,
) {
    val empty: Boolean get() = member == null && !ai
}

@Serializable
data class Room(
    val id: String,
    val name: String,
    val seats: List<Seat> = listOf(Seat(), Seat()),
    /** Who made it: they and kai may close it. */
    val by: String = "",
    /** Ai may be asked in this room — to sit, to answer in its log (kai's call, per room). */
    val ai: Boolean = false,
    /** Watchers see only what is face-up (kai's call); otherwise everything, each hiding what they choose. */
    val publicOnly: Boolean = false,
    /** A duel is on at this table. */
    val playing: Boolean = false,
    /** A seated player asked to trade seats with the other: who asked. */
    val swapAsk: String? = null,
) {
    fun seated(memberId: String): Int? = seats.indexOfFirst { it.member == memberId }.takeIf { it >= 0 }

    /** Both seats filled and ready (Ai is always ready): the duel may begin. */
    val canStart: Boolean get() = !playing && seats.all { !it.empty && (it.ai || it.ready) }
}

/** A change someone asks for. The server fills in who is asking; the rules decide. */
sealed class LoungeAsk {
    data class Join(val id: String, val nick: String, val host: Boolean = false) : LoungeAsk()
    /** The member's connection went; [now] starts the hold on a seat in a duel. */
    data class Drop(val id: String, val now: Long) : LoungeAsk()
    data class Leave(val id: String) : LoungeAsk()
    data class Create(val by: String, val roomId: String, val name: String) : LoungeAsk()
    data class Enter(val by: String, val room: String?) : LoungeAsk()
    data class Sit(val by: String, val seat: Int, val now: Long) : LoungeAsk()
    data class Stand(val by: String) : LoungeAsk()
    data class Ready(val by: String, val deck: String, val deckName: String) : LoungeAsk()
    data class AskSwap(val by: String) : LoungeAsk()
    data class AnswerSwap(val by: String, val yes: Boolean) : LoungeAsk()
    /** Ai sat down at [seat] with [by]'s kept [deck] ([deckName]), or stood up ([on] false). */
    data class SeatAi(val by: String, val seat: Int, val on: Boolean, val deck: String? = null, val deckName: String = "") : LoungeAsk()
    data class SetRoom(val by: String, val room: String, val ai: Boolean? = null, val publicOnly: Boolean? = null) : LoungeAsk()
    data class Close(val by: String, val room: String) : LoungeAsk()
    data class Kick(val by: String, val who: String) : LoungeAsk()
    /** The duel at [room] began (both seats ready) or ended. */
    data class Playing(val room: String, val on: Boolean) : LoungeAsk()
    /** Held seats whose time is up are let go. */
    data class Tick(val now: Long) : LoungeAsk()
}

sealed class LoungeResult {
    data class Ok(val lounge: Lounge) : LoungeResult()
    data class No(val why: String) : LoungeResult()
}

object LoungeRules {
    /** How long a dropped player's seat waits for them in a duel (a browser reloading, a train's tunnel). */
    const val HOLD_MS = 3 * 60_000L
    const val MAX_ROOMS = 8
    const val MAX_MEMBERS = 16
    const val NICK_MAX = 20
    const val ROOM_NAME_MAX = 30

    /** [raw] as a nickname, or null when it cannot be one: letters, digits, spaces and `_ - .`, at most [NICK_MAX]. */
    fun nick(raw: String): String? {
        val n = raw.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty() || n.length > NICK_MAX) return null
        return n.takeIf { it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '_' || c == '-' || c == '.' } }
    }

    fun apply(l: Lounge, ask: LoungeAsk): LoungeResult = when (ask) {
        is LoungeAsk.Join -> join(l, ask)
        is LoungeAsk.Drop -> drop(l, ask)
        is LoungeAsk.Leave -> ok(free(l, ask.id).let { x -> x.copy(members = x.members.filter { it.id != ask.id }) })
        is LoungeAsk.Create -> create(l, ask)
        is LoungeAsk.Enter -> enter(l, ask)
        is LoungeAsk.Sit -> sit(l, ask)
        is LoungeAsk.Stand -> stand(l, ask)
        is LoungeAsk.Ready -> ready(l, ask)
        is LoungeAsk.AskSwap -> askSwap(l, ask)
        is LoungeAsk.AnswerSwap -> answerSwap(l, ask)
        is LoungeAsk.SeatAi -> seatAi(l, ask)
        is LoungeAsk.SetRoom -> setRoom(l, ask)
        is LoungeAsk.Close -> close(l, ask)
        is LoungeAsk.Kick -> kick(l, ask)
        is LoungeAsk.Playing -> l.room(ask.room)?.let { r ->
            ok(l.withRoom(r.copy(playing = ask.on, swapAsk = null, seats = if (ask.on) r.seats else r.seats.map { it.copy(ready = false) })))
        } ?: no("There is no such room")
        is LoungeAsk.Tick -> ok(l.copy(rooms = l.rooms.map { r ->
            r.copy(seats = r.seats.map { s -> if (s.heldUntil != null && s.heldUntil <= ask.now) Seat() else s })
        }))
    }

    private fun ok(l: Lounge) = LoungeResult.Ok(l)
    private fun no(why: String) = LoungeResult.No(why)

    private fun join(l: Lounge, a: LoungeAsk.Join): LoungeResult {
        val nick = nick(a.nick) ?: return no("A nickname is up to $NICK_MAX letters, digits, spaces, _ - or .")
        val taken = l.members.any { it.id != a.id && it.nick.equals(nick, ignoreCase = true) }
        if (taken) return no("Someone here is already called $nick")
        val back = l.member(a.id)
        if (back != null) {
            // Back after a drop: their seat, if it was held, is theirs again.
            val rooms = l.rooms.map { r -> r.copy(seats = r.seats.map { s -> if (s.member == a.id) s.copy(heldUntil = null) else s }) }
            return ok(l.withMember(back.copy(nick = nick, online = true)).copy(rooms = rooms))
        }
        if (l.members.size >= MAX_MEMBERS) return no("The Lounge is full")
        return ok(l.copy(members = l.members + Member(a.id, nick, host = a.host)))
    }

    private fun drop(l: Lounge, a: LoungeAsk.Drop): LoungeResult {
        val m = l.member(a.id) ?: return ok(l)
        val rooms = l.rooms.map { r ->
            r.copy(
                seats = r.seats.map { s ->
                    when {
                        s.member != a.id -> s
                        // In a duel the seat waits for them; out of one it is simply free.
                        r.playing -> s.copy(heldUntil = a.now + HOLD_MS)
                        else -> Seat()
                    }
                },
                swapAsk = r.swapAsk.takeIf { it != a.id },
            )
        }
        return ok(l.withMember(m.copy(online = false)).copy(rooms = rooms))
    }

    /** Every seat [memberId] holds let go, and any swap they asked for forgotten. */
    private fun free(l: Lounge, memberId: String): Lounge = l.copy(rooms = l.rooms.map { r ->
        r.copy(seats = r.seats.map { if (it.member == memberId) Seat() else it }, swapAsk = r.swapAsk.takeIf { it != memberId })
    })

    private fun create(l: Lounge, a: LoungeAsk.Create): LoungeResult {
        l.member(a.by) ?: return no("Join the Lounge first")
        val name = a.name.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty() || name.length > ROOM_NAME_MAX) return no("A room's name is up to $ROOM_NAME_MAX characters")
        if (l.rooms.size >= MAX_ROOMS) return no("There are already $MAX_ROOMS rooms; close one first")
        if (l.rooms.any { it.id == a.roomId }) return no("That room already exists")
        val made = l.copy(rooms = l.rooms + Room(a.roomId, name, by = a.by))
        return enter(made, LoungeAsk.Enter(a.by, a.roomId))
    }

    private fun enter(l: Lounge, a: LoungeAsk.Enter): LoungeResult {
        val m = l.member(a.by) ?: return no("Join the Lounge first")
        if (a.room != null && l.room(a.room) == null) return no("There is no such room")
        if (m.room == a.room) return ok(l)
        // Walking out of a room gives up its seat; a duel there goes on, and the seat waits for anyone to take it.
        return ok(free(l, m.id).withMember(m.copy(room = a.room)))
    }

    private fun sit(l: Lounge, a: LoungeAsk.Sit): LoungeResult {
        val m = l.member(a.by) ?: return no("Join the Lounge first")
        val r = l.room(m.room) ?: return no("Go into a room first")
        if (a.seat !in r.seats.indices) return no("There is no such seat")
        val s = r.seats[a.seat]
        if (s.member == m.id) return ok(l)
        if (s.ai) return no("Ai sits there")
        if (s.member != null) {
            val who = l.member(s.member)?.nick
            if (s.heldUntil == null) return no("${who ?: "Someone"} sits there")
            if (s.heldUntil > a.now) return no("That seat waits for ${who ?: "its player"} to come back")
            // A hold run out: the seat, and in a duel the hand with it, is anyone's.
        }
        val other = r.seated(m.id)
        // Changing seats mid-duel is a swap (both agree), never a walk across the table.
        if (other != null && r.playing) return no("Ask the other player to swap seats")
        val seats = r.seats.mapIndexed { i, x ->
            when {
                i == a.seat -> Seat(member = m.id)
                i == other -> Seat()
                else -> x
            }
        }
        return ok(l.withRoom(r.copy(seats = seats, swapAsk = null)))
    }

    private fun stand(l: Lounge, a: LoungeAsk.Stand): LoungeResult {
        val (r, i) = l.seatOf(a.by) ?: return ok(l)
        return ok(l.withRoom(r.copy(seats = r.seats.mapIndexed { k, s -> if (k == i) Seat() else s }, swapAsk = r.swapAsk.takeIf { it != a.by })))
    }

    private fun ready(l: Lounge, a: LoungeAsk.Ready): LoungeResult {
        val (r, i) = l.seatOf(a.by) ?: return no("Sit down first")
        if (r.playing) return no("A duel is on: the decks are dealt")
        return ok(l.withRoom(r.copy(seats = r.seats.mapIndexed { k, s -> if (k == i) s.copy(deck = a.deck, deckName = a.deckName, ready = true) else s })))
    }

    private fun askSwap(l: Lounge, a: LoungeAsk.AskSwap): LoungeResult {
        val (r, i) = l.seatOf(a.by) ?: return no("Sit down first")
        val other = r.seats[1 - i]
        if (other.empty) return if (r.playing) no("The other seat is empty: no one to swap with") else sit(l, LoungeAsk.Sit(a.by, 1 - i, 0L))
        // Ai never says no: trading with it is a move across the table.
        if (other.ai) return ok(l.withRoom(r.copy(seats = listOf(r.seats[1], r.seats[0]), swapAsk = null)))
        return ok(l.withRoom(r.copy(swapAsk = a.by)))
    }

    private fun answerSwap(l: Lounge, a: LoungeAsk.AnswerSwap): LoungeResult {
        val (r, _) = l.seatOf(a.by) ?: return no("Sit down first")
        val asker = r.swapAsk ?: return no("No one asked to swap")
        if (asker == a.by) return no("You asked: the other player answers")
        if (!a.yes) return ok(l.withRoom(r.copy(swapAsk = null)))
        return ok(l.withRoom(r.copy(seats = listOf(r.seats[1], r.seats[0]), swapAsk = null)))
    }

    private fun seatAi(l: Lounge, a: LoungeAsk.SeatAi): LoungeResult {
        val m = l.member(a.by) ?: return no("Join the Lounge first")
        val r = l.room(m.room) ?: return no("Go into a room first")
        if (a.seat !in r.seats.indices) return no("There is no such seat")
        val s = r.seats[a.seat]
        if (!a.on) {
            if (!s.ai) return ok(l)
            return ok(l.withRoom(r.copy(seats = r.seats.mapIndexed { k, x -> if (k == a.seat) Seat() else x })))
        }
        if (!r.ai) return no("Ai is not on in this room: kai turns it on")
        if (!s.empty) return no("That seat is taken")
        if (r.playing) return no("A duel is on: Ai sits down before it starts")
        val deck = a.deck ?: return no("Choose the deck Ai plays")
        val seat = Seat(ai = true, ready = true, deck = deck, deckName = a.deckName, aiDeckOf = a.by)
        return ok(l.withRoom(r.copy(seats = r.seats.mapIndexed { k, x -> if (k == a.seat) seat else x })))
    }

    private fun setRoom(l: Lounge, a: LoungeAsk.SetRoom): LoungeResult {
        val m = l.member(a.by) ?: return no("Join the Lounge first")
        val r = l.room(a.room) ?: return no("There is no such room")
        if (!m.host) return no("Only kai changes a room's settings")
        val ai = a.ai ?: r.ai
        // Ai turned off stands it up from its seats too.
        val seats = if (ai) r.seats else r.seats.map { if (it.ai) Seat() else it }
        return ok(l.withRoom(r.copy(ai = ai, publicOnly = a.publicOnly ?: r.publicOnly, seats = seats)))
    }

    private fun close(l: Lounge, a: LoungeAsk.Close): LoungeResult {
        val m = l.member(a.by) ?: return no("Join the Lounge first")
        val r = l.room(a.room) ?: return no("There is no such room")
        if (!m.host && r.by != m.id) return no("Only the room's maker or kai closes it")
        if (r.playing && !m.host) return no("A duel is on in it")
        return ok(l.copy(rooms = l.rooms.filter { it.id != r.id }, members = l.members.map { if (it.room == r.id) it.copy(room = null) else it }))
    }

    private fun kick(l: Lounge, a: LoungeAsk.Kick): LoungeResult {
        val m = l.member(a.by) ?: return no("Join the Lounge first")
        if (!m.host) return no("Only kai sends people away")
        if (a.who == m.id) return no("You cannot send yourself away")
        return ok(free(l, a.who).let { x -> x.copy(members = x.members.filter { it.id != a.who }) })
    }
}
