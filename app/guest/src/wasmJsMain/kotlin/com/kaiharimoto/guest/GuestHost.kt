package com.kaiharimoto.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.MemoryDuelStore
import com.kaiharimoto.neue.duel.TableAi
import com.kaiharimoto.neue.duel.TableHost
import com.kaiharimoto.neue.duel.TableVoice
import com.kaiharimoto.neue.kit.MenuSpec
import kotlinx.serialization.json.Json

/**
 * The table as a friend's browser gives it (`docs/LOUNGE.md`): a duel held on kai's computer and sent here as this
 * member may see it, the pool kai's computer serves, the duel's settings kept in this browser. No Ai, voice or written
 * effects of its own: those are kai's computer's.
 */
class GuestHost : TableHost {
    override val duel: Duels = Duels(MemoryDuelStore())
    override var cards: CardIndex by mutableStateOf(CardIndex.EMPTY)
        private set
    override var duelPrefs: DuelPrefs by mutableStateOf(readPrefs())
        private set
    override val foil: String = Foils.HOLO

    var notice by mutableStateOf<String?>(null)
    var openMenu by mutableStateOf<MenuSpec?>(null)
    /** The table is on screen: the duel's keys reach it. */
    var onTable by mutableStateOf(false)

    override fun updateDuelPrefs(change: (DuelPrefs) -> DuelPrefs) {
        duelPrefs = change(duelPrefs)
        Kept.put(PREFS, JSON.encodeToString(DuelPrefs.serializer(), duelPrefs))
    }

    override fun note(text: String) { notice = text }
    override fun menu(spec: MenuSpec) { openMenu = spec }
    override val keysHere: Boolean get() = onTable && openMenu == null
    /** The room's conversation with Ai (kai's computer answers), set once the Lounge's client is made. */
    var loungeAi: TableAi? = null
    override val ai: TableAi? get() = loungeAi?.takeIf { it.atTable() }
    override val voice: TableVoice? = null
    override val cardExtra: (@Composable (Card) -> Unit)? = null

    /** The pool arrived: art addressed to kai's computer (the same origin), the index built, the table told. */
    fun usePool(cards: List<Card>) {
        val here = cards.map { c ->
            c.copy(imageUrl = c.imageUrl?.let { origin + it }, imageUrlSmall = c.imageUrlSmall?.let { origin + it })
        }
        val index = CardIndex.build(here)
        this.cards = index
        duel.useIndex(index)
    }

    private fun readPrefs(): DuelPrefs =
        Kept.get(PREFS)?.let { runCatching { JSON.decodeFromString(DuelPrefs.serializer(), it) }.getOrNull() } ?: DuelPrefs()

    companion object {
        private const val PREFS = "lounge.duelPrefs"
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    }
}
