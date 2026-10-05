package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.Lock

/**
 * A script read back in plain words (Phase D step 2, D.md §3.5): our own sentences, one an effect, as D.md §2.5½ shows —
 * "Effect 1 — Search: a trigger. When this card is Normal or Special Summoned, you can add 1 "Example" monster of Level
 * 1–4 from your Deck to your hand. Once per turn, by name." What the person reads beside the card's printed text, in the
 * Effects pane, the inspector's "Effects" and `fx_state`.
 *
 * Written from the data alone, never from the card's text: so the two side by side show where they disagree. A word this
 * build cannot read says so ("a step a newer build wrote"), never guesses.
 */
object FxWords {
    /** One effect, or the card's summoning rules ([FxTag.PROC]), in words. */
    data class Line(val id: String, val head: String, val text: String) {
        override fun toString(): String = "$head — $text"
    }

    /** [s] in words, one line an effect. [nameOf] names a passcode (`Filter.Name`); unknown, its number is said. */
    fun of(s: CardScript, nameOf: (Int) -> String? = { null }): List<Line> {
        val w = Writer(nameOf)
        return buildList {
            s.summon?.let { r -> w.summon(r)?.let { add(Line(FxTag.PROC, "Summoning", it)) } }
            s.effects.forEachIndexed { i, e -> add(Line(e.id, head(e, i), w.effect(e))) }
            if (s.unsupported.isNotEmpty()) add(Line("unsupported", "Not written", s.unsupported.joinToString("; ") + "."))
        }
    }

    /** [s] in words as one text, a line an effect. */
    fun text(s: CardScript, nameOf: (Int) -> String? = { null }): String = of(s, nameOf).joinToString("\n") { it.toString() }

    private fun head(e: Effect, i: Int): String {
        val n = e.id.removePrefix("e").toIntOrNull() ?: (i + 1)
        return "Effect $n" + (if (e.label.isNotBlank()) " · ${e.label}" else "")
    }

    private class Writer(val nameOf: (Int) -> String?) {
        /** The names an effect's targets are bound to: read as "the target". */
        var targetNames: Set<String> = emptySet()

        fun effect(e: Effect): String {
            targetNames = e.targets.mapNotNull { it.bind }.toSet()
            val parts = ArrayList<String>()
            parts += when (e.kind) {
                Kind.IGNITION -> "Ignition effect${from(e.from)}"
                Kind.TRIGGER -> "Trigger effect${from(e.from)}"
                Kind.QUICK -> "Quick Effect${from(e.from)}"
                Kind.ACTIVATION -> "The card's activation${from(e.from)}"
                Kind.CONTINUOUS -> "Continuous${from(e.from)}"
            }
            e.respond?.let { parts += respond(it) }
            e.condition?.let { parts += "Only if " + cond(it) }
            if (e.cost.isNotEmpty()) parts += "Cost: " + steps(e.cost)
            e.targets.forEachIndexed { i, p -> parts += (if (i == 0) "Target " else "and target ") + pick(p, verb = false) }
            val t = e.trigger
            when {
                t != null && e.does.isNotEmpty() -> parts += trigger(t) + (if (t.optional) ", you can " else ", ") + steps(e.does)
                t != null -> parts += trigger(t) + (if (t.optional) ", you can use it" else "")
                e.does.isNotEmpty() -> parts += sentence(steps(e.does))
            }
            e.leaves.forEach { parts += restriction(it, activation = e.kind != Kind.CONTINUOUS) }
            e.opt?.let { parts += opt(it) }
            if (t?.timing == Timing.WHEN && t.optional) parts += "It can miss the timing"
            if (e.sameTurn) parts += "It can be activated the turn it was Set"
            return parts.filter { it.isNotBlank() }.joinToString(". ") { sentence(it) } + "."
        }

