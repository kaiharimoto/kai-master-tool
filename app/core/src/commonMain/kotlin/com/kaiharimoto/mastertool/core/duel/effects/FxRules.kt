package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * The game's rules the engine holds (D.md §2.4), as pure functions, **one list**: the engine reads them, and so does the
 * puzzles' referee (`PuzzleReferee`). The engine refuses what card text and these rules forbid; `DuelRules` refuses only
 * what the table cannot hold. Each refusal is a sentence a person reads.
 *
 * - One Normal Summon or Set a turn, plus any "Normal Summon 1 more" ([normalSlot]).
 * - Tributes: one for Level 5–6, two for 7 and above, unless the script's [SummonRule.tributes] says otherwise ([tributes]).
 * - A Trap, or a set Quick-Play Spell, is not activated the turn it was Set unless its effect allows it ([setTurnRefusal]);
 *   a Quick-Play Spell is activated from the hand only on your own turn ([quickPlayRefusal]).
 * - A Link Monster, or a Pendulum Monster face-up in the Extra Deck, goes to an Extra Monster Zone or a Main Monster Zone
 *   a Link Monster points to ([summonZones]); a seat uses one Extra Monster Zone at a time.
 * - "Cannot be Normal Summoned/Set" (and every Extra Deck and Ritual monster) is never Normal Summoned; "must first be"
 *   is honoured, as is "an Extra Deck monster not properly summoned stays where it is" ([specialRefusal]).
 * - Restrictions in force bind ([restricted]), and once-per-turn is counted ([optRefusal], [use]).
 * - The phases only go forward ([phaseRefusal]).
 *
 * Materials going to the GY and Xyz materials going beneath their monster are the procedures' ([FxProcs]).
 */
object FxRules {
    const val NOT_TURN = "It is not your turn."
    const val NOT_MAIN = "Monsters are Normal Summoned or Set in your Main Phase."
    const val NOT_OPEN = "Not while a chain is open or triggers wait: summons are made with nothing pending."
    const val USED = "You have Normal Summoned or Set this turn already."
    const val NOT_MAIN_DECK_MONSTER = "Only a Main Deck monster is Normal Summoned."
    const val CANNOT_NORMAL = "This card cannot be Normal Summoned or Set."
    const val RITUAL_NORMAL = "A Ritual Monster is Ritual Summoned, never Normal Summoned or Set."
    const val SET_TURN = "A Trap, or a set Quick-Play Spell, is activated from the turn after it was Set."
    const val QUICK_PLAY_HAND = "A Quick-Play Spell is activated from the hand only on your own turn."
    const val NO_SPELL_ZONE = "No Spell & Trap Zone is free for it."
    const val NO_BATTLE_FIRST = "No Battle Phase on the duel's first turn."
    const val OPT_USED = "Once per turn: used."
    const val OPD_USED = "Once per Duel: used."
    const val OPT_UNREAD = "Its once-per-turn rule is written in a newer build's words."
    const val ONCE_SPECIAL = "It can be Special Summoned only once per turn: done."

    // ---- the turn ------------------------------------------------------------------------------------------------

    fun main(phase: DuelPhase): Boolean = phase == DuelPhase.MAIN1 || phase == DuelPhase.MAIN2

    /** The open state (§2.3): no chain, no trigger waiting. */
    fun open(t: FxTable): Boolean = t.state.chain.isEmpty() && t.fx.pending.isEmpty()

    /** Why the phase may not move on to [to] from [s]'s, or null when it may. Skipping a phase is moving forward. */
    fun phaseRefusal(s: DuelState, to: DuelPhase, open: Boolean = s.chain.isEmpty()): String? = when {
        to.ordinal <= s.phase.ordinal -> "The phases only go forward: it is the ${s.phase.label} Phase."
        !open -> "Finish the chain first: the phase moves on with nothing pending."
        to == DuelPhase.BATTLE && s.turn == 1 -> NO_BATTLE_FIRST
        else -> null
    }

    /** The phases [seat] may move on to now (forward only, the turn player's, nothing pending). */
    fun phases(t: FxTable, seat: Int): List<DuelPhase> {
        val s = t.state
        if (s.active != seat && !s.solo) return emptyList()
        return DuelPhase.entries.filter { phaseRefusal(s, it, open(t)) == null }
    }

