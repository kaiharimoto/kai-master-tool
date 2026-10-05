package com.kaiharimoto.mastertool.core.world.desk

/**
 * The seven apps every world has (`docs/world/DESKTOP.md` §3), in the order their `Alt` keys number them. [id] is the
 * word `desk.json` keeps; [pinned] says whether a fresh device pins it to the taskbar (§2.2: the five 1.0.97 showed at
 * once); [comfortW] and [comfortH] are the size it opens at, as fractions of the work area (§2.3).
 */
enum class BuiltInApp(val id: String, val title: String, val alt: Int, val pinned: Boolean, val comfortW: Double, val comfortH: Double) {
    FILES("files", "Files", 1, true, 0.26, 0.62),
    EDITOR("editor", "Editor", 2, true, 0.52, 0.72),
    TERMINAL("terminal", "Terminal", 3, true, 0.48, 0.38),
    BROWSER("browser", "Browser", 4, true, 0.64, 0.86),
    THOUGHTS("thoughts", "Thoughts", 5, true, 0.32, 0.86),
    INSTRUMENTS("instruments", "Instruments", 6, false, 0.44, 0.66),
    LIBRARY("library", "Library", 7, false, 0.64, 0.86),
    ;

    val ref: AppRef.BuiltIn get() = AppRef.BuiltIn(this)

    companion object {
        fun of(id: String): BuiltInApp? = entries.firstOrNull { it.id == id }

        /** The pins a device starts with: `WorldPrefs.pinned`'s default. */
        val PINNED: List<String> = entries.filter { it.pinned }.map { it.id }
    }
}

/**
 * Which app a window, an icon or a taskbar cell is: a built-in or one Ai made. One window per app, so [key] is also the
 * window's id. Kept in `desk.json` as [key] — a word, so a desk from a newer build with an app kind this one does not
 * know still reads ([parse] keeps it as [Made] only when it is one).
 */
sealed interface AppRef {
    /** `files`, `editor` … for a built-in; `app:<slug>` for an app Ai made. */
    val key: String

    data class BuiltIn(val kind: BuiltInApp) : AppRef {
        override val key: String get() = kind.id
    }

    data class Made(val slug: String) : AppRef {
        override val key: String get() = MADE + slug
    }

    companion object {
        const val MADE = "app:"

        /** The app [key] names, or null for a word this build does not know. */
        fun parse(key: String): AppRef? = when {
            key.startsWith(MADE) -> key.removePrefix(MADE).takeIf { SLUG.matches(it) }?.let(::Made)
            else -> BuiltInApp.of(key)?.ref
        }

        /** What a made app's folder may be called (§8.2). */
        val SLUG = Regex("[a-z0-9-]{1,32}")
    }
}
