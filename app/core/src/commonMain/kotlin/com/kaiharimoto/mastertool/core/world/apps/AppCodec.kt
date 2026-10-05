package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.DeskSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Where an app's files are under its world's folder (§8.2): `apps/<slug>/` beside `files/`, synced and backed up with
 * the world (`.tmp` never synced).
 */
object AppPaths {
    const val ROOT = "apps"
    const val MANIFEST = "app.json"
    const val CODE = "main.js"
    const val STATE = "state.json"
    const val BROKEN = "state.broken.json"
    const val PREVIOUS = "state.prev.json"
    const val VERSIONS = "versions"

    fun dir(slug: String) = "$ROOT/$slug"
    fun manifest(slug: String) = "${dir(slug)}/$MANIFEST"
    fun code(slug: String) = "${dir(slug)}/$CODE"
    fun state(slug: String) = "${dir(slug)}/$STATE"
    fun version(slug: String, n: Int) = "${dir(slug)}/$VERSIONS/$n.js"
}

/** What a stored state came to: none yet (run `init`), a state, or one that will not read (set aside, start fresh). */
sealed interface StateRead {
    data object Fresh : StateRead
    data class Ok(val json: String) : StateRead
    data class Broken(val why: String) : StateRead
}

/**
 * An app's manifest and state to and from disk (§8.2, `AppCodecTest`), read the way [WorldCodec] reads a world: a newer
 * build's keys skipped, a broken manifest salvaged to its slug and name, an unreadable state set aside — an app is the
 * person's to keep, and so is what they typed into it.
 */
object AppCodec {
    private val pretty = Json(WorldCodec.json) { prettyPrint = true }

    fun encode(m: AppManifest): String = pretty.encodeToString(AppManifest.serializer(), m)

    /**
     * The manifest in [text], found in the folder [folder]: the folder's name is the slug whatever the file says (a
     * manifest copied between folders must not take another app's place). Null only when [folder] is not a slug.
     */
    fun decode(text: String?, folder: String): AppManifest? {
        if (!AppRef.SLUG.matches(folder)) return null
        val read = text?.takeIf { it.isNotBlank() }?.let {
            try {
                WorldCodec.json.decodeFromString(AppManifest.serializer(), it)
            } catch (e: Exception) {
                salvage(it, folder)
            }
        } ?: AppManifest(slug = folder)
        return sane(read.copy(slug = folder))
    }

    private fun salvage(text: String, folder: String): AppManifest {
        val root = try {
            WorldCodec.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        fun s(k: String) = (root[k] as? JsonPrimitive)?.contentOrNull
        return AppManifest(slug = folder, name = s("name") ?: folder, kind = s("kind") ?: AppKind.VIEWER.id, description = s("description").orEmpty())
    }

    /** [m] held to its limits: a clean one-line name and description, a size in bounds, a version of at least 1. */
    fun sane(m: AppManifest): AppManifest = m.copy(
        name = cleanName(m.name).ifEmpty { m.slug },
        description = oneLine(m.description).take(AppLimits.DESCRIPTION),
        size = DeskSize(
            m.size.w.takeIf { it.isFinite() }?.coerceIn(1.0, 4_000.0) ?: 480.0,
            m.size.h.takeIf { it.isFinite() }?.coerceIn(1.0, 4_000.0) ?: 360.0,
        ),
        version = m.version.coerceAtLeast(1),
        kind = m.kind.trim().lowercase().take(32).ifEmpty { AppKind.VIEWER.id },
    )

    /** A name as a title bar may show it (§8.6 point 4): no control characters, one line, at most [AppLimits.NAME]. */
    fun cleanName(name: String): String = oneLine(name).take(AppLimits.NAME).trim()

    private fun oneLine(s: String): String =
        s.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().filter { !it.isISOControl() && it.code !in 0x200B..0x200F && it.code !in 0x202A..0x202E && it.code != 0x2066 && it.code != 0x2067 && it.code != 0x2068 && it.code != 0x2069 }.trim()

    /** A slug made from [name]: lowercase letters and digits, words joined by `-`, at most 32. */
    fun slugOf(name: String): String =
        name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-").take(32).trimEnd('-').ifEmpty { "app" }

    fun validSlug(s: String): Boolean = AppRef.SLUG.matches(s)

    /** Why [code] cannot be an app's, or null when it can. */
    fun codeProblem(code: String): String? = when {
        code.isBlank() -> "an app's code is empty"
        code.length > AppLimits.CODE -> "an app's code holds at most ${AppLimits.CODE / 1024} KB (this is ${code.length / 1024} KB)"
        else -> null
    }

    /** A stored state, read: [StateRead.Broken] for one too large or not a JSON value, so it is set aside, never lost. */
    fun readState(text: String?): StateRead {
        if (text.isNullOrBlank()) return StateRead.Fresh
        if (text.length > AppLimits.STATE) return StateRead.Broken("the state is over ${AppLimits.STATE / 1024} KB")
        return try {
            WorldCodec.json.parseToJsonElement(text)
            StateRead.Ok(text)
        } catch (e: Exception) {
            StateRead.Broken("the state is not JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(120)}")
        }
    }

    /**
     * An app's state as Ai reads it back (`world_app state`, §8.6 point 7): in the envelope outside text gets — it holds
     * the person's typing, card text and whatever another device wrote, so it is data, never instructions.
     */
    fun forAi(m: AppManifest, state: String): String = Untrusted.wrap("app ${m.slug} v${m.version} state.json", state)

    /** Why a state the app returned cannot be kept, or null when it can. */
    fun stateProblem(json: String): String? =
        if (json.length > AppLimits.STATE) "the state would be ${json.length / 1024} KB; an app keeps at most ${AppLimits.STATE / 1024} KB — read knowledge through ygo.knowledge rather than keeping it" else null
}
