package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ShortcutAsking
import com.kaiharimoto.mastertool.core.duel.ShortcutOption
import com.kaiharimoto.mastertool.core.duel.ShortcutResult
import com.kaiharimoto.mastertool.core.duel.ShortcutStep
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.DeclareKind
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.duel.effects.catalog
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAsk
import com.kaiharimoto.mastertool.core.duel.text.ShortcutWindow

/**
 * Shortcut at the table (Phase D §5½, §5¾), a part of [Duels]: the written effects the table is handed, and the Shortcut
 * window — the table's `Chooser` — while a Shortcut asks. The window asks **by replay** ([ShortcutAsking]): nothing is
 * committed until every choice is made, and then the whole use is one group, one undo, every entry tagged.
 *
 * [Duels] forwards what outside code reads under its own names.
 */
class DuelShortcuts internal constructor(private val d: Duels) {

    /**
     * The written effects the table is handed (Phase D step 2): the `Effects` holder's library, any status but broken —
     * null, as at every table before the library, and no card is offered a Shortcut. The studio and the tests hand in
     * their own.
     */
    var written: () -> Shortcuts? = { null }

    private class Memo(val game: DuelGame, val base: Shortcuts, val net: Boolean, val sc: Shortcuts)
    private var memo: Memo? = null

    /**
     * The table's Shortcuts now: the written effects at the duel shown, the engine's state folded from its log once a
     * game. None while a replay or Ai vs Ai is on the table; at a networked table they are listed, and refused in words.
     */
    fun now(): Shortcuts? {
        if (d.replayer.replay != null || d.matches.live != null) return null
        val base = written() ?: return null
        val g = d.shown ?: return null
        val net = d.network.role != null
        memo?.let { if (it.game === g && it.base === base && it.net == net) return it.sc }
        return base.at(g, networked = net).also { memo = Memo(g, base, net, it) }
    }

    /** A card the pool does not hold, named by the written effects' facts: the reserved range's samples. */
    fun catalogFallback(): DuelCatalog = written()?.facts?.catalog() ?: DuelCatalog.NONE

    // ---- the window ---------------------------------------------------------------------------------------------

    /** The Shortcut being asked for, and its answers so far. */
    var asking by mutableStateOf<ShortcutAsking?>(null)
        private set

    /** The question standing: what the window shows. */
    var question by mutableStateOf<ShortcutStep.Asking?>(null)
        private set

    val open: Boolean get() = question != null

    /** The cards picked so far for a card choice: indexes into its `among`. */
    var picks by mutableStateOf<List<Int>>(emptyList())

    /** What the keys stand on: a candidate, a row, a lit zone. */
    var cursor by mutableStateOf(0)

    /** The position chosen for the card being placed; answers the position question that follows the zone. */
    var position by mutableStateOf<CardPosition?>(null)

    /** The waiting triggers' order as the window has it (indexes into the decision's triggers). */
    var order by mutableStateOf<List<Int>>(emptyList())

    /** What is typed into the window: a coordinate, a label, a name; null while its field is shut. */
    var typed by mutableStateOf<String?>(null)

    /** Shift Q's two-choice strip (§5½ 3): By hand or By Shortcut, while a written link stands. */
    var resolveStrip by mutableStateOf(false)

    /** A word under the window: why Enter waits, or what a typed answer did not match. */
    var hint by mutableStateOf<String?>(null)

    /** Where the window stands on the table, in table dp, as last laid out: a press there is the window's, not the table's. */
    var windowAt: com.kaiharimoto.mastertool.core.layout.Slot? = null

    /** The pointer over a lit zone (§5¾.5): the zone the keys and Enter stand on, its card drawn there. */
    fun hoverZone(z: Place.Zone?) {
        val q = question?.decision as? Decision.Zone ?: return
        val i = z?.let { zz -> q.among.indexOfFirst { it.kind == zz.kind && it.index == zz.index && (it.kind == ZoneKind.EMZ || it.seat == zz.seat) } } ?: -1
        if (i >= 0 && i != cursor) cursor = i
    }

