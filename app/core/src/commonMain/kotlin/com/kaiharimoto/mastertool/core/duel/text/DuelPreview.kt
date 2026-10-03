package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Preview

/**
 * The line's preview in words (1.0.87): "Summon Ash Blossom & Joyous Spring from h2 to M3". Built from the parse
 * and the table *before* the move — never from a table the move was committed to, and never with chance stamped:
 * a preview of a shuffle, a coin, a die, a draw, a mill or a look says only that it will happen ("Mill 3"), so it
 * cannot tell what is coming. The move is checked by [DuelRules.applyAll] on the unstamped actions. A card is named
 * only when the seat can see it before the move, or named it itself; otherwise by where it is.
 */
object DuelPreview {

    fun of(text: String, parsed: Parsed, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long = 0L): Preview = when (parsed) {
        is Parsed.Problem -> Preview(
            ok = false, words = "", actions = emptyList(), touched = emptySet(), dest = emptyList(), problem = parsed.text,
            choices = parsed.choices, parsed = parsed,
            fixes = parsed.query?.let { q -> parsed.choices.map { c -> replaceWords(text, q, c.lowercase()) } } ?: emptyList(),
        )
        is Parsed.Actions -> actions(listOf(parsed), parsed, s, seat, catalog, secret)
        is Parsed.Many -> actions(parsed.parts, parsed, s, seat, catalog, secret)
        is Parsed.Ruling -> Preview(true, "Keep a house ruling: ${parsed.card?.let { "$it — " } ?: ""}${parsed.text}", emptyList(), emptySet(), emptyList(), null, emptyList(), parsed)
        is Parsed.Query -> Preview(true, DuelAnswer.answer(parsed, s, seat, catalog, secret), emptyList(), setOfNotNull(parsed.uid), emptyList(), null, emptyList(), parsed)
        is Parsed.Ui -> {
            val words = when (parsed.kind) {
                DuelCommand.UiKind.OPEN -> "Open ${if (parsed.seat == seat) "your" else "their"} ${pile(parsed.pile)}"
                DuelCommand.UiKind.CLOSE -> "Close the open pile"
                DuelCommand.UiKind.READ -> "Read ${parsed.uid?.let { card(s, it, seat, catalog, secret, false, withCoord = true) } ?: parsed.arg}"
                DuelCommand.UiKind.CUE -> "Ai: " + when (parsed.cue) {
                    AiCue.NO_RESPONSE -> "No response"
                    AiCue.PASS -> "Over to you"
                    AiCue.YOUR_MOVE -> "Your move"
                    AiCue.DONE -> "Done responding"
                    AiCue.DONT_WAIT -> "Don't wait"
                    AiCue.BUSY -> "Stop"
                    null -> if (parsed.arg == DuelCommand.CUE_CATCH_UP) "Catch up" else "Respond"
                }
                DuelCommand.UiKind.SWAP -> "Sit at the other seat"
                DuelCommand.UiKind.UNDO -> "Undo"
                DuelCommand.UiKind.REDO -> "Redo"
            }
            val dest = if (parsed.kind == DuelCommand.UiKind.OPEN && parsed.seat != null && parsed.pile != null) listOf(Place.Pile(parsed.seat, parsed.pile)) else emptyList()
            Preview(true, words, emptyList(), setOfNotNull(parsed.uid), dest, null, emptyList(), parsed)
        }
    }

    private fun replaceWords(text: String, q: String, with: String): String {
        val i = text.lowercase().lastIndexOf(q.lowercase())
        return if (i < 0) text else text.substring(0, i) + with + text.substring(i + q.length)
    }

