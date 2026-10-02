package com.kaiharimoto.neue.present

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.present.ai.RestyleBrief
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch

/**
 * Restyle (1.0.72, kai: "a 'Restyle' button that lets Ai do it based on the user's input"): the
 * look described in the person's words — a suggestion to start from, the whole presentation or this
 * slide, readability held, a picture to take the colors from — then a restyle conversation that
 * changes the look and never the content. What it was before is kept for Undo restyle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RestyleDialog(h: NeueHolders) {
    val present = h.present
    if (!present.restyling) return
    val p = present.open ?: run { present.restyling = false; return }
    val ai = h.ai
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf("") }
    var onlyThis by remember { mutableStateOf(false) }
    var readable by remember { mutableStateOf(true) }
    val c = Mu.colors
    val picture = ai.attached.isNotEmpty()

    fun go() {
        val slide = present.slide
        present.restyling = false
        present.restyleBefore = p
        ai.restyle(
            RestyleBrief(
                presentationId = p.id,
                ask = ask,
                slideId = if (onlyThis) slide?.id else null,
                slideNumber = present.slideIndex + 1,
                readable = readable,
                picture = picture,
            ),
        )
    }

    MuDialog(
        "Restyle",
        { present.restyling = false },
        width = 600.dp,
        description = "${ai.name} changes how the slides look — the theme, colors, faces and backgrounds — and never the words, cards or notes. Undo restyle puts it all back.",
        footer = {
            MuButton("Cancel", { present.restyling = false }, variant = BtnVariant.GHOST)
            MuButton("Restyle", { go() }, variant = BtnVariant.PRIMARY, enabled = !ai.attaching && (ask.isNotBlank() || picture), reason = if (ai.attaching) "The picture is still being read" else "Describe the look, or add a picture")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Describe the look")
            MuInput(ask, { ask = it }, Modifier.fillMaxWidth(), placeholder = "Black and lime like my channel, big bold titles", onSubmit = { if (ask.isNotBlank()) go() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RestyleBrief.SUGGESTIONS.forEach { idea -> Tag(idea, ask == idea, { ask = idea }) }
            }
            FieldLabel("For")
            Segmented(onlyThis, listOf(false, true), { if (it) "This slide" else "Whole presentation" }, { onlyThis = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuSwitch(readable, { readable = it })
                Column {
                    Small("Keep it easy to read", color = c.ink)
                    Small("Every word at 4.5 to 1 against what is behind it", color = c.ink45)
                }
            }
            FieldLabel("A picture", hint = "optional: your logo, banner or channel")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuButton(if (picture) "Another picture" else "Add a picture", {
                    scope.launch { Platform.pick("A picture to take the colors from", PICTURE_EXTENSIONS)?.let { ai.attach(it) } }
                }, size = BtnSize.SM, icon = Icons.Image)
                Small(
                    when {
                        ai.attaching -> "Reading the picture…"
                        picture -> "${ai.attached.size} attached: ${ai.name} takes its colors from it"
                        else -> "None"
                    },
                    color = c.ink45,
                )
            }
        }
    }
}

/**
 * When a restyle conversation ends, the way back: a note that puts the presentation as it was
 * before the restyle, in one step of Undo.
 */
@Composable
internal fun RestyleWatcher(h: NeueHolders) {
    val present = h.present
    LaunchedEffect(Unit) {
        var was = false
        snapshotFlow { h.ai.running to present.restyleBefore }.collect { (running, before) ->
            if (was && !running && before != null) {
                h.neue.note = Note("Restyled", action = "Undo restyle", lastsMs = 12_000, onAction = { present.undoRestyle() })
            }
            was = running && before != null
        }
    }
}
