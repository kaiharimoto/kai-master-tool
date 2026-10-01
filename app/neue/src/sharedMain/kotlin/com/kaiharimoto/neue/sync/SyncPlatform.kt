package com.kaiharimoto.neue.sync

import com.kaiharimoto.mastertool.core.sync.SyncStore

/** What sync needs of each platform (1.0.68): a folder as a store, the system's folder chooser, the device's name. */
expect object SyncPlatform {
    /** A folder as a store: a path on the computer; on Android a path or a folder the picker granted (`content://`). */
    fun folderStore(folder: String): SyncStore

    /** The system's own folder chooser; null when it was closed. */
    suspend fun pickFolder(): String?

    /** A chosen folder as the person reads it. */
    fun folderLabel(folder: String): String

    /** What the system calls this device, for the others' copies of a conflict. */
    val deviceName: String

    /** A random string from the platform's secure source: a sign-in's verifier and state. */
    fun random(length: Int): String
}
