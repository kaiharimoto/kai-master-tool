package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.ai.eval.EvalLog
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.ItemOutcome
import com.kaiharimoto.mastertool.core.ai.eval.EvalRun
import com.kaiharimoto.mastertool.core.ai.eval.EvalSet
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.check.FactCheck
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryChange
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.SetupStep
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.ai.report.book.BookSample
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.data.PoolProgress
import com.kaiharimoto.mastertool.core.deck.GroupStats
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.dice.DiceStage
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.duel.dice.Toss
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.layout.DuelFocus
import com.kaiharimoto.mastertool.core.layout.FormFactor
import com.kaiharimoto.mastertool.core.layout.Revealed
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.motion.ZenArrangement
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.motion.ZenPick
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prep.DrillStat
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.PrepProfile
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.start.StartPrefs
import com.kaiharimoto.mastertool.core.start.StartStep
import com.kaiharimoto.mastertool.core.sync.SyncPrefs
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.ai.AiDocs
import com.kaiharimoto.neue.ai.Attachments
import com.kaiharimoto.neue.ai.LivingDoc
import com.kaiharimoto.neue.ai.Question
import com.kaiharimoto.neue.ai.openWizard
import com.kaiharimoto.neue.ai.previewTuning
import com.kaiharimoto.neue.ai.bookChanged
import com.kaiharimoto.neue.ai.previewVoice
import com.kaiharimoto.neue.ai.askTune
import com.kaiharimoto.neue.art.ArtCropping
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.Replay
import com.kaiharimoto.neue.duel.dice.DiceCarry
import com.kaiharimoto.neue.duel.dice.RESTING
import com.kaiharimoto.neue.duel.dice.DIE_HOME
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.pages.GuideExport
import com.kaiharimoto.neue.phoneMenu
import com.kaiharimoto.mastertool.core.present.modules.ModuleInput
import com.kaiharimoto.mastertool.core.present.modules.SideMatchup
import com.kaiharimoto.mastertool.core.present.modules.SideTurn
import com.kaiharimoto.mastertool.core.present.modules.MatchRow
import com.kaiharimoto.mastertool.core.present.modules.RoundRow
import com.kaiharimoto.mastertool.core.present.modules.Shoutout
import com.kaiharimoto.mastertool.core.present.modules.GroupOdds
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.modules.Pick
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
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.prep.PrepTab
import com.kaiharimoto.neue.rememberHolders
import com.kaiharimoto.neue.shot.ShotStyle
import com.kaiharimoto.neue.start.StudioStart
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
 * a card in the inspector (`--inspect=<passcode>` any card of the pool); `--hover=x,y` moves the pointer there (fractions of
 * the frame); `--lens=roles`, `--palette`, `--drawer=issues|groups`, `--genesys=true`, `--legal-as-of=`,
 * `--query=ash` and `--scale=1.25` set the rest of the scene.
 */