        fun summon(r: SummonRule): String? {
            val parts = ArrayList<String>()
            if (!r.normal) parts += "Cannot be Normal Summoned or Set"
            r.tributes?.let { parts += if (it == 0) "Normal Summoned without Tributing" else "Normal Summoned with $it Tribute${if (it == 1) "" else "s"}" }
            r.mustFirstBe?.let { parts += if (it == ProcKind.INHERENT) "Must first be Special Summoned by its own procedure" else "Must first be ${procKind(it)} Summoned" }
            r.procs.forEach { parts += proc(it) }
            if (r.oncePerTurn) parts += "You can Special Summon it only once per turn"
            return parts.takeIf { it.isNotEmpty() }?.joinToString(". ") { sentence(it) }?.plus(".")
        }

        fun proc(p: Proc): String = when (p) {
            is Proc.Fusion -> "Fusion materials: " + p.materials.joinToString(" + ") { mat(it) }
            is Proc.Synchro -> "Synchro: " + mat(p.tuner) + " + " + mat(p.others)
            is Proc.Xyz -> "Xyz: ${p.n}${p.max?.let { m -> " to $m" }.orEmpty()} ${filter(p.each, "monsters")} of the same Level as its Rank"
            is Proc.Link -> "Link: ${if (p.min == p.max) "${p.min}" else "${p.min} to ${p.max}"} ${filter(p.each, "monsters")}" +
                (p.also?.let { " including ${filter(it, "monster")}" }.orEmpty())
            Proc.Ritual -> "Ritual Summoned by a Ritual Spell"
            is Proc.Inherent -> "You can Special Summon it from ${where(p.from)}" +
                (p.condition?.let { " if " + cond(it) }.orEmpty()) +
                (if (p.cost.isNotEmpty()) " by " + steps(p.cost) else "") +
                (if (p.pos != Pos.EITHER) " in ${pos(p.pos)}" else "") +
                (p.opt?.let { ". " + opt(it) }.orEmpty())
            is Proc.Unknown -> "A procedure a newer build wrote"
        }

        fun mat(m: Mat): String {
            val n = when {
                m.more -> "${m.n} or more"
                m.upTo -> "1 to ${m.n}"
                else -> "${m.n}"
            }
            return "$n ${filter(m.where, if (m.n == 1 && !m.more && !m.upTo) "monster" else "monsters")}"
        }

        fun trigger(t: Trigger): String {
            val on = t.on
            val what = if (t.self) "this card" else filter(t.about ?: Filter.Any, "a card")
            val event = when (on.event) {
                Event.SUMMONED -> "is " + summoned(on.summon, "Summoned")
                Event.NORMAL_SUMMONED -> "is Normal Summoned"
                Event.SPECIAL_SUMMONED -> "is " + summoned(on.summon, "Special Summoned")
                Event.FLIPPED -> "is flipped face-up"
                Event.SENT_TO_GY -> "is sent to the GY"
                Event.DESTROYED -> "is destroyed"
                Event.BANISHED -> "is banished"
                Event.ADDED_TO_HAND -> "is added to the hand"
                Event.DISCARDED -> "is discarded"
                Event.DETACHED -> "is detached"
                Event.MATERIAL -> "is used as " + (on.summon.takeIf { it.isNotEmpty() }?.joinToString(" or ") { procKind(it) }?.let { "$it " }.orEmpty()) + "material"
                Event.LEFT_FIELD -> "leaves the field"
                Event.DRAWN -> "is drawn"
                Event.STANDBY -> "STANDBY"
                Event.END_PHASE -> "END"
                Event.ACTIVATED -> "is activated"
            }
            val opening = if (t.timing == Timing.WHEN) "When" else "If"
            val head = when (on.event) {
                Event.STANDBY -> "During the Standby Phase"
                Event.END_PHASE -> "During the End Phase"
                else -> "$opening $what $event" +
                    (on.from?.let { " from ${where(it)}" }.orEmpty()) +
                    (on.cause?.let { " " + cause(it) }.orEmpty())
            }
            return head
        }

        private fun summoned(kinds: List<ProcKind>, fallback: String): String =
            if (kinds.isEmpty()) fallback else kinds.joinToString(" or ") { procKind(it) } + " Summoned"

