package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Given
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ShortcutAsking
import com.kaiharimoto.mastertool.core.duel.ShortcutStep
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.DeclareKind
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.Dest
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.Num
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.Purpose
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.Spot
import com.kaiharimoto.mastertool.core.duel.effects.StepKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.Slot

/**
 * The Shortcut window's arithmetic (D.md §5¾): the table's `Chooser`, drawn from the [Decision] alone. Everything a window
 * says and where it stands is worked out here — the sentence for every [Purpose] and [Dest], the count, Enter's and Esc's
 * words, the candidates grouped by place in the effect's own order, the position chips, the keys, and the band of the
 * table it stands in (never over a lit card) — so the painter (`neue/duel/ShortcutWindow.kt`) only draws, and a script
 * never says how it is shown.
 */
object ShortcutWindow {

    /** The six shapes a window's body takes (§5¾.3), and which Shortcut. */
    enum class Body { WHICH, OPTION, YES_NO, PICK, PLACE, POSITION, ORDER, DECLARE }

    /** The body for a standing question. */
    fun body(step: ShortcutStep.Asking): Body = body(step.decision, step.which)

    fun body(d: Decision, which: Boolean = false): Body = when (d) {
        is Decision.Option -> if (which) Body.WHICH else Body.OPTION
        is Decision.YesNo -> Body.YES_NO
        is Decision.Cards -> Body.PICK
        is Decision.Zone -> Body.PLACE
        is Decision.Position -> Body.POSITION
        is Decision.Order -> Body.ORDER
        is Decision.Declare -> Body.DECLARE
    }

    /** Whether the table itself is the answer (a zone, a position, targets): the window shrinks to a band (§5¾.2). */
    fun onTable(d: Decision): Boolean = d is Decision.Zone || d is Decision.Position || (d is Decision.Cards && d.purpose == Purpose.TARGET)

    // ---- the head: who, which, what --------------------------------------------------------------------------------

    /** "Gatekeeper Herald · Call · Shortcut": the line the log will write, in micro caps. */
    fun who(card: String?, label: String?): String = listOfNotNull(card, label?.takeIf { it.isNotBlank() }, "Shortcut").joinToString(" · ")

    /** The engine's verb in a card choice's words: "Special Summon", "Send to the GY", "Target". */
    fun verb(d: Decision.Cards): String = d.why.removePrefix(THEY_CHOOSE).substringBefore(" · ").trim()

    /**
     * What is chosen, in one sentence (§5¾.2, §5¾.4): "Special Summon 1 monster from your hand, Deck, GY or banishment",
     * "Target up to 2 cards they control, or in either GY or banishment", "Cost: Discard 1 card from your hand".
     */
    fun sentence(d: Decision, s: DuelState, seat: Int, catalog: DuelCatalog): String = when (d) {
        is Decision.Cards -> cardsSentence(d, s, seat, catalog)
        is Decision.Zone -> {
            val name = d.card?.let { u -> s.cards[u]?.let { c -> if (DuelSight.sees(s, u, seat) || c.owner == seat) catalog.nameOf(c) else null } }
            "Place ${name ?: "it"}" + (d.card?.let { u -> from(s.placeOf(u), seat)?.let { " · $it" } } ?: "")
        }
        is Decision.Position -> {
            val name = s.cards[d.card]?.let { c -> if (DuelSight.sees(s, d.card, seat) || c.owner == seat) catalog.nameOf(c) else null }
            "${name ?: "It"}: which position?"
        }
        is Decision.YesNo -> d.why.trim().removeSuffix("?").replaceFirstChar { it.uppercase() } + "?"
        is Decision.Option -> if (d.effect == null) "Which Shortcut?" else "Choose one"
        is Decision.Order -> "In which order do they go on the chain?"
        is Decision.Declare -> when (d.kind) {
            DeclareKind.NAME -> "Declare a card name"
            DeclareKind.TYPE -> "Declare a Type"
            DeclareKind.ATTRIBUTE -> "Declare an Attribute"
            DeclareKind.LEVEL -> "Declare a Level"
        }
    }

    private const val THEY_CHOOSE = "Your opponent chooses: "

