package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.BoardKey
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapMoves
import com.kaiharimoto.mastertool.core.duel.mapper.MapPlan
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import com.kaiharimoto.mastertool.core.duel.mapper.MapperVocab
import com.kaiharimoto.mastertool.core.duel.mapper.MovePrior
import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/*
 * The gate's hands (M.md §4.5, step M3): a set of hands no training ever sees, mapped once exhaustively with no prior for the
 * boards nothing beats, and the orchestration that judges a candidate network on them — every number from the engine, none
 * from the network's own loss or from Ai's word.
 *
 * **Held out means held out** (§4.6): gate hands are dealt from a seed no training round uses ([GATE_SEED]), and a training
 * record whose hand is a gate hand's ([HeldOut]) is never written — by the hand's engine cards and seat order, so the same
 * starter dealt beside other blanks is held out too. [HeldOut.leaks] is the audit: it reads the records written and names
 * any that broke the rule.
 */

/** The gate hands' identities (their engine cards and the seat order): what training never writes a record of. */
class HeldOut(val keys: Set<String>) {
    /** Whether [deal] is a gate hand's. */
    fun holds(deal: MapDeal): Boolean = key(deal) in keys

    /** Whether a record's `hand` ([com.kaiharimoto.mastertool.core.duel.mapper.TrainingExport.handId]) is a gate hand's. */
    fun holds(handId: String): Boolean = keyOf(handId) in keys

    /** The audit (§4.6): the hands of [lines] (records, JSON Lines) that are gate hands'. Empty when the rule held. */
    fun leaks(lines: List<String>): List<String> = lines.mapNotNull { line -> HAND.find(line)?.groupValues?.get(1) }.filter(::holds).distinct()