    // ---- Normal Summons ------------------------------------------------------------------------------------------

    /** Tributes a Normal Summon or Set of a Level [level] monster needs: the script's say, else 1 for 5–6 and 2 for 7+. */
    fun tributes(level: Int, rule: SummonRule? = null): Int = rule?.tributes ?: when {
        level >= 7 -> 2
        level >= 5 -> 1
        else -> 0
    }

    /** Why the card [c] cannot be Normal Summoned or Set at all, whatever the turn; null when it can be. */
    fun normalKindRefusal(c: FxCard?, rule: SummonRule?): String? = when {
        c == null || !c.monster || CardFrame.TOKEN in c.frames || c.extraDeck -> NOT_MAIN_DECK_MONSTER
        CardFrame.RITUAL in c.frames -> RITUAL_NORMAL
        rule?.normal == false -> CANNOT_NORMAL
        else -> null
    }

    /**
     * Why a Normal Summon or Set of [c] is refused on a table in [phase], the seat having made [used] of the [allowed]
     * this turn; null when it is allowed (Tributes apart: [tributeRefusal]). What the referee and the engine both read.
     */
    fun normalRefusal(c: FxCard?, rule: SummonRule?, phase: DuelPhase, used: Int, allowed: Int = 1): String? = when {
        !main(phase) -> NOT_MAIN
        used >= allowed -> USED
        else -> normalKindRefusal(c, rule)
    }

    /** Why [paid] Tributes are not what a Level [level] monster's Normal Summon needs; null when they are. */
    fun tributeRefusal(level: Int, rule: SummonRule?, paid: Int): String? {
        val need = tributes(level, rule)
        if (paid == need) return null
        return if (need == 0) "A Level $level monster needs no Tribute." else "A Level $level monster needs $need Tribute${if (need == 1) "" else "s"}."
    }

    /**
     * Which Normal Summon [seat] would use for [uid] now: [OWN] for the turn's own, a grant's index in
     * [FxState.grants] for "Normal Summon 1 more", or null when none is left for it.
     */
    fun normalSlot(t: FxTable, seat: Int, uid: Int): Int? {
        if (t.fx.normalsUsed(seat) == 0) return OWN
        val i = t.fx.grants.indexOfFirst { g ->
            !g.used && g.seat == seat && FxFilters.matches(g.filter, uid, FxScope(t, seat, g.source))
        }
        return i.takeIf { it >= 0 }
    }

    const val OWN = -1

    /** Why [seat] may not Normal Summon or Set [uid] from the hand on [t] now; null when it may (Tributes apart). */
    fun normalSummonRefusal(t: FxTable, seat: Int, uid: Int): String? {
        val s = t.state
        if (s.active != seat && !s.solo) return NOT_TURN
        if (!open(t)) return NOT_OPEN
        val p = s.placeOf(uid)
        if (p !is Place.Pile || p.kind != PileKind.HAND || p.seat != seat) return "A Normal Summon or Set is made from your hand."
        val c = t.card(uid)
        val rule = t.script(uid)?.summon
        val slot = normalSlot(t, seat, uid)
        normalRefusal(c, rule, s.phase, used = if (slot == null) 1 else 0, allowed = 1)?.let { return it }
        restricted(t, seat, Ban.NORMAL_SUMMON, uid)?.let { return words(it) }
        return null
    }

    // ---- Spells and Traps -------------------------------------------------------------------------------------------

    /** Spell speed (§2.3): ignition and trigger 1, quick 2; a Spell's or Trap's own activation by its kind; continuous 0. */
    fun speed(e: Effect, c: FxCard?): Int = when (e.kind) {
        Kind.IGNITION, Kind.TRIGGER -> 1
        Kind.QUICK -> 2
        Kind.CONTINUOUS -> 0
        Kind.ACTIVATION -> when {
            c?.type == CardType.TRAP && c.isSpellSub("Counter") -> 3
            c?.type == CardType.TRAP -> 2
            c?.type == CardType.SPELL && c.isSpellSub("Quick-Play") -> 2
            else -> 1
        }
    }

