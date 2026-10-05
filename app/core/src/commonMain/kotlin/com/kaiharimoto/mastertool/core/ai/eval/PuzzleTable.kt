package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Attack
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import com.kaiharimoto.mastertool.core.duel.effects.FxFacts
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import kotlinx.serialization.json.JsonObject

/** What the referee has seen this turn: the Normal Summon, who was summoned or changed position, Tributes waiting, moves made. */
data class PuzzleTurn(
    val normalUsed: Boolean = false,
    val summoned: Set<Int> = emptySet(),
    val changed: Set<Int> = emptySet(),
    /** Monsters that have attacked: the table forgets an attack once its monster leaves the field, the referee does not. */
    val attacked: Set<Int> = emptySet(),
    /** Monsters sent to the GY as Tributes for a Normal Summon not yet made. */
    val tributes: Int = 0,
    val moves: Int = 0,
)

/**
 * The puzzle's referee (Phase C stage 3): the game's rules for one turn, held over the manual table, so a goal can only be
 * met by moves a real duel allows. Each planned step of a `duel_act` op is admitted — sometimes with what the rules make
 * follow from it (battle's damage and destruction, a Spell's resolution) — or refused with the rule that forbids it.
 *
 * Admitted: the phases forward; one Normal Summon or Set from the hand, Level 5–6 after one Tribute and 7 or more after
 * two (each Tribute a monster of yours sent to the GY just before); a position change once a turn for a monster neither
 * summoned this turn nor attacked, a Flip Summon of a face-down one; an attack once a monster from face-up Attack Position
 * in the Battle Phase, at a monster of theirs or directly only when they control none — its damage and destruction worked
 * out here ([DuelBattle], the printed numbers); a Spell from the hand in a Main Phase whose effect the puzzle writes
 * ([PuzzleEffect]), resolved at once; and talk. The summon, Tribute and phase rules are read from `FxRules` (Phase D, one
 * list for the referee and the effect engine); the Spells become scripts ([PuzzleCards.scripts]) the engine plays once its
 * step executor runs them, and [PuzzleEffect] goes then. Refused: everything else — life points typed, a card sent, banished or
 * moved by hand, a Special Summon, a draw, the end of the turn.
 */
object PuzzleReferee {
    sealed interface Verdict {
        /** [actions] are what is committed (the move and what follows from it); [turn] the facts after it. */
        data class Admit(val actions: List<DuelAction>, val turn: PuzzleTurn, val move: Boolean) : Verdict
        data class Refuse(val why: String) : Verdict
    }

    private const val ONLY = "In a puzzle only the turn's own moves are played: summons and Tributes, position changes, " +
        "the phases, attacks and the Spells in your hand. Life points and cards move only by battle and those Spells."

