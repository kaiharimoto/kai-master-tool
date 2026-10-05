package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.model.Card
import kotlinx.serialization.Serializable

/*
 * The legality pass (Phase D step 2, `docs/phases/D.md` §3.4): a compiled script checked before anything plays it. Four
 * layers — its shape, its references, the card it is for, and the card's printed text ([FxLints]). An error makes the
 * script BROKEN, never played; a warning keeps an effect from being verified until it is fixed, or accepted by the person
 * (`FxReview`, the person's alone).
 *
 * Pure and quick (a script is a few kilobytes): it runs after every compile, on every script the library loads and on
 * every script a sync brings in, so nothing arrives "checked" from outside.
 */

@Serializable
enum class FxLevel { ERROR, WARNING }

/**
 * One thing the pass found. [code] names the rule ("unique-ids", "lint-opt-name"); [effect] the effect it is about ("e1",
 * [FxTag.PROC] for a summoning procedure), or null for the card. [key] is what an acceptance names, so accepting one warning
 * never accepts another.
 */
@Serializable
data class FxFinding(
    val level: FxLevel,
    val code: String,
    val message: String,
    val effect: String? = null,
) {
    val key: String get() = if (effect == null) code else "$code@$effect"
    val error: Boolean get() = level == FxLevel.ERROR
}

/** What the pass found on one card's script. */
data class FxReport(val card: Int, val findings: List<FxFinding>) {
    val errors: List<FxFinding> get() = findings.filter { it.error }
    val warnings: List<FxFinding> get() = findings.filter { !it.error }

    /** An error anywhere: the script is never played. */
    val broken: Boolean get() = findings.any { it.error }

    /** The warnings the person has not accepted ([accepted] keys). */
    fun open(accepted: Set<String>): List<FxFinding> = warnings.filter { it.key !in accepted }

    /** In words, one finding a line: "Error · e1: …". */
    fun words(accepted: Set<String> = emptySet()): String = findings.joinToString("\n") { f ->
        val head = if (f.error) "Error" else if (f.key in accepted) "Accepted" else "Warning"
        "$head${f.effect?.let { " · $it" }.orEmpty()}: ${f.message}"
    }
}

object FxCheck {
    /** At most this many effects a script (D.md §3.4). */
    const val MOST_EFFECTS = 8

    /** Steps nested at most this deep ([Op.Choose], [Op.If]). */
    const val MOST_DEPTH = 6

