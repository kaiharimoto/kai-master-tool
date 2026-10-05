package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.Toss
import kotlin.random.Random

/** What applying an action came to: the next table, or why the table could not do it. */
sealed interface Outcome {
    data class Ok(val state: DuelState) : Outcome
    data class Refused(val reason: String) : Outcome
}

/**
 * The table's physics, and nothing more: one card to a zone, the Extra Monster Zones shared, a card
 * goes to its owner's piles whoever controls it, a token that leaves the field leaves the duel,
 * materials follow their card (and go to the graveyard when it leaves the field), a draw needs a deck.
 * Whether a card's text allows a play is the players' business, as it is at a real table.
 *
 * Pure: the same state and action always give the same answer, which is what lets the log be the
 * duel and a replay be a fold.
 */
object DuelRules {

    fun apply(s: DuelState, a: DuelAction, by: Int? = null): Outcome {
        // The turn waits for the opening roll (1.0.87): no phase, no End Turn, no attack before who goes first is chosen.
        Opening.waits(s, a)?.let { return Outcome.Refused(it) }
        val out = when (a) {
            is DuelAction.Move -> move(s, a)
            is DuelAction.Draw -> draw(s, a)
            is DuelAction.Shuffle -> shuffle(s, a)
            is DuelAction.Pick -> pick(s, a)
            is DuelAction.Position -> position(s, a)
            is DuelAction.Counter -> counter(s, a)
            is DuelAction.Token -> token(s, a)
            // In Long, so a gain past the top stays at the top rather than wrapping to 0 (Phase D's red team).
            is DuelAction.Lp -> seatOk(s, a.seat) ?: ok(s.withSeat(a.seat) { it.copy(lp = (a.set?.toLong() ?: (it.lp.toLong() + a.delta)).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) })
            is DuelAction.Phase -> ok(s.copy(phase = a.phase, proposal = null))
            DuelAction.EndTurn -> ok(
                s.rehidden().copy(
                    turn = s.turn + 1,
                    active = if (s.solo) s.active else 1 - s.active,
                    phase = DuelPhase.DRAW,
                    chain = emptyList(),
                    arrows = emptyList(),
                    window = null,
                    proposal = null,
                    resolved = emptyList(),
                    attacks = emptyList(),
                    // What lasted the turn (or a chain) is over.
                    locks = s.locks.filter { it.until == Lock.UNTIL_DUEL },
                ),
            )
            is DuelAction.Propose -> seatOk(s, a.seat) ?: if (a.phase == null && !a.end) Outcome.Refused("Ask for a phase, or the end of the turn")
                else ok(s.copy(proposal = Proposal(a.seat, a.phase, a.end)))
            is DuelAction.Decline -> if (s.proposal == null) Outcome.Refused("Nothing was asked") else ok(s.copy(proposal = null))
            is DuelAction.Lock -> seatOk(s, a.seat) ?: if (a.text.isBlank()) Outcome.Refused("Lock what?") else lock(s, a)
            is DuelAction.Unlock -> if (s.locks.none { it.id == a.id }) Outcome.Refused("No lock ${a.id}")
                else ok(s.copy(locks = s.locks.filterNot { it.id == a.id }))
            is DuelAction.ChainAdd -> chainAdd(s, a)
            DuelAction.ChainResolve -> {
                val top = s.chain.lastOrNull() ?: return Outcome.Refused("There is no chain to resolve")
                val chain = s.chain.dropLast(1)
                ok(
                    (if (chain.isEmpty()) s.rehidden() else s).copy(
                        chain = chain,
                        arrows = s.arrows.filterNot { top.uid != null && it.from == top.uid },
                        locks = if (chain.isEmpty()) s.locks.filterNot { it.until == Lock.UNTIL_CHAIN } else s.locks,
                        // Its card waits on the field until the chain is over.
                        resolved = if (top.uid != null && top.uid !in s.resolved) s.resolved + top.uid else s.resolved,
                    ),
                )
            }
            DuelAction.ChainClear -> {
                val links = s.chain.mapNotNull { it.uid }.toSet()
                ok(s.rehidden().copy(chain = emptyList(), arrows = s.arrows.filterNot { it.from in links }, locks = s.locks.filterNot { it.until == Lock.UNTIL_CHAIN }, resolved = emptyList()))
            }
            is DuelAction.Keep -> ok(s.copy(resolved = s.resolved - a.uid))
            is DuelAction.Negate -> seatOk(s, a.seat) ?: when {
                a.link !in 1..s.chain.size -> Outcome.Refused(if (s.chain.isEmpty()) "There is no chain" else "There is no Chain Link ${a.link}")
                s.chain[a.link - 1].negated -> Outcome.Refused("Chain Link ${a.link} is negated already")
                else -> ok(s.copy(chain = s.chain.mapIndexed { i, l -> if (i == a.link - 1) l.copy(negated = true) else l }))
            }
            is DuelAction.Target -> target(s, a)
            is DuelAction.Attack -> attack(s, a)
            is DuelAction.Reveal -> reveal(s, a)
            is DuelAction.Coin -> ok(thrown(s, a.seat, coin = true, if (a.heads) 1 else 0, a.toss))
            is DuelAction.Dice -> ok(thrown(s, a.seat, coin = false, a.value, a.toss))
            is DuelAction.Chat, is DuelAction.Ping, is DuelAction.Note,
            is DuelAction.Unknown -> ok(s)
            is DuelAction.Thinking -> ok(s.copy(thinking = if (a.on) s.thinking + a.seat else s.thinking - a.seat))
            is DuelAction.Answer -> ok(s.copy(window = null))
            is DuelAction.Concede -> seatOk(s, a.seat) ?: ok(s.copy(conceded = a.seat))
            is DuelAction.OpeningRoll -> Opening.roll(s, a)
            is DuelAction.GoFirst -> Opening.choose(s, a)
        }
        // The die and the coin are put back by the next move that is not talk or chance (1.0.96).
        val moved = out is Outcome.Ok && out.state.chance.isNotEmpty() && !a.social &&
            a !is DuelAction.Coin && a !is DuelAction.Dice && a !is DuelAction.Thinking && a !is DuelAction.Answer
        val after = if (moved) Outcome.Ok((out as Outcome.Ok).state.copy(chance = emptyList())) else out
        // A seat that acts is no longer thinking.
        if (after is Outcome.Ok && by != null && !a.social && by in after.state.thinking) {
            return Outcome.Ok(after.state.copy(thinking = after.state.thinking - by))
        }
        return after
    }