    fun admit(s: DuelState, turn: PuzzleTurn, seat: Int, actions: List<DuelAction>, catalog: DuelCatalog, budget: Int): Verdict {
        val talk = actions.filter { social(it) }
        val moves = actions.filterNot { social(it) }
        if (moves.isEmpty()) return Verdict.Admit(actions, turn, move = false)
        if (turn.moves >= budget) return Verdict.Refuse("The puzzle's $budget moves are spent.")
        val them = 1 - seat
        val main = s.phase == DuelPhase.MAIN1 || s.phase == DuelPhase.MAIN2
        fun ok(list: List<DuelAction>, next: PuzzleTurn) = Verdict.Admit(talk + list, next.copy(moves = next.moves + 1), move = true)
        if (s.active != seat) return Verdict.Refuse("It is not your turn.")

        // A Spell of the puzzle's from the hand: the move into the zone (and its chain link) becomes its whole resolution.
        val spellMove = moves.firstOrNull { a ->
            a is DuelAction.Move && a.how == "activate" && inHand(s, a.uid, seat) && PuzzleCards.effects.containsKey(s.cards[a.uid]?.code) &&
                a.to.let { it is Place.Zone && it.kind == ZoneKind.SPELL && it.seat == seat }
        } as DuelAction.Move?
        if (spellMove != null && moves.all { it == spellMove || (it is DuelAction.ChainAdd && it.uid == spellMove.uid) }) {
            if (!main) return Verdict.Refuse("A Normal Spell is activated in your Main Phase.")
            if (turn.tributes > 0) return Verdict.Refuse("Finish the Tribute Summon first: Tributes are paid as the monster is summoned.")
            val effect = PuzzleCards.effects.getValue(s.cards.getValue(spellMove.uid).code)
            val zone = spellMove.to as Place.Zone
            val result = resolve(s, seat, effect, catalog) ?: return Verdict.Refuse(cannot(effect))
            return ok(
                listOf(DuelAction.Move(spellMove.uid, zone, CardPosition.FACE_UP_ATK, "activate")) + result +
                    DuelAction.Move(spellMove.uid, Place.Pile(seat, PileKind.GY), how = "resolve"),
                turn,
            )
        }
        if (moves.size != 1) return Verdict.Refuse(ONLY)
        return when (val a = moves.single()) {
            is DuelAction.Phase -> {
                FxRules.phaseRefusal(s, a.phase)?.let { return Verdict.Refuse(it) }
                if (turn.tributes > 0) Verdict.Refuse("Finish the Tribute Summon first.") else ok(listOf(a), turn)
            }
            DuelAction.EndTurn -> Verdict.Refuse("The puzzle is this turn: stop when you are done and say DONE; the goal is checked on the table as it stands.")
            is DuelAction.Move -> {
                val card = s.cards[a.uid] ?: return Verdict.Refuse(ONLY)
                val from = s.placeOf(a.uid)
                val to = a.to
                when {
                    // A Normal Summon or Set from the hand.
                    from is Place.Pile && from.kind == PileKind.HAND && from.seat == seat && to is Place.Zone && to.seat == seat &&
                        to.kind == ZoneKind.MONSTER && (a.how == "normal" || a.how == "set") -> {
                        // The game's rules for a Normal Summon are FxRules' (Phase D: one list); the zone is the table's.
                        val info = catalog.info(card.code)
                        val facts = info?.let { FxFacts.of(card.code, it) }
                        FxRules.normalRefusal(facts, null, s.phase, used = if (turn.normalUsed) 1 else 0)?.let { return Verdict.Refuse(it) }
                        if (a.over || s.at(to) != null) return Verdict.Refuse("That Monster Zone is taken: summon it to a free one.")
                        if (info?.kind != CardKind.MONSTER) return Verdict.Refuse(FxRules.NOT_MAIN_DECK_MONSTER)
                        val need = tributesFor(info.level ?: 0)
                        if (FxRules.tributeRefusal(info.level ?: 0, null, turn.tributes) != null) {
                            return Verdict.Refuse(
                                if (need == 0) "A Level ${info.level} monster needs no Tribute." else
                                    "A Level ${info.level} monster needs $need Tribute${if (need == 1) "" else "s"}: send ${if (need == 1) "a monster" else "$need monsters"} of yours to the GY first (g m1), then summon it.",
                            )
                        }
                        ok(listOf(a), turn.copy(normalUsed = true, summoned = turn.summoned + a.uid, tributes = 0))
                    }
                    // A Tribute: your monster to the GY, for the Normal Summon about to be made.
                    from is Place.Zone && from.seat == seat && from.kind == ZoneKind.MONSTER && card.controller == seat &&
                        to is Place.Pile && to.kind == PileKind.GY -> {
                        if (!main) return Verdict.Refuse("A monster leaves the field only by battle, a Tribute or a card's effect.")
                        val most = if (turn.normalUsed) 0 else s.seats[seat].hand.maxOfOrNull { u ->
                            catalog.info(s.cards[u]?.code ?: 0)?.takeIf { it.kind == CardKind.MONSTER }?.level?.let(::tributesFor) ?: 0
                        } ?: 0
                        if (turn.tributes + 1 > most) return Verdict.Refuse("A monster leaves the field only by battle, a Tribute for a monster in your hand that needs one, or a card's effect.")
                        ok(listOf(a.copy(how = "tribute")), turn.copy(tributes = turn.tributes + 1))
                    }
                    a.how == "special" -> Verdict.Refuse("Nothing here lets you Special Summon.")
                    else -> Verdict.Refuse(ONLY)
                }
            }
            is DuelAction.Position -> {
                val card = s.cards[a.uid] ?: return Verdict.Refuse(ONLY)
                val at = s.placeOf(a.uid)
                if (card.controller != seat || at !is Place.Zone || at.kind != ZoneKind.MONSTER) return Verdict.Refuse("Only your own monsters change position.")
                if (!main) return Verdict.Refuse("A monster changes position in your Main Phase.")
                if (turn.tributes > 0) return Verdict.Refuse("Finish the Tribute Summon first.")
                if (a.uid in turn.summoned) return Verdict.Refuse("A monster does not change position the turn it was summoned.")
                if (a.uid in turn.changed) return Verdict.Refuse("A monster changes position once a turn.")
                if (a.uid in turn.attacked) return Verdict.Refuse("A monster that attacked does not change position that turn.")
                val legal = when (card.pos) {
                    CardPosition.FACE_DOWN_DEF -> a.pos == CardPosition.FACE_UP_ATK
                    CardPosition.FACE_UP_ATK -> a.pos == CardPosition.FACE_UP_DEF
                    CardPosition.FACE_UP_DEF -> a.pos == CardPosition.FACE_UP_ATK
                    CardPosition.FACE_DOWN_ATK -> false
                }
                if (!legal) return Verdict.Refuse(
                    if (card.pos == CardPosition.FACE_DOWN_DEF) "A face-down monster is Flip Summoned face-up in Attack Position (s m1)." else "A face-up monster turns face-down only by a card's effect.",
                )
                ok(listOf(a), turn.copy(changed = turn.changed + a.uid))
            }
            is DuelAction.Attack -> {
                if (s.phase != DuelPhase.BATTLE) return Verdict.Refuse("Attacks are declared in the Battle Phase (bp).")
                if (a.seat != seat || !DuelVerbs.canAttack(s, seat, a.attacker)) return Verdict.Refuse("A monster you control attacks from face-up Attack Position.")
                if (a.attacker in turn.attacked || s.attacks.any { it.attacker == a.attacker }) return Verdict.Refuse("Each monster attacks once a turn.")
                val theirs = PuzzleGoal.monsters(s, them)
                val target = a.target
                if (target == null && theirs.isNotEmpty()) return Verdict.Refuse("A direct attack only when they control no monster.")
                if (target != null && target !in theirs) return Verdict.Refuse("Attack a monster they control, or directly.")
                // Battle, by the printed numbers: a face-down monster is turned face-up as it is attacked.
                val flip = target?.takeIf { s.cards[it]?.faceUp == false }?.let { listOf(DuelAction.Position(it, CardPosition.FACE_UP_DEF)) }.orEmpty()
                val declared = DuelRules.applyAll(s, flip + a, seat).first ?: return Verdict.Refuse("The table cannot hold that attack.")
                val outcome = DuelBattle.outcome(declared, Attack(a.seat, a.attacker, a.target), catalog)
                    ?: return Verdict.Refuse("The referee cannot read this battle's numbers.")
                ok(flip + a + DuelBattle.actions(declared, outcome), turn.copy(attacked = turn.attacked + a.attacker))
            }
            else -> Verdict.Refuse(ONLY)
        }
    }

