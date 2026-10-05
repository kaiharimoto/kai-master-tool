package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ShortcutResult
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.Purpose
import com.kaiharimoto.mastertool.core.duel.nameOf

/** What a Shortcut line asks (Phase D §5½): a card's Shortcut used, or the chain resolved as written. */
sealed interface ShortcutAsk {
    /** `u h2`, `u h2 e2`, `shortcut scout search pick=…`: [uid]'s Shortcut [effect] (null: the only one, or asked). */
    data class Use(val uid: Int, val effect: String? = null, val answers: ShortcutAnswers = ShortcutAnswers()) : ShortcutAsk

    /** `resolve by shortcut`, `resolve all by shortcut`. */
    data class Resolve(val all: Boolean = false, val answers: ShortcutAnswers = ShortcutAnswers()) : ShortcutAsk
}

/**
 * The choices a line gives with a Shortcut, so Ai (and a saved combo) answers the engine without being asked: `pick=` cards
 * (names or coordinates, commas between), `target=gy3,ob2`, `zone=m3`, `pos=` a summoned monster's position (atk, def, set), `option=` an option's words or number (or yes / no),
 * `declare=` a name, Type, Attribute or Level, `order=2,1` the order of simultaneous triggers.
 */
data class ShortcutAnswers(
    val pick: List<String> = emptyList(),
    val target: List<String> = emptyList(),
    val zone: List<String> = emptyList(),
    val option: List<String> = emptyList(),
    val declare: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    /** Each summoned monster's position, in step with the monster zones of [zone]: `atk`, `def` or `set`. */
    val pos: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = this == ShortcutAnswers()

    /** The answers as a line writes them: ` pick=a,b target=gy3 zone=m3`. */
    fun words(): String = buildString {
        fun put(key: String, values: List<String>) { if (values.isNotEmpty()) append(' ').append(key).append('=').append(values.joinToString(",")) }
        put("pick", pick)
        put("target", target)
        put("zone", zone)
        put("pos", pos)
        put("option", option)
        put("declare", declare)
        put("order", order.map { it.toString() })
    }

    companion object {
        /** The keys a line gives answers by, with the words that mean the same. */
        val KEYS: Map<String, String> = mapOf(
            "pick" to "pick", "picks" to "pick", "choose" to "pick",
            "target" to "target", "targets" to "target",
            "zone" to "zone", "zones" to "zone",
            "option" to "option", "opt" to "option",
            "declare" to "declare",
            "order" to "order",
            "pos" to "pos", "position" to "pos", "positions" to "pos",
        )

        /** Whether [word] begins an answer: `pick=…`, `zone=m3`. */
        fun starts(word: String): Boolean = word.substringBefore('=', "").lowercase() in KEYS

        /**
         * [words] split into those before the first answer, and the answers: a key's value runs to the next key, so a name
         * with spaces needs no quotes (`pick=fallen of albaz zone=m3`).
         */
        fun split(words: List<String>): Pair<List<String>, ShortcutAnswers> {
            val at = words.indexOfFirst { starts(it) }
            if (at < 0) return words to ShortcutAnswers()
            val values = LinkedHashMap<String, MutableList<String>>()
            var key: String? = null
            val run = StringBuilder()
            fun flush() {
                val k = key ?: return
                run.toString().split(',').map { it.trim().trim('"', '“', '”') }.filter { it.isNotEmpty() }.let { values.getOrPut(k) { mutableListOf() } += it }
                run.clear()
            }
            for (w in words.drop(at)) {
                if (starts(w)) {
                    flush()
                    key = KEYS.getValue(w.substringBefore('=').lowercase())
                    run.append(w.substringAfter('='))
                } else run.append(' ').append(w)
            }
            flush()
            fun of(k: String) = values[k].orEmpty()
            return words.take(at) to ShortcutAnswers(
                of("pick"), of("target"), of("zone"), of("option"), of("declare"), of("order").mapNotNull { it.toIntOrNull() }, of("pos"),
            )
        }
    }
}

/**
 * A [Chooser] that answers from a line's [answers] (Ai's op, a combo's step), reading cards by coordinate or by name as the
 * seat would name them — any copy of a name will do. A choice the answers do not settle goes to [fallback] (a saved combo's
 * first legal answer), or, with none, cancels the use and leaves [question]: the choice asked back, its options listed.
 *
 * **Never a guess** (the red team, D.md §9): a word that names cards of more than one name equally well ("Example" for
 * Example Scout and Example Tinker) settles nothing — the choice is asked back with the names it could mean, as
 * `DuelCommand.lookup` does. An exact name beats a prefix, a prefix beats word starts.
 *
 * **In step with the decisions**: `zone=` and `pos=` (and `pick=`) are read in order, including for the choices the
 * engine settled without asking ([Chooser.told]) — a combo writes every zone and position it took. A position is paired
 * with the monster zone given before it; where the zone fixes the position, a `pos=` word is taken for it only when it
 * says that very position, so a line that gives positions only where there was a choice stays aligned too.
 *
 * A target is a [Purpose.TARGET] pick, never read off the words of its `why`. A name declaration ([Decision.Declare.open])
 * takes any card of the pool by its exact name through [names], a card on the table by its name, and lists the choices
 * when a name is ambiguous.
 */
