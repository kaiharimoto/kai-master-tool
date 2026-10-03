package com.kaiharimoto.neue.builder

import kotlinx.coroutines.launch
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.LocalTouchFirst
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.zen.zenDeep
import com.kaiharimoto.neue.zen.zenQuiet
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Stat
import com.kaiharimoto.neue.kit.Stepper
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * The card under the pointer, or the one last clicked — the desktop's answer
 * to the tablet's long-press sheet. A large display can afford to keep it open,
 * so reading a card costs a hover rather than a gesture.
 *
 * The picture first, then what the card says — its name, its numbers and its
 * text — then two sections that fold shut and stay shut: the facets, and the
 * copies in the deck. (1.0.9 put the words above the picture; kai moved the
 * picture back.) The text is what must be read whole (1.0.88): the picture takes
 * the height the words leave, and the text steps down a size only once the picture
 * is at its least (`TextFirstCard`).
 */
@Composable
fun Inspector(state: DeckBuilderState, neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val card = neue.inspected
    Box(modifier.zenDeep()) {
        // Hidden from where it stands (kai, 1.0.19), rather than from the window's bar.
        // Drawn last, in the corner, so it costs the picture nothing.
        val touch = neue.touchFirst
        val hide = @Composable {
            // On a tablet the top-right corner is Full screen's, and the screen's edge the
            // system's (touch swarm, rec 16): the button stands at the bottom left, 32 wide.
            Box(Modifier.fillMaxSize().zenQuiet(), contentAlignment = if (touch) Alignment.BottomStart else Alignment.TopEnd) {
                Tip("Hide the inspector", kbd = DeskShortcuts.chordFor(DeskAction.TOGGLE_INSPECTOR)?.let(DeskShortcuts::kbd)) {
                    // 20 px, 2 in from the corner: inside the 24 px margin, clear of the art.
                    IconButton(
                        Icons.PanelRightClose,
                        { neue.update { it.copy(inspectorVisible = false) } },
                        Modifier.padding(if (touch) 8.dp else 2.dp),
                        size = if (touch) 32.dp else 20.dp,
                        label = "Hide inspector",
                    )
                }
            }
        }
        if (card == null) {
            hide()
            Column(Modifier.zenQuiet().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.kaiharimoto.neue.kit.MuText("Nothing here.", style = MuType.h1(LocalMuFonts.current))
                Body(
                    com.kaiharimoto.mastertool.core.input.DeskWords.inspectorEmpty(LocalTouchFirst.current),
                    color = c.ink70,
                )
            }
        } else {
            InspectedCard(card, state, neue)
            hide()
        }
    }
}

/**
 * The card itself: its picture and its words, fitted so the text is read whole without scrolling
 * (1.0.88, `TextFirstCard`), then the folds below them — below the fold too, where the window is short.
 */
@Composable
private fun InspectedCard(card: Card, state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // What is seen without scrolling: the column less its top padding and a margin at its foot —
        // on a tablet the margin is the Hide button's corner, which stands over the column's last lines.
        val room = if (constraints.hasBoundedHeight) maxHeight - 20.dp - (if (neue.touchFirst) 48.dp else 16.dp) else null
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
            // A picture dropped on the card is its own art, cropped in (1.0.34).
            val custom = com.kaiharimoto.neue.art.LocalCustomArt.current
            val fetching = androidx.compose.runtime.rememberCoroutineScope()
            val drop = remember(card) {
                object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                    override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                        val picked = com.kaiharimoto.neue.platform.droppedPicture(event)
                        if (picked != null) {
                            neue.cropping = com.kaiharimoto.neue.art.ArtCropping(card, picked)
                            return true
                        }
                        // An image dragged out of a browser is its address: fetched, then cropped in (1.0.89).
                        val link = com.kaiharimoto.neue.platform.droppedLink(event) ?: return false
                        fetching.launch {
                            com.kaiharimoto.neue.platform.fetchPicture(link)?.let { neue.cropping = com.kaiharimoto.neue.art.ArtCropping(card, it) }
                                ?: run { neue.note = com.kaiharimoto.neue.Note("That picture could not be fetched. Save it, then drop the file") }
                        }
                        return true
                    }
                }
            }
            // In zen the card stays a moment longer than what is written about it.
            TextFirstCard(
                room = room,
                text = card.description.ifBlank { "No card text." },
                style = MuType.body(LocalMuFonts.current),
                artGap = 20.dp,
                headGap = 16.dp,
                head = { Box(Modifier.zenQuiet()) { CardHeading(card) } },
                art = {
                    NeueCard(
                        card = card,
                        modifier = Modifier.fillMaxSize().let { base ->
                            if (custom == null) base else base.dragAndDropTarget(shouldStartDragAndDrop = { com.kaiharimoto.neue.platform.mayBePicture(it) }, target = drop)
                        },
                        format = state.format,
                        foil = neue.prefs.foil,
                    )
                },
                body = { style ->
                    Box(Modifier.zenQuiet()) {
                        SelectionContainer { com.kaiharimoto.neue.kit.MuText(card.description.ifBlank { "No card text." }, style = style, color = c.ink) }
                    }
                },
            )
            Column(Modifier.zenQuiet().padding(top = 20.dp)) {
                Fold("Details", "details", neue) { CardTags(card, state) }
                Fold("In the deck", "deck", neue) { Copies(card, state) }
                // The artwork last (1.0.42, kai: "not vital to deckbuilding"): which picture, and your own.
                Fold("Artwork", "art", neue) { ArtSwitch(card, neue) }
            }
        }
        Box(Modifier.matchParentSize().zenQuiet()) { ScrollbarFor(scroll) }
    }
}


