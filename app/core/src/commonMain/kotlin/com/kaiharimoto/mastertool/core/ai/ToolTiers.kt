package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.prefs.TierCeiling
import com.kaiharimoto.mastertool.core.prefs.TierPrefs
import com.kaiharimoto.mastertool.core.world.WorldPaths
import kotlinx.serialization.json.JsonObject

/**
 * What a tool does, by tier (Phase H, H.4; `docs/ROADMAP.md` F4): the person chooses what Ai may do by tier, never tool by
 * tool, and every tool is in exactly one.
 *
 * - [READ]: it reads the app, or talks with the person — always allowed.
 * - [WRITE]: it changes something of the person's — a deck, a web, a note, a setting, the table, a world.
 * - [DESTRUCTIVE]: it deletes something that cannot be undone (`ToolSpec.destructive`).
 * - [OUTWARD]: it reaches outside the app — the web, YGOPRODeck, Yugipedia, Gemini.
 *
 * A course study is the person's own start, under its own guards (`BrowseGuard`, the review on return): the tiers are the
 * person's conversations', and the study's steps keep their own.
 */
enum class ToolTier(val words: String) {
    READ("Reading the app"),
    WRITE("Changing your decks, notes, settings and the table"),
    DESTRUCTIVE("Deleting"),
    OUTWARD("Reaching outside the app: the web, YGOPRODeck, Yugipedia, Gemini"),
}

object ToolTiers {
    /**
     * Tools that reach outside the app. Course study tools are not here: a study is the person's own start. `past_format` reads
     * the app, but refreshes the banlists from Yugipedia when the ones kept are old (the red team's R10).
     */
    val OUTWARD: Set<String> = setOf(
        "web_search", "web_fetch", "rulings", "archetype_guide", "banlist", "past_format",
        "ygopro_tournament_decks", "ygopro_deck", "ygopro_field_snapshot", "ygopro_player", "import_ygopro_deck",
        "field_profile", "field_compare", "watch_video",
    )

    /** Tools that only talk with the person or think aloud: reading, whatever they are grouped under. */
    val CONVERSATION: Set<String> = setOf("ask_user", "express", "todo_write", "compact", "delegate", "navigate", "show_in_pool", "predict")

    /**
     * Outward tools that keep what they bring in — a deck saved to the library or a web (the red team, 2026-10): judged by
     * Changes as well as the outside, the stricter winning.
     */
    val OUTWARD_CHANGES: Set<String> = setOf("import_ygopro_deck")

    /**
     * Tools that always run the person's own Python (I.10): Gameplay Mapper's trainer is a program on this computer with its
     * network, as Python in Ai World is ([runsPython]) — judged by Changes and the outside both, the stricter winning.
     */
    val RUNS_PYTHON: Set<String> = setOf("train_start")

    /** The tier of [spec]: exactly one, whatever a call asks. */
    fun of(spec: ToolSpec): ToolTier = when {
        spec.destructive -> ToolTier.DESTRUCTIVE
        spec.name in OUTWARD -> ToolTier.OUTWARD
        spec.name in AiTools.readOnly || spec.name in CONVERSATION -> ToolTier.READ
        else -> ToolTier.WRITE
    }

    /**
     * Every tier a call of [spec] with [input] is judged by, its own ([of]) first (the red team, 2026-10): a tool that brings
     * something in from outside and keeps it is a change too ([OUTWARD_CHANGES]); and where the reach depends on what the
     * call asks — a Python run in Ai World is a program on this computer that can reach the network ([runsPython]), an app of
     * the World deleted cannot be undone ([deletes]) — the input's tiers join. [input] null is the tool as offered, before
     * any call: only what holds for every call.
     */
    fun tiersOf(spec: ToolSpec, input: JsonObject? = null): Set<ToolTier> = buildSet {
        add(of(spec))
        if (spec.name in OUTWARD_CHANGES) add(ToolTier.WRITE)
        if (runsPython(spec, input)) add(ToolTier.OUTWARD)
        if (deletes(spec, input)) add(ToolTier.DESTRUCTIVE)
    }

