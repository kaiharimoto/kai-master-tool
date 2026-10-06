package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.CardFrame
import com.kaiharimoto.mastertool.core.duel.effects.CardScript
import com.kaiharimoto.mastertool.core.duel.effects.Cond
import com.kaiharimoto.mastertool.core.duel.effects.Event
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxCard
import com.kaiharimoto.mastertool.core.duel.effects.FxFacts
import com.kaiharimoto.mastertool.core.duel.effects.FxFilters
import com.kaiharimoto.mastertool.core.duel.effects.FxScope
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.FxWalk
import com.kaiharimoto.mastertool.core.duel.effects.Num
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.Proc
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.duel.effects.Spot
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.model.Card

/**
 * What a goldfish run reads by (D.md §5, §11): the trusted scripts ([FxTrust], its [book]), the pool's facts and the
 * duel's catalog over [cards] (any printing resolved), and which cards it plays as inert.
 */
class GoldfishKit(val trust: FxTrust, val cards: (Int) -> Card?) {
    val facts: FxFacts = FxFacts.over { cards(it.value) }
    val book: ScriptBook get() = trust.book
    val catalog: DuelCatalog = DuelCatalog.cached { code -> cards(code)?.let(DuelCardInfo::of) }

    fun canonical(code: Int): Int = book.canonical(code)

    /** Whether the engine knows [code]: a trusted script, or a Normal Monster (nothing to know). */
    fun known(code: Int): Boolean = book.has(code) || facts[code]?.normal == true

    /** Played as inert (§5.5): never activated, summoned, Set, material or a Tribute — moved only by known effects. */
    fun inert(code: Int): Boolean = !known(code)

    /** [uid] on [t] is a card played as inert (a token never is). */
    fun inertUid(uid: Int, t: FxTable): Boolean {
        val c: CardInst = t.state.cards[uid] ?: return false
        if (c.token) return false
        return inert(c.code)
    }

    fun name(code: Int): String = cards(code)?.name ?: facts[code]?.name ?: "#$code"

    companion object {
        /** Over a list of cards (the tests', the fixtures'): every printing resolves. */
        fun of(trust: FxTrust, pool: Iterable<Card>): GoldfishKit {
            val byId = HashMap<Int, Card>()
            pool.forEach { c -> c.passcodes.forEach { byId[it.value] = c } }
            return GoldfishKit(trust, byId::get)
        }
    }
}

/** A deck as the goldfish deals it: its Main Deck in the order listed and its Extra Deck, as canonical passcodes. */
data class GoldfishDeck(
    val main: List<Int>,
    val extra: List<Int> = emptyList(),
    val id: String? = null,
    /** `Ledger.fingerprint` of the deck: a number goes stale when it moves. */
    val fingerprint: String = "",
    val name: String = "",
)

/**
 * What the scripts in play let the goldfish skip (D.md §5.3): which cards are blanks, whether the Deck's order is ever read,
 * whether zones matter, whether the End Phase has triggers.
 *
 * A card is a **blank** when the engine does not know it (played as inert) and **both** hold:
 * - no trusted effect in play could pick it from the hand or the Deck — every pick and count of every script in play that
 *   looks there, costs included, judged against its printed facts; a filter that cannot be judged off a name alone (a bound
 *   card, a declaration, the lowest) is taken to match;
 * - no condition of the target names it.
 * An inert card a discard cost could take is not a blank: it is fodder. A hand is reduced to its engine part, and hands with
 * one engine part share their search — only while the scripts never read the Deck's order ([orderFree]).
 */
class GoldfishReduce(private val kit: GoldfishKit, private val deck: GoldfishDeck, private val target: EndBoard) {
    private val inPlay: List<CardScript> = (deck.main + deck.extra).map(kit::canonical).distinct().mapNotNull { kit.book.script(it) }

    /** No script in play draws, mills from the top or waits for a draw: the Deck's order is never read. */
    val orderFree: Boolean = inPlay.none { s ->
        s.effects.any { e ->
            e.trigger?.on?.event == Event.DRAWN || FxWalk.steps(e).any { st -> st.op is Op.Draw || picks(st.op).any { it.top } }
        } || s.summon?.procs.orEmpty().any { p -> p is Proc.Inherent && FxWalk.steps(p.cost).any { st -> st.op is Op.Draw || picks(st.op).any { it.top } } }
    }

    /** A Link Monster in the deck: arrows make zones matter. */
    val zonesMatter: Boolean = (deck.main + deck.extra).distinct().any { kit.facts[it]?.frames?.contains(CardFrame.LINK) == true }

    /** A script in play waits for the End Phase. */
    val endTriggers: Boolean = inPlay.any { s -> s.effects.any { it.trigger?.on?.event == Event.END_PHASE } }