    /** The position the zone was answered with, waiting for the position question it settles. */
    private var settles: CardPosition? = null

    /** [uid]'s Shortcuts for the seat acting on it, legal or greyed with their rule. */
    fun options(uid: Int): List<ShortcutOption> {
        val sc = now() ?: return emptyList()
        val g = d.shown ?: return emptyList()
        return sc.options(g.state, d.seatFor(uid), uid)
    }

    /** [uid]'s Shortcut used (U, the strip, the inspector, a finger's menu): [effect] by id or name, or asked which. */
    fun use(uid: Int, effect: String? = null): Boolean = start(ShortcutAsking.use(uid, d.seatFor(uid), effect))

    /** The chain resolved as written: its newest link, or with [all] the whole chain (§5½ 3). */
    fun resolve(all: Boolean): Boolean {
        resolveStrip = false
        d.chainMenu = null
        return start(ShortcutAsking.resolve(d.bottom, all))
    }

    /** A Shortcut asked for, by a key, a menu, the line (with its answers) or Ai. */
    fun start(a: ShortcutAsking): Boolean {
        val why = when {
            d.matches.live != null -> DuelMatches.ON_THE_TABLE
            d.replayer.replay != null -> "A replay is open: close it to use a Shortcut"
            d.network.role != null -> DuelHost.NO_SHORTCUTS
            d.insertAfter != null -> "Insert here takes moves made by hand: make it by hand, or let Insert here go"
            else -> null
        }
        if (why != null) { d.problem = why; return false }
        if (now() == null) { d.problem = Shortcuts.NONE_AT_TABLE; return false }
        d.verbStrip = false
        d.chainMenu = null
        d.attacking = null
        d.attaching = null
        return advance(a)
    }

    /** The use run with [a]'s answers: the next question shown, or the whole use committed, refused or let go. */
    private fun advance(a: ShortcutAsking): Boolean {
        val sc = now() ?: run { close(); d.problem = Shortcuts.NONE_AT_TABLE; return false }
        val g = d.shown ?: run { close(); return false }
        return when (val st = a.run(sc, g.state, d.catalog, g.header.seed)) {
            is ShortcutStep.Asking -> {
                // The position chosen on the zone's chips answers the position question that follows (§5¾.5).
                val q = st.decision
                val pos = settles
                settles = null
                if (q is Decision.Position && pos != null && pos in q.among) return advance(a.answer(listOf(q.among.indexOf(pos))))
                asking = a
                question = st
                reset(st)
                true
            }
            is ShortcutStep.Done -> {
                close()
                commit(st.result, a.seat)
            }
            is ShortcutStep.Refused -> {
                close()
                d.problem = st.why
                false
            }
            ShortcutStep.Cancelled -> {
                close()
                false
            }
        }
    }

    /** The working answer for a new question: nothing picked, the first row, the first position, the order as asked. */
    private fun reset(st: ShortcutStep.Asking) {
        picks = emptyList()
        typed = null
        hint = null
        cursor = 0
        order = (st.decision as? Decision.Order)?.triggers?.indices?.toList() ?: emptyList()
        position = when (val q = st.decision) {
            is Decision.Zone -> q.positions.firstOrNull()
            is Decision.Position -> q.among.firstOrNull()
            else -> null
        }
        if (st.which) {
            // Which: the first Shortcut the engine allows now.
            val legal = (st.decision as Decision.Option).among
            cursor = 0.coerceAtMost(legal.size - 1)
        }
    }

    /** The whole use made: one group, one undo, every entry tagged with the engine's own, stamped with who chose it. */
    private fun commit(r: ShortcutResult, seat: Int): Boolean {
        val ok = d.act(r.actions, seat, fx = r.tags)
        if (ok) {
            d.problem = r.note
            d.verbStrip = false
        }
        return ok
    }

    /** The answer to the question standing; the next one is asked, or the use is made. */
    fun answer(a: List<Int>): Boolean {
        val now = asking ?: return false
        return advance(now.answer(a))
    }

