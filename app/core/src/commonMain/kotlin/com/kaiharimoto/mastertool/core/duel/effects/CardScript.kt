package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Lock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/*
 * The effect vocabulary (Phase D, `docs/phases/D.md` §2.2): what a card does, as data in our own small words. Nothing here
 * is Konami's card text or another engine's script; a script is written from a card's meaning and read back in plain
 * sentences. Ai writes a script only for a card the person asked for (§3.1), as `lib/effects/<passcode>.js`, which builds
 * this data; the engine (`FxEngine`) reads it and plays each activation as ordinary `DuelAction`s.
 *
 * Forgiving to read: an op, a filter, a condition, a procedure, a once-per-turn rule or a number a newer build wrote is
 * kept as its `Unknown` and written back as it came, as `DuelAction.Unknown` is ([FxCodec]); a whole script of a newer
 * vocabulary is never decoded at all ([FxRead.Newer]). An effect that holds an `Unknown` anywhere is never played.
 */

/** The vocabulary's versions (D.md §2.2, §4.4). */
object FxVocab {
    /** The words a script may use. A script whose `vocab` is higher is kept unread ([FxRead.Newer]). */
    const val VERSION = 1

    /** The engine's own version: a verdict is bound to it (§4.4), so an engine change re-runs every test. */
    const val ENGINE = 1

    /**
     * Passcodes reserved for fictional reference cards (the tests' scripts, the rulings set, part C's planted errors):
     * nine digits, so never a Konami passcode (always eight or fewer), and unused by YGOPRODeck's pool when chosen
     * (14,769 passcodes and alternates checked, Oct 2026; its own placeholders sit at 100…, 101…, 111…, 149…, 300… and
     * 501… million). D.md's example 90000001 sits in a window that holds a real card (90000652), which is why not there.
     */
    val RESERVED: IntRange = 900_000_000..900_000_999

    fun reserved(code: Int): Boolean = code in RESERVED
}

/**
 * One card's effects. [card] is its canonical passcode ([com.kaiharimoto.mastertool.core.model.CardIdentity]): every
 * printing reads this one script. The card's facts (Level, Attribute, Link arrows…) are never here: they come from the
 * pool ([FxFacts]), so a script cannot claim a Level the card does not have.
 */
@Serializable
data class CardScript(
    val card: Int,
    /** The vocabulary it was written in; a newer one is kept unread, as `DuelAction.Unknown` is. */
    val vocab: Int = FxVocab.VERSION,
    /** As the pool has it, for reading. */
    val name: String,
    /** 12 hex of the SHA-256 of the printed text it was written from: errata shows when the pool's text moves on. */
    val text: String = "",
    /** "Always treated as an X card": names a [Filter.NameHas] honours. */
    val alsoNamed: List<String> = emptyList(),
    val summon: SummonRule? = null,
    val effects: List<Effect> = emptyList(),
    /** What the vocabulary cannot say, in Ai's words. */
    val unsupported: List<String> = emptyList(),
    val notes: String = "",
) {
    fun effect(id: String): Effect? = effects.firstOrNull { it.id == id }
}

/** One effect of a card. [id] ("e1"…) is stable: once-per-turn, tests and tags name it. */
@Serializable
data class Effect(
    val id: String,
    /** A short name for the table's list: "Search", "Bounce", "Revive". */
    val label: String = "",
    val kind: Kind,
    /** Where the card is when the effect is used. */
    val from: Set<Where> = emptySet(),
    /** [Kind.TRIGGER] only. */
    val trigger: Trigger? = null,
    /** [Kind.QUICK]: what it answers, when it answers something. */
    val respond: Respond? = null,
    val opt: ReadOpt? = null,
    val condition: ReadCond? = null,
    val cost: List<Step> = emptyList(),
    /** Chosen as it is activated, checked again as it resolves. */
    val targets: List<Pick> = emptyList(),
    val does: List<Step> = emptyList(),
    /**
     * "You cannot … the turn you activate this": a condition on activating it, not part of what it does (OCG FAQ, Rage with
     * Eyes of Blue, YGOrg's April 2025 rulings update). It binds from the activation for the rest of the turn, is lifted
     * when the activation is negated, and holds when only the effect is negated; and it cannot be activated after its
     * seat has done what it forbids this turn. On a [Kind.CONTINUOUS] effect, what it forbids while the card is face-up.
     * A lock that starts only once the effect resolves ("for the rest of this turn after this effect resolves") is an
     * [Op.Restrict] step of [does] instead, and is not applied when the activation or the effect is negated (FAQ 23140).
     */
    val leaves: List<Restriction> = emptyList(),
    /**
     * A Trap, or a set Quick-Play Spell, that may be activated the turn it was Set (D.md §2.4: "unless the script's flag
     * allows it").
     */
    val sameTurn: Boolean = false,
)

