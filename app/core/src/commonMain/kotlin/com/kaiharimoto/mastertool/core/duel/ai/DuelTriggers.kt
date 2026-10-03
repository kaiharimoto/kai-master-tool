package com.kaiharimoto.mastertool.core.duel.ai

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlinx.serialization.Serializable

/**
 * Ai's response triggers (1.0.85, kai: "Ai looks at the hand and sets up a trigger waiting for a range of
 * specific actions … so it can automatically catch up and possibly respond"). Ai reads its hand once and
 * leaves watches — "a Summon by them", "them leaving Main Phase 1", "an attack" — and the table checks each
 * move against them itself, so the model is only asked when something it could answer happens. A watch is
 * Ai's private plan: it is never in the log, the record or the network, and its [Watch.note] (which may
 * name a card in Ai's hand) is shown to no one but Ai.
 */
@Serializable
data class Watch(
    val id: Int = 0,
    /** [Trigger] keys: "summon", "activate", "phase_leave", … */
    val on: List<String> = emptyList(),
    /** Whose move fires it: [BY_OPPONENT], [BY_SELF] or [BY_ANY]. */
    val by: String = BY_OPPONENT,
    /** Part of a card's name; blank for any card. Only a card the watcher can see can match. */
    val card: String = "",
    /** A phase it fires in (for phase_enter / phase_leave, the phase entered or left); blank for any. */
    val phase: String = "",
    /** Summons only: fire once that seat has made at least this many Summons this turn (Nibiru at 5). */
    val atLeast: Int = 0,
    /** Ai's own reason, for itself. */
    val note: String = "",
    /** Gone once it has fired. */
    val once: Boolean = false,
    /** [UNTIL_DUEL], or [UNTIL_TURN]: gone when the turn it was set in ends. */
    val until: String = UNTIL_DUEL,
    /** The turn it was set in. */
    val turn: Int = 0,
    /** Set on the watcher's own turn: "this turn only" then lasts through the opponent's next turn. */
    val own: Boolean = false,
) {
    companion object {
        const val BY_OPPONENT = "opponent"
        const val BY_SELF = "self"
        const val BY_ANY = "any"
        const val UNTIL_DUEL = "duel"
        const val UNTIL_TURN = "turn"
    }
}

/** What a watch can wait for. */
enum class Trigger(val key: String, val words: String) {
    SUMMON("summon", "Summons"),
    NORMAL_SUMMON("normal_summon", "Normal Summons"),
    SPECIAL_SUMMON("special_summon", "Special Summons"),
    SET("set", "cards Set"),
    ACTIVATE("activate", "activations"),
    PHASE_ENTER("phase_enter", "entering a phase"),
    PHASE_LEAVE("phase_leave", "leaving a phase"),
    ATTACK("attack", "attacks"),
    DRAW("draw", "draws"),
    SEARCH("search", "cards added from the Deck"),
    SEND("send", "cards sent to the GY"),
    BANISH("banish", "cards banished"),
    ;

    companion object {
        fun of(key: String): Trigger? {
            val k = key.trim().lowercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.key == k } ?: when (k) {
                "summons", "summoned" -> SUMMON
                "normal" -> NORMAL_SUMMON
                "special" -> SPECIAL_SUMMON
                "activation", "activations", "chain", "effect" -> ACTIVATE
                "enter", "enter_phase", "phase" -> PHASE_ENTER
                "leave", "leave_phase", "end_phase", "phase_end" -> PHASE_LEAVE
                "attacks", "battle" -> ATTACK
                "add", "add_to_hand", "searches" -> SEARCH
                "gy", "send_gy", "sent", "mill" -> SEND
                "banished" -> BANISH
                "sets" -> SET
                "draws" -> DRAW
                else -> null
            }
        }
    }
}

/** One thing that happened on the table, as the watcher could see it. */
data class Happening(
    val kind: Trigger,
    /** The seat that did it. */
    val seat: Int,
    val uid: Int? = null,
    /** The card's name, only when the watcher can see it. */
    val name: String? = null,
    /** The phase it happened in; for phase_enter / phase_leave, the phase entered or left. */
    val phase: DuelPhase,
    /** The move in the log's words, as the watcher reads it. */
    val words: String,
)

