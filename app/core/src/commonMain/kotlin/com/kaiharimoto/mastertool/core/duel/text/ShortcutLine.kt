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
 */
class AnswerChooser(
    answers: ShortcutAnswers,
    private val s: DuelState,
    private val seat: Int,
    private val catalog: DuelCatalog,
    private val secret: Long = 0L,
    private val fallback: Chooser? = null,
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

    /** The choice asked back, in words, when the answers did not settle it. */
    var question: String? = null
        private set

    override fun choose(d: Decision): List<Int> {
        val a = answer(d)
        if (a != null && Chooser.legal(d, a)) return a
        fallback?.choose(d)?.let { return it }
        question = ask(d)
        return Chooser.CANCEL
    }

    private fun answer(d: Decision): List<Int>? = when (d) {
        is Decision.Cards -> {
            val targeting = d.why.lowercase().startsWith("target")
            val from = if (targeting && targets.isNotEmpty()) targets else picks
            if (from.isEmpty()) null else {
                // Each answer taken by the first card it names that is not chosen yet; an answer naming none stays for later.
                val chosen = mutableListOf<Int>()
                val words = from.iterator()
                while (words.hasNext() && chosen.size < d.max) {
                    val w = words.next()
                    val i = d.among.indices.firstOrNull { k -> k !in chosen && names(d.among[k], w) }
                    if (i != null) { chosen += i; words.remove() }
                }
                chosen.takeIf { it.size >= d.min }
            }
        }
        is Decision.Zone -> take(zones) { w -> d.among.indexOfFirst { DuelNotation.slotCoord(it, seat)?.equals(w, ignoreCase = true) == true } }
            ?.also { a -> if (d.among[a.single()].let { it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ }) paired = positions.removeFirstOrNull() }
        is Decision.Position -> {
            val mine = paired
            paired = null
            mine?.let { w -> positionOf(w)?.let { d.among.indexOf(it) }?.takeIf { it >= 0 }?.let(::listOf) }
                ?: take(positions) { w -> positionOf(w)?.let { d.among.indexOf(it) } ?: -1 }
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
                ?: d.among.indexOfFirst { NameScore.of(w, it) >= 70 }
        }
        is Decision.Declare -> take(declares) { w ->
            d.among.indexOfFirst { it.equals(w, ignoreCase = true) }.takeIf { it >= 0 } ?: d.among.indexOfFirst { NameScore.of(w, it) >= 70 }
        }
        is Decision.Order -> order.map { it - 1 }.takeIf { it.isNotEmpty() }
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
            val key = if (d.why.lowercase().startsWith("target")) "target" else "pick"
            val n = if (d.min == d.max) "${d.min}" else "${d.min} to ${d.max}"
            "${d.why}: which $n? Say $key=…, among: ${d.among.joinToString(" · ") { label(it) }}"
        }
        is Decision.Zone -> "Which zone? Say zone=…, among: ${d.among.mapNotNull { DuelNotation.slotCoord(it, seat) }.joinToString(" · ")}"
        is Decision.Position -> "Which position? Say pos=…, among: ${d.among.joinToString(" · ") { positionWord(it) }}"
        is Decision.YesNo -> "${d.why}? Say option=yes or option=no"
        is Decision.Option -> "Which? Say option=…, among: ${d.among.joinToString(" · ")}"
        is Decision.Declare -> "Declare which? Say declare=…, among: ${d.among.take(DECLARE_SHOWN).joinToString(" · ")}${if (d.among.size > DECLARE_SHOWN) " …" else ""}"
        is Decision.Order -> "In which order on the chain? Say order=…, numbering: ${d.triggers.mapIndexed { i, p -> "${i + 1} ${s.cards[p.uid]?.let { catalog.nameOf(it) } ?: "a card"}" }.joinToString(" · ")}"
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
        val chooser = AnswerChooser(answers, s, seat, catalog, secret, fallback)
        val r = run(ask, shortcuts, s, seat, catalog, chooser)
        return if (r.cancelled) ShortcutResult.no(chooser.question ?: "Cancelled") else r
    }
}
