package com.kaiharimoto.mastertool.core.shootout.model

/**
 * The ways a game is started that the shootout keeps apart (Phase S §1½, kai's decision): the deck on its own,
 * first or second; and per matchup, game one or sided, first or second.
 *
 * The deck alone and a matchup are separate models, never averaged together; a model instance names the strata it
 * holds ([ModelSpec.strata]).
 */
enum class Stratum(val goingFirst: Boolean, val sided: Boolean, val alone: Boolean) {
    ALONE_FIRST(true, false, true),
    ALONE_SECOND(false, false, true),
    G1_FIRST(true, false, false),
    G1_SECOND(false, false, false),
    SIDED_FIRST(true, true, false),
    SIDED_SECOND(false, true, false),
    ;

    /** The player going first opens five; the player going second has six by their first turn. */
    val handSize: Int get() = if (goingFirst) 5 else 6

    /** The other seat's hand: six when you go first, five when they do. */
    val opponentHandSize: Int get() = if (goingFirst) 6 else 5
}

/**
 * The five-point answer to a matchup trial (Phase S §1), from worst to best so its order is the value's order.
 * Each answer names a band of win chance, and [score] is the band's middle, used where an answer must be read as a
 * number (the real-world rate's check, §3).
 */
enum class Answer(val score: Double) {
    CLEAR_LOSS(0.1),
    LEAN_LOSS(0.3),
    COIN_FLIP(0.5),
    LEAN_WIN(0.7),
    CLEAR_WIN(0.9),
}

/**
 * One answered trial (Phase S §1). The trials are the log; the fit is read from them, so a trial is never changed,
 * only added.
 *
 * [judge] is who answered: 0 the person blind. Each judge has their own fitted noise and lean, which is the seam
 * for Ai's answers and for the person's answers after seeing Ai's (§6½, Dawid–Skene).
 */
sealed interface Trial {
    val stratum: Stratum
    val opponent: Hand?
    val judge: Int
}

/**
 * A hand (and, in a matchup, the opponent's) answered on the five-point scale. [plain] marks a hand dealt as a
 * plain shuffle rather than chosen: those are the trials the real-world rate is checked against (Phase S §3).
 */
data class Rated(
    val hand: Hand,
    override val opponent: Hand?,
    override val stratum: Stratum,
    val answer: Answer,
    override val judge: Int = 0,
    val plain: Boolean = false,
) : Trial

/** Two of your hands against the same opponent hand and turn, and which one the judge would rather open. */
data class Compared(
    val left: Hand,
    val right: Hand,
    override val opponent: Hand?,
    override val stratum: Stratum,
    val leftPreferred: Boolean,
    override val judge: Int = 0,
) : Trial
