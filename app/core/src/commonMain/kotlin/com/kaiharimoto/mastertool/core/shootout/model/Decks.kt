package com.kaiharimoto.mastertool.core.shootout.model

import kotlin.random.Random

/**
 * The decks a model's hands are dealt from, per stratum (Phase S §1½): yours as built or after your siding plan,
 * and theirs likewise. The deck alone has no opponent.
 */
class Decks(private val own: Map<Stratum, DeckList>, private val theirs: Map<Stratum, DeckList> = emptyMap()) {

    /** Your deck in [stratum]. */
    fun own(stratum: Stratum): DeckList = own[stratum] ?: error("no deck for $stratum")

    /** Their deck in [stratum], or null for the deck alone. */
    fun theirs(stratum: Stratum): DeckList? = theirs[stratum]

    /** A hand and the opponent's, dealt as a real shuffle would in [stratum]. */
    fun deal(stratum: Stratum, random: Random): Pair<Hand, Hand?> =
        own(stratum).draw(stratum.handSize, random) to theirs(stratum)?.draw(stratum.opponentHandSize, random)

    companion object {
        /** The deck on its own, first and second. */
        fun alone(deck: DeckList): Decks = Decks(mapOf(Stratum.ALONE_FIRST to deck, Stratum.ALONE_SECOND to deck))

        /** A matchup: game one both ways, and sided both ways when both plans are given. */
        fun matchup(deck: DeckList, theirs: DeckList, sided: DeckList? = null, theirsSided: DeckList? = null): Decks {
            val own = mutableMapOf(Stratum.G1_FIRST to deck, Stratum.G1_SECOND to deck)
            val opp = mutableMapOf(Stratum.G1_FIRST to theirs, Stratum.G1_SECOND to theirs)
            if (sided != null && theirsSided != null) {
                own[Stratum.SIDED_FIRST] = sided; own[Stratum.SIDED_SECOND] = sided
                opp[Stratum.SIDED_FIRST] = theirsSided; opp[Stratum.SIDED_SECOND] = theirsSided
            }
            return Decks(own, opp)
        }
    }
}

/**
 * Hands dealt as a real shuffle would, kept for reading the model back out (Phase S §2): averaging over these is
 * averaging over the hands that really occur, by their real odds. Seeded, so a report is the same twice.
 */
class Pool(val stratum: Stratum, val hands: List<Hand>, val opponents: List<Hand?>) {
    val size: Int get() = hands.size

    companion object {
        fun deal(decks: Decks, stratum: Stratum, size: Int, seed: Long): Pool {
            val random = Random(seed)
            val dealt = List(size) { decks.deal(stratum, random) }
            return Pool(stratum, dealt.map { it.first }, dealt.map { it.second })
        }
    }
}
