package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * [FxState] as a fold over a duel's log (D.md §2.1, §4.2): **the one place `FxState` is written**. The engine computes its
 * own state by folding every entry it makes through [step] as it makes it, so a tagged log folds to exactly the engine's
 * state (`FxFoldTest`) by construction; agent (a)'s summons are folded again the same way by `FxEngine.play`.
 *
 * **A tagged entry** (`DuelEntry.fx`) is read exactly:
 * - a `rule` Normal Summon or Set from the hand counts its Normal Summon (a "1 more" grant used when the turn's own is
 *   spent, [FxRules.normalSlot]); a monster whose Level needs Tributes is a Tribute Summon;
 * - a `proc` summon is proper, of its frame's kind from the Extra Deck or inherent from elsewhere, and an inherent
 *   summon's once-per-turn rule is used;
 * - an effect's summon reads its `how` word ("special", "fusion", "synchro", "xyz", "link", "ritual"), and is proper
 *   when it is a summon by procedure or a Main Deck monster with no rule of its own;
 * - an activation's use is counted on its `ChainAdd` by its effect's [Opt] key ([FxRules.optKey]), and its link (effect,
 *   speed, bindings from [FxMemo.bound], the bound cards' instances) is kept beside `DuelState.chain`;
 * - a pass (`Answer` false) hands priority over, a `ChainResolve` takes its link off, a `Negate` of the effect only
 *   marks its link;
 * - restrictions come from their `Lock`'s memo, Level changes, grants and skipped triggers from their notes;
 * - chance is counted ([FxState.rolls]) and batches kept ([FxState.batch]).
 *
 * **A log made by hand** has no tags and is inferred, marking the state [FxState.inferred]: Normal Summons from `how`
 * "normal" or "set" from the hand; this turn's summons from `how` (an untagged "special" from the Extra Deck as its frame's
 * summon); a Flip Summon from a face-down monster turned face-up in Attack Position; a `ChainAdd` of a card with one
 * effect that fits where it is (or the trigger waiting for it) as that effect, its use counted; any other link as an
 * unknown effect. Each hand-made move is a batch of its own; a move with nothing on the chain lets waiting triggers go,
 * as a player who moves on lets them go. The table's own entries (`seat` null: the deal, a test's layout) change only
 * where cards are.
 *
 * **Every move bumps its card's instance** ([FxState.moved]): a `Move` (and the card it covers, laid beneath it), each
 * card drawn and each card picked. A card stays properly summoned in the GY or banished, and stops being so in the hand,
 * the Deck or the Extra Deck.
 *
 * **Events** ([FxEvent]) are read off each entry — what moved where, how, and for what (a cost, a material, a Tribute, an
 * effect) — and set off the triggers waiting for them ([FxChain.gather]). Anything that happens in a later batch makes
 * every waiting trigger's event no longer last.
 */
object FxFold {
    /** The state after [entries] on [header]'s deal, for the table [at] (its turn): entries the table refuses are skipped. */
    fun fold(header: DuelHeader, entries: List<DuelEntry>, book: ScriptBook, facts: FxFacts, at: DuelState): FxState {
        var s = DuelSetup.initial(header)
        var fx = FxState.at(s)
        for (e in entries) {
            val o = DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok ?: continue
            fx = step(fx, s, e, o.state, book, facts)
            s = o.state
        }
        return fx.forTurn(at.turn)
    }

    /** [fx] after one more [entry], the table [before] it and [after] it. */
    fun step(fx: FxState, before: DuelState, entry: DuelEntry, after: DuelState, book: ScriptBook, facts: FxFacts): FxState =
        read(fx, before, entry, after, book, facts).fx

    /** What one entry came to: the state after it and the events it was. */
    internal class Read(val fx: FxState, val events: List<FxEvent>)

