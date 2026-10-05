package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * The chain, triggers and timing (D.md §2.3): activations, spell speeds, priority, resolution and the triggers an event
 * sets off, held once here.
 *
 * - **Open state.** With the chain empty and nothing pending, the turn player uses speed-1 effects in their Main Phase
 *   (ignitions, Normal, Field, Continuous and Ritual Spells); speed-2 and speed-3 effects are used whenever their
 *   conditions allow, by either player (house ruling: the other player's chance to act in an open state, as at a phase's
 *   end, is not modelled apart).
 * - **Activation.** Once-per-turn is counted at activation (a negated activation still used it); then the card's own move
 *   (a Spell or Trap from the hand to its zone, a set one turned face-up), the costs paid in full ([FxTag.COST]), the
 *   targets chosen ([FxTag.ACTIVATE]), and the link (`ChainAdd`, carrying its targets and, in its memo, every binding).
 * - **Adding a link.** Only at spell speed 2 or more, and never below the newest link's speed (speed 3 answers only speed
 *   3); never while the chain resolves; at most [MOST_LINKS]. A Quick Effect that answers something ([Effect.respond])
 *   needs a newest link that matches it.
 * - **Priority.** After each link the other seat may respond first; a pass (`Answer` false) hands priority over; two
 *   passes in a row and the newest link resolves. A one-player table passes once. [Resolve][FxMove.Resolve] resolves the
 *   newest link whatever priority says: the manual table's players keep their own.
 * - **Resolution.** A negated link does nothing. Otherwise its targets are checked again — one that moved since it was
 *   targeted (a new instance) or no longer matches its pick is dropped, and the log says so — and its steps run; then
 *   the restrictions it leaves. The link leaves through `DuelVerbs.resolve`, which, with the last link, sends the chain's
 *   own Normal, Quick-Play and Ritual Spells and Normal and Counter Traps to the GY: the one list.
 * - **Triggers** ([gather]) wait for the chain or the action to be over, then form a new chain by SEGOC ([segoc]): the turn
 *   player's mandatory, their optional, the other's mandatory, the other's optional, each player ordering their own
 *   ([Decision.Order]) and saying yes or no to each optional one ([Decision.YesNo]). An optional `WHEN` trigger whose event
 *   was not the last thing to happen misses the timing; `IF` and mandatory triggers never do. A trigger let go is said in
 *   the log ([FxTag.SKIP]). A trigger on an activation ([Event.ACTIVATED]) answers it at once instead, as a response.
 * - **Summons start no chain**: their triggers gather afterwards (`FxEngine.play`).
 *
 * Bounds (§7): a chain of at most [MOST_LINKS] links, at most [MOST_TRIGGERS] triggers waiting at once, a thousand actions
 * in one move ([FxScribe.MOST_ACTIONS]). A loop through triggers alone is the driver's to stop: each move is bounded.
 */
object FxChain {
    const val MOST_LINKS = 32
    const val MOST_TRIGGERS = 64

    /** The activations, passes and resolutions [seat] may make on [t] (already [FxTable.current]). */
    fun moves(t: FxTable, seat: Int): List<FxMove> = buildList {
        val s = t.state
        if (s.chain.isNotEmpty()) {
            if (t.fx.resolving) {
                if (t.fx.links.any { it.link == s.chain.size }) add(FxMove.Resolve)
                return@buildList
            }
            val pri = t.fx.priority
            if (pri != null && pri != seat) return@buildList
            addAll(activations(t, seat))
            add(FxMove.Pass)
        } else {
            addAll(activations(t, seat))
        }
    }

    /**
     * Who moves next on [t]: the seat with priority while a chain stands, the turn player otherwise (and while the chain
     * resolves). What a driver that plays both seats (the goldfish, a walk) asks before [FxEngine.moves].
     */
    fun next(t: FxTable): Int {
        val s = t.state
        if (s.solo) return s.active
        if (s.chain.isNotEmpty() && !t.fx.resolving) t.fx.priority?.let { return it }
        return s.active
    }

