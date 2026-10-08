package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.CardMap
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.IntMemo
import com.kaiharimoto.mastertool.core.duel.IntTable
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Places
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.model.Card
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine's speed (2026-10, M.md §8) keeps every answer, by the memo-against-old rule of 1.0.92: on every table of the
 * seeded walks, each short cut — the cards kept in arrays, the place index, the book's and the facts' memos by passcode,
 * triggers looked for only where one waits, activations listed in one pass, free zones and areas gathered without
 * joining lists, the place handed on instead of asked again — gives what the code it replaced gave, written out here
 * again as it was.
 */
class FxSpeedMemoTest {
    /** A table of a walk, the events its move set off, and the table the move made. */
    private class Walked(val table: FxTable, val events: List<FxEvent>, val after: FxTable?)

    private fun walked(): List<Walked> {
        val out = ArrayList<Walked>()
        for (seed in 1L..24L) FxWalker.walk(seed, steps = 60) { st ->
            val t = st.before
            val p = st.play as? FxPlay.Done
            out += Walked(t, p?.events.orEmpty(), p?.let { FxTable(it.state, it.fx, t.book, t.facts, t.seed) })
        }
        return out
    }

    private fun tables(): List<FxTable> = walked().map { it.table }

    // ---- the cards kept in arrays ------------------------------------------------------------------------------------

    @Test
    fun anIntTableAnswersAsAMap() {
        val r = Random(5)
        repeat(60) {
            val n = r.nextInt(0, 300)
            val keys = List(n) { if (r.nextInt(4) == 0) r.nextInt() else r.nextInt(-50, 400) }
            val firsts = HashMap<Int, Int>()
            val lasts = HashMap<Int, Int>()
            val kept = IntTable.Builder<Int>(r.nextInt(0, 20)) // too small: it grows
            val over = IntTable.Builder<Int>(n)
            var grown = IntTable.empty<Int>()
            keys.forEachIndexed { i, k ->
                if (k !in firsts) firsts[k] = i
                lasts[k] = i
                kept.putIfAbsent(k, i)
                over.put(k, i)
                if (i < 40) grown = grown.plus(k, i)
            }
            val a = kept.build()
            val b = over.build()
            assertEquals(firsts.size, a.size)
            assertEquals(lasts.size, b.size)
            val grownKeys = keys.take(40)
            for (k in keys + List(60) { r.nextInt() } + listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertEquals(firsts[k], a[k], "first $k")
                assertEquals(lasts[k], b[k], "last $k")
                assertEquals(if (k in grownKeys) grownKeys.lastIndexOf(k) else null, grown[k], "plus $k")
            }
        }
        // A memo asks once a key, a miss too, and answers what it was asked.
        var asked = 0
        val memo = IntMemo { k -> asked++; if (k % 3 == 0) null else "v$k" }
        val ks = List(500) { r.nextInt(-100, 100) }
        ks.forEach { k -> assertEquals(if (k % 3 == 0) null else "v$k", memo[k]) }
        assertEquals(ks.toSet().size, asked)
    }

    /** [fast] is [old] as a map: equal either way round, hashing, written, iterated and looked up alike. */
    private fun same(old: Map<Int, CardInst>, fast: Map<Int, CardInst>) {
        assertEquals(old, fast)
        assertEquals(fast, old)
        assertEquals(old.hashCode(), fast.hashCode())
        assertEquals(old.toString(), fast.toString())
        assertEquals(old.size, fast.size)
        assertEquals(old.isEmpty(), fast.isEmpty())
        assertEquals(old.keys.toList(), fast.keys.toList())
        assertEquals(old.values.toList(), fast.values.toList())
        assertEquals(old.entries.map { it.key to it.value }, fast.entries.map { it.key to it.value })
        assertEquals(old.entries.map { it.hashCode() }, fast.entries.map { it.hashCode() })
        assertEquals(old.entries.map { it.toString() }, fast.entries.map { it.toString() })
        assertEquals(old.entries, fast.entries)
        assertEquals(fast.entries, old.entries)
        assertEquals(old.keys, fast.keys)
        for (k in old.keys + listOf(-1, 0, Int.MAX_VALUE)) {
            assertEquals(old[k], fast[k])
            assertEquals(old.containsKey(k), fast.containsKey(k))
            assertEquals(k in old.keys, k in fast.keys)
        }
        old.values.take(3).forEach { assertTrue(fast.containsValue(it)) }
    }

