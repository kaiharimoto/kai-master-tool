package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelFolds
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.HouseRulingBook
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.Tally
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.ViewCard
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.Line
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.duel.text.DuelWords

/**
 * The duel table in words, as one seat sees it — what Ai reads before it plays (`duel_state`). Built
 * from [DuelView], so a hidden card is never named: it is "a face-down card" with a veil (`?…`) that
 * holds while it stays where it is. A card the seat can see is written with its uid (`#17`), which
 * every command and `duel_act` op accepts in place of a name.
 *
 * **The table in full** (Phase C stage 2, `docs/phases/C.md` §4): who has priority, each card a seat sees with its
 * printed facts ([facts]: Level, Rank or Link, Attribute, Type, ATK and DEF), both GYs and banished piles whole, the Extra
 * Deck (its owner's every card, theirs face-up and counted), this turn's moves ([turnLines]), and every card's coordinate
 * from the acting seat's side — one convention with the command line ([DuelNotation]), so each prints and parses back to
 * the same card.
 *
 * Knowledge is three ways, kai's (1.0.76): **full** (every card, as a tester wants), **one seat's**
 * (what that player could know — no more), and **auto**, the seat's own view with `duel_peek` for when
 * Ai judges it needs more, every peek written in the log for both players to see.
 */
object DuelBrief {
    const val FULL = "full"
    const val SELF = "self"
    const val OPPONENT = "opponent"
    const val AUTO = "auto"
    val PERSPECTIVES = listOf(FULL, SELF, OPPONENT, AUTO)

    /** The seat a perspective reads through, for Ai acting as [seat]; null is everything. */
    fun viewer(perspective: String, seat: Int): Int? = when (perspective) {
        FULL -> null
        OPPONENT -> 1 - seat
        else -> seat
    }