/** A watch that fired, and on what. */
data class Hit(val watch: Watch, val happening: Happening)

object DuelTriggers {
    /** The phases a watch may name, forgivingly: "main 1", "m1", "bp", "end phase". */
    fun phaseOf(text: String): DuelPhase? {
        val t = text.trim().lowercase().removeSuffix("phase").replace(" ", "").replace("_", "")
        if (t.isEmpty()) return null
        return when (t) {
            "draw", "dp" -> DuelPhase.DRAW
            "standby", "sp" -> DuelPhase.STANDBY
            "main1", "m1", "mp1", "main" -> DuelPhase.MAIN1
            "battle", "bp" -> DuelPhase.BATTLE
            "main2", "m2", "mp2" -> DuelPhase.MAIN2
            "end", "ep" -> DuelPhase.END
            else -> null
        }
    }

    /**
     * A watch from Ai's words, or why not: unknown kinds are named with the list of known ones, so a
     * typo never leaves a watch that can never fire.
     */
    fun make(
        on: List<String>, by: String, card: String, phase: String, atLeast: Int, note: String, once: Boolean,
        until: String, turn: Int, id: Int, own: Boolean = false,
    ): Pair<Watch?, String?> {
        val kinds = on.map { it to Trigger.of(it) }
        val bad = kinds.filter { it.second == null }.map { it.first }
        if (on.isEmpty()) return null to "Say what to watch for: ${known()}."
        if (bad.isNotEmpty()) return null to "Unknown: ${bad.joinToString(", ")}. Watch for: ${known()}."
        val b = by.trim().lowercase().ifBlank { Watch.BY_OPPONENT }
        if (b !in setOf(Watch.BY_OPPONENT, Watch.BY_SELF, Watch.BY_ANY)) return null to "by is opponent, self or any."
        if (phase.isNotBlank() && phaseOf(phase) == null) return null to "No phase called “$phase”: draw, standby, main1, battle, main2 or end."
        val u = until.trim().lowercase().ifBlank { Watch.UNTIL_DUEL }
        if (u !in setOf(Watch.UNTIL_DUEL, Watch.UNTIL_TURN)) return null to "until is duel or turn."
        return Watch(
            id = id, on = kinds.mapNotNull { it.second?.key }.distinct(), by = b, card = card.trim(), phase = phase.trim(),
            atLeast = atLeast.coerceAtLeast(0), note = note.trim().take(300), once = once, until = u, turn = turn, own = own,
        ) to null
    }

    fun known(): String = Trigger.entries.joinToString(", ") { it.key }

    /** Watches still standing on [turn]: a turn's watch ends with its turn. */
    fun alive(watches: List<Watch>, turn: Int): List<Watch> =
        watches.filter { it.until != Watch.UNTIL_TURN || it.turn == turn || (it.own && turn == it.turn + 1) }

    /**
     * What [entries] did, entry by entry from [before], as [watcher] could see it. Stamped entries (a draw's
     * cards, a shuffle's salt) fold exactly as the log does.
     */
    fun happenings(before: DuelState, entries: List<DuelEntry>, catalog: DuelCatalog, watcher: Int, actor: Int? = null): List<Happening> {
        val out = ArrayList<Happening>()
        var s = before
        for (e in entries) {
            val after = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: continue
            // [actor]: who really made the move — the person destroying Ai's card moves it as its controller's
            // seat in the log, but it is the person's move (1.0.85). Phases stay the turn player's.
            out += of(s, after, e, catalog, watcher).map { h ->
                if (actor != null && h.kind != Trigger.PHASE_ENTER && h.kind != Trigger.PHASE_LEAVE) h.copy(seat = actor) else h
            }
            s = after
        }
        return out
    }