    /** An open name declaration answered with a card of the pool. */
    fun named(code: Int): Boolean {
        val now = asking ?: return false
        return advance(now.named(code))
    }

    /**
     * Esc (§5¾.10): the typing field shuts first, then an open pile, then the last answer is taken back — the decision
     * before is asked again — and with none left the whole use is cancelled, nothing committed.
     */
    fun back() {
        if (typed != null) { typed = null; return }
        if (d.strip != null) { d.closeStrip(); return }
        val a = asking ?: return close()
        val prev = a.back() ?: return close()
        advance(prev)
    }

    /** The window shut, its answers let go: nothing is committed. */
    fun close() {
        asking = null
        question = null
        picks = emptyList()
        typed = null
        hint = null
        settles = null
    }

    // ---- the answers by kind ---------------------------------------------------------------------------------------

    /**
     * A candidate picked, or let go (a click, a tap, its digit, Space on it). With one to pick, another card takes its
     * place; with the count met, one must be let go first. Enter confirms.
     */
    fun togglePick(i: Int) {
        val q = question?.decision as? Decision.Cards ?: return
        if (i !in q.among.indices) return
        cursor = i
        hint = null
        picks = when {
            i in picks -> picks - i
            picks.size < q.max -> picks + i
            q.max == 1 -> listOf(i)
            else -> picks.also { hint = "${ShortcutWindow.count(q, picks.size)}: let one go first" }
        }
    }

    /** The card [uid] clicked on the table: picked when it is a candidate, else read in the inspector. */
    fun tableCard(uid: Int): Boolean {
        val q = question?.decision ?: return false
        if (q is Decision.Cards) {
            val i = q.among.indexOf(uid)
            if (i >= 0) { togglePick(i); return true }
        }
        d.inspected = uid
        return false
    }

    /** A zone clicked on the table: the answer, when it is one the question offers. */
    fun tableZone(z: Place.Zone): Boolean {
        val q = question?.decision as? Decision.Zone ?: return false
        val i = q.among.indexOfFirst { it.kind == z.kind && it.index == z.index && (it.kind == ZoneKind.EMZ || it.seat == z.seat) }
        if (i < 0) { hint = q.closed.entries.firstOrNull { it.key.kind == z.kind && it.key.index == z.index }?.value ?: "Not a zone this can take"; return false }
        return placeIn(i)
    }

    /** The card being placed put in zone [i] of the question, in the position chosen. */
    fun placeIn(i: Int): Boolean {
        val q = question?.decision as? Decision.Zone ?: return false
        if (i !in q.among.indices) return false
        settles = position
        return answer(listOf(i))
    }

    /** A pile clicked on the table while a choice stands: opened as a row when it holds a candidate (§5¾.6). */
    fun tablePile(seat: Int, kind: PileKind): Boolean {
        val q = question?.decision as? Decision.Cards ?: return false
        val s = d.shown?.state ?: return false
        val holds = q.among.any { s.placeOf(it).let { p -> p is Place.Pile && p.seat == seat && p.kind == kind } }
        if (holds && kind != PileKind.DECK && kind != PileKind.HAND) { d.openPile(seat, kind); return true }
        return false
    }

    /** Enter (§5¾.10): the answer the window holds, said on its chip. */
    fun confirm(): Boolean {
        val st = question ?: return false
        return when (val q = st.decision) {
            is Decision.Cards -> {
                val why = ShortcutWindow.waiting(q, picks.size)
                if (why != null) { hint = why; false } else answer(picks)
            }
            is Decision.Zone -> placeIn(cursor.coerceIn(0, q.among.size - 1))
            is Decision.Position -> answer(listOf((position?.let { q.among.indexOf(it) } ?: -1).takeIf { it >= 0 } ?: 0))
            is Decision.YesNo -> answer(listOf(1))
            is Decision.Option -> answer(listOf(cursor.coerceIn(0, q.among.size - 1)))
            is Decision.Order -> answer(order)
            is Decision.Declare -> false
        }
    }

