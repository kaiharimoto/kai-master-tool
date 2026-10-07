package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.duel.nameOf

/**
 * The log in words: "Kai Normal Summons Ash Blossom to M3", "Rival sets a card in S/T 2". Written for
 * one viewer — a card that viewer could not see before or after is "a card" — so the same entry reads
 * right in the hot-seat's log, an opponent's log over the network, a replay viewed as either seat, and
 * what Ai is told when it plays with one seat's knowledge.
 */
object DuelWords {

    /**
     * [before] and [after] are the table either side of [e]; [viewer] null knows everything. An entry a Shortcut made
     * (`DuelEntry.fx`, Phase D §5½) says so after its words: "— Example Scout · Search (Shortcut)", with "unverified" where
     * the effect was; [book] gives the effect's short name. A card the viewer cannot see is not named, nor its effect.
     */
    fun say(before: DuelState, after: DuelState, e: DuelEntry, viewer: Int?, catalog: DuelCatalog, book: ScriptBook = ScriptBook.EMPTY): String {
        val line = plain(before, after, e, viewer, catalog)
        val tag = e.fx ?: return line
        val c = before.cards[tag.uid] ?: after.cards[tag.uid]
        val seen = c != null && (DuelSight.sees(before, tag.uid, viewer) || (tag.uid in after.cards && DuelSight.sees(after, tag.uid, viewer)))
        val name = if (seen && tag.effect != FxTag.RULE) catalog.nameOf(c) else null
        val label = when {
            name == null || c == null -> null
            tag.effect == FxTag.PROC -> "its summoning procedure"
            else -> book.script(c.code)?.let { sc -> sc.effects.indexOfFirst { it.id == tag.effect }.takeIf { it >= 0 }?.let { Shortcuts.label(sc.effects[it], it) } }
                ?: "Effect ${tag.effect.removePrefix("e")}"
        }
        return "$line — ${shortcutTag(name, label, tag.verified)}"
    }

    /** A Shortcut's mark in the log: "Example Scout · Search (Shortcut)", "(Shortcut, unverified)" where it was. */
    fun shortcutTag(card: String?, label: String?, verified: Boolean): String = buildString {
        if (card != null) {
            append(card)
            if (label != null) append(" · ").append(label)
            append(' ')
        }
        append(if (verified) "(Shortcut)" else "(Shortcut, unverified)")
    }