    private fun json(s: DuelState): String = Json.encodeToString(DuelState.serializer(), s)

    @Test
    fun theCardsKeptInArraysAreTheMapTheyStandIn() {
        same(emptyMap(), CardMap.of(emptyMap()))
        val r = Random(9)
        for (t in tables()) {
            val s = t.state
            val old = LinkedHashMap(s.cards)
            val fast = CardMap.of(old)
            same(old, fast)
            // A table holding either is the same table: its equality, its hash, its file, a card by uid.
            val asOld = s.copy(cards = old)
            val asFast = s.copy(cards = fast)
            assertEquals(asOld, asFast)
            assertEquals(asOld.hashCode(), asFast.hashCode())
            assertEquals(json(asOld), json(asFast))
            assertEquals(asOld, Json.decodeFromString(DuelState.serializer(), json(asFast)))
            for (uid in old.keys + listOf(-1, 0)) assertEquals(asOld.card(uid), asFast.card(uid))
            // Cards changed in place and cards joining last, as a LinkedHashMap puts them; the map changed from is unchanged.
            var o: Map<Int, CardInst> = old
            var f = fast
            repeat(6) {
                val join = r.nextInt(3) == 0
                val uid = if (join) (o.keys.maxOrNull() ?: 0) + 1 + r.nextInt(50) else o.keys.elementAt(r.nextInt(o.size))
                val card = if (join) CardInst(uid, FxRef.SCOUT, r.nextInt(2)) else o.getValue(uid).let { it.copy(controller = 1 - it.controller) }
                val nextOld = LinkedHashMap(o).apply { put(uid, card) }
                val nextFast = f.with(uid, card)
                same(nextOld, nextFast)
                same(o, f)
                o = nextOld
                f = nextFast
            }
        }
    }

    // ---- where a card is ------------------------------------------------------------------------------------------------

    private fun oldOnField(s: DuelState): List<Int> =
        s.emz.filterNotNull() + s.seats.flatMap { it.monsters.filterNotNull() + it.spells.filterNotNull() + listOfNotNull(it.field) }

    private fun oldFreeZones(s: DuelState, seat: Int, kind: ZoneKind): List<Place.Zone> = when (kind) {
        ZoneKind.EMZ -> s.emz.indices.filter { s.emz[it] == null }.map { Place.Zone(seat, ZoneKind.EMZ, it) }
        ZoneKind.FIELD -> if (s.seats[seat].field == null) listOf(Place.Zone(seat, ZoneKind.FIELD, 0)) else emptyList()
        else -> (0 until DuelState.ZONES).filter { s.at(Place.Zone(seat, kind, it)) == null }.map { Place.Zone(seat, kind, it) }
    }