@Serializable
enum class Kind {
    /** Spell speed 1, your Main Phase, nothing pending. */
    IGNITION,
    /** Spell speed 1, after an event ([Trigger]). */
    TRIGGER,
    /** Spell speed 2, any time its condition allows. */
    QUICK,
    /** A Spell's or Trap's own activation: speed from its kind (Quick-Play 2, Trap 2, Counter Trap 3, others 1). */
    ACTIVATION,
    /** Not activated; in the first cut only the restrictions it applies while it is face-up. */
    CONTINUOUS,
}

/** Where a card is, for an effect's [Effect.from], a trigger's [On.from] and an inherent summon's [Proc.Inherent.from]. */
@Serializable
enum class Where { HAND, DECK, EXTRA, MONSTER_ZONE, SPELL_ZONE, FIELD_ZONE, GY, BANISHED }

/** A seat relative to the card's controller: its own, the other, or either. */
@Serializable
enum class Rel { YOU, THEM, ANY }

@Serializable
data class Trigger(
    /** What happened, to this card ([self]) or to cards that [about] matches. */
    val on: On,
    val self: Boolean = true,
    val about: ReadFilter? = null,
    /** [Timing.IF] never misses the timing; an optional [Timing.WHEN] can. */
    val timing: Timing = Timing.IF,
    /** "You can". */
    val optional: Boolean = true,
)

@Serializable
enum class Timing { IF, WHEN }

/**
 * An event a trigger waits for. [summon] narrows a summon or a material event to its kinds — empty is any; a list rather
 * than D.md's single kind, so "Normal or Special Summoned" is one trigger (`SUMMONED` with `[NORMAL, SPECIAL]`).
 */
@Serializable
data class On(
    val event: Event,
    val from: Where? = null,
    val cause: Cause? = null,
    val summon: List<ProcKind> = emptyList(),
)

@Serializable
enum class Event {
    SUMMONED, NORMAL_SUMMONED, SPECIAL_SUMMONED, FLIPPED, SENT_TO_GY, DESTROYED, BANISHED,
    ADDED_TO_HAND, DISCARDED, DETACHED, MATERIAL, LEFT_FIELD, DRAWN, STANDBY, END_PHASE, ACTIVATED,
}

/** Why an event happened. [BATTLE] is out of the first cut; [TRIBUTE] is a Tribute for a summon or as a cost. */
@Serializable
enum class Cause { COST, EFFECT, MATERIAL, BATTLE, TRIBUTE }

/** A kind of summon: what a [Proc] makes, what [On.summon] and "must first be" name. */
@Serializable
enum class ProcKind { NORMAL, TRIBUTE, FLIP, SPECIAL, FUSION, SYNCHRO, XYZ, LINK, RITUAL, PENDULUM, INHERENT }

/** What a [Kind.QUICK] effect answers: an activation by [seat], of a card [about] matches, that includes [includes]. */
@Serializable
data class Respond(
    val seat: Rel = Rel.ANY,
    val about: ReadFilter? = null,
    val includes: List<Includes> = emptyList(),
)

