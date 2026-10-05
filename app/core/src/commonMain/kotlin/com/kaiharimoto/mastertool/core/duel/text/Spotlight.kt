package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Attack
import com.kaiharimoto.mastertool.core.duel.BattleOutcome
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Preview

/**
 * Command mode's Spotlight (1.0.87, kai chose it from the design canvas, direction C): the big box over the table
 * where a duel is typed, or spoken, a line at a time. This is its arithmetic, pure and tested — what the box shows for
 * a line ([view]), which row is chosen and where ↑ walks the history ([up], [down]), when a digit picks a
 * "did you mean" rather than typing ([digitPicks]) — so the page only draws it.
 *
 * A row is a whole sentence with its consequence and the coordinates at the right, as kai's design has it:
 * "Ash Blossom attacks Divine Arsenal AA-ZEUS" / "0 vs 3000 · Ash Blossom is destroyed, you take 3000" / "m3 → e1".
 * Everything is worded through the seat's own eyes ([DuelPreview], [DuelAnswer]): a hidden card is named by where
 * it is, never by what it is.
 */
object Spotlight {

    /** What the box is doing: being typed in, listening (M held), showing what was heard to confirm, answering. */
    enum class Mode { TYPING, LISTENING, HEARD, ANSWER }

    /**
     * The box's state, kept by the page between keystrokes. [chosen] is the inked row (−1: none, as on an empty
     * box until ↓); [heard] the words as the transcriber wrote them, shown faint above the line they became;
     * [answer] a question's answer, or why nothing was understood; [historyAt] where ↑ stands in the history (0 the
     * newest; null at the line being typed); [problem] what the table refused when Enter was pressed; [shownAt] the
     * duel's cursor when a heard move was shown, so a "yes" after the table moved is asked again, never made blind.
     */
    data class State(
        val text: String = "",
        val cursor: Int = text.length,
        val chosen: Int = if (text.isEmpty()) -1 else 0,
        val mode: Mode = Mode.TYPING,
        val heard: String? = null,
        val answer: String? = null,
        val historyAt: Int? = null,
        val problem: String? = null,
        val shownAt: Int? = null,
    ) {
        /**
         * The line typed (or recalled) was changed by hand: the chosen row goes back to the first, and what was heard
         * is gone — the line is the person's own now, so M types again (the red team).
         */
        fun typed(text: String, cursor: Int): State = copy(
            text = text,
            cursor = cursor.coerceIn(0, text.length),
            chosen = if (text.isBlank()) -1 else 0,
            mode = if (mode == Mode.LISTENING) mode else Mode.TYPING,
            heard = null,
            answer = null,
            historyAt = null,
            problem = null,
            shownAt = null,
        )

        /** A line recalled from the history is typed, not heard. */
        internal fun recalled(): State = if (mode == Mode.HEARD || heard != null) copy(mode = Mode.TYPING, heard = null, shownAt = null) else this
    }

    /** What a row is: the move the line makes, a step of a `;` line, a "did you mean", a completion, a line from history or one to try. */
    enum class RowKind { MOVE, STEP, FIX, COMPLETE, RECENT, TRY }

    /**
     * One result. [words] the move as a sentence; [consequence] what it comes to (an attack's battle, a refusal);
     * [coords] where it happens ("h2 → m3"); [line] what Enter commits or Tab takes, with [cursor] where the caret
     * goes; [uid] the card whose art the row shows; [number] the digit that picks it (1–3); [makes]: Enter makes
     * the move (false: Enter takes it into the line, as Tab).
     */
    data class Row(
        val kind: RowKind,
        val words: String,
        val consequence: String? = null,
        val coords: String = "",
        val line: String,
        val cursor: Int = line.length,
        val uid: Int? = null,
        val number: Int? = null,
        val makes: Boolean = true,
    )

