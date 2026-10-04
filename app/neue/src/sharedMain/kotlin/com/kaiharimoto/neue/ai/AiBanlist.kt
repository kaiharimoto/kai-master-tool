package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.cards.BanlistHistory
import com.kaiharimoto.mastertool.core.cards.BanlistMatch
import com.kaiharimoto.mastertool.core.cards.BanlistWords
import com.kaiharimoto.mastertool.core.cards.LimitationList
import com.kaiharimoto.mastertool.core.cards.YugipediaLists
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.NeueHolders
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * Ai's `banlist` (1.1.1, Phase B §3): any Forbidden & Limited list by date, a card's history through them, and what
 * moved between two — read from Yugipedia's list pages ([com.kaiharimoto.neue.banlist.BanlistCenter]), the names in
 * the envelope (outside text) and the list and its source cited in every answer. Also the dated list
 * `validate_deck`'s `as_of` checks against ([listOn]).
 */
internal class AiBanlist(private val h: NeueHolders) {
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)
    private val index: CardIndex get() = h.builder.index

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "banlist" -> banlist(i)
        else -> null
    }

    /** "tcg"/"ocg", or the builder's format; null for anything else. */
    fun region(word: String?): Format? = when (word?.trim()?.lowercase()) {
        null, "" -> h.builder.format
        "tcg" -> Format.TCG
        "ocg" -> Format.OCG
        else -> null
    }

    /** A dated list and the pool's match to it; or the words for why there is none. */
    class Dated(val list: LimitationList?, val match: BanlistMatch?, val problem: String?, val note: String?)

    /** The list of [region] in force on [date], matched to the pool, for `validate_deck`'s `as_of`. */
    suspend fun listOn(region: Format, date: String): Dated {
        if (!Legality.isDate(date)) return Dated(null, null, "as_of is a day, yyyy-MM-dd (it was “$date”).", null)
        val got = h.banlists.ensure(region)
        val history = got.history ?: return Dated(null, null, "The ${region.name} lists could not be read from Yugipedia. ${got.problem.orEmpty()}".trim(), null)
        val list = history.asOf(date) ?: return Dated(null, null, before(history, date), null)
        return Dated(list, list.match(index::byName), null, got.problem?.let(::stale))
    }

    private fun before(history: BanlistHistory, date: String): String {
        val first = history.lists.first()
        return "No ${history.region.name} list was in force on ${Legality.readable(date)}: the first kept is ${first.title}, from ${Legality.readable(first.start)}."
    }

    private fun stale(problem: String) = "(The lists could not be refreshed just now, so these are the ones kept: $problem)"

    private fun cite(list: LimitationList) = "${Yugipedia.ATTRIBUTION} ${YugipediaLists.pageUrl(list.title)}"

    private suspend fun banlist(i: JsonObject): MetaAnswer {
        val region = region(ToolArgs.string(i, "region")) ?: return fail("region is tcg or ocg.")
        val today = LocalDate.now().toString()
        val date = ToolArgs.string(i, "date")?.trim()?.ifEmpty { null } ?: today
        if (!Legality.isDate(date)) return fail("date is a day, yyyy-MM-dd (it was “$date”).")
        val compare = ToolArgs.string(i, "compare_to")?.trim()?.ifEmpty { null }
        if (compare != null && !Legality.isDate(compare)) return fail("compare_to is a day, yyyy-MM-dd (it was “$compare”).")
        val got = h.banlists.ensure(region)
        val history = got.history ?: return fail("The ${region.name} lists could not be read from Yugipedia. ${got.problem.orEmpty()}".trim())
        val note = got.problem?.let { "\n" + stale(it) }.orEmpty()

        val asked = ToolArgs.string(i, "card")?.trim()?.ifEmpty { null }
        if (asked != null) return card(history, asked, date, note)

        if (compare != null) {
            val (from, to) = if (compare <= date) compare to date else date to compare
            val c = history.changes(from, to) ?: return fail(before(history, from))
            return MetaAnswer(
                "${region.name}, what changed between the list in force on ${Legality.readable(from)} and on ${Legality.readable(to)}:\n" +
                    Untrusted.wrap("Yugipedia: ${c.from.title} and ${c.to.title}", BanlistWords.changes(c)) + "\n" +
                    "${Yugipedia.ATTRIBUTION} ${YugipediaLists.pageUrl(c.from.title)} and ${YugipediaLists.pageUrl(c.to.title)}$note",
                if (c.from.title == c.to.title) "${c.to.title}: nothing moved" else "${c.from.title} → ${c.to.title}: ${c.changes.size} cards moved",
            )
        }

        val list = history.asOf(date) ?: return fail(before(history, date))
        val unmatched = if (index.size > 0) list.match(index::byName).unmatched else emptyList()
        return MetaAnswer(
            "On ${Legality.readable(date)}: " + BanlistWords.header(list) + "\n" +
                Untrusted.wrap("Yugipedia: ${list.title}", BanlistWords.body(list, unmatched)) + "\n" +
                "A card not on the list is Unlimited. ${cite(list)}$note",
            "Read the ${list.title}",
        )
    }

    private fun card(history: BanlistHistory, asked: String, date: String, note: String): MetaAnswer {
        val found: Card? = (CardWords.resolve(asked, index) as? Resolved.Found)?.card
        val name = found?.name ?: asked
        val lookup: (String) -> Card? = index::byName
        val list = history.asOf(date)
        val now = list?.let { l -> if (found != null) l.match(lookup).statusOf(found) else l.statusOf(asked) }
        val spells = if (found != null) history.historyOf(found, lookup) else history.historyOf(asked)
        val head = buildString {
            if (found == null) append("(“$asked” is not a card in the app's pool; read by the name as written.)\n")
            if (list == null) append(before(history, date)).append('\n')
            else append("$name on ${Legality.readable(date)}: ${BanlistWords.status(now!!)} on the ${list.title} (${BanlistWords.span(list)}).\n")
        }
        val source = list ?: history.latest!!
        return MetaAnswer(
            head + Untrusted.wrap("Yugipedia: ${history.region.name} lists", BanlistWords.history(name, spells)) + "\n" + cite(source) + note,
            if (now != null) "$name: ${BanlistWords.status(now)} on ${Legality.readable(date)}" else "$name through the lists",
        )
    }
}
