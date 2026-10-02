package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.model.Card

/** What the table needs to know about a card to choose a zone for it — never what its text does. */
enum class CardKind { MONSTER, EXTRA_MONSTER, SPELL, FIELD_SPELL, TRAP, TOKEN }

data class DuelCardInfo(
    val name: String,
    val kind: CardKind,
    val pendulum: Boolean = false,
    val link: Boolean = false,
) {
    companion object {
        fun of(card: Card): DuelCardInfo {
            val frame = card.frameType.lowercase()
            val kind = when {
                frame.contains("token") -> CardKind.TOKEN
                card.isExtraDeck -> CardKind.EXTRA_MONSTER
                frame == "spell" && card.race.equals("Field", ignoreCase = true) -> CardKind.FIELD_SPELL
                frame == "spell" -> CardKind.SPELL
                frame == "trap" -> CardKind.TRAP
                else -> CardKind.MONSTER
            }
            return DuelCardInfo(card.name, kind, pendulum = frame.contains("pendulum"), link = frame.contains("link"))
        }
    }
}

/** The pool's cards, as the duel asks about them. */
fun interface DuelCatalog {
    fun info(code: Int): DuelCardInfo?

    companion object {
        val NONE = DuelCatalog { null }
    }
}

/** A card's name for the log, the command line and Ai: a token's own name, a known card's, or "a card". */
fun DuelCatalog.nameOf(card: CardInst): String = card.name?.takeIf { card.token }
    ?: info(card.code)?.name ?: if (card.token) "Token" else "#${card.code}"

/**
 * The verbs a player uses on a card — one list, read by a right-click, a key, a button in the
 * inspector, the command line and Ai, so that "S" on a card and `summon ash` and Ai's `summon` all do
 * the same thing. Each verb turns into ordinary [DuelAction]s; nothing a verb does is beyond a drag.
 */
enum class DuelVerb(val label: String) {
    DEFAULT("Default"),
    ACTIVATE("Activate"),
    SUMMON("Summon"),
    SPECIAL("Special Summon"),
    SET("Set"),
    POSITION("Change position"),
    FLIP("Flip"),
    GRAVE("Send to GY"),
    BANISH("Banish"),
    BANISH_DOWN("Banish face-down"),
    HAND("To hand"),
    DECK_TOP("To top of Deck"),
    DECK_BOTTOM("To bottom of Deck"),
    EXTRA("To Extra Deck"),
    ATTACH("Attach as material"),
    DETACH("Detach"),
    REVEAL("Reveal"),
    COUNTER_UP("Add a counter"),
    COUNTER_DOWN("Remove a counter"),
    TARGET("Target"),
}

object DuelVerbs {

    /** The verb a right-click, a double-tap or Space runs on [uid], for the seat acting. */
    fun default(s: DuelState, seat: Int, uid: Int, catalog: DuelCatalog): DuelVerb {
        val card = s.cards[uid] ?: return DuelVerb.TARGET
        val kind = kindOf(card, catalog)
        return when (val p = s.placeOf(uid)) {
            is Place.Zone -> when {
                card.controller != seat -> DuelVerb.TARGET
                !card.faceUp && (p.kind == ZoneKind.MONSTER || p.kind == ZoneKind.EMZ) -> DuelVerb.SUMMON
                else -> DuelVerb.ACTIVATE
            }
            is Place.Pile -> when (p.kind) {
                PileKind.HAND -> when (kind) {
                    CardKind.SPELL, CardKind.FIELD_SPELL -> DuelVerb.ACTIVATE
                    CardKind.TRAP -> DuelVerb.SET
                    else -> DuelVerb.SUMMON
                }
                PileKind.DECK -> DuelVerb.HAND
                PileKind.EXTRA -> DuelVerb.SUMMON
                PileKind.GY, PileKind.BANISHED -> if (p.seat == seat) DuelVerb.ACTIVATE else DuelVerb.TARGET
            }
            is Place.Under -> DuelVerb.DETACH
            else -> DuelVerb.TARGET
        }
    }

