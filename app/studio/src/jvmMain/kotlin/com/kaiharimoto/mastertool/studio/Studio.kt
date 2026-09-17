package com.kaiharimoto.mastertool.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.data.CardRepository
import com.kaiharimoto.mastertool.core.data.DatabaseFactory
import com.kaiharimoto.mastertool.core.data.DeckRepository
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.scene.DeskLight
import com.kaiharimoto.mastertool.core.scene.Scene
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import com.kaiharimoto.mastertool.core.update.Release
import com.kaiharimoto.mastertool.core.update.UpdateChecker
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.DeckFileAccess
import com.kaiharimoto.mastertool.ui.ImportedFile
import com.kaiharimoto.mastertool.ui.SafeArea
import com.kaiharimoto.mastertool.ui.components.CardBackChoice
import com.kaiharimoto.mastertool.ui.components.LocalCardBack
import com.kaiharimoto.mastertool.ui.theme.LocalPrismaticCards
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderScreen
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckLayoutState
import com.kaiharimoto.mastertool.ui.fx.LocalFeedback
import com.kaiharimoto.mastertool.ui.fx.rememberFeedback
import com.kaiharimoto.mastertool.ui.library.DeckLibraryScreen
import com.kaiharimoto.mastertool.ui.play.PlayScreen
import com.kaiharimoto.mastertool.ui.theme.MasterToolTheme
import com.kaiharimoto.mastertool.ui.update.AppUpdater
import com.kaiharimoto.mastertool.ui.update.InstallOutcome
import com.kaiharimoto.mastertool.ui.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import com.kaiharimoto.mastertool.core.tune.StageTuning
import com.kaiharimoto.mastertool.core.tune.TuningCodec
import java.io.File
import java.util.UUID

/**
 * The studio: the play stage, drawn to a PNG, with nobody watching.
 *
 * Every other check in this repository is an assertion. `GoldenStageTest` knows
 * where a shadow's corners landed and `SceneryTest` knows how tall the lamp is,
 * and between them they can prove a change did what it said — but neither can
 * say whether the room *looks* like a room. That question has only ever been
 * answerable on the tablet, which puts a whole release between an idea and the
 * first look at it.
 *
 * This closes that loop. [ImageComposeScene] composes the real screen — the real
 * theme, the real dependencies, real card art off the network — onto a raster
 * surface whose frame clock the caller advances by hand, and hands back a Skia
 * image. No window, no X server, no GPU.
 *
 * Four things about it are deliberate.
 *
 * **It composes `PlayScreen`, not a stand-in.** A harness that draws its own
 * approximation of the stage is a harness that eventually disagrees with the
 * stage, and the disagreement is always found on the device. [Stage] below is a
 * copy of `MasterToolApp`'s scaffolding, in the same order, for that reason.
 *
 * **The frame clock is a parameter.** Compose's animations and the stage's own
 * `withFrameNanos` loop both advance by exactly what `render` is handed, so a
 * settled table and a card twelve frames into a flight are both reachable and
 * both reproducible.
 *
 * **One composition, many shots.** Starting the app costs a card-pool sync and
 * a few hundred frames of settling; a room change costs a preference write and
 * a seat change costs a keystroke. So the shot list is walked inside a single
 * composition, which is the difference between a contact sheet in thirty
 * seconds and one in six minutes — and an instrument nobody waits for is an
 * instrument nobody uses.
 *
 * **It drives the app the way a hand does.** The seat is chosen by sending the
 * digit its own shortcut table binds, not by reaching into the camera. Anything
 * the studio can photograph is therefore something a user can reach.
 */