    private fun oldArea(area: Area, seat: Int, s: DuelState): List<Int> {
        val side = s.seats.getOrNull(seat) ?: return emptyList()
        fun monsters() = side.monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat }
        fun spells() = side.spells.filterNotNull() + listOfNotNull(side.field)
        return when (area) {
            Area.HAND -> side.pile(PileKind.HAND)
            Area.DECK -> side.pile(PileKind.DECK)
            Area.EXTRA -> side.pile(PileKind.EXTRA)
            Area.GY -> side.pile(PileKind.GY)
            Area.BANISHED -> side.pile(PileKind.BANISHED)
            Area.MONSTERS -> monsters()
            Area.SPELLS -> spells()
            Area.FIELD -> monsters() + spells()
            Area.MATERIALS -> monsters().flatMap { s.cards[it]?.under.orEmpty() }
        }
    }

    private fun oldController(uid: Int, s: DuelState): Int? = when (val p = s.placeOf(uid)) {
        is Place.Zone -> if (p.kind == ZoneKind.EMZ) s.cards[uid]?.controller else p.seat
        is Place.Pile -> p.seat
        is Place.Under -> oldController(p.host, s)
        else -> null
    }

    private fun oldSees(s: DuelState, uid: Int, viewer: Int?): Boolean {
        if (s.beforeTurnOne && s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.HAND }) return false
        if (viewer == null) return true
        val card = s.cards[uid] ?: return false
        val place = s.placeOf(uid) ?: return false
        if (place is Place.Pile && place.kind == PileKind.HAND) return place.seat == viewer || DuelSight.onChain(s, uid)
        if (viewer in (s.seen[uid] ?: emptySet())) return true
        return when (val p = place) {
            is Place.Zone -> card.faceUp || card.controller == viewer
            is Place.Pile -> when (p.kind) {
                PileKind.HAND -> p.seat == viewer
                PileKind.DECK -> false
                PileKind.GY -> true
                PileKind.EXTRA, PileKind.BANISHED -> card.faceUp || p.seat == viewer
            }
            is Place.Under -> true
            else -> false
        }
    }

    @Test
    fun whereACardIsAndWhoSeesItAreAsBefore() {
        for (t in tables()) {
            val s = t.state
            val uids = s.cards.keys.sorted() + listOf(-5)
            // The index (from the third ask on) is the walk, place for place.
            val asked = s.copy()
            repeat(3) { uids.forEach { u -> assertEquals(s.copy().placeOf(u), asked.placeOf(u), "uid $u") } }
            assertEquals(oldOnField(s), s.onField())
            for (seat in 0..1) {
                ZoneKind.entries.forEach { k -> assertEquals(oldFreeZones(s, seat, k), s.freeZones(seat, k), "$seat $k") }
            }
            for (seat in -1..2) Area.entries.forEach { a -> assertEquals(oldArea(a, seat, s), FxFilters.area(a, seat, s), "$seat $a") }
            for (u in uids) {
                val at = s.placeOf(u)
                assertEquals(oldController(u, s), FxFilters.controllerAt(u, at, s), "uid $u")
                assertEquals(oldController(u, s), FxFilters.controller(u, s), "uid $u")
                for (v in listOf(null, 0, 1)) assertEquals(oldSees(s, u, v), DuelSight.sees(s, u, v, at), "uid $u, viewer $v")
                assertEquals(s.seats.indices.filter { oldSees(s, u, it) }.toSet(), DuelSight.knowers(s, u, at), "uid $u")
            }
        }
        // The places shared are the places made, out of their range too.
        for (seat in -1..2) for (i in -1..130) {
            ZoneKind.entries.forEach { k -> assertEquals(Place.Zone(seat, k, i), Places.zone(seat, k, i)) }
            PileKind.entries.forEach { k -> assertEquals(Place.Pile(seat, k, i), Places.pile(seat, k, i)) }
        }
    }

    @Test
    fun theSimpleFiltersReadTheCardAsBefore() {
        for (t in tables()) {
            for (uid in t.state.cards.keys.sorted() + listOf(-5)) for (seat in 0..1) {
                val scope = FxScope(t, seat, if (uid % 2 == 0) uid else null)
                val inst = t.inst(uid)
                assertEquals(inst != null, FxFilters.matches(Filter.Any, uid, scope))
                assertEquals(inst != null && uid == scope.self, FxFilters.matches(Filter.Self, uid, scope))
                assertEquals(inst != null && uid != scope.self, FxFilters.matches(Filter.NotSelf, uid, scope))
                assertEquals(inst?.faceUp == true, FxFilters.matches(Filter.FaceUp, uid, scope))
                assertEquals(inst != null && !inst.faceUp, FxFilters.matches(Filter.FaceDown, uid, scope))
                Rel.entries.forEach { rel ->
                    val old = inst != null && oldController(uid, t.state)?.let { it in scope.seats(rel) } == true
                    assertEquals(old, FxFilters.matches(Filter.Controller(rel), uid, scope), "$uid $rel")
                }
            }
        }
    }

    // ---- what the engine lists ------------------------------------------------------------------------------------------

    private fun oldActivations(t: FxTable, seat: Int): List<FxMove.Activate> {
        val s = t.state
        val side = s.seats[seat]
        val cards = (side.hand + oldArea(Area.FIELD, seat, s) + side.gy + side.banished).filter { t.book.has(t.inst(it)?.code ?: 0) }
        val out = ArrayList<FxMove.Activate>()
        cards.forEach { uid ->
            val script = t.script(uid) ?: return@forEach
            script.effects.forEach { e ->
                if (e.kind == Kind.CONTINUOUS) return@forEach
                if (FxChain.refusal(t, seat, uid, e.id) == null) out += FxMove.Activate(uid, e.id)
            }
        }
        return out
    }

    private fun oldPhases(t: FxTable, seat: Int): List<DuelPhase> {
        val s = t.state
        if (s.active != seat && !s.solo) return emptyList()
        return DuelPhase.entries.filter { FxRules.phaseRefusal(s, it, FxRules.open(t)) == null }
    }

    private fun oldSummons(t: FxTable, seat: Int): List<FxMove> = buildList {
        val s = t.state
        val mine = s.active == seat || s.solo
        if (mine && FxRules.main(s.phase) && FxRules.open(t)) {
            s.seats[seat].hand.forEach { uid ->
                if (!FxSummons.known(t, uid) || FxRules.normalSummonRefusal(t, seat, uid) != null) return@forEach
                val need = t.card(uid)?.level?.let { FxRules.tributes(it, t.script(uid)?.summon) } ?: return@forEach
                val monsters = oldArea(Area.MONSTERS, seat, s)
                val room = need > 0 && monsters.size >= need || need == 0 && oldFreeZones(s, seat, ZoneKind.MONSTER).isNotEmpty()
                if (!room) return@forEach
                add(FxMove.NormalSummon(uid))
                add(FxMove.NormalSummon(uid, set = true))
            }
            (s.seats[seat].hand + s.seats[seat].extra + s.seats[seat].gy + s.seats[seat].banished).forEach { uid ->
                if (!FxSummons.known(t, uid)) return@forEach
                FxProcs.open(t, seat, uid).forEach { add(FxMove.Procedure(uid, it)) }
            }
        }
        oldPhases(t, seat).forEach { add(FxMove.Phase(it)) }
    }

    @Test
    fun theActivationsSummonsAndPhasesListedAreTheOldLists() {
        var listed = 0
        for (t in tables()) for (seat in 0..1) {
            val activations = FxChain.activations(t.copy(), seat)
            assertEquals(oldActivations(t.copy(), seat), activations, "seat $seat")
            assertEquals(oldSummons(t.copy(), seat), FxSummons.moves(t.copy(), seat), "seat $seat")
            listed += activations.size
            // Every phase the table might stand in, the chain open or not.
            DuelPhase.entries.forEach { p ->
                val at = t.copy(state = t.state.copy(phase = p))
                assertEquals(oldPhases(at, seat), FxRules.phases(at, seat), "seat $seat in $p")
            }
        }
        assertTrue(listed > 0, "the walks reach activations to compare")
    }

    @Test
    fun anActsEyesBindItsOwnCardAsBefore() {
        val t = FxWalker.start(3).second
        val uids = t.state.cards.keys.sorted().take(6)
        for (uid in uids) {
            val other = uids.first { it != uid }
            val bounds = listOf(
                emptyMap(),
                mapOf(Pick.SELF to listOf(uid)),
                mapOf("a" to listOf(other), Pick.SELF to listOf(uid), "b" to listOf(uid)),
                mapOf(Pick.SELF to listOf(other)),
                mapOf(Pick.SELF to listOf(uid, uid)),
                mapOf(Pick.SELF to emptyList()),
                mapOf("a" to listOf(uid), Pick.TARGETS to listOf(other)),
            )
            for (b in bounds) {
                val act = FxAct(0, uid, t.code(uid) ?: 0, "e1", FxTag.RESOLVE, bound = b)
                val old = FxScope(t, act.seat, act.uid, act.bound + (Pick.SELF to listOf(act.uid)), act.declared)
                val now = act.scope(t)
                assertEquals(old, now)
                assertEquals(old.bound.toList(), now.bound.toList(), "in the same order")
            }
        }
    }

    // ---- what sets a trigger off ----------------------------------------------------------------------------------------

    private fun oldWhereIs(p: Place?, where: Where): Boolean = when (p) {
        is Place.Pile -> FxProcs.at(p, where, p.seat)
        is Place.Zone -> FxProcs.at(p, where, p.seat)
        else -> false
    }

    private fun oldFits(on: On, ev: FxEvent): Boolean {
        if (on.event != ev.event) return false
        if (on.cause != null && on.cause != ev.cause) return false
        val from = on.from
        if (from != null && !oldWhereIs(ev.from, from)) return false
        if (on.summon.isNotEmpty()) {
            val k = ev.summon ?: return false
            if (on.summon.none { it == k || (it == ProcKind.NORMAL && k == ProcKind.TRIBUTE) || (it == ProcKind.SPECIAL && k != ProcKind.NORMAL && k != ProcKind.TRIBUTE && k != ProcKind.FLIP) }) return false
        }
        return true
    }

    private fun oldGather(t: FxTable, events: List<FxEvent>): FxState {
        if (events.isEmpty() || t.book.triggers.isEmpty()) return t.fx
        val s = t.state
        val watchers = s.cards.values.filter { !it.token && t.book.canonical(it.code) in t.book.triggers }.map { it.uid }.sorted()
        if (watchers.isEmpty()) return t.fx
        val pending = ArrayList(t.fx.pending)
        for (ev in events) {
            for (uid in watchers) {
                val script = t.script(uid) ?: continue
                val place = s.placeOf(uid) ?: continue
                val seat = oldController(uid, s) ?: continue
                for (e in script.effects) {
                    if (e.kind != Kind.TRIGGER || t.book.unread(script.card, e.id)) continue
                    val tr = e.trigger ?: continue
                    if (!oldFits(tr.on, ev)) continue
                    if (ev.uid != 0) {
                        if (tr.self && ev.uid != uid) continue
                        val about = tr.about
                        if (!tr.self && about != null && !FxFilters.matches(about, ev.uid, FxScope(t, seat, uid))) continue
                    }
                    if (e.from.none { FxProcs.at(place, it, seat) }) continue
                    if (pending.any { it.uid == uid && it.effect == e.id }) continue
                    if (pending.size >= FxChain.MOST_TRIGGERS) return t.fx.copy(pending = pending)
                    pending += Pending(uid, t.code(uid) ?: 0, e.id, seat, mandatory = !tr.optional, event = ev, last = true)
                }
            }
        }
        return if (pending.size == t.fx.pending.size) t.fx else t.fx.copy(pending = pending)
    }

    /** Events of every kind about the table's cards, from and to where they stand, with any cause and summon. */
    private fun events(t: FxTable, r: Random): List<FxEvent> {
        val s = t.state
        val uids = s.cards.keys.sorted()
        val places = uids.mapNotNull { s.placeOf(it) }
        fun place() = if (places.isEmpty() || r.nextInt(4) == 0) null else places[r.nextInt(places.size)]
        return List(1 + r.nextInt(3)) {
            FxEvent(
                event = Event.entries[r.nextInt(Event.entries.size)],
                uid = if (uids.isEmpty() || r.nextInt(5) == 0) 0 else uids[r.nextInt(uids.size)],
                seat = r.nextInt(2),
                from = place(),
                to = place(),
                cause = if (r.nextBoolean()) null else Cause.entries[r.nextInt(Cause.entries.size)],
                summon = if (r.nextBoolean()) null else ProcKind.entries[r.nextInt(ProcKind.entries.size)],
            )
        }
    }

    @Test
    fun theTriggersGatheredAreTheOldGathersOwn() {
        val r = Random(17)
        var set = 0
        for (w in walked()) {
            val runs = ArrayList<Pair<FxTable, List<FxEvent>>>()
            w.after?.let { runs += it to w.events }
            runs += w.table to w.events
            repeat(4) { runs += w.table to events(w.table, r) }
            w.after?.let { a -> repeat(4) { runs += a to events(a, r) } }
            for ((t, evs) in runs) {
                val old = oldGather(t.copy(), evs)
                val now = FxChain.gather(t.copy(), evs)
                assertEquals(old, now, "events $evs")
                if (now.pending.size > t.fx.pending.size) set++
            }
        }
        assertTrue(set > 0, "the events set triggers off to compare")
    }

    // ---- what the book and the facts remember ---------------------------------------------------------------------------

    @Test
    fun aBooksLookUpsByPrintingAreItsCardsOwn() {
        val list = FxRef.scripts
        val canon: (Int) -> Int = FxRef.facts::canonical
        val book = ScriptBook.all(list, canon)
        val index = LinkedHashMap<Int, CardScript>()
        list.forEach { s -> if (s.vocab <= FxVocab.VERSION) canon(s.card).let { c -> if (c !in index) index[c] = s } }
        assertEquals(index.filterValues { s -> s.effects.any { it.kind == Kind.TRIGGER } }.keys, book.triggers)
        val awaited = index.values.flatMap { s -> s.effects.filter { it.kind == Kind.TRIGGER }.mapNotNull { e -> e.trigger?.let { it.on.event } } }.toSet()
        assertEquals(awaited, book.awaited)
        val codes = (list.map { it.card } + FxRef.cards.flatMap { c -> c.passcodes.map { it.value } } + listOf(FxRef.SCOUT_ALT, 0, -1, 42, Int.MAX_VALUE))
            .shuffled(Random(11))
        repeat(2) {
            for (code in codes) {
                val s = index[canon(code)]
                assertEquals(s, book.script(code), "$code")
                assertEquals(s != null, book.has(code))
                assertEquals(canon(code), book.canonical(code))
                assertEquals(s?.let { FxCodec.hash(it) } ?: "", book.hash(code))
                assertEquals(canon(code) in book.triggers, book.watches(code))
                assertEquals(false, book.unread(code, "e99"))
                s?.effects?.forEach { e ->
                    assertEquals(s.effects.filter { FxWalk.unread(it) }.any { it.id == e.id }, book.unread(code, e.id), "$code ${e.id}")
                    assertEquals(s.effects.firstOrNull { it.id == e.id }, s.effect(e.id))
                }
                s?.summon?.procs.orEmpty().forEachIndexed { i, p -> assertEquals(FxWalk.unread(p), book.unreadProc(code, i)) }
            }
        }
        list.forEach { s -> assertEquals(null, s.effect("e99")) }
    }

    @Test
    fun theFactsReadThroughTheirMemoAreTheCardsOwn() {
        val facts = FxFacts.of(FxRef.cards)
        val byCode = HashMap<Int, Card>()
        FxRef.cards.forEach { c -> c.passcodes.forEach { byCode[it.value] = c } }
        val plain = FxFacts({ code -> byCode[code]?.let { FxFacts.of(it) } }) // no memo: asked afresh every time
        val codes = (byCode.keys + listOf(0, -1, 42, Int.MAX_VALUE)).shuffled(Random(3))
        repeat(2) {
            for (code in codes) {
                val want = byCode[code]?.let { FxFacts.of(it) }
                assertEquals(want, facts[code], "$code")
                assertEquals(want, plain[code], "$code")
                facts[code]?.let { c -> repeat(2) { assertEquals(c.name.lowercase(), c.lowerName) } }
            }
        }
        // A name lowered is never part of the card: not its equality, its hash or its words.
        val scout = facts[FxRef.SCOUT]!!
        val fresh = FxFacts.of(byCode.getValue(FxRef.SCOUT))
        scout.lowerName
        assertEquals(fresh, scout)
        assertEquals(fresh.hashCode(), scout.hashCode())
        assertEquals(fresh.toString(), scout.toString())
    }
}
