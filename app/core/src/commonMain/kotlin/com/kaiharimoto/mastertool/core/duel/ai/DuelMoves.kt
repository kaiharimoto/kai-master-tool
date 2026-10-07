package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelReach
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelLetters
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation

/**
 * A menu of the moves a seat may make now (Phase C stage 2, `docs/phases/C.md` §4; `duel_moves`), so Ai chooses a line
 * instead of composing one — the research's cheapest gain: an LLM player given the engine's legal-action menu lost most to
 * what the interface left out, never to the menu (`docs/AI-INTELLIGENCE.md` §2).
 *
 * Read off [DuelVerbs] — each card's verbs where it stands, the verbs a right-click, a key and the command line run — for
 * every card the seat may touch: its hand, its field, its GY, banished cards and Extra Deck, and the other seat's cards on
 * the field and in its piles that a player reaches across the table for. Each move is the exact line `duel_act` takes
 * (table notation, [DuelNotation], and the verb letters, [DuelLetters]), and **each is kept only when it plans** —
 * parsed and folded on the table through [ComboRunner.plan], as `duel_act` checks a line — **and [DuelReach] lets the seat
 * make it**: a move the table refuses, or one that would take, turn up, show or target a card hidden from the seat, is
 * never offered. Physics only, never card text: the menu says what the table holds, not what a card allows.
 *
 * A card the seat cannot see is written by its place and never named. Moves that do the same thing are offered once.
 */
object DuelMoves {
    /** One move: [line] exactly as `duel_act` takes it; [what] its words. */
    data class Move(val line: String, val what: String)

    /** Moves under one heading: the turn, the chain, the opening roll, the attacks, or one card by its coordinate. */
    data class Group(val title: String, val moves: List<Move>, val card: Int? = null)

    /** The overview's verbs per place — the rest when one card is asked for ([menu]'s `only`). */
    private val HAND = listOf(DuelVerb.SUMMON, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.ACTIVATE, DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.REVEAL)
    private val FIELD = listOf(
        DuelVerb.ACTIVATE, DuelVerb.SUMMON, DuelVerb.SET, DuelVerb.POSITION, DuelVerb.FLIP, DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.HAND,
        DuelVerb.TARGET,
    )
    private val PILE = listOf(DuelVerb.ACTIVATE, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.HAND, DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.TARGET)
    private val EXTRA = listOf(DuelVerb.SPECIAL, DuelVerb.GRAVE, DuelVerb.BANISH)
    private val THEIRS = listOf(DuelVerb.TARGET, DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.HAND, DuelVerb.SPECIAL)

    /** The default cap of moves in the overview; past it the menu says how many more there are. */
    const val CAP = 160

