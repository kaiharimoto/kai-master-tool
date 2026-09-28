package com.kaiharimoto.mastertool.ui.deckbuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.deck.SortMode
import com.kaiharimoto.mastertool.core.layout.Posture
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.SectionPreferences
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How the deck builder is laid out, and where that gets remembered.
 *
 * Split out from [DeckBuilderState] because it answers a different question: one
 * holds the deck you are building, this holds the shape of the room you are
 * building it in. They change at completely different rates — a resize drag
 * writes this sixty times a second and never touches the deck.
 */
class DeckLayoutState(
    private val repository: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    var preferences by mutableStateOf(UiPreferences.DEFAULT)
        private set

    /** Height of the deck column in pixels, needed to turn a drag into a weight. */
    var deckColumnHeightPx by mutableStateOf(0f)

    /** Width of the whole builder in pixels, for the search / deck split. */
    var builderWidthPx by mutableStateOf(0f)

    private var saveJob: Job? = null

    /** Whether the user has touched the layout since launch. */
    private var edited = false

    /**
     * Survives the composition the debounced [saveJob] cannot: a save that is
     * still waiting out its delay when the window closes gets cancelled with
     * the rest of the UI's scope, so [flush] hands the final write to a scope
     * nothing tears down.
     */
    private val flushScope = CoroutineScope(SupervisorJob())

    fun start(onLoaded: (UiPreferences) -> Unit = {}) {
        scope.launch {
            val stored = repository.load()
            // A document written by an older build keeps its own row widths
            // forever unless something rewrites them — a changed default only
            // ever reaches a device with nothing saved on it.
            val current = stored.migrated()
            // The read races the user's first inputs. A divider dragged while
            // the database was still opening must not snap back when the stored
            // document finally arrives — whatever the user did wins.
            if (!edited) {
                preferences = current
                onLoaded(preferences)
                if (current != stored) scheduleSave(0L)
            }
        }
    }

    /**
     * [debounce] is for per-frame writers — the resize drags — where none of
     * the intermediate values are worth a disk write. Everything discrete saves
     * straight away, so a toggled setting is on disk before anything can
     * happen to the process.
     */
    fun update(debounce: Boolean = false, transform: (UiPreferences) -> UiPreferences) {
        edited = true
        preferences = transform(preferences).sanitised()
        scheduleSave(if (debounce) SAVE_DEBOUNCE_MS else 0L)
    }

    fun updateSection(section: DeckSection, transform: (SectionPreferences) -> SectionPreferences) {
        update { it.with(section, transform(it[section])) }
    }

    fun toggleCollapsed(section: DeckSection) {
        updateSection(section) { it.copy(collapsed = !it.collapsed) }
    }

    /**
     * Gives one section the whole column by collapsing the other two, and puts
     * them back when it is already the only one open.
     *
     * What "focus the Main deck" can usefully mean once panes collapse: all three
     * are on screen at once, so there is nothing to scroll to — the useful move is
     * to give the one being worked on the space.
     */
    fun focusSection(section: DeckSection) {
        val alreadyFocused = DeckSection.entries.all { entry ->
            preferences[entry].collapsed == (entry != section)
        }

        update { prefs ->
            DeckSection.entries.fold(prefs) { acc, entry ->
                acc.with(
                    entry,
                    acc[entry].copy(collapsed = if (alreadyFocused) false else entry != section),
                )
            }
        }
    }

    /**
     * How many cards a row of [section] holds, in this [posture].
     *
     * Portrait has one row width for the whole deck rather than one per
     * section — see [UiPreferences.tallColumns] — and, crucially, it is stored
     * somewhere else. The two postures share a device now: a phone-sized row
     * width written while a tablet was held upright must not still be there
     * when it is turned back on its side.
     */
    fun columnsFor(section: DeckSection, posture: Posture): Int =
        if (posture.isTall) preferences.tallColumns else preferences[section].columns

    /**
     * Sets how many cards a row of [section] holds.
     *
     * Under the fitter this is the whole setting — the row width is chosen, and
     * the card size follows from it. By hand it also means "stop choosing the
     * count for me", which is what [SectionPreferences.autoFit] is. In portrait
     * there is nothing to stop choosing: the column scrolls and the row width
     * was never solved for, so the number is simply the number.
     */
    fun setColumns(section: DeckSection, columns: Int, posture: Posture) {
        val clamped = columns.coerceIn(
            SectionPreferences.MIN_COLUMNS,
            SectionPreferences.MAX_COLUMNS,
        )
        if (posture.isTall) {
            update { it.copy(tallColumns = clamped) }
        } else {
            updateSection(section) { it.copy(columns = clamped, autoFit = false) }
        }
    }

    /** Whether the three panes are sized to show the whole deck at once. */
    fun setFitAll(value: Boolean) {
        update { it.copy(fitAll = value) }
    }

    /** Hides the card pool and gives the deck the whole window. */
    fun toggleSearchPane() {
        update { it.copy(searchVisible = !it.searchVisible) }
    }

    fun setSortMode(section: DeckSection, mode: SortMode) {
        updateSection(section) { it.copy(sortMode = mode) }
    }

    /**
     * Drags the divider between two panes.
     *
     * The two panes trade weight rather than each being set independently, so the
     * total stays put and the pane on the far side of the drag does not move —
     * which is what makes a three-pane resize feel like moving one boundary
     * instead of rescaling the whole column.
     */
    fun resizePanes(above: DeckSection, below: DeckSection, deltaPx: Float) {
        val height = deckColumnHeightPx
        if (height <= 0f) return

        val visibleWeight = DeckSection.entries
            .filterNot { preferences[it].collapsed }
            .sumOf { preferences[it].weight.toDouble() }
            .toFloat()
        if (visibleWeight <= 0f) return

        val weightPerPixel = visibleWeight / height
        val delta = deltaPx * weightPerPixel

        val top = preferences[above]
        val bottom = preferences[below]

        val newTop = (top.weight + delta)
            .coerceIn(SectionPreferences.MIN_WEIGHT, SectionPreferences.MAX_WEIGHT)
        // Apply only what the top pane actually accepted, so hitting a limit
        // stops the drag rather than quietly shrinking the other pane past it.
        val applied = newTop - top.weight
        val newBottom = bottom.weight - applied
        if (newBottom < SectionPreferences.MIN_WEIGHT) return

        update(debounce = true) {
            // Taking hold of a divider is the decision to size the panes by
            // hand: the fitter owns every height while it is on, so a drag under
            // it would move nothing and read as broken.
            it.copy(fitAll = false)
                .with(above, top.copy(weight = newTop))
                .with(below, bottom.copy(weight = newBottom))
        }
    }

    fun resizeSearchPane(deltaPx: Float) {
        val width = builderWidthPx
        if (width <= 0f) return
        update(debounce = true) { it.copy(searchWeight = it.searchWeight + deltaPx / width) }
    }

    /** Writes anything still waiting out its debounce. Call on the way out. */
    fun flush() {
        val pending = saveJob ?: return
        if (!pending.isActive) return
        pending.cancel()
        val snapshot = preferences
        flushScope.launch { repository.save(snapshot) }
    }

    private fun scheduleSave(afterMs: Long) {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (afterMs > 0) delay(afterMs)
            repository.save(preferences)
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