/** What an activation includes, read off its steps ([FxIncludes]): "an effect that adds a card from the Deck". */
@Serializable
enum class Includes { SEARCH, SALVAGE, SPECIAL_SUMMON, SEND_FROM_DECK, DRAW, DESTROY, BANISH, RETURN, NEGATE, DISCARD }

/** Once-per-turn rules. **Counted at activation**: a negated activation still uses it. */
@Serializable
sealed interface Opt {
    /**
     * "Only once per turn by name": every copy and every printing; [group] shares one use between effects.
     *
     * [activate]: the text says "you can only **activate**" (as [CARD] always does), not "use": a negated activation is as
     * if it was never activated, so it does not count (YGOrg, "Demystifying Rulings, Part 10: Negation": Pot of Duality
     * against Nekroz Mirror). "Use" wording counts a negated activation.
     */
    @Serializable @SerialName("name")
    data class ByName(val times: Int = 1, val group: String? = null, val activate: Boolean = false) : Opt {
        /** Whether a negated activation gives the use back. */
        val refunds: Boolean get() = activate || group == CARD
    }

    /** "Once per turn": this copy, while it stays where it is (a new instance once it leaves and comes back). */
    @Serializable @SerialName("copy")
    data object PerCopy : Opt

    /** "Once per Duel", by name. */
    @Serializable @SerialName("duel")
    data object PerDuel : Opt

    /** A newer build's rule, kept as written; an effect with one is never used. */
    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : Opt

    companion object {
        /** "You can only activate 1 X per turn": [ByName] with this group. */
        const val CARD = "card"
    }
}

/** A place to choose cards from: a seat relative to the card's controller, and an [Area]. */
@Serializable
data class Spot(val rel: Rel = Rel.YOU, val area: Area)

/**
 * Areas of the table. [MONSTERS] are the Main and Extra Monster Zones; [SPELLS] the Spell & Trap Zones and the Field
 * Zone; [FIELD] all of them; [MATERIALS] the Xyz materials beneath the seat's monsters. A target may be in any of them,
 * either seat's ([Spot.rel] [Rel.ANY]): the field, the GY, banishment, the hand where an effect names it, and materials.
 */
@Serializable
enum class Area { HAND, DECK, EXTRA, GY, BANISHED, MONSTERS, SPELLS, FIELD, MATERIALS }

/**
 * Choosing cards, and saying which cards. [bind] names the picked cards so later steps can refer to them; [ref] points at
 * an earlier binding: [SELF], [TARGETS] or a bound name. [top] takes the top [n] of the Deck (a mill), not a choice.
 *
 * [all] is every card that matches, with no choice ("destroy all monsters they control") — not in D.md's sketch, which
 * has no way to say "all"; the puzzle's Raigeki and Dark Hole need it. A face-down banished card is never a candidate
 * unless [faceDown] says so (D.md §5½: face-up banished only, unless the effect says otherwise).
 */
@Serializable
data class Pick(
    val n: Int = 1,
    val upTo: Boolean = false,
    val from: List<Spot> = emptyList(),
    val where: ReadFilter = Filter.Any,
    /** Who chooses. */
    val who: Rel = Rel.YOU,
    val ref: String? = null,
    val bind: String? = null,
    val top: Boolean = false,
    val all: Boolean = false,
    /** Face-down banished cards may be picked too: only when the effect says so; by default a pick sees face-up ones only. */
    val faceDown: Boolean = false,
) {
    companion object {
        const val SELF = "self"
        const val TARGETS = "targets"
        /** The most cards one pick may take (D.md §3.4). */
        const val MOST = 60
    }
}

/** A closed range, either end open when null: Level 1–4 is `Span(1, 4)`, "2000 or more ATK" `Span(min = 2000)`. */
@Serializable
data class Span(val min: Int? = null, val max: Int? = null) {
    operator fun contains(v: Int): Boolean = (min == null || v >= min) && (max == null || v <= max)
}

@Serializable
enum class CardType { MONSTER, SPELL, TRAP }