    /**
     * Whether this call runs Python: a tool that always does ([RUNS_PYTHON]), or a `world_run` of a `.py` path or a snippet in
     * `py`. JavaScript runs shut in inside the app (`JsRuntime`); Python is the desk's own process, with this computer's
     * network. A `py` named beside a path is judged as Python too — the stricter reading, never the looser.
     */
    fun runsPython(spec: ToolSpec, input: JsonObject?): Boolean {
        if (spec.name in RUNS_PYTHON) return true
        if (spec.name != "world_run" || input == null) return false
        val path = ToolArgs.string(input, "path")?.trim()?.takeIf { it.isNotEmpty() }
        return ToolArgs.string(input, "lang")?.trim()?.lowercase() == WorldPaths.LANG_PY ||
            (path != null && WorldPaths.lang(path) == WorldPaths.LANG_PY)
    }

    /** Whether this call deletes something that cannot be undone: a destructive tool, or an app of Ai World deleted. */
    fun deletes(spec: ToolSpec, input: JsonObject?): Boolean =
        spec.destructive || (spec.name == "world_app" && input != null && ToolArgs.string(input, "action")?.trim()?.lowercase() == "delete")

    /**
     * What [prefs] allow for [tier] on [connection]: [TierPrefs.ALLOW], [TierPrefs.ASK] or [TierPrefs.OFF]. Deleting is asked
     * unless the person said never to ask ([AiPrefs.alwaysAllow], which every build reads), or off. The connection's own
     * ceiling (`AiConnection.ceiling`) only ever makes it stricter. [connection] is the one the run started on — never the
     * one in use now, which Ai may have switched mid-run.
     */
    fun policy(tier: ToolTier, prefs: AiPrefs, connection: AiConnection? = prefs.connection): String {
        val chosen = chosen(tier, prefs)
        val ceiling = connection?.ceiling ?: return chosen
        return when (tier) {
            ToolTier.READ -> chosen
            ToolTier.WRITE -> TierCeiling.stricter(chosen, ceiling.write)
            ToolTier.OUTWARD -> TierCeiling.stricter(chosen, ceiling.outward)
            ToolTier.DESTRUCTIVE -> TierCeiling.stricter(chosen, ceiling.destructive)
        }
    }

    /** What the person chose for [tier] in Settings › Ai, before any connection's ceiling. */
    fun chosen(tier: ToolTier, prefs: AiPrefs): String = when (tier) {
        ToolTier.READ -> TierPrefs.ALLOW
        ToolTier.WRITE -> prefs.tiers.write
        ToolTier.OUTWARD -> prefs.tiers.outward
        ToolTier.DESTRUCTIVE -> when {
            prefs.tiers.destructive == TierPrefs.OFF -> TierPrefs.OFF
            prefs.alwaysAllow -> TierPrefs.ALLOW
            else -> TierPrefs.ASK
        }
    }

    /** Whether [connection]'s ceiling makes [tier] stricter than the person's choice: Settings says so beside it. */
    fun capped(tier: ToolTier, prefs: AiPrefs, connection: AiConnection? = prefs.connection): Boolean =
        policy(tier, prefs, connection) != chosen(tier, prefs)

    /** What [prefs] allow for [spec] as offered, before any call: every tier it is always judged by, the stricter winning. */
    fun policy(spec: ToolSpec, prefs: AiPrefs, connection: AiConnection? = prefs.connection): String = policyFor(spec, null, prefs, connection)

    /**
     * What [prefs] allow for this call of [spec] with [input] on [connection] (the red team, 2026-10): the strictest of every
     * tier it is judged by ([tiersOf]) — a Python run needs Changes and the outside, a deck imported from YGOPRODeck the
     * outside and Changes, an app of the World deleted Deleting. A call that reaches no further than its tool is judged as
     * ever.
     */
    fun policyFor(spec: ToolSpec, input: JsonObject?, prefs: AiPrefs, connection: AiConnection? = prefs.connection): String =
        tiersOf(spec, input).map { policy(it, prefs, connection) }.reduce { a, b -> TierCeiling.stricter(a, b) }

