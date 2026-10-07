package com.kaiharimoto.mastertool.core.present.ai

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideColor
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.play.Builds
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.Box
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import kotlin.math.roundToInt

/**
 * A presentation in words, for Ai (1.0.71): the outline `present_state` shows — every slide, what
 * is on it with its id and box, its builds and notes — and the check `present_view` runs on one
 * slide: what sits on the camera or off the canvas, words too many or too small to read, colours
 * too close to read, a deck slide with nothing to talk about. What a person would see, said so a
 * model that may not see pictures can act on it.
 */
object PresentReport {
    /** The most words a slide's words should run to (`slide-design`). */
    const val WORDS = 45

    /** The smallest words read on a phone at 1080p, in canvas units. */
    const val SMALLEST = 26f

    /** WCAG's large-text contrast. */
    const val CONTRAST = 3.0

    fun outline(p: Presentation, name: (Int) -> String?): String = buildString {
        val theme = Themes.of(p)
        appendLine("“${p.name}” (id ${p.id}) — ${Presentation.styleName(p.style)}, theme ${theme.name}, ${p.slides.size} slides")
        p.deck?.let { d ->
            appendLine("Deck: ${d.name}${d.deckId?.let { " (id $it)" } ?: ""} — main ${d.main.size}, extra ${d.extra.size}, side ${d.side.size}")
            if (d.groups.isNotEmpty()) appendLine("Groups: " + d.ordered().joinToString("; ") { g -> "${g.name} (${d.assignments.count { it.value == g.id }} cards)" })
        } ?: appendLine("No deck.")
        val z = p.webcam
        appendLine(if (z.enabled) "Webcam: ${z.preset.lowercase()} ${z.size} ${z.shape.lowercase()}" else "Webcam: off")
        if (p.creator.isNotBlank()) appendLine("Creator: ${p.creator}")
        val show = CompiledShow(p)
        appendLine("Canvas 1920 × 1080; STAGE boxes are fractions of the room the camera leaves.")
        p.slides.forEachIndexed { i, s ->
            appendLine()
            append("${i + 1}. [${s.id}] ${s.title.ifBlank { "(untitled)" }}")
            if (s.hidden) append(" (skipped)")
            s.module?.let { append(" — module ${Modules.name(it.type)}") }
            if (s.layout != SlideLayouts.BLANK) append(" · layout ${s.layout.lowercase()}")
            append(" · transition ${s.transition.kind.lowercase()}")
            if (s.camera != Slide.CAMERA_DEFAULT) append(" · camera ${s.camera.lowercase()}")
            appendLine()
            s.deck?.let { f ->
                val what = when {
                    f.all -> "the whole deck"
                    else -> (f.groups.mapNotNull { g -> p.deck?.group(g)?.name?.let { "group $it" } } + f.cards.map { name(it) ?: "#$it" }).joinToString(", ").ifBlank { "nothing yet" }
                }
                appendLine("   Deck step: $what${if (f.note.isNotBlank()) " — note: “${f.note}”" else ""}")
            }
            s.elements.forEach { e ->
                val b = Geometry.box(e, show.stage(i))
                append("   - ${e.id} ${e.type.lowercase()}")
                if (e.type == Element.SHAPE) append(" ${e.shape.lowercase()}")
                if (e.role != Element.ROLE_NONE) append(" ${e.role.lowercase()}")
                append(" at ${b.x.roundToInt()},${b.y.roundToInt()} ${b.w.roundToInt()}×${b.h.roundToInt()}")
                if (e.anchor == Element.ANCHOR_STAGE) append(" (stage)")
                val words = e.plainText.replace('\n', ' ').trim()
                if (words.isNotEmpty()) append(": “${words.take(80)}${if (words.length > 80) "…" else ""}”")
                if (e.cards.isNotEmpty()) append(" cards ${e.cards.joinToString { name(it) ?: "#$it" }}")
                e.stat?.let { append(" ${it.value} ${it.label}") }
                if (e.animations.isNotEmpty()) append(" builds ${e.animations.joinToString { "${it.kind.lowercase()} ${it.effect.lowercase()} ${it.trigger.lowercase()}" }}")
                if (e.edited) append(" (edited by hand)")
                appendLine()
            }
            if (s.notes.isNotBlank()) appendLine("   Notes: ${s.notes.take(160)}${if (s.notes.length > 160) "…" else ""}")
        }
    }

    /** What is wrong with slide [i], one line each; empty when it reads well. */
    fun check(p: Presentation, i: Int, name: (Int) -> String? = { null }): List<String> {
        val s = p.slides.getOrNull(i) ?: return listOf("No slide ${i + 1}.")
        val show = CompiledShow(p)
        val theme = Themes.of(p)
        val zone = show.zone(i)
        val stage = show.stage(i)
        val out = ArrayList<String>()
        val bg = SlideColor.argb(s.background?.color ?: "@bg", theme) ?: 0xFF000000
        var words = 0
        for (e in s.elements) {
            val b = Geometry.box(e, stage)
            if (zone != null && e.type != Element.CAMERA && b.intersects(zone)) {
                val over = b.intersection(zone)?.area ?: 0f
                if (over > b.area * 0.05f) out += "${e.id} (${Element.typeName(e.type).lowercase()}) sits on the camera; move it into the stage (STAGE boxes stay clear)."
            }
            if (b.x < -2f || b.y < -2f || b.right > Box.CANVAS.right + 2f || b.bottom > Box.CANVAS.bottom + 2f) out += "${e.id} runs off the slide."
            if (e.paras.isNotEmpty()) {
                val count = e.plainText.split(Regex("\\s+")).count { it.isNotBlank() }
                words += count
                val look = roleSize(e.role)
                val sizes = e.paras.flatMap { pa -> pa.runs.map { it.style.size ?: look } }
                if (sizes.any { it < SMALLEST }) out += "${e.id}'s words are set smaller than $SMALLEST; they will not read on a phone."
                // A box too small for its words shrinks them: roughly how many lines it holds.
                val size = sizes.maxOrNull() ?: look
                val perLine = (b.w / (size * 0.52f)).coerceAtLeast(1f)
                val lines = e.paras.sumOf { pa -> ((pa.text.length / perLine).toInt() + 1) }
                val fits = (b.h / (size * 1.25f)).toInt().coerceAtLeast(1)
                if (lines > fits * 1.6f) out += "${e.id} holds about $lines lines in a box for $fits; its words will shrink a lot. Fewer words, or a bigger box."
                val color = e.paras.firstOrNull()?.runs?.firstOrNull()?.style?.color ?: if (e.role == Element.ROLE_SUBTITLE || e.role == Element.ROLE_CAPTION) "@muted" else "@text"
                val fg = SlideColor.argb(color, theme)
                val under = e.fill?.color?.let { SlideColor.argb(it, theme) } ?: bg
                if (fg != null && SlideColor.contrast(fg, under) < CONTRAST) out += "${e.id}'s words (${color}) are too close to what is behind them to read (contrast ${ChatChart.fixed(SlideColor.contrast(fg, under), 1)}:1)."
            }
            if ((e.type == Element.CARD || e.type == Element.CARDS) && e.cards.isEmpty()) out += "${e.id} is a card slot with no cards in it."
            if (e.type == Element.IMAGE && e.media == null) out += "${e.id} is a picture slot with no picture; the person has to add one, or remove it."
        }
        if (words > WORDS) out += "About $words words on one slide; aim for under $WORDS. Put the rest in the speaker notes."
        s.deck?.let { f ->
            if (!f.all && f.isEmpty) out += "This deck slide talks about nothing yet: give it groups or cards."
            val frame = show.deckFrame(i)
            if (frame != null && frame.cards.isEmpty() && p.deck?.isEmpty == false) out += "The deck draws nothing on this slide."
            if (frame != null && zone != null && frame.cards.any { it.box.intersects(zone) }) out += "Cards are drawn under the camera."
        }
        val builds = Builds.compile(s)
        if (builds.count > 6) out += "${builds.count - 1} clicks on one slide; three or fewer keep it moving."
        if (s.notes.isBlank() && !s.hidden) out += "No speaker notes yet."
        return out
    }

    private fun roleSize(role: String): Float = when (role) {
        Element.ROLE_TITLE -> 84f
        Element.ROLE_SUBTITLE -> 44f
        Element.ROLE_CAPTION -> 28f
        else -> 36f
    }

    /** What deck slide [i] draws, in words: which cards are lit, how big. */
    fun deckWords(p: Presentation, i: Int, name: (Int) -> String?): String? {
        val frame = CompiledShow(p).deckFrame(i) ?: return null
        val lit = frame.cards.filter { it.emphasis > 0.5f }
        val w = frame.cards.maxOfOrNull { it.box.w }?.roundToInt() ?: 0
        val names = lit.map { DeckStage.idOf(it.key) }.distinct().mapNotNull { id -> id?.let(name) }
        return "${frame.cards.size} cards drawn, the largest $w wide" + if (names.isNotEmpty()) "; lit: ${names.joinToString()}" else ""
    }
}
