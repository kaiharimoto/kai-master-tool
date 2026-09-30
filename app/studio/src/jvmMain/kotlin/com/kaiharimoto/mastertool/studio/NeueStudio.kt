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
            // --form=phone: Neue as a phone draws itself (v1.3.5), whatever the desk is.
            if (map["form"] == "phone") {
                h.neue.formOverride = com.kaiharimoto.mastertool.core.layout.FormFactor.PHONE
                h.neue.form = com.kaiharimoto.mastertool.core.layout.FormFactor.PHONE
            }
            // --lens=roles etc. is below; --dock=PEEK|HALF|FULL sets the phone's pool dock.
            map["dock"]?.let { d -> h.neue.update { it.copy(phoneDockStop = d.uppercase()) } }
            h.neue.page = when (map["page"]) {
                "decks" -> Page.DECKS
                "siding" -> Page.SIDING
                "format" -> Page.FORMAT
                "prep" -> Page.PREP
                "settings" -> Page.SETTINGS
                else -> Page.BUILDER
            }
            // The webs are read at the app's start, which the studio does not run: for pages that show them.
            if (map["page"] == "siding" || map["page"] == "format" || map["page"] == "prep") {
                h.webs.load()
                h.prep.load()
                clock.run(30)
            }
            // --grow=0.4: the deck drawn that small first and then full size — the cards grow
            // after their pictures were decoded, as they do when the window is maximised on
            // opening. Before DecodeSize they kept the small decode and looked blurry.
            map["grow"]?.toFloatOrNull()?.let { z -> h.neue.update { it.copy(deckZoom = z) } }
            clock.run((map["settle"] ?: "150").toInt())
            if (map["grow"] != null) {
                h.neue.update { it.copy(deckZoom = 1f) }
                clock.run(60)
            }

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
            // --ydkw=path: a web of decks opened, as Format's Open a .ydkw does (1.0.33);
            // --web-deck=N then puts its N-th deck on the builder, to show the bar's switcher.
            map["ydkw"]?.let { path ->
                h.webs.open(java.io.File(path).readText()) { made -> println("[neue-studio] opened web ${made?.name}: ${made?.entries?.size} decks") }
                clock.run(40)
                map["web-deck"]?.toIntOrNull()?.let { n ->
                    h.webs.selected?.deckIds?.getOrNull(n)?.let { id ->
                        h.openDeck(id)
                        h.neue.page = when (map["page"]) { "format" -> Page.FORMAT; else -> Page.BUILDER }
                        clock.run(60)
                    }
                }
                // --siding=N: the web's N-th deck in the siding editor (1.0.35); --against=M on its M-th deck,
                // or --against=m:ID on a matchup made by name (1.0.49).
                // --matchups=true: the web's Matchups table instead.
                // --siding-view=art|list: how Siding and its guide show a plan (1.0.49).
                map["siding-view"]?.let { v -> h.neue.update { it.copy(sidingView = v) } }
                // --siding-extra=true: the Extra Deck shown to side out from (1.0.51).
                map["siding-extra"]?.let { v -> h.neue.update { it.copy(sidingExtra = v == "true") } }
                // --opponent=NAME: Siding opens New opponent with NAME typed, its suggestions under it (1.0.49).
                map["opponent"]?.let { h.webs.newOpponent = it }
                map["siding"]?.toIntOrNull()?.let { n ->
                    val ids = h.webs.selected?.deckIds.orEmpty()
                    ids.getOrNull(n)?.let { id -> h.webs.side(id, map["against"]?.let { a -> a.toIntOrNull()?.let(ids::getOrNull) ?: a }) }
                    h.neue.page = Page.SIDING
                    clock.run(60)
                }
                // --guide=path: the siding guide of the deck --siding names, written to path as a PDF (1.0.36).
                map["guide"]?.let { path ->
                    val web = h.webs.selected
                    val id = h.webs.sidingDeckId
                    if (web != null && id != null) {
                        val decks = h.webs.decks(web)
                        decks.firstOrNull { it.entry.id == id }?.let { me ->
                            val bytes = com.kaiharimoto.neue.pages.GuideExport.build(h.webs, web, decks, me, h.builder, h.neue, h.art, h.customArt)
                            java.io.File(path).writeBytes(bytes)
                            println("[neue-studio] guide: ${bytes.size / 1024} KiB to $path")
                        }
                    }
                }
                if (map["matchups"] == "true") {
                    h.webs.showMatchups = true
                    h.neue.page = Page.FORMAT
                    clock.run(60)
                }
            }
            // --prep-demo=true (with --ydkw): an event on the opened web's field, a fortnight out, with a
            // practice log and two drills, for pictures of the Prep page (1.0.50); --prep-tab=PLAN|PRACTICE|DRILLS|DECKLIST|DAY.
            if (map["prep-demo"] == "true") {
                val web = h.webs.selected
                val mine = web?.entries?.firstOrNull { it.mine }?.deckId
                val foes = web?.entries?.filter { it.deckId != mine }.orEmpty()
                val today = com.kaiharimoto.mastertool.core.prep.IsoDate.epochDay(h.prep.today()) ?: 0L
                val event = com.kaiharimoto.mastertool.core.prep.PrepEvent(
                    "ev-demo", "Regional Qualifier", com.kaiharimoto.mastertool.core.prep.IsoDate.of(today + 12), tier = 2, attendance = 96,
                    webId = web?.id, deckId = mine, decklist = com.kaiharimoto.mastertool.core.prep.PrepEvent.DECKLIST_PAPER,
                    deadline = com.kaiharimoto.mastertool.core.prep.IsoDate.of(today + 10), checkIn = "Saturday 9:00, closes 9:45",
                    checked = listOf("id", "dice"),
                )
                var doc = h.prep.doc.put(event).copy(active = event.id, profile = com.kaiharimoto.mastertool.core.prep.PrepProfile("Kai Harimoto", "0412345678", "USA"))
                // A week of practice: the mirror close, the loose matchup worse going second.
                val results = listOf("W", "L", "W", "W", "L", "W", "L", "L", "W", "D", "W", "L")
                val names = foes.map { f -> f.deckId to (h.webs.decks(web!!).firstOrNull { it.entry.id == f.deckId }?.entry?.name ?: "Opponent") } +
                    listOf("Snake-Eye Fire King" to "Snake-Eye Fire King")
                results.forEachIndexed { n, r ->
                    val (key, name) = names[n % names.size]
                    doc = doc.record(
                        com.kaiharimoto.mastertool.core.prep.TestGame(
                            "g$n", 1_000L * n, mine, key, name,
                            if (n % 2 == 0) com.kaiharimoto.mastertool.core.prep.TestGame.FIRST else com.kaiharimoto.mastertool.core.prep.TestGame.SECOND,
                            game = 1 + n % 3, result = r, minutes = if (key == names.last().first) 19 else 12,
                            reason = if (r == "L") com.kaiharimoto.mastertool.core.prep.TestGame.REASON_INTERRUPTED else null,
                        ),
                    )
                }
                doc = doc.record(com.kaiharimoto.mastertool.core.prep.TestGame("r1", 99_000, mine, names.first().first, names.first().second, "", result = "W", note = "2–1", eventId = event.id, round = 1))
                doc = doc.copy(drills = mapOf("m1:FIRST" to com.kaiharimoto.mastertool.core.prep.DrillStat(3, 2, 1, 2)))
                h.prep.commit(doc)
                map["prep-tab"]?.let { t -> h.prep.tab = com.kaiharimoto.neue.prep.PrepTab.valueOf(t.uppercase()) }
                h.neue.page = Page.PREP
                clock.run(60)
            }
            // --check: Settings → Offline → Check for updates, asked for real, and its answer.
            if (map["check"] == "true") {
                h.builder.checkCardPool()
                var waited = 0
                do {
                    clock.run(10)
                    waited += 10
                } while ((h.builder.checkingPool || h.builder.poolCheck == null) && waited < 3000)
                println("[neue-studio] check: ${h.builder.poolCheck}")
            }
            // --update=downloading|saving: Update now, pressed for real, and the picture taken
            // while it is at that step — the bars in the title bar and in Settings.
            map["update"]?.let { step ->
                h.builder.refreshCardPool(force = true)
                var waited = 0
                while (waited < 6000) {
                    clock.run(1)
                    waited++
                    val at = h.builder.poolProgress
                    if (step == "saving" && at is com.kaiharimoto.mastertool.core.data.PoolProgress.Saving && at.done > 0) break
                    if (step != "saving" && at is com.kaiharimoto.mastertool.core.data.PoolProgress.Downloading && at.bytes > 4_000_000) break
                }
                println("[neue-studio] update: ${h.builder.poolProgress} after $waited frames")
            }
            if (map["side"] == "true") h.neue.update { it.copy(poolToSide = true) }
            if (map["extra"] == "false") h.neue.update { it.copy(extraVisible = false) }
            if (map["sideshown"] == "false") h.neue.update { it.copy(sideVisible = false) }
            map["zoom"]?.toFloatOrNull()?.let { z -> h.neue.update { it.copy(deckZoom = z) } }
            map["gap"]?.toFloatOrNull()?.let { g -> h.neue.update { it.copy(groupGap = g) } }
            map["group-palette"]?.let { id -> h.neue.update { it.copy(groupPalette = id) } }
            // --arrange=as_is|fitted|separate: how the groups stand when they are out (1.0.37).
            map["arrange"]?.let { a -> h.neue.update { it.copy(groupArrangement = a.uppercase()) } }
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
            // --updatedialog: the update dialog on a made-up release, as a phone must be able to reach its Install.
            if (map["updatedialog"] == "true") h.updates.offer(h.updates.sample())
            // --phonemenu: the phone's overflow menu, open.
            if (map["phonemenu"] == "true") h.neue.menu = com.kaiharimoto.neue.kit.MenuSpec(androidx.compose.ui.geometry.Offset(width.toFloat(), 48f), h.phoneMenu(androidx.compose.ui.geometry.Offset(width.toFloat(), 48f)))
            // --showcase=N (and --showart): main deck card N full screen, the whole card or its art.
            map["showcase"]?.toIntOrNull()?.let { i ->
                h.builder.deck[DeckSection.MAIN].getOrNull(i)?.let(h.builder.index::byId)?.let { h.neue.showcase = it }
            }
            // --view=N: main deck card N opened large.
            map["view"]?.toIntOrNull()?.let { i ->
                val id = h.builder.deck[DeckSection.MAIN].getOrNull(i)
                h.builder.index.byId(id ?: return@let)?.let { card -> h.neue.viewing = com.kaiharimoto.neue.Viewing(card, DeckSection.MAIN, i) }
            }
            // --crop=N (and --crop-picture=path): your own art for main deck card N, the picture laid in (1.0.34).
            map["crop"]?.toIntOrNull()?.let { i ->
                val id = h.builder.deck[DeckSection.MAIN].getOrNull(i)
                val picture = map["crop-picture"]?.let { java.io.File(it) }?.takeIf { it.isFile }?.let { com.kaiharimoto.neue.platform.PickedFile(it.name, it.readBytes()) }
                h.builder.index.byId(id ?: return@let)?.let { card -> h.neue.cropping = com.kaiharimoto.neue.art.ArtCropping(card, picture) }
            }
            // --group-palettes=true: the Groups panel's palettes opened out (with --groups).
            if (map["group-palettes"] == "true") h.neue.groupPalettesOpen = true
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
            if (map["groups"] == "false") h.setGroups(false)
            if (map["help"] == "true") h.neue.helpOpen = true
            // Ai's panel (1.0.43): --ai=panel (a sample conversation), empty, wizard (--ai-step=KEY:anthropic), setup (the first setup, the same steps), tune or review.
            map["ai"]?.let { mode -> studioAi(h, mode, map["ai-step"]) }
            // Ai's face (1.0.52): --ai-face=wink (any of the twenty), --ai-working="Searching cards" for a turn at work.
            map["ai-working"]?.let { h.ai.pretendWorking(it) }
            map["ai-face"]?.let { id ->
                com.kaiharimoto.mastertool.core.ai.avatar.Expression.byId(id)?.let { h.ai.express(it, 8) }
            }
            if (map["ai"] != null || map["ai-face"] != null) {
                h.lastInput = System.nanoTime()
                h.ai.tickFace()
            }
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
            // that does nothing shows up as numbers that did not move. `wheel@x,y` (down, a notch)
            // and `wheel-up@x,y` scroll instead, with a still mid-glide (`-mouse<i>-mid.png`).
            // --matchup=Yubel:89631139,14558127,23434538 — the builder's deck given a matchup made
            // by name and three cards (1.0.42), and the Siding page open on it.
            map["matchup"]?.let { spec ->
                val (who, cards) = spec.split(":")
                val covers = cards.split(",").mapNotNull { it.trim().toIntOrNull() }.map { com.kaiharimoto.mastertool.core.model.CardId(it) }
                val id = h.builder.deckId
                if (id != null) {
                    val now = com.kaiharimoto.mastertool.core.siding.SidingCodec.read(h.builder.extendedNow())
                    h.webs.saveSiding(id, now.put(com.kaiharimoto.mastertool.core.siding.Matchup("m-studio", who, covers = covers)), h.builder)
                }
                h.webs.load()
                h.neue.page = Page.SIDING
                clock.run(60)
            }
            // --drags=drag@m3>m7+0.3,0;hold-drag@m5>m9 — real presses through the builder's drag:
            // a point is `m12`/`e3`/`s0` (the middle of that card of the main, extra or side deck)
            // with an optional `+dx,dy` in the card's own widths and heights, or window fractions.
            // `hold-drag` waits 36 frames (~600 ms) before moving. Each is logged and undone.
            map["drags"]?.let { spec ->
                var t = System.nanoTime() / 1_000_000
                suspend fun step(frames: Int) { clock.run(frames); t += frames * 16L }
                val primary = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
                val none = androidx.compose.ui.input.pointer.PointerButtons()
                fun order(section: DeckSection) = h.builder.deck[section].map { it.value }
                fun point(p: String): Offset {
                    val section = when (p.firstOrNull()) { 'm' -> DeckSection.MAIN; 'e' -> DeckSection.EXTRA; 's' -> DeckSection.SIDE; else -> null }
                        ?: return p.split(",").map { it.toFloat() }.let { Offset(it[0] * width, it[1] * height) }
                    val index = p.drop(1).substringBefore("+").toInt()
                    val r = h.drag.boxOf(section, index) ?: error("no card $p")
                    val by = p.substringAfter("+", "").takeIf { it.isNotBlank() }?.split(",")?.map { it.toFloat() }
                    return Offset(r.center.x + (by?.get(0) ?: 0f) * r.width, r.center.y + (by?.get(1) ?: 0f) * r.height)
                }
                spec.split(";").filter { it.isNotBlank() }.forEachIndexed { i, gesture ->
                    val (kind, where) = gesture.split("@")
                    val (a, b) = where.split(">")
                    val from = point(a)
                    val to = point(b)
                    val before = order(DeckSection.MAIN)
                    val fittedBefore = h.builder.groups.fitted.map { it.value }
                    scene.sendPointerEvent(PointerEventType.Move, from, timeMillis = t)
                    step(2)
                    scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = t, buttons = primary, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                    step(if (kind == "hold-drag") 36 else 1)
                    var mid = ""
                    for (k in 1..16) {
                        val at = from + (to - from) * (k / 16f)
                        scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = t, buttons = primary)
                        step(1)
                    }
                    step(2)
                    mid = "held ${h.drag.held?.card?.name}@${h.drag.held?.from}/${h.drag.held?.index}, hover ${h.drag.hover}, mark ${h.drag.mark}, preview ${h.drag.preview}, viewing ${h.neue.viewing?.card?.name}"
                    clock.frame().encodeToData(EncodedImageFormat.PNG)?.let { File(out, "$name-drag$i-mid.png").writeBytes(it.bytes) }
                    scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = t, buttons = none, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                    step(20)
                    val after = order(DeckSection.MAIN)
                    val names = { ids: List<Int> -> ids.map { id -> h.builder.index.byId(com.kaiharimoto.mastertool.core.model.CardId(id))?.name?.take(10) ?: "$id" } }
                    val changed = before.indices.filter { before.getOrNull(it) != after.getOrNull(it) }
                    println("[neue-studio] drag $i $kind $a>$b: $mid")
                    println("[neue-studio] drag $i moved: ${if (before == after) "nothing" else "positions ${changed.first()}..${changed.last()}: ${names(before.slice(changed.first()..changed.last()))} -> ${names(after.slice(changed.first()..changed.last()))}"}")
                    clock.frame().encodeToData(EncodedImageFormat.PNG)?.let { File(out, "$name-drag$i.png").writeBytes(it.bytes) }
                    val fittedAfter = h.builder.groups.fitted.map { it.value }
                    if (fittedAfter != fittedBefore) println("[neue-studio] drag $i fitted: ${names(fittedBefore)} -> ${names(fittedAfter)}")
                    if (before != after || fittedAfter != fittedBefore) { h.builder.undo(); step(10) }
                    h.neue.viewing = null
                    step(4)
                }
            }
            map["mouse"]?.let { spec ->
                fun counts() = "main ${h.builder.deck[DeckSection.MAIN].size} extra ${h.builder.deck[DeckSection.EXTRA].size} side ${h.builder.deck[DeckSection.SIDE].size}" +
                    "; pool ${h.neue.prefs.poolVisible} inspector ${h.neue.prefs.inspectorVisible}; group palettes ${h.neue.groupPalettesOpen} (${h.neue.prefs.groupPalette}); zoom ${h.neue.prefs.deckZoom}"
                spec.split(";").filter { it.isNotBlank() }.forEachIndexed { i, step ->
                    val (kind, where) = step.split("@")
                    val (fx, fy) = where.split(",").map { it.toFloat() }
                    val at = Offset(fx * width, fy * height)
                    if (kind.startsWith("wheel")) {
                        val before = counts()
                        scene.sendPointerEvent(PointerEventType.Move, at)
                        clock.run(4)
                        scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, if (kind == "wheel-up") -1f else 1f))
                        clock.run(3)
                        clock.frame().encodeToData(EncodedImageFormat.PNG)?.let { File(out, "$name-mouse$i-mid.png").writeBytes(it.bytes) }
                        clock.run(30)
                        println("[neue-studio] mouse $i $kind at ($fx, $fy): $before -> ${counts()}")
                        clock.frame().encodeToData(EncodedImageFormat.PNG)?.let { File(out, "$name-mouse$i.png").writeBytes(it.bytes) }
                        return@forEachIndexed
                    }
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
            if (map["deckshot"] == "all") {
                // Every shape the picture can take, from the one snapshot.
                val model = h.shots.snapshot(h.builder, h.neue)
                com.kaiharimoto.neue.shot.ShotStyle.entries.forEach { style ->
                    val (shot, missing) = h.shots.picture(model.copy(style = style))
                    val file = File(out, "$name-deckshot-${style.name.lowercase()}.png")
                    file.writeBytes(shot)
                    println("[neue-studio] ${file.name}  ${shot.size / 1024} KiB  $missing without a picture")
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


/** Ai's panel as the studio draws it: open, with a sample conversation, empty, or at a wizard step. */
private fun studioAi(h: com.kaiharimoto.neue.NeueHolders, mode: String, step: String?) {
    val ai = h.ai
    h.neue.update {
        it.copy(ai = it.ai.copy(
            panelOpen = true,
            connections = listOf(com.kaiharimoto.mastertool.core.prefs.AiConnection("anthropic-demo", "anthropic", "Anthropic", "claude-opus-5-5")),
            active = "anthropic-demo",
        ))
    }
    // --ai=setup: the first setup, the whole window (1.0.45) — Ai asked for with no connection.
    if (mode == "setup") h.neue.update { it.copy(ai = it.ai.copy(connections = emptyList(), active = null)) }
    // --ai=saved (1.0.59): Setup pressed with connections made opens on them; with --ai-step=KEY:compatible, the person's own presets.
    if (mode == "saved") {
        h.neue.update {
            it.copy(ai = it.ai.copy(connections = it.ai.connections + listOf(
                com.kaiharimoto.mastertool.core.prefs.AiConnection("compatible-mimo", "compatible", "Xiaomi MiMo", "mimo-v2-pro", "https://api.xiaomimimo.com/v1"),
                com.kaiharimoto.mastertool.core.prefs.AiConnection("compatible-work", "compatible", "Work gateway", "gpt-5", "https://llm.example.com/v1"),
            )))
        }
    }
    when (mode) {
        "wizard", "setup", "saved" -> {
            ai.openWizard()
            step?.let { spec ->
                ai.wizard.saved = false
                val (name, provider) = spec.split(':').let { it[0] to it.getOrNull(1) }
                com.kaiharimoto.mastertool.core.ai.providers.Providers.byId(provider)?.let { p ->
                    ai.wizard.kind = p.kind
                    ai.wizard.choose(p)
                    if (name == "MODEL") {
                        ai.wizard.models = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5")
                        ai.wizard.model = "claude-opus-5-5"
                    }
                }
                com.kaiharimoto.mastertool.core.ai.providers.SetupStep.entries.firstOrNull { it.name == name }?.let { ai.wizard.step = it }
            }
        }
        "panel" -> {
            val now = System.currentTimeMillis()
            fun result(name: String, summary: String) = com.kaiharimoto.mastertool.core.ai.Part.ToolResult("t-$name", name, "{}", summary = summary)
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio",
                    title = "Tune for YCS",
                    connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Tune this for a field full of Maliss and Mitsurugi, and group it."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Let me look at the list first."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(result("get_deck", "Read the open deck"), result("search_cards", "Found 12 cards for “negate”"))),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant(
                            "## The plan\n\nYour engine is tight — the flex slots are the problem.\n\n" +
                                "- **+1** [[Infinite Impermanence]]: Maliss lives on its link plays.\n" +
                                "- **−1** [[Nibiru, the Primal Being]]: Mitsurugi rarely summons five times.\n\n" +
                                "Grouped into *Starters*, *Extenders*, *Hand traps* and *Bricks*.",
                        ),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(result("edit_deck", "+1 Infinite Impermanence, −1 Nibiru, the Primal Being"), result("set_groups", "Grouped the deck: Starters, Extenders, Hand traps, Bricks"))),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Done — one step of undo if you want it back."),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        // "What can you do?" (1.0.46): --ai=demo, with --ai-step=N for the scene (from 1), written out whole.
        "demo" -> {
            ai.demoStill = (step?.toIntOrNull() ?: 1) - 1
            ai.demoOpen = true
        }
        // Fine Tuning's launcher (1.0.48): teach it, or let it study, and how hard.
        "teach" -> ai.tuneAsk = true
        // A study in progress: thinking out loud, a plan, a question with the card it is about.
        "study" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-study",
                    title = "Study",
                    connection = "anthropic-demo",
                    mode = com.kaiharimoto.mastertool.core.ai.AiSession.MODE_STUDY,
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Study “lab” yourself, and think out loud so I can learn with you. Intensity: Standard."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn(
                            com.kaiharimoto.mastertool.core.ai.Role.ASSISTANT,
                            listOf(
                                com.kaiharimoto.mastertool.core.ai.Part.Reasoning("Labrynth is a trap deck: the Furniture monsters set traps and Lady Labrynth recycles them. The engine is 23 cards, so the ratio of Furniture to Normal Traps matters most."),
                                com.kaiharimoto.mastertool.core.ai.Part.Activity("archetype_guide", "Read how Labrynth plays: Playing style, Weaknesses"),
                                com.kaiharimoto.mastertool.core.ai.Part.Activity("rulings", "Read 14 rulings for Lady Labrynth of the Silver Castle"),
                                com.kaiharimoto.mastertool.core.ai.Part.Text(
                                    "Reading [[Arianna the Labrynth Servant]]: when a Normal Trap resolves she adds a Labrynth card and can Special Summon — **so she is a one-card starter going first**.\n\n" +
                                        "The lists at recent events run 3 Arianna and 2 Ariane; yours runs 3 and 3. I'll ask you why.",
                                ),
                            ),
                        ),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ai.todos = listOf("[x] Read every card", "[x] Read the archetype's page", "[>] Check the key rulings", "[ ] Compare with tournament lists", "[ ] Write the guide")
            h.builder.index.byName("Arianna the Labrynth Servant")?.let { card ->
                ai.previewTuning(com.kaiharimoto.neue.ai.Question("You run 3 [[Arianna the Labrynth Servant]] and 3 Ariane. Which is your real starter?", listOf("Arianna", "Ariane", "Both, depends on the hand"), false, listOf(card)), null)
            }
        }
        // Its thinking above a reply, and a plan in hand (1.0.47).
        "reason" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-reason",
                    title = "Ratios",
                    connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Should I play a third Called by the Grave?"),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn(
                            com.kaiharimoto.mastertool.core.ai.Role.ASSISTANT,
                            listOf(
                                com.kaiharimoto.mastertool.core.ai.Part.Reasoning(
                                    "The deck has 9 hand traps and 2 Called by the Grave. The field is mostly Snake-Eye and Yubel, which lean on hand traps less than on board breakers going second.\n" +
                                        "A third Called helps when they open Ash into my starter, which is 1 in 3 games at most. Worth checking the odds of drawing 2 with 3 in the deck.",
                                ),
                                com.kaiharimoto.mastertool.core.ai.Part.Text("Checking how often a third copy shows up next to the first.\n\nAt 3 copies you open at least one **33.8%** of the time going first, and two at once only **2.8%**: the third is live, not a brick. Keep it if Ash is common at your event."),
                            ),
                        ),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ai.todos = listOf("[x] Read the deck", "[x] Work out the odds", "[>] Weigh it against the field", "[ ] Suggest the cut")
        }
        // A reply with a table, a chart and a strip of cards (1.0.46).
        "chart" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-chart",
                    title = "Odds",
                    connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("How likely am I to open a starter, and what do I side against Snake-Eye?"),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant(
                            "## Your openers\n\n| Starters | Going first | Going second |\n| --- | ---: | ---: |\n| 9 | 78% | 83% |\n| 12 | 88% | 91% |\n| 15 | 94% | 96% |\n\n" +
                                "```chart\n{\"type\": \"bar\", \"title\": \"At least one starter in the opening hand\", \"labels\": [\"9\", \"12\", \"15\"], " +
                                "\"series\": [{\"name\": \"Going first\", \"values\": [78, 88, 94]}, {\"name\": \"Going second\", \"values\": [83, 91, 96]}], \"unit\": \"%\"}\n```\n\n" +
                                "Against **Snake-Eye**, bring these in going second:\n\n```cards\n3 Nibiru, the Primal Being\n2 Dominus Impulse\n1 Called by the Grave\n```",
                        ),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        // 1.0.54: the living guide, the session's end, the brain, quick settings, the profile, a petted face.
        "guide", "end", "brain", "quick", "profile", "about", "petted" -> studioAi154(h, mode)
        // 1.0.55: a picture sent and read into a deck, the new layouts, pictures waiting to go.
        "picture", "visual", "attach" -> studioAi155(h, mode)
        // 1.0.56: a long conversation, its start summarised, the gauge; --ai=context opens the panel.
        // 1.0.57: listening with the words arriving, talk mode speaking, the model's download asked for.
        "listening", "talk", "voice" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-157", title = "Voice", connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("What do I side in against Snake-Eye going second?"),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Two [[Infinite Impermanence]] for your two [[Labrynth Cooclock]]: their turn is a chain of Special Summons, and Impermanence stops the first one."),
                    ),
                    createdAt = now, updatedAt = now,
                ),
            )
            when (mode) {
                "listening" -> {
                    ai.draft = "and what about going first against Fiendsmith"
                    ai.previewVoice(listening = true, level = 0.08f, talk = false, speaking = false)
                }
                "talk" -> ai.previewVoice(listening = false, level = 0f, talk = true, speaking = true)
                else -> ai.voiceAsk = true
            }
        }
        // 1.0.58: an answer checked against the card text, one claim wrong, and the correction under it.
        "checked" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-158", title = "Siding", connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("What do I side in against Snake-Eye going second?"),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant(
                            "Bring in two [[Infinite Impermanence]]: it negates their first monster's effect, and you can activate it from your hand at any time. " +
                                "Keep [[Ash Blossom & Joyous Spring]] for [[Snake-Eye Ash]]'s search.",
                        ),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(com.kaiharimoto.mastertool.core.ai.Part.Context("check"))),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant(
                            "**Correction:** [[Infinite Impermanence]] can be activated from the hand only if you control no cards — so going second it works on your opponent's first turn, before you play anything. The siding advice stands.",
                        ),
                    ),
                    checks = listOf(
                        com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check(
                            1,
                            listOf(
                                com.kaiharimoto.mastertool.core.ai.check.FactCheck.Claim("Infinite Impermanence negates a monster's effects", com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict.OK, source = "card text"),
                                com.kaiharimoto.mastertool.core.ai.check.FactCheck.Claim("It can be activated from the hand at any time", com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict.WRONG, "only if you control no cards", "card text"),
                                com.kaiharimoto.mastertool.core.ai.check.FactCheck.Claim("Ash Blossom negates Snake-Eye Ash's search", com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict.OK, source = "rulings"),
                            ),
                        ),
                    ),
                    createdAt = now, updatedAt = now,
                ),
            )
        }
        "context", "summarised" -> {
            val now = System.currentTimeMillis()
            val talk = (1..8).flatMap { i ->
                listOf(
                    com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Question $i about the siding against Snake-Eye.", at = now - (9 - i) * 60_000L),
                    com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Answer $i: bring in [[Infinite Impermanence]] going second, and keep the traps for their turn.", at = now - (9 - i) * 60_000L + 1),
                )
            }
            val system = ai.previewSystem()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-156", title = "Siding", connection = "anthropic-demo", system = system,
                    turns = talk, summary = "They play Labrynth and are siding against Snake-Eye: Impermanence in going second, traps held for the opponent's turn; they fear backrow removal.",
                    summarized = 10, context = 128_400,
                    usage = com.kaiharimoto.mastertool.core.ai.Usage(input = 180_000, output = 9_400, cacheRead = 610_000, cacheWrite = 90_000),
                    createdAt = now, updatedAt = now,
                ),
            )
            if (mode == "context") ai.contextOpen = true
        }
        // Fine Tuning (phase 3): the interview mid-way, a question on the table; or its review.
        "tune", "review" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-tune",
                    title = "Fine Tuning",
                    connection = "anthropic-demo",
                    mode = com.kaiharimoto.mastertool.core.ai.AiSession.MODE_TUNE,
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Let's do Fine Tuning."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Good — a few questions, one at a time, and I'll remember the answers. First, the event."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("A regional in three weeks, about 200 players."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Noted. Which deck are you taking, and how well do you know it?"),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (mode == "tune") {
                ai.previewTuning(com.kaiharimoto.neue.ai.Question("Going first or second — which do you choose when you win the roll?", listOf("First", "Second", "Depends on the matchup"), false), null)
            } else {
                ai.previewTuning(
                    null,
                    listOf(
                        com.kaiharimoto.mastertool.core.ai.memory.MemoryChange("USER.md", listOf("Regional in three weeks, about 200 players.", "Plays Branded Dracotail; knows it well.", "Chooses to go second."), listOf("Plays Branded.")),
                        com.kaiharimoto.mastertool.core.ai.memory.MemoryChange("MEMORY.md", listOf("Explain lines with the cards named, not in general."), emptyList()),
                    ),
                )
            }
        }
        else -> Unit
    }
}


