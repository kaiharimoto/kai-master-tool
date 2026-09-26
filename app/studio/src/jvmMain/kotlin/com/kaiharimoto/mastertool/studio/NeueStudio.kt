package com.kaiharimoto.mastertool.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.data.CardRepository
import com.kaiharimoto.mastertool.core.data.DatabaseFactory
import com.kaiharimoto.mastertool.core.data.DeckRepository
import com.kaiharimoto.mastertool.core.data.PreferencesRepository
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.YgoProDeckApi
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import com.kaiharimoto.mastertool.core.update.NeueUpdateChecker
import com.kaiharimoto.mastertool.core.update.Release
import com.kaiharimoto.mastertool.core.update.UpdateChecker
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.DeckFileAccess
import com.kaiharimoto.mastertool.ui.ImportedFile
import com.kaiharimoto.mastertool.ui.configureImageLoader
import com.kaiharimoto.mastertool.ui.update.AppUpdater
import com.kaiharimoto.mastertool.ui.update.InstallOutcome
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.NeueRoot
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.Selection
import com.kaiharimoto.neue.rememberHolders
import com.kaiharimoto.neue.update.NeueUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.UUID

/**
 * Neue Master Tool, photographed.
 *
 * The same idea as the play-stage studio — the real root composable, real
 * card art, a clock advanced by hand, no window — pointed at the desktop app.
 * One run is one picture, named by `--name`:
 *
 * ```
 * tools/shoot.sh --neue --page=builder --theme=ink --width=2560 --height=1440 --name=builder-ink
 * ```
 *
 * `--page` is decks, builder, odds, stats or settings. `--select=main:3` puts
 * a card in the inspector; `--hover=x,y` moves the pointer there (fractions of
 * the frame); `--lens=roles`, `--palette`, `--drawer=issues|groups`,
 * `--query=ash` and `--scale=1.25` set the rest of the scene.
 */
