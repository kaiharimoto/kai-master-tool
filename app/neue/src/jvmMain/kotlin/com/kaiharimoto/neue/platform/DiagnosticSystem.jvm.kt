package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.async.thread
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory

internal actual object DiagnosticSystem {
    private const val MB = 1024 * 1024

    actual fun memory(): String {
        val r = Runtime.getRuntime()
        val heap = "heap ${(r.totalMemory() - r.freeMemory()) / MB} of ${r.maxMemory() / MB} MB"
        val free = runCatching {
            val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            ", the computer ${os.freeMemorySize / MB} MB free of ${os.totalMemorySize / MB}"
        }.getOrDefault("")
        return heap + free
    }

    actual fun process(): String = " · process ${ProcessHandle.current().pid()}"

    actual fun onExit(block: () -> Unit) {
        // A hook that threw would reach the crash handler, whose exit blocks for ever while the hooks run.
        Runtime.getRuntime().addShutdownHook(Background.thread("the closing line", start = false) { block() })
    }
}
