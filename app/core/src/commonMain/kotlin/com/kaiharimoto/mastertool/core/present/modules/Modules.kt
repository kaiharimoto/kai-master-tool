package com.kaiharimoto.mastertool.core.present.modules

import com.kaiharimoto.mastertool.core.present.Anim
import com.kaiharimoto.mastertool.core.present.Chart
import com.kaiharimoto.mastertool.core.present.ChartSeries
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Fill
import com.kaiharimoto.mastertool.core.present.ModuleRef
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.Stat
import com.kaiharimoto.mastertool.core.present.Stroke
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** A card picked by hand, with why. */
@Serializable
data class Pick(val card: Int, val note: String = "")

/** One turn's siding: cards out and in (each copy listed), and why. */
@Serializable
data class SideTurn(val out: List<Int> = emptyList(), val into: List<Int> = emptyList(), val note: String = "")

@Serializable
data class SideMatchup(val name: String, val first: SideTurn = SideTurn(), val second: SideTurn = SideTurn(), val note: String = "", val covers: List<Int> = emptyList())

/** One opponent's practice record: rates 0..1 with how many games each. */
@Serializable
data class MatchRow(
    val name: String,
    val first: Double = 0.0, val firstGames: Int = 0,
    val second: Double = 0.0, val secondGames: Int = 0,
    val game1: Double = 0.0, val game1Games: Int = 0,
    val postSide: Double = 0.0, val postSideGames: Int = 0,
    val all: Double = 0.0, val games: Int = 0,
    /** The opponent's share of the field, percent, when a web says. */
    val share: Int? = null,
)

@Serializable
data class RoundRow(val round: Int, val opponent: String, val result: String)

@Serializable
data class Shoutout(val media: String? = null, val name: String = "", val handle: String = "", val line: String = "")

@Serializable
data class GroupOdds(val name: String, val opening: Double, val openingSecond: Double, val count: Int, val color: Int = 0)

/**
 * What a module is made from. Data a module reads from the app (siding, practice, an event, the
 * deck's numbers) is gathered by the app and handed in; what a creator picks by hand (performers,
 * tech, a combo, shoutouts) is kept in the slide's [ModuleRef] so it can be made again.
 */
@Serializable
data class ModuleInput(
    val title: String = "",
    val subtitle: String = "",
    val matchups: List<SideMatchup> = emptyList(),
    val rows: List<MatchRow> = emptyList(),
    /** The match win to expect against the field, 0..1. */
    val expected: Double? = null,
    val strong: List<Pick> = emptyList(),
    val weak: List<Pick> = emptyList(),
    val picks: List<Pick> = emptyList(),
    val rounds: List<RoundRow> = emptyList(),
    val record: String = "",
    val placement: String = "",
    val shoutouts: List<Shoutout> = emptyList(),
    val odds: List<GroupOdds> = emptyList(),
    val handSize: Int = 5,
    /** The deck's ydke code, for a QR. */
    val code: String = "",
    /** Where the data came from, for a refresh: the event's id, the matchups chosen. */
    val params: Map<String, String> = emptyMap(),
)

/**
 * The modules (kai, 1.0.70: "siding profiles, matchup reports, strong/weak performers, tournament
 * reports, shoutouts"), and more: generators that make ordinary slides — every element editable
 * like any other — and remember how they were made ([ModuleRef]) so a refresh can make them again
 * from fresh data, leaving alone whatever the creator changed by hand (`Element.edited`).
 *
 * Every element is stage-anchored, so a module slide makes room for the camera as any layout does,
 * and its ids are the slide's own plus a slot, so a refresh finds what it made.
 */
object Modules {
    const val SIDING = "SIDING"
    const val MATCHUPS = "MATCHUPS"
    const val PERFORMERS = "PERFORMERS"
    const val TOURNAMENT = "TOURNAMENT"
    const val SHOUTOUTS = "SHOUTOUTS"
    const val ODDS = "ODDS"
    const val RATIOS = "RATIOS"
    const val GET_THE_DECK = "GET_THE_DECK"
    const val TECH = "TECH"
    const val COMBO = "COMBO"
    const val DECKLIST = "DECKLIST"

