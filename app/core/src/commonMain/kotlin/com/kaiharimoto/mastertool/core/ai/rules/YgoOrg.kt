package com.kaiharimoto.mastertool.core.ai.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * YGOrganization's card database (db.ygoresources.com): Konami's own OCG documentation —
 * the FAQ notes on a card and the Q&A entries — with YGOrganization's English translations.
 * It is the strongest source of rulings there is, with one caveat kai gave: it is the OCG's,
 * so the TCG may differ, and the differences must be said. [CAVEAT] goes with every answer.
 *
 * The addresses to ask and the JSON they answer with, read; the fetching is the caller's.
 * The site asks to be asked only for what is needed, and cached: the English name index once
 * (cached), then the one card, then a handful of its Q&As ([pick]) — never the whole database.
 *
 * Konami's ids are not passcodes: a name goes to an id through [Index]. Card references in the
 * text are written `<<id>>`; [names] puts the card's English name in their place.
 *
 * Every reader is forgiving: a shape it does not expect is a failed [Result] whose message says
 * so in words (an `IllegalStateException`, as `Unreachable.of` shows it), never a crash.
 */
object YgoOrg {
    const val BASE = "https://db.ygoresources.com"

    /** Where YGOrganization says what its database is, and is not, for the TCG. */
    const val NOT_TCG = "https://db.ygorganization.com/about/not_tcg"

    const val ATTRIBUTION = "Source: YGOrganization's card database (db.ygoresources.com) — Konami's OCG FAQ and Q&A, " +
        "translated into English by YGOrganization."

    /**
     * The caveat on every answer from here, in our own words (kai: "it's based on the OCG, so minor
     * differences may exist and exceptions should be noted").
     */
    const val CAVEAT = "These are Konami's OCG (Japanese) rulings, translated by YGOrganization. TCG rulings usually match, " +
        "but they can differ: where a TCG ruling (Konami's TCG rulings, or the TCG section on Yugipedia) disagrees, the TCG " +
        "one stands for TCG play. Say which game a ruling comes from, and name any exception marked here " +
        "(\"TCG caveat\"). An unmarked entry is not a promise that the TCG agrees ($NOT_TCG). At an event the head judge " +
        "has the final word."

    const val INDEX_URL = "$BASE/data/idx/card/name/en"

    fun cardUrl(id: Int): String = "$BASE/data/card/$id"

    fun qaUrl(id: Int): String = "$BASE/data/qa/$id"

    /** The pages a person reads, for a link in the answer. */
    fun cardPage(id: Int): String = "$BASE/card#$id"

    fun qaPage(id: Int): String = "$BASE/qa#$id"

    /** At most this many Q&As are read for one question (each a request, if not cached). */
    const val MAX_QAS = 8

    // ---- the name index ---------------------------------------------------------------------

    /**
     * The English name index, `{ "<name>": [konamiId, …], … }`, both ways. A card can have several
     * names (an old translation, a quoted form, a no-break space); [id] looks a name up exactly,
     * then ignoring case and spacing, then by its letters and digits alone.
     */
    class Index internal constructor(private val byName: Map<String, List<Int>>) {
        private val folded: Map<String, Int> by lazy { firstBy(::fold) }
        private val loose: Map<String, Int> by lazy { firstBy(::letters) }
        private val byId: Map<Int, List<String>> by lazy {
            val m = LinkedHashMap<Int, MutableList<String>>()
            byName.forEach { (name, ids) -> ids.forEach { m.getOrPut(it) { mutableListOf() } += name } }
            m
        }

        val size: Int get() = byName.size

        fun id(name: String): Int? {
            val n = name.trim()
            byName[n]?.firstOrNull()?.let { return it }
            folded[fold(n)]?.let { return it }
            return letters(n).takeIf { it.isNotEmpty() }?.let { loose[it] }
        }

        /** Every name the index gives [id], in the index's order. */
        fun names(id: Int): List<String> = byId[id].orEmpty()