fun main(args: Array<String>) {
    val opts = Options.parse(args)
    opts.out.mkdirs()

    println("[studio] out=${opts.out.absolutePath}  ${opts.width}x${opts.height} @${opts.density}x")
    println("[studio] shots: ${opts.shots.joinToString(" ") { it.name }}")

    val deps = buildDependencies(opts)

    // Warm the pool before composing. The screen would fetch it itself, but a
    // sync landing halfway through a shot is a shot with half a deck in it.
    runBlocking {
        println("[studio] card pool: ${deps.cardRepository.sync(force = false)}")
    }

    val director = Director()

    // Compose runs on the AWT event thread even with no window, because that is
    // where the desktop effect dispatchers post. Headless AWT is happy with
    // this; it simply never opens one.
    runBlocking(Dispatchers.Swing) {
        val scene = ImageComposeScene(
            width = opts.width,
            height = opts.height,
            density = Density(opts.density),
            coroutineContext = coroutineContext,
        ) {
            Stage(deps, director, opts)
        }

        try {
            val clock = FrameClock(scene, opts.pauseMillis)
            // The tuning goes in **before** the settle and never again.
            //
            // Writing it per shot alongside the room raced the seat press: a
            // preferences write is a coroutine, the tuned camera is placed by a
            // `SideEffect` on the composition that write causes, and the seat
            // key press is sent from here — so which of the two landed first
            // depended on the dispatcher. Two runs of the same tuning then came
            // back with the camera in two different places. Once, up front, and
            // the settle absorbs it.
            director.prefs?.update { it.copy(stageTuning = opts.tuning) }

            // The deck arrives over an import and the art over the network, and
            // the deal springs for about a second after both. Everything here is
            // waiting for that, once.
            clock.run(opts.settleFrames)

            // A script, if one was asked for: `--keys=nd` deals and draws. Each
            // press gets its own settle, because a stage mid-spring is a
            // different picture from the one that press was meant to produce.
            for (char in opts.keys) {
                val key = Keys.of(char) ?: error("nothing is bound to '$char' that the studio can press")
                Keys.press(scene, key)
                clock.run(opts.shotFrames)
            }

            for (shot in opts.shots) {
                director.prefs?.update { it.copy(scene = shot.scene, deskLight = shot.light) }
                // A shot name carries a seat, and pressing it aims the camera —
                // which overwrites a tuned pose completely. So a tuning with a
                // camera in it *is* the camera, and the seat in the name is
                // ignored. Without this, `--tune` could not be used to check the
                // one group of knobs it was most wanted for: every shot came
                // back at its named seat with only the lens surviving, because
                // `aimAt(seat)` carries the lens across and nothing else.
                //
                // And the seat is a *play stage* idea, so it may not be pressed
                // on a screen that is not the play stage. In the builder `1`,
                // `2` and `3` are FOCUS_MAIN / FOCUS_EXTRA / FOCUS_SIDE, and an
                // unrecognised name falls back to TABLE — so every builder shot
                // was taken with two of the three deck panes collapsed, which is
                // what `tools/devices.sh --screen=builder` has been
                // photographing. Worse, `focusSection` toggles and persists, so
                // consecutive runs alternated between two layouts and the
                // studio's two-runs-are-bit-identical contract did not hold.
                val seat = shot.seat
                if (seat != null && opts.aimsAtTable && opts.tuning.camera == StageTuning.DEFAULT.camera) {
                    Keys.press(scene, seat.digit)
                }
                // A seat change is a spring, and the room's palette crosses a
                // recomposition. Both are done well inside this.
                clock.run(opts.shotFrames)

                // Then the finger, if this run has one. After the seat, because
                // both of the things it is for — opening a pile, turning the
                // table — are aimed at a place on the glass, and where that
                // place is depends on where the camera ended up.
                opts.drag?.let { (from, to) ->
                    Pointer.drag(
                        scene, clock,
                        from.first * opts.width, from.second * opts.height,
                        to.first * opts.width, to.second * opts.height,
                    )
                }
                opts.tap?.let { (x, y) ->
                    Pointer.tap(scene, clock, x * opts.width, y * opts.height)
                }

                val png = clock.frame().encodeToData(EncodedImageFormat.PNG)
                    ?: error("Skia declined to encode ${shot.name}")
                val file = File(opts.out, "${shot.name}.png")
                file.writeBytes(png.bytes)
                println("[studio] ${file.name}  ${png.size / 1024} KiB")
            }

            if (opts.budget > 0) measure(scene, clock, opts.budget)
        } finally {
            scene.close()
        }
    }

    // Ktor's OkHttp engine and SQLDelight both hold non-daemon threads. Every
    // shot is on disk by the time this runs.
    kotlin.system.exitProcess(0)
}

// ---- what a frame costs -------------------------------------------------------------

