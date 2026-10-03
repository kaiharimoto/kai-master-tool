package com.kaiharimoto.neue.pages

import androidx.compose.foundation.background
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.offline.ArtCount
import com.kaiharimoto.mastertool.core.offline.Offline
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.NameStyles
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.theme.Mu

/** What Settings needs from outside the deck: the updater, the folders, the way to write back. */
class SettingsHost(
    val version: String,
    val dataDir: String,
    val updateStatus: String,
    val checking: Boolean,
    val onCheckUpdates: () -> Unit,
    val onReportIssue: () -> Unit,
    val onOpenDataDir: () -> Unit,
    val onSearchEffects: (Boolean) -> Unit,
    val art: ArtLibrary? = null,
    /** The assistant, for its section (1.0.43). */
    val ai: com.kaiharimoto.neue.ai.AiState? = null,
    /** Sync across devices, for its section (1.0.68). */
    val sync: com.kaiharimoto.neue.sync.SyncCenter? = null,
    /** Backups, for theirs (1.0.69). */
    val backups: com.kaiharimoto.neue.backup.BackupCenter? = null,
    /** Shows the setup offered on opening again (1.0.69). */
    val onSetupAgain: (() -> Unit)? = null,
)

/**
 * Settings (§4): numbered sections, rows of a 260px label and help beside the
 * control, hairlines between. Nothing here needs a Save; every control writes
 * as it changes.
 */