/**
 * Which of a card's artworks is drawn, for a card printed with more than one
 * (kai, 1.0.14): `Art 2 of 3` and a step either way, wrapping. The choice is
 * the card's everywhere — pool, deck, library, screenshot — and is only ever a
 * picture: the deck keeps the passcode it was built with (`CardArt`).
 */
@Composable
internal fun ArtSwitch(card: Card, neue: NeueState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val custom = com.kaiharimoto.neue.art.LocalCustomArt.current
    // Read, so adding or removing a picture redraws the count.
    val own = custom?.version
    val arts = remember(card, own) { neue.artChoices(card) }
    val chosen = neue.prefs.arts[card.id.value]?.takeIf { it in arts } ?: card.id.value
    Row(modifier.fillMaxWidth().height(if (neue.touchFirst) 40.dp else 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Art", color = c.ink70)
        Mono(if (arts.size > 1) "${arts.indexOf(chosen) + 1} of ${arts.size}" else "1", Modifier.weight(1f), color = c.ink)
        // A card whose reprints share its passcode has one picture in the pool; the
        // person may add their own (1.0.18, `CustomArt`).
        // On a tablet Remove is on the card's hold menu, not beside the arrows; either way
        // it asks first, since it deletes the file (touch swarm, rec 24).
        val touch = neue.touchFirst
        if (custom != null) {
            if (chosen < 0 && !touch) {
                MicroLink("Remove", { neue.confirmRemoveArt = card to -chosen })
            }
            // A picture picked, dropped or pasted, cropped into the art box or kept whole (1.0.34).
            val add = { neue.cropping = com.kaiharimoto.neue.art.ArtCropping(card) }
            if (touch) MuButton("+ Your own", add, variant = BtnVariant.GHOST, size = BtnSize.SM) else MicroLink("+ Your own", add)
        }
        if (arts.size > 1) {
            val arrow = if (touch) com.kaiharimoto.mastertool.core.input.TouchMetrics.ICON.dp else 28.dp
            IconButton(Icons.ChevronLeft, { neue.stepArt(card, -1) }, size = arrow, label = "Previous art")
            IconButton(Icons.ChevronRight, { neue.stepArt(card, 1) }, size = arrow, label = "Next art")
        }
    }
}

/**
 * A section of the inspector with a rule and a name over it, which a click
 * folds shut. Which are shut is a preference, so it stays the way it was left.
 */
@Composable
private fun Fold(title: String, key: String, neue: NeueState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Mu.colors
    val open = key !in neue.prefs.inspectorFolded
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Column(modifier.fillMaxWidth()) {
        HRule(color = c.ink25)
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .hoverable(source)
                .cursorPointer(caption = if (open) "Fold" else "Unfold")
                .clickable(interactionSource = source, indication = null) {
                    neue.update { p -> p.copy(inspectorFolded = if (open) p.inspectorFolded + key else p.inspectorFolded - key) }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro(title, Modifier.weight(1f), color = if (hovered) c.ink else c.ink70)
            Mono(if (open) "−" else "+", color = if (hovered) c.ink else c.ink70)
        }
        if (open) Box(Modifier.fillMaxWidth().padding(bottom = 20.dp)) { content() }
    }
}

/** The card's name, what it is, and its numbers. */
@Composable
internal fun CardHeading(card: Card, large: Boolean = false) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (large) {
            com.kaiharimoto.neue.kit.MuText(card.name, style = MuType.h1(LocalMuFonts.current).copy(lineHeight = 38.sp), maxLines = 3)
        } else {
            H2(card.name, maxLines = 3)
        }
        Micro(card.type, color = c.ink70, maxLines = 2)
        if (card.category == CardCategory.MONSTER) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (large) 40.dp else 28.dp), modifier = Modifier.padding(top = 4.dp)) {
                card.level?.let { Stat(if (card.frameType.contains("xyz")) "Rank" else "Level", it.toString()) }
                card.linkValue?.let { Stat("Link", it.toString()) }
                Stat("ATK", card.atk?.toString() ?: "?")
                if (card.linkValue == null) Stat("DEF", card.def?.toString() ?: "?")
                card.pendulumScale?.let { Stat("Scale", it.toString()) }
            }
        }
    }
}