    /** Effect ids: short, plain ("e1", "e2"). */
    private val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,15}")

    /**
     * Checks [script] (layers 1–3; the text's lints are [FxLints], run by [full]). [card] is the pool's card for its
     * passcode (null when the pool does not know it); [canonical] resolves a printing; [expected] is the passcode the file
     * is named for; [bytes] its size as stored.
     */
    fun check(
        script: CardScript,
        card: Card?,
        canonical: (Int) -> Int = { it },
        expected: Int? = null,
        bytes: Int = FxCodec.encode(script).encodeToByteArray().size,
    ): FxReport {
        val out = ArrayList<FxFinding>()
        fun err(code: String, message: String, effect: String? = null) { out += FxFinding(FxLevel.ERROR, code, message, effect) }
        fun warn(code: String, message: String, effect: String? = null) { out += FxFinding(FxLevel.WARNING, code, message, effect) }

        // ---- 1. Shape -------------------------------------------------------------------------------------------------
        if (script.vocab > FxVocab.VERSION) err("vocab", "Written in vocabulary ${script.vocab}; this build reads ${FxVocab.VERSION}.")
        if (bytes > FxCodec.MAX_BYTES) err("size", "The script is ${bytes / 1024} KB; at most 64 KB.")
        if (script.effects.size > MOST_EFFECTS) err("effects", "${script.effects.size} effects; a script holds at most $MOST_EFFECTS.")
        script.effects.forEach { e ->
            val id = e.id
            if (!ID.matches(id)) err("id", "“$id” is not an effect id: a short word like e1.", id.ifBlank { null })
            if (FxWalk.unread(e)) err("unknown-word", if (FxWalk.tooDeep(e)) "It nests deeper than the engine reads." else "It uses a word this build does not know.", id)
            val depth = maxOf(FxWalk.depth(e.cost, MOST_DEPTH + 1), FxWalk.depth(e.does, MOST_DEPTH + 1))
            if (depth > MOST_DEPTH) err("depth", "Its steps nest $depth deep; at most $MOST_DEPTH.", id)
            picks(e).forEach { p ->
                if (p.n < 1 || p.n > Pick.MOST) err("pick-size", "A pick of ${p.n}; a pick takes 1 to ${Pick.MOST} cards.", id)
            }
            if (e.from.isEmpty()) err("from", "It says nowhere it is used from: give from (hand, monster_zone, gy…).", id)
            if (e.label.length > 40) warn("label", "Its short name is ${e.label.length} characters; the table's list shows about 20.", id)
        }
        script.summon?.procs.orEmpty().forEach { p -> if (FxWalk.unread(p)) err("unknown-word", "A summoning procedure uses a word this build does not know.", FxTag.PROC) }

        // ---- 2. References ----------------------------------------------------------------------------------------------
        script.effects.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { err("unique-ids", "Two effects are called $it: each needs its own id.", it) }
        script.effects.forEach { e -> references(e, ::err) }
        script.effects.forEach { e ->
            val id = e.id
            when (e.kind) {
                Kind.TRIGGER -> if (e.trigger == null) err("trigger", "A trigger effect says what sets it off (trigger.on).", id)
                else -> if (e.trigger != null) err("trigger", "Only a trigger effect has a trigger; this one is ${e.kind.name.lowercase()}.", id)
            }
            if (e.respond != null && e.kind != Kind.QUICK && e.kind != Kind.ACTIVATION) err("respond", "Only a quick effect or an activation answers a link.", id)
            if (e.kind == Kind.IGNITION && Where.HAND in e.from) err("place", "An ignition effect is never used from the hand: a hand effect is a quick effect, or a summon (proc.inherent).", id)
            if (e.kind == Kind.CONTINUOUS && (e.cost.isNotEmpty() || e.does.isNotEmpty() || e.targets.isNotEmpty())) {
                err("continuous", "A continuous effect is not activated: it has no cost, targets or steps, only what it forbids (leaves).", id)
            }
            if (e.sameTurn && e.kind != Kind.ACTIVATION) warn("same-turn", "sameTurn is for a Trap or a set Quick-Play Spell's activation.", id)
            (e.opt as? Opt.ByName)?.let { o ->
                if (o.group != null && o.group.isBlank()) err("opt-group", "A once-per-turn group needs a name.", id)
                if (o.times !in 1..9) err("opt-times", "Once per turn ${o.times} times? 1 to 9.", id)
            }
        }
        script.summon?.procs.orEmpty().forEach { p ->
            (p as? Proc.Inherent)?.let { inh ->
                (inh.opt as? Opt.ByName)?.let { o -> if (o.group != null && o.group.isBlank()) err("opt-group", "A once-per-turn group needs a name.", FxTag.PROC) }
                val bound = mutableSetOf(Pick.SELF)
                stepsRefs(inh.cost, bound, FxTag.PROC, ::err)
            }
        }

        // ---- 3. The card --------------------------------------------------------------------------------------------------
        if (expected != null && script.card != expected) err("card", "The file is for ${expected}, but the script says card ${script.card}.")
        val canon = canonical(script.card)
        if (canon != script.card) err("canonical", "${script.card} is an alternate artwork: the script belongs to its card, $canon (every printing reads it).")
        if (card == null) {
            warn("pool", "The card pool does not know ${script.card}: its kind and frame are not checked.")
        } else {
            cardFit(script, FxFacts.of(card), ::err, ::warn)
        }
        return FxReport(script.card, out)
    }

    /** [check] and the text's lints ([FxLints]) together: every layer. [nameOf] names a passcode, for quoted names. */
    fun full(
        script: CardScript,
        card: Card?,
        canonical: (Int) -> Int = { it },
        expected: Int? = null,
        bytes: Int = FxCodec.encode(script).encodeToByteArray().size,
        nameOf: (Int) -> String? = { null },
    ): FxReport {
        val base = check(script, card, canonical, expected, bytes)
        val lints = if (card == null) emptyList() else FxLints.lint(script, card, nameOf)
        return FxReport(script.card, base.findings + lints)
    }

    /** The kinds, places and procedures fit what the card is (layer 3). */
    private fun cardFit(script: CardScript, c: FxCard, err: (String, String, String?) -> Unit, warn: (String, String, String?) -> Unit) {
        if (c.normal && script.effects.isNotEmpty()) err("normal", "${c.name} is a Normal Monster: it has no effects.", null)
        script.effects.forEach { e ->
            val id = e.id
            if (c.monster) {
                if (e.kind == Kind.ACTIVATION) err("kind", "A monster's effects are ignition, trigger, quick or continuous; activation is a Spell's or a Trap's own.", id)
                if (Where.SPELL_ZONE in e.from && !c.pendulum) err("place", "A monster that is not a Pendulum is never in the Spell & Trap Zone.", id)
                if (Where.FIELD_ZONE in e.from) err("place", "A monster is never in the Field Zone.", id)
            } else {
                if (Where.MONSTER_ZONE in e.from) err("place", "A ${c.type.name.lowercase()} is never in a Monster Zone.", id)
                if (e.kind == Kind.ACTIVATION && c.type == CardType.TRAP && Where.SPELL_ZONE !in e.from) {
                    err("place", "A Trap is activated from the Spell & Trap Zone (set first): from spell_zone.", id)
                }
                if (e.kind == Kind.ACTIVATION && c.type == CardType.TRAP && Where.HAND in e.from) {
                    warn("place", "A Trap activated from the hand is rare: only when its text allows it.", id)
                }
                if (e.kind == Kind.ACTIVATION && c.isSpellSub("Field") && Where.SPELL_ZONE in e.from) {
                    err("place", "A Field Spell lies in the Field Zone: from field_zone.", id)
                }
            }
        }
        if (!c.monster && script.summon != null) err("summon", "Only a monster has summoning rules.", FxTag.PROC)
        val procs = script.summon?.procs.orEmpty()
        when (c.frameProc) {
            ProcKind.LINK -> {
                val link = procs.filterIsInstance<Proc.Link>()
                if (link.isEmpty()) err("proc", "${c.name} is a Link Monster: write its procedure (fx.proc.link).", FxTag.PROC)
                val rating = c.link
                link.forEach { p ->
                    if (p.min < 1 || p.max < p.min) err("proc", "Link materials ${p.min}–${p.max} make no sense.", FxTag.PROC)
                    if (rating != null && p.max > rating) err("proc", "Link-$rating takes at most $rating materials, not ${p.max}.", FxTag.PROC)
                    if (rating != null && p.min > rating) err("proc", "Link-$rating cannot need ${p.min} materials.", FxTag.PROC)
                }
            }
            ProcKind.XYZ -> {
                val xyz = procs.filterIsInstance<Proc.Xyz>()
                if (xyz.isEmpty()) err("proc", "${c.name} is an Xyz Monster: write its procedure (fx.proc.xyz).", FxTag.PROC)
                xyz.forEach { p -> if (p.max != null && p.max < p.n) err("proc", "Xyz materials ${p.n} to ${p.max} make no sense.", FxTag.PROC) }
            }
            ProcKind.SYNCHRO -> {
                val synchro = procs.filterIsInstance<Proc.Synchro>()
                if (synchro.isEmpty()) err("proc", "${c.name} is a Synchro Monster: write its procedure (fx.proc.synchro).", FxTag.PROC)
                if (c.level == null) warn("proc", "The pool gives ${c.name} no Level: a Synchro's materials sum to it.", FxTag.PROC)
                synchro.forEach { p ->
                    if (!mentionsTuner(p.tuner.where)) warn("proc", "A Synchro's first material is a Tuner: its filter should say fx.tuner().", FxTag.PROC)
                }
            }
            ProcKind.FUSION -> if (procs.none { it is Proc.Fusion }) err("proc", "${c.name} is a Fusion Monster: write its materials (fx.proc.fusion).", FxTag.PROC)
            ProcKind.RITUAL -> if (procs.none { it is Proc.Ritual }) warn("proc", "${c.name} is a Ritual Monster: fx.proc.ritual() says a Ritual Spell summons it.", FxTag.PROC)
            else -> {
                if (procs.any { it is Proc.Link || it is Proc.Xyz || it is Proc.Synchro || it is Proc.Fusion }) {
                    err("proc", "${c.name} is a Main Deck monster: Link, Xyz, Synchro and Fusion procedures are an Extra Deck monster's.", FxTag.PROC)
                }
            }
        }
        if (c.extraDeck && script.summon?.normal == true && script.summon.procs.isEmpty()) {
            warn("summon", "An Extra Deck monster is never Normal Summoned; its summon says nothing else.", FxTag.PROC)
        }
    }

    private fun mentionsTuner(f: Filter, d: Int = 0): Boolean = d < FxWalk.DEEPEST && when (f) {
        is Filter.Frame -> f.frame == CardFrame.TUNER
        is Filter.All -> f.all.any { mentionsTuner(it, d + 1) }
        is Filter.AnyOf -> f.any.isNotEmpty() && f.any.all { mentionsTuner(it, d + 1) }
        else -> false
    }

    /**
     * Every ref is bound before it is used: [Pick.SELF] always; [Pick.TARGETS] and each target's bind once targets are
     * chosen; a cost's or a step's bind for the steps after it; a declaration's and a negation's names. A cost never refers
     * to a target: costs are paid before the targets are named to the chain.
     */
    private fun references(e: Effect, err: (String, String, String?) -> Unit) {
        val id = e.id
        val bound = mutableSetOf(Pick.SELF)
        e.condition?.let { cond(it, bound, id, err, 0) }
        e.trigger?.about?.let { filter(it, bound, id, err, 0) }
        e.respond?.about?.let { filter(it, bound, id, err, 0) }
        val targetNames = e.targets.mapNotNull { it.bind }.toSet() + Pick.TARGETS
        // The cost: no target is named yet.
        stepsRefs(e.cost, bound, id, err) { ref -> if (ref in targetNames) err("cost-target", "A cost refers to “$ref”, a target: costs never target.", id) }
        e.targets.forEach { p ->
            if (p.ref != null && p.ref != Pick.SELF) err("target-ref", "A target is chosen as it is activated: “${p.ref}” cannot be a target's ref.", id)
            filter(p.where, bound, id, err, 0)
            p.bind?.let { bound += it }
        }
        if (e.targets.isNotEmpty()) bound += Pick.TARGETS
        stepsRefs(e.does, bound, id, err)
        e.leaves.forEach { r -> r.except?.let { filter(it, bound, id, err, 0) } }
    }

    private fun stepsRefs(steps: List<Step>, bound: MutableSet<String>, id: String, err: (String, String, String?) -> Unit, onRef: (String) -> Unit = {}) {
        fun ref(r: String?) {
            if (r == null) return
            onRef(r)
            if (r !in bound) err("ref", "“$r” is used before anything is bound to it.", id)
        }
        fun pick(p: Pick) {
            ref(p.ref)
            filter(p.where, bound, id, err, 0)
        }
        fun walk(list: List<Step>, depth: Int) {
            if (depth > MOST_DEPTH + 1) return
            list.forEach { s ->
                when (val op = s.op) {
                    is Op.Move -> pick(op.pick)
                    is Op.Add -> pick(op.pick)
                    is Op.Send -> pick(op.pick)
                    is Op.Discard -> pick(op.pick)
                    is Op.Destroy -> pick(op.pick)
                    is Op.Banish -> pick(op.pick)
                    is Op.Tribute -> pick(op.pick)
                    is Op.Return -> pick(op.pick)
                    is Op.Reveal -> pick(op.pick)
                    is Op.SpecialSummon -> pick(op.pick)
                    is Op.Attach -> { pick(op.pick); ref(op.to) }
                    is Op.Detach -> ref(op.from)
                    is Op.ChangeLevel -> { pick(op.pick); num(op.to, bound, id, err); num(op.by, bound, id, err) }
                    is Op.Counter -> pick(op.pick)
                    is Op.Lp -> num(op.delta, bound, id, err)
                    is Op.PayLp -> num(op.n, bound, id, err)
                    is Op.FusionSummon -> filter(op.fusion, bound, id, err, 0)
                    is Op.RitualSummon -> filter(op.ritual, bound, id, err, 0)
                    is Op.SynchroSummon -> filter(op.f, bound, id, err, 0)
                    is Op.XyzSummon -> filter(op.f, bound, id, err, 0)
                    is Op.LinkSummon -> filter(op.f, bound, id, err, 0)
                    is Op.NormalSummonAgain -> filter(op.filter, bound, id, err, 0)
                    is Op.Restrict -> op.restriction.except?.let { filter(it, bound, id, err, 0) }
                    is Op.Declare -> op.among?.let { filter(it, bound, id, err, 0) }
                    is Op.If -> {
                        cond(op.cond, bound, id, err, 0)
                        val before = bound.toSet()
                        walk(op.then, depth + 1)
                        val afterThen = bound.toSet()
                        bound.clear(); bound += before
                        walk(op.otherwise, depth + 1)
                        // A name is bound after the If only when both branches bind it.
                        bound.retainAll(afterThen + before)
                        bound += before
                    }
                    is Op.Choose -> {
                        val before = bound.toSet()
                        var common: Set<String>? = null
                        op.options.forEach { o ->
                            bound.clear(); bound += before
                            walk(o, depth + 1)
                            common = common?.intersect(bound) ?: bound.toSet()
                        }
                        bound.clear(); bound += before + common.orEmpty()
                    }
                    is Op.Draw, is Op.Shuffle, is Op.Token, is Op.Unknown -> {}
                    is Op.Negate -> {}
                }
                binds(s.op)?.let { bound += it }
            }
        }
        walk(steps, 1)
    }

    /** What a step binds for the steps after it. */
    private fun binds(op: Op): String? = when (op) {
        is Op.Move -> op.pick.bind
        is Op.Add -> op.pick.bind
        is Op.Send -> op.pick.bind
        is Op.Discard -> op.pick.bind
        is Op.Destroy -> op.pick.bind
        is Op.Banish -> op.pick.bind
        is Op.Tribute -> op.pick.bind
        is Op.Return -> op.pick.bind
        is Op.Reveal -> op.pick.bind
        is Op.SpecialSummon -> op.pick.bind
        is Op.Attach -> op.pick.bind
        is Op.ChangeLevel -> op.pick.bind
        is Op.Counter -> op.pick.bind
        is Op.Negate -> op.bind
        is Op.Declare -> op.bind
        else -> null
    }

    private fun filter(f: Filter, bound: Set<String>, id: String, err: (String, String, String?) -> Unit, d: Int) {
        if (d > FxWalk.DEEPEST) return
        when (f) {
            is Filter.Same -> if (f.ref !in bound) err("ref", "“${f.ref}” is used before anything is bound to it.", id)
            is Filter.Declared -> if (f.ref !in bound) err("ref", "“${f.ref}” is used before anything is declared under it.", id)
            is Filter.All -> f.all.forEach { filter(it, bound, id, err, d + 1) }
            is Filter.AnyOf -> f.any.forEach { filter(it, bound, id, err, d + 1) }
            is Filter.Not -> filter(f.not, bound, id, err, d + 1)
            else -> {}
        }
    }

    private fun num(n: Num?, bound: Set<String>, id: String, err: (String, String, String?) -> Unit) {
        when (n) {
            is Num.Of -> if (n.ref !in bound) err("ref", "“${n.ref}” is used before anything is bound to it.", id)
            is Num.Count -> filter(n.where, bound, id, err, 0)
            else -> {}
        }
    }

    private fun cond(c: Cond, bound: Set<String>, id: String, err: (String, String, String?) -> Unit, d: Int) {
        if (d > FxWalk.DEEPEST) return
        when (c) {
            is Cond.Controls -> { filter(c.where, bound, id, err, 0); num(c.n, bound, id, err) }
            is Cond.Count -> { filter(c.where, bound, id, err, 0); num(c.n, bound, id, err) }
            is Cond.Lp -> num(c.n, bound, id, err)
            is Cond.Compare -> { num(c.left, bound, id, err); num(c.right, bound, id, err) }
            is Cond.Newest -> c.about?.let { filter(it, bound, id, err, 0) }
            is Cond.All -> c.all.forEach { cond(it, bound, id, err, d + 1) }
            is Cond.AnyOf -> c.any.forEach { cond(it, bound, id, err, d + 1) }
            is Cond.Not -> cond(c.not, bound, id, err, d + 1)
            else -> {}
        }
    }

    /** Every pick an effect makes: its targets and each step's, nested ones too. */
    fun picks(e: Effect): List<Pick> = e.targets + FxWalk.steps(e).mapNotNull { pickOf(it.op) }

    /** The pick a step makes, if it makes one. */
    fun pickOf(op: Op): Pick? = when (op) {
        is Op.Move -> op.pick
        is Op.Add -> op.pick
        is Op.Send -> op.pick
        is Op.Discard -> op.pick
        is Op.Destroy -> op.pick
        is Op.Banish -> op.pick
        is Op.Tribute -> op.pick
        is Op.Return -> op.pick
        is Op.Reveal -> op.pick
        is Op.SpecialSummon -> op.pick
        is Op.Attach -> op.pick
        is Op.ChangeLevel -> op.pick
        is Op.Counter -> op.pick
        else -> null
    }

    /** Every filter in [e], flattened (All, AnyOf and Not opened), no deeper than [FxWalk.DEEPEST]. */
    fun filters(e: Effect): List<Filter> = buildList {
        fun f(x: Filter?, d: Int) {
            if (x == null || d > FxWalk.DEEPEST) return
            add(x)
            when (x) {
                is Filter.All -> x.all.forEach { f(it, d + 1) }
                is Filter.AnyOf -> x.any.forEach { f(it, d + 1) }
                is Filter.Not -> f(x.not, d + 1)
                else -> {}
            }
        }
        fun n(x: Num?) { if (x is Num.Count) f(x.where, 0) }
        fun c(x: Cond?, d: Int) {
            if (x == null || d > FxWalk.DEEPEST) return
            when (x) {
                is Cond.Controls -> { f(x.where, 0); n(x.n) }
                is Cond.Count -> { f(x.where, 0); n(x.n) }
                is Cond.Newest -> f(x.about, 0)
                is Cond.All -> x.all.forEach { c(it, d + 1) }
                is Cond.AnyOf -> x.any.forEach { c(it, d + 1) }
                is Cond.Not -> c(x.not, d + 1)
                else -> {}
            }
        }
        c(e.condition, 0)
        f(e.trigger?.about, 0)
        f(e.respond?.about, 0)
        picks(e).forEach { f(it.where, 0) }
        FxWalk.steps(e).forEach { s ->
            when (val op = s.op) {
                is Op.FusionSummon -> f(op.fusion, 0)
                is Op.RitualSummon -> f(op.ritual, 0)
                is Op.SynchroSummon -> f(op.f, 0)
                is Op.XyzSummon -> f(op.f, 0)
                is Op.LinkSummon -> f(op.f, 0)
                is Op.NormalSummonAgain -> f(op.filter, 0)
                is Op.Restrict -> f(op.restriction.except, 0)
                is Op.Declare -> f(op.among, 0)
                is Op.If -> c(op.cond, 0)
                is Op.ChangeLevel -> { n(op.to); n(op.by) }
                else -> {}
            }
        }
        e.leaves.forEach { f(it.except, 0) }
    }

    /** Every filter in a script's summoning procedures. */
    fun filters(s: SummonRule?): List<Filter> = s?.procs.orEmpty().flatMap { p ->
        when (p) {
            is Proc.Fusion -> p.materials.map { it.where }
            is Proc.Synchro -> listOf(p.tuner.where, p.others.where)
            is Proc.Xyz -> listOf(p.each)
            is Proc.Link -> listOfNotNull(p.each, p.also)
            else -> emptyList()
        }
    }
}
