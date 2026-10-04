package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.Serializable

/**
 * Ai World's settings (1.0.97), this device's alone: whether Python may run here is a decision about this computer,
 * made by the person in setup or Settings and never by Ai (`AiSettings.INTERNAL`).
 */
@Serializable
data class WorldPrefs(
    /** Python runs with the person's own permissions, so it is off until they allow it. */
    val python: Boolean = false,
    /** The Python to run; blank finds one on the PATH (python3, python, py -3). */
    val pythonPath: String = "",
    /** How fast Ai's code types into the editor, in characters a second; 0 shows it at once. */
    val typing: Int = 600,
    /** The World page comes forward when Ai starts working in a world, so nothing it does is out of sight. */
    val follow: Boolean = true,
    /** The world open last. */
    val open: String? = null,
)