/** What a card's frame says it is. [TUNER] and [PENDULUM] sit beside the frame proper. */
@Serializable
enum class CardFrame { NORMAL, EFFECT, FUSION, SYNCHRO, XYZ, LINK, RITUAL, PENDULUM, TUNER, TOKEN }

/** A card's stat, for [Filter.Same], [Filter.Lowest], [Filter.Highest] and [Num.Of]. */
@Serializable
enum class Stat { LEVEL, RANK, LINK, ATK, DEF, NAME, ATTRIBUTE, RACE }

/**
 * Which cards. Judged against a card on the table by `FxFilters`, through the eyes of the game: a face-down card on the
 * field shows only where it is and whose it is.
 */
@Serializable
sealed interface Filter {
    @Serializable @SerialName("any")
    data object Any : Filter

    @Serializable @SerialName("self")
    data object Self : Filter

    @Serializable @SerialName("not-self")
    data object NotSelf : Filter

    /** This card by name: its canonical passcode, so an alternate artwork is the same card. */
    @Serializable @SerialName("name")
    data class Name(val card: Int) : Filter

    /** An archetype by name: the words in its name, or one of its script's [CardScript.alsoNamed]. */
    @Serializable @SerialName("name-has")
    data class NameHas(val word: String) : Filter

    /** A Monster, Spell or Trap, and its kind as printed when [sub] says ("Quick-Play", "Continuous", "Field"…). */
    @Serializable @SerialName("kind")
    data class Kind(val type: CardType, val sub: String? = null) : Filter

    @Serializable @SerialName("frame")
    data class Frame(val frame: CardFrame) : Filter

    @Serializable @SerialName("attribute")
    data class Attribute(val any: Set<CardAttribute>) : Filter

    /** A monster's Type ("Spellcaster", "Dragon"), any of them, ignoring case. */
    @Serializable @SerialName("race")
    data class Race(val any: Set<String>) : Filter

    @Serializable @SerialName("level")
    data class Level(val span: Span) : Filter

    @Serializable @SerialName("rank")
    data class Rank(val span: Span) : Filter

    @Serializable @SerialName("link")
    data class LinkRating(val span: Span) : Filter

    @Serializable @SerialName("atk")
    data class Atk(val span: Span) : Filter

    @Serializable @SerialName("def")
    data class Def(val span: Span) : Filter

    @Serializable @SerialName("face-up")
    data object FaceUp : Filter

    @Serializable @SerialName("face-down")
    data object FaceDown : Filter

    @Serializable @SerialName("controller")
    data class Controller(val rel: Rel) : Filter

    /** "With the same name (Level, Attribute…) as [ref]": any card bound to it. */
    @Serializable @SerialName("same")
    data class Same(val stat: Stat, val ref: String) : Filter

    /** The lowest [stat] among the cards a pick chooses from (ties all match: the chooser picks). */
    @Serializable @SerialName("lowest")
    data class Lowest(val stat: Stat) : Filter

    @Serializable @SerialName("highest")
    data class Highest(val stat: Stat) : Filter

    @Serializable @SerialName("all")
    data class All(val all: List<ReadFilter>) : Filter

    @Serializable @SerialName("any-of")
    data class AnyOf(val any: List<ReadFilter>) : Filter

    @Serializable @SerialName("not")
    data class Not(val not: ReadFilter) : Filter

    /** A card with what was declared under [ref] ([Op.Declare]): that name, Type, Attribute or Level. */
    @Serializable @SerialName("declared")
    data class Declared(val ref: String) : Filter

    /** A newer build's filter, kept as written; it matches nothing. */
    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : Filter
}

/** One step of an effect, and how it joins the one before it. */
@Serializable
data class Step(val op: ReadOp, val link: Join = Join.AND)

/**
 * How a step joins the one before it: whether it happens at the same time, and whether it needs the one before to happen
 * (YGOrg, "Demystifying Rulings, Part 5: Conjunctions"; the red team, D.md §2.3).
 */