@Composable
fun SettingsPage(state: DeckBuilderState, neue: NeueState, host: SettingsHost) {
    val prefs = neue.prefs
    val scroll = rememberScrollState()
    val touch = neue.touchFirst
    Column(Modifier.fillMaxSize()) {
        PageHeader(null, "Settings", "Stored on this ${if (neue.phone) "phone" else if (touch) "tablet" else "computer"} · v${host.version}")
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // A phone's page keeps 16 at its edges, not 32: the words need the width (v1.3.5).
            val edge = if (maxWidth < 600.dp) 16.dp else 32.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(start = edge, end = edge, top = edge, bottom = 64.dp).widthIn(max = 960.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column {
                    SectionTitle(1, "Appearance")
                    SettingRow("Theme", "Paper is the default. Ink is its exact inversion.") {
                        Segmented(prefs.theme, NeueTheme.entries, { if (it == NeueTheme.PAPER) "Paper" else "Ink" }, { t -> neue.update { it.copy(theme = t) } })
                    }
                    // Which way the screen turns (kai, v1.3.5): also one tap in the bar's menu.
                    if (touch) SettingRow("Screen", "Portrait stands the phone up, Landscape lays it down, Auto follows how it is held. The rotation lock is respected.") {
                        Segmented(neue.orientation, com.kaiharimoto.mastertool.core.layout.ScreenOrientation.entries, { it.label }, { o -> neue.update { it.copy(orientation = o.key) } })
                    }
                    SettingRow("Contrast", "High darkens the grey text, the outlines of controls and the rules between rows, in both themes.") {
                        Segmented(prefs.contrast, listOf(NeuePreferences.CONTRAST_STANDARD, NeuePreferences.CONTRAST_HIGH), { if (it == NeuePreferences.CONTRAST_HIGH) "High" else "Standard" }, { v -> neue.update { it.copy(contrast = v) } })
                    }
                    SettingRow(
                        "Interface scale",
                        if (touch) "Everything, text and cards alike. For larger text alone, use Text size." else
                            "Everything, text and cards alike. ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.ZOOM_IN)} and ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.ZOOM_OUT)} step through it from anywhere.",
                    ) {
                        // Seven steps are wider than a phone: they scroll sideways there (v1.3.5).
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Segmented(prefs.scale, NeuePreferences.SCALES, { "${kotlin.math.round(it * 100).toInt()}%" }, { s -> neue.update { it.copy(scale = s) } }, small = true)
                        }
                    }
                    // The type alone (touch swarm, rec 26): panes, cards and targets keep their size.
                    SettingRow("Text size", "The type alone; the panes and the cards keep their size.") {
                        Segmented(prefs.textScaleOn(touch, neue.phone), NeuePreferences.TEXT_SCALES, { "${kotlin.math.round(it * 100).toInt()}%" }, { t -> neue.update { it.copy(textScale = t) } }, small = true)
                    }
                    SettingRow("Foil", if (touch) "The light on a card's face." else "The light on a card's face. It follows the pointer across the card.") {
                        Segmented(prefs.foil, Foils.all.map { it.id }, Foils::label, { f -> neue.update { it.copy(foil = f) } })
                    }
                    // The foil follows the phone's tilt (kai, v1.3.6).
                    if (touch) SettingRow("Foil follows the tilt", "Turn the phone and the light on every card moves with it, as a real foil catches a lamp. Held still, it settles in the middle.", onToggle = { neue.update { it.copy(foilTilt = !it.foilTilt) } }) {
                        com.kaiharimoto.neue.kit.MuSwitch(prefs.foilTilt, { on -> neue.update { it.copy(foilTilt = on) } })
                    }
                    SettingRow("Card names", "The name printed across the top of a card, stamped in the same foil as its border. Holographic foil only.") {
                        Segmented(prefs.foilNames, NameStyles.all, NameStyles::label, { n -> neue.update { it.copy(foilNames = n) } })
                    }
                    SettingRow("Limit marks", "A 1 or 2 in the corner of Limited and Semi-Limited cards. A Forbidden card's 0 always shows.", onToggle = { neue.update { it.copy(limitMarks = !it.limitMarks) } }) {
                        MuSwitch(prefs.limitMarks, { on -> neue.update { it.copy(limitMarks = on) } })
                    }
                    SettingRow(
                        "Zen by itself",
                        "In immersive mode, idle ten seconds and the deck floats on its own. Off, zen comes only when asked for with ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.ZEN)}.",
                        onToggle = { neue.update { it.copy(autoZen = !it.autoZen) } },
                    ) {
                        MuSwitch(prefs.autoZen, { on -> neue.update { it.copy(autoZen = on) } })
                    }
                    // A tablet's index is always out (the strip): there is nothing to choose.
                    if (!touch) SettingRow("Index", "Folded away until the pointer reaches the window's left edge, or always out. ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.GO_DECKS)} to ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.GO_DUEL)} reach the pages either way.") {
                        Segmented(prefs.railPinned, listOf(false, true), { if (it) "Pinned" else "Auto-hide" }, { p -> neue.update { it.copy(railPinned = p) } })
                    }
                }
                Column {
                    SectionTitle(2, "Building")
                    SettingRow("Pool columns", "Auto draws the pool's cards the size of the main deck's.") {
                        Segmented(prefs.poolColumns, listOf(0, 3, 4, 5, 6, 8), { if (it == 0) "Auto" else it.toString() }, { n -> neue.update { it.copy(poolColumns = n) } }, small = true)
                    }
                    // The deck's picture is the desktop's: the tablet has no screenshot yet.
                    if (com.kaiharimoto.neue.platform.Platform.os != com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID) {
                        SettingRow("Screenshot", "Picture is the deck as the builder draws it, groups and all. List is a decklist, each card once with its count and name, made to be read on a phone.") {
                            Segmented(prefs.shotStyle, listOf(NeuePreferences.SHOT_PICTURE, NeuePreferences.SHOT_LIST), { if (it == NeuePreferences.SHOT_LIST) "List" else "Picture" }, { v -> neue.update { it.copy(shotStyle = v) } })
                        }
                    }
                    SettingRow("Search card text", "Match the words printed on a card as well as its name. name: and text: in a search choose one.", onToggle = { host.onSearchEffects(!state.searchEffects) }) {
                        MuSwitch(state.searchEffects, host.onSearchEffects)
                    }
                }
                host.ai?.let { ai -> Column { AssistantSection(ai, neue) } }
                // Sync (1.0.68): where this device meets the others.
                host.sync?.let { sync ->
                    Column {
                        SectionTitle(4, "Sync")
                        com.kaiharimoto.neue.sync.SyncSection(sync) { label, help, onToggle, control -> SettingRow(label, help, onToggle = onToggle, control = control) }
                    }
                }
                // Backups (1.0.69): everything made, kept safe across versions.
                host.backups?.let { backups ->
                    Column {
                        SectionTitle(5, "Backups")
                        BackupsSection(backups)
                    }
                }
                // Offline (kai, for a flight): whether the pool is current, bringing it up to
                // date, every card's picture on this computer, and when all of it is.
                Column {
                    SectionTitle(6, "Offline")
                    val check = state.poolCheck
                    val clock = check?.let { checkedClock(it.checkedAt) }
                    val updating = state.poolProgress ?: if (state.isSyncing) com.kaiharimoto.mastertool.core.data.PoolProgress.Asking else null
                    SettingRow("Card pool", Offline.poolLine(check, state.index.size, clock, updating)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (updating != null) {
                                Bar(updating.fraction, "${(updating.fraction * 100).toInt()}%")
                            } else {
                                if (state.checkingPool) Breathe()
                                MuButton(
                                    if (state.checkingPool) "Checking" else "Check for updates",
                                    state::checkCardPool,
                                    variant = BtnVariant.SUBTLE,
                                    size = BtnSize.SM,
                                    enabled = !state.checkingPool,
                                    reason = "Asking YGOPRODeck",
                                )
                                MuButton(
                                    "Update now",
                                    { state.refreshCardPool(force = true) },
                                    variant = if (check is com.kaiharimoto.mastertool.core.data.PoolCheck.Behind) BtnVariant.SECONDARY else BtnVariant.SUBTLE,
                                    size = BtnSize.SM,
                                    icon = Icons.Refresh,
                                )
                            }
                        }
                    }
                    val art = host.art
                    if (art != null) {
                        val count = art.count
                        SettingRow(
                            "High-resolution art",
                            (if (prefs.hdArt && neue.waitingForWifi) "Waiting for Wi-Fi. " else "") +
                                "${art.describe()}. Every card's full-size picture, about 2 GB, downloaded while you work, the deck and the card you are reading first.",
                            // The live status is the point of the help here: none of it is cut off.
                            helpLines = 6,
                            onToggle = { neue.update { it.copy(hdArt = !it.hdArt) } },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MuSwitch(prefs.hdArt, { on -> neue.update { it.copy(hdArt = on) } })
                                if (count.total > 0 && !count.complete) {
                                    Bar(count.fraction, "${count.percent}%", running = art.running)
                                }
                                if (!count.complete && !(prefs.hdArt && art.running && art.problem == null)) {
                                    MuButton(
                                        "Download all",
                                        {
                                            if (!prefs.hdArt) neue.update { it.copy(hdArt = true) }
                                            art.downloadAll()
                                        },
                                        variant = BtnVariant.SUBTLE,
                                        size = BtnSize.SM,
                                    )
                                }
                                // A folder is a desktop's to open; Android has nothing to hand one to.
                                if (com.kaiharimoto.neue.platform.Platform.os != com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID) {
                                    MuButton("Open folder", { com.kaiharimoto.neue.platform.Platform.open(art.dir) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                                }
                            }
                        }
                    }
                    val ready = Offline.readiness(check, state.index.size, art?.count ?: ArtCount.NONE, prefs.hdArt && art != null)
                    SettingRow(
                        "Ready for offline",
                        if (ready.ready) "The card pool is current and every card's picture is on this computer: nothing here needs the network." else ready.words + ".",
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ready.ready) {
                                com.kaiharimoto.neue.theme.Inverted {
                                    Box(Modifier.background(Mu.colors.paper).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Mono("Ready", color = Mu.colors.ink)
                                    }
                                }
                            } else {
                                Mono("Not yet", color = Mu.colors.ink45)
                            }
                        }
                    }
                }
                Column {
                    SectionTitle(7, "Updates and feedback")
                    SettingRow("Version", host.updateStatus) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(host.version, color = Mu.colors.ink)
                            if (host.checking) Breathe()
                            MuButton("Check now", host.onCheckUpdates, variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = !host.checking, reason = "Checking")
                        }
                    }
                    host.onSetupAgain?.let { again ->
                        SettingRow("Setup", "The steps offered when the app opens: your look, your decks, sync, the assistant and offline art. Done ones are left out.") {
                            MuButton("Show again", again, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                        }
                    }
                    SettingRow("Report an issue", "Opens a new issue on GitHub with the version and the system filled in.") {
                        MuButton("Report", host.onReportIssue, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                    }
                    SettingRow("Data folder", host.dataDir) {
                        if (com.kaiharimoto.neue.platform.Platform.os != com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID) {
                            MuButton("Open", host.onOpenDataDir, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                        }
                    }
                }
                Column {
                    SectionTitle(8, "Licences")
                    Help("Inter and JetBrains Mono, SIL Open Font License 1.1. Card images and data from YGOPRODeck. Neue Master Tool is not affiliated with Konami.")
                }
            }
            ScrollbarFor(scroll)
        }
    }
}