fun neueMain(args: Array<String>) {
    val map = args.mapNotNull { arg -> arg.removePrefix("--").split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else it[0] to "true" } }.toMap()
    val width = map["width"]?.toInt() ?: 1920
    val height = map["height"]?.toInt() ?: 1080
    val density = map["density"]?.toFloat() ?: 1f
    val out = File(map["out"] ?: "../shots").apply { mkdirs() }
    val name = map["name"] ?: "neue-${map["page"] ?: "builder"}"
    val data = File(map["data"] ?: File(System.getProperty("user.home"), ".cache/mastertool-studio").path).apply { mkdirs() }
    val deck = File(map["deck"] ?: "../ydk/lab.ydkx")

    val deps = dependencies(data, deck)
    runBlocking { println("[neue-studio] card pool: ${deps.cardRepository.sync(force = false)}") }

    var holders: NeueHolders? = null
    runBlocking(Dispatchers.Swing) {
        val scene = ImageComposeScene(width, height, Density(density), coroutineContext = coroutineContext) {
            Scene(deps, data) { holders = it }
        }
        try {
            val clock = FrameClock(scene, pauseMillis = (map["pause"] ?: "6").toLong())
            clock.run(20)
            val h = holders ?: error("the scene never composed")
            h.neue.update {
                it.copy(
                    theme = if (map["theme"] == "ink") NeueTheme.INK else NeueTheme.PAPER,
                    scale = map["scale"]?.toFloat() ?: 1f,
                    filtersOpen = map["filters"] == "true",
                    poolWidth = map["pool"]?.toFloat() ?: it.poolWidth,
                )
            }
            h.neue.page = when (map["page"]) {
                "decks" -> Page.DECKS
                "odds" -> Page.ODDS
                "stats" -> Page.STATS
                "settings" -> Page.SETTINGS
                else -> Page.BUILDER
            }
            clock.run((map["settle"] ?: "150").toInt())

            map["query"]?.let { h.builder.onQueryChange(it) }
            map["lens"]?.let { wanted -> Lens.entries.firstOrNull { it.name.equals(wanted, true) }?.let(h.builder::useLens) }
            map["select"]?.let { spec ->
                val (sectionName, index) = spec.split(":").let { it[0] to it[1].toInt() }
                val section = DeckSection.entries.first { it.name.equals(sectionName, true) }
                val id = h.builder.deck[section].getOrNull(index)
                val card = id?.let(h.builder.index::byId)
                if (card != null) h.neue.selection = Selection.InDeck(card, section, index)
            }
            // Zen is set by hand here: idleness is measured in wall-clock time, and a
            // studio run is minutes of it for seconds of frames.
            h.zenAuto = false
            if (map["immersive"] == "true") h.neue.immersive = true
            if (map["pinned"] == "true") h.neue.update { it.copy(railPinned = true) }
            map["reveal"]?.let { spec ->
                val parts = spec.split(",")
                h.neue.revealed = com.kaiharimoto.mastertool.core.layout.Revealed(left = "left" in parts, top = "top" in parts, bottom = "bottom" in parts)
            }
            if (map["palette"] == "true") h.neue.paletteOpen = true
            if (map["help"] == "true") h.neue.helpOpen = true
            map["drawer"]?.let { h.neue.drawer = if (it == "groups") Drawer.GROUPS else Drawer.ISSUES }
            if (map["goal"] == "true") h.builder.newGoal()
            clock.run((map["frames"] ?: "90").toInt())
            map["zen"]?.let { phase ->
                h.neue.immersive = true
                h.zen.gardenSeed = map["garden-seed"]?.toInt() ?: 7
                clock.run(30)
                h.neue.zen = if (phase == "quiet") com.kaiharimoto.mastertool.core.motion.ZenPhase.QUIET else com.kaiharimoto.mastertool.core.motion.ZenPhase.DEEP
                // The fades take under three seconds; the garden is raked for as long as asked.
                clock.run(((map["zen-seconds"] ?: "4").toFloat() * 60).toInt())
                // --garden-plan: when each composition is raked, held and wiped, for choosing --garden-at.
                if (map["garden-plan"] == "true") {
                    val stone = h.zen.deckInZen.let { com.kaiharimoto.mastertool.core.layout.GardenRect(it.left, it.top, it.right, it.bottom) }
                    val program = com.kaiharimoto.mastertool.core.layout.RakeProgram(width.toFloat(), height.toFloat(), stone, h.zen.gardenSeed ?: 7, h.zen.gardenLook.grain)
                    var t = com.kaiharimoto.mastertool.core.layout.RakeProgram.OPENING
                    val wipe = com.kaiharimoto.mastertool.core.layout.RakeLayer(com.kaiharimoto.mastertool.core.layout.Samon.CHOKUSEN, width.toFloat(), height.toFloat(), grain = h.zen.gardenLook.grain).duration
                    repeat(8) { n ->
                        val c = program.composition(n)
                        println("[neue-studio] garden %d %-9s raked %6.1f..%6.1f  held ..%6.1f  wiped ..%6.1f".format(
                            n, c.samon, t, t + c.duration, t + c.duration + 8f, t + c.duration + 8f + wipe))
                        t += c.duration + 8f + wipe + com.kaiharimoto.mastertool.core.layout.RakeProgram.REST
                    }
                }
                // --garden-at=T: the garden is a function of its clock, so jump it to T seconds in.
                map["garden-at"]?.let { at ->
                    h.zen.time += at.toFloat() - (map["zen-seconds"] ?: "4").toFloat()
                    clock.run(2)
                }
                // --garden-held=N: the first N compositions, each photographed finished.
                map["garden-held"]?.let { spec ->
                    val stone = h.zen.deckInZen.let { com.kaiharimoto.mastertool.core.layout.GardenRect(it.left, it.top, it.right, it.bottom) }
                    val program = com.kaiharimoto.mastertool.core.layout.RakeProgram(width.toFloat(), height.toFloat(), stone, h.zen.gardenSeed ?: 7, h.zen.gardenLook.grain)
                    var t = com.kaiharimoto.mastertool.core.layout.RakeProgram.OPENING
                    repeat(spec.toInt()) { n ->
                        val c = program.composition(n)
                        h.zen.time = t + c.duration + com.kaiharimoto.mastertool.core.layout.RakeProgram.HOLD / 2f
                        clock.run(2)
                        val still = clock.frame().encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                        File(out, "$name-$n-${c.samon.name.lowercase()}.png").writeBytes(still.bytes)
                        println("[neue-studio] $n ${c.samon}: raked in %.0fs".format(c.duration))
                        t += c.duration + com.kaiharimoto.mastertool.core.layout.RakeProgram.HOLD + program.grain.sweepTime(width.toFloat()) + com.kaiharimoto.mastertool.core.layout.RakeProgram.REST
                    }
                }
                // --garden-times=a,b,c: one still per garden time, in seconds. The garden's clock
                // started with zen's, so setting zen's sets the garden's.
                map["garden-times"]?.let { spec ->
                    spec.split(",").forEach { at ->
                        h.zen.time = at.toFloat()
                        clock.run(2)
                        val still = clock.frame().encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                        File(out, "$name-t$at.png").writeBytes(still.bytes)
                    }
                    println("[neue-studio] garden stills at $spec")
                }
                // --zen-frames=N,K: N stills, K frames apart, for a GIF of the garden being raked.
                map["zen-frames"]?.let { spec ->
                    val (n, k) = spec.split(",").map { it.toInt() }
                    val dir = File(out, "$name-frames").apply { mkdirs() }
                    // --garden-step=S: each still is S garden-seconds on, rather than K frames.
                    val step = map["garden-step"]?.toFloat()
                    repeat(n) { i ->
                        if (step != null) {
                            h.zen.time += step
                            clock.run(1)
                        } else {
                            clock.run(k)
                        }
                        val still = clock.frame().encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                        File(dir, "%03d.png".format(i)).writeBytes(still.bytes)
                    }
                    println("[neue-studio] $n frames in ${dir.name}")
                }
            }
            map["hover"]?.let { spec ->
                val (x, y) = spec.split(",").map { it.toFloat() }
                scene.sendPointerEvent(PointerEventType.Move, Offset(x * width, y * height))
                // A second move, so the pointer has arrived rather than appeared: Enter then Move.
                scene.sendPointerEvent(PointerEventType.Move, Offset(x * width + 1f, y * height))
                clock.run(60)
            }

            if (map["deckshot"] == "true") {
                // The shared picture, drawn by the app's own code rather than photographed off the window.
                val (shot, missing) = h.shots.picture(h.shots.snapshot(h.builder, h.neue))
                val file = File(out, "$name-deckshot.png")
                file.writeBytes(shot)
                println("[neue-studio] ${file.name}  ${shot.size / 1024} KiB  $missing without a picture")
            }
            val png = clock.frame().encodeToData(EncodedImageFormat.PNG) ?: error("Skia declined to encode $name")
            val file = File(out, "$name.png")
            file.writeBytes(png.bytes)
            println("[neue-studio] ${file.name}  ${png.size / 1024} KiB  ${width}x$height @${density}x")
        } finally {
            scene.close()
        }
    }
    kotlin.system.exitProcess(0)
}