@Serializable
enum class Join {
    /**
     * The text's "and": at the same time, and **both or neither** — a run of steps joined by `AND` happens only when every
     * one of them can happen at resolution; otherwise none does.
     */
    AND,
    /** "And if you do": at the same time, and only if the step before happened in full (the step before needs nothing of it). */
    AND_IF_YOU_DO,
    /** "Then": afterwards (a new batch), and only if the step before happened in full. */
    THEN,
    /** "Also, after that": afterwards (a new batch), needing nothing. */
    ALSO,
    /** "Also": at the same time, needing nothing — each part happens if it can. */
    WITH,
}

/** Where a [Op.Move] or a [Op.Return] takes a card. */
@Serializable
enum class Dest { HAND, DECK_TOP, DECK_BOTTOM, DECK_SHUFFLED, EXTRA, GY, BANISHED, MONSTER_ZONE, SPELL_ZONE, FIELD_ZONE }

/** A monster's position as it is summoned: [EITHER] face-up, its controller's choice. */
@Serializable
enum class Pos { ATTACK, DEFENSE, SET, EITHER }

/** A Ritual Summon's Tributes: Levels that equal the monster's exactly, or reach it. */
@Serializable
enum class LevelRule { EQUAL, AT_LEAST }

@Serializable
enum class NegWhat { ACTIVATION, EFFECT }

/** Which chain link a [Op.Negate] means: the one this effect answers (the link below it), or the newest. */
@Serializable
enum class LinkRef { ANSWERED, NEWEST }

/** What a step does. Each is turned into ordinary `DuelAction`s by the step executor (`FxSteps`). */
@Serializable
sealed interface Op {
    /** The general move; the rest are its common shapes, each with its own `how` and event. */
    @Serializable @SerialName("move")
    data class Move(val pick: Pick, val to: Dest, val faceDown: Boolean = false) : Op

    /** To the hand: from the Deck a search, from the GY or banished a salvage. */
    @Serializable @SerialName("add")
    data class Add(val pick: Pick) : Op

    /** To the GY. */
    @Serializable @SerialName("send")
    data class Send(val pick: Pick) : Op

    @Serializable @SerialName("discard")
    data class Discard(val pick: Pick) : Op

    @Serializable @SerialName("destroy")
    data class Destroy(val pick: Pick) : Op

    @Serializable @SerialName("banish")
    data class Banish(val pick: Pick, val faceDown: Boolean = false) : Op

    @Serializable @SerialName("tribute")
    data class Tribute(val pick: Pick) : Op

    /** To the hand, the Deck (top, bottom, shuffled in) or the Extra Deck. */
    @Serializable @SerialName("return")
    data class Return(val pick: Pick, val to: Dest = Dest.HAND) : Op

    @Serializable @SerialName("draw")
    data class Draw(val n: Int = 1, val rel: Rel = Rel.YOU) : Op

    @Serializable @SerialName("shuffle")
    data class Shuffle(val rel: Rel = Rel.YOU, val pile: Area = Area.DECK) : Op

    @Serializable @SerialName("reveal")
    data class Reveal(val pick: Pick) : Op

    /** From any place, into a zone the rules allow. */
    @Serializable @SerialName("special")
    data class SpecialSummon(val pick: Pick, val pos: Pos = Pos.EITHER) : Op

    /** A Fusion Summon by effect: a Fusion Monster [fusion] matches, its materials from [materialsFrom]. */
    @Serializable @SerialName("fusion")
    data class FusionSummon(val fusion: ReadFilter, val materialsFrom: List<Spot> = listOf(Spot(Rel.YOU, Area.HAND), Spot(Rel.YOU, Area.MONSTERS))) : Op

    /** A Ritual Summon by effect: [ritual] from [from], Tributes from [tributesFrom] whose Levels meet its own by [levels]. */
    @Serializable @SerialName("ritual")
    data class RitualSummon(
        val ritual: ReadFilter,
        val tributesFrom: List<Spot> = listOf(Spot(Rel.YOU, Area.HAND), Spot(Rel.YOU, Area.MONSTERS)),
        val levels: LevelRule = LevelRule.AT_LEAST,
        val from: List<Spot> = listOf(Spot(Rel.YOU, Area.HAND)),
    ) : Op