class AnswerChooser(
    answers: ShortcutAnswers,
    private val s: DuelState,
    private val seat: Int,
    private val catalog: DuelCatalog,
    private val secret: Long = 0L,
    private val fallback: Chooser? = null,
    /** The pool's cards by exact name, any case: passcodes ([Shortcuts.names]); null where the pool is not at hand. */
    private val names: ((String) -> List<Int>)? = null,
) : Chooser {
    private val picks = answers.pick.toMutableList()
    private val targets = answers.target.toMutableList()
    private val zones = answers.zone.toMutableList()
    private val options = answers.option.toMutableList()
    private val declares = answers.declare.toMutableList()
    private val order = answers.order
    private val positions = answers.pos.toMutableList()
    /** The position given with the monster zone just taken: `zone=m3 pos=def` go together. */
    private var paired: String? = null
    /** A monster zone was just taken: the next position decision is its own, and [paired] (if any) answers it. */
    private var zoneTook = false

    /** The choice asked back, in words, when the answers did not settle it. */
    var question: String? = null
        private set

    /** A word that named more than one card, said when the choice is asked back. */
    private var ambiguous: String? = null

    override fun choose(d: Decision): List<Int> {
        val a = answer(d)
        val why = ambiguous
        ambiguous = null
        if (a != null && Chooser.legal(d, a)) return a
        fallback?.choose(d)?.let { return it }
        question = (why?.let { "$it. " } ?: "") + ask(d)
        return Chooser.CANCEL
    }

    override fun told(d: Decision, answer: List<Int>) {
        when (d) {
            is Decision.Zone -> answer.singleOrNull()?.let { d.among.getOrNull(it) }?.let { z ->
                val k = zones.indexOfFirst { DuelNotation.slotCoord(z, seat)?.equals(it, ignoreCase = true) == true }
                if (k >= 0) {
                    zones.removeAt(k)
                    pair(z, d.positions)
                }
            }
            is Decision.Position -> {
                val p = answer.singleOrNull()?.let { d.among.getOrNull(it) }
                // A position settled without asking: the word its zone took is spent; with no zone before it, the next
                // word is spent when it says that position.
                if (zoneTook) paired = null
                else if (p != null) positions.firstOrNull()?.let { w -> if (positionOf(w) == p) positions.removeAt(0) }
                zoneTook = false
            }
            is Decision.Cards -> {
                val queue = queueFor(d)
                answer.mapNotNull { d.among.getOrNull(it) }.forEach { uid ->
                    val k = queue.indexOfFirst { w -> names(uid, w) }
                    if (k >= 0) queue.removeAt(k)
                }
            }
            else -> {}
        }
    }

    override fun name(d: Decision.Declare): Int? {
        if (!d.open) return null
        val w = declares.firstOrNull() ?: return null
        // A name on the table first, by the list's own words, so a declaration answered there keeps its index.
        if (d.among.any { it.equals(w.trim(), ignoreCase = true) }) return null
        val found = names?.invoke(w.trim()).orEmpty().distinct()
        return when (found.size) {
            0 -> null
            1 -> found.single().also { declares.removeAt(0) }
            else -> null.also { ambiguous = "“$w” is the name of more than one card" }
        }
    }

    /** The queue a card choice reads: a target's `target=` when it has any, else `pick=`. */
    private fun queueFor(d: Decision.Cards): MutableList<String> = if (d.purpose == Purpose.TARGET && targets.isNotEmpty()) targets else picks

    /** [z] taken with [allowed] positions: the next `pos=` word goes with it, when there is a choice or it says the fixed one. */
    private fun pair(z: Place.Zone, allowed: List<CardPosition>) {
        if (z.kind != ZoneKind.MONSTER && z.kind != ZoneKind.EMZ) return
        zoneTook = true
        val next = positions.firstOrNull()
        paired = when {
            next == null -> null
            allowed.size == 1 -> if (positionOf(next) == allowed.single()) positions.removeAt(0).let { null } else null
            else -> positions.removeAt(0)
        }
    }

    private fun answer(d: Decision): List<Int>? = when (d) {
        is Decision.Cards -> {
            val from = queueFor(d)
            if (from.isEmpty()) null else {
                // Each answer taken by the card it names best that is not chosen yet; an answer naming none stays for later.
                val chosen = mutableListOf<Int>()
                val words = from.iterator()
                while (words.hasNext() && chosen.size < d.max) {
                    val w = words.next()
                    val i = best(w, d.among.indices.filter { it !in chosen }) { d.among[it] } ?: if (ambiguous != null) return null else continue
                    chosen += i
                    words.remove()
                }
                chosen.takeIf { it.size >= d.min }
            }
        }
        is Decision.Zone -> take(zones) { w -> d.among.indexOfFirst { DuelNotation.slotCoord(it, seat)?.equals(w, ignoreCase = true) == true } }
            ?.also { a -> pair(d.among[a.single()], d.positions) }
        is Decision.Position -> {
            val mine = paired
            val fromZone = zoneTook
            paired = null
            zoneTook = false
            mine?.let { w -> positionOf(w)?.let { d.among.indexOf(it) }?.takeIf { it >= 0 }?.let(::listOf) }
                ?: if (fromZone) null else take(positions) { w -> positionOf(w)?.let { d.among.indexOf(it) } ?: -1 }
        }
        is Decision.YesNo -> take(options) { w ->
            when (w.lowercase()) {
                "yes", "y", "1" -> 1
                "no", "n", "0" -> 0
                else -> -1
            }
        }
        is Decision.Option -> take(options) { w ->
            w.toIntOrNull()?.let { it - 1 }?.takeIf { it in d.among.indices }
                ?: d.among.indexOfFirst { it.equals(w, ignoreCase = true) }.takeIf { it >= 0 }
                ?: bestWord(w, d.among)
                ?: -1
        }
        is Decision.Declare -> take(declares) { w ->
            d.among.indexOfFirst { it.equals(w.trim(), ignoreCase = true) }.takeIf { it >= 0 }
                // A name may be any card: only an exact name settles one off the table ([name]); on it, the best clear match.
                ?: (if (d.open) null else bestWord(w, d.among))
                ?: -1
        }
        is Decision.Order -> order.map { it - 1 }.takeIf { it.isNotEmpty() }
    }

    /**
     * The one of [among] (indexes, [uid] of each) that [w] names best — a coordinate exactly, else a name the seat may give
     * at the best score (exact, then a prefix, then word starts); null when none does, or when the best names more than
     * one card name ([ambiguous] says which).
     */
    private fun best(w: String, among: List<Int>, uid: (Int) -> Int): Int? {
        if (DuelNotation.parse(w) != null) return among.firstOrNull { names(uid(it), w) }
        var top = 0
        val tops = mutableListOf<Int>()
        among.forEach { i ->
            val u = uid(i)
            val card = s.cards[u] ?: return@forEach
            if (!nameable(u)) return@forEach
            val score = NameScore.of(w, catalog.nameOf(card)).takeIf { it >= 70 } ?: 0
            when {
                score > top -> { top = score; tops.clear(); tops += i }
                score == top && score > 0 -> tops += i
            }
        }
        val distinct = tops.map { s.cards[uid(it)]?.let(catalog::nameOf) }.distinct()
        if (distinct.size > 1) {
            ambiguous = "“$w” could be ${distinct.joinToString(" or ")}"
            return null
        }
        return tops.firstOrNull()
    }

    /** The one of [among] (words) that [w] names best, at the best score; null when none or more than one does. */
    private fun bestWord(w: String, among: List<String>): Int? {
        val scored = among.mapIndexed { i, a -> i to NameScore.of(w, a) }.filter { it.second >= 70 }
        val top = scored.maxOfOrNull { it.second } ?: return null
        val tops = scored.filter { it.second == top }
        if (tops.map { among[it.first].lowercase() }.distinct().size > 1) {
            ambiguous = "“$w” could be ${tops.joinToString(" or ") { among[it.first] }}"
            return null
        }
        return tops.first().first
    }

    /** The first of [queue] that [index] finds in the decision, taken out. */
    private fun take(queue: MutableList<String>, index: (String) -> Int): List<Int>? {
        val k = queue.indices.firstOrNull { index(queue[it]) >= 0 } ?: return null
        return listOf(index(queue.removeAt(k)))
    }

    /** Whether [w] names [uid]: its coordinate, or — a card the seat may name — its name. */
    private fun names(uid: Int, w: String): Boolean {
        if (DuelNotation.parse(w) != null) return DuelNotation.at(s, w, seat, secret) == uid ||
            DuelNotation.coordOf(s, uid, seat, secret)?.equals(w, ignoreCase = true) == true
        val card = s.cards[uid] ?: return false
        return nameable(uid) && NameScore.of(w, catalog.nameOf(card)) >= 70
    }

    /** A card the seat may name: one it sees, or one of its own Deck or Extra Deck, as a search shows them. */
    private fun nameable(uid: Int): Boolean {
        if (DuelSight.sees(s, uid, seat)) return true
        val card = s.cards[uid] ?: return false
        val p = s.placeOf(uid)
        return card.owner == seat && p is Place.Pile && (p.kind == PileKind.DECK || p.kind == PileKind.EXTRA)
    }

    /** A card in the question's list: its name where the seat may name it, and its coordinate where it has one. */
    private fun label(uid: Int): String {
        val coord = DuelNotation.coordOf(s, uid, seat, secret)
        val card = s.cards[uid]
        val name = if (card != null && nameable(uid)) catalog.nameOf(card) else null
        return when {
            name != null && coord != null -> "$name ($coord)"
            name != null -> name
            else -> coord ?: "a hidden card"
        }
    }

    private fun ask(d: Decision): String = when (d) {
        is Decision.Cards -> {
            val key = if (d.purpose == Purpose.TARGET) "target" else "pick"
            val n = if (d.min == d.max) "${d.min}" else "${d.min} to ${d.max}"
            "${d.why}: which $n? Say $key=…, among: ${d.among.joinToString(" · ") { label(it) }}"
        }
        is Decision.Zone -> "Which zone? Say zone=…, among: ${d.among.mapNotNull { DuelNotation.slotCoord(it, seat) }.joinToString(" · ")}"
        is Decision.Position -> "Which position? Say pos=…, among: ${d.among.joinToString(" · ") { positionWord(it) }}"
        is Decision.YesNo -> "${d.why}? Say option=yes or option=no"
        is Decision.Option -> "Which? Say option=…, among: ${d.among.joinToString(" · ")}"
        is Decision.Declare -> {
            val shown = d.among.take(DECLARE_SHOWN).joinToString(" · ") + if (d.among.size > DECLARE_SHOWN) " …" else ""
            if (d.open) "Declare which card? Say declare=… with any card's exact name" + (if (shown.isNotEmpty()) " — on the table: $shown" else "")
            else "Declare which? Say declare=…, among: $shown"
        }
        // Only the seat's own triggers are named, and only where it may name them.
        is Decision.Order -> "In which order on the chain? Say order=…, numbering: ${d.triggers.mapIndexed { i, p -> "${i + 1} ${if (nameable(p.uid)) s.cards[p.uid]?.let { catalog.nameOf(it) } ?: "a card" else "a card"}" }.joinToString(" · ")}"
    }

    companion object {
        private const val DECLARE_SHOWN = 24

        /** A position as a line writes it: `atk`, `def`, `set`. */
        fun positionWord(p: CardPosition): String = when (p) {
            CardPosition.FACE_UP_ATK -> "atk"
            CardPosition.FACE_UP_DEF -> "def"
            CardPosition.FACE_DOWN_DEF, CardPosition.FACE_DOWN_ATK -> "set"
        }

        /** The position a line's word means. */
        fun positionOf(w: String): CardPosition? = when (w.trim().lowercase()) {
            "atk", "attack", "a" -> CardPosition.FACE_UP_ATK
            "def", "defense", "defence", "d" -> CardPosition.FACE_UP_DEF
            "set", "facedown", "face-down", "fd" -> CardPosition.FACE_DOWN_DEF
            else -> null
        }
    }
}