    /**
     * What the box shows for its line: [label] "DO" or "ASK"; [rows] the results, best first; [answer] a question
     * answered in words; [problem] why the line cannot be made as it stands; [recent] and [tries] for an empty box.
     */
    data class View(
        val label: String,
        val rows: List<Row>,
        val answer: String? = null,
        val problem: String? = null,
        val preview: Preview? = null,
        val recent: List<String> = emptyList(),
        val tries: List<Row> = emptyList(),
    ) {
        /** The "did you mean" rows, numbered 1–3. */
        val fixes: List<Row> get() = rows.filter { it.kind == RowKind.FIX }

        /** Every row ↑/↓ choose among: an empty box's lines to try, else the results. */
        val choosable: List<Row> get() = if (rows.isEmpty()) tries else rows

        /** Whether this line is a question, answered rather than made. */
        val asks: Boolean get() = label == ASK
    }

    const val DO = "DO"
    const val ASK = "ASK"

    /** Most rows a box shows, the move first. */
    const val MOST = 5

    /** Most lines kept in the history (`Duels.lineHistory`). */
    const val HISTORY = 50

    /**
     * The box for [text] with the caret at [cursor], for [seat] at [s]: the preview first when the line makes a move,
     * a `;` line's steps numbered, "did you mean" choices numbered 1–3, then completions — each worded as a sentence
     * when it would be a whole move. An empty line shows [history]'s recent lines and a few to try on this table.
     */
    fun view(
        text: String,
        cursor: Int,
        s: DuelState,
        seat: Int,
        catalog: DuelCatalog,
        history: List<String> = emptyList(),
        secret: Long = 0L,
        most: Int = MOST,
        /** The battle an attack just declared comes to ([DuelBattle.pending]): offered first on an empty box, as typed words. */
        battle: BattleOutcome? = null,
    ): View {
        if (text.isBlank()) {
            return View(DO, emptyList(), recent = recent(history), tries = tries(s, seat, catalog, secret, battle = battle))
        }
        val p = DuelCommand.preview(text, s, seat, catalog, secret)
        val label = if (asks(text, p)) ASK else DO
        if (p.parsed is Parsed.Query) return View(ASK, emptyList(), answer = p.words, preview = p)
        val rows = mutableListOf<Row>()
        var problem: String? = null
        when (val parsed = p.parsed) {
            is Parsed.Many -> if (p.ok) {
                var state = s
                parsed.parts.forEachIndexed { i, part ->
                    val said = segment(p.words, parsed.parts.size, i) ?: parsed.lines.getOrNull(i) ?: part.said
                    rows += Row(
                        RowKind.STEP, sentence(part.actions, said, state, seat, catalog), consequence(part.actions, state, seat, catalog), coords(part.actions, state, seat, secret),
                        line = text, uid = card(part.actions), number = i + 1,
                    )
                    state = DuelRules.applyAll(state, part.actions, seat).first ?: state
                }
            } else problem = p.problem
            is Parsed.Actions -> {
                rows += Row(
                    RowKind.MOVE, sentence(parsed.actions, p.words.ifBlank { parsed.said }, s, seat, catalog), if (p.ok) consequence(parsed.actions, s, seat, catalog) else p.problem,
                    coords(parsed.actions, s, seat, secret), line = text, uid = card(parsed.actions), makes = p.ok,
                )
                if (!p.ok) problem = p.problem
            }
            is Parsed.Ui, is Parsed.Ruling -> rows += Row(RowKind.MOVE, p.words, line = text, uid = (parsed as? Parsed.Ui)?.uid)
            is Parsed.Shortcut -> rows += Row(RowKind.MOVE, p.words, line = text, uid = (parsed.ask as? ShortcutAsk.Use)?.uid)
            is Parsed.Problem -> {
                problem = p.problem
                p.fixes.take(3).forEachIndexed { i, fix ->
                    val fp = DuelCommand.preview(fix, s, seat, catalog, secret)
                    val acts = fp.actions
                    rows += Row(
                        RowKind.FIX, if (fp.ok && fp.words.isNotBlank()) sentence(acts, fp.words, s, seat, catalog) else (p.choices.getOrNull(i) ?: fix),
                        if (fp.ok) consequence(acts, s, seat, catalog) else null, coords(acts, s, seat, secret),
                        line = fix, uid = card(acts), number = i + 1, makes = fp.ok,
                    )
                }
            }
            else -> Unit
        }
        // Then what the line could go on to be, worded as the move it would make where it would make one.
        val room = (most - rows.size).coerceAtLeast(0)
        if (room > 0) {
            val seen = rows.map { it.line.trim().lowercase() }.toMutableSet()
            val made = rows.map { it.words }.toMutableSet()
            for (sug in DuelComplete.suggest(text, cursor, s, seat, catalog, history, secret, most = 12)) {
                if (rows.size >= most) break
                val (line, at) = DuelComplete.apply(text, cursor, sug)
                val key = line.trim().lowercase()
                if (key == text.trim().lowercase() || !seen.add(key)) continue
                val sp = DuelCommand.preview(line, s, seat, catalog, secret)
                val whole = sp.ok && (sp.parsed is Parsed.Actions || sp.parsed is Parsed.Many)
                val row = if (whole) {
                    Row(RowKind.COMPLETE, sentence(sp.actions, sp.words, s, seat, catalog), consequence(sp.actions, s, seat, catalog), coords(sp.actions, s, seat, secret), line.trimEnd(), at.coerceAtMost(line.trimEnd().length), card(sp.actions))
                } else {
                    Row(RowKind.COMPLETE, sug.label, null, sug.insert.takeIf { DuelNotation.parse(it) != null } ?: "", line, at, cardOf(sug, s, seat, secret), makes = false)
                }
                if (row.words in made) continue
                made += row.words
                rows += row
            }
        }
        return View(label, rows, problem = problem, preview = p)
    }

