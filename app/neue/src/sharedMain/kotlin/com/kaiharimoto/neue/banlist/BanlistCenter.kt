package com.kaiharimoto.neue.banlist

import com.kaiharimoto.mastertool.core.cards.RegionNames
import com.kaiharimoto.mastertool.core.cards.RegionDoc
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.cards.BanlistCodec
import com.kaiharimoto.mastertool.core.cards.BanlistDoc
import com.kaiharimoto.mastertool.core.cards.BanlistHistory
import com.kaiharimoto.mastertool.core.cards.BanlistPlan
import com.kaiharimoto.mastertool.core.cards.LimitationList
import com.kaiharimoto.mastertool.core.cards.LimitationParser
import com.kaiharimoto.mastertool.core.cards.YugipediaLists
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.neue.platform.Platform
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Every Forbidden & Limited list, by date (1.1.1, Phase B §3): read from Yugipedia's list pages and kept in
 * `<data>/banlists/<tcg|ocg>.json` ([BanlistDoc]). A cache — re-fetchable, never synced or backed up, its folder this
 * device's alone — so a broken or missing file is only a fetch away.
 *
 * Fetched off the main thread, one fetch at a time, each request after a pause (Yugipedia's API etiquette, like
 * `AiHarness`'s): the category, then only the pages not kept yet — a list never changes once it has ended — and the
 * one still in force, weekly ([BanlistPlan]). A failure is worded by [Unreachable] and the lists kept stay in use.
 *
 * [stored] and [history] are safe from any thread (a world's script reads them on its own).
 */
class BanlistCenter(
    private val dir: File,
    private val fetch: (suspend (String) -> String)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> String = { LocalDate.now().toString() },
    /** Between two requests to Yugipedia. */
    private val pause: Long = 1_000,
) {
    /** What [ensure] found: the lists (null when none are kept) and, when a fetch failed, why in words. */
    data class Got(val history: BanlistHistory?, val problem: String?)

    private val oneAtATime = Mutex()
    private val docs = HashMap<Format, BanlistDoc?>()
    private val histories = HashMap<Format, Pair<BanlistDoc, BanlistHistory>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last refresh's problem per region, for a caller that started one in the background. */
    @Volatile
    var problems: Map<Format, String> = emptyMap()
        private set

    private val http by lazy { HttpClientFactory.create() }
    private val agent = "NeueMasterTool/${runCatching { Platform.version }.getOrDefault("dev")} (Yu-Gi-Oh! deck builder; https://github.com/kaiharimoto/kai-master-tool)"

    private fun file(region: Format) = File(dir, BanlistCodec.file(region))

    /** What is kept for [region], read from disk the first time; null when nothing is (or the file is broken). */
    fun stored(region: Format): BanlistDoc? = synchronized(docs) {
        if (region !in docs) docs[region] = runCatching { file(region).takeIf { it.isFile }?.readText() }.getOrNull()?.let(BanlistCodec::decode)
        docs[region]
    }

    /** Holds [doc] as [region]'s lists for this run, without writing it (the studio's and the tests' lists). */
    fun use(region: Format, doc: BanlistDoc) {
        synchronized(docs) { docs[region] = doc }
    }

    /** [region]'s lists as kept now, or null when none are. */
    fun history(region: Format): BanlistHistory? {
        val doc = stored(region) ?: return null
        return synchronized(histories) {
            histories[region]?.takeIf { it.first === doc }?.second
                ?: doc.history().also { histories[region] = doc to it }
        }.takeIf { !it.isEmpty }
    }

    /** [region]'s lists, refreshed first when due (a week since the last read, or none kept). */
    suspend fun ensure(region: Format): Got = withContext(Dispatchers.IO) {
        // The file is read here, off the caller's thread (Ai's tools run on the main one).
        val problem = if (BanlistPlan.stale(stored(region), clock())) refresh(region) else null
        Got(history(region), problem)
    }

    /** Starts a refresh in the background when one is due (the check, the file's first read too); never waits. */
    fun warm(region: Format) {
        scope.launch { refresh(region) }
    }

    /**
     * Reads what is due from Yugipedia and keeps it; the problem in words, or null when all went well. One at a time:
     * a second caller waits, and then finds the work done.
     */
    suspend fun refresh(region: Format, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        oneAtATime.withLock {
            val before = stored(region)
            if (!force && !BanlistPlan.stale(before, clock())) return@withLock null
            val problem = runRefresh(region, before)
            problems = if (problem == null) problems - region else problems + (region to problem)
            problem
        }
    }

    private suspend fun runRefresh(region: Format, before: BanlistDoc?): String? {
        // The category: every list page of the region.
        val titles = mutableListOf<String>()
        var from: String? = null
        var asked = 0
        do {
            val url = YugipediaLists.categoryUrl(region, from)
            if (asked++ > 0) delay(pause)
            val body = get(url).getOrElse { return "Could not read Yugipedia's list of ${region.name} banlists. ${Unreachable.of(url, it)}" }
            val members = YugipediaLists.members(body) ?: return "Yugipedia's list of ${region.name} banlists came back in a shape the app cannot read."
            titles += members.titles
            from = members.continueFrom
        } while (from != null && asked < MAX_CATEGORY_PAGES)
        if (titles.isEmpty()) return "Yugipedia lists no ${region.name} banlists just now."

        // The pages due, a batch at a time.
        val due = BanlistPlan.pages(before, titles, today(), clock())
        val read = mutableListOf<LimitationList>()
        val failed = LinkedHashMap<String, String>()
        var problem: String? = null
        for (batch in due.chunked(YugipediaLists.BATCH)) {
            delay(pause)
            val url = YugipediaLists.pagesUrl(batch)
            val got = get(url)
            val body = got.getOrNull()
            if (body == null) {
                problem = "Could not read every ${region.name} banlist from Yugipedia. ${Unreachable.of(url, got.exceptionOrNull() ?: IllegalStateException("no answer"))}"
                break
            }
            val pages = YugipediaLists.pages(body)
            if (pages == null) {
                problem = "Yugipedia's banlist pages came back in a shape the app cannot read."
                break
            }
            batch.forEach { title ->
                // MediaWiki answers with the title as it keeps it: match it back ignoring underscores.
                val text = pages[title] ?: pages.entries.firstOrNull { it.key.replace('_', ' ') == title }?.value
                if (text == null) {
                    failed[title] = "Yugipedia sent no text for $title."
                } else {
                    val r = LimitationParser.read(title, text, region)
                    if (r.list != null) read += r.list!! else failed[title] = r.problem ?: "$title could not be read."
                }
            }
        }
        // A fetch cut short keeps what it read, but the category is not marked read: the next ask carries on.
        val doc = BanlistPlan.merge(before, region, titles.takeIf { problem == null }, read, failed, clock())
        save(region, doc)
        return problem
    }

    // ---- where cards are printed: Yugipedia's two categories (1.1.1, `RegionNames`) ----------------------------

    private val regionFile get() = File(dir, RegionDoc.FILE)
    private var regionDoc: RegionDoc? = null
    private var regionRead = false

    /** The two categories as kept, read from disk the first time; null when none are. */
    fun storedRegions(): RegionDoc? = synchronized(docs) {
        if (!regionRead) {
            regionDoc = runCatching { regionFile.takeIf { it.isFile }?.readText() }.getOrNull()?.let(RegionDoc::decode)
            regionRead = true
        }
        regionDoc
    }

    /** Holds [doc] as the categories for this run, without writing it (the tests'). */
    fun useRegions(doc: RegionDoc) = synchronized(docs) {
        regionDoc = doc
        regionRead = true
    }

    /**
     * Which cards Yugipedia has in the TCG and in the OCG, read again when a week old: about 60 requests a second apart,
     * so a minute, in the background. What was kept before is answered when a read fails or is cut short.
     */
    suspend fun regions(): RegionDoc? = withContext(Dispatchers.IO) {
        oneAtATime.withLock {
            val before = storedRegions()
            if (before != null && !before.stale(clock())) return@withLock before
            val tcg = category(RegionNames.TCG_CATEGORY) ?: return@withLock before
            val ocg = category(RegionNames.OCG_CATEGORY) ?: return@withLock before
            val doc = RegionDoc(checked = clock(), tcg = tcg, ocg = ocg)
            synchronized(docs) { regionDoc = doc; regionRead = true }
            runCatching {
                dir.mkdirs()
                val temp = File(dir, regionFile.name + ".tmp")
                temp.writeText(RegionDoc.encode(doc))
                if (!temp.renameTo(regionFile)) {
                    regionFile.delete()
                    temp.renameTo(regionFile)
                }
            }
            doc
        }
    }

    /** Every title in [name], or null when a page could not be read or the category never ends. */
    private suspend fun category(name: String): List<String>? {
        val titles = mutableListOf<String>()
        var from: String? = null
        repeat(RegionDoc.MAX_PAGES) { page ->
            if (page > 0) delay(pause)
            val body = get(RegionNames.categoryUrl(name, from)).getOrNull() ?: return null
            val got = YugipediaLists.members(body) { true } ?: return null
            titles += got.titles
            from = got.continueFrom ?: return titles
        }
        return null
    }

    private suspend fun get(url: String): Result<String> = runCatching {
        fetch?.invoke(url) ?: run {
            val r = http.get(url) {
                header("User-Agent", agent)
                header("Accept", "application/json")
            }
            if (r.status.value !in 200..299) error("${Unreachable.host(url)} answered ${r.status.value} ${r.status.description}")
            r.bodyAsText()
        }
    }

    /** Written whole: a temporary file, then renamed over the old one. */
    private fun save(region: Format, doc: BanlistDoc) {
        synchronized(docs) { docs[region] = doc }
        runCatching {
            dir.mkdirs()
            val target = file(region)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(BanlistCodec.encode(doc))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    private companion object {
        /** A category of 500 a page needs one; a guard against an answer that never ends. */
        const val MAX_CATEGORY_PAGES = 10
    }
}
