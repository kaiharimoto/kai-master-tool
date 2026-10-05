package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelReach
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.AnswerChooser
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.duel.text.NameScore
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAnswers
import com.kaiharimoto.mastertool.core.duel.text.ShortcutLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A combo: a line a deck plays, kept with the deck (`<data>/duel/combos/<deckId>.json`) — what it needs
 * in hand to start, and its steps as the command line says them ("summon aluber to m3", "chain ash").
 * Steps name cards, never uids, so a combo plays against any shuffle; written by hand, recorded from a
 * span of the log ([ComboRecorder]), or by Ai, and played out by [ComboRunner] a step at a time.
 */
@Serializable
data class Combo(
    val id: String,
    val name: String,
    val deckId: String? = null,
    val needs: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val notes: String = "",
    val created: Long = 0L,
)

@Serializable
data class ComboBook(val combos: List<Combo> = emptyList(), val version: Int = 1)

object ComboCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; prettyPrint = true }
    fun encode(b: ComboBook): String = json.encodeToString(ComboBook.serializer(), b)
    fun decode(text: String): ComboBook = runCatching { json.decodeFromString(ComboBook.serializer(), text) }.getOrElse { ComboBook() }

    /** The file a deck's combos live in, under `<data>/duel/`. */
    fun path(deckId: String): String = "combos/${deckId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "deck" }}.json"
}

/**
 * A combo played step by step: each step's actions, or where and why it stopped. [fx]: a Shortcut step's engine tags, by
 * the step's place in [steps], one per action — committed with it (`DuelGame.act`'s `fx`) so its entries carry them.
 */
data class ComboRun(
    val steps: List<Pair<String, List<DuelAction>>>,
    val stoppedAt: Int? = null,
    val problem: String? = null,
    val fx: Map<Int, List<FxTag?>> = emptyMap(),
) {
    val ok: Boolean get() = problem == null

    /** Step [i]'s tags: empty for a step made by hand. */
    fun tags(i: Int): List<FxTag?> = fx[i].orEmpty()
}

object ComboRunner {
    /** The cards a combo needs that the seat does not hold in its hand (by name, loosely). */
    fun missing(s: DuelState, seat: Int, combo: Combo, catalog: DuelCatalog): List<String> {
        val hand = s.seats[seat].hand.map { catalog.nameOf(s.cards.getValue(it)) }.toMutableList()
        return combo.needs.filter { need ->
            val hit = hand.firstOrNull { NameScore.of(need, it) > 0 }
            if (hit != null) { hand.remove(hit); false } else true
        }
    }

