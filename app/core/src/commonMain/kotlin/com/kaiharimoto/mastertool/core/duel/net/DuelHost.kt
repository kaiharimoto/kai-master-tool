package com.kaiharimoto.mastertool.core.duel.net

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelReach
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ResponseWindow
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.SeatState
import com.kaiharimoto.mastertool.core.duel.ViewCard
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelFolds
import com.kaiharimoto.mastertool.core.duel.effects.FxTag

/**
 * The host's side of a networked duel, pure: what a guest's intent comes to on the real table, and what
 * the guest is sent after. A guest names cards by the refs its view gave it — a uid for a card it can
 * see, a veil for one it cannot (an opponent's set card it destroys), a [DuelMirror.deckRef] for a card of
 * a deck by its place — and the host turns them back into uids, refusing anything that names a card the
 * guest was never shown.
 */
object DuelHost {

    /**
     * Shortcut at a networked table (Phase D §5½ 5): refused in words. The guest's own engine would need cards its view does
     * not hold, and the host's would play moves the guest never saw chosen; the moves are made by hand there.
     */
    const val NO_SHORTCUTS = "Shortcuts are not played at a networked table: make the moves by hand"

    /** Another seat setting this seat's life points outright (the red team, 2026-10): refused in words. */
    const val NOT_YOUR_LP = "Only your own life points are set outright: change theirs by an amount"

    /**
     * The most moves one intent from another seat carries (the red team, 2026-10): a gesture is a handful and a combo played
     * out a few dozen, and every one is kept in the log for good.
     */
    const val MAX_ACTIONS = 400

    /**
     * The longest words a move from another seat carries — a chat line, a note, a lock, a link's note, a token's or a
     * counter's name (the red team, 2026-10): longer is cut here, since the log keeps it in the replay, synced and backed up.
     */
    const val MAX_TEXT = 500

    /** The largest deck a table deals (a Hello's, a Lounge seat's): far past any legal deck, short of a flood. */
    const val MAX_MAIN = 200
    const val MAX_EXTRA = 100
    const val MAX_SIDE = 100

    /** Why a deck of these sizes is too large for a table, or null when it is not. */
    fun tooLarge(main: Int, extra: Int, side: Int = 0): String? =
        if (main > MAX_MAIN || extra > MAX_EXTRA || side > MAX_SIDE) {
            "That deck is too large for the table: at most $MAX_MAIN Main Deck, $MAX_EXTRA Extra Deck and $MAX_SIDE Side Deck cards."
        } else {
            null
        }