    private fun actions(parts: List<Parsed.Actions>, parsed: Parsed, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long): Preview {
        var state = s
        val words = mutableListOf<String>()
        val touched = linkedSetOf<Int>()
        val dest = mutableListOf<Place>()
        var problem: String? = null
        parts.forEachIndexed { k, p ->
            if (problem != null) return@forEachIndexed
            // A mill, a draw or a look says what its parse said, nothing of the cards it reads (the red team).
            val blindLabel = p.actions.any { it is DuelAction.Draw || it is DuelAction.Shuffle || it is DuelAction.Coin || it is DuelAction.Dice || it is DuelAction.OpeningRoll } ||
                p.said.substringBefore(' ') in setOf("Mill", "Look", "Peek", "Top", "Excavate")
            val phrases = mutableListOf<String>()
            p.actions.forEachIndexed { i, a ->
                if (!blindLabel) phrase(a, i, p.actions, state, seat, catalog, secret, p.named)?.let { phrases += it }
                touch(a, state, seat, touched, dest)
                when (val o = DuelRules.apply(state, a, seat)) {
                    is Outcome.Ok -> state = o.state
                    is Outcome.Refused -> if (problem == null) problem = (if (parts.size > 1) "Move ${k + 1}: " else "") + o.reason
                }
            }
            words += if (blindLabel || phrases.isEmpty()) p.said else phrases.joinToString(", then ")
        }
        return Preview(
            ok = problem == null,
            words = words.joinToString("; "),
            actions = parts.flatMap { it.actions },
            touched = touched,
            dest = dest,
            problem = problem,
            choices = emptyList(),
            parsed = parsed,
            parts = parts.map { it.actions },
        )
    }

    /** The cards to outline and the places to mark — never a Deck's hidden card, whose place in the Deck is no one's. */
    private fun touch(a: DuelAction, s: DuelState, seat: Int, touched: MutableSet<Int>, dest: MutableList<Place>) {
        fun card(uid: Int?) {
            uid ?: return
            val p = s.placeOf(uid) ?: return
            if (p is Place.Pile && p.kind == PileKind.DECK && !DuelSight.sees(s, uid, seat)) return
            touched += uid
        }
        when (a) {
            is DuelAction.Move -> {
                card(a.uid)
                when (val t = a.to) {
                    is Place.Zone, is Place.Pile -> dest += t
                    is Place.Under -> s.placeOf(t.host)?.let { dest += it }
                    Place.Void -> Unit
                }
            }
            is DuelAction.Position -> card(a.uid)
            is DuelAction.Counter -> card(a.uid)
            is DuelAction.ChainAdd -> card(a.uid)
            is DuelAction.Attack -> { card(a.attacker); a.target?.let { card(it); s.placeOf(it)?.let { p -> dest += p } } }
            is DuelAction.Target -> { card(a.from); a.to.forEach { card(it) } }
            is DuelAction.Reveal -> a.uids.forEach { card(it) }
            is DuelAction.Token -> dest += a.to
            is DuelAction.Draw -> dest += Place.Pile(a.seat, PileKind.HAND)
            else -> Unit
        }
    }

    /** A card in words, as the seat may say it: its name when seen (or named by the seat), else where it is. */
    private fun card(s: DuelState, uid: Int, seat: Int, catalog: DuelCatalog, secret: Long, named: Boolean, withCoord: Boolean = false): String {
        val c = s.cards[uid] ?: return "a card"
        val coord = DuelNotation.coordOf(s, uid, seat, secret)
        if (named || DuelSight.sees(s, uid, seat)) return catalog.nameOf(c) + if (withCoord && coord != null) " ($coord)" else ""
        return when (val p = s.placeOf(uid)) {
            is Place.Zone -> "the face-down card in ${coord ?: "that zone"}"
            is Place.Pile -> if (p.kind == PileKind.DECK) "the top card of ${if (p.seat == seat) "your" else "their"} Deck" else "the card at ${coord ?: "that place"}"
            else -> "a hidden card"
        }
    }

    private fun pile(kind: PileKind?): String = when (kind) {
        PileKind.GY -> "GY"
        PileKind.BANISHED -> "banished cards"
        PileKind.EXTRA -> "Extra Deck"
        PileKind.DECK -> "Deck"
        PileKind.HAND -> "hand"
        null -> "pile"
    }