    /**
     * Each step parsed on the table as the steps before it left it — dry-run all the way, so a combo
     * that would stop halfway says where before anything moves.
     */
    fun plan(
        s: DuelState,
        seat: Int,
        steps: List<String>,
        catalog: DuelCatalog,
        secret: Long = 0L,
        /** The table's written effects, for a Shortcut step (`u h2 e1 pick=…`); without them such a step stops the plan. */
        shortcuts: Shortcuts? = null,
        /** Who answers a Shortcut's choice its step does not settle; null: the plan stops and asks it back. */
        guess: Chooser? = null,
    ): ComboRun {
        var state = s
        val out = mutableListOf<Pair<String, List<DuelAction>>>()
        val tags = mutableMapOf<Int, List<FxTag?>>()
        // The engine's state carried along the plan: a Shortcut's own, else folded over the moves made by hand.
        var written = shortcuts
        fun byHand(before: DuelState, actions: List<DuelAction>) { written = written?.after(before, actions, seat) }
        steps.forEachIndexed { i, text ->
            // A combo's steps name cards, never places: copies on the field are taken in turn (anyCopy). [secret] is the
            // duel's (its seed), so `oh2` is the card the brief and DuelView show there (Phase C stage 2; it was 0: another order).
            when (val p = DuelCommand.parse(text, state, seat, catalog, secret, anyCopy = true)) {
                is DuelCommand.Parsed.Problem -> return ComboRun(out, i, "Step ${i + 1} (“$text”): ${p.text}")
                // A house ruling is not a move: a combo has no use for one.
                is DuelCommand.Parsed.Ruling -> return ComboRun(out, i, "Step ${i + 1} (“$text”): a ruling is kept with duel_ruling, not played")
                is DuelCommand.Parsed.Actions -> {
                    val (next, why) = DuelRules.applyAll(state, p.actions, seat)
                    if (next == null) return ComboRun(out, i, "Step ${i + 1} (“$text”): $why", tags)
                    out += text to p.actions
                    byHand(state, p.actions)
                    state = next
                }
                // Moves joined with ";": each its own step, so a phase change is never one gesture with a move (1.0.87).
                is DuelCommand.Parsed.Many -> p.parts.forEachIndexed { k, part ->
                    val (next, why) = DuelRules.applyAll(state, part.actions, seat)
                    if (next == null) return ComboRun(out, i, "Step ${i + 1} (“${p.lines.getOrElse(k) { text }}”): $why", tags)
                    out += p.lines.getOrElse(k) { text } to part.actions
                    byHand(state, part.actions)
                    state = next
                }
                // A Shortcut (Phase D §5½): the engine makes it with the choices the step gives, its tags kept with it.
                is DuelCommand.Parsed.Shortcut -> {
                    val sc = written ?: return ComboRun(out, i, "Step ${i + 1} (“$text”): $NO_SHORTCUTS", tags)
                    val r = ShortcutLine.answered(p.ask, sc, state, seat, catalog, secret, guess)
                    r.problem?.let { return ComboRun(out, i, "Step ${i + 1} (“$text”): $it", tags) }
                    val (next, why) = DuelRules.applyAll(state, r.actions, seat)
                    if (next == null || r.actions.isEmpty()) return ComboRun(out, i, "Step ${i + 1} (“$text”): ${why ?: "nothing to do"}", tags)
                    tags[out.size] = r.tags
                    out += text to r.actions
                    written = sc.withFx(r.fx)
                    state = next
                }
                is DuelCommand.Parsed.Query, is DuelCommand.Parsed.Ui ->
                    return ComboRun(out, i, "Step ${i + 1} (“$text”): a question or the table's chrome, not a move", tags)
            }
        }
        return ComboRun(out, fx = tags)
    }

    /** A Shortcut step at a table with no written effects. */
    const val NO_SHORTCUTS = Shortcuts.NONE_AT_TABLE

    /**
     * A planned line held to what a player may do to cards it cannot see ([DuelReach], Phase C): Ai's lines are, as the
     * network's guest is — its knowledge covers what it reads, never what it does. The first step that would take, turn
     * up, show or target a card hidden from [seat], with why; null when none would.
     */
    /**
     * A planned line's words — what it says (`say`, `note`), the locks it writes, its chain links' notes — kept from naming
     * [seat]'s cards its opponent cannot see ([Secrets]; Phase C stage 2: Ai's text after a `;` and in a combo's steps
     * reached the record as typed, since only an op that began with `say` was guarded). Each step is judged on the table
     * the steps before it leave; the moves themselves are unchanged.
     */
    fun redacted(s: DuelState, seat: Int, run: ComboRun, catalog: DuelCatalog): ComboRun {
        if (s.solo) return run
        var state = s
        val steps = run.steps.map { (text, actions) ->
            fun hide(words: String) = Secrets.redact(words, state, 1 - seat, seat, catalog).text
            val said = actions.map { a ->
                when (a) {
                    is DuelAction.Chat -> a.copy(text = hide(a.text))
                    is DuelAction.Note -> a.copy(text = hide(a.text))
                    is DuelAction.Lock -> a.copy(text = hide(a.text))
                    is DuelAction.ChainAdd -> if (a.note.isBlank()) a else a.copy(note = hide(a.note))
                    else -> a
                }
            }
            val shown = if (said != actions) hide(text) else text
            state = DuelRules.applyAll(state, said, seat).first ?: state
            shown to said
        }
        return run.copy(steps = steps)
    }