    val all = listOf(SIDING, MATCHUPS, PERFORMERS, TOURNAMENT, SHOUTOUTS, ODDS, RATIOS, TECH, COMBO, GET_THE_DECK, DECKLIST)

    fun name(type: String): String = when (type) {
        SIDING -> "Siding profile"
        MATCHUPS -> "Matchup report"
        PERFORMERS -> "Strong and weak performers"
        TOURNAMENT -> "Tournament report"
        SHOUTOUTS -> "Shoutouts"
        ODDS -> "Opening odds"
        RATIOS -> "The deck by job"
        GET_THE_DECK -> "Get the deck"
        TECH -> "Tech choices"
        COMBO -> "A combo, step by step"
        DECKLIST -> "Decklist"
        else -> type
    }

    fun line(type: String): String = when (type) {
        SIDING -> "What comes out and goes in, going first and second, per matchup"
        MATCHUPS -> "Your practice record against the field, and the match win to expect"
        PERFORMERS -> "The cards that won games and the ones that sat dead, with why"
        TOURNAMENT -> "An event's rounds, the record and where it finished"
        SHOUTOUTS -> "Logos, names and handles of the people to thank"
        ODDS -> "How often each group shows up in the opening hand"
        RATIOS -> "A chart of how many cards each group holds"
        TECH -> "The unusual choices, and the reason for each"
        COMBO -> "Cards played one by one, each on a click"
        GET_THE_DECK -> "A QR code of the deck for viewers to scan"
        DECKLIST -> "The whole list on one slide"
        else -> ""
    }

    /** Whether the creator picks this module's content by hand. */
    fun manual(type: String): Boolean = type in setOf(PERFORMERS, SHOUTOUTS, TECH, COMBO)