    /**
     * Every move [seat] may make on [s], grouped. [only] is one card (a uid): every verb it takes where it stands, with each
     * free zone and host spelled out. [secret] is the duel's (its seed): the other seat's hand in the order the seat is shown it.
     * [shortcuts]: the table's written effects (Phase D §5½) — each of the seat's own cards then lists its Shortcuts legal
     * now (`u h2 e1`), and the chain its resolution as written; without them nothing of the kind is listed.
     */
    fun menu(
        s: DuelState,
        seat: Int,
        catalog: DuelCatalog,
        secret: Long,
        only: Int? = null,
        shortcuts: Shortcuts? = null,
        /** A table's own law beyond the physics (an Ai vs Ai match's `MatchLaw`): a move it refuses is never offered. */
        allow: (List<DuelAction>) -> Boolean = { true },
    ): List<Group> {
        val seen = HashSet<List<DuelAction>>()
        fun plan(line: String): List<DuelAction>? {
            val run = ComboRunner.plan(s, seat, listOf(line), catalog, secret)
            if (!run.ok || run.steps.isEmpty()) return null
            if (ComboRunner.reach(s, seat, run) != null) return null
            return run.steps.flatMap { it.second }.takeIf { it.isNotEmpty() && allow(it) }
        }
        /** [line] when it plans and does what no move before it did. */
        fun offer(out: MutableList<Move>, line: String, what: String) {
            val actions = plan(line) ?: return
            if (!seen.add(actions)) return
            out += Move(line, what + destination(s, seat, actions))
        }
        val groups = mutableListOf<Group>()
        if (only == null) {
            turn(s, seat).let { lines ->
                val out = mutableListOf<Move>()
                lines.forEach { (line, what) -> offer(out, line, what) }
                if (out.isNotEmpty()) groups += Group(if (s.beforeTurnOne) "Before turn 1" else "The turn", out)
            }
            if (s.chain.isNotEmpty()) {
                val out = mutableListOf<Move>()
                offer(out, "resolve", "Resolve Chain Link ${s.chain.size}")
                if (s.chain.size > 1) offer(out, "resolve all", "Resolve the whole chain")
                s.chain.forEachIndexed { i, l -> if (!l.negated) offer(out, "negate ${i + 1}", "Negate Chain Link ${i + 1}") }
                // As written (Phase D §5½): offered when the newest link, or any, was made by a Shortcut. The engine makes it
                // with the choices it asks for, so it is listed, not planned.
                if (shortcuts != null && !shortcuts.networked) {
                    val links = (1..s.chain.size).filter { shortcuts.written(s, it) }
                    if (s.chain.size in links) out += Move("resolve by shortcut", "Resolve Chain Link ${s.chain.size} as written")
                    if (links.isNotEmpty() && s.chain.size > 1) out += Move("resolve all by shortcut", "Resolve the whole chain, the written links as written")
                }
                if (out.isNotEmpty()) groups += Group("The chain", out)
            }
            attacks(s, seat, catalog, secret).let { lines ->
                val out = mutableListOf<Move>()
                lines.forEach { (line, what) -> offer(out, line, what) }
                if (out.isNotEmpty()) groups += Group("Attacks", out)
            }
            if (s.seats[seat].deck.isNotEmpty()) {
                val out = mutableListOf<Move>()
                offer(out, "draw", "Draw a card")
                if (out.isNotEmpty()) groups += Group("Your Deck (${s.seats[seat].deck.size})", out)
            }
        }
        val other = 1 - seat
        val mine = s.seats[seat]
        val field = s.onField()
        // Each card the seat may touch, with the overview's verbs for where it is; the other seat's cards take a player's
        // reach across the table (target, destroy, banish, bounce, take from their GY), whatever DuelVerbs offers its owner.
        val cards: List<Triple<Int, List<DuelVerb>, Boolean>> = buildList {
            DuelNotation.handOrder(s, seat, seat, secret).forEach { add(Triple(it, HAND, false)) }
            field.filter { s.cards[it]?.controller == seat }.forEach { add(Triple(it, FIELD, false)) }
            mine.gy.forEach { add(Triple(it, PILE, false)) }
            mine.banished.forEach { add(Triple(it, PILE, false)) }
            mine.extra.forEach { add(Triple(it, EXTRA, false)) }
            if (!s.solo) {
                field.filter { s.cards[it]?.controller == other }.forEach { add(Triple(it, THEIRS, true)) }
                s.seats[other].gy.forEach { add(Triple(it, THEIRS, true)) }
                s.seats[other].banished.filter { DuelSight.sees(s, it, seat) }.forEach { add(Triple(it, THEIRS, true)) }
            }
        }
        for ((uid, verbs, theirs) in cards) {
            if (only != null && uid != only) continue
            val card = s.cards[uid] ?: continue
            val at = DuelNotation.coordOf(s, uid, seat, secret) ?: continue
            val sees = DuelSight.sees(s, uid, seat)
            val offered = DuelVerbs.offered(s, seat, uid, catalog).filter { it != DuelVerb.DEFAULT && it != DuelVerb.ATTACK && it != DuelVerb.SHORTCUT }
            val chosen = when {
                only != null -> (if (theirs) verbs else offered) + (if (theirs) emptyList() else verbs.filter { it !in offered })
                theirs -> verbs
                else -> verbs.filter { it in offered }
            }
            val out = mutableListOf<Move>()
            // The card's Shortcuts legal now (Phase D §5½), by effect id: the engine makes each with the choices given in the
            // op (`pick=`, `target=`, `zone=`, `declare=`), or asks them back.
            if (!theirs && shortcuts != null) shortcuts.options(s, seat, uid).filter { it.legal }.forEach { o ->
                out += Move("${word(DuelVerb.SHORTCUT)} $at ${o.effect}", "Shortcut: ${o.label}${if (o.verified) "" else " (unverified)"}")
            }
            for (verb in chosen) {
                val word = word(verb) ?: continue
                offer(out, "$word $at", verb.label)
                if (only != null) zonesFor(s, seat, uid, verb, catalog).forEach { z -> offer(out, "$word $at $z", "${verb.label} to $z") }
            }
            if (only != null) {
                // Under a monster of the seat's own; moved to a free zone of its kind; a material detached from it.
                field.filter { it != uid && s.cards[it]?.controller == seat && s.placeOf(it).isMonsterZone() }.forEach { host ->
                    DuelNotation.coordOf(s, host, seat, secret)?.let { h -> offer(out, "o $at $h", "Attach under $h") }
                }
                val where = s.placeOf(uid)
                if (where is Place.Zone && card.controller == seat) {
                    val kind = if (where.kind == ZoneKind.EMZ) ZoneKind.MONSTER else where.kind
                    s.freeZones(seat, kind).mapNotNull { DuelNotation.slotCoord(it, seat) }.forEach { z -> offer(out, "m $at $z", "Move to $z") }
                }
            }
            if (card.under.isNotEmpty() && card.controller == seat) offer(out, "detach $at", "Detach a material (${card.under.size} under it)")
            if (out.isEmpty()) continue
            val who = if (sees) catalog.nameOf(card) else "a face-down card"
            groups += Group("$at $who", out, uid)
        }
        return groups
    }

