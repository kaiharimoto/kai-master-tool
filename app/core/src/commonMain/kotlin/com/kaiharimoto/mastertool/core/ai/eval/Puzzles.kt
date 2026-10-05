package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/**
 * Duel puzzles as an evaluation set (Phase C stage 3, `docs/phases/C.md` §5): a position with a known goal, played by Ai
 * on a table of its own through the duel tools it plays kai with (`duel_state`, `duel_moves`, `duel_act`), and graded
 * **by the table** — the goal is a check on life points, zones and cards after Ai's moves ([PuzzleGoal.met]), never a
 * model's say-so.
 *
 * The duel engine is manual: any card may be moved anywhere and life points typed. A puzzle is therefore played under a
 * referee ([PuzzleReferee]) that admits only what the game's rules allow in a turn — a Normal Summon or Set with its
 * Tributes, a position change, a Flip Summon, the phases forward, an attack once a monster and directly only at an empty
 * field — works out battle itself by the printed numbers ([com.kaiharimoto.mastertool.core.duel.DuelBattle]), and
 * resolves the puzzle's Spells as their text says, written here as table moves ([PuzzleEffect]). Life points and cards
 * move only that way, so a goal can never be met by a manual move a real duel forbids. Every monster is a Normal Monster
 * (no text to automate), the opponent has no hand and nothing set, and never responds.
 */
data class Puzzle(
    val id: String,
    val title: String,
    /** What the solver is asked to bring about, in words, and the check the table makes. */
    val goal: PuzzleGoal,
    val setup: PuzzleSetup,
    /** The most moves (duel_act ops that change the table) a solution may take. */
    val budget: Int,
    /** A line that meets the goal, as `duel_act` takes it: the code test proves it. */
    val solution: List<String>,
    /** A tempting line that does not: the code test proves it fails. */
    val wrong: List<String>,
    /** Why the puzzle is a puzzle: what the obvious line misses. */
    val why: String,
)

/** The position: both seats' life points, your hand, and each side's monsters (and your Spells) on the field. */
data class PuzzleSetup(
    val yourLp: Int = DuelState.START_LP,
    val theirLp: Int = DuelState.START_LP,
    val hand: List<Int> = emptyList(),
    val yours: List<PuzzleSlot> = emptyList(),
    val theirs: List<PuzzleSlot> = emptyList(),
)

/** A card in a Monster Zone ([index] 0 is m1), in [pos]. */
data class PuzzleSlot(val code: Int, val index: Int, val pos: CardPosition = CardPosition.FACE_UP_ATK)

/** The goal, checked on the table. */
sealed interface PuzzleGoal {
    val words: String

    /** Whether the table [s] meets it, and what it read. */
    fun met(s: DuelState, catalog: DuelCatalog): Pair<Boolean, String>

    /** Your opponent's life points at most [lp] (0: they lose). */
    data class TheirLp(val lp: Int = 0) : PuzzleGoal {
        override val words get() = if (lp == 0) "reduce your opponent to 0 LP this turn" else "leave your opponent at $lp LP or less"
        override fun met(s: DuelState, catalog: DuelCatalog) = (s.seats[THEM].lp <= lp) to "their LP ${s.seats[THEM].lp}"
    }

    /** Your own life points at least [lp]. */
    data class YourLp(val lp: Int) : PuzzleGoal {
        override val words get() = "keep at least $lp LP yourself"
        override fun met(s: DuelState, catalog: DuelCatalog) = (s.seats[YOU].lp >= lp) to "your LP ${s.seats[YOU].lp}"
    }

    /** Your opponent controls no monster. */
    data object TheirFieldClear : PuzzleGoal {
        override val words get() = "leave your opponent with no monster on the field"
        override fun met(s: DuelState, catalog: DuelCatalog): Pair<Boolean, String> {
            val left = monsters(s, THEM)
            return left.isEmpty() to if (left.isEmpty()) "their field clear" else "they still control ${left.joinToString { name(s, it, catalog) }}"
        }
    }

    /** You control a face-up monster named [name] (its passcode [code]). */
    data class YouControl(val code: Int, val name: String) : PuzzleGoal {
        override val words get() = "control $name face-up"
        override fun met(s: DuelState, catalog: DuelCatalog): Pair<Boolean, String> {
            val has = monsters(s, YOU).any { s.cards[it]?.let { c -> c.code == code && c.faceUp } == true }
            return has to if (has) "you control $name" else "no $name on your field"
        }
    }