    /** [seat]'s die or coin landed on the table, in place of the last one; a roll with no throw (before 1.0.96) lies nowhere. */
    private fun thrown(s: DuelState, seat: Int, coin: Boolean, value: Int, toss: Toss?): DuelState {
        if (toss == null || seat !in s.seats.indices) return s
        return s.copy(chance = s.chance.filterNot { it.seat == seat && it.coin == coin } + Chance(seat, coin, value, toss))
    }

    /** Applies [actions] in order, all or nothing: the first refusal names its index. */
    fun applyAll(s: DuelState, actions: List<DuelAction>, by: Int? = null): Pair<DuelState?, String?> {
        var state = s
        actions.forEachIndexed { i, a ->
            when (val o = apply(state, a, by)) {
                is Outcome.Ok -> state = o.state
                is Outcome.Refused -> return null to (if (actions.size > 1) "Step ${i + 1}: ${o.reason}" else o.reason)
            }
        }
        return state to null
    }

    // ---- cards ---------------------------------------------------------------------------------------

    private fun move(s: DuelState, a: DuelAction.Move): Outcome {
        val card = s.cards[a.uid] ?: return Outcome.Refused("No such card")
        val from = s.placeOf(a.uid) ?: return Outcome.Refused("That card has left the duel")
        // Piles are the owner's, whoever controls the card; a token leaving the field leaves the duel.
        val to: Place = when (val t = a.to) {
            is Place.Pile -> if (card.token) Place.Void else t.copy(seat = card.owner)
            is Place.Zone -> {
                if (t.seat !in 0..1 || t.index !in 0 until zoneCount(t.kind)) return Outcome.Refused("No such zone")
                val there = s.at(t)
                if (there != null && there != a.uid && !(a.over && (t.kind == ZoneKind.MONSTER || t.kind == ZoneKind.EMZ))) return Outcome.Refused("That zone is taken")
                if (there != null && a.over && a.uid in s.cards.getValue(there).under) return Outcome.Refused("A material cannot go on top of its own card")
                t
            }
            is Place.Under -> {
                val host = s.cards[t.host] ?: return Outcome.Refused("No such card to attach to")
                if (t.host == a.uid) return Outcome.Refused("A card cannot be its own material")
                val hostPlace = s.placeOf(t.host)
                if (hostPlace !is Place.Zone) return Outcome.Refused("Materials go under a card on the field")
                if (a.uid in host.under && t.index == -1) return Outcome.Refused("It is already a material of that card")
                t
            }
            Place.Void -> if (card.token) Place.Void else return Outcome.Refused("Only a token leaves the duel")
        }
        if (to is Place.Zone && from is Place.Zone && to == from && a.pos == null) return Outcome.Refused("It is already there")

        val knowers = DuelSight.knowers(s, a.uid)
        var next = s.without(a.uid, from)
        // On top of the card there (Move.over): it and its materials are lifted out, to go beneath this one.
        val covered = if (a.over && to is Place.Zone) s.at(to)?.takeIf { it != a.uid } else null
        val beneath = covered?.let { listOf(it) + next.cards.getValue(it).under }.orEmpty()
        if (covered != null) {
            next = next.without(covered, to).withCard(covered) { it.copy(under = emptyList(), counters = emptyMap(), controller = it.owner) }
            next = next.copy(
                arrows = next.arrows.dropUid(covered),
                attacks = next.attacks.filterNot { it.attacker == covered || it.target == covered },
            )
        }
        // Materials follow their card onto the field or beneath another; anywhere else they go to the graveyard.
        val materials = next.cards.getValue(a.uid).under
        if (materials.isNotEmpty() && to !is Place.Zone) {
            next = next.withCard(a.uid) { it.copy(under = emptyList()) }
            if (to is Place.Under) {
                next = next.withCard(to.host) { it.copy(under = it.under + materials) }
            } else {
                materials.forEach { m ->
                    val mat = next.cards.getValue(m)
                    next = if (mat.token) next.copy(cards = next.cards - m)
                    else next.insertInPile(mat.owner, PileKind.GY, m, Place.TOP).withCard(m) { it.copy(pos = CardPosition.FACE_UP_ATK, controller = it.owner) }
                }
            }
        }

        if (to == Place.Void) {
            return ok(next.copy(
                cards = next.cards - a.uid, seen = next.seen - a.uid, arrows = next.arrows.dropUid(a.uid),
                resolved = next.resolved - a.uid, attacks = next.attacks.filterNot { it.attacker == a.uid || it.target == a.uid },
            ))
        }
        val onField = from is Place.Zone
        val pos = positionFor(to, a.pos, card, onField)
        // The Extra Deck takes back its own cards; a Main Deck card only face-up, as a Pendulum Monster goes there (1.0.93).
        if (to is Place.Pile && to.kind == PileKind.EXTRA && !card.extraDeck && !pos.faceUp) {
            return Outcome.Refused(MAIN_TO_EXTRA)
        }
        next = when (to) {
            is Place.Zone -> next.inZone(to, a.uid).let { n -> if (beneath.isEmpty()) n else n.withCard(a.uid) { it.copy(under = it.under + beneath) } }
            is Place.Pile -> next.insertInPile(to.seat, to.kind, a.uid, to.at)
            is Place.Under -> next.withCard(to.host) { h ->
                val under = h.under.toMutableList().apply { if (to.index < 0 || to.index > size) add(a.uid) else add(to.index, a.uid) }
                h.copy(under = under)
            }
            Place.Void -> next
        }
        val stays = to is Place.Zone && onField
        next = next.withCard(a.uid) {
            it.copy(
                pos = pos,
                controller = if (to is Place.Zone) to.seat else it.owner,
                counters = if (stays) it.counters else emptyMap(),
            )
        }
        next = if (to is Place.Pile && to.kind == PileKind.HAND) {
            // Into a hand: its owner's alone; what anyone saw of it before is forgotten (1.0.82).
            next.copy(seen = next.seen - a.uid)
        } else if (hidesOnSet(from, to, pos)) {
            // Set from the hand: whatever was known of it is gone, and its veil is new, so the other seat
            // cannot tell which card it was (1.0.81, kai: a card searched, then Set, was named across the table).
            next.copy(seen = next.seen - a.uid)
        } else {
            next.copy(seen = next.seen.with(a.uid, knowers))
        }
        if (to !is Place.Zone) next = next.copy(
            arrows = next.arrows.dropUid(a.uid), resolved = next.resolved - a.uid,
            // A card that left the field no longer attacks or is attacked (1.0.85).
            attacks = next.attacks.filterNot { it.attacker == a.uid || it.target == a.uid },
        )
        if (from is Place.Pile && from.kind == PileKind.HAND && !(to is Place.Pile && to.kind == PileKind.HAND && to.seat == from.seat)) {
            // A card left the hand: it and every card still in it take a new veil, so the other seat cannot follow
            // one card from a search to its Set — or a face-down banish — by which veil went missing (1.0.85).
            next = next.reveiled(next.seats[from.seat].hand + a.uid)
        }
        return ok(next)
    }

