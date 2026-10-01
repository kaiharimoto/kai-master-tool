package com.kaiharimoto.neue.art

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.platform.Platform
import java.io.File

/**
 * Pictures the person adds to a card themselves (kai, 1.0.18: "some cards with
 * alt arts aren't showing that they have alt arts"). YGOPRODeck only knows an
 * alternate artwork that was printed under a passcode of its own — Nibiru's and
 * Lady Labrynth's reprints share theirs, so the pool has one picture of each and
 * nothing to fetch. So any card can be given more: an image file, copied into
 * `<data>/custom-art/<passcode>/`, becomes one more artwork after the ones the
 * pool knows. It is only ever a picture, like every artwork choice.
 *
 * An artwork choice is stored as an Int (`NeuePreferences.arts`): a passcode for
 * one the pool knows, and `-k` for this card's k-th own picture.
 */
class CustomArt(private val dir: File) {
    /** Bumped whenever a picture is added or removed, so what reads [files] redraws. */
    var version by mutableIntStateOf(0)
        private set

    private val cache = HashMap<Int, List<File>>()

    /** Pictures came in from another device (sync, 1.0.68): read the folders again. */
    fun reload() {
        cache.clear()
        version++
    }

    fun files(card: Int): List<File> {
        if (version < 0) return emptyList()
        return cache.getOrPut(card) {
            File(dir, card.toString()).listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
                ?.sortedBy { it.name }.orEmpty()
        }
    }

    /**
     * Asks for an image file and keeps a copy for [card]; the new picture's choice
     * (`-k`), or null if none was picked. A pick that could not be kept says so
     * through [onFailed] (touch swarm, rec 24) — cancelling the picker is not a failure.
     */
    suspend fun pickAndAdd(card: Int, onFailed: () -> Unit = {}): Int? {
        val picked = Platform.pick("Choose a picture for this card", EXTENSIONS) ?: return null
        return keep(card, picked) ?: run {
            onFailed()
            null
        }
    }

    /**
     * [picked] kept for [card] as one more picture of its own — a whole card, or
     * (1.0.34) the card's own render with the person's art cropped into its art
     * box (`ArtCropDialog`). Its choice (`-k`), or null when it is not a picture
     * or could not be written.
     */
    fun keep(card: Int, picked: PickedFile): Int? {
        // Android's providers often name a file without its extension: the bytes say what it is.
        val extension = picked.extension.takeIf { it in EXTENSIONS } ?: sniff(picked.bytes) ?: return null
        val into = File(dir, card.toString()).apply { mkdirs() }
        val target = File(into, "${System.currentTimeMillis()}.$extension")
        runCatching { target.writeBytes(picked.bytes) }.getOrElse { return null }
        cache.remove(card)
        version++
        return -files(card).indexOf(target).plus(1)
    }

    /** A picture's kind from its first bytes: PNG, JPEG or WebP, else null. */
    private fun sniff(bytes: ByteArray): String? {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
    }

    /** Deletes this card's k-th own picture. */
    fun remove(card: Int, k: Int) {
        files(card).getOrNull(k - 1)?.delete()
        cache.remove(card)
        version++
    }

    /**
     * Every artwork [card] can be drawn with, as choices: the pool's passcodes,
     * its own first, then this card's own pictures as `-1`, `-2` …
     */
    fun choices(card: Card): List<Int> = CardArt.arts(card).map { it.value } + (1..files(card.id.value).size).map { -it }

    /**
     * [card] as drawn with [choice]: the pool's artwork by `CardArt`, or one of its
     * own pictures under an id of its own, so the originals library and the name
     * masks keep it apart from the card's printed art.
     */
    fun drawn(card: Card, choice: Int?): Card {
        if (choice == null || choice >= 0) return CardArt.show(card, choice?.let(::CardId))
        val file = files(card.id.value).getOrNull(-choice - 1) ?: return card
        val uri = file.toURI().toString()
        return card.copy(id = CardId(-(card.id.value * 16 + (-choice).coerceAtMost(15))), imageUrl = uri, imageUrlSmall = uri)
    }

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

val LocalCustomArt = staticCompositionLocalOf<CustomArt?> { null }