    /** The verbs that make sense for [uid] where it is, for the inspector's column — the default first. */
    fun offered(s: DuelState, seat: Int, uid: Int, catalog: DuelCatalog): List<DuelVerb> {
        val card = s.cards[uid] ?: return emptyList()
        val kind = kindOf(card, catalog)
        val monster = kind == CardKind.MONSTER || kind == CardKind.EXTRA_MONSTER || kind == CardKind.TOKEN
        val list = when (val p = s.placeOf(uid)) {
            is Place.Zone -> buildList {
                add(DuelVerb.ACTIVATE)
                if (p.kind == ZoneKind.MONSTER || p.kind == ZoneKind.EMZ) {
                    if (!card.faceUp) add(DuelVerb.SUMMON)
                    add(DuelVerb.POSITION)
                }
                add(DuelVerb.FLIP)
                add(DuelVerb.TARGET)
                addAll(listOf(DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.BANISH_DOWN, DuelVerb.HAND, DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM))
                if (kind == CardKind.EXTRA_MONSTER || card.pendulum(catalog)) add(DuelVerb.EXTRA)
                add(DuelVerb.ATTACH)
                add(DuelVerb.COUNTER_UP)
                if (card.counters.isNotEmpty()) add(DuelVerb.COUNTER_DOWN)
            }
            is Place.Pile -> buildList {
                if (p.kind == PileKind.HAND) {
                    if (monster) { add(DuelVerb.SUMMON); add(DuelVerb.SPECIAL) }
                    add(DuelVerb.SET)
                    add(DuelVerb.ACTIVATE)
                } else {
                    add(DuelVerb.ACTIVATE)
                    if (monster) add(DuelVerb.SPECIAL)
                    if (!monster && p.kind != PileKind.EXTRA) add(DuelVerb.SET)
                }
                if (p.kind != PileKind.HAND) add(DuelVerb.HAND)
                if (p.kind != PileKind.GY) add(DuelVerb.GRAVE)
                if (p.kind != PileKind.BANISHED) { add(DuelVerb.BANISH); add(DuelVerb.BANISH_DOWN) }
                if (p.kind != PileKind.DECK && kind != CardKind.EXTRA_MONSTER) { add(DuelVerb.DECK_TOP); add(DuelVerb.DECK_BOTTOM) }
                if (p.kind != PileKind.EXTRA && (kind == CardKind.EXTRA_MONSTER || card.pendulum(catalog))) add(DuelVerb.EXTRA)
                add(DuelVerb.ATTACH)
                add(DuelVerb.REVEAL)
                add(DuelVerb.TARGET)
            }
            is Place.Under -> listOf(DuelVerb.DETACH, DuelVerb.HAND, DuelVerb.BANISH, DuelVerb.DECK_TOP)
            else -> emptyList()
        }
        val d = default(s, seat, uid, catalog)
        return (listOf(d) + list).distinct()
    }

    /** Whether [verb] on [uid] puts it in a zone, so a key can ask which one (numbers on the free zones). */
    fun zoneKind(s: DuelState, seat: Int, uid: Int, verb: DuelVerb, catalog: DuelCatalog): ZoneKind? {
        val card = s.cards[uid] ?: return null
        val kind = kindOf(card, catalog)
        val from = s.placeOf(uid)
        if (from is Place.Zone) return null
        val v = if (verb == DuelVerb.DEFAULT) default(s, seat, uid, catalog) else verb
        return when (v) {
            DuelVerb.SUMMON, DuelVerb.SPECIAL -> when (kind) {
                CardKind.SPELL, CardKind.TRAP -> ZoneKind.SPELL
                CardKind.FIELD_SPELL -> ZoneKind.FIELD
                else -> ZoneKind.MONSTER
            }
            DuelVerb.SET, DuelVerb.ACTIVATE -> when (kind) {
                CardKind.SPELL, CardKind.TRAP -> ZoneKind.SPELL
                CardKind.FIELD_SPELL -> ZoneKind.FIELD
                CardKind.MONSTER -> if (v == DuelVerb.SET) ZoneKind.MONSTER else if (card.pendulum(catalog) && from is Place.Pile && from.kind == PileKind.HAND) ZoneKind.SPELL else null
                else -> if (v == DuelVerb.SET) ZoneKind.MONSTER else null
            }
            else -> null
        }
    }

