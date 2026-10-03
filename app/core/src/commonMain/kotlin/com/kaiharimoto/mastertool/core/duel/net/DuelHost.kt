package com.kaiharimoto.mastertool.core.duel.net

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ResponseWindow
import com.kaiharimoto.mastertool.core.duel.SeatState
import com.kaiharimoto.mastertool.core.duel.ViewCard
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelFolds

/**
 * The host's side of a networked duel, pure: what a guest's intent comes to on the real table, and what
 * the guest is sent after. A guest names cards by the refs its view gave it — a uid for a card it can
 * see, a veil for one it cannot (an opponent's set card it destroys), a [DuelMirror.deckRef] for a card of
 * a deck by its place — and the host turns them back into uids, refusing anything that names a card the
 * guest was never shown.
 */
object DuelHost {

    /** [actions] with every ref turned into a uid, or the reason one could not be. */
    fun resolve(s: DuelState, seat: Int, secret: Long, actions: List<DuelAction>): Pair<List<DuelAction>?, String?> {
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
        // A host card the guest cannot see stays out of its sight (1.0.85, the red team's second pass): it may go to
        // its owner's own piles or side of the field — destroyed, banished, milled — but never to the guest's side,
        // into the guest's hand, or face-up by a flip the guest makes.
        fun hidden(u: Int): Boolean = s.cards[u]?.let { it.owner != seat && it.controller != seat && !DuelSight.sees(s, u, seat) } == true
        fun refuse(why: String) { if (problem == null) problem = why }
        // The turn player through the batch: two End Turns in one intent must not end the host's turn too.
        var active = s.active
        val out = actions.map { a ->
            when (a) {
                is DuelAction.Move -> a.copy(uid = uid(a.uid), to = place(a.to)).also { m ->
                    val to = m.to
                    if (hidden(m.uid) && when (to) {
                            is Place.Zone -> to.seat == seat
                            // A graveyard or banishment is its owner's whoever's pile it was dropped on.
                            is Place.Pile -> to.seat == seat && to.kind != PileKind.GY && to.kind != PileKind.BANISHED
                            is Place.Under -> true
                            Place.Void -> false
                        }
                    ) refuse("That card is not yours to take")
                }
                is DuelAction.Position -> a.copy(uid = uid(a.uid)).also { p ->
                    if (hidden(p.uid) && p.pos.faceUp) refuse("Only its controller turns that card face-up")
                }
                is DuelAction.Counter -> a.copy(uid = uid(a.uid))
                is DuelAction.ChainAdd -> a.copy(seat = seat, uid = a.uid?.let(::uid), targets = a.targets.map(::uid)).also { c ->
                    if (c.targets.any { inHandOrDeck(s, it, seat) }) refuse("A card in their hand or Deck cannot be targeted")
                }
                is DuelAction.Target -> a.copy(seat = seat, from = a.from?.let(::uid), to = a.to.map(::uid)).also { t ->
                    if (t.to.any { inHandOrDeck(s, it, seat) }) refuse("A card in their hand or Deck cannot be targeted")
                }
                is DuelAction.Reveal -> a.copy(seat = seat, uids = a.uids.map(::uid)).also { r ->
                    // A guest reveals its own cards only: never the host's deck, hand or set cards (1.0.85).
                    if (problem == null && r.uids.any { u -> s.cards[u]?.let { it.owner != seat && it.controller != seat } != false }) {
                        problem = "You can reveal only your own cards"
                    }
                }
                is DuelAction.Attack -> a.copy(seat = seat, attacker = uid(a.attacker), target = a.target?.let(::uid))
                is DuelAction.Keep -> a.copy(uid = uid(a.uid))
                is DuelAction.Negate -> a.copy(seat = seat)
                is DuelAction.Token -> a.copy(seat = seat)
                is DuelAction.Lp -> a
                is DuelAction.Propose -> a.copy(seat = seat)
                is DuelAction.Decline -> a.copy(seat = seat)
                is DuelAction.Lock -> a.copy(seat = seat)
                // The turn player moves the phase; the other player asks (Propose).
                is DuelAction.Phase, DuelAction.EndTurn -> a.also {
                    if (active != seat) refuse("It is not your turn: ask them to move on")
                    if (a is DuelAction.EndTurn && !s.solo) active = 1 - active
                }
                is DuelAction.Ping -> a.copy(seat = seat, uid = a.uid?.let(::uid))
                // What a guest says or does as itself is always its own seat's.
                is DuelAction.Draw -> a.copy(seat = seat)
                is DuelAction.Shuffle -> a.copy(seat = seat)
                // A card at random (1.0.87): the guest names its pool by refs; chance is the host's, as every roll is.
                is DuelAction.Pick -> a.copy(seat = seat, among = a.among.map(::uid), salt = 0L)
                is DuelAction.Chat -> a.copy(seat = seat)
                is DuelAction.Thinking -> a.copy(seat = seat)
                is DuelAction.Answer -> a.copy(seat = seat).also {
                    // Only the player a window waits on answers it.
                    if (problem == null && s.window != null && s.window.responder != seat) problem = "That window waits on the other player"
                }
                is DuelAction.Concede -> a.copy(seat = seat)
                is DuelAction.Coin -> a.copy(seat = seat)
                is DuelAction.Dice -> a.copy(seat = seat)
                // The guest throws its own dice and only those; what they read is the host's to stamp (1.0.87).
                is DuelAction.OpeningRoll -> a.copy(seat = seat, values = emptyList(), toss = a.toss?.takeIf { it.valid })
                is DuelAction.GoFirst -> a.copy(seat = seat)
                is DuelAction.Note -> a.copy(seat = seat)
                else -> a
            }
        }
        return if (problem != null) null to problem else out to null
    }