    /** The line is a question: it reads as one ([Parsed.Query]), or is put as one ("?m3", "what's in their gy?"). */
    fun asks(text: String, p: Preview? = null): Boolean {
        if (p?.parsed is Parsed.Query) return true
        val t = text.trim()
        return t.startsWith("?") || (t.endsWith("?") && p?.parsed !is Parsed.Actions && p?.parsed !is Parsed.Many)
    }

    /** The history's newest lines, newest first, each once. */
    fun recent(history: List<String>, most: Int = 3): List<String> = history.asReversed().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(most)

    /** [history] with [line] added at its end (newest), a repeat moved there, at most [HISTORY] kept. */
    fun remember(history: List<String>, line: String): List<String> {
        val t = line.trim()
        if (t.isEmpty()) return history
        return (history.filter { it != t } + t).takeLast(HISTORY)
    }

    // ---- keys ---------------------------------------------------------------------------------------

    /**
     * ↑: on an empty or recalled line, an older line from [history] into the box (the newest first); else the row
     * above among [rows] (none above the first).
     */
    fun up(st: State, rows: Int, history: List<String>): State {
        val recent = history.asReversed().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (st.text.isBlank() || st.historyAt != null) {
            val next = (st.historyAt?.plus(1) ?: 0)
            val line = recent.getOrNull(next) ?: return st
            return st.copy(text = line, cursor = line.length, historyAt = next, chosen = 0, answer = null, problem = null).recalled()
        }
        return st.copy(chosen = (st.chosen - 1).coerceAtLeast(0).coerceAtMost((rows - 1).coerceAtLeast(0)))
    }

    /**
     * ↓: walking the history, a newer line (past the newest, the box empty again); else the row below among [rows]
     * (an empty box's first ↓ inks its first line to try).
     */
    fun down(st: State, rows: Int, history: List<String>): State {
        val at = st.historyAt
        if (at != null) {
            if (at == 0) return st.copy(text = "", cursor = 0, historyAt = null, chosen = -1).recalled()
            val recent = history.asReversed().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val line = recent.getOrNull(at - 1) ?: return st
            return st.copy(text = line, cursor = line.length, historyAt = at - 1, chosen = 0).recalled()
        }
        if (rows == 0) return st
        return st.copy(chosen = (st.chosen + 1).coerceAtMost(rows - 1))
    }

