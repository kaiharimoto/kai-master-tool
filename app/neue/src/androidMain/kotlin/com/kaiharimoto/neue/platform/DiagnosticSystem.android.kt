package com.kaiharimoto.neue.platform

internal actual object DiagnosticSystem {
    actual fun memory(): String {
        val r = Runtime.getRuntime()
        return "heap ${(r.totalMemory() - r.freeMemory()) / (1024 * 1024)} of ${r.maxMemory() / (1024 * 1024)} MB"
    }

    /** Android ends an app as it likes, and says nothing to it first. */
    actual fun process(): String = ""

    actual fun onExit(block: () -> Unit) = Unit
}