    companion object {
        private val HAND = Regex(""""hand":"([^"]*)"""")

        /** A deal's identity: its engine cards sorted and whether it goes first, never its blanks or its seed. */
        fun key(deal: MapDeal): String = "${deal.hand.sorted().joinToString(".")}/${if (deal.first) "1st" else "2nd"}"

        /** The identity in a record's hand id, "<cards>[+<fodder>]/<1st|2nd>". */
        fun keyOf(handId: String): String = handId.substringBeforeLast('/').substringBefore('+') + "/" + handId.substringAfterLast('/')
    }
}

/**
 * One gate hand: its deal, the keys of the boards on its exhaustive map's front ([MapSearch.Mapped.front]), and the most of
 * each trait any of its boards has — what the network's value at the hand's start should say.
 */
@Serializable
data class GateDeal(
    val deal: MapDeal,
    val front: List<String> = emptyList(),
    val most: Map<String, Double> = emptyMap(),
    /** The engine moves the exhaustive map took. */
    val moves: Int = 0,
    /**
     * The hand-written order's front recall at the set's budget (version 0's): read off the exhaustive map itself, which is
     * that order's own search — cut at the budget it would have met the same boards at the same moves.
     */
    val bare: Double = 0.0,
) {
    val key: String get() = HeldOut.key(deal)
}

/**
 * The gate set (`train/gate.json`): the held-out hands, mapped once exhaustively, and the one budget every network maps them
 * at. [print] names what it was made from — the deck, its scripts, the seed and the sizes — and a set whose print is not the
 * deck's as it is is made again.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GateSet(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION,
    val print: String = "",
    val budget: Int = GATE_BUDGET,
    val hands: List<GateDeal> = emptyList(),
    /** Deals tried and left out: their exhaustive map was incomplete, or had no choice to make. */
    val skipped: Int = 0,
) {
    val heldOut: HeldOut get() = HeldOut(hands.map { it.key }.toSet())

    /** The value heads every hand measured: what the gate judges the heads on. */
    fun heads(): List<String> = BoardTraits.HEADS.filter { h -> hands.isNotEmpty() && hands.all { h in it.most } }

    /** The hand-written order's front recall on each hand (version 0's), by the hand's key. */
    val bare: Map<String, Double> get() = hands.associate { it.key to it.bare }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        /** The seed gate hands are dealt from: no training round's ([TrainLoop.roundSeed]). */
        const val GATE_SEED = 0x6A7E_5EEDL

        /** The gate set's one budget (M.md §4.5): what every network maps a gate hand at, the page's own runs' budget. */
        const val GATE_BUDGET = 20_000

        /** What a gate hand's exhaustive map may spend; a hand it cannot finish is no gate hand. */
        const val EXHAUSTIVE_BUDGET = 400_000

        /** Hands in the set: above [TrainGate.MIN_HANDS], so a verdict can be made. */
        const val HANDS = 40

        /** Hands dealt a side for each gate hand wanted: a deck of big maps finishes few of them exhaustively. */
        const val DEALS = 6

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; allowSpecialFloatingPointValues = true }

        fun decode(text: String): GateSet? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()

        /** What a set for [deck] with [kit] at these sizes is made from. */
        fun print(deck: GoldfishDeck, kit: GoldfishKit, hands: Int, budget: Int, exhaustive: Int): String =
            Sha256.hex("$VERSION|${deck.fingerprint}|${Mapper.scripts(deck, kit)}|$GATE_SEED|$hands|$DEALS|$budget|$exhaustive|${BoardKey.VERSION}").take(16)

        /**
         * The gate set for [deck]: deals from [GATE_SEED], half going first and half second, each mapped exhaustively with no
         * prior; the first [hands] whose maps are complete and hold a choice (two boards or more), half going first and half
         * second where the deck allows, in the deals' order — the same set however many [workers] ran, since a chunk's maps are
         * read in order and the set stops growing at the first [hands]. [done] is told after each map. Null when [stop] ended it.
         */
        suspend fun build(
            deck: GoldfishDeck,
            kit: GoldfishKit,
            hands: Int = HANDS,
            budget: Int = GATE_BUDGET,
            exhaustive: Int = EXHAUSTIVE_BUDGET,
            workers: Int = MapWork.workers(),
            stop: () -> Boolean = { false },
            done: () -> Unit = {},
        ): GateSet? {
            val plan = Mapper.Plan(MapperSetup(deck, true, 1, GATE_SEED, exhaustive), kit).maps
            // Enough deals to find the set on a deck whose maps are mostly too big to finish: they are only mapped until it is found.
            fun deals(first: Boolean) = Mapper.Plan(MapperSetup(deck, first, hands * DEALS, GATE_SEED, exhaustive), kit).deals
            val chunk = maxOf(8, workers * 2)
            var examined = 0

            /** The first [need] gate hands of [from] from its [at]th deal on, and how far it read; null when stopped. */
            suspend fun gather(from: List<MapDeal>, need: Int, at: Int = 0): Pair<List<GateDeal>, Int>? {
                val out = ArrayList<GateDeal>()
                var i = at
                while (out.size < need && i < from.size) {
                    val part = from.subList(i, minOf(from.size, i + chunk))
                    val mapped = MapWork.each(part.size, workers, stop) { j, halt ->
                        val d = part[j]
                        val t = d.table(plan.main, plan.extra, kit)
                        val m = MapSearch(kit, exhaustive, plan.depth, ordered = plan.ordered, zonesMatter = plan.zones, cancelled = halt).map(t, d.fodderUids(t))
                        done()
                        // A map cut short, or one with nothing to choose, is no gate hand: its moves say so (-1).
                        m.takeIf { it.complete && it.ends.size >= 2 }?.let { g ->
                            val front = g.front()
                            GateDeal(d, front.sorted(), BoardTraits.HEADS.associateWith { h -> g.ends.maxOf { it.traits[h] ?: 0.0 } }, g.moves, FrontRecall.auc(front, g.found, budget))
                        } ?: GateDeal(d, moves = -1)
                    }
                    if (stop()) return null
                    for (g in mapped) {
                        if (out.size >= need) break
                        i++
                        examined++
                        if (g != null && g.moves >= 0) out += g
                    }
                }
                return out to i
            }

            val firsts = deals(true)
            val (a, readA) = gather(firsts, hands / 2) ?: return null
            val (b, _) = gather(deals(false), hands - a.size) ?: return null
            val (c, _) = gather(firsts, hands - a.size - b.size, readA) ?: return null
            val chosen = a + c + b
            return GateSet(VERSION, print(deck, kit, hands, budget, exhaustive), budget, chosen, examined - chosen.size)
        }
    }
}

/** A board the person confirmed (a kept line, a combo saved from a route): the deal that reaches it and its key. */
data class Confirmed(val deal: MapDeal, val key: String, val words: String = "")

/** What the gate measured of one network on the set: each hand's front recall by its key, and each head's error. */
class Measured(val recall: Map<String, Double>, val valueError: Map<String, Double>)

/**
 * The gate's orchestration (M.md §4.5): [set]'s hands mapped by each network at the set's budget, front recall from each
 * board's `End.at`, each value head's error at the hands' start against the exhaustive maps' maxima, the person's confirmed
 * boards looked for again, and [TrainGate.judge]. Pure over its seams: the networks are [PriorNet]s, so a fake one judges
 * the same way in a test.
 */