    /**
     * The chain is over: a card activated from a hand, shown while its link stood ([DuelSight.onChain]), goes back
     * to being one of its owner's cards and takes a new veil, so it cannot be followed back into the hand (1.0.87).
     */
    private fun DuelState.rehidden(): DuelState =
        reveiled((chain.mapNotNull { it.uid } + resolved).filter { u -> placeOf(u).let { it is Place.Pile && it.kind == PileKind.HAND } })

    /**
     * [uids] take new veils, and nothing still points at them by an old one: an arrow or a chain link's target
     * drawn at a hidden card would follow it through every new veil (1.0.85, the red team's second pass).
     */
    private fun DuelState.reveiled(uids: Collection<Int>): DuelState {
        if (uids.isEmpty()) return this
        val set = uids.toSet()
        return copy(
            epoch = epoch + set.associateWith { (epoch[it] ?: 0) + 1 },
            arrows = set.fold(arrows) { a, u -> a.dropUid(u) },
            chain = chain.map { l -> if (l.targets.any { it in set }) l.copy(targets = l.targets - set) else l },
        )
    }

    /**
     * Whether a move hides the card from all but its controller: from a hand to a zone face-down. A
     * reveal made it public only for that moment; once in the hand, which card was Set is private. A
     * card Set from the Deck or the GY keeps what was known of it.
     */
    fun hidesOnSet(from: Place?, to: Place, pos: CardPosition): Boolean =
        from is Place.Pile && from.kind == PileKind.HAND && to is Place.Zone && !pos.faceUp

