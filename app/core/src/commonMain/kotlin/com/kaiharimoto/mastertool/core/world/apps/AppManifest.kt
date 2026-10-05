package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.DeskSize
import com.kaiharimoto.mastertool.core.world.desk.Tile
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import kotlinx.serialization.Serializable

/**
 * What an app is for (§8.2), kept in its manifest as the word: a kind a newer build adds is kept as written and drawn as
 * a [VIEWER] here ([AppKind.of]).
 */
enum class AppKind(val id: String) {
    CALCULATOR("calculator"),
    EXPLORER("explorer"),
    TRACKER("tracker"),
    SIMULATOR("simulator"),
    VIEWER("viewer"),
    DRILL("drill"),
    PLANNER("planner"),
    NOTEBOOK("notebook"),
    ;

    companion object {
        fun of(id: String?): AppKind = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: VIEWER
    }
}

/**
 * An app Ai made (`docs/world/DESKTOP.md` §8.2): `<data>/world/<id>/apps/<slug>/app.json`. [slug] is the folder's name
 * (`[a-z0-9-]{1,32}`); [name] one line of at most 32 characters; [kind] the word as written; [glyph] and [monogram]
 * Ai's two choices for its tile (§7.3); [size] its comfort size in dp; [version] rises on every change of its code;
 * [api] the contract it was written to (1); [by] `ai` or `you`.
 */
@Serializable
data class AppManifest(
    val slug: String,
    val name: String = slug,
    val kind: String = AppKind.VIEWER.id,
    val glyph: String? = null,
    val monogram: String? = null,
    val description: String = "",
    val size: DeskSize = DeskSize(480.0, 360.0),
    val version: Int = 1,
    val api: Int = API,
    val by: String = WorldEvent.AI,
    val created: Long = 0L,
    val updated: Long = 0L,
) {
    val appKind: AppKind get() = AppKind.of(kind)
    val ref: AppRef.Made get() = AppRef.Made(slug)

    /** Its tile: the glyph it chose (or its kind's) and its monogram (or its initials). */
    val tile: Tile get() = WorldIcons.tile(name, appKind, glyph, monogram)

    /** The name as the title bar shows it: control characters stripped, one line, at most [AppLimits.NAME] (§8.6). */
    val title: String get() = AppCodec.cleanName(name).ifEmpty { slug }

    companion object {
        const val API = 1
    }
}