/** A guide, its sessions and a profile for the studio's pictures of 1.0.54, in the studio's own data folder. */
private fun studioAi154(h: com.kaiharimoto.neue.NeueHolders, mode: String) {
    val ai = h.ai
    val deckId = h.builder.deckId ?: "studio-lab"
    val deckName = h.builder.deckName
    val guide = """# How $deckName plays

- Goals: Resolve [[Big Welcome Labrynth]] on the opponent's turn with [[Lady Labrynth of the Silver Castle]] on the field, so every Normal Trap draws or summons.
- Goals: Grind: each trap resolved is a card, and the Furniture keeps the traps coming back.
- Game plan: Going first, set two traps and hold a Furniture; going second, play hand traps and set up for the next turn.
- Lines: [[Arianna the Labrynth Servant]] alone: summon, activate a set Welcome, add [[Labrynth Labyrinth]] — two cards from one.
- Lines: [[Labrynth Chandraglier]] to the grave returns a trap after it resolves.
- Connections: [[Arianna the Labrynth Servant]] and [[Ariane the Labrynth Servant]] each answer a trap resolving — Arianna searches, Ariane destroys.
- Connections: [[Lovely Labrynth of the Silver Castle]] sets any Normal Trap from the grave, so every trap spent is a trap again.
- Card roles: [[Welcome Labrynth]] is the engine's key: a starter that is also an interruption.
- Card roles: [[Labrynth Stovie Torbie]] and [[Labrynth Cooclock]] are the recursion; they cost nothing to keep in hand.
- Weak points: Backrow removal before the Furniture resolve, and decks that never summon.
- Weak points: Opening only Normal Traps and no Furniture: the traps are slow.
- Side deck: Against Snake-Eye bring in more backrow removal; the matchup is about keeping the traps alive.
- Insights: A third Welcome Labrynth over the second Stovie Torbie: it is both a starter and an answer.
- Open questions: Which trap to set first when both Big Welcome and Welcome are in hand?
- Sources: The card text and the rules primer; no guides or lists.
"""
    ai.files.write(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.GUIDE, deckId), guide)
    val day = 86_400_000L
    val now = System.currentTimeMillis()
    val reports = listOf(
        Triple(com.kaiharimoto.mastertool.core.ai.report.SessionReport.STUDIED, Triple(38, 30, 34), 9 * day),
        Triple(com.kaiharimoto.mastertool.core.ai.report.SessionReport.TAUGHT, Triple(56, 44, 47), 4 * day),
        Triple(com.kaiharimoto.mastertool.core.ai.report.SessionReport.PRINCIPLES, Triple(71, 55, 52), 0L),
    ).mapIndexed { i, (m, s, ago) ->
        com.kaiharimoto.mastertool.core.ai.report.SessionReport(
            deckId, deckName, now - ago, m, "standard",
            summary = listOf(
                "Read every card and the archetype's page; the spine is Furniture into Normal Traps.",
                "You taught it why three Ariane: the destruction answers backrow going second.",
                "Worked out from the text alone how each trap turns into a card, and where the chain breaks.",
            )[i],
            learned = listOf("Arianna is a one-card starter.", "Big Welcome is the payoff, not the start.", "Lovely recycles every trap spent."),
            insights = listOf("Run a third Welcome Labrynth.", "Cut the second Stovie Torbie."),
            openQuestions = listOf("Which trap to set first with Big Welcome and Welcome in hand?"),
            understanding = s.first, playing = s.second, mirror = s.third,
            why = "The lines follow from the text and are legal; the timing against hand traps is untested, and a mirror turns on who sets first.",
            questions = if (m == com.kaiharimoto.mastertool.core.ai.report.SessionReport.STUDIED) emptyList() else listOf(
                com.kaiharimoto.mastertool.core.ai.report.SessionReport.Asked("You run 3 Arianna and 3 Ariane. Which is your real starter?", "Arianna, always; Ariane is for going second."),
                com.kaiharimoto.mastertool.core.ai.report.SessionReport.Asked("What do you fear most across the table?", "Backrow removal before my Furniture resolve."),
            ),
            startedAt = now - ago - 1_500_000,
        )
    }
    ai.files.deleteReports(deckId)
    reports.forEach { ai.files.addReport(it) }
    ai.files.write(
        "USER.md",
        """# What I know about you

- Goals: Top a Regional this season with Labrynth, then a YCS.
- Goals: Learn the deck well enough to play without notes at the table.
- Preferences: Short answers with the cards named; tables for odds, not paragraphs.
- Preferences: Likes going second; hates coin-flip hand-trap wars.
- Workflow: Builds on the desk on Sunday, tests on the tablet midweek, sides on the phone at events.
- How you play: Careful with the chain; tends to over-extend into board breakers.
- Decks: Labrynth (main), Snake-Eye (testing).
- Events: A Regional in three weeks, about 200 players.
""",
    )
    ai.deckNames = mapOf(deckId to deckName)
    when (mode) {
        "guide" -> ai.docOpen = com.kaiharimoto.neue.ai.LivingDoc.Guide(deckId, deckName)
        "profile" -> ai.docOpen = com.kaiharimoto.neue.ai.LivingDoc.Profile
        "about" -> ai.profileAsk = true
        "quick" -> ai.quickOpen = true
        "brain" -> ai.memoryOpen = com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.GUIDE, deckId)
        "end" -> {
            ai.endReport = reports.last()
            ai.previewTuning(
                null,
                listOf(com.kaiharimoto.mastertool.core.ai.memory.MemoryChange(
                    com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(com.kaiharimoto.mastertool.core.ai.memory.MemoryKind.GUIDE, deckId),
                    listOf("Connections: Lovely Labrynth of the Silver Castle sets any Normal Trap from the grave.", "Open questions: Which trap to set first?"),
                    emptyList(),
                )),
            )
        }
        "petted" -> {
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = "studio-petted", title = "Hello", connection = "anthropic-demo",
                    turns = listOf(com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Hi."), com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Hello — what are we building today?")),
                    createdAt = now, updatedAt = now,
                ),
            )
            ai.touched(com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay.Reaction(com.kaiharimoto.mastertool.core.ai.avatar.Expression.LOVE, 600.0, "I could get used to this."))
        }
    }
    // The guide and the last report as PDFs, in the real fonts and chosen art, beside the shots.
    if (mode == "guide" || mode == "end") runCatching {
        kotlinx.coroutines.runBlocking {
            val dir = java.io.File("../shots").apply { mkdirs() }
            dir.resolve("ai-guide.pdf").writeBytes(com.kaiharimoto.neue.ai.AiDocs.guideBytes(h, deckId, deckName))
            dir.resolve("ai-report.pdf").writeBytes(com.kaiharimoto.neue.ai.AiDocs.reportBytes(h, reports.last()))
        }
    }.onFailure { System.err.println("studio: the PDFs failed: $it") }
}


