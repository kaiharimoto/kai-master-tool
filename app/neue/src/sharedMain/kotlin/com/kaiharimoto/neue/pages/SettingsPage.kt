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
import androidx.compose.runtime.Composable
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
        PageHeader(null, "Settings", "Stored on this ${if (touch) "tablet" else "computer"} · v${host.version}")
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 64.dp).widthIn(max = 960.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column {
                    SectionTitle(1, "Appearance")
                    SettingRow("Theme", "Paper is the default. Ink is its exact inversion.") {
                        Segmented(prefs.theme, NeueTheme.entries, { if (it == NeueTheme.PAPER) "Paper" else "Ink" }, { t -> neue.update { it.copy(theme = t) } })
                    }
                    SettingRow("Contrast", "High darkens the grey text, the outlines of controls and the rules between rows, in both themes.") {
                        Segmented(prefs.contrast, listOf(NeuePreferences.CONTRAST_STANDARD, NeuePreferences.CONTRAST_HIGH), { if (it == NeuePreferences.CONTRAST_HIGH) "High" else "Standard" }, { v -> neue.update { it.copy(contrast = v) } })
                    }
                    SettingRow(
                        "Interface scale",
                        if (touch) "Everything, text and cards alike. For larger text alone, use Text size." else
                            "Everything, text and cards alike. ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.ZOOM_IN)} and ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.ZOOM_OUT)} step through it from anywhere.",
                    ) {
                        Segmented(prefs.scale, NeuePreferences.SCALES, { "${kotlin.math.round(it * 100).toInt()}%" }, { s -> neue.update { it.copy(scale = s) } }, small = true)
                    }
                    // The type alone (touch swarm, rec 26): panes, cards and targets keep their size.
                    SettingRow("Text size", "The type alone; the panes and the cards keep their size.") {
                        Segmented(prefs.textScaleOn(touch), NeuePreferences.TEXT_SCALES, { "${kotlin.math.round(it * 100).toInt()}%" }, { t -> neue.update { it.copy(textScale = t) } }, small = true)
                    }
                    SettingRow("Foil", if (touch) "The light on a card's face." else "The light on a card's face. It follows the pointer across the card.") {
                        Segmented(prefs.foil, Foils.all.map { it.id }, Foils::label, { f -> neue.update { it.copy(foil = f) } })
                    }
                    SettingRow("Card names", "The name printed across the top of a card, stamped in the same foil as its border. Holographic foil only.") {
                        Segmented(prefs.foilNames, NameStyles.all, NameStyles::label, { n -> neue.update { it.copy(foilNames = n) } })
                    }
                    // A tablet's index is always out (the strip): there is nothing to choose.
                    if (!touch) SettingRow("Index", "Folded away until the pointer reaches the window's left edge, or always out. ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.GO_DECKS)} to ${chord(com.kaiharimoto.mastertool.core.input.DeskAction.GO_STATS)} reach the pages either way.") {
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
                // Offline (kai, for a flight): whether the pool is current, bringing it up to
                // date, every card's picture on this computer, and when all of it is.
                Column {
                    SectionTitle(3, "Offline")
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
                    SectionTitle(4, "Updates and feedback")
                    SettingRow("Version", host.updateStatus) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(host.version, color = Mu.colors.ink)
                            if (host.checking) Breathe()
                            MuButton("Check now", host.onCheckUpdates, variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = !host.checking, reason = "Checking")
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
                    SectionTitle(5, "Licences")
                    Help("Inter and JetBrains Mono, SIL Open Font License 1.1. Card images and data from YGOPRODeck. Neue Master Tool is not affiliated with Konami.")
                }
            }
            ScrollbarFor(scroll)
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
    Row(
        Modifier.fillMaxWidth()
            .let { if (whole) it.defaultMinSize(minHeight = com.kaiharimoto.mastertool.core.input.TouchMetrics.SETTING_ROW.dp).muClickable(onClick = onToggle!!).cursorPointer(showsWords = true) else it }
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 12.dp),
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