    /** Every one of [all]. */
    data class All(val all: List<PuzzleGoal>) : PuzzleGoal {
        override val words get() = all.joinToString(", and ") { it.words }
        override fun met(s: DuelState, catalog: DuelCatalog): Pair<Boolean, String> {
            val each = all.map { it.met(s, catalog) }
            return each.all { it.first } to each.joinToString("; ") { it.second }
        }
    }

    companion object {
        const val YOU = 0
        const val THEM = 1

        fun monsters(s: DuelState, seat: Int): List<Int> =
            s.seats[seat].monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat }

        private fun name(s: DuelState, uid: Int, catalog: DuelCatalog) = s.cards[uid]?.let { catalog.info(it.code)?.name } ?: "a monster"
    }
}

/**
 * What a puzzle's Spell does, as the referee resolves it — the card's text written as table moves, for the few cards
 * the set uses; nothing reads card text.
 */
data class PuzzleEffect(val kind: Kind, val them: Int = 0, val you: Int = 0, val text: String) {
    enum class Kind {
        /** Destroy all monsters your opponent controls (Raigeki). */
        DESTROY_THEIRS,
        /** Destroy all monsters on the field (Dark Hole). */
        DESTROY_ALL,
        /** Destroy the 1 face-up monster your opponent controls with the lowest ATK (Fissure). */
        DESTROY_THEIR_LOWEST,
        /** Inflict [them] damage to your opponent, and [you] to you (Ookazi, Hinotama, Tremendous Fire). */
        DAMAGE,
    }
}

/** The cards the puzzles use: Normal Monsters and a few Normal Spells, their printed facts (passcodes as printed). */
object PuzzleCards {
    const val BLUE_EYES = 89631139
    const val DARK_MAGICIAN = 46986414
    const val RED_EYES = 74677422
    const val GAIA = 6368038
    const val SUMMONED_SKULL = 70781052
    const val ALEXANDRITE = 43096270
    const val GEMINI_ELF = 69140098
    const val VORSE_RAIDER = 14898066
    const val LUSTER_DRAGON = 11091375
    const val LA_JINN = 97590747
    const val BATTLE_OX = 5053103
    const val ROGUE_DOLL = 91939608
    const val CELTIC_GUARDIAN = 91152256
    const val FERAL_IMP = 41392891
    const val MYSTICAL_ELF = 15025844
    const val GIANT_SOLDIER = 13039848
    const val BABY_DRAGON = 88819587
    const val MAMMOTH_GRAVEYARD = 40374923
    const val RAIGEKI = 12580477
    const val DARK_HOLE = 53129443
    const val FISSURE = 66788016
    const val OOKAZI = 19523799
    const val TREMENDOUS_FIRE = 46918794

    private fun normal(name: String, level: Int, attribute: String, race: String, atk: Int, def: Int) =
        DuelCardInfo(name, CardKind.MONSTER, atk = atk, def = def, level = level, attribute = attribute, race = race, typeLine = "Normal Monster")

    private fun spell(name: String) = DuelCardInfo(name, CardKind.SPELL, sub = "Normal")

