package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.model.Card

/** What the table needs to know about a card to choose a zone for it — never what its text does. */
enum class CardKind { MONSTER, EXTRA_MONSTER, SPELL, FIELD_SPELL, TRAP, TOKEN }

data class DuelCardInfo(
    val name: String,
    val kind: CardKind,
    val pendulum: Boolean = false,
    val link: Boolean = false,
    /** A Spell's or Trap's kind as printed — Normal, Quick-Play, Continuous, Equip, Field, Ritual, Counter (1.0.79). */
    val sub: String? = null,
    /** A monster's printed ATK and DEF, for the battle chip's suggestion (1.0.86); null when it has none. */
    val atk: Int? = null,
    val def: Int? = null,
    /**
     * Where the card goes as it is activated from the hand, when its own text pays with it — "discard this card" or
     * "send this card from your hand to the GY" to the GY, "banish this card from your hand" banished (1.0.87, kai: "only
     * some cards discard themselves when activated"); null: it is revealed and stays in the hand while the chain stands.
     * A reading of the text for the obvious thing, never a rule: the card can always be moved by hand.
     */
    val handCost: PileKind? = null,
) {
    /** A card that goes to the GY once it resolves: a Normal, Quick-Play or Ritual Spell, a Normal or Counter Trap. */
    val leavesOnResolve: Boolean
        get() = (kind == CardKind.SPELL || kind == CardKind.TRAP) && !pendulum &&
            (sub?.lowercase() ?: "") in setOf("normal", "quick-play", "ritual", "counter")

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
            val sub = if (kind == CardKind.SPELL || kind == CardKind.TRAP || kind == CardKind.FIELD_SPELL) card.race?.takeIf { it.isNotBlank() } else null
            return DuelCardInfo(
                card.name, kind, pendulum = frame.contains("pendulum"), link = frame.contains("link"), sub = sub, atk = card.atk, def = card.def,
                handCost = if (kind == CardKind.MONSTER || kind == CardKind.EXTRA_MONSTER) handCost(card.description) else null,
            )
        }

        private val DISCARDS = Regex("\\bdiscard this card\\b|\\bsend this card from your hand to the (gy|graveyard)\\b")
        private val BANISHES = Regex("\\bbanish this card from your hand\\b")

        /** Where a monster's text sends it from the hand to pay for its own effect, if it does (see [DuelCardInfo.handCost]). */
        fun handCost(text: String): PileKind? {
            val t = text.lowercase().replace('\n', ' ')
            return when {
                DISCARDS.containsMatchIn(t) -> PileKind.GY
                BANISHES.containsMatchIn(t) -> PileKind.BANISHED
                else -> null
            }
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
    DECK_SHUFFLE("Shuffle into Deck"),
    EXTRA("To Extra Deck"),
    ATTACH("Attach as material"),
    DETACH("Detach"),
    REVEAL("Reveal"),
    COUNTER_UP("Add a counter"),
    COUNTER_DOWN("Remove a counter"),
    TARGET("Target"),
    /** Into a zone as it is, no chain link: a card placed face-up as a Continuous Spell, a Field Spell put down (1.0.79). */
    PLACE("Place"),
    /** From one zone on the field to another. */
    MOVE("Move"),
    /** In the Battle Phase a face-up Attack Position monster attacks: then a click on their monster, or their life points (1.0.86). */
    ATTACK("Attack"),
}

object DuelVerbs {

