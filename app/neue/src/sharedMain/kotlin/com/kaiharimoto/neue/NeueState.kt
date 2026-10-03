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
import com.kaiharimoto.mastertool.core.prefs.CardList
import com.kaiharimoto.mastertool.core.prefs.CardLists
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
    // The builder is home and first (1.0.89, kai: "have builder be 1 and decks to 2, default to the builder").
    BUILDER(1, "Builder"),
    DECKS(2, "Decks"),

    /**
     * Siding your decks against their web (1.0.40, kai: "3 should be Siding"): the editor
     * that stood inside Format, a page of its own. Odds and Stats are gone, on kai's word.
     */
    SIDING(3, "Siding"),

    /** The webs of decks (1.0.33): the fields you prepare for. */
    FORMAT(4, "Format"),

    /** Tournament prep (1.0.50): an event, its field, the practice, the drills and the decklist. */
    PREP(5, "Prep"),

    /** Present (1.0.70): deck profiles as slides — made, presented, and recorded. */
    PRESENT(6, "Present"),

    /** Duel (1.0.74): the duel simulator — a table, one seat or two, every card moved by hand. */
    DUEL(7, "Duel"),
    SETTINGS(null, "Settings"),
}

enum class Drawer { ISSUES }

/** A toast the shell owns, with at most one action. */
data class Note(
    val message: String,
    val action: String? = null,
    val id: Long = System.nanoTime(),
    /** How long it stays: six seconds, longer for a note with something to read. */
    val lastsMs: Long = 6000,
    val onAction: () -> Unit = {},
)

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

/** The search pop-out, open (1.0.19). [listId] is the list it adds to, or null for the deck. */
/**
 * The search pop-out, open. [focus]: whether it takes the keyboard as it opens —
 * a key or the palette asked for it, so typing is next; a finger's tap on the
 * pool's button did not, and a soft keyboard over half the pop-out is not an
 * answer to a tap (touch swarm, rec 10).
 */
data class Studio(val listId: String? = null, val focus: Boolean = true)

