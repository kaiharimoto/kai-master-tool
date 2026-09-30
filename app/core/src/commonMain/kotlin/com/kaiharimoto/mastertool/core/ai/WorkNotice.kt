package com.kaiharimoto.mastertool.core.ai

/**
 * What Ai's notification says on a phone or a tablet (1.0.61): while it works out of sight, and
 * when it has answered. Words only; the APK's service shows them.
 */
object WorkNotice {
    /** The ongoing notification while it works: what it is doing, else that it is answering. */
    fun working(name: String, doing: String?): Pair<String, String> =
        "$name is working" to (doing?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: "Answering — you can switch apps; it carries on.")

    /** The notification when it has answered: the start of the answer, or what stopped it. */
    fun answered(name: String, firstLine: String?, problem: String?): Pair<String, String> = when {
        problem != null -> "$name stopped" to problem.trim().take(160)
        !firstLine.isNullOrBlank() -> "$name answered" to firstLine.trim().take(160)
        else -> "$name is done" to "Open the app to see what it did."
    }
}