/**
 * How long the stage takes to raster, with something moving on it.
 *
 * `FrameProbe` already answers this on the device, behind three taps on the
 * life-point total, and it is the honest number because it is measured on the
 * panel the app has to hit. But it needs a device and a person holding it,
 * which puts the loop's affordability gate out of reach of the loop — so a
 * change gets shipped and *then* found to be expensive.
 *
 * This is the cheap standing-in number. It is not the tablet: it is one
 * software raster of the whole scene on a container's CPU, and the absolute
 * milliseconds mean very little. What means a great deal is the **ratio** — a
 * shading term that doubles this will not be free on a tablet either, and that
 * is a fact worth having before the release rather than after it.
 *
 * Measured with a deal in flight rather than at rest, because a settled table
 * steps nothing: `MatPilot`'s loop skips parked cards by design, so an idle
 * stage measures the cost of drawing sixty things and none of the cost of
 * moving them.
 */
private suspend fun measure(scene: ImageComposeScene, clock: FrameClock, frames: Int) {
    // Twice, and the first one is thrown away. A cold JVM's first pass through
    // the draw path is JIT and layer allocation, and it showed up as a p95 nine
    // times the median — a number that says nothing about the renderer and
    // would drown any change worth measuring.
    repeat(2) { pass ->
        Keys.press(scene, Key.N) // deal again, so there is something in the air
        clock.run(WARM_FRAMES)
        val samples = LongArray(frames) { clock.timed() }
        if (pass == 0) return@repeat
        report(samples, frames)
    }
}

/** Long enough for the deal to be genuinely in flight when the stopwatch starts. */
private const val WARM_FRAMES = 20

private fun report(samples: LongArray, frames: Int) {
    samples.sort()
    fun ms(nanos: Long) = nanos / 1_000_000.0
    println(
        "[studio] frame cost over $frames frames: " +
            "median ${"%.2f".format(ms(samples[frames / 2]))}ms  " +
            "p95 ${"%.2f".format(ms(samples[(frames * 95 / 100).coerceAtMost(frames - 1)]))}ms  " +
            "worst ${"%.2f".format(ms(samples.last()))}ms",
    )
}

// ---- driving it ---------------------------------------------------------------------

private const val FRAME_NANOS = 16_666_667L

/**
 * A frame clock that advances in real time and in stage time at once.
 *
 * The stage's springs integrate the nanos it is handed; the network hands back
 * card art on other threads and needs the wall clock to move for its callbacks
 * to land. Advancing only one of the two is how a harness photographs either a
 * frozen table or a table with no pictures on it.
 */
internal class FrameClock(private val scene: ImageComposeScene, private val pauseMillis: Long) {
    private var nanos = 0L

    suspend fun run(frames: Int) {
        repeat(frames) {
            scene.render(nanos)
            nanos += FRAME_NANOS
            if (pauseMillis > 0) delay(pauseMillis)
        }
    }

    fun frame() = scene.render(nanos)

    /** One frame, and how long it took to raster. No pause: this is a stopwatch. */
    fun timed(): Long {
        val started = System.nanoTime()
        scene.render(nanos)
        val took = System.nanoTime() - started
        nanos += FRAME_NANOS
        return took
    }
}

/**
 * A published handle on the screen's own state holders.
 *
 * The room is a *preference*, so changing it from outside the composition means
 * reaching the same object the settings sheet reaches. Everything here happens
 * on the AWT thread, which is where snapshot writes belong.
 */
private class Director {
    var prefs: DeckLayoutState? = null
}

// ---- the screen under test ---------------------------------------------------------

/**
 * `MasterToolApp`'s scaffolding with the navigation removed.
 *
 * Kept beside the real one on purpose: every local this provides is one the play
 * stage reads, and a studio that provided a different card back or a different
 * theme would be photographing a slightly different app.
 */