        fun respond(r: Respond): String = buildString {
            append("In answer to ")
            append(when (r.seat) { Rel.YOU -> "your"; Rel.THEM -> "their"; Rel.ANY -> "a" })
            append(" activation")
            r.about?.let { append(" of ").append(filter(it, "a card")) }
            if (r.includes.isNotEmpty()) append(" that ").append(r.includes.joinToString(" or ") { includes(it) })
        }

        fun opt(o: Opt): String = when (o) {
            is Opt.ByName -> when {
                o.group == Opt.CARD -> "You can activate only 1 of this card a turn"
                o.group != null -> "Once per turn, by name, shared with the other effects of “${o.group}”"
                o.times > 1 -> "${o.times} times a turn, by name"
                else -> "Once per turn, by name"
            } + (if (o.activate && o.group != Opt.CARD) " (a negated activation does not count)" else "")
            Opt.PerCopy -> "Once per turn (this copy)"
            Opt.PerDuel -> "Once per Duel"
            is Opt.Unknown -> "A once-per-turn rule a newer build wrote"
        }

        fun restriction(r: Restriction, activation: Boolean): String {
            val who = when (r.seat) { Rel.YOU -> "you cannot"; Rel.THEM -> "they cannot"; Rel.ANY -> "neither player can" }
            val what = when (r.ban) {
                Ban.SPECIAL_SUMMON -> "Special Summon"
                Ban.SPECIAL_SUMMON_FROM_EXTRA -> "Special Summon from the Extra Deck"
                Ban.NORMAL_SUMMON -> "Normal Summon or Set"
                Ban.ACTIVATE -> "activate cards or effects"
            }
            val except = r.except?.let { " except " + filter(it, "cards") }.orEmpty()
            val until = when (r.until) {
                Lock.UNTIL_TURN -> if (activation) " the turn you activate it" else " this turn"
                Lock.UNTIL_CHAIN -> " while this chain resolves"
                Lock.UNTIL_DUEL -> " for the rest of the Duel"
                else -> " until ${r.until}"
            }
            return (if (activation) "" else "While it is face-up, ") + "$who $what$except$until"
        }

        fun steps(list: List<Step>): String {
            val out = StringBuilder()
            list.forEachIndexed { i, s ->
                if (i > 0) out.append(when (s.link) {
                    Join.AND -> ", and "
                    Join.AND_IF_YOU_DO -> ", and if you do, "
                    Join.THEN -> ", then "
                    Join.ALSO -> ". Also, after that, "
                    Join.WITH -> ". Also, "
                })
                out.append(op(s.op))
            }
            return out.toString()
        }