    /**
     * Whether digit key [digit] picks "did you mean" choice [digit] rather than typing it: only while [fixes]
     * choices show, the digit names one, and the word under the caret is not a coordinate or a number being typed —
     * `m` then `3` is still `m3`, `lp -` then `1` still `lp -1000`.
     */
    fun digitPicks(text: String, cursor: Int, digit: Int, fixes: Int): Boolean {
        if (fixes <= 0 || digit !in 1..minOf(3, fixes)) return false
        val c = cursor.coerceIn(0, text.length)
        var i = c
        while (i > 0 && text[i - 1] != ' ' && text[i - 1] != ';') i--
        val word = text.substring(i, c).lowercase()
        if (word.isEmpty()) return true
        if (!word.last().isLetter()) return false
        return !COORD_HEAD.matches(word)
    }

    /** A word a digit would make a coordinate of: `h`, `m`, `s`, `e`, `gy`, `ban`, `ex`, `dk`, `o…`, `?m`. */
    private val COORD_HEAD = Regex("^\\??o?(h|m|s|gy|ban|ex|dk)$|^\\??e$")

    // ---- an empty box ---------------------------------------------------------------------------------

    /**
     * Two or three lines to try on this table, each a whole move the preview accepts, made from [DuelComplete]'s own
     * suggestions: a card from the hand to a free zone, an attack in the Battle Phase, the next phase, a question.
     */
    fun tries(s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long = 0L, most: Int = 3, battle: BattleOutcome? = null): List<Row> {
        val out = mutableListOf<Row>()
        // An attack just declared: what battle comes to, first — the battle chip's moves, typed.
        val fought = battle?.let { battleLine(s, it, seat, secret) }
        if (battle != null && fought != null) {
            val p = DuelCommand.preview(fought, s, seat, catalog, secret)
            if (p.ok) out += Row(RowKind.TRY, DuelBattle.words(s, battle, catalog).joinToString(" · "), null, coords(p.actions, s, seat, secret), fought, uid = battle.destroyed.firstOrNull())
        }
        fun offer(line: String) {
            if (out.size >= most || out.any { it.line == line }) return
            val p = DuelCommand.preview(line, s, seat, catalog, secret)
            if (!p.ok) return
            val words = if (p.parsed is Parsed.Query) "Ask: ${p.words.substringBefore(':').ifBlank { line }}" else p.words
            out += Row(RowKind.TRY, sentence(p.actions, words, s, seat, catalog), consequence(p.actions, s, seat, catalog), coords(p.actions, s, seat, secret), line, uid = card(p.actions))
        }
        /** "verb card zone", each word the first [DuelComplete] offers there. */
        fun build(head: String) {
            val first = DuelComplete.suggest("$head ", head.length + 1, s, seat, catalog, secret = secret)
                .firstOrNull { it.kind == DuelComplete.Kind.CARD } ?: return
            val withCard = "$head ${first.insert}"
            val second = DuelComplete.suggest("$withCard ", withCard.length + 1, s, seat, catalog, secret = secret)
                .firstOrNull { it.kind == DuelComplete.Kind.ZONE } ?: return
            offer("$withCard ${second.insert}")
        }
        if (s.active == seat) {
            when (s.phase) {
                DuelPhase.BATTLE -> {
                    // A monster that may attack, and the first thing [DuelComplete] offers it to attack.
                    val attacker = s.onField().firstOrNull { DuelVerbs.canAttack(s, seat, it) }
                    val at = attacker?.let { DuelNotation.coordOf(s, it, seat, secret) }
                    if (at != null) {
                        val head = "a $at "
                        DuelComplete.suggest(head, head.length, s, seat, catalog, secret = secret)
                            .firstOrNull { it.kind == DuelComplete.Kind.ZONE }?.let { offer("a $at ${it.insert}") }
                    }
                }
                DuelPhase.MAIN1, DuelPhase.MAIN2 -> { build("s"); build("e") }
                else -> Unit
            }
            val next = when (s.phase) {
                DuelPhase.DRAW, DuelPhase.STANDBY -> "m1"
                DuelPhase.MAIN1 -> "bp"
                else -> "end"
            }
            offer(next)
        }
        offer("hand")
        if (!s.solo) offer("their field")
        return out
    }

