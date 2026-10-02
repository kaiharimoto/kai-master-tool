package com.kaiharimoto.neue.present

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.present.PresentPrefs
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.present.paint.ThemeSwatch
import com.kaiharimoto.neue.present.paint.rememberSlideFonts
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/** The drawing context for [p], from the window's holders: its cards, pictures, faces and foil. */
@Composable
fun rememberSlideContext(h: NeueHolders, p: Presentation): SlideContext {
    val fonts = rememberSlideFonts()
    val index = h.builder.index
    val format = h.builder.format
    val foil = h.neue.prefs.foil
    return remember(p, fonts, index, format, foil) {
        SlideContext(p, fonts, { id -> index.byId(CardId(id)) }, { name -> h.present.bitmap(name) }, format, foil)
    }
}

/**
 * Present (1.0.70, `06`, `NEUE.md` §4o; kai: "a slideshow presentation creator that's animated
 * and interactive" for deck profiles). The library of presentations, or the one open in the
 * editor; the New dialog and the presenter hang off the holder.
 */
@Composable
fun PresentPage(h: NeueHolders) {
    val present = h.present
    LaunchedEffect(Unit) { if (!present.loaded) present.load() }
    val open = present.open
    if (open == null) PresentLibrary(h) else PresentEditor(h, open)
    if (present.creating) NewPresentationDialog(h)
    present.confirmDelete?.let { p ->
        MuDialog(
            "Delete ${p.name}?",
            { present.confirmDelete = null },
            description = "Its slides and notes go. Its pictures stay until no presentation uses them.",
            footer = {
                MuButton("Keep it", { present.confirmDelete = null }, variant = BtnVariant.GHOST)
                MuButton("Delete", { present.delete(p); present.confirmDelete = null }, variant = BtnVariant.PRIMARY, icon = Icons.Trash)
            },
        ) {}
    }
}

@Composable
private fun PresentLibrary(h: NeueHolders) {
    val present = h.present
    val c = Mu.colors
    if (!present.loaded) return
    val all = present.library
    if (all.isEmpty()) {
        EmptyState(
            "No presentations yet.",
            "Make a deck profile: the whole deck dimmed with what you talk about lit, a slide per card or group, or the deck built up as you go — with room for your webcam, your notes, and any slide you like.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("New deck profile", { present.creating = true }, variant = BtnVariant.PRIMARY, icon = Icons.Plus)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MuText("Presentations", style = MuType.h2(LocalMuFonts.current), color = c.ink)
                Small("${all.size} · deck profiles and anything else you present", color = c.ink70)
            }
            MuButton("New deck profile", { present.creating = true }, variant = BtnVariant.PRIMARY, icon = Icons.Plus)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(260.dp),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(all, key = { it.id }) { p -> LibraryTile(h, p) }
        }
    }
}

@Composable
private fun LibraryTile(h: NeueHolders, p: Presentation) {
    val present = h.present
    val c = Mu.colors
    val ctx = rememberSlideContext(h, p)
    val show = remember(p) { CompiledShow(p) }
    val first = p.slides.firstOrNull()
    val source = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, c.ink25)
                .hoverable(source)
                .cursorPointer(label = "Open ${p.name}")
                .muClickable(interactionSource = source) { present.openIt(p) },
        ) {
            if (first != null) {
                SlideView(ctx, first, show.zone(0), show.stage(0), Modifier.fillMaxSize(), deck = { show.deckFrame(0) }, deckKeys = emptyList())
            }
        }
        MuText(p.name, style = MuType.body(LocalMuFonts.current), color = c.ink, maxLines = 1)
        Small(
            listOfNotNull(
                p.deck?.name?.takeIf { it.isNotBlank() },
                Presentation.styleName(p.style),
                "${p.slides.size} slides",
                if (p.webcam.enabled) "camera" else null,
                SimpleDateFormat("d MMM").format(Date(p.updatedAt)),
            ).joinToString(" · "),
            color = c.ink45,
            maxLines = 1,
        )
        var moreAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton("Open", { present.openIt(p) }, size = BtnSize.SM)
            MuButton("Present", { present.openIt(p); present.present() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            Box(Modifier.onGloballyPositioned { moreAt = it.positionInWindow() }) {
                com.kaiharimoto.neue.kit.IconButton(Icons.More, {
                    h.neue.menu = com.kaiharimoto.neue.kit.MenuSpec(
                        androidx.compose.ui.geometry.Offset(moreAt.x, moreAt.y + 30f),
                        listOf(
                            com.kaiharimoto.neue.kit.MenuEntry("Duplicate") { present.duplicate(p) },
                            com.kaiharimoto.neue.kit.MenuEntry("Delete", danger = true, separatorBefore = true) { present.confirmDelete = p },
                        ),
                    )
                }, label = "More")
            }
        }
    }
}