class GateRun(
    private val set: GateSet,
    private val deck: GoldfishDeck,
    private val kit: GoldfishKit,
    private val vocab: MapperVocab,
    private val workers: Int = MapWork.workers(),
) {
    private val plan: MapPlan = Mapper.Plan(MapperSetup(deck, true, 1, GateSet.GATE_SEED, set.budget), kit).maps

    /** Each gate hand's front recall mapped in [prior]'s order (by the hand's key); null when [stop] ended it. */
    suspend fun recall(prior: MovePrior, stop: () -> Boolean = { false }, done: () -> Unit = {}): Map<String, Double>? {
        val out = MapWork.each(set.hands.size, workers, stop) { i, halt ->
            val g = set.hands[i]
            val t = g.deal.table(plan.main, plan.extra, kit)
            val m = MapSearch(kit, set.budget, plan.depth, ordered = plan.ordered, zonesMatter = plan.zones, prior = prior, cancelled = halt).map(t, g.deal.fodderUids(t))
            done()
            g.key to FrontRecall.auc(g.front.toSet(), m.found, set.budget)
        }
        if (stop() || out.any { it == null }) return null
        return out.filterNotNull().toMap()
    }

    /** Each head of [net] the set measured: the mean absolute error of its value at every gate hand's start. */
    fun valueError(net: PriorNet): Map<String, Double> {
        if (set.hands.isEmpty()) return emptyMap()
        val positions = set.hands.map { g ->
            val t = g.deal.table(plan.main, plan.extra, kit)
            val seat = FxEngine.next(t)
            NetInput.encode(t, seat, 0, MapMoves.distinct(t, seat, FxRules.open(t), plan.ordered), vocab)
        }
        val out = net.run(NetInput.batch(positions))
        val k = net.heads.size
        return set.heads().filter { it in net.heads }.associateWith { h ->
            val j = net.heads.indexOf(h)
            set.hands.indices.sumOf { i -> abs(out.value[i * k + j].toDouble() - set.hands[i].most.getValue(h)) } / set.hands.size
        }
    }

    /** Version 0's error (M.md §4.5): predicting each head's training mean, [headMean] by [heads]' order. */
    fun meanError(heads: List<String>, headMean: List<Double>): Map<String, Double> =
        set.heads().filter { it in heads }.associateWith { h ->
            val mean = headMean.getOrNull(heads.indexOf(h)) ?: 0.0
            set.hands.sumOf { abs(mean - it.most.getValue(h)) } / set.hands.size.coerceAtLeast(1)
        }

    /** [net] measured on the set: its recall and its heads' error. Null when [stop] ended it. */
    suspend fun measure(net: PriorNet, stop: () -> Boolean = { false }, done: () -> Unit = {}): Measured? {
        val r = recall(NetPrior(net, vocab), stop, done) ?: return null
        return Measured(r, valueError(net))
    }

    /** The boards of [confirmed] [prior]'s maps at the set's budget no longer find: any one blocks a promotion. */
    suspend fun regressions(prior: MovePrior, confirmed: List<Confirmed>, stop: () -> Boolean = { false }): List<String>? {
        if (confirmed.isEmpty()) return emptyList()
        val out = MapWork.each(confirmed.size, workers, stop) { i, halt ->
            val c = confirmed[i]
            val t = c.deal.table(plan.main, plan.extra, kit)
            val m = MapSearch(kit, set.budget, plan.depth, ordered = plan.ordered, zonesMatter = plan.zones, prior = prior, cancelled = halt).map(t, c.deal.fodderUids(t))
            if (c.key in m.found) "" else c.words.ifBlank { c.key }
        }
        if (stop() || out.any { it == null }) return null
        return out.filterNotNull().filter { it.isNotEmpty() }
    }

    companion object {
        /**
         * The verdict on a candidate measured as [candidate] against the network in use measured as [current] (version 0, the
         * hand-written order, has no value: its error is [currentError], predicting the mean): [TrainGate.judge] on the hands
         * both mapped, with the confirmed boards it lost and the candidates tried since the last promotion.
         */
        fun verdict(
            candidate: Measured,
            current: Map<String, Double>,
            currentError: Map<String, Double>,
            regressed: List<String>,
            attempts: Int,
            budget: Int,
        ): GateVerdict {
            val hands = candidate.recall.keys.sorted().filter { it in current }.map { k -> GateHand(k, candidate.recall.getValue(k), current.getValue(k), budget) }
            return TrainGate.judge(hands, regressed, candidate.valueError, currentError, attempts, heads = currentError.keys)
        }
    }
}
