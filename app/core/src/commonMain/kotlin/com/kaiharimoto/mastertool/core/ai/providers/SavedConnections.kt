package com.kaiharimoto.mastertool.core.ai.providers

import com.kaiharimoto.mastertool.core.prefs.AiConnection

/**
 * What setup reuses of the connections already made (1.0.59, kai: "if I press setup accidentally
 * it makes me go through everything again and reinput the API key"): a saved OpenAI-compatible
 * connection is a preset of the person's own beside the built-in ones, a key already given for a
 * service is offered again instead of asked for, and setting a connection up again replaces it
 * rather than adding a twin.
 */
object SavedConnections {
    /** An address as a service is known by it: no trailing slash, the scheme and host in lower case. */
    fun address(url: String?): String {
        val t = url?.trim()?.trimEnd('/') ?: return ""
        val cut = t.indexOf("://").let { if (it < 0) 0 else t.indexOf('/', it + 3).let { end -> if (end < 0) t.length else end } }
        return t.substring(0, cut).lowercase() + t.substring(cut)
    }

    /** Whether [c] reaches the same service as [provider] at [baseUrl] (the address counts only where the person types it). */
    fun sameService(c: AiConnection, provider: String, baseUrl: String?): Boolean {
        if (c.provider != provider) return false
        val typed = Providers.byId(provider)?.let(Providers::typedAddress) ?: (baseUrl != null)
        return !typed || address(c.baseUrl) == address(baseUrl)
    }

    /** The person's own OpenAI-compatible services, once each, newest first: presets beside the built-in ones. */
    fun presets(connections: List<AiConnection>): List<AiConnection> =
        connections.filter { it.provider == Providers.compatible.id && !it.baseUrl.isNullOrBlank() }
            .reversed()
            .distinctBy { address(it.baseUrl) to it.label.trim().lowercase() }

    /** The saved connection whose key serves [provider] at [baseUrl], the newest first; null when none has one. */
    fun keyFrom(connections: List<AiConnection>, provider: String, baseUrl: String?, hasKey: (AiConnection) -> Boolean): AiConnection? =
        connections.reversed().firstOrNull { sameService(it, provider, baseUrl) && hasKey(it) }

    /** The saved connection a new one replaces: the same service, name and model, set up again. */
    fun replaced(connections: List<AiConnection>, new: AiConnection): AiConnection? = connections.firstOrNull {
        sameService(it, new.provider, new.baseUrl) && it.label.trim().equals(new.label.trim(), ignoreCase = true) && it.model == new.model
    }
}