/**
 * A new presentation: a deck profile of a saved deck — its style, theme and camera chosen up
 * front, its skeleton made from the deck's groups — or a blank one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewPresentationDialog(h: NeueHolders) {
    val present = h.present
    val neue = h.neue
    val prefs = neue.prefs.present
    val scope = rememberCoroutineScope()
    var decks by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(Unit) { decks = h.deps.deckRepository.all().sortedBy { it.entry.name.lowercase() } }
    var deck by remember { mutableStateOf<StoredDeck?>(null) }
    LaunchedEffect(decks) { if (deck == null) deck = decks.firstOrNull { it.entry.id == h.builder.deckId } ?: decks.firstOrNull() }
    var style by remember { mutableStateOf(prefs.style) }
    // Master UI unless the person has picked another theme themselves (kai, 1.0.72).
    val appDark = neue.prefs.theme == com.kaiharimoto.mastertool.core.prefs.NeueTheme.INK
    var theme by remember { mutableStateOf(prefs.startTheme(appDark)) }
    var themeChosen by remember { mutableStateOf(prefs.themeChosen) }
    var camera by remember { mutableStateOf(prefs.webcam) }
    var preset by remember { mutableStateOf(prefs.webcamPreset) }
    var creator by remember { mutableStateOf(prefs.creator) }
    var name by remember { mutableStateOf("") }
    val c = Mu.colors

    fun create(blank: Boolean, withAi: Boolean = false) {
        val chosen = deck
        val now = System.currentTimeMillis()
        val snapshot = if (blank || chosen == null) null else {
            val groups = DeckGroupsCodec.read(chosen.extended).groups
            val ids = (chosen.entry.deck.main + chosen.entry.deck.extra + chosen.entry.deck.side).map { it.value }.toSet()
            PresentEdits.snapshot(
                chosen.entry.deck, groups, chosen.entry.name, chosen.entry.id,
                neue.prefs.groupArrangement,
                neue.prefs.arts.filterKeys { it in ids },
                GroupMarkers.palettes.indexOfFirst { it.id == neue.prefs.groupPalette }.coerceAtLeast(0),
                now,
            )
        }
        val webcam = WebcamZone(enabled = camera, preset = preset)
        val title = name.ifBlank { snapshot?.name?.let { "$it deck profile" } ?: "Untitled presentation" }
        val p = if (snapshot == null) {
            Presentation(present.newId(), title, now, now, style, null, theme, webcam = webcam, creator = creator, slides = listOf(SlideLayouts.slide(SlideLayouts.TITLE)))
        } else {
            PresentEdits.newProfile(present.newId(), title, snapshot, style, theme, webcam, creator, now)
        }
        neue.update { it.copy(present = PresentPrefs(style, theme, camera, preset, creator, p.id, themeChosen = themeChosen)) }
        present.creating = false
        present.create(p)
        if (withAi) present.briefing = true
    }

    MuDialog(
        "New deck profile",
        { present.creating = false },
        width = 720.dp,
        description = "Pick the deck and how to tell it. Every slide it makes is yours to change.",
        footer = {
            MuButton("Blank presentation", { create(blank = true) }, variant = BtnVariant.GHOST)
            if (neue.prefs.ai.enabled) MuButton("Build with ${h.ai.name}", { create(blank = false, withAi = true) }, enabled = deck != null, reason = "Save a deck first")
            MuButton("Make it", { create(blank = false) }, variant = BtnVariant.PRIMARY, enabled = deck != null, reason = "Save a deck first")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Deck")
            if (decks.isEmpty()) Help("No saved decks yet: save one on the builder, or start a blank presentation.")
            else MuSelect(deck, decks, { it?.entry?.name ?: "None" }, { deck = it }, Modifier.fillMaxWidth())
            FieldLabel("Name", hint = "optional")
            MuInput(name, { name = it }, Modifier.fillMaxWidth(), placeholder = deck?.entry?.name?.let { "$it deck profile" } ?: "Deck profile")
            FieldLabel("How the deck is told")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Presentation.STYLES.forEach { st ->
                    StyleChoice(st, st == style, { style = st }, Modifier.weight(1f))
                }
            }
            FieldLabel("Look", hint = "Master UI unless you choose another")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Themes.all.forEach { t ->
                    val master = t.id == Themes.MASTER || t.id == Themes.MASTER_DARK
                    ThemeSwatch(
                        t, t.id == theme, {
                            theme = t.id
                            // Picking the default back is not a choice: the next profile follows the app again.
                            themeChosen = !master
                        },
                        Modifier.width(96.dp),
                        caption = if (master) "Default" else null,
                    )
                }
            }
            FieldLabel("Webcam", hint = "the slides make room for it")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuSwitch(camera, { camera = it })
                if (camera) MuSelect(preset, WebcamZone.PRESETS.filter { it != WebcamZone.CUSTOM }, WebcamZone::presetName, { preset = it }, Modifier.width(200.dp), small = true)
                else Small("Off", color = c.ink45)
            }
            FieldLabel("Your name", hint = "for the title slide")
            MuInput(creator, { creator = it }, Modifier.fillMaxWidth(), placeholder = "Channel or handle")
        }
    }
}

/** One of the three styles, drawn as a tiny diagram of what it does. */
@Composable
private fun StyleChoice(style: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Mu.colors
    Column(
        modifier.border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.ink25)
            .cursorPointer(label = Presentation.styleName(style))
            .muClickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StyleDiagram(style, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Micro(Presentation.styleName(style), color = c.ink)
        Small(Presentation.styleLine(style), color = c.ink70, maxLines = 3)
    }
}

