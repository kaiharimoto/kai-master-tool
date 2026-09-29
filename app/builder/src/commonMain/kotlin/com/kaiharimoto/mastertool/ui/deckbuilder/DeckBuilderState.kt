package com.kaiharimoto.mastertool.ui.deckbuilder

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.data.PoolCheck
import com.kaiharimoto.mastertool.core.data.PoolProgress
import com.kaiharimoto.mastertool.core.data.SyncResult
import com.kaiharimoto.mastertool.core.deck.DeckEdit
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.DeckHistory
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.deck.DeckLenses
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.deck.LensKeying
import com.kaiharimoto.mastertool.core.deck.DeckSorter
import com.kaiharimoto.mastertool.core.deck.StoredGroups
import com.kaiharimoto.mastertool.core.deck.DeckStatistics
import com.kaiharimoto.mastertool.core.deck.DeckValidation
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.deck.GroupDraft
import com.kaiharimoto.mastertool.core.deck.GroupDrafts
import com.kaiharimoto.mastertool.core.deck.GroupPresets
import com.kaiharimoto.mastertool.core.deck.RejectionReason
import com.kaiharimoto.mastertool.core.deck.SortMode
import com.kaiharimoto.mastertool.core.hand.Ask
import com.kaiharimoto.mastertool.core.hand.GoalOdds
import com.kaiharimoto.mastertool.core.hand.HandGoal
import com.kaiharimoto.mastertool.core.hand.HandGoals
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.core.web.WebCodec
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.DeckRead
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.mastertool.core.ydk.YdkParseResult
import com.kaiharimoto.mastertool.core.ydk.Zlib
import com.kaiharimoto.mastertool.ui.AppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject

/** The group a draft's selection is drawn as, before it has been saved. */
internal const val DRAFT_GROUP_ID = "__draft"

/** A transient message shown in the snackbar, optionally with an undo action. */
data class Toast(
    val message: String,
    val undo: (() -> Unit)? = null,
    val id: Long = 0,
)

/**
 * Identifies one edit so its snackbar can only undo *that* edit.
 *
 * A snackbar outlives the action that raised it. Without this, removing a card,
 * adding three more and then pressing the still-visible "Undo" would undo the
 * last add — the stack has no idea which button the user thought they pressed.
 */
data class UndoToken(val serial: Long)

/**
 * State holder for the deck builder.
 *
 * A plain remembered holder rather than a ViewModel: the Android activity keeps
 * its own instance across a configuration change (`configChanges` in the
 * manifest lists orientation), so a rotation does not recreate it and there is
 * nothing here that needs to survive one. One fewer dependency whose API can
 * drift.
 */
