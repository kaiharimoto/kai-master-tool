package com.kaiharimoto.neue.ai.course

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.neue.platform.decodePicture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * What a study is reading beside what it is writing, live (kai, 2026-10: "the user sees what the Ai is writing live in
 * comparison to what it's reading … so the user doesn't feel left out and can learn with the AI"). Fed by every tool the
 * study calls — a page, a section, a replay, a card's text read; notes, playbook entries and guide entries written, and
 * what was refused — by the video it plays (each picture as it is taken, the transcript once heard), and by its
 * thinking as the model streams it. Kept for the run on screen; nothing here is stored.
 */
class StudyMonitor {
    data class Reading(val title: String, val text: String, val ref: String, val at: Long)

    data class Written(
        /** notes, playbook, guide, refused. */
        val kind: String,
        val ref: String,
        val title: String,
        val text: String,
        val at: Long,
    )

    var reading by mutableStateOf<Reading?>(null)
        private set
    var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    var pictureCaption by mutableStateOf("")
        private set
    var written by mutableStateOf<List<Written>>(emptyList())
        private set

    /** The model's words and thinking as they stream, the latest part. */
    var thinking by mutableStateOf("")
        private set

    /** The monitor is open over the app. */
    var open by mutableStateOf(false)

    fun reading(title: String, text: String, ref: String) {
        reading = Reading(title, text.take(READ_CAP), ref, System.currentTimeMillis())
    }

    fun picture(jpeg: ByteArray, caption: String) {
        decodePicture(jpeg)?.let { picture = it; pictureCaption = caption }
    }

    fun thought(delta: String) {
        thinking = (thinking + delta).takeLast(THINK_CAP)
    }

    /** A new step: its thinking starts afresh, what it read and wrote stays on screen. */
    fun step() {
        thinking = ""
    }

    fun clear() {
        reading = null
        picture = null
        pictureCaption = ""
        written = emptyList()
        thinking = ""
    }

    /** Something written that is not a tool's: an exam's answer beside the author's play. */
    fun note(kind: String, ref: String, title: String, text: String) {
        wrote(Written(kind, ref, title, text, System.currentTimeMillis()))
    }

    /** Kept short as it is written: the pane draws [SHOWN] characters, and notes can be 300,000 (1.1.47). */
    @Synchronized
    private fun wrote(w: Written) {
        written = (written + w.copy(text = w.text.take(SHOWN))).takeLast(WRITTEN_CAP)
    }

    /** What a tool call shows: a read in the reading pane, a write (or its refusal) in the writing pane. */
    fun saw(call: Part.ToolUse, result: Part.ToolResult) {
        val name = call.name.removePrefix("mcp__neue__")
        val i = call.input
        val now = System.currentTimeMillis()
        when (name) {
            in READS -> if (!result.isError) {
                reading(result.summary.ifBlank { name }, unwrap(result.content), ref(name, i))
                if (name != "course_frames" && name != "course_pictures") picture = null
                result.pictures.firstOrNull()?.data?.takeIf { it.isNotBlank() }?.let { data ->
                    runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { picture(it, result.summary) }
                }
            }
            "course_notes", "replay_notes" -> wrote(
                if (result.isError) Written("refused", ref(name, i), "Notes not kept", result.content, now)
                else Written("notes", ref(name, i), if (ToolArgs.bool(i, "append") == true) "Notes, continued" else "Notes", ToolArgs.string(i, "notes").orEmpty(), now),
            )
            "playbook_write" -> {
                val op = ToolArgs.string(i, "op").orEmpty()
                if (result.isError) {
                    wrote(Written("refused", "playbook", "Not kept", result.content, now))
                } else {
                    val entries = (i["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOfNotNull(i["entry"] as? JsonObject)
                    if (entries.isEmpty()) wrote(Written("playbook", "playbook", "Playbook: $op", result.content, now))
                    entries.forEach { e -> wrote(Written("playbook", ToolArgs.string(e, "kind").orEmpty().ifBlank { op }, ToolArgs.string(e, "title") ?: ToolArgs.string(i, "id").orEmpty(), entry(e), now)) }
                }
            }
            "memory" -> if (ToolArgs.string(i, "scope") == "guide") wrote(
                if (result.isError) Written("refused", "guide", "Not written to the guide", result.content, now)
                else Written("guide", "guide", "Guide: ${ToolArgs.string(i, "action").orEmpty()}", ToolArgs.string(i, "text").orEmpty().ifBlank { result.summary }, now),
            )
        }
    }

    /** A playbook entry as written, in words. */
    private fun entry(e: JsonObject): String = buildString {
        listOf("against" to "Against", "situation" to "When", "choice" to "Do", "end_board" to "Ends on", "why" to "Why").forEach { (k, label) ->
            ToolArgs.string(e, k)?.takeIf { it.isNotBlank() }?.let { appendLine("$label: $it") }
        }
        ToolArgs.strings(e, "needs").takeIf { it.isNotEmpty() }?.let { appendLine("Needs: " + it.joinToString()) }
        (e["steps"] as? JsonArray)?.forEachIndexed { k, st ->
            val o = st as? JsonObject
            if (o != null) appendLine("${k + 1}. ${ToolArgs.string(o, "card").orEmpty()}: ${ToolArgs.string(o, "action").orEmpty()}" + (ToolArgs.string(o, "result")?.takeIf { it.isNotBlank() }?.let { " → $it" } ?: ""))
            else appendLine("${k + 1}. ${st.toString().trim('"')}")
        }
        ToolArgs.strings(e, "through").takeIf { it.isNotEmpty() }?.let { appendLine("Plays through: " + it.joinToString("; ")) }
        ToolArgs.strings(e, "weak_to").takeIf { it.isNotEmpty() }?.let { appendLine("Weak to: " + it.joinToString("; ")) }
        ToolArgs.string(e, "body")?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    }.trim()

    private fun ref(name: String, i: JsonObject): String = when {
        ToolArgs.int(i, "chapter") != null -> "ch. ${ToolArgs.int(i, "chapter")}" + if (name.endsWith("notes")) " notes" else ""
        ToolArgs.int(i, "replay") != null -> "replay ${ToolArgs.int(i, "replay")}" + if (name.endsWith("notes")) " notes" else ""
        ToolArgs.string(i, "ref") != null -> ToolArgs.string(i, "ref")!!
        else -> name.replace('_', ' ')
    }

    /** A result without its outside-text envelope: the words themselves. */
    private fun unwrap(text: String): String = text.replace(Regex("""^<[a-z_]+ source="[^"]*">\n?"""), "").replace(Regex("""\n?</[a-z_]+>\s*$"""), "")

    companion object {
        val READS = setOf(
            "course_read", "replay_read", "course_open", "course_search", "course_cards", "course_frames", "course_pictures", "browser_read",
            "playbook_read", "playbook_search", "memory_read", "card_info", "rulings", "course_replays",
        )
        const val READ_CAP = 60_000
        const val THINK_CAP = 4_000
        const val WRITTEN_CAP = 300
        const val SHOWN = 6_000
    }
}