    private fun cardsSentence(d: Decision.Cards, s: DuelState, seat: Int, catalog: DuelCatalog): String {
        val verb = verb(d)
        val n = howMany(d)
        val noun = noun(d, s, catalog)
        val plural = d.max > 1
        val what = "$n ${if (plural) plural(noun) else noun}"
        val whose = if (d.by != null && d.by != seat) THEY_CHOOSE else ""
        val where = places(d, s, seat, target = d.purpose == Purpose.TARGET)
        return whose + capital(when (d.purpose) {
            Purpose.TARGET -> "Target $what${where?.let { " $it" } ?: ""}"
            Purpose.COST -> "Cost: ${verb.replaceFirstChar { it.lowercase() }.replaceFirstChar { it.uppercase() }} $what${where?.let { " $it" } ?: ""}"
            Purpose.ADD -> "Add $what${where?.let { " $it" } ?: ""} to your hand"
            Purpose.SUMMON -> "Special Summon $what${where?.let { " $it" } ?: ""}"
            Purpose.RETURN -> "$verb: $what${where?.let { " $it" } ?: ""}" + (d.to?.let { " · to ${destWords(it.dest)}" } ?: "")
            Purpose.ATTACH, Purpose.MATERIAL, Purpose.TRIBUTE -> "$verb: $what${where?.let { " $it" } ?: ""}"
            else -> "$verb $what${where?.let { " $it" } ?: ""}"
        })
    }

    private fun capital(s: String) = s.replaceFirstChar { it.uppercase() }

    /** "1", "up to 2", "2 to 3", "every". */
    fun howMany(d: Decision.Cards): String = when {
        d.min == d.max -> "${d.min}"
        d.min <= 1 -> "up to ${d.max}"
        else -> "${d.min} to ${d.max}"
    }

    /** What the candidates are: "monster" when every one is, "Spell" or "Trap" likewise, else "card". */
    private fun noun(d: Decision.Cards, s: DuelState, catalog: DuelCatalog): String {
        val kinds = d.among.mapNotNull { s.cards[it] }.map { if (it.token) CardKind.TOKEN else catalog.info(it.code)?.kind }
        return when {
            kinds.isEmpty() || kinds.any { it == null } -> "card"
            kinds.all { it == CardKind.MONSTER || it == CardKind.EXTRA_MONSTER || it == CardKind.TOKEN } -> "monster"
            kinds.all { it == CardKind.SPELL || it == CardKind.FIELD_SPELL } -> "Spell"
            kinds.all { it == CardKind.TRAP } -> "Trap"
            else -> "card"
        }
    }

    private fun plural(noun: String) = when (noun) {
        "Spell" -> "Spells"
        "Trap" -> "Traps"
        else -> noun + "s"
    }

    /** Where a pile's card comes from, as a head says it: "from your Deck", "from their GY". */
    fun from(p: Place?, seat: Int): String? = when (p) {
        is Place.Pile -> "from ${if (p.seat == seat) "your" else "their"} ${pileWord(p.kind)}"
        is Place.Zone -> "from ${if (p.seat == seat) "your" else "their"} ${zoneWord(p.kind)}"
        is Place.Under -> "from beneath a monster"
        else -> null
    }

    private fun pileWord(k: PileKind) = when (k) {
        PileKind.DECK -> "Deck"
        PileKind.HAND -> "hand"
        PileKind.GY -> "GY"
        PileKind.BANISHED -> "banishment"
        PileKind.EXTRA -> "Extra Deck"
    }

    private fun zoneWord(k: ZoneKind) = when (k) {
        ZoneKind.MONSTER -> "Monster Zone"
        ZoneKind.SPELL -> "Spell & Trap Zone"
        ZoneKind.FIELD -> "Field Zone"
        ZoneKind.EMZ -> "Extra Monster Zone"
    }

    /** Where the step takes the cards, in words: "your hand", "the top of the Deck". */
    fun destWords(d: Dest): String = when (d) {
        Dest.HAND -> "the hand"
        Dest.DECK_TOP -> "the top of the Deck"
        Dest.DECK_BOTTOM -> "the bottom of the Deck"
        Dest.DECK_SHUFFLED -> "the Deck, shuffled"
        Dest.EXTRA -> "the Extra Deck"
        Dest.GY -> "the GY"
        Dest.BANISHED -> "banishment"
        Dest.MONSTER_ZONE -> "a Monster Zone"
        Dest.SPELL_ZONE -> "a Spell & Trap Zone"
        Dest.FIELD_ZONE -> "the Field Zone"
    }