class DeckBuilderState(
    private val deps: AppDependencies,
    private val scope: CoroutineScope,
) {
    var index by mutableStateOf(CardIndex.EMPTY)
        private set

    var query by mutableStateOf("")
        private set

    var filter by mutableStateOf(CardFilter.NONE)
        private set

    var results by mutableStateOf<List<Card>>(emptyList())
        private set

    /** How many cards matched in total, which is usually more than were drawn. */
    var matchCount by mutableStateOf(0)
        private set

    /** How many of [matchCount] were found by their text rather than their name. */
    var effectMatchCount by mutableStateOf(0)
        private set

    /**
     * Whether a query also reads the text printed under the name.
     *
     * Lives here rather than on the layout state because it changes what
     * [results] *is*, and every text hit ranks below every name hit — so
     * turning it on appends and turning it off truncates, and neither reorders
     * anything above the cut. Persisted by the toolbar, the way the format is.
     */
    var searchEffects by mutableStateOf(true)
        private set

    var deck by mutableStateOf(Deck.EMPTY)
        private set

    var deckName by mutableStateOf("Untitled Deck")
        private set

    var deckId by mutableStateOf<String?>(null)
        private set

    var isSyncing by mutableStateOf(false)
        private set

    var syncMessage by mutableStateOf<String?>(null)
        private set

    /** How far an update of the pool has got, while one runs: for the progress bars in the title bar and Settings. */
    var poolProgress by mutableStateOf<PoolProgress?>(null)
        private set

    /** What the last check found — asked by hand in Settings, or implied by an update this session. Null until then. */
    var poolCheck by mutableStateOf<PoolCheck?>(null)
        private set

    var checkingPool by mutableStateOf(false)
        private set

    /**
     * The inspector opens onto a list, not a card.
     *
     * Opening a card from the search results and then flipping through the rest
     * of them is how you compare candidates for a slot; opening one card and
     * having to close it to see the next is how you stop bothering.
     */
    var inspection by mutableStateOf<Inspection?>(null)

    /**
     * The deck's functional breakdown — fully manual, drawn by the user.
     *
     * Travels with the deck: stored under its own key in the ydkx extended
     * payload, so a deck organised into groups opens organised, and a plain
     * `.ydk` round-trips without gaining a payload it never had.
     */
    var groups by mutableStateOf(DeckGroups.EMPTY)
        private set

    /**
     * Which lens the main deck is being read through.
     *
     * [Lens.DECK] is the quiet position — the plain mosaic. The other three are
     * partitions of the same grid, and the deck never moves between them: what
     * changes is which cards are drawn as one block, and in what colour.
     */
    var lens by mutableStateOf(Lens.DECK)
        private set

    /**
     * The key being looked at alone, if any.
     *
     * Tapping a key in the bar covers everything that is not in it. It answers
     * "where are my handtraps" in one gesture and without editing anything —
     * which is the question the breakdown exists for.
     */
    var isolatedKey by mutableStateOf<String?>(null)
        private set

    fun useLens(value: Lens) {
        if (value == lens) return
        lens = value
        isolatedKey = null
        // A draft is a Roles decision. Reading the deck another way is not
        // cancelling it, so it only closes when the lens can no longer show it.
        if (!value.isEditable) groupDraft = null
    }

    fun nextLens() = useLens(lens.next())

    fun previousLens() = useLens(lens.previous())

    fun toggleIsolation(keyId: String) {
        isolatedKey = if (isolatedKey == keyId) null else keyId
    }

    /** How the main deck is cut up right now, draft included. */
    fun keying(section: DeckSection = DeckSection.MAIN): LensKeying =
        DeckLenses.key(lens, deck[section], index::byId, groupsWithDraft, format)

    /**
     * The groups plus the one being drawn up, so a selection is a block of the
     * deck while it is being made rather than only after it is saved.
     */
    val groupsWithDraft: DeckGroups
        get() {
            val draft = groupDraft ?: return groups
            val provisional = DeckGroup(
                id = DRAFT_GROUP_ID,
                name = draft.name.ifBlank { "New group" },
                color = draft.color,
                // Last, so drawing up a group never renumbers the ones that
                // are already there and the bar does not jump under the finger.
                order = Int.MAX_VALUE,
            )
            return draft.selection.fold(groups.upsert(provisional)) { acc, id ->
                acc.assign(id, DRAFT_GROUP_ID)
            }
        }

    /**
     * The group being drawn up right now, if any.
     *
     * Its presence is a mode: while it is open, tapping a card in the main deck
     * puts that card in the group instead of removing a copy. That is the only
     * modal gesture in the builder, and it is worth it — assignment is a
     * multi-card decision ("these six are my handtraps"), and making it one card
     * at a time through a menu is how the breakdown went unused.
     */
    var groupDraft by mutableStateOf<GroupDraft?>(null)
        private set

    var groupManagerVisible by mutableStateOf(false)

    /**
     * The questions this deck is being asked, stored with it.
     *
     * The maths was always exact; what evaporated was the question. Keeping it
     * next to the groups it is written in terms of is what turns "open the
     * calculator and describe your deck to it again" into a number that is
     * simply true on screen while you cut a card.
     */
    var goals by mutableStateOf(HandGoals.EMPTY)
        private set

    /**
     * The goal open in the editor, if any — a working copy.
     *
     * Edited in the sheet and committed on Save, exactly like a group draft:
     * half a question does not belong in the deck file or on the undo stack.
     */
    var editingGoal by mutableStateOf<HandGoal?>(null)
        private set

    /** The card currently held in the 3D inspect, if any. */
    var heldCard by mutableStateOf<Card?>(null)

    var filtersVisible by mutableStateOf(false)

    var statsVisible by mutableStateOf(false)

    var issuesVisible by mutableStateOf(false)

    var helpVisible by mutableStateOf(false)

    var eggVisible by mutableStateOf(false)

    /**
     * Whether any text field in the builder has the caret.
     *
     * A count rather than a flag because focus moving from one field to another
     * reports the two changes in whichever order it likes, and a flag would end
     * up clear when focus had simply moved next door.
     */
    private var focusedTextFields by mutableStateOf(0)

    val textInputFocused: Boolean get() = focusedTextFields > 0

    fun onTextFieldFocusChanged(focused: Boolean) {
        focusedTextFields = (focusedTextFields + if (focused) 1 else -1).coerceAtLeast(0)
    }

    /** Which section the statistics panel is reporting on. */
    var statsSection by mutableStateOf(DeckSection.MAIN)

    var toast by mutableStateOf<Toast?>(null)
        private set

    var format by mutableStateOf(Format.TCG)
        private set

    /**
     * Set when an issue or a search result should be brought into view.
     *
     * Cleared by the pane once it has scrolled, so the same card can be
     * requested again later.
     */
    var revealRequest by mutableStateOf<RevealRequest?>(null)

    /** The `#ydkx-extended` payload of the loaded deck, preserved untouched. */
    private var extended: JsonObject? = null

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var searchJob: Job? = null
    private var toastCounter = 0L
    private var editSerial = 0L

    /** Whether the deck has changed since it was last loaded or saved: what an autosave waits on. */
    var dirty by mutableStateOf(false)
        private set

    /**
     * Recomputed only when the deck, pool or format actually changes.
     *
     * As a plain `get()` this ran on every recomposition of anything that read
     * it — including every keystroke in the deck-name field, since that shares a
     * recomposition scope with the legality readout — and validation walks every
     * distinct card scanning all three sections.
     */
    val validation: DeckValidation by derivedStateOf {
        DeckValidator.validate(deck, index::byId, format)
    }

    val statistics: DeckStatistics by derivedStateOf {
        DeckStatistics.of(deck, index::byId, statsSection)
    }

    /** Main-deck statistics, which is what opening-hand odds are drawn from. */
    val mainStatistics: DeckStatistics by derivedStateOf {
        DeckStatistics.of(deck, index::byId, DeckSection.MAIN)
    }

    /**
     * Backed by observable state rather than read from the deques directly: a
     * plain `ArrayDeque` is invisible to snapshot observation, so the undo button
     * never noticed it had become enabled.
     */
    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        scope.launch {
            index = deps.cardRepository.loadFromCache()
            runSearch(immediate = true)

            val status = deps.cardRepository.status()
            if (status.isEmpty) {
                refreshCardPool(force = true, silentWhenFresh = false)
            } else {
                refreshCardPool(force = false, silentWhenFresh = true)
            }
        }
    }

    /**
     * Asks YGOPRODeck whether the pool is current, without fetching it (kai: "before
     * they leave on a flight"). The answer stays in [poolCheck] for Settings to read.
     */
    fun checkCardPool() {
        if (checkingPool || isSyncing) return
        scope.launch {
            checkingPool = true
            try {
                poolCheck = deps.cardRepository.check()
            } finally {
                checkingPool = false
            }
        }
    }

    fun refreshCardPool(force: Boolean = true, silentWhenFresh: Boolean = false) {
        if (isSyncing) return
        scope.launch {
            isSyncing = true
            syncMessage = if (index.size == 0) "Downloading the card database…" else "Refreshing…"
            // The repository reports from whichever thread is working; the state is
            // written on the scope's, and only when the bar would move.
            var shown = -1
            val result = try {
                deps.cardRepository.sync(force = force) { step ->
                    val at = (step.fraction * 200).toInt()
                    if (at != shown) {
                        shown = at
                        scope.launch { if (isSyncing) poolProgress = step }
                    }
                }
            } finally {
                poolProgress = null
            }

            when (result) {
                is SyncResult.Updated -> {
                    index = deps.cardRepository.index.value
                    runSearch(immediate = true)
                    poolCheck = PoolCheck.Current(result.version, result.cardCount, deps.now())
                    showToast("Card database updated — ${result.cardCount} cards.")
                }
                is SyncResult.UpToDate -> {
                    if (!silentWhenFresh) showToast("Card database is up to date.")
                }
                is SyncResult.Failed -> {
                    val suffix = if (result.cachedCardCount > 0) {
                        " Using the ${result.cachedCardCount} cards already on this device."
                    } else {
                        " No cards are cached yet — connect to the internet and try again."
                    }
                    showToast("Couldn't reach the card database.$suffix")
                }
            }

            isSyncing = false
            syncMessage = null
            poolProgress = null
        }
    }

    // ---- search ------------------------------------------------------------

    fun onQueryChange(value: String) {
        query = value
        runSearch()
    }

    fun onFilterChange(value: CardFilter) {
        // The format is owned by the toolbar, not the filter sheet, so it is
        // stamped on here rather than left for whoever consumes the filter.
        filter = value.copy(format = format)
        runSearch(immediate = true)
    }

    fun clearFilters() = onFilterChange(CardFilter.NONE)

    fun onSearchEffectsChange(value: Boolean) {
        if (searchEffects == value) return
        searchEffects = value
        runSearch(immediate = true)
    }

    private fun runSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        val activeQuery = query
        val activeFilter = filter
        val activeScope = if (searchEffects) SearchScope.ALL else SearchScope.NAMES
        searchJob = scope.launch {
            // Debounced so a fast typist scans the pool once, not once per key.
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            // Scoring 13,000 names with a bounded Levenshtein is far too much
            // work for the frame thread, and `scope` is the composition's.
            val outcome = withContext(deps.computeDispatcher) {
                index.search(activeQuery, activeFilter, activeScope, limit = RESULT_LIMIT)
            }
            results = outcome.cards
            matchCount = outcome.matchCount
            effectMatchCount = outcome.effectMatchCount
        }
    }

    // ---- editing -----------------------------------------------------------

    /** Whether the copy went in: what a finger's haptic and ring wait on (touch swarm, rec 13). */
    fun addCard(card: Card, section: DeckSection = card.requiredSection()): Boolean =
        applyEdit(DeckEditor.add(deck, card, section, format), card)

    fun removeOne(card: Card, section: DeckSection) {
        when (val result = DeckEditor.remove(deck, card.id, section)) {
            is DeckEdit.Applied -> {
                val token = pushUndo(deck)
                deck = result.deck
                showToast("Removed ${card.name}.", undo = { undoIfCurrent(token) })
            }
            is DeckEdit.Rejected -> showToast(explain(result.reason, card))
        }
    }

    fun moveCard(card: Card, from: DeckSection, to: DeckSection) {
        applyEdit(DeckEditor.move(deck, card, from, to, format), card)
    }

    fun setCount(card: Card, section: DeckSection, count: Int) {
        applyEdit(DeckEditor.setCount(deck, card, section, count, format), card)
    }

    // ---- drops -------------------------------------------------------------
    //
    // Every one of these goes through the same `applyEdit` as tapping does, so a
    // drop is undoable and a rejected drop explains itself in the same words.

    fun addCardAt(card: Card, section: DeckSection, index: Int): Boolean =
        applyEdit(DeckEditor.addAt(deck, card, section, index, format), card)

    fun moveCardTo(
        card: Card,
        from: DeckSection,
        fromIndex: Int,
        to: DeckSection,
        insertBefore: Int,
    ): Boolean = applyEdit(DeckEditor.moveAt(deck, card, from, fromIndex, to, insertBefore, format), card)

    /** Drag-out: the copy at [index] leaves the deck. */
    fun removeAt(card: Card, section: DeckSection, index: Int): Boolean =
        when (val result = DeckEditor.removeAt(deck, section, index)) {
            is DeckEdit.Applied -> {
                val token = pushUndo(deck)
                deck = result.deck
                showToast("Removed ${card.name}.", undo = { undoIfCurrent(token) })
                true
            }
            is DeckEdit.Rejected -> false
        }

    /**
     * Whether a drop would be accepted, for live feedback during a drag.
     *
     * [from] is null when the card is being dragged out of the search pane.
     */
    fun canDrop(card: Card, from: DeckSection?, to: DeckSection): Boolean {
        if (!DeckEditor.sectionAccepts(card, to)) return false
        // A move does not change how many copies the deck holds, so only a card
        // arriving from the pool can be stopped by the banlist.
        if (from == null && DeckEditor.remainingCopies(deck, card, format) <= 0) return false
        // Reordering within a section needs no room; arriving in a new one does.
        if (from != to && deck[to].size >= to.maxSize) return false
        return true
    }

    fun removeAllCopies(card: Card, section: DeckSection) {
        val remaining = deck[section].filterNot { it == card.id }
        if (remaining.size == deck[section].size) return

        val token = pushUndo(deck)
        deck = deck.with(section, remaining)
        showToast(
            "Removed every copy of ${card.name} from ${section.displayName}.",
            undo = { undoIfCurrent(token) },
        )
    }

    /**
     * Reorders a section.
     *
     * An edit, not a view setting: the stored order is what gets written back to
     * `.ydk`, so sorting only the display would leave the file disagreeing with
     * the deck. Being an edit also means one press of undo puts it back.
     */
    fun sortSection(section: DeckSection, mode: SortMode) {
        val current = deck[section]
        val sorted = DeckSorter.sort(current, index::byId, mode, format)
        if (sorted == current) return

        val token = pushUndo(deck)
        deck = deck.with(section, sorted)
        showToast(
            "Sorted ${section.displayName} Deck by ${mode.displayName.lowercase()}.",
            undo = { undoIfCurrent(token) },
        )
    }

    /** Every section currently holding [id], for controls that act on a card. */
    fun sectionsHolding(id: CardId): List<DeckSection> =
        DeckSection.entries.filter { section -> deck[section].any { it == id } }

    fun copiesIn(id: CardId, section: DeckSection): Int = deck[section].count { it == id }

    private fun applyEdit(edit: DeckEdit, card: Card): Boolean =
        when (edit) {
            is DeckEdit.Applied -> {
                if (edit.deck != deck) {
                    pushUndo(deck)
                    deck = edit.deck
                }
                true
            }
            is DeckEdit.Rejected -> {
                showToast(explain(edit.reason, card))
                false
            }
        }

    private fun explain(reason: RejectionReason, card: Card): String = when (reason) {
        RejectionReason.SECTION_FULL -> "That section is full."
        RejectionReason.COPY_LIMIT -> {
            val limit = DeckEditor.copyLimit(card, format)
            if (limit == 0) {
                "${card.name} is Forbidden in ${format.name}."
            } else {
                "${card.name} is limited to $limit ${if (limit == 1) "copy" else "copies"}."
            }
        }
        RejectionReason.WRONG_SECTION ->
            if (card.isExtraDeck) {
                "${card.name} belongs in the Extra Deck."
            } else {
                "${card.name} can't go in the Extra Deck."
            }
        RejectionReason.NOT_PLAYABLE -> "${card.name} can't be put in a deck."
        RejectionReason.NOT_PRESENT -> "That card isn't in this section."
    }

    // ---- history -----------------------------------------------------------

    /**
     * What the deck was, and — only when the edit replaced the whole deck —
     * who it was.
     *
     * A card edit stores no identity, so undoing one never reverts a rename
     * made after it (the bug that rule exists for). "New deck" and an import
     * change the name, the id and the ydkx payload as part of the edit itself,
     * and an undo that brought back the cards under the wrong name — ready to
     * be saved as a duplicate, with the ydkx metadata gone — was half an undo.
     */
    private data class DeckIdentity(
        val name: String,
        val id: String?,
        val extended: JsonObject?,
    )

    private data class HistoryEntry(
        val deck: Deck,
        val identity: DeckIdentity?,
        /** Snapshotted only by edits that touch the breakdown, same rule as identity. */
        val groups: StoredGroups?,
    )

    private fun currentIdentity() = DeckIdentity(deckName, deckId, extended)

    private fun applyIdentity(identity: DeckIdentity) {
        deckName = identity.name
        deckId = identity.id
        extended = identity.extended
    }

    private fun currentStoredGroups() = StoredGroups(groups, lens, goals)

    private fun applyStoredGroups(stored: StoredGroups) {
        groups = stored.groups
        lens = stored.lens
        goals = stored.goals.pruned(stored.groups)
        isolatedKey = null
        editingGoal = null
        // A draft is about the cards in front of you. Loading, importing or
        // starting a new deck replaces those, so it cannot mean anything now.
        groupDraft = null
    }

    private fun pushUndo(
        previous: Deck,
        identity: DeckIdentity? = null,
        groupsSnapshot: StoredGroups? = null,
    ): UndoToken {
        undoStack.addLast(HistoryEntry(previous, identity, groupsSnapshot))
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        // A new edit invalidates anything that was undone to reach this point.
        redoStack.clear()
        return stamp()
    }

    /**
     * The undo history in words, newest first (desktop's history menu): each step
     * the stack could take back, then each it could put back again. Read off the
     * decks either side of every entry, since the stack keeps decks, not edits.
     */
    fun history(): HistoryView {
        val name: (com.kaiharimoto.mastertool.core.model.CardId) -> String? = { index.byId(it)?.name }
        // Every change to the groups or the goals snapshots them, so the state after an
        // entry's is the next snapshot along, or now.
        fun goalsOnly(was: StoredGroups?, now: StoredGroups): Boolean =
            was != null && was.goals != now.goals && was.groups == now.groups
        val done = undoStack.mapIndexed { i, entry ->
            val after = undoStack.getOrNull(i + 1)?.deck ?: deck
            val afterGroups = undoStack.drop(i + 1).firstOrNull { it.groups != null }?.groups ?: currentStoredGroups()
            DeckHistory.describe(entry.deck, after, entry.groups != null, entry.identity != null, goalsOnly(entry.groups, afterGroups), name)
        }.asReversed()
        // The redo stack's last entry is the next redo; the deck before it is now.
        val redoOrder = redoStack.asReversed()
        val undone = redoOrder.mapIndexed { i, entry ->
            val before = if (i == 0) deck else redoOrder[i - 1].deck
            val beforeGroups = redoOrder.take(i).lastOrNull { it.groups != null }?.groups ?: currentStoredGroups()
            DeckHistory.describe(before, entry.deck, entry.groups != null, entry.identity != null, entry.groups?.let { goalsOnly(beforeGroups, it) } ?: false, name)
        }
        return HistoryView(done, undone)
    }

    /** Takes back [steps] edits at once, or puts back [steps] undone ones when negative. */
    fun travel(steps: Int) {
        if (steps > 0) repeat(steps) { undo() } else repeat(-steps) { redo() }
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        // Symmetric: only an entry that restores identity or groups captures
        // them going the other way, so redo can put the new state back too.
        redoStack.addLast(
            HistoryEntry(
                deck,
                entry.identity?.let { currentIdentity() },
                entry.groups?.let { currentStoredGroups() },
            )
        )
        deck = entry.deck
        entry.identity?.let(::applyIdentity)
        entry.groups?.let(::applyStoredGroups)
        stamp()
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(
            HistoryEntry(
                deck,
                entry.identity?.let { currentIdentity() },
                entry.groups?.let { currentStoredGroups() },
            )
        )
        deck = entry.deck
        entry.identity?.let(::applyIdentity)
        entry.groups?.let(::applyStoredGroups)
        stamp()
    }

    // ---- groups ------------------------------------------------------------

    /**
     * Every breakdown edit goes through here: undoable like a card edit, and a
     * no-op transform stays off the history.
     */
    fun updateGroups(transform: (DeckGroups) -> DeckGroups) {
        val next = transform(groups)
        if (next == groups) return
        pushUndo(deck, groupsSnapshot = currentStoredGroups())
        groups = next
        // An ask against a role that no longer exists would read as "at least
        // one of nothing" and report zero forever, with nothing on screen to
        // explain why.
        goals = goals.pruned(next)
    }

    // ---- the questions -----------------------------------------------------

    /** Every goal edit is undoable, like a group edit and by the same path. */
    private fun updateGoals(transform: (HandGoals) -> HandGoals) {
        val next = transform(goals)
        if (next == goals) return
        pushUndo(deck, groupsSnapshot = currentStoredGroups())
        goals = next
    }

    fun newGoal() {
        editingGoal = HandGoal.blank(id = "q-${Random.nextLong().toString(16)}")
    }

    /** Opens a stored goal, or the first one, or a fresh one if there are none. */
    fun openGoal(id: String? = null) {
        editingGoal = id?.let(goals::byId) ?: goals.goals.firstOrNull()
        if (editingGoal == null) newGoal()
    }

    fun setGoalName(value: String) {
        editingGoal = editingGoal?.copy(name = value)
    }

    fun setGoalHandSize(value: Int) {
        editingGoal = editingGoal?.copy(handSize = value)
    }

    fun setGoalAsk(groupId: String, ask: Ask) {
        editingGoal = editingGoal?.with(groupId, ask)
    }

    fun clearGoalAsks() {
        editingGoal = editingGoal?.copy(asks = emptyMap())
    }

    fun saveGoal() {
        val goal = editingGoal ?: return
        // A question that asks nothing is not a question; saving it would put a
        // chip on the bar reading 100% forever.
        if (!goal.isEmpty) {
            updateGoals { it.upsert(goal.copy(name = goal.name.trim())) }
        }
        editingGoal = null
    }

    fun deleteGoal() {
        val goal = editingGoal ?: return
        updateGoals { it.remove(goal.id) }
        editingGoal = null
    }

    fun cancelGoal() {
        editingGoal = null
    }

    /** What a stored goal currently comes out at, over the deck as it stands. */
    fun oddsOf(goal: HandGoal): Double = GoalOdds.probability(goal, deck.main, groups)

    fun assignCardToGroup(id: CardId, groupId: String?) =
        updateGroups { it.assign(id, groupId) }

    // ---- the group draft ---------------------------------------------------
    //
    // Nothing here touches the breakdown until Save: a draft is a decision being
    // made, and half a decision does not belong in the deck file or on the undo
    // stack. Cancel therefore costs nothing and leaves nothing behind.

    /**
     * Opens an empty draft, optionally named by a [preset] and with [seed]
     * already picked.
     *
     * Drawing up a group is a Roles decision, so it switches to that lens: the
     * cards you are about to tap have to be the ones the colour is landing on.
     */
    fun startGroupDraft(seed: CardId? = null, preset: GroupPresets.Preset? = null) {
        lens = Lens.ROLES
        isolatedKey = null
        groupDraft = GroupDraft(
            name = preset?.name.orEmpty(),
            color = preset?.color ?: groups.nextColor(),
            selection = setOfNotNull(seed),
        )
    }

    /** Reopens an existing group with everything already in it selected. */
    fun editGroup(group: DeckGroup) {
        lens = Lens.ROLES
        isolatedKey = null
        // Every section: a group may hold extra- and side-deck cards too, and a draft
        // opened on the main deck alone dropped them from the group when it was saved.
        groupDraft = GroupDrafts.edit(groups, group, deck.main + deck.extra + deck.side)
    }

    fun setDraftName(value: String) {
        groupDraft = groupDraft?.copy(name = value)
    }

    fun setDraftColor(color: Int) {
        groupDraft = groupDraft?.copy(color = color)
    }

    fun toggleDraftSelection(id: CardId) {
        groupDraft = groupDraft?.toggle(id)
    }

    fun cancelGroupDraft() {
        groupDraft = null
    }

    fun saveGroupDraft() {
        val draft = groupDraft ?: return
        val name = draft.name.trim()
        updateGroups { GroupDrafts.commit(it, draft, ::mintGroupId) }
        groupDraft = null
        showToast(
            if (name.isBlank()) {
                "Saved a group of ${draft.selection.size}."
            } else {
                "$name — ${draft.selection.size} card${if (draft.selection.size == 1) "" else "s"}."
            }
        )
    }

    /** Deletes the group the draft was opened on, freeing its cards. */
    fun deleteDraftGroup() {
        val id = groupDraft?.editingId ?: return
        val name = groups.byId(id)?.name
        updateGroups { it.remove(id) }
        groupDraft = null
        showToast("Deleted ${name ?: "the group"}.")
    }

    private fun mintGroupId(): String = "g-${Random.nextLong().toString(16)}"

    /** The loaded deck's extended payload as the next save will write it: groups and every other key. */
    fun extendedNow(): JsonObject? = extendedForWrite()

    /**
     * One key of the loaded deck's extended payload set from outside the builder —
     * the siding editor's (1.0.35), which writes the stored deck itself and hands
     * the builder the same key so its next save does not write the old one back.
     * Not an edit to the deck: nothing to undo, and nothing unsaved.
     */
    fun putExtended(key: String, value: kotlinx.serialization.json.JsonElement?) {
        val others = extended?.filterKeys { it != key }.orEmpty()
        val next = if (value == null) others else others + (key to value)
        extended = if (next.isEmpty()) null else JsonObject(next)
    }

    /** The extended payload with the current breakdown written into it. */
    private fun extendedForWrite() =
        DeckGroupsCodec.write(extended, StoredGroups(groups, lens, goals))

    /** Undoes an edit only while it is still the most recent one. */
    private fun undoIfCurrent(token: UndoToken) {
        if (editSerial == token.serial) undo()
    }

    private fun stamp(): UndoToken {
        editSerial++
        dirty = true
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        return UndoToken(editSerial)
    }

    fun onFormatChange(value: Format) {
        format = value
        filter = filter.copy(format = value)
        runSearch(immediate = true)
    }

    /**
     * Renaming is not a deck edit and deliberately does not touch the undo
     * stack: it used to be restored alongside the deck, so undoing a card add
     * silently reverted a rename made after it.
     */
    fun rename(value: String) {
        deckName = value
    }

    // ---- persistence -------------------------------------------------------

    fun newDeck() {
        afterFirstSave = null
        val token = pushUndo(deck, currentIdentity(), currentStoredGroups())
        deck = Deck.EMPTY
        deckName = "Untitled Deck"
        deckId = null
        extended = null
        applyStoredGroups(StoredGroups.EMPTY)
        showToast("Started a new deck.", undo = { undoIfCurrent(token) })
    }

    /** Saves the deck; [quiet] is an autosave, which says nothing. */
    fun save(quiet: Boolean = false, onSaved: (String) -> Unit = {}) {
        val serial = editSerial
        scope.launch {
            val fresh = deckId == null
            val id = deckId ?: deps.newDeckId().also { deckId = it }
            deps.deckRepository.save(
                id,
                deckName.ifBlank { "Untitled Deck" },
                deck,
                extendedForWrite(),
            )
            if (fresh) afterFirstSave?.invoke(id)
            afterFirstSave = null
            // An edit made while the write was in flight is still unsaved.
            if (editSerial == serial) dirty = false
            if (!quiet) showToast("Saved \"$deckName\".")
            onSaved(id)
        }
    }

    fun load(id: String) {
        scope.launch {
            val stored = deps.deckRepository.byId(id) ?: return@launch
            afterFirstSave = null
            deck = stored.entry.deck
            deckName = stored.entry.name
            deckId = stored.entry.id
            extended = stored.extended
            applyStoredGroups(DeckGroupsCodec.read(stored.extended))
            undoStack.clear()
            redoStack.clear()
            stamp()
            dirty = false
        }
    }

    /**
     * Where a `.ydkw` goes when it arrives through a deck's Import, or is handed over
     * by another app (1.0.33): it is a web of decks, not a deck, and Neue opens it in
     * Format. Unset, it is read as a deck file, which finds none of its decks.
     */
    var onWebFile: ((String) -> Unit)? = null

    fun importFromFile() {
        scope.launch {
            val file = deps.fileAccess.importDeck() ?: return@launch
            val web = onWebFile
            if (web != null && WebCodec.isWeb(file.content)) {
                web(file.content)
                return@launch
            }
            adopt(YdkCodec.parse(file.content), file.name.substringBeforeLast('.').ifBlank { "Imported Deck" })
        }
    }

    /**
     * A deck read from text rather than a file (v1.3.7): what a QR code held —
     * Neue's own code, the whole `.ydkx` with its name and covers ([DeckQr]), a
     * `ydke://` code, or a deck file's lines ([DeckCodes]). [read] fetches it (a
     * scan, which may take a while); null is a scan put away, and says nothing.
     * Like a file, it replaces the deck, with Undo on the toast. The name is the
     * code's, else [fallbackName]; [onFirstSave] hears the deck's id when it is
     * first saved, with what was read — the covers are kept by the deck's id.
     */
    fun importFrom(
        fallbackName: String,
        zlib: Zlib,
        onFirstSave: (id: String, read: DeckRead) -> Unit = { _, _ -> },
        read: suspend () -> String?,
    ) {
        scope.launch {
            val text = read() ?: return@launch
            val found = DeckCodes.read(text, zlib)
            if (found == null) {
                showToast("That code holds no deck.")
            } else {
                adopt(found.parsed, found.name ?: fallbackName)
                afterFirstSave = { id -> onFirstSave(id, found) }
            }
        }
    }

    /** What a deck read from a code still owes once it has an id: its covers (1.0.31). */
    private var afterFirstSave: ((String) -> Unit)? = null

    private fun adopt(parsed: YdkParseResult, name: String) {
        afterFirstSave = null
        val token = pushUndo(deck, currentIdentity(), currentStoredGroups())
        deck = parsed.document.deck
        deckName = name
        deckId = null
        extended = parsed.document.extended
        applyStoredGroups(DeckGroupsCodec.read(parsed.document.extended))

        val warning = parsed.warnings.size
            .takeIf { it > 0 }
            ?.let { " ($it line${if (it == 1) "" else "s"} skipped)" }
            .orEmpty()
        showToast("Imported ${deck.totalCards} cards$warning.", undo = { undoIfCurrent(token) })
    }

    fun exportToFile() {
        scope.launch {
            val payload = extendedForWrite()
            val text = YdkCodec.write(deck, createdBy = "kai's master tool", extended = payload)
            val extension = if (payload != null) "ydkx" else "ydk"
            val name = "${deckName.ifBlank { "deck" }}.$extension"
            if (deps.fileAccess.exportDeck(name, text)) {
                showToast("Exported $name.")
            }
        }
    }

    /**
     * The deck to a file of the kind asked for: a plain `.ydk`, or a `.ydkx` with
     * the groups, goals and anything else the deck carries (desktop's Export menu).
     */
    fun exportFile(withGroups: Boolean) {
        scope.launch {
            val payload = if (withGroups) extendedForWrite() else null
            val text = YdkCodec.write(deck, createdBy = "kai's master tool", extended = payload)
            val name = "${deckName.ifBlank { "deck" }}.${if (withGroups) "ydkx" else "ydk"}"
            if (deps.fileAccess.exportDeck(name, text)) showToast("Exported $name.")
        }
    }

    /** The deck as its `.ydkx` carries it: the cards, and the groups, goals and every other extended key (the QR code's, 1.0.31). */
    fun document(): YdkDocument = YdkDocument(deck, extended = extendedForWrite())

    fun shareDeck() {
        scope.launch {
            val payload = extendedForWrite()
            val text = YdkCodec.write(deck, createdBy = "kai's master tool", extended = payload)
            val extension = if (payload != null) "ydkx" else "ydk"
            deps.fileAccess.shareDeck("${deckName.ifBlank { "deck" }}.$extension", text)
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** Opens the inspector on [index] of [cards], which it can then page through. */
    fun inspect(cards: List<Card>, index: Int) {
        if (cards.isEmpty()) return
        inspection = Inspection(cards, index.coerceIn(0, cards.lastIndex))
    }

    fun inspect(card: Card) = inspect(listOf(card), 0)

    /** Steps the inspector through the list it was opened on. */
    fun pageInspection(delta: Int) {
        val current = inspection ?: return
        val next = (current.index + delta).coerceIn(0, current.cards.lastIndex)
        if (next != current.index) inspection = current.copy(index = next)
    }

    /** Keeps the stored page in step when the inspector is swiped rather than keyed. */
    fun onInspectionPageChanged(index: Int) {
        val current = inspection ?: return
        if (current.index != index) inspection = current.copy(index = index)
    }

    fun copiesInDeck(id: CardId): Int = deck.copiesOf(id)

    fun remaining(card: Card): Int = DeckEditor.remainingCopies(deck, card, format)

    /** Asks the owning pane to scroll [id] into view and flash it. */
    /** Asks the deck to ring the card at [position] in [section] (touch swarm, rec 15: an add a finger made, where it landed). */
    fun revealAt(section: DeckSection, position: Int) {
        val id = deck[section].getOrNull(position) ?: return
        revealRequest = RevealRequest(section, position, id, ++toastCounter)
    }

    fun reveal(section: DeckSection, id: CardId) {
        val position = deck[section].indexOfFirst { it == id }
        if (position < 0) return
        revealRequest = RevealRequest(section, position, id, ++toastCounter)
    }

    fun showToast(message: String, undo: (() -> Unit)? = null) {
        toast = Toast(message, undo, ++toastCounter)
    }

    fun consumeToast() {
        toast = null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 130L
        const val RESULT_LIMIT = 150
        const val UNDO_DEPTH = 50
    }
}

/** What the inspector is showing, and what it can page to from there. */
data class Inspection(val cards: List<Card>, val index: Int)

/** A request to scroll a card into view, raised by the issues panel. */
data class RevealRequest(
    val section: DeckSection,
    val position: Int,
    val cardId: CardId,
    val id: Long,
)

/** The history menu's two lists, newest first: steps [done] (undo takes them back) and [undone] (redo puts them back). */
data class HistoryView(val done: List<String>, val undone: List<String>)
