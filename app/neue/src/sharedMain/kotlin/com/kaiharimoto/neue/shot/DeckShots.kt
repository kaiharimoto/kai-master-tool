package com.kaiharimoto.neue.shot

import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.art.ArtLibrary
import kotlinx.coroutines.CoroutineScope

/**
 * The deck as a picture (`Ctrl Shift S`, 1.0.10): main, extra and side with none
 * of the window, drawn offscreen at 2× from the originals and saved where the
 * person says. Each platform draws and saves its own way; the desktop's is
 * `DeckShot`, Skia offscreen.
 */
expect class DeckShots(art: ArtLibrary, scope: CoroutineScope) {
    /** Taking the picture: seconds, with the full-size art to load. The cursor shows it busy. */
    var taking: Boolean
        private set

    fun export(state: DeckBuilderState, neue: NeueState)
}
