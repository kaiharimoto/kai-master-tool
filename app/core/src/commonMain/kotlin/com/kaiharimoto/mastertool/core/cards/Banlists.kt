package com.kaiharimoto.mastertool.core.cards

import com.kaiharimoto.mastertool.core.ai.web.HtmlText
import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.TextMatching
import kotlinx.serialization.Serializable

/**
 * Every Forbidden & Limited list, by date (Phase B §3, `docs/phases/B.md`).
 *
 * Yugipedia keeps each list as a page — "April 2025 Lists (TCG)" — whose `{{Limitation list}}` template gives the
 * dates and the cards by name, as written on the list. A list is kept as it was read: the names as Yugipedia wrote
 * them ([statuses]), matched to the pool only when asked ([match]), so a name the pool spells another way is kept and
 * reported, never dropped. A card a list does not name is [BanStatus.UNLIMITED] on it.
 *
 * Dates are `yyyy-MM-dd`, which compare as text.
 */
@Serializable
data class LimitationList(
    val region: Format,
    /** The page's title: "April 2025 Lists (TCG)". */
    val title: String,
    /** The first day it was in force. */
    val start: String,
    /** The last day it was in force; null while it still is (or the page does not say). */
    val end: String? = null,
    /** Every card it names, by the name it was written under, with its status on it. */
    val statuses: Map<String, BanStatus> = emptyMap(),
    /** The pages of the lists before and after it, as the page names them. */
    val prev: String? = null,
    val next: String? = null,
) {
    private val byName: Map<String, BanStatus> by lazy {
        val out = HashMap<String, BanStatus>(statuses.size * 2)
        statuses.forEach { (name, s) -> out.getOrPut(TextMatching.normalize(name)) { s } }
        out
    }

    /** The status of the card written [name] on this list: unlimited when it is not named. */
    fun statusOf(name: String): BanStatus = byName[TextMatching.normalize(name)] ?: BanStatus.UNLIMITED

    /** Whether [name] is named on the list at all (an unlimited entry counts: it came off it). */
    fun names(name: String): Boolean = TextMatching.normalize(name) in byName

    /** The cards at [status], in the page's order. */
    fun at(status: BanStatus): List<String> = statuses.filterValues { it == status }.keys.toList()

    /** Whether the list was in force on [date]. A list with no end is read as still in force. */
    fun inForce(date: String): Boolean = start <= date && (end == null || date <= end)

    /** Its names matched to the pool by [lookup] (the pool's own name lookup, `TextMatching.normalize` underneath). */
    fun match(lookup: (String) -> Card?): BanlistMatch {
        val byCard = LinkedHashMap<CardId, BanStatus>()
        val unmatched = mutableListOf<String>()
        statuses.forEach { (name, s) ->
            val card = lookup(name)
            if (card == null) unmatched += name else byCard.getOrPut(card.id) { s }
        }
        return BanlistMatch(this, byCard, unmatched)
    }

    /** [card]'s status on this list, by its name alone. [match] reaches names the pool spells another way. */
    fun statusOf(card: Card): BanStatus = statusOf(card.name)
}

/**
 * A list's names matched to the pool: a [BanSource] for the validator. A card is found by any of its printings, then by
 * its name (a name the lookup did not resolve can still be the card's own, written the same way); [unmatched] are the
 * names that reached no card, kept and reported.
 */
class BanlistMatch(
    val list: LimitationList,
    val byCard: Map<CardId, BanStatus>,
    val unmatched: List<String>,
) : BanSource {
    override fun statusOf(card: Card): BanStatus =
        card.passcodes.firstNotNullOfOrNull { byCard[it] } ?: list.statusOf(card.name)

    override val label: String get() = list.title
}

/** What moved for one card between two lists. */
data class BanChange(val name: String, val before: BanStatus, val after: BanStatus)

/** Every card that moved between [from] and [to], most restricted first, then by name. */
data class ListChanges(val from: LimitationList, val to: LimitationList, val changes: List<BanChange>)