    private fun draw(s: DuelState, a: DuelAction.Draw): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (a.n < 1) return Outcome.Refused("Draw at least one card")
        val deck = s.seats[a.seat].deck
        if (deck.size < a.n) return Outcome.Refused(if (deck.isEmpty()) "The deck is empty" else "The deck holds only ${deck.size}")
        val drawn = deck.take(a.n)
        var next = s.withSeat(a.seat) { it.copy(deck = it.deck.drop(a.n), hand = it.hand + drawn) }
        drawn.forEach { u -> next = next.withCard(u) { it.copy(pos = CardPosition.FACE_UP_ATK) } }
        // A card drawn is its owner's alone, whatever was seen of it on the deck (1.0.82).
        return ok(next.copy(seen = next.seen - drawn.toSet()))
    }

    /** The cards [a] picks on [s], in the order they are moved: its pool riffled by its salt. Empty when it cannot. */
    fun picked(s: DuelState, a: DuelAction.Pick): List<Int> {
        val pool = pool(s, a)
        if (pool.isEmpty() || a.n !in 1..pool.size) return emptyList()
        return DuelRandom.riffle(pool, a.salt).take(a.n)
    }

    private fun pool(s: DuelState, a: DuelAction.Pick): List<Int> =
        a.among.ifEmpty { a.from?.let { f -> s.seats.getOrNull(f.seat)?.pile(f.kind) }.orEmpty() }.distinct()

    private fun pick(s: DuelState, a: DuelAction.Pick): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (a.among.isEmpty() && a.from == null) return Outcome.Refused("Pick from where?")
        if (a.among.any { it !in s.cards }) return Outcome.Refused("No such card")
        val pool = pool(s, a)
        if (pool.isEmpty()) return Outcome.Refused("There is nothing to pick from")
        if (a.n < 1) return Outcome.Refused("Pick at least one")
        if (a.n > pool.size) return Outcome.Refused("There ${if (pool.size == 1) "is only 1" else "are only ${pool.size}"} to pick from")
        var st = s
        for (u in picked(s, a)) {
            when (val o = move(st, DuelAction.Move(u, a.to, a.pos, a.how))) {
                is Outcome.Ok -> st = o.state
                is Outcome.Refused -> return o
            }
        }
        return ok(st)
    }

    private fun shuffle(s: DuelState, a: DuelAction.Shuffle): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (a.pile == PileKind.GY || a.pile == PileKind.BANISHED) return Outcome.Refused("Only a deck, a hand or an Extra Deck is shuffled")
        val pile = s.seats[a.seat].pile(a.pile)
        val shuffled = DuelRandom.riffle(pile, a.salt)
        return ok(
            s.withSeat(a.seat) { it.withPile(a.pile, shuffled) }.copy(seen = s.seen - pile.toSet()).reveiled(pile),
        )
    }

    private fun position(s: DuelState, a: DuelAction.Position): Outcome {
        val place = s.placeOf(a.uid) as? Place.Zone ?: return Outcome.Refused("Only a card on the field changes position")
        val card = s.cards.getValue(a.uid)
        val pos = positionFor(place, a.pos, card, true)
        if (pos == card.pos) return Outcome.Refused("It is already in that position")
        val knowers = DuelSight.knowers(s, a.uid)
        return ok(s.withCard(a.uid) { it.copy(pos = pos) }.let { it.copy(seen = it.seen.with(a.uid, knowers)) })
    }

    private fun counter(s: DuelState, a: DuelAction.Counter): Outcome {
        val card = s.cards[a.uid] ?: return Outcome.Refused("No such card")
        if (s.placeOf(a.uid) !is Place.Zone) return Outcome.Refused("Counters sit on cards on the field")
        val n = (card.counters[a.kind] ?: 0) + a.delta
        if (n < 0) return Outcome.Refused("It has no counters to remove")
        return ok(s.withCard(a.uid) { it.copy(counters = if (n == 0) it.counters - a.kind else it.counters + (a.kind to n)) })
    }

    private fun token(s: DuelState, a: DuelAction.Token): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (a.to.kind == ZoneKind.FIELD) return Outcome.Refused("A token goes to a Monster Zone")
        if (a.to.index !in 0 until zoneCount(a.to.kind)) return Outcome.Refused("No such zone")
        if (s.at(a.to) != null) return Outcome.Refused("That zone is taken")
        // Stamped on commit (1.0.86), so a token put into the past never renumbers the ones after it; a log
        // written before has none, and takes the table's next uid as it did.
        val uid = a.uid ?: s.nextUid
        if (uid in s.cards) return Outcome.Refused("That token is already on the table")
        val card = CardInst(uid, a.code, owner = a.seat, controller = a.to.seat, pos = positionFor(a.to, a.pos, null, false), token = true, name = a.name, atk = a.atk, def = a.def)
        return ok(s.copy(cards = s.cards + (uid to card), nextUid = maxOf(s.nextUid, uid + 1)).inZone(a.to, uid))
    }

    /** A lock written down: its stamped id (1.0.86), or — in a log from before — the highest held + 1, as it was. */
    private fun lock(s: DuelState, a: DuelAction.Lock): Outcome {
        val id = a.id ?: ((s.locks.maxOfOrNull { it.id } ?: 0) + 1)
        if (s.locks.any { it.id == id }) return Outcome.Refused("Lock $id is already written")
        return ok(s.copy(locks = s.locks + Lock(id, a.seat, a.text.trim(), a.until), lastLock = maxOf(s.lastLock, id)))
    }

    private fun attack(s: DuelState, a: DuelAction.Attack): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (s.phase != DuelPhase.BATTLE) return Outcome.Refused("Attacks are declared in the Battle Phase")
        // Only the turn player attacks (1.0.87, the red team: typed attacks reached here without `DuelVerbs.canAttack`).
        if (!s.solo && s.active != a.seat) return Outcome.Refused("Only the turn player attacks")
        val attacker = s.cards[a.attacker] ?: return Outcome.Refused("No such card")
        val at = s.placeOf(a.attacker)
        if (at !is Place.Zone || (at.kind != ZoneKind.MONSTER && at.kind != ZoneKind.EMZ) || attacker.controller != a.seat) {
            return Outcome.Refused("Only a monster you control attacks")
        }
        if (!attacker.faceUp || attacker.defense) return Outcome.Refused("A monster attacks in face-up Attack Position")
        if (a.target != null) {
            val target = s.cards[a.target] ?: return Outcome.Refused("No such card")
            val there = s.placeOf(a.target)
            if (there !is Place.Zone || (there.kind != ZoneKind.MONSTER && there.kind != ZoneKind.EMZ) || target.controller == a.seat) {
                return Outcome.Refused("It attacks a monster the other player controls")
            }
        }
        return ok(s.copy(attacks = s.attacks + Attack(a.seat, a.attacker, a.target)))
    }

    private fun chainAdd(s: DuelState, a: DuelAction.ChainAdd): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (a.uid != null && a.uid !in s.cards) return Outcome.Refused("No such card")
        val arrows = if (a.targets.isNotEmpty()) s.arrows + Arrow(a.seat, a.uid, a.targets) else s.arrows
        // A new chain begins with nothing waiting from the last one.
        val resolved = if (s.chain.isEmpty()) emptyList() else s.resolved
        return ok(s.copy(chain = s.chain + ChainLink(a.seat, a.uid, a.note, a.targets), arrows = arrows, resolved = resolved))
    }

    private fun target(s: DuelState, a: DuelAction.Target): Outcome {
        seatOk(s, a.seat)?.let { return it }
        return if (a.on) {
            if (a.to.isEmpty()) return Outcome.Refused("Target at least one card")
            if (a.to.any { it !in s.cards }) return Outcome.Refused("No such card")
            ok(s.copy(arrows = s.arrows + Arrow(a.seat, a.from, a.to)))
        } else {
            ok(s.copy(arrows = s.arrows.filterNot { it.seat == a.seat && it.from == a.from && (a.to.isEmpty() || it.to == a.to) }))
        }
    }

    private fun reveal(s: DuelState, a: DuelAction.Reveal): Outcome {
        seatOk(s, a.seat)?.let { return it }
        if (a.uids.isEmpty()) return Outcome.Refused("Reveal at least one card")
        if (a.uids.any { it !in s.cards }) return Outcome.Refused("No such card")
        val to = a.to?.let { setOf(it) } ?: setOf(0, 1)
        // A card revealed from a hand is shown for that moment — the log names it — and stays its owner's (1.0.82).
        val kept = a.uids.filterNot { u -> s.placeOf(u).let { it is Place.Pile && it.kind == PileKind.HAND } }
        return ok(s.copy(seen = kept.fold(s.seen) { m, u -> m.with(u, to) }))
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /**
     * The position a card lands in. A Spell & Trap or Field Zone has no battle position, so it is
     * upright face-up or face-down; a monster from off the field comes face-up in Attack; a graveyard
     * is face-up, a deck face-down, a hand face-up to its owner.
     */
    fun positionFor(to: Place, wanted: CardPosition?, card: CardInst?, fromField: Boolean): CardPosition = when (to) {
        is Place.Zone -> when (to.kind) {
            ZoneKind.SPELL, ZoneKind.FIELD -> {
                val up = wanted?.faceUp ?: (if (fromField && card != null) card.faceUp else true)
                if (up) CardPosition.FACE_UP_ATK else CardPosition.FACE_DOWN_ATK
            }
            ZoneKind.MONSTER, ZoneKind.EMZ -> wanted
                ?: if (fromField && card != null && card.pos != CardPosition.FACE_DOWN_ATK) card.pos
                else CardPosition.FACE_UP_ATK
        }
        is Place.Pile -> when (to.kind) {
            PileKind.GY, PileKind.HAND -> CardPosition.FACE_UP_ATK
            PileKind.DECK -> CardPosition.FACE_DOWN_DEF
            PileKind.EXTRA, PileKind.BANISHED -> if (wanted?.faceUp == false) CardPosition.FACE_DOWN_DEF
                else if (to.kind == PileKind.BANISHED || wanted?.faceUp == true) CardPosition.FACE_UP_ATK
                else CardPosition.FACE_DOWN_DEF
        }
        is Place.Under -> CardPosition.FACE_UP_ATK
        Place.Void -> CardPosition.FACE_UP_ATK
    }

    fun zoneCount(kind: ZoneKind): Int = when (kind) {
        ZoneKind.MONSTER, ZoneKind.SPELL -> DuelState.ZONES
        ZoneKind.EMZ -> 2
        ZoneKind.FIELD -> 1
    }

    private fun ok(s: DuelState) = Outcome.Ok(s)

    /** Why a Main Deck card face-down is refused the Extra Deck. */
    const val MAIN_TO_EXTRA = "A Main Deck card goes to the Extra Deck only face-up, as a Pendulum Monster does"


    private fun seatOk(s: DuelState, seat: Int): Outcome? = if (seat !in s.seats.indices) Outcome.Refused("No such seat") else null

    private fun List<Arrow>.dropUid(uid: Int): List<Arrow> =
        mapNotNull { if (it.from == uid) null else it.copy(to = it.to - uid).takeIf { a -> a.to.isNotEmpty() } }

    private fun Map<Int, Set<Int>>.with(uid: Int, seats: Set<Int>): Map<Int, Set<Int>> {
        val all = (this[uid] ?: emptySet()) + seats
        return if (all.isEmpty()) this - uid else this + (uid to all)
    }
}