@Composable
private fun Stage(deps: AppDependencies, director: Director, opts: Options) {
    val scope = rememberCoroutineScope()
    val builderState = remember { DeckBuilderState(deps, scope) }
    val layoutState = remember { DeckLayoutState(deps.preferencesRepository, scope) }
    val updateState = remember { UpdateState(deps.updateChecker, deps.updater, scope) }

    DisposableEffect(Unit) {
        configureImageLoader(deps.imageCacheDir)
        layoutState.start { preferences ->
            builderState.onFormatChange(preferences.format)
            builderState.onSearchEffectsChange(preferences.searchEffects)
        }
        builderState.start()
        director.prefs = layoutState
        // The deck arrives through the ordinary import path, so the studio is
        // exercising the same codec the tablet does.
        builderState.importFromFile()
        onDispose { director.prefs = null }
    }

    val feedback = rememberFeedback(enabled = { false })

    MasterToolTheme(mode = layoutState.preferences.themeMode) {
        CompositionLocalProvider(
            LocalFeedback provides feedback,
            LocalCardBack provides CardBackChoice(layoutState.preferences.cardBackUrl),
            // Provided rather than left to the default for the reason the whole
            // harness exists: a shot has to be of the app, and the app reads
            // this from the same document.
            LocalPrismaticCards provides layoutState.preferences.prismaticCards,
        ) {
            SafeArea {
                // The whole app, or the play stage on its own.
                //
                // `MasterToolApp` opens on the deck builder, which is the screen
                // a person actually spends their time in — and until this flag
                // existed the studio could not see it at all. That is not a gap
                // in coverage, it is the reason a broken builder shipped: the
                // harness was pointed at the one screen that was fine.
                when (opts.screen) {
                    // `DeckBuilderScreen` rather than `MasterToolApp`, because
                    // the app makes its *own* `DeckBuilderState` and never loads
                    // anything into it. `--deck` was a no-op on this screen: the
                    // studio imported the deck into the state above and then
                    // photographed a second, empty one — "Untitled Deck", 0/0/0.
                    // Handing over the state that already holds the deck is also
                    // one card-pool sync rather than two.
                    "builder" -> DeckBuilderScreen(
                        state = builderState,
                        layout = layoutState,
                        updateState = updateState,
                        onOpenLibrary = {},
                        onOpenPlay = {},
                    )
                    "library" -> DeckLibraryScreen(deps = deps, onOpenDeck = {}, onBack = {})
                    else -> PlayScreen(
                        state = builderState,
                        prefs = layoutState,
                        onBack = {},
                        seed = opts.seed,
                    )
                }
            }
        }
    }
}

// ---- wiring ------------------------------------------------------------------------

private fun buildDependencies(opts: Options): AppDependencies {
    val data = opts.data.apply { mkdirs() }
    val database = DatabaseFactory.create(
        StudioDriverFactory(File(data, DatabaseFactory.DATABASE_NAME).absolutePath)
    )
    val api = YgoProDeckApi(HttpClientFactory.create())

    return AppDependencies(
        cardRepository = CardRepository(
            database = database,
            api = api,
            clock = System::currentTimeMillis,
            ioDispatcher = Dispatchers.IO,
        ),
        deckRepository = DeckRepository(
            database = database,
            clock = System::currentTimeMillis,
            ioDispatcher = Dispatchers.IO,
        ),
        preferencesRepository = PreferencesRepository(
            database = database,
            ioDispatcher = Dispatchers.IO,
        ),
        fileAccess = FixedDeck(opts.deck),
        updateChecker = UpdateChecker(
            api = GitHubReleaseApi(HttpClientFactory.create()),
            currentVersionName = STUDIO,
        ),
        updater = NoUpdates,
        newDeckId = { UUID.randomUUID().toString() },
        now = System::currentTimeMillis,
        imageCacheDir = File(data, "card-art").absolutePath,
    )
}

private const val STUDIO = "studio"

/** The picker, answered before it is asked. */
private class FixedDeck(private val file: File) : DeckFileAccess {
    override suspend fun importDeck(): ImportedFile? =
        if (file.isFile) {
            ImportedFile(file.name, file.readText())
        } else {
            println("[studio] no deck at ${file.absolutePath} — the table will be empty")
            null
        }

    override suspend fun exportDeck(suggestedName: String, content: String): Boolean = false

    override suspend fun shareDeck(suggestedName: String, content: String) = Unit
}

private object NoUpdates : AppUpdater {
    override val currentVersionName = STUDIO
    override val canInstallInPlace = false
    override suspend fun downloadAndInstall(
        release: Release,
        onProgress: (Float?) -> Unit,
    ): InstallOutcome = InstallOutcome.Failed("the studio does not install anything")

    override fun openReleasePage(url: String) = Unit
}

// ---- options -----------------------------------------------------------------------