    /** Every effect [seat] may activate now, in the table's order. */
    fun activations(t: FxTable, seat: Int): List<FxMove.Activate> {
        val out = ArrayList<FxMove.Activate>()
        cardsOf(t, seat).forEach { uid ->
            val script = t.script(uid) ?: return@forEach
            script.effects.forEach { e ->
                if (e.kind == Kind.CONTINUOUS) return@forEach
                if (refusal(t, seat, uid, e.id) == null) out += FxMove.Activate(uid, e.id)
            }
        }
        return out
    }

    /** The cards [seat] might activate an effect of: its hand, its field, its GY and its face-up banished cards. */
    private fun cardsOf(t: FxTable, seat: Int): List<Int> {
        val s = t.state
        val side = s.seats[seat]
        val field = FxFilters.area(Area.FIELD, seat, s)
        return (side.hand + field + side.gy + side.banished).filter { t.book.has(t.inst(it)?.code ?: 0) }
    }

    /**
     * Why [seat] may not activate [uid]'s effect [effect] now, in words; null when it may. What [moves] lists and what the
     * table shows greyed beside a Shortcut ("once per turn: used").
     */
    fun refusal(t0: FxTable, seat: Int, uid: Int, effect: String): String? {
        val t = t0.current()
        val s = t.state
        val inst = t.inst(uid) ?: return "No such card."
        val script = t.script(uid) ?: return "The engine has no written effect for this card: use it by hand."
        val e = script.effect(effect) ?: return "It has no effect $effect."
        if (FxWalk.unread(e)) return "This effect is written in a newer build's words."
        if (e.kind == Kind.CONTINUOUS) return "A continuous effect is never activated: it applies while the card is face-up."
        val place = s.placeOf(uid) ?: return "That card has left the duel."
        if (FxFilters.controller(uid, s) != seat) return "Only its controller uses it."
        if (e.from.none { FxProcs.at(place, it, seat) }) return "Not from where it is: ${whereWords(e.from)}."
        val c = t.card(uid)
        when {
            place is Place.Zone && e.kind == Kind.ACTIVATION -> if (inst.faceUp) return "It is face-up already: a card is activated once."
            place is Place.Zone -> if (!inst.faceUp) return "A face-down card's effect is not used."
            place is Place.Pile && place.kind == PileKind.BANISHED -> if (!inst.faceUp) return "A face-down banished card's effect is not used."
            else -> {}
        }
        if (place is Place.Pile && place.kind == PileKind.HAND && e.kind == Kind.ACTIVATION) {
            if (c?.type == CardType.TRAP && Where.HAND !in e.from) return "A Trap is Set first, then activated."
            if (FxRules.spellZones(t, seat, uid).isEmpty()) return FxRules.NO_SPELL_ZONE
        }
        if (t.fx.resolving) return "The chain is resolving: nothing is added to it now."
        if (s.chain.size >= MOST_LINKS) return "A chain holds at most $MOST_LINKS links."
        val speed = FxRules.speed(e, c)
        val waiting = t.fx.pending.any { it.uid == uid && it.effect == effect }
        val mine = s.solo || s.active == seat
        if (e.kind == Kind.TRIGGER) {
            if (e.trigger?.on?.event == Event.ACTIVATED) {
                answers(t, seat, uid, e)?.let { return it }
            } else {
                if (!waiting) return "A trigger effect is used when its event happens: nothing set it off now" +
                    (if (t.fx.inferred) " (as far as a log made by hand tells)." else ".")
                if (s.chain.isNotEmpty()) return "It waits for the chain to be over, then goes on a new chain."
            }
        } else if (speed <= 1) {
            if (!mine) return FxRules.NOT_TURN
            if (!FxRules.main(s.phase)) return "Spell speed 1: only in your Main Phase."
            if (s.chain.isNotEmpty()) return "Spell speed 1 starts a chain, never answers one."
            if (t.fx.pending.isNotEmpty()) return "Triggers wait to go on a chain first."
        } else {
            if (s.chain.isEmpty()) {
                if (t.fx.pending.isNotEmpty()) return "Triggers wait to go on a chain first."
            } else {
                val pri = t.fx.priority
                if (pri != null && pri != seat) return "Your opponent may respond first."
                val top = t.fx.links.lastOrNull { it.link == s.chain.size }?.speed ?: 2
                if (speed < top) return "Spell speed $speed cannot answer spell speed $top."
            }
        }
        e.respond?.let { r -> respondRefusal(t, seat, uid, r)?.let { return it } }
        FxRules.setTurnRefusal(t, uid, e)?.let { return it }
        FxRules.quickPlayRefusal(t, seat, uid)?.let { return it }
        if (e.condition != null && !FxConds.holds(e.condition, FxScope(t, seat, uid))) return "Its condition does not hold now."
        FxRules.optRefusal(t, seat, uid, effect, e.opt)?.let { return it }
        FxRules.restricted(t, seat, Ban.ACTIVATE, uid)?.let { return FxRules.words(it) }
        val act = FxAct(seat, uid, t.code(uid) ?: 0, effect, FxTag.COST, link = s.chain.size + 1, bound = mapOf(Pick.SELF to listOf(uid)))
        if (!FxSteps.able(t, act, e.cost)) return "Its cost cannot be paid now."
        if (!FxSteps.ableTargets(t, act, e.targets)) return "It has no legal target now."
        e.does.firstOrNull()?.let { first -> if (!FxSteps.able(t, act, first.op)) return "It would do nothing now." }
        return null
    }