    private fun placeWords(p: Place, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long): String = when (p) {
        is Place.Zone -> DuelNotation.slotCoord(p, seat)?.let { c -> DuelNotation.parse(c)?.let { DuelNotation.label(it) } } ?: DuelWords.zoneName(p, s)
        is Place.Pile -> {
            val whose = if (p.seat == seat) "your" else "their"
            when {
                p.kind == PileKind.DECK && p.at == Place.BOTTOM -> "the bottom of $whose Deck"
                p.kind == PileKind.DECK -> "the top of $whose Deck"
                p.kind == PileKind.HAND && p.at != null -> "h${p.at + 1}".let { if (p.seat == seat) it else "o$it" }
                else -> "$whose ${pile(p.kind)}"
            }
        }
        is Place.Under -> "under ${card(s, p.host, seat, catalog, secret, false, withCoord = true)}"
        Place.Void -> "out of the duel"
    }

    private fun phrase(
        a: DuelAction, i: Int, all: List<DuelAction>, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long, named: Set<Int>,
    ): String? {
        fun name(uid: Int) = card(s, uid, seat, catalog, secret, uid in named)
        fun from(uid: Int): String {
            val c = DuelNotation.coordOf(s, uid, seat, secret)
            return when {
                c != null -> c
                s.placeOf(uid) is Place.Under -> "under a card"
                else -> s.placeOf(uid)?.let { placeWords(it, s, seat, catalog, secret).removePrefix("the top of ") } ?: "nowhere"
            }
        }
        return when (a) {
            is DuelAction.Move -> {
                val verb = when (a.how) {
                    "normal" -> "Summon"
                    "special" -> "Special Summon"
                    "set" -> "Set"
                    "activate" -> "Activate"
                    "pendulum", "place" -> "Place"
                    "move" -> "Move"
                    "send" -> "Send"
                    "banish" -> if (a.pos == CardPosition.FACE_DOWN_DEF) "Banish face-down" else "Banish"
                    "search" -> "Add"
                    "attach" -> "Attach"
                    "detach" -> "Detach"
                    "battle" -> "Destroy"
                    "flip" -> "Turn over"
                    "resolve" -> "Send"
                    else -> if (a.to is Place.Zone) "Move" else "Return"
                }
                "$verb ${name(a.uid)} from ${from(a.uid)} to ${placeWords(a.to, s, seat, catalog, secret)}"
            }
            is DuelAction.Draw -> if (a.seat == seat) (if (a.n == 1) "Draw" else "Draw ${a.n}") else "They draw ${a.n}"
            is DuelAction.Shuffle -> "Shuffle the ${pile(a.pile)}"
            // Chance, said as chance: which cards is decided only as it is made.
            is DuelAction.Pick -> {
                val src = when {
                    a.among.isNotEmpty() -> a.among.joinToString(", ") { name(it) }
                    a.from != null -> (if (a.from.seat == seat) "your " else "their ") + pile(a.from.kind)
                    else -> "nowhere"
                }
                val n = if (a.n == 1) "1 card" else "${a.n} cards"
                val where = when (a.to.kind) {
                    PileKind.GY -> "to the GY"
                    PileKind.BANISHED -> if (a.pos?.faceUp == false) "banished face-down" else "banished"
                    PileKind.DECK -> if (a.to.at == Place.BOTTOM) "to the bottom of the Deck" else "to the top of the Deck"
                    PileKind.HAND -> "to the hand"
                    PileKind.EXTRA -> "to the Extra Deck"
                }
                if (a.among.isNotEmpty()) "$src $where in a random order" else "$n at random from $src $where"
            }
            is DuelAction.Position -> {
                val c = s.cards[a.uid]
                val where = DuelNotation.coordOf(s, a.uid, seat, secret)
                val zone = s.placeOf(a.uid).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) }
                // A face-down card turned up names itself as it turns, when the seat may see it (its own set card).
                when {
                    c != null && !c.faceUp && a.pos == CardPosition.FACE_UP_ATK && zone -> "Flip Summon ${name(a.uid)} in $where"
                    c != null && c.faceUp != a.pos.faceUp -> if (a.pos.faceUp) "Turn ${name(a.uid)} in $where face-up" else "Set ${name(a.uid)} in $where face-down"
                    else -> "Change ${name(a.uid)} in $where to ${if (a.pos == CardPosition.FACE_UP_DEF || a.pos == CardPosition.FACE_DOWN_DEF) "Defense" else "Attack"} Position"
                }
            }
            is DuelAction.Counter -> if (a.delta > 0) "Put ${a.delta} counter${if (a.delta == 1) "" else "s"} on ${name(a.uid)} (${from(a.uid)})"
                else "Remove ${-a.delta} counter${if (a.delta == -1) "" else "s"} from ${name(a.uid)} (${from(a.uid)})"
            is DuelAction.Token -> "${a.name} to ${placeWords(a.to, s, seat, catalog, secret)}"
            is DuelAction.Lp -> {
                val whose = if (a.seat == seat) "Your" else "Their"
                val now = s.seats[a.seat].lp
                val next = (a.set ?: (now + a.delta)).coerceAtLeast(0)
                "$whose LP ${if (a.set != null) "=${a.set}" else if (a.delta >= 0) "+${a.delta}" else "−${-a.delta}"} ($now → $next)"
            }
            is DuelAction.Phase -> "${a.phase.label} Phase"
            DuelAction.EndTurn -> "End the turn"
            is DuelAction.Propose -> if (a.end) "Ask to end the turn" else "Ask for the ${a.phase?.label} Phase"
            is DuelAction.Decline -> "Not yet"
            is DuelAction.Lock -> "Lock: ${a.text}"
            is DuelAction.Unlock -> "Unlock ${a.id}"
            is DuelAction.ChainAdd -> {
                val n = s.chain.size + 1
                // "Activate Pot … to S3" already said which card: the link only adds its number.
                val said = all.take(i).any { it is DuelAction.Move && it.uid == a.uid } || all.take(i).any { it is DuelAction.Position && it.uid == a.uid }
                if (said) "Chain Link $n" else "Chain Link $n: ${a.uid?.let { "${name(it)} (${from(it)})" } ?: a.note.ifBlank { "an effect" }}"
            }
            DuelAction.ChainResolve -> "Resolve Chain Link ${s.chain.size}"
            DuelAction.ChainClear -> "Clear the chain"
            is DuelAction.Keep -> "keep ${name(a.uid)} on the field"
            is DuelAction.Attack -> a.target?.let { "Attack ${name(it)} (${from(it)}) with ${name(a.attacker)} (${from(a.attacker)})" }
                ?: "Attack directly with ${name(a.attacker)} (${from(a.attacker)})"
            is DuelAction.Target -> {
                val to = a.to.joinToString(", ") { "${name(it)} (${from(it)})" }
                if (!a.on) "Take the arrow off $to" else a.from?.let { "Target $to with ${name(it)} (${from(it)})" } ?: "Target $to"
            }
            is DuelAction.Reveal -> {
                // A search's reveal goes with its move.
                if (all.take(i).any { it is DuelAction.Move && it.how == "search" && it.uid in a.uids }) "shown to both players"
                else "Reveal ${a.uids.joinToString(", ") { "${name(it)} (${from(it)})" }}"
            }
            is DuelAction.Coin -> "Flip a coin"
            is DuelAction.Dice -> "Roll a die"
            is DuelAction.OpeningRoll -> "Throw your dice for who goes first"
            is DuelAction.GoFirst -> if (a.first) "Go first" else "Go second"
            is DuelAction.Chat -> "Say “${a.text}”"
            is DuelAction.Ping -> "Ping"
            is DuelAction.Thinking -> if (a.on) "Thinking" else "Ready"
            is DuelAction.Answer -> if (a.respond) "Respond" else "No response"
            is DuelAction.Note -> "Note: ${a.text}"
            is DuelAction.Concede -> "Concede"
            is DuelAction.Unknown -> null
        }
    }
}