    /** The tiers of this call that ask the person first: what the confirm card says the call is about. */
    fun asking(spec: ToolSpec, input: JsonObject?, prefs: AiPrefs, connection: AiConnection? = prefs.connection): Set<ToolTier> =
        tiersOf(spec, input).filter { policy(it, prefs, connection) == TierPrefs.ASK }.toSet()

    /** The tools of [specs] the person has not turned off, on [connection]: what a conversation is offered. */
    fun offered(specs: List<ToolSpec>, prefs: AiPrefs, connection: AiConnection? = prefs.connection): List<ToolSpec> =
        specs.filter { policy(it, prefs, connection) != TierPrefs.OFF }

    /**
     * Whether a turn on [connection] may reach the web on the provider's side — Anthropic's server tools, a CLI's own
     * WebSearch/WebFetch, Codex's live search (`TurnRequest.serverWeb`): those calls never pass the app, so only when the
     * outside is allowed without asking, after the connection's own limit. Under Ask the app's own `web_search` and
     * `web_fetch` go instead, which ask; under Off neither is offered.
     */
    fun serverWeb(prefs: AiPrefs, connection: AiConnection? = prefs.connection): Boolean =
        policy(ToolTier.OUTWARD, prefs, connection) == TierPrefs.ALLOW

    /**
     * Why a run on [from] may not switch to [to] (`ai.active`), or null when it may: never to a connection that may do more
     * — a model held to reading must not hand the next answer to one that is not.
     */
    fun switchRefusal(from: AiConnection?, to: AiConnection): String? {
        val now = from?.ceiling ?: return null
        return if (to.ceiling.within(now)) null
        else "${to.label.ifBlank { to.provider }} may do more than this connection in Settings › Ai, so only the person switches to it."
    }

    /**
     * What Ai is told when a tool's tier is off: by the person's choice, or by the ceiling of the connection it runs on. With
     * [input], the tier of this call that is off, and why the call needs it when its tool alone would not.
     */
    fun offWords(spec: ToolSpec, prefs: AiPrefs? = null, connection: AiConnection? = prefs?.connection, input: JsonObject? = null): String {
        val tier = prefs?.let { p -> tiersOf(spec, input).firstOrNull { policy(it, p, connection) == TierPrefs.OFF } } ?: of(spec)
        val ceiling = prefs != null && chosen(tier, prefs) != TierPrefs.OFF && policy(tier, prefs, connection) == TierPrefs.OFF
        val why = when {
            tier == of(spec) -> ""
            tier == ToolTier.OUTWARD && spec.name in RUNS_PYTHON -> ": the trainer runs as a program on this computer, with the person's own Python, which can reach the network"
            tier == ToolTier.OUTWARD && runsPython(spec, input) -> ": Python runs as a program on this computer and can reach the network (JavaScript runs inside the app)"
            tier == ToolTier.WRITE -> ": it keeps what it brings in, which is a change"
            tier == ToolTier.DESTRUCTIVE -> ": deleting cannot be undone"
            else -> ""
        }
        return "${tier.words} is turned off ${if (ceiling) "for this connection" else "for Ai"} in Settings › Ai, so ${spec.name} does not run$why. $OFF_TAIL"
    }

    private const val OFF_TAIL = "Only the person turns it back on: don't try again unless they do."

    /** What Ai is told when the person answered No to a tool they asked to be asked about. */
    const val DENIED = "The person said no. Don't do it unless they ask again."

    /** Whether a tool's answer is the person's refusal (a tier off, or No when asked): what a run's trace counts as refused. */
    fun refused(content: String): Boolean = content == DENIED || content.endsWith(OFF_TAIL)
}
