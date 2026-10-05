package com.kaiharimoto.neue.world.apps

import com.kaiharimoto.neue.world.CardChip
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.world.type.WorldType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.search.SearchScope
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.apps.AppLinks
import com.kaiharimoto.mastertool.core.world.apps.Tone
import com.kaiharimoto.mastertool.core.world.apps.UiEvent
import com.kaiharimoto.mastertool.core.world.apps.UiNode
import com.kaiharimoto.mastertool.core.world.apps.UiOption
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.ChatCard
import com.kaiharimoto.neue.ai.MarkdownBlock
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.world.type.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.world.type.Help
import com.kaiharimoto.neue.world.type.Micro
import com.kaiharimoto.neue.world.type.MicroLink
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSlider
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.world.type.Small
import com.kaiharimoto.neue.kit.Stepper
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.BoardBody
import com.kaiharimoto.neue.world.desk.deskTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * An app Ai made, drawn (`docs/world/DESKTOP.md` §8.4): its screen, the tree its `view` returned, read by `UiTree` and
 * drawn with Neue's own components, so an app looks like the app it lives in. Nothing here takes a colour, a font, a size
 * or a place from the app: the widget list is the whole vocabulary. A node that would not read says why in its place; a
 * widget from a newer build says so; a call that threw is one line over the app, with its line in `main.js`.
 */

/** The spacing scale `s1`–`s6` (§8.4), in dp; 0 is none. */
private fun space(n: Int): Dp = when (n) {
    0 -> 0.dp
    1 -> 4.dp
    2 -> 8.dp
    3 -> 12.dp
    4 -> 16.dp
    5 -> 24.dp
    else -> 32.dp
}

/** A number as an event carries it: whole numbers as whole. */
private fun num(d: Double): JsonPrimitive = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

private fun fmt(d: Double): String = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else "%.2f".format(d)

/** What a widget sends: the window's [send], with the widget's id. */
private class Sender(val app: String, val send: (id: String, type: String, value: JsonElement) -> Unit, val live: (id: String, value: JsonElement, done: Boolean) -> Unit)

/** The id a widget's events carry, for the avatar to walk to it (`world_app press`, §5.2). */
private fun idOf(n: UiNode): String? = when (n) {
    is UiNode.Button -> n.id.ifEmpty { null }
    is UiNode.Input -> n.id
    is UiNode.Stepper -> n.id
    is UiNode.Slider -> n.id
    is UiNode.Select -> n.id
    is UiNode.Segmented -> n.id
    is UiNode.Toggle -> n.id
    is UiNode.Checks -> n.id
    is UiNode.CardPicker -> n.id
    is UiNode.DeckPicker -> n.id
    is UiNode.Table -> n.id
    is UiNode.Cards -> n.id
    is UiNode.Card -> n.id
    else -> null
}

/**
 * The window of the app [slug] (§8): its screen, its state on disk, its errors and its versions. The desktop's frame
 * (title bar, tile, `by Ai`) stands round it; this is the body.
 */
