package com.kaiharimoto.mastertool.core.ai.course

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A chapter's page as the study read it, kept on this computer (1.1.51, kai: "have the contents of the guide saved locally
 * so we don't have to keep referring back to it via browser use"). Its words were kept from the start (`pages/<n>.md`);
 * now so are the rest of what the page held — its links (the replays it points to), its pictures (a combo drawn out, a
 * board, a decklist: what the words only point at) and whether it carried a video — so nothing the study does later
 * opens the page again. Only a video still to be watched does.
 *
 * Kept as `pages/<n>.page.json` beside the words, the pictures as files under `pictures/<n>/`. The pictures are the
 * bytes the page itself received ([PageImage.src] read from the browser's own cache), never fetched again by the app.
 */
@Serializable
data class PageSnapshot(
    val url: String,
    val title: String = "",
    /** When it was kept. */
    val at: Long = 0,
    val links: List<SavedLink> = emptyList(),
    val pictures: List<SavedPicture> = emptyList(),
    /** The page carried a video player. */
    val video: Boolean = false,
)

@Serializable
data class SavedLink(val text: String, val href: String)

/**
 * A picture of the page, kept: [n] is its number in the chapter's words ("[Picture 3: …]"), [file] its name under the
 * chapter's pictures folder, [alt] what the page said of it, [near] the words just before it, [w] × [h] its size.
 */
@Serializable
data class SavedPicture(
    val n: Int,
    val file: String,
    val alt: String = "",
    val src: String = "",
    val near: String = "",
    val w: Int = 0,
    val h: Int = 0,
)

/** A picture the page shows, as the browser lists it before it is kept. */
@Serializable
data class PageImage(
    /** Its number, from 1, in the page's order: the marker its words carry. */
    val n: Int,
    val src: String,
    val alt: String = "",
    val near: String = "",
    val w: Int = 0,
    val h: Int = 0,
)

object PageSnapshots {
    /** The smallest picture worth keeping, either side: smaller is an icon, an avatar, a button. */
    const val MIN_SIDE = 120

    /** The most pictures kept from one page. */
    const val MAX_PICTURES = 60

    /** The largest picture file kept, in bytes. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * The pictures of [images] worth keeping: big enough to hold something, each once (by where it came from), at most
     * [MAX_PICTURES], in the page's order. Avatars and logos are small; a drawn-out combo or a board is not.
     */
    fun worth(images: List<PageImage>): List<PageImage> =
        images.filter { it.src.isNotBlank() && !it.src.startsWith("data:") && it.w >= MIN_SIDE && it.h >= MIN_SIDE }
            .distinctBy { it.src.substringBefore('#') }
            .take(MAX_PICTURES)

    /** The file extension for a picture's [bytes], read from its first bytes; null when it is not a picture kept. */
    fun extension(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "gif"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "webp"
        else -> null
    }

    /** The media type for a picture file named [file]. */
    fun mediaType(file: String): String = when (file.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** How a picture is marked in the chapter's words: "[Picture 3: the end board]". */
    fun marker(n: Int, alt: String): String = "[Picture $n" + alt.trim().takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty() + "]"

    /** The numbers of the pictures [text] marks. */
    fun marked(text: String): Set<Int> = MARK.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    private val MARK = Regex("""\[Picture (\d{1,3})\b""")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    fun read(text: String?): PageSnapshot? = if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(PageSnapshot.serializer(), text) }.getOrNull()

    fun write(s: PageSnapshot): String = json.encodeToString(PageSnapshot.serializer(), s)
}