    /** The slides [type] makes from [input], new ids from [random]. */
    fun generate(type: String, input: ModuleInput, now: Long = 0L, random: Random = Random.Default): List<Slide> {
        val ref = { ModuleRef(type, mapOf("input" to PresentCodec.json.encodeToString(ModuleInput.serializer(), input)), now) }
        fun slide(title: String, build: Builder.() -> Unit): Slide {
            val id = PresentIds.next("s", random)
            val b = Builder(id).apply(build)
            return Slide(id = id, title = title, elements = b.elements, module = ref(), notes = b.notes)
        }
        return when (type) {
            SIDING -> input.matchups.ifEmpty { listOf(SideMatchup("No matchups yet")) }.map { m ->
                slide("Siding vs ${m.name}") {
                    title("vs ${m.name}")
                    // Bands that never touch: the note, then each turn's tags, cards and why (1.0.71).
                    if (m.note.isNotBlank()) caption(m.note, 0f, 0.13f, 1f, 0.06f, "note")
                    turn("Going first", m.first, 0.26f, "first", click = false)
                    turn("Going second", m.second, 0.64f, "second", click = true)
                    notes = buildString {
                        append("Against ${m.name}. ")
                        if (m.first.note.isNotBlank()) append("Going first: ${m.first.note} ")
                        if (m.second.note.isNotBlank()) append("Going second: ${m.second.note}")
                    }.trim()
                }
            }
            MATCHUPS -> listOf(
                slide(input.title.ifBlank { "Matchups" }) {
                    title(input.title.ifBlank { "Matchups" })
                    val rows = input.rows
                    if (rows.isEmpty()) {
                        caption("Log practice games on Prep and refresh this slide.", 0f, 0.3f, 1f, 0.1f, "empty")
                    } else {
                        val header = listOf("Opponent", "1st", "2nd", "G1", "G2–3", "Games")
                        val body = rows.take(9).map { r ->
                            listOf(r.name, pct(r.first, r.firstGames), pct(r.second, r.secondGames), pct(r.game1, r.game1Games), pct(r.postSide, r.postSideGames), r.games.toString())
                        }
                        add(Element(id("table"), Element.TABLE, 0f, 0.17f, 0.6f, min(0.83f, 0.09f * (body.size + 1)), anchor = Element.ANCHOR_STAGE, table = listOf(header) + body))
                        add(
                            Element(
                                id("chart"), Element.CHART, 0.64f, 0.17f, 0.36f, 0.52f, anchor = Element.ANCHOR_STAGE,
                                chart = Chart(Chart.BAR, rows.take(6).map { it.name }, listOf(ChartSeries("Win rate", rows.take(6).map { (it.all * 100).toFloat() })), max = 100f, percent = true),
                            ),
                        )
                        input.expected?.let { e ->
                            add(Element(id("expected"), Element.STAT, 0.64f, 0.72f, 0.36f, 0.28f, anchor = Element.ANCHOR_STAGE, stat = Stat("${(e * 100).roundToInt()}%", "match win to expect", "against the field, best of three")))
                        }
                        notes = "The practice record: ${rows.sumOf { it.games }} games against ${rows.size} decks."
                    }
                },
            )
            PERFORMERS -> listOf(
                slide(input.title.ifBlank { "Performers" }) {
                    title(input.title.ifBlank { "Strong and weak performers" })
                    column("Strong", input.strong, 0f, "strong", "@accent3")
                    column("Weak", input.weak, 0.52f, "weak", "@accent2")
                },
            )
            TECH -> listOf(
                slide(input.title.ifBlank { "Tech choices" }) {
                    title(input.title.ifBlank { "Tech choices" })
                    picksWithNotes(input.picks, "tech")
                },
            )
            COMBO -> listOf(
                slide(input.title.ifBlank { "The combo" }) {
                    title(input.title.ifBlank { "The combo" })
                    combo(input.picks)
                },
            )
            TOURNAMENT -> listOf(
                slide(input.title.ifBlank { "Tournament report" }) {
                    title(input.title.ifBlank { "Tournament report" })
                    if (input.subtitle.isNotBlank()) caption(input.subtitle, 0f, 0.13f, 1f, 0.07f, "sub")
                    val body = input.rounds.sortedBy { it.round }.map { listOf("Round ${it.round}", it.opponent, it.result) }
                    if (body.isEmpty()) caption("Log rounds on Prep's day tab and refresh this slide.", 0f, 0.3f, 0.6f, 0.1f, "empty")
                    else add(Element(id("rounds"), Element.TABLE, 0f, 0.22f, 0.6f, min(0.78f, 0.085f * (body.size + 1)), anchor = Element.ANCHOR_STAGE, table = listOf(listOf("Round", "Opponent", "Result")) + body))
                    add(Element(id("record"), Element.STAT, 0.64f, 0.22f, 0.36f, 0.36f, anchor = Element.ANCHOR_STAGE, stat = Stat(input.record.ifBlank { "–" }, "the record", "")))
                    if (input.placement.isNotBlank()) add(Element(id("placed"), Element.TEXT, 0.64f, 0.62f, 0.36f, 0.3f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_SUBTITLE, paras = listOf(Para.of(input.placement, align = Para.ALIGN_CENTER)), vAlign = Element.V_MIDDLE))
                },
            )
            SHOUTOUTS -> listOf(
                slide(input.title.ifBlank { "Shoutouts" }) {
                    title(input.title.ifBlank { "Shoutouts" })
                    shoutouts(input.shoutouts)
                },
            )
            ODDS -> listOf(
                slide(input.title.ifBlank { "Opening odds" }) {
                    title(input.title.ifBlank { "Opening odds" })
                    caption("At least one in a ${input.handSize}-card hand going first (and six going second)", 0f, 0.13f, 1f, 0.07f, "sub")
                    val tiles = input.odds.take(6)
                    val cols = if (tiles.size <= 3) tiles.size.coerceAtLeast(1) else 3
                    val rows = ceil(tiles.size / cols.toDouble()).toInt().coerceAtLeast(1)
                    tiles.forEachIndexed { i, g ->
                        val w = 1f / cols
                        val h = 0.76f / rows
                        add(
                            Element(
                                id("odds$i"), Element.STAT, (i % cols) * w + 0.01f, 0.23f + (i / cols) * h, w - 0.02f, h - 0.02f,
                                anchor = Element.ANCHOR_STAGE,
                                stat = Stat("${(g.opening * 100).roundToInt()}%", g.name, "${(g.openingSecond * 100).roundToInt()}% going second · ${g.count} cards"),
                                animations = listOf(Anim(PresentIds.next("a", random), effect = Anim.RISE, trigger = if (i == 0) Anim.ON_CLICK else Anim.AFTER_PREVIOUS, durationMs = 350, order = i)),
                            ),
                        )
                    }
                },
            )
            RATIOS -> listOf(
                slide(input.title.ifBlank { "The deck by job" }) {
                    title(input.title.ifBlank { "The deck by job" })
                    val parts = input.odds.filter { it.count > 0 }
                    add(
                        Element(
                            id("donut"), Element.CHART, 0.1f, 0.17f, 0.8f, 0.8f, anchor = Element.ANCHOR_STAGE,
                            chart = Chart(Chart.DONUT, parts.map { "${it.name} (${it.count})" }, listOf(ChartSeries("Cards", parts.map { it.count.toFloat() }))),
                        ),
                    )
                },
            )
            GET_THE_DECK -> listOf(
                slide("Get the deck") {
                    title(input.title.ifBlank { "Get the deck" })
                    add(Element(id("qr"), Element.QR, 0.04f, 0.2f, 0.42f, 0.74f, anchor = Element.ANCHOR_STAGE, qr = input.code.ifBlank { "ydke://" }))
                    add(
                        Element(
                            id("how"), Element.TEXT, 0.52f, 0.24f, 0.48f, 0.6f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_BODY, vAlign = Element.V_MIDDLE,
                            paras = listOf(
                                Para.of("Scan it with Neue Master Tool's Import, or paste the ydke:// code from the description into your simulator."),
                                Para.of("The list is in the description too.", RunStyle(color = "@muted")),
                            ),
                        ),
                    )
                },
            )
            DECKLIST -> listOf(
                slide(input.title.ifBlank { "Decklist" }) {
                    add(Element(id("deck"), Element.DECK, 0f, 0f, 1f, 1f, anchor = Element.ANCHOR_STAGE, focus = DeckFocus(all = true)))
                },
            )
            else -> emptyList()
        }
    }

