package com.kaiharimoto.guest

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.kaiharimoto.mastertool.ui.configureImageLoader
import kotlinx.browser.document

/** The Lounge's page (`docs/LOUNGE.md`): the whole window is the app. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Card art over the same stack as the cards, from kai's computer; a browser keeps its own disk cache.
    configureImageLoader(null)
    document.getElementById("loading")?.remove()
    ComposeViewport(document.body!!) { GuestApp() }
}