    private fun plain(before: DuelState, after: DuelState, e: DuelEntry, viewer: Int?, catalog: DuelCatalog): String {
        val who = e.seat?.let { seatName(before, it) }
        fun card(uid: Int): String {
            val c = before.cards[uid] ?: after.cards[uid] ?: return "a card"
            val known = DuelSight.sees(before, uid, viewer) || (uid in after.cards && DuelSight.sees(after, uid, viewer))
            return if (known) catalog.nameOf(c) else if (c.token) "a token" else "a card"
        }
        fun subject(verb: String) = if (who == null) verb.replaceFirstChar { it.uppercase() } else "$who $verb"

        return when (val a = e.action) {
            is DuelAction.Move -> move(before, after, a, ::card, ::subject) { uid ->
                val c = after.cards[uid]
                if (c != null && DuelSight.sees(after, uid, viewer)) catalog.nameOf(c) else "a card"
            }
            is DuelAction.Pick -> {
                // Named as each card can be seen where it went: a discard from a hand is named, a card put on the
                // bottom of the Deck only to whoever saw it before (1.0.87).
                val picked = DuelRules.picked(before, a)
                val names = picked.joinToString(", ") { card(it) }
                val fromHand = a.from?.kind == PileKind.HAND || picked.all { before.placeOf(it).let { p -> p is Place.Pile && p.kind == PileKind.HAND } }
                val whose = a.from?.let { f -> if (f.kind == PileKind.HAND) (if (f.seat == a.seat) " from their hand" else " from ${seatName(before, f.seat)}'s hand") else " from ${pileWords(f.kind)}" }.orEmpty()
                val atRandom = if (a.among.isNotEmpty() && a.n == a.among.size) "in a random order" else "at random"
                val what = "${count(a.n, "card")} $atRandom$whose"
                subject(
                    when (a.to.kind) {
                        PileKind.GY -> if (fromHand) "discards $what: $names" else "sends $what to the GY: $names"
                        PileKind.BANISHED -> "banishes $what${if (a.pos?.faceUp == false) " face-down" else ""}: $names"
                        PileKind.DECK -> "puts $what on the ${if (a.to.at == Place.BOTTOM) "bottom" else "top"} of the Deck: $names"
                        PileKind.HAND -> "adds $what to the hand: $names"
                        PileKind.EXTRA -> "returns $what to the Extra Deck: $names"
                    },
                )
            }
            is DuelAction.Draw -> {
                val drawn = before.seats[a.seat].deck.take(a.n)
                val names = drawn.filter { DuelSight.sees(after, it, viewer) }
                val actor = seatName(before, a.seat)
                if (names.size == drawn.size && drawn.isNotEmpty()) "$actor draws ${names.joinToString(", ") { card(it) }}"
                else "$actor draws ${count(a.n, "card")}"
            }
            is DuelAction.Shuffle -> "${seatName(before, a.seat)} shuffles their ${a.pile.label.let { if (it == "Hand") "hand" else it }}"
            is DuelAction.Position -> {
                val c = before.cards[a.uid]
                val name = card(a.uid)
                when {
                    c != null && !c.faceUp && a.pos == CardPosition.FACE_UP_ATK && before.placeOf(a.uid).isMonsterZone() -> subject("Flip Summons $name")
                    c != null && c.faceUp != a.pos.faceUp -> subject(if (a.pos.faceUp) "flips $name face-up" else "sets $name face-down")
                    else -> subject("changes $name to ${if (a.pos == CardPosition.FACE_UP_DEF || a.pos == CardPosition.FACE_DOWN_DEF) "Defense" else "Attack"} Position")
                }
            }
            is DuelAction.Counter -> {
                val kind = if (a.kind.isBlank()) "counter" else "${a.kind} Counter"
                val n = after.cards[a.uid]?.counters?.get(a.kind) ?: 0
                if (a.delta > 0) subject("puts ${count(a.delta, kind)} on ${card(a.uid)} ($n)")
                else subject("removes ${count(-a.delta, kind)} from ${card(a.uid)} ($n)")
            }
            is DuelAction.Token -> {
                val stats = if (a.atk != null || a.def != null) " (ATK ${a.atk ?: "?"} / DEF ${a.def ?: "?"})" else ""
                val pos = if (a.pos == CardPosition.FACE_UP_ATK) " in Attack Position" else ""
                subject("Special Summons ${if (a.name.isBlank()) "a Token" else "a ${a.name}"}$stats to ${zoneName(a.to, before)}$pos")
            }
            is DuelAction.Lp -> {
                val name = seatName(before, a.seat)
                val was = before.seats[a.seat].lp
                val now = after.seats[a.seat].lp
                when {
                    a.set != null -> "${possessive(name)} LP become $now"
                    now < was -> "$name loses ${was - now} LP ($now)"
                    else -> "$name gains ${now - was} LP ($now)"
                }
            }
            is DuelAction.Phase -> "${a.phase.label} Phase"
            DuelAction.EndTurn -> "${seatName(before, before.active)} ends the turn · Turn ${after.turn}"
            is DuelAction.Propose -> "${seatName(before, a.seat)} asks to ${if (a.end) "end the turn" else "go to the ${a.phase?.label} Phase"}"
            is DuelAction.Decline -> "${seatName(before, a.seat)} says not yet"
            is DuelAction.Lock -> "${seatName(before, a.seat)} is locked: ${a.text} (${untilWords(a.until)})"
            is DuelAction.Unlock -> "Lock ${a.id} lifted" + (before.locks.firstOrNull { it.id == a.id }?.let { ": ${it.text}" } ?: "")
            is DuelAction.ChainAdd -> {
                val n = after.chain.size
                val what = a.uid?.let(::card) ?: "an effect"
                val note = if (a.note.isNotBlank()) " — ${a.note}" else ""
                val targets = if (a.targets.isNotEmpty()) ", targeting ${a.targets.joinToString(", ") { card(it) }}" else ""
                "${seatName(before, a.seat)} activates $what (Chain Link $n)$targets$note"
            }
            DuelAction.ChainResolve -> {
                val link = before.chain.lastOrNull()
                "Chain Link ${before.chain.size} resolves${link?.uid?.let { ": ${card(it)}" } ?: ""}"
            }
            DuelAction.ChainClear -> "The chain is cleared"
            is DuelAction.Negate -> {
                val link = before.chain.getOrNull(a.link - 1)
                "${seatName(before, a.seat)} negates Chain Link ${a.link}${link?.uid?.let { ": ${card(it)}" } ?: ""}"
            }
            is DuelAction.Attack -> {
                val by = card(a.attacker)
                val whose = possessive(seatName(before, a.seat))
                if (a.target == null) "$whose $by attacks directly"
                else "$whose $by attacks ${a.target.let { t -> if (card(t) == "a card") "a face-down monster" else card(t) }}"
            }
            is DuelAction.Keep -> "${card(a.uid).replaceFirstChar { it.uppercase() }} stays on the field"
            is DuelAction.Target -> {
                val names = a.to.joinToString(", ") { card(it) }
                val with = a.from?.let { " with ${card(it)}" } ?: ""
                if (a.on) "${seatName(before, a.seat)} targets $names$with" else "${seatName(before, a.seat)} takes back the arrow to $names"
            }
            is DuelAction.Reveal -> {
                val names = a.uids.joinToString(", ") { uid ->
                    val c = before.cards[uid]
                    val knows = viewer == null || a.to == null || a.to == viewer || DuelSight.sees(before, uid, viewer)
                    if (c != null && knows) catalog.nameOf(c) else "a card"
                }
                when (a.to) {
                    null -> "${seatName(before, a.seat)} reveals $names"
                    a.seat -> "${seatName(before, a.seat)} looks at $names"
                    else -> "${seatName(before, a.seat)} shows $names to ${seatName(before, a.to)}"
                }
            }
            is DuelAction.Coin -> "${seatName(before, a.seat)} tosses a coin: ${if (a.heads) "heads" else "tails"}"
            is DuelAction.Dice -> "${seatName(before, a.seat)} rolls a die: ${a.value}"
            is DuelAction.Stow -> "${seatName(before, a.seat)} puts ${when (a.coin) { null -> "the die and the coin"; true -> "the coin"; false -> "the die" }} back"
            is DuelAction.OpeningRoll -> opening(before, after, a)
            is DuelAction.GoFirst -> "${seatName(before, a.seat)} wins the roll and goes ${if (a.first) "first" else "second"}"
            is DuelAction.Chat -> "${seatName(before, a.seat)}: ${a.text}"
            is DuelAction.Ping -> {
                val on = a.uid?.let(::card) ?: a.place?.let { placeName(it, before) }
                val name = seatName(before, a.seat)
                when (a.kind) {
                    DuelAction.PING_OK -> "$name: OK${on?.let { " ($it)" } ?: ""}"
                    DuelAction.PING_NO -> "$name: No${on?.let { " ($it)" } ?: ""}"
                    DuelAction.PING_WAIT -> "$name: Wait${on?.let { " — $it" } ?: ""}"
                    else -> "$name points at ${on ?: "the table"}"
                }
            }
            is DuelAction.Thinking -> if (a.on) "${seatName(before, a.seat)} is thinking" else "${seatName(before, a.seat)} is ready"
            is DuelAction.Answer -> "${seatName(before, a.seat)} ${if (a.respond) "responds" else "passes"}"
            // A player's note says whose it is, so it is never read as the table's own words (the red team: a seat's
            // note read "…it forfeits" exactly as the referee's would).
            is DuelAction.Note -> a.seat?.let { "${seatName(before, it)}'s note: ${a.text}" } ?: a.text
            is DuelAction.Concede -> "${seatName(before, a.seat)} concedes"
            is DuelAction.Unknown -> "Something this version cannot read"
        }
    }

