package com.kaiharimoto.mastertool.core.prep

/**
 * What Konami's rules say about an event, as facts a prep page can show.
 *
 * Source: the Official KDE-US Yu-Gi-Oh! TRADING CARD GAME Tournament Policy,
 * v2.5, in effect as of 5 September 2025
 * (`img.yugioh-card.com/en/downloads/penalty_guide/YGOTCG_Tournament_Policy_v_2_5.pdf`).
 * Every number here is read off that document, and the section it comes from is
 * named beside it, so the next version of the policy can be checked line by
 * line. An event's own Ops Doc outranks all of it — the policy says so itself —
 * which is why these are reminders to prepare by, never a ruling.
 */
object Policy {

    /** §III.C: rounds are 50 minutes at Tier 1, 2 and 3. */
    const val ROUND_MINUTES = 50

    /** §VII.C Side Deck: "less than three (3) minutes" to side between duels. */
    const val SIDING_MINUTES = 3

    /** §V.A: a completed match's result is due within 5 minutes of the end of the round. */
    const val REPORT_MINUTES = 5

    /**
     * §IV.D Deck Registration: "Tier 2 to 4 events all require Deck Lists"; most Tier 1
     * events do not, though an organiser may ask for one if they say so first.
     */
    fun decklistRequired(tier: Int): Boolean = tier >= 2

    /** §IV.G Sleeves: "Sleeves are required at Tier 2 and higher events." */
    fun sleevesRequired(tier: Int): Boolean = tier >= 2

    /**
     * How an event of a size is run: its Swiss rounds, split over two days when
     * it is that big, and its playoff cut (0 for none).
     */
    data class Swiss(val rounds: Int, val day1: Int, val day2: Int, val topCut: Int) {
        val twoDays: Boolean get() = day2 > 0
    }

    /**
     * §III.H, Tier 1 and 2 (players → Swiss rounds, optional top cut). The cut is
     * optional at these tiers; it is given as the table gives it.
     */
    private val TIER_1_2 = listOf(
        8 to Swiss(3, 3, 0, 0),
        16 to Swiss(4, 4, 0, 4),
        32 to Swiss(5, 5, 0, 4),
        64 to Swiss(6, 6, 0, 8),
        128 to Swiss(7, 7, 0, 8),
        256 to Swiss(8, 8, 0, 8),
        512 to Swiss(9, 9, 0, 8),
        1024 to Swiss(10, 10, 0, 8),
        2048 to Swiss(11, 11, 0, 8),
        Int.MAX_VALUE to Swiss(12, 12, 0, 8),
    )

    /** §III.I, Tier 3 and 4: rounds split over Day 1 and Day 2, Top 8. The table starts at 129. */
    private val TIER_3_4 = listOf(
        256 to Swiss(11, 7, 4, 8),
        512 to Swiss(12, 8, 4, 8),
        1024 to Swiss(13, 8, 5, 8),
        2048 to Swiss(14, 9, 5, 8),
        Int.MAX_VALUE to Swiss(15, 9, 6, 8),
    )

    /**
     * The rounds for [players] at [tier], by the policy's tables. The count is
     * taken when registration closes; late entries do not change it.
     *
     * Two gaps the tables leave are filled, and said so: fewer than four players
     * cannot be sanctioned at all (§III.E), so they are given the smallest row;
     * and a Tier 3 or 4 event under 129 players, below where §III.I starts, is
     * given the Tier 1–2 row for its size in one day. For Tier 3 and 4, pass
     * [playersForRounds] when players hold byes.
     */
    fun swiss(tier: Int, players: Int): Swiss {
        val table = if (tier >= 3 && players > 128) TIER_3_4 else TIER_1_2
        return table.first { players <= it.first }.second
    }

    /**
     * §III.I: at Tier 3 and 4 the rounds are counted from the players in Round 1,
     * plus each player holding byes, plus a modifier per such player — 1 for a
     * one-round bye, 3 for two, 7 for three. [byes] maps a bye's length in rounds
     * to how many players hold it. The policy's example: 1,000 in Round 1 and 5
     * with two-round byes count as 1,000 + 5 + 15 = 1,020.
     */
    fun playersForRounds(roundOne: Int, byes: Map<Int, Int> = emptyMap()): Int =
        roundOne + byes.entries.sumOf { (rounds, holders) ->
            val modifier = when (rounds) {
                1 -> 1
                2 -> 3
                3 -> 7
                else -> (1 shl rounds.coerceIn(0, 10)) - 1
            }
            holders * (1 + modifier)
        }

    /**
     * Which record makes the cut, as a guess worth planning around: the Swiss
     * as the policy designs it (§III.H: "one undefeated Duelist after the last
     * Round"), every match won or lost, so after `R` rounds about
     * `players × C(R, k) / 2^R` players have lost `k`. The best record whose
     * players all fit in the cut makes it; the next one is on tie-breakers.
     * Draws, double losses at time and drops move this in real events —
     * usually in your favour, since they thin the records above you.
     */
    fun cutRecord(swiss: Swiss, players: Int): String {
        val r = swiss.rounds
        val cut = swiss.topCut
        if (cut == 0) return "No top cut: the best record after $r rounds wins"
        if (players <= cut) return "Everyone makes Top $cut"
        var cumulative = 0.0
        var safe = -1
        var choose = 1.0 // C(r, k)
        val scale = players / 2.0.pow(r)
        for (k in 0..r) {
            if (k > 0) choose = choose * (r - k + 1) / k
            val next = cumulative + scale * choose
            if (next > cut + 0.5) break
            cumulative = next
            safe = k
        }
        return when {
            safe < 0 -> "Only some ${r}-0s make Top $cut, on tie-breakers"
            safe >= r -> "Everyone makes Top $cut"
            cut - cumulative < 0.5 -> "${r - safe}-$safe or better makes Top $cut"
            else -> "${r - safe}-$safe or better makes Top $cut; some ${r - safe - 1}-${safe + 1}s make it on tie-breakers"
        }
    }

    private fun Double.pow(n: Int): Double {
        var v = 1.0
        repeat(n) { v *= this }
        return v
    }

    /** The rules a player most often gets wrong on the day, in plain words. */
    val rules: List<String> = listOf(
        // §III.D
        "Matches are best of three: the first to win two duels wins the match.",
        // §IV.F
        "The loser of a duel decides who goes first in the next one.",
        // §V.B
        "At 50 minutes the match is over: if no one has won two duels, both players take a loss.",
        // §VII.C
        "Siding is card for card between duels, never before Game 1, counted in view of your opponent, in under 3 minutes.",
        // §IV.J
        "No notes may be read during a match, between games included — not even a siding plan.",
        // §VII.C
        "After each round, take the side cards out and restore your deck to its registered list.",
        // §V.A–B
        "The winner reports the result, within 5 minutes of the end of the round.",
        // §IV.G
        "From Tier 2, sleeves are required, identical across Main and Side Deck.",
        // §IV.D
        "From Tier 2, a decklist is required; once it is handed in it cannot be changed.",
    )
}
