package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place

/**
 * What a seat may do at an Ai vs Ai table beyond the physics (the red team on Ai vs Ai, 2026-10): the table is manual and
 * never reads card text, so a seat could take the other's life points, draw, search, mill them out, wipe their chain or
 * roll again until a die suits it — and the record could not tell a won duel from a made one. Card text is still not
 * judged; what is held is **when** a move may happen at all:
 *
 * - What only an effect does — anything to the other seat's cards, life points, locks or chain links; a draw, a search,
 *   a look at or a shuffle of a Deck; a die, a coin, a random pick; a link negated — happens only while the seat is
 *   **resolving its own chain link** (its RESOLVE cue, its link on top), as the prompt teaches every effect to be made.
 * - Battle is its own: in its Battle Phase the turn player destroys the other's monsters and deals them damage.
 * - A chain resolves link by link, each by its own player: never a link of the other's, never the whole chain cleared, and
 *   no phase moved or turn ended while one stands.
 * - Only a seat's own cards go on the chain.
 * - What a seat writes is short: talk, a lock's words, a token's or a counter's name.
 *
 * Pure, folded action by action on the table each would leave; `MatchTable` asks it before a move is made, and the moves
 * menu offers only what it allows.
 */
object MatchLaw {
    /** Words a seat writes in one go: a `say`, a `note`. */
    const val TALK = 300

    /** A lock's words. */
    const val LOCK = 200

    /** A token's or a counter's name. */
    const val NAME = 40

    /** Talk in one cue: `say`, `note`, `lock`, pings — so no seat floods the other's log. */
    const val TALK_PER_CUE = 4

    /** Dice, coins and random picks in one resolution: an effect that rolls rolls once or twice, never until it suits. */
    const val ROLLS_PER_CUE = 2

    /** Whether [seat] is resolving its own chain link on [s] in a cue of [kind]: the time an effect's moves are made. */
    fun resolving(s: DuelState, seat: Int, kind: CueKind?): Boolean = kind == CueKind.RESOLVE && s.chain.lastOrNull()?.seat == seat

    /** Why [seat] may not make [actions] on [s] in a cue of [kind], or null when it may. */
    fun refusal(s: DuelState, seat: Int, kind: CueKind?, actions: List<DuelAction>): String? {
        var state = s
        for (a in actions) {
            refusal1(state, seat, kind, a)?.let { return it }
            state = (DuelRules.apply(state, a, seat) as? Outcome.Ok)?.state ?: return null
        }
        return null
    }

    private fun refusal1(s: DuelState, seat: Int, kind: CueKind?, a: DuelAction): String? {
        val resolving = resolving(s, seat, kind)
        val battle = s.phase == DuelPhase.BATTLE && s.active == seat
        fun theirs(uid: Int): Boolean {
            val c = s.cards[uid] ?: return false
            return when (s.placeOf(uid)) {
                is Place.Zone, is Place.Under -> c.controller != seat
                else -> c.owner != seat
            }
        }
        fun inDeck(uid: Int): Boolean = s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.DECK }
        return when (a) {
            is DuelAction.Move -> when {
                theirs(a.uid) && !resolving && !(battle && a.to is Place.Pile && (a.to as Place.Pile).kind == PileKind.GY) ->
                    EFFECT_ONLY + " (the other player's card)"
                inDeck(a.uid) && !resolving -> EFFECT_ONLY + " (a card from a Deck)"
                else -> null
            }
            is DuelAction.Draw -> if (!resolving) "$EFFECT_ONLY: the table draws for each turn; a draw beyond it is an effect's" else null
            is DuelAction.Shuffle -> if (!resolving) "$EFFECT_ONLY (a shuffle)" else null
            is DuelAction.Reveal -> if (!resolving && a.uids.any(::inDeck)) "$EFFECT_ONLY (a look at a Deck)" else null
            is DuelAction.Lp -> when {
                // Paying costs and taking battle damage: one's own, any time.
                a.seat == seat && a.set == null && a.delta <= 0 -> null
                resolving -> null
                battle && a.seat != seat && a.set == null && a.delta < 0 -> null
                else -> "$EFFECT_ONLY (life points)"
            }
            is DuelAction.Position -> if (theirs(a.uid) && !resolving) "$EFFECT_ONLY (the other player's card)" else null
            is DuelAction.Counter -> when {
                a.kind.length > NAME -> "A counter's name is at most $NAME characters"
                theirs(a.uid) && !resolving -> "$EFFECT_ONLY (the other player's card)"
                else -> null
            }
            is DuelAction.Token -> when {
                a.name.length > NAME -> "A token's name is at most $NAME characters"
                a.to.seat != seat && !resolving -> "$EFFECT_ONLY (a token on the other player's field)"
                else -> null
            }
            is DuelAction.Negate -> if (!resolving) "$EFFECT_ONLY: a link is negated by an effect resolving" else null
            DuelAction.ChainResolve -> {
                val top = s.chain.lastOrNull()
                if (top != null && top.seat != seat) "Chain Link ${s.chain.size} is the other player's: they resolve it" else null
            }
            DuelAction.ChainClear -> "The chain resolves link by link: it is never cleared"
            is DuelAction.ChainAdd -> when {
                a.uid != null && theirs(a.uid) -> "Only your own cards go on the chain"
                a.note.length > TALK -> "Say it in at most $TALK characters"
                else -> null
            }
            is DuelAction.Target -> if (a.from != null && theirs(a.from)) "Only your own card targets" else null
            is DuelAction.Unlock -> {
                val lock = s.locks.firstOrNull { it.id == a.id }
                if (lock != null && lock.seat != seat && !resolving) "$EFFECT_ONLY (the other player's lock)" else null
            }
            is DuelAction.Lock -> if (a.text.length > LOCK) "A lock is at most $LOCK characters" else null
            is DuelAction.Dice, is DuelAction.Coin, is DuelAction.Pick -> if (!resolving) "$EFFECT_ONLY (chance)" else null
            is DuelAction.Phase, DuelAction.EndTurn -> if (s.chain.isNotEmpty()) "Resolve the chain first" else null
            is DuelAction.Chat -> if (a.text.length > TALK) "Say it in at most $TALK characters" else null
            is DuelAction.Note -> if (a.text.length > TALK) "Say it in at most $TALK characters" else null
            else -> null
        }
    }

    /** Whether [a] is chance: a die, a coin, a random pick. */
    fun chance(a: DuelAction): Boolean = a is DuelAction.Dice || a is DuelAction.Coin || a is DuelAction.Pick

    /** Whether [a] is talk counted against [TALK_PER_CUE]. */
    fun talk(a: DuelAction): Boolean = a is DuelAction.Chat || a is DuelAction.Note || a is DuelAction.Lock || a is DuelAction.Ping

    const val EFFECT_ONLY = "Only an effect does that, made while you resolve your own chain link"
}
