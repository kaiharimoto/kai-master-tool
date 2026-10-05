package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * The engine's summons and phases (agent (a)): Normal Summons and Sets with their Tributes, the summoning procedures
 * ([FxProcs]) and moving the phase on — listed by [moves], made by [play] as ordinary tagged `DuelAction`s. Summons start
 * no chain; their events are handed back for the triggers (`FxChain.gather`).
 *
 * **Only cards the engine knows** are summoned: a card with a script in the book, or a Normal Monster (no effects to
 * know). A card with neither is played by hand at the table and is inert to the goldfish (D.md §5.5).
 *
 * Every choice is the chooser's — Tributes, materials, a zone, a position — asked only when there is more than one
 * answer: one legal answer is no guess.
 */
object FxSummons {
    /** Whether the engine knows [uid]: a script, or a Normal Monster's facts. */
    fun known(t: FxTable, uid: Int): Boolean = t.script(uid) != null || t.card(uid)?.normal == true

    fun moves(t: FxTable, seat: Int): List<FxMove> = buildList {
        val s = t.state
        val mine = s.active == seat || s.solo
        if (mine && FxRules.main(s.phase) && FxRules.open(t)) {
            s.seats[seat].hand.forEach { uid ->
                if (!known(t, uid) || FxRules.normalSummonRefusal(t, seat, uid) != null) return@forEach
                val need = tributesFor(t, uid) ?: return@forEach
                val monsters = FxFilters.area(Area.MONSTERS, seat, s)
                val room = need > 0 && monsters.size >= need || need == 0 && s.freeZones(seat, ZoneKind.MONSTER).isNotEmpty()
                if (!room) return@forEach
                add(FxMove.NormalSummon(uid))
                add(FxMove.NormalSummon(uid, set = true))
            }
            (s.seats[seat].hand + s.seats[seat].extra + s.seats[seat].gy + s.seats[seat].banished).forEach { uid ->
                if (!known(t, uid)) return@forEach
                FxProcs.options(t, seat, uid).forEach { add(FxMove.Procedure(uid, it.index)) }
            }
        }
        FxRules.phases(t, seat).forEach { add(FxMove.Phase(it)) }
    }

    /** Tributes [uid]'s Normal Summon needs, or null when it has no Level. */
    private fun tributesFor(t: FxTable, uid: Int): Int? {
        val level = t.card(uid)?.level ?: return null
        return FxRules.tributes(level, t.script(uid)?.summon)
    }

    fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay = when (move) {
        is FxMove.Phase -> phase(t, seat, move)
        is FxMove.NormalSummon -> normal(t, seat, move, chooser)
        is FxMove.Procedure -> procedure(t, seat, move, chooser)
        else -> FxPlay.Refused("Not a summon.")
    }

    private fun tag(t: FxTable, uid: Int, effect: String, part: String): FxTag =
        FxTag(uid, effect, part, script = t.inst(uid)?.let { t.book.hash(it.code) } ?: "", verified = t.book.verifiedOnly)

    private fun phase(t: FxTable, seat: Int, move: FxMove.Phase): FxPlay {
        if (t.state.active != seat && !t.state.solo) return FxPlay.Refused(FxRules.NOT_TURN)
        FxRules.phaseRefusal(t.state, move.to, FxRules.open(t))?.let { return FxPlay.Refused(it) }
        val a = DuelAction.Phase(move.to)
        return commit(t, listOf(a), listOf(FxTag(0, FxTag.RULE, FxTag.RULE)), t.fx, emptyList())
    }

