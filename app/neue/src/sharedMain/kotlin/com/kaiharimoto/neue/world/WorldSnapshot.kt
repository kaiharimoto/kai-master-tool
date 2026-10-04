package com.kaiharimoto.neue.world

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboCodec
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import java.io.File

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
) : WorldHost {
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
    override fun file(path: String): String? {
        val safe = WorldPaths.safe(path) ?: return null
        return filesDir?.let { runCatching { File(it, safe).takeIf { f -> f.isFile }?.readText() }.getOrNull() }
    }

    /** This snapshot reading a world's own files, for `ygo.use`. */
    fun reading(files: File): WorldSnapshot = WorldSnapshot(index, open, library, groupsOf, logged, shares, comboDir, files)

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
            val games = TestStats.mirrored(h.prep.doc.games, mine, listOfNotNull(stored.firstOrNull { it.entry.id == mine }?.entry?.name))
            return WorldSnapshot(b.index, open, stored.map { it.entry }, groups, games, shares, File(Platform.dataDir, "duel"))
        }
    }
}
