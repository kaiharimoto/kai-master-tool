package com.kaiharimoto.mastertool.ui

import com.kaiharimoto.mastertool.core.data.CardRepository
import com.kaiharimoto.mastertool.core.data.DeckRepository
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.update.UpdateChecker
import com.kaiharimoto.mastertool.ui.update.AppUpdater
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A deck file chosen by the user, as returned by the platform picker. */
data class ImportedFile(val name: String, val content: String)

/**
 * Platform file access.
 *
 * An interface rather than expect/actual so `:ui` stays free of platform source
 * sets: Android supplies an implementation backed by the Storage Access
 * Framework, desktop one backed by a file dialog.
 */
interface DeckFileAccess {
    /** Opens a picker. Returns null when the user cancels. */
    suspend fun importDeck(): ImportedFile?

    /** Writes [content] out, letting the user choose where. */
    suspend fun exportDeck(suggestedName: String, content: String): Boolean

    /** Hands the file to the platform share sheet, where one exists. */
    suspend fun shareDeck(suggestedName: String, content: String)
}

/**
 * Everything the UI needs, assembled by each platform's entry point.
 *
 * Hand-rolled rather than reached for via a DI framework: the graph is four
 * objects deep and an explicit constructor is easier to follow than annotations.
 */
class AppDependencies(
    val cardRepository: CardRepository,
    val deckRepository: DeckRepository,
    val preferencesRepository: PreferencesRepository,
    val fileAccess: DeckFileAccess,
    val updateChecker: UpdateChecker,
    val updater: AppUpdater,
    val newDeckId: () -> String,
    val now: () -> Long,
    /**
     * Where CPU-bound work runs.
     *
     * Searching scores every card in the pool, which is far too much to do on
     * the frame thread — and the scope the UI hands to its state holders is the
     * composition's, so anything launched there lands on the main dispatcher
     * unless it says otherwise.
     */
    val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Directory for the card-art disk cache, or null to run memory-only.
     *
     * Card art has to survive a cold start without a network — the app's whole
     * premise is working at a venue with no signal, and a memory-only cache
     * quietly re-fetched every image on every launch.
     */
    val imageCacheDir: String? = null,
)