    private fun normal(t: FxTable, seat: Int, move: FxMove.NormalSummon, chooser: Chooser): FxPlay {
        val uid = move.uid
        if (!known(t, uid)) return FxPlay.Refused("The engine does not know this card: summon it by hand.")
        FxRules.normalSummonRefusal(t, seat, uid)?.let { return FxPlay.Refused(it) }
        val c = t.card(uid) ?: return FxPlay.Refused(FxRules.NOT_MAIN_DECK_MONSTER)
        val need = tributesFor(t, uid) ?: return FxPlay.Refused(FxRules.NOT_MAIN_DECK_MONSTER)
        val slot = FxRules.normalSlot(t, seat, uid) ?: return FxPlay.Refused(FxRules.USED)
        val s = t.state
        val tributes = if (need == 0) emptyList() else {
            val among = FxFilters.area(Area.MONSTERS, seat, s)
            if (among.size < need) return FxPlay.Refused(FxRules.tributeRefusal(c.level ?: 0, t.script(uid)?.summon, among.size) ?: "Not enough Tributes.")
            val d = Decision.Cards("Tribute for ${c.name}", among, need, need)
            val answer = ask(chooser, d) ?: return FxPlay.Cancelled
            answer.map { among[it] }
        }
        val zones = (0 until 5).map { Place.Zone(seat, ZoneKind.MONSTER, it) }.filter { z -> s.at(z).let { it == null || it in tributes } }
        val zone = pickZone(zones, chooser) ?: return if (zones.isEmpty()) FxPlay.Refused("No Monster Zone is free.") else FxPlay.Cancelled
        val pos = if (move.set) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_ATK
        val actions = tributes.map { DuelAction.Move(it, Place.Pile(t.inst(it)?.owner ?: seat, PileKind.GY), how = HOW_TRIBUTE) } +
            DuelAction.Move(uid, zone, pos, if (move.set) HOW_SET else HOW_NORMAL)
        val events = buildList {
            tributes.forEach { m ->
                val from = s.placeOf(m)
                add(FxEvent(Event.SENT_TO_GY, m, seat, from, Place.Pile(t.inst(m)?.owner ?: seat, PileKind.GY), Cause.TRIBUTE, null, uid))
                add(FxEvent(Event.LEFT_FIELD, m, seat, from, null, Cause.TRIBUTE, null, uid))
            }
            if (!move.set) {
                val kind = if (tributes.isEmpty()) ProcKind.NORMAL else ProcKind.TRIBUTE
                add(FxEvent(Event.SUMMONED, uid, seat, s.placeOf(uid), zone, null, kind))
                add(FxEvent(Event.NORMAL_SUMMONED, uid, seat, s.placeOf(uid), zone, null, kind))
            }
        }
        var fx = t.fx.copy(normals = t.fx.normals + (seat to t.fx.normalsUsed(seat) + 1))
        if (slot != FxRules.OWN) fx = fx.copy(grants = fx.grants.mapIndexed { i, g -> if (i == slot) g.copy(used = true) else g })
        fx = if (move.set) fx.copy(setCards = fx.setCards + uid)
        else fx.copy(summoned = fx.summoned + (uid to if (tributes.isEmpty()) ProcKind.NORMAL else ProcKind.TRIBUTE))
        fx = fx.copy(sent = fx.sent + tributes)
        tributes.forEach { m -> fx = fx.moved(m, Place.Pile(t.inst(m)?.owner ?: seat, PileKind.GY)) }
        fx = fx.moved(uid, zone)
        val tag = tag(t, uid, FxTag.RULE, FxTag.RULE)
        return commit(t, actions, actions.map { tag }, fx, events)
    }