@Composable
fun AppWindow(h: NeueHolders, slug: String, modifier: Modifier = Modifier) {
    val apps = h.world.apps
    val host = remember(slug, h.world.open?.id) { apps.host(slug) }
    LaunchedEffect(slug) { apps.opened(slug) }
    val c = Mu.colors
    val sender = remember(host) {
        Sender(
            app = com.kaiharimoto.mastertool.core.world.desk.AppRef.Made(slug).key,
            send = { id, type, value -> apps.send(host, id, type, value) },
            live = { id, value, done ->
                val now = System.currentTimeMillis()
                if (done) {
                    host.live.release(id)
                    apps.send(host, id, UiEvent.CHANGE, value)
                } else if (host.live.admit(id, now)) {
                    apps.send(host, id, UiEvent.CHANGE, value)
                }
            },
        )
    }
    Column(modifier.fillMaxSize()) {
        // What went wrong, in words with its line, over the app: never a blank window.
        host.failure?.let { f ->
            Row(
                Modifier.fillMaxWidth().background(c.ink).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MuText(f.words, Modifier.weight(1f), style = WorldType.label(LocalMuFonts.current, LocalPhone.current).copy(fontWeight = FontWeight.Medium), color = c.paper, maxLines = 3)
                MicroLink("Show code", { showCode(h, slug, f.line) }, color = c.paper)
                if (host.offerFresh) MicroLink("Start fresh", { apps.startFresh(slug) }, color = c.paper)
            }
        }
        host.note?.let { n ->
            Row(
                Modifier.fillMaxWidth().border(1.dp, c.ink25).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Help(n, Modifier.weight(1f), color = c.ink70)
                MicroLink("Dismiss", { host.note = null })
            }
        }
        val tree = host.tree
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                host.missing -> EmptyState("No such app.", "It was deleted, or has not reached this device yet.")
                tree == null && host.failure == null -> Help("Starting…", Modifier.padding(16.dp))
                tree == null -> EmptyState("It did not start.", "Its first call failed: the line above says where. Ask ${h.ai.name} to fix it, or open its code.")
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .alpha(if (host.stale) 0.45f else 1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Node(h, tree.root, sender)
                }
            }
        }
    }
}

/** Opens the app's code in the Editor, at [line] where a call failed. */
internal fun showCode(h: NeueHolders, slug: String, line: Int? = null) {
    val world = h.world
    world.saveEditor()
    world.showFile(WorldApps.codePath(slug))
    world.apps.windows.open(com.kaiharimoto.mastertool.core.world.desk.BuiltInApp.EDITOR.ref, WorldEvent.YOU)
    if (line != null) h.neue.note = Note("The error is at line $line")
}

@Composable
private fun Node(h: NeueHolders, n: UiNode, s: Sender, inRow: Boolean = false) {
    // A widget with an id is a place the avatar can go: reported from layout, never composition (§5.2).
    val id = idOf(n)
    if (id != null) {
        Box(Modifier.deskTarget(h, s.app, com.kaiharimoto.mastertool.core.world.desk.Anchor.WIDGET, id)) { Widget(h, n, s, inRow) }
    } else {
        Widget(h, n, s, inRow)
    }
}