    /**
     * The places a choice looks in, in words, in the effect's own order: "from your hand, Deck, GY or banishment",
     * "they control, or in either GY or banishment". Read off [Decision.Cards.looked], else off where the candidates are.
     */
    fun places(d: Decision.Cards, s: DuelState, seat: Int, target: Boolean = false): String? {
        val spots = d.looked.ifEmpty {
            d.from.mapNotNull { p ->
                when (p) {
                    is Place.Pile -> Spot(if (p.seat == seat) Rel.YOU else Rel.THEM, areaOf(p.kind))
                    is Place.Zone -> Spot(if (p.seat == seat) Rel.YOU else Rel.THEM, if (p.kind == ZoneKind.SPELL || p.kind == ZoneKind.FIELD) Area.SPELLS else Area.MONSTERS)
                    is Place.Under -> Spot(Rel.ANY, Area.MATERIALS)
                    else -> null
                }
            }.distinct()
        }
        if (spots.isEmpty()) return null
        val field = spots.filter { it.area == Area.MONSTERS || it.area == Area.SPELLS || it.area == Area.FIELD }
        val piles = spots.filter { it !in field && it.area != Area.MATERIALS }
        val parts = mutableListOf<String>()
        if (field.isNotEmpty()) {
            val rels = field.map { it.rel }.toSet()
            parts += when {
                Rel.ANY in rels || (Rel.YOU in rels && Rel.THEM in rels) -> "on the field"
                Rel.THEM in rels -> "they control"
                else -> "you control"
            }
        }
        if (piles.isNotEmpty()) {
            val byRel = piles.groupBy { it.rel }
            val words = byRel.map { (rel, ss) ->
                val whose = when (rel) {
                    Rel.YOU -> "your"
                    Rel.THEM -> "their"
                    Rel.ANY -> "either"
                }
                "$whose ${or(ss.map { areaWord(it.area) }.distinct())}"
            }
            val lead = if (target || field.isNotEmpty()) "in" else "from"
            parts += "$lead ${words.joinToString(" or ")}"
        }
        if (spots.any { it.area == Area.MATERIALS }) parts += "among the materials"
        return parts.joinToString(", or ")
    }

    private fun areaOf(k: PileKind) = when (k) {
        PileKind.DECK -> Area.DECK
        PileKind.HAND -> Area.HAND
        PileKind.GY -> Area.GY
        PileKind.BANISHED -> Area.BANISHED
        PileKind.EXTRA -> Area.EXTRA
    }

    private fun areaWord(a: Area) = when (a) {
        Area.HAND -> "hand"
        Area.DECK -> "Deck"
        Area.EXTRA -> "Extra Deck"
        Area.GY -> "GY"
        Area.BANISHED -> "banishment"
        Area.MONSTERS -> "Monster Zones"
        Area.SPELLS -> "Spell & Trap Zones"
        Area.FIELD -> "field"
        Area.MATERIALS -> "materials"
    }

    /** "a", "a or b", "a, b or c". */
    fun or(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " or " + words.last()
    }

    // ---- the step, the count, Enter and Esc ------------------------------------------------------------------------

    /** "STEP 2 OF 3", from the engine's "2 of 3"; null when the decision does not say. */
    fun step(d: Decision): String? = (d as? Decision.Cards)?.step?.let { "Step $it" }

    /** The crumbs of the step (§5¾.2): each part, done, now or later. */
    data class Crumb(val word: String, val state: State) {
        enum class State { DONE, NOW, LATER }
    }

    /**
     * The parts of this step: Pick → Place for a summon, Cost → Target → Resolve for an effect's own choices, Which first
     * when the card was asked which; the one standing inverted, those before ticked.
     */
    fun crumbs(step: ShortcutStep.Asking): List<Crumb> {
        val d = step.decision
        val words = mutableListOf<String>()
        val asked = step.asked.map { kindWord(it) }
        if (step.which || step.asked.firstOrNull().let { it is Decision.Option && it.effect == null }) words += "Which"
        val now = if (step.which) "Which" else kindWord(d)
        asked.forEach { if (it !in words) words += it }
        if (now !in words) words += now
        // What a summon still asks once its cards are picked.
        if (d is Decision.Cards && d.purpose == Purpose.SUMMON && "Place" !in words) words += "Place"
        val at = words.indexOf(now)
        return words.mapIndexed { i, w -> Crumb(w, if (i < at) Crumb.State.DONE else if (i == at) Crumb.State.NOW else Crumb.State.LATER) }
    }

    private fun kindWord(d: Decision): String = when (d) {
        is Decision.Cards -> when (d.stepKind) {
            StepKind.COST -> "Cost"
            StepKind.TARGET -> "Target"
            else -> if (d.purpose == Purpose.TARGET) "Target" else if (d.purpose == Purpose.COST) "Cost" else "Pick"
        }
        is Decision.Zone, is Decision.Position -> "Place"
        is Decision.YesNo -> "Use"
        is Decision.Order -> "Order"
        is Decision.Option -> if (d.effect == null) "Which" else "Choose"
        is Decision.Declare -> "Declare"
    }