    /** The menu in words for `duel_moves`: each group a line, each move its line and words; past [cap] moves, a count. */
    fun words(groups: List<Group>, cap: Int = CAP): String = buildString {
        var shown = 0
        val total = groups.sumOf { it.moves.size }
        for (g in groups) {
            if (shown >= cap) break
            val room = g.moves.take(cap - shown)
            appendLine("${g.title}: " + room.joinToString(" · ") { "`${it.line}` ${it.what}" })
            shown += room.size
        }
        if (shown < total) appendLine("…and ${total - shown} more not shown: duel_moves with card=<coordinate> lists one card's every move.")
    }.trimEnd()

    /** The turn's own moves for [seat]: the phases ahead and the end, an answer to an ask, the opening roll. */
    private fun turn(s: DuelState, seat: Int): List<Pair<String, String>> = buildList {
        val o = s.opening
        if (o != null && !o.decided) {
            if (o.winner == null && !o.thrown(seat)) add("roll" to "Throw your two dice for who goes first")
            if (o.winner == seat) { add("go first" to "Go first"); add("go second" to "Go second") }
            return@buildList
        }
        val own = s.solo || s.active == seat
        s.proposal?.takeIf { own && it.seat != seat }?.let {
            add("accept" to "Accept their ask")
            add("decline" to "Decline it")
        }
        if (own) {
            DuelPhase.entries.filter { it.ordinal > s.phase.ordinal }.forEach { p -> add(PHASE_WORDS.getValue(p) to "${p.label} Phase") }
            add("end" to "End the turn")
        } else {
            add("next" to "Ask to move to the next phase")
        }
    }

    /** Each attack the seat could declare now: on each monster of theirs, and directly. */
    private fun attacks(s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long): List<Pair<String, String>> {
        if (s.phase != DuelPhase.BATTLE) return emptyList()
        val field = s.onField()
        val theirs = field.filter { s.cards[it]?.controller != seat && s.placeOf(it).isMonsterZone() }
        return field.filter { DuelVerbs.canAttack(s, seat, it) }.flatMap { a ->
            val at = DuelNotation.coordOf(s, a, seat, secret) ?: return@flatMap emptyList()
            val by = catalog.nameOf(s.cards.getValue(a))
            theirs.mapNotNull { t ->
                DuelNotation.coordOf(s, t, seat, secret)?.let { tc ->
                    val them = if (DuelSight.sees(s, t, seat)) catalog.nameOf(s.cards.getValue(t)) else "their face-down monster"
                    "a $at $tc" to "$by attacks $them"
                }
            } + ("a $at direct" to "$by attacks directly")
        }
    }

    /** The free zones a zone-taking verb could put [uid] in, each by its coordinate. */
    private fun zonesFor(s: DuelState, seat: Int, uid: Int, verb: DuelVerb, catalog: DuelCatalog): List<String> {
        val kind = DuelVerbs.zoneKind(s, seat, uid, verb, catalog) ?: return emptyList()
        if (kind == ZoneKind.FIELD) return emptyList()
        val zones = s.freeZones(seat, kind) +
            if (kind == ZoneKind.MONSTER && DuelVerbs.kindOf(s.cards.getValue(uid), catalog) == CardKind.EXTRA_MONSTER) s.freeZones(seat, ZoneKind.EMZ) else emptyList()
        return zones.mapNotNull { DuelNotation.slotCoord(it, seat) }
    }

    /** Where a move puts its card, when it goes to a zone: " to m3". */
    private fun destination(s: DuelState, seat: Int, actions: List<DuelAction>): String {
        val to = actions.filterIsInstance<DuelAction.Move>().firstOrNull()?.to as? Place.Zone ?: return ""
        return DuelNotation.slotCoord(to, seat)?.let { " to $it" } ?: ""
    }

    /** The word a verb is typed with before a coordinate: its letter, or `ss` and `place` for the two with none. */
    fun word(verb: DuelVerb): String? = when (verb) {
        DuelVerb.SPECIAL -> "ss"
        DuelVerb.PLACE -> "place"
        DuelVerb.DEFAULT, DuelVerb.ATTACK, DuelVerb.DETACH, DuelVerb.ATTACH, DuelVerb.MOVE -> null
        else -> DuelLetters.ROWS.firstOrNull { it.verb == verb }?.letter
    }

    private fun Place?.isMonsterZone(): Boolean = this is Place.Zone && (kind == ZoneKind.MONSTER || kind == ZoneKind.EMZ)

    private val PHASE_WORDS = mapOf(
        DuelPhase.DRAW to "dp", DuelPhase.STANDBY to "sp", DuelPhase.MAIN1 to "m1",
        DuelPhase.BATTLE to "bp", DuelPhase.MAIN2 to "m2", DuelPhase.END to "ep",
    )
}