@Composable
private fun Widget(h: NeueHolders, n: UiNode, s: Sender, inRow: Boolean) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    when (n) {
        is UiNode.Col -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space(n.gap))) {
            n.children.forEachIndexed { i, k -> key(i) { Node(h, k, s) } }
        }
        is UiNode.Row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(space(n.gap)), verticalAlignment = Alignment.Top) {
            // Every child shares the row by its weight, 1 when it names none (§8.4).
            n.children.forEachIndexed { i, k ->
                key(i) { Box(Modifier.weight((k.weight ?: 1).toFloat())) { Node(h, k, s, inRow = true) } }
            }
        }
        is UiNode.Grid -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space(n.gap))) {
            n.children.chunked(n.columns).forEachIndexed { r, row ->
                key(r) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(space(n.gap))) {
                        row.forEach { k -> Box(Modifier.weight(1f)) { Node(h, k, s, inRow = true) } }
                        repeat(n.columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        is UiNode.Section -> Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(n.title, color = c.ink70)
            HRule()
            n.children.forEachIndexed { i, k -> key(i) { Node(h, k, s) } }
        }
        is UiNode.Divider -> HRule(Modifier.padding(vertical = 4.dp))
        is UiNode.Space -> Spacer(Modifier.height(space(n.size)))
        is UiNode.Text -> {
            val style = if (n.mono) WorldType.mono(f, LocalPhone.current) else WorldType.body(f, LocalPhone.current)
            MuText(
                n.text,
                style = if (n.tone == Tone.STRONG) style.copy(fontWeight = FontWeight.Medium) else style,
                color = if (n.tone == Tone.MUTED) c.ink45 else c.ink,
            )
        }
        is UiNode.Kv -> Column(Modifier.fillMaxWidth()) {
            n.rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Small(k, Modifier.weight(1f), color = c.ink70, maxLines = 2)
                    Mono(v, color = c.ink)
                }
            }
        }
        is UiNode.Stat -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MuText(n.value, style = if (n.value.length <= 8 && !inRow) MuType.display(f) else MuType.h1(f), color = c.ink, maxLines = 1)
            if (n.label.isNotBlank()) MuText(n.label, style = WorldType.body(f, LocalPhone.current).copy(fontWeight = FontWeight.Medium), color = c.ink)
            if (n.note.isNotBlank()) Help(n.note, color = c.ink70)
        }
        is UiNode.Note -> Help(n.text, color = c.ink70)
        is UiNode.Markdown -> AppMarkdown(h, n.text)
        is UiNode.Button -> AppButton(h, n, s)
        is UiNode.Input -> AppInput(n, s)
        is UiNode.Stepper -> Field(n.label, n.hint) {
            // Whole steps on the kit's stepper; a fractional one would need another control, and is shown as the value.
            val step = n.step.coerceAtLeast(1.0)
            Stepper(
                n.value.toInt(),
                { v -> s.send(n.id, UiEvent.CHANGE, num((v.toDouble()).coerceIn(n.min, n.max))) },
                min = n.min.toInt(),
                max = n.max.toInt(),
            )
            if (step != 1.0) Help("steps of ${fmt(step)}")
        }
        is UiNode.Slider -> AppSlider(n, s)
        is UiNode.Select -> Field(n.label, null) {
            val none = UiOption("", "Choose…")
            val opts = if (n.value == null) listOf(none) + n.options else n.options
            MuSelect(opts.firstOrNull { it.value == n.value } ?: none, opts, { it.label }, { o -> if (o.value.isNotEmpty()) s.send(n.id, UiEvent.CHANGE, JsonPrimitive(o.value)) }, Modifier.fillMaxWidth(), small = true)
        }
        is UiNode.Segmented -> Field(n.label, null) {
            Segmented(n.options.firstOrNull { it.value == n.value } ?: n.options.first(), n.options, { it.label }, { o -> s.send(n.id, UiEvent.CHANGE, JsonPrimitive(o.value)) }, small = true)
        }
        is UiNode.Toggle -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MuSwitch(n.value, { v -> s.send(n.id, UiEvent.CHANGE, JsonPrimitive(v)) })
            if (n.label.isNotBlank()) MuText(n.label, style = WorldType.body(f, LocalPhone.current), color = c.ink)
        }
        is UiNode.Checks -> Field(n.label, null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.options.forEach { o ->
                    val on = o.value in n.values
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuCheckbox(on, { v ->
                            val next = if (v) n.values + o.value else n.values - o.value
                            s.send(n.id, UiEvent.CHANGE, JsonArray(next.distinct().map(::JsonPrimitive)))
                        })
                        MuText(o.label, style = WorldType.body(f, LocalPhone.current), color = c.ink)
                    }
                }
            }
        }
        is UiNode.CardPicker -> CardPicker(h, n, s)
        is UiNode.DeckPicker -> DeckPicker(h, n, s)
        is UiNode.Table -> AppTable(h, n, s)
        is UiNode.Cards -> CardStrip(h, n, s)
        is UiNode.Card -> AppCard(h, n, s)
        is UiNode.Board -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (n.title.isNotBlank()) Micro(n.title, color = c.ink70)
            // A chart in an app is the same chart as on a page: the boards' painter, at the window's width.
            val board = remember(n) { Board("app", n.title, n.kind.id, n.payload) }
            BoardBody(h, board, h.world.open?.id.orEmpty(), Modifier.fillMaxWidth().height(260.dp).border(1.dp, c.ink12))
        }
        is UiNode.Progress -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (n.label.isNotBlank()) Small(n.label, color = c.ink70)
            Progress(n.value.toFloat())
        }
        is UiNode.Empty -> Box(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(16.dp)) { Body(n.text, color = c.ink70) }
        is UiNode.Broken -> BrokenLine("${n.kind}: ${n.why}")
        is UiNode.Unknown -> BrokenLine("“${n.kind}” is a widget from a newer version of the app: update to see it.")
    }
}