    private fun move(
        before: DuelState,
        after: DuelState,
        a: DuelAction.Move,
        card: (Int) -> String,
        subject: (String) -> String,
        cardNow: (Int) -> String,
    ): String {
        val from = before.placeOf(a.uid)
        val to = after.placeOf(a.uid) ?: a.to
        // Set from the hand: named only to those who see it now, not those who saw it in the hand (1.0.81).
        val setFromHand = after.cards[a.uid]?.let { DuelRules.hidesOnSet(from, to, it.pos) } == true
        val name = if (setFromHand) cardNow(a.uid) else card(a.uid)
        val c = after.cards[a.uid] ?: before.cards[a.uid]
        val fromPile = (from as? Place.Pile)?.kind
        val fromWords = when {
            from is Place.Pile -> " from ${pileWords(from.kind)}"
            from is Place.Under -> " from ${card(from.host)}"
            else -> ""
        }
        return when (to) {
            is Place.Zone -> {
                val z = zoneName(to, before)
                val up = c?.faceUp == true
                val onTop = if (a.over) before.at(to)?.takeIf { it != a.uid }?.let { " on top of ${card(it)}" }.orEmpty() else ""
                when {
                    onTop.isNotEmpty() && from is Place.Zone -> subject("moves $name to $z$onTop")
                    onTop.isNotEmpty() -> subject("Special Summons $name$fromWords to $z$onTop")
                    from is Place.Zone -> subject("moves $name to $z")
                    a.how == "place" -> subject(if (up) "places $name face-up in $z" else "places $name face-down in $z")
                    to.kind == ZoneKind.MONSTER || to.kind == ZoneKind.EMZ -> when {
                        !up -> subject("sets $name in $z")
                        a.how == "normal" -> subject("Normal Summons $name to $z")
                        a.how == "tribute" -> subject("Tribute Summons $name to $z")
                        fromPile == PileKind.HAND && a.how == null -> subject("summons $name to $z")
                        else -> subject("Special Summons $name$fromWords to $z")
                    }
                    !up -> subject("sets $name in $z")
                    a.how == "pendulum" || (to.kind == ZoneKind.SPELL && (to.index == 0 || to.index == 4) && a.how != "activate") -> subject("places $name in $z")
                    else -> subject("activates $name in $z")
                }
            }
            is Place.Pile -> {
                when (to.kind) {
                    PileKind.GY -> when {
                        a.how == "resolve" -> "$name goes to the GY as it resolves"
                        a.how == "negate" -> "${name.replaceFirstChar { it.uppercase() }} goes to the GY, negated"
                        a.how == DuelBattle.HOW -> "${name.replaceFirstChar { it.uppercase() }} is destroyed by battle"
                        a.how == "tribute" -> subject("Tributes $name")
                        a.how == "detach" || from is Place.Under -> subject("detaches $name$fromWords")
                        fromPile == PileKind.HAND && a.how == "activate" -> subject("discards $name to activate it")
                        fromPile == PileKind.HAND -> subject("discards $name")
                        fromPile == PileKind.DECK -> subject("sends $name from the Deck to the GY")
                        else -> subject("sends $name$fromWords to the GY")
                    }
                    PileKind.HAND -> when (fromPile) {
                        PileKind.DECK -> subject("adds $name from the Deck to the hand")
                        PileKind.HAND -> subject("moves $name in the hand")
                        null -> subject("returns $name to the hand")
                        else -> subject("adds $name$fromWords to the hand")
                    }
                    PileKind.DECK -> {
                        val bottom = a.to is Place.Pile && (a.to as Place.Pile).at == Place.BOTTOM
                        subject("puts $name$fromWords on the ${if (bottom) "bottom" else "top"} of the Deck")
                    }
                    PileKind.EXTRA -> subject(if (c?.faceUp == true) "adds $name$fromWords to the Extra Deck face-up" else "returns $name$fromWords to the Extra Deck")
                    PileKind.BANISHED -> subject(if (c?.faceUp == false) "banishes $name$fromWords face-down" else "banishes $name$fromWords")
                }
            }
            is Place.Under -> subject("attaches $name$fromWords to ${card(to.host)}")
            Place.Void -> "$name leaves the field"
        }
    }