    /** The position chips' Space: Attack, Defense, Set, round again, among those allowed. */
    fun cyclePosition() {
        val allowed = when (val q = question?.decision) {
            is Decision.Zone -> q.positions
            is Decision.Position -> q.among
            else -> return
        }
        if (allowed.isEmpty()) return
        val at = allowed.indexOf(position)
        position = allowed[(at + 1) % allowed.size]
    }

    /** A, D or E: that position, where it is allowed; the reason said where it is not. */
    fun choosePosition(p: CardPosition): Boolean {
        val allowed = when (val q = question?.decision) {
            is Decision.Zone -> q.positions
            is Decision.Position -> q.among
            else -> return false
        }
        if (p !in allowed) {
            hint = ShortcutWindow.chips(allowed).firstOrNull { it.position == p }?.why
            return false
        }
        position = p
        hint = null
        // Asked alone, the position is the answer.
        if (question?.decision is Decision.Position) return confirm()
        return true
    }

    /** A trigger moved earlier ([by] -1) or later in the order. */
    fun moveOrder(by: Int) {
        val o = order.toMutableList()
        val at = cursor.coerceIn(0, (o.size - 1).coerceAtLeast(0))
        val to = (at + by).coerceIn(0, o.size - 1)
        if (at == to || o.isEmpty()) return
        val x = o.removeAt(at)
        o.add(to, x)
        order = o
        cursor = to
    }

    /**
     * An answer typed into the window (§5¾.10, the same words the line reads): a coordinate (`gy1`, `om1`, `m3`), an
     * option's label or number, yes or no, a position. False, and the hint says so, when it matches nothing offered.
     */
    fun typedAnswer(text: String): Boolean {
        val q = question?.decision ?: return false
        val s = d.shown?.state ?: return false
        val secret = d.shown?.header?.seed ?: 0L
        val w = text.trim()
        typed = null
        if (w.isEmpty()) return false
        val seat = asking?.seat ?: d.bottom
        val ok = when (q) {
            is Decision.Cards -> {
                val i = q.among.indexOfFirst { u ->
                    ShortcutWindow.coord(s, u, seat, secret)?.equals(w, ignoreCase = true) == true ||
                        s.cards[u]?.let { d.catalog.info(it.code)?.name }?.equals(w, ignoreCase = true) == true
                }
                if (i >= 0) { togglePick(i); true } else false
            }
            is Decision.Zone -> {
                val i = q.among.indexOfFirst { com.kaiharimoto.mastertool.core.duel.text.DuelNotation.slotCoord(it, seat)?.equals(w, ignoreCase = true) == true }
                if (i >= 0) placeIn(i) else false
            }
            is Decision.Option -> {
                val i = w.toIntOrNull()?.minus(1)?.takeIf { it in q.among.indices } ?: q.among.indexOfFirst { it.equals(w, ignoreCase = true) || it.startsWith(w, ignoreCase = true) }
                if (i >= 0) answer(listOf(i)) else false
            }
            is Decision.YesNo -> when (w.lowercase()) {
                "yes", "y", "use" -> answer(listOf(1))
                "no", "n", "skip" -> answer(listOf(0))
                else -> false
            }
            is Decision.Position -> com.kaiharimoto.mastertool.core.duel.text.AnswerChooser.positionOf(w)?.let { choosePosition(it) } ?: false
            is Decision.Declare -> {
                val i = q.among.indexOfFirst { it.equals(w, ignoreCase = true) }
                if (i >= 0) answer(listOf(i)) else false
            }
            is Decision.Order -> false
        }
        if (!ok) hint = "“$w” is not one of these"
        return ok
    }

    // ---- the keys (§5¾.10: `DeskScope.SHORTCUT_WINDOW`) -----------------------------------------------------------

