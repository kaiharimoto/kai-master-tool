package com.kaiharimoto.mastertool.core.sync

/**
 * This app's registration with Google (1.0.68): a free developer registration, owned by kai, a Desktop
 * client (its loopback redirect `http://localhost:53682/` needs no registering), put in at build time from
 * the release workflows' secrets (`CloudKeys`, written by `:core`'s `generateCloudKeys`) and never kept in
 * the repository. A client id is not a secret —
 * it names the app to the service, and every installed copy carries it; Google's desktop "secret" is
 * the same (Google: "the client secret is obviously not treated as a secret"). While the id is blank,
 * Google Drive is not offered. `docs/SYNC.md` has how each is registered.
 */
object CloudClients {
    private const val GOOGLE_ID = CloudKeys.GOOGLE_ID
    private const val GOOGLE_SECRET = CloudKeys.GOOGLE_SECRET

    fun id(cloud: Cloud): String = when (cloud) {
        Cloud.GOOGLE_DRIVE -> GOOGLE_ID
    }

    fun secret(cloud: Cloud): String = if (cloud == Cloud.GOOGLE_DRIVE) GOOGLE_SECRET else ""
}