    internal fun read(fx0: FxState, before: DuelState, e: DuelEntry, after: DuelState, book: ScriptBook, facts: FxFacts): Read {
        var fx = fx0.forTurn(before.turn)
        val a = e.action
        val tag = e.fx
        val memo = tag?.memo
        if (DuelRandom.rolls(a)) fx = fx.copy(rolls = fx.rolls + 1)
        val talk = DuelGame.isTalk(a)
        val setup = tag == null && e.seat == null
        val hand = tag == null && !setup
        if (hand && !talk) fx = fx.copy(inferred = true)
        // The batch it happened in: the engine's, or one of its own.
        val batch = memo?.batch ?: (fx.batch + 1)
        if (!talk) fx = fx.copy(batch = maxOf(fx.batch, batch))
        // A player who moves on by hand with nothing on the chain lets waiting triggers go.
        if (hand && fx.pending.isNotEmpty() && before.chain.isEmpty() && moves(a) && !(a is DuelAction.ChainAdd && fx.pending.any { it.uid == a.uid })) {
            fx = fx.copy(pending = emptyList())
        }
        val r = Reader(fx, before, after, e, book, facts, batch, setup)
        r.read()
        fx = r.fx
        // Anything that happens in a later batch: a waiting trigger's event is no longer last.
        if (!setup && happening(a, tag) && fx.pending.any { it.last && it.event.batch < batch }) {
            fx = fx.copy(pending = fx.pending.map { if (it.last && it.event.batch < batch) it.copy(last = false) else it })
        }
        // A link resolved, whatever it did: what happened before its resolution began is no longer last.
        if (!setup && a == DuelAction.ChainResolve && fx.pending.any { it.last && it.event.batch <= fx.since }) {
            fx = fx.copy(pending = fx.pending.map { if (it.last && it.event.batch <= fx.since) it.copy(last = false) else it })
        }
        if (a is DuelAction.ChainAdd || a == DuelAction.ChainResolve) fx = fx.copy(since = fx.batch)
        val events = r.events
        if (events.isNotEmpty()) fx = FxChain.gather(FxTable(after, fx.forTurn(after.turn), book, facts), events)
        return Read(fx.forTurn(after.turn), events)
    }

    /** A move that changes the table, by which a player moves on. */
    private fun moves(a: DuelAction): Boolean = a is DuelAction.Move || a is DuelAction.Draw || a is DuelAction.Phase ||
        a is DuelAction.EndTurn || a is DuelAction.Token || a is DuelAction.Position || a is DuelAction.ChainAdd || a is DuelAction.Pick

    /**
     * Whether [a] is something happening, for timing: a card moved, drawn, turned or made, life points, counters, a
     * negation. The chain's own bookkeeping, talk, locks, reveals and shuffles are not, nor the chain's own Spells and Traps
     * going to the GY once it is over.
     */
    internal fun happening(a: DuelAction, tag: FxTag?): Boolean = when (a) {
        is DuelAction.Move -> !(a.how == HOW_RESOLVE && (tag == null || tag.part == FxTag.RULE))
        is DuelAction.Draw, is DuelAction.Lp, is DuelAction.Token, is DuelAction.Position, is DuelAction.Counter,
        is DuelAction.Pick, is DuelAction.Negate -> true
        else -> false
    }

    const val HOW_RESOLVE = "resolve"

    /** The words of `how` that name a summon an effect made, and the kind each is. */
    private val SUMMON_WORDS = mapOf(
        "fusion" to ProcKind.FUSION, "synchro" to ProcKind.SYNCHRO, "xyz" to ProcKind.XYZ, "link" to ProcKind.LINK,
        "ritual" to ProcKind.RITUAL, "pendulum" to ProcKind.PENDULUM,
    )