        fun op(o: Op): String = when (o) {
            is Op.Move -> "move ${pick(o.pick)} to ${dest(o.to)}" + (if (o.faceDown) " face-down" else "")
            is Op.Add -> "add ${pick(o.pick)} to your hand"
            is Op.Send -> "send ${pick(o.pick)} to the GY"
            is Op.Discard -> "discard ${pick(o.pick)}"
            is Op.Destroy -> "destroy ${pick(o.pick)}"
            is Op.Banish -> "banish ${pick(o.pick)}" + (if (o.faceDown) " face-down" else "")
            is Op.Tribute -> "Tribute ${pick(o.pick)}"
            is Op.Return -> "return ${pick(o.pick)} to ${dest(o.to)}"
            is Op.Draw -> (if (o.rel == Rel.THEM) "they draw " else "draw ") + "${o.n} card${if (o.n == 1) "" else "s"}"
            is Op.Shuffle -> "shuffle ${rel(o.rel)} ${area(o.pile, single = true)}"
            is Op.Reveal -> "reveal ${pick(o.pick)}"
            is Op.SpecialSummon -> "Special Summon ${pick(o.pick)}" + (if (o.pos != Pos.EITHER) " in ${pos(o.pos)}" else "")
            is Op.FusionSummon -> "Fusion Summon ${filter(o.fusion, "Fusion Monster")} using materials from ${spots(o.materialsFrom)}"
            is Op.RitualSummon -> "Ritual Summon ${filter(o.ritual, "Ritual Monster")} from ${spots(o.from)} by Tributing from ${spots(o.tributesFrom)} " +
                (if (o.levels == LevelRule.EQUAL) "Levels equal to its Level" else "Levels at least its Level")
            is Op.SynchroSummon -> "Synchro Summon ${filter(o.f, "Synchro Monster")}"
            is Op.XyzSummon -> "Xyz Summon ${filter(o.f, "Xyz Monster")}"
            is Op.LinkSummon -> "Link Summon ${filter(o.f, "Link Monster")}"
            is Op.Attach -> "attach ${pick(o.pick)} to ${ref(o.to)} as material"
            is Op.Detach -> "detach ${o.n} material${if (o.n == 1) "" else "s"} from ${ref(o.from)}"
            is Op.Token -> "Special Summon ${o.n} “${o.name}”${if (o.n == 1) "" else "s"}" +
                (listOfNotNull(o.attribute?.name, o.race, o.level?.let { "Level $it" }).takeIf { it.isNotEmpty() }?.joinToString(" ", " (", ")").orEmpty()) +
                " ${o.atk}/${o.def}" + (if (o.rel == Rel.THEM) " to their field" else "") + (if (o.pos != Pos.EITHER) " in ${pos(o.pos)}" else "")
            is Op.Negate -> "negate " + (if (o.what == NegWhat.ACTIVATION) "the activation" else "the effect") +
                (if (o.link == LinkRef.NEWEST) " of the newest link" else "")
            is Op.ChangeLevel -> "make ${pick(o.pick)} " + (o.to?.let { "Level ${num(it)}" } ?: o.by?.let { "${num(it)} Levels ${if ((it as? Num.Const)?.n?.let { n -> n < 0 } == true) "lower" else "higher"}" }.orEmpty()) +
                (if (o.until == Lock.UNTIL_TURN) " until the end of the turn" else "")
            is Op.Lp -> when ((o.delta as? Num.Const)?.n?.let { it < 0 }) {
                true -> "${rel(o.rel, subject = true)} take${if (o.rel == Rel.YOU) "" else "s"} ${-((o.delta as Num.Const).n)} damage".replace("you takes", "you take")
                else -> "${rel(o.rel, subject = true)} gain${if (o.rel == Rel.YOU) "" else "s"} ${num(o.delta)} LP"
            }
            is Op.PayLp -> "pay ${num(o.n)} LP"
            is Op.Counter -> "${if (o.delta >= 0) "place" else "remove"} ${kotlin.math.abs(o.delta)} ${o.kind.ifBlank { "" }}${if (o.kind.isBlank()) "" else " "}counter${if (kotlin.math.abs(o.delta) == 1) "" else "s"} ${if (o.delta >= 0) "on" else "from"} ${pick(o.pick)}"
            is Op.NormalSummonAgain -> "you can Normal Summon 1 more ${filter(o.filter, "monster")} this turn"
            is Op.Choose -> (if (o.who == Rel.THEM) "they choose one: " else "choose one: ") +
                o.options.mapIndexed { i, opt -> (o.labels.getOrNull(i)?.let { "“$it”: " }.orEmpty()) + steps(opt) }.joinToString("; or ")
            is Op.If -> "if ${cond(o.cond)}, ${steps(o.then)}" + (if (o.otherwise.isNotEmpty()) "; otherwise, ${steps(o.otherwise)}" else "")
            is Op.Restrict -> restriction(o.restriction, activation = false).removePrefix("While it is face-up, ").replace("this turn", "for the rest of this turn")
            is Op.Declare -> "declare a ${o.kind.name.lowercase()}" + (o.among?.let { " of ${filter(it, "cards")}" }.orEmpty())
            is Op.Unknown -> "(a step a newer build wrote)"
        }