    /**
     * [old] made again from [fresh] — the same module made from newer data: elements the person
     * changed by hand (and ones they added) stay as they are; what the module made, and the
     * person never touched, is replaced.
     */
    fun refresh(old: Slide, fresh: Slide): Slide {
        val freshBySlot = fresh.elements.associateBy { slotOf(fresh.id, it.id) }
        val kept = old.elements.filter { e ->
            val slot = slotOf(old.id, e.id)
            e.edited || slot == null || slot !in freshBySlot
        }
        val keptSlots = kept.mapNotNull { slotOf(old.id, it.id) }.toSet()
        val made = fresh.elements.filter { slotOf(fresh.id, it.id) !in keptSlots }.map { e ->
            val slot = slotOf(fresh.id, e.id)
            if (slot == null) e else e.copy(id = "${old.id}-$slot")
        }
        return old.copy(elements = made + kept, module = fresh.module)
    }

    private fun slotOf(slideId: String, elementId: String): String? =
        if (elementId.startsWith("$slideId-")) elementId.removePrefix("$slideId-") else null

    /** The input a module slide was made from, or empty. */
    fun inputOf(ref: ModuleRef?): ModuleInput = ref?.params?.get("input")?.let {
        runCatching { PresentCodec.json.decodeFromString(ModuleInput.serializer(), it) }.getOrNull()
    } ?: ModuleInput()

    private fun pct(rate: Double, games: Int): String = if (games == 0) "–" else "${(rate * 100).roundToInt()}%"

    /** Elements laid on a module slide, each id the slide's plus a slot. */
    class Builder(private val slideId: String) {
        val elements = ArrayList<Element>()
        var notes = ""

        fun id(slot: String) = "$slideId-$slot"

        fun add(e: Element) {
            elements += e
        }

