package com.kaiharimoto.mastertool.core.layout

/**
 * What the app is running on, which decides the shell and the builder it draws
 * (the phone, v1.3.5).
 *
 * [Posture] answers *which way round* a window is; this answers *how much of
 * it there is*. A phone held sideways is WIDE and still has no room for a pool,
 * a deck and an inspector side by side, so the two are asked separately.
 *
 * The line is the one Android itself draws between a phone and a tablet: a
 * smallest width under [PHONE_BELOW] dp. It is the *smallest* width — the
 * shorter side — so turning a phone round never turns it into a tablet, and a
 * tablet in portrait stays a tablet. The desk is never a phone: a small desktop
 * window is a window to make bigger, not a reason to hide the rail.
 */
enum class FormFactor {
    DESK,
    TABLET,
    PHONE;

    val isPhone: Boolean get() = this == PHONE

    companion object {
        const val PHONE_BELOW = 600f

        fun of(widthDp: Float, heightDp: Float, touch: Boolean): FormFactor = when {
            !touch -> DESK
            minOf(widthDp, heightDp) < PHONE_BELOW -> PHONE
            else -> TABLET
        }

        /** The same test from Android's own `smallestScreenWidthDp`. */
        fun ofSmallestWidth(smallestDp: Float, touch: Boolean): FormFactor = of(smallestDp, smallestDp, touch)
    }
}

/**
 * Which way the screen may turn (the phone, v1.3.5): kai's three-way choice,
 * a setting and a one-tap toggle. Stored by [key] in `NeuePreferences.orientation`,
 * where null is the device's own default — [defaultFor].
 */
enum class ScreenOrientation(val key: String, val label: String) {
    PORTRAIT("portrait", "Portrait"),
    LANDSCAPE("landscape", "Landscape"),
    AUTO("auto", "Auto");

    /** The toggle's next state: Portrait → Landscape → Auto → Portrait. */
    fun next(): ScreenOrientation = entries[(ordinal + 1) % entries.size]

    companion object {
        fun parse(key: String?): ScreenOrientation? = entries.firstOrNull { it.key == key }

        /** A phone is held upright; a tablet (and the desk, which never asks) lies down. */
        fun defaultFor(form: FormFactor): ScreenOrientation =
            if (form == FormFactor.PHONE) PORTRAIT else LANDSCAPE

        /** The stored choice, else the device's default. */
        fun resolve(stored: String?, form: FormFactor): ScreenOrientation = parse(stored) ?: defaultFor(form)

        /**
         * The turn in force: [resolve], unless the page on screen lies down whatever was chosen —
         * Present (1.0.70, kai: "android should be landscape only but it should work"), whose
         * slides are 16:9.
         */
        fun resolve(stored: String?, form: FormFactor, forceLandscape: Boolean): ScreenOrientation =
            if (forceLandscape) LANDSCAPE else resolve(stored, form)
    }
}