        /** "1 "Example" monster of Level 1–4 from your Deck". */
        fun pick(p: Pick, verb: Boolean = true): String {
            if (p.ref != null) return ref(p.ref)
            val many = p.n != 1 || p.all
            val count = when {
                p.all -> "every"
                p.top -> "the top ${p.n}"
                p.upTo -> "up to ${p.n}"
                else -> "${p.n}"
            }
            val monsters = p.from.isNotEmpty() && p.from.all { it.area == Area.MONSTERS }
            val noun = if (monsters) "monster" else "card"
            val what = filter(p.where, if (many && !p.all) noun + "s" else noun)
            val from = if (p.from.isEmpty()) "" else if (p.top) " of ${spots(p.from)}" else " " + placesPhrase(p.from)
            val who = if (p.who == Rel.THEM) " (they choose)" else ""
            return "$count $what$from$who".trim()
        }

        private fun placesPhrase(from: List<Spot>): String {
            val onField = from.all { it.area == Area.MONSTERS || it.area == Area.SPELLS || it.area == Area.FIELD }
            return if (onField) {
                val seat = from.map { it.rel }.distinct().singleOrNull()
                when (seat) {
                    Rel.YOU -> "you control"
                    Rel.THEM -> "they control"
                    else -> "on the field"
                } + if (from.size == 1 && from[0].area != Area.FIELD) "" else ""
            } else "from ${spots(from)}"
        }

        fun spots(from: List<Spot>): String {
            if (from.isEmpty()) return "nowhere"
            val bySeat = from.groupBy { it.rel }
            return bySeat.entries.joinToString(" or ") { (rel, list) ->
                val areas = list.map { area(it.area, single = true) }
                rel(rel) + " " + when (areas.size) {
                    1 -> areas[0]
                    else -> areas.dropLast(1).joinToString(", ") + " or " + areas.last()
                }
            }
        }

        fun rel(r: Rel, subject: Boolean = false): String = when (r) {
            Rel.YOU -> if (subject) "you" else "your"
            Rel.THEM -> if (subject) "they" else "their"
            Rel.ANY -> if (subject) "each player" else "either player's"
        }

        fun area(a: Area, single: Boolean): String = when (a) {
            Area.HAND -> "hand"
            Area.DECK -> "Deck"
            Area.EXTRA -> "Extra Deck"
            Area.GY -> "GY"
            Area.BANISHED -> "banishment"
            Area.MONSTERS -> "Monster Zones"
            Area.SPELLS -> "Spell & Trap Zones"
            Area.FIELD -> "field"
            Area.MATERIALS -> "Xyz materials"
        }

        fun where(w: Where): String = when (w) {
            Where.HAND -> "the hand"
            Where.DECK -> "the Deck"
            Where.EXTRA -> "the Extra Deck"
            Where.MONSTER_ZONE -> "the Monster Zone"
            Where.SPELL_ZONE -> "the Spell & Trap Zone"
            Where.FIELD_ZONE -> "the Field Zone"
            Where.GY -> "the GY"
            Where.BANISHED -> "banishment"
        }

        fun from(ws: Set<Where>): String = if (ws.isEmpty()) " (used from nowhere)" else " from " + ws.sortedBy { it.ordinal }.joinToString(" or ") { where(it) }

        fun dest(d: Dest): String = when (d) {
            Dest.HAND -> "the hand"
            Dest.DECK_TOP -> "the top of the Deck"
            Dest.DECK_BOTTOM -> "the bottom of the Deck"
            Dest.DECK_SHUFFLED -> "the Deck (shuffled in)"
            Dest.EXTRA -> "the Extra Deck"
            Dest.GY -> "the GY"
            Dest.BANISHED -> "banishment"
            Dest.MONSTER_ZONE -> "a Monster Zone"
            Dest.SPELL_ZONE -> "a Spell & Trap Zone"
            Dest.FIELD_ZONE -> "the Field Zone"
        }

        fun pos(p: Pos): String = when (p) {
            Pos.ATTACK -> "Attack Position"
            Pos.DEFENSE -> "Defense Position"
            Pos.SET -> "face-down Defense Position"
            Pos.EITHER -> "either position"
        }

        fun ref(r: String): String = when (r) {
            Pick.SELF -> "this card"
            Pick.TARGETS -> if (targetNames.size <= 1) "the target" else "the targets"
            in targetNames -> "the target"
            else -> "that card"
        }