internal fun DuelState.withSeat(i: Int, f: (SeatState) -> SeatState): DuelState =
    copy(seats = seats.mapIndexed { j, seat -> if (j == i) f(seat) else seat })

internal fun DuelState.withCard(uid: Int, f: (CardInst) -> CardInst): DuelState =
    copy(cards = cards + (uid to f(cards.getValue(uid))))

internal fun DuelState.inZone(zone: Place.Zone, uid: Int): DuelState = when (zone.kind) {
    ZoneKind.EMZ -> copy(emz = emz.mapIndexed { i, u -> if (i == zone.index) uid else u })
    ZoneKind.MONSTER -> withSeat(zone.seat) { it.copy(monsters = it.monsters.mapIndexed { i, u -> if (i == zone.index) uid else u }) }
    ZoneKind.SPELL -> withSeat(zone.seat) { it.copy(spells = it.spells.mapIndexed { i, u -> if (i == zone.index) uid else u }) }
    ZoneKind.FIELD -> withSeat(zone.seat) { it.copy(field = uid) }
}

internal fun DuelState.insertInPile(seat: Int, kind: PileKind, uid: Int, at: Int?): DuelState = withSeat(seat) { st ->
    val list = st.pile(kind).toMutableList()
    val index = when {
        at == null -> if (kind == PileKind.HAND) list.size else 0
        at < 0 -> list.size
        else -> at.coerceAtMost(list.size)
    }
    list.add(index, uid)
    st.withPile(kind, list)
}