    fun describe(
        s: DuelState,
        viewer: Int?,
        catalog: DuelCatalog,
        secret: Long,
        seat: Int? = viewer,
        tally: Tally? = null,
        rulings: HouseRulingBook = HouseRulingBook(),
        /** This turn's moves in words as [viewer] saw them ([turnLines]); nothing is said when empty. */
        history: List<String> = emptyList(),
    ): String {
        val v = DuelView.of(s, viewer, secret)
        // Coordinates are the acting seat's (Phase C stage 2): every one printed parses back, through DuelNotation, to the
        // card beside it — their hand in the order that seat is shown it, whatever this brief's knowledge.
        val side = seat ?: viewer ?: 0
        fun uidOf(c: ViewCard, among: List<Int>): Int? =
            if (c.ref > 0) c.ref else among.firstOrNull { DuelView.veil(secret, it, s.epoch[it] ?: 0) == c.ref }
        fun coord(c: ViewCard, among: List<Int>): String? = uidOf(c, among)?.let { DuelNotation.coordOf(s, it, side, secret) }
        val told = HashSet<Pair<Int?, String>>()
        fun name(c: ViewCard, at: String? = null): String {
            val head = at?.let { "$it " }.orEmpty()
            if (c.code == null) return "${head}a face-down card [?${-c.ref}]"
            val base = if (c.token) (c.name ?: "Token") else catalog.info(c.code)?.name ?: "#${c.code}"
            // A card of the reader's own the other player has seen (a search, a reveal): they know it (1.0.79).
            val known = if (viewer != null && c.ref > 0 && c.owner == viewer && (1 - viewer) in (s.seen[c.ref] ?: emptySet()) &&
                s.placeOf(c.ref).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) }
            ) " (they know it)" else ""
            val pos = when (c.pos) {
                CardPosition.FACE_UP_ATK -> ""
                CardPosition.FACE_UP_DEF -> ", Defense"
                CardPosition.FACE_DOWN_DEF -> ", set"
                CardPosition.FACE_DOWN_ATK -> ", set"
            }
            val extra = buildList {
                // A card's facts at its first mention only (Phase C stage 3, the red team: a long game's GYs repeated the
                // same Level, Attribute and ATK for every copy — a quarter of the brief on a full table).
                facts(c, catalog)?.let { f -> if (told.add(c.code to f)) add(f) }
                if (c.counters.isNotEmpty()) add(c.counters.entries.joinToString { (k, n) -> "$n ${k.ifBlank { "counter" }}" })
                if (c.under.isNotEmpty()) add("materials: " + c.under.joinToString { name(it) })
            }.joinToString("; ")
            return "$head#${c.ref} $base$known$pos${if (extra.isNotEmpty()) " ($extra)" else ""}"
        }
        fun list(cards: List<ViewCard>, among: List<Int>) =
            if (cards.isEmpty()) "none" else cards.joinToString(", ") { name(it, coord(it, among)) }
        fun zones(cards: List<ViewCard?>, owner: Int, kind: ZoneKind) =
            cards.mapIndexed { i, c ->
                val at = DuelNotation.slotCoord(Place.Zone(owner, kind, i), side) ?: "${kind.name.first()}${i + 1}"
                c?.let { name(it, at) } ?: "$at —"
            }.joinToString(" · ")
        return buildString {
            appendLine("Turn ${s.turn} · ${DuelWords.seatLabel(s, s.active)} to play · ${s.phase.label} Phase" + if (s.solo) " · one player's table" else "")
            appendLine("You read the table as: ${if (viewer == null) "everything (full knowledge)" else "${DuelWords.seatLabel(s, viewer)} — only what that player could see"}")
            if (seat != null) appendLine("You act as: ${DuelWords.seatLabel(s, seat)}")
            priority(s)?.let { appendLine(it) }
            // The opening roll (1.0.87): before turn 1 each seat throws two dice; the higher chooses to go first or second.
            s.opening?.takeIf { !it.decided }?.let { o ->
                val thrown = (0..1).filter { o.thrown(it) }.joinToString("; ") { "${DuelWords.seatLabel(s, it)} rolled ${o.dice[it].joinToString(" and ")} (${o.sum(it)})" }
                appendLine(
                    "Opening roll, before turn 1: " + when {
                        o.winner != null -> "$thrown — ${DuelWords.seatLabel(s, o.winner)} won and chooses: `go first` or `go second`"
                        o.tied -> "$thrown — a tie: both throw again (`roll`)"
                        else -> (if (thrown.isEmpty()) "" else "$thrown; ") + "each seat throws its two dice (`roll`), the higher sum chooses"
                    },
                )
            }
            s.proposal?.let { p -> appendLine("Asked: ${DuelWords.seatLabel(s, p.seat)} asks to ${if (p.end) "end the turn" else "go to the ${p.phase?.label} Phase"} — the turn player answers (accept / decline)") }
            if (s.chain.isNotEmpty()) {
                appendLine("Chain: " + v.chain.mapIndexed { i, l ->
                    val card = l.uid?.let { ref -> if (ref > 0) s.cards[ref]?.let { catalog.info(it.code)?.name ?: it.name } else "a face-down card" }
                    val where = l.uid?.takeIf { it > 0 }?.let { s.placeOf(it) }?.let { DuelWords.placeName(it, s) }?.let { " (now in $it)" } ?: ""
                    "${i + 1}) ${card ?: l.note.ifBlank { "an effect" }}$where by ${DuelWords.seatLabel(s, l.seat)}${if (l.negated) ", negated" else ""}"
                }.joinToString("; "))
            }
            val emz = v.emz.mapIndexed { i, c ->
                val where = if (s.solo) (if (i == 0) "left" else "right") else if (i == 0) "seat 0's left / seat 1's right" else "seat 0's right / seat 1's left"
                "e${i + 1}, $where: ${c?.let { "${name(it)} (${DuelWords.possessive(DuelWords.seatName(s, it.controller))})" } ?: "—"}"
            }
            appendLine("Extra Monster Zones (\"emz left\" / \"emz right\" are your own left and right): ${emz.joinToString(" · ")}")
            appendLine(
                "Coordinates are ${DuelWords.possessive(DuelWords.seatName(s, side))} (h1 the hand's first card, m1–m5, s1–s5, fz, gy1 the GY's top, " +
                    "ban1, ex1; theirs with o: oh2, om3, ogy1; e1/e2 the Extra Monster Zones): any op takes one in place of a name. A card's printed facts are at its first mention.",
            )
            appendLine()
            val seats = if (s.solo) listOf(0) else listOf(0, 1)
            seats.forEach { i ->
                val st = v.seats[i]
                val real = s.seats[i]
                appendLine("${DuelWords.seatLabel(s, i)} · ${st.lp} LP${if (i in s.thinking) " · thinking" else ""}")
                // The hand in the order the acting seat's coordinates count it (DuelNotation.handOrder).
                val order = DuelNotation.handOrder(s, i, side, secret)
                val hand = st.hand.sortedBy { c -> uidOf(c, real.hand)?.let(order::indexOf) ?: Int.MAX_VALUE }
                appendLine("  Hand (${st.hand.size}): ${list(hand, real.hand)}")
                appendLine("  Monster Zones: ${zones(st.monsters, i, ZoneKind.MONSTER)}")
                appendLine("  Spell & Trap Zones: ${zones(st.spells, i, ZoneKind.SPELL)}")
                appendLine("  Field Zone: ${st.field?.let { name(it, DuelNotation.slotCoord(Place.Zone(i, ZoneKind.FIELD, 0), side)) } ?: "—"}")
                appendLine("  GY (${st.gy.size}, top first): ${list(st.gy, real.gy)}")
                appendLine("  Banished (${st.banished.size}): ${list(st.banished, real.banished)}")
                val extraKnown = st.extra.filter { it.code != null }
                appendLine("  Extra Deck (${st.extra.size}): ${if (extraKnown.isEmpty()) "unseen" else list(extraKnown, real.extra) + if (extraKnown.size < st.extra.size) ", and ${st.extra.size - extraKnown.size} unseen" else ""}")
                appendLine("  Deck: ${st.deck} cards${if (st.deckKnown.isNotEmpty()) " (known: " + st.deckKnown.entries.joinToString { (k, c) -> "${k + 1} from the top ${name(c)}" } + ")" else ""}")
            }
            if (s.arrows.isNotEmpty()) appendLine("Arrows: " + v.arrows.joinToString("; ") { a -> "${DuelWords.seatName(s, a.seat)} → ${a.to.joinToString { "#$it" }}" })
            if (v.attacks.isNotEmpty()) appendLine("Attacks this turn: " + v.attacks.joinToString("; ") { a ->
                "#${a.attacker} → ${a.target?.let { "#$it" } ?: "directly"}"
            })
            tally?.words(s)?.takeIf { it.isNotEmpty() }?.let { lines ->
                appendLine()
                appendLine("This turn so far:")
                lines.forEach { appendLine("  $it") }
            }
            if (history.isNotEmpty()) {
                appendLine()
                appendLine("This turn's moves, as you saw them:")
                history.forEach { appendLine("  $it") }
            }
            // House rulings for the cards the reader can see, and those for no card.
            val seenCodes = s.cards.values.filter { DuelSight.sees(s, it.uid, viewer) }.map { it.code }.toSet()
            val agreed = rulings.about(seenCodes)
            if (agreed.isNotEmpty()) {
                appendLine()
                appendLine("House rulings (agreed at this table):")
                agreed.forEach { r -> appendLine("  ${r.card?.let { "$it: " } ?: ""}${r.text}") }
            }
        }.trimEnd()
    }

    /**
     * Who may act now, in words (Phase C stage 2: the harness research found an LLM player lost most to what the
     * interface left out): a response window's responder, the player who may chain to the newest link, or the turn
     * player. Null before turn 1 (the opening roll says it), at a finished duel and at one player's table.
     */
    fun priority(s: DuelState): String? {
        if (s.beforeTurnOne || s.conceded != null || s.solo) return null
        s.window?.let { w ->
            return "Priority: ${DuelWords.seatLabel(s, w.responder)} — a response window is open on ${DuelWords.possessive(DuelWords.seatName(s, w.opener))} move (respond, or pass)"
        }
        s.chain.lastOrNull()?.let { top ->
            return "Priority: ${DuelWords.seatLabel(s, 1 - top.seat)} may chain to link ${s.chain.size} or pass; when both pass, it resolves newest first (`resolve`)"
        }
        return "Priority: ${DuelWords.seatLabel(s, s.active)}, the turn player"
    }

    /**
     * A card's printed facts, for a card the reader sees (Phase C stage 2): Level, Rank or Link rating, Scale, Attribute,
     * Type and kind, ATK and DEF — the table's own number where it holds one (a token's), the printed one beside it where
     * they differ; a Spell's or Trap's kind. Null for a hidden card, or one the catalog cannot read.
     */
    fun facts(c: ViewCard, catalog: DuelCatalog): String? {
        val code = c.code ?: return null
        if (c.token) return if (c.atk != null || c.def != null) "Token · ATK ${c.atk ?: "?"} / DEF ${c.def ?: "?"}" else "Token"
        val info = catalog.info(code) ?: return null
        return when (info.kind) {
            CardKind.SPELL, CardKind.FIELD_SPELL -> listOfNotNull(info.sub, "Spell").joinToString(" ")
            CardKind.TRAP -> listOfNotNull(info.sub, "Trap").joinToString(" ")
            else -> {
                fun stat(word: String, now: Int?, printed: Int?): String? {
                    val n = now ?: printed ?: return null
                    return "$word $n" + if (printed != null && n != printed) " (printed $printed)" else ""
                }
                val numbers = listOfNotNull(
                    stat("ATK", c.atk, info.atk),
                    if (info.linkRating == null && !info.link) stat("DEF", c.def, info.def) else null,
                ).joinToString(" / ")
                listOfNotNull(
                    info.linkRating?.let { "Link-$it" } ?: info.level?.let { if (info.xyz) "Rank $it" else "Level $it" },
                    info.scale?.let { "Scale $it" },
                    info.attribute,
                    info.race,
                    info.typeLine?.removeSuffix(" Monster")?.replace("XYZ", "Xyz")?.takeIf { it.isNotBlank() },
                    numbers.ifEmpty { null },
                ).joinToString(" · ").ifEmpty { null }
            }
        }
    }

    /**
     * This turn's moves in words, as [viewer] saw them (Phase C stage 2: the history the research found an LLM player
     * lacked): from the turn's start — just after the last End Turn, or the deal — through [DuelHost.lines], which says a
     * hidden card as hidden: the words the network's guest is sent. The newest [cap] lines, numbered as the log is.
     */
    /**
     * What happened since Ai last read ([from]), as [viewer] reads it, for a cue: every entry but Ai's own (Phase C stage 2,
     * the lead "cues drop the person's moves on Ai's cards"). A person's move on a card of Ai's is logged as Ai's seat — the
     * page acts as a card's controller — so whose seat it was says nothing; who made it ([Provenance]) does. An entry with
     * no provenance (an older duel) is judged by its seat, as before; a turn's own draw for Ai's seat is Ai's.
     */
    fun since(game: DuelGame, from: Int, aiSeat: Int, viewer: Int?, catalog: DuelCatalog, folds: DuelFolds<*>? = null): List<Line> {
        if (from >= game.cursor) return emptyList()
        val lines = DuelHost.lines(game, from, viewer, catalog, folds)
        return lines.zip(game.entries.subList(from, game.cursor)).filterNot { (_, e) -> aisOwn(e, aiSeat) }.map { it.first }
    }

    /**
     * Whether [e] was Ai's own: made by Ai at [aiSeat], or the table's for Ai's seat; an entry with no provenance by its
     * seat. An Ai at the other seat — the other session of an Ai vs Ai match (`docs/phases/C.md` §6) — is the opponent.
     */
    fun aisOwn(e: DuelEntry, aiSeat: Int): Boolean {
        val by = e.by ?: return e.seat == aiSeat
        return (by.byAi && (by.aiSeat == null || by.aiSeat == aiSeat)) || (by.by == Provenance.TABLE && e.seat == aiSeat)
    }

    fun turnLines(game: DuelGame, viewer: Int?, catalog: DuelCatalog, folds: DuelFolds<*>? = null, cap: Int = 40): List<String> {
        val played = game.entries.subList(0, game.cursor)
        val from = played.indexOfLast { it.action == DuelAction.EndTurn }.let { if (it < 0) game.floor else it + 1 }.coerceAtLeast(game.floor)
        return DuelHost.lines(game, from, viewer, catalog, folds).map { "${it.i}. ${it.text}" }.takeLast(cap)
    }
}
