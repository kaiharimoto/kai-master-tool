package com.kaiharimoto.neue.start

import com.kaiharimoto.neue.builder.GenesysCapField
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.mastertool.core.deck.PlayChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.start.StartState
import com.kaiharimoto.mastertool.core.start.StartStep
import com.kaiharimoto.mastertool.core.start.StartSteps
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.VoiceModelChoice
import com.kaiharimoto.neue.ai.downloadForDuel
import com.kaiharimoto.neue.ai.voiceModel
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.Voice
import com.kaiharimoto.neue.sync.SyncSection
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.WorldPython

/**
 * The setup offered on opening (1.0.69): over the whole window, the steps still to do — all of them for
 * someone new, only what arrived since for someone updating — one at a time, each with its choice made
 * there and then. Skip never asks again; Later (and Esc, and Back) puts the whole of it away until
 * Settings › Setup shows it again.
 */
@Composable
fun StartScreen(h: NeueHolders, modifier: Modifier = Modifier) {
    val neue = h.neue
    val steps = neue.startSteps
    if (steps.isEmpty()) return
    val c = Mu.colors
    var at by remember(steps) { mutableIntStateOf(StudioStart.at.coerceIn(0, steps.lastIndex)) }
    val step = steps[at.coerceIn(0, steps.lastIndex)]
    val fresh = remember(steps) { StartSteps.isNew(neue.prefs.start, startState(h)) }

    fun next(done: Boolean = true) {
        if (done) neue.update { it.copy(start = it.start.copy(done = (it.start.done + step.id).distinct())) }
        if (at < steps.lastIndex) at++ else {
            neue.startEnded()
            neue.update { it.copy(start = it.start.copy(seen = Platform.version)) }
        }
    }

    BoxWithConstraints(
        modifier
            .background(c.paper)
            // The page under it hears nothing while it is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .imePadding(),
    ) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = if (wide) 24.dp else 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Micro(if (fresh) "Welcome to Neue Master Tool" else "New since you last opened it · ${Platform.version}", color = c.ink)
                Box(Modifier.weight(1f))
                MuButton("Later", { neue.startLater() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            HRule(color = c.ink)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    Column(Modifier.width(300.dp).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Micro(if (fresh) "Setting up" else "To set up", color = c.ink45, modifier = Modifier.padding(bottom = 8.dp))
                        steps.forEachIndexed { i, s ->
                            val current = i == at
                            val done = i < at
                            Row(
                                Modifier.fillMaxWidth().background(if (current) c.ink else c.paper).padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Mono(if (done) "✓" else (i + 1).toString().padStart(2, '0'), color = if (current) c.paper else if (done) c.ink else c.ink45)
                                Small(short(s, h), color = if (current) c.paper else if (done) c.ink else c.ink45)
                            }
                        }
                    }
                    VRule(color = c.ink12)
                }
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = if (wide) 48.dp else 16.dp, vertical = if (wide) 48.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Mono("${(at + 1).toString().padStart(2, '0')} / ${steps.size.toString().padStart(2, '0')}", color = c.ink45)
                        if (fresh && at == 0) {
                            MuText("Welcome.", style = MuType.display(LocalMuFonts.current), color = c.ink)
                            Small("A few choices before you start. Each one can be changed later in Settings.", color = c.ink70)
                            HRule(Modifier.padding(top = 4.dp))
                        }
                        MuText(title(step, h), style = MuType.h1(LocalMuFonts.current), color = c.ink)
                        Body(h, step) { next() }
                        HRule()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (at > 0) MuButton("Back", { at-- }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                            Box(Modifier.weight(1f))
                            if (step != StartStep.LOOK) MuButton("Skip", { next() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                            MuButton(if (at == steps.lastIndex) "Done" else "Next", { next() }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = at < steps.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

/** The step the studio opens the setup on (`--start=new:2`); zero in the app. */
object StudioStart {
    var at = 0
}

/** What is set up here already: the steps that are done are never offered. */
fun startState(h: NeueHolders) = StartState(
    hasDecks = h.decksKnown,
    syncOn = h.sync.on,
    aiEnabled = h.neue.prefs.ai.enabled,
    aiConnected = h.neue.prefs.ai.connection != null,
    artSettled = !h.neue.prefs.hdArt || h.art.count.complete,
    voiceReady = !Voice.usesModels || !Voice.needsModel(h.ai.voiceModel),
    worldReady = !WorldPython.possible || h.neue.prefs.world.python,
    rulesChosen = h.neue.prefs.genesys || h.neue.prefs.legalAsOf.isNotBlank(),
)

private fun short(step: StartStep, h: NeueHolders) = when (step) {
    StartStep.LOOK -> "Paper or ink"
    StartStep.PLAY -> "What you play"
    StartStep.DECKS -> "Your decks"
    StartStep.SYNC -> "Every device"
    StartStep.AI -> h.neue.prefs.ai.name
    StartStep.ART -> "Offline art"
    StartStep.VOICE -> "Keys and voice"
    StartStep.WORLD -> "Ai World"
}

private fun title(step: StartStep, h: NeueHolders) = when (step) {
    StartStep.LOOK -> "Paper or ink"
    StartStep.PLAY -> "What do you play?"
    StartStep.DECKS -> "Bring your decks"
    StartStep.SYNC -> "Your decks on every device"
    StartStep.AI -> "Meet ${h.neue.prefs.ai.name}"
    StartStep.ART -> "Every card's picture, offline"
    StartStep.VOICE -> "Duel by keys and voice"
    StartStep.WORLD -> "${h.neue.prefs.ai.name}'s own computer"
}

@Composable
private fun Body(h: NeueHolders, step: StartStep, next: () -> Unit) {
    val c = Mu.colors
    val neue = h.neue
    val prefs = neue.prefs
    val android = Platform.os == DesktopOs.ANDROID
    when (step) {
        StartStep.LOOK -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small("Neue is drawn in two colours, ink on paper. Ink turns it over for a dark room.", color = c.ink70)
            Labelled("Theme") { Segmented(prefs.theme, NeueTheme.entries, { if (it == NeueTheme.PAPER) "Paper" else "Ink" }, { t -> neue.update { it.copy(theme = t) } }) }
            Labelled("Foil") { Segmented(prefs.foil, Foils.all.map { it.id }, Foils::label, { f -> neue.update { it.copy(foil = f) } }) }
            Help("The foil is the light on a card's face; it follows the pointer, or the tilt of a phone.")
        }
        // What do you play (1.1.8, the 1.1.2 design review, finding 15): the bar's own choice, so Genesys is found.
        StartStep.PLAY -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small("The deck is checked against it as you build: which cards are out, and how many of each you may play.", color = c.ink70)
            val play = h.play
            Labelled("Play") { Segmented(play, PlayChoice.entries, { it.label }, h::setPlay) }
            Help(
                when (play) {
                    PlayChoice.TCG -> "The TCG's Forbidden & Limited List, and the cards released in the TCG."
                    PlayChoice.OCG -> "The OCG's Forbidden & Limited List, and the cards released in the OCG."
                    PlayChoice.GENESYS -> "Konami's points format: no Forbidden & Limited List, TCG cards, no Link or Pendulum monsters, and the whole deck's points under a cap. Each card's points stand in its corner."
                },
            )
            if (play == PlayChoice.GENESYS) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { GenesysCapField(neue) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MuButton("Check against a past list", {
                    // Legality, opened on the builder once the setup is put away.
                    neue.afterStart = Drawer.ISSUES
                    next()
                }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                Help(
                    "Any day's list since 2002 is in Legality: " +
                        (if (neue.phone) "the line under the deck's name, or ⋯ › Legality." else "the ✓ beside the deck's name, or I.") +
                        " It opens when you finish here.",
                )
            }
        }
        StartStep.DECKS -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small(
                "Open a .ydk or .ydkx file from EDOPro, Master Duel exporters or YGOPRODeck" + if (android) ", or scan a deck's QR code from Import." else ".",
                color = c.ink70,
            )
            Small("Coming from another device? Set up sync next, and your decks arrive by themselves.", color = c.ink70)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Import a deck file", { h.builder.importFromFile() }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
            }
        }
        // The section says what sync is itself: the step adds nothing to it.
        StartStep.SYNC -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SyncSection(h.sync) { label, help, _, control ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RowText(label)
                    Help(help)
                    // The control scrolls sideways itself where it must: a second scroll round it cannot be measured.
                    Box(Modifier.fillMaxWidth()) { control() }
                }
            }
        }
        StartStep.AI -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small(
                "An assistant in the app that can do what you can: build and tune decks, read the latest tournament results, plan your siding, " +
                    "write a guide to your deck. It thinks with a model you connect: your Claude or ChatGPT plan, an API key, or one on your own computer.",
                color = c.ink70,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Set it up", {
                    next()
                    h.ai.setOpen(true)
                }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true)
                MuButton("Turn it off", {
                    neue.update { it.copy(ai = it.ai.copy(enabled = false)) }
                    next()
                }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            Help("Off hides every trace of it. Settings › Assistant turns it back on.")
        }
        // Command mode (1.0.87): only offered where a speech model is still to download, the desk.
        StartStep.VOICE -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small(
                "Play a whole duel without the mouse. Type a move on the command line — \"summon h2 to m3\" — or hold M and say it, " +
                    "and let go: the move is shown on the table, and Enter (or saying \"yes\") makes it.",
                color = c.ink70,
            )
            Small("Your words are written out on this computer, never sent anywhere. That needs a speech model, downloaded once.", color = c.ink70)
            VoiceModelChoice(h.ai)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Download ${h.ai.voiceModel.megabytes} MB", {
                    h.ai.downloadForDuel()
                    next()
                }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
                MuButton("Typing is enough", { next() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            Help("Fast is quickest for short commands. Holding M without the model asks again.")
        }
        StartStep.WORLD -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small(
                "Ai World (Ctrl 8) is a small computer of ${prefs.ai.name}'s own: it writes code, runs it and pins what it finds — odds, " +
                    "simulations, card webs, charts — and you watch every keystroke, run and thought as it works.",
                color = c.ink70,
            )
            Small(
                "JavaScript always runs there, shut away from your files and the network. Python runs too if you allow it, with numpy " +
                    "and matplotlib if you have them — but Python runs as you, with your permissions, so only allow it if you are happy for " +
                    "${prefs.ai.name}'s code to run on this computer.",
                color = c.ink70,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Allow Python", {
                    neue.update { it.copy(world = it.world.copy(python = true)) }
                    next()
                }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
                MuButton("JavaScript is enough", { next() }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            Help("Change it any time in Settings › Ai World.")
        }
        StartStep.ART -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Small(
                "Every card's full-size picture, about 2 GB, downloaded in the background while you work — so cards stay sharp at an event with no signal." +
                    if (android) " On a phone or tablet it waits for Wi-Fi." else "",
                color = c.ink70,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Download them", {
                    neue.update { it.copy(hdArt = true) }
                    h.art.downloadAll()
                    next()
                }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
                MuButton("Not on this device", {
                    neue.update { it.copy(hdArt = false) }
                    next()
                }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
        }
    }
}

@Composable
private fun Labelled(label: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro(label, color = Mu.colors.ink70)
        Box(Modifier.horizontalScroll(rememberScrollState())) { control() }
    }
}
