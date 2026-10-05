package com.kaiharimoto.neue.world.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.FieldKind
import com.kaiharimoto.mastertool.core.world.FormField
import com.kaiharimoto.mastertool.core.world.InstrumentForm
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.theme.Inverted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/*
 * Instruments (`docs/world/DESKTOP.md` §3): the engineered studies as an app — their list on the left (name and question),
 * a form on the right made from the instrument's declared arguments (`InstrumentForm`), Run. Its lines go to the Terminal
 * and its boards open as pages; the last runs are listed under the form with Open pages.
 */

private val CLOCK = SimpleDateFormat("HH:mm")

/** Instruments' window body. */
@Composable
fun InstrumentsApp(h: NeueHolders, modifier: Modifier = Modifier) {
    val apps = h.world.apps
    var chosen by remember { mutableStateOf(apps.instrumentsPick ?: InstrumentForm.ALL.first().instrument) }
    LaunchedEffect(apps.instrumentsPick) {
        apps.instrumentsPick?.let {
            chosen = it
            apps.instrumentsPick = null
        }
    }
    val phone = LocalPhone.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp && !phone
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                InstrumentList(Modifier.width(240.dp).fillMaxHeight(), chosen) { chosen = it }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Mu.colors.ink12))
                key(chosen) { InstrumentFormView(h, chosen, Modifier.weight(1f).fillMaxHeight()) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    MuSelect(InstrumentForm.of(chosen) ?: InstrumentForm.ALL.first(), InstrumentForm.ALL, { title(it.instrument) }, { chosen = it.instrument }, Modifier.fillMaxWidth(), small = true)
                }
                key(chosen) { InstrumentFormView(h, chosen, Modifier.fillMaxWidth().weight(1f)) }
            }
        }
    }
}

private fun title(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun InstrumentList(modifier: Modifier, chosen: String, onPick: (String) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        InstrumentForm.ALL.forEach { f ->
            val on = f.instrument == chosen
            val source = remember(f.instrument) { MutableInteractionSource() }
            val hot by source.collectIsHotAsState()
            Inverted(on) {
                val c = Mu.colors
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) c.paper else animatedColor(if (hot) c.ink06 else c.paper))
                        .hoverable(source)
                        .cursorPointer(caption = if (on) null else "Choose")
                        .muClickable(interactionSource = source) { onPick(f.instrument) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MuText(title(f.instrument), style = MuType.row(LocalMuFonts.current), color = c.ink, maxLines = 1)
                    Small(f.question, color = if (on) c.ink70 else c.ink45, maxLines = 2)
                }
            }
        }
    }
}

/** The form for [name]: a field per declared argument, filled with its default; Run; the last runs. */
@Composable
private fun InstrumentFormView(h: NeueHolders, name: String, modifier: Modifier) {
    val world = h.world
    val c = Mu.colors
    val form = InstrumentForm.of(name) ?: return
    val values = remember(name) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.name, it.default) } } }
    var problem by remember(name) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val blocked = when {
        world.open == null -> "Open a world first"
        world.running != null -> "Something is running"
        else -> null
    }
    val runs = world.activity.filter { it.kind == WorldEvent.Kind.RUN && it.run?.lang == "instrument" && it.run?.path == name }.takeLast(5).reversed()
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Micro("Instrument", color = c.ink45)
            MuText(form.question, style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 3)
            MicroLink("What it answers →", { world.browser.show(WorldAddress.Instrument(name).format()) })
        }
        form.fields.forEach { f -> key(f.name) { FormFieldView(h, f, values[f.name].orEmpty()) { values[f.name] = it } } }
        problem?.let { Small(it, color = c.ink) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuButton("Run", {
                form.args(values).fold({ args ->
                    problem = null
                    scope.launch { world.tool(name, args, WorldEvent.YOU).onFailure { problem = it.message } }
                }, { problem = it.message })
            }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = blocked == null, reason = blocked, arrow = true)
            Help("Its lines print in the Terminal; its boards open as pages.")
        }
        if (runs.isNotEmpty()) {
            Micro("Last runs", Modifier.padding(top = 8.dp), color = c.ink45)
            runs.mapNotNull { e -> e.run?.let { e to it } }.forEach { (e, r) ->
                Row(
                    Modifier.fillMaxWidth().drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Mono(CLOCK.format(Date(e.t)), color = c.ink45)
                    Small("${r.ms} ms · ${r.boards.size} page${if (r.boards.size == 1) "" else "s"}", Modifier.weight(1f), color = c.ink70, maxLines = 1)
                    if (r.boards.isNotEmpty()) MicroLink("Open pages", {
                        r.boards.forEach { id -> world.browser.shown(id, raise = false, by = WorldEvent.YOU) }
                        world.browser.show(WorldAddress.Board(r.boards.first()).format())
                    }, color = c.ink)
                    MicroLink("The run", { world.browser.show(WorldAddress.Run(e.t).format()) })
                }
            }
        }
    }
}

