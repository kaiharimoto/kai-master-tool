package com.kaiharimoto.mastertool.core.present

import kotlin.math.pow

/**
 * A slide theme: the colours words and shapes take by token (`@bg`, `@text`, `@accent`…),
 * the fonts each role is set in, and how the deck is drawn on its stage. Slides are the
 * creator's content — colour is theirs (kai, 1.0.70: "slides are content: full colour") —
 * while the editor around them stays paper and ink.
 */
data class Theme(
    val id: String,
    val name: String,
    /** Token → `#RRGGBB`: bg, surface, text, muted, accent, accent2, accent3, accent4, line. */
    val colors: Map<String, String>,
    val headingFont: String,
    val bodyFont: String,
    /** A second background colour for a gradient, or null for a flat one. */
    val backgroundTo: String? = null,
    /** How bright the cards not being talked about stay, 0..1. */
    val dim: Float = 0.28f,
    /** How the cards talked about are marked: [HIGHLIGHT_GLOW], [HIGHLIGHT_OUTLINE], [HIGHLIGHT_LIFT]. */
    val highlight: String = HIGHLIGHT_GLOW,
    /** Titles in capitals. */
    val capsTitles: Boolean = false,
) {
    fun color(token: String): String = colors[token] ?: colors["text"] ?: "#000000"

    /** This theme with the person's changes over it. */
    fun with(override: ThemeOverride?): Theme = if (override == null) this else copy(
        colors = colors + override.colors,
        headingFont = override.headingFont ?: headingFont,
        bodyFont = override.bodyFont ?: bodyFont,
        dim = override.dim ?: dim,
    )

    companion object {
        const val HIGHLIGHT_GLOW = "GLOW"
        const val HIGHLIGHT_OUTLINE = "OUTLINE"
        const val HIGHLIGHT_LIFT = "LIFT"

        val TOKENS = listOf("bg", "surface", "text", "muted", "accent", "accent2", "accent3", "accent4", "line")

        fun tokenName(token: String): String = when (token) {
            "bg" -> "Background"
            "surface" -> "Surface"
            "text" -> "Text"
            "muted" -> "Muted text"
            "accent" -> "Accent"
            "accent2" -> "Accent 2"
            "accent3" -> "Accent 3"
            "accent4" -> "Accent 4"
            "line" -> "Lines"
            else -> token
        }
    }
}

object Themes {
    const val PAPER = "paper"
    const val INK = "ink"
    const val ARENA = "arena"
    const val NEON = "neon"
    const val DUEL = "duel"
    const val CLEAN = "clean"

    val all: List<Theme> = listOf(
        Theme(
            PAPER, "Paper",
            mapOf(
                "bg" to "#F4F2EC", "surface" to "#FFFFFF", "text" to "#141414", "muted" to "#5E5E5E",
                "accent" to "#141414", "accent2" to "#7A7A7A", "accent3" to "#B5B5B5", "accent4" to "#D9D6CE",
                "line" to "#141414",
            ),
            headingFont = SlideFonts.INTER, bodyFont = SlideFonts.INTER, highlight = Theme.HIGHLIGHT_OUTLINE,
        ),
        Theme(
            INK, "Ink",
            mapOf(
                "bg" to "#101010", "surface" to "#1C1C1C", "text" to "#F2F0EA", "muted" to "#A3A3A3",
                "accent" to "#F2F0EA", "accent2" to "#8C8C8C", "accent3" to "#5A5A5A", "accent4" to "#2E2E2E",
                "line" to "#F2F0EA",
            ),
            headingFont = SlideFonts.INTER, bodyFont = SlideFonts.INTER, dim = 0.22f,
        ),
        Theme(
            ARENA, "Arena",
            mapOf(
                "bg" to "#0E1A2B", "surface" to "#16263D", "text" to "#F5F7FA", "muted" to "#9FB0C7",
                "accent" to "#F5B82E", "accent2" to "#3FA7FF", "accent3" to "#FF5A5F", "accent4" to "#4CD6A0",
                "line" to "#F5B82E",
            ),
            headingFont = SlideFonts.BEBAS, bodyFont = SlideFonts.INTER, backgroundTo = "#1B3354", dim = 0.24f,
            capsTitles = true,
        ),
        Theme(
            NEON, "Neon",
            mapOf(
                "bg" to "#0B0716", "surface" to "#170F2C", "text" to "#F7F2FF", "muted" to "#A99BC9",
                "accent" to "#FF3EA5", "accent2" to "#2DE2E6", "accent3" to "#B26BFF", "accent4" to "#FFE45E",
                "line" to "#2DE2E6",
            ),
            headingFont = SlideFonts.OSWALD, bodyFont = SlideFonts.INTER, backgroundTo = "#241046", dim = 0.2f,
            capsTitles = true,
        ),
        Theme(
            DUEL, "Duel",
            mapOf(
                "bg" to "#1A0F0A", "surface" to "#2A1810", "text" to "#F8EBDD", "muted" to "#C4A88F",
                "accent" to "#E4B363", "accent2" to "#C2412D", "accent3" to "#7FA37A", "accent4" to "#6B8FB8",
                "line" to "#E4B363",
            ),
            headingFont = SlideFonts.PLAYFAIR, bodyFont = SlideFonts.INTER, backgroundTo = "#3A1E12", dim = 0.25f,
        ),
        Theme(
            CLEAN, "Clean",
            mapOf(
                "bg" to "#FFFFFF", "surface" to "#F2F5F9", "text" to "#16202C", "muted" to "#5B6B7E",
                "accent" to "#2F6BFF", "accent2" to "#16B79E", "accent3" to "#FF7A45", "accent4" to "#9B5CFF",
                "line" to "#2F6BFF",
            ),
            headingFont = SlideFonts.INTER, bodyFont = SlideFonts.INTER, dim = 0.3f,
        ),
    )

