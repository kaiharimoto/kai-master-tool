package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Lock
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import kotlinx.serialization.Serializable

/**
 * What the table does not hold and the engine folds beside it (D.md §2.1): a fold over the same log entries (`FxFold`,
 * agent (c)), **never stored**. The per-turn parts belong to [turn] and are dropped by [forTurn] when the table's turn
 * moves on; [proper], [lives], [tokens] and once-per-Duel uses last the duel.
 *
 * Written by: the summons and procedures (`FxSummons`, agent (a)); the chain, its links and pending triggers (`FxChain`,
 * agent (b)); the steps — uses, restrictions, Level changes, grants (`FxSteps`, agent (c)); and the fold of a log
 * (`FxFold`, agent (c)), which must agree with what the engine wrote (`FxFoldTest`).
 */
data class FxState(
    /** The turn the per-turn parts below belong to. */
    val turn: Int = 1,
    /** Normal Summons or Sets each seat made this turn, Tribute ones included. */
    val normals: Map<Int, Int> = emptyMap(),
    /** "Normal Summon 1 more" granted this turn ([Op.NormalSummonAgain]). */
    val grants: List<NormalGrant> = emptyList(),
    /** Once-per-turn (and once-per-Duel) uses, counted at activation. */
    val uses: List<OptUse> = emptyList(),
    /** Monsters summoned this turn, and how. */
    val summoned: Map<Int, ProcKind> = emptyMap(),
    /** Cards sent to the GY this turn. */
    val sent: Set<Int> = emptySet(),
    /** Cards Set this turn: a Trap or a set Quick-Play waits a turn (§2.4). */
    val setCards: Set<Int> = emptySet(),
    /** Canonical passcodes each seat Special Summoned this turn: [SummonRule.oncePerTurn]. */
    val specials: Map<Int, Set<Int>> = emptyMap(),
    /** Restrictions in force. */
    val restrictions: List<InForce> = emptyList(),
    /**
     * What each seat did this turn that a "the turn you activate this" condition asks about ([Effect.leaves]): its Normal
     * Summons and Sets, its Special Summons (from the Extra Deck or not) and its activations.
     */
    val deeds: List<Deed> = emptyList(),
    /** Each chain link's effect, targets and bindings, in step with `DuelState.chain` (agent (b)). */
    val links: List<FxLink> = emptyList(),
    /** Triggers waiting to go on a chain (agent (b)). */
    val pending: List<Pending> = emptyList(),
    /** Level changes still in effect ([Op.ChangeLevel]). */
    val levels: List<LevelChange> = emptyList(),
    /** Monsters properly summoned (by their own procedure, or as "must first be" asks), until they go back to a hidden pile. */
    val proper: Set<Int> = emptySet(),
    /** Each card's instance: one more each time a move takes it somewhere else (D.md: "a new instance once it leaves"). */
    val lives: Map<Int, Int> = emptyMap(),
    /** Tokens the engine made, with the facts their op gave them (a Level the table's token does not hold). */
    val tokens: Map<Int, FxCard> = emptyMap(),
    /** Folded from a log made by hand: inferred, so a rule's doubt is said rather than refused silently (§5½). */
    val inferred: Boolean = false,
    /**
     * How many things the log has left to chance so far (shuffles, coins, dice, random picks): the engine stamps a shuffle
     * it makes with the duel's dice for that roll ([FxTable.seed], `DuelRandom.forRoll`), so committing it through
     * `DuelGame.act` stamps the same salt and the table the engine played on is the one the log folds to.
     */
    val rolls: Int = 0,
    /**
     * The newest batch of things that happened at the same time (D.md §2.3): an engine entry carries its batch
     * ([FxMemo.batch]); one made by hand is a batch of its own. What "last" and missing the timing are judged by.
     */
    val batch: Int = 0,
    /**
     * The batch at the chain's last boundary (a link added or resolved): when a link resolves, whatever happened before
     * its resolution began is no longer last — the last thing to happen is that link's resolution, whatever it did (TCG
     * Rulebook v10: "the last thing to happen is the resolution of the effect at Chain Link 1").
     */
    val since: Int = 0,
    /** While a chain stands and is not resolving: the seat that may respond now (the other seat after each link). */
    val priority: Int? = null,
    /** Passes in a row since the newest link or the last resolution: two, and the newest link resolves. */
    val passes: Int = 0,
    /** The chain has begun to resolve: no link is added until it is over. */
    val resolving: Boolean = false,
) {
    fun life(uid: Int): Int = lives[uid] ?: 0

    fun normalsUsed(seat: Int): Int = normals[seat] ?: 0

    /**
     * This state for [turn]: the same when it is already that turn's, else the per-turn parts dropped — Normal Summons,
     * grants, uses (once-per-Duel ones kept), this turn's summons, sends and sets, restrictions and Level changes that
     * last the turn, links and pending triggers.
     */
    fun forTurn(turn: Int): FxState = if (turn == this.turn) this else copy(
        turn = turn,
        normals = emptyMap(),
        grants = emptyList(),
        uses = uses.filter { it.duel },
        summoned = emptyMap(),
        sent = emptySet(),
        setCards = emptySet(),
        specials = emptyMap(),
        restrictions = restrictions.filter { it.restriction.until == Lock.UNTIL_DUEL },
        deeds = emptyList(),
        links = emptyList(),
        pending = emptyList(),
        levels = levels.filter { it.until == Lock.UNTIL_DUEL },
        priority = null,
        passes = 0,
        resolving = false,
    )

    /**
     * [uid] was taken to [to]: a new instance from now on. Back in the hand, the Deck or the Extra Deck it is no longer
     * properly summoned; in the GY or banished it still is, which is what "must first be" asks.
     */
    fun moved(uid: Int, to: Place?): FxState {
        val hidden = to is Place.Pile && (to.kind == PileKind.HAND || to.kind == PileKind.DECK || to.kind == PileKind.EXTRA)
        return copy(lives = lives + (uid to life(uid) + 1), proper = if (hidden || to == null || to == Place.Void) proper - uid else proper)
    }

    companion object {
        /** A fresh state for the table [s]: its turn. */
        fun at(s: DuelState): FxState = FxState(turn = s.turn)
    }
}

