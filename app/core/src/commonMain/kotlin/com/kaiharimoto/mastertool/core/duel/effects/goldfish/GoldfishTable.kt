package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.Purpose

/*
 * The goldfish's tables (D.md §5.3): hand k dealt from the run's seed, on a one-player table — there is no opponent, which is
 * what a goldfish is — and the canonical key of a table that the search's transposition table and the reduced hands rest on.
 */

/** Hands and seeds (§5.3). */
object GoldfishHands {
    /**
     * The seed of hand [k] of a run seeded [seed]: the duel's own dice for that roll (`DuelRandom.forRoll`), so the same seed
     * deals the same hands on every platform. It keys the riffle and the hand's table (a shuffle the engine makes in it).
     */
    fun handSeed(seed: Long, k: Int): Long = DuelRandom.forRoll(seed, k).nextLong()

    /**
     * How hands are dealt (2026-10, the red team's G1). Deal 2, [keyed]: every copy of the Main Deck takes a key from the
     * hand's roll, its card and which copy of it it is, and the Deck's order is the keys' order — so the hands never depend
     * on the order the decklist lists its cards in, and two versions of a deck deal every copy they share to the same
     * place: "cut one X, add one Y" changes only the hands the changed copy lands in, and two versions are compared on the
     * same hands. Deal 1 is the riffle over the list as given, kept for results made with it.
     */
    const val DEAL = 2

    /**
     * The Main Deck's order for hand [k], top first: [keyed] (deal 2), or the duel's Fisher–Yates over [main] as listed (deal 1).
     * [keyAs] is [keyed]'s.
     */
    fun order(main: List<Int>, seed: Long, k: Int, deal: Int = DEAL, keyAs: List<Int>? = null): List<Int> =
        if (deal >= 2) keyed(main, seed, k, keyAs) else DuelRandom.riffle(main, handSeed(seed, k))

    /**
     * Deal 2: [main]'s copies in the order of their keys for hand [k] ([DEAL]). [keyAs], beside [main] entry for entry, names
     * the card each entry is keyed as (Phase G): a card put in another's place — a substitute, a blank for "without it" —
     * takes the key of the copy it replaced, so the two versions' hands are the same hands but for that one card. Null keys
     * every entry as itself.
     */
    fun keyed(main: List<Int>, seed: Long, k: Int, keyAs: List<Int>? = null): List<Int> {
        val roll = handSeed(seed, k)
        val names = keyAs?.takeIf { it.size == main.size } ?: main
        val copies = HashMap<Int, Int>()
        val keyed = main.indices.map { i ->
            val name = names[i]
            val copy = copies[name] ?: 0
            copies[name] = copy + 1
            Keyed(key(roll, name, copy), name, copy, main[i])
        }
        return keyed.sortedWith(compareBy<Keyed>({ it.key }, { it.name }, { it.copy })).map { it.card }
    }

    private class Keyed(val key: Long, val name: Int, val copy: Int, val card: Int)