/** The table with [uid] lifted out of [from]: its zone emptied, its pile closed up, its host's materials shortened. */
internal fun DuelState.without(uid: Int, from: Place): DuelState = when (from) {
    is Place.Zone -> when (from.kind) {
        ZoneKind.EMZ -> copy(emz = emz.map { if (it == uid) null else it })
        ZoneKind.MONSTER -> withSeat(from.seat) { it.copy(monsters = it.monsters.map { u -> if (u == uid) null else u }) }
        ZoneKind.SPELL -> withSeat(from.seat) { it.copy(spells = it.spells.map { u -> if (u == uid) null else u }) }
        ZoneKind.FIELD -> withSeat(from.seat) { it.copy(field = null) }
    }
    is Place.Pile -> withSeat(from.seat) { it.withPile(from.kind, it.pile(from.kind) - uid) }
    is Place.Under -> withCard(from.host) { it.copy(under = it.under - uid) }
    Place.Void -> this
}

/**
 * Chance, written down: the deck's Fisher-Yates spelled out as `PlayField` spells it — the stdlib's
 * `shuffled` may change between Kotlin versions and platforms, and a seed that dealt one hand on the
 * tablet and another on the desk would make every replay a different duel — and one [Random] per log
 * entry, so stamping entry 40 never depends on what entry 39 rolled.
 */