    fun of(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first()

    /** The theme a presentation is drawn in, with the person's changes. */
    fun of(p: Presentation): Theme = of(p.theme).with(p.themeOverride)
}

/**
 * The faces slides may be set in. Inter and JetBrains Mono are the app's own; the display
 * faces are bundled for slide content only (all SIL Open Font License, Permanent Marker
 * Apache 2.0) and never drawn in the editor's chrome.
 */
object SlideFonts {
    const val INTER = "inter"
    const val MONO = "mono"
    const val BEBAS = "bebas"
    const val OSWALD = "oswald"
    const val PLAYFAIR = "playfair"
    const val MARKER = "marker"

    val all = listOf(INTER, BEBAS, OSWALD, PLAYFAIR, MARKER, MONO)

    fun name(id: String): String = when (id) {
        MONO -> "JetBrains Mono"
        BEBAS -> "Bebas Neue"
        OSWALD -> "Oswald"
        PLAYFAIR -> "Playfair Display"
        MARKER -> "Permanent Marker"
        else -> "Inter"
    }
}

/**
 * Colours on slides are text: `#RRGGBB`, `#AARRGGBB`, or a theme token `@accent` resolved
 * against the presentation's theme, so a change of theme recolours every slide made from
 * tokens. Parsed here, once, to an ARGB long the painter turns into a colour.
 */
object SlideColor {
    /** [value] as ARGB, tokens resolved through [theme]; null when it is not a colour. */
    fun argb(value: String?, theme: Theme): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        if (v.startsWith("@")) {
            val token = v.drop(1)
            if (token == "transparent" || token == "none") return 0L
            return hex(theme.color(token))
        }
        return hex(v)
    }

    /** `#RGB`, `#RRGGBB` or `#AARRGGBB` as ARGB. */
    fun hex(value: String): Long? {
        val s = value.trim().removePrefix("#")
        val full = when (s.length) {
            3 -> "FF" + s.map { "$it$it" }.joinToString("")
            6 -> "FF$s"
            8 -> s
            else -> return null
        }
        return full.toLongOrNull(16)
    }

    fun toHex(argb: Long): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
        return if (a == 0xFFL) "#$rgb" else "#" + a.toString(16).uppercase().padStart(2, '0') + rgb
    }

    /** WCAG relative luminance of [argb]. */
    fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    /** WCAG contrast between two colours, 1..21. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [argb] with its alpha times [alpha]. */
    fun faded(argb: Long, alpha: Float): Long {
        val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toLong()
        return (a shl 24) or (argb and 0xFFFFFF)
    }
}
