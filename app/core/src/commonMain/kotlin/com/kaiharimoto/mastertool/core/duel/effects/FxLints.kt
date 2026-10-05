package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * The text's lints (Phase D step 2, D.md §3.4 layer 4): the printed text read for its **obvious markers** — "once per
 * turn", "(Quick Effect)", "target", a cost's semicolon, quoted names, "Level 4 or lower" — and each compared with the
 * script. Like `DuelCardInfo.handCost`, these are reads for the obvious: they never parse the text into effects, and every
 * one is a warning the person may accept with why (`FxReview`). The text itself stays in the pool; a script keeps only
 * its hash, so **errata shows** ([TEXT_CHANGED]).
 *
 * Each lint is held by `FxCheckTest` on a fictional card's own words.
 */
object FxLints {
    const val TEXT_CHANGED = "lint-text-changed"

    /** The 25 Types a monster can be, as printed: a search's filter should carry the one its text names. */
    val TYPES = listOf(
        "Aqua", "Beast", "Beast-Warrior", "Creator God", "Cyberse", "Dinosaur", "Divine-Beast", "Dragon", "Fairy", "Fiend",
        "Fish", "Illusion", "Insect", "Machine", "Plant", "Psychic", "Pyro", "Reptile", "Rock", "Sea Serpent", "Spellcaster",
        "Thunder", "Warrior", "Winged Beast", "Wyrm", "Zombie",
    )