    /** "… then Synchro Summon using …": a Synchro Summon made by an effect, by the monster's own procedure. */
    @Serializable @SerialName("synchro")
    data class SynchroSummon(val f: ReadFilter = Filter.Any) : Op

    @Serializable @SerialName("xyz")
    data class XyzSummon(val f: ReadFilter = Filter.Any) : Op

    @Serializable @SerialName("link")
    data class LinkSummon(val f: ReadFilter = Filter.Any) : Op

    /** Beneath the card bound to [to], as material. */
    @Serializable @SerialName("attach")
    data class Attach(val pick: Pick, val to: String = Pick.SELF) : Op

    @Serializable @SerialName("detach")
    data class Detach(val n: Int = 1, val from: String = Pick.SELF) : Op

    @Serializable @SerialName("token")
    data class Token(
        val name: String,
        val attribute: CardAttribute? = null,
        val race: String? = null,
        val level: Int? = null,
        val atk: Int = 0,
        val def: Int = 0,
        val n: Int = 1,
        val pos: Pos = Pos.EITHER,
        val rel: Rel = Rel.YOU,
    ) : Op

    /** Negates a link on the chain (by default the one answered); [bind] names its card for the next step. */
    @Serializable @SerialName("negate")
    data class Negate(val what: NegWhat = NegWhat.ACTIVATION, val link: LinkRef = LinkRef.ANSWERED, val bind: String? = null) : Op

    /** Sets a Level ([to]) or moves it ([by]), until [until]: held in `FxState`, which Synchro and Xyz read. */
    @Serializable @SerialName("level")
    data class ChangeLevel(val pick: Pick, val to: ReadNum? = null, val by: ReadNum? = null, val until: String = Lock.UNTIL_TURN) : Op

    /** Life points: [delta] to [rel] (negative is damage). */
    @Serializable @SerialName("lp")
    data class Lp(val rel: Rel, val delta: ReadNum) : Op

    /** Paying [n] life points: a cost. */
    @Serializable @SerialName("pay")
    data class PayLp(val n: ReadNum) : Op

    @Serializable @SerialName("counter")
    data class Counter(val pick: Pick, val kind: String = "", val delta: Int = 1) : Op

    /** "You can Normal Summon 1 more" monster that [filter] matches, this turn. */
    @Serializable @SerialName("normal-again")
    data class NormalSummonAgain(val filter: ReadFilter = Filter.Any) : Op

    /** One of [options], chosen by [who]; [labels] are the options' words for the chooser. */
    @Serializable @SerialName("choose")
    data class Choose(val options: List<List<Step>>, val who: Rel = Rel.YOU, val labels: List<String> = emptyList()) : Op

    @Serializable @SerialName("if")
    data class If(val cond: ReadCond, val then: List<Step> = emptyList(), val otherwise: List<Step> = emptyList()) : Op

    @Serializable @SerialName("restrict")
    data class Restrict(val restriction: Restriction) : Op

    /**
     * "Declare a card name / a Type / an Attribute / a Level": the chooser declares one ([Decision.Declare]), of the cards
     * [among] matches for a name, and the answer is bound as [bind] for later steps ([Filter.Declared]).
     */
    @Serializable @SerialName("declare")
    data class Declare(val kind: DeclareKind, val among: ReadFilter? = null, val bind: String = DECLARED) : Op {
        companion object {
            const val DECLARED = "declared"
        }
    }

    /** A newer build's step, kept as written; an effect with one is never used. */
    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : Op
}

/** What a [Op.Declare] declares. */
@Serializable
enum class DeclareKind { NAME, TYPE, ATTRIBUTE, LEVEL }

