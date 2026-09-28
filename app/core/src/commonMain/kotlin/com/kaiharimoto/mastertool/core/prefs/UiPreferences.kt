package com.kaiharimoto.mastertool.core.prefs

import com.kaiharimoto.mastertool.core.deck.SortMode
import com.kaiharimoto.mastertool.core.layout.PoolStop
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.scene.DeskLight
import com.kaiharimoto.mastertool.core.scene.Scene
import com.kaiharimoto.mastertool.core.tune.StageTuning
import kotlinx.serialization.Serializable

/** Which colour scheme the app renders in. */
@Serializable
enum class ThemeMode {
    /** Follow the operating system. */
    SYSTEM,
    DARK,
    LIGHT,
}

/** How one deck pane is laid out. */
@Serializable
data class SectionPreferences(
    /** Share of the deck column's height, relative to the other panes. */
    val weight: Float,
    /** Cards per row, used only when [autoFit] is off. */
    val columns: Int,
    /**
     * Size the cards so the whole section is visible, rather than by hand.
     *
     * On by default. A deck is a small, known quantity and the useful default is
     * to see all of it; picking a column count manually means re-picking it every
     * time the deck grows past a row.
     */
    val autoFit: Boolean = true,
    val collapsed: Boolean = false,
    val sortMode: SortMode = SortMode.MANUAL,
) {
    fun sanitised(fallbackWeight: Float): SectionPreferences = copy(
        weight = if (weight.isFinite()) weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT) else fallbackWeight,
        columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
    )

    companion object {
        const val MIN_WEIGHT = 0.25f
        const val MAX_WEIGHT = 8f
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 20
    }
}

/**
 * Layout settings, stored as one JSON document.
 *
 * One document rather than a column per setting so that adding a preference is a
 * field and never a schema migration — the file is read with unknown keys
 * ignored, so an older build opening a newer document keeps working too.
 *
 * Everything here is read back off disk, which is why [sanitised] exists and is
 * applied on both load and save. A weight of zero or NaN would reach
 * `Modifier.weight`, which rejects both, and a settings file is exactly the kind
 * of thing that survives a half-written update.
 */