    /** Every filter in play that looks at a hand or a Deck — yours, or either player's. */
    private val looks: List<Filter> = buildList {
        inPlay.forEach { s ->
            s.effects.forEach { e ->
                e.targets.forEach { p -> if (handOrDeck(p.from)) add(p.where) }
                (FxWalk.steps(e)).forEach { st -> addAll(filtersOf(st.op)) }
                e.condition?.let { addAll(condFilters(it)) }
            }
            s.summon?.procs.orEmpty().forEach { p ->
                if (p is Proc.Inherent) {
                    FxWalk.steps(p.cost).forEach { st -> addAll(filtersOf(st.op)) }
                    p.condition?.let { addAll(condFilters(it)) }
                }
            }
        }
    }

    private val named: List<Filter> = target.all.flatMap { BoardCheck.filters(it) }.map { it.second }

    private val scope = FxScope(FxTable(DuelState(), FxState(), kit.book, kit.facts), 0)

    /** The deck's blanks, worked out once: read-only from then on, so the run's workers share it. */
    private val blanks: Map<Int, Boolean> by lazy {
        (deck.main + deck.extra).map(kit::canonical).distinct().associateWith(::work)
    }

    private fun work(c: Int): Boolean {
        if (kit.known(c)) return false
        val facts = kit.facts[c] ?: return false
        return looks.none { couldMatch(it, facts) } && named.none { couldMatch(it, facts) }
    }

    /** Whether [code] is a blank. */
    fun blank(code: Int): Boolean = kit.canonical(code).let { c -> blanks[c] ?: work(c) }

    /** [hand]'s engine part: its cards that are not blanks, canonical and sorted. */
    fun reduce(hand: List<Int>): List<Int> = hand.map(kit::canonical).filterNot(::blank).sorted()

    private fun handOrDeck(from: List<Spot>): Boolean = from.any { (it.rel == Rel.YOU || it.rel == Rel.ANY) && (it.area == Area.HAND || it.area == Area.DECK) }

    private fun picks(op: Op): List<Pick> = when (op) {
        is Op.Move -> listOf(op.pick)
        is Op.Add -> listOf(op.pick)
        is Op.Send -> listOf(op.pick)
        is Op.Discard -> listOf(op.pick)
        is Op.Destroy -> listOf(op.pick)
        is Op.Banish -> listOf(op.pick)
        is Op.Tribute -> listOf(op.pick)
        is Op.Return -> listOf(op.pick)
        is Op.Reveal -> listOf(op.pick)
        is Op.SpecialSummon -> listOf(op.pick)
        is Op.Attach -> listOf(op.pick)
        is Op.Counter -> listOf(op.pick)
        is Op.ChangeLevel -> listOf(op.pick)
        else -> emptyList()
    }

    private fun filtersOf(op: Op): List<Filter> = buildList {
        picks(op).forEach { p -> if (handOrDeck(p.from)) add(p.where) }
        when (op) {
            is Op.If -> addAll(condFilters(op.cond))
            is Op.Lp -> addAll(numFilters(op.delta))
            is Op.PayLp -> addAll(numFilters(op.n))
            is Op.ChangeLevel -> { op.to?.let { addAll(numFilters(it)) }; op.by?.let { addAll(numFilters(it)) } }
            else -> {}
        }
    }

    private fun numFilters(n: Num): List<Filter> = if (n is Num.Count && handOrDeck(n.from)) listOf(n.where) else emptyList()

    private fun condFilters(c: Cond): List<Filter> = when (c) {
        is Cond.Count -> (if (handOrDeck(c.from)) listOf(c.where) else emptyList()) + numFilters(c.n)
        is Cond.Controls -> numFilters(c.n)
        is Cond.Compare -> numFilters(c.left) + numFilters(c.right)
        is Cond.Lp -> numFilters(c.n)
        is Cond.All -> c.all.flatMap(::condFilters)
        is Cond.AnyOf -> c.any.flatMap(::condFilters)
        is Cond.Not -> condFilters(c.not)
        else -> emptyList()
    }

    /**
     * Whether [f] could match a card with the printed [facts], anywhere: what a name alone cannot settle — a bound card, a
     * declaration, the lowest or highest, a newer word, a face or a controller — is taken to match.
     */
    private fun couldMatch(f: Filter, facts: FxCard): Boolean = when (f) {
        is Filter.All -> f.all.all { couldMatch(it, facts) }
        is Filter.AnyOf -> f.any.any { couldMatch(it, facts) }
        is Filter.Not -> if (certain(f.not)) !FxFilters.printed(f.not, facts, null, scope) else true
        Filter.Self -> false
        else -> if (certain(f)) FxFilters.printed(f, facts, null, scope) else true
    }

    /** A filter judged off the printed card alone, the same wherever the card is. */
    private fun certain(f: Filter): Boolean = when (f) {
        is Filter.All -> f.all.all(::certain)
        is Filter.AnyOf -> f.any.all(::certain)
        is Filter.Not -> certain(f.not)
        Filter.Self, Filter.NotSelf, is Filter.Same, is Filter.Lowest, is Filter.Highest, is Filter.Declared, is Filter.Unknown,
        Filter.FaceUp, Filter.FaceDown, is Filter.Controller -> false
        // "Always treated as" names live in the card's own script, which an inert card has none of.
        else -> true
    }
}
