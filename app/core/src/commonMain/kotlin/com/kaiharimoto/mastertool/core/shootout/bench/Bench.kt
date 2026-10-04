package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.CardPair
import com.kaiharimoto.mastertool.core.shootout.model.Compared
import com.kaiharimoto.mastertool.core.shootout.model.DeckList
import com.kaiharimoto.mastertool.core.shootout.model.Decks
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrint
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrints
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn

/** The other deck of a matchup, as it stands: its id (the log's file), its name and its cards. */
class Opponent(val id: String, val name: String, val deck: Deck)

/**
 * What a Shootout is built from (Phase S §1½): the deck as it stands, its groups, the card pool's lookup (so an
 * alternate artwork is the same card), the opponent for a matchup, and both decks' siding plans against each other.
 *
 * [mine] is your plan by your turn; [theirs] is their plan by **their** turn, so going first you meet their
 * going-second plan (Siding's "how they side against you"). The trials already kept join the numbering, so a card cut
 * since is still the card that was in the hand.
 */
class BenchInput(
    val deck: Deck,
    val cards: (CardId) -> Card?,
    val groups: DeckGroups = DeckGroups.EMPTY,
    val opponent: Opponent? = null,
    val mine: Map<Turn, SidePlan> = emptyMap(),
    val theirs: Map<Turn, SidePlan> = emptyMap(),
    val trials: List<StoredTrial> = emptyList(),
)

/**
 * The deck (and, for a matchup, the opponent) as the model sees it (Phase S stage 2): cards numbered by their
 * canonical passcode ([CardIdentity]), roles from the deck's groups, the strata that can be dealt and the ones
 * waiting for a siding plan, the decks each stratum deals from, and the conversion between kept trials and the
 * model's.
 *
 * Only the main deck is dealt from: the Extra Deck never opens in a hand. A sided deck is the main deck less the plan's
 * outs, plus its ins that are not Extra Deck cards. **A sided stratum needs both plans** — yours for your turn and
 * theirs for the answering one — and without them it is [waiting], never filled with game-one hands (S.md §1½).
 */