    /** Why a trigger on an activation does not answer the newest link now; null when it does. */
    private fun answers(t: FxTable, seat: Int, uid: Int, e: Effect): String? {
        val s = t.state
        val top = s.chain.lastOrNull() ?: return "It answers an activation: nothing is being activated."
        val tr = e.trigger ?: return "It has no event."
        val pri = t.fx.priority
        if (pri != null && pri != seat) return "Your opponent may respond first."
        if (tr.self && top.uid != uid) return "It answers its own activation."
        if (!tr.self && tr.about != null && (top.uid == null || !FxFilters.matches(tr.about, top.uid, FxScope(t, seat, uid)))) return "It does not answer that activation."
        return null
    }

    /** Why [r] is not met by the newest link; null when it is. */
    private fun respondRefusal(t: FxTable, seat: Int, uid: Int, r: Respond): String? {
        val s = t.state
        val top = s.chain.lastOrNull() ?: return "It answers an activation: there is none to answer."
        val ok = when (r.seat) {
            Rel.YOU -> top.seat == seat
            Rel.THEM -> top.seat != seat
            Rel.ANY -> true
        }
        if (!ok) return "It answers ${if (r.seat == Rel.THEM) "your opponent's" else "your own"} activations."
        if (r.about != null && (top.uid == null || !FxFilters.matches(r.about, top.uid, FxScope(t, seat, uid)))) return "It does not answer that card."
        if (r.includes.isNotEmpty()) {
            val link = t.fx.links.lastOrNull { it.link == s.chain.size }
            val effect = link?.takeIf { it.effect.isNotEmpty() }?.let { t.book.effect(it.card, it.effect) }
                ?: return "The engine cannot tell what that link includes."
            if (!FxWalk.includes(effect).containsAll(r.includes)) return "That activation does not include what it answers."
        }
        return null
    }

    private fun whereWords(from: Set<Where>): String = from.joinToString(" or ") {
        when (it) {
            Where.HAND -> "the hand"
            Where.DECK -> "the Deck"
            Where.EXTRA -> "the Extra Deck"
            Where.MONSTER_ZONE -> "a Monster Zone"
            Where.SPELL_ZONE -> "a Spell & Trap Zone"
            Where.FIELD_ZONE -> "the Field Zone"
            Where.GY -> "the GY"
            Where.BANISHED -> "banishment"
        }
    }