    /** The count against what is asked (§5¾.2): "0 / 1", "2 / up to 2", "1 of 2" for targets. */
    fun count(d: Decision, picked: Int): String = when (d) {
        is Decision.Cards -> when {
            d.purpose == Purpose.TARGET -> "$picked of ${d.max}"
            d.min == d.max -> "$picked / ${d.max}"
            else -> "$picked / up to ${d.max}"
        }
        is Decision.Order -> "${d.triggers.size}"
        else -> ""
    }

    /** Whether [picked] cards answer [d]: the count is met. */
    fun ready(d: Decision.Cards, picked: Int): Boolean = picked in d.min..d.max

    /** Why Enter waits, as its cursor says it: "Pick 1 more". Null when it does not. */
    fun waiting(d: Decision.Cards, picked: Int): String? = when {
        picked < d.min -> "${if (d.purpose == Purpose.TARGET) "Target" else "Pick"} ${d.min - picked} more"
        picked > d.max -> "Let ${picked - d.max} go"
        else -> null
    }

    /**
     * Enter's words, the answer said exactly (§5¾.2, §5¾.4): "Summon Gatekeeper Vell", "Confirm 2 targets", "Pay: discard
     * Pawn", "Add Scout", "Place Oru in M3 · Attack". [names] are the cards picked, by name or by place.
     */
    fun enter(d: Decision, names: List<String>, zone: String? = null, position: CardPosition? = null): String = when (d) {
        is Decision.Cards -> {
            val one = names.singleOrNull()
            when (d.purpose) {
                Purpose.TARGET -> if (names.size == 1) "Confirm 1 target" else "Confirm ${names.size} targets"
                Purpose.SUMMON -> if (one != null) "Summon $one" else "Summon these ${names.size}"
                Purpose.COST -> "Pay: ${verb(d).lowercase()} ${one ?: "these ${names.size}"}"
                Purpose.ADD -> "Add ${one ?: "these ${names.size}"}"
                Purpose.SEND -> "Send ${one ?: "these ${names.size}"}"
                Purpose.DESTROY -> "Destroy ${one ?: "these ${names.size}"}"
                Purpose.BANISH -> "Banish ${one ?: "these ${names.size}"}"
                Purpose.DISCARD -> "Discard ${one ?: "these ${names.size}"}"
                Purpose.RETURN -> "Return ${one ?: "these ${names.size}"}"
                Purpose.REVEAL -> "Reveal ${one ?: "these ${names.size}"}"
                Purpose.ATTACH, Purpose.MATERIAL, Purpose.TRIBUTE -> "Use ${if (one != null) one else "these ${names.size}"}"
                Purpose.OTHER -> "Confirm"
            }
        }
        is Decision.Zone -> buildString {
            append("Place")
            names.singleOrNull()?.let { append(' ').append(it) }
            zone?.let { append(" in ").append(it) }
            (position ?: d.positions.singleOrNull())?.let { append(" · ").append(PositionGlyphs.word(it)) }
        }
        is Decision.Position -> position?.let { PositionGlyphs.word(it) } ?: "Confirm"
        is Decision.YesNo -> "Yes"
        is Decision.Option -> names.singleOrNull()?.let { "Use $it" } ?: "Choose"
        is Decision.Order -> "Put them on the chain"
        is Decision.Declare -> names.singleOrNull()?.let { "Declare $it" } ?: "Declare"
    }

    /**
     * Esc's words (§5¾.2): "Back: take Vell out of M4" while there is an answer to take back, "Cancel" on the first. [last]
     * says the answer being taken back, when it has words.
     */
    fun back(asking: ShortcutAsking, last: String? = null): String =
        if (asking.given.isEmpty()) "Cancel" else "Back" + (last?.let { ": $it" } ?: "")