    /**
     * The actions [verb] on [uid] comes to. [zone], when given, is where it goes (a key's number, the
     * zone under the pointer); otherwise the free zone nearest the middle. [host] is the card an
     * [DuelVerb.ATTACH] goes under. A problem is said in words, never thrown.
     */
    fun actions(
        s: DuelState,
        seat: Int,
        uid: Int,
        verb: DuelVerb,
        catalog: DuelCatalog,
        zone: Place.Zone? = null,
        host: Int? = null,
    ): VerbResult {
        val card = s.cards[uid] ?: return VerbResult.no("No such card")
        val from = s.placeOf(uid) ?: return VerbResult.no("That card has left the duel")
        val kind = kindOf(card, catalog)
        val v = if (verb == DuelVerb.DEFAULT) default(s, seat, uid, catalog) else verb
        val owner = card.owner
        fun move(to: Place, pos: CardPosition? = null, how: String? = null) = DuelAction.Move(uid, to, pos, how)
        fun into(k: ZoneKind, preferEmz: Boolean = false): Place.Zone? = zone?.takeIf { it.kind == k || (k == ZoneKind.MONSTER && it.kind == ZoneKind.EMZ) }
            ?: nearestFree(s, seat, k, preferEmz)

        return when (v) {
            DuelVerb.DEFAULT -> VerbResult.no("Nothing to do")
            DuelVerb.SUMMON, DuelVerb.SPECIAL -> when {
                from is Place.Zone -> if (!card.faceUp && (from.kind == ZoneKind.MONSTER || from.kind == ZoneKind.EMZ)) {
                    VerbResult(listOf(DuelAction.Position(uid, CardPosition.FACE_UP_ATK)))
                } else VerbResult.no("It is already on the field")
                kind == CardKind.SPELL || kind == CardKind.TRAP || kind == CardKind.FIELD_SPELL -> actions(s, seat, uid, DuelVerb.ACTIVATE, catalog, zone)
                else -> {
                    val z = into(ZoneKind.MONSTER, preferEmz = kind == CardKind.EXTRA_MONSTER && card.link(catalog))
                        ?: return VerbResult.no("No free Monster Zone")
                    val how = if (v == DuelVerb.SPECIAL || from !is Place.Pile || from.kind != PileKind.HAND) "special" else "normal"
                    VerbResult(listOf(move(z, CardPosition.FACE_UP_ATK, how)))
                }
            }
            DuelVerb.SET -> when {
                from is Place.Zone -> {
                    if (!card.faceUp) return VerbResult.no("It is already set")
                    VerbResult(listOf(DuelAction.Position(uid, if (from.kind == ZoneKind.MONSTER || from.kind == ZoneKind.EMZ) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_DOWN_ATK)))
                }
                kind == CardKind.SPELL || kind == CardKind.TRAP -> {
                    val z = into(ZoneKind.SPELL) ?: return VerbResult.no("No free Spell & Trap Zone")
                    VerbResult(listOf(move(z, CardPosition.FACE_DOWN_ATK, "set")))
                }
                kind == CardKind.FIELD_SPELL -> {
                    val z = into(ZoneKind.FIELD) ?: return VerbResult.no("The Field Zone is taken")
                    VerbResult(listOf(move(z, CardPosition.FACE_DOWN_ATK, "set")))
                }
                else -> {
                    val z = into(ZoneKind.MONSTER) ?: return VerbResult.no("No free Monster Zone")
                    VerbResult(listOf(move(z, CardPosition.FACE_DOWN_DEF, "set")))
                }
            }
            DuelVerb.ACTIVATE -> when {
                from is Place.Zone && !card.faceUp && from.kind != ZoneKind.MONSTER && from.kind != ZoneKind.EMZ ->
                    VerbResult(listOf(DuelAction.Position(uid, CardPosition.FACE_UP_ATK), DuelAction.ChainAdd(seat, uid)))
                from is Place.Zone -> VerbResult(listOf(DuelAction.ChainAdd(seat, uid)))
                from is Place.Pile && from.kind == PileKind.HAND && (kind == CardKind.SPELL || kind == CardKind.TRAP) -> {
                    val z = into(ZoneKind.SPELL) ?: return VerbResult.no("No free Spell & Trap Zone")
                    VerbResult(listOf(move(z, CardPosition.FACE_UP_ATK, "activate"), DuelAction.ChainAdd(seat, uid)))
                }
                from is Place.Pile && from.kind == PileKind.HAND && kind == CardKind.FIELD_SPELL -> {
                    val z = into(ZoneKind.FIELD) ?: return VerbResult.no("The Field Zone is taken")
                    VerbResult(listOf(move(z, CardPosition.FACE_UP_ATK, "activate"), DuelAction.ChainAdd(seat, uid)))
                }
                from is Place.Pile && from.kind == PileKind.HAND && card.pendulum(catalog) -> {
                    val z = zone?.takeIf { it.kind == ZoneKind.SPELL } ?: listOf(0, 4).map { Place.Zone(seat, ZoneKind.SPELL, it) }.firstOrNull { s.at(it) == null }
                        ?: return VerbResult.no("Both Pendulum Zones are taken")
                    VerbResult(listOf(move(z, CardPosition.FACE_UP_ATK, "pendulum")))
                }
                // A hand trap: to the graveyard as its cost, and onto the chain.
                from is Place.Pile && from.kind == PileKind.HAND ->
                    VerbResult(listOf(move(Place.Pile(owner, PileKind.GY), how = "activate"), DuelAction.ChainAdd(seat, uid)))
                else -> VerbResult(listOf(DuelAction.ChainAdd(seat, uid)))
            }
            DuelVerb.POSITION -> {
                if (from !is Place.Zone || (from.kind != ZoneKind.MONSTER && from.kind != ZoneKind.EMZ)) return VerbResult.no("Only a monster on the field changes position")
                if (!card.faceUp) return VerbResult.no("Flip it face-up first")
                VerbResult(listOf(DuelAction.Position(uid, if (card.defense) CardPosition.FACE_UP_ATK else CardPosition.FACE_UP_DEF)))
            }
            DuelVerb.FLIP -> when {
                from is Place.Zone && (from.kind == ZoneKind.MONSTER || from.kind == ZoneKind.EMZ) ->
                    VerbResult(listOf(DuelAction.Position(uid, if (card.faceUp) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_DEF)))
                from is Place.Zone -> VerbResult(listOf(DuelAction.Position(uid, if (card.faceUp) CardPosition.FACE_DOWN_ATK else CardPosition.FACE_UP_ATK)))
                from is Place.Pile && (from.kind == PileKind.BANISHED || from.kind == PileKind.EXTRA) ->
                    VerbResult(listOf(move(from, if (card.faceUp) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_ATK, "flip")))
                else -> VerbResult.no("It cannot be turned over there")
            }
            DuelVerb.GRAVE -> pileMove(card, from, PileKind.GY, null, if (from is Place.Under) "detach" else "send")
            DuelVerb.BANISH -> pileMove(card, from, PileKind.BANISHED, CardPosition.FACE_UP_ATK, "banish")
            DuelVerb.BANISH_DOWN -> pileMove(card, from, PileKind.BANISHED, CardPosition.FACE_DOWN_DEF, "banish")
            DuelVerb.HAND -> pileMove(card, from, PileKind.HAND, null, if (from is Place.Pile && from.kind == PileKind.DECK) "search" else "return")
            DuelVerb.DECK_TOP -> pileMove(card, from, PileKind.DECK, null, "return", Place.TOP)
            DuelVerb.DECK_BOTTOM -> pileMove(card, from, PileKind.DECK, null, "return", Place.BOTTOM)
            // A Pendulum Monster goes to the Extra Deck face-up.
            DuelVerb.EXTRA -> pileMove(card, from, PileKind.EXTRA, if (card.pendulum(catalog)) CardPosition.FACE_UP_ATK else null, "return")
            DuelVerb.ATTACH -> {
                val h = host ?: return VerbResult(emptyList(), needsHost = true)
                VerbResult(listOf(move(Place.Under(h), how = "attach")))
            }
            DuelVerb.DETACH -> if (from is Place.Under) VerbResult(listOf(move(Place.Pile(owner, PileKind.GY), how = "detach")))
                else VerbResult.no("It is not a material")
            DuelVerb.REVEAL -> VerbResult(listOf(DuelAction.Reveal(seat, listOf(uid))))
            DuelVerb.COUNTER_UP -> VerbResult(listOf(DuelAction.Counter(uid, 1)))
            DuelVerb.COUNTER_DOWN -> {
                val k = card.counters.keys.firstOrNull() ?: return VerbResult.no("It has no counters")
                VerbResult(listOf(DuelAction.Counter(uid, -1, k)))
            }
            DuelVerb.TARGET -> {
                // A second time takes the arrow away.
                val drawn = s.arrows.firstOrNull { it.seat == seat && it.from == null && uid in it.to }
                VerbResult(listOf(if (drawn == null) DuelAction.Target(seat, null, listOf(uid)) else DuelAction.Target(seat, null, drawn.to, on = false)))
            }
        }
    }

