package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxFacts
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.text.ShortcutWindow

/**
 * The engine's two doors as the table reaches them (Phase D §5½): [FX] is the engine itself; a test hands in its own, so
 * the verb is held to its contract whatever the engine has grown into.
 */
interface ShortcutEngine {
    fun moves(t: FxTable, seat: Int): List<FxMove>

    fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay

    companion object {
        val FX: ShortcutEngine = object : ShortcutEngine {
            override fun moves(t: FxTable, seat: Int): List<FxMove> = FxEngine.moves(t, seat)
            override fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay = FxEngine.play(t, seat, move, chooser)
        }
    }
}

/**
 * One of a card's Shortcuts as the table lists it: its [effect] id and short [label]; [legal] now, or greyed with the rule
 * that forbids it ([why]); [verified] or not, which every surface that lists it says.
 */
data class ShortcutOption(
    val uid: Int,
    val effect: String,
    val label: String,
    val legal: Boolean,
    val why: String? = null,
    val verified: Boolean = false,
    /** What it needs before it resolves, from its script (D.md §5¾.12): "no target", "1 target · they control · cost: banish this card". */
    val needs: String = "",
) {
    /** "Search", "Search (unverified)", "Revive: once per turn: used". */
    val words: String
        get() = buildString {
            append(label)
            if (!verified) append(" (unverified)")
            if (!legal && why != null) append(": ").append(why)
        }
}

/**
 * What a Shortcut came to: the [actions] to commit as one group, one [tags] entry each (`DuelEntry.fx`); or why not
 * ([problem]); or [cancelled] — Esc, or an answer out of bounds — with nothing to commit. [state] and [fx] are the table and
 * the engine after the actions, for whoever plans the next step on them.
 */
data class ShortcutResult(
    val actions: List<DuelAction> = emptyList(),
    val tags: List<FxTag?> = emptyList(),
    val problem: String? = null,
    val cancelled: Boolean = false,
    /** The use in words: "Example Scout · Search (Shortcut)". */
    val said: String = "",
    /** Said beside a use that was made: its resolution could not follow at once, say. */
    val note: String? = null,
    val state: DuelState? = null,
    val fx: FxState? = null,
) {
    val ok: Boolean get() = problem == null && !cancelled && actions.isNotEmpty()

    /**
     * Committed to [game] as one group by [seat], each entry tagged and stamped with [by] — the player who chose to use it,
     * the person or Ai, so the results still count players.
     */
    fun commit(game: DuelGame, seat: Int, by: Provenance, at: Long = 0L): DuelGame.Result =
        if (!ok) DuelGame.Result(game, problem ?: if (cancelled) null else "Nothing to do") else game.act(actions, seat, at, by = by, fx = tags)

    /** The same, as the verbs say it. */
    fun verb(): VerbResult = VerbResult(actions, problem, fx = tags, cancelled = cancelled)

    companion object {
        fun no(why: String) = ShortcutResult(problem = why)
        val CANCELLED = ShortcutResult(cancelled = true)
    }
}

/**
 * The written effects at a table (Phase D §5½, **Shortcut**): the scripts ([book]), the pool's facts, and the engine's state
 * for the table it was made for ([fx]: `FxFold` over the duel's log, [of]). Handed to the verbs ([DuelVerbs.offered],
 * [DuelVerbs.actions]), the line and Ai's menu; **absent, nothing is offered** — every table before the library has none.
 *
 * The default never changes here: a Shortcut runs only when it is chosen, by its own verb, key, letter or word.
 */