/**
 * A declaration's answer ([Op.Declare]): [kind], and [value] — a name's canonical passcode, a Level — or [word] — a Type,
 * an Attribute — as the chooser picked it from [Decision.Declare.among].
 */
@Serializable
data class Declared(val kind: DeclareKind, val value: Int = 0, val word: String = "")

/**
 * A once-per-turn use: counted under [key] (`FxRules.optKey`), by [seat], of [card] (canonical) [effect] on [uid]. [link]:
 * the chain link it was counted for while that link stands; [refunds]: "you can only activate" wording, given back when
 * that activation is negated ([Opt.ByName.refunds]).
 */
data class OptUse(
    val seat: Int,
    val card: Int,
    val effect: String,
    val key: String,
    val uid: Int,
    /** The card's instance when used ([FxState.life]): a per-copy use ends when it leaves. */
    val life: Int,
    val turn: Int,
    /** Once per Duel: never dropped at the turn's end. */
    val duel: Boolean = false,
    val link: Int? = null,
    val refunds: Boolean = false,
)

/** Something [seat] did this turn with [uid] ([FxState.deeds]): [ban] names it as a restriction would; [extra] from the Extra Deck. */
data class Deed(val seat: Int, val uid: Int, val ban: Ban, val extra: Boolean = false, val link: Int? = null)

/** "Normal Summon 1 more": for [seat], of a monster [filter] matches (judged as [source]'s controller would). */
data class NormalGrant(val seat: Int, val source: Int, val filter: Filter = Filter.Any, val used: Boolean = false)

/**
 * A [restriction] in force on [seat] (absolute), left by [source]. [lock]: the table's `Lock` that shows it; [link]: the
 * chain link whose activation set it ([Effect.leaves]) while that link stands — lifted if that activation is negated.
 */
data class InForce(val restriction: Restriction, val seat: Int, val source: Int, val turn: Int, val lock: Int? = null, val link: Int? = null)

/** A Level changed by an effect: set [to], or moved [by], while [uid] stays the instance [life], until [until]. */
@Serializable
data class LevelChange(val uid: Int, val life: Int, val to: Int? = null, val by: Int? = null, val until: String = Lock.UNTIL_TURN)

/**
 * Something that happened, for triggers to read (agent (b)) and steps to report (agent (c)): [event] to [uid], whose
 * controller was [seat] before it, from [from] to [to], for [cause], by [source]'s effect or procedure. [batch] numbers
 * the batch it happened in within one resolution or action, so "last" (missing the timing) can be judged.
 */
data class FxEvent(
    val event: Event,
    val uid: Int,
    val seat: Int,
    val from: Place? = null,
    val to: Place? = null,
    val cause: Cause? = null,
    val summon: ProcKind? = null,
    val source: Int? = null,
    val batch: Int = 0,
)

