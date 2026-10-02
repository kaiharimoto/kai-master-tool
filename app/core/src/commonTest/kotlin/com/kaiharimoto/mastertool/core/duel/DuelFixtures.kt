package com.kaiharimoto.mastertool.core.duel

/** A small, fixed world for the duel's tests: a few real cards' shapes under made-up passcodes. */
object DuelFixtures {
    const val ASH = 101
    const val DROLL = 102
    const val POT = 103
    const val CALLED = 104
    const val ZEUS = 105
    const val SANCT = 106
    const val PENDY = 107
    const val LINK = 108
    const val FILLER = 199

    val catalog = DuelCatalog { code ->
        when (code) {
            ASH -> DuelCardInfo("Ash Blossom & Joyous Spring", CardKind.MONSTER)
            DROLL -> DuelCardInfo("Droll & Lock Bird", CardKind.MONSTER)
            POT -> DuelCardInfo("Pot of Prosperity", CardKind.SPELL)
            CALLED -> DuelCardInfo("Called by the Grave", CardKind.SPELL)
            ZEUS -> DuelCardInfo("Divine Arsenal AA-ZEUS - Sky Thunder", CardKind.EXTRA_MONSTER)
            SANCT -> DuelCardInfo("Sanctifire", CardKind.FIELD_SPELL)
            PENDY -> DuelCardInfo("Pendulum Pal", CardKind.MONSTER, pendulum = true)
            LINK -> DuelCardInfo("Link Lad", CardKind.EXTRA_MONSTER, link = true)
            FILLER -> DuelCardInfo("Filler", CardKind.TRAP)
            else -> null
        }
    }

    /** Seat 0 holds Ash, Droll, Pot, Called in that order on top of its deck before the shuffle; seat 1 is all filler. */
    fun header(solo: Boolean = false, seed: Long = 42L) = DuelHeader(
        id = "t",
        seed = seed,
        seats = listOf(
            SeatSetup("Kai", main = listOf(ASH, DROLL, POT, CALLED, PENDY) + List(35) { FILLER }, extra = listOf(ZEUS, LINK)),
            SeatSetup("Rival", main = List(40) { FILLER }, extra = listOf(ZEUS)),
        ),
        solo = solo,
    )

    /** A table with nothing shuffled: seat 0's deck in written order, no hands. */
    fun bare(solo: Boolean = false) = DuelSetup.initial(header(solo))

    fun ok(s: DuelState, a: DuelAction, by: Int? = 0): DuelState =
        when (val o = DuelRules.apply(s, a, by)) {
            is Outcome.Ok -> o.state
            is Outcome.Refused -> error("Refused: ${o.reason} for $a")
        }

    fun refused(s: DuelState, a: DuelAction): String =
        (DuelRules.apply(s, a, 0) as? Outcome.Refused)?.reason ?: error("Expected a refusal for $a")

    /** Seat 0's uid for the n-th card it was dealt (main then extra). */
    fun uid(seat: Int, n: Int) = 1 + seat * DuelState.SEAT_UIDS + n
}