/**
 * Which of the four seats a shot is taken from, and the digit that reaches it.
 *
 * [POV] is the one that matters most and was the one the eye could not reach:
 * `docs/LOOP.md` §6 recorded that every unparameterised shot came back at five,
 * twenty-one or thirty-four degrees, so the seat a head at a desk actually sits
 * at had never been in a contact sheet. It is the seat the stage opens at now.
 */
private enum class Seat(val digit: Key) {
    OVERHEAD(Key.One), TABLE(Key.Two), SEATED(Key.Three), POV(Key.Four)
}

/**
 * One picture. A null [seat] means **do not press a seat at all**.
 *
 * That is not a convenience, it is the only way to photograph the view kai
 * actually gets when he opens the app. Every other shot types a digit first, so
 * for as long as the studio has existed it has been unable to see the *opening*
 * camera — which is how `CameraTune`'s defaults went several releases without
 * reaching the rig at all and nobody noticed. Name a shot `opening` for it.
 */
private class Shot(val name: String, val scene: Scene, val light: DeskLight, val seat: Seat?)

/**
 * `--pitch=80`, `--yaw=45`: aim the whole run, rather than one shot.
 *
 * The envelope runs to eighty degrees of pitch and can be spun all the way
 * round, and `docs/LOOP.md` §6 records that neither has ever been in a contact
 * sheet — eighty in particular, because it is where a procedural surface
 * aliases worst *and* where the table's horizon comes onto the glass. Before
 * this the only way there was to write a tuning file by hand.
 *
 * **A run, not a shot, and that is not laziness.** Writing a tuning per shot
 * next to the room is the bug the settle comment above `prefs.update` records:
 * a preferences write is a coroutine, the tuned camera is placed by a
 * `SideEffect` on the composition that write causes, and the seat press is sent
 * from here, so which landed first depended on the dispatcher and two runs of
 * one tuning came back with the camera in two places. This folds into
 * `opts.tuning` at parse time instead, where it goes in once and the settle
 * absorbs it — and it then trips the existing rule that a tuning carrying a
 * camera *is* the camera, so the seat in every shot name is ignored for that
 * run. Which is right: `--pitch=80` means eighty, not eighty-then-whatever-the
 * -name-said.
 *
 * Clamped by the envelope like any other tuning, so a number outside it comes
 * back as the nearest legal one rather than as a picture of nothing.
 */
private fun StageTuning.aimedBy(map: Map<String, String>): StageTuning {
    val pitch = map["pitch"]?.toFloatOrNull()
    val yaw = map["yaw"]?.toFloatOrNull()
    if (pitch == null && yaw == null) return this
    return copy(
        camera = camera.copy(
            pitchDegrees = pitch ?: camera.pitchDegrees,
            yawDegrees = yaw ?: camera.yawDegrees,
        ),
    ).sanitised()
}

