package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AvatarStatus
import com.kaiharimoto.mastertool.core.world.desk.AvatarTargets
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.DeskPoint
import com.kaiharimoto.mastertool.core.world.desk.Edge
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.mastertool.core.world.desk.WorldNotices
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.world.desk.WorldDeskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Ai World's desktop, photographed (`docs/world/DESKTOP.md` §12.4): `--world-desk=` sets the scene on a seeded world —
 *
 * - `fresh`: nothing open, the plate (with `--world=fresh`, the empty world's);
 * - `several`: Files, the Terminal and the Browser opened by the person, the Editor in front;
 * - `working`: Ai at work — a run in the Terminal, then a file typed into the Editor; the Terminal recedes. With
 *   `--world-avatar=icon|travel|caret|terminal|home` the avatar is frozen on its way (`--world-avatar-t=0.5` of a hop
 *   from home to the Editor's icon by default), as the frame clock is advanced by hand. With
 *   `--world-status=writing|running|instrument|reading|thinking|pleased|worried|waiting` the avatar stands where that
 *   work is and wears its status (§5.7): its plate, its face, its sign, and the waiting ring;
 * - `launcher`, `notices`, `switcher`: the launcher open, a toast and the tray's count, the strip of windows.
 */
internal suspend fun studioDesk(h: NeueHolders, scene: String, map: Map<String, String>, clock: FrameClock) {
    val world = h.world
    val desk = world.desk
    val now = WorldDeskState.now()
    fun open(app: BuiltInApp) = desk.apply(DeskOp.Open(app.ref, WorldDeskState.now(), WorldEvent.YOU))

    /** [app]'s window moved and sized as a hand would, through the reducer: [x], [y], [w], [hh] as fractions of the desktop. */
    fun place(app: BuiltInApp, x: Double, y: Double, w: Double, hh: Double) {
        val full = desk.area.full
        val r = desk.desk.window(app.id)?.rect(desk.area) ?: return
        val to = DeskPoint(full.x + x * full.w, full.y + y * full.h)
        desk.apply(DeskOp.DragStart(app.id, DeskPoint(r.x + 60, r.y + 16), WorldDeskState.now()))
        desk.apply(DeskOp.Drag(app.id, to.x - r.x, to.y - r.y))
        desk.apply(DeskOp.DragEnd(app.id, DeskPoint(to.x + 60, to.y + 16)))
        val now = desk.desk.window(app.id)?.rect(desk.area) ?: return
        desk.apply(DeskOp.Resize(app.id, Edge.BOTTOM_RIGHT, w * full.w - now.w, hh * full.h - now.h))
    }
    when (scene) {
        "fresh" -> Unit
        "several", "launcher", "switcher", "notices" -> {
            open(BuiltInApp.FILES)
            place(BuiltInApp.FILES, 0.09, 0.03, 0.2, 0.6)
            open(BuiltInApp.THOUGHTS)
            place(BuiltInApp.THOUGHTS, 0.7, 0.04, 0.28, 0.88)
            open(BuiltInApp.TERMINAL)
            place(BuiltInApp.TERMINAL, 0.3, 0.6, 0.5, 0.36)
            open(BuiltInApp.EDITOR)
            place(BuiltInApp.EDITOR, 0.16, 0.06, 0.5, 0.62)
            clock.run(30)
            when (scene) {
                "launcher" -> desk.launcherOpen = true
                "switcher" -> {
                    desk.cycle(forward = true, held = true)
                    clock.run(4)
                    Thread.sleep(WorldDeskState.STRIP_AFTER_MS + 50)
                }
                "notices" -> {
                    desk.notify(WorldNotices.runFinished("openings.js", 1_240, 3, now, terminalInFront = false))
                    desk.notify(WorldNotices.newPages(2, browserInFront = false))
                }
            }
            clock.run(40)
        }
        "working" -> {
            // Ai's turn: it ran a study in the Terminal, then writes the next file. The prefs make the typing slow
            // enough to be caught midway.
            h.neue.update { it.copy(world = it.world.copy(typing = 90, follow = true)) }
            desk.arriveNow(BuiltInApp.TERMINAL.ref, AiDoes.Run("hands.js"))
            clock.run(20)
            place(BuiltInApp.TERMINAL, 0.6, 0.6, 0.38, 0.34)
            clock.run(40)
            val code = OPENINGS
            CoroutineScope(Dispatchers.Swing).launch { world.write("openings.js", code, WorldEvent.AI) }
            clock.run(90)
            val avatar = desk.avatar
            val home = avatar.targets.rect(null, Anchor.HOME)?.center ?: DeskPoint(1200.0, 820.0)
            val t = map["world-avatar-t"]?.toDoubleOrNull() ?: 0.3
            fun stand(app: String, anchor: Anchor) = avatar.targets.rect(app, anchor)?.let { AvatarTargets.stand(it, anchor, 28.0) }
            val editorIcon = stand(BuiltInApp.EDITOR.id, Anchor.ICON) ?: home
            val writing = AvatarStatus.of(AiDoes.Write("openings.js"))
            val running = AvatarStatus.of(AiDoes.Run("hands.js"))
            // A status posed (below) leaves the avatar to its own loop until it stands it somewhere.
            when (map["world-avatar"] ?: if (map["world-status"] != null) "live" else "travel") {
                "icon" -> avatar.pose(editorIcon, editorIcon, 1.0, "Writing openings.js", BuiltInApp.EDITOR.id, writing)
                "travel" -> avatar.pose(home, editorIcon, t, "Writing openings.js", BuiltInApp.EDITOR.id, writing)
                // At the caret the avatar is left to its own loop, riding the line as it types.
                "caret" -> clock.run(150)
                "terminal" -> stand(BuiltInApp.TERMINAL.id, Anchor.LINE)?.let { avatar.pose(it, it, 1.0, "Running hands.js", BuiltInApp.TERMINAL.id, running) }
                "home", "live" -> Unit
            }
            // --world-status=…: the avatar's status (§5.7) posed where that work is, through the real state — a run's end
            // told to the avatar, a question set on Ai, a turn with no tool running — not drawn by hand.
            map["world-status"]?.let { posed ->
                clock.run(150)
                val caret = stand(BuiltInApp.EDITOR.id, Anchor.CARET) ?: editorIcon
                val line = stand(BuiltInApp.TERMINAL.id, Anchor.LINE) ?: stand(BuiltInApp.TERMINAL.id, Anchor.TITLE) ?: caret
                when (posed) {
                    // Writing is left to its own loop, riding the caret as the code types in.
                    "writing" -> clock.run(120)
                    "running" -> avatar.pose(line, line, 1.0, "Running hands.js", BuiltInApp.TERMINAL.id, running)
                    "instrument" -> avatar.pose(line, line, 1.0, "Running openings", BuiltInApp.TERMINAL.id, AvatarStatus.of(AiDoes.Tool("openings", AvatarStatus.instrument("openings", null))))
                    "pleased" -> {
                        // Stamped a little ahead: the headless frames take longer than the 2.5 s it is shown for.
                        desk.ran("hands.js", ok = true, error = "", ms = 1_240, pages = 1, at = WorldDeskState.now() + 60_000)
                        avatar.pose(line, line, 1.0, "Running hands.js", BuiltInApp.TERMINAL.id, running)
                    }
                    "worried" -> {
                        desk.ran("hands.js", ok = false, error = "ReferenceError: starters is not defined (hands.js#12)", ms = 80, pages = 0, at = WorldDeskState.now())
                        avatar.pose(line, line, 1.0, "Running hands.js", BuiltInApp.TERMINAL.id, running)
                    }
                    "thinking" -> {
                        h.ai.pretendWorking("Thinking")
                        val title = stand(BuiltInApp.EDITOR.id, Anchor.TITLE) ?: caret
                        avatar.pose(title, title, 1.0, "Writing openings.js", BuiltInApp.EDITOR.id, writing)
                    }
                    "reading" -> {
                        desk.arriveNow(BuiltInApp.LIBRARY.ref, AiDoes.Read(BuiltInApp.LIBRARY.ref, "Labrynth guide"))
                        clock.run(90)
                        val body = stand(BuiltInApp.LIBRARY.id, Anchor.BODY) ?: stand(BuiltInApp.LIBRARY.id, Anchor.TITLE) ?: caret
                        avatar.pose(body, body, 1.0, "Reading Labrynth guide", BuiltInApp.LIBRARY.id, AvatarStatus.of(AiDoes.Read(BuiltInApp.LIBRARY.ref, "Labrynth guide")))
                    }
                    "waiting" -> {
                        desk.open(BuiltInApp.THOUGHTS.ref)
                        place(BuiltInApp.THOUGHTS, 0.62, 0.04, 0.34, 0.8)
                        clock.run(60)
                        h.ai.pretendAsking("Keep the third Ash Blossom, or cut it for a Droll?", listOf("Keep it", "Cut it"))
                        clock.run(30)
                        val at = stand(BuiltInApp.THOUGHTS.id, Anchor.COMPOSER) ?: stand(BuiltInApp.THOUGHTS.id, Anchor.TITLE) ?: caret
                        avatar.pose(at, at, 1.0, "Waiting on you", BuiltInApp.THOUGHTS.id, AvatarStatus.WAITING)
                    }
                }
                // The pulse at its widest, so the picture shows the ring breathed out.
                if (posed == "waiting") Thread.sleep(AvatarStatus.BREATH_MS / 2)
            }
            clock.run(6)
            println("[neue-studio] caret: ${avatar.targets.rect(BuiltInApp.EDITOR.id, Anchor.CARET)} body ${avatar.targets.rect(BuiltInApp.EDITOR.id, Anchor.BODY)} typed ${world.editorText.length} typing ${world.typing} density ${desk.density} origin ${desk.origin}")
            println("[neue-studio] avatar: ${avatar.describe()}")
            println("[neue-studio] avatar: at ${avatar.at} asleep ${avatar.asleep} home $home icon $editorIcon caret ${stand(BuiltInApp.EDITOR.id, Anchor.CARET)}")
        }
    }
    println("[neue-studio] desk: ${desk.desk.windows.size} windows, front ${desk.desk.front}, ai ${desk.desk.ai}, avatar ${desk.avatar.status}")
}

/** The file Ai types in the `working` scene: the mockup's openings.js. */
private val OPENINGS = """
// How often does the deck open a starter? Exact, then checked.
var deck = ygo.deck();
var starters = deck.groups.Starters || [];
var N = deck.main.length, K = starters.length;

var first = ygo.atLeast(N, K, 5, 1);
var second = ygo.atLeast(N, K, 6, 1);
print('going first:  ' + (first * 100).toFixed(1) + '%');
print('going second: ' + (second * 100).toFixed(1) + '%');

var r = ygo.rng(7), hits = 0;
for (var i = 0; i < 100000; i++) {
  var hand = ygo.hand(deck.main, r, 5);
  if (hand.some(function (c) { return starters.indexOf(c) >= 0; })) hits++;
}
print('simulated: ' + (hits / 1000).toFixed(1) + '%');
ygo.show.stat({ value: (first * 100).toFixed(1) + '%', label: 'Opens a starter' });
""".trimStart()
