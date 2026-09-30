package com.kaiharimoto.neue.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.vision.Vision
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.platform.droppedPicture
import com.kaiharimoto.neue.platform.mayBePicture
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Pictures in the chat (1.0.55, kai: "enable … image input"): what waits in the composer, what was
 * sent in the transcript, and one opened large. A picture is content, so it keeps its colour, like
 * card art; everything around it is paper and ink.
 */

/** A picture the conversation holds, read from its file off the main thread. */
@Composable
private fun rememberPicture(ai: AiState, image: Part.Image): ImageBitmap? {
    var bitmap by remember(image.file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image.file) {
        bitmap = withContext(Dispatchers.IO) { ai.files.imageBytes(image.file)?.let(::decodePicture) }
    }
    return bitmap
}

/** One picture as a clickable thumbnail [tall] high; a click opens it large. */
@Composable
private fun Thumb(bitmap: ImageBitmap?, tall: androidx.compose.ui.unit.Dp, onOpen: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val ratio = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }?.coerceIn(0.4f, 2.5f) ?: 1f
    Box(
        Modifier
            .height(tall)
            .width(tall * ratio)
            .border(1.dp, animatedColor(if (hovered) c.ink else c.ink12))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .muClickable(interactionSource = source, onClick = onOpen),
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/** The pictures of a message the person sent, on the right above their words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SentPictures(ai: AiState, images: List<Part.Image>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            val bitmap = rememberPicture(ai, image)
            Thumb(bitmap, if (images.size == 1) 160.dp else 96.dp) { ai.pictureOpen = bitmap }
        }
    }
}

/** The pictures waiting to go with the next message, each with a way to take it back out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachedRow(ai: AiState) {
    val c = Mu.colors
    if (ai.attached.isEmpty() && !ai.attaching) return
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ai.attached.forEach { a ->
                Box {
                    Thumb(a.preview, 56.dp) { ai.pictureOpen = a.preview }
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                            .background(animatedColor(if (hovered) c.ink else c.ink70))
                            .hoverable(source)
                            .cursorPointer(caption = "Remove")
                            .muClickable(interactionSource = source) { ai.detach(a) },
                        contentAlignment = Alignment.Center,
                    ) { Mono("×", color = c.paper) }
                }
            }
            if (ai.attaching) Box(Modifier.size(56.dp).border(1.dp, c.ink12), contentAlignment = Alignment.Center) { Micro("Reading", color = c.ink45) }
        }
        if (ai.attached.isNotEmpty() && ai.sight == Vision.Sight.NO) {
            Help("This model may not see pictures. Pick one that can in quick settings.", color = c.ink)
        }
    }
}

/** Opens the file dialog for pictures, and adds what was chosen. */
internal suspend fun choosePicture(ai: AiState) {
    Platform.pick("Choose a picture for ${ai.name}", PICTURE_EXTENSIONS)?.let(ai::attach)
}

/**
 * The whole panel takes a dropped picture (1.0.55), as the art crop does; it lights up while one
 * is carried over it.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.takesPictures(ai: AiState): Modifier {
    val c = Mu.colors
    var over by remember { mutableStateOf(false) }
    val target = remember(ai) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                val file = droppedPicture(event) ?: return false
                ai.attach(file)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) {
                over = true
            }

            override fun onExited(event: DragAndDropEvent) {
                over = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                over = false
            }
        }
    }
    return this
        .dragAndDropTarget(shouldStartDragAndDrop = { mayBePicture(it) }, target = target)
        .border(2.dp, animatedColor(if (over) c.ink else androidx.compose.ui.graphics.Color.Transparent))
}

/** A picture of the chat opened large, over the page. */
@Composable
fun PictureDialog(ai: AiState) {
    val picture = ai.pictureOpen ?: return
    MuDialog(
        title = "Picture",
        onDismiss = { ai.pictureOpen = null },
        width = 960.dp,
        footer = { MuButton("Close", { ai.pictureOpen = null }, variant = BtnVariant.PRIMARY) },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ratio = picture.width.toFloat() / picture.height.coerceAtLeast(1)
            Image(
                picture,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).height(maxWidth / ratio),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