        /** One name for [id]: the first that [prefer] knows (the app's own card names), else the first. */
        fun name(id: Int, prefer: (String) -> Boolean = { false }): String? {
            val all = names(id).map { it.replace(NBSP, ' ') }
            return all.firstOrNull(prefer) ?: all.firstOrNull()
        }

        private fun firstBy(key: (String) -> String): Map<String, Int> {
            val m = HashMap<String, Int>()
            byName.forEach { (name, ids) -> ids.firstOrNull()?.let { id -> key(name).takeIf { it.isNotEmpty() }?.let { if (it !in m) m[it] = id } } }
            return m
        }
    }

    private const val NBSP = '\u00A0'

    private fun fold(s: String): String =
        s.replace(NBSP, ' ').trim().removeSurrounding("\"").lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun letters(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun index(json: String): Result<Index> = read(json, "name index") { root ->
        val out = LinkedHashMap<String, List<Int>>()
        root.forEach { (name, ids) ->
            val list = (ids as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
            if (list.isNotEmpty()) out[name] = list
        }
        if (out.isEmpty()) error("YGOrganization's name index came back without any cards.")
        Index(out)
    }

    // ---- a card and its FAQ -----------------------------------------------------------------

    /** One FAQ note: English, or Konami's Japanese where it is not translated yet; [tcg] is YGOrganization's warning. */
    data class Note(val text: String, val translated: Boolean, val tcg: String? = null)

    /** The notes on one part of the card's text ([label]; null for the card as a whole). */
    data class Section(val label: String?, val notes: List<Note>)

    data class Card(
        val id: Int,
        val name: String?,
        val sections: List<Section>,
        /** Konami's FAQ date and the translation's, as the site gives them. */
        val faqDate: String?,
        val translatedDate: String?,
        /** Older English notes no longer in Konami's database: left out, as the site leaves them out. */
        val outdatedNotes: Int,
        val qaIds: List<Int>,
    )

    fun card(json: String): Result<Card> = read(json, "card") { root ->
        val id = root.int("cardId") ?: error("YGOrganization's card answer has no card id.")
        val name = ((root["cardData"] as? JsonObject)?.obj("en") ?: (root["cardData"] as? JsonObject)?.obj("ja"))?.str("name")
        val faq = root.obj("faqData")
        var outdated = 0
        val sections = mutableListOf<Section>()
        for ((key, pendulum) in listOf("entries" to false, "pendEntries" to true)) {
            val entries = faq?.obj(key) ?: continue
            for (effect in entries.keys.mapNotNull { it.toIntOrNull() }.sorted()) {
                val list = (entries[effect.toString()] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                if (list.isEmpty()) continue
                var rest = list
                var label: String? = when {
                    pendulum && effect > 0 -> "About the Pendulum Effect's ${numeral(effect)} part:"
                    pendulum -> "About the Pendulum Effect:"
                    effect > 0 -> "About the effect's ${numeral(effect)} part:"
                    else -> null
                }
                val first = list.first()
                if (effect > 0 && isLabel(first.str("ja"))) {
                    label = first.str("en") ?: first.str("ja")
                    rest = list.drop(1)
                }
                val notes = rest.mapNotNull { e ->
                    val ja = e.str("ja")
                    val en = e.str("en")
                    // No Japanese: a note Konami's database no longer has. The site shows it only to its editors.
                    if (ja == null) {
                        outdated++
                        return@mapNotNull null
                    }
                    val tcg = e.obj("note")?.takeIf { it.str("type") == "invalid_tcg" }?.let { it.str("body") ?: "" }
                    Note(en ?: ja, translated = en != null, tcg = tcg)
                }
                if (notes.isNotEmpty()) sections += Section(label, notes)
            }
        }
        val meta = faq?.obj("meta")
        val qas = (root["qaIndex"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        Card(id, name, sections, meta?.obj("ja")?.str("date"), meta?.obj("en")?.str("date"), outdated, qas)
    }

    /** `【①の効果について】`: a section's own heading, as the site reads it. */
    private fun isLabel(ja: String?): Boolean =
        ja != null && ((ja.startsWith("【") && ja.endsWith("】")) || (ja.startsWith("【『") && "』】" in ja))

    private fun numeral(n: Int): String = if (n in 1..20) ('\u2460' + (n - 1)).toString() else "#$n"

    // ---- a Q&A ------------------------------------------------------------------------------

    /**
     * Where a Q&A's English stands against Konami's, in the site's own terms (its `TL_STATUSES`).
     * [shown] false: the entry is not to be read as a ruling at all.
     */
    enum class Status(val word: String, val meaning: String, val shown: Boolean = true) {
        CONFIRMED("up to date", "the English matches Konami's current entry"),
        UNCONFIRMABLE("unconfirmable", "a legacy translation with no record of its source; Konami's entry may have changed since"),
        OUTDATED("outdated", "Konami edited the entry after it was translated, so Konami's Japanese is given instead"),
        UNTRANSLATED("untranslated", "no English yet, so Konami's Japanese is given"),
        RETRACTED("retracted", "Konami deleted the entry", shown = false),
        UNKNOWN("not stated", "the translation's status is not given"),
        ;

        companion object {
            fun of(raw: String?): Status = when (raw) {
                "confirmed" -> CONFIRMED
                "unconfirmable" -> UNCONFIRMABLE
                "outdated" -> OUTDATED
                "zombie" -> RETRACTED
                "untranslated" -> UNTRANSLATED
                else -> UNKNOWN
            }
        }
    }

    data class Qa(
        val id: Int,
        val cards: List<Int>,
        val question: String,
        val answer: String,
        /** Konami's date for the entry (its last update), as `yyyy-mm-dd`. */
        val date: String?,
        val status: Status,
        /** English, or Konami's Japanese. */
        val translated: Boolean,
        /** YGOrganization's warnings that this may not hold in the TCG. */
        val tcg: List<String> = emptyList(),
        /** What this entry is a precedent for, if YGOrganization marked it. */
        val precedent: List<String> = emptyList(),
    )

    fun qa(json: String): Result<Qa> = read(json, "Q&A") { root ->
        val data = root.obj("qaData") ?: error("YGOrganization's Q&A answer has no Q&A in it.")
        val en = data.obj("en")
        val ja = data.obj("ja")
        val id = en?.int("id") ?: ja?.int("id") ?: error("YGOrganization's Q&A answer has no id.")
        val status = if (en == null) Status.UNTRANSLATED else Status.of(en.str("translationStatus"))
        // The site's own choice: an outdated or retracted English is not shown; Konami's Japanese stands in.
        val useEnglish = en != null && status != Status.OUTDATED && status != Status.RETRACTED && en.str("answer") != null
        val text = if (useEnglish) en else ja
        val question = text?.str("question") ?: text?.str("title")
        val answer = text?.str("answer")
        if ((question == null || answer == null) && status != Status.RETRACTED) error("YGOrganization's Q&A $id has no question or answer.")
        val date = ja?.obj("thisSrc")?.str("date") ?: en?.obj("tlSrc")?.str("date") ?: en?.obj("thisSrc")?.str("date")
        val notes = root.obj("notes")
        fun bodies(key: String) = (notes?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("body") }.orEmpty()
        Qa(
            id = id,
            cards = (root["cards"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty(),
            question = question.orEmpty(),
            answer = answer.orEmpty(),
            date = date,
            status = status,
            translated = useEnglish,
            tcg = bodies("invalid_tcg"),
            precedent = bodies("precedent"),
        )
    }

    // ---- choosing and writing ---------------------------------------------------------------

    /**
     * Which of a card's Q&As to read: the newest first (the database numbers them as they come,
     * so the highest ids are the newest), only those shared with [with]'s when given, at most [max].
     */
    fun pick(qaIds: List<Int>, with: List<Int>? = null, max: Int = MAX_QAS): List<Int> {
        val shared = with?.toSet()
        return qaIds.distinct().filter { shared == null || it in shared }.sortedDescending().take(max.coerceAtLeast(0))
    }

    private val REF = Regex("<<(\\d+)>>")

    /** [text] with every `<<id>>` replaced by the card's name from [name], or `card #id` where it has none. */
    fun names(text: String, name: (Int) -> String?): String =
        REF.replace(text) { m -> m.groupValues[1].toIntOrNull()?.let(name) ?: "card #${m.groupValues[1]}" }

    /**
     * What a date says, where the site knows it does not mean what it says: Konami stamped every
     * entry from before Master Rule 4 with 2017-03-24, and some 2,200 untouched ones with 2022-12-30.
     */
    fun dateWords(date: String?): String = when (date) {
        null -> "date not given"
        "2017-03-24" -> "from before Master Rule 4 (April 2017; the date Konami gives is a placeholder) — check it against today's rules"
        "2022-12-30" -> "dated 2022-12-30, which Konami stamped on old entries by mistake: likely older"
        else -> date
    }

    /**
     * The FAQ notes and the Q&As in words for Ai, every `<<id>>` named by [name]; [total] is how many
     * Q&As the card has (or shares with [with]), of which [qas] were read; a retracted one is counted, not shown.
     */
    fun text(card: Card, qas: List<Qa>, total: Int, name: (Int) -> String?, with: String? = null): String = buildString {
        val title = card.name ?: name(card.id) ?: "card #${card.id}"
        appendLine("Konami's OCG documentation for “$title” — ${cardPage(card.id)}")
        if (card.sections.isEmpty()) {
            appendLine("FAQ notes: none.")
        } else {
            val dates = listOfNotNull(card.faqDate?.let { "Konami's FAQ updated $it" }, card.translatedDate?.let { "translated $it" })
            appendLine("FAQ notes" + (if (dates.isEmpty()) "" else " (${dates.joinToString(", ")})") + ":")
            card.sections.forEach { s ->
                s.label?.let { appendLine(names(it, name)) }
                s.notes.forEach { n ->
                    append("- ")
                    if (!n.translated) append("[untranslated, Konami's Japanese] ")
                    appendLine(names(n.text, name))
                    n.tcg?.let { appendLine("  TCG caveat (YGOrganization): ${names(it, name).ifBlank { "may not apply in the TCG" }}") }
                }
            }
        }
        if (card.outdatedNotes > 0) appendLine("(${card.outdatedNotes} older notes no longer in Konami's database left out)")
        val shown = qas.filter { it.status.shown }.sortedWith(compareByDescending<Qa> { it.date ?: "" }.thenByDescending { it.id })
        val gone = qas.size - shown.size
        appendLine()
        val scope = if (with != null) " shared with “$with”" else ""
        when {
            total == 0 -> appendLine("Q&A$scope: none.")
            else -> appendLine("Q&A$scope: ${shown.size} of $total, newest first" + (if (total > qas.size) " (the rest not read)" else "") + ":")
        }
        shown.forEach { q ->
            appendLine()
            append("Q&A #${q.id} — Konami ${dateWords(q.date)}; translation ${q.status.word}")
            if (q.status != Status.CONFIRMED) append(" (${q.status.meaning})")
            appendLine(" — ${qaPage(q.id)}")
            appendLine("Q: ${names(q.question, name).trim()}")
            appendLine("A: ${names(q.answer, name).trim()}")
            q.precedent.forEach { appendLine("  Precedent for: ${names(it, name)}") }
            q.tcg.forEach { appendLine("  TCG caveat (YGOrganization): ${names(it, name)}") }
        }
        if (gone > 0) appendLine("($gone retracted by Konami, left out)")
    }.trimEnd()

    // ---- reading JSON -----------------------------------------------------------------------

    private fun <T> read(json: String, what: String, body: (JsonObject) -> T): Result<T> {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        }
        return runCatching { body(root ?: error("YGOrganization's $what answer was not the JSON expected.")) }
            .recoverCatching { e ->
                throw if (e is IllegalStateException) e else IllegalStateException("YGOrganization's $what answer did not read: ${e.message ?: e::class.simpleName}")
            }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