    /** The words for the answer Esc takes back: "take Vell out of M4", "let Vell go", "undo Which". */
    fun lastWords(asking: ShortcutAsking, asked: List<Decision>, s: DuelState, seat: Int, catalog: DuelCatalog): String? {
        val d = asked.lastOrNull() ?: return null
        val a = (asking.given.lastOrNull() as? Given.Pick)?.answer ?: return null
        fun name(u: Int?) = u?.let { s.cards[it] }?.let { c -> if (DuelSight.sees(s, c.uid, seat) || c.owner == seat) catalog.nameOf(c) else null } ?: "it"
        return when (d) {
            is Decision.Zone -> d.among.getOrNull(a.singleOrNull() ?: -1)?.let { z -> "take ${name(d.card)} out of ${zoneLabel(z, seat)}" }
            is Decision.Position -> "${name(d.card)}'s position"
            is Decision.Cards -> if (a.size == 1) "let ${name(d.among.getOrNull(a.single()))} go" else "let these ${a.size} go"
            is Decision.Option -> d.among.getOrNull(a.singleOrNull() ?: -1)?.let { "not $it" }
            is Decision.YesNo -> if (a.singleOrNull() == 1) "not yes" else "not no"
            is Decision.Order -> "the order"
            is Decision.Declare -> "the declaration"
        }
    }

    /** A zone as the window and the log write it: M3, S2, E1, FZ; theirs with "their". */
    fun zoneLabel(z: Place.Zone, seat: Int): String {
        val base = when (z.kind) {
            ZoneKind.MONSTER -> "M${z.index + 1}"
            ZoneKind.SPELL -> "S${z.index + 1}"
            ZoneKind.EMZ -> "E${z.index + 1}"
            ZoneKind.FIELD -> "FZ"
        }
        return if (z.seat != seat && z.kind != ZoneKind.EMZ) "their $base" else base
    }

    /** The key that answers a zone (§5¾.5): 1–5, Shift 1–5, 6 and 7 for the Extra Monster Zones, 0 for the Field Zone. */
    fun zoneKey(z: Place.Zone): String = when (z.kind) {
        ZoneKind.MONSTER -> "${z.index + 1}"
        ZoneKind.SPELL -> "Shift ${z.index + 1}"
        ZoneKind.EMZ -> if (z.index == 0) "6" else "7"
        ZoneKind.FIELD -> "0"
    }

    /** The zone a zone key means, among [among]: the key's own kind and index, the Extra Monster Zone for 6 and 7. */
    fun zoneFor(among: List<Place.Zone>, kind: ZoneKind, index: Int): Int? =
        among.indexOfFirst { it.kind == kind && (kind == ZoneKind.FIELD || it.index == index) }.takeIf { it >= 0 }

    // ---- the picking strip (§5¾.4) ---------------------------------------------------------------------------------

    /**
     * One place's candidates: its [head] ("GY"), its [coord] ("gy"), and the candidates' indexes into the decision's
     * `among`, in the pile's own order. [none] says why a place looked in holds nothing legal.
     */
    data class Group(val place: Place?, val head: String, val coord: String?, val indices: List<Int>, val none: String? = null)

    /**
     * The candidates grouped by where they are (option B, §5¾.4), the groups in the order the effect names its places
     * ([Decision.Cards.looked]), then any other place a candidate is in; a place looked in that holds nothing legal is a
     * group of none, named.
     */
    fun groups(d: Decision.Cards, s: DuelState, seat: Int): List<Group> {
        fun keyOf(p: Place?): Place? = when (p) {
            is Place.Pile -> p.copy(at = null)
            is Place.Zone -> Place.Zone(if (p.kind == ZoneKind.EMZ) (s.cards[s.at(p) ?: -1]?.controller ?: p.seat) else p.seat, ZoneKind.MONSTER, 0)
                .let { if (p.kind == ZoneKind.SPELL || p.kind == ZoneKind.FIELD) Place.Zone(p.seat, ZoneKind.SPELL, 0) else it }
            is Place.Under -> Place.Under(0)
            else -> null
        }
        val from = d.among.indices.map { i -> keyOf(d.from.getOrNull(i) ?: s.placeOf(d.among[i])) }
        val order = mutableListOf<Place?>()
        d.looked.forEach { spot -> placesOf(spot, seat).forEach { if (it !in order) order += it } }
        from.forEach { if (it !in order) order += it }
        return order.mapNotNull { p ->
            val idx = d.among.indices.filter { from[it] == p }
                .sortedBy { i -> (s.placeOf(d.among[i]) as? Place.Pile)?.let { pp -> s.seats.getOrNull(pp.seat)?.pile(pp.kind)?.indexOf(d.among[i]) } ?: 0 }
            val head = headOf(p, seat)
            if (idx.isEmpty()) {
                // Only a place the effect looked in, named with the engine's words when it gave them.
                if (p !in d.looked.flatMap { placesOf(it, seat) }) null
                else Group(p, head, coordOf(p, seat), emptyList(), none = noneWhy(d, s, p))
            } else Group(p, head, coordOf(p, seat), idx)
        }
    }