    /** Why [uid], Set this turn, may not use [e] yet: a Trap, or a set Quick-Play Spell, waits a turn unless [Effect.sameTurn]. */
    fun setTurnRefusal(t: FxTable, uid: Int, e: Effect): String? {
        val c = t.card(uid) ?: return null
        val inst = t.inst(uid) ?: return null
        val p = t.state.placeOf(uid)
        val waits = c.type == CardType.TRAP || (c.type == CardType.SPELL && c.isSpellSub("Quick-Play"))
        val set = p is Place.Zone && p.kind == ZoneKind.SPELL && !inst.faceUp
        return if (waits && set && uid in t.fx.setCards && !e.sameTurn && e.kind == Kind.ACTIVATION) SET_TURN else null
    }

    /** Why [seat] may not activate the Quick-Play Spell [uid] from the hand: only on its own turn. */
    fun quickPlayRefusal(t: FxTable, seat: Int, uid: Int): String? {
        val c = t.card(uid) ?: return null
        val p = t.state.placeOf(uid)
        val fromHand = p is Place.Pile && p.kind == PileKind.HAND
        return if (fromHand && c.type == CardType.SPELL && c.isSpellSub("Quick-Play") && t.state.active != seat && !t.state.solo) QUICK_PLAY_HAND else null
    }

    /** The zones a Spell or Trap activated (or Set) from [seat]'s hand may go to: a Field Spell the Field Zone, else a free Spell & Trap Zone. */
    fun spellZones(t: FxTable, seat: Int, uid: Int): List<Place.Zone> {
        val c = t.card(uid) ?: return emptyList()
        return if (c.type == CardType.SPELL && c.isSpellSub("Field")) {
            listOf(Place.Zone(seat, ZoneKind.FIELD, 0)) // a new Field Spell replaces your old one, which goes to the GY by the game's own rule (Yugipedia, "Field Spell Card")
        } else t.state.freeZones(seat, ZoneKind.SPELL)
    }

    // ---- where monsters go -------------------------------------------------------------------------------------------

    /** A cell of the monster rows, as seat 0 sees them: x the column 0–4, y 0 seat 0's row, 1 the Extra Monster Zones, 2 seat 1's. */
    private data class Cell(val x: Int, val y: Int)

    private fun cell(zone: Place.Zone): Cell? = when (zone.kind) {
        ZoneKind.MONSTER -> if (zone.seat == 0) Cell(zone.index, 0) else Cell(4 - zone.index, 2)
        ZoneKind.EMZ -> Cell(1 + 2 * zone.index, 1)
        else -> null
    }

    private fun zoneAt(c: Cell, seat: Int): Place.Zone? = when {
        c.x !in 0..4 -> null
        c.y == 0 -> Place.Zone(0, ZoneKind.MONSTER, c.x)
        c.y == 2 -> Place.Zone(1, ZoneKind.MONSTER, 4 - c.x)
        c.y == 1 && c.x == 1 -> Place.Zone(seat, ZoneKind.EMZ, 0)
        c.y == 1 && c.x == 3 -> Place.Zone(seat, ZoneKind.EMZ, 1)
        else -> null
    }

    /**
     * The Monster Zones the face-up Link Monster [uid] points to (either seat's, and the Extra Monster Zones, [seatOfEmz]
     * written as the seat of an EMZ zone), from its arrows as its controller faces the table.
     */
    fun pointsTo(t: FxTable, uid: Int, seatOfEmz: Int = 0): List<Place.Zone> {
        val inst = t.inst(uid) ?: return emptyList()
        if (!inst.faceUp) return emptyList()
        val c = t.card(uid) ?: return emptyList()
        if (c.link == null) return emptyList()
        val at = t.state.placeOf(uid) as? Place.Zone ?: return emptyList()
        val from = cell(at) ?: return emptyList()
        val flip = if (FxFilters.controller(uid, t.state) == 1) -1 else 1
        return c.arrows.mapNotNull { a -> zoneAt(Cell(from.x + a.dx * flip, from.y + a.dy * flip), seatOfEmz) }
    }