object DuelRandom {
    fun <T> riffle(list: List<T>, seed: Long): List<T> {
        val shuffled = list.toMutableList()
        val random = Random(seed)
        for (i in shuffled.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val swap = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = swap
        }
        return shuffled
    }

    /** The dice for log entry [i] of a duel seeded [seed]. */
    fun forEntry(seed: Long, i: Int): Random = Random(seed * 1_000_003L + i * 7_919L + 17L)

    /**
     * The dice for the [n]th thing left to chance in a duel (1.0.86): keyed to how many shuffles, coins and
     * dice came before it, not to its place in the log, so a shuffle undone and made again after a line of
     * chat — or any move that leaves nothing to chance — comes out the same. Undo cannot fish for a draw.
     */
    fun forRoll(seed: Long, n: Int): Random = forEntry(seed, n)

    /** Whether [a] leaves something to chance, to be stamped. */
    fun rolls(a: DuelAction): Boolean =
        a is DuelAction.Shuffle || a is DuelAction.Coin || a is DuelAction.Dice || a is DuelAction.Pick || a is DuelAction.OpeningRoll

    /** Fills in what [a] leaves to chance: a shuffle's salt, a coin, a die. Everything else is returned as it came. */
    fun stamp(a: DuelAction, random: Random): DuelAction = when (a) {
        is DuelAction.Shuffle -> a.copy(salt = random.nextLong())
        // The value first, then the throw when no hand made one (1.0.96): what it reads is what it read before throws.
        is DuelAction.Coin -> a.copy(heads = random.nextBoolean()).let { c -> c.copy(toss = a.toss?.takeIf { it.valid }?.rounded() ?: Toss.randomCoin(random)) }
        is DuelAction.Dice -> a.copy(value = random.nextInt(1, 7)).let { d -> d.copy(toss = a.toss?.takeIf { it.valid }?.rounded() ?: Toss.randomDie(random)) }
        is DuelAction.Pick -> a.copy(salt = random.nextLong())
        // The opening roll's two dice (1.0.87), and a throw for them when no hand made one: the values first, so a
        // throw made by hand never changes what the dice read.
        is DuelAction.OpeningRoll -> {
            val values = listOf(random.nextInt(1, 7), random.nextInt(1, 7))
            a.copy(values = values, toss = a.toss?.takeIf { it.valid }?.rounded() ?: DiceThrow.random(random))
        }
        else -> a
    }
}