    val info: Map<Int, DuelCardInfo> = mapOf(
        BLUE_EYES to normal("Blue-Eyes White Dragon", 8, "LIGHT", "Dragon", 3000, 2500),
        DARK_MAGICIAN to normal("Dark Magician", 7, "DARK", "Spellcaster", 2500, 2100),
        RED_EYES to normal("Red-Eyes Black Dragon", 7, "DARK", "Dragon", 2400, 2000),
        GAIA to normal("Gaia The Fierce Knight", 7, "EARTH", "Warrior", 2300, 2100),
        SUMMONED_SKULL to normal("Summoned Skull", 6, "DARK", "Fiend", 2500, 1200),
        ALEXANDRITE to normal("Alexandrite Dragon", 4, "LIGHT", "Dragon", 2000, 100),
        GEMINI_ELF to normal("Gemini Elf", 4, "EARTH", "Spellcaster", 1900, 900),
        VORSE_RAIDER to normal("Vorse Raider", 4, "DARK", "Beast-Warrior", 1900, 1200),
        LUSTER_DRAGON to normal("Luster Dragon", 4, "WIND", "Dragon", 1900, 1600),
        LA_JINN to normal("La Jinn the Mystical Genie of the Lamp", 4, "DARK", "Fiend", 1800, 1000),
        BATTLE_OX to normal("Battle Ox", 4, "EARTH", "Beast-Warrior", 1700, 1000),
        ROGUE_DOLL to normal("Rogue Doll", 4, "LIGHT", "Spellcaster", 1600, 1000),
        CELTIC_GUARDIAN to normal("Celtic Guardian", 4, "EARTH", "Warrior", 1400, 1200),
        FERAL_IMP to normal("Feral Imp", 4, "DARK", "Fiend", 1300, 1400),
        MYSTICAL_ELF to normal("Mystical Elf", 4, "LIGHT", "Spellcaster", 800, 2000),
        GIANT_SOLDIER to normal("Giant Soldier of Stone", 3, "EARTH", "Rock", 1300, 2000),
        BABY_DRAGON to normal("Baby Dragon", 3, "WIND", "Dragon", 1200, 700),
        MAMMOTH_GRAVEYARD to normal("Mammoth Graveyard", 3, "EARTH", "Dinosaur", 1200, 800),
        RAIGEKI to spell("Raigeki"),
        DARK_HOLE to spell("Dark Hole"),
        FISSURE to spell("Fissure"),
        OOKAZI to spell("Ookazi"),
        TREMENDOUS_FIRE to spell("Tremendous Fire"),
    )

    val effects: Map<Int, PuzzleEffect> = mapOf(
        RAIGEKI to PuzzleEffect(PuzzleEffect.Kind.DESTROY_THEIRS, text = "Destroy all monsters your opponent controls."),
        DARK_HOLE to PuzzleEffect(PuzzleEffect.Kind.DESTROY_ALL, text = "Destroy all monsters on the field."),
        FISSURE to PuzzleEffect(PuzzleEffect.Kind.DESTROY_THEIR_LOWEST, text = "Destroy the 1 face-up monster your opponent controls that has the lowest ATK (your choice, if tied)."),
        OOKAZI to PuzzleEffect(PuzzleEffect.Kind.DAMAGE, them = 800, text = "Inflict 800 damage to your opponent."),
        TREMENDOUS_FIRE to PuzzleEffect(PuzzleEffect.Kind.DAMAGE, them = 1000, you = 500, text = "Inflict 1000 damage to your opponent, and 500 damage to you."),
    )

    /** The puzzles' catalog: these cards only, so a grade never depends on the pool. */
    val catalog: DuelCatalog = DuelCatalog { info[it] }

    fun name(code: Int): String = info[code]?.name ?: "#$code"
}

object Puzzles {
    /** Cards in each Deck, never drawn: a seat with no Deck reads oddly, and drawing is not a puzzle's move. */
    const val DECK = 10
    /** The turn a puzzle is played in: past the first, so the Battle Phase is open. */
    const val TURN = 3

    /** The puzzle's table: dealt, then laid out by the table's own moves, behind undo's reach, in your Main Phase 1. */
    fun start(p: Puzzle, seed: Long = 1L): DuelGame {
        val s = p.setup
        val mine = s.hand + s.yours.map { it.code }
        val header = DuelHeader(
            id = "puzzle-${p.id}",
            seed = seed,
            seats = listOf(
                SeatSetup("You", main = mine + List(DECK) { PuzzleCards.MAMMOTH_GRAVEYARD }, deckName = "Puzzle"),
                SeatSetup("Opponent", main = s.theirs.map { it.code } + List(DECK) { PuzzleCards.MAMMOTH_GRAVEYARD }, deckName = "Puzzle"),
            ),
            handSize = 0,
        )
        fun uid(seat: Int, n: Int) = 1 + seat * DuelState.SEAT_UIDS + n
        val lay = buildList<DuelAction> {
            // Two turns gone by: it is your turn 3, so the Battle Phase is open.
            add(DuelAction.EndTurn)
            add(DuelAction.EndTurn)
            s.hand.indices.forEach { add(DuelAction.Move(uid(0, it), Place.Pile(0, PileKind.HAND), how = "place")) }
            s.yours.forEachIndexed { k, slot -> add(DuelAction.Move(uid(0, s.hand.size + k), Place.Zone(0, ZoneKind.MONSTER, slot.index), slot.pos, "place")) }
            s.theirs.forEachIndexed { k, slot -> add(DuelAction.Move(uid(1, k), Place.Zone(1, ZoneKind.MONSTER, slot.index), slot.pos, "place")) }
            add(DuelAction.Lp(0, set = s.yourLp))
            add(DuelAction.Lp(1, set = s.theirLp))
            add(DuelAction.Phase(DuelPhase.MAIN1))
        }
        val dealt = DuelGame(header, emptyList(), 0, DuelSetup.initial(header), 0)
        val laid = dealt.act(lay, null)
        check(laid.ok) { "puzzle ${p.id} does not lay out: ${laid.problem}" }
        return laid.game.copy(floor = laid.game.cursor)
    }

