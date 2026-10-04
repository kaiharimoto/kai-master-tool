package com.kaiharimoto.mastertool.core.ai.wire

/**
 * A request that never reached its service, said in words a person can act on (1.0.61, kai's
 * phone: "Unable to resolve host "api.xiaomimimo.com": No address associated with hostname").
 * The address was right; the device could not look the name up — offline for a moment, or a
 * Private DNS, ad blocker or VPN refusing it. Each platform words that failure its own way.
 */
object Unreachable {
    private val lookup = listOf(
        "unable to resolve host", // Android
        "no address associated with hostname", // Android, Linux
        "unknownhostexception",
        "name or service not known", // Linux
        "nodename nor servname provided", // macOS
        "no such host is known", // Windows
        "temporary failure in name resolution", // Linux
    )
    private val offline = listOf("network is unreachable", "no route to host", "failed to connect", "connection refused", "timed out", "timeout")

    /** The host of [url], or the url itself. */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { url }

    /**
     * What went wrong with a request to [url], for a tool's answer: the app's own refusal — an
     * [IllegalStateException], what `error("…")` throws ("YGOPRODeck answered 503", a page whose
     * layout changed) — in its own words, anything else as a failure to reach the service ([say]).
     * Never the bare exception text: `UnknownHostException: ygoprodeck.com` tells a person nothing.
     */
    fun of(url: String, failure: Throwable): String = when {
        failure is IllegalStateException && !failure.message.isNullOrBlank() -> failure.message!!
        // The JVM's UnknownHostException often says only the host: its class says what happened.
        else -> say(url, failure.message ?: failure::class.simpleName, failure::class.simpleName)
    }

    /**
     * What went wrong reaching [url], from the error's own [message] (and its class name, where the
     * message is empty); [kind], the error's class name, is read for what happened but never shown.
     */
    fun say(url: String, message: String?, kind: String? = null): String {
        val raw = message?.trim().orEmpty()
        val low = raw.lowercase() + " " + kind.orEmpty().lowercase()
        val name = host(url)
        return when {
            lookup.any { it in low } ->
                "This device could not look up $name, so the request never left it. It may be offline for a moment, " +
                    "or a Private DNS, ad blocker or VPN may be blocking it. Check the connection and try again."
            offline.any { it in low } ->
                "Could not connect to $name: the network did not answer. Check the connection and try again."
            else -> "Could not reach $url: ${raw.ifEmpty { "no answer" }}"
        }
    }
}