    private fun procedure(t: FxTable, seat: Int, move: FxMove.Procedure, chooser: Chooser): FxPlay {
        val s = t.state
        val uid = move.uid
        if (s.active != seat && !s.solo) return FxPlay.Refused(FxRules.NOT_TURN)
        if (!FxRules.main(s.phase)) return FxPlay.Refused("A monster is summoned by its procedure in your Main Phase.")
        if (!FxRules.open(t)) return FxPlay.Refused(FxRules.NOT_OPEN)
        if (!known(t, uid)) return FxPlay.Refused("The engine does not know this card: summon it by hand.")
        val option = FxProcs.options(t, seat, uid).firstOrNull { it.index == move.proc }
            ?: return FxPlay.Refused(why(t, seat, uid, move.proc))
        val c = t.card(uid) ?: return FxPlay.Refused("That card is not known.")
        // The materials: one set of the legal ones.
        val k = if (option.sets.size == 1) 0 else {
            val among = option.sets.flatten().distinct().let { all -> FxFilters.area(Area.MONSTERS, seat, s).filter { it in all } }
            val d = Decision.Cards("Materials for ${c.name}", among, option.sets.minOf { it.size }, option.sets.maxOf { it.size })
            val answer = ask(chooser, d) ?: return FxPlay.Cancelled
            val chosen = answer.map { among[it] }.toSet()
            option.sets.indexOfFirst { it.toSet() == chosen }.takeIf { it >= 0 }
                ?: return FxPlay.Refused("Those materials do not make a ${FxRules.procWord(option.kind)} Summon of ${c.name}.")
        }
        val materials = option.sets[k]
        var now = t
        val pre = ArrayList<DuelAction>()
        val preTags = ArrayList<FxTag>()
        val preEvents = ArrayList<FxEvent>()
        val proc = option.proc
        var fx = t.fx
        if (proc is Proc.Inherent) {
            fx = FxRules.use(t, seat, uid, FxTag.PROC, proc.opt)
            now = t.copy(fx = fx)
            if (proc.cost.isNotEmpty()) {
                val act = FxAct(seat, uid, t.code(uid) ?: 0, FxTag.PROC, FxTag.COST, bound = mapOf(Pick.SELF to listOf(uid)),
                    script = t.inst(uid)?.let { t.book.hash(it.code) } ?: "", verified = t.book.verifiedOnly)
                when (val r = FxSteps.run(now, act, proc.cost, chooser)) {
                    is FxRun.Refused -> return FxPlay.Refused(r.why)
                    FxRun.Cancelled -> return FxPlay.Cancelled
                    is FxRun.Done -> {
                        pre += r.actions; preTags += r.tags; preEvents += r.events
                        now = now.copy(state = r.state, fx = r.fx)
                        fx = r.fx
                    }
                }
            }
        }
        val zones = if (proc is Proc.Inherent) FxRules.summonZones(now, seat, uid) else option.zones[k]
        val zone = pickZone(zones, chooser) ?: return if (zones.isEmpty()) FxPlay.Refused("No zone is free for it.") else FxPlay.Cancelled
        val wanted = when {
            option.kind == ProcKind.LINK -> Pos.ATTACK
            proc is Proc.Inherent -> proc.pos
            else -> Pos.EITHER
        }
        val defense = if (wanted == Pos.EITHER) {
            val answer = ask(chooser, Decision.Option(listOf(ATTACK_POSITION, DEFENSE_POSITION))) ?: return FxPlay.Cancelled
            answer.single() == 1
        } else false
        val actions = FxProcs.actions(now, option.kind, uid, materials, zone, FxProcs.position(wanted, defense))
        val events = FxProcs.events(now, seat, option.kind, uid, materials, zone)
        fx = fx.copy(
            summoned = fx.summoned + (uid to option.kind),
            proper = fx.proper + uid,
            sent = if (option.kind == ProcKind.XYZ) fx.sent else fx.sent + materials.filter { now.inst(it)?.token != true },
            specials = fx.specials + (seat to (fx.specials[seat].orEmpty() + listOfNotNull(t.code(uid)))),
        )
        materials.forEach { m -> fx = fx.moved(m, if (option.kind == ProcKind.XYZ) Place.Under(uid) else Place.Pile(now.inst(m)?.owner ?: seat, PileKind.GY)) }
        fx = fx.moved(uid, zone).let { it.copy(proper = it.proper + uid) }
        val tag = tag(t, uid, FxTag.PROC, FxTag.PROC)
        return commit(t, pre + actions, preTags + actions.map { tag }, fx, preEvents + events)
    }

    /** Why procedure [index] is not open to [uid] now, in words. */
    private fun why(t: FxTable, seat: Int, uid: Int, index: Int): String {
        val proc = t.script(uid)?.summon?.procs?.getOrNull(index) ?: return "It has no such procedure."
        val kind = when (proc) {
            is Proc.Link -> ProcKind.LINK
            is Proc.Synchro -> ProcKind.SYNCHRO
            is Proc.Xyz -> ProcKind.XYZ
            is Proc.Inherent -> ProcKind.INHERENT
            else -> return "That is summoned by an effect, not a procedure of its own."
        }
        FxRules.specialRefusal(t, seat, uid, kind)?.let { return it }
        if (proc is Proc.Inherent) {
            FxRules.optRefusal(t, seat, uid, FxTag.PROC, proc.opt)?.let { return it }
            return "Its condition does not hold, or it is not where it is summoned from."
        }
        return "No legal materials, or no zone left for it."
    }

    /** One zone of [zones]: the only one, or the chooser's; null when there is none or it cancelled. */
    private fun pickZone(zones: List<Place.Zone>, chooser: Chooser): Place.Zone? = when (zones.size) {
        0 -> null
        1 -> zones.single()
        else -> ask(chooser, Decision.Zone(zones))?.single()?.let(zones::get)
    }

    /** The chooser's answer to [d], or null when it is no legal answer (a cancel). */
    private fun ask(chooser: Chooser, d: Decision): List<Int>? = chooser.choose(d).takeIf { Chooser.legal(d, it) }

    /** [actions] applied to the table: the play, or the table's refusal (which a correct engine never meets). */
    private fun commit(t: FxTable, actions: List<DuelAction>, tags: List<FxTag>, fx: FxState, events: List<FxEvent>): FxPlay {
        val (next, problem) = DuelRules.applyAll(t.state, actions)
        next ?: return FxPlay.Refused("The table refused it: $problem")
        return FxPlay.Done(actions, tags, next, fx, events)
    }

    const val HOW_NORMAL = "normal"
    const val HOW_SET = "set"
    const val HOW_TRIBUTE = "tribute"
    const val ATTACK_POSITION = "Attack Position"
    const val DEFENSE_POSITION = "Defense Position"
}