/** One stretch of a card's history: [status] from [from] until [until] (exclusive; null while it holds), on [lists]. */
data class BanSpell(val status: BanStatus, val from: String, val until: String?, val lists: List<String>)

/**
 * One region's lists, oldest first. [asOf] is the list in force on a day: the latest that started on or before it.
 * Pages read twice (a list renamed) are kept once, the later read winning.
 */
class BanlistHistory(val region: Format, lists: List<LimitationList>) {
    val lists: List<LimitationList> = lists
        .filter { it.region == region }
        .associateBy { it.title }.values
        .sortedWith(compareBy<LimitationList> { it.start }.thenBy { it.title })

    val isEmpty: Boolean get() = lists.isEmpty()

    /** The list in force on [date] (`yyyy-MM-dd`), or null before the first. */
    fun asOf(date: String): LimitationList? = lists.lastOrNull { it.start <= date }

    /** The newest list there is. */
    val latest: LimitationList? get() = lists.lastOrNull()

    fun named(title: String): LimitationList? = lists.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }

    /** What changed between the lists in force on [from] and on [to]; null when either day has none. */
    fun changes(from: String, to: String): ListChanges? {
        val a = asOf(from) ?: return null
        val b = asOf(to) ?: return null
        return changes(a, b)
    }

    /** The status of the card written [name] on [date]; null when no list was in force that day. */
    fun statusOf(name: String, date: String): BanStatus? = asOf(date)?.statusOf(name)

    /** [card]'s status on [date], through [lookup] where given (see [LimitationList.match]); null when no list was in force. */
    fun statusOf(card: Card, date: String, lookup: ((String) -> Card?)? = null): BanStatus? {
        val list = asOf(date) ?: return null
        return if (lookup == null) list.statusOf(card) else statusOn(list, card, lookup)
    }

    /**
     * The card written [name] through every list, from the first that names it: each stretch at one status. Empty
     * when no list ever named it (unlimited throughout).
     */
    fun historyOf(name: String): List<BanSpell> = spells { it.statusOf(name) to it.names(name) }

    /** [card]'s history; with [lookup], names the pool resolves to the card count too (a name written another way). */
    fun historyOf(card: Card, lookup: ((String) -> Card?)? = null): List<BanSpell> =
        spells { list ->
            if (lookup == null) list.statusOf(card) to list.names(card.name)
            else statusOn(list, card, lookup) to (list.names(card.name) || namedAs(list, card, lookup))
        }

    private fun spells(read: (LimitationList) -> Pair<BanStatus, Boolean>): List<BanSpell> {
        val first = lists.indexOfFirst { read(it).second }
        if (first < 0) return emptyList()
        val out = mutableListOf<BanSpell>()
        for (i in first until lists.size) {
            val list = lists[i]
            val s = read(list).first
            val last = out.lastOrNull()
            if (last != null && last.status == s) {
                out[out.size - 1] = last.copy(lists = last.lists + list.title)
            } else {
                if (last != null) out[out.size - 1] = last.copy(until = list.start)
                out += BanSpell(s, list.start, null, listOf(list.title))
            }
        }
        // The newest stretch runs on through the newest list kept: its until stays null.
        return out
    }

    private fun statusOn(list: LimitationList, card: Card, lookup: (String) -> Card?): BanStatus {
        if (list.names(card.name)) return list.statusOf(card.name)
        for ((name, s) in list.statuses) {
            val c = lookup(name) ?: continue
            if (c.id == card.id || card.id in c.passcodes) return s
        }
        return BanStatus.UNLIMITED
    }

    private fun namedAs(list: LimitationList, card: Card, lookup: (String) -> Card?): Boolean =
        list.statuses.keys.any { n -> lookup(n)?.let { c -> c.id == card.id || card.id in c.passcodes } == true }

    companion object {
        /** What moved between [from] and [to]: every name either list carries whose status differs. */
        fun changes(from: LimitationList, to: LimitationList): ListChanges {
            val names = LinkedHashMap<String, String>()
            (to.statuses.keys + from.statuses.keys).forEach { names.getOrPut(TextMatching.normalize(it)) { it } }
            val moved = names.values.mapNotNull { n ->
                val a = from.statusOf(n)
                val b = to.statusOf(n)
                if (a == b) null else BanChange(n, a, b)
            }.sortedWith(compareBy<BanChange> { it.after.maxCopies }.thenBy { it.name.lowercase() })
            return ListChanges(from, to, moved)
        }
    }
}