    fun reach(s: DuelState, seat: Int, run: ComboRun): String? {
        var state = s
        run.steps.forEachIndexed { i, (text, actions) ->
            DuelReach.check(state, seat, actions)?.let { return "Step ${i + 1} (“$text”): $it" }
            state = DuelRules.applyAll(state, actions, seat).first ?: return null
        }
        return null
    }
}

/**
 * A span of the log turned back into steps a combo can replay: every move written as the command
 * line would say it, with names for cards, so it plays against any shuffle.
 *
 * Recorded for a [seat] (Phase C, the red team's lead: "duel_combo records name hidden cards Ai touched"), a card is
 * named only when that seat knew it — saw it before or after the move, or it came from its own Deck or Extra Deck,
 * whose list it knows; any other is written by where it stood ("os2"), and a step with no such place is left out. A
 * combo is kept with a deck and read back by anyone later, so it holds nothing the seat could not have written.
 */
object ComboRecorder {
    fun steps(start: DuelState, entries: List<DuelEntry>, catalog: DuelCatalog, seat: Int? = null, secret: Long = 0L): List<String> {
        var s = start
        val out = mutableListOf<String>()
        // A Shortcut is written back as one step (Phase D §5½), never as the moves the engine made: the whole group it made
        // — a resolve-all's links made by hand too — is that one step, written as the group begins.
        val kinds = entries.groupBy { it.group }.mapValues { (_, g) -> shortcutKind(g) }
        val written = HashSet<Int>()
        entries.forEachIndexed { k, e ->
            val after = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
            val before = s
            val knows: (Int) -> Boolean = if (seat == null) { _ -> true } else { uid -> knew(before, after, uid, seat) }
            val coord: (Int) -> String? = { uid -> seat?.let { DuelNotation.coordOf(before, uid, it, secret) } }
            when {
                e.group in written -> Unit
                kinds[e.group] != null -> {
                    written += e.group
                    shortcut(before, entries.drop(k).takeWhile { it.group == e.group }, catalog, seat, secret)?.let { out += it }
                }
                else -> command(before, e.action, catalog, knows, coord)?.let { out += it }
            }
            s = after
        }
        return out
    }

    /** Whether a group holds a Shortcut's use (true) or only a resolution as written (false), or neither (null): a summon's procedure. */
    private fun shortcutKind(group: List<DuelEntry>): Boolean? {
        val tags = group.mapNotNull { it.fx }
        return when {
            tags.any { it.part == FxTag.COST || it.part == FxTag.ACTIVATE } -> true
            tags.any { it.part == FxTag.RESOLVE } -> false
            else -> null
        }
    }

