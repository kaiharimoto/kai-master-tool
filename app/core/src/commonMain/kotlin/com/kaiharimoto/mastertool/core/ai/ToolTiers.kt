package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.mastertool.core.prefs.TierPrefs

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
    /** Tools that reach outside the app. Course study tools are not here: a study is the person's own start. */
    val OUTWARD: Set<String> = setOf(
        "web_search", "web_fetch", "rulings", "archetype_guide", "banlist",
        "ygopro_tournament_decks", "ygopro_deck", "ygopro_field_snapshot", "ygopro_player", "import_ygopro_deck",
        "field_profile", "field_compare", "watch_video",
    )

    /** Tools that only talk with the person or think aloud: reading, whatever they are grouped under. */
    val CONVERSATION: Set<String> = setOf("ask_user", "express", "todo_write", "compact", "delegate", "navigate", "show_in_pool")

    /** The tier of [spec]. */
    fun of(spec: ToolSpec): ToolTier = when {
        spec.destructive -> ToolTier.DESTRUCTIVE
        spec.name in OUTWARD -> ToolTier.OUTWARD
        spec.name in AiTools.readOnly || spec.name in CONVERSATION -> ToolTier.READ
        else -> ToolTier.WRITE
    }

    /**
     * What [prefs] allow for [tier]: [TierPrefs.ALLOW], [TierPrefs.ASK] or [TierPrefs.OFF]. Deleting is asked unless the
     * person said never to ask ([AiPrefs.alwaysAllow], which every build reads), or off.
     */
    fun policy(tier: ToolTier, prefs: AiPrefs): String = when (tier) {
        ToolTier.READ -> TierPrefs.ALLOW
        ToolTier.WRITE -> prefs.tiers.write
        ToolTier.OUTWARD -> prefs.tiers.outward
        ToolTier.DESTRUCTIVE -> when {
            prefs.tiers.destructive == TierPrefs.OFF -> TierPrefs.OFF
            prefs.alwaysAllow -> TierPrefs.ALLOW
            else -> TierPrefs.ASK
        }
    }

    fun policy(spec: ToolSpec, prefs: AiPrefs): String = policy(of(spec), prefs)

    /** The tools of [specs] the person has not turned off: what a conversation is offered. */
    fun offered(specs: List<ToolSpec>, prefs: AiPrefs): List<ToolSpec> = specs.filter { policy(it, prefs) != TierPrefs.OFF }

    /** What Ai is told when a tool's tier is off. */
    fun offWords(spec: ToolSpec): String =
        "${of(spec).words} is turned off for Ai in Settings › Ai, so ${spec.name} does not run. $OFF_TAIL"

    private const val OFF_TAIL = "Only the person turns it back on: don't try again unless they do."

    /** What Ai is told when the person answered No to a tool they asked to be asked about. */
    const val DENIED = "The person said no. Don't do it unless they ask again."

    /** Whether a tool's answer is the person's refusal (a tier off, or No when asked): what a run's trace counts as refused. */
    fun refused(content: String): Boolean = content == DENIED || content.endsWith(OFF_TAIL)
}