    /**
     * Each seat's Summons this turn, counted as the watches count them (1.0.85: the turn tally counted monster
     * Sets as Normal Summons and missed Flip Summons, so Nibiru fired a Summon early).
     */
    fun summonsThisTurn(game: com.kaiharimoto.mastertool.core.duel.DuelGame, catalog: DuelCatalog): IntArray {
        val played = game.played
        val start = played.indexOfLast { it.action is DuelAction.EndTurn } + 1
        val n = IntArray(2)
        happenings(game.stateAt(start), played.subList(start, played.size), catalog, 0)
            .filter { it.kind == Trigger.SUMMON }
            .forEach { if (it.seat in 0..1) n[it.seat]++ }
        return n
    }

    /** What one entry did. */
    fun of(before: DuelState, after: DuelState, e: DuelEntry, catalog: DuelCatalog, watcher: Int): List<Happening> {
        val words by lazy { DuelWords.say(before, after, e, watcher, catalog) }
        fun nameOf(uid: Int): String? {
            val card = after.cards[uid] ?: before.cards[uid] ?: return null
            val seen = DuelSight.sees(after, uid, watcher) || (after.cards[uid] == null && DuelSight.sees(before, uid, watcher))
            return if (seen) catalog.nameOf(card) else null
        }
        val phase = before.phase
        return when (val a = e.action) {
            is DuelAction.Move -> {
                val card = before.cards[a.uid] ?: return emptyList()
                val from = before.placeOf(a.uid) ?: return emptyList()
                val to = a.to
                val seat = e.seat ?: card.controller
                val name = nameOf(a.uid)
                fun h(k: Trigger) = Happening(k, seat, a.uid, name, phase, words)
                when {
                    to is Place.Zone && (to.kind == ZoneKind.MONSTER || to.kind == ZoneKind.EMZ) && from !is Place.Zone && from !is Place.Under -> {
                        val now = after.cards[a.uid]
                        if (now != null && !now.faceUp) listOf(h(Trigger.SET))
                        else {
                            val fromHand = from is Place.Pile && from.kind == PileKind.HAND
                            val main = DuelVerbs.kindOf(card, catalog) == CardKind.MONSTER
                            val normal = fromHand && main && (a.how == null || a.how == "normal" || a.how == "tribute")
                            listOf(h(Trigger.SUMMON), h(if (normal) Trigger.NORMAL_SUMMON else Trigger.SPECIAL_SUMMON))
                        }
                    }
                    to is Place.Zone && (to.kind == ZoneKind.SPELL || to.kind == ZoneKind.FIELD) && from !is Place.Zone -> {
                        val now = after.cards[a.uid]
                        if (now != null && !now.faceUp) listOf(h(Trigger.SET)) else emptyList()
                    }
                    to is Place.Pile && to.kind == PileKind.HAND && from is Place.Pile && from.kind == PileKind.DECK -> listOf(h(Trigger.SEARCH))
                    to is Place.Pile && to.kind == PileKind.GY && !(from is Place.Pile && from.kind == PileKind.GY) && a.how != "resolve" -> listOf(h(Trigger.SEND))
                    to is Place.Pile && to.kind == PileKind.BANISHED && !(from is Place.Pile && from.kind == PileKind.BANISHED) -> listOf(h(Trigger.BANISH))
                    else -> emptyList()
                }
            }
            is DuelAction.Position -> {
                val was = before.cards[a.uid] ?: return emptyList()
                val now = after.cards[a.uid] ?: return emptyList()
                val at = after.placeOf(a.uid)
                // A face-down monster turned face-up is a Flip Summon.
                if (at is Place.Zone && (at.kind == ZoneKind.MONSTER || at.kind == ZoneKind.EMZ) && !was.faceUp && now.faceUp) {
                    val seat = e.seat ?: now.controller
                    listOf(Happening(Trigger.SUMMON, seat, a.uid, nameOf(a.uid), phase, words))
                } else emptyList()
            }
            is DuelAction.Token -> listOf(
                Happening(Trigger.SUMMON, e.seat ?: a.to.seat, null, a.name.ifBlank { "Token" }, phase, words),
                Happening(Trigger.SPECIAL_SUMMON, e.seat ?: a.to.seat, null, a.name.ifBlank { "Token" }, phase, words),
            )
            is DuelAction.Draw -> listOf(Happening(Trigger.DRAW, a.seat, null, null, phase, words))
            is DuelAction.ChainAdd -> listOf(Happening(Trigger.ACTIVATE, a.seat, a.uid, a.uid?.let { nameOf(it) } ?: a.note.ifBlank { null }, phase, words))
            is DuelAction.Attack -> listOf(Happening(Trigger.ATTACK, a.seat, a.attacker, nameOf(a.attacker), phase, words))
            is DuelAction.Phase -> if (a.phase == before.phase) emptyList() else listOf(
                Happening(Trigger.PHASE_LEAVE, before.active, null, null, before.phase, words),
                Happening(Trigger.PHASE_ENTER, before.active, null, null, a.phase, words),
            )
            // Ending the turn from Main or Battle passes through the End Phase (1.0.85: "a trap at the End
            // Phase" never fired when the person ended the turn straight from Main Phase 2).
            is DuelAction.EndTurn -> buildList {
                add(Happening(Trigger.PHASE_LEAVE, before.active, null, null, before.phase, words))
                if (before.phase != DuelPhase.END) {
                    add(Happening(Trigger.PHASE_ENTER, before.active, null, null, DuelPhase.END, words))
                    add(Happening(Trigger.PHASE_LEAVE, before.active, null, null, DuelPhase.END, words))
                }
                add(Happening(Trigger.PHASE_ENTER, after.active, null, null, after.phase, words))
            }
            else -> emptyList()
        }
    }