@Serializable
data class UiPreferences(
    /** Share of the window given to search, against the deck panes. */
    val searchWeight: Float = DEFAULT_SEARCH_WEIGHT,
    /**
     * Cards per row in the search grid; 0 means size to fit.
     *
     * Four by default: the pool is scanned, not read, and four across is the
     * width at which a card's art is recognisable at arm's length without the
     * column becoming a list.
     */
    val searchColumns: Int = 4,
    /**
     * Whether the card database is on screen at all.
     *
     * Switching it off is the "now I am looking at the deck" move: the pool is
     * where cards come from, and once they are in, it is just the thing between
     * you and the list. The deck column takes the whole window instead.
     */
    val searchVisible: Boolean = true,
    /**
     * Cards per row in the pool, in a portrait window.
     *
     * Three, against the four a tablet gets, and for the opposite reason to
     * [tallColumns]: the pool is where you *recognise* a card rather than count
     * one, and three across a 360dp phone is a 109dp tile — big enough to read
     * the name badge on, and a comfortable thumb target on a surface that is
     * mostly reached by thumb.
     */
    val tallSearchColumns: Int = 3,
    /**
     * Whether a query is also read against the text printed under the name.
     *
     * **On**, which is safe in a way a scope setting usually is not: every text
     * hit scores below every name hit (see `EffectMatching`), so switching this
     * on can only append rows to the bottom of a list whose top is unchanged.
     * Off is for the moment the appended rows are in the way.
     *
     * It is a preference at all because the alternative was making people type
     * `text:` every time, and the archetype kai was chasing — every card that
     * mentions "Light and Darkness Ritual" — is not a thing you look up once.
     */
    val searchEffects: Boolean = true,
    /**
     * Size the three panes from their row widths so all of them are visible.
     *
     * The alternative — each pane taking a share of the column height and
     * choosing its own column count to suit — is what put cards out of bounds:
     * three panes sized against heights a divider drag handed them cannot agree
     * on a total. On by default; dragging a divider is what turns it off.
     */
    val fitAll: Boolean = true,
    // Ten across for the main deck and fifteen for the extra and side: the row
    // widths a decklist is read in — four rows of ten is forty at a glance, and
    // an extra or side deck is one row of its own maximum.
    val main: SectionPreferences = SectionPreferences(weight = 2f, columns = 10),
    val extra: SectionPreferences = SectionPreferences(weight = 1f, columns = 15),
    val side: SectionPreferences = SectionPreferences(weight = 1f, columns = 15),
    /** Show one tile per distinct card with a count, rather than one per copy. */
    val stacked: Boolean = false,
    /**
     * Cards per row in a portrait window — one number for all three sections.
     *
     * Separate from [main], [extra] and [side] because a phone and a tablet are
     * looked at on the same device now, and a column count written by one of
     * them must not reach the other: rotating a tablet to portrait and back
     * would otherwise leave the tablet builder wearing the phone's row width.
     *
     * One number rather than three because the tablet's hierarchy — a main-deck
     * card half again the size of an extra-deck one — is a luxury of width. In
     * portrait the sections are told apart by their headers, and the cards are
     * better off all being as large as the narrowest of them can be.
     *
     * Six is a card about 56dp wide on a 360dp phone, which is the width at
     * which the art is still a picture rather than a swatch.
     */
    val tallColumns: Int = 6,
    /**
     * How far the card pool is open in a portrait window.
     *
     * Remembered because it is a working posture rather than a transient: a
     * person who dragged the pool down to see their whole deck should find it
     * down again next time, the same way the search split is remembered on a
     * tablet.
     */
    val poolStop: PoolStop = PoolStop.HALF,
    /**
     * An image to use for the card back instead of the one that ships.
     *
     * Blank by default. The back is now kai's own artwork, bundled — see
     * `ui/components/CardBack.kt` — which is what retired the `cardBack` style
     * that used to sit here and pick between two *drawn* ones. This field
     * outlived it because the reason for it is different and unchanged: the
     * official back is Konami's artwork and this project is public, so it is
     * not shipped here, and anyone who wants the real thing points this at it on
     * their own device, where it is cached like any other card image.
     */
    val cardBackUrl: String = "",
    /**
     * The foil edge every card wears, in the builder and on the play stage.
     *
     * **On by default**, which is a deliberate exception to the handbook. The
     * prismatic ramp is spent sparingly everywhere else — `docs/classic/DESIGN.md` says
     * fringing everything reads as decoration and fringing the thing under your
     * finger reads as light, and that rule stands for the *fringe*. This is the
     * other effect: the original tool put it on every card in the deck grid, it
     * is what a sleeved card in a binder actually does, and kai asked for it
     * back by name. Default off would have meant restoring something nobody
     * could see without first finding a switch.
     *
     * A preference rather than a constant because it is a matter of taste that a
     * person can hold an opinion about, and because the honest answer to "this
     * is too much" is a way to turn it off rather than an argument.
     */
    val prismaticCards: Boolean = true,
    val format: Format = Format.TCG,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Which room the play stage is in.
     *
     * [Scene.MINIMAL] by default, and that is a decision rather than caution.
     * The minimal stage is what `docs/classic/DESIGN.md` describes and it is what the
     * app *is*; a desk is a thing you go and choose, the way you choose a card
     * back. A build that arrived one morning with a bedroom in it would have
     * changed the tool on somebody who had not asked.
     */
    val scene: Scene = Scene.MINIMAL,
    /**
     * Which lamp is on in the desk scene.
     *
     * [DeskLight.AUTO] reads the hour off the device, so a table opened at
     * midnight is lit by a lamp without anybody having said so. The two manual
     * values exist because a preference that can only be inferred is a
     * preference the user cannot disagree with.
     */
    val deskLight: DeskLight = DeskLight.AUTO,
    /**
     * Whether fingers on the felt move the camera.
     *
     * Off, which is the one default here chosen against the feature it governs.
     * *Fingers on a card move the card, fingers on the felt move the camera* is
     * still the control scheme and the felt is still most of the screen — which
     * is the problem: on a tablet held in two hands over a table you are playing
     * on, a great deal of what lands on the felt is not a request to walk round
     * the room. kai's report was simply "too many accidental touches", and every
     * one of them moves the whole board.
     *
     * The camera does not go away when this is off. The three seat buttons are
     * the touch idiom and were always the discoverable one; a mouse keeps the
     * wheel and the middle-drag, both of which reach the camera without going
     * through the arbiter at all. What is switched off is the surface that
     * cannot tell a gesture from a resting hand.
     *
     * Stored preferences do not have this key, so **existing installs get the
     * new default too** — which is deliberate, since they are the ones with the
     * complaint, and it is the kind of surprise that belongs in a release note.
     */
    val cameraTouch: Boolean = false,
    /**
     * Whether the tablet's own tilt moves the camera a degree or two.
     *
     * `docs/classic/AAA.md` #8, and the strongest "this is a place rather than a
     * picture" cue a handheld screen has: things at different depths move by
     * different amounts when you tip the device, which is what a window does and
     * what a photograph cannot.
     *
     * **Off, and for the same reason [cameraTouch] is off.** It is the second
     * thing on this stage that moves without anybody deciding to move it, and
     * the first one had to be switched off after kai played on it. A picture
     * that answers a wrist is either the best thing here or a wobble, and which
     * of those it is cannot be settled from a contact sheet — it wants a tablet
     * in two hands over a real game. So it ships reachable and not chosen.
     *
     * It costs nothing while it is off: the sensor is not registered at all
     * (`ui/fx/Tilt.kt`), rather than registered and ignored.
     */
    val headSway: Boolean = false,
    /**
     * Sound and haptic feedback. Null means "the platform's default" — on for
     * a tablet in the hands, off at a desk — until the user says otherwise.
     */
    val feedbackEnabled: Boolean? = null,
    /**
     * Passcodes the easter egg throws, when it has been given a set to keep.
     *
     * Empty means "whatever is in the deck right now", which is the useful
     * default and needs no curating.
     */
    val easterEggPool: List<Int> = emptyList(),
    /**
     * The numbers the play stage is tuned by.
     *
     * A field with a default, the same as everything else here, and that is the
     * whole of the plumbing — `StageTuning.DEFAULT` is what shipped, so a
     * document written before this existed reads back as the untouched stage.
     *
     * It is on the preferences rather than in a file of its own because it is a
     * preference: it is a person's opinion about how their table should look,
     * and it should survive a relaunch the way the room and the card back do.
     * Exporting it is a separate act — see `TuningCodec`.
     */
    val stageTuning: StageTuning = StageTuning.DEFAULT,
    /**
     * Which generation of the layout this document was written by.
     *
     * The one thing a defaulted field cannot do on its own: a preference that
     * was *stored* keeps its old value forever, so changing a default only ever
     * reaches new installs. Bumping this is how a change to what the layout
     * fundamentally is — row widths, whether the deck is fitted — reaches a
     * device that already has a document on it. Everything else here stays a
     * plain field with a default.
     */
    val layoutVersion: Int = 0,
) {
    operator fun get(section: DeckSection): SectionPreferences = when (section) {
        DeckSection.MAIN -> main
        DeckSection.EXTRA -> extra
        DeckSection.SIDE -> side
    }

    fun with(section: DeckSection, preferences: SectionPreferences): UiPreferences =
        when (section) {
            DeckSection.MAIN -> copy(main = preferences)
            DeckSection.EXTRA -> copy(extra = preferences)
            DeckSection.SIDE -> copy(side = preferences)
        }

    fun sanitised(): UiPreferences = copy(
        searchWeight = if (searchWeight.isFinite()) {
            searchWeight.coerceIn(MIN_SEARCH_WEIGHT, MAX_SEARCH_WEIGHT)
        } else {
            DEFAULT_SEARCH_WEIGHT
        },
        searchColumns = if (searchColumns <= 0) {
            0
        } else {
            searchColumns.coerceIn(SectionPreferences.MIN_COLUMNS, SectionPreferences.MAX_COLUMNS)
        },
        tallSearchColumns = tallSearchColumns.coerceIn(
            SectionPreferences.MIN_COLUMNS,
            SectionPreferences.MAX_COLUMNS,
        ),
        tallColumns = tallColumns.coerceIn(
            SectionPreferences.MIN_COLUMNS,
            SectionPreferences.MAX_COLUMNS,
        ),
        main = main.sanitised(fallbackWeight = 2f),
        extra = extra.sanitised(fallbackWeight = 1f),
        side = side.sanitised(fallbackWeight = 1f),
        // The only field here whose values arrive from a finger dragging a
        // slider, so the only one that can produce a NaN. One reaching
        // `StagePlane`'s trigonometry is a stage that is broken after a restart,
        // with nothing on screen and nothing in a log.
        stageTuning = stageTuning.sanitised(),
    )

    /**
     * The same document as written by today's build.
     *
     * Only the layout is rebuilt — the format, the theme, the feedback setting,
     * the pinned easter-egg pool and the search split are the person's, not the
     * generation's, and survive untouched.
     */
    fun migrated(): UiPreferences = when {
        layoutVersion >= LAYOUT_VERSION -> this
        else -> copy(
            layoutVersion = LAYOUT_VERSION,
            fitAll = DEFAULT.fitAll,
            searchColumns = DEFAULT.searchColumns,
            main = main.copy(columns = DEFAULT.main.columns, autoFit = true),
            extra = extra.copy(columns = DEFAULT.extra.columns, autoFit = true),
            side = side.copy(columns = DEFAULT.side.columns, autoFit = true),
        )
    }

    companion object {
        const val DEFAULT_SEARCH_WEIGHT = 0.36f
        const val MIN_SEARCH_WEIGHT = 0.2f
        const val MAX_SEARCH_WEIGHT = 0.7f

        /**
         * 1: ten across for the main deck and fifteen for the extra and side,
         * with all three panes sized to be visible at once.
         * 2: four across in the card pool.
         */
        const val LAYOUT_VERSION = 2

        val DEFAULT = UiPreferences(layoutVersion = LAYOUT_VERSION)
    }
}