    /** [seat]'s Main Monster Zones some face-up Link Monster on the field points to, leaving out [gone] (materials about to leave). */
    fun linkedZones(t: FxTable, seat: Int, gone: Set<Int> = emptySet()): Set<Place.Zone> =
        t.state.onField().filter { it !in gone }.flatMap { pointsTo(t, it, seat) }
            .filter { it.kind == ZoneKind.MONSTER && it.seat == seat }.toSet()

    /**
     * Where [seat] may summon [uid] from where it is now, the cards [gone] (its materials or Tributes) having left first:
     * - a Link Monster, or a Pendulum Monster face-up in the Extra Deck — a free Extra Monster Zone or a free Main Monster
     *   Zone a Link Monster points to;
     * - another Extra Deck monster — any free Main Monster Zone or Extra Monster Zone;
     * - anything else — a free Main Monster Zone.
     * A seat that controls a monster in an Extra Monster Zone (not leaving) uses no other.
     */
    fun summonZones(t: FxTable, seat: Int, uid: Int, gone: Set<Int> = emptySet()): List<Place.Zone> {
        val s = t.state
        val c = t.card(uid) ?: return emptyList()
        val inst = t.inst(uid) ?: return emptyList()
        val p = s.placeOf(uid)
        val fromExtra = p is Place.Pile && p.kind == PileKind.EXTRA
        fun free(z: Place.Zone) = s.at(z).let { it == null || it in gone || it == uid }
        val main = (0 until DuelState.ZONES).map { Place.Zone(seat, ZoneKind.MONSTER, it) }.filter(::free)
        val holdsEmz = s.emz.any { u -> u != null && u !in gone && u != uid && s.cards[u]?.controller == seat }
        val emz = if (holdsEmz) emptyList() else s.emz.indices.map { Place.Zone(seat, ZoneKind.EMZ, it) }.filter(::free)
        val needsLink = fromExtra && (c.link != null || (c.pendulum && inst.faceUp))
        return when {
            needsLink -> emz + linkedZones(t, seat, gone).filter(::free).sortedBy { it.index }
            fromExtra -> main + emz
            else -> main
        }
    }

    /**
     * The zones of the kinds in [open] that are not among them, each with why, as the Shortcut window says it (D.md
     * §5¾.12): taken by a card, an Extra Monster Zone when the seat already uses one, a Main Monster Zone no Link points to.
     * A set card is named only as a set card.
     */
    fun closedZones(t: FxTable, seat: Int, open: List<Place.Zone>): Map<Place.Zone, String> {
        if (open.isEmpty()) return emptyMap()
        val s = t.state
        val kinds = open.map { it.kind }.toSet()
        val all = buildList {
            if (ZoneKind.MONSTER in kinds || ZoneKind.EMZ in kinds) {
                (0 until DuelState.ZONES).forEach { add(Place.Zone(seat, ZoneKind.MONSTER, it)) }
                s.emz.indices.forEach { add(Place.Zone(seat, ZoneKind.EMZ, it)) }
            }
            if (ZoneKind.SPELL in kinds) (0 until DuelState.ZONES).forEach { add(Place.Zone(seat, ZoneKind.SPELL, it)) }
        }
        val holdsEmz = s.emz.any { u -> u != null && s.cards[u]?.controller == seat }
        return all.filter { it !in open }.associateWith { z ->
            val there = s.at(z)
            when {
                there != null -> if (s.cards[there]?.faceUp == true) "Taken by ${t.card(there)?.name ?: "a card"}" else "Taken by a set card"
                z.kind == ZoneKind.EMZ && holdsEmz -> "You already use an Extra Monster Zone"
                z.kind == ZoneKind.EMZ -> "Not a zone this summon can use"
                ZoneKind.EMZ in kinds -> "No Link Monster points here"
                else -> "Not a zone this can use"
            }
        }
    }

    // ---- restrictions -------------------------------------------------------------------------------------------------

    /**
     * The restrictions binding [seat] now: those left by effects ([FxState.restrictions]) and those a face-up card's
     * [Kind.CONTINUOUS] effects apply while their condition holds.
     */
    fun inForce(t: FxTable): List<InForce> = t.inForce

    /** What [inForce] reads, worked out once a table ([FxTable.inForce]). */
    internal fun inForceNow(t: FxTable): List<InForce> = t.fx.restrictions + continuous(t)