/**
 * Reads a list's page (its wikitext) into a [LimitationList], forgivingly: the template's dates in any of the ways a
 * wiki writes a date, its sections by any spelling (`semi-limited`, `semi_limited`), each name's `// prev::…` note and
 * any other markup round it dropped. A page it cannot read is [Outcome.problem], never an exception.
 */
object LimitationParser {

    /** A page read: the list, or why not. */
    data class Outcome(val list: LimitationList?, val problem: String?)

    /** [title]'s page read; [region] is the category it came from, for a page that does not say its medium. */
    fun read(title: String, wikitext: String, region: Format? = null): Outcome = try {
        readOrThrow(title, wikitext, region)
    } catch (e: Exception) {
        Outcome(null, "$title could not be read: ${e.message ?: e::class.simpleName}")
    }

    /** The list on [title]'s page, or null (see [read] for the reason). */
    fun parse(title: String, wikitext: String, region: Format? = null): LimitationList? = read(title, wikitext, region).list

    private fun readOrThrow(title: String, wikitext: String, region: Format?): Outcome {
        val open = TEMPLATE.find(wikitext) ?: return Outcome(null, "$title has no {{Limitation list}} on it.")
        val fields = LinkedHashMap<String, String>()
        val statuses = LinkedHashMap<String, BanStatus>()
        var section: BanStatus? = null
        var inList = false
        for (raw in wikitext.substring(open.range.last + 1).lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("}}")) break
            if (line.isEmpty()) continue
            val field = FIELD.matchEntire(line)
            if (field != null) {
                val key = key(field.groupValues[1])
                val value = field.groupValues[2].trim()
                val status = SECTIONS[key]
                inList = status != null
                section = status
                if (status == null) {
                    fields[key] = value
                } else if (value.isNotEmpty()) {
                    // A name written on the field's own line.
                    name(value)?.let { statuses.getOrPut(it) { status } }
                }
                continue
            }
            if (!inList) continue
            val s = section ?: continue
            name(line)?.let { statuses.getOrPut(it) { s } }
        }