    fun lint(script: CardScript, card: Card, nameOf: (Int) -> String? = { null }): List<FxFinding> {
        val text = card.description.replace("\r\n", "\n")
        val low = text.lowercase()
        val out = ArrayList<FxFinding>()
        fun warn(code: String, message: String, effect: String? = null) { out += FxFinding(FxLevel.WARNING, "lint-$code", message, effect) }
        val effects = script.effects
        val active = effects.filter { it.kind != Kind.CONTINUOUS }
        val opts = active.map { it.opt }

        // Errata: the text it was written from is not the pool's now.
        if (script.text.isNotBlank() && script.text != FxCodec.textOf(card.description)) {
            out += FxFinding(FxLevel.WARNING, TEXT_CHANGED, "The card's text changed since this was written: read it again.")
        }
        if (text.isBlank()) return out

        // Once per turn.
        val byName = opts.filterIsInstance<Opt.ByName>()
        if ("only use this effect of \"" in low && byName.isEmpty()) {
            warn("opt-name", "The text limits an effect to once per turn by name (\"only use this effect of …\"); no effect has fx.opt.byName().")
        }
        if ("only use each effect of \"" in low) {
            active.filter { it.opt !is Opt.ByName }.forEach { warn("opt-each", "The text limits each effect to once per turn by name; this one has no fx.opt.byName().", it.id) }
        }
        if (ONE_EFFECT.containsMatchIn(low)) {
            val groups = byName.map { it.group }.toSet()
            if (byName.size < active.size || groups.size != 1 || groups.single() == null) {
                warn("opt-shared", "The text allows only 1 of its effects a turn: every effect needs fx.opt.byName({group: …}) with one shared group.")
            }
        }
        if (ONE_CARD.containsMatchIn(low) && byName.none { it.group == Opt.CARD }) {
            warn("opt-card", "The text allows activating only 1 of this card a turn: fx.opt.oneCardPerTurn() (group \"card\").")
        }
        val perCopyText = ONCE_PER_TURN.findAll(text).count()
        val perCopyScript = opts.count { it is Opt.PerCopy } + script.summon?.procs.orEmpty().count { (it as? Proc.Inherent)?.opt is Opt.PerCopy }
        if (perCopyText > 0 && perCopyScript == 0) warn("opt-copy", "The text says \"Once per turn\"; no effect has fx.opt.perCopy().")
        if (perCopyText == 0 && perCopyScript > 0 && "once per turn" !in low) warn("opt-copy", "An effect is once per turn (fx.opt.perCopy()) where the text says nothing of it.")

        // Quick effects.
        val quickText = "(quick effect)" in low
        val quick = effects.count { it.kind == Kind.QUICK }
        if (quickText && quick == 0 && card.category == com.kaiharimoto.mastertool.core.model.CardCategory.MONSTER) {
            warn("quick", "The text has a (Quick Effect); no effect is fx.quick(…).")
        }
        if (!quickText && card.category == com.kaiharimoto.mastertool.core.model.CardCategory.MONSTER && effects.any { it.kind == Kind.QUICK && Where.HAND !in it.from }) {
            warn("quick", "An effect is quick where the text says no (Quick Effect).", effects.first { it.kind == Kind.QUICK && Where.HAND !in it.from }.id)
        }

        // "If …:" and "When …:" triggers.
        val whenText = WHEN.containsMatchIn(text)
        val ifText = IF.containsMatchIn(text)
        val triggers = effects.filter { it.kind == Kind.TRIGGER }
        triggers.filter { it.trigger?.timing == Timing.WHEN }.forEach { e ->
            if (!whenText) warn("timing-when", "A WHEN trigger (it can miss the timing) where the text opens no effect with \"When …:\".", e.id)
        }
        if (whenText && triggers.isNotEmpty() && triggers.none { it.trigger?.timing == Timing.WHEN }) {
            warn("timing-if", "The text opens an effect with \"When …:\"; every trigger here is IF (timing: 'when' can miss the timing).")
        }
        if (!ifText && !whenText && triggers.isNotEmpty() && TRIGGERISH.containsMatchIn(low).not()) {
            warn("timing", "A trigger effect where the text opens none with \"If …:\" or \"When …:\".", triggers.first().id)
        }

        // "You can".
        if ("you can" !in low) {
            triggers.filter { it.trigger?.optional == true }.forEach { warn("optional", "An optional trigger (\"you can\") where the text never says \"you can\": optional: false.", it.id) }
        }

        // A cost: a clause between ":" and ";".
        val semicolon = COST.containsMatchIn(text)
        if (!semicolon) {
            effects.filter { it.cost.isNotEmpty() }.forEach { warn("cost", "A cost where the text has no \"…: cost; effect\" clause.", it.id) }
        } else if (effects.all { it.cost.isEmpty() && it.targets.isEmpty() } && script.summon?.procs.orEmpty().none { (it as? Proc.Inherent)?.cost?.isNotEmpty() == true }) {
            warn("cost", "The text has a \"…: …; …\" clause (a cost or a target before the semicolon); no effect has a cost or targets.")
        }

        // Targets.
        val targetText = TARGET.containsMatchIn(low)
        val targets = effects.count { it.targets.isNotEmpty() }
        if (targetText && targets == 0) warn("target", "The text targets; no effect has targets.")
        if (!targetText && targets > 0) effects.filter { it.targets.isNotEmpty() }.forEach { warn("target", "Targets where the text never says \"target\": a pick that does not target goes in does.", it.id) }

        // Summoning rules.
        if (CANNOT_NORMAL.containsMatchIn(low) && script.summon?.normal != false && !card.isExtraDeck) {
            warn("summon-normal", "The text says it cannot be Normal Summoned/Set: fx.summon({normal: false}).", FxTag.PROC)
        }
        MUST_FIRST.find(low)?.let { m ->
            val how = m.groupValues[3].ifBlank { "special" }
            if (script.summon?.mustFirstBe == null) warn("summon-first", "The text says it must first be ${how.replaceFirstChar { it.uppercase() }} Summoned: fx.summon({mustFirstBe: '$how'}).", FxTag.PROC)
        }

        // Quoted names: each the script reads somewhere, by a name filter or an archetype's word.
        val filters = effects.flatMap { FxCheck.filters(it) } + FxCheck.filters(script.summon)
        val words = filters.filterIsInstance<Filter.NameHas>().map { it.word.lowercase() } + script.alsoNamed.map { it.lowercase() }
        val named = filters.filterIsInstance<Filter.Name>().mapNotNull { nameOf(it.card)?.lowercase() }
        QUOTED.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() && !it.equals(card.name, ignoreCase = true) }.distinct().forEach { q ->
            val lq = q.lowercase()
            if (named.none { it == lq } && words.none { w -> w in lq || lq in w }) {
                warn("names", "The text names \"$q\"; nothing in the script reads that name (fx.name or fx.nameHas).")
            }
        }