private class Options(
    val out: File,
    val data: File,
    val deck: File,
    val width: Int,
    val height: Int,
    val density: Float,
    val settleFrames: Int,
    val shotFrames: Int,
    val pauseMillis: Long,
    val keys: String,
    val seed: Long,
    /**
     * `play` (the default), `builder` or `library`. Anything unrecognised — and
     * `table`, which is not a branch of its own — falls through to `play`.
     */
    val screen: String,
    val budget: Int,
    /**
     * The tuning to shoot with, from `--tune=file.json`.
     *
     * `StageTuning.DEFAULT` — what ships — unless a file says otherwise, so
     * every existing shot and every golden contact sheet is unchanged.
     */
    val tuning: StageTuning,
    /**
     * `--tap=0.27,0.70`: where to put a finger, in fractions of the glass.
     *
     * Fractions rather than a slot name, and `Pointer`'s KDoc has the argument:
     * re-solving the board and the camera here would be a second copy of both
     * that has to agree with the one the screen reached, possibly through a
     * seat press and `CameraFit`. Found by looking at a shot, which is the
     * loop's own method.
     */
    val tap: Pair<Float, Float>?,
    /** `--drag=0.5,0.5:0.7,0.5`: a swept drag on the glass. On felt, an orbit. */
    val drag: Pair<Pair<Float, Float>, Pair<Float, Float>>?,
    val shots: List<Shot>,
) {
    /**
     * Whether this run is looking at the table, and so whether a shot name's
     * seat means anything. Only the play stage binds the digits to seats.
     */
    val aimsAtTable: Boolean get() = screen != "builder" && screen != "library"

    companion object {
        /**
         * The default contact sheet: both rooms, both hours, and every seat once.
         *
         * `desk-night-pov` is the newest and is the one to read first — it is
         * the seat the stage opens at, so it is the picture kai actually gets.
         * The other three are the room seen from above it, which is what the
         * sheet was entirely made of while `docs/LOOP.md` §6 was complaining
         * that the eye could only reach three seats by digit.
         */
        private val CONTACT = listOf(
            "desk-day-table", "desk-night-seated", "desk-day-overhead",
            "desk-night-pov", "minimal-day-table",
        )

        fun parse(args: Array<String>): Options {
            val map = args.mapNotNull { arg ->
                arg.removePrefix("--").split("=", limit = 2).takeIf { it.size == 2 }
                    ?.let { it[0] to it[1] }
            }.toMap()

            fun str(key: String, fallback: String) = map[key] ?: fallback
            fun int(key: String, fallback: Int) = map[key]?.toIntOrNull() ?: fallback

            val names = str("shots", CONTACT.joinToString(",")).split(",").filter { it.isNotBlank() }

            return Options(
                out = File(str("out", "build/shots")),
                data = File(str("data", System.getProperty("user.home") + "/.cache/mastertool-studio")),
                deck = File(str("deck", "../lab.ydkx")).absoluteFile,
                // The desktop window's own size, so a shot is what a desktop
                // user sees. `--density=2` re-solves every layout at tablet
                // metrics without changing the framing.
                width = int("width", 1600),
                height = int("height", 1000),
                density = map["density"]?.toFloatOrNull() ?: 1f,
                settleFrames = int("settle", 150),
                shotFrames = int("frames", 60),
                pauseMillis = map["pause"]?.toLongOrNull() ?: 6L,
                keys = str("keys", ""),
                screen = str("screen", "play").lowercase(),
                // One deal, every run, unless a shot is deliberately after a
                // different one. Two pictures of two different hands are not a
                // before and an after.
                seed = map["seed"]?.toLongOrNull() ?: 20260808L,
                budget = int("budget", 0),
                // A tuning exported off the tablet, replayed here. The whole
                // point of the round trip: a number kai moved with his thumb
                // becomes a picture the loop can diff, without a release.
                tuning = (
                    map["tune"]?.let { path ->
                        val file = File(path).absoluteFile
                        require(file.isFile) { "no tuning at $file" }
                        TuningCodec.parse(file.readText())
                            ?: error("$file is not a tuning export")
                    } ?: StageTuning.DEFAULT
                    ).aimedBy(map),
                tap = map["tap"]?.let(::point),
                drag = map["drag"]?.let { spec ->
                    val ends = spec.split(":")
                    require(ends.size == 2) { "--drag wants from:to, got $spec" }
                    point(ends[0]) to point(ends[1])
                },
                shots = names.map(::shotOf),
            )
        }

        /**
         * `desk-night-seated` and friends.
         *
         * A name rather than three flags because a shot's name is also its
         * filename, and a contact sheet whose files are `a.png`, `b.png` is a
         * contact sheet nobody can talk about.
         */
        /** `0.27,0.70` — a place on the glass, as fractions of it. */
        private fun point(spec: String): Pair<Float, Float> {
            val parts = spec.split(",")
            require(parts.size == 2) { "a point is x,y in fractions of the glass, got $spec" }
            val x = parts[0].toFloatOrNull()
            val y = parts[1].toFloatOrNull()
            require(x != null && y != null) { "a point is two numbers, got $spec" }
            return x to y
        }

        private fun shotOf(name: String): Shot {
            val parts = name.lowercase().split("-")
            fun has(vararg words: String) = parts.any { it in words }
            return Shot(
                name = name,
                scene = if (has("minimal")) Scene.MINIMAL else Scene.DESK,
                light = if (has("night")) DeskLight.NIGHT else DeskLight.DAY,
                seat = when {
                    has("opening", "open") -> null
                    has("overhead") -> Seat.OVERHEAD
                    has("seated") -> Seat.SEATED
                    has("pov", "head", "chair") -> Seat.POV
                    else -> Seat.TABLE
                },
            )
        }
    }
}