        val medium = fields["medium"]?.let(::clean)?.uppercase()
        val where = when {
            medium == "TCG" -> Format.TCG
            medium == "OCG" -> Format.OCG
            title.contains("(TCG)") -> Format.TCG
            title.contains("(OCG)") -> Format.OCG
            region != null -> region
            else -> return Outcome(null, "$title does not say whether it is the TCG's or the OCG's.")
        }
        val start = fields["start_date"]?.let(::date) ?: fields["start"]?.let(::date) ?: fromTitle(title)
            ?: return Outcome(null, "$title has no start date the app can read (“${fields["start_date"].orEmpty()}”).")
        val end = fields["end_date"]?.let(::date) ?: fields["end"]?.let(::date)
        if (statuses.isEmpty()) return Outcome(null, "$title names no cards.")
        return Outcome(
            LimitationList(
                region = where,
                title = title,
                start = start,
                end = end?.takeIf { it >= start },
                statuses = statuses,
                prev = fields["prev"]?.let(::clean)?.ifBlank { null },
                next = fields["next"]?.let(::clean)?.ifBlank { null },
            ),
            null,
        )
    }

    /** A field's name as the parser keys it: lower case, `-` and spaces as `_`. */
    private fun key(raw: String): String = raw.trim().lowercase().replace(Regex("[\\s-]+"), "_")

    private val TEMPLATE = Regex("""\{\{\s*Limitation[ _]list\b""", RegexOption.IGNORE_CASE)
    private val FIELD = Regex("""^\|\s*([A-Za-z][A-Za-z0-9 _-]*?)\s*=(.*)$""")

    private val SECTIONS: Map<String, BanStatus> = mapOf(
        "forbidden" to BanStatus.FORBIDDEN,
        "banned" to BanStatus.FORBIDDEN,
        "limited" to BanStatus.LIMITED,
        "semi_limited" to BanStatus.SEMI_LIMITED,
        "semilimited" to BanStatus.SEMI_LIMITED,
        "unlimited" to BanStatus.UNLIMITED,
        // Cards that came off the list: unlimited on it, and named, so their history shows the step.
        "no_longer_on_list" to BanStatus.UNLIMITED,
    )

    private val COMMENT = Regex("""<!--.*?(-->|$)""")
    private val REF = Regex("""<ref\b[^>]*/>|<ref\b[^>]*>.*?(</ref>|$)""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val LINK = Regex("""\[\[([^\]|]*)(?:\|[^\]]*)?]]""")
    private val TEMPLATE_CALL = Regex("""\{\{[^{}]*}}""")

    /** Markup off a field's value. */
    private fun clean(value: String): String =
        HtmlText.decode(
            value.replace(COMMENT, "").replace(REF, "").replace(TEMPLATE_CALL, "")
                .replace(LINK) { it.groupValues[1] }.replace(TAG, ""),
        ).trim()

    /** One name off a list line: before its `//` note, markup and any `|` annotation dropped; null for nothing. */
    internal fun name(line: String): String? {
        var s = line.substringBefore("//")
        s = s.replace(COMMENT, "").replace(REF, "").replace(TEMPLATE_CALL, "")
        s = s.replace(LINK) { it.groupValues[1] }
        s = s.substringBefore('|').replace(TAG, "")
        s = HtmlText.decode(s).trim().trimStart('*', '#', ':', ';').trim()
        return s.ifEmpty { null }
    }

    /**
     * A date as a wiki writes it — "April 7, 2025", "7 April 2025", "2025-04-07", "Apr. 7 2025" — as `yyyy-MM-dd`; a
     * month and year alone is its first day. Null for anything else (an empty end date: still in force).
     */
    fun date(value: String): String? {
        val v = clean(value)
        if (v.isEmpty()) return null
        ISO.matchEntire(v)?.let { m -> return iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        val words = v.replace(",", " ").replace(".", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val month = words.firstNotNullOfOrNull { month(it) } ?: return null
        val numbers = words.mapNotNull { w -> w.trimEnd('s', 't', 'n', 'd', 'r', 'h').toIntOrNull() }
        val year = numbers.firstOrNull { it in 1990..2200 } ?: return null
        val day = numbers.firstOrNull { it in 1..31 && it != year } ?: 1
        return iso(year, month, day)
    }

    /** "April 2025 Lists (TCG)" → the first of April 2025, for a page that gives no start date. */
    fun fromTitle(title: String): String? {
        val m = TITLE.find(title) ?: return null
        val month = month(m.groupValues[1]) ?: return null
        return iso(m.groupValues[2].toInt(), month, 1)
    }

    private val ISO = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val TITLE = Regex("""([A-Za-z]+)\s+(\d{4})\s+Lists""")
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun month(word: String): Int? {
        val w = word.lowercase()
        if (w.length < 3) return null
        val i = MONTHS.indexOfFirst { it == w || (w.length >= 3 && it.startsWith(w)) }
        return if (i < 0) null else i + 1
    }

    private fun iso(y: Int, m: Int, d: Int): String? {
        if (m !in 1..12 || d !in 1..31) return null
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }
}
