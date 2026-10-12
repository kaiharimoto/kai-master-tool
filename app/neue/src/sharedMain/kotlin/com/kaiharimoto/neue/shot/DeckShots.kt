package com.kaiharimoto.neue.shot

import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.art.ArtLibrary
import kotlinx.coroutines.CoroutineScope

/**
 * The deck as a picture (`Ctrl Shift S`, 1.0.10): main, extra and side with none
 * of the window, drawn offscreen at 2× from the originals and saved where the
 * person says. Each platform draws and saves its own way: the desktop's is
 * `DeckShot.render`, Skia offscreen, saved where the person says; the tablet's and
 * phone's (I.2) is the same drawing recorded out of sight in the window ([Host]),
 * handed to the share sheet.
 */
expect class DeckShots(art: ArtLibrary, scope: CoroutineScope) {
    /** Taking the picture: seconds, with the full-size art to load. The cursor shows it busy. */
    var taking: Boolean
        private set

    fun export(state: DeckBuilderState, neue: NeueState)

    /** Where the picture is drawn out of sight, on a platform that draws it in the window; nothing on the desk. */
    @Composable
    fun Host()
}