    /**
     * The watches [happenings] fire, each at most once, on its first match. [summons] is a seat's Summons
     * so far this turn, for a watch with [Watch.atLeast].
     */
    fun hits(watches: List<Watch>, happenings: List<Happening>, watcher: Int, summons: (Int) -> Int = { 0 }): List<Hit> =
        watches.mapNotNull { w -> happenings.firstOrNull { fires(w, it, watcher, summons) }?.let { Hit(w, it) } }

    fun fires(w: Watch, h: Happening, watcher: Int, summons: (Int) -> Int = { 0 }): Boolean {
        if (h.kind.key !in w.on) return false
        when (w.by) {
            Watch.BY_OPPONENT -> if (h.seat == watcher) return false
            Watch.BY_SELF -> if (h.seat != watcher) return false
        }
        if (w.card.isNotBlank()) {
            val name = h.name ?: return false
            if (!name.contains(w.card, ignoreCase = true)) return false
        }
        if (w.phase.isNotBlank() && phaseOf(w.phase) != h.phase) return false
        if (w.atLeast > 0 && h.kind in SUMMONS && summons(h.seat) < w.atLeast) return false
        return true
    }

    private val SUMMONS = setOf(Trigger.SUMMON, Trigger.NORMAL_SUMMON, Trigger.SPECIAL_SUMMON)

    /** A watch in Ai's words: "#2 summon, activate by opponent · card “Dragon” · Main 1 · at least 5 · once — note". */
    fun describe(w: Watch): String = buildString {
        append("#${w.id} ${w.on.joinToString(", ")} by ${w.by}")
        if (w.card.isNotBlank()) append(" · card “${w.card}”")
        phaseOf(w.phase)?.let { append(" · ${it.label}") }
        if (w.atLeast > 0) append(" · at least ${w.atLeast} Summons this turn")
        if (w.once) append(" · once")
        if (w.until == Watch.UNTIL_TURN) append(" · this turn only")
        if (w.note.isNotBlank()) append(" — ${w.note}")
    }

    /** What the watches wait for, in the person's words, naming no card: "Summons, activations, leaving a phase". */
    fun kindsWords(watches: List<Watch>): String =
        watches.flatMap { it.on }.distinct().mapNotNull { k -> Trigger.entries.firstOrNull { it.key == k }?.words }.joinToString(", ")
}
