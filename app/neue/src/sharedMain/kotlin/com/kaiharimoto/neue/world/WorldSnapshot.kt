package com.kaiharimoto.neue.world

import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Goldfish
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDoc
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHost
import com.kaiharimoto.mastertool.core.cards.BanlistHistory
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboCodec
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldKnowledge
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.platform.Platform
import java.io.File
import java.time.LocalDate

/**
 * The app as a world's scripts read it (1.0.97), taken once as a run starts: the pool's index (never changed in
 * place), the library, the builder's open deck as it stands, unsaved edits and all, every deck's groups, and the
 * practice games, the active event's field. Plain values, so a script's thread reads them while the app goes on
 * changing its own; a deck's combos and the world's own files are read from disk on that thread, when asked.
 */
class WorldSnapshot private constructor(
    private val index: CardIndex,
    private val open: DeckEntry?,
    private val library: List<DeckEntry>,
    private val groupsOf: Map<String, DeckGroups>,
    private val logged: List<TestGame>,
    private val shares: Map<String, Int>,
    private val comboDir: File?,
    private val filesDir: File? = null,
    /** The banlists kept (1.1.1): read on the script's thread, as it asks; never fetched there. */
    private val bans: (Format) -> BanlistHistory? = { null },
    private val region: Format = Format.TCG,
    private val day: String? = null,
    /** The duel in play as Ai's seat reads it (Phase C stage 3, `ygo.duel.fork`): a view, never the live table. */
    private val fork: Lazy<DuelFork.Source?> = lazyOf(null),
    /** What Ai knows, read-only (`ygo.knowledge`, `docs/world/DESKTOP.md` §10.4): the Library's catalogue and files. */
    private val known: WorldKnowledge? = null,
    /** The app's folders every world sees (Phase D step 2, `lib/effects/`): a prefix and its folder, for `ygo.use`. */
    private val mounts: List<WorldMount> = emptyList(),
    /** The library as the goldfish trusts it and each deck's targets (Phase D step 4, the `goldfish` instrument). */
    private val fish: GoldfishHost? = null,
) : WorldHost {
    override fun goldfish(): GoldfishHost? = fish

    override fun now(): Long = System.currentTimeMillis()
    override fun liveDuel(): DuelFork.Source? = fork.value
    override fun cardById(id: Int): Card? = index.byId(CardId(id))
    override fun cardNamed(name: String): Card? = index.byName(name)
    override fun search(query: String, limit: Int): List<Card> = index.search(query, scope = SearchScope.NAMES, limit = limit).cards
    override fun deck(id: String?): DeckEntry? = if (id == null || id == open?.id) open else library.firstOrNull { it.id == id }
    override fun decks(): List<DeckEntry> = library
    override fun groups(deckId: String): Map<String, List<Int>> = groupsOf[deckId]?.let { g ->
        g.ordered().associate { group -> group.name to g.assignments.filterValues { it == group.id }.keys.map { it.value } }
    }.orEmpty()
    override fun games(): List<TestGame> = logged
    override fun combos(deckId: String): List<Combo> = comboDir?.let { dir ->
        runCatching { File(dir, ComboCodec.path(deckId)).takeIf { it.isFile }?.readText()?.let(ComboCodec::decode)?.combos }.getOrNull()
    }.orEmpty()
    override fun field(deckId: String?): Map<String, Int> = shares
    override fun banlists(region: Format): BanlistHistory? = bans(region)
    override fun format(): Format = region
    override fun today(): String? = day
    override fun knowledge(): WorldKnowledge? = known
    override fun file(path: String): String? {
        val safe = WorldPaths.safe(path) ?: return null
        mounts.firstOrNull { safe.startsWith(it.prefix) }?.let { m ->
            val rel = safe.removePrefix(m.prefix)
            if (rel.isEmpty() || !m.readable(rel)) return null
            return runCatching { File(m.dir, rel).takeIf { f -> f.isFile }?.readText() }.getOrNull()
        }
        return filesDir?.let { runCatching { File(it, safe).takeIf { f -> f.isFile }?.readText() }.getOrNull() }
    }

    /** This snapshot reading a world's own files and the app's folders mounted in it, for `ygo.use`. */
    fun reading(files: File, mounted: List<WorldMount> = mounts): WorldSnapshot =
        WorldSnapshot(index, open, library, groupsOf, logged, shares, comboDir, files, bans, region, day, fork, known, mounted, fish)

    companion object {
        /** Read on the main thread, where the builder's state lives; the library from its repository. */
        suspend fun of(h: NeueHolders): WorldSnapshot {
            val b = h.builder
            val stored = h.deps.deckRepository.all()
            val openId = b.deckId ?: "open"
            val open = if (b.deck.isEmpty) null else DeckEntry(openId, b.deckName, b.deck, 0, 0)
            val groups = stored.associate { it.entry.id to DeckGroupsCodec.read(it.extended).groups } + (openId to b.groups)
            // The field: the active event's web, every deck in it that has a share — yours too, as the mirror (Phase B),
            // its games typed by name folded under its id.
            val web = h.webs.library.byId(h.prep.active?.webId)
            val shares = TestStats.field(web?.entries.orEmpty())
            val mine = h.prep.active?.deckId
            // Practice only: a real round is a match result, never a Game 1 of practice (Prep keeps them apart the same way).
            val games = TestStats.mirrored(h.prep.doc.games.filter { it.round == null }, mine, listOfNotNull(stored.firstOrNull { it.entry.id == mine }?.entry?.name))
            // The banlists as kept; one due a refresh is fetched in the background, for the next run.
            val banlists = h.banlists
            banlists.warm(b.format)
            // The goldfish (Phase D step 4): what it trusts now, read here on the main thread; each deck's targets from disk.
            val effects = h.effects
            val trust = effects.trust()
            val phone = h.neue.phone
            val fish = object : GoldfishHost {
                override val trust: FxTrust = trust
                override fun doc(deckId: String): GoldfishDoc = effects.goldfish(deckId)
                override val defaultHands: Int = if (phone) Goldfish.PHONE_HANDS else Goldfish.DESK_HANDS
            }
            return WorldSnapshot(
                b.index, open, stored.map { it.entry }, groups, games, shares, File(Platform.dataDir, "duel"),
                bans = banlists::history, region = b.format, day = LocalDate.now().toString(), fork = fork(h),
                known = h.world.library.knowledge, fish = fish,
            )
        }

        /**
         * The duel in play, as the seat Ai would hold reads it with its knowledge setting (Phase C stage 3): the page's when
         * the Duel page has been opened, else the one left in `<data>/duel/current.json`. Never a networked table's (its
         * seats are two people's: `AiTable`), and never the table itself — a [DuelFork.Source] holds that seat's view.
         */
        private fun fork(h: NeueHolders): Lazy<DuelFork.Source?> {
            val d = h.neue.prefs.duel
            fun of(game: DuelGame?): DuelFork.Source? {
                game ?: return null
                // A host's networked duel is the two players' (its entries say so, on disk too).
                if (game.played.any { it.by?.net == true }) return null
                return DuelFork.source(game, if (game.state.solo) 0 else d.aiSeat, d.aiKnowledge)
            }
            // The page's duel, read now on the main thread; else the one on disk, read only if a script forks it.
            if (h.duelStarted) return lazyOf(if (h.duel.role != null) null else of(h.duel.game))
            val file = File(File(Platform.dataDir, "duel"), Duels.CURRENT)
            return lazy { of(runCatching { file.takeIf { it.isFile }?.readText()?.let(DuelCodec::decode)?.let(DuelGame::of) }.getOrNull()) }
        }
    }
}
