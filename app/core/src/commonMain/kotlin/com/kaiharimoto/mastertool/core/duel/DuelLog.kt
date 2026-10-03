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
            val extra = setup.extra.map { code -> (uid++).also { cards[it] = CardInst(it, code, owner = s, pos = CardPosition.FACE_DOWN_DEF) } }
            SeatState(name = setup.name, lp = h.startLp, deck = deck, extra = extra)
        }
        return DuelState(cards = cards, seats = seats, active = h.first, solo = h.solo)
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
    fun act(actions: List<DuelAction>, seat: Int?, at: Long = 0L): Result {
        if (actions.isEmpty()) return Result(this, null)
        val group = (entries.getOrNull(cursor - 1)?.group ?: -1) + 1
        // Chance from the dice of the roll it is (1.0.86, `forRoll`), not of the entry: a line of chat
        // between an undo and a new shuffle no longer changes the shuffle.
        var roll = played.count { DuelRandom.rolls(it.action) }
        val rolled = actions.map { a -> if (DuelRandom.rolls(a)) DuelRandom.stamp(a, DuelRandom.forRoll(header.seed, roll++)) else a }
        // A token's uid and a lock's id written in too (1.0.86), so an insert into the past never renumbers them.
        val stamped = DuelIds.stamp(state, rolled, DuelIds.next(state, played))
        val (next, problem) = DuelRules.applyAll(state, stamped, seat)
        if (next == null) return Result(this, problem)
        val added = stamped.mapIndexed { k, a -> DuelEntry(cursor + k, at, seat, group, a) }
        return Result(copy(entries = played + added, cursor = cursor + added.size, state = next), null)
    }

    fun act(action: DuelAction, seat: Int?, at: Long = 0L): Result = act(listOf(action), seat, at)

    fun undo(): DuelGame {
        if (!canUndo) return this
        val group = entries[cursor - 1].group
        var to = cursor - 1
        while (to > floor && entries[to - 1].group == group) to--
        return copy(cursor = to, state = DuelSetup.fold(header, entries.subList(0, to)).first)
    }

    fun redo(): DuelGame {
        if (!canRedo) return this
        val group = entries[cursor].group
        var to = cursor + 1
        while (to < entries.size && entries[to].group == group) to++
        val (state, _) = DuelSetup.fold(header, entries.subList(cursor, to), this.state)
        return copy(cursor = to, state = state)
    }

    /** The table before entry [n] (after the first n entries). */
    fun stateAt(n: Int): DuelState = DuelSetup.fold(header, entries.subList(0, n.coerceIn(0, entries.size))).first

    fun record(name: String = "", parent: String? = null, parentAt: Int? = null, saved: Long = 0L): DuelRecord =
        DuelRecord(header, entries, cursor, name, parent = parent, parentAt = parentAt, saved = saved)

    data class Result(val game: DuelGame, val problem: String?) {
        val ok: Boolean get() = problem == null
    }

    companion object {
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
            return DuelGame(record.header, entries, cursor, DuelSetup.fold(record.header, entries.subList(0, cursor)).first, floor)
        }
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