/** What the search pop-out was searching when it closed, so reopening it carries on (touch swarm, rec 10). */
data class StudioMemory(
    val query: String,
    val filter: com.kaiharimoto.mastertool.core.search.CardFilter,
    val onlyList: Boolean,
)

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

    /**
     * The groups' palettes, opened out under the Groups panel (1.0.24): they stay out
     * while palettes are tried one after another, and fold when a press lands off
     * the panel ([groupsPanel]), on `Esc`, or when the panel goes.
     */
    var groupPalettesOpen by mutableStateOf(false)

    /** Where the Groups panel is, in the window, while it is out: a press outside it folds the palettes. Plain. */
    var groupsPanel: androidx.compose.ui.geometry.Rect? = null
    var helpOpen by mutableStateOf(false)
    var drawer by mutableStateOf<Drawer?>(null)
    var menu by mutableStateOf<MenuSpec?>(null)

    /** The card opened large, if one is. */
    var viewing by mutableStateOf<Viewing?>(null)

    /**
     * A card shown full screen, turning with the phone (kai, v1.3.6): over everything,
     * the viewer it was opened from waiting under it.
     */
    var showcase by mutableStateOf<Card?>(null)

    /** A deck the user asked to delete, waiting on the confirmation dialog. */
    var confirmDelete by mutableStateOf<Pair<String, String>?>(null)

    /**
     * Removing a card's own picture asks first (touch swarm, rec 24): it deletes the
     * imported file, and nothing undoes that. The card, and which of its own it is (k).
     */
    var confirmRemoveArt by mutableStateOf<Pair<Card, Int>?>(null)

    /** The card whose own art is being cropped in, and the picture it came with (1.0.34, `ArtCropDialog`). */
    var cropping by mutableStateOf<com.kaiharimoto.neue.art.ArtCropping?>(null)

    /** The main deck's bands of group blocks, and what keeps an edit from reshuffling them (1.0.37). */
    internal val bandCache = com.kaiharimoto.neue.builder.BandCache()

    /** A deck shown as a QR code for a phone or a tablet to scan (1.0.30), while it is. */
    var qr by mutableStateOf<com.kaiharimoto.neue.qr.QrShown?>(null)

    /** The card under the pointer, which the inspector shows. Hover is the desktop's cheapest question. */
    /** The window's haptics (touch swarm, rec 13): set by the window, nothing on the desk. */
    var feel: (com.kaiharimoto.mastertool.core.haptics.Haptic) -> Unit = {}

    /** Whether the gesture being acted on is a finger's or a pen's: the only hands the tablet answers with a buzz or a ring. */
    var fingerActing = false
        private set

    /** Runs [block] as [finger]'s gesture: what it adds, drops or removes is felt and ringed only for a finger. */
    fun <T> actingBy(finger: Boolean, block: () -> T): T {
        val was = fingerActing
        fingerActing = finger
        try {
            return block()
        } finally {
            fingerActing = was
        }
    }

    /** Plays [event] for a finger's gesture; "no buzz" always means "not in the deck" (DeskFeel). */
    fun felt(event: com.kaiharimoto.mastertool.core.haptics.DeskEvent?) {
        if (fingerActing && event != null) com.kaiharimoto.mastertool.core.haptics.DeskFeel.of(event)?.let(feel)
    }

    /** The art library is on and waiting for a network that costs nothing (touch swarm, rec 27). */
    var waitingForWifi by mutableStateOf(false)

    var hovered by mutableStateOf<Card?>(null)

    /** The card clicked, which the inspector falls back to and `Delete` acts on. */
    var selection by mutableStateOf<Selection?>(null)

    /** How wide the main deck's cards are drawn: the pool draws its own that size by default. */
    var deckCardWidth by mutableStateOf(androidx.compose.ui.unit.Dp.Unspecified)

    /** How many cards a row of the pool holds as it is drawn now: the arrow keys' row. Plain. */
    var poolColumns: Int = 1

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

    /**
     * The index rail held out by a click on the logo (1.0.89, kai: "when you click the program logo it should open the
     * side bar"): out until a page is chosen, the logo is clicked again, or a press lands outside it.
     */
    var railHeld by mutableStateOf(false)

    /** Where the Export button is, in the window, so the keyboard opens its menu there too. Plain. */
    var exportAnchor: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(640f, 48f)

    /**
     * The setup offered on opening (1.0.69): the steps still to show, over the whole window, while there
     * are any. Esc and Back are "later": the steps stay undone, and Settings can show them again.
     */
    var startSteps by mutableStateOf<List<com.kaiharimoto.mastertool.core.start.StartStep>>(emptyList())
    val starting: Boolean get() = startSteps.isNotEmpty()

    /** The setup put away for now: this version counts as seen, the steps not done. */
    fun startLater() {
        startSteps = emptyList()
        update { it.copy(start = it.start.copy(seen = com.kaiharimoto.neue.platform.Platform.version)) }
    }

    /** The reader's guide open over the window, on this deck's book (1.0.67); Esc and Back close it. */
    var reading by mutableStateOf<String?>(null)

    /** A line at the bottom right that is the app's rather than the deck's: "Saved", "Copied". */
    var note by mutableStateOf<Note?>(null)

    /**
     * The search pop-out (1.0.19), when it is open: the window given over to
     * finding cards, adding to the deck or to a list.
     */
    var studio by mutableStateOf<Studio?>(null)

    /** Ai's panel on a phone: a sheet over the page, closed by Back like any other (1.0.43). */
    val aiSheet: Boolean get() = phone && prefs.ai.enabled && prefs.ai.panelOpen && !aiSetup

    /**
     * Ai's first setup (1.0.45, kai: "it should take over the entire program's UI and focus
     * on it"): the whole window, while Ai is asked for and has no connection yet. Derived,
     * not kept — the first connection made ends it, and Ai docks beside the page.
     */
    val aiSetup: Boolean get() = prefs.ai.enabled && prefs.ai.panelOpen && prefs.ai.connection == null

    /**
     * Ai's panel down the right of the page (desk and tablet). On the builder it stands in
     * the inspector's place rather than beside it (1.0.45, kai: "it should replace the
     * sidebar inspector for UI space economy"): open, the inspector goes; closed, it is back.
     * Immersive mode too (1.0.46, kai: "Ai panel doesn't work in immersive mode"): it docks
     * there the same way, and zen waits while it is open.
     */
    val aiDocked: Boolean get() = prefs.ai.enabled && prefs.ai.panelOpen && !phone && !aiSetup

    val overlayOpen: Boolean
        get() = aiSheet || aiSetup || starting || reading != null || showcase != null || paletteOpen || helpOpen || drawer != null || menu != null || viewing != null || confirmDelete != null || confirmRemoveArt != null || cropping != null || qr != null || studio != null

    /**
     * A touch screen first (Neue on a tablet): no hover to bring the rail out or
     * to read a card by, so the rail stays out and the inspector follows the
     * selection. A mouse plugged into the tablet still works as a mouse.
     */
    val touchFirst: Boolean get() = com.kaiharimoto.neue.platform.Platform.os == com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID

    /**
     * What the app is running on (the phone, v1.3.5): read off the window in physical dp
     * by `NeueWindowContent`. The desk is always [FormFactor.DESK].
     */
    var form by mutableStateOf(com.kaiharimoto.mastertool.core.layout.FormFactor.DESK)

    /** The studio's way to draw a phone on the desk (`--form=phone`); null reads the window. */
    var formOverride: com.kaiharimoto.mastertool.core.layout.FormFactor? = null

    /** Which way round the window is: on a phone, which builder is drawn. */
    var posture by mutableStateOf(com.kaiharimoto.mastertool.core.layout.Posture.WIDE)

    /** A phone: the tab bar, the slim bar and its overflow, the docked pool, no inspector. */
    val phone: Boolean get() = form == com.kaiharimoto.mastertool.core.layout.FormFactor.PHONE

    /** The deck's row width on a phone (`DeckFitter.phoneColumns`), set by the deck as it fits; null elsewhere. */
    var phoneColumns by mutableStateOf<Int?>(null)

    /** Which way the screen may turn, the stored choice or the device's default. */
    val orientation: com.kaiharimoto.mastertool.core.layout.ScreenOrientation
        get() = com.kaiharimoto.mastertool.core.layout.ScreenOrientation.resolve(prefs.orientation, form)

    /** The one-tap toggle: Portrait → Landscape → Auto (kai, v1.3.5). */
    fun rotate() {
        val next = orientation.next()
        update { it.copy(orientation = next.key) }
        note = Note("Screen: ${next.label}")
    }

    private var viewJob: Job? = null

    /**
     * On a phone a tap opens the card large (v1.3.5) — once the double-tap window has
     * passed, so a double-tap adds or removes and never opens the viewer under itself.
     */
    fun viewSoon(v: Viewing) {
        viewJob?.cancel()
        // A finger of a two- or three-finger tap schedules this right after the tap is
        // classified: skipped now, by the clock of the tap itself (1.0.32). Checked only
        // when the timer fired, a busy phone's late timer let the second finger's tap
        // outlive the mark and open the card after the undo.
        val sinceFingersNow = System.nanoTime() / 1_000_000 - fingersAt
        if (sinceFingersNow < com.kaiharimoto.mastertool.core.input.DeskTouch.PHONE_VIEW_MS) {
            trace("skip ${v.card.id.value}@${v.index}, fingers ${sinceFingersNow}ms ago")
            viewJob = null
            return
        }
        trace("soon ${v.card.id.value}@${v.index}")
        viewJob = scope.launch {
            delay(com.kaiharimoto.mastertool.core.input.DeskTouch.PHONE_VIEW_MS)
            // A two- or three-finger tap is undo or redo, and each finger is also a card's tap:
            // none of them opens the card.
            val sinceFingers = System.nanoTime() / 1_000_000 - fingersAt
            if (sinceFingers < com.kaiharimoto.mastertool.core.input.DeskTouch.PHONE_VIEW_MS * 2) {
                trace("skip, fingers ${sinceFingers}ms ago")
                return@launch
            }
            trace("open ${v.card.id.value}@${v.index}")
            if (menu == null && studio == null) {
                viewing = v
                softOpened = v
                softOpenedAt = System.nanoTime() / 1_000_000
            }
        }
    }

    /** The viewer this timer opened, and when: a double-tap's second tap arriving late closes it (1.0.32). Plain. */
    private var softOpened: Viewing? = null
    private var softOpenedAt = 0L

    /**
     * The last few things the phone's tap-to-open did, for the emulator's walk to report
     * when it finds the viewer where it should not be. Plain, bounded.
     */
    var viewTrace: List<String> = emptyList()
        private set

    /** A card's gesture, as the actions saw it, into [viewTrace]. */
    fun noteAction(line: String) = trace(line)

    private fun trace(line: String) {
        viewTrace = (viewTrace + "${System.nanoTime() / 1_000_000 % 100_000} $line").takeLast(12)
    }

    /** When more than one finger was last down in the window, in ms of `System.nanoTime`. Plain. */
    var fingersAt = 0L

    /**
     * A double-tap (or any other gesture) arrived: the tap before it opens nothing.
     * [secondTap] is a double-tap's add or remove: on a phone busy enough to hand it
     * over after the timer has already opened the viewer (the emulator's walk, 1.0.32,
     * a second tap 180 ms after the first by its own clock but 550 ms later by the
     * main thread's), it closes the viewer the timer just opened.
     */
    fun cancelViewSoon(secondTap: Boolean = false) {
        if (viewJob?.isActive == true) trace("cancel")
        viewJob?.cancel()
        viewJob = null
        val opened = softOpened
        softOpened = null
        if (secondTap && opened != null && viewing === opened &&
            System.nanoTime() / 1_000_000 - softOpenedAt < com.kaiharimoto.mastertool.core.input.DeskTouch.PHONE_VIEW_MS
        ) {
            trace("close, a second tap")
            viewing = null
        }
    }

    /** The index rail stays out: pinned, or on a touch screen, where nothing can reach for it. */
    val railPinned: Boolean get() = prefs.railPinned || touchFirst

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

    fun update(debounce: Boolean = false, persist: Boolean = true, transform: (NeuePreferences) -> NeuePreferences) {
        loaded = true
        prefs = transform(prefs).sanitised()
        // A pinch in flight re-fits the deck on every event, and is written once, on release (rec 21).
        if (!persist) return
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
        val all = artChoices(card)
        val at = all.indexOf(p.arts[card.id.value] ?: card.id.value).coerceAtLeast(0)
        val next = all[((at + by) % all.size + all.size) % all.size]
        p.copy(arts = if (next == card.id.value) p.arts - card.id.value else p.arts + (card.id.value to next))
    }

    /** Draws [card] with artwork [choice] from now on: a passcode, or `-k` for its k-th own picture. */
    fun chooseArt(card: Card, choice: Int) = update { p ->
        p.copy(arts = if (choice == card.id.value) p.arts - card.id.value else p.arts + (card.id.value to choice))
    }

    /** The pictures the person added to cards (1.0.18). Set by the window. */
    var customArt: com.kaiharimoto.neue.art.CustomArt? = null

    /** Every artwork [card] can be drawn with: the pool's, then its own pictures. */
    fun artChoices(card: Card): List<Int> = customArt?.choices(card) ?: CardArt.arts(card).map { it.value }

    fun toggleTheme() = update { it.copy(theme = if (it.theme == NeueTheme.PAPER) NeueTheme.INK else NeueTheme.PAPER) }

    /** A keyboard is attached to the tablet (set by the activity); the desk always has one. */
    var hardwareKeyboard by mutableStateOf(false)

    /** The search pop-out's last search, kept while it is closed. */
    var studioMemory by mutableStateOf<StudioMemory?>(null)

    /** The Decks page's cover picker, here rather than in the page so Back can close it (touch swarm, rec 2). */
    var coverPicking by mutableStateOf<com.kaiharimoto.mastertool.core.data.StoredDeck?>(null)

    /** Whether [dismissTop] has something to close. */
    val hasTop: Boolean
        get() = aiSheet || aiSetup || starting || reading != null || showcase != null || menu != null || viewing != null || paletteOpen || confirmDelete != null || confirmRemoveArt != null || cropping != null || qr != null || helpOpen || drawer != null || studio != null

    /** Closes the top-most thing. Returns false when nothing was open, so Esc can fall through. */
    fun dismissTop(): Boolean = when {
        showcase != null -> { showcase = null; true }
        cropping != null -> { cropping = null; true }
        menu != null -> { menu = null; true }
        viewing != null -> { viewing = null; true }
        paletteOpen -> { paletteOpen = false; true }
        confirmDelete != null -> { confirmDelete = null; true }
        confirmRemoveArt != null -> { confirmRemoveArt = null; true }
        qr != null -> { qr = null; true }
        helpOpen -> { helpOpen = false; true }
        drawer != null -> { drawer = null; true }
        studio != null -> { studio = null; true }
        reading != null -> { reading = null; true }
        starting -> { startLater(); true }
        aiSheet || aiSetup -> { update { it.copy(ai = it.ai.copy(panelOpen = false)) }; true }
        else -> false
    }

    // ---- lists of cards kept for consideration (1.0.19) ----------------------

    fun list(id: String?): CardList? = id?.let { wanted -> prefs.cardLists.firstOrNull { it.id == wanted } }

    /** The list a card goes onto: the one last used, else the one showing, else the first. */
    val activeList: CardList? get() = list(prefs.activeList) ?: list(prefs.poolList) ?: prefs.cardLists.firstOrNull()

    /** A new, empty list, made the active one; its id. */
    fun newList(name: String? = null): String {
        val id = CardLists.newId(prefs.cardLists)
        update { p -> p.copy(cardLists = p.cardLists + CardList(id, name ?: CardLists.newName(p.cardLists)), activeList = id) }
        return id
    }

    fun renameList(id: String, name: String) = update { p ->
        p.copy(cardLists = p.cardLists.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it })
    }

    fun deleteList(id: String) = update { p ->
        p.copy(
            cardLists = p.cardLists.filterNot { it.id == id },
            poolList = p.poolList?.takeIf { it != id },
            activeList = p.activeList?.takeIf { it != id },
        )
    }

    /** [card] onto list [id] — a new one when null — or off it; the list becomes the active one. */
    fun toggleOnList(card: Card, id: String? = activeList?.id) {
        val listId = id ?: newList()
        val before = list(listId) ?: return
        val on = card.id.value in before.ids
        update { p -> p.copy(cardLists = CardLists.replace(p.cardLists, CardLists.toggle(before, card.id.value)), activeList = listId) }
        note = Note(if (on) "Took ${card.name} off ${before.name}" else "Put ${card.name} on ${before.name}")
    }

    /** The pool showing list [id], or every card when null. */
    fun showList(id: String?) = update { it.copy(poolList = id, activeList = id ?: it.activeList) }

    fun go(to: Page) {
        dismissTop()
        page = to
        railHeld = false
    }

    fun focusSearch() {
        page = Page.BUILDER
        if (!prefs.poolVisible) update { it.copy(poolVisible = true) }
        focusSearchTick++
    }
}