    /**
     * A seat's name. 1.0.74–1.0.78 wrote "You" and "Opponent" as names, which read as "You draws" and
     * "You's turn" (1.0.79, Ai's feedback): those read as Player 1 and Player 2.
     */
    /** "Kai rolls 4 and 6 (10)", and what the round came to once both have thrown (1.0.87). */
    fun opening(before: DuelState, after: DuelState, a: DuelAction.OpeningRoll): String {
        val who = seatName(before, a.seat)
        val dice = a.values.takeIf { it.size == 2 } ?: return "$who throws the dice"
        val head = "$who rolls ${dice[0]} and ${dice[1]} (${dice.sum()})"
        val o = after.opening ?: return head
        return when {
            o.winner != null && before.opening?.winner == null -> "$head · ${seatName(after, o.winner)} wins the roll and chooses"
            o.tied && before.opening?.tied != true -> "$head · a tie: both roll again"
            else -> head
        }
    }

    fun seatName(s: DuelState, seat: Int): String {
        val name = s.seats.getOrNull(seat)?.name?.trim().orEmpty()
        return when {
            name.isBlank() || name.equals("You", true) || name.equals("Opponent", true) -> if (seat == 0) "Player 1" else "Player 2"
            else -> name
        }
    }

    /** "Kai's", "Marcus'" — the one possessive the log writes. */
    fun possessive(name: String): String = if (name.endsWith("s") || name.endsWith("S")) "$name'" else "$name's"