@Serializable
enum class Ban { SPECIAL_SUMMON, SPECIAL_SUMMON_FROM_EXTRA, NORMAL_SUMMON, ACTIVATE }

/** A lock: [seat] may not do [ban], except with cards [except] matches, until [until] (`Lock`'s words). */
@Serializable
data class Restriction(
    val ban: Ban,
    val except: ReadFilter? = null,
    val seat: Rel = Rel.YOU,
    val until: String = Lock.UNTIL_TURN,
)

/** How a monster is summoned, beyond what its frame says. */
@Serializable
data class SummonRule(
    /** False: "cannot be Normal Summoned/Set" (and never true of an Extra Deck monster, whatever it says). */
    val normal: Boolean = true,
    /** "Must first be … Summoned": until it is, it cannot be Special Summoned from the GY or banished. */
    val mustFirstBe: ProcKind? = null,
    val procs: List<ReadProc> = emptyList(),
    /** "You can only Special Summon X once per turn". */
    val oncePerTurn: Boolean = false,
    /** Tributes for its Normal Summon or Set, when not the Level's (D.md §2.4). */
    val tributes: Int? = null,
)

/** A summoning procedure. Link, Synchro, Xyz and Inherent start no chain; Fusion and Ritual are made by an effect. */
@Serializable
sealed interface Proc {
    /** Made by a Fusion effect ([Op.FusionSummon]); not a procedure of its own. */
    @Serializable @SerialName("fusion")
    data class Fusion(val materials: List<Mat>) : Proc

    /** Level sum = the card's Level: one Tuner ([tuner]) and the rest ([others]). */
    @Serializable @SerialName("synchro")
    data class Synchro(val tuner: Mat, val others: Mat) : Proc

    /** [n] (to [max], when it says "or more") monsters [each] matches, each of Level = the card's Rank. */
    @Serializable @SerialName("xyz")
    data class Xyz(val n: Int = 2, val each: ReadFilter = Filter.Any, val max: Int? = null) : Proc

    /** [min]–[max] monsters [each] matches, [also] by one of them; rating sum = the card's Link rating (a Link counts 1 or its rating). */
    @Serializable @SerialName("link")
    data class Link(val min: Int = 2, val max: Int = 2, val each: ReadFilter = Filter.Any, val also: ReadFilter? = null) : Proc

    /** Made by a Ritual Spell ([Op.RitualSummon]). */
    @Serializable @SerialName("ritual")
    data object Ritual : Proc

    /** "You can Special Summon this card from [from] if …": no chain link, a summon of its own. */
    @Serializable @SerialName("inherent")
    data class Inherent(
        val from: Where = Where.HAND,
        val condition: ReadCond? = null,
        val cost: List<Step> = emptyList(),
        val opt: ReadOpt? = null,
        val pos: Pos = Pos.EITHER,
    ) : Proc

    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : Proc
}

/**
 * Materials: [n] monsters [where] matches. [upTo]: 1 to [n]; [more]: [n] or more ("1+ non-Tuner monsters") — D.md's
 * sketch has only [upTo], and Synchro's "1 or more" needs the other.
 */
@Serializable
data class Mat(val n: Int = 1, val where: ReadFilter = Filter.Any, val upTo: Boolean = false, val more: Boolean = false) {
    val least: Int get() = if (upTo) 1 else n
    val most: Int get() = if (more) Int.MAX_VALUE else n
}

/** A number: a constant (written as a bare number), a count of cards, or a bound card's stat. No arithmetic beyond that. */
@Serializable
sealed interface Num {
    @Serializable @SerialName("const")
    data class Const(val n: Int) : Num

    @Serializable @SerialName("count")
    data class Count(val from: List<Spot>, val where: ReadFilter = Filter.Any) : Num

    /** [stat] of the card bound to [ref] (the first, when several). */
    @Serializable @SerialName("of")
    data class Of(val stat: Stat, val ref: String = Pick.SELF) : Num

    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : Num
}

@Serializable
enum class Cmp { GE, LE, EQ, GT, LT, NE }