/** A part of the screen that would not draw, said in its place; the rest of the screen stands. */
@Composable
private fun BrokenLine(text: String) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().border(1.dp, c.ink25).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mono("✕", color = c.ink)
        Small(text, color = c.ink70, maxLines = 3)
    }
}

/** A label over a control, with its hint under. */
@Composable
private fun Field(label: String, hint: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) FieldLabel(label)
        content()
        if (!hint.isNullOrBlank()) Help(hint)
    }
}

/** The chat's markdown; only `world://` addresses are links, under the words, and `[[Card]]` opens a card. */
@Composable
private fun AppMarkdown(h: NeueHolders, text: String) {
    val blocks = remember(text) { ChatMarkdown.parse(text) }
    val links = remember(text) { Regex("""world://[^\s)\]>"']+""").findAll(text).map { it.value }.filter(AppLinks::allowed).distinct().take(8).toList() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { MarkdownBlock(h.ai, it) }
        if (links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                links.forEach { a -> MicroLink(a.removePrefix("world://"), { h.world.browser.show(a) }, color = Mu.colors.ink) }
            }
        }
    }
}

@Composable
private fun AppButton(h: NeueHolders, n: UiNode.Button, s: Sender) {
    MuButton(
        n.label.ifBlank { n.id.ifBlank { "Press" } },
        {
            // Done by the desktop on the person's press, never by the app's code (§8.4).
            n.copy?.let {
                Platform.copy(it)
                h.neue.note = Note("Copied")
            }
            n.open?.let { h.world.browser.show(it) }
            if (n.id.isNotEmpty()) s.send(n.id, UiEvent.PRESS, JsonPrimitive(true))
        },
        variant = if (n.primary) BtnVariant.PRIMARY else BtnVariant.SECONDARY,
        size = BtnSize.SM,
        enabled = !n.disabled,
    )
}

/** A field committed with Enter or on leaving it; a [UiNode.Input.live] one sends as it is typed. Never a password. */
@Composable
private fun AppInput(n: UiNode.Input, s: Sender) {
    var text by remember(n.id, n.value) { mutableStateOf(n.value) }
    fun value(t: String): JsonElement? = if (!n.number) JsonPrimitive(t) else t.trim().toDoubleOrNull()?.let { d ->
        num(d.coerceIn(n.min ?: Double.NEGATIVE_INFINITY, n.max ?: Double.POSITIVE_INFINITY))
    }
    fun commit() {
        if (text != n.value) value(text)?.let { s.send(n.id, UiEvent.CHANGE, it) }
    }
    Field(n.label, n.hint) {
        MuInput(
            text,
            { t ->
                text = t
                if (n.live) value(t)?.let { s.live(n.id, it, false) }
            },
            Modifier.fillMaxWidth(),
            placeholder = n.placeholder,
            mono = n.number,
            onSubmit = { commit() },
            onFocusChange = { focused -> if (!focused) commit() },
        )
    }
}

/** A slider: let go, it sends; a live one also sends as it moves, at most eight a second, the last always delivered. */
@Composable
private fun AppSlider(n: UiNode.Slider, s: Sender) {
    var v by remember(n.id, n.value) { mutableStateOf(n.value.toFloat()) }
    val steps = if (n.step > 0) (((n.max - n.min) / n.step).toInt() - 1).coerceAtLeast(0) else 0
    Field(n.label, n.hint) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MuSlider(
                v,
                { x ->
                    v = x
                    if (n.live) s.live(n.id, num(x.toDouble()), false)
                },
                Modifier
                    .weight(1f)
                    .pointerInput(n.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Final)
                                if (e.type == PointerEventType.Release) s.live(n.id, num(v.toDouble()), true)
                            }
                        }
                    },
                range = n.min.toFloat()..n.max.toFloat(),
                steps = steps,
                name = n.label.ifBlank { null },
                valueText = fmt(v.toDouble()),
            )
            Mono(fmt(v.toDouble()), Modifier.widthIn(min = 40.dp), color = Mu.colors.ink, align = TextAlign.End)
        }
    }
}

