package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.NameScore
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

/** A combo played step by step: each step's actions, or where and why it stopped. */
data class ComboRun(val steps: List<Pair<String, List<DuelAction>>>, val stoppedAt: Int? = null, val problem: String? = null) {
    val ok: Boolean get() = problem == null
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
    fun plan(s: DuelState, seat: Int, steps: List<String>, catalog: DuelCatalog): ComboRun {
        var state = s
        val out = mutableListOf<Pair<String, List<DuelAction>>>()
        steps.forEachIndexed { i, text ->
            when (val p = DuelCommand.parse(text, state, seat, catalog)) {
                is DuelCommand.Parsed.Problem -> return ComboRun(out, i, "Step ${i + 1} (“$text”): ${p.text}")
                // A house ruling is not a move: a combo has no use for one.
                is DuelCommand.Parsed.Ruling -> return ComboRun(out, i, "Step ${i + 1} (“$text”): a ruling is kept with duel_ruling, not played")
                is DuelCommand.Parsed.Actions -> {
                    val (next, why) = DuelRules.applyAll(state, p.actions, seat)
                    if (next == null) return ComboRun(out, i, "Step ${i + 1} (“$text”): $why")
                    out += text to p.actions
                    state = next
                }
            }
        }
        return ComboRun(out)
    }
}

/**
 * A span of the log turned back into steps a combo can replay: every move written as the command
 * line would say it, with names for cards, so it plays against any shuffle.
 */
object ComboRecorder {
    fun steps(start: DuelState, entries: List<DuelEntry>, catalog: DuelCatalog): List<String> {
        var s = start
        val out = mutableListOf<String>()
        entries.forEach { e ->
            command(s, e.action, catalog)?.let { out += it }
            s = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
        }
        return out
    }

    fun command(s: DuelState, a: DuelAction, catalog: DuelCatalog): String? {
        fun n(uid: Int) = s.cards[uid]?.let { catalog.nameOf(it) } ?: "#$uid"
        return when (a) {
            is DuelAction.Move -> {
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
            else -> null
        }
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
