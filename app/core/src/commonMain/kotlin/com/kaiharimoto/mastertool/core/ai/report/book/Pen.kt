package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.ArtWindow
import com.kaiharimoto.mastertool.core.siding.GuideFonts

/** The guide's four faces, measured from the app's own fonts. */
class Faces(val regular: Face, val medium: Face, val bold: Face, val mono: Face) {
    val byWeight: Map<Weight, Face> = mapOf(Weight.REGULAR to regular, Weight.MEDIUM to medium, Weight.BOLD to bold, Weight.MONO to mono)

    companion object {
        fun of(fonts: GuideFonts) = Faces(
            Face(Weight.REGULAR, fonts.regular),
            Face(Weight.MEDIUM, fonts.medium),
            Face(Weight.BOLD, fonts.bold),
            Face(Weight.MONO, fonts.mono),
        )
    }
}

/**
 * Type and cards set on an [Ink] (1.0.67): the guide's measured words — wrapped, tracked, cut short
 * — and its card tiles, the same arithmetic whether the ink is a PDF page or a recording for the app.
 * The scale is the app's own (`MuType`) brought to a phone's page; ink is three weights.
 */
class Pen(val faces: Faces, var ink: Ink, val deck: String) {
    val regular get() = faces.regular
    val medium get() = faces.medium
    val bold get() = faces.bold
    val mono get() = faces.mono

    /** Words broken into lines no wider than [width]; a word longer than the line is cut. */
    fun wrap(text: String, face: Face, size: Float, width: Float, tracking: Float = 0f): List<String> =
        wrapWith(clean(text), { face.width(it, size, tracking) }, width)

    /** [text] set in [width] from [top]; the height it took. Never more than [max] lines, the last cut short. */
    fun para(
        text: String, x: Float, top: Float, width: Float,
        face: Face = regular, size: Float = BODY, lead: Float = LEAD, gray: Float = INK, max: Int = Int.MAX_VALUE, tracking: Float = 0f,
    ): Float {
        val lines = wrap(text, face, size, width, tracking).let { if (it.size > max) it.take(max - 1) + fit(it.drop(max - 1).joinToString(" "), face, size, width) else it }
        lines.forEachIndexed { i, l -> ink.text(face, size, x, top + lead * i + size * 0.94f, l, gray, tracking) }
        return lead * lines.size
    }

    fun height(text: String, width: Float, face: Face = regular, size: Float = BODY, lead: Float = LEAD, max: Int = Int.MAX_VALUE, tracking: Float = 0f): Float =
        lead * minOf(wrap(text, face, size, width, tracking).size, max)

    /** Micro caps: small, upper case, tracked open (MuType's micro, +0.08 em). */
    fun micro(text: String, x: Float, baseline: Float, gray: Float = INK45, size: Float = 7f) =
        ink.text(regular, size, x, baseline, text.uppercase(), gray, tracking = size * 0.08f)

    fun microWidth(text: String, size: Float = 7f) = regular.width(text.uppercase(), size, size * 0.08f)

    /** A headline that makes a claim: display type, tight. The height it took. */
    fun headline(text: String, x: Float, top: Float, width: Float, size: Float = 26f, gray: Float = INK): Float =
        para(text, x, top, width, bold, size, size * 1.04f, gray, tracking = -size * 0.025f)

    fun right(face: Face, size: Float, x: Float, baseline: Float, text: String, gray: Float = INK, tracking: Float = 0f) =
        ink.text(face, size, x - face.width(text, size, tracking), baseline, text, gray, tracking)

    fun centre(face: Face, size: Float, cx: Float, baseline: Float, text: String, gray: Float = INK, tracking: Float = 0f) =
        ink.text(face, size, cx - face.width(text, size, tracking) / 2, baseline, text, gray, tracking)

    fun fit(text: String, face: Face, size: Float, width: Float): String {
        if (face.width(text, size) <= width) return text
        var t = text
        while (t.isNotEmpty() && face.width("$t…", size) > width) t = t.dropLast(1)
        return "${t.trimEnd()}…"
    }

    /** The card named [name], [w] wide at the card's shape, framed; a place a reader can touch. */
    fun card(name: String, x: Float, top: Float, w: Float, line: Float = 0.5f, gray: Float = INK12) {
        val h = w * CARD
        ink.card(name, x, top, w, h)
        ink.strokeRect(x, top, w, h, gray, line)
        ink.tag("card:$name", x, top, w, h)
    }

    /** A card's name short enough to sit under its tile: "Arianna", "Lady Labrynth", "Stovie Torbie". */
    fun short(name: String): String {
        // Cut at the first " the " or " of ": "Lady Labrynth of the Silver Castle" is Lady Labrynth.
        val at = listOf(" the ", " of ").map { name.indexOf(it) }.filter { it > 0 }.minOrNull()
        var s = if (at != null) name.substring(0, at) else name
        // The archetype's own word in front is said by the page already: "Labrynth Chandraglier" is Chandraglier.
        val words = s.split(' ')
        if (words.size >= 2 && words.first().equals(deck, ignoreCase = true)) s = words.drop(1).joinToString(" ")
        return s
    }

    /** [draw] recorded at (0, 0) for [width]: the picture as a [Drawing], its height what [draw] returns. */
    fun record(width: Float, draw: Pen.() -> Float): Drawing {
        val rec = RecordingInk()
        val pen = Pen(faces, rec, deck)
        val h = pen.draw()
        return rec.drawing(width, h)
    }

    companion object {
        const val BODY = 10.5f
        const val LEAD = 15f
        const val CARD = 1.4583f

        const val INK = 0f
        const val INK80 = 0.2f
        const val INK70 = 0.3f
        const val INK45 = 0.55f
        const val INK25 = 0.75f
        const val INK12 = 0.88f
        const val INK06 = 0.95f
        const val PAPER = 1f

        fun two(n: Int) = n.toString().padStart(2, '0')

        /** Markup a reader should not see: `[[Card]]` is the card's name, `**x**` is x. */
        fun clean(text: String): String = text.replace("[[", "").replace("]]", "").replace("**", "")

        /** Words broken into lines no wider than [width] as [measure] measures them. */
        fun wrapWith(text: String, measure: (String) -> Float, width: Float): List<String> {
            val out = mutableListOf<String>()
            text.split('\n').forEach { para ->
                var line = ""
                para.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                    val trial = if (line.isEmpty()) word else "$line $word"
                    if (measure(trial) <= width) {
                        line = trial
                    } else {
                        if (line.isNotEmpty()) out += line
                        var rest = word
                        while (measure(rest) > width && rest.length > 1) {
                            var cut = rest.length - 1
                            while (cut > 1 && measure(rest.substring(0, cut)) > width) cut--
                            out += rest.substring(0, cut)
                            rest = rest.substring(cut)
                        }
                        line = rest
                    }
                }
                if (line.isNotEmpty()) out += line
            }
            return out
        }

        /**
         * Where to draw a whole card so its art window covers the box ([x], [top], [w], [h]): the
         * card's own x, top, width and height, the rest to be clipped away.
         */
        fun coverArt(x: Float, top: Float, w: Float, h: Float): FloatArray {
            val win = ArtWindow.of(ArtFrame.STANDARD)
            val winW = win.right - win.left
            val winH = (win.bottom - win.top) * CARD
            val cardW = maxOf(w / winW, h / winH)
            val cardH = cardW * CARD
            val cx = x + w / 2 - (win.left + winW / 2) * cardW
            val cy = top + h / 2 - (win.top + (win.bottom - win.top) / 2) * cardH
            return floatArrayOf(cx, cy, cardW, cardH)
        }
    }
}