    /**
     * The battle chip's moves as a line (1.0.87): each monster battle destroys to the GY by its coordinate, and the damage
     * to its player's life points — "g om1; lp o -2700". Null when a card has no coordinate (it left the field).
     */
    fun battleLine(s: DuelState, o: BattleOutcome, seat: Int, secret: Long = 0L): String? {
        val parts = mutableListOf<String>()
        for (uid in o.destroyed) parts += "g " + (DuelNotation.coordOf(s, uid, seat, secret) ?: return null)
        if (o.damaged != null && o.damage > 0) parts += if (o.damaged == seat) "lp -${o.damage}" else "lp o -${o.damage}"
        return parts.joinToString("; ").ifEmpty { null }
    }

    // ---- the words beside the sentence -----------------------------------------------------------------

    /**
     * Where a move happens, in coordinates: "h2 → m3", "m3 → om1", "m3 → direct", "s1 → om2", "dk → h". The first
     * action that moves or points at a card says it; a phase is its short word.
     */
    fun coords(actions: List<DuelAction>, s: DuelState, seat: Int, secret: Long = 0L): String {
        fun at(uid: Int) = DuelNotation.coordOf(s, uid, seat, secret) ?: "?"
        fun to(p: Place): String = when (p) {
            is Place.Zone -> DuelNotation.slotCoord(p, seat) ?: "?"
            // A hand is the hand, wherever in it the card lands; a pile is the pile (its top).
            is Place.Pile -> if (p.kind == PileKind.HAND) (if (p.seat == seat) "h" else "oh") else DuelNotation.slotCoord(Place.Pile(p.seat, p.kind), seat) ?: "?"
            is Place.Under -> at(p.host)
            Place.Void -> "out"
        }
        for (a in actions) {
            when (a) {
                is DuelAction.Move -> return "${at(a.uid)} → ${to(a.to)}"
                is DuelAction.Attack -> return "${at(a.attacker)} → ${a.target?.let(::at) ?: "direct"}"
                is DuelAction.Target -> return listOfNotNull(a.from?.let(::at), a.to.joinToString(", ") { at(it) }).joinToString(" → ")
                is DuelAction.Position -> return at(a.uid)
                is DuelAction.Counter -> return at(a.uid)
                is DuelAction.ChainAdd -> a.uid?.let { return at(it) }
                is DuelAction.Reveal -> return a.uids.joinToString(", ") { at(it) }
                is DuelAction.Token -> return "→ ${to(a.to)}"
                is DuelAction.Draw -> return if (a.seat == seat) "dk → h" else "odk → oh"
                is DuelAction.Phase -> return SHORT[a.phase] ?: a.phase.label
                DuelAction.EndTurn -> return "end"
                is DuelAction.Lp -> return if (a.seat == seat) "lp" else "olp"
                else -> Unit
            }
        }
        return ""
    }

    private val SHORT = mapOf(
        DuelPhase.DRAW to "dp", DuelPhase.STANDBY to "sp", DuelPhase.MAIN1 to "m1", DuelPhase.BATTLE to "bp",
        DuelPhase.MAIN2 to "m2", DuelPhase.END to "ep",
    )

    /**
     * What a move comes to, when it is an attack the printed numbers settle ([DuelBattle]): "0 vs 3000 · Ash Blossom
     * is destroyed, you take 3000", "3000 vs 2100 DEF · Dark Magician is destroyed", "2500 direct · they take 2500".
     * A face-down defender says only that it is face-down. Null for anything else.
     */
    fun consequence(actions: List<DuelAction>, s: DuelState, seat: Int, catalog: DuelCatalog): String? {
        var state = s
        for (a in actions) {
            if (a is DuelAction.Attack) return battle(state, a, seat, catalog)
            state = (DuelRules.apply(state, a, seat) as? Outcome.Ok)?.state ?: return null
        }
        return null
    }

