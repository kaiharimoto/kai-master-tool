package com.kaiharimoto.neue.shot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.art.ArtLibrary
import kotlinx.coroutines.CoroutineScope

actual class DeckShots actual constructor(art: ArtLibrary, scope: CoroutineScope) {
    actual var taking by mutableStateOf(false)
        private set

    actual fun export(state: DeckBuilderState, neue: NeueState) {
        neue.note = Note("The deck picture comes to the tablet in the next build")
    }
}