    /** Whether [uid] is the other seat's, in its hand or Deck: never a guest's target. */
    private fun inHandOrDeck(s: DuelState, uid: Int, seat: Int): Boolean {
        val c = s.cards[uid] ?: return false
        if (c.owner == seat) return false
        val p = s.placeOf(uid)
        return p is Place.Pile && (p.kind == PileKind.HAND || p.kind == PileKind.DECK)
    }

    /**
     * An intent from [seat], made on the game if it can be: refused while a response window waits on the
     * other player (unless [force]), and opening one for the other player when their [windows] setting
     * asks for it.
     */
    fun act(game: DuelGame, seat: Int, actions: List<DuelAction>, windows: Map<Int, String>, force: Boolean = false, at: Long = 0L): DuelGame.Result {
        val w = game.state.window
        val tableMoves = actions.any { !it.social && it !is DuelAction.Answer }
        if (w != null && w.opener == seat && tableMoves && !force) {
            return DuelGame.Result(game, "Waiting for ${DuelWords.seatName(game.state, w.responder)} to respond or pass")
        }
        val r = game.act(actions, seat, at)
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
            val line = Line(e.i, DuelWords.say(s, after, e, seat, catalog), e.seat, chat = e.action is DuelAction.Chat, turn = after.turn)
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

    /** The seat's setting for windows, after a Hello. */
    fun knows(hello: Wire.Hello, secret: Int): String? = when {
        hello.proto != Wire.PROTO -> "That app speaks another version of the duel (${hello.proto}, this one ${Wire.PROTO}). Update both."
        hello.secret != secret -> "The code is not this table's."
        hello.main.isEmpty() -> "Bring a deck with a Main Deck."
        hello.main.size > 200 || hello.extra.size > 100 -> "That deck is too large for the table."
        else -> null
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
            cards[c.ref] = CardInst(c.ref, c.code ?: 0, c.owner, c.controller, c.pos, c.counters, c.token, c.name, c.under.map { add(it) }, c.atk, c.def)
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
        )
    }

    /** A guest's game to draw: the mirror, with no log of its own (the host keeps the log). */
    fun game(v: DuelView, header: com.kaiharimoto.mastertool.core.duel.DuelHeader): DuelGame =
        DuelGame(header, emptyList(), 0, state(v), 0)
}