    /** Tributes a Normal Summon of a monster of [level] needs: [FxRules.tributes], the one list. */
    fun tributesFor(level: Int): Int = FxRules.tributes(level)

    /** Talk, which never changes the table: always admitted, never counted. */
    fun social(a: DuelAction): Boolean = a.social || a is DuelAction.Target

    private fun inHand(s: DuelState, uid: Int, seat: Int) = s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.HAND && it.seat == seat }

    /** The Spell's effect as table moves, or null when it cannot be activated now. */
    private fun resolve(s: DuelState, seat: Int, e: PuzzleEffect, catalog: DuelCatalog): List<DuelAction>? {
        fun destroy(uids: List<Int>) = uids.map { u -> DuelAction.Move(u, Place.Pile(s.cards.getValue(u).owner, PileKind.GY), how = "destroy") }
        val them = 1 - seat
        return when (e.kind) {
            PuzzleEffect.Kind.DESTROY_THEIRS -> PuzzleGoal.monsters(s, them).takeIf { it.isNotEmpty() }?.let(::destroy)
            PuzzleEffect.Kind.DESTROY_ALL -> (PuzzleGoal.monsters(s, seat) + PuzzleGoal.monsters(s, them)).takeIf { it.isNotEmpty() }?.let(::destroy)
            PuzzleEffect.Kind.DESTROY_THEIR_LOWEST -> {
                val up = PuzzleGoal.monsters(s, them).filter { s.cards[it]?.faceUp == true }
                val atk = up.associateWith { u -> s.cards.getValue(u).let { it.atk ?: catalog.info(it.code)?.atk ?: 0 } }
                val low = atk.values.minOrNull() ?: return null
                val lowest = atk.filterValues { it == low }.keys.toList()
                // A tie is the player's to choose: no puzzle asks it, so it is never guessed.
                lowest.singleOrNull()?.let { destroy(listOf(it)) }
            }
            PuzzleEffect.Kind.DAMAGE -> buildList {
                if (e.them > 0) add(DuelAction.Lp(them, delta = -e.them))
                if (e.you > 0) add(DuelAction.Lp(seat, delta = -e.you))
            }
        }
    }

    private fun cannot(e: PuzzleEffect): String = when (e.kind) {
        PuzzleEffect.Kind.DESTROY_THEIR_LOWEST -> "It needs one face-up monster of theirs with the lowest ATK."
        else -> "It has nothing to destroy."
    }
}