/** What the card is filed under — each a question, click it to search the pool by it — and its standing on the list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardTags(card: Card, state: DeckBuilderState) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            card.race?.let { race -> Tag(race, false, { state.onFilterChange(CardFilter(races = setOf(race), format = state.format)) }, caption = "Search") }
            if (card.category == CardCategory.MONSTER) {
                val attribute = card.attribute
                Tag(attribute.name.lowercase().replaceFirstChar { it.uppercase() }, false, {
                    state.onFilterChange(CardFilter(attributes = setOf(attribute), format = state.format))
                }, caption = "Search")
            }
            card.archetype?.let { archetype -> Tag(archetype, false, { state.onFilterChange(CardFilter(archetypes = setOf(archetype), format = state.format)) }, caption = "Search") }
        }
        val ban = card.banStatus(state.format)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(state.format.name)
            when (ban) {
                BanStatus.UNLIMITED -> Mono("Unlimited", color = c.ink70)
                BanStatus.FORBIDDEN -> Badge("✕ Forbidden", inverted = true)
                BanStatus.LIMITED -> Badge("Limited · 1", inverted = true)
                BanStatus.SEMI_LIMITED -> Badge("Semi-limited · 2", inverted = true)
            }
        }
    }
}

/** Copies in each section it can go in, and what they buy: the chance of opening one. */
@Composable
internal fun Copies(card: Card, state: DeckBuilderState) {
    val c = Mu.colors
    val home = card.requiredSection()
    val limit = DeckEditor.copyLimit(card, state.format)
    val total = state.copiesInDeck(card.id)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("Copies", Modifier.weight(1f))
            Mono("$total of $limit", color = if (total > limit) c.ink else c.ink70)
        }
        listOf(home, DeckSection.SIDE).forEach { section ->
            val count = state.copiesIn(card.id, section)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowText("${section.displayName} deck", Modifier.weight(1f))
                Stepper(
                    count = count,
                    onChange = { state.setCount(card, section, it) },
                    max = (count + (limit - total)).coerceAtLeast(count).coerceAtMost(3),
                )
            }
        }
        val inMain = state.copiesIn(card.id, DeckSection.MAIN)
        val size = state.deck.main.size
        if (home == DeckSection.MAIN && inMain > 0 && size > 0) {
            Help(
                "Opening hand in $size cards · ${percent(state.mainStatistics.openingHandOdds(inMain, 5))} going first · " +
                    "${percent(state.mainStatistics.openingHandOdds(inMain, 6))} going second",
                color = c.ink70,
            )
        }
        val groups = state.groups.ordered()
        if (groups.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Micro("Group", Modifier.weight(1f))
                val current = state.groups.groupOf(card.id)
                Box(Modifier.background(current?.let { id -> state.groups.byId(id) }?.let { GroupMarkers.hue(it.color) } ?: c.paper).padding(start = 4.dp)) {
                    MuSelect(
                        value = current,
                        options = listOf<String?>(null) + groups.map { it.id },
                        label = { id -> id?.let { state.groups.byId(it)?.name } ?: "No group" },
                        onSelect = { state.assignCardToGroup(card.id, it) },
                        small = true,
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }
        }
    }
}
