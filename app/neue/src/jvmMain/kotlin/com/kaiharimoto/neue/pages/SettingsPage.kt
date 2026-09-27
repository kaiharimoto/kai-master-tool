package com.kaiharimoto.neue.pages

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
    Column(Modifier.fillMaxSize()) {
        PageHeader(null, "Settings", "Stored on this computer · v${host.version}")
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
                    SettingRow("Interface scale", "Everything, text and cards alike. Ctrl = and Ctrl - step through it from anywhere.") {
                        Segmented(prefs.scale, NeuePreferences.SCALES, { "${kotlin.math.round(it * 100).toInt()}%" }, { s -> neue.update { it.copy(scale = s) } }, small = true)
                    }
                    SettingRow("Foil", "The light on a card's face. It follows the pointer across the card.") {
                        Segmented(prefs.foil, Foils.all.map { it.id }, Foils::label, { f -> neue.update { it.copy(foil = f) } })
                    }
                    SettingRow("Card names", "The name printed across the top of a card, stamped in the same foil as its border. Holographic foil only.") {
                        Segmented(prefs.foilNames, NameStyles.all, NameStyles::label, { n -> neue.update { it.copy(foilNames = n) } })
                    }
                    SettingRow("Index", "Folded away until the pointer reaches the window's left edge, or always out. Ctrl 1 to 4 reach the pages either way.") {
                        Segmented(prefs.railPinned, listOf(false, true), { if (it) "Pinned" else "Auto-hide" }, { p -> neue.update { it.copy(railPinned = p) } })
                    }
                }
                Column {
                    SectionTitle(2, "Building")
                    SettingRow("Pool columns", "Auto draws the pool's cards the size of the main deck's.") {
                        Segmented(prefs.poolColumns, listOf(0, 3, 4, 5, 6, 8), { if (it == 0) "Auto" else it.toString() }, { n -> neue.update { it.copy(poolColumns = n) } }, small = true)
                    }
                    SettingRow("Search card text", "Match the words printed on a card as well as its name. name: and text: in a search choose one.") {
                        MuSwitch(state.searchEffects, host.onSearchEffects)
                    }
                    SettingRow("Card pool", "${"%,d".format(state.index.size)} cards${state.syncMessage?.let { " · $it" } ?: ""}") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.isSyncing) Breathe()
                            MuButton(
                                if (state.isSyncing) "Refreshing" else "Refresh",
                                { state.refreshCardPool(force = true) },
                                variant = BtnVariant.SUBTLE,
                                size = BtnSize.SM,
                                icon = Icons.Refresh,
                                enabled = !state.isSyncing,
                            )
                        }
                    }
                }
                host.art?.let { art ->
                    Column {
                        SectionTitle(3, "Card art")
                        SettingRow(
                            "High-resolution art",
                            "Downloads every card's full-size picture while you work, about 2 GB in all, and draws from it once it is here: " +
                                "the deck and the card you are reading first. ${art.describe()}${art.problem?.let { " · $it" } ?: ""}",
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MuSwitch(prefs.hdArt, { on -> neue.update { it.copy(hdArt = on) } })
                                if (art.running) {
                                    Breathe()
                                    Mono(art.percent(), color = Mu.colors.ink)
                                }
                                MuButton("Open folder", { com.kaiharimoto.neue.platform.Platform.open(art.dir) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                            }
                        }
                    }
                }
                Column {
                    SectionTitle(if (host.art != null) 4 else 3, "Updates and feedback")
                    SettingRow("Version", host.updateStatus) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(host.version, color = Mu.colors.ink)
                            if (host.checking) Breathe()
                            MuButton("Check now", host.onCheckUpdates, variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = !host.checking)
                        }
                    }
                    SettingRow("Report an issue", "Opens a new issue on GitHub with the version and the system filled in.") {
                        MuButton("Report", host.onReportIssue, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                    }
                    SettingRow("Data folder", host.dataDir) {
                        MuButton("Open", host.onOpenDataDir, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                    }
                }
                Column {
                    SectionTitle(if (host.art != null) 5 else 4, "Licences")
                    Help("Inter and JetBrains Mono, SIL Open Font License 1.1. Card images and data from YGOPRODeck. Neue Master Tool is not affiliated with Konami.")
                }
            }
            ScrollbarFor(scroll)
        }
    }
}

@Composable
private fun SettingRow(label: String, help: String, control: @Composable () -> Unit) {
    val c = Mu.colors
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RowText(label)
            Help(help, maxLines = 3)
        }
        Box(Modifier.weight(1f)) { control() }
    }
}