        fun cause(c: Cause): String = when (c) {
            Cause.COST -> "as a cost"
            Cause.EFFECT -> "by a card effect"
            Cause.MATERIAL -> "as material"
            Cause.BATTLE -> "by battle"
            Cause.TRIBUTE -> "as a Tribute"
        }

        fun procKind(k: ProcKind): String = when (k) {
            ProcKind.NORMAL -> "Normal"
            ProcKind.TRIBUTE -> "Tribute"
            ProcKind.FLIP -> "Flip"
            ProcKind.SPECIAL -> "Special"
            ProcKind.FUSION -> "Fusion"
            ProcKind.SYNCHRO -> "Synchro"
            ProcKind.XYZ -> "Xyz"
            ProcKind.LINK -> "Link"
            ProcKind.RITUAL -> "Ritual"
            ProcKind.PENDULUM -> "Pendulum"
            ProcKind.INHERENT -> "Special"
        }

        fun includes(i: Includes): String = when (i) {
            Includes.SEARCH -> "adds a card from the Deck"
            Includes.SALVAGE -> "adds a card from the GY or banishment"
            Includes.SPECIAL_SUMMON -> "Special Summons"
            Includes.SEND_FROM_DECK -> "sends a card from the Deck"
            Includes.DRAW -> "draws"
            Includes.DESTROY -> "destroys"
            Includes.BANISH -> "banishes"
            Includes.RETURN -> "returns a card"
            Includes.NEGATE -> "negates"
            Includes.DISCARD -> "discards"
        }

        fun num(n: Num): String = when (n) {
            is Num.Const -> "${n.n}"
            is Num.Count -> "the number of ${filter(n.where, "cards")} in ${spots(n.from)}"
            is Num.Of -> "${ref(n.ref)}'s ${stat(n.stat)}"
            is Num.Unknown -> "(a number a newer build wrote)"
        }

        fun stat(s: Stat): String = when (s) {
            Stat.LEVEL -> "Level"
            Stat.RANK -> "Rank"
            Stat.LINK -> "Link Rating"
            Stat.ATK -> "ATK"
            Stat.DEF -> "DEF"
            Stat.NAME -> "name"
            Stat.ATTRIBUTE -> "Attribute"
            Stat.RACE -> "Type"
        }

        fun cmp(c: Cmp): String = when (c) {
            Cmp.GE -> "at least"
            Cmp.LE -> "at most"
            Cmp.EQ -> "exactly"
            Cmp.GT -> "more than"
            Cmp.LT -> "fewer than"
            Cmp.NE -> "other than"
        }

        fun cond(c: Cond, d: Int = 0): String = if (d > FxWalk.DEEPEST) "…" else when (c) {
            is Cond.Controls -> "${rel(c.seat, subject = true)} control${if (c.seat == Rel.YOU) "" else "s"} ${num(c.n).let { if (it == "1") "" else "$it " }}${filter(c.where, "card")}".replace("they controls", "they control").replace("  ", " ")
            is Cond.NoMonsters -> "${rel(c.seat, subject = true)} control${if (c.seat == Rel.YOU) "" else "s"} no monsters".replace("they controls", "they control")
            is Cond.Count -> "${spots(c.from)} hold ${cmp(c.cmp)} ${num(c.n)} ${filter(c.where, "cards")}"
            is Cond.Phase -> "it is the " + c.any.sortedBy { it.ordinal }.joinToString(" or ") { phase(it) }
            is Cond.Turn -> if (c.whose == Rel.YOU) "it is your turn" else if (c.whose == Rel.THEM) "it is their turn" else "it is either turn"
            Cond.ChainEmpty -> "nothing is on the chain"
            is Cond.Newest -> "the newest link is ${rel(c.seat)} activation" + (c.about?.let { " of ${filter(it, "a card")}" }.orEmpty()) +
                (if (c.includes.isNotEmpty()) " that " + c.includes.joinToString(" or ") { includes(it) } else "")
            is Cond.ThisTurn -> "this card was ${c.event.name.lowercase().replace('_', ' ')} this turn"
            is Cond.Lp -> "${rel(c.seat)} LP are ${cmp(c.cmp)} ${num(c.n)}"
            is Cond.Compare -> "${num(c.left)} is ${cmp(c.cmp)} ${num(c.right)}"
            is Cond.All -> c.all.joinToString(" and ") { cond(it, d + 1) }
            is Cond.AnyOf -> c.any.joinToString(" or ") { cond(it, d + 1) }
            is Cond.Not -> "not (" + cond(c.not, d + 1) + ")"
            is Cond.Unknown -> "(a condition a newer build wrote)"
        }