    private fun atk(code: Int, index: Int) = PuzzleSlot(code, index, CardPosition.FACE_UP_ATK)
    private fun def(code: Int, index: Int) = PuzzleSlot(code, index, CardPosition.FACE_UP_DEF)
    private fun set(code: Int, index: Int) = PuzzleSlot(code, index, CardPosition.FACE_DOWN_DEF)

    private val lethal = PuzzleGoal.TheirLp(0)

    /** Every puzzle, in the order the set asks them. */
    val all: List<Puzzle> by lazy {
        with(PuzzleCards) {
            listOf(
                Puzzle(
                    "p01", "Two swings", lethal,
                    PuzzleSetup(theirLp = 3800, yours = listOf(atk(GEMINI_ELF, 0), atk(VORSE_RAIDER, 1))),
                    budget = 6,
                    solution = listOf("bp", "a m1 direct", "a m2 direct"),
                    wrong = listOf("bp", "a m1 direct"),
                    why = "The warm-up: both monsters attack directly. Doing nothing, or one attack, leaves them standing.",
                ),
                Puzzle(
                    "p02", "Clear the way", lethal,
                    PuzzleSetup(theirLp = 2000, yours = listOf(atk(ALEXANDRITE, 0), atk(BATTLE_OX, 1)), theirs = listOf(atk(CELTIC_GUARDIAN, 0))),
                    budget = 6,
                    solution = listOf("bp", "a m1 om1", "a m2 direct"),
                    wrong = listOf("bp", "a m2 om1"),
                    why = "No direct attack while they control a monster: battle first (600), then the other monster attacks directly (1700).",
                ),
                Puzzle(
                    "p03", "Summon, then swing", lethal,
                    PuzzleSetup(theirLp = 3700, hand = listOf(LA_JINN), yours = listOf(atk(VORSE_RAIDER, 0))),
                    budget = 6,
                    solution = listOf("s h1 m2", "bp", "a m1 direct", "a m2 direct"),
                    wrong = listOf("bp", "a m1 direct"),
                    why = "The Normal Summon is the missing 1800: 1900 + 1800 = 3700.",
                ),
                Puzzle(
                    "p04", "Tribute for the finisher", lethal,
                    PuzzleSetup(theirLp = 2500, hand = listOf(SUMMONED_SKULL), yours = listOf(atk(CELTIC_GUARDIAN, 0))),
                    budget = 6,
                    solution = listOf("g m1", "s h1 m1", "bp", "a m1 direct"),
                    wrong = listOf("bp", "a m1 direct"),
                    why = "Celtic Guardian's 1400 is short; Tributed for Summoned Skull (Level 6, one Tribute), 2500 is exact.",
                ),
                Puzzle(
                    "p05", "Raigeki opens the way", lethal,
                    PuzzleSetup(
                        theirLp = 4200, hand = listOf(RAIGEKI), yours = listOf(atk(GAIA, 0), atk(LUSTER_DRAGON, 1)),
                        theirs = listOf(def(MYSTICAL_ELF, 0), def(GIANT_SOLDIER, 1)),
                    ),
                    budget = 6,
                    solution = listOf("activate raigeki", "bp", "a m1 direct", "a m2 direct"),
                    wrong = listOf("bp", "a m1 om1", "a m2 om2"),
                    why = "Two walls of 2000 DEF: attacking them deals nothing. Raigeki clears both and 2300 + 1900 is exact.",
                ),
                Puzzle(
                    "p06", "Burn the rest", lethal,
                    PuzzleSetup(theirLp = 2700, hand = listOf(OOKAZI), yours = listOf(atk(VORSE_RAIDER, 0))),
                    budget = 6,
                    solution = listOf("bp", "a m1 direct", "m2", "activate ookazi"),
                    wrong = listOf("bp", "a m1 direct"),
                    why = "1900 from the attack and 800 from Ookazi; either order, but the Spell is a Main Phase card.",
                ),
                Puzzle(
                    "p07", "8000 in one turn", lethal,
                    PuzzleSetup(
                        theirLp = 8000, hand = listOf(RAIGEKI, OOKAZI),
                        yours = listOf(atk(BLUE_EYES, 0), atk(GAIA, 1), atk(VORSE_RAIDER, 2)), theirs = listOf(def(MYSTICAL_ELF, 0)),
                    ),
                    budget = 8,
                    solution = listOf("activate raigeki", "activate ookazi", "bp", "a m1 direct", "a m2 direct", "a m3 direct"),
                    wrong = listOf("activate ookazi", "bp", "a m1 om1", "a m2 direct", "a m3 direct"),
                    why = "Exactly 8000: 800 + 3000 + 2300 + 1900, which needs Raigeki for the wall rather than an attack into it.",
                ),
                Puzzle(
                    "p08", "Dark Hole first", lethal,
                    PuzzleSetup(theirLp = 1900, hand = listOf(DARK_HOLE, VORSE_RAIDER), yours = listOf(atk(CELTIC_GUARDIAN, 0)), theirs = listOf(atk(BLUE_EYES, 0))),
                    budget = 6,
                    solution = listOf("activate dark hole", "s h1 m1", "bp", "a m1 direct"),
                    wrong = listOf("s h2 m2", "activate dark hole", "bp"),
                    why = "Dark Hole takes your own monsters too: clear the field first, then summon the attacker.",
                ),
                Puzzle(
                    "p09", "The lowest ATK", lethal,
                    PuzzleSetup(
                        theirLp = 2500, hand = listOf(FISSURE), yours = listOf(atk(GEMINI_ELF, 0), atk(VORSE_RAIDER, 1)),
                        theirs = listOf(def(MYSTICAL_ELF, 0), atk(FERAL_IMP, 1)),
                    ),
                    budget = 6,
                    solution = listOf("activate fissure", "bp", "a m1 om2", "a m2 direct"),
                    wrong = listOf("bp", "a m1 om2", "a m2 om1"),
                    why = "Fissure reads ATK, not DEF: it takes the 2000-DEF wall (800 ATK), and Feral Imp falls in battle (600) before 1900 direct.",
                ),
                Puzzle(
                    "p10", "Stand up", lethal,
                    PuzzleSetup(theirLp = 2300, yours = listOf(def(GAIA, 0))),
                    budget = 5,
                    solution = listOf("p m1", "bp", "a m1 direct"),
                    wrong = listOf("bp"),
                    why = "A monster in Defense Position cannot attack; one set on an earlier turn may change position now.",
                ),
                Puzzle(
                    "p11", "Flip, summon, swing", lethal,
                    PuzzleSetup(theirLp = 3700, hand = listOf(LA_JINN), yours = listOf(set(VORSE_RAIDER, 0))),
                    budget = 7,
                    solution = listOf("s m1", "s h1 m2", "bp", "a m1 direct", "a m2 direct"),
                    wrong = listOf("s h1 m2", "bp", "a m2 direct"),
                    why = "A Flip Summon is not the turn's Normal Summon: both monsters can join the attack.",
                ),
                Puzzle(
                    "p12", "Win without falling",
                    PuzzleGoal.All(listOf(lethal, PuzzleGoal.YourLp(1))),
                    PuzzleSetup(yourLp = 500, theirLp = 1800, hand = listOf(TREMENDOUS_FIRE, OOKAZI), yours = listOf(atk(BATTLE_OX, 0))),
                    budget = 6,
                    solution = listOf("bp", "a m1 direct", "m2", "activate ookazi"),
                    wrong = listOf("bp", "a m1 direct", "m2", "activate tremendous fire"),
                    why = "Tremendous Fire burns you for 500 too: at 500 LP it is a draw, not a win. Ookazi finishes alone.",
                ),
                Puzzle(
                    "p13", "Clear their field", PuzzleGoal.TheirFieldClear,
                    PuzzleSetup(
                        yours = listOf(atk(ALEXANDRITE, 0), atk(BATTLE_OX, 1), atk(CELTIC_GUARDIAN, 2)),
                        theirs = listOf(atk(LUSTER_DRAGON, 0), atk(ROGUE_DOLL, 1), atk(FERAL_IMP, 2)),
                    ),
                    budget = 6,
                    solution = listOf("bp", "a m1 om1", "a m2 om2", "a m3 om3"),
                    wrong = listOf("bp", "a m1 om3", "a m2 om2", "a m3 om1"),
                    why = "Only Alexandrite beats Luster Dragon, and only Battle Ox can take Rogue Doll: the strongest takes the strongest.",
                ),
                Puzzle(
                    "p14", "End with Blue-Eyes",
                    PuzzleGoal.All(listOf(PuzzleGoal.YouControl(BLUE_EYES, "Blue-Eyes White Dragon"), PuzzleGoal.TheirFieldClear)),
                    PuzzleSetup(hand = listOf(BLUE_EYES, DARK_HOLE), yours = listOf(atk(CELTIC_GUARDIAN, 0), atk(FERAL_IMP, 1)), theirs = listOf(atk(RED_EYES, 0))),
                    budget = 7,
                    solution = listOf("g m1", "g m2", "s h1 m1", "bp", "a m1 om1"),
                    wrong = listOf("activate dark hole"),
                    why = "Dark Hole clears their field but leaves nothing to Tribute; two Tributes for Blue-Eyes, and it wins the battle.",
                ),
                Puzzle(
                    "p15", "A trade opens the way", lethal,
                    PuzzleSetup(theirLp = 1900, yours = listOf(atk(DARK_MAGICIAN, 0), atk(GEMINI_ELF, 1)), theirs = listOf(atk(SUMMONED_SKULL, 0))),
                    budget = 5,
                    solution = listOf("bp", "a m1 om1", "a m2 direct"),
                    wrong = listOf("bp", "a m2 om1"),
                    why = "2500 into 2500 destroys both and deals nothing, but it empties their field for Gemini Elf's 1900.",
                ),
                Puzzle(
                    "p16", "Which two to Tribute", lethal,
                    PuzzleSetup(theirLp = 4400, hand = listOf(BLUE_EYES), yours = listOf(atk(CELTIC_GUARDIAN, 0), atk(FERAL_IMP, 1), atk(BABY_DRAGON, 2))),
                    budget = 7,
                    solution = listOf("g m2", "g m3", "s h1 m2", "bp", "a m1 direct", "a m2 direct"),
                    wrong = listOf("g m1", "g m2", "s h1 m1", "bp", "a m1 direct", "a m3 direct"),
                    why = "Three attackers make 3900; Blue-Eyes takes two of them, and keeping the strongest (1400) is 4400 exactly.",
                ),
                Puzzle(
                    "p17", "Raigeki, not Dark Hole", lethal,
                    PuzzleSetup(
                        theirLp = 3800, hand = listOf(RAIGEKI, DARK_HOLE), yours = listOf(atk(VORSE_RAIDER, 0), atk(GEMINI_ELF, 1)),
                        theirs = listOf(atk(BLUE_EYES, 0), atk(DARK_MAGICIAN, 1)),
                    ),
                    budget = 5,
                    solution = listOf("activate raigeki", "bp", "a m1 direct", "a m2 direct"),
                    wrong = listOf("activate dark hole", "bp"),
                    why = "Both clear their field; only one leaves your attackers standing.",
                ),
            )
        }
    }

    fun byId(id: String): Puzzle? = all.firstOrNull { it.id == id }

    /** What a puzzle asks, word for word: the goal, the budget, the Spells in hand; the table is read with the tools. */
    fun prompt(p: Puzzle): String = buildString {
        append("Puzzle ${p.id}, “${p.title}”: ${p.goal.words}. You have at most ${p.budget} moves. ")
        val spells = p.setup.hand.distinct().mapNotNull { c -> PuzzleCards.effects[c]?.let { "${PuzzleCards.name(c)}: ${it.text}" } }
        if (spells.isNotEmpty()) append("Your Spells do what they say: ${spells.joinToString(" ")} ")
        append("Read the table with duel_state, see the legal moves with duel_moves, and play with duel_act. When you have finished, reply DONE.")
    }

    /** The set Test scores runs: each puzzle an item, graded by playing it ([PuzzleTable]). */
    fun set(): EvalSet = EvalSet(
        EvalSets.PUZZLES,
        "Duel puzzles",
        "${all.size} positions with a known goal, played on a table of their own under a referee and checked on the table: " +
            "life points, zones and cards after the moves.",
        all.map { p -> EvalItem(p.id, prompt(p), Grader.Puzzle(p.id), "written for the set: ${p.why}") },
    )
}
