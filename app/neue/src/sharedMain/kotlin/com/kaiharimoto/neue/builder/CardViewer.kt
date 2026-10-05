package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MenuColumn
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuType

/**
 * A card opened large, in the middle of the window — a right-hold, or Space on
 * the selection. The art as tall as the window allows, and beside it
 * everything the inspector says, in larger type, with everything that can be
 * done to the card where the menu used to be.
 *
 * It leaves the way every overlay does: a click outside it, Esc, or the ✕.
 */
@Composable
fun CardViewer(state: DeckBuilderState, neue: NeueState) {
    val viewing = neue.viewing ?: return
    val c = Mu.colors
    val close = { neue.viewing = null }
    val shown = remember(viewing) { Animatable(0f) }
    LaunchedEffect(viewing) { shown.animateTo(1f, tween(MuMotion.FAST, easing = MuMotion.ease)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(c.overlay)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        val card = viewing.card
        // Taller than wide (a phone, v1.3.5): the art over the details, and the whole sheet
        // scrolls; there is no width for the two side by side.
        if (maxHeight > maxWidth) {
            val pad = 16.dp
            val artWidth = min(maxWidth - pad * 4, (maxHeight * 0.5f) * CARD_RATIO)
            val sheet = maxHeight - 24.dp - pad * 2
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .background(c.paper)
                    .border(1.dp, c.ink)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(pad), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // The text first (1.0.88, as the inspector): the name, the art in the height the words
                    // leave of the sheet, the text whole — then what to do with the card.
                    val text = card.description.ifBlank { "No card text." }
                    TextFirstCard(
                        room = sheet,
                        text = text,
                        style = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, lineHeight = 23.sp),
                        artGap = 16.dp,
                        headGap = 16.dp,
                        headFirst = true,
                        artMax = artWidth / CARD_RATIO,
                        head = {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.weight(1f).padding(end = 12.dp)) { CardHeading(card, standing = state.standingOf(card)) }
                                // Full screen, turning with the phone (v1.3.6).
                                IconButton(Icons.Maximize, { neue.showcase = card }, size = 40.dp, label = "Full screen")
                                IconButton(Icons.X, close, size = 40.dp, label = "Close")
                            }
                        },
                        art = { NeueCard(card = card, modifier = Modifier.fillMaxSize(), format = state.format, foil = neue.prefs.foil, marks = state.marks) },
                        body = { style -> SelectionContainer { MuText(text, style = style, color = c.ink) } },
                    )
                    HRule(color = c.ink)
                    Micro("Do", color = c.ink70)
                    MenuColumn(entriesFor(viewing, state, neue), onDismiss = close, modifier = Modifier.fillMaxWidth())
                    HRule()
                    ArtSwitch(card, neue)
                    CardTags(card, state)
                    HRule()
                    Copies(card, state)
                }
                ScrollbarFor(scroll)
            }
            return@BoxWithConstraints
        }
        val pad = 32.dp
        val details = min(480.dp, maxWidth * 0.4f)
        // As tall as the window allows, unless the window is too narrow for that and the details beside it.
        val artHeight = min(maxHeight * 0.88f - pad * 2, (maxWidth * 0.94f - details - pad * 3) / CARD_RATIO)
        val artWidth = artHeight * CARD_RATIO
        Row(
            Modifier
                .background(c.paper)
                .border(1.dp, c.ink)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(pad),
            horizontalArrangement = Arrangement.spacedBy(pad),
        ) {
            NeueCard(
                card = card,
                modifier = Modifier.size(artWidth, artHeight),
                format = state.format,
                marks = state.marks,
                foil = neue.prefs.foil,
            )
            Box(Modifier.width(details).height(artHeight)) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f).padding(end = 16.dp)) { CardHeading(card, large = true, standing = state.standingOf(card)) }
                        Tip("Full screen: the card as large as it goes, turning with the pointer or the tablet") {
                            IconButton(Icons.Maximize, { neue.showcase = card }, size = 32.dp, label = "Full screen")
                        }
                        IconButton(Icons.X, close, size = 32.dp, label = "Close")
                    }
                    SelectionContainer {
                        MuText(
                            card.description.ifBlank { "No card text." },
                            style = MuType.body(LocalMuFonts.current).copy(fontSize = 16.sp, lineHeight = 25.sp),
                            color = c.ink,
                        )
                    }
                    HRule(color = c.ink)
                    ArtSwitch(card, neue)
                    CardTags(card, state)
                    HRule()
                    Copies(card, state)
                    HRule()
                    Micro("Do", color = c.ink70)
                    MenuColumn(entriesFor(viewing, state, neue), onDismiss = close, modifier = Modifier.fillMaxWidth())
                }
                ScrollbarFor(scroll)
            }
        }
    }
}

/**
 * The menu for where the card was opened from. A deck position can have moved
 * since (the viewer does not stop the deck being edited), so it is checked, and
 * found again by the card when it has.
 */
private fun entriesFor(viewing: Viewing, state: DeckBuilderState, neue: NeueState) = run {
    val section = viewing.section
    if (section == null) return@run CardActions.poolMenu(viewing.card, state, neue)
    val ids = state.deck[section]
    val index = viewing.index.takeIf { ids.getOrNull(it) == viewing.card.id } ?: ids.indexOf(viewing.card.id)
    if (index < 0) CardActions.poolMenu(viewing.card, state, neue) else CardActions.deckMenu(viewing.card, section, index, state, neue)
}