    private fun continuous(t: FxTable): List<InForce> = t.state.onField().flatMap { uid ->
        val inst = t.inst(uid)
        if (inst == null || !inst.faceUp) return@flatMap emptyList()
        val script = t.script(uid) ?: return@flatMap emptyList()
        val owner = FxFilters.controller(uid, t.state) ?: return@flatMap emptyList()
        script.effects.filter { it.kind == Kind.CONTINUOUS && !t.book.unread(script.card, it.id) }
            .filter { e -> e.condition == null || FxConds.holds(e.condition, FxScope(t, owner, uid)) }
            .flatMap { e -> e.leaves.flatMap { r -> seatsOf(r.seat, owner).map { InForce(r, it, uid, t.state.turn) } } }
    }

    /** The absolute seats a restriction's [rel] binds, from its source's controller [owner]. */
    fun seatsOf(rel: Rel, owner: Int): List<Int> = when (rel) {
        Rel.YOU -> listOf(owner)
        Rel.THEM -> listOf(1 - owner)
        Rel.ANY -> listOf(0, 1)
    }

    /** The restriction that forbids [seat] to [ban] with [uid], or null. A restriction's exception is judged as its source's controller sees. */
    fun restricted(t: FxTable, seat: Int, ban: Ban, uid: Int): InForce? = inForce(t).firstOrNull { r ->
        r.seat == seat && bans(r.restriction.ban, ban) &&
            (r.restriction.except == null || !FxFilters.matches(r.restriction.except, uid, FxScope(t, seat, r.source)))
    }

    /** Whether a restriction on [held] forbids [asked]: no Special Summons forbids those from the Extra Deck too. */
    internal fun bans(held: Ban, asked: Ban): Boolean = held == asked || (held == Ban.SPECIAL_SUMMON && asked == Ban.SPECIAL_SUMMON_FROM_EXTRA)

    fun words(r: InForce): String {
        val what = when (r.restriction.ban) {
            Ban.SPECIAL_SUMMON -> "Special Summon"
            Ban.SPECIAL_SUMMON_FROM_EXTRA -> "Special Summon from the Extra Deck"
            Ban.NORMAL_SUMMON -> "Normal Summon or Set"
            Ban.ACTIVATE -> "activate cards or effects"
        }
        return "A restriction is in force: you cannot $what" + (if (r.restriction.except != null) " except as it allows." else ".")
    }

    /**
     * Why [seat] may not Special Summon [uid] now as a [kind] Summon ([ProcKind.SPECIAL] for one by an effect): a
     * restriction; "only once per turn"; "must first be …"; an Extra Deck or Ritual monster from the GY or banished that
     * was never properly summoned.
     */
    fun specialRefusal(t: FxTable, seat: Int, uid: Int, kind: ProcKind): String? {
        val c = t.card(uid) ?: return "That card is not known."
        if (!c.monster) return "Only a monster is Special Summoned."
        val p = t.state.placeOf(uid)
        val fromExtra = p is Place.Pile && p.kind == PileKind.EXTRA
        restricted(t, seat, if (fromExtra) Ban.SPECIAL_SUMMON_FROM_EXTRA else Ban.SPECIAL_SUMMON, uid)?.let { return words(it) }
        val rule = t.script(uid)?.summon
        val code = t.code(uid)
        if (rule?.oncePerTurn == true && code != null && code in t.fx.specials[seat].orEmpty()) return ONCE_SPECIAL
        val proper = uid in t.fx.proper
        rule?.mustFirstBe?.let { first ->
            if (kind != first && !proper) return "It must first be ${procWord(first)} Summoned."
        }
        val revived = p is Place.Pile && (p.kind == PileKind.GY || p.kind == PileKind.BANISHED)
        if (revived && !proper && (c.extraDeck || CardFrame.RITUAL in c.frames)) {
            return "It was not properly summoned, so it is not Special Summoned from there."
        }
        if (fromExtra && c.extraDeck && kind != ProcKind.SPECIAL && c.frameProc != null && kind != c.frameProc && rule?.mustFirstBe == null) {
            return "A ${procWord(c.frameProc!!)} Monster comes from the Extra Deck by its own summon."
        }
        return null
    }