    /**
     * A Shortcut's [group] as one step, on the table [start] before it: `u <card> <effect>` with the cards and zones it chose
     * (`pick=`, `target=`, `zone=`), so a combo makes the same choices against any shuffle; or `resolve by shortcut`
     * (`resolve all by shortcut` when it resolved more than one link). Each card is named as [seat] knew it, else by its
     * place; null when the card used cannot be written for [seat].
     */
    private fun shortcut(start: DuelState, group: List<DuelEntry>, catalog: DuelCatalog, seat: Int?, secret: Long): String? {
        if (shortcutKind(group) == false) {
            return if (group.count { it.action == DuelAction.ChainResolve } > 1) "resolve all by shortcut" else "resolve by shortcut"
        }
        val tag = group.mapNotNull { it.fx }.first { it.part == FxTag.COST || it.part == FxTag.ACTIVATE }
        var name: String? = null
        val picks = mutableListOf<String>()
        val targets = mutableListOf<String>()
        val zones = mutableListOf<String>()
        val positions = mutableListOf<String>()
        var s = start
        group.forEach { e ->
            val before = s
            val after = (DuelRules.apply(before, e.action, e.seat) as? Outcome.Ok)?.state ?: before
            fun n(uid: Int): String? = when {
                seat == null || knew(before, after, uid, seat) -> before.cards[uid]?.let { catalog.nameOf(it) }
                else -> DuelNotation.coordOf(before, uid, seat, secret)
            }
            if (name == null && tag.uid in before.cards) name = n(tag.uid)
            if (e.fx != null) when (val a = e.action) {
                is DuelAction.ChainAdd -> a.targets.forEach { t -> n(t)?.let { targets += it } }
                is DuelAction.Target -> if (a.on) a.to.forEach { t -> n(t)?.let { targets += it } }
                is DuelAction.Move -> {
                    if (a.uid != tag.uid) n(a.uid)?.let { if (it !in picks) picks += it }
                    (a.to as? Place.Zone)?.let { z ->
                        DuelNotation.slotCoord(z, e.seat ?: seat ?: 0)?.let { zones += it }
                        // A monster summoned: its position goes with its zone (kai: "what zone they summon to, what position, all matters").
                        if ((z.kind == ZoneKind.MONSTER || z.kind == ZoneKind.EMZ) && before.placeOf(a.uid) !is Place.Zone) a.pos?.let { positions += AnswerChooser.positionWord(it) }
                    }
                }
                is DuelAction.Token -> {
                    DuelNotation.slotCoord(a.to, e.seat ?: seat ?: 0)?.let { zones += it }
                    positions += AnswerChooser.positionWord(a.pos)
                }
                else -> Unit
            }
            s = after
        }
        val card = name ?: return null
        return "u $card ${tag.effect}" + ShortcutAnswers(pick = picks, target = targets.distinct(), zone = zones, pos = positions).words()
    }