    private fun pileMove(card: CardInst, from: Place, kind: PileKind, pos: CardPosition?, how: String, at: Int? = null): VerbResult {
        if (from is Place.Pile && from.kind == kind && kind != PileKind.DECK) return VerbResult.no("It is already there")
        return VerbResult(listOf(DuelAction.Move(card.uid, Place.Pile(card.owner, kind, at), pos, how)))
    }

    /** The free zone of [kind] on [seat]'s side nearest the middle (the Extra Monster Zones first when [preferEmz]). */
    fun nearestFree(s: DuelState, seat: Int, kind: ZoneKind, preferEmz: Boolean = false): Place.Zone? {
        if (kind == ZoneKind.FIELD) return s.freeZones(seat, ZoneKind.FIELD).firstOrNull()
        if (kind == ZoneKind.EMZ) return s.freeZones(seat, ZoneKind.EMZ).firstOrNull()
        val order = listOf(2, 1, 3, 0, 4).map { Place.Zone(seat, kind, it) }.filter { s.at(it) == null }
        if (kind == ZoneKind.MONSTER && preferEmz) {
            val emz = s.freeZones(seat, ZoneKind.EMZ).firstOrNull()
            if (emz != null) return emz
        }
        return order.firstOrNull()
    }

    fun kindOf(card: CardInst, catalog: DuelCatalog): CardKind =
        if (card.token) CardKind.TOKEN else catalog.info(card.code)?.kind ?: CardKind.MONSTER

    private fun CardInst.pendulum(catalog: DuelCatalog) = catalog.info(code)?.pendulum == true
    private fun CardInst.link(catalog: DuelCatalog) = catalog.info(code)?.link == true
}

/** What a verb comes to: the actions, or why not, or that it needs a card to go under. */
data class VerbResult(
    val actions: List<DuelAction>,
    val problem: String? = null,
    val needsHost: Boolean = false,
) {
    companion object {
        fun no(why: String) = VerbResult(emptyList(), why)
    }
}