    /** How many rows or candidates the question has, for the keys that walk them. */
    private fun size(q: Decision): Int = when (q) {
        is Decision.Cards -> q.among.size
        is Decision.Zone -> q.among.size
        is Decision.Option -> q.among.size
        is Decision.Order -> q.triggers.size
        is Decision.Declare -> q.among.size
        is Decision.Position -> q.among.size
        is Decision.YesNo -> 2
    }

    /** The order a card choice's candidates are walked in: the strip's, place by place. */
    private fun walkOrder(q: Decision.Cards): List<Int> {
        val s = d.shown?.state ?: return q.among.indices.toList()
        return ShortcutWindow.groups(q, s, q.by ?: asking?.seat ?: d.bottom).flatMap { it.indices }
    }

    /** One of the window's keys, while it is open. False when the key means nothing to this question. */
    fun key(action: DeskAction): Boolean {
        val st = question ?: return false
        val q = st.decision
        val n = size(q)
        fun walk(by: Int) {
            if (n == 0) return
            cursor = if (q is Decision.Cards) {
                val order = walkOrder(q)
                val at = order.indexOf(cursor).coerceAtLeast(0)
                order[(at + by).mod(order.size)]
            } else (cursor + by).coerceIn(0, n - 1)
        }
        val digit = DIGITS[action]
        return when {
            action == DeskAction.SHORTCUT_CONFIRM -> { confirm(); true }
            action == DeskAction.SHORTCUT_TOGGLE -> when (q) {
                is Decision.Cards -> { togglePick(cursor); true }
                is Decision.Zone, is Decision.Position -> { cyclePosition(); true }
                else -> false
            }
            action == DeskAction.SHORTCUT_LEFT -> { walk(-1); true }
            action == DeskAction.SHORTCUT_RIGHT -> { walk(1); true }
            action == DeskAction.SHORTCUT_UP -> { walk(-1); true }
            action == DeskAction.SHORTCUT_DOWN -> { walk(1); true }
            action == DeskAction.SHORTCUT_EARLIER -> { moveOrder(-1); true }
            action == DeskAction.SHORTCUT_LATER -> { moveOrder(1); true }
            action == DeskAction.SHORTCUT_NEXT_PLACE -> {
                // Tab: the first candidate of the next place in the strip.
                if (q is Decision.Cards) {
                    val s = d.shown?.state
                    val groups = s?.let { ShortcutWindow.groups(q, it, q.by ?: asking?.seat ?: d.bottom).filter { g -> g.indices.isNotEmpty() } }.orEmpty()
                    val at = groups.indexOfFirst { cursor in it.indices }
                    groups.getOrNull((at + 1).mod(groups.size.coerceAtLeast(1)))?.indices?.firstOrNull()?.let { cursor = it }
                }
                true
            }
            action == DeskAction.SHORTCUT_ATTACK -> letterOr(q, "a") { choosePosition(CardPosition.FACE_UP_ATK) }
            action == DeskAction.SHORTCUT_DEFENSE -> letterOr(q, "d") { choosePosition(CardPosition.FACE_UP_DEF) }
            action == DeskAction.SHORTCUT_SET -> letterOr(q, "e") { choosePosition(CardPosition.FACE_DOWN_DEF) }
            action == DeskAction.SHORTCUT_YES -> if (q is Decision.YesNo) answer(listOf(1)) else type("y")
            action == DeskAction.SHORTCUT_NO -> if (q is Decision.YesNo) answer(listOf(0)) else type("n")
            action == DeskAction.SHORTCUT_TYPE -> { typed = typed ?: ""; true }
            ZONE_KEYS[action] != null && q is Decision.Zone -> {
                val (kind, index) = ZONE_KEYS.getValue(action)
                val i = ShortcutWindow.zoneFor(q.among, kind, index)
                if (i == null) { hint = "That zone is not one it can take"; false } else placeIn(i)
            }
            digit != null -> when (q) {
                is Decision.Cards -> walkOrder(q).getOrNull(digit - 1)?.let { togglePick(it); true } ?: false
                is Decision.Option -> (digit - 1).takeIf { it in q.among.indices }?.let { answer(listOf(it)) } ?: false
                is Decision.Order -> (digit - 1).takeIf { it in q.triggers.indices }?.let { cursor = it; true } ?: false
                is Decision.Declare -> if (q.kind == DeclareKind.LEVEL) {
                    // A digit picks a Level; 1 then 0–2 makes 10–12.
                    val v = if (digit == 0) 10 else digit
                    q.among.indexOf("$v").takeIf { it >= 0 }?.let { answer(listOf(it)) } ?: false
                } else (digit - 1).takeIf { it in q.among.indices }?.let { answer(listOf(it)) } ?: false
                is Decision.YesNo -> if (digit == 1) answer(listOf(1)) else false
                else -> false
            }
            else -> false
        }
    }