/** A Shortcut line run (Phase D §5½): one door for the person's window, Ai's op and a combo's step. */
object ShortcutLine {

    /** [ask] made on [s] by [seat], every choice [chooser]'s. */
    fun run(ask: ShortcutAsk, shortcuts: Shortcuts, s: DuelState, seat: Int, catalog: DuelCatalog, chooser: Chooser): ShortcutResult = when (ask) {
        is ShortcutAsk.Use -> shortcuts.use(s, seat, ask.uid, ask.effect, chooser)
        is ShortcutAsk.Resolve -> shortcuts.resolve(s, seat, catalog, chooser, ask.all)
    }

    /**
     * [ask] made with the choices its line gave ([AnswerChooser]): Ai's op, a combo's step. A choice they do not settle goes
     * to [fallback]; with none, nothing is made and the choice is asked back in [ShortcutResult.problem], its options listed.
     */
    fun answered(
        ask: ShortcutAsk,
        shortcuts: Shortcuts,
        s: DuelState,
        seat: Int,
        catalog: DuelCatalog,
        secret: Long = 0L,
        fallback: Chooser? = null,
    ): ShortcutResult {
        val answers = when (ask) {
            is ShortcutAsk.Use -> ask.answers
            is ShortcutAsk.Resolve -> ask.answers
        }
        val chooser = AnswerChooser(answers, s, seat, catalog, secret, fallback, shortcuts.names)
        val r = run(ask, shortcuts, s, seat, catalog, chooser)
        return if (r.cancelled) ShortcutResult.no(chooser.question ?: "Cancelled") else r
    }
}