class Bench private constructor(
    val spec: ModelSpec,
    val decks: Decks,
    /** The numbering: card `i` of the model is `own[i]`, a canonical passcode. */
    val own: List<Int>,
    /** The opponent's numbering, likewise; empty for the deck alone. */
    val theirs: List<Int>,
    /** Each role's name, by the role's number. */
    val roleNames: List<String>,
    /** The strata that can be dealt, in [ALONE] or [MATCHUP] order. */
    val strata: List<Stratum>,
    /** The strata that cannot be dealt yet, and why, in words. */
    val waiting: Map<Stratum, String>,
    /** The plans each sided stratum is dealt under. */
    val prints: Map<Stratum, PlanPrints>,
    /** The opponent's name, or null for the deck alone. */
    val opponentName: String?,
    /** Any passcode to its card's canonical one: a kept trial may name a printing. */
    private val canon: (Int) -> Int,
) {
    private val ownAt: Map<Int, Int> = own.withIndex().associate { it.value to it.index }
    private val theirAt: Map<Int, Int> = theirs.withIndex().associate { it.value to it.index }

    /** Whether this is the deck on its own. */
    val alone: Boolean get() = opponentName == null

    /** The role of card [card] (a canonical passcode), or null when it is not in the numbering. */
    fun roleOf(card: Int): String? = ownAt[card]?.let { roleNames[spec.roles[it]] }

    /** Copies of [card] the deck deals in [stratum]. */
    fun copies(card: Int, stratum: Stratum): Int = ownAt[card]?.let { decks.own(stratum)[it] } ?: 0

    /** A hand kept as passcodes, over the numbering; null when one is not in it. */
    fun hand(ids: List<Int>): Hand? = counts(ids.map(canon), ownAt, own.size)

    /** The opponent's hand kept as passcodes, over theirs; null when one is not in it. */
    fun opponentHand(ids: List<Int>): Hand? = counts(ids.map(canon), theirAt, theirs.size)

    /** A hand as passcodes, one per copy, in the numbering's order. */
    fun ids(hand: Hand): List<Int> = hand.cards.flatMap { c -> List(hand[c]) { own[c] } }

    /** The opponent's hand as passcodes. */
    fun opponentIds(hand: Hand): List<Int> = hand.cards.flatMap { c -> List(hand[c]) { theirs[c] } }

    /**
     * A kept trial as the model reads it, or null when this model does not read it: another stratum, one waiting now,
     * an answer not the person's own blind one (Ai's parts come later, S.md §6), or a shape no build knows.
     */
    fun trial(t: StoredTrial): Trial? {
        if (!t.blind) return null
        val stratum = Stratum.entries.firstOrNull { it.name == t.stratum }?.takeIf { it in spec.strata } ?: return null
        val opp = if (alone) null else (t.opponent ?: return null).let { opponentHand(it) ?: return null }
        return when (t.kind) {
            StoredTrial.RATE -> {
                val answer = Answer.entries.firstOrNull { it.name == t.answer } ?: return null
                val hand = hand(t.hand)?.takeIf { it.size > 0 } ?: return null
                Rated(hand, opp, stratum, answer, plain = t.reason == PLAIN)
            }
            StoredTrial.COMPARE -> {
                val left = hand(t.left)?.takeIf { it.size > 0 } ?: return null
                val right = hand(t.right)?.takeIf { it.size > 0 } ?: return null
                val prefer = t.prefer ?: return null
                Compared(left, right, opp, stratum, leftPreferred = prefer == StoredTrial.LEFT)
            }
            else -> null
        }
    }

    /** A rating answered by the person, blind, ready to keep. */
    fun rated(p: Proposal.Rate, answer: Answer, id: String, at: Long, ms: Long?, session: String?): StoredTrial = StoredTrial(
        id = id,
        at = at,
        stratum = p.stratum.name,
        kind = StoredTrial.RATE,
        hand = ids(p.hand),
        opponent = p.opponent?.let(::opponentIds),
        answer = answer.name,
        reason = reasonWord(p.reason),
        plans = prints[p.stratum],
        ms = ms,
        session = session,
    )

    /** A comparison answered by the person, blind, ready to keep. */
    fun compared(p: Proposal.Compare, leftPreferred: Boolean, id: String, at: Long, ms: Long?, session: String?): StoredTrial = StoredTrial(
        id = id,
        at = at,
        stratum = p.stratum.name,
        kind = StoredTrial.COMPARE,
        left = ids(p.left),
        right = ids(p.right),
        opponent = p.opponent?.let(::opponentIds),
        prefer = if (leftPreferred) StoredTrial.LEFT else StoredTrial.RIGHT,
        reason = reasonWord(p.reason),
        plans = prints[p.stratum],
        ms = ms,
        session = session,
    )

    /** Whether a kept sided trial was dealt under plans other than today's: kept, labelled, pooled (S.md §1½). */
    fun underOlderPlan(t: StoredTrial): Boolean {
        val stratum = Stratum.entries.firstOrNull { it.name == t.stratum } ?: return false
        if (!stratum.sided) return false
        val now = prints[stratum] ?: return t.plans != null
        return t.plans != null && t.plans != now
    }

    companion object {
        /** The deck alone's strata. */
        val ALONE: List<Stratum> = listOf(Stratum.ALONE_FIRST, Stratum.ALONE_SECOND)

        /** A matchup's four strata, in the order they stand side by side (S.md §5). */
        val MATCHUP: List<Stratum> = listOf(Stratum.G1_FIRST, Stratum.G1_SECOND, Stratum.SIDED_FIRST, Stratum.SIDED_SECOND)

        /**
         * How many pairs are named for the model: the pairs most often drawn together, until Ai's reading of the cards
         * names better ones (S.md §6). The simulation tuned the pair prior for twelve.
         */
        const val PAIRS = 12

        /** The smallest deck a hand of six can be dealt from. */
        const val SMALLEST = 6

        private const val PLAIN = "plain"

        /** The words for a role with no group, and for the one role of a deck with none. */
        const val UNGROUPED = "Ungrouped"
        const val ALL_CARDS = "Cards"

        fun reasonWord(r: Reason): String = r.name.lowercase()

        /** Why [input] cannot be run yet, in words, or null when it can. */
        fun problem(input: BenchInput): String? {
            if (input.deck.main.size < SMALLEST) return "The main deck needs at least $SMALLEST cards to deal a hand from."
            val o = input.opponent
            if (o != null && o.deck.main.size < SMALLEST) return "${o.name}'s main deck needs at least $SMALLEST cards to deal a hand from."
            return null
        }

        /** The bench for [input]; [problem] must be null. */
        fun of(input: BenchInput): Bench {
            require(problem(input) == null) { problem(input).orEmpty() }
            val canon: (CardId) -> CardId = { CardIdentity.canonical(it, input.cards) }
            fun isExtra(id: CardId) = input.cards(id)?.isExtraDeck == true

            // The numbering: the main deck's cards in deck order, then cards a plan brings in, then cards only kept trials hold.
            val own = LinkedHashSet<Int>()
            input.deck.main.forEach { own += canon(it).value }
            val opponent = input.opponent
            val theirs = LinkedHashSet<Int>()
            opponent?.deck?.main?.forEach { theirs += canon(it).value }

            // The plans that make each sided stratum, mine for my turn and theirs for the answering one.
            val waiting = LinkedHashMap<Stratum, String>()
            val sidedDecks = HashMap<Stratum, Pair<List<Int>, List<Int>>>()
            val prints = HashMap<Stratum, PlanPrints>()
            if (opponent != null) {
                for (turn in Turn.entries) {
                    val stratum = if (turn == Turn.FIRST) Stratum.SIDED_FIRST else Stratum.SIDED_SECOND
                    val mine = input.mine[turn]?.takeIf { !it.isEmpty }
                    val theirsPlan = input.theirs[turn.theirs]?.takeIf { !it.isEmpty }
                    when {
                        mine == null && theirsPlan == null -> waiting[stratum] = "No siding plan for this turn yet, yours or ${opponent.name}'s."
                        mine == null -> waiting[stratum] = "Waiting for your plan ${turnWords(turn)}."
                        theirsPlan == null -> waiting[stratum] = "Waiting for ${opponent.name}'s plan ${turnWords(turn.theirs)}."
                        else -> {
                            val me = sided(input.deck.main, mine, canon, ::isExtra)
                            val them = sided(opponent.deck.main, theirsPlan, canon, ::isExtra)
                            if (me.size < SMALLEST || them.size < SMALLEST) {
                                waiting[stratum] = "A plan leaves a main deck under $SMALLEST cards."
                            } else {
                                me.forEach { own += it }
                                them.forEach { theirs += it }
                                sidedDecks[stratum] = me to them
                                prints[stratum] = PlanPrints(PlanPrint.of(mine, canon), PlanPrint.of(theirsPlan, canon))
                            }
                        }
                    }
                }
            }
            input.trials.forEach { t ->
                t.hands.forEach { h -> h.forEach { own += canon(CardId(it)).value } }
                if (opponent != null) t.opponent?.forEach { theirs += canon(CardId(it)).value }
            }
            val ownList = own.toList()
            val theirList = theirs.toList()

            fun listOf(cards: List<Int>, numbering: List<Int>): DeckList {
                val at = numbering.withIndex().associate { it.value to it.index }
                val copies = IntArray(numbering.size)
                cards.forEach { copies[at.getValue(it)]++ }
                return DeckList(copies)
            }
            val main = input.deck.main.map { canon(it).value }
            val mainList = listOf(main, ownList)

            val strata: List<Stratum>
            val decks: Decks
            if (opponent == null) {
                strata = ALONE
                decks = Decks.alone(mainList)
            } else {
                val theirMain = listOf(opponent.deck.main.map { canon(it).value }, theirList)
                val mineBy = HashMap<Stratum, DeckList>()
                val theirsBy = HashMap<Stratum, DeckList>()
                mineBy[Stratum.G1_FIRST] = mainList; mineBy[Stratum.G1_SECOND] = mainList
                theirsBy[Stratum.G1_FIRST] = theirMain; theirsBy[Stratum.G1_SECOND] = theirMain
                sidedDecks.forEach { (s, pair) ->
                    mineBy[s] = listOf(pair.first, ownList)
                    theirsBy[s] = listOf(pair.second, theirList)
                }
                strata = MATCHUP.filter { it in mineBy }
                decks = Decks(mineBy, theirsBy)
            }

            // Roles from the deck's groups, in their order; a card no group holds is Ungrouped; no groups, one role.
            val groupOf = HashMap<Int, String>()
            input.groups.assignments.forEach { (id, g) -> if (input.groups.byId(g) != null) groupOf.getOrPut(canon(id).value) { g } }
            val used = input.groups.ordered().filter { g -> ownList.any { groupOf[it] == g.id } }
            val names = used.map { it.name }.toMutableList()
            val roleAt = used.withIndex().associate { it.value.id to it.index }
            val roles = IntArray(ownList.size) { i -> roleAt[groupOf[ownList[i]]] ?: -1 }
            if (roles.any { it < 0 }) {
                names += if (used.isEmpty()) ALL_CARDS else UNGROUPED
                for (i in roles.indices) if (roles[i] < 0) roles[i] = names.lastIndex
            }

            val spec = ModelSpec(
                cards = ownList.size,
                strata = strata,
                roles = roles,
                opponentCards = if (opponent == null) 0 else theirList.size,
                pairs = pairs(mainList),
            )
            return Bench(spec, decks, ownList, theirList, names, strata, waiting, prints, opponent?.name) { canon(CardId(it)).value }
        }

        /** The [PAIRS] pairs most often in an opening hand together, by the deck's real odds. */
        private fun pairs(deck: DeckList): List<CardPair> {
            val held = (0 until deck.universe).filter { deck[it] > 0 }
            val all = ArrayList<Pair<CardPair, Double>>()
            for (i in held.indices) for (j in i + 1 until held.size) {
                all += CardPair(held[i], held[j]) to deck.bothShare(held[i], held[j], 5)
            }
            return all.sortedWith(compareByDescending<Pair<CardPair, Double>> { it.second }.thenBy { it.first.a }.thenBy { it.first.b })
                .take(PAIRS).map { it.first }.sortedWith(compareBy({ it.a }, { it.b }))
        }

        /** A main deck after [plan]: its outs taken out where it holds them, its Main Deck ins put in. */
        private fun sided(main: List<CardId>, plan: SidePlan, canon: (CardId) -> CardId, isExtra: (CardId) -> Boolean): List<Int> {
            val cards = main.map { canon(it).value }.toMutableList()
            plan.out.forEach { out -> cards.remove(canon(out).value) }
            plan.into.filterNot(isExtra).forEach { cards += canon(it).value }
            return cards
        }

        private fun turnWords(turn: Turn) = if (turn == Turn.FIRST) "going first" else "going second"

        private fun counts(ids: List<Int>, at: Map<Int, Int>, size: Int): Hand? {
            val c = IntArray(size)
            for (id in ids) c[at[id] ?: return null]++
            return Hand(c)
        }
    }
}
