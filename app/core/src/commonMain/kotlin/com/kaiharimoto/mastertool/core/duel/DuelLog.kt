package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/** How a duel began: the decks in their written order, the seed every shuffle and coin comes from, who went first. */
@Serializable
data class DuelHeader(
    val id: String = "",
    val seed: Long = 0L,
    val seats: List<SeatSetup> = listOf(SeatSetup(), SeatSetup()),
    val first: Int = 0,
    val solo: Boolean = false,
    val startLp: Int = DuelState.START_LP,
    val handSize: Int = 5,
    val created: Long = 0L,
    /**
     * The duel opens with the dice (1.0.87, [Opening]): each seat throws two, the higher chooses to go first or second.
     * Absent in every duel before — and on a one-player table — which begins with [first] going first, as it did.
     */
    val openingRoll: Boolean = false,
)

@Serializable
data class SeatSetup(
    val name: String = "",
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    /** The library deck it came from, when it came from one: combos and Ai's notes hang off it. */
    val deckId: String? = null,
    val deckName: String = "",
)

/**
 * One thing that happened: [action] by [seat] (null: the table itself, the deal) at [at] ms. Entries
 * that share a [group] were one gesture — an Xyz Summon is a move and its materials, a mill of three is
 * three moves, an Ai combo is all of its steps — and undo takes back a group at a time.
 */
@Serializable
data class DuelEntry(
    val i: Int,
    val at: Long = 0L,
    val seat: Int? = null,
    val group: Int = 0,
    @Serializable(with = LenientAction::class) val action: DuelAction,
    /**
     * Who made it and how the table was set (Phase C, 1.1.x, [Provenance]): stamped on commit. Absent on every entry
     * written before, and on the deal's — the table's own.
     */
    val by: Provenance? = null,
)