    /** [text] cut to [max] characters, never through the middle of a character outside the BMP. */
    fun clip(text: String, max: Int = MAX_TEXT): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    /** [actions] with every ref turned into a uid, or the reason one could not be. */
    fun resolve(s: DuelState, seat: Int, secret: Long, actions: List<DuelAction>): Pair<List<DuelAction>?, String?> {
        if (actions.size > MAX_ACTIONS) return null to "That is too many moves at once: at most $MAX_ACTIONS"
        val byVeil: Map<Int, Int> by lazy {
            s.cards.keys.associateBy { uid -> DuelView.veil(secret, uid, s.epoch[uid] ?: 0) }
        }
        var problem: String? = null
        fun uid(ref: Int): Int {
            if (problem != null) return ref
            DuelMirror.deckOf(ref)?.let { (deckSeat, i) ->
                return s.seats.getOrNull(deckSeat)?.deck?.getOrNull(i) ?: ref.also { problem = "That card is no longer in the deck" }
            }
            if (ref > 0) {
                if (ref !in s.cards) { problem = "No such card"; return ref }
                if (!DuelSight.sees(s, ref, seat)) { problem = "That card is not one you can see"; return ref }
                return ref
            }
            return byVeil[ref] ?: ref.also { problem = "That card has moved out of reach" }
        }
        fun place(p: Place): Place = if (p is Place.Under) p.copy(host = uid(p.host)) else p
        // A list of cards names each card once at most: one longer than the table's cards only repeats itself into the log.
        fun refs(list: List<Int>): List<Int> {
            if (list.size > s.cards.size) {
                if (problem == null) problem = "That names more cards than the table holds"
                return list
            }
            return list.map(::uid)
        }
        // A host card the guest cannot see stays out of its sight (1.0.85, the red team's second pass): it may go to
        // its owner's own piles or side of the field — destroyed, banished, milled — but never to the guest's side,
        // into the guest's hand, or face-up by a flip the guest makes. One list with Ai's ([DuelReach], Phase C).
        fun refuse(why: String) { if (problem == null) problem = why }
        // The turn player through the batch: two End Turns in one intent must not end the host's turn too.
        var active = s.active
        val out = actions.map { a ->
            when (a) {
                is DuelAction.Move -> a.copy(uid = uid(a.uid), to = place(a.to), how = a.how?.let(::clip)).also { m -> DuelReach.refusal(s, seat, m)?.let(::refuse) }
                is DuelAction.Position -> a.copy(uid = uid(a.uid)).also { p -> DuelReach.refusal(s, seat, p)?.let(::refuse) }
                is DuelAction.Counter -> a.copy(uid = uid(a.uid), kind = clip(a.kind))
                is DuelAction.ChainAdd -> a.copy(seat = seat, uid = a.uid?.let(::uid), note = clip(a.note), targets = refs(a.targets)).also { c -> DuelReach.refusal(s, seat, c)?.let(::refuse) }
                is DuelAction.Target -> a.copy(seat = seat, from = a.from?.let(::uid), to = refs(a.to)).also { t -> DuelReach.refusal(s, seat, t)?.let(::refuse) }
                // A guest reveals its own cards only: never the host's deck, hand or set cards (1.0.85).
                is DuelAction.Reveal -> a.copy(seat = seat, uids = refs(a.uids)).also { r -> DuelReach.refusal(s, seat, r)?.let(::refuse) }
                is DuelAction.Attack -> a.copy(seat = seat, attacker = uid(a.attacker), target = a.target?.let(::uid))
                is DuelAction.Keep -> a.copy(uid = uid(a.uid))
                is DuelAction.Negate -> a.copy(seat = seat)
                is DuelAction.Token -> a.copy(seat = seat, name = clip(a.name))
                // Life points (the red team, 2026-10): a manual table deals the other player damage by an amount, so a
                // change to theirs stands; set outright, theirs would be a duel won by typing 0. A seat sets only its own.
                is DuelAction.Lp -> a.also { if (it.set != null && it.seat != seat) refuse(NOT_YOUR_LP) }
                is DuelAction.Propose -> a.copy(seat = seat)
                is DuelAction.Decline -> a.copy(seat = seat)
                is DuelAction.Lock -> a.copy(seat = seat, text = clip(a.text), until = clip(a.until))
                // The turn player moves the phase; the other player asks (Propose).
                is DuelAction.Phase, DuelAction.EndTurn -> a.also {
                    if (active != seat) refuse("It is not your turn: ask them to move on")
                    if (a is DuelAction.EndTurn && !s.solo) active = 1 - active
                }
                is DuelAction.Ping -> a.copy(seat = seat, kind = clip(a.kind), uid = a.uid?.let(::uid))
                // What a guest says or does as itself is always its own seat's.
                is DuelAction.Draw -> a.copy(seat = seat)
                is DuelAction.Shuffle -> a.copy(seat = seat)
                // A card at random (1.0.87): the guest names its pool by refs; chance is the host's, as every roll is.
                is DuelAction.Pick -> a.copy(seat = seat, among = refs(a.among), salt = 0L, how = a.how?.let(::clip))
                is DuelAction.Chat -> a.copy(seat = seat, text = clip(a.text))
                is DuelAction.Thinking -> a.copy(seat = seat)
                is DuelAction.Answer -> a.copy(seat = seat).also {
                    // Only the player a window waits on answers it.
                    if (problem == null && s.window != null && s.window.responder != seat) problem = "That window waits on the other player"
                }
                is DuelAction.Concede -> a.copy(seat = seat)
                is DuelAction.Coin -> a.copy(seat = seat)
                is DuelAction.Dice -> a.copy(seat = seat)
                // The guest puts back its own die and coin only (1.1.9).
                is DuelAction.Stow -> a.copy(seat = seat)
                // The guest throws its own dice and only those; what they read is the host's to stamp (1.0.87).
                is DuelAction.OpeningRoll -> a.copy(seat = seat, values = emptyList(), toss = a.toss?.takeIf { it.valid })
                is DuelAction.GoFirst -> a.copy(seat = seat)
                is DuelAction.Note -> a.copy(seat = seat, text = clip(a.text))
                // The guest's app is this one's version (a Hello's proto; the Lounge's page is served from here): a move
                // it does not know is no player's move, only bytes for the log to keep.
                is DuelAction.Unknown -> a.also { refuse("That move is from another version of the duel") }
                else -> a
            }
        }
        return if (problem != null) null to problem else out to null
    }