@Composable
internal fun StyleDiagram(style: String, modifier: Modifier) {
    val c = Mu.colors
    androidx.compose.foundation.Canvas(modifier.background(c.ink06)) {
        val cols = 10
        val rows = 4
        val pad = size.width * 0.08f
        val cw = (size.width - pad * 2) / cols
        val ch = (size.height - pad * 2) / rows
        when (style) {
            Presentation.STYLE_SLIDES -> {
                val h = size.height * 0.7f
                val w = h * 0.69f
                drawRect(c.ink, androidx.compose.ui.geometry.Offset((size.width - w) / 2f, (size.height - h) / 2f), androidx.compose.ui.geometry.Size(w, h))
            }
            Presentation.STYLE_BUILD_UP -> {
                for (i in 0 until 14) {
                    val x = pad * 2 + (i % 7) * cw * 1.3f
                    val y = pad * 2 + (i / 7) * ch * 1.3f
                    drawRect(if (i >= 10) c.ink25 else c.ink, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cw * 1.1f, ch * 1.1f))
                }
            }
            else -> {
                for (r in 0 until rows) for (k in 0 until cols) {
                    val lit = r == 1 && k in 2..5
                    drawRect(if (lit) c.ink else c.ink25, androidx.compose.ui.geometry.Offset(pad + k * cw + 1f, pad + r * ch + 1f), androidx.compose.ui.geometry.Size(cw - 2f, ch - 2f))
                }
            }
        }
    }
}