/** A trigger waiting to go on a chain (agent (b)): [uid]'s [effect], its controller [seat], set off by [event]. */
data class Pending(
    val uid: Int,
    val card: Int,
    val effect: String,
    val seat: Int,
    val mandatory: Boolean,
    val event: FxEvent,
    /**
     * Nothing has happened since its event (D.md §2.3): an optional `WHEN` trigger whose event is no longer last when its
     * chain is built misses the timing. `IF` triggers and mandatory ones never do.
     */
    val last: Boolean = true,
)

/**
 * A chain link as the engine knows it (agent (b)): link [link] (1-based, as `DuelState.chain`), [uid]'s [effect] by [seat]
 * at spell speed [speed], with its [bound] cards ("targets", "self", names a step bound) and its script's hash.
 */
data class FxLink(
    val link: Int,
    val seat: Int,
    val uid: Int,
    val card: Int,
    val effect: String,
    val speed: Int,
    val bound: Map<String, List<Int>> = emptyMap(),
    /** "Negate the effect" (the card stays and resolves doing nothing) as against the activation (`ChainLink.negated`). */
    val effectNegated: Boolean = false,
    val script: String = "",
    val verified: Boolean = false,
    /** Each bound card's instance as it was bound ([FxState.life]): a target that moved since is gone at resolution. */
    val lives: Map<Int, Int> = emptyMap(),
    /** What was declared as it was activated ([Op.Declare] among its costs). */
    val declared: Map<String, Declared> = emptyMap(),
)

/**
 * The engine's view of a duel at one moment (D.md §2.5): the table, what the engine folds beside it, the scripts it plays
 * by and the pool's facts. Pure: the same table, scripts and answers always give the same actions.
 */
data class FxTable(
    val state: DuelState,
    val fx: FxState,
    val book: ScriptBook,
    val facts: FxFacts,
    /** The duel's seed (`DuelHeader.seed`): a shuffle the engine makes is stamped as the log will stamp it ([FxState.rolls]). */
    val seed: Long = 0L,
) {
    fun inst(uid: Int): CardInst? = state.cards[uid]

    /** A card's printed facts — a token's from what made it — before any Level change. */
    fun card(uid: Int): FxCard? {
        val c = state.cards[uid] ?: return null
        return fx.tokens[uid] ?: facts.of(c)
    }

    /** A card's canonical passcode: what a script, a once-per-turn use and "by name" read. */
    fun code(uid: Int): Int? = state.cards[uid]?.let { if (it.token && it.code == 0) null else book.canonical(it.code) }

    /** The card's script, by any printing. */
    fun script(uid: Int): CardScript? = state.cards[uid]?.takeIf { !it.token || it.code != 0 }?.let { book.script(it.code) }

    /** [uid]'s Level now: its printed one with every change still in effect on this instance applied, in order. */
    fun level(uid: Int): Int? {
        var level = (card(uid)?.level ?: return null).toLong()
        val life = fx.life(uid)
        fx.levels.forEach { ch ->
            if (ch.uid != uid || ch.life != life) return@forEach
            ch.to?.let { level = it.toLong() }
            ch.by?.let { level += it }
        }
        // In Long and clamped, so a change past any Level never wraps round.
        return level.coerceIn(1L, MOST_LEVEL.toLong()).toInt()
    }

    /** The same table with [fx] for the table's turn: what every rule reads. */
    fun current(): FxTable = if (fx.turn == state.turn) this else copy(fx = fx.forTurn(state.turn))

    companion object {
        /** The highest Level a change can make (a card's own Level never passes 13). */
        const val MOST_LEVEL = 99
    }

    // ---- worked out once a table (the red team's profile, D.md §5.7) ---------------------------------------------------
    // A table never changes: a move makes a new one (`copy`, which starts these afresh). They are not part of equality.

    /** The restrictions binding now ([FxRules.inForce]): read by every activation, summon and Special Summon checked. */
    internal val inForce: List<InForce> by lazy(LazyThreadSafetyMode.PUBLICATION) { FxRules.inForceNow(this) }

    /** Why each (seat, card, effect) may not be activated on this table, as `FxChain.refusal` worked it out: null, it may. */
    @kotlin.concurrent.Volatile
    private var refusals: Map<Triple<Int, Int, String>, String?> = emptyMap()

    /** [seat]'s refusal for [uid]'s [effect] on this table: worked out once by [work], a map replaced whole on a miss. */
    internal fun refusal(seat: Int, uid: Int, effect: String, work: () -> String?): String? {
        val key = Triple(seat, uid, effect)
        val now = refusals
        if (key in now) return now[key]
        val why = work()
        refusals = HashMap<Triple<Int, Int, String>, String?>(now.size * 2 + 4).apply { putAll(now); put(key, why) }
        return why
    }
}