        fun title(text: String) = add(
            Element(id("title"), Element.TEXT, 0f, 0f, 1f, 0.13f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_TITLE, vAlign = Element.V_MIDDLE, paras = listOf(Para.of(text))),
        )

        fun caption(text: String, x: Float, y: Float, w: Float, h: Float, slot: String) = add(
            Element(id(slot), Element.TEXT, x, y, w, h, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_CAPTION, vAlign = Element.V_MIDDLE, paras = listOf(Para.of(text))),
        )

        fun turn(label: String, t: SideTurn, top: Float, slot: String, click: Boolean) {
            val anim = { order: Int -> if (click) listOf(Anim(PresentIds.next("a"), effect = Anim.RISE, trigger = if (order == 0) Anim.ON_CLICK else Anim.WITH_PREVIOUS, order = order)) else emptyList() }
            add(Element(id("$slot-label"), Element.TEXT, 0f, top, 0.16f, 0.3f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_SUBTITLE, vAlign = Element.V_MIDDLE, paras = listOf(Para.of(label)), animations = anim(0)))
            if (t.out.isEmpty() && t.into.isEmpty()) {
                caption("No change", 0.18f, top, 0.8f, 0.3f, "$slot-none")
                return
            }
            add(Element(id("$slot-out-tag"), Element.TEXT, 0.18f, top - 0.04f, 0.36f, 0.05f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_CAPTION, paras = listOf(Para.of("OUT", RunStyle(weight = 700, color = "@accent2"))), animations = anim(1)))
            add(Element(id("$slot-out"), Element.CARDS, 0.18f, top + 0.01f, 0.36f, 0.27f, anchor = Element.ANCHOR_STAGE, cards = t.out, animations = anim(2)))
            add(
                Element(
                    id("$slot-arrow"), Element.SHAPE, 0.555f, top + 0.11f, 0.05f, 0.06f, anchor = Element.ANCHOR_STAGE,
                    shape = Element.SHAPE_ARROW, fill = Fill.solid("@muted"), animations = anim(3),
                ),
            )
            add(Element(id("$slot-in-tag"), Element.TEXT, 0.62f, top - 0.04f, 0.36f, 0.05f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_CAPTION, paras = listOf(Para.of("IN", RunStyle(weight = 700, color = "@accent3"))), animations = anim(4)))
            add(Element(id("$slot-in"), Element.CARDS, 0.62f, top + 0.01f, 0.38f, 0.27f, anchor = Element.ANCHOR_STAGE, cards = t.into, animations = anim(5)))
            if (t.note.isNotBlank()) caption(t.note, 0.18f, top + 0.285f, 0.8f, 0.06f, "$slot-why")
        }

        fun column(label: String, picks: List<Pick>, left: Float, slot: String, accent: String) {
            add(
                Element(
                    id("$slot-head"), Element.TEXT, left, 0.16f, 0.48f, 0.08f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_SUBTITLE,
                    paras = listOf(Para.of(label, RunStyle(weight = 700, color = accent))),
                ),
            )
            if (picks.isEmpty()) {
                caption("None picked yet", left, 0.26f, 0.48f, 0.08f, "$slot-none")
                return
            }
            add(Element(id("$slot-cards"), Element.CARDS, left, 0.25f, 0.48f, 0.4f, anchor = Element.ANCHOR_STAGE, cards = picks.map { it.card }))
            val lines = picks.filter { it.note.isNotBlank() }
            if (lines.isNotEmpty()) {
                add(
                    Element(
                        id("$slot-why"), Element.TEXT, left, 0.68f, 0.48f, 0.32f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_BODY,
                        paras = lines.map { Para(listOf(com.kaiharimoto.mastertool.core.present.Run(it.note)), list = Para.LIST_BULLET) },
                    ),
                )
            }
        }

