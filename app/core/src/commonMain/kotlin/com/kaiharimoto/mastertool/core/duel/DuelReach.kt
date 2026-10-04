package com.kaiharimoto.mastertool.core.duel

/**
 * What a seat may do to a card it cannot see (one list, Phase C): the network's guest is held to it (`DuelHost.resolve`,
 * 1.0.85) and so is Ai at the table, whose knowledge setting covers what it reads but not what it does (the red team's
 * lead: "Ai's own-seat moves may reveal, flip or take the opponent's hidden cards").
 *
 * Another seat's card that [seat] neither owns, controls nor sees may go to its owner's own piles or side of the field —
 * destroyed, banished, milled — but never to [seat]'s side, into [seat]'s hand or under its cards, nor face-up by a flip
 * [seat] makes; [seat] reveals only its own cards; and a card in another seat's hand or Deck is never a target.
 */
object DuelReach {
    /** [uid] is another seat's, out of [seat]'s sight and control. */
    fun hidden(s: DuelState, uid: Int, seat: Int): Boolean =
        s.cards[uid]?.let { it.owner != seat && it.controller != seat && !DuelSight.sees(s, uid, seat) } == true

    /** Why [seat] may not make [a] on the table [s] — it takes, turns up, shows or targets a card hidden from it — or null. */
    fun refusal(s: DuelState, seat: Int, a: DuelAction): String? = when (a) {
        is DuelAction.Move -> {
            val to = a.to
            val takes = hidden(s, a.uid, seat) && when (to) {
                is Place.Zone -> to.seat == seat
                // A graveyard or banishment is its owner's whoever's pile it was dropped on.
                is Place.Pile -> to.seat == seat && to.kind != PileKind.GY && to.kind != PileKind.BANISHED
                is Place.Under -> true
                Place.Void -> false
            }
            if (takes) TAKE else null
        }
        is DuelAction.Position -> if (hidden(s, a.uid, seat) && a.pos.faceUp) FLIP else null
        is DuelAction.ChainAdd -> if (a.targets.any { inHandOrDeck(s, it, seat) }) TARGET else null
        is DuelAction.Target -> if (a.to.any { inHandOrDeck(s, it, seat) }) TARGET else null
        is DuelAction.Reveal -> if (a.uids.any { u -> s.cards[u]?.let { it.owner != seat && it.controller != seat } != false }) REVEAL else null
        else -> null
    }

    /** The first of [actions] [seat] may not make, folded on [s] one at a time, as its reason; null when all may be made. */
    fun check(s: DuelState, seat: Int, actions: List<DuelAction>): String? {
        var state = s
        for (a in actions) {
            refusal(state, seat, a)?.let { return it }
            state = (DuelRules.apply(state, a, seat) as? Outcome.Ok)?.state ?: return null
        }
        return null
    }

    /** Whether [uid] is another seat's, in its hand or Deck: never a target. */
    fun inHandOrDeck(s: DuelState, uid: Int, seat: Int): Boolean {
        val c = s.cards[uid] ?: return false
        if (c.owner == seat) return false
        val p = s.placeOf(uid)
        return p is Place.Pile && (p.kind == PileKind.HAND || p.kind == PileKind.DECK)
    }

    const val TAKE = "That card is not yours to take"
    const val FLIP = "Only its controller turns that card face-up"
    const val TARGET = "A card in their hand or Deck cannot be targeted"
    const val REVEAL = "You can reveal only your own cards"
}