        fun phase(p: DuelPhase): String = p.label + " Phase"

        /** A filter as a noun phrase: "“Example” monster of Level 1–4", with [noun] when it names no kind. */
        fun filter(f: Filter, noun: String, d: Int = 0): String {
            if (d > FxWalk.DEEPEST) return noun
            val parts = if (f is Filter.All) f.all else listOf(f)
            val adjectives = ArrayList<String>()
            val names = ArrayList<String>()
            var kind: String? = null
            val after = ArrayList<String>()
            parts.forEach { x ->
                when (x) {
                    Filter.Any -> {}
                    Filter.Self -> after += "(this card)"
                    Filter.NotSelf -> after += "other than this card"
                    is Filter.Name -> names += "“${nameOf(x.card) ?: x.card.toString()}”"
                    is Filter.NameHas -> names += "“${x.word}”"
                    is Filter.Kind -> kind = (x.sub?.let { "$it " }.orEmpty()) + x.type.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (it == "Monster") "monster" else it }
                    is Filter.Frame -> adjectives += x.frame.name.lowercase().replaceFirstChar { it.uppercase() }
                    is Filter.Attribute -> adjectives += x.any.joinToString(" or ") { it.name }
                    is Filter.Race -> adjectives += x.any.joinToString(" or ")
                    is Filter.Level -> after += "of Level ${span(x.span)}"
                    is Filter.Rank -> after += "of Rank ${span(x.span)}"
                    is Filter.LinkRating -> after += "of Link Rating ${span(x.span)}"
                    is Filter.Atk -> after += "with ${span(x.span)} ATK"
                    is Filter.Def -> after += "with ${span(x.span)} DEF"
                    Filter.FaceUp -> adjectives.add(0, "face-up")
                    Filter.FaceDown -> adjectives.add(0, "face-down")
                    is Filter.Controller -> after += when (x.rel) { Rel.YOU -> "you control"; Rel.THEM -> "they control"; Rel.ANY -> "either player controls" }
                    is Filter.Same -> after += "with the same ${stat(x.stat)} as ${ref(x.ref)}"
                    is Filter.Lowest -> adjectives.add(0, "the lowest-${stat(x.stat)}")
                    is Filter.Highest -> adjectives.add(0, "the highest-${stat(x.stat)}")
                    is Filter.All -> after += "(" + filter(x, noun, d + 1) + ")"
                    is Filter.AnyOf -> after += "that is " + x.any.joinToString(" or ") { filter(it, "card", d + 1) }
                    is Filter.Not -> if (x.not is Filter.Frame || x.not is Filter.Attribute || x.not is Filter.Race) adjectives += "non-" + filter(x.not, "", d + 1).trim()
                        else after += "that is not " + filter(x.not, "card", d + 1)
                    is Filter.Declared -> after += "with what was declared${if (x.ref != Op.Declare.DECLARED) " as ${ref(x.ref)}" else ""}"
                    is Filter.Unknown -> after += "(a filter a newer build wrote)"
                }
            }
            val head = (adjectives + names + listOf(kind ?: noun)).joinToString(" ")
            return (listOf(head) + after).joinToString(" ")
        }

        fun span(s: Span): String = when {
            s.min != null && s.max != null && s.min == s.max -> "${s.min}"
            s.min != null && s.max != null -> "${s.min}–${s.max}"
            s.min != null -> "${s.min} or more"
            s.max != null -> "${s.max} or less"
            else -> "any"
        }

        fun sentence(s: String): String = s.trim().replaceFirstChar { it.uppercase() }
    }
}