        // "Level N or lower", Attributes and Types in what it picks.
        val spans = filters.filterIsInstance<Filter.Level>().map { it.span }
        LEVEL_OR_LOWER.findAll(low).map { it.groupValues[1].toInt() }.distinct().forEach { n ->
            if (spans.none { it.max == n }) warn("level", "The text says \"Level $n or lower\"; no filter says fx.level(null, $n).")
        }
        LEVEL_OR_HIGHER.findAll(low).map { it.groupValues[1].toInt() }.distinct().forEach { n ->
            if (spans.none { it.min == n }) warn("level", "The text says \"Level $n or higher\"; no filter says fx.level($n, null).")
        }
        val attrs = filters.filterIsInstance<Filter.Attribute>().flatMap { it.any }.toSet()
        // A card's own Attribute is on its frame, not in its text: an Attribute in the text is one a pick or a condition reads.
        CardAttribute.entries.filter { it != CardAttribute.UNKNOWN && Regex("\\b${it.name}\\b").containsMatchIn(text) }
            .forEach { a -> if (a !in attrs) warn("attribute", "The text names ${a.name}; no filter says fx.attribute('${a.name.lowercase()}').") }
        val races = filters.filterIsInstance<Filter.Race>().flatMap { it.any.map(String::lowercase) }.toSet()
        TYPES.filter { t -> Regex("\\b${Regex.escape(t)} (monster|monsters|-type)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }
            .forEach { t -> if (t.lowercase() !in races) warn("race", "The text names $t monsters; no filter says fx.race('$t').") }

        // How many activated effects.
        val counted = EFFECT_COLON.findAll(text).count()
        val written = active.size + script.summon?.procs.orEmpty().count { it is Proc.Inherent }
        if (counted > 0 && written > 0 && counted != written && script.unsupported.isEmpty()) {
            warn("count", "The text reads as $counted effect${if (counted == 1) "" else "s"} with a \":\"; the script writes $written.")
        }
        return out
    }

    private val ONE_EFFECT = Regex("only use 1 \"[^\"]+\" effect per turn|only use 1 effect of \"[^\"]+\" per turn|only use 1 of the following effects")
    private val ONE_CARD = Regex("only activate 1 \"[^\"]+\" per turn")
    private val ONCE_PER_TURN = Regex("(^|[.\n:;]\\s*|\\(|\\s)Once per turn", RegexOption.MULTILINE)
    private val WHEN = Regex("(^|[.\n]\\s*|\\)\\s*|\\s)When [^:.;]*:", RegexOption.MULTILINE)
    private val IF = Regex("(^|[.\n]\\s*|\\)\\s*|\\s)If [^:.;]*:", RegexOption.MULTILINE)
    private val TRIGGERISH = Regex("during the (standby|end) phase|at the (start|end) of")
    private val COST = Regex(":[^:;]*;")
    private val TARGET = Regex("\\btarget(s|ed|ing)?\\b")
    private val CANNOT_NORMAL = Regex("cannot be normal summoned(/set)?")
    private val MUST_FIRST = Regex("must (first )?be ((fusion|synchro|xyz|link|ritual|special) )?summoned")
    private val QUOTED = Regex("\"([^\"]{1,80})\"")
    private val LEVEL_OR_LOWER = Regex("level (\\d{1,2}) or lower")
    private val LEVEL_OR_HIGHER = Regex("level (\\d{1,2}) or higher")
    private val EFFECT_COLON = Regex(":(?=\\s)")
}