    /** "Seat 0 (Kai)": a seat as Ai reads it, its number and its name together (1.0.79). */
    fun seatLabel(s: DuelState, seat: Int): String = "Seat $seat (${seatName(s, seat)})"

    /**
     * A zone's name. The Extra Monster Zones are shared and their sides depend on who looks, so with the
     * table at hand they are named from both seats (1.0.79): "the Extra Monster Zone on Kai's left (Ai's right)".
     */
    fun zoneName(z: Place.Zone, s: DuelState? = null): String = when (z.kind) {
        ZoneKind.MONSTER -> "M${z.index + 1}"
        ZoneKind.SPELL -> "S/T ${z.index + 1}"
        ZoneKind.FIELD -> "the Field Zone"
        ZoneKind.EMZ -> if (s == null || s.solo) {
            if (z.index == 0) "the left Extra Monster Zone" else "the right Extra Monster Zone"
        } else {
            val a = if (z.index == 0) "left" else "right"
            val b = if (z.index == 0) "right" else "left"
            "the Extra Monster Zone on ${possessive(seatName(s, 0))} $a (${possessive(seatName(s, 1))} $b)"
        }
    }

    fun untilWords(until: String): String = when (until) {
        com.kaiharimoto.mastertool.core.duel.Lock.UNTIL_CHAIN -> "for this chain"
        com.kaiharimoto.mastertool.core.duel.Lock.UNTIL_DUEL -> "for the duel"
        else -> "until the end of the turn"
    }

    fun placeName(p: Place, s: DuelState): String = when (p) {
        is Place.Zone -> zoneName(p, s)
        is Place.Pile -> "${possessive(seatName(s, p.seat))} ${p.kind.label}"
        is Place.Under -> "materials"
        Place.Void -> "nowhere"
    }

    /** For tests. */
    internal fun pileProse(kind: PileKind): String = pileWords(kind)

    private fun pileWords(kind: PileKind): String = PileWords.prose(kind)

    private fun Place?.isMonsterZone() = this is Place.Zone && (kind == ZoneKind.MONSTER || kind == ZoneKind.EMZ)

    private fun count(n: Int, noun: String) = if (n == 1) "a $noun" else "$n ${noun}s"
}