    /** The verb a right-click, a double-tap or Space runs on [uid], for the seat acting. */
    fun default(s: DuelState, seat: Int, uid: Int, catalog: DuelCatalog): DuelVerb {
        val card = s.cards[uid] ?: return DuelVerb.TARGET
        val kind = kindOf(card, catalog)
        return when (val p = s.placeOf(uid)) {
            is Place.Zone -> when {
                card.controller != seat -> DuelVerb.TARGET
                // The Battle Phase's obvious thing for the turn player's attacker is to attack (1.0.86).
                (s.solo || s.active == seat) && canAttack(s, seat, uid) -> DuelVerb.ATTACK
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
                    if (canAttack(s, seat, uid)) add(DuelVerb.ATTACK)
                    if (!card.faceUp) add(DuelVerb.SUMMON)
                    add(DuelVerb.POSITION)
                }
                add(DuelVerb.FLIP)
                add(DuelVerb.TARGET)
                addAll(listOf(DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.BANISH_DOWN, DuelVerb.HAND, DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.DECK_SHUFFLE))
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
                if (p.kind != PileKind.EXTRA || card.pendulum(catalog)) add(DuelVerb.PLACE)
                if (p.kind != PileKind.HAND) add(DuelVerb.HAND)
                if (p.kind != PileKind.GY) add(DuelVerb.GRAVE)
                if (p.kind != PileKind.BANISHED) { add(DuelVerb.BANISH); add(DuelVerb.BANISH_DOWN) }
                if (p.kind != PileKind.DECK && kind != CardKind.EXTRA_MONSTER) { add(DuelVerb.DECK_TOP); add(DuelVerb.DECK_BOTTOM); add(DuelVerb.DECK_SHUFFLE) }
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
            DuelVerb.PLACE -> if (kind == CardKind.FIELD_SPELL) ZoneKind.FIELD else ZoneKind.SPELL
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
        /** For [DuelVerb.ATTACK]: a direct attack. Otherwise [host] is the monster attacked. */
        direct: Boolean = false,
    ): VerbResult {
        val card = s.cards[uid] ?: return VerbResult.no("No such card")
        val from = s.placeOf(uid) ?: return VerbResult.no("That card has left the duel")
        val kind = kindOf(card, catalog)
        val v = if (verb == DuelVerb.DEFAULT) default(s, seat, uid, catalog) else verb
        val owner = card.owner
        fun move(to: Place, pos: CardPosition? = null, how: String? = null) = DuelAction.Move(uid, to, pos, how)
        fun into(k: ZoneKind, preferEmz: Boolean = false): Place.Zone? = zone?.takeIf { it.kind == k || (k == ZoneKind.MONSTER && it.kind == ZoneKind.EMZ) }
            ?: nearestFree(s, seat, k, preferEmz)
        // A zone named outright is where the card goes (1.0.79, Ai: "set #1045 to s2" set it in M3): a verb
        // whose kind of zone differs from the one named puts the card there as it is, never somewhere else.
        val named = zone
        if (named != null && v in setOf(DuelVerb.SUMMON, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.ACTIVATE, DuelVerb.PLACE, DuelVerb.MOVE)) {
            val fits = when (v) {
                DuelVerb.SUMMON, DuelVerb.SPECIAL -> named.kind == ZoneKind.MONSTER || named.kind == ZoneKind.EMZ ||
                    (kind == CardKind.SPELL || kind == CardKind.TRAP || kind == CardKind.FIELD_SPELL)
                DuelVerb.ACTIVATE -> from is Place.Pile && from.kind == PileKind.HAND && (kind == CardKind.SPELL || kind == CardKind.TRAP || kind == CardKind.FIELD_SPELL) &&
                    named.kind == (if (kind == CardKind.FIELD_SPELL) ZoneKind.FIELD else ZoneKind.SPELL)
                else -> false
            }
            if (!fits) {
                if (from is Place.Zone && from == named) return VerbResult.no("It is already there")
                val face = when (v) {
                    DuelVerb.SET -> if (named.kind == ZoneKind.MONSTER || named.kind == ZoneKind.EMZ) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_DOWN_ATK
                    DuelVerb.MOVE -> null
                    else -> CardPosition.FACE_UP_ATK
                }
                val how = when {
                    from is Place.Zone -> "move"
                    v == DuelVerb.SET -> "set"
                    v == DuelVerb.ACTIVATE -> "activate"
                    else -> "place"
                }
                val list = mutableListOf<DuelAction>(move(named, face, how))
                if (v == DuelVerb.ACTIVATE) list += DuelAction.ChainAdd(seat, uid)
                return VerbResult(list)
            }
        }

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
                // A monster's effect from the hand: revealed there while the chain stands (DuelSight.onChain), unless its
                // own text pays with it — then to the GY (or banished) as it goes on the chain (1.0.87).
                from is Place.Pile && from.kind == PileKind.HAND -> when (val cost = catalog.info(card.code)?.handCost) {
                    null -> VerbResult(listOf(DuelAction.ChainAdd(seat, uid)))
                    else -> VerbResult(listOf(move(Place.Pile(owner, cost), if (cost == PileKind.BANISHED) CardPosition.FACE_UP_ATK else null, "activate"), DuelAction.ChainAdd(seat, uid)))
                }
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
            DuelVerb.HAND -> {
                // A card added from the Deck is shown to the other player, as at a table (1.0.79): it is known.
                val search = from is Place.Pile && from.kind == PileKind.DECK
                val r = pileMove(card, from, PileKind.HAND, null, if (search) "search" else "return")
                if (search && r.problem == null) r.copy(actions = r.actions + DuelAction.Reveal(owner, listOf(uid))) else r
            }
            DuelVerb.DECK_TOP -> pileMove(card, from, PileKind.DECK, null, "return", Place.TOP)
            DuelVerb.DECK_BOTTOM -> pileMove(card, from, PileKind.DECK, null, "return", Place.BOTTOM)
            // Shuffled in (kai, 1.0.87): onto the Deck, then the Deck shuffled — one gesture, so its place is never seen.
            DuelVerb.DECK_SHUFFLE -> pileMove(card, from, PileKind.DECK, null, "shuffle", Place.TOP).let { r ->
                if (r.problem == null) r.copy(actions = r.actions + DuelAction.Shuffle(owner, PileKind.DECK)) else r
            }
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
            DuelVerb.PLACE -> {
                val k = when (kind) {
                    CardKind.FIELD_SPELL -> ZoneKind.FIELD
                    else -> ZoneKind.SPELL
                }
                val z = nearestFree(s, seat, k) ?: return VerbResult.no(if (k == ZoneKind.FIELD) "The Field Zone is taken" else "No free Spell & Trap Zone")
                VerbResult(listOf(move(z, CardPosition.FACE_UP_ATK, if (from is Place.Zone) "move" else "place")))
            }
            DuelVerb.MOVE -> VerbResult.no("Move it where? Name a zone: “move it to m4”")
            DuelVerb.ATTACK -> when {
                s.phase != DuelPhase.BATTLE -> VerbResult.no("Attacks are declared in the Battle Phase")
                !canAttack(s, seat, uid) -> VerbResult.no("A monster you control attacks in face-up Attack Position")
                host != null -> VerbResult(listOf(DuelAction.Attack(seat, uid, host)))
                direct -> VerbResult(listOf(DuelAction.Attack(seat, uid, null)))
                else -> VerbResult(emptyList(), needsTarget = true)
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

    /**
     * What resolving the newest chain link comes to (1.0.79): the link leaves the chain. Its card stays on
     * the field until the whole chain has resolved (1.0.83, kai); with the last link, every Normal or
     * Quick-Play Spell, Normal or Counter Trap of the chain still face-up in its zone goes to the GY
     * together. [keep] ("resolve keep": a card whose text says it stays) keeps this link's card where it is.
     * The table stays physics; this is the verb.
     */
    fun resolve(s: DuelState, catalog: DuelCatalog, keep: Boolean = false): List<DuelAction> {
        val top = s.chain.lastOrNull() ?: return emptyList()
        val out = mutableListOf<DuelAction>(DuelAction.ChainResolve)
        if (keep && top.uid != null) out += DuelAction.Keep(top.uid)
        if (s.chain.size > 1) return out
        // The chain is over: what resolved in it goes, in the order it resolved.
        val waiting = (s.resolved + listOfNotNull(top.uid.takeUnless { keep })).distinct()
        waiting.forEach { uid ->
            val card = s.cards[uid] ?: return@forEach
            val leaves = card.faceUp && !card.token &&
                s.placeOf(uid).let { it is Place.Zone && it.kind == ZoneKind.SPELL } &&
                catalog.info(card.code)?.leavesOnResolve == true
            if (leaves) out += DuelAction.Move(uid, Place.Pile(card.owner, PileKind.GY), how = "resolve")
        }
        return out
    }

    /**
     * Whether [uid] could attack now (1.0.86): the Battle Phase, and a face-up Attack Position monster in a
     * Monster Zone or an Extra Monster Zone that [seat] controls. Only what the table holds — whether it
     * has attacked already, or may, is the players' to say.
     */
    fun canAttack(s: DuelState, seat: Int, uid: Int): Boolean {
        val c = s.cards[uid] ?: return false
        val at = s.placeOf(uid)
        // Only the turn player attacks (1.0.86, the red team: an attack armed earlier fired in the other player's Battle Phase).
        return s.phase == DuelPhase.BATTLE && (s.solo || s.active == seat) && at is Place.Zone && (at.kind == ZoneKind.MONSTER || at.kind == ZoneKind.EMZ) &&
            c.controller == seat && c.faceUp && !c.defense
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
    /** An attack waiting for what it attacks: a monster, or the other player directly (1.0.86). */
    val needsTarget: Boolean = false,
) {
    companion object {
        fun no(why: String) = VerbResult(emptyList(), why)
    }
}