/** One argument as its kind of field (§3): decks a select, conditions and cards lines, choices segments, a switch. */
@Composable
private fun FormFieldView(h: NeueHolders, f: FormField, value: String, onChange: (String) -> Unit) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(f.label)
        when (f.kind) {
            FieldKind.DECK -> DeckField(h, value, onChange)
            FieldKind.CONDITIONS, FieldKind.CARDS, FieldKind.JSON -> Lines(value, onChange, f.hint)
            FieldKind.CHOICE -> if (f.options.size in 2..5) {
                Segmented(value.ifEmpty { f.options.first() }, f.options, { it.replaceFirstChar { ch -> ch.uppercase() } }, onChange, small = true)
            } else {
                MuSelect(value.ifEmpty { f.options.firstOrNull().orEmpty() }, f.options, { it }, onChange, small = true)
            }
            FieldKind.CHOICES -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val on = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                f.options.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MuCheckbox(o in on, { v -> onChange((if (v) on + o else on - o).distinct().joinToString(", ")) })
                        Small(o, color = c.ink)
                    }
                }
            }
            FieldKind.SWITCH -> MuSwitch(value.lowercase() in setOf("true", "yes", "on", "1"), { onChange(it.toString()) })
            FieldKind.NUMBER, FieldKind.NUMBERS -> MuInput(value, onChange, Modifier.width(200.dp), placeholder = f.default.ifEmpty { "${f.min}–${f.max}" }, mono = true, dense = true)
            else -> MuInput(value, onChange, Modifier.fillMaxWidth(), placeholder = f.hint, dense = true)
        }
        if (f.hint.isNotBlank() && f.kind !in setOf(FieldKind.CONDITIONS, FieldKind.CARDS, FieldKind.JSON, FieldKind.CONDITION, FieldKind.CARD, FieldKind.GROUP)) Help(f.hint)
    }
}

/** A field of several lines, in a ruled box: conditions, cards, or JSON. */
@Composable
private fun Lines(value: String, onChange: (String) -> Unit, hint: String) {
    val c = Mu.colors
    val style = MuType.mono(LocalMuFonts.current).copy(color = c.ink)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = 160.dp)
            .border(1.dp, animatedColor(if (focused) c.ink else c.ink25))
            .cursor(CursorMode.TEXT, fontSize = style.fontSize, singleLine = false, focused = focused)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) MuText(hint, style = style, color = c.ink45)
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth().reportsTextFocus(),
            textStyle = style,
            cursorBrush = SolidColor(c.ink),
            interactionSource = source,
        )
    }
}

/** A deck by name: the open deck by default (blank), or one of the library's. */
@Composable
private fun DeckField(h: NeueHolders, value: String, onChange: (String) -> Unit) {
    var decks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) { decks = h.deps.deckRepository.all().map { it.entry.id to it.entry.name }.sortedBy { it.second.lowercase() } }
    val open = "" to (h.builder.deckName.takeIf { h.builder.deckId != null }?.let { "The open deck · $it" } ?: "The open deck")
    val options = listOf(open) + decks
    MuSelect(options.firstOrNull { it.first == value } ?: open, options, { it.second }, { onChange(it.first) }, Modifier.fillMaxWidth(), small = true)
}