    /** An [FxMove.Activate], [FxMove.Pass] or [FxMove.Resolve] made. */
    fun play(t0: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay {
        val t = t0.current()
        return when (move) {
            is FxMove.Activate -> {
                refusal(t, seat, move.uid, move.effect)?.let { return FxPlay.Refused(it) }
                FxScribe.play(t, chooser, seat) { sc -> activate(sc, seat, move.uid, move.effect) }
            }
            FxMove.Pass -> {
                val s = t.state
                if (s.chain.isEmpty()) return FxPlay.Refused("There is no chain to pass on.")
                if (t.fx.resolving) return FxPlay.Refused("The chain is resolving: resolve its next link.")
                val pri = t.fx.priority
                if (pri != null && pri != seat) return FxPlay.Refused("It is not your priority: your opponent may respond first.")
                FxScribe.play(t, chooser, seat) { sc ->
                    sc.emit(DuelAction.Answer(seat, respond = false), rule(sc))
                    if (sc.t.fx.passes >= 2 || s.solo) resolve(sc)
                }
            }
            FxMove.Resolve -> {
                if (t.state.chain.isEmpty()) return FxPlay.Refused("There is no chain to resolve.")
                FxScribe.play(t, chooser, seat) { sc -> resolve(sc) }
            }
            else -> FxPlay.Refused("Not a chain move.")
        }
    }

    private fun rule(sc: FxScribe, uid: Int = 0): FxTag = FxTag(uid, FxTag.RULE, FxTag.RULE, memo = FxMemo(batch = sc.t.fx.batch + 1))

    /** [uid]'s [effect] activated by [seat] on the scribe's table: its card's move, costs, targets and link. */
    internal fun activate(sc: FxScribe, seat: Int, uid: Int, effect: String) {
        val t = sc.t
        val script = t.script(uid) ?: throw FxStop.refuse("No written effect.")
        val e = script.effect(effect) ?: throw FxStop.refuse("It has no effect $effect.")
        val code = t.code(uid) ?: 0
        val link = t.state.chain.size + 1
        val hash = t.book.hash(code)
        val act = FxAct(seat, uid, code, effect, FxTag.ACTIVATE, link, mapOf(Pick.SELF to listOf(uid)), hash, t.book.verifiedOnly)
        fun tag(part: String) = act.tag().copy(part = part, memo = FxMemo(batch = sc.t.fx.batch + 1))
        // The card's own move: a Spell or Trap from the hand to its zone, a set one turned face-up.
        if (e.kind == Kind.ACTIVATION) {
            when (val p = t.state.placeOf(uid)) {
                is Place.Pile -> if (p.kind == PileKind.HAND) {
                    val zones = FxRules.spellZones(sc.t, seat, uid)
                    if (zones.isEmpty()) throw FxStop.refuse(FxRules.NO_SPELL_ZONE)
                    val z = zones[sc.ask(Decision.Zone(zones, uid, listOf(CardPosition.FACE_UP_ATK), FxSource(uid, effect, e.label))).single()]
                    // A new Field Spell replaces the old.
                    if (z.kind == ZoneKind.FIELD) sc.t.state.at(z)?.let { old ->
                        sc.emit(DuelAction.Move(old, Place.Pile(sc.t.inst(old)?.owner ?: seat, PileKind.GY), how = "send"), tag(FxTag.ACTIVATE))
                    }
                    sc.emit(DuelAction.Move(uid, z, CardPosition.FACE_UP_ATK, "activate"), tag(FxTag.ACTIVATE))
                }
                is Place.Zone -> if (sc.t.inst(uid)?.faceUp == false) sc.emit(DuelAction.Position(uid, CardPosition.FACE_UP_ATK), tag(FxTag.ACTIVATE))
                else -> {}
            }
        }
        val paid = FxSteps.exec(sc, act.copy(part = FxTag.COST), e.cost)
        if (!paid.whole) throw FxStop.refuse("Its cost cannot be paid in full.")
        val aimed = FxSteps.targets(sc, act.copy(bound = paid.bound, declared = paid.declared), e.targets)
        val targets = aimed.bound[Pick.TARGETS].orEmpty()
        val bound = aimed.bound - Pick.SELF
        sc.emit(
            DuelAction.ChainAdd(seat, uid, note = e.label, targets = targets),
            act.tag().copy(memo = FxMemo(batch = sc.t.fx.batch + 1, bound = bound, declared = aimed.declared)),
        )
    }

    /** The newest link resolves on the scribe's table; with the last one, the triggers waiting form a new chain. */
    internal fun resolve(sc: FxScribe) {
        val t = sc.t
        val n = t.state.chain.size
        if (n == 0) throw FxStop.refuse("There is no chain to resolve.")
        val cl = t.state.chain.last()
        val fl = t.fx.links.lastOrNull { it.link == n }
            ?: throw FxStop.refuse("The engine did not see Chain Link $n activated: resolve it by hand.")
        if (fl.effect.isEmpty()) throw FxStop.refuse("The engine cannot tell which effect Chain Link $n is: resolve it by hand.")
        val e = t.book.effect(fl.card, fl.effect) ?: throw FxStop.refuse("Chain Link $n has no written effect: resolve it by hand.")
        if (FxWalk.unread(e)) throw FxStop.refuse("Chain Link $n's effect is written in a newer build's words.")
        val act = FxAct(fl.seat, fl.uid, fl.card, fl.effect, FxTag.RESOLVE, n, fl.bound, fl.script, fl.verified, fl.declared)
        if (!cl.negated && !fl.effectNegated) {
            val bound = recheck(sc, act, fl, e)
            val r = FxSteps.exec(sc, act.copy(bound = bound), e.does)
            // What it leaves behind, once it has resolved.
            if (e.leaves.isNotEmpty()) FxSteps.exec(sc, act.copy(bound = r.bound, declared = r.declared), e.leaves.map { Step(Op.Restrict(it), Join.ALSO) })
        }
        DuelVerbs.resolve(sc.t.state, sc.t.facts.catalog()).forEach { a ->
            val tag = if (a == DuelAction.ChainResolve) act.tag().copy(memo = FxMemo(batch = sc.t.fx.batch + 1))
            else rule(sc, (a as? DuelAction.Move)?.uid ?: 0)
            sc.emit(a, tag)
        }
        if (sc.t.state.chain.isEmpty()) segoc(sc)
    }

    /** [fl]'s targets checked again as it resolves: each that moved since, or no longer matches its pick, is dropped and said. */
    private fun recheck(sc: FxScribe, act: FxAct, fl: FxLink, e: Effect): Map<String, List<Int>> {
        if (e.targets.isEmpty()) return fl.bound
        val t = sc.t
        val bound = fl.bound.toMutableMap()
        val scope = act.scope(t)
        val gone = LinkedHashSet<Int>()
        e.targets.forEachIndexed { i, p ->
            val mine = bound[FxSteps.targetKey(i)].orEmpty()
            val legal = FxFilters.candidates(p.copy(ref = null, bind = null), scope).toSet()
            mine.forEach { u ->
                val moved = t.inst(u) == null || fl.lives[u] != null && fl.lives[u] != t.fx.life(u)
                if (moved || u !in legal) gone += u
            }
        }
        if (gone.isEmpty()) return fl.bound
        val keep = bound.mapValues { (k, v) -> if (k == Pick.TARGETS || k.startsWith("${Pick.TARGETS}#") || e.targets.any { it.bind == k }) v - gone else v }
        val words = if (gone.size == 1) "a target is" else "${gone.size} targets are"
        sc.emit(
            DuelAction.Note("Chain Link ${fl.link}: $words no longer there or no longer fits, and dropped.", fl.seat),
            act.tag().copy(memo = FxMemo(batch = sc.t.fx.batch)),
        )
        return keep
    }

    /**
     * [events] happened on [t] (a summon, a batch, a resolution): the triggers they set off, put in [FxState.pending] to
     * wait for the chain or action to end. A trigger fits when its event, where it came from, its cause and its kind of
     * summon match, it is this card's own event (or one its filter matches), and the card is where the effect is used from
     * now. A card waits once for each effect; at most [MOST_TRIGGERS] wait.
     */
    fun gather(t: FxTable, events: List<FxEvent>): FxState {
        if (events.isEmpty() || t.book.triggers.isEmpty()) return t.fx
        val s = t.state
        val watchers = s.cards.values.filter { !it.token && t.book.canonical(it.code) in t.book.triggers }.map { it.uid }.sorted()
        if (watchers.isEmpty()) return t.fx
        val pending = ArrayList(t.fx.pending)
        for (ev in events) {
            for (uid in watchers) {
                val script = t.script(uid) ?: continue
                val place = s.placeOf(uid) ?: continue
                val seat = FxFilters.controller(uid, s) ?: continue
                for (e in script.effects) {
                    if (e.kind != Kind.TRIGGER || FxWalk.unread(e)) continue
                    val tr = e.trigger ?: continue
                    if (tr.on.event == Event.ACTIVATED || !fits(tr.on, ev)) continue
                    if (ev.uid != 0) {
                        if (tr.self && ev.uid != uid) continue
                        if (!tr.self && tr.about != null && !FxFilters.matches(tr.about, ev.uid, FxScope(t, seat, uid))) continue
                    }
                    if (e.from.none { FxProcs.at(place, it, seat) }) continue
                    if (pending.any { it.uid == uid && it.effect == e.id }) continue
                    if (pending.size >= MOST_TRIGGERS) return t.fx.copy(pending = pending)
                    pending += Pending(uid, t.code(uid) ?: 0, e.id, seat, mandatory = !tr.optional, event = ev, last = true)
                }
            }
        }
        return if (pending.size == t.fx.pending.size) t.fx else t.fx.copy(pending = pending)
    }

    /** Whether the event [ev] is the one [on] waits for. */
    private fun fits(on: On, ev: FxEvent): Boolean {
        if (on.event != ev.event) return false
        if (on.cause != null && on.cause != ev.cause) return false
        if (on.from != null && !whereIs(ev.from, on.from)) return false
        if (on.summon.isNotEmpty()) {
            val k = ev.summon ?: return false
            if (on.summon.none { it == k || (it == ProcKind.NORMAL && k == ProcKind.TRIBUTE) || (it == ProcKind.SPECIAL && k != ProcKind.NORMAL && k != ProcKind.TRIBUTE && k != ProcKind.FLIP) }) return false
        }
        return true
    }

    /** Whether [p] is a [where], whoever's side. */
    private fun whereIs(p: Place?, where: Where): Boolean = when (p) {
        is Place.Pile -> FxProcs.at(p, where, p.seat)
        is Place.Zone -> FxProcs.at(p, where, p.seat)
        is Place.Under -> false
        else -> false
    }

    /**
     * The triggers waiting form a new chain (SEGOC, D.md §2.3), with the chain empty: the turn player's mandatory triggers,
     * their optional ones, then the other player's mandatory and optional ones; each optional one asked yes or no, each
     * player ordering their own. One that missed the timing, cannot be used now, or is declined is let go with a note.
     */
    internal fun segoc(sc: FxScribe) {
        if (sc.t.state.chain.isNotEmpty() || sc.t.fx.pending.isEmpty()) return
        // Optional WHEN triggers whose event was not last miss the timing.
        sc.t.fx.pending.forEach { p ->
            if (!p.mandatory && !p.last && timing(sc.t, p) == Timing.WHEN) skip(sc, p, "missed the timing")
        }
        val tp = sc.t.state.active
        for ((seat, mandatory) in listOf(tp to true, tp to false, 1 - tp to true, 1 - tp to false)) {
            var group = sc.t.fx.pending.filter { it.seat == seat && it.mandatory == mandatory }
            if (group.isEmpty()) continue
            group = group.filter { p ->
                val why = usable(sc.t, p)
                if (why != null) skip(sc, p, why)
                why == null
            }
            if (!mandatory) group = group.filter { p ->
                val e = sc.t.book.effect(p.card, p.effect)
                val yes = sc.ask(Decision.YesNo("${whose(sc, seat)}use ${label(sc.t, p)}?", FxSource(p.uid, p.effect, e?.label.orEmpty()))).single() == 1
                if (!yes) skip(sc, p, "not used")
                yes
            }
            val ordered = if (group.size > 1) sc.ask(Decision.Order(group, group.map { label(sc.t, it) })).map { group[it] } else group
            ordered.forEach { p ->
                val why = usable(sc.t, p)
                if (why != null) skip(sc, p, why) else activate(sc, p.seat, p.uid, p.effect)
            }
        }
    }

    private fun whose(sc: FxScribe, seat: Int): String = if (seat == sc.t.state.active) "" else "Your opponent's trigger: "

    private fun timing(t: FxTable, p: Pending): Timing? = t.book.effect(p.card, p.effect)?.trigger?.timing

    /** A waiting trigger's card and effect, in words: "Example Scout's Search". */
    private fun label(t: FxTable, p: Pending): String {
        val name = t.card(p.uid)?.name ?: "a card"
        val e = t.book.effect(p.card, p.effect)
        return "$name's " + (e?.label?.takeIf { it.isNotBlank() } ?: "effect")
    }

    /** Why the waiting trigger [p] cannot go on the chain now; null when it can. */
    private fun usable(t: FxTable, p: Pending): String? {
        val inst = t.inst(p.uid) ?: return "it left the duel"
        val e = t.script(p.uid)?.effect(p.effect) ?: return "it has no such effect"
        val place = t.state.placeOf(p.uid) ?: return "it left the duel"
        if (e.from.none { FxProcs.at(place, it, p.seat) }) return "it is no longer where it is used from"
        if (place is Place.Zone && !inst.faceUp) return "it is face-down"
        if (t.state.chain.size >= MOST_LINKS) return "the chain is full"
        if (e.condition != null && !FxConds.holds(e.condition, FxScope(t, p.seat, p.uid))) return "its condition does not hold"
        FxRules.optRefusal(t, p.seat, p.uid, p.effect, e.opt)?.let { return "once per turn: used" }
        FxRules.restricted(t, p.seat, Ban.ACTIVATE, p.uid)?.let { return "a restriction forbids it" }
        val act = FxAct(p.seat, p.uid, p.card, p.effect, FxTag.COST, link = t.state.chain.size + 1, bound = mapOf(Pick.SELF to listOf(p.uid)))
        if (!FxSteps.able(t, act, e.cost)) return "its cost cannot be paid"
        if (!FxSteps.ableTargets(t, act, e.targets)) return "it has no legal target"
        return null
    }

    /** [p] let go, said in the log; a card hidden from the other seat now is not named. */
    private fun skip(sc: FxScribe, p: Pending, why: String) {
        val t = sc.t
        val hidden = t.state.placeOf(p.uid).let { it is Place.Pile && (it.kind == PileKind.HAND || it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) } ||
            t.inst(p.uid)?.faceUp == false
        val what = if (hidden) "A card's trigger" else label(t, p)
        sc.emit(
            DuelAction.Note("$what: $why.", p.seat),
            FxTag(p.uid, p.effect, FxTag.SKIP, script = t.book.hash(p.card), verified = t.book.verifiedOnly, memo = FxMemo(batch = t.fx.batch)),
        )
    }

    /**
     * A summon, a procedure or a phase made by `FxSummons` ([p]), carried on: each of its entries given its batch and
     * folded again ([FxFold], so the engine's state is the log's), then the triggers it set off put on a chain.
     */
    internal fun after(t: FxTable, seat: Int, p: FxPlay.Done, chooser: Chooser): FxPlay {
        val given = p.tags.mapNotNull { it.memo?.batch }
        val next = (given.maxOrNull() ?: t.fx.batch) + 1
        val tags = p.tags.map { if (it.memo?.batch != null) it else it.copy(memo = (it.memo ?: FxMemo()).copy(batch = next)) }
        val sc = FxScribe(t, chooser, seat)
        return try {
            p.actions.forEachIndexed { i, a -> sc.emit(a, tags[i]) }
            val own = sc.events.size
            segoc(sc)
            sc.done().copy(events = p.events + sc.events.drop(own))
        } catch (s: FxStop) {
            if (s.why == null) FxPlay.Cancelled else FxPlay.Refused(s.why)
        }
    }
}
