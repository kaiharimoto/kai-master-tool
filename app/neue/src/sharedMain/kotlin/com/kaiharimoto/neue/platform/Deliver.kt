package com.kaiharimoto.neue.platform

/**
 * [bytes] handed to the person as a file called [name] (the siding guide's PDF,
 * 1.0.36): on the desk saved where they choose and opened, on a phone or a
 * tablet sent through the system's share sheet, where Files, Drive and a
 * printer all are. What to tell them, or null when they cancelled.
 */
expect suspend fun deliverFile(name: String, mime: String, bytes: ByteArray): String?
