package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand

/**
 * Command mode's world (1.0.87): cards with ATK and DEF so a duel typed from start to end can be won, and names that
 * trip a first-word grammar ("Battle Fader", "Draw Muscle", "Dice Jar", "Effect Veiler", "Token Collector").
 */
object CommandFixtures {
    const val BEWD = 301
    const val DM = 302
    const val KURIBOH = 303
    const val POT = 304
    const val MIRROR = 305
    const val ASH = 306
    const val SNAKE = 307
    const val ZEUS = 308
    const val FADER = 309
    const val MUSCLE = 310
    const val JAR = 311
    const val VEILER = 312
    const val COLLECTOR = 313
    const val SWORDS = 314
    const val DROLL = 315
    const val GATEWAY = 316
    const val CHAOS = 317

    val catalog = DuelCatalog { code ->
        when (code) {
            BEWD -> DuelCardInfo("Blue-Eyes White Dragon", CardKind.MONSTER, atk = 3000, def = 2500)
            DM -> DuelCardInfo("Dark Magician", CardKind.MONSTER, atk = 2500, def = 2100)
            KURIBOH -> DuelCardInfo("Kuriboh", CardKind.MONSTER, atk = 300, def = 200)
            POT -> DuelCardInfo("Pot of Prosperity", CardKind.SPELL, sub = "Normal")
            MIRROR -> DuelCardInfo("Mirror Force", CardKind.TRAP, sub = "Normal")
            ASH -> DuelCardInfo("Ash Blossom & Joyous Spring", CardKind.MONSTER, atk = 0, def = 1800)
            SNAKE -> DuelCardInfo("Snake-Eye Ash", CardKind.MONSTER, atk = 800, def = 1000)
            ZEUS -> DuelCardInfo("Divine Arsenal AA-ZEUS - Sky Thunder", CardKind.EXTRA_MONSTER, atk = 3000, def = 3000)
            FADER -> DuelCardInfo("Battle Fader", CardKind.MONSTER, atk = 0, def = 0)
            MUSCLE -> DuelCardInfo("Draw Muscle", CardKind.SPELL, sub = "Normal")
            JAR -> DuelCardInfo("Dice Jar", CardKind.MONSTER, atk = 200, def = 300)
            VEILER -> DuelCardInfo("Effect Veiler", CardKind.MONSTER, atk = 0, def = 0)
            COLLECTOR -> DuelCardInfo("Token Collector", CardKind.MONSTER, atk = 0, def = 0)
            SWORDS -> DuelCardInfo("Swords of Revealing Light", CardKind.SPELL, sub = "Normal")
            DROLL -> DuelCardInfo("Droll & Lock Bird", CardKind.MONSTER, atk = 0, def = 0)
            GATEWAY -> DuelCardInfo("Gateway to Chaos", CardKind.SPELL, sub = "Field")
            CHAOS -> DuelCardInfo("Chaos Angel", CardKind.EXTRA_MONSTER, atk = 3500, def = 2800)
            else -> null
        }
    }

    fun header(solo: Boolean = false, kai: List<Int> = KAI, rival: List<Int> = RIVAL) = DuelHeader(
        id = "cmd",
        seed = 11L,
        seats = listOf(
            SeatSetup("Kai", main = kai, extra = listOf(ZEUS, CHAOS)),
            SeatSetup("Rival", main = rival, extra = listOf(ZEUS)),
        ),
        solo = solo,
    )

    /** Kai's Deck, top first: three Blue-Eyes are drawn by turn 5. */
    val KAI = listOf(BEWD, BEWD, POT, MIRROR, ASH, BEWD, BEWD, SNAKE, DROLL, FADER) + List(30) { KURIBOH }

    /** Rival's Deck, top first. */
    val RIVAL = listOf(KURIBOH, KURIBOH, DM, ASH, MIRROR, DM, VEILER) + List(33) { KURIBOH }

    /** A two-seat table, nothing shuffled, no hands. */
    fun bare(solo: Boolean = false) = DuelSetup.initial(header(solo))

    fun ok(s: DuelState, a: DuelAction, by: Int? = 0): DuelState = DuelFixtures.ok(s, a, by)

    fun play(s: DuelState, vararg actions: DuelAction): DuelState = actions.fold(s) { st, a -> ok(st, a, null) }

    fun uid(seat: Int, n: Int) = DuelFixtures.uid(seat, n)

    /**
     * A table in Kai's Battle Phase: Kai holds h1 Blue-Eyes, h2 Blue-Eyes, h3 Pot of Prosperity, h4 Ash Blossom, h5
     * Battle Fader; a face-up Blue-Eyes in M1 with a material (Snake-Eye Ash) and a counter, a set Mirror Force in S1,
     * Zeus in E1, Droll in the GY. Rival: a face-up Dark Magician in M1, a set Mirror Force in S1, a Kuriboh in its GY,
     * and Kuriboh, Kuriboh, Dark Magician, Ash Blossom in hand.
     */
    fun battle(): DuelState {
        var s = bare()
        s = play(s, DuelAction.Draw(0, 5), DuelAction.Draw(1, 5))
        val bewd = uid(0, 5)
        val mirror = uid(0, 3)
        s = play(
            s,
            DuelAction.Move(uid(0, 9), Place.Pile(0, PileKind.HAND)), // Battle Fader, a 6th card in hand
            DuelAction.Move(bewd, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"),
            DuelAction.Move(uid(0, 7), Place.Under(bewd), how = "attach"),
            DuelAction.Counter(bewd, 1),
            DuelAction.Move(mirror, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"),
            DuelAction.Move(uid(0, 40), Place.Zone(0, ZoneKind.EMZ, 0), CardPosition.FACE_UP_ATK, "special"),
            DuelAction.Move(uid(0, 8), Place.Pile(0, PileKind.GY), how = "send"),
            DuelAction.Move(uid(1, 5), Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"),
            DuelAction.Move(uid(1, 4), Place.Zone(1, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"),
            DuelAction.Move(uid(1, 7), Place.Pile(1, PileKind.GY), how = "send"),
            DuelAction.Phase(DuelPhase.MAIN1),
            DuelAction.Phase(DuelPhase.BATTLE),
        )
        return s
    }

    fun parse(text: String, s: DuelState, seat: Int = 0) = DuelCommand.parse(text, s, seat, catalog)

    fun actions(text: String, s: DuelState, seat: Int = 0): List<DuelAction> = when (val p = parse(text, s, seat)) {
        is DuelCommand.Parsed.Actions -> p.actions
        is DuelCommand.Parsed.Many -> p.parts.flatMap { it.actions }
        else -> error("“$text” is not a move: $p")
    }

    fun problem(text: String, s: DuelState, seat: Int = 0): DuelCommand.Parsed.Problem =
        parse(text, s, seat) as? DuelCommand.Parsed.Problem ?: error("“$text” was not refused: ${parse(text, s, seat)}")
}