    /** One entry read: [fx] as it goes, and [events]. */
    private class Reader(
        var fx: FxState,
        val before: DuelState,
        val after: DuelState,
        val e: DuelEntry,
        val book: ScriptBook,
        val facts: FxFacts,
        val batch: Int,
        val setup: Boolean,
    ) {
        val events = ArrayList<FxEvent>()
        val tag = e.fx
        val memo = tag?.memo
        val hand = tag == null && !setup

        fun table(): FxTable = FxTable(before, fx, book, facts)

        fun read() {
            when (val a = e.action) {
                is DuelAction.Move -> move(a.uid, a.to, a.how, a.over)
                is DuelAction.Draw -> before.seats.getOrNull(a.seat)?.deck?.take(a.n)?.forEach { u ->
                    fx = fx.moved(u, Place.Pile(a.seat, PileKind.HAND))
                    event(Event.DRAWN, u, a.seat, before.placeOf(u), Place.Pile(a.seat, PileKind.HAND), causeOf(null))
                }
                is DuelAction.Pick -> {
                    // One at a time, each from the table the move before it left.
                    var s = before
                    for (u in DuelRules.picked(before, a)) {
                        val o = DuelRules.apply(s, DuelAction.Move(u, a.to, a.pos, a.how)) as? Outcome.Ok ?: break
                        move(u, a.to, a.how, false, s, o.state)
                        s = o.state
                    }
                }
                is DuelAction.Token -> token(a)
                is DuelAction.Phase -> when (a.phase) {
                    DuelPhase.STANDBY -> event(Event.STANDBY, 0, before.active, null, null, null)
                    DuelPhase.END -> event(Event.END_PHASE, 0, before.active, null, null, null)
                    else -> {}
                }
                is DuelAction.Position -> flip(a)
                is DuelAction.ChainAdd -> chainAdd(a)
                DuelAction.ChainResolve -> {
                    val top = before.chain.size
                    fx = fx.copy(
                        links = fx.links.filter { it.link != top },
                        passes = 0,
                        priority = null,
                        resolving = after.chain.isNotEmpty(),
                    ).settled { it == top }
                }
                DuelAction.ChainClear -> fx = fx.copy(links = emptyList(), passes = 0, priority = null, resolving = false).settled { true }
                is DuelAction.Negate -> if (memo?.effectOnly == true) {
                    fx = fx.copy(links = fx.links.map { if (it.link == a.link) it.copy(effectNegated = true) else it })
                } else {
                    // A negated activation is as if it was never activated (YGOrg, Demystifying Rulings Part 10; OCG FAQ on
                    // Rage with Eyes of Blue): "you can only activate" is given back, its "the turn you activate this"
                    // conditions are lifted, and it is no deed of the turn. "Use" wording stays counted.
                    fx = fx.copy(
                        uses = fx.uses.filterNot { it.link == a.link && it.refunds },
                        restrictions = fx.restrictions.filterNot { it.link == a.link },
                        deeds = fx.deeds.filterNot { it.link == a.link },
                    )
                }
                is DuelAction.Unlock -> fx = fx.copy(restrictions = fx.restrictions.filterNot { it.lock == a.id })
                is DuelAction.Answer -> if (!a.respond && before.chain.isNotEmpty() && !fx.resolving) {
                    fx = fx.copy(passes = fx.passes + 1, priority = if (before.solo) a.seat else 1 - a.seat)
                }
                is DuelAction.Lock -> memo?.restriction?.let { r ->
                    val id = a.id ?: after.locks.maxOfOrNull { it.id }
                    val link = tag?.link?.takeIf { tag.part == FxTag.ACTIVATE }
                    fx = fx.copy(restrictions = fx.restrictions + InForce(r, a.seat, tag?.uid ?: 0, before.turn, lock = id, link = link))
                }
                is DuelAction.Note -> if (tag != null) note(a)
                else -> {}
            }
        }

        private fun event(event: Event, uid: Int, seat: Int, from: Place?, to: Place?, cause: Cause?, summon: ProcKind? = null) {
            if (setup) return
            events += FxEvent(event, uid, seat, from, to, cause, summon, tag?.uid, batch)
        }

        /** Why a card moved: paid as a cost, a material, a Tribute, or an effect's doing. */
        private fun causeOf(how: String?): Cause? = when {
            tag?.part == FxTag.COST -> Cause.COST
            how == FxProcs.HOW_MATERIAL -> Cause.MATERIAL
            how == FxSummons.HOW_TRIBUTE -> Cause.TRIBUTE
            tag?.part == FxTag.RESOLVE -> Cause.EFFECT
            tag == null && how == "activate" -> Cause.COST // a monster's own cost from the hand, moved by hand
            else -> null
        }

        /** The summon a material went to: its memo's, else a procedure's frame. */
        private fun materialKind(): ProcKind? = memo?.summon ?: tag?.takeIf { it.part == FxTag.PROC }?.let { t ->
            before.cards[t.uid]?.let { facts.of(it) }?.frameProc
        }

        fun move(uid: Int, to: Place, how: String?, over: Boolean, b: DuelState = before, a: DuelState = after) {
            val inst = b.cards[uid] ?: return
            val from = b.placeOf(uid)
            val dest = a.placeOf(uid)
            val seat = FxFilters.controllerAt(uid, from, b) ?: inst.owner
            val t = FxTable(b, fx, book, facts)
            val card = t.card(uid)
            val part = tag?.part
            val fromHand = from is Place.Pile && from.kind == PileKind.HAND
            val toMonster = dest is Place.Zone && (dest.kind == ZoneKind.MONSTER || dest.kind == ZoneKind.EMZ)
            var kind: ProcKind? = null
            val normalish = (tag == null || (tag.effect == FxTag.RULE && part == FxTag.RULE)) && fromHand && toMonster &&
                (how == FxSummons.HOW_NORMAL || how == FxSummons.HOW_SET)
            if (normalish && !setup) {
                val zone = dest as Place.Zone
                fx = fx.copy(deeds = fx.deeds + Deed(zone.seat, uid, Ban.NORMAL_SUMMON))
                val slot = FxRules.normalSlot(t, zone.seat, uid)
                fx = fx.copy(normals = fx.normals + (zone.seat to fx.normalsUsed(zone.seat) + 1))
                if (slot != null && slot != FxRules.OWN) fx = fx.copy(grants = fx.grants.mapIndexed { i, g -> if (i == slot) g.copy(used = true) else g })
                if (how == FxSummons.HOW_NORMAL) {
                    val need = card?.level?.let { FxRules.tributes(it, t.script(uid)?.summon) } ?: 0
                    kind = if (need > 0) ProcKind.TRIBUTE else ProcKind.NORMAL
                }
            } else if (toMonster && from !is Place.Zone && !setup) {
                kind = SUMMON_WORDS[how] ?: if (how == FxProcs.HOW_SUMMON) when {
                    part == FxTag.PROC -> if (from is Place.Pile && from.kind == PileKind.EXTRA) card?.frameProc ?: ProcKind.SPECIAL else ProcKind.INHERENT
                    tag == null && from is Place.Pile && from.kind == PileKind.EXTRA -> card?.frameProc ?: ProcKind.SPECIAL
                    else -> ProcKind.SPECIAL
                } else null
            }
            val zoneSeat = (dest as? Place.Zone)?.seat ?: seat
            if (kind != null) {
                if (kind == ProcKind.INHERENT && part == FxTag.PROC) {
                    val proc = t.script(uid)?.summon?.procs?.filterIsInstance<Proc.Inherent>()?.firstOrNull { FxProcs.at(from, it.from, zoneSeat) }
                    if (proc != null) fx = FxRules.use(t.copy(fx = fx), zoneSeat, uid, FxTag.PROC, proc.opt)
                }
                fx = fx.copy(summoned = fx.summoned + (uid to kind))
                if (kind != ProcKind.NORMAL && kind != ProcKind.TRIBUTE) {
                    t.code(uid)?.let { code -> fx = fx.copy(specials = fx.specials + (zoneSeat to (fx.specials[zoneSeat].orEmpty() + code))) }
                    val extra = from is Place.Pile && from.kind == PileKind.EXTRA
                    fx = fx.copy(deeds = fx.deeds + Deed(zoneSeat, uid, Ban.SPECIAL_SUMMON, extra))
                }
            }
            // Laid on top of a card: it goes beneath, a new instance.
            val covered = if (over && to is Place.Zone) b.at(to)?.takeIf { it != uid } else null
            if (covered != null) {
                fx = fx.moved(covered, Place.Under(uid))
                val cs = FxFilters.controller(covered, b) ?: seat
                event(Event.MATERIAL, covered, cs, to, Place.Under(uid), Cause.MATERIAL, materialKind() ?: ProcKind.XYZ)
                event(Event.LEFT_FIELD, covered, cs, to, Place.Under(uid), Cause.MATERIAL, materialKind() ?: ProcKind.XYZ)
            }
            fx = fx.moved(uid, dest ?: Place.Void)
            if (kind != null && kind != ProcKind.NORMAL && kind != ProcKind.TRIBUTE) {
                val proper = when (kind) {
                    ProcKind.SPECIAL -> card?.frameProc == null && t.script(uid)?.summon?.mustFirstBe == null
                    else -> true
                }
                if (proper || part == FxTag.PROC) fx = fx.copy(proper = fx.proper + uid)
            }
            val gy = dest is Place.Pile && dest.kind == PileKind.GY
            if (!setup) {
                if ((part == FxTag.RULE && how == FxSummons.HOW_TRIBUTE) || gy) fx = fx.copy(sent = fx.sent + uid)
                if (how == FxSummons.HOW_SET) fx = fx.copy(setCards = fx.setCards + uid)
            }
            // What it was, for the triggers.
            if (setup || (how == HOW_RESOLVE && (tag == null || part == FxTag.RULE))) return
            // A token that left the field is gone: its events are as if it went where it was sent.
            val where = dest ?: (to as? Place.Pile)?.copy(seat = inst.owner)
            val cause = causeOf(how)
            if (from is Place.Zone && where !is Place.Zone) event(Event.LEFT_FIELD, uid, seat, from, where, cause)
            if (how == FxProcs.HOW_MATERIAL) event(Event.MATERIAL, uid, seat, from, where, Cause.MATERIAL, materialKind())
            when {
                where is Place.Pile && where.kind == PileKind.GY -> {
                    event(Event.SENT_TO_GY, uid, seat, from, where, cause, if (how == FxProcs.HOW_MATERIAL) materialKind() else null)
                    if (how == HOW_DESTROY) event(Event.DESTROYED, uid, seat, from, where, cause)
                    if (how == HOW_DISCARD && fromHand) event(Event.DISCARDED, uid, seat, from, where, cause)
                }
                where is Place.Pile && where.kind == PileKind.BANISHED -> {
                    event(Event.BANISHED, uid, seat, from, where, cause)
                    if (how == HOW_DESTROY) event(Event.DESTROYED, uid, seat, from, where, cause)
                }
                where is Place.Pile && where.kind == PileKind.EXTRA -> if (how == HOW_DESTROY) event(Event.DESTROYED, uid, seat, from, where, cause)
                where is Place.Pile && where.kind == PileKind.HAND -> if (!fromHand) event(Event.ADDED_TO_HAND, uid, seat, from, where, cause)
                else -> {}
            }
            if (from is Place.Under && how == HOW_DETACH) event(Event.DETACHED, uid, seat, from, where, cause)
            if (kind != null) {
                event(Event.SUMMONED, uid, zoneSeat, from, dest, null, kind)
                event(if (kind == ProcKind.NORMAL || kind == ProcKind.TRIBUTE) Event.NORMAL_SUMMONED else Event.SPECIAL_SUMMONED, uid, zoneSeat, from, dest, null, kind)
            }
        }

        private fun token(a: DuelAction.Token) {
            val uid = a.uid ?: before.nextUid
            if (after.cards[uid] == null) return
            memo?.token?.let { k ->
                val c = FxCard(
                    code = a.code, name = a.name, type = CardType.MONSTER, frames = setOf(CardFrame.TOKEN, CardFrame.NORMAL),
                    level = k.level, attribute = k.attribute, race = k.race, atk = a.atk, def = a.def,
                )
                fx = fx.copy(tokens = fx.tokens + (uid to c))
            }
            if (setup) return
            fx = fx.copy(summoned = fx.summoned + (uid to ProcKind.SPECIAL), deeds = fx.deeds + Deed(a.to.seat, uid, Ban.SPECIAL_SUMMON))
            event(Event.SUMMONED, uid, a.to.seat, null, a.to, null, ProcKind.SPECIAL)
            event(Event.SPECIAL_SUMMONED, uid, a.to.seat, null, a.to, null, ProcKind.SPECIAL)
        }

        /** A face-down monster turned face-up: flipped; by hand in Attack Position, a Flip Summon. */
        private fun flip(a: DuelAction.Position) {
            val inst = before.cards[a.uid] ?: return
            val at = before.placeOf(a.uid) as? Place.Zone ?: return
            if (setup || inst.faceUp || !a.pos.faceUp) return
            if (at.kind != ZoneKind.MONSTER && at.kind != ZoneKind.EMZ) return
            if (hand && a.pos == CardPosition.FACE_UP_ATK) fx = fx.copy(summoned = fx.summoned + (a.uid to ProcKind.FLIP))
            event(Event.FLIPPED, a.uid, at.seat, at, at, if (tag?.part == FxTag.RESOLVE) Cause.EFFECT else null)
        }

        private fun chainAdd(a: DuelAction.ChainAdd) {
            if (setup) return
            val link = after.chain.size
            val uid = a.uid
            val t = table()
            val code = uid?.let { t.code(it) } ?: 0
            val effect: String = when {
                uid == null -> ""
                tag != null -> tag.effect
                else -> infer(t, uid)
            }
            if (uid != null && effect.isNotEmpty()) fx = fx.copy(pending = fx.pending.filterNot { it.uid == uid && it.effect == effect })
            val e = if (uid != null && effect.isNotEmpty()) t.script(uid)?.effect(effect) else null
            if (e != null && uid != null) fx = FxRules.use(t.copy(fx = fx), a.seat, uid, effect, e.opt, link)
            if (uid != null) {
                fx = fx.copy(deeds = fx.deeds + Deed(a.seat, uid, Ban.ACTIVATE, link = link))
                // An activation is an event too: a Trigger Effect it sets off waits for the chain (spell speed 1 never answers).
                val at = after.placeOf(uid)
                event(Event.ACTIVATED, uid, a.seat, at, at, null)
            }
            val bound = memo?.bound ?: if (a.targets.isNotEmpty()) mapOf(Pick.TARGETS to a.targets) else emptyMap()
            val lives = bound.values.flatten().distinct().associateWith { fx.life(it) }
            val l = FxLink(
                link = link, seat = a.seat, uid = uid ?: 0, card = code, effect = effect,
                speed = e?.let { FxRules.speed(it, uid?.let(t::card)) } ?: 2,
                bound = bound, script = tag?.script ?: "", verified = tag?.verified ?: false, lives = lives,
                declared = memo?.declared.orEmpty(),
            )
            fx = fx.copy(
                links = fx.links.filter { it.link != link } + l,
                priority = if (before.solo) a.seat else 1 - a.seat,
                passes = 0,
                resolving = false,
            )
        }

        /** Which effect a link made by hand is: the trigger waiting for it, or its one effect that fits where it is; else unknown. */
        private fun infer(t: FxTable, uid: Int): String {
            fx.pending.firstOrNull { it.uid == uid }?.let { return it.effect }
            val script = t.script(uid) ?: return ""
            val p = after.placeOf(uid)
            val seat = FxFilters.controller(uid, after) ?: return ""
            val fits = script.effects.filter { e ->
                e.kind != Kind.CONTINUOUS && e.kind != Kind.TRIGGER && !t.book.unread(script.card, e.id) &&
                    (e.from.any { FxProcs.at(p, it, seat) } || (e.kind == Kind.ACTIVATION && Where.HAND in e.from && p is Place.Zone))
            }
            return fits.singleOrNull()?.id ?: ""
        }

        private fun note(a: DuelAction.Note) {
            val tg = tag ?: return
            if (tg.part == FxTag.SKIP) {
                fx = fx.copy(pending = fx.pending.filterNot { it.uid == tg.uid && it.effect == tg.effect })
                return
            }
            memo?.level?.let { fx = fx.copy(levels = fx.levels + it) }
            memo?.grant?.let { f -> fx = fx.copy(grants = fx.grants + NormalGrant(a.seat ?: 0, tg.uid, f)) }
        }
    }

    /** [this] with every use, condition and deed that [gone] chain links counted no longer tied to a link: they resolved. */
    private fun FxState.settled(gone: (Int) -> Boolean): FxState = copy(
        uses = uses.map { if (it.link != null && gone(it.link)) it.copy(link = null) else it },
        restrictions = restrictions.map { if (it.link != null && gone(it.link)) it.copy(link = null) else it },
        deeds = deeds.map { if (it.link != null && gone(it.link)) it.copy(link = null) else it },
    )

    const val HOW_DESTROY = "destroy"
    const val HOW_DISCARD = "discard"
    const val HOW_DETACH = "detach"
}