fun neueMain(args: Array<String>) {
    val map = args.mapNotNull { arg -> arg.removePrefix("--").split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else it[0] to "true" } }.toMap()
    diceFrames = map["duel-dice-frames"]?.toIntOrNull()
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
                h.neue.formOverride = FormFactor.PHONE
                h.neue.form = FormFactor.PHONE
            }
            // --lens=roles etc. is below; --dock=PEEK|HALF|FULL sets the phone's pool dock.
            map["dock"]?.let { d -> h.neue.update { it.copy(phoneDockStop = d.uppercase()) } }
            h.neue.page = when (map["page"]) {
                "decks" -> Page.DECKS
                "siding" -> Page.SIDING
                "format" -> Page.FORMAT
                "prep" -> Page.PREP
                "present" -> Page.PRESENT
                "duel" -> Page.DUEL
                "world" -> Page.WORLD
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
            // --duel=two|one|solo: the builder's deck against itself on the duel table (1.0.74), dealt from a
            // fixed seed; --duel-play=true plays a turn's worth of moves on both sides; --duel-know=seat hides
            // the far hand; --duel-strip=gy|deck|extra|banished opens that pile of the near seat.
            map["duel"]?.let { mode ->
                val b = h.builder
                val main = b.deck.main.map { it.value }
                val extra = b.deck.extra.map { it.value }
                val index = b.index
                h.duel.catalog = DuelCatalog { code ->
                    index.byId(CardId(code))?.let(DuelCardInfo::of)
                }
                h.neue.update {
                    it.copy(duel = it.duel.copy(
                        twoSided = mode != "one",
                        knowledge = if (map["duel-know"] == "seat") DuelPrefs.KNOW_SEAT else DuelPrefs.KNOW_ALL,
                        // --duel-facing=true: the far seat's cards turned to face them (1.0.78).
                        facing = map["duel-facing"] == "true",
                        // --duel-coords=true: every place's coordinate at its corner (1.0.87).
                        coordinates = map["duel-coords"] == "true",
                    ))
                }
                val solo = mode == "solo"
                // --duel-dice=rest|held|flying|settled|choose: the opening roll (1.0.87) — the dice resting in front of each
                // field, held in the hand over the near field, the near seat's throw mid-flight (the far seat's landed),
                // the near seat's landed (the far still to throw), or both landed and the near seat choosing.
                val dice = map["duel-dice"]
                fun header(seed: Long) = DuelHeader(
                    id = "studio",
                    seed = seed,
                    seats = listOf(
                        SeatSetup("Kai", main, extra),
                        if (solo) SeatSetup("Rival") else SeatSetup("Rival", main, extra),
                    ),
                    solo = solo,
                    openingRoll = dice != null,
                )
                // For the choice, a seed whose first round the near seat wins; for the flight, one that does not tie.
                val seed = if (dice == null) 7L else (7L..400L).first { sd ->
                    var g = DuelGame.start(header(sd))
                    g = g.act(DuelAction.OpeningRoll(0), 0).game
                    g = g.act(DuelAction.OpeningRoll(1), 1).game
                    g.state.opening?.winner == 0
                }
                h.duel.start(header(seed))
                if (map["duel-play"] == "true") studioDuelMoves(h)
                map["duel-strip"]?.let { k ->
                    val kind = when (k) {
                        "deck" -> PileKind.DECK
                        "extra" -> PileKind.EXTRA
                        "banished" -> PileKind.BANISHED
                        else -> PileKind.GY
                    }
                    h.duel.openPile(0, kind)
                }
                // --duel-attack=arm|declared: the Battle Phase, the near seat's first face-up Attack Position monster
                // waiting for what it attacks (its band), or attacking their first monster (the battle chip) (1.0.86).
                map["duel-attack"]?.let { how ->
                    val d = h.duel
                    d.bottom = 0
                    d.act(DuelAction.Phase(DuelPhase.BATTLE), 0)
                    val st = d.game!!.state
                    val mine = st.onField().firstOrNull { DuelVerbs.canAttack(st, 0, it) }
                    val theirs = st.onField().firstOrNull { u -> st.cards[u]?.controller == 1 && st.placeOf(u).let { it is Place.Zone && it.kind != ZoneKind.SPELL && it.kind != ZoneKind.FIELD } && st.cards[u]?.faceUp == true }
                    if (mine != null) {
                        d.verb(mine, DuelVerb.DEFAULT)
                        if (how == "declared") d.attack(theirs)
                    }
                    println("[neue-studio] attack: ${how} with $mine at $theirs; attacking ${d.attacking}; chip ${d.game?.let { com.kaiharimoto.mastertool.core.duel.DuelBattle.pending(it, d.catalog) }}")
                }
                // --duel-defense=true: the near seat's first face-up Attack Position monster turned to Defense
                // (1.0.87), for the ATK / DEF plate on a card lying across.
                if (map["duel-defense"] == "true") {
                    val st = h.duel.game!!.state
                    st.seats[0].monsters.filterNotNull().firstOrNull { st.cards[it]?.pos == CardPosition.FACE_UP_ATK }
                        ?.let { println("[neue-studio] defense: $it turned ${h.duel.verb(it, com.kaiharimoto.mastertool.core.duel.DuelVerb.POSITION)}") }
                }
                // --duel-select=near|far: a card on that side's field selected, its verb strip out (1.0.78).
                map["duel-select"]?.let { side ->
                    val g = h.duel.game!!
                    val seat = if (side == "far") 1 else 0
                    val uid = g.state.seats[seat].monsters.firstOrNull { it != null } ?: g.state.seats[seat].hand.firstOrNull()
                    if (uid != null) {
                        h.duel.inspected = uid
                        h.duel.selection = setOf(uid)
                        h.duel.verbStrip = true
                    }
                }
                // Several cards, one move (1.0.90): --duel-multi=true selects across the near GY and banished pile (two
                // cards banished first) with the banished pile laid open, its badges and the selection's bar showing;
                // --duel-order=top|bottom opens the ordering strip on three of them.
                if (map["duel-multi"] == "true" || map["duel-order"] != null) {
                    val d = h.duel
                    d.bottom = 0
                    val st0 = d.game!!.state
                    d.act(st0.seats[0].deck.take(2).map { DuelAction.Move(it, Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_UP_ATK, "banish") }, 0)
                    val st = d.game!!.state
                    val gy = st.seats[0].gy
                    val ban = st.seats[0].banished
                    val picked = listOfNotNull(gy.getOrNull(0), ban.getOrNull(0), gy.getOrNull(1), ban.getOrNull(1))
                    picked.forEach { d.toggleSelect(it) }
                    if (map["duel-order"] != null) {
                        val three = listOfNotNull(gy.getOrNull(0), ban.getOrNull(0), st.seats[0].hand.firstOrNull())
                        d.clearSelection()
                        three.forEach { d.toggleSelect(it) }
                        d.verbAll(if (map["duel-order"] == "bottom") DuelVerb.DECK_BOTTOM else DuelVerb.DECK_TOP)
                        d.orderMove(1)
                    } else {
                        d.openPile(0, PileKind.BANISHED)
                    }
                    println("[neue-studio] multi: selection ${d.selection}, ordering ${d.ordering}")
                }
                // The chain by keys (1.0.90): --duel-chain-focus=N builds a chain of three and walks the keys' focus onto its
                // link N in the chain well; --duel-chain-menu=true opens Enter's menu there.
                map["duel-chain-focus"]?.let { spec ->
                    val d = h.duel
                    d.bottom = 0
                    val st = d.game!!.state
                    val near = st.seats[0].hand.firstOrNull()
                    val far = st.onField().firstOrNull { st.cards[it]?.controller == 1 }
                    near?.let { d.act(DuelAction.ChainAdd(0, it), 0) }
                    st.onField().firstOrNull { st.cards[it]?.controller == 0 }?.let { d.act(DuelAction.ChainAdd(0, it), 0) }
                    val n = d.game!!.state.chain.size
                    val link = ((spec.toIntOrNull() ?: n) - 1).coerceIn(0, (n - 1).coerceAtLeast(0))
                    d.focusOn(DuelFocus.Slot.Link(link))
                    if (map["duel-chain-menu"] == "true") { d.chainMenu = link; d.chainCursor = 0 }
                    println("[neue-studio] chain: ${d.game!!.state.chain.size} links (far $far), focus ${d.focus}, menu ${d.chainMenu}")
                }
                // --duel-focus=m3|h2|ogy|…: Command mode's ring on that coordinate, as the arrows would leave it (1.0.87);
                // --duel-focus-menu=true opens the card's verbs as Enter does, --duel-pick=h2 picks that card up first.
                map["duel-focus"]?.let { coord ->
                    val d = h.duel
                    val st = d.game!!.state
                    val shape = DuelFocus.Shape(twoSided = h.neue.prefs.duel.twoSided)
                    fun slotFor(c: String) = DuelFocus.cells(st, d.bottom, shape).map { it.slot }
                        .firstOrNull { DuelFocus.label(it, d.bottom) == c }
                    map["duel-pick"]?.let { p -> slotFor(p)?.let { d.picked = DuelFocus.uidAt(st, it) } }
                    val slot = slotFor(coord)
                    d.focusOn(slot)
                    val uid = d.focusUid()
                    if (map["duel-focus-menu"] == "true" && uid != null) {
                        d.inspected = uid
                        d.selection = setOf(uid)
                        d.verbStrip = true
                        d.verbCursor = 1
                    }
                    println("[neue-studio] focus: $coord -> $slot, card $uid, picked ${d.picked}")
                }
                // Command mode's Spotlight (1.0.87): --duel-spot=s_h2_m3 (underscores for spaces) opens it holding that line (--duel-spot alone: empty;
                // --duel-spot=attack: the Battle Phase and an attack the table can make); --duel-spot-state=listening|answer|many
                // (listening: the microphone's bars, the words so far from --duel-heard; answer: the line made, so a question
                // answers; many: the line as given, joined with ";"); --duel-heard="summon ash blossom to monster three" runs
                // the heard words through the real wiring (normalize, classify, shown to confirm).
                val spotState = map["duel-spot-state"]
                if (map["duel-spot"] != null || map["duel-heard"] != null || spotState != null) {
                    val d = h.duel
                    d.bottom = 0
                    // Shot arguments are split on spaces: an underscore stands for one ("s_h2_m3").
                    var line = map["duel-spot"]?.takeIf { it != "true" }?.replace('_', ' ') ?: ""
                    if (line == "attack") {
                        d.act(DuelAction.Phase(DuelPhase.BATTLE), 0)
                        val st = d.game!!.state
                        val mine = st.onField().firstOrNull { DuelVerbs.canAttack(st, 0, it) }
                        val theirs = st.onField().firstOrNull { u -> st.cards[u]?.controller == 1 && st.cards[u]?.faceUp == true && st.placeOf(u).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) } }
                        fun at(u: Int?) = u?.let { DuelNotation.coordOf(st, it, 0, d.game!!.header.seed) }
                        line = "a ${at(mine) ?: "m1"} ${at(theirs) ?: "direct"}"
                    }
                    val heard = map["duel-heard"]?.replace('_', ' ')
                    // A few lines made before, for the empty box's Recent.
                    listOf("draw", "s h1 m3", "bp").forEach { d.rememberLine(it) }
                    when (spotState) {
                        "listening" -> {
                            d.openSpotlight(mode = com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode.LISTENING)
                            d.spotlight = d.spotlight?.copy(heard = heard ?: "summon ash blossom to")
                            d.spotlightLevels = listOf(0.02f, 0.05f, 0.11f, 0.16f, 0.09f, 0.14f, 0.12f, 0.06f, 0.15f, 0.1f, 0.04f, 0.13f, 0.08f, 0.03f)
                        }
                        "answer" -> {
                            if (heard != null) com.kaiharimoto.neue.duel.spotHeard(h, heard)
                            else { d.openSpotlight(line.ifEmpty { "their field" }, swallow = null); com.kaiharimoto.neue.duel.spotEnter(h, keep = false) }
                        }
                        else -> if (heard != null) com.kaiharimoto.neue.duel.spotHeard(h, heard) else d.openSpotlight(line, swallow = null)
                    }
                    println("[neue-studio] spotlight: ${d.spotlight}")
                }
                // --duel-replay=N: the duel as a replay, stood at entry N (or halfway), with a note there.
                map["duel-replay"]?.let { spec ->
                    val g = h.duel.game!!
                    val at = spec.toIntOrNull() ?: (g.cursor / 2)
                    h.duel.replay = Replay("studio", g.record("Studio duel"), at)
                    h.duel.note("Ash here would have stopped the whole line")
                }
                // --duel-net=guest: the same duel as the guest's seat is sent it over the network — its own view only.
                if (map["duel-net"] == "guest") {
                    val g = h.duel.game!!
                    val u = DuelHost.update(g, 1, 0, g.header.seed, h.duel.catalog)
                    h.duel.role = Duels.NetRole.GUEST
                    h.duel.remote = DuelMirror.game(u.view, g.header)
                    h.duel.remoteLines = u.lines
                    h.duel.remoteWaiting = 1
                    h.duel.peer = "Kai"
                    h.duel.netStatus = "Playing Kai over the network"
                    h.duel.bottom = 1
                }
                h.neue.page = Page.DUEL
                clock.run(120)
                if (dice != null) studioDice(h, dice, clock)
                // --duel-chance=landed|flying|held: the table's die and coin (1.0.96) — both seats' thrown and lying on the
                // field, the near seat's coin mid-flip, or the near seat's die carried in the hand.
                map["duel-chance"]?.let { studioChance(h, it, clock) }
                val g = h.duel.game!!
                println("[neue-studio] duel: ${g.cursor} entries, field ${g.state.onField().size}, hands ${g.state.seats.map { it.hand.size }}, lp ${g.state.seats.map { it.lp }}")
            }
            // --present=demo: a deck profile of the builder's deck (1.0.70), opened in the editor;
            // --present-style=spotlight|slides|buildup, --present-theme=arena|neon|…, --present-webcam=tr|tl|br|bl|left|right|off,
            // --present-slide=N the slide in view, --present-mode=library|edit|play|overview|notes, and
            // --present-frames=N,K: from that slide, the next click and N stills K frames apart;
            // --present-module=TYPE: a module's slides from sample data.
            if (map["present"] == "demo") {
                val style = when (map["present-style"]) {
                    "slides" -> Presentation.STYLE_SLIDES
                    "buildup" -> Presentation.STYLE_BUILD_UP
                    else -> Presentation.STYLE_SPOTLIGHT
                }
                val webcam = when (map["present-webcam"] ?: "br") {
                    "off" -> WebcamZone()
                    "tr" -> WebcamZone(true, WebcamZone.TOP_RIGHT)
                    "tl" -> WebcamZone(true, WebcamZone.TOP_LEFT)
                    "bl" -> WebcamZone(true, WebcamZone.BOTTOM_LEFT)
                    "left" -> WebcamZone(true, WebcamZone.LEFT_COLUMN)
                    "right" -> WebcamZone(true, WebcamZone.RIGHT_COLUMN)
                    else -> WebcamZone(true, WebcamZone.BOTTOM_RIGHT)
                }
                val snap = PresentEdits.snapshot(
                    h.builder.deck, h.builder.groups, h.builder.deckName, h.builder.deckId, h.neue.prefs.groupArrangement,
                    h.neue.prefs.arts, 0, System.currentTimeMillis(),
                )
                var p = PresentEdits.newProfile(
                    "pstudio", "${h.builder.deckName} deck profile", snap, style, map["present-theme"]?.let { Themes.named(it)?.id } ?: Themes.MASTER,
                    webcam, "kai", System.currentTimeMillis(), kotlin.random.Random(7),
                )
                // A note on each group's step, as a creator would write it.
                p = p.copy(slides = p.slides.map { sl ->
                    val f = sl.deck ?: return@map sl
                    if (f.all) sl else sl.copy(deck = f.copy(note = "Why these: what they open, what they need, and the line they start."))
                })
                // --present-module=SIDING|MATCHUPS|PERFORMERS|…: that module's slides after the title, from sample
                // data made of the deck's own cards (1.0.71), the first of them in view.
                var moduleFirst: String? = null
                map["present-module"]?.uppercase()?.let { type ->
                    val ids = h.builder.deck.main.map { it.value }.distinct()
                    val side = h.builder.deck.side.map { it.value }.distinct().ifEmpty { ids.takeLast(3) }
                    fun pick(i: Int, note: String) = Pick(ids[i % ids.size], note)
                    val input = ModuleInput(
                        title = "",
                        matchups = listOf(
                            SideMatchup("Snake-Eye", SideTurn(ids.take(2), side.take(2), "Their board is one big turn: stop it early."), SideTurn(ids.drop(2).take(2), side.take(2), "Break the board, then out-grind."), "The most played deck."),
                            SideMatchup("Yubel", SideTurn(ids.take(1), side.take(1), "Keep the board small."), SideTurn(ids.take(1), side.takeLast(1), "Out them with removal."), ""),
                        ),
                        rows = listOf(
                            MatchRow("Snake-Eye", 0.6, 10, 0.4, 10, 0.5, 10, 0.55, 10, 0.52, 20, 30),
                            MatchRow("Yubel", 0.7, 8, 0.5, 8, 0.6, 8, 0.6, 8, 0.6, 16, 20),
                            MatchRow("Fiendsmith", 0.4, 6, 0.3, 6, 0.35, 6, 0.4, 6, 0.37, 12, 25),
                        ),
                        expected = 0.54,
                        strong = listOf(pick(0, "Opened every hand it touched"), pick(3, "Live in every matchup")),
                        weak = listOf(pick(5, "Dead going second"), pick(7, "Too slow against Yubel")),
                        picks = listOf(pick(1, "The starter"), pick(2, "Extends through a hand trap"), pick(4, "The payoff")),
                        rounds = listOf(RoundRow(1, "Snake-Eye", "Won"), RoundRow(2, "Yubel", "Won"), RoundRow(3, "Fiendsmith", "Lost"), RoundRow(4, "Tenpai", "Won")),
                        record = "3–1",
                        placement = "Top 8",
                        shoutouts = listOf(Shoutout(null, "Locals crew", "@locals", "Testing every week"), Shoutout(null, "Card shop", "@shop", "Hosting the event")),
                        odds = GroupStats.of(h.builder.deck, h.builder.groups, { h.builder.index.byId(it) }).groups.map { g ->
                            GroupOdds(g.name, g.opening, g.openingSecond, g.main + g.extra + g.side, g.color)
                        },
                        code = YdkeCodec.encode(h.builder.deck),
                    ).let { if (type == Modules.TOURNAMENT) it.copy(title = "Regional Qualifier", subtitle = "2026-09-20 · 120 players · Tier 2") else it }
                    val made = Modules.generate(type, input, System.currentTimeMillis(), kotlin.random.Random(4))
                    var after = 0
                    made.forEach { sl -> p = PresentEdits.addSlide(p, sl, after); after++ }
                    moduleFirst = made.firstOrNull()?.id
                }
                h.present.create(p)
                moduleFirst?.let { h.present.slideId = it }
                map["present-slide"]?.toIntOrNull()?.let { n -> p.slides.getOrNull(n)?.let { h.present.slideId = it.id } }
                h.neue.page = Page.PRESENT
                clock.run(80)
                when (map["present-mode"]) {
                    "library" -> { h.present.close(); clock.run(60) }
                    "restyle" -> { h.present.restyling = true; clock.run(40) }
                    "style" -> { h.present.restyleBefore = h.present.open; clock.run(20) }
                    "play", "overview", "notes" -> {
                        h.present.present(h.present.slideIndex)
                        clock.run(90)
                        if (map["present-mode"] == "overview") { h.present.toggleOverview(); clock.run(90) }
                        if (map["present-mode"] == "notes") { h.present.toggleNotes(); clock.run(30) }
                    }
                }
                map["present-frames"]?.let { spec ->
                    val (n, k) = spec.split(",").map { it.toInt() }
                    if (h.present.playing == null) { h.present.present(h.present.slideIndex); clock.run(90) }
                    h.present.next()
                    val dir = File(out, "$name-frames").apply { mkdirs() }
                    repeat(n) { i ->
                        clock.run(k)
                        val still = clock.frame().encodeToData(EncodedImageFormat.PNG) ?: error("no encode")
                        File(dir, "%03d.png".format(i)).writeBytes(still.bytes)
                    }
                    println("[neue-studio] present: $n frames in ${dir.name}")
                }
                println("[neue-studio] present: ${p.slides.size} slides, ${p.deck?.groups?.size ?: 0} groups, style ${p.style}")
            }
            // --world=demo: a world seeded onto Ai World's page (1.0.97), every kind of board on its canvas;
            // --world-pane=boards|editor|… gives that pane the page, --world-ai=… puts Ai in it (`WorldStudio.kt`).
            if (map["world"] == "demo") {
                studioWorld(h, map)
                h.neue.page = Page.WORLD
                clock.run(120)
            }
            // --ydkw=path: a web of decks opened, as Format's Open a .ydkw does (1.0.33);
            // --web-deck=N then puts its N-th deck on the builder, to show the bar's switcher.
            // --start=new|update[:N]: the setup offered on opening (1.0.69), as someone new sees it or someone
            // updating from before sync; :N opens it on its N-th step.
            map["start"]?.let { spec ->
                val fresh = spec.startsWith("new")
                h.decksKnown = !fresh
                h.neue.update { it.copy(start = StartPrefs(seen = if (fresh) "" else "1.0.60")) }
                h.neue.startSteps = if (fresh) StartStep.entries.toList()
                else listOf(StartStep.SYNC, StartStep.AI, StartStep.ART)
                StudioStart.at = spec.substringAfter(':', "0").toIntOrNull() ?: 0
                clock.run(20)
            }
            // --sync=<folder>: this run as a device syncing with a folder (1.0.68) — what it sent and took, and
            // the decks it holds after; --device=Name names it. Two runs with different --data and XDG_DATA_HOME
            // are two devices meeting in one folder.
            map["sync"]?.let { folder ->
                h.webs.load()
                h.prep.load()
                clock.run(30)
                h.neue.update { it.copy(sync = it.sync.copy(service = SyncPrefs.FOLDER, folder = folder, deviceName = map["device"] ?: "Studio")) }
                h.sync.syncNow()
                clock.run(10)
                var waited = 0
                while ((h.sync.running || h.sync.last == null && h.sync.problem == null) && waited < 600) { clock.run(5); waited++ }
                val decks = kotlinx.coroutines.runBlocking { h.deps.deckRepository.all() }
                println("[neue-studio] sync: ${h.sync.problem ?: h.sync.last?.let { com.kaiharimoto.neue.sync.describe(it) }}")
                println("[neue-studio] sync: ${decks.size} decks here: ${decks.joinToString { it.entry.name }}")
                println("[neue-studio] sync: theme ${h.neue.prefs.theme}, ${h.webs.library.webs.size} webs, ai files ${java.io.File(com.kaiharimoto.neue.platform.Platform.dataDir, "ai").walkTopDown().count { it.isFile }}")
            }
            // --book=pdf|json|all: the sample reader's guide (the Las Vegas Labrynth as a book) written to shots/ (1.0.67).
            map["book"]?.let { which ->
                val book = BookSample.labrynth
                val dir = java.io.File(map["out"] ?: "shots").also { it.mkdirs() }
                if (which == "pdf" || which == "all") {
                    val bytes = kotlinx.coroutines.runBlocking { AiDocs.bookBytes(h, book) }
                    java.io.File(dir, "book.pdf").writeBytes(bytes)
                    println("[neue-studio] book: ${bytes.size / 1024} KiB to ${dir}/book.pdf")
                }
                if (which == "json" || which == "all") {
                    java.io.File(dir, "book.json").writeText(GuideBook.write(book))
                    println("[neue-studio] book: json to ${dir}/book.json")
                }
            }
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
                            val bytes = GuideExport.build(h.webs, web, decks, me, h.builder, h.neue, h.art, h.customArt)
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
                val today = IsoDate.epochDay(h.prep.today()) ?: 0L
                val event = PrepEvent(
                    "ev-demo", "Regional Qualifier", IsoDate.of(today + 12), tier = 2, attendance = 96,
                    webId = web?.id, deckId = mine, decklist = PrepEvent.DECKLIST_PAPER,
                    deadline = IsoDate.of(today + 10), checkIn = "Saturday 9:00, closes 9:45",
                    checked = listOf("id", "dice"),
                )
                var doc = h.prep.doc.put(event).copy(active = event.id, profile = PrepProfile("Kai Harimoto", "0412345678", "USA"))
                // A week of practice: the mirror close, the loose matchup worse going second.
                val results = listOf("W", "L", "W", "W", "L", "W", "L", "L", "W", "D", "W", "L")
                val names = foes.map { f -> f.deckId to (h.webs.decks(web!!).firstOrNull { it.entry.id == f.deckId }?.entry?.name ?: "Opponent") } +
                    listOf("Snake-Eye Fire King" to "Snake-Eye Fire King")
                results.forEachIndexed { n, r ->
                    val (key, name) = names[n % names.size]
                    doc = doc.record(
                        TestGame(
                            "g$n", 1_000L * n, mine, key, name,
                            if (n % 2 == 0) TestGame.FIRST else TestGame.SECOND,
                            game = 1 + n % 3, result = r, minutes = if (key == names.last().first) 19 else 12,
                            reason = if (r == "L") TestGame.REASON_INTERRUPTED else null,
                        ),
                    )
                }
                doc = doc.record(TestGame("r1", 99_000, mine, names.first().first, names.first().second, "", result = "W", note = "2–1", eventId = event.id, round = 1))
                doc = doc.copy(drills = mapOf("m1:FIRST" to DrillStat(3, 2, 1, 2)))
                h.prep.commit(doc)
                map["prep-tab"]?.let { t -> h.prep.tab = PrepTab.valueOf(t.uppercase()) }
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
                    if (step == "saving" && at is PoolProgress.Saving && at.done > 0) break
                    if (step != "saving" && at is PoolProgress.Downloading && at.bytes > 4_000_000) break
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
                val at = ids.indexOfFirst { id -> (h.builder.index.byId(id)?.let { CardArt.arts(it).size } ?: 0) > 1 }
                val card = ids.getOrNull(at)?.let(h.builder.index::byId)
                if (card != null) {
                    val next = CardArt.step(card, null, 1)
                    h.neue.update { it.copy(arts = it.arts + (card.id.value to next.value)) }
                    h.neue.selection = Selection.InDeck(card, DeckSection.MAIN, at)
                }
                println("[neue-studio] art: ${card?.name} at main $at, ${card?.let { com.kaiharimoto.mastertool.core.model.CardArt.arts(it) }}")
            } else map["art"]?.toIntOrNull()?.let { passcode ->
                // --art=46986414: that card, searched for in the pool and selected, on its second artwork.
                val card = h.builder.index.byId(CardId(passcode))
                if (card != null) {
                    val next = CardArt.step(card, null, 1)
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
            // --inspect=72270339: that card, searched for in the pool and selected, so the inspector reads it (1.0.88).
            map["inspect"]?.toIntOrNull()?.let { passcode ->
                val card = h.builder.index.byId(CardId(passcode))
                if (card != null) {
                    h.builder.onQueryChange(card.name)
                    h.neue.selection = Selection.InPool(card, 0)
                    // --inspect-view: opened large too, as a phone reads it (a phone has no inspector).
                    if (map["inspect-view"] == "true") h.neue.viewing = com.kaiharimoto.neue.Viewing(card, null, 0)
                }
                println("[neue-studio] inspect: ${card?.name} (${card?.description?.length} characters)")
            }
            // Zen is set by hand here: idleness is measured in wall-clock time, and a
            // studio run is minutes of it for seconds of frames.
            h.zenAuto = false
            if (map["immersive"] == "true") h.neue.immersive = true
            if (map["pinned"] == "true") h.neue.update { it.copy(railPinned = true) }
            map["reveal"]?.let { spec ->
                val parts = spec.split(",")
                h.neue.revealed = Revealed(left = "left" in parts, top = "top" in parts, bottom = "bottom" in parts)
            }
            if (map["palette"] == "true") h.neue.paletteOpen = true
            // --updatedialog: the update dialog on a made-up release, as a phone must be able to reach its Install.
            if (map["updatedialog"] == "true") h.updates.offer(h.updates.sample())
            // --phonemenu: the phone's overflow menu, open.
            if (map["phonemenu"] == "true") h.neue.menu = MenuSpec(androidx.compose.ui.geometry.Offset(width.toFloat(), 48f), h.phoneMenu(androidx.compose.ui.geometry.Offset(width.toFloat(), 48f)))
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
                val picture = map["crop-picture"]?.let { java.io.File(it) }?.takeIf { it.isFile }?.let { PickedFile(it.name, it.readBytes()) }
                h.builder.index.byId(id ?: return@let)?.let { card -> h.neue.cropping = ArtCropping(card, picture) }
            }
            // --group-palettes=true: the Groups panel's palettes opened out (with --groups).
            if (map["group-palettes"] == "true") h.neue.groupPalettesOpen = true
            // --history: a few edits, then the history menu open, to see it list them.
            if (map["history"] == "true") {
                val deck = h.builder.deck
                deck.main.firstOrNull()?.let(h.builder.index::byId)?.let { c -> h.builder.addCard(c, DeckSection.MAIN) }
                deck.side.firstOrNull()?.let(h.builder.index::byId)?.let { c -> h.builder.removeAt(c, DeckSection.SIDE, 0) }
                clock.run(10)
                h.neue.menu = MenuSpec(androidx.compose.ui.geometry.Offset(width * 0.5f, 48f), com.kaiharimoto.neue.builder.historyMenu(h.builder))
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
                Expression.byId(id)?.let { h.ai.express(it, 8) }
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
                val kinds = spec.split(",").mapNotNull { k -> EffectKind.entries.firstOrNull { it.name.equals(k.trim(), true) } }.toSet()
                h.builder.onFilterChange(h.builder.filter.copy(effects = kinds))
            }
            if (map["nopool"] == "true") h.neue.update { it.copy(poolVisible = false) }
            if (map["noinspector"] == "true") h.neue.update { it.copy(inspectorVisible = false) }
            // --studio=deck|list: the search pop-out, adding to the deck or to the list made by --list.
            map["studio"]?.let { mode -> h.neue.studio = com.kaiharimoto.neue.Studio(if (mode == "list") h.neue.prefs.poolList else null) }
            map["drawer"]?.let { h.neue.drawer = Drawer.ISSUES }
            // --genesys=true / --legal-as-of=2025-05-01: what the builder checks against (1.1.1).
            if (map["genesys"] == "true") h.neue.update { it.copy(genesys = true) }
            map["legal-as-of"]?.let { day -> h.neue.update { it.copy(legalAsOf = day) } }
            if (map["goal"] == "true") h.builder.newGoal()
            clock.run((map["frames"] ?: "90").toInt())
            map["zen"]?.let { phase ->
                h.neue.immersive = true
                clock.run(30)
                h.neue.zen = if (phase == "quiet") ZenPhase.QUIET else ZenPhase.DEEP
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
                        val key = ZenArrangement.key(0, n.toInt())
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
                            val r = ZenPick.rectOf(key, h.zen.homes, h.zen.arrangement, h.zen.stage, h.zen.pivot.x, h.zen.pivot.y)
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
                val covers = cards.split(",").mapNotNull { it.trim().toIntOrNull() }.map { CardId(it) }
                val id = h.builder.deckId
                if (id != null) {
                    val now = SidingCodec.read(h.builder.extendedNow())
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
                    val names = { ids: List<Int> -> ids.map { id -> h.builder.index.byId(CardId(id))?.name?.take(10) ?: "$id" } }
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
                ShotStyle.entries.forEach { style ->
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
            connections = listOf(AiConnection("anthropic-demo", "anthropic", "Anthropic", "claude-opus-5-5")),
            active = "anthropic-demo",
        ))
    }
    // --ai=setup: the first setup, the whole window (1.0.45) — Ai asked for with no connection.
    if (mode == "setup") h.neue.update { it.copy(ai = it.ai.copy(connections = emptyList(), active = null)) }
    // --ai=saved (1.0.59): Setup pressed with connections made opens on them; with --ai-step=KEY:compatible, the person's own presets.
    if (mode == "saved") {
        h.neue.update {
            it.copy(ai = it.ai.copy(connections = it.ai.connections + listOf(
                AiConnection("compatible-mimo", "compatible", "Xiaomi MiMo", "mimo-v2-pro", "https://api.xiaomimimo.com/v1"),
                AiConnection("compatible-work", "compatible", "Work gateway", "gpt-5", "https://llm.example.com/v1"),
            )))
        }
    }
    when (mode) {
        "wizard", "setup", "saved" -> {
            ai.openWizard()
            step?.let { spec ->
                ai.wizard.saved = false
                val (name, provider) = spec.split(':').let { it[0] to it.getOrNull(1) }
                Providers.byId(provider)?.let { p ->
                    ai.wizard.kind = p.kind
                    ai.wizard.choose(p)
                    if (name == "MODEL") {
                        ai.wizard.models = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5")
                        ai.wizard.model = "claude-opus-5-5"
                    }
                }
                SetupStep.entries.firstOrNull { it.name == name }?.let { ai.wizard.step = it }
            }
        }
        "panel" -> {
            val now = System.currentTimeMillis()
            fun result(name: String, summary: String) = Part.ToolResult("t-$name", name, "{}", summary = summary)
            ai.preview(
                AiSession(
                    id = "studio",
                    title = "Tune for YCS",
                    connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("Tune this for a field full of Maliss and Mitsurugi, and group it."),
                        ChatTurn.assistant("Let me look at the list first."),
                        ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(result("get_deck", "Read the open deck"), result("search_cards", "Found 12 cards for “negate”"))),
                        ChatTurn.assistant(
                            "## The plan\n\nYour engine is tight — the flex slots are the problem.\n\n" +
                                "- **+1** [[Infinite Impermanence]]: Maliss lives on its link plays.\n" +
                                "- **−1** [[Nibiru, the Primal Being]]: Mitsurugi rarely summons five times.\n\n" +
                                "Grouped into *Starters*, *Extenders*, *Hand traps* and *Bricks*.",
                        ),
                        ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(result("edit_deck", "+1 Infinite Impermanence, −1 Nibiru, the Primal Being"), result("set_groups", "Grouped the deck: Starters, Extenders, Hand traps, Bricks"))),
                        ChatTurn.assistant("Done — one step of undo if you want it back."),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        // --ai=tables (1.0.65): tables of two, four and six columns laid out to the panel — fitted, wrapped, stacked.
        "tables" -> {
            val now = System.currentTimeMillis()
            val reply = """Here is how your flex slots compare.

| Card | Why |
| --- | --- |
| [[Infinite Impermanence]] | Stops Maliss's first link play and still works from the hand going second. |
| [[Dominus Impulse]] | Answers a quick effect without needing a set trap first. |

| Matchup | Going first | Going second | Share |
| --- | --- | --- | ---: |
| Maliss | Floodgates, hold Welcome | Impermanence, Dominus | 22% |
| Mitsurugi | Big Welcome early | Ash, Droll | 18% |
| Ryzeal | Lady on board | Nibiru, Veiler | 12% |

| Card | Copies | Role | Opens | Dead going second | Side out vs |
| --- | ---: | --- | ---: | --- | --- |
| [[Arianna the Labrynth Servant]] | 3 | Starter | 34% | No | Nothing |
| [[Big Welcome Labrynth]] | 3 | Engine | 34% | Sometimes | Mitsurugi |

The long reasons sit under the first table only where they must; the third is too wide for the panel, so it reads a row at a time."""
            ai.preview(
                AiSession(
                    id = "studio-tables",
                    title = "Flex slots",
                    connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("Compare my flex slots and the matchups."),
                        ChatTurn.assistant(reply),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        // --ai=heard (1.0.65): a read-back in Learn About You, what it heard above the question.
        "heard" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                AiSession(
                    id = "studio-heard",
                    title = "Learn About You",
                    connection = "anthropic-demo",
                    mode = AiSession.MODE_PROFILE,
                    turns = listOf(
                        ChatTurn.user("Let's do Learn About You."),
                        ChatTurn.assistant("You've built three Labrynth lists this month and Las Vegas is on the 12th — let me check I've got you right."),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ai.previewTuning(
                Question(
                    "Anything to correct?",
                    listOf("All right", "Fix something"),
                    false,
                    heard = listOf(
                        "You're taking the no-floodgate Labrynth to Las Vegas, aiming for top cut.",
                        "You test on DuelingBook with two friends on weeknights.",
                        "You want short answers, and tables when it's numbers.",
                        "[[Dominus Impulse]] is your favourite tech going second.",
                    ),
                ),
                null,
            )
        }
        // --ai=videokey (1.0.62): a video linked with no Gemini key; the key's box stands in the chat.
        "videokey" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                AiSession(
                    id = "studio-video",
                    title = "Learn from a deck profile",
                    connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("Learn the deck from this deck profile: https://youtu.be/fE-RsenvS5I"),
                        ChatTurn(
                            com.kaiharimoto.mastertool.core.ai.Role.USER,
                            listOf(Part.ToolResult("t-video", "watch_video", "{}", isError = true, summary = "Watching a video needs a Gemini API key")),
                        ),
                        ChatTurn.assistant(
                            "To watch it I need a **Gemini key** — Gemini is the model that can watch a YouTube video, frames and sound. " +
                                "It's free: make one below, paste it, and ask me again. We'll keep chatting on this model.",
                        ),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ai.videoKeyAsked = true
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
                AiSession(
                    id = "studio-study",
                    title = "Study",
                    connection = "anthropic-demo",
                    mode = AiSession.MODE_STUDY,
                    turns = listOf(
                        ChatTurn.user("Study “lab” yourself, and think out loud so I can learn with you. Intensity: Standard."),
                        ChatTurn(
                            com.kaiharimoto.mastertool.core.ai.Role.ASSISTANT,
                            listOf(
                                Part.Reasoning("Labrynth is a trap deck: the Furniture monsters set traps and Lady Labrynth recycles them. The engine is 23 cards, so the ratio of Furniture to Normal Traps matters most."),
                                Part.Activity("archetype_guide", "Read how Labrynth plays: Playing style, Weaknesses"),
                                Part.Activity("rulings", "Read 14 rulings for Lady Labrynth of the Silver Castle"),
                                Part.Text(
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
                ai.previewTuning(Question("You run 3 [[Arianna the Labrynth Servant]] and 3 Ariane. Which is your real starter?", listOf("Arianna", "Ariane", "Both, depends on the hand"), false, listOf(card)), null)
            }
        }
        // Its thinking above a reply, and a plan in hand (1.0.47).
        "reason" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                AiSession(
                    id = "studio-reason",
                    title = "Ratios",
                    connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("Should I play a third Called by the Grave?"),
                        ChatTurn(
                            com.kaiharimoto.mastertool.core.ai.Role.ASSISTANT,
                            listOf(
                                Part.Reasoning(
                                    "The deck has 9 hand traps and 2 Called by the Grave. The field is mostly Snake-Eye and Yubel, which lean on hand traps less than on board breakers going second.\n" +
                                        "A third Called helps when they open Ash into my starter, which is 1 in 3 games at most. Worth checking the odds of drawing 2 with 3 in the deck.",
                                ),
                                Part.Text("Checking how often a third copy shows up next to the first.\n\nAt 3 copies you open at least one **33.8%** of the time going first, and two at once only **2.8%**: the third is live, not a brick. Keep it if Ash is common at your event."),
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
                AiSession(
                    id = "studio-chart",
                    title = "Odds",
                    connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("How likely am I to open a starter, and what do I side against Snake-Eye?"),
                        ChatTurn.assistant(
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
        "guide", "refactor", "end", "brain", "quick", "profile", "about", "petted" -> studioAi154(h, mode)
        // 1.0.99: Trust, with sample runs — hand odds and rulings scored, decklists not run yet, the checker's catch rate.
        "trust" -> {
            val ai = h.ai
            val conn = ai.prefs.connection?.id ?: "anthropic-demo"
            fun sample(set: EvalSet, missEvery: Int, tries: Int, at: Long) =
                EvalRun(
                    set.id, conn, "claude-opus-5-5", at, tries,
                    set.items.mapIndexed { i, item ->
                        val pass = (i + 1) % missEvery != 0
                        val miss = when (item.grader) {
                            is Grader.YesNo -> if ((item.grader as Grader.YesNo).expected) "no (expected yes)" else "yes (expected no)"
                            is Grader.Planted -> if ((item.grader as Grader.Planted).hasError) "missed (0 other claims marked wrong)" else "false alarm: a true claim marked wrong"
                            else -> "74.5% (expected 74.2%)"
                        }
                        ItemOutcome(item.id, if (pass) tries else 0, tries, pass, if (pass) "right" else miss)
                    },
                    tokensIn = set.items.size * 5_200L * tries, tokensOut = set.items.size * 420L * tries,
                )
            val now = System.currentTimeMillis()
            val runs = listOf(
                sample(EvalSets.handOdds(), 6, 3, now - 86_400_000),
                sample(EvalSets.rulings(), 10, 1, now - 3_600_000),
                sample(EvalSets.planted(), 5, 1, now - 600_000),
            )
            ai.files.write(EvalLog.path(conn), EvalLog.write(runs))
            ai.evalVersion++
            ai.trustOpen = true
        }
        // 1.0.67: the reader's guide, the sample book open in the reader; --ai=reader-lines lands on the first line,
        // reader-lessons on the lessons, reader-empty is a deck with no book.
        "reader", "reader-lines", "reader-lessons", "reader-empty" -> {
            val deckId = h.builder.deckId ?: "studio-lab"
            val path = GuideBook.path(deckId)
            if (mode == "reader-empty") {
                h.ai.files.delete(path)
            } else {
                val book = BookSample.labrynth
                h.ai.files.write(path, GuideBook.write(book))
                // The reader's rows: the cover, then each chapter's opening, each section's head and each block.
                var at = 1
                var landed = 0
                book.chapters.forEach { ch ->
                    if (mode == "reader-lessons" && ch.title == "Lessons") landed = at
                    at++
                    ch.sections.forEach { s ->
                        at++
                        s.blocks.forEach { b ->
                            if (mode == "reader-lines" && landed == 0 && b is com.kaiharimoto.mastertool.core.ai.report.book.Block.Line && b.frames) landed = at
                            at++
                        }
                    }
                }
                h.ai.bookPlaces[deckId] = landed
            }
            h.ai.bookChanged()
            h.neue.reading = deckId
        }
        // 1.0.55: a picture sent and read into a deck, the new layouts, pictures waiting to go.
        "picture", "visual", "attach" -> studioAi155(h, mode)
        // 1.0.56: a long conversation, its start summarised, the gauge; --ai=context opens the panel.
        // 1.0.57: listening with the words arriving, talk mode speaking, the model's download asked for.
        "listening", "talk", "voice" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                AiSession(
                    id = "studio-157", title = "Voice", connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("What do I side in against Snake-Eye going second?"),
                        ChatTurn.assistant("Two [[Infinite Impermanence]] for your two [[Labrynth Cooclock]]: their turn is a chain of Special Summons, and Impermanence stops the first one."),
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
                AiSession(
                    id = "studio-158", title = "Siding", connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("What do I side in against Snake-Eye going second?"),
                        ChatTurn.assistant(
                            "Bring in two [[Infinite Impermanence]]: it negates their first monster's effect, and you can activate it from your hand at any time. " +
                                "Keep [[Ash Blossom & Joyous Spring]] for [[Snake-Eye Ash]]'s search.",
                        ),
                        ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(Part.Context("check"))),
                        ChatTurn.assistant(
                            "**Correction:** [[Infinite Impermanence]] can be activated from the hand only if you control no cards — so going second it works on your opponent's first turn, before you play anything. The siding advice stands.",
                        ),
                    ),
                    checks = listOf(
                        FactCheck.Check(
                            1,
                            listOf(
                                FactCheck.Claim("Infinite Impermanence negates a monster's effects", FactCheck.Verdict.OK, source = "card text"),
                                FactCheck.Claim("It can be activated from the hand at any time", FactCheck.Verdict.WRONG, "only if you control no cards", "card text"),
                                FactCheck.Claim("Ash Blossom negates Snake-Eye Ash's search", FactCheck.Verdict.OK, source = "rulings"),
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
                    ChatTurn.user("Question $i about the siding against Snake-Eye.", at = now - (9 - i) * 60_000L),
                    ChatTurn.assistant("Answer $i: bring in [[Infinite Impermanence]] going second, and keep the traps for their turn.", at = now - (9 - i) * 60_000L + 1),
                )
            }
            val system = ai.previewSystem()
            ai.preview(
                AiSession(
                    id = "studio-156", title = "Siding", connection = "anthropic-demo", system = system,
                    turns = talk, summary = "They play Labrynth and are siding against Snake-Eye: Impermanence in going second, traps held for the opponent's turn; they fear backrow removal.",
                    summarized = 10, context = 128_400,
                    usage = Usage(input = 180_000, output = 9_400, cacheRead = 610_000, cacheWrite = 90_000),
                    createdAt = now, updatedAt = now,
                ),
            )
            if (mode == "context") ai.contextOpen = true
        }
        // Fine Tuning (phase 3): the interview mid-way, a question on the table; or its review.
        "tune", "review" -> {
            val now = System.currentTimeMillis()
            ai.preview(
                AiSession(
                    id = "studio-tune",
                    title = "Fine Tuning",
                    connection = "anthropic-demo",
                    mode = AiSession.MODE_TUNE,
                    turns = listOf(
                        ChatTurn.user("Let's do Fine Tuning."),
                        ChatTurn.assistant("Good — a few questions, one at a time, and I'll remember the answers. First, the event."),
                        ChatTurn.user("A regional in three weeks, about 200 players."),
                        ChatTurn.assistant("Noted. Which deck are you taking, and how well do you know it?"),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (mode == "tune") {
                ai.previewTuning(Question("Going first or second — which do you choose when you win the roll?", listOf("First", "Second", "Depends on the matchup"), false), null)
            } else {
                ai.previewTuning(
                    null,
                    listOf(
                        MemoryChange("USER.md", listOf("Regional in three weeks, about 200 players.", "Plays Branded Dracotail; knows it well.", "Chooses to go second."), listOf("Plays Branded.")),
                        MemoryChange("MEMORY.md", listOf("Explain lines with the cards named, not in general."), emptyList()),
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
    ai.files.write(AiMemory.path(MemoryKind.GUIDE, deckId), guide)
    val day = 86_400_000L
    val now = System.currentTimeMillis()
    val reports = listOf(
        Triple(SessionReport.STUDIED, Triple(38, 30, 34), 9 * day),
        Triple(SessionReport.TAUGHT, Triple(56, 44, 47), 4 * day),
        Triple(SessionReport.PRINCIPLES, Triple(71, 55, 52), 0L),
    ).mapIndexed { i, (m, s, ago) ->
        SessionReport(
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
            questions = if (m == SessionReport.STUDIED) emptyList() else listOf(
                SessionReport.Asked("You run 3 Arianna and 3 Ariane. Which is your real starter?", "Arianna, always; Ariane is for going second."),
                SessionReport.Asked("What do you fear most across the table?", "Backrow removal before my Furniture resolve."),
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
        "guide" -> ai.docOpen = LivingDoc.Guide(deckId, deckName)
        // Refactor guide (1.0.66): the launcher, on its fourth way in.
        "refactor" -> ai.askTune(AiSession.MODE_REFACTOR)
        "profile" -> ai.docOpen = LivingDoc.Profile
        "about" -> ai.profileAsk = true
        "quick" -> ai.quickOpen = true
        "brain" -> ai.memoryOpen = AiMemory.path(MemoryKind.GUIDE, deckId)
        "end" -> {
            ai.endReport = reports.last()
            ai.previewTuning(
                null,
                listOf(MemoryChange(
                    AiMemory.path(MemoryKind.GUIDE, deckId),
                    listOf("Connections: Lovely Labrynth of the Silver Castle sets any Normal Trap from the grave.", "Open questions: Which trap to set first?"),
                    emptyList(),
                )),
            )
        }
        "petted" -> {
            ai.preview(
                AiSession(
                    id = "studio-petted", title = "Hello", connection = "anthropic-demo",
                    turns = listOf(ChatTurn.user("Hi."), ChatTurn.assistant("Hello — what are we building today?")),
                    createdAt = now, updatedAt = now,
                ),
            )
            ai.touched(AvatarPlay.Reaction(Expression.LOVE, 600.0, "I could get used to this."))
        }
    }
    // The guide and the last report as PDFs, in the real fonts and chosen art, beside the shots.
    if (mode == "guide" || mode == "end") runCatching {
        kotlinx.coroutines.runBlocking {
            val dir = java.io.File("../shots").apply { mkdirs() }
            dir.resolve("ai-guide.pdf").writeBytes(AiDocs.guideBytes(h, deckId, deckName))
            dir.resolve("ai-report.pdf").writeBytes(AiDocs.reportBytes(h, reports.last()))
        }
    }.onFailure { System.err.println("studio: the PDFs failed: $it") }
}


/** The studio's pictures of 1.0.55: a decklist screenshot read into a deck, each new layout, the composer's waiting pictures. */
private fun studioAi155(h: com.kaiharimoto.neue.NeueHolders, mode: String) {
    val ai = h.ai
    val now = System.currentTimeMillis()
    val shot = java.io.File("../docs/shots/neue-builder.png").takeIf { it.isFile }?.readBytes()
    val deck = h.builder.deck
    fun name(id: CardId) = h.builder.index.byId(id)?.name
    fun counted(ids: List<CardId>) =
        ids.groupingBy { it }.eachCount().entries.mapNotNull { (id, n) -> name(id)?.let { "$n $it" } }.joinToString("\n")
    val main = deck.main.mapNotNull(::name).distinct()
    val sessionId = "studio-155"
    when (mode) {
        "attach" -> {
            ai.preview(
                AiSession(
                    id = sessionId, title = "Pictures", connection = "anthropic-demo",
                    turns = listOf(ChatTurn.user("Hi."), ChatTurn.assistant("Hello — what are we building today?")),
                    createdAt = now, updatedAt = now,
                ),
            )
            ai.draft = "Is this list any good against Snake-Eye?"
            shot?.let { bytes ->
                kotlinx.coroutines.runBlocking { Attachments.prepare(PickedFile("screenshot.png", bytes)) }
                    ?.let { a -> ai.previewAttached(listOf(a, a)) }
            }
        }
        "picture" -> {
            val image = shot?.let { ai.files.putImage(sessionId, it, "image/png", 1920, 1080) }
            val call = Part.ToolResult("t1", "resolve_cards", "{}", summary = "Read ${main.size} cards off the picture, 1 to check")
            ai.preview(
                AiSession(
                    id = sessionId, title = "A picture", connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("What deck is this? Build it for me.", images = listOfNotNull(image)),
                        ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(call)),
                        ChatTurn.assistant(
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
                AiSession(
                    id = sessionId, title = "Lines", connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("Show me your best line, the board it ends on, and what you'd change."),
                        ChatTurn.assistant(
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

/**
 * A turn's worth of moves on both sides of the studio's duel, through the same verbs a right-click
 * runs: the first cards of each hand played as they would be by default, an Extra Deck monster with a
 * material under it, a mill, a chain link with a target, life points paid, a ping.
 */
private fun studioDuelMoves(h: com.kaiharimoto.neue.NeueHolders) {
    val d = h.duel
    fun state() = d.game!!.state
    d.act(DuelAction.Phase(DuelPhase.MAIN1), 0)
    for (seat in 0..1) {
        if (state().solo && seat == 1) break
        d.bottom = seat
        state().seats[seat].hand.take(3).forEach { uid -> d.verb(uid, DuelVerb.DEFAULT) }
        state().seats[seat].extra.firstOrNull()?.let { x ->
            if (d.verb(x, DuelVerb.SUMMON)) {
                state().seats[seat].hand.firstOrNull()?.let { m -> d.verb(m, DuelVerb.ATTACH, host = x) }
            }
        }
        d.act(state().seats[seat].deck.take(2).map { DuelAction.Move(it, Place.Pile(seat, PileKind.GY), how = "send") }, seat)
    }
    d.bottom = 0
    val theirs = state().onField().firstOrNull { state().cards[it]?.controller == 1 }
    val mine = state().onField().firstOrNull { state().cards[it]?.controller == 0 }
    if (theirs != null && mine != null) {
        d.act(DuelAction.ChainAdd(1, theirs, targets = listOf(mine)), 1)
        d.act(DuelAction.Ping(0, DuelAction.PING_WAIT, uid = theirs), 0)
    }
    d.act(DuelAction.Lp(1, delta = -1500), 1)
    d.act(DuelAction.Thinking(1, true), 1)
    d.act(DuelAction.Chat(0, "Ash on that?"), 0)
    // 1.0.79: a lock written down, a token with stats, and the other seat asking to move on.
    d.run("lock Synchro Monsters only from the Extra Deck")
    d.run("token sheep atk 0 def 0 def")
    d.bottom = 1
    d.run("bp")
    d.bottom = 0
}

/**
 * The opening roll photographed (1.0.87, `--duel-dice`): real throws through the real holder, the frame clock run by
 * hand to the moment asked for. The throws are fixed drags, so every run takes the same picture.
 */
private suspend fun studioDice(h: NeueHolders, how: String, clock: FrameClock) {
    val d = h.duel
    d.bottom = 0
    val layout = d.tableLayout ?: return
    val stage = DiceStage(layout)
    val held = RESTING
    val near = DiceThrow.fromDrag(
        V3(6.5, 7.0), held, V3(13.0, -21.0), 3.0,
    )
    val far = DiceThrow.fromDrag(
        V3(13.0, 7.0), held, V3(-9.0, -19.0), -2.0,
    )
    when (how) {
        "held" -> {
            // Over the near field, a little turned in the hand.
            val at = stage.toTable(0, V3(9.0, 5.5, 0.0))
            val turn = Quat(0.93, 0.25, 0.2, 0.18).normalized()
            d.diceCarry = DiceCarry(0, at.x.toFloat(), at.y.toFloat(), held.map { (turn * it).normalized() })
            clock.run(4)
        }
        "flying" -> {
            d.throwDice(1, far)
            clock.run(240)
            d.throwDice(0, near)
            // A tenth of a second and a little: in the air, tumbling, before the first bounce.
            clock.run(diceFrames ?: 7)
        }
        "settled" -> {
            d.throwDice(0, near)
            clock.run(240)
        }
        "choose" -> {
            // The near seat first, as the seed was chosen for: it wins and chooses.
            d.throwDice(0, near)
            clock.run(30)
            d.throwDice(1, far)
            clock.run(260)
        }
        else -> clock.run(2)
    }
    println("[neue-studio] dice: $how · ${d.game?.state?.opening}")
}

/** The table's die and coin photographed (1.0.96, `--duel-chance`): fixed throws through the real holder. */
private suspend fun studioChance(h: NeueHolders, how: String, clock: FrameClock) {
    val d = h.duel
    d.bottom = 0
    val layout = d.tableLayout ?: return
    val stage = DiceStage(layout)
    when (how) {
        "held" -> {
            val at = stage.toTable(0, V3(6.0, 5.0, 0.0))
            d.chanceCarry = com.kaiharimoto.neue.duel.dice.ChanceCarry(0, false, at.x.toFloat(), at.y.toFloat(), Quat(0.93, 0.25, 0.2, 0.18).normalized())
            clock.run(4)
        }
        "flying" -> {
            val toss = Toss.coin(V3(5.0, 6.5), Quat.IDENTITY, V3(8.0, -14.0), 2.0)
            d.throwChance(0, coin = true, toss = toss)
            clock.run(diceFrames ?: 14)
            println("[neue-studio] chance in the air: ${d.chanceRolling}; run ${com.kaiharimoto.mastertool.core.duel.dice.TossRuns.of(com.kaiharimoto.mastertool.core.duel.dice.DiceSim.Shape.COIN, toss).duration}s; stamped ${d.game?.state?.chance?.firstOrNull()?.toss == toss}")
        }
        else -> {
            d.throwChance(0, coin = false, toss = Toss.die(V3(5.0, 6.5), DIE_HOME, V3(12.0, -16.0), 3.0))
            d.throwChance(0, coin = true, toss = Toss.coin(V3(4.0, 6.5), Quat.IDENTITY, V3(10.0, -12.0), -2.0))
            d.throwChance(1, coin = false)
            d.throwChance(1, coin = true)
            clock.run(420)
        }
    }
    println("[neue-studio] chance: $how · ${d.game?.state?.chance?.map { "${it.seat}:${if (it.coin) (if (it.heads) "heads" else "tails") else it.value}" }}")
}

/** `--duel-dice-frames=N`: how many frames into the near seat's throw a flying shot is taken. */
private var diceFrames: Int? = null