/**
 * A puzzle's table (Phase C stage 3): the position, the referee, and the three duel tools Ai plays it with — the brief
 * (`duel_state`), the menu of legal moves (`duel_moves`, the referee's moves only) and the moves (`duel_act`, each op
 * planned on the table as `duel_act` plans a line at kai's table, held to [com.kaiharimoto.mastertool.core.duel.DuelReach],
 * then admitted by the [PuzzleReferee]). Ai's moves carry its provenance. Graded on the table: [grade].
 */
class PuzzleTable(val puzzle: Puzzle, private val catalog: DuelCatalog = PuzzleCards.catalog) {
    var game: DuelGame = Puzzles.start(puzzle)
        private set
    var turn = PuzzleTurn()
        private set
    /** The ops admitted, in order: the line a grade replays. */
    val lines = mutableListOf<String>()

    private val seat = PuzzleGoal.YOU
    private val by = Provenance(Provenance.AI, aiSeat = seat, aiKnows = DuelBrief.SELF)
    val state: DuelState get() = game.state

    /** One op played: what happened, in words, and whether it was admitted. */
    data class Played(val line: String, val ok: Boolean, val said: String)

    /** Plays [ops] in order (each split at `;`), stopping at the first refused. */
    fun play(ops: List<String>): List<Played> {
        val out = mutableListOf<Played>()
        for (op in ops.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }) {
            val p = playOne(op, commit = true)
            out += p
            if (!p.ok) break
        }
        return out
    }

    /** Whether [op] would be admitted now, without playing it. */
    fun admits(op: String): Boolean = playOne(op, commit = false).ok

    private fun playOne(op: String, commit: Boolean): Played {
        val g = game
        val plan = ComboRunner.plan(g.state, seat, listOf(op), catalog, g.header.seed)
        if (!plan.ok) return Played(op, false, plan.problem ?: "That is not a move.")
        ComboRunner.reach(g.state, seat, plan)?.let { return Played(op, false, it) }
        var now = g
        var facts = turn
        for ((_, actions) in plan.steps) {
            when (val v = PuzzleReferee.admit(now.state, facts, seat, actions, catalog, puzzle.budget)) {
                is PuzzleReferee.Verdict.Refuse -> return Played(op, false, v.why)
                is PuzzleReferee.Verdict.Admit -> {
                    val r = now.act(v.actions, seat, by = by)
                    if (!r.ok) return Played(op, false, r.problem ?: "The table refused it.")
                    now = r.game
                    facts = v.turn
                }
            }
        }
        if (commit) {
            game = now
            turn = facts
            lines += op
        }
        return Played(op, true, "Played")
    }

    /** The goal checked on the table now. */
    fun met(): Pair<Boolean, String> = puzzle.goal.met(state, catalog)

    /**
     * The grade: the goal met on the table, and the same lines, replayed on a fresh table under the referee, reaching the
     * same table — so a grade never rests on anything but legal moves.
     */
    fun grade(): Graded {
        val (ok, read) = met()
        val again = PuzzleTable(puzzle, catalog).also { it.play(lines) }
        val same = again.state == state && again.lines == lines
        val pass = ok && same
        return Graded(pass, (if (pass) "met: " else "not met: ") + read + " after ${turn.moves} move${if (turn.moves == 1) "" else "s"}" +
            if (!same) " (the moves did not replay to this table)" else "")
    }

    /** The table for `duel_state`: the puzzle, its rules in a line, the Spells' text, then the brief as at kai's table. */
    fun brief(): String = buildString {
        appendLine("Puzzle ${puzzle.id}, “${puzzle.title}”. Goal: ${puzzle.goal.words}. Moves left: ${puzzle.budget - turn.moves} of ${puzzle.budget}.")
        if (turn.tributes > 0) appendLine("Tributes paid: ${turn.tributes}, for the Normal Summon you make next.")
        if (turn.normalUsed) appendLine("Your Normal Summon for this turn is used.")
        val spells = (state.seats[seat].hand.mapNotNull { state.cards[it]?.code }).distinct().mapNotNull { c ->
            PuzzleCards.effects[c]?.let { "${PuzzleCards.name(c)} (Normal Spell): ${it.text}" }
        }
        if (spells.isNotEmpty()) appendLine("Your Spells: " + spells.joinToString(" "))
        appendLine()
        append(DuelBrief.describe(state, seat, catalog, game.header.seed, seat))
    }

    /** The legal moves for `duel_moves`: the table's menu, kept to what the referee admits. */
    fun menu(cap: Int = DuelMoves.CAP): String {
        val groups = DuelMoves.menu(state, seat, catalog, game.header.seed)
            .map { g -> g.copy(moves = g.moves.filter { admits(it.line) }) }
            .filter { it.moves.isNotEmpty() }
        if (groups.isEmpty()) return "No moves are left: say DONE."
        return "Your legal moves (each `op` exactly as duel_act takes it; add a zone to choose one, s h1 m2):\n" + DuelMoves.words(groups, cap)
    }

    /** A puzzle's tool call, answered: the text and whether it is an error. */
    fun tool(name: String, input: JsonObject): Pair<String, Boolean> = when (name.removePrefix("mcp__neue__")) {
        "duel_state" -> brief() to false
        "duel_moves" -> menu((ToolArgs.int(input, "limit") ?: DuelMoves.CAP).coerceIn(10, 600)) to false
        "duel_act" -> {
            val ops = ToolArgs.strings(input, "ops").filter { it.isNotBlank() }
            if (ops.isEmpty()) "No ops to play." to true
            else {
                val played = play(ops)
                val text = played.joinToString("\n") { p -> if (p.ok) "✓ ${p.line}" else "✗ ${p.line}: ${p.said}" }
                val refused = played.any { !it.ok }
                "$text\n\nThe table now:\n${brief()}" to refused
            }
        }
        else -> "Only duel_state, duel_moves and duel_act are played in a puzzle." to true
    }

    companion object {
        /** The tools a puzzle is played with: these three, nothing that looks anything up. */
        val TOOLS: Set<String> = setOf("duel_state", "duel_moves", "duel_act")

        /** What every puzzle is played under: the referee's rules in words. */
        const val RULES = "You are solving a duel puzzle on a table of its own. It is your turn, Main Phase 1; your opponent has no " +
            "cards in hand and nothing set, and will not respond. A referee holds you to the game's rules: one Normal Summon or Set " +
            "a turn (a Level 5 or 6 monster needs 1 Tribute, Level 7 or more 2: send that many of your monsters to the GY with g m1, " +
            "then summon it at once with s h1); a monster changes position once a turn (p m1), not the turn it was summoned nor after " +
            "it attacked, and a face-down one is Flip Summoned with s m1; the phases only go forward (bp, m2); in the Battle Phase " +
            "each face-up Attack Position monster attacks once, a monster of theirs (a m1 om1) or directly only when they control " +
            "none (a m1 direct), and the referee applies the battle damage and destruction as you attack; a Spell in your hand is " +
            "activated in a Main Phase (activate raigeki, or a h1) and the referee resolves it as its text says. Nothing else moves " +
            "a card or a life point: a move outside these is refused and says why. Read the table with duel_state, list the legal " +
            "moves with duel_moves, play with duel_act. Moves are counted against the puzzle's budget. When you are done, reply DONE."
    }
}