/** A condition. Numbers are [Num]s; there are no loops and no arithmetic. */
@Serializable
sealed interface Cond {
    /** [seat] controls at least [n] cards [where] matches, on the field. */
    @Serializable @SerialName("controls")
    data class Controls(val where: ReadFilter = Filter.Any, val n: ReadNum = Num.Const(1), val seat: Rel = Rel.YOU) : Cond

    @Serializable @SerialName("no-monsters")
    data class NoMonsters(val seat: Rel = Rel.YOU) : Cond

    /** The count of cards [where] matches in [from], against [n]. */
    @Serializable @SerialName("count")
    data class Count(val from: List<Spot>, val where: ReadFilter = Filter.Any, val cmp: Cmp = Cmp.GE, val n: ReadNum = Num.Const(1)) : Cond

    @Serializable @SerialName("phase")
    data class Phase(val any: Set<DuelPhase>) : Cond

    /** Whose turn it is: [Rel.YOU] your own. */
    @Serializable @SerialName("turn")
    data class Turn(val whose: Rel) : Cond

    @Serializable @SerialName("chain-empty")
    data object ChainEmpty : Cond

    /** The chain's newest link: whose, its card, what it includes. */
    @Serializable @SerialName("newest")
    data class Newest(val seat: Rel = Rel.ANY, val about: ReadFilter? = null, val includes: List<Includes> = emptyList()) : Cond

    /** This card ([Pick.SELF]) had [event] happen to it this turn: summoned, sent… */
    @Serializable @SerialName("this-turn")
    data class ThisTurn(val event: Event) : Cond

    @Serializable @SerialName("lp")
    data class Lp(val seat: Rel = Rel.YOU, val cmp: Cmp = Cmp.GE, val n: ReadNum) : Cond

    @Serializable @SerialName("compare")
    data class Compare(val left: ReadNum, val cmp: Cmp, val right: ReadNum) : Cond

    @Serializable @SerialName("all")
    data class All(val all: List<ReadCond>) : Cond

    @Serializable @SerialName("any-of")
    data class AnyOf(val any: List<ReadCond>) : Cond

    @Serializable @SerialName("not")
    data class Not(val not: ReadCond) : Cond

    /** A newer build's condition, kept as written; it never holds. */
    @Serializable @SerialName("?")
    data class Unknown(val raw: JsonObject = JsonObject(emptyMap())) : Cond
}

/**
 * Which effect or procedure made a log entry (D.md §2.1): the one optional field the engine adds to `DuelEntry`, as `by`
 * was added. With it the fold of an engine-made log is exact; a log made by hand has none, and its `FxState` is inferred.
 */
@Serializable
data class FxTag(
    /** The card whose effect or procedure it is; 0 for a move of the turn's own (the phase moving on). */
    val uid: Int,
    /** "e1"…, [PROC], or [RULE] (a Normal Summon or Set, the phase, the chain's own Spells to the GY). */
    val effect: String,
    /** [COST], [ACTIVATE], [RESOLVE], [PROC], [RULE], [SKIP]. */
    val part: String,
    /** Its chain link, 1-based. */
    val link: Int? = null,
    /** The first 12 hex digits of the compiled script's hash ([FxCodec.hash]). */
    val script: String = "",
    /** The effect was VERIFIED when it was used (§4.4); the table offers unverified ones too. */
    val verified: Boolean = false,
    /**
     * What the engine knew that the action itself does not say ([FxMemo]): its batch, a link's bindings, a restriction, a
     * Level change. With it a tagged log folds to exactly the engine's own `FxState` (`FxFold`, `FxFoldTest`).
     */
    val memo: FxMemo? = null,
) {
    companion object {
        const val PROC = "proc"
        const val RULE = "rule"
        const val COST = "cost"
        const val ACTIVATE = "activate"
        const val RESOLVE = "resolve"
        /** A waiting trigger let go (not used, missed the timing, or not usable now), said in the log as a note. */
        const val SKIP = "skip"
    }
}
