package com.kaiharimoto.neue.shot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.cards.NameMask
import com.kaiharimoto.neue.cards.NameMasks
import com.kaiharimoto.neue.cards.NameStyles
import com.kaiharimoto.neue.platform.deliverFile
import com.kaiharimoto.neue.theme.MuTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The deck picture on the tablet and the phone (I.2): the desk's drawing ([ShotDesigns.Picture]) from the same model
 * ([shotModel]), recorded out of sight in the window ([Host]) — Android has no scene off a window — then handed to the share
 * sheet as a PNG, since the tablet has no file dialog to save it with.
 */
actual class DeckShots actual constructor(private val art: ArtLibrary, private val scope: CoroutineScope) {
    actual var taking by mutableStateOf(false)
        private set

    /** The picture asked for and its art, waiting for [Host] to draw it; [done] takes the PNG. */
    private class Job(val model: ShotModel, val images: Map<Int, ImageBitmap>, val masks: Map<Int, NameMask>) {
        val done = CompletableDeferred<ByteArray>()
    }

    private var job by mutableStateOf<Job?>(null)
    private val sets = ShotSets()

    actual fun export(state: DeckBuilderState, neue: NeueState) {
        if (taking) return
        val model = snapshot(state, neue)
        taking = true
        scope.launch {
            try {
                neue.note = Note("Taking the picture")
                val (png, missing) = picture(model)
                val problem = deliverFile(shotFileName(model), "image/png", png)
                neue.note = Note(problem ?: if (missing == 0) "The picture is ready" else "The picture is ready · $missing without a picture")
            } catch (e: Exception) {
                neue.note = Note("The picture could not be made: ${e.message ?: e::class.simpleName}")
            } finally {
                taking = false
            }
        }
    }

    /** What the picture shows, as the builder stands. */
    fun snapshot(state: DeckBuilderState, neue: NeueState): ShotModel = shotModel(state, neue)

    /**
     * The PNG, and how many cards had no picture to put in it: the art gathered (each original waited for [artMs] at most,
     * else the small render), then drawn by [Host] in the window. The emulator walk asks it without the share sheet.
     */
    suspend fun picture(model: ShotModel, artMs: Long = 20_000): Pair<ByteArray, Int> {
        val cards = model.sections.flatMap { it.cards }.filterNotNull().distinctBy { it.id }
        val loaded = load(cards, artMs)
        val images = loaded.mapValues { it.value.first.asImageBitmap() }
        val masks = if (model.names == NameStyles.PRINTED) emptyMap() else withContext(Dispatchers.Default) {
            loaded.mapNotNull { (id, pair) -> runCatching { mask(pair.first, pair.second) }.getOrNull()?.let { id to it } }.toMap()
        }
        val latest = withContext(Dispatchers.IO) { sets.latest(model.date) }
        val drawing = Job(model.copy(latestSet = latest), images, masks)
        job = drawing
        try {
            return withTimeout(DRAW_MS) { drawing.done.await() } to (cards.size - images.size)
        } finally {
            if (job === drawing) job = null
        }
    }

    /**
     * The picture drawn out of sight at its own size (as `GiftInk`'s pictures are): laid out at no size in the window, its
     * drawing recorded into a layer and never onto the screen; once recorded, the layer read back and written as a PNG.
     */
    @Composable
    actual fun Host() {
        val j = job ?: return
        val plan = remember(j) { ShotDesigns.plan(j.model) }
        // Past the largest picture a GPU draws in one piece (4,096 a side on many tablets), a long decklist is drawn finer.
        val density = minOf(ShotDesigns.density(j.model.style), MOST_PX / max(plan.width, plan.height))
        val w = (plan.width * density).roundToInt()
        val h = (plan.height * density).roundToInt()
        val layer = rememberGraphicsLayer()
        var drawn by remember(j) { mutableStateOf(false) }
        Box(
            Modifier.layout { m, _ ->
                val p = m.measure(Constraints.fixed(w, h))
                layout(0, 0) { p.place(0, 0) }
            },
        ) {
            Box(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; if (!drawn) drawn = true }) {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MuTheme(ink = j.model.ink) { ShotDesigns.Picture(plan, j.model, j.images, j.masks) }
                }
            }
        }
        LaunchedEffect(j, drawn) {
            if (!drawn) return@LaunchedEffect
            try {
                val shot = layer.toImageBitmap().asAndroidBitmap()
                j.done.complete(withContext(Dispatchers.Default) { png(shot) })
            } catch (e: CancellationException) {
                // The window let this drawing go (it left the composition): the job stays asked for, and the next Host draws
                // it. Completing it with the cancellation failed the picture (the emulator walk, 2026-10-12).
                throw e
            } catch (e: Exception) {
                j.done.completeExceptionally(e)
            }
        }
    }

    /** Every picture, the original where it can be had and the small render where it cannot, four at a time. */
    private suspend fun load(cards: List<Card>, artMs: Long): Map<Int, Pair<Bitmap, String>> {
        val gate = Semaphore(4)
        return coroutineScope {
            cards.map { card ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val bytes = withTimeoutOrNull(artMs) { art.ensure(card)?.readBytes() }
                            ?: runCatching { card.imageUrlSmall?.let { URL(it).readBytes() } }.getOrNull()
                        bytes?.let(::decode)?.let { card.id.value to (it to card.frameType) }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    companion object {
        /** A card in the picture is about 300 pixels wide: an original is read at no less, and no more than twice it. */
        private const val CARD_PX = 320

        /** The longest side a picture is drawn at here. */
        private const val MOST_PX = 4_096f

        /** Long enough for the slowest tablet to draw and read back the picture; past it, it says so. */
        private const val DRAW_MS = 60_000L

        private fun decode(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= CARD_PX) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /** The name's letters found in a card's pixels, as the desk finds them in its own. */
        private fun mask(bitmap: Bitmap, frameType: String): NameMask? =
            NameMasks.compute(bitmap.width, bitmap.height, frameType) { x, y, w, h -> IntArray(w * h).also { bitmap.getPixels(it, 0, w, x, y, w, h) } }

        /** The picture as a PNG; a picture held by the GPU is copied out first, since only memory is written. */
        private fun png(bitmap: Bitmap): ByteArray {
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            return ByteArrayOutputStream().use { out ->
                check(readable.compress(Bitmap.CompressFormat.PNG, 100, out)) { "The picture could not be encoded" }
                out.toByteArray()
            }
        }
    }
}