    /** Whether [seat] knew [uid] across a move: seen before or after it, or a card of its own Deck or Extra Deck. */
    fun knew(before: DuelState, after: DuelState, uid: Int, seat: Int): Boolean {
        if (DuelSight.sees(before, uid, seat) || DuelSight.sees(after, uid, seat)) return true
        val own = before.cards[uid]?.owner == seat
        return own && before.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) }
    }

    /**
     * [a] as the command line says it. A card [knows] refuses is written as [coord] gives it, and the step is left out
     * (null) when it gives none.
     */
    fun command(
        s: DuelState,
        a: DuelAction,
        catalog: DuelCatalog,
        knows: (Int) -> Boolean = { true },
        coord: (Int) -> String? = { null },
    ): String? {
        var unnamed = false
        fun n(uid: Int): String = when {
            knows(uid) -> s.cards[uid]?.let { catalog.nameOf(it) } ?: "#$uid"
            else -> coord(uid) ?: "".also { unnamed = true }
        }
        val said = when (a) {
            // A card going to the GY as its chain resolves is the resolve step's own doing.
            is DuelAction.Move -> if (a.how == "resolve" || a.how == "negate") null else {
                val name = n(a.uid)
                val from = s.placeOf(a.uid)
                val fromWords = (from as? Place.Pile)?.kind?.let { " from ${word(it)}" } ?: ""
                when (val to = a.to) {
                    is Place.Zone -> {
                        val z = when (to.kind) {
                            ZoneKind.MONSTER -> "m${to.index + 1}"
                            ZoneKind.SPELL -> "s${to.index + 1}"
                            // Its controller's own left or right (1.0.79), read back the same way by zoneOf.
                            ZoneKind.EMZ -> if ((to.index == 0) == (to.seat == 0)) "emz left" else "emz right"
                            ZoneKind.FIELD -> "fz"
                        }
                        val verb = when {
                            a.how == "place" -> if (a.pos?.faceUp == false) "set" else "place"
                            a.pos?.faceUp == false -> "set"
                            from is Place.Zone -> "move"
                            a.how == "activate" && (to.kind == ZoneKind.SPELL || to.kind == ZoneKind.FIELD) -> "activate"
                            to.kind == ZoneKind.SPELL || to.kind == ZoneKind.FIELD -> "place"
                            a.how == "normal" -> "summon"
                            else -> "ss"
                        }
                        val def = if (a.pos == CardPosition.FACE_UP_DEF) " def" else ""
                        "$verb $name$fromWords$def to $z"
                    }
                    is Place.Pile -> when (to.kind) {
                        PileKind.DECK -> "$name$fromWords to deck${if (to.at == Place.BOTTOM) " bottom" else ""}"
                        PileKind.BANISHED -> if (a.pos?.faceUp == false) "bfd $name$fromWords" else "$name$fromWords to banished"
                        else -> "$name$fromWords to ${word(to.kind)}"
                    }
                    is Place.Under -> "attach $name$fromWords to ${n(to.host)}"
                    Place.Void -> null
                }
            }
            is DuelAction.Draw -> if (a.n == 1) "draw" else "draw ${a.n}"
            is DuelAction.Shuffle -> if (a.pile == PileKind.DECK) "shuffle" else "shuffle ${word(a.pile)}"
            is DuelAction.Position -> "${if (a.pos.faceUp != s.cards[a.uid]?.faceUp) "flip" else "pos"} ${n(a.uid)}"
            is DuelAction.ChainAdd -> a.uid?.let { "link ${n(it)}" }
            is DuelAction.Lp -> a.set?.let { "lp ${if (a.seat == 1) "opp " else ""}=$it" } ?: "lp ${if (a.seat == 1) "opp " else ""}${if (a.delta >= 0) "+" else ""}${a.delta}"
            is DuelAction.Phase -> when (a.phase) {
                DuelPhase.DRAW -> "dp"; DuelPhase.STANDBY -> "sp"; DuelPhase.MAIN1 -> "m1"
                DuelPhase.BATTLE -> "bp"; DuelPhase.MAIN2 -> "m2"; DuelPhase.END -> "ep"
            }
            is DuelAction.Token -> "token ${a.name}" + (a.atk?.let { " atk $it" } ?: "") + (a.def?.let { " def $it" } ?: "") + (if (a.pos == CardPosition.FACE_UP_ATK) " atk-pos" else "")
            is DuelAction.Counter -> if (a.delta > 0) "counter ${n(a.uid)}" else "uncounter ${n(a.uid)}"
            is DuelAction.Reveal -> a.uids.firstOrNull()?.let { "reveal ${n(it)}" }
            is DuelAction.ChainResolve -> "resolve"
            is DuelAction.ChainClear -> "clear chain"
            is DuelAction.Negate -> "negate ${a.link}"
            is DuelAction.Attack -> "${n(a.attacker)} attacks ${a.target?.let(::n) ?: "directly"}"
            else -> null
        }
        return said.takeUnless { unnamed }
    }

    /** What a recorded line needs in hand: the cards it plays from the hand that were there at the start. */
    fun needs(start: DuelState, seat: Int, entries: List<DuelEntry>, catalog: DuelCatalog): List<String> {
        val inHand = start.seats[seat].hand.toSet()
        return entries.mapNotNull { e ->
            val uid = when (val a = e.action) {
                is DuelAction.Move -> a.uid
                is DuelAction.ChainAdd -> a.uid
                else -> null
            }
            uid?.takeIf { it in inHand && DuelSight.sees(start, it, seat) }
        }.distinct().map { catalog.nameOf(start.cards.getValue(it)) }
    }

    private fun word(k: PileKind) = when (k) {
        PileKind.HAND -> "hand"
        PileKind.DECK -> "deck"
        PileKind.EXTRA -> "extra"
        PileKind.GY -> "gy"
        PileKind.BANISHED -> "banished"
    }
}