    /**
     * An intent from [seat], made on the game if it can be: refused while a response window waits on the
     * other player (unless [force]), and opening one for the other player when their [windows] setting
     * asks for it.
     */
    fun act(
        game: DuelGame,
        seat: Int,
        actions: List<DuelAction>,
        windows: Map<Int, String>,
        force: Boolean = false,
        at: Long = 0L,
        by: Provenance? = null,
        /** The engine's tags, when the moves are a Shortcut's: refused here ([NO_SHORTCUTS]) unless [engine]. */
        fx: List<FxTag?> = emptyList(),
        /**
         * The engine's moves are let in, tags and all (Phase E, E.3): only at a match on this computer, for a seat the app's own
         * search plays. A networked table never passes it.
         */
        engine: Boolean = false,
    ): DuelGame.Result {
        if (!engine && fx.any { it != null }) return DuelGame.Result(game, NO_SHORTCUTS)
        val w = game.state.window
        val tableMoves = actions.any { !it.social && it !is DuelAction.Answer }
        if (w != null && w.opener == seat && tableMoves && !force) {
            return DuelGame.Result(game, "Waiting for ${DuelWords.seatName(game.state, w.responder)} to respond or pass")
        }
        val r = game.act(actions, seat, at, by = by, fx = if (engine) fx else emptyList())
        if (!r.ok) return r
        var s = r.game.state
        // The responder acting closes the window as surely as a Pass does.
        if (w != null && w.responder == seat && tableMoves) s = s.copy(window = null)
        val other = 1 - seat
        if (!s.solo && Windows.opens(actions, windows[other] ?: Windows.OFF)) {
            s = s.copy(window = ResponseWindow(seat, other, r.game.cursor - 1, actions.firstOrNull { !it.social }?.let { it::class.simpleName.orEmpty() } ?: ""))
        }
        return DuelGame.Result(r.game.copy(state = s), null)
    }

    /**
     * The log entries from [from] to the game's cursor, as [seat] reads them; the table at [from] from
     * [folds] when the page keeps one (1.0.86), not from folding the whole duel again.
     */
    fun lines(game: DuelGame, from: Int, seat: Int?, catalog: DuelCatalog, folds: DuelFolds<*>? = null): List<Line> {
        if (from >= game.cursor) return emptyList()
        var s = folds?.takeIf { it.header == game.header }?.sync(game.entries)?.stateAt(from) ?: game.stateAt(from)
        return game.entries.subList(from, game.cursor).map { e ->
            val after = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
            val line = Line(e.i, DuelWords.say(s, after, e, seat, catalog), e.seat, chat = e.action is DuelAction.Chat, turn = after.turn, at = e.at)
            s = after
            line
        }
    }

    /** Everything a guest at [seat] is sent after a change: its view, the new lines, whose answer is awaited. */
    fun update(game: DuelGame, seat: Int, from: Int, secret: Long, catalog: DuelCatalog, takeBackFrom: Int? = null, folds: DuelFolds<*>? = null): Wire.Update =
        Wire.Update(
            cursor = game.cursor,
            view = DuelView.of(game.state, seat, secret),
            lines = lines(game, from, seat, catalog, folds),
            waitingFor = game.state.window?.responder,
            takeBackFrom = takeBackFrom,
        )

    /** The longest name a guest's Hello gives itself or its deck: both are kept in the duel's header, and its replay. */
    const val MAX_NAME = 60

    /** The seat a guest's [hello] sits at: its deck, and its own and its deck's names cut to [MAX_NAME] (the red team, 2026-10). */
    fun guestSeat(hello: Wire.Hello): SeatSetup =
        SeatSetup(clip(hello.name.trim(), MAX_NAME).ifBlank { "Guest" }, hello.main, hello.extra, null, clip(hello.deckName.trim(), MAX_NAME))