    /** Why a place holds nothing to choose: what the engine refused there, else that nothing there fits. */
    private fun noneWhy(d: Decision.Cards, s: DuelState, p: Place?): String {
        val there = d.refused.keys.filter { u -> keyMatches(s.placeOf(u), p) }
        return if (there.isEmpty()) "none" else "none: ${d.refused.getValue(there.first())}"
    }

    private fun keyMatches(a: Place?, b: Place?): Boolean = when {
        a is Place.Pile && b is Place.Pile -> a.seat == b.seat && a.kind == b.kind
        a is Place.Zone && b is Place.Zone -> a.seat == b.seat && ((a.kind == ZoneKind.SPELL || a.kind == ZoneKind.FIELD) == (b.kind == ZoneKind.SPELL))
        else -> false
    }

    /** The table places a spot of the effect's names, as group keys. */
    private fun placesOf(spot: Spot, seat: Int): List<Place?> {
        val seats = when (spot.rel) {
            Rel.YOU -> listOf(seat)
            Rel.THEM -> listOf(1 - seat)
            Rel.ANY -> listOf(seat, 1 - seat)
        }
        return seats.flatMap { st ->
            when (spot.area) {
                Area.HAND -> listOf(Place.Pile(st, PileKind.HAND))
                Area.DECK -> listOf(Place.Pile(st, PileKind.DECK))
                Area.EXTRA -> listOf(Place.Pile(st, PileKind.EXTRA))
                Area.GY -> listOf(Place.Pile(st, PileKind.GY))
                Area.BANISHED -> listOf(Place.Pile(st, PileKind.BANISHED))
                Area.MONSTERS -> listOf(Place.Zone(st, ZoneKind.MONSTER, 0))
                Area.SPELLS -> listOf(Place.Zone(st, ZoneKind.SPELL, 0))
                Area.FIELD -> listOf(Place.Zone(st, ZoneKind.MONSTER, 0), Place.Zone(st, ZoneKind.SPELL, 0))
                Area.MATERIALS -> listOf(Place.Under(0))
            }
        }
    }

    private fun headOf(p: Place?, seat: Int): String = when (p) {
        is Place.Pile -> {
            val mine = p.seat == seat
            when (p.kind) {
                PileKind.HAND -> if (mine) "Hand" else "Their hand"
                PileKind.DECK -> if (mine) "Deck" else "Their Deck"
                PileKind.GY -> if (mine) "GY" else "Their GY"
                PileKind.BANISHED -> if (mine) "Banished" else "Their banished"
                PileKind.EXTRA -> if (mine) "Extra Deck" else "Their Extra Deck"
            }
        }
        is Place.Zone -> (if (p.seat == seat) "Your " else "Their ") + if (p.kind == ZoneKind.SPELL) "Spells & Traps" else "monsters"
        is Place.Under -> "Materials"
        else -> "Elsewhere"
    }

    private fun coordOf(p: Place?, seat: Int): String? = when (p) {
        is Place.Pile -> if (p.kind == PileKind.HAND) (if (p.seat == seat) "h" else "oh") else DuelNotation.slotCoord(p, seat)
        is Place.Zone -> if (p.kind == ZoneKind.SPELL) (if (p.seat == seat) "s" else "os") else (if (p.seat == seat) "m" else "om")
        else -> null
    }

    /**
     * A candidate's coordinate under its name (§5¾.4): `gy1`, `ob2`, `om1`; a card in the Deck or a face-down Extra Deck is
     * the pile's own (`dk`, `ex`), since a deck's order is no one's to see.
     */
    fun coord(s: DuelState, uid: Int, seat: Int, secret: Long = 0L): String? =
        DuelNotation.coordOf(s, uid, seat, secret) ?: (s.placeOf(uid) as? Place.Pile)?.let { DuelNotation.slotCoord(it.copy(at = null), seat) }

    // ---- position chips (§5¾.5, §5¾.9) ------------------------------------------------------------------------------

    /** A position chip: its glyph's [position], word and key, and — where the rules forbid it — why, under the word. */
    data class Chip(val position: CardPosition, val word: String, val key: String, val allowed: Boolean, val why: String? = null)

