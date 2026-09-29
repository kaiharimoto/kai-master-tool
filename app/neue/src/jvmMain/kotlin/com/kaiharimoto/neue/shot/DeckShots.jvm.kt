package com.kaiharimoto.neue.shot

import com.kaiharimoto.neue.cards.read
import com.kaiharimoto.neue.builder.bandsOn
import com.kaiharimoto.mastertool.core.layout.BandLayout
import com.kaiharimoto.mastertool.core.layout.GroupBands

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.remote.CardSetRelease
import com.kaiharimoto.mastertool.core.remote.CardSetReleases
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.cards.NameMasks
import com.kaiharimoto.neue.cards.NameStyles
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.time.LocalDate

/**
 * Taking the picture: ask where it goes, gather the originals of every card in
 * the deck (the art library's, downloaded now if need be — a picture is worth a
 * second's wait), ask YGOPRODeck what the newest TCG set is, draw, save.
 */
actual class DeckShots actual constructor(
    private val art: ArtLibrary,
    private val scope: CoroutineScope,
) {
    private var busy = false

    /** Taking the picture: seconds, with the full-size art to load. The family cursor shows it busy. */
    actual var taking by mutableStateOf(false)
        private set
    private var sets: List<CardSetRelease>? = null
    private val api by lazy { YgoProDeckApi(HttpClientFactory.create()) }

    actual fun export(state: DeckBuilderState, neue: NeueState) {
        if (busy) return
        val model = snapshot(state, neue)
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ask(suggestedName(model)) } ?: return@launch
                neue.note = Note("Taking the picture")
                taking = true
                val (png, missing) = try {
                    picture(model).also { withContext(Dispatchers.IO) { file.writeBytes(it.first) } }
                } finally {
                    taking = false
                }
                neue.note = Note(
                    if (missing == 0) "Saved ${file.name}" else "Saved ${file.name} · $missing without a picture",
                    action = "Show",
                ) { Platform.open(file.parentFile) }
            } catch (e: Exception) {
                neue.note = Note("The picture could not be saved: ${e.message ?: e::class.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    /** The PNG, and how many cards had no picture to put in it. Drawn on the calling thread; call it from the UI thread. */
    suspend fun picture(model: ShotModel): Pair<ByteArray, Int> {
        val cards = model.sections.flatMap { it.cards }.filterNotNull().distinctBy { it.id }
        val loaded = withContext(Dispatchers.IO) { load(cards) }
        val images = loaded.mapValues { it.value.first.toComposeImageBitmap() }
        val masks = if (model.names == NameStyles.PRINTED) emptyMap() else withContext(Dispatchers.Default) {
            loaded.mapNotNull { (id, pair) -> runCatching { NameMasks.read(pair.first, pair.second) }.getOrNull()?.let { id to it } }.toMap()
        }
        val latest = withContext(Dispatchers.IO) { latestSet(model.date) }
        return DeckShot.render(model.copy(latestSet = latest), images, masks) to (cards.size - images.size)
    }

    /** The bands the builder is showing, or the same deck laid out for the picture's shape; null when it shows none. */
    private fun bandsFor(state: DeckBuilderState, neue: NeueState): BandLayout? {
        if (!bandsOn(state, neue, neue.phoneColumns)) return null
        val ids = state.deck[DeckSection.MAIN]
        val shown: BandLayout? = neue.bandCache.last
        if (shown != null && shown.row.size == ids.size) return shown
        val keying = state.keying(DeckSection.MAIN)
        return GroupBands.layout(ids.map { it.value }, keying.keyOfCell, keying.keyOrder, 1600f to 900f)
    }

    fun snapshot(state: DeckBuilderState, neue: NeueState): ShotModel {
        // The groups are in the picture when they are on screen: a lens chosen, and no draft half-drawn.
        val showing = state.lens != Lens.DECK && state.groupDraft == null
        return ShotModel(
            name = state.deckName,
            format = state.format,
            date = LocalDate.now().toString(),
            latestSet = null,
            sections = DeckSection.entries.map { section ->
                ShotSection(
                    section = section,
                    // Each card with the artwork chosen for it, as the builder draws it.
                    cards = state.deck[section].map { id ->
                        state.index.byId(id)?.let { card ->
                            val choice = neue.prefs.arts[card.id.value]
                            neue.customArt?.drawn(card, choice) ?: com.kaiharimoto.mastertool.core.model.CardArt.show(card, choice?.let(::CardId))
                        }
                    },
                    keying = if (showing) state.keying(section).takeIf { !it.isEmpty } else null,
                    // The bands the builder is showing, or the same deck laid out for the picture's shape.
                    bands = if (section == DeckSection.MAIN) bandsFor(state, neue) else null,
                    separate = neue.prefs.arrangement == com.kaiharimoto.mastertool.core.layout.GroupArrangement.SEPARATE,
                )
            },
            lens = if (showing) state.lens.displayName else null,
            ink = neue.prefs.theme == NeueTheme.INK,
            foil = neue.prefs.foil,
            names = neue.prefs.foilNames,
            style = ShotStyle.of(neue.prefs.shotStyle),
        )
    }

    private fun suggestedName(model: ShotModel): String {
        val safe = model.name.ifBlank { "Deck" }.replace(Regex("""[\\/:*?"<>|]"""), " ").trim()
        return "$safe ${model.date}.png"
    }

    private fun ask(name: String): File? {
        val dialog = FileDialog(null as Frame?, "Save a picture of the deck", FileDialog.SAVE).apply {
            file = name
            isVisible = true
        }
        val chosen = dialog.file ?: return null
        val file = File(dialog.directory, chosen)
        return if (file.name.endsWith(".png", ignoreCase = true)) file else File(file.parentFile, file.name + ".png")
    }

    /** Every picture, the original where it can be had and the small render where it cannot, eight at a time. */
    private suspend fun load(cards: List<Card>): Map<Int, Pair<Image, String>> {
        val gate = Semaphore(8)
        return withContext(Dispatchers.IO) {
            cards.map { card ->
                async {
                    gate.withPermit {
                        val bytes = withTimeoutOrNull(20_000) { art.ensure(card)?.readBytes() }
                            ?: runCatching { card.imageUrlSmall?.let { URI.create(it).toURL().readBytes() } }.getOrNull()
                        bytes?.let { data ->
                            runCatching { card.id.value to (Image.makeFromEncoded(data) to card.frameType) }.getOrNull()
                        }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private suspend fun latestSet(today: String): CardSetRelease? {
        val list = sets ?: withTimeoutOrNull(10_000) { api.fetchCardSets().getOrNull() }?.also { sets = it }
        return list?.let { CardSetReleases.latest(it, today) }
    }
}