    /** A copy's key: SplitMix64's finaliser over the hand's roll, the card and its copy — plain `Long` arithmetic, the same on every platform. */
    private fun key(roll: Long, card: Int, copy: Int): Long {
        var z = roll + card.toLong() * -0x61c8864680b583ebL + copy.toLong() * 0x632be59bd9b4e019L
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Cards in the opening hand: 5 going first, 6 going second (the turn's draw made). */
    fun size(first: Boolean): Int = if (first) 5 else 6

    /** Hand [k] as dealt: the top [size] of [order]. */
    fun hand(main: List<Int>, seed: Long, k: Int, first: Boolean, deal: Int = DEAL, keyAs: List<Int>? = null): List<Int> =
        order(main, seed, k, deal, keyAs).take(size(first))

    /**
     * Hand [k]'s table, as a duel: a one-player table (`solo`), the Main Deck in its order for the hand and the Extra Deck as
     * listed; going second, the turn passed once first (an empty field across the table). The deal, the draw and the move
     * to the Main Phase 1 are the table's own entries, behind undo's reach — what a replay opens on.
     */
    fun game(main: List<Int>, extra: List<Int>, seed: Long, k: Int, first: Boolean, name: String = "", deal: Int = DEAL, keyAs: List<Int>? = null): DuelGame {
        val handSeed = handSeed(seed, k)
        val header = DuelHeader(
            id = "goldfish-$seed-$k",
            seed = handSeed,
            seats = listOf(SeatSetup(name = name, main = order(main, seed, k, deal, keyAs), extra = extra), SeatSetup()),
            first = 0,
            solo = true,
            handSize = 0,
        )
        val base = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val n = minOf(size(first), main.size)
        val deal = buildList {
            if (!first) add(DuelAction.EndTurn)
            if (n > 0) add(DuelAction.Draw(0, n))
            add(DuelAction.Phase(DuelPhase.MAIN1))
        }
        val dealt = base.act(deal, null)
        check(dealt.ok) { "the goldfish's deal was refused: ${dealt.problem}" }
        return dealt.game.copy(floor = dealt.game.cursor)
    }

    /** The engine's view of [game]: a fresh engine state at its turn (nothing the engine made is in its log yet). */
    fun table(game: DuelGame, kit: GoldfishKit): FxTable =
        FxTable(game.state, FxState.at(game.state), kit.book, kit.facts, game.header.seed)
}

/**
 * A table read without its uids (D.md §5.4): each card by identity and place — two copies in one pile are one — with
 * what the engine keeps of it, and the turn's own state. Equal keys are tables the search treats as one. Deck order is
 * kept only when the scripts read it ([ordered]).
 */
internal class TableKey(private val t: FxTable, private val ordered: Boolean) {
    private val s = t.state

    /** A card as identity and place: "900000001z0.0.2+0". */
    fun token(uid: Int): String {
        val c = s.cards[uid] ?: return "gone"
        val code = if (c.token) "T" + (c.name ?: "") + (t.fx.tokens[uid]?.level ?: "") else t.book.canonical(c.code).toString()
        val place = when (val p = s.placeOf(uid)) {
            is Place.Zone -> "z${p.seat}.${p.kind.ordinal}.${p.index}"
            is Place.Pile -> if (p.kind == PileKind.DECK && ordered) "p${p.seat}.${p.kind.ordinal}@${p.at}" else "p${p.seat}.${p.kind.ordinal}"
            is Place.Under -> "u(" + token(p.host) + ")${p.index}"
            Place.Void, null -> "v"
        }
        return code + place + (if (c.faceUp) "+" else "-") + c.pos.ordinal
    }

    /** A pick's candidate as the choice collapses it: the same card in the same place is one option. */
    fun choice(uid: Int): String = token(uid)

    fun build(): String {
        val fx = t.fx
        val b = StringBuilder(2048)
        b.append(s.turn).append('|').append(s.phase.ordinal).append('|').append(s.active).append('|')
        s.seats.forEach { b.append(it.lp).append(',') }
        val life = fx.lives
        val cards = ArrayList<String>(s.cards.size)
        s.cards.values.forEach { c ->
            val u = c.uid
            val sb = StringBuilder(token(u))
            if (c.counters.isNotEmpty()) sb.append("c").append(c.counters.entries.sortedBy { it.key }.joinToString(","))
            if (s.placeOf(u) is Place.Zone) t.level(u)?.let { sb.append("L").append(it) }
            if (u in fx.proper) sb.append("P")
            fx.summoned[u]?.let { sb.append("S").append(it.ordinal) }
            if (u in fx.sent) sb.append("G")
            if (u in fx.setCards) sb.append("Z")
            cards += sb.toString()
        }
        cards.sort()
        cards.forEach { b.append(it).append(';') }
        b.append("|n").append(fx.normals.entries.sortedBy { it.key }.joinToString(","))
        b.append("|g").append(fx.grants.map { "${it.seat}:${token(it.source)}:${it.filter}:${it.used}" }.sorted().joinToString(","))
        b.append("|u").append(
            fx.uses.mapNotNull { u ->
                val key = if (u.key.startsWith("copy:")) {
                    // A per-copy use lasts while that instance stays: once it has moved on, it no longer binds.
                    if (life[u.uid] ?: 0 != u.life) return@mapNotNull null
                    "copy:" + token(u.uid) + ":" + u.effect
                } else u.key
                "${u.seat}:$key:${u.duel}:${u.link ?: ""}"
            }.sorted().joinToString(","),
        )
        b.append("|x").append(fx.specials.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.sorted()}" })
        b.append("|r").append(fx.restrictions.map { "${it.restriction}:${it.seat}:${it.link ?: ""}" }.sorted().joinToString(","))
        b.append("|d").append(fx.deeds.map { "${it.seat}:${s.cards[it.uid]?.let { c -> t.book.canonical(c.code) }}:${it.ban}:${it.extra}:${it.link ?: ""}" }.sorted().joinToString(","))
        b.append("|l").append(fx.levels.filter { (life[it.uid] ?: 0) == it.life }.map { "${token(it.uid)}:${it.to}:${it.by}:${it.until}" }.sorted().joinToString(","))
        if (s.chain.isNotEmpty() || fx.pending.isNotEmpty()) {
            b.append("|C").append(s.chain.joinToString(",") { "${it.seat}:${it.uid?.let(::token)}:${it.negated}:${it.targets.map(::token)}" })
            b.append("|K").append(fx.links.joinToString(",") { l -> "${l.link}:${l.effect}:${l.effectNegated}:" + l.bound.entries.sortedBy { it.key }.joinToString { e -> e.key + "=" + e.value.map(::token) } })
            b.append("|R").append(s.resolved.map(::token))
            b.append("|Q").append(fx.pending.map { "${token(it.uid)}:${it.effect}:${it.mandatory}:${it.last}" }.sorted())
            b.append("|p").append(fx.priority).append(':').append(fx.passes).append(':').append(fx.resolving)
        }
        return b.toString()
    }

    /** The key: the canonical text's two 64-bit hashes, a collision as good as never. */
    fun key(): Key {
        val text = build()
        var a = -0x340d631b7bdddcdbL
        var h = 1125899906842597L
        for (ch in text) {
            a = (a xor ch.code.toLong()) * 0x100000001b3L
            h = 31 * h + ch.code
        }
        return Key(a, h xor (text.length.toLong() shl 48))
    }

    data class Key(val a: Long, val b: Long)
}

/**
 * The answers a decision is tried with (D.md §5.4: choices collapsed where they cannot matter):
 * - copies of one card in one place are one option ([TableKey.choice]);
 * - zones by symmetry, unless a rule reads them (Link arrows: [zonesMatter]);
 * - face-up Attack and face-up Defense are one (no filter reads a face-up monster's battle position); face-down apart;
 * - the order of more than three triggers: as asked and reversed.
 * A card played as inert ([inert]) is never summoned, never material and never a Tribute.
 * [truncated] says a decision had more answers than [MOST]: a "no line" is then not known.
 */
internal class Options(private val zonesMatter: Boolean) {
    var truncated = false

    fun of(key: TableKey, d: Decision, inert: (Int) -> Boolean): List<List<Int>> = when (d) {
        is Decision.Cards -> cards(key, d, inert)
        is Decision.Zone -> {
            if (zonesMatter) d.among.indices.map { listOf(it) }
            else d.among.withIndex().distinctBy { it.value.kind }.map { listOf(it.index) }
        }
        is Decision.Position -> {
            val up = d.among.indexOfFirst { it == CardPosition.FACE_UP_ATK }.takeIf { it >= 0 }
                ?: d.among.indexOfFirst { it == CardPosition.FACE_UP_DEF }.takeIf { it >= 0 }
            val down = d.among.indexOfFirst { !it.faceUp }.takeIf { it >= 0 }
            listOfNotNull(up, down).map { listOf(it) }
        }
        is Decision.Order -> {
            val n = d.triggers.size
            if (n <= 3) permutations(n) else listOf((0 until n).toList(), (0 until n).reversed().toList())
        }
        is Decision.YesNo -> listOf(listOf(1), listOf(0))
        is Decision.Option -> d.among.indices.map { listOf(it) }
        is Decision.Declare -> {
            if (d.among.size > MOST) truncated = true
            d.among.indices.take(MOST).map { listOf(it) }
        }
    }

    /** Whether [answer] to [d] uses a card played as inert where it never may be: summoned, material, a Tribute. */
    fun inertUse(d: Decision, answer: List<Int>, inert: (Int) -> Boolean): Boolean =
        d is Decision.Cards && d.purpose in BARRED && answer.any { i -> d.among.getOrNull(i)?.let(inert) == true }

    private fun cards(key: TableKey, d: Decision.Cards, inert: (Int) -> Boolean): List<List<Int>> {
        val usable = d.among.indices.filter { d.purpose !in BARRED || !inert(d.among[it]) }
        // One option a group: the same card in the same place, its candidates in the decision's order.
        val groups = usable.groupBy { key.choice(d.among[it]) }.entries.sortedBy { it.key }.map { it.value }
        val most = minOf(d.max, usable.size)
        if (d.min > most) return emptyList()
        val out = ArrayList<List<Int>>()
        for (size in d.min..most) {
            combos(groups, size, 0, ArrayList(), out)
            if (out.size > MOST) break
        }
        if (out.size > MOST) truncated = true
        return out.take(MOST)
    }

    private fun combos(groups: List<List<Int>>, left: Int, from: Int, acc: ArrayList<Int>, out: ArrayList<List<Int>>) {
        if (out.size > MOST) return
        if (left == 0) { out += acc.toList(); return }
        for (g in from until groups.size) {
            val group = groups[g]
            // Take k of this group (its first k copies), then the rest from later groups.
            for (k in minOf(left, group.size) downTo 1) {
                val n = acc.size
                acc.addAll(group.subList(0, k))
                combos(groups, left - k, g + 1, acc, out)
                while (acc.size > n) acc.removeAt(acc.size - 1)
            }
        }
    }

    private fun permutations(n: Int): List<List<Int>> {
        if (n <= 1) return listOf((0 until n).toList())
        val out = ArrayList<List<Int>>()
        fun go(acc: List<Int>, left: List<Int>) {
            if (left.isEmpty()) { out += acc; return }
            left.forEach { x -> go(acc + x, left - x) }
        }
        go(emptyList(), (0 until n).toList())
        return out
    }

    companion object {
        /** The most answers one decision is tried with. */
        const val MOST = 48
        val BARRED = setOf(Purpose.SUMMON, Purpose.MATERIAL, Purpose.TRIBUTE)
    }
}

/** Where a set Spell or Trap goes at the end of the turn: the first free Spell & Trap Zone. */
internal fun freeSpellZones(t: FxTable, seat: Int): List<Place.Zone> = t.state.freeZones(seat, ZoneKind.SPELL)
