package com.kaiharimoto.mastertool.core.cards

import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.model.Format
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The lists read from Yugipedia, kept in `<data>/banlists/<tcg|ocg>.json` (Phase B §3): a cache, re-fetchable at any
 * time, so it is neither synced nor backed up, and the folder is this device's alone (`InboundPath.DEVICE_FOLDERS`).
 * Versioned and read forgivingly — unknown keys ignored, a newer [version] still read, a broken file read as none —
 * like every stored shape (`OldDataTest`).
 */
@Serializable
data class BanlistDoc(
    val version: Int = VERSION,
    val region: Format = Format.TCG,
    /** Every list read, any order ([BanlistHistory] sorts them). */
    val lists: List<LimitationList> = emptyList(),
    /** Pages that could not be read, with why: asked for again at the next refresh. */
    val unreadable: Map<String, String> = emptyMap(),
    /** When each page was last read (epoch ms), by title. */
    val fetched: Map<String, Long> = emptyMap(),
    /** When the category was last read whole (epoch ms); 0 never. */
    val checked: Long = 0,
) {
    fun history(): BanlistHistory = BanlistHistory(region, lists)

    companion object {
        const val VERSION = 1
    }
}

object BanlistCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(doc: BanlistDoc): String = json.encodeToString(BanlistDoc.serializer(), doc)

    /** The doc in [text], or null for anything that is not one (a cache: the caller fetches again). */
    fun decode(text: String): BanlistDoc? = runCatching { json.decodeFromString(BanlistDoc.serializer(), text) }.getOrNull()

    /** The file's name under `<data>/banlists/`. */
    fun file(region: Format): String = region.name.lowercase() + ".json"
}

/**
 * What a refresh asks for ([BanlistPlan.pages]) and what it keeps ([BanlistPlan.merge]). Lists never change once
 * they have ended, so a page is read once — except the one still in force, read again weekly, and a page that could
 * not be read, asked for again at each refresh. The category itself is read weekly ([stale]).
 */
object BanlistPlan {
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /** Whether [doc] is due a refresh: nothing kept, or the category last read a week or more ago. */
    fun stale(doc: BanlistDoc?, now: Long): Boolean = doc == null || doc.lists.isEmpty() || now - doc.checked >= WEEK_MS

    /** The pages of [titles] (the category, read now) to fetch, given what [doc] keeps; [today] is `yyyy-MM-dd`. */
    fun pages(doc: BanlistDoc?, titles: List<String>, today: String, now: Long): List<String> {
        val have = doc?.lists.orEmpty().associateBy { it.title }
        return titles.distinct().filter { t ->
            val list = have[t] ?: return@filter true
            val current = list.end == null || list.end >= today
            current && now - (doc?.fetched?.get(t) ?: 0L) >= WEEK_MS
        }
    }

    /**
     * [doc] with [read] (the lists just read) and [failed] (title → why) folded in. With the whole category in
     * [titles], a page no longer in it (renamed, deleted) is let go, so a renamed list is not kept twice.
     */
    fun merge(doc: BanlistDoc?, region: Format, titles: List<String>?, read: List<LimitationList>, failed: Map<String, String>, now: Long): BanlistDoc {
        val keep = titles?.toSet()
        val lists = LinkedHashMap<String, LimitationList>()
        doc?.lists.orEmpty().forEach { if (keep == null || it.title in keep) lists[it.title] = it }
        read.forEach { lists[it.title] = it }
        val unreadable = doc?.unreadable.orEmpty().filterKeys { it !in lists && (keep == null || it in keep) } + failed.filterKeys { it !in lists }
        val fetched = doc?.fetched.orEmpty().filterKeys { it in lists } + read.associate { it.title to now }
        return BanlistDoc(
            region = region,
            lists = lists.values.toList(),
            unreadable = unreadable,
            fetched = fetched,
            checked = if (titles != null) now else doc?.checked ?: 0,
        )
    }
}

/**
 * Yugipedia's lists: the category of each region's list pages and the pages' wikitext, through its MediaWiki API —
 * the addresses and the JSON they answer with. The fetching is the caller's (its client, its user agent, one request
 * at a time). The text is CC BY-SA 4.0: anything that quotes it carries [Yugipedia.ATTRIBUTION].
 */
object YugipediaLists {
    /** Pages asked for in one request: MediaWiki's limit for a bot-less client. */
    const val BATCH = 50

    /** The category of [region]'s lists. */
    fun category(region: Format): String = when (region) {
        Format.TCG -> "TCG Advanced Format Forbidden & Limited Lists"
        Format.OCG -> "OCG Forbidden & Limited Lists"
    }

    fun categoryUrl(region: Format, continueFrom: String? = null): String =
        "${Yugipedia.API}?action=query&list=categorymembers&cmtitle=Category:${Yugipedia.title(category(region))}" +
            "&cmnamespace=0&cmlimit=500&format=json" + (continueFrom?.let { "&cmcontinue=" + Yugipedia.title(it) } ?: "")

    /** [titles]' wikitext, at most [BATCH] at a time. */
    fun pagesUrl(titles: List<String>): String {
        require(titles.size in 1..BATCH) { "1 to $BATCH titles" }
        return "${Yugipedia.API}?action=query&prop=revisions&rvprop=content&titles=" +
            titles.joinToString("%7C") { Yugipedia.title(it) } + "&format=json"
    }

    /** The page's address, for citing. */
    fun pageUrl(title: String): String = "https://yugipedia.com/wiki/${Yugipedia.title(title)}"

    /** A category answer: the article titles in it, and where to carry on from (null at the end). */
    data class Members(val titles: List<String>, val continueFrom: String?)

    /** [json] read as a category answer, or null for an error or anything else. */
    fun members(json: String): Members? {
        val root = root(json) ?: return null
        if ("error" in root) return null
        val query = root["query"] as? JsonObject ?: return null
        val list = query["categorymembers"] as? JsonArray ?: return null
        val titles = list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val ns = (o["ns"] as? JsonPrimitive)?.intOrNull ?: 0
            if (ns != 0) return@mapNotNull null
            (o["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.contains("Lists") }
        }
        val next = ((root["continue"] as? JsonObject)?.get("cmcontinue") as? JsonPrimitive)?.contentOrNull
        return Members(titles, next)
    }

    /**
     * [json] read as a pages answer: each title with its wikitext, or null for a page that is missing or came back
     * without its text (asked for again later). Null for an error or anything else. Both of MediaWiki's shapes for
     * the text are read (`"*"`, and `"content"` or `slots.main` of the newer format).
     */
    fun pages(json: String): Map<String, String?>? {
        val root = root(json) ?: return null
        if ("error" in root) return null
        val query = root["query"] as? JsonObject ?: return null
        val pages = when (val p = query["pages"]) {
            is JsonObject -> p.values.toList()
            is JsonArray -> p.toList()
            else -> return null
        }
        val out = LinkedHashMap<String, String?>()
        pages.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val rev = (o["revisions"] as? JsonArray)?.firstOrNull() as? JsonObject
            out[title] = rev?.let(::content)
        }
        return out
    }

    private fun content(rev: JsonObject): String? {
        (rev["*"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        (rev["content"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        val main = (rev["slots"] as? JsonObject)?.get("main") as? JsonObject ?: return null
        return (main["*"] as? JsonPrimitive)?.contentOrNull ?: (main["content"] as? JsonPrimitive)?.contentOrNull
    }

    private fun root(json: String): JsonObject? = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: Exception) {
        null
    }
}