class Shortcuts(
    val book: ScriptBook,
    val facts: FxFacts,
    /** The engine's state for the table this was made for; null: a fresh one at that table's turn. */
    val fx: FxState? = null,
    val engine: ShortcutEngine = ShortcutEngine.FX,
    /** A networked table: every Shortcut is refused there in words ([DuelHost.NO_SHORTCUTS]). */
    val networked: Boolean = false,
    /**
     * A use resolves in the same group when nothing can respond: true on a one-player table or with response windows off,
     * false where the other seat may answer (then Resolve by Shortcut is its own group). Null: the table's own [DuelState.solo].
     */
    val resolveAtOnce: Boolean? = null,
    /** Whether a card's effect is verified (§4.4): none is until the tests of step 3. */
    private val verified: (code: Int, effect: String) -> Boolean = { _, _ -> false },
    /**
     * The pool's cards by exact name, any case — their passcodes: how a line's `declare=` names a card that is not on the
     * table (any card that exists may be declared). Null where the pool is not at hand: only the table's names are read.
     */
    val names: ((String) -> List<Int>)? = null,
) {
    /** The same, with the engine's state [next] — after a step of a plan. */
    fun withFx(next: FxState?): Shortcuts = Shortcuts(book, facts, next, engine, networked, resolveAtOnce, verified, names)

    /**
     * The same scripts at the duel in play [game]: the engine's state folded from its log, at a networked table or not
     * (D.md §5½: refused there in words), resolving at once or not.
     */
    fun at(game: DuelGame, networked: Boolean = this.networked, resolveAtOnce: Boolean? = this.resolveAtOnce): Shortcuts =
        Shortcuts(book, facts, FxFold.fold(game.header, game.played, book, facts, game.state), engine, networked, resolveAtOnce, verified, names)

    /** Whether [code]'s effect [effect] is verified (§4.4), as the log's tag will say. */
    fun isVerified(code: Int, effect: String): Boolean = verified(book.canonical(code), effect)

    /** The engine's view of [s]. */
    fun table(s: DuelState): FxTable = FxTable(s, fx ?: FxState.at(s), book, facts).current()

    /** [uid]'s script, by any printing; a token made of nothing has none. */
    private fun scriptOf(s: DuelState, uid: Int) = s.cards[uid]?.takeIf { !it.token || it.code != 0 }?.let { book.script(it.code) }

    /**
     * Whether [seat] is offered Shortcut on [uid]: the card has a written effect, [seat] sees it, and it is the seat's
     * own — controlled on the field, owned anywhere else.
     */
    fun has(s: DuelState, seat: Int, uid: Int): Boolean {
        val card = s.cards[uid] ?: return false
        // A script of summoning rules or Continuous effects alone has nothing to use.
        if (scriptOf(s, uid)?.effects?.any { it.kind != Kind.CONTINUOUS } != true) return false
        if (!DuelSight.sees(s, uid, seat)) return false
        return if (s.placeOf(uid) is Place.Zone) card.controller == seat else card.owner == seat
    }

    /**
     * [uid]'s Shortcuts for [seat], each legal now or greyed with its rule: legal when the engine lists it among the
     * seat's moves; else the engine's own words for why not. A Continuous effect is never used, so never listed.
     */
    fun options(s: DuelState, seat: Int, uid: Int): List<ShortcutOption> {
        if (!has(s, seat, uid)) return emptyList()
        val card = s.cards.getValue(uid)
        val script = scriptOf(s, uid) ?: return emptyList()
        val usable = script.effects.withIndex().filter { it.value.kind != Kind.CONTINUOUS }
        if (usable.isEmpty()) return emptyList()
        val t = table(s)
        val moves = if (networked) emptySet() else engine.moves(t, seat).filterIsInstance<FxMove.Activate>().filter { it.uid == uid }.map { it.effect }.toSet()
        return usable.map { (i, e) ->
            val ok = e.id in moves
            val why = when {
                ok -> null
                networked -> DuelHost.NO_SHORTCUTS
                else -> when (val p = engine.play(t, seat, FxMove.Activate(uid, e.id), Chooser { Chooser.CANCEL })) {
                    is FxPlay.Refused -> p.why
                    else -> NOT_NOW
                }
            }
            ShortcutOption(uid, e.id, label(e, i), ok, why, verified(book.canonical(card.code), e.id), ShortcutWindow.needs(e))
        }
    }

    /**
     * [seat] uses [uid]'s Shortcut [effect] — its id ("e2"), its short name ("Search"), or "2"; null asks [chooser] which
     * when more than one is legal ([Decision.Option], by short name). Every choice after is the [chooser]'s; [Chooser.CANCEL]
     * cancels the whole use with nothing to commit. The activation, and its resolution when nothing can respond
     * ([resolveAtOnce]), are one result: one group, one undo.
     */
    fun use(s: DuelState, seat: Int, uid: Int, effect: String?, chooser: Chooser): ShortcutResult {
        if (networked) return ShortcutResult.no(DuelHost.NO_SHORTCUTS)
        if (uid !in s.cards) return ShortcutResult.no("No such card")
        val script = scriptOf(s, uid) ?: return ShortcutResult.no(NO_SCRIPT)
        if (!has(s, seat, uid)) return ShortcutResult.no(if (!DuelSight.sees(s, uid, seat)) "You cannot see that card" else NOT_YOURS)
        val opts = options(s, seat, uid)
        if (opts.isEmpty()) return ShortcutResult.no("${script.name} has nothing to use: its written effects apply on their own")
        val chosen = if (effect != null && effect.isNotBlank()) {
            val e = find(script.effects, effect)
            opts.firstOrNull { it.effect == e?.id } ?: return ShortcutResult.no("${script.name} has no Shortcut called “$effect”: ${opts.joinToString(", ") { "${it.label} (${it.effect})" }}")
        } else {
            val legal = opts.filter { it.legal }
            when {
                legal.size == 1 -> legal.single()
                opts.size == 1 -> opts.single()
                legal.isEmpty() -> return ShortcutResult.no("No Shortcut of ${script.name} can be used now: " + opts.joinToString("; ") { "${it.label}: ${it.why ?: NOT_NOW}" })
                else -> {
                    val ask = Decision.Option(legal.map { it.label })
                    val a = chooser.choose(ask)
                    if (!Chooser.legal(ask, a)) return ShortcutResult.CANCELLED
                    legal[a.single()]
                }
            }
        }
        if (!chosen.legal) return ShortcutResult.no("${chosen.label}: ${chosen.why ?: NOT_NOW}")
        val said = "${script.name} · ${chosen.label} (Shortcut)"
        val t = table(s)
        val done = when (val p = engine.play(t, seat, FxMove.Activate(uid, chosen.effect), chooser)) {
            is FxPlay.Refused -> return ShortcutResult.no(p.why)
            FxPlay.Cancelled -> return ShortcutResult.CANCELLED
            is FxPlay.Done -> p
        }
        val first = ShortcutResult(done.actions, steps(done), said = said, state = done.state, fx = done.fx)
        if (!(resolveAtOnce ?: s.solo) || done.state.chain.size <= s.chain.size) return first
        // Nothing can respond: the resolution joins the activation's group, so one undo takes back the whole effect.
        return when (val r = engine.play(FxTable(done.state, done.fx, book, facts).current(), seat, FxMove.Resolve, chooser)) {
            is FxPlay.Done -> first.copy(actions = first.actions + r.actions, tags = first.tags + steps(r), state = r.state, fx = r.fx)
            FxPlay.Cancelled -> ShortcutResult.CANCELLED
            is FxPlay.Refused -> first.copy(note = "It waits on the chain: ${r.why}")
        }
    }

    /**
     * Whether Chain Link [link] (1-based) of [s] holds a written effect — made by a Shortcut, or a hand-made activation of a
     * card that has one (§5½ 3: "when the newest link's card has a written effect") — so it can resolve as written.
     */
    fun written(s: DuelState, link: Int): Boolean = writtenIn(table(s), link)

    /** Whether link [n] of [t] holds a card's written effect the engine knows: a link of a card with none is resolved by hand. */
    private fun writtenIn(t: FxTable, n: Int): Boolean =
        t.fx.links.any { it.link == n && it.effect.isNotEmpty() && book.effect(it.card, it.effect) != null }

    /**
     * **Resolve by Shortcut** (§5½ 3): the newest link resolved as written, through the engine — or with [all] the whole
     * chain, newest first, each written link through the engine and the others by hand ([DuelVerbs.resolve]), in order.
     * One result, one group.
     */
    fun resolve(s: DuelState, seat: Int, catalog: DuelCatalog, chooser: Chooser, all: Boolean = false): ShortcutResult {
        if (networked) return ShortcutResult.no(DuelHost.NO_SHORTCUTS)
        if (s.chain.isEmpty()) return ShortcutResult.no("There is no chain to resolve")
        var t = table(s)
        if (!all) {
            val n = s.chain.size
            if (!writtenIn(t, n)) return ShortcutResult.no(
                if (t.fx.links.none { it.link == n }) "Chain Link $n was not made by a Shortcut: resolve it by hand" else "Chain Link $n has no written effect: resolve it by hand",
            )
            return when (val p = engine.play(t, seat, FxMove.Resolve, chooser)) {
                is FxPlay.Done -> ShortcutResult(p.actions, steps(p), said = "Resolve Chain Link $n by Shortcut", state = p.state, fx = p.fx)
                FxPlay.Cancelled -> ShortcutResult.CANCELLED
                is FxPlay.Refused -> ShortcutResult.no(p.why)
            }
        }
        val actions = mutableListOf<DuelAction>()
        val tags = mutableListOf<FxTag?>()
        val links = s.chain.size
        var guard = 0
        while (t.state.chain.isNotEmpty() && guard++ < MAX_LINKS) {
            val n = t.state.chain.size
            if (writtenIn(t, n)) {
                when (val p = engine.play(t, seat, FxMove.Resolve, chooser)) {
                    is FxPlay.Done -> {
                        actions += p.actions
                        tags += steps(p)
                        t = FxTable(p.state, p.fx, book, facts).current()
                    }
                    FxPlay.Cancelled -> return ShortcutResult.CANCELLED
                    is FxPlay.Refused -> return ShortcutResult.no("Chain Link $n: ${p.why}")
                }
            } else {
                val made = DuelVerbs.resolve(t.state, catalog)
                val (state, fx) = byHand(t.state, t.fx, made, seat) ?: return ShortcutResult.no("Chain Link $n cannot resolve")
                actions += made
                tags += made.map { null }
                t = FxTable(state, fx, book, facts).current()
            }
        }
        return ShortcutResult(actions, tags, said = "Resolve the whole chain by Shortcut ($links link${if (links == 1) "" else "s"})", state = t.state, fx = t.fx)
    }

    /**
     * [actions] made by hand on [s] by [seat], and the engine's state folded over them ([FxFold.step]) — what a plan needs
     * between two Shortcuts. Null when the table refuses one.
     */
    fun byHand(s: DuelState, fx: FxState, actions: List<DuelAction>, seat: Int?): Pair<DuelState, FxState>? {
        var state = s
        var f = fx
        actions.forEach { a ->
            val after = (DuelRules.apply(state, a, seat) as? Outcome.Ok)?.state ?: return null
            f = FxFold.step(f, state, DuelEntry(0, 0L, seat, 0, a), after, book, facts)
            state = after
        }
        return state to f
    }

    /** These Shortcuts after [actions] made by hand on [s]: the engine's state carried over them. */
    fun after(s: DuelState, actions: List<DuelAction>, seat: Int?): Shortcuts =
        withFx(byHand(s, table(s).fx, actions, seat)?.second ?: fx)

    companion object {
        const val NO_SCRIPT = "This card has no Shortcut written for it"
        /** A Shortcut line at a table given no written effects (every table, until the library of step 2). */
        const val NONE_AT_TABLE = "No card at this table has a written effect yet: make the moves by hand"
        const val NOT_YOURS = "Only the card's own player uses its Shortcut"
        const val NOT_NOW = "Not now"
        const val MAX_LINKS = 64

        /**
         * The Shortcuts of a duel in play: the engine's state folded from its log ([FxFold.fold] — exact for the engine's own
         * entries, inferred for a table played by hand).
         */
        fun of(
            game: DuelGame,
            book: ScriptBook,
            facts: FxFacts,
            engine: ShortcutEngine = ShortcutEngine.FX,
            networked: Boolean = false,
            resolveAtOnce: Boolean? = null,
            names: ((String) -> List<Int>)? = null,
            verified: (code: Int, effect: String) -> Boolean = { _, _ -> false },
        ): Shortcuts = Shortcuts(book, facts, FxFold.fold(game.header, game.played, book, facts, game.state), engine, networked, resolveAtOnce, verified, names)

        /**
         * The written effects a table is handed (D.md §5½): the library's scripts and the pool's facts, before any duel's
         * log is folded — what the `Effects` holder gives the Duel page, and [at] makes each table's.
         */
        fun written(
            book: ScriptBook,
            facts: FxFacts,
            verified: (code: Int, effect: String) -> Boolean = { _, _ -> false },
            names: ((String) -> List<Int>)? = null,
        ): Shortcuts = Shortcuts(book, facts, verified = verified, names = names)

        /** An effect's short name: its own label, else "Effect 2" by its id (or its place in the script). */
        fun label(e: Effect, index: Int): String =
            e.label.ifBlank { "Effect ${e.id.removePrefix("e").toIntOrNull() ?: (index + 1)}" }

        /** The effect [word] names: its id, its short name, "effect 2" or "2", or a short name it begins. */
        fun find(effects: List<Effect>, word: String): Effect? {
            val w = word.trim().lowercase()
            val labelled = effects.mapIndexed { i, e -> e to label(e, i).lowercase() }
            labelled.firstOrNull { (e, _) -> e.id.lowercase() == w }?.let { return it.first }
            labelled.firstOrNull { (_, l) -> l == w }?.let { return it.first }
            val n = w.removePrefix("effect").trim().toIntOrNull()
            if (n != null) labelled.firstOrNull { (e, l) -> e.id == "e$n" || l == "effect $n" }?.let { return it.first }
            return labelled.filter { (_, l) -> l.startsWith(w) }.singleOrNull()?.first
        }

        /** The engine's tags in step with its actions: one each. */
        private fun steps(p: FxPlay.Done): List<FxTag?> = p.actions.indices.map { p.tags.getOrNull(it) }
    }
}