/** A duel as written to a file: how it began, and everything that happened. */
@Serializable
data class DuelRecord(
    val header: DuelHeader = DuelHeader(),
    val entries: List<DuelEntry> = emptyList(),
    /** How many entries were in play (the rest is undone, kept for redo). */
    val cursor: Int = entries.size,
    val name: String = "",
    val version: Int = VERSION,
    /** A "what if" remembers the replay it was played on from, and from where. */
    val parent: String? = null,
    val parentAt: Int? = null,
    /** When it was saved as a replay, in ms. */
    val saved: Long = 0L,
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * The deal, and the duel as a fold over its log. [DuelSetup.initial] lays the decks out in their written
 * order; [DuelSetup.opening] is what every duel's log begins with — each deck shuffled and the opening
 * hands drawn — so that even the deal is in the record.
 */
object DuelSetup {
    fun initial(h: DuelHeader): DuelState {
        val cards = mutableMapOf<Int, CardInst>()
        val seats = (0..1).map { s ->
            val setup = h.seats.getOrNull(s) ?: SeatSetup()
            var uid = 1 + s * DuelState.SEAT_UIDS
            val deck = setup.main.map { code -> (uid++).also { cards[it] = CardInst(it, code, owner = s, pos = CardPosition.FACE_DOWN_DEF) } }
            val extra = setup.extra.map { code -> (uid++).also { cards[it] = CardInst(it, code, owner = s, pos = CardPosition.FACE_DOWN_DEF, extraDeck = true) } }
            SeatState(name = setup.name, lp = h.startLp, deck = deck, extra = extra)
        }
        return DuelState(cards = cards, seats = seats, active = h.first, solo = h.solo, opening = if (h.openingRoll && !h.solo) Opening() else null)
    }

    fun opening(h: DuelHeader): List<DuelAction> = (0..1).flatMap { s ->
        val setup = h.seats.getOrNull(s) ?: return@flatMap emptyList()
        if (setup.main.isEmpty() || (h.solo && s == 1)) return@flatMap emptyList()
        listOf(DuelAction.Shuffle(s), DuelAction.Draw(s, minOf(h.handSize, setup.main.size)))
    }

    /**
     * Folds [entries] onto the deal, skipping (and naming) any the table refuses — an edited replay
     * whose later entries no longer fit is shown struck through, never thrown away.
     */
    fun fold(h: DuelHeader, entries: List<DuelEntry>, from: DuelState = initial(h)): Pair<DuelState, Set<Int>> {
        var s = from
        val refused = mutableSetOf<Int>()
        entries.forEach { e ->
            when (val o = DuelRules.apply(s, e.action, e.seat)) {
                is Outcome.Ok -> s = o.state
                is Outcome.Refused -> refused += e.i
            }
        }
        return s to refused
    }
}

/**
 * A duel in play: the log, how much of it is in play ([cursor]; past it is what undo took back, for
 * redo), and the table it folds to. Immutable — every act, undo and redo is a new game — so the page
 * holds one value and Compose sees every change.
 *
 * Randomness is stamped from the dice of its roll ([DuelRandom.forRoll]: the nth shuffle, coin or die of
 * the duel; 1.0.74–1.0.85 keyed them by entry, and what those stamped keeps its values): an undone shuffle
 * shuffled again comes out the same, chat or no chat in between, so undo can never be used to fish for a
 * better draw. Tokens' uids and locks' ids are stamped too ([DuelIds]).
 */
data class DuelGame(
    val header: DuelHeader,
    val entries: List<DuelEntry>,
    val cursor: Int,
    val state: DuelState,
    /** Undo never goes behind the deal. */
    val floor: Int,
) {
    val canUndo: Boolean get() = cursor > floor
    val canRedo: Boolean get() = cursor < entries.size

    /** The entries in play. */
    val played: List<DuelEntry> get() = entries.subList(0, cursor)

    /**
     * Commits [actions] as one group by [seat], all or nothing. What they leave to chance is stamped
     * here. Anything undone is dropped: acting after an undo starts a new future.
     */
    fun act(actions: List<DuelAction>, seat: Int?, at: Long = 0L, join: Boolean = false, by: Provenance? = null): Result {
        if (actions.isEmpty()) return Result(this, null)
        // [join]: one gesture made in steps (a turn's opening, 1.0.86) — the last group grows, never one behind the deal.
        val last = entries.getOrNull(cursor - 1)
        val group = if (join && last != null && cursor > floor) last.group else (last?.group ?: -1) + 1
        // Chance from the dice of the roll it is (1.0.86, `forRoll`), not of the entry: a line of chat
        // between an undo and a new shuffle no longer changes the shuffle.
        var roll = played.count { DuelRandom.rolls(it.action) }
        val rolled = actions.map { a -> if (DuelRandom.rolls(a)) DuelRandom.stamp(a, DuelRandom.forRoll(header.seed, roll++)) else a }
        // A token's uid and a lock's id written in too (1.0.86), so an insert into the past never renumbers them.
        val stamped = DuelIds.stamp(state, rolled, DuelIds.next(state, played))
        val (next, problem) = DuelRules.applyAll(state, stamped, seat)
        if (next == null) return Result(this, problem)
        // Who moved, stamped as the dice are (Phase C): an Ai move with the fingerprint of the view it acted on.
        val sealed = Provenance.seal(by, state, header, played)
        val added = stamped.mapIndexed { k, a -> DuelEntry(cursor + k, at, seat, group, a, sealed) }
        return Result(copy(entries = played + added, cursor = cursor + added.size, state = next), null)
    }

    fun act(action: DuelAction, seat: Int?, at: Long = 0L, by: Provenance? = null): Result = act(listOf(action), seat, at, by = by)

    fun undo(): DuelGame {
        if (!canUndo) return this
        val group = entries[cursor - 1].group
        var to = cursor - 1
        while (to > floor && entries[to - 1].group == group) to--
        return copy(cursor = to, state = DuelCheckpoints.stateAt(header, entries, to))
    }

    fun redo(): DuelGame {
        if (!canRedo) return this
        val group = entries[cursor].group
        var to = cursor + 1
        while (to < entries.size && entries[to].group == group) to++
        val (state, _) = DuelSetup.fold(header, entries.subList(cursor, to), this.state)
        return copy(cursor = to, state = state)
    }

    /** Some table move is in play to take back: talk alone is not one (1.0.86). */
    val canUndoMove: Boolean get() = (floor until cursor).any { !isTalk(entries[it].action) }

    /**
     * Undo that steps over talk (1.0.86): the newest group that moved anything on the table is taken back, and the
     * groups of talk made after it — a word to Ai, a cue, a ping, a thinking mark, a note — stay in the log, in their
     * order, now just before it, so Ctrl Z after cueing Ai takes back the move and not the cue. The move is the first
     * thing redo puts back. With no talk after the last move this is [undo]; with only talk back to the deal, nothing
     * changes.
     */
    fun undoMove(): DuelGame {
        var end = cursor
        while (end > floor) {
            val start = groupStart(end)
            if ((start until end).any { !isTalk(entries[it].action) }) {
                if (end == cursor) return undo()
                val talk = entries.subList(end, cursor)
                val move = entries.subList(start, end)
                // The same run of group numbers in the new order, so groups stay distinct and rising.
                var next = move.first().group
                var was: Int? = null
                val moved = (talk + move).mapIndexed { k, e ->
                    if (was != null && e.group != was) next++
                    was = e.group
                    e.copy(i = start + k, group = next)
                }
                val reordered = entries.subList(0, start) + moved + entries.subList(cursor, entries.size)
                val to = start + talk.size
                return copy(entries = reordered, cursor = to, state = DuelCheckpoints.stateAt(header, reordered, to))
            }
            end = start
        }
        return this
    }

    /** Redo that steps over talk (1.0.86): groups are put back until one that moves something on the table is. */
    fun redoMove(): DuelGame {
        var g = this
        while (g.canRedo) {
            val from = g.cursor
            g = g.redo()
            if ((from until g.cursor).any { !isTalk(g.entries[it].action) }) break
        }
        return g
    }

    /** Where the group that ends just before entry [end] begins (never behind the deal). */
    private fun groupStart(end: Int): Int {
        val group = entries[end - 1].group
        var to = end - 1
        while (to > floor && entries[to - 1].group == group) to--
        return to
    }

    /** The table before entry [n] (after the first n entries). */
    fun stateAt(n: Int): DuelState = DuelCheckpoints.stateAt(header, entries, n)

    fun record(name: String = "", parent: String? = null, parentAt: Int? = null, saved: Long = 0L): DuelRecord =
        DuelRecord(header, entries, cursor, name, parent = parent, parentAt = parentAt, saved = saved)

    data class Result(val game: DuelGame, val problem: String?) {
        val ok: Boolean get() = problem == null
    }

    companion object {
        /**
         * Talk (1.0.86): what is said at the table and changes nothing on it — chat (every word to Ai and every cue),
         * a ping, a thinking mark, a note, and what a newer build wrote. An ask, the answer to one and a lock written
         * down are the players' moves, not talk: undo takes them back.
         */
        fun isTalk(a: DuelAction): Boolean = a is DuelAction.Chat || a is DuelAction.Ping || a is DuelAction.Thinking ||
            a is DuelAction.Note || a is DuelAction.Unknown

        /** A new duel, dealt: the opening shuffles and draws are its first entries, behind undo's reach. */
        fun start(header: DuelHeader, at: Long = 0L): DuelGame {
            val base = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
            val dealt = base.act(DuelSetup.opening(header), null, at).game
            return dealt.copy(floor = dealt.cursor)
        }

        /** A duel read back from its file, folded to where it was. */
        fun of(record: DuelRecord): DuelGame {
            val entries = record.entries.mapIndexed { i, e -> e.copy(i = i) }
            val cursor = record.cursor.coerceIn(0, entries.size)
            val floor = entries.indexOfFirst { it.seat != null }.let { if (it < 0) cursor else it }.coerceAtMost(cursor)
            return DuelGame(record.header, entries, cursor, DuelCheckpoints.stateAt(record.header, entries, cursor), floor)
        }
    }
}

/**
 * The tables along the logs folded last, one every [EVERY] entries (1.0.92): undo, an undo that steps over talk, the
 * table before an entry and a duel read back each folded the whole log from the deal, every time — a cost that grew with
 * the duel. Now each starts from the nearest table kept behind where it is going, for a log that begins with the same
 * entries (a pointer check an entry, as data classes compare by identity first), and folds on from there exactly as
 * [DuelSetup.fold] does, a refused entry skipped. The tables are a cache of the log, never the record: memory only, a few
 * logs at most, and safe to share — a list replaced whole, never changed in place, so two threads at worst both fold.
 */
internal object DuelCheckpoints {
    const val EVERY = 32
    /** Logs kept at once: the duel in play, a replay, a what-if, a guest's view. */
    const val LOGS = 4

    /** [states] the tables after 0, [EVERY], 2 × [EVERY] … entries of [entries], on [header]'s deal. */
    private class Mark(val header: DuelHeader, val entries: List<DuelEntry>, val states: List<DuelState>)

    @Volatile
    private var marks: List<Mark> = emptyList()

    /** How many entries were applied to a table here, all told: what the tests count. */
    internal var applied: Long = 0L
        private set

    /** The table after the first [n] of [entries] on [header]'s deal: [DuelSetup.fold]'s answer, from the nearest table kept. */
    fun stateAt(header: DuelHeader, entries: List<DuelEntry>, n: Int): DuelState {
        val target = n.coerceIn(0, entries.size)
        if (target < EVERY) return fold(header, entries, 0, target, DuelSetup.initial(header))
        val now = marks
        // The kept log that shares the longest start with this one, up to where it is going.
        var best: Mark? = null
        var shared = -1
        for (m in now) {
            if (m.header != header) continue
            val c = common(m.entries, entries, target)
            if (c > shared) { best = m; shared = c }
        }
        val usable = if (best == null) 0 else minOf(shared / EVERY, best.states.size - 1)
        var state = best?.states?.get(usable) ?: DuelSetup.initial(header)
        var k = usable * EVERY
        val added = ArrayList<DuelState>()
        while (k < target) {
            val stop = minOf(target, (k / EVERY + 1) * EVERY)
            state = fold(header, entries, k, stop, state)
            k = stop
            if (k % EVERY == 0) added += state
        }
        // Folded past what was kept: the new tables kept for this log (its start's tables too).
        if (added.isNotEmpty()) {
            val start = best?.states?.subList(0, usable + 1) ?: listOf(DuelSetup.initial(header))
            val mark = Mark(header, entries, start + added)
            // The log it grew from goes when this one holds every table that one did.
            val covered = best != null && usable + 1 == best.states.size
            marks = (listOf(mark) + now.filter { it !== best || !covered }).take(LOGS)
        }
        return state
    }

    /** How many of the first [most] entries the two logs share. */
    private fun common(a: List<DuelEntry>, b: List<DuelEntry>, most: Int): Int {
        val m = minOf(a.size, b.size, most)
        var k = 0
        while (k < m && a[k] == b[k]) k++
        return k
    }

    private fun fold(header: DuelHeader, entries: List<DuelEntry>, from: Int, to: Int, start: DuelState): DuelState {
        if (from >= to) return start
        applied += to - from
        return DuelSetup.fold(header, entries.subList(from, to), start).first
    }

    /** Forgets every table kept: for the tests. */
    internal fun clear() {
        marks = emptyList()
    }
}

/**
 * A long log scrubbed back and forth (a replay's timeline): the table at any entry, folded from the
 * nearest snapshot behind it rather than from the deal. Snapshots are kept every [EVERY] entries, in
 * memory only — the log is the record, these are a cache of it.
 */
class DuelTimeline(val header: DuelHeader, val entries: List<DuelEntry>) {
    private val snapshots = HashMap<Int, DuelState>()
    private val refusedSoFar = HashMap<Int, Set<Int>>()

    /** The table after the first [n] entries, and which of them were refused. */
    fun at(n: Int): Pair<DuelState, Set<Int>> {
        val target = n.coerceIn(0, entries.size)
        var base = (target / EVERY) * EVERY
        while (base > 0 && base !in snapshots) base -= EVERY
        var state = if (base == 0) DuelSetup.initial(header) else snapshots.getValue(base)
        var refused = if (base == 0) emptySet() else refusedSoFar.getValue(base)
        var k = base
        while (k < target) {
            val stop = minOf(target, (k / EVERY + 1) * EVERY)
            val (s, r) = DuelSetup.fold(header, entries.subList(k, stop), state)
            state = s
            refused = refused + r
            k = stop
            if (k % EVERY == 0 && k !in snapshots) {
                snapshots[k] = state
                refusedSoFar[k] = refused
            }
        }
        return state to refused
    }

    companion object {
        const val EVERY = 32
    }
}

/** Reads an action it knows as itself, and one it does not as [DuelAction.Unknown], written back as it came. */
object LenientAction : KSerializer<DuelAction> {
    override val descriptor: SerialDescriptor = DuelAction.serializer().descriptor

    override fun serialize(encoder: Encoder, value: DuelAction) {
        val json = encoder as? JsonEncoder
        if (value is DuelAction.Unknown && json != null) json.encodeJsonElement(value.raw)
        else encoder.encodeSerializableValue(DuelAction.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): DuelAction {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(DuelAction.serializer())
        val element = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(DuelAction.serializer(), element) }
            .getOrElse { DuelAction.Unknown(element as? JsonObject ?: JsonObject(emptyMap())) }
    }
}

object DuelCodec {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        classDiscriminator = "t"
    }

    fun encode(r: DuelRecord): String = json.encodeToString(DuelRecord.serializer(), r)

    /** Null when the text is not a duel at all; a duel with parts this build cannot read keeps them as unknown. */
    fun decode(text: String): DuelRecord? = runCatching { json.decodeFromString(DuelRecord.serializer(), text) }.getOrNull()
}