    /** The seat's setting for windows, after a Hello. */
    fun knows(hello: Wire.Hello, secret: Int): String? = when {
        hello.proto != Wire.PROTO -> "That app speaks another version of the duel (${hello.proto}, this one ${Wire.PROTO}). Update both."
        hello.secret != secret -> "The code is not this table's."
        hello.main.isEmpty() -> "Bring a deck with a Main Deck."
        else -> tooLarge(hello.main.size, hello.extra.size)
    }
}

/**
 * The guest's table, built from what the host sent it: a [DuelState] whose cards are the view's refs —
 * a uid for a card it sees, a veil for one it does not — so the guest's page draws, hit-tests and
 * builds intents exactly as a local duel does, and can never hold more than its view.
 */
object DuelMirror {
    private const val DECK_BASE = 2_000_000
    private const val DECK_SPAN = 100_000

    /** A deck's card by its place: the guest cannot see which card it is, only where. */
    fun deckRef(seat: Int, index: Int): Int = -(DECK_BASE + seat * DECK_SPAN + index)

    fun deckOf(ref: Int): Pair<Int, Int>? {
        val n = -ref - DECK_BASE
        if (ref >= 0 || n < 0 || n >= DECK_SPAN * 2) return null
        return n / DECK_SPAN to n % DECK_SPAN
    }

    fun state(v: DuelView, names: List<String> = emptyList()): DuelState {
        val cards = HashMap<Int, CardInst>()
        val seen = HashMap<Int, Set<Int>>()
        val viewer = v.viewer
        fun add(c: ViewCard, inHidden: Boolean = false): Int {
            cards[c.ref] = CardInst(c.ref, c.code ?: 0, c.owner, c.controller, c.pos, c.counters, c.token, c.name, c.under.map { add(it) }, c.atk, c.def, c.extraDeck)
            // A card the viewer knows in a place it could not otherwise see (revealed, seen go back).
            if (inHidden && c.code != null && viewer != null) seen[c.ref] = setOf(viewer)
            return c.ref
        }
        val seats = v.seats.mapIndexed { i, sv ->
            val hiddenHand = viewer != null && viewer != i
            val deck = List(sv.deck) { k ->
                val known = sv.deckKnown[k]
                if (known != null) add(known, inHidden = true)
                else deckRef(i, k).also { cards[it] = CardInst(it, 0, i, i, CardPosition.FACE_DOWN_DEF) }
            }
            SeatState(
                name = sv.name.ifBlank { names.getOrElse(i) { "" } },
                lp = sv.lp,
                hand = sv.hand.map { add(it, inHidden = hiddenHand) },
                deck = deck,
                extra = sv.extra.map { add(it, inHidden = viewer != null && viewer != i) },
                gy = sv.gy.map { add(it) },
                banished = sv.banished.map { add(it, inHidden = !it.pos.faceUp && viewer != i) },
                monsters = sv.monsters.map { it?.let { c -> add(c, inHidden = !c.pos.faceUp && viewer != c.controller) } },
                spells = sv.spells.map { it?.let { c -> add(c, inHidden = !c.pos.faceUp && viewer != c.controller) } },
                field = sv.field?.let { c -> add(c, inHidden = !c.pos.faceUp && viewer != c.controller) },
            )
        }
        val emz = v.emz.map { it?.let { c -> add(c, inHidden = !c.pos.faceUp && viewer != c.controller) } }
        return DuelState(
            cards = cards,
            seats = seats,
            emz = emz,
            turn = v.turn,
            active = v.active,
            phase = v.phase,
            chain = v.chain,
            arrows = v.arrows,
            seen = seen,
            thinking = v.thinking,
            solo = v.solo,
            proposal = v.proposal,
            locks = v.locks,
            resolved = v.resolved,
            attacks = v.attacks,
            opening = v.opening,
            chance = v.chance,
            conceded = v.conceded,
        )
    }

    /** A guest's game to draw: the mirror, with no log of its own (the host keeps the log). */
    fun game(v: DuelView, header: DuelHeader): DuelGame =
        DuelGame(header, emptyList(), 0, state(v), 0)
}
