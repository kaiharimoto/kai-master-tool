package com.kaiharimoto.mastertool.core.sync

/**
 * This app's registrations with the sign-in clouds (1.0.68): one free developer registration per
 * service, owned by kai, each with the redirect `http://localhost:53682/`. A client id is not a secret —
 * it names the app to the service, and every installed copy carries it; Google's desktop "secret" is
 * the same (Google: "the client secret is obviously not treated as a secret"). A service whose id is
 * blank is not offered. `docs/SYNC.md` has how each is registered.
 */
object CloudClients {
    private const val GOOGLE_ID = ""
    private const val GOOGLE_SECRET = ""
    private const val DROPBOX_ID = ""
    private const val ONEDRIVE_ID = ""

    fun id(cloud: Cloud): String = when (cloud) {
        Cloud.GOOGLE_DRIVE -> GOOGLE_ID
        Cloud.DROPBOX -> DROPBOX_ID
        Cloud.ONEDRIVE -> ONEDRIVE_ID
    }

    fun secret(cloud: Cloud): String = if (cloud == Cloud.GOOGLE_DRIVE) GOOGLE_SECRET else ""
}