/**
 * The assistant (Ai, 1.0.43): on or off — off hides every trace of it — then, while
 * on, its name, its connection, how hard it thinks, whether it asks before deleting,
 * its voice and what it knows.
 */
@Composable
private fun AssistantSection(ai: com.kaiharimoto.neue.ai.AiState, neue: NeueState) {
    val prefs = neue.prefs.ai
    SectionTitle(3, "Assistant")
    // Ai's mark by its name, for flavour (1.0.63).
    if (prefs.enabled) com.kaiharimoto.neue.ai.avatar.AiName(prefs.name, com.kaiharimoto.neue.theme.Mu.colors.ink, mark = 28.dp)
    SettingRow(
        "Assistant",
        if (prefs.enabled) "${prefs.name} is on: in the bar, on ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.AI_PANEL).ifEmpty { "its button" }}, beside every page. Off hides every trace of it; what it remembers is kept."
        else "Off: nothing of the assistant shows anywhere in the app. Turn it on to set it up.",
        onToggle = { neue.update { it.copy(ai = it.ai.copy(enabled = !it.ai.enabled)) } },
    ) {
        MuSwitch(prefs.enabled, { on -> neue.update { it.copy(ai = it.ai.copy(enabled = on)) } })
    }
    if (!prefs.enabled) return
    var name by androidx.compose.runtime.remember(prefs.name) { androidx.compose.runtime.mutableStateOf(prefs.name) }
    SettingRow("Name", "What it is called. Ai by default, after the Ignis of VRAINS.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.kaiharimoto.neue.kit.MuInput(name, { name = it.take(com.kaiharimoto.mastertool.core.prefs.AiPrefs.MAX_NAME) }, Modifier.width(220.dp), placeholder = "Ai", onSubmit = { if (name.isNotBlank()) ai.rename(name) })
            if (name.trim() != prefs.name && name.isNotBlank()) MuButton("Rename", { ai.rename(name) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
        }
    }
    val connection = prefs.connection
    val provider = com.kaiharimoto.mastertool.core.ai.providers.Providers.byId(connection?.provider)
    SettingRow(
        "Connection",
        if (provider == null) "Not connected yet. The wizard walks you through a Claude or ChatGPT plan, an API key, or a model on your own machine."
        else "${provider.label}${connection?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}. ${provider.blurb}",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton(if (provider == null) "Set up" else "Change", { ai.openWizard() }, variant = if (provider == null) BtnVariant.PRIMARY else BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
            if (prefs.connections.size > 1) {
                com.kaiharimoto.neue.kit.MuSelect(
                    connection,
                    prefs.connections,
                    { c -> (c?.label ?: "").ifBlank { c?.provider.orEmpty() } + (c?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") },
                    { c -> c?.let { ai.use(it.id) } },
                )
            }
            if (connection != null) MuButton("Forget", { ai.forget(connection.id) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }
    if (provider != null && provider.efforts.isNotEmpty()) {
        SettingRow("Thinking", "How hard it thinks before answering, where the model can be told. Higher is slower and costs more.") {
            Segmented(prefs.effort, listOf("") + provider.efforts, { if (it.isBlank()) "Default" else it.replaceFirstChar { ch -> ch.uppercase() } }, { e -> neue.update { it.copy(ai = it.ai.copy(effort = e)) } }, small = true)
        }
    }
    SettingRow("Reasoning", "How its thinking shows above an answer, where the model shares it: its first lines, all of it, or none. Reading it is a way to learn along.") {
        Segmented(
            prefs.showReasoning,
            com.kaiharimoto.mastertool.core.prefs.AiPrefs.REASONINGS,
            { when (it) { com.kaiharimoto.mastertool.core.prefs.AiPrefs.REASONING_OPEN -> "Open"; com.kaiharimoto.mastertool.core.prefs.AiPrefs.REASONING_HIDDEN -> "Hidden"; else -> "Folded" } },
            { r -> neue.update { it.copy(ai = it.ai.copy(showReasoning = r)) } },
            small = true,
        )
    }
    SettingRow("Ask before deleting", "Deleting a deck, a web, or a deck from a web waits for your OK in the chat. Every other change can be undone.", onToggle = { neue.update { it.copy(ai = it.ai.copy(alwaysAllow = !it.ai.alwaysAllow)) } }) {
        MuSwitch(!prefs.alwaysAllow, { on -> neue.update { it.copy(ai = it.ai.copy(alwaysAllow = !on)) } })
    }
    SettingRow("What it knows", "Its voice, what it has learned about you, its own notes, and notes on your decks and webs: markdown files you can read and edit.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton("Open", { ai.memoryOpen = "USER.md" }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
            MuButton("Voice", { ai.memoryOpen = com.kaiharimoto.mastertool.core.ai.memory.Persona.FILE }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
            if (com.kaiharimoto.neue.platform.Platform.os != com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID) {
                MuButton("Open folder", { ai.files.root.mkdirs(); com.kaiharimoto.neue.platform.Platform.open(ai.files.root) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
            }
            MuButton("Forget everything", { ai.forgetAsked = true }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
    }
}

/**
 * Backups (1.0.69, kai: "I have a lot of progress in my current version … how do we account for that?"):
 * the last one and why it was made, Back up now, Export, and Restore from the list or a file.
 */
@Composable
private fun BackupsSection(b: com.kaiharimoto.neue.backup.BackupCenter) {
    val c = Mu.colors
    val list = androidx.compose.runtime.remember(b.revision) { b.list() }
    var choosing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val last = list.firstOrNull()
    SettingRow(
        "Backups",
        (last?.let { "Last: ${com.kaiharimoto.neue.backup.BackupCenter.date(it.manifest.at)} · ${it.manifest.reason.lowercase()}. " } ?: "None yet. ") +
            "One is made by itself the first time a new version opens, before it changes anything, and once a week; the last ten are kept. " +
            "Each holds your decks, webs, Prep, settings, Ai's notes and your own pictures.",
        helpLines = 5,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton(b.working ?: "Back up now", b::backUpNow, variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = b.working == null, reason = "Working")
            MuButton("Export", b::export, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Export, enabled = b.working == null, reason = "Working")
            MuButton("Restore", { choosing = true }, variant = BtnVariant.GHOST, size = BtnSize.SM, enabled = b.working == null, reason = "Working")
        }
    }
    if (choosing) {
        com.kaiharimoto.neue.kit.MuDialog(
            title = "Restore a backup",
            onDismiss = { choosing = false },
            width = 560.dp,
            description = "Your decks, settings, webs, Prep, notes and pictures as they were. Nothing made since is deleted, and how things are now is backed up first.",
            footer = {
                MuButton("Cancel", { choosing = false }, variant = BtnVariant.GHOST)
                MuButton("From a file", { choosing = false; b.restoreFromFile() }, variant = BtnVariant.SECONDARY, arrow = true)
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (list.isEmpty()) Help("No backups on this device yet.")
                list.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RowText(com.kaiharimoto.neue.backup.BackupCenter.date(e.manifest.at))
                            Help("${e.manifest.reason} · ${e.manifest.decks} decks · v${e.manifest.version}".trim())
                        }
                        MuButton("Restore", { choosing = false; b.restore(e) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                    }
                }
            }
        }
    }
}

/**
 * A setting: its label and help beside its control. A switch's row is one target on a
 * tablet (touch swarm, rec 27, [onToggle]): the words beside a 36×18 track were dead.
 */
@Composable
private fun SettingRow(label: String, help: String, helpLines: Int = 3, onToggle: (() -> Unit)? = null, control: @Composable () -> Unit) {
    val c = Mu.colors
    val whole = onToggle != null && com.kaiharimoto.neue.kit.LocalTouchFirst.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Narrow (a phone, v1.3.5), the label and its help stand over the control rather than
    // in a 260 column beside it, which left the control a sliver.
    val stacked = maxWidth < 600.dp
    val row = Modifier.fillMaxWidth()
        .let { if (whole) it.defaultMinSize(minHeight = com.kaiharimoto.mastertool.core.input.TouchMetrics.SETTING_ROW.dp).muClickable(onClick = onToggle!!).cursorPointer(showsWords = true) else it }
        .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
        .padding(vertical = 12.dp)
    if (stacked) {
        Column(row, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RowText(label)
                Help(help, maxLines = helpLines + 3)
            }
            Box(Modifier.fillMaxWidth()) { control() }
        }
    } else {
        Row(
            row,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RowText(label)
                Help(help, maxLines = helpLines)
            }
            Box(Modifier.weight(1f)) { control() }
        }
    }
    }
}

/** A long job's bar (§6): the 3px track, its figure in mono beside it, a square breathing while it moves. */
@Composable
private fun Bar(fraction: Float, figure: String, running: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Breathe(running = running)
        Progress(fraction, Modifier.width(160.dp))
        Mono(figure, color = Mu.colors.ink)
    }
}

/** When a check was made, as this device's clock reads it: `12:04`. */
private fun checkedClock(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** A chord as this machine writes it, for the settings' own sentences. */
private fun chord(action: com.kaiharimoto.mastertool.core.input.DeskAction): String =
    com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(action)
        ?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd).orEmpty()
