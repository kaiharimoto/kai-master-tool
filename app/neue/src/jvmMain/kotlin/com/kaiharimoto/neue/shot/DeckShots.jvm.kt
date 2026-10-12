package com.kaiharimoto.neue.shot

import com.kaiharimoto.neue.cards.read

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.kaiharimoto.mastertool.core.model.Card
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
    private val sets = ShotSets()

    actual fun export(state: DeckBuilderState, neue: NeueState) {
        if (busy) return
        val model = snapshot(state, neue)
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ask(shotFileName(model)) } ?: return@launch
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
        val latest = withContext(Dispatchers.IO) { sets.latest(model.date) }
        return DeckShot.render(model.copy(latestSet = latest), images, masks) to (cards.size - images.size)
    }

    /** The desk draws the picture in a scene of its own ([DeckShot.render]): nothing in the window. */
    @Composable
    actual fun Host() {}

    /** What the picture shows, as the builder stands (the studio photographs it too). */
    fun snapshot(state: DeckBuilderState, neue: NeueState): ShotModel = shotModel(state, neue)

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
}