    /**
     * Attack, Defense and Set as chips for a card placed in one of [allowed] (§5¾.5): Attack first, as `Chooser.FIRST`
     * gives it; a position the rules forbid is offered dashed with its reason — a Link is never in Defense, a summon by
     * effect is face-up.
     */
    fun chips(allowed: List<CardPosition>, link: Boolean = false): List<Chip> {
        val three = listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF, CardPosition.FACE_DOWN_DEF)
        return three.map { p ->
            val ok = p in allowed
            val why = when {
                ok -> null
                link && p != CardPosition.FACE_UP_ATK -> "A Link is never in Defense"
                p == CardPosition.FACE_DOWN_DEF && allowed.any { it.faceUp } -> "This effect summons face-up"
                p == CardPosition.FACE_UP_DEF && allowed == listOf(CardPosition.FACE_UP_ATK) -> "This effect summons in Attack"
                p == CardPosition.FACE_UP_ATK && allowed.none { it == CardPosition.FACE_UP_ATK } -> "This effect summons in Defense"
                else -> "Not this time"
            }
            Chip(p, PositionGlyphs.word(p), PositionGlyphs.key(p), ok, why)
        }
    }

    private val CardPosition.faceUp: Boolean get() = this == CardPosition.FACE_UP_ATK || this == CardPosition.FACE_UP_DEF

    // ---- which Shortcut, and what each needs -----------------------------------------------------------------------

    /**
     * What an effect needs before it resolves (§5¾.3, `ShortcutOption.needs`), from the script's costs and targets: "no
     * target", "1 target · they control", "cost: banish this card".
     */
    fun needs(e: Effect): String {
        val parts = mutableListOf<String>()
        e.targets.forEach { p ->
            val n = if (p.all) "every" else if (p.upTo) "up to ${p.n}" else "${p.n}"
            val where = p.from.takeIf { it.isNotEmpty() }?.let { spotsWords(it) }
            parts += "$n target${if (p.n > 1 || p.all) "s" else ""}" + (where?.let { " · $it" } ?: "")
        }
        if (e.targets.isEmpty()) parts += "no target"
        e.cost.forEach { step -> costWords(step.op)?.let { parts += "cost: $it" } }
        return parts.joinToString(" · ")
    }

    private fun spotsWords(spots: List<Spot>): String {
        val field = spots.filter { it.area == Area.MONSTERS || it.area == Area.SPELLS || it.area == Area.FIELD }
        val rest = spots - field.toSet()
        val bits = mutableListOf<String>()
        if (field.isNotEmpty()) bits += when (field.map { it.rel }.toSet()) {
            setOf(Rel.THEM) -> "they control"
            setOf(Rel.YOU) -> "you control"
            else -> "on the field"
        }
        rest.groupBy { it.rel }.forEach { (rel, ss) ->
            val whose = when (rel) {
                Rel.YOU -> "your"
                Rel.THEM -> "their"
                Rel.ANY -> "either"
            }
            bits += "$whose ${or(ss.map { areaWord(it.area) }.distinct())}"
        }
        return bits.joinToString(", ")
    }

    private fun costWords(op: Op): String? {
        fun pick(p: Pick) = if (p.ref == Pick.SELF) "this card" else "${p.n} card${if (p.n > 1) "s" else ""}"
        return when (op) {
            is Op.Banish -> "banish ${pick(op.pick)}"
            is Op.Discard -> "discard ${pick(op.pick)}"
            is Op.Send -> "send ${pick(op.pick)} to the GY"
            is Op.Tribute -> "Tribute ${pick(op.pick)}"
            is Op.Detach -> "detach ${op.n}"
            is Op.PayLp -> (op.n as? Num.Const)?.let { "pay ${it.n} LP" } ?: "pay LP"
            else -> "a cost"
        }
    }

    // ---- the keys (§5¾.10) -----------------------------------------------------------------------------------------

    private fun k(a: DeskAction, fallback: String): String =
        DeskShortcuts.all.firstOrNull { it.action == a && it.scope == com.kaiharimoto.mastertool.core.input.DeskScope.SHORTCUT_WINDOW }
            ?.chord?.let(DeskShortcuts::kbd) ?: fallback

    /** The keys of a body, as the foot writes them, read off the window's own rows (`DeskShortcuts`). */
    fun keys(body: Body): String {
        val enter = k(DeskAction.SHORTCUT_CONFIRM, "Enter")
        val esc = "Esc"
        return when (body) {
            Body.WHICH, Body.OPTION -> "1–9 · ↑↓ · $enter · $esc"
            Body.YES_NO -> "${k(DeskAction.SHORTCUT_YES, "Y")} · ${k(DeskAction.SHORTCUT_NO, "N")} · $enter · $esc"
            Body.PICK -> "1–9 or ${k(DeskAction.SHORTCUT_TOGGLE, "Space")} · ←→ · ${k(DeskAction.SHORTCUT_NEXT_PLACE, "Tab")} · type gy1 · $enter · $esc"
            Body.PLACE -> "1–5 · Shift 1–5 · 6 7 · 0 · A D E · ←→ · $enter · $esc"
            Body.POSITION -> "A · D · E · ${k(DeskAction.SHORTCUT_TOGGLE, "Space")} · $enter · $esc"
            Body.ORDER -> "↑↓ · Alt ↑↓ move · ${k(DeskAction.SHORTCUT_TOGGLE, "Space")} · $enter · $esc"
            Body.DECLARE -> "type · ↑↓ · 1–9 · $enter · $esc"
        }
    }

    // ---- where it stands (§5¾.2) -----------------------------------------------------------------------------------

    /** Where the window stands: over the far half, over the near half, a band over a hand, or — a phone — a sheet. */
    enum class Band { FAR_HALF, NEAR_HALF, FAR_BAND, NEAR_BAND, SHEET, SHEET_HIGH }

    /** The window's place: [band], and its rectangle on the table (dp). */
    data class Placement(val band: Band, val slot: Slot)

    /**
     * Where the window stands on [l] (§5¾.2 "Where it stands"), [height] tall: never over a lit card, a lit zone, or the
     * activating card ([lit]). It takes the half of the table that has none of them; when the table itself is the answer
     * ([table]) it is a band over a hand. The field's width, so the rails, the log and the score column stay readable.
     * On a phone ([phone]) it is a sheet on the bottom edge, standing above the near hand when a hand card is lit (§5¾.11).
     */
    fun place(l: DuelLayout, lit: List<Slot>, height: Float, table: Boolean, phone: Boolean = false): Placement {
        val f = l.field
        val near = l.pile(l.bottom, PileKind.HAND)
        if (phone) {
            val sheet = Slot(0f, l.height - height, l.width, height)
            val handLit = near != null && lit.any { overlap(it, near) > 0f }
            if (!handLit || near == null) return Placement(Band.SHEET, sheet)
            val above = Slot(0f, (near.top - height).coerceAtLeast(0f), l.width, height)
            return Placement(Band.SHEET_HIGH, above)
        }
        val far = l.pile(1 - l.bottom, PileKind.HAND)?.takeIf { l.twoSided && !l.farHandFolded }
        val h = height.coerceAtMost(l.height)
        val farHalf = Slot(f.left, f.top, f.width, h)
        val nearHalf = Slot(f.left, ((near?.top ?: f.bottom) - h).coerceAtLeast(0f), f.width, h)
        val farBand = Slot(f.left, (far?.let { maxOf(0f, it.bottom - h) } ?: 0f).coerceAtLeast(0f), f.width, h)
        val nearBand = Slot(f.left, (l.height - h).coerceAtLeast(0f), f.width, h)
        val order = if (table) listOf(Band.NEAR_BAND to nearBand, Band.FAR_BAND to farBand, Band.FAR_HALF to farHalf, Band.NEAR_HALF to nearHalf)
        else listOf(Band.FAR_HALF to farHalf, Band.NEAR_HALF to nearHalf, Band.NEAR_BAND to nearBand, Band.FAR_BAND to farBand)
        val (band, slot) = order.firstOrNull { (_, s) -> lit.none { overlap(it, s) > 0f } }
            ?: order.minBy { (_, s) -> lit.sumOf { overlap(it, s).toDouble() } }
        return Placement(band, slot)
    }

    /** The area two rectangles share. */
    fun overlap(a: Slot, b: Slot): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0.01f && h > 0.01f) w * h else 0f
    }

    /**
     * Beside a card, as the verb strip stands (§5¾.2.3, "Which Shortcut stands beside the card"): right of [anchor] where
     * there is room, else left; above a card in the hand ([inHand]). Kept inside the table.
     */
    fun beside(l: DuelLayout, anchor: Slot, width: Float, height: Float, inHand: Boolean, gap: Float = 6f): Slot {
        val (x, y) = if (inHand) (anchor.centerX - width / 2f) to (anchor.top - height - gap)
        else {
            val right = anchor.right + gap
            (if (right + width <= l.width - 4f || anchor.left - gap - width < 0f) right else anchor.left - gap - width) to anchor.top
        }
        return Slot(x.coerceIn(0f, (l.width - width).coerceAtLeast(0f)), y.coerceIn(0f, (l.height - height).coerceAtLeast(0f)), width, height)
    }
}