/** The studio's pictures of 1.0.55: a decklist screenshot read into a deck, each new layout, the composer's waiting pictures. */
private fun studioAi155(h: com.kaiharimoto.neue.NeueHolders, mode: String) {
    val ai = h.ai
    val now = System.currentTimeMillis()
    val shot = java.io.File("../docs/shots/neue-builder.png").takeIf { it.isFile }?.readBytes()
    val deck = h.builder.deck
    fun name(id: com.kaiharimoto.mastertool.core.model.CardId) = h.builder.index.byId(id)?.name
    fun counted(ids: List<com.kaiharimoto.mastertool.core.model.CardId>) =
        ids.groupingBy { it }.eachCount().entries.mapNotNull { (id, n) -> name(id)?.let { "$n $it" } }.joinToString("\n")
    val main = deck.main.mapNotNull(::name).distinct()
    val sessionId = "studio-155"
    when (mode) {
        "attach" -> {
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = sessionId, title = "Pictures", connection = "anthropic-demo",
                    turns = listOf(com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Hi."), com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant("Hello — what are we building today?")),
                    createdAt = now, updatedAt = now,
                ),
            )
            ai.draft = "Is this list any good against Snake-Eye?"
            shot?.let { bytes ->
                kotlinx.coroutines.runBlocking { com.kaiharimoto.neue.ai.Attachments.prepare(com.kaiharimoto.neue.platform.PickedFile("screenshot.png", bytes)) }
                    ?.let { a -> ai.previewAttached(listOf(a, a)) }
            }
        }
        "picture" -> {
            val image = shot?.let { ai.files.putImage(sessionId, it, "image/png", 1920, 1080) }
            val call = com.kaiharimoto.mastertool.core.ai.Part.ToolResult("t1", "resolve_cards", "{}", summary = "Read ${main.size} cards off the picture, 1 to check")
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = sessionId, title = "A picture", connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("What deck is this? Build it for me.", images = listOfNotNull(image)),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(call)),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant(
                            "It's a **Labrynth** list with a hand-trap package — here is what I read off the screenshot:\n\n" +
                                "```deck\nMain:\n${counted(deck.main)}\nExtra:\n${counted(deck.extra)}\nSide:\n${counted(deck.side)}\n```\n\n" +
                                "One card I read from its art alone; say if it is wrong and I'll fix it before building.",
                        ),
                    ),
                    createdAt = now, updatedAt = now,
                ),
            )
        }
        "visual" -> {
            val a = main.getOrElse(0) { "Arianna the Labrynth Servant" }
            val b = main.getOrElse(1) { "Labrynth Labyrinth" }
            val c = main.getOrElse(2) { "Big Welcome Labrynth" }
            val d = main.getOrElse(3) { "Welcome Labrynth" }
            val e = main.getOrElse(4) { "Lady Labrynth of the Silver Castle" }
            ai.preview(
                com.kaiharimoto.mastertool.core.ai.AiSession(
                    id = sessionId, title = "Lines", connection = "anthropic-demo",
                    turns = listOf(
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.user("Show me your best line, the board it ends on, and what you'd change."),
                        com.kaiharimoto.mastertool.core.ai.ChatTurn.assistant(
                            "## The line\n\n```line\n1. [[$a]] — Normal Summon it; it searches the card that starts the chain.\n" +
                                "2. [[$b]] — Activate it; the field spell sets up the rest of the turn.\n" +
                                "3. [[$c]] — Set it; on their turn it summons from the deck.\n4. [[$d]] — The interruption: it answers their first play.\n```\n\n" +
                                "## Where it ends\n\n```board\nMonsters: $a, -, $e, -, -\nSpells/Traps: $c (set), $d (set), -, -, -\nField: $b\nHand: ${main.getOrElse(5) { a }}\nGY: $d\n```\n\n" +
                                "## What I'd change\n\n```compare\nOut:\n1 $e\nIn:\n1 $d\n```\n\nA second copy of [[$d]] opens more often than the boss it replaces.",
                        ),
                    ),
                    createdAt = now, updatedAt = now,
                ),
            )
        }
    }
}
