package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.duel.effects.FxVocab
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCheck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Goldfish
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishResult
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSearch
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSetup
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.world.Study.Companion.obj
import com.kaiharimoto.mastertool.core.world.Study.Companion.strs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `goldfish` instrument (Phase D step 4, `docs/phases/D.md` §5.6): the goldfish simulator run as a study, so its answer
 * comes through `world_tool` and `Evidence.judge` traces a number to it — and only it may vouch for a line's percentage
 * (`Evidence.lineClaims`). Built to the instruments' standard (`docs/world/INSTRUMENTS-REDTEAM.md`): the question first,
 * the method (searched, n hands, the seed, the bounds, the library's fingerprint), the finding with its interval, every
 * warning; errors that show a call that works; the same arguments give the same answer.
 */
object GoldfishInstrument {
    const val NAME = "goldfish"

    val SPEC = Instruments.Spec(
        NAME, "how often the written effects reach an end board",
        "The goldfish: of N hands dealt from a seed, in how many the written effects the app trusts (compiled and checked) reach " +
            "a target end board in one turn with no opponent, and by which lines — or, given a saved combo, in how many that line " +
            "gets there. A lower bound: cards with no written effect are played as inert, and hands the search could not finish " +
            "count as not reached. Any hand opens as a replay.",
        "deck (a deck's id or name; the open deck by default), target (a kept target's id or name — fx_target names one — or " +
            "{name, all: [conditions]}), going ('first' or 'second', default first), hands (default 2000 on the desk, 500 on a phone, " +
            "at most 20000), seed (default 1), budget (engine moves a hand, default 20000), combo (a saved combo's name: that line only)",
    )

    fun run(args: JsonObject, host: WorldHost): Instruments.Result {
        val gf = host.goldfish() ?: throw IllegalArgumentException("the goldfish needs the library of written effects, which this host does not give")
        val read = Instruments.deckFor(args, host)
        val entry = read.entry
        val doc = gf.doc(entry.id)
        val target = targetOf(args, doc.targets, entry.id)
        val going = args.str("going") ?: args.str("first")?.let { if (it.equals("false", true) || it.equals("second", true)) "second" else "first" } ?: "first"
        require(going == "first" || going == "second") { "going is 'first' or 'second' (it was “$going”)" }
        val hands = args.int("hands") ?: gf.defaultHands
        require(hands in 1..Goldfish.MOST_HANDS) { "hands must be from 1 to ${Goldfish.MOST_HANDS} (it was $hands): try hands: 2000" }
        val seed = args.num("seed")?.toLong() ?: 1L
        val budget = args.int("budget") ?: GoldfishSearch.DEFAULT_BUDGET
        require(budget in 100..1_000_000) { "budget is engine moves a hand, from 100 to 1000000 (it was $budget): the default is 20000" }
        val comboWord = args.str("combo")
        val combo = comboWord?.let { w ->
            val all = host.combos(entry.id)
            all.firstOrNull { it.id == w || it.name.equals(w, ignoreCase = true) } ?: all.firstOrNull { it.name.contains(w, ignoreCase = true) }
                ?: throw IllegalArgumentException("no combo “$w” for ${entry.name}: ${all.joinToString { it.name }.ifEmpty { "it has none saved" }} — or leave combo out to search")
        }
        val kit = GoldfishKit(gf.trust, host::cardById)
        val deck = GoldfishDeck(
            entry.deck.main.map { it.value }, entry.deck.extra.map { it.value }, entry.id, Ledger.fingerprint(entry.deck), entry.name,
        )
        val setup = GoldfishSetup(deck, target, going == "first", hands, seed, budget, combo = combo)
        val name = { c: Int -> kit.name(c) }
        Goldfish.refusal(setup, kit)?.let { nc ->
            throw IllegalArgumentException(nc.why + if (nc.cards.isNotEmpty()) " Ask for them with fx_request; the person's Write starts it." else "")
        }
        val r = Goldfish.runBlocking(setup, kit, now = host.now())
        val s = Study()
        val question = if (combo != null) "how often does “${combo.name}” get there" else "can the written effects reach it"
        s.say("goldfish: ${entry.name} — “${target.name}” (${BoardCheck.words(target, name)}): $question in one turn, going $going?")
        s.say(
            "  method: ${if (combo != null) "the line played through the engine" else "depth-first search over the engine's moves"}, " +
                "${GoldfishWords.count(r.hands)} hands from seed ${r.seed}, at most ${r.budget} engine moves a hand and ${r.depth} a line" +
                (if (r.ordered) ", every hand on its own (the scripts read the Deck's order)" else ", hands with one engine part searched once") +
                "; library ${r.library} (engine ${FxVocab.ENGINE}); ${r.ms} ms",
        )
        val copies = { c: Int -> deck.main.count { kit.canonical(it) == c } }
        GoldfishWords.all(r, name, copies).forEach { s.say("  $it") }
        if (r.unknown.isNotEmpty()) s.warn("played as inert (no written effect the goldfish trusts): ${r.unknown.joinToString { name(it) }} — fx_request offers them to write")
        if (r.warned.isNotEmpty()) s.warn("open warnings on ${r.warned.joinToString { name(it) }}: accept or repair them in the Effects app; the next run says so")
        if (r.undecided > 0) s.warn("${GoldfishWords.pct(r.undecided.toDouble() / r.hands)} of hands were undecided (the budget ran out): the number is a lower bound; a larger budget may settle them")
        s.stat(
            "goldfish-rate", "Gets there", GoldfishWords.pct(r.rate), "of ${GoldfishWords.count(r.hands)} hands, going $going",
            "95 %: ${GoldfishWords.interval(r.reached, r.hands)}; no line ${GoldfishWords.pct(r.noLine.toDouble() / r.hands)}; undecided ${GoldfishWords.pct(r.undecided.toDouble() / r.hands)}",
            "Searched: ${r.hands} hands, seed ${r.seed}, Wilson 95 % interval; a lower bound. Library ${r.library}.",
        )
        if (r.lines.isNotEmpty()) {
            s.table(
                "goldfish-lines", "Lines found", listOf("Line", "Hands", "Share"),
                r.lines.take(20).map { listOf(it.skeleton, it.count.toString(), GoldfishWords.pct(it.count.toDouble() / r.hands)) },
                "Each reached hand's line as its activations and summons, grouped. Seed ${r.seed}.",
            )
        }
        return s.done(answer(r))
    }

    /** The target [args] names: a kept one by id or name, or one given whole. */
    private fun targetOf(args: JsonObject, kept: List<EndBoard>, deckId: String): EndBoard {
        val raw = args["target"] ?: throw IllegalArgumentException(
            "give target: a kept target's name (${kept.joinToString { it.name }.ifEmpty { "this deck has none yet: fx_target names one" }}) or " +
                "{\"name\": \"Two Example monsters\", \"all\": [{\"t\": \"controls\", \"where\": {\"t\": \"name-has\", \"word\": \"Example\"}, \"n\": 2}]}",
        )
        if (raw is JsonPrimitive) {
            val w = raw.contentOrNull.orEmpty()
            return kept.firstOrNull { it.id == w } ?: kept.firstOrNull { it.name.equals(w, ignoreCase = true) }
                ?: throw IllegalArgumentException("no target “$w” kept for this deck: ${kept.joinToString { it.name }.ifEmpty { "none yet — fx_target names one, or give it whole" }}")
        }
        val o = raw as? JsonObject ?: throw IllegalArgumentException("target is a kept target's name or {name, all: [conditions]}")
        return GoldfishCodec.target("given", o.str("name") ?: "Given target", deckId, o["all"], EndBoard.AI)
    }

    /** The answer to build on: the counts, the fingerprints and the lines. */
    fun answer(r: GoldfishResult): JsonObject = obj(
        "rate" to JsonPrimitive(r.rate),
        "reached" to JsonPrimitive(r.reached),
        "hands" to JsonPrimitive(r.hands),
        "noLine" to JsonPrimitive(r.noLine),
        "undecided" to JsonPrimitive(r.undecided),
        "seed" to JsonPrimitive(r.seed),
        "first" to JsonPrimitive(r.first),
        "deck" to JsonPrimitive(r.deck),
        "library" to JsonPrimitive(r.library),
        "target" to JsonPrimitive(r.target.name),
        "combo" to r.combo?.let(::JsonPrimitive),
        "heldUnknown" to JsonPrimitive(r.heldUnknown),
        "touchedUnknown" to JsonPrimitive(r.touchedUnknown),
        "unknown" to JsonArray(r.unknown.map(::JsonPrimitive)),
        "used" to JsonArray(r.used.map(::JsonPrimitive)),
        "warned" to JsonArray(r.warned.map(::JsonPrimitive)),
        "playedByYou" to JsonPrimitive(r.playedByYou),
        "lines" to JsonArray(r.lines.take(20).map { obj("line" to JsonPrimitive(it.skeleton), "hands" to JsonPrimitive(it.count)) }),
        "notComputable" to strs(r.notComputable.map { it.why }),
    )

}
