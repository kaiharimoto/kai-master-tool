package com.kaiharimoto.neue.present

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.layout.Revealed
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.platform.pastedPicture
import com.kaiharimoto.neue.present.play.PresentStage
import com.kaiharimoto.neue.present.play.PresenterConsole

/** Present's keys (1.0.70, `DeskShortcuts`' Making slides and Presenting rows), run on the window's holders. */
internal fun runPresent(h: NeueHolders, action: DeskAction) {
    val present = h.present
    if (present.open == null && action != DeskAction.GO_PRESENT) return
    when (action) {
        DeskAction.PRESENT_START -> present.present(0)
        DeskAction.PRESENT_FROM_HERE -> present.present(present.slideIndex)
        DeskAction.SLIDE_NEW -> addSlide(h, SlideLayouts.TITLE_BODY)
        DeskAction.PRESENT_DUPLICATE -> duplicate(h)
        DeskAction.PRESENT_COPY -> copy(h)
        DeskAction.PRESENT_CUT -> cut(h)
        DeskAction.PRESENT_PASTE -> if (present.clipboard.isNotEmpty() || present.clipboardSlides.isNotEmpty()) {
            paste(h)
        } else {
            // A picture on the system clipboard (a screenshot, a logo) comes in as a picture.
            present.launch { pastedPicture()?.let { addPictureBytes(h, it.bytes, it.extension) } }
        }
        DeskAction.PRESENT_SELECT_ALL -> selectAll(h)
        DeskAction.PRESENT_GROUP -> group(h, true)
        DeskAction.PRESENT_UNGROUP -> group(h, false)
        DeskAction.BRING_FORWARD -> reorder(h, PresentEdits.FORWARD)
        DeskAction.SEND_BACKWARD -> reorder(h, PresentEdits.BACKWARD)
        DeskAction.BRING_TO_FRONT -> reorder(h, PresentEdits.FRONT)
        DeskAction.SEND_TO_BACK -> reorder(h, PresentEdits.BACK)
        DeskAction.NUDGE_LEFT -> nudge(h, -2f, 0f)
        DeskAction.NUDGE_RIGHT -> nudge(h, 2f, 0f)
        DeskAction.NUDGE_UP -> nudge(h, 0f, -2f)
        DeskAction.NUDGE_DOWN -> nudge(h, 0f, 2f)
        DeskAction.NUDGE_LEFT_FAR -> nudge(h, -20f, 0f)
        DeskAction.NUDGE_RIGHT_FAR -> nudge(h, 20f, 0f)
        DeskAction.NUDGE_UP_FAR -> nudge(h, 0f, -20f)
        DeskAction.NUDGE_DOWN_FAR -> nudge(h, 0f, 20f)
        DeskAction.TEXT_BOLD -> bold(h)
        DeskAction.TEXT_ITALIC -> italic(h)
        DeskAction.TEXT_UNDERLINE -> underline(h)
        DeskAction.PRESENT_NEXT -> present.next()
        DeskAction.PRESENT_PREVIOUS -> present.previous()
        DeskAction.PRESENT_FIRST -> present.first()
        DeskAction.PRESENT_LAST -> present.last()
        DeskAction.PRESENT_DECK -> present.toggleOverview()
        DeskAction.PRESENT_BLACK -> present.blank("B")
        DeskAction.PRESENT_WHITE -> present.blank("W")
        DeskAction.PRESENT_LASER -> present.toggleLaser()
        DeskAction.PRESENT_PEN -> present.togglePen()
        DeskAction.PRESENT_CLEAR_INK -> present.clearInk()
        DeskAction.PRESENT_NOTES -> present.toggleNotes()
        else -> Unit
    }
}

/**
 * Esc and Back on Present, before the window's chain: a presentation playing ends; words being
 * edited in place are let go; a selection is cleared; and Back leaves the editor for the library.
 * Returns whether it did anything.
 */
internal fun dismissPresent(h: NeueHolders, esc: Boolean): Boolean {
    val present = h.present
    if (present.playing != null) {
        if (h.neue.menu != null) return false
        present.stop()
        return true
    }
    if (h.neue.page != Page.PRESENT || h.neue.hasTop || h.overlays.isOpen) return false
    if (present.pickingCards != null) { present.pickingCards = null; return true }
    if (present.creating) { present.creating = false; return true }
    if (present.addingModule != null) { present.addingModule = null; return true }
    if (present.briefing) { present.briefing = false; return true }
    if (present.restyling) { present.restyling = false; return true }
    if (present.exporting != null) { present.exporting = null; return true }
    if (present.confirmDelete != null) { present.confirmDelete = null; return true }
    if (present.editingText != null) { present.editingText = null; h.focus?.clearFocus(); return true }
    if (present.selection.isNotEmpty()) { present.selection = emptySet(); return true }
    if (!esc && present.open != null) { present.close(); return true }
    return false
}

/** The presenter over the whole window while a presentation plays, in immersive mode for its length. */
@Composable
internal fun PresentOverlay(h: NeueHolders) {
    val pl = h.present.playing ?: return
    val ctx = rememberSlideContext(h, pl.show.presentation)
    // Full screen for the show, and back as it was after.
    val wasImmersive = remember(pl) { h.neue.immersive }
    DisposableEffect(pl) {
        if (!h.neue.immersive) {
            h.neue.immersive = true
            h.neue.revealed = Revealed.NONE
        }
        onDispose { if (!wasImmersive) h.neue.immersive = false }
    }
    if (h.present.audience && h.present.screens > 1) PresenterConsole(h, ctx) else PresentStage(h.present, ctx)
}