@Composable
private fun Scene(deps: AppDependencies, data: File, publish: (NeueHolders) -> Unit) {
    val h = rememberHolders(deps) { scope ->
        NeueUpdates(NeueUpdateChecker(GitHubReleaseApi(HttpClientFactory.create()), "studio", DesktopOs.LINUX), scope)
    }
    DisposableEffect(Unit) {
        configureImageLoader(File(data, "card-art").absolutePath)
        h.layout.start { prefs ->
            h.builder.onFormatChange(prefs.format)
            h.builder.onSearchEffectsChange(prefs.searchEffects)
        }
        h.builder.start()
        h.builder.importFromFile()
        publish(h)
        onDispose { }
    }
    NeueRoot(h, launchEffects = false)
}

private fun dependencies(data: File, deck: File): AppDependencies {
    val database = DatabaseFactory.create(StudioDriverFactory(File(data, "neue-" + DatabaseFactory.DATABASE_NAME).absolutePath))
    return AppDependencies(
        cardRepository = CardRepository(
            database = database,
            api = YgoProDeckApi(HttpClientFactory.create()),
            clock = System::currentTimeMillis,
            ioDispatcher = Dispatchers.IO,
        ),
        deckRepository = DeckRepository(database = database, clock = System::currentTimeMillis, ioDispatcher = Dispatchers.IO),
        preferencesRepository = PreferencesRepository(database = database, ioDispatcher = Dispatchers.IO),
        fileAccess = object : DeckFileAccess {
            override suspend fun importDeck(): ImportedFile? = runCatching { ImportedFile(deck.name, deck.readText()) }.getOrNull()
            override suspend fun exportDeck(suggestedName: String, content: String) = false
            override suspend fun shareDeck(suggestedName: String, content: String) = Unit
        },
        updateChecker = UpdateChecker(GitHubReleaseApi(HttpClientFactory.create()), "studio"),
        updater = object : AppUpdater {
            override val currentVersionName = "studio"
            override val canInstallInPlace = false
            override suspend fun downloadAndInstall(release: Release, onProgress: (Float?) -> Unit) = InstallOutcome.HandedToInstaller
            override fun openReleasePage(url: String) = Unit
        },
        newDeckId = { UUID.randomUUID().toString() },
        now = System::currentTimeMillis,
        imageCacheDir = File(data, "card-art").absolutePath,
    )
}

/** Entry point for `:studio:shootNeue`. */
object NeueStudio {
    @JvmStatic
    fun main(args: Array<String>) = neueMain(args)
}
