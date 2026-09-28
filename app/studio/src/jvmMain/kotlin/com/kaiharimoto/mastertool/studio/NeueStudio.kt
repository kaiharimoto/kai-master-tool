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

            // --save: the deck into the library, so `01 Decks` has a row; --covers=0,1,2 puts
            // those main-deck cards on its cover; --default marks it the deck that opens first.
            if (map["save"] == "true") {
                h.builder.save()
                clock.run(20)
                val id = h.builder.deckId
                map["covers"]?.let { spec ->
                    val raw = spec.split(",").mapNotNull { h.builder.deck[DeckSection.MAIN].getOrNull(it.trim().toInt())?.value }
                    if (id != null) h.neue.update { it.copy(covers = it.covers + (id to raw)) }
                }
                if (map["default"] == "true" && id != null) h.neue.update { it.copy(defaultDeckId = id) }
                println("[neue-studio] saved as $id; covers ${h.neue.prefs.covers[id]}; default ${h.neue.prefs.defaultDeckId}")
            }
            if (map["side"] == "true") h.neue.update { it.copy(poolToSide = true) }
            if (map["extra"] == "false") h.neue.update { it.copy(extraVisible = false) }
            if (map["sideshown"] == "false") h.neue.update { it.copy(sideVisible = false) }
            map["zoom"]?.toFloatOrNull()?.let { z -> h.neue.update { it.copy(deckZoom = z) } }
            map["gap"]?.toFloatOrNull()?.let { g -> h.neue.update { it.copy(groupGap = g) } }
            map["group-palette"]?.let { id -> h.neue.update { it.copy(groupPalette = id) } }
            // --art=auto: the first main-deck card printed with more than one artwork, selected,
            // with its second artwork chosen — the inspector shows "Art 2 of n" and the deck the picture.
            if (map["art"] == "auto") {
                val ids = h.builder.deck[DeckSection.MAIN]
                val at = ids.indexOfFirst { id -> (h.builder.index.byId(id)?.let { com.kaiharimoto.mastertool.core.model.CardArt.arts(it).size } ?: 0) > 1 }
                val card = ids.getOrNull(at)?.let(h.builder.index::byId)
                if (card != null) {
                    val next = com.kaiharimoto.mastertool.core.model.CardArt.step(card, null, 1)
                    h.neue.update { it.copy(arts = it.arts + (card.id.value to next.value)) }
                    h.neue.selection = Selection.InDeck(card, DeckSection.MAIN, at)
                }
                println("[neue-studio] art: ${card?.name} at main $at, ${card?.let { com.kaiharimoto.mastertool.core.model.CardArt.arts(it) }}")
            } else map["art"]?.toIntOrNull()?.let { passcode ->
                // --art=46986414: that card, searched for in the pool and selected, on its second artwork.
                val card = h.builder.index.byId(com.kaiharimoto.mastertool.core.model.CardId(passcode))
                if (card != null) {
                    val next = com.kaiharimoto.mastertool.core.model.CardArt.step(card, null, 1)
                    h.neue.update { it.copy(arts = it.arts + (card.id.value to next.value)) }
                    h.builder.onQueryChange(card.name)
                    h.neue.selection = Selection.InPool(card, 0)
                }
                println("[neue-studio] art: ${card?.name}, ${card?.let { com.kaiharimoto.mastertool.core.model.CardArt.arts(it) }}")
            }
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
            // --history: a few edits, then the history menu open, to see it list them.
            if (map["history"] == "true") {
                val deck = h.builder.deck
                deck.main.firstOrNull()?.let(h.builder.index::byId)?.let { c -> h.builder.addCard(c, DeckSection.MAIN) }
                deck.side.firstOrNull()?.let(h.builder.index::byId)?.let { c -> h.builder.removeAt(c, DeckSection.SIDE, 0) }
                clock.run(10)
                h.neue.menu = com.kaiharimoto.neue.kit.MenuSpec(androidx.compose.ui.geometry.Offset(width * 0.5f, 48f), com.kaiharimoto.neue.builder.historyMenu(h.builder))
            }
            // --groups: the Groups button pressed — the Roles lens, the deck in pieces, the panel.
            if (map["groups"] == "true") h.setGroups(true)
            if (map["help"] == "true") h.neue.helpOpen = true
            // --list=N: a list of the first N main-deck cards, shown in the pool (1.0.19).
            map["list"]?.toIntOrNull()?.let { n ->
                val id = h.neue.newList()
                val ids = h.builder.deck[DeckSection.MAIN].distinct().take(n).map { it.value }
                h.neue.update { p -> p.copy(cardLists = p.cardLists.map { if (it.id == id) it.copy(ids = ids) else it }) }
                h.neue.showList(id)
                println("[neue-studio] list $id with ${ids.size} cards")
            }
            // --filters=true: the pool's filter panel open; --effect=SEARCH,NEGATE picks effect kinds.
            if (map["filters"] == "true") h.neue.update { it.copy(filtersOpen = true) }
            map["effect"]?.let { spec ->
                val kinds = spec.split(",").mapNotNull { k -> com.kaiharimoto.mastertool.core.search.EffectKind.entries.firstOrNull { it.name.equals(k.trim(), true) } }.toSet()
                h.builder.onFilterChange(h.builder.filter.copy(effects = kinds))
            }
            if (map["nopool"] == "true") h.neue.update { it.copy(poolVisible = false) }
            if (map["noinspector"] == "true") h.neue.update { it.copy(inspectorVisible = false) }
            // --studio=deck|list: the search pop-out, adding to the deck or to the list made by --list.
            map["studio"]?.let { mode -> h.neue.studio = com.kaiharimoto.neue.Studio(if (mode == "list") h.neue.prefs.poolList else null) }
            map["drawer"]?.let { h.neue.drawer = Drawer.ISSUES }
            if (map["goal"] == "true") h.builder.newGoal()
            clock.run((map["frames"] ?: "90").toInt())
            map["zen"]?.let { phase ->
                h.neue.immersive = true
                clock.run(30)
                h.neue.zen = if (phase == "quiet") com.kaiharimoto.mastertool.core.motion.ZenPhase.QUIET else com.kaiharimoto.mastertool.core.motion.ZenPhase.DEEP
                // The fades take under three seconds; then the float runs for as long as asked.
                clock.run(((map["zen-seconds"] ?: "4").toFloat() * 60).toInt())
                // --zen-groups=true|false: the corner's Groups toggle, pressed, and time for the pieces to open.
                map["zen-groups"]?.let { on ->
                    h.zen.groups = on == "true"
                    map["zen-gap"]?.toFloatOrNull()?.let { h.zen.gapScale = it }
                    clock.run(90)
                    println("[neue-studio] zen groups ${h.zen.groups}, amount ${h.zen.groupsAmount}")
                }
                // --zen-moves=0:12,6;1:1150,15: carry main-deck card N by dx,dy (window pixels, at
                // rest scale) and let it go, as a hand would; where it settled is logged.
                map["zen-moves"]?.let { spec ->
                    spec.split(";").filter { it.isNotBlank() }.forEach { step ->
                        val (n, d) = step.split(":")
                        val (dx, dy) = d.split(",").map { it.toFloat() }
                        val key = com.kaiharimoto.mastertool.core.motion.ZenArrangement.key(0, n.toInt())
                        h.zen.move(key, dx, dy)
                        val result = h.zen.arrangement.drop(key, h.zen.homes)
                        h.zen.arranged++
                        println("[neue-studio] zen card $n moved ($dx, $dy): $result, now at ${h.zen.arrangement.offsetOf(key)}")
                    }
                    clock.run(4)
                }
                // --zen-drags=drag@0.1,0.1>0.5,0.4;shift-click@0.3,0.3;dbl@0.4,0.3: real pointer
                // gestures in deep zen, through the app's own handlers — a box over the table, a
                // card carried, Shift, a double-click. What was picked out, and where the carried
                // cards settled, is logged; a still mid-gesture and one after.
                map["zen-drags"]?.let { spec ->
                    var t = System.nanoTime() / 1_000_000
                    suspend fun step(frames: Int) { clock.run(frames); t += frames * 16L }
                    val primary = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
                    val none = androidx.compose.ui.input.pointer.PointerButtons()
                    spec.split(";").filter { it.isNotBlank() }.forEachIndexed { i, gesture ->
                        val (kind, where) = gesture.split("@")
                        // A point is a fraction of the window, or `k12` — the middle of the card keyed 12
                        // where it is drawn now — with an optional `+dx,dy` in fractions after it.
                        fun point(p: String): Offset {
                            if (!p.startsWith("k")) return p.split(",").map { it.toFloat() }.let { Offset(it[0] * width, it[1] * height) }
                            val key = p.drop(1).substringBefore("+").toInt()
                            val r = com.kaiharimoto.mastertool.core.motion.ZenPick.rectOf(key, h.zen.homes, h.zen.arrangement, h.zen.stage, h.zen.pivot.x, h.zen.pivot.y)
                                ?: error("no card keyed $key")
                            val by = p.substringAfter("+", "").takeIf { it.isNotBlank() }?.split(",")?.map { it.toFloat() }
                            return Offset((r[0] + r[2]) / 2f + (by?.get(0) ?: 0f) * width, (r[1] + r[3]) / 2f + (by?.get(1) ?: 0f) * height)
                        }
                        val points = where.split(">").map(::point)
                        val mods = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isShiftPressed = kind.startsWith("shift"))
                        val from = points.first()
                        val to = points.last()
                        scene.sendPointerEvent(PointerEventType.Move, from, timeMillis = t, keyboardModifiers = mods)
                        step(2)
                        val presses = if (kind == "dbl") 2 else 1
                        repeat(presses) {
                            scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = t, buttons = primary, keyboardModifiers = mods, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                            step(1)
                            if (kind.endsWith("drag")) {
                                for (k in 1..16) {
                                    val at = from + (to - from) * (k / 16f)
                                    scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = t, buttons = primary, keyboardModifiers = mods)
                                    step(1)
                                    if (k == 12) {
                                        val mid = clock.frame().encodeToData(EncodedImageFormat.PNG)
                                        if (mid != null) File(out, "$name-zen$i-mid.png").writeBytes(mid.bytes)
                                        println("[neue-studio] zen $i $kind mid: picked ${h.zen.selection.sorted()}, carrying ${h.zen.carrying.sorted()}, box ${h.zen.marquee}")
                                    }
                                }
                            }
                            scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = t, buttons = none, keyboardModifiers = mods, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                            step(2)
                        }
                        step(20)
                        val moved = h.zen.homes.keys.filter { h.zen.arrangement.isMoved(it) }.sorted()
                        println("[neue-studio] zen $i $kind: picked ${h.zen.selection.sorted()}; moved $moved; " +
                            moved.joinToString { "$it@${h.zen.arrangement.offsetOf(it)}/g${h.zen.arrangement.membershipOf(it, h.zen.homes.getValue(it).membership).group}" })
                        val still = clock.frame().encodeToData(EncodedImageFormat.PNG)
                        if (still != null) File(out, "$name-zen$i.png").writeBytes(still.bytes)
                    }
                }
                // --zen-frames=N,K: N stills, K frames apart, for a GIF of the float.
                map["zen-frames"]?.let { spec ->
                    val (n, k) = spec.split(",").map { it.toInt() }
                    val dir = File(out, "$name-frames").apply { mkdirs() }
                    repeat(n) { i ->
                        clock.run(k)
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

            // --hovers=card@0.3,0.2;undo@0.47,0.02: the family cursor at each point, one still
            // apiece, and what it resolved to in the log — the frame, the caret, the bar, ✕.
            map["hovers"]?.let { spec ->
                spec.split(";").filter { it.isNotBlank() }.forEach { step ->
                    val (label, where) = step.split("@")
                    val (fx, fy) = where.split(",").map { it.toFloat() }
                    val at = Offset(fx * width, fy * height)
                    scene.sendPointerEvent(PointerEventType.Move, at)
                    scene.sendPointerEvent(PointerEventType.Move, at + Offset(1f, 0f))
                    clock.run(30)
                    val (mode, hook) = h.cursor.debugResolve()
                    println("[neue-studio] hover $label at ($fx, $fy): $mode ${hook ?: ""}")
                    val still = clock.frame().encodeToData(EncodedImageFormat.PNG)
                    if (still != null) File(out, "$name-hover-$label.png").writeBytes(still.bytes)
                }
            }

            // --mouse=right@0.1,0.3;left-hold@0.5,0.5: real presses, with real buttons, at fractions
            // of the frame; after each the deck's counts and any open menu are logged, so a gesture
            // that does nothing shows up as numbers that did not move.
            map["mouse"]?.let { spec ->
                fun counts() = "main ${h.builder.deck[DeckSection.MAIN].size} extra ${h.builder.deck[DeckSection.EXTRA].size} side ${h.builder.deck[DeckSection.SIDE].size}"
                spec.split(";").filter { it.isNotBlank() }.forEachIndexed { i, step ->
                    val (kind, where) = step.split("@")
                    val (fx, fy) = where.split(",").map { it.toFloat() }
                    val at = Offset(fx * width, fy * height)
                    val secondary = kind.startsWith("right") || kind.startsWith("shift-right")
                    val shift = kind.startsWith("shift")
                    val hold = kind.endsWith("hold")
                    val buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = !secondary, isSecondaryPressed = secondary)
                    val button = if (secondary) androidx.compose.ui.input.pointer.PointerButton.Secondary else androidx.compose.ui.input.pointer.PointerButton.Primary
                    val mods = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isShiftPressed = shift)
                    val before = counts()
                    scene.sendPointerEvent(PointerEventType.Move, at)
                    clock.run(4)
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = buttons, keyboardModifiers = mods, button = button)
                    clock.run(if (hold) 50 else 3)
                    scene.sendPointerEvent(PointerEventType.Release, at, buttons = androidx.compose.ui.input.pointer.PointerButtons(), keyboardModifiers = mods, button = button)
                    clock.run(30)
                    println("[neue-studio] mouse $i $kind at ($fx, $fy): $before -> ${counts()}; menu ${h.neue.menu != null}; viewing ${h.neue.viewing?.card?.name}")
                    val still = clock.frame().encodeToData(EncodedImageFormat.PNG)
                    if (still != null) File(out, "$name-mouse$i.png").writeBytes(still.bytes)
                    h.neue.menu = null; h.neue.viewing = null
                }
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