/** The pool's own search: a name typed, the nearest cards offered; the app gets a passcode. */
@Composable
private fun CardPicker(h: NeueHolders, n: UiNode.CardPicker, s: Sender) {
    val c = Mu.colors
    val index = h.builder.index
    val chosen = remember(n.value, index.size) { n.value?.let { index.byId(CardId(it))?.name } }
    var query by remember(n.id, n.value) { mutableStateOf(chosen.orEmpty()) }
    var open by remember { mutableStateOf(false) }
    val found = remember(query, open, index.size) {
        if (!open || query.trim().length < 2) emptyList() else index.search(query, scope = SearchScope.NAMES, limit = 6).cards
    }
    Field(n.label, null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The card chosen, as its art: known by its picture before its name is read.
            if (chosen != null && !open) CardChip(h, chosen, 26.dp)
            MuInput(
                query,
                { t -> query = t; open = true },
                Modifier.weight(1f),
                placeholder = "Search the cards",
                onSubmit = { found.firstOrNull()?.let { card -> s.send(n.id, UiEvent.CHANGE, JsonPrimitive(card.id.value)); query = card.name; open = false } },
            )
        }
        if (found.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().border(1.dp, c.ink)) {
                found.forEach { card ->
                    val source = remember(card.id) { MutableInteractionSource() }
                    val hot by source.collectIsHotAsState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(animatedColor(if (hot) c.ink06 else c.paper))
                            .hoverable(source)
                            .cursorPointer(caption = "Choose")
                            .muClickable(interactionSource = source) {
                                s.send(n.id, UiEvent.CHANGE, JsonPrimitive(card.id.value))
                                query = card.name
                                open = false
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CardChip(h, card.name, 20.dp)
                        Body(card.name, color = c.ink, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The library's decks: the app gets a deck's id. */
@Composable
private fun DeckPicker(h: NeueHolders, n: UiNode.DeckPicker, s: Sender) {
    var decks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        val open = h.builder.deckId?.let { listOf(it to "${h.builder.deckName} (open)") }.orEmpty()
        decks = open + h.deps.deckRepository.all().map { it.entry.id to it.entry.name }.filter { (id, _) -> open.none { it.first == id } }.sortedBy { it.second.lowercase() }
    }
    val none = "" to "Choose a deck…"
    val options = listOf(none) + decks
    Field(n.label, null) {
        MuSelect(options.firstOrNull { it.first == n.value } ?: none, options, { it.second }, { d -> if (d.first.isNotEmpty()) s.send(n.id, UiEvent.CHANGE, JsonPrimitive(d.first)) }, Modifier.fillMaxWidth(), small = true)
    }
}

/** The World's table, a page of rows at a time; a pickable row sends `pick` with its number, a row with an address opens it. */
@Composable
private fun AppTable(h: NeueHolders, n: UiNode.Table, s: Sender) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    var page by remember(n.id, n.rows.size) { mutableStateOf(0) }
    val pages = ((n.rows.size + PAGE - 1) / PAGE).coerceAtLeast(1)
    val shown = n.rows.drop(page * PAGE).take(PAGE)
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
        Row(Modifier.fillMaxWidth().background(c.ink06).padding(vertical = 6.dp)) {
            n.columns.forEach { col -> Micro(col, Modifier.weight(1f).padding(horizontal = 8.dp), color = c.ink70) }
        }
        shown.forEachIndexed { i, row ->
            val at = page * PAGE + i
            val address = n.open.getOrNull(at)
            val acts = n.pickable || address != null
            val source = remember(at) { MutableInteractionSource() }
            val hot by source.collectIsHotAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(animatedColor(if (hot && acts) c.ink06 else c.paper))
                    .let { m ->
                        if (!acts) m else m.hoverable(source).cursorPointer(caption = if (address != null) "Open" else "Pick").muClickable(interactionSource = source) {
                            if (address != null) h.world.browser.show(address)
                            if (n.pickable) s.send(n.id ?: "table", UiEvent.PICK, JsonPrimitive(at))
                        }
                    }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                n.columns.indices.forEach { col ->
                    val cell = row.getOrElse(col) { "" }
                    if (col in n.cardColumns && cell.isNotBlank()) {
                        // A column the app marked as cards: each card's art beside its name (never guessed from the words).
                        Row(Modifier.weight(1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardChip(h, cell, 20.dp)
                            Body(cell, color = c.ink, maxLines = 2)
                        }
                    } else {
                        MuText(cell, Modifier.weight(1f).padding(horizontal = 8.dp), style = WorldType.body(f, LocalPhone.current), color = if (col == 0) c.ink else c.ink70, maxLines = 2)
                    }
                }
            }
        }
        if (n.rows.isEmpty()) Help("No rows.", Modifier.padding(8.dp))
        if (pages > 1 || n.more > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono("${page * PAGE + 1}–${minOf(n.rows.size, (page + 1) * PAGE)} of ${n.rows.size + n.more}", color = c.ink45)
                Spacer(Modifier.weight(1f))
                if (page > 0) MicroLink("‹ Previous", { page-- }, color = c.ink)
                if (page < pages - 1) MicroLink("Next ›", { page++ }, color = c.ink)
            }
        }
    }
}

private const val PAGE = 50

/**
 * One card (`ui.card`): its art small beside its label, or [UiNode.Card.large] on its own a card a reader can study. The
 * pointer on it reads it in the inspector and a click opens it large (`ChatCard`); a pickable card sends `pick`.
 */
@Composable
private fun AppCard(h: NeueHolders, n: UiNode.Card, s: Sender) {
    val c = Mu.colors
    val index = h.builder.index
    val name = remember(n.card, index.size) { n.card.toIntOrNull()?.let { index.byId(CardId(it))?.name } ?: n.card }
    val pick = Modifier.let { m ->
        if (!n.pickable) m else m.cursorPointer(caption = "Pick").pointerInput(n.card) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Release) s.send(n.id ?: "card", UiEvent.PICK, JsonPrimitive(n.card))
                }
            }
        }
    }
    if (n.large) {
        Column(pick, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChatCard(h.ai, name, 168.dp)
            if (n.label.isNotBlank()) Body(n.label, color = c.ink, maxLines = 2)
        }
    } else {
        Row(pick, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChatCard(h.ai, name, 34.dp)
            Column {
                Body(n.label.ifBlank { name }, color = c.ink, maxLines = 2)
                if (n.label.isNotBlank() && n.label != name) Small(name, color = c.ink70, maxLines = 1)
            }
        }
    }
}

/** A strip of card art; a pickable card sends `pick` with what the app named it by. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardStrip(h: NeueHolders, n: UiNode.Cards, s: Sender) {
    val index = h.builder.index
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        n.cards.forEachIndexed { i, raw ->
            val name = remember(raw, index.size) { raw.toIntOrNull()?.let { index.byId(CardId(it))?.name } ?: raw }
            key(i) {
                Box(
                    Modifier.let { m ->
                        if (!n.pickable) m else m.cursorPointer(caption = "Pick").pointerInput(raw) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    if (e.type == PointerEventType.Release) s.send(n.id ?: "cards", UiEvent.PICK, JsonPrimitive(raw))
                                }
                            }
                        }
                    },
                ) { ChatCard(h.ai, name, 72.dp) }
            }
        }
    }
}

/** Words a window's frame may show beside the name: the version. */
fun WorldApps.context(slug: String): String = manifest(slug)?.let { "v${it.version}" }.orEmpty()
