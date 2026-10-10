package com.kaiharimoto.neue.field

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.ai.meta.FieldCluster
import com.kaiharimoto.mastertool.core.ai.meta.FieldProfile
import com.kaiharimoto.mastertool.core.ai.meta.FieldProfiles
import com.kaiharimoto.mastertool.core.ai.meta.FieldShares
import com.kaiharimoto.mastertool.core.ai.meta.FieldSnapshot
import com.kaiharimoto.mastertool.core.ai.meta.StrategyRatios
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.mastertool.core.ai.meta.FieldLegality
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckDecks
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The field as last read (Phase G, G.5): the tournament lists a field tool last fetched, kept in `<data>/field/latest.json`
 * on this device alone (a cache: never synced or backed up), with what is read from them off the frame thread — the
 * strategies, the field's interaction, and the open deck against its own strategy for the inspector's field line.
 */
class FieldCache(private val dir: File, private val h: NeueHolders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var snapshot by mutableStateOf<FieldSnapshot?>(null)
        private set

    /** The strategies and the field's interaction, read from [snapshot] by the honest weighting. */
    var read by mutableStateOf<Read?>(null)
        private set

    class Read(val snapshot: FieldSnapshot, val clusters: List<FieldCluster>, val profile: FieldProfile)

    /** The open deck against the strategy most like it: the deck it was read for, and the ratios and how alike (null: none). */
    var ratios by mutableStateOf<Ratios?>(null)
        private set

    class Ratios(val deck: Deck, val read: Read, val ratios: StrategyRatios?, val alike: Double)

    private var loaded = false

    /** Reads the kept snapshot once, the first time anything asks. */
    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            val kept = withContext(Dispatchers.IO) { runCatching { File(dir, FILE).takeIf { it.isFile }?.readText()?.let(FieldSnapshot::decode) }.getOrNull() }
            if (kept != null && snapshot == null) adopt(kept)
        }
    }

    /** A field just read: kept on disk and read again. */
    fun keep(s: FieldSnapshot) {
        loaded = true
        scope.launch {
            adopt(s)
            withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, "$FILE.tmp")
                    tmp.writeText(FieldSnapshot.encode(s))
                    tmp.renameTo(File(dir, FILE)) || run { File(dir, FILE).delete(); tmp.renameTo(File(dir, FILE)) }
                }
            }
        }
    }

    private suspend fun adopt(s: FieldSnapshot) {
        snapshot = s
        ratios = null
        val index = h.builder.index
        read = withContext(Dispatchers.Default) {
            val decks = s.decks()
            val weigh = FieldShares.weigher(decks, FieldShares.Weighting.BUDGET)
            val clusters = FieldBuilder.build(decks, TOP, index::byId, weigh)
            Read(s, clusters, FieldProfiles.of(clusters, index::byId, weigh))
        }
    }

    /** A read under way, and why the last one failed (null: it did not). */
    var reading by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    private val source by lazy {
        YgoProDeckDecks(HttpClientFactory.create(), userAgent = "NeueMasterTool/${Platform.version}", clock = System::currentTimeMillis)
    }

    /**
     * Reads the field from YGOPRODeck as `ygopro_field_snapshot` does — tier [tier] and up over [days] days, the lists the
     * banlist does not allow left out — and keeps it: Format's "Read the field", for the person without Ai.
     */
    fun fetch(format: DeckFormat, tier: Int = 2, days: Int = 45) {
        if (reading) return
        reading = true
        problem = null
        scope.launch {
            try {
                val read = source.recent(tier, days, format, maxPages = 6)
                if (read.decks.isEmpty()) {
                    problem = if (read.problems.isNotEmpty()) "YGOPRODeck could not be read: ${read.problems.first()}" else "No ${format.name} results at tier $tier+ in the last $days days."
                    return@launch
                }
                val index = h.builder.index
                val kept = withContext(Dispatchers.Default) {
                    FieldLegality.formatOf(format)?.let { f -> FieldLegality.check(read.decks, index::byId, f).kept } ?: read.decks
                }
                if (kept.isEmpty()) problem = "Every list read is illegal under today's ${format.name} list." else keep(FieldSnapshot.of(System.currentTimeMillis(), format, tier, days, null, kept))
            } catch (e: Exception) {
                problem = Unreachable.of(YgoProDeckDecks.SITE, e)
            } finally {
                reading = false
            }
        }
    }

    /** [deck] against the strategy most like it, read off the frame thread when it is not already. */
    fun ask(deck: Deck) {
        load()
        val r = read ?: return
        if (ratios?.deck == deck && ratios?.read === r) return
        if (deck.main.isEmpty()) return
        val index = h.builder.index
        scope.launch {
            val got = withContext(Dispatchers.Default) {
                val decks = r.clusters.flatMap { it.decks }
                val weigh = FieldShares.weigher(decks, FieldShares.Weighting.BUDGET)
                val closest = StrategyRatios.closest(r.clusters, deck, index::byId)
                if (closest == null) Ratios(deck, r, null, 0.0)
                else Ratios(deck, r, StrategyRatios.of(closest.first.name, closest.first.decks, deck, index::byId, weigh), closest.second)
            }
            if (read === r) ratios = got
        }
    }

    companion object {
        const val FILE = "latest.json"

        /** Strategies read from the kept lists. */
        const val TOP = 12

        /** A deck at least this alike to a strategy is "like" its lists: the inspector speaks of them. */
        const val ALIKE = 0.3
    }
}
