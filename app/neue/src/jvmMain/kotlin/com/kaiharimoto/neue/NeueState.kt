package com.kaiharimoto.neue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.layout.Revealed
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.neue.kit.MenuSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The rail's pages, numbered the way the family numbers them. Settings sits below the rail's rule, unnumbered. */
enum class Page(val numeral: Int?, val title: String) {
    DECKS(1, "Decks"),
    BUILDER(2, "Builder"),
    ODDS(3, "Odds"),
    STATS(4, "Stats"),
    SETTINGS(null, "Settings"),
}

enum class Drawer { ISSUES }

/** A toast the shell owns, with at most one action. */
data class Note(val message: String, val action: String? = null, val id: Long = System.nanoTime(), val onAction: () -> Unit = {})

/** A card picked out on the builder: a deck position, or a pool row. */
sealed interface Selection {
    val card: Card

    data class InDeck(override val card: Card, val section: DeckSection, val index: Int) : Selection
    data class InPool(override val card: Card, val row: Int) : Selection
}

/**
 * A card opened large in the middle of the window (a right-hold): where it was
 * opened from, so what the viewer offers is the menu for that place — a deck
 * position, or a pool row when [section] is null.
 */
data class Viewing(val card: Card, val section: DeckSection?, val index: Int)

/**
 * Everything about the window that is not the deck: which page, what is open
 * on top, what the pointer is over, and the desktop's own settings.
 *
 * The deck itself lives in `:ui`'s `DeckBuilderState`, shared with the tablet
 * so the rules cannot drift between the two apps; this is only the shell.
 */
class NeueState(
    private val repository: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    var page by mutableStateOf(Page.BUILDER)

    var prefs by mutableStateOf(NeuePreferences.DEFAULT)
        private set

    var paletteOpen by mutableStateOf(false)
    var helpOpen by mutableStateOf(false)
    var drawer by mutableStateOf<Drawer?>(null)
    var menu by mutableStateOf<MenuSpec?>(null)

    /** The card opened large, if one is. */
    var viewing by mutableStateOf<Viewing?>(null)

    /** A deck the user asked to delete, waiting on the confirmation dialog. */
    var confirmDelete by mutableStateOf<Pair<String, String>?>(null)

    /** The card under the pointer, which the inspector shows. Hover is the desktop's cheapest question. */
    var hovered by mutableStateOf<Card?>(null)

    /** The card clicked, which the inspector falls back to and `Delete` acts on. */
    var selection by mutableStateOf<Selection?>(null)

    /** How wide the main deck's cards are drawn: the pool draws its own that size by default. */
    var deckCardWidth by mutableStateOf(androidx.compose.ui.unit.Dp.Unspecified)

    /** The highlighted pool row, walked by ↑ and ↓. */
    var poolCursor by mutableStateOf(0)

    var searchFocused by mutableStateOf(false)

    /** Bumped to ask the pool's search field for focus. */
    var focusSearchTick by mutableStateOf(0)

    /**
     * Full screen, with the title bar, the builder's header and its footer
     * folded away until the pointer reaches for them. For the session only:
     * a window that opened full screen by surprise would be a worse surprise.
     */
    var immersive by mutableStateOf(false)

    /** How far into zen the builder is (immersive mode only): `ZenClock` decides, any input wakes it. */
    var zen by mutableStateOf(ZenPhase.AWAKE)

    /** Which folded bars the pointer has brought out (`EdgeReveal`). */
    var revealed by mutableStateOf(Revealed.NONE)

    /** Where the Export button is, in the window, so the keyboard opens its menu there too. Plain. */
    var exportAnchor: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(640f, 48f)

    /** A line at the bottom right that is the app's rather than the deck's: "Saved", "Copied". */
    var note by mutableStateOf<Note?>(null)

    val overlayOpen: Boolean
        get() = paletteOpen || helpOpen || drawer != null || menu != null || viewing != null || confirmDelete != null

    /** What the inspector is showing: the hover, else the selection. */
    val inspected: Card? get() = hovered ?: selection?.card

    private var loaded = false

    /** Whether the stored settings have been read: what the builder waits on to know which deck to open. */
    var ready by mutableStateOf(false)
        private set
    private var saveJob: Job? = null
    private val flushScope = CoroutineScope(SupervisorJob())

    fun start() {
        scope.launch {
            val stored = repository.loadNeue()
            // Whatever the user changed while the database was opening wins.
            if (!loaded) prefs = stored
            loaded = true
            ready = true
        }
    }

    fun update(debounce: Boolean = false, transform: (NeuePreferences) -> NeuePreferences) {
        loaded = true
        prefs = transform(prefs).sanitised()
        saveJob?.cancel()
        saveJob = scope.launch {
            if (debounce) delay(400)
            repository.saveNeue(prefs)
        }
    }

    /** The last write before the window closes is the one the user quit to keep. */
    fun flush() {
        val last = prefs
        flushScope.launch { repository.saveNeue(last) }
    }

    /** Card [card]'s artwork, [by] along from the one showing, wrapping; its own art is stored as no choice. */
    fun stepArt(card: Card, by: Int) = update { p ->
        val next = CardArt.step(card, p.arts[card.id.value]?.let(::CardId), by)
        p.copy(arts = if (next == card.id) p.arts - card.id.value else p.arts + (card.id.value to next.value))
    }

    fun toggleTheme() = update { it.copy(theme = if (it.theme == NeueTheme.PAPER) NeueTheme.INK else NeueTheme.PAPER) }

    /** Closes the top-most thing. Returns false when nothing was open, so Esc can fall through. */
    fun dismissTop(): Boolean = when {
        menu != null -> { menu = null; true }
        viewing != null -> { viewing = null; true }
        paletteOpen -> { paletteOpen = false; true }
        confirmDelete != null -> { confirmDelete = null; true }
        helpOpen -> { helpOpen = false; true }
        drawer != null -> { drawer = null; true }
        else -> false
    }

    fun go(to: Page) {
        dismissTop()
        page = to
    }

    fun focusSearch() {
        page = Page.BUILDER
        if (!prefs.poolVisible) update { it.copy(poolVisible = true) }
        focusSearchTick++
    }
}
