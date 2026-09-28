package com.kaiharimoto.neue.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.kaiharimoto.neue.res.Res
import com.kaiharimoto.neue.res.inter_bold
import com.kaiharimoto.neue.res.inter_medium
import com.kaiharimoto.neue.res.inter_regular
import com.kaiharimoto.neue.res.jetbrainsmono_medium
import com.kaiharimoto.neue.res.jetbrainsmono_regular
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Immutable
data class MuFonts(val sans: FontFamily, val mono: FontFamily, val sansName: String)

val LocalMuFonts = staticCompositionLocalOf { MuFonts(FontFamily.SansSerif, FontFamily.Monospace, "sans-serif") }

/**
 * The type scale of §3: 96 / 56 / 32 / 20 / 14 / 12 / 11, tight tracking on
 * everything 20 and up, positive tracking only on micro caps, weights 400, 500
 * and 700 and nothing else. Mono is for data and never for prose.
 */
object MuType {
    /** `ss01` single-storey a, `cv11`, tabular numbers everywhere (§3). */
    private const val FEATURES = "ss01, cv11, tnum"

    /**
     * Inter, the family's shipped face. Neue Haas Grotesk Display Pro comes
     * first in the web stack when a machine has it installed, but it is licensed
     * from Monotype, never bundled, and Skia can only hand one of its weights to
     * Compose as a single typeface — which would fake the 500 and the 700 the
     * scale is built on. Inter at its real weights is the better of the two.
     *
     * Compose resources rather than the JVM classpath (1.0.20), so the desktop and
     * Android read the same five files the same way.
     */
    @Composable
    fun rememberFamilies(): MuFonts {
        val inter = FontFamily(
            Font(Res.font.inter_regular, FontWeight.Normal),
            Font(Res.font.inter_medium, FontWeight.Medium),
            Font(Res.font.inter_bold, FontWeight.Bold),
        )
        val mono = FontFamily(
            Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
            Font(Res.font.jetbrainsmono_medium, FontWeight.Medium),
        )
        return remember(inter, mono) { MuFonts(sans = inter, mono = mono, sansName = "Inter") }
    }

    private fun style(
        fonts: MuFonts,
        size: TextUnit,
        weight: FontWeight,
        tracking: TextUnit = 0.sp,
        leading: Float,
        mono: Boolean = false,
    ) = TextStyle(
        fontFamily = if (mono) fonts.mono else fonts.sans,
        fontSize = size,
        fontWeight = weight,
        letterSpacing = tracking,
        lineHeight = (size.value * leading).sp,
        fontFeatureSettings = if (mono) "tnum" else FEATURES,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    fun displayXl(f: MuFonts) = style(f, 96.sp, FontWeight.Bold, (-0.035).em, 0.88f)
    fun display(f: MuFonts) = style(f, 56.sp, FontWeight.Bold, (-0.03).em, 0.92f)
    fun h1(f: MuFonts) = style(f, 32.sp, FontWeight.Medium, (-0.02).em, 1.05f)
    fun h2(f: MuFonts) = style(f, 20.sp, FontWeight.Medium, (-0.01).em, 1.15f)
    fun body(f: MuFonts) = style(f, 14.sp, FontWeight.Normal, leading = 1.45f)
    fun row(f: MuFonts) = style(f, 13.sp, FontWeight.Normal, leading = 1.4f)
    fun small(f: MuFonts) = style(f, 12.sp, FontWeight.Normal, leading = 1.4f)
    fun help(f: MuFonts) = style(f, 11.sp, FontWeight.Normal, leading = 1.35f)

    /** Label voice: 11px, 500, +0.08em. The caller uppercases the text (see `Micro`). */
    fun micro(f: MuFonts, size: TextUnit = 11.sp) = style(f, size, FontWeight.Medium, 0.08.em, 1.2f)

    /** Every number, index, count, percentage, key. */
    fun mono(f: MuFonts, size: TextUnit = 11.sp) = style(f, size, FontWeight.Normal, leading = 1.3f, mono = true)

    /** The wordmark: 13px bold uppercase, −0.02em. */
    fun wordmark(f: MuFonts) = style(f, 13.sp, FontWeight.Bold, (-0.02).em, 1f)
}

/** No ripple and no press overlay: state changes are colour changes (§7). */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0
}