    fun procWord(k: ProcKind): String = when (k) {
        ProcKind.NORMAL -> "Normal"
        ProcKind.TRIBUTE -> "Tribute"
        ProcKind.FLIP -> "Flip"
        ProcKind.SPECIAL -> "Special"
        ProcKind.FUSION -> "Fusion"
        ProcKind.SYNCHRO -> "Synchro"
        ProcKind.XYZ -> "Xyz"
        ProcKind.LINK -> "Link"
        ProcKind.RITUAL -> "Ritual"
        ProcKind.PENDULUM -> "Pendulum"
        ProcKind.INHERENT -> "Special"
    }

    // ---- once per turn -------------------------------------------------------------------------------------------------

    /**
     * What a use of [card]'s (canonical) [effect] is counted under: by name (shared by [Opt.ByName.group]), per copy
     * (this instance, [life]), or per Duel by name. Null for a rule this build cannot read.
     */
    fun optKey(card: Int, effect: String, opt: Opt, uid: Int, life: Int): String? = when (opt) {
        is Opt.ByName -> "name:$card:${opt.group ?: effect}"
        Opt.PerCopy -> "copy:$uid:$life:$effect"
        Opt.PerDuel -> "duel:$card:$effect"
        is Opt.Unknown -> null
    }

    /** Why [seat] may not use [uid]'s [effect] under [opt] again now; null when it may (no rule, or uses left). */
    fun optRefusal(t: FxTable, seat: Int, uid: Int, effect: String, opt: Opt?): String? {
        opt ?: return null
        val card = t.code(uid) ?: return null
        val key = optKey(card, effect, opt, uid, t.fx.life(uid)) ?: return OPT_UNREAD
        val used = t.fx.uses.count { it.seat == seat && it.key == key }
        val times = (opt as? Opt.ByName)?.times ?: 1
        return if (used >= times) (if (opt == Opt.PerDuel) OPD_USED else OPT_USED) else null
    }

    /**
     * [fx] with [seat]'s use of [uid]'s [effect] counted under [opt] (nothing when there is no rule), for chain link [link]
     * when it is an activation — so a negated activation can give back what "you can only activate" counted.
     */
    fun use(t: FxTable, seat: Int, uid: Int, effect: String, opt: Opt?, link: Int? = null): FxState {
        opt ?: return t.fx
        val card = t.code(uid) ?: return t.fx
        val life = t.fx.life(uid)
        val key = optKey(card, effect, opt, uid, life) ?: return t.fx
        val refunds = (opt as? Opt.ByName)?.refunds == true
        return t.fx.copy(uses = t.fx.uses + OptUse(seat, card, effect, key, uid, life, t.state.turn, duel = opt == Opt.PerDuel, link = link, refunds = refunds))
    }

    /**
     * Why [seat] may not activate [e] of [uid] because of its own "the turn you activate this" conditions ([Effect.leaves]):
     * the seat has already done this turn what one of them forbids. Null when it may.
     */
    fun conditionRefusal(t: FxTable, seat: Int, uid: Int, e: Effect): String? {
        if (e.leaves.isEmpty() || e.kind == Kind.CONTINUOUS) return null
        for (r in e.leaves) {
            if (seat !in seatsOf(r.seat, seat)) continue
            val done = t.fx.deeds.firstOrNull { d ->
                d.seat == seat && !(r.ban == Ban.ACTIVATE && d.uid == uid) &&
                    bans(r.ban, if (d.ban == Ban.SPECIAL_SUMMON && d.extra) Ban.SPECIAL_SUMMON_FROM_EXTRA else d.ban) &&
                    (r.except == null || t.inst(d.uid) == null || !FxFilters.matches(r.except, d.uid, FxScope(t, seat, uid)))
            } ?: continue
            return "It cannot be activated the turn you ${deedWords(done)}" + (if (r.except != null) " other than as it allows." else ".")
        }
        return null
    }

    private fun deedWords(d: Deed): String = when (d.ban) {
        Ban.NORMAL_SUMMON -> "Normal Summon or Set"
        Ban.SPECIAL_SUMMON, Ban.SPECIAL_SUMMON_FROM_EXTRA -> if (d.extra) "Special Summon from the Extra Deck" else "Special Summon"
        Ban.ACTIVATE -> "activate another card or effect"
    }
}