    /** A letter the question has a use for, else typed into the window's field (a coordinate starting with it). */
    private fun letterOr(q: Decision, letter: String, use: () -> Boolean): Boolean =
        if (q is Decision.Zone || q is Decision.Position) use() else type(letter)

    /** A letter typed at the window (any letter not a key of the question): its field opens holding it. */
    fun type(text: String): Boolean {
        if (question == null) return false
        typed = (typed ?: "") + text
        return true
    }

    /** The question's ask in words, for the line and Ai: what a Shortcut waits on when the window is open. */
    fun waitingWords(): String? = question?.let { st ->
        val s = d.shown?.state ?: return@let null
        ShortcutWindow.sentence(st.decision, s, asking?.seat ?: d.bottom, d.catalog)
    }

    /** Whether the line [ask] should open the window: a Shortcut line at a table with written effects. */
    fun line(ask: ShortcutAsk): Boolean = start(ShortcutAsking(ask, d.bottom))

    companion object {
        /** The digit keys, 1–9 and 0 (Level 10). */
        val DIGITS: Map<DeskAction, Int> = mapOf(
            DeskAction.SHORTCUT_1 to 1, DeskAction.SHORTCUT_2 to 2, DeskAction.SHORTCUT_3 to 3, DeskAction.SHORTCUT_4 to 4,
            DeskAction.SHORTCUT_5 to 5, DeskAction.SHORTCUT_6 to 6, DeskAction.SHORTCUT_7 to 7, DeskAction.SHORTCUT_8 to 8,
            DeskAction.SHORTCUT_9 to 9, DeskAction.SHORTCUT_0 to 0,
        )

        /** The zone keys while a zone is asked (§5¾.5): 1–5, Shift 1–5, 6 and 7 the Extra Monster Zones, 0 the Field Zone. */
        val ZONE_KEYS: Map<DeskAction, Pair<ZoneKind, Int>> = mapOf(
            DeskAction.SHORTCUT_1 to (ZoneKind.MONSTER to 0), DeskAction.SHORTCUT_2 to (ZoneKind.MONSTER to 1),
            DeskAction.SHORTCUT_3 to (ZoneKind.MONSTER to 2), DeskAction.SHORTCUT_4 to (ZoneKind.MONSTER to 3),
            DeskAction.SHORTCUT_5 to (ZoneKind.MONSTER to 4),
            DeskAction.SHORTCUT_S1 to (ZoneKind.SPELL to 0), DeskAction.SHORTCUT_S2 to (ZoneKind.SPELL to 1),
            DeskAction.SHORTCUT_S3 to (ZoneKind.SPELL to 2), DeskAction.SHORTCUT_S4 to (ZoneKind.SPELL to 3),
            DeskAction.SHORTCUT_S5 to (ZoneKind.SPELL to 4),
            DeskAction.SHORTCUT_6 to (ZoneKind.EMZ to 0), DeskAction.SHORTCUT_7 to (ZoneKind.EMZ to 1),
            DeskAction.SHORTCUT_0 to (ZoneKind.FIELD to 0),
        )

        /** Every key of the window, for the dispatch. */
        val ACTIONS: Set<DeskAction> = DeskAction.entries.filter { it.name.startsWith("SHORTCUT_") }.toSet()
    }
}
