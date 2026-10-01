package com.kaiharimoto.neue.sync

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.sync.Cloud
import com.kaiharimoto.mastertool.core.sync.SyncPrefs
import com.kaiharimoto.mastertool.core.sync.SyncReport
import com.kaiharimoto.mastertool.core.sync.WebDavStore
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * Settings › Sync (1.0.68, kai: "give users options to use any service they choose. Bring your
 * cloud"): where the devices meet — a folder another app keeps in sync, a WebDAV server, or a sign-in
 * to Google Drive, Dropbox or OneDrive — this device's name, and what the last sync did. [row] is
 * Settings' own row, label and help beside the control.
 */
@Composable
fun SyncSection(sync: SyncCenter, row: @Composable (label: String, help: String, onToggle: (() -> Unit)?, control: @Composable () -> Unit) -> Unit) {
    val prefs = sync.prefs
    val clouds = Cloud.entries.filter { it.ready }
    val choices = listOf(SyncPrefs.OFF, SyncPrefs.FOLDER, SyncPrefs.WEBDAV) + clouds.map { it.id }
    var choice by remember(prefs.service) { mutableStateOf(prefs.service.takeIf { it in choices } ?: SyncPrefs.OFF) }
    val c = Mu.colors

    row(
        "Sync",
        "Decks, webs, Prep, settings, ${aiName(sync)}'s notes and your own pictures on every device, through a place you already have.",
        null,
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Segmented(choice, choices, ::serviceLabel, { v ->
                choice = v
                if (v == SyncPrefs.OFF) sync.turnOff()
            }, small = true)
        }
    }

    when (choice) {
        SyncPrefs.FOLDER -> row(
            "Folder",
            "A folder another app keeps in sync: iCloud Drive, Dropbox, OneDrive, Google Drive or Syncthing.",
            null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                prefs.folder?.takeIf { prefs.service == SyncPrefs.FOLDER }?.let { Mono(SyncPlatform.folderLabel(it), Modifier.widthIn(max = 320.dp), color = c.ink) }
                MuButton(if (prefs.folder != null && prefs.service == SyncPrefs.FOLDER) "Change" else "Choose folder", { sync.chooseFolder() }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
            }
        }
        SyncPrefs.WEBDAV -> WebDav(sync, row)
        SyncPrefs.OFF -> Unit
        else -> Cloud.of(choice)?.let { cloud -> CloudRow(sync, cloud, row) }
    }

    if (choice == SyncPrefs.OFF) return
    var name by remember(prefs.deviceName) { mutableStateOf(prefs.deviceName) }
    row("This device", "What your other devices call it, on the copy kept when both changed one deck.", null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuInput(name, { name = it.take(40) }, Modifier.widthIn(min = 160.dp, max = 240.dp), placeholder = SyncPlatform.deviceName, onSubmit = { sync.update { it.copy(deviceName = name.trim()) } })
            if (name.trim() != prefs.deviceName) MuButton("Rename", { sync.update { it.copy(deviceName = name.trim()) } }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
        }
    }
    row("Status", status(sync), { sync.update { it.copy(auto = !it.auto) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuButton(
                if (sync.running) "Syncing" else "Sync now",
                { sync.syncNow() },
                variant = BtnVariant.SUBTLE,
                size = BtnSize.SM,
                icon = com.kaiharimoto.neue.kit.Icons.Refresh,
                enabled = sync.on && !sync.running,
                reason = if (sync.running) "Syncing" else "Choose where to sync first",
            )
            MuSwitch(prefs.auto, { on -> sync.update { it.copy(auto = on) } })
            Small("By itself", color = c.ink70)
        }
    }
}

@Composable
private fun WebDav(sync: SyncCenter, row: @Composable (String, String, (() -> Unit)?, @Composable () -> Unit) -> Unit) {
    val prefs = sync.prefs
    val c = Mu.colors
    var url by remember { mutableStateOf(prefs.webdavUrl) }
    var user by remember { mutableStateOf(prefs.webdavUser) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val connected = prefs.service == SyncPrefs.WEBDAV && sync.configured(prefs)
    row(
        "WebDAV",
        "Nextcloud, ownCloud, pCloud, Koofr, Synology or any WebDAV server: a folder's address, your user name and an app password.",
        null,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WebDavStore.PRESETS.forEach { (label, address) ->
                    MuButton(label, { url = address }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                }
            }
            MuInput(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "https://cloud.example.com/remote.php/dav/files/you/", mono = true, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuInput(user, { user = it }, Modifier.weight(1f), placeholder = "User name")
                MuInput(password, { password = it }, Modifier.weight(1f), placeholder = if (connected) "Password kept" else "App password", secret = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuButton(
                    if (busy) "Connecting" else if (connected) "Connect again" else "Connect",
                    {
                        busy = true
                        problem = null
                        sync.connectWebDav(url, user, password) { why ->
                            busy = false
                            problem = why
                            if (why == null) password = ""
                        }
                    },
                    variant = BtnVariant.PRIMARY,
                    size = BtnSize.SM,
                    enabled = !busy && url.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                    reason = if (busy) "Connecting" else "Fill in the address, user name and password",
                )
                when {
                    problem != null -> Small(problem!!, color = c.ink)
                    connected -> Small("Connected as ${prefs.webdavUser}.", color = c.ink70)
                }
            }
        }
    }
}

@Composable
private fun CloudRow(sync: SyncCenter, cloud: Cloud, row: @Composable (String, String, (() -> Unit)?, @Composable () -> Unit) -> Unit) {
    val prefs = sync.prefs
    val signedIn = prefs.service == cloud.id && sync.configured(prefs)
    val waiting = sync.signingIn == cloud
    row(
        cloud.label,
        if (waiting) "Finish signing in in the browser, then come back." else "Your own account. The app sees only its own folder: ${cloud.where}.",
        null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (signedIn) {
                Small("Signed in" + prefs.account.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(), color = Mu.colors.ink)
                MuButton("Sign out", { sync.signOut(cloud) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            } else {
                MuButton(
                    if (waiting) "Waiting for the browser" else "Sign in with ${cloud.label}",
                    { sync.signIn(cloud) },
                    variant = BtnVariant.PRIMARY,
                    size = BtnSize.SM,
                    arrow = !waiting,
                    enabled = sync.signingIn == null,
                    reason = "Signing in",
                )
            }
        }
    }
}

/** What the last sync did, or why there was none, in a line. */
private fun status(sync: SyncCenter): String {
    val p = sync.prefs
    sync.problem?.let { return it }
    if (!sync.on) return if (p.service == SyncPrefs.OFF) "Off." else "Not set up yet."
    if (sync.running) return "Syncing…"
    val last = sync.last ?: return "Syncs on opening, a little after anything changes, and every few minutes" + if (p.auto) "." else " when By itself is on."
    return describe(last)
}

fun describe(r: SyncReport): String {
    val parts = buildList {
        if (r.sent > 0) add("${r.sent} sent")
        if (r.received > 0) add("${r.received} received")
        if (r.merged > 0) add("${r.merged} merged")
        if (r.copies > 0) add("${r.copies} kept as copies")
    }
    val with = if (r.devices.isEmpty()) " No other device has synced here yet." else " With ${r.devices.joinToString(", ")}."
    return "Synced " + clock(r.at) + (if (parts.isEmpty()) ", nothing changed." else ": " + parts.joinToString(", ") + ".") + with
}

private fun clock(at: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())).let { "at $it" }

private fun serviceLabel(id: String): String = when (id) {
    SyncPrefs.OFF -> "Off"
    SyncPrefs.FOLDER -> "Folder"
    SyncPrefs.WEBDAV -> "WebDAV"
    else -> Cloud.of(id)?.label ?: id
}

private fun aiName(sync: SyncCenter) = sync.aiName