/**
 * The puzzle set's bounds (Phase C stage 3, its baseline score): what doing nothing, a battle-only greedy player and the
 * recorded solutions score, each played through the same [PuzzleTable] and graded the same way.
 */
object PuzzleBaselines {
    /** Nothing played. */
    fun nothing(p: Puzzle): Graded = PuzzleTable(p).grade()

    /** The recorded solution. */
    fun solved(p: Puzzle): Graded = PuzzleTable(p).also { it.play(p.solution) }.grade()

    /**
     * A battle-only greedy player: into the Battle Phase, then each monster, strongest first, attacks the weakest monster of
     * theirs it destroys without being destroyed or taking damage — or directly when they control none. No summons, no
     * Spells, no position changes: the obvious line, for the set's middle bound.
     */
    fun greedy(p: Puzzle): Graded {
        val t = PuzzleTable(p)
        t.play(listOf("bp"))
        val info = PuzzleCards.catalog
        fun atk(uid: Int) = t.state.cards[uid]?.let { info.info(it.code)?.atk } ?: 0
        fun def(uid: Int) = t.state.cards[uid]?.let { info.info(it.code)?.def } ?: 0
        val attackers = PuzzleGoal.monsters(t.state, PuzzleGoal.YOU).filter { u -> t.state.cards[u]?.let { it.faceUp && !it.defense } == true }
            .sortedByDescending(::atk)
        for (a in attackers) {
            if (t.state.cards[a] == null || t.state.placeOf(a) !is Place.Zone) continue
            val mine = atk(a)
            val theirs = PuzzleGoal.monsters(t.state, PuzzleGoal.THEM)
            val beaten = theirs.filter { u -> t.state.cards[u]?.let { c -> c.faceUp && (if (c.defense) mine > def(u) else mine > atk(u)) } == true }
            val pick = beaten.minByOrNull { u -> if (t.state.cards.getValue(u).defense) def(u) else atk(u) }
            val from = coord(t, a) ?: continue
            when {
                pick != null -> coord(t, pick)?.let { t.play(listOf("a $from $it")) }
                theirs.isEmpty() -> t.play(listOf("a $from direct"))
            }
        }
        return t.grade()
    }

    private fun coord(t: PuzzleTable, uid: Int): String? =
        DuelNotation.coordOf(t.state, uid, PuzzleGoal.YOU, t.game.header.seed)

    /** How many of the set each bound passes: nothing, greedy, solved. */
    fun bounds(all: List<Puzzle> = Puzzles.all): Triple<Int, Int, Int> =
        Triple(all.count { nothing(it).pass }, all.count { greedy(it).pass }, all.count { solved(it).pass })
}