        fun picksWithNotes(picks: List<Pick>, slot: String) {
            if (picks.isEmpty()) {
                caption("Pick the cards for this slide", 0f, 0.3f, 1f, 0.1f, "$slot-none")
                return
            }
            val n = picks.size.coerceAtMost(4)
            val w = 1f / n
            picks.take(4).forEachIndexed { i, p ->
                add(Element(id("$slot-card$i"), Element.CARD, i * w + w * 0.15f, 0.16f, w * 0.7f, 0.5f, anchor = Element.ANCHOR_STAGE, cards = listOf(p.card)))
                if (p.note.isNotBlank()) {
                    add(
                        Element(
                            id("$slot-note$i"), Element.TEXT, i * w + 0.01f, 0.69f, w - 0.02f, 0.3f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_BODY,
                            paras = listOf(Para.of(p.note, align = Para.ALIGN_CENTER)),
                        ),
                    )
                }
            }
        }

        fun combo(picks: List<Pick>) {
            if (picks.isEmpty()) {
                caption("Pick the cards in the order they are played", 0f, 0.3f, 1f, 0.1f, "combo-none")
                return
            }
            val n = picks.size.coerceAtMost(8)
            val gap = 0.03f
            val w = (1f - gap * (n - 1)) / n
            picks.take(8).forEachIndexed { i, p ->
                val x = i * (w + gap)
                add(
                    Element(
                        id("combo$i"), Element.CARD, x, 0.2f, w, 0.5f, anchor = Element.ANCHOR_STAGE, cards = listOf(p.card),
                        animations = listOf(Anim(PresentIds.next("a"), effect = Anim.RISE, trigger = Anim.ON_CLICK, order = i * 3)),
                    ),
                )
                if (p.note.isNotBlank()) {
                    add(
                        Element(
                            id("comboNote$i"), Element.TEXT, x, 0.72f, w, 0.27f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_CAPTION,
                            paras = listOf(Para.of("${i + 1}. ${p.note}", align = Para.ALIGN_CENTER)),
                            animations = listOf(Anim(PresentIds.next("a"), effect = Anim.FADE, trigger = Anim.WITH_PREVIOUS, order = i * 3 + 1)),
                        ),
                    )
                }
                if (i < n - 1) {
                    add(
                        Element(
                            id("comboArrow$i"), Element.SHAPE, x + w, 0.43f, gap, 0.04f, anchor = Element.ANCHOR_STAGE, shape = Element.SHAPE_ARROW_LINE,
                            stroke = Stroke("@muted", 5f),
                            animations = listOf(Anim(PresentIds.next("a"), effect = Anim.WIPE, trigger = Anim.WITH_PREVIOUS, order = i * 3 + 2)),
                        ),
                    )
                }
            }
        }

        fun shoutouts(list: List<Shoutout>) {
            if (list.isEmpty()) {
                caption("Add the people to thank: a logo, a name and a handle", 0f, 0.3f, 1f, 0.1f, "shout-none")
                return
            }
            val tiles = list.take(6)
            val cols = if (tiles.size <= 3) tiles.size else 3
            val rows = ceil(tiles.size / cols.toDouble()).toInt()
            val w = 1f / cols
            val h = 0.84f / rows
            tiles.forEachIndexed { i, s ->
                val x = (i % cols) * w
                val y = 0.16f + (i / cols) * h
                val side = min(w * 0.5f, h * 0.55f)
                add(
                    Element(
                        id("logo$i"), Element.IMAGE, x + (w - side * 0.5625f) / 2f, y, side * 0.5625f, side, anchor = Element.ANCHOR_STAGE,
                        media = s.media, shape = Element.SHAPE_ELLIPSE, imageFit = Element.FIT_COVER, stroke = Stroke("@accent", 4f),
                    ),
                )
                add(
                    Element(
                        id("who$i"), Element.TEXT, x, y + side + 0.01f, w, h - side - 0.02f, anchor = Element.ANCHOR_STAGE, role = Element.ROLE_BODY,
                        paras = listOfNotNull(
                            Para.of(s.name, RunStyle(weight = 700), Para.ALIGN_CENTER),
                            s.handle.takeIf { it.isNotBlank() }?.let { Para.of(it, RunStyle(color = "@accent"), Para.ALIGN_CENTER) },
                            s.line.takeIf { it.isNotBlank() }?.let { Para.of(it, RunStyle(color = "@muted"), Para.ALIGN_CENTER) },
                        ),
                    ),
                )
            }
        }
    }
}