    private fun battle(s: DuelState, a: DuelAction.Attack, seat: Int, catalog: DuelCatalog): String? {
        val attacker = s.cards[a.attacker] ?: return null
        val power = DuelBattle.atk(attacker, catalog) ?: return null
        val target = a.target?.let { s.cards[it] }
        val against = when {
            a.target == null -> "$power direct"
            target == null -> return null
            !target.faceUp -> return "$power vs a face-down monster"
            target.defense -> "$power vs ${DuelBattle.def(target, catalog) ?: "?"} DEF"
            else -> "$power vs ${DuelBattle.atk(target, catalog) ?: "?"}"
        }
        val o = DuelBattle.outcome(s, Attack(a.seat, a.attacker, a.target), catalog) ?: return against
        val names = o.destroyed.mapNotNull { u -> s.cards[u]?.takeIf { DuelSight.sees(s, u, seat) }?.let { catalog.nameOf(it) } }
        val parts = buildList {
            if (names.isNotEmpty()) add(names.joinToString(" and ") + if (names.size == 1) " is destroyed" else " are destroyed")
            if (o.damaged != null && o.damage > 0) add(if (o.damaged == seat) "you take ${o.damage}" else "they take ${o.damage}")
        }
        return against + " · " + (parts.joinToString(", ").ifEmpty { "nothing happens" })
    }

    /**
     * The row's sentence: an attack as a player says it — "Ash Blossom & Joyous Spring attacks Divine Arsenal AA-ZEUS",
     * "Blue-Eyes White Dragon attacks directly" — its places being the coordinates beside it; anything else as the
     * preview words it ([words]). A card is named only when the seat may see it, else by where it is.
     */
    fun sentence(actions: List<DuelAction>, words: String, s: DuelState, seat: Int, catalog: DuelCatalog): String {
        val a = actions.singleOrNull() as? DuelAction.Attack ?: return words
        fun name(uid: Int): String = s.cards[uid]?.takeIf { DuelSight.sees(s, uid, seat) }?.let { catalog.nameOf(it) }
            ?: "the face-down card in ${DuelNotation.coordOf(s, uid, seat) ?: "that zone"}"
        return name(a.attacker).replaceFirstChar { it.uppercase() } + " attacks " + (a.target?.let(::name) ?: "directly")
    }

    /** The card a move is about, for the row's art: the first it moves or points at. */
    private fun card(actions: List<DuelAction>): Int? = actions.firstNotNullOfOrNull { a ->
        when (a) {
            is DuelAction.Move -> a.uid
            is DuelAction.Attack -> a.attacker
            is DuelAction.Target -> a.from ?: a.to.firstOrNull()
            is DuelAction.Position -> a.uid
            is DuelAction.Counter -> a.uid
            is DuelAction.ChainAdd -> a.uid
            is DuelAction.Reveal -> a.uids.firstOrNull()
            else -> null
        }
    }

    /** A completion's card, when it names one by its coordinate. */
    private fun cardOf(sug: DuelComplete.Suggestion, s: DuelState, seat: Int, secret: Long): Int? =
        if (sug.kind == DuelComplete.Kind.CARD || sug.kind == DuelComplete.Kind.ZONE) DuelNotation.at(s, sug.insert, seat, secret) else null

    /** Step [i] of a `;` line's words, when the preview joined exactly one phrase per step. */
    private fun segment(words: String, parts: Int, i: Int): String? = words.split("; ").takeIf { it.size == parts }?.getOrNull(i)

    /** Whether [uid] is a card this seat may name (for the row's art: a face is shown only when it may). */
    fun seen(s: DuelState, uid: Int, seat: Int): Boolean = DuelSight.sees(s, uid, seat)
}
