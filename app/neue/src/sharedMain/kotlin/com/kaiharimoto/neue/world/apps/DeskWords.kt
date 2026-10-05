package com.kaiharimoto.neue.world.apps

import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.world.Worlds

/**
 * What `world_state` adds for the desktop (`docs/world/DESKTOP.md` §8.7): the apps with their versions, the Browser's
 * tabs, which windows are open, and any app that threw since Ai last looked — once.
 */
internal fun Worlds.describeDesk(): String = buildString {
    appendLine()
    if (apps.list.isEmpty()) {
        appendLine("Apps: none yet. world_app make builds one when the person will use it again.")
    } else {
        appendLine("Apps (world://apps/<slug> opens one):")
        apps.list.forEach { m ->
            appendLine("- ${m.slug} | ${m.title} | ${m.kind} | v${m.version}" + (if (m.description.isNotBlank()) " — ${m.description}" else "") +
                (if (apps.isLive(m.slug)) " | open" else ""))
        }
    }
    val tabs = browser.tabs
    if (tabs.tabs.isNotEmpty()) {
        appendLine("Browser tabs:")
        tabs.tabs.forEach { t ->
            val board = (t.parsed as? WorldAddress.Board)?.let { b -> open?.board(b.id)?.title?.let { " “$it”" } }.orEmpty()
            appendLine("- ${t.address}$board" + (if (t.id == tabs.selected) " (selected)" else "") + (if (t.kept) " (kept)" else ""))
        }
    }
    val windows = (BuiltInApp.entries.map { it.ref } + apps.list.map { AppRef.Made(it.slug) }).filter { apps.windows.isOpen(it) }
    if (windows.isNotEmpty()) appendLine("Windows open: " + windows.joinToString { it.key })
    val threw = apps.takeUnheard()
    if (threw.isNotEmpty()) {
        appendLine("Apps that threw since you last looked (fix them with world_app change):")
        threw.forEach { appendLine("- $it") }
    }
}
