package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.RunRecord
import com.kaiharimoto.mastertool.core.world.ShowSpec
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.WorldCanvas
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.world.TermLine
import com.kaiharimoto.neue.world.WorldPane
import kotlin.random.Random

/**
 * `--world=demo` (1.0.95): a world seeded straight onto Ai World's page — files, a run's lines, the activity, a short
 * conversation in Thoughts and one board of every kind — so the page can be photographed without a model.
 * `--world-pane=boards|editor|…` gives that pane the page; `--world-ai=editor|…` puts Ai in that pane (editor by default).
 */
internal fun studioWorld(h: NeueHolders, map: Map<String, String>) {
    val now = System.currentTimeMillis()
    val index = h.builder.index
    // The builder's cards, by name, so the boards that draw art draw real cards.
    val names = h.builder.deck.main.mapNotNull { index.byId(it)?.name }.distinct().ifEmpty {
        listOf("Ash Blossom & Joyous Spring", "Maxx \"C\"", "Infinite Impermanence", "Called by the Grave", "Triple Tactics Talent", "Pot of Prosperity")
    }
    fun n(i: Int) = names[i % names.size]
    fun q(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    val specs = listOf(
        Triple("stat", "Opens a starter", """{"value":"63.4%","label":"Opens a starter","detail":"Of 100,000 seeded five-card hands, going first. 1 in 8 opens two."}"""),
        Triple("chart", "Starters seen", """{"type":"hbar","title":"","labels":["${q(n(0))}","${q(n(1))}","${q(n(2))}","${q(n(3))}","${q(n(4))}"],"series":[{"name":"in hand","values":[38.1,33.2,29.8,21.4,12.9]},{"name":"live","values":[31.0,30.1,22.6,18.9,10.2]}],"unit":"%"}"""),
        Triple("chart", "Hand strength", run {
            val r = Random(11)
            val a = (0 until 70).joinToString(",") { val x = r.nextDouble() * 10; "[${"%.2f".format(x)},${"%.2f".format(x * 0.7 + r.nextDouble() * 3)}]" }
            val b = (0 until 40).joinToString(",") { val x = r.nextDouble() * 10; "[${"%.2f".format(x)},${"%.2f".format(2 + r.nextDouble() * 4)}]" }
            """{"type":"scatter","title":"","x":"starters","y":"end board","series":[{"name":"going first","points":[$a]},{"name":"going second","points":[$b]}]}"""
        }),
        Triple("chart", "Matchups", """{"type":"heatmap","title":"","rows":["Us","Maliss","Mitsurugi","Ryzeal","Yummy"],"cols":["Us","Maliss","Mitsurugi","Ryzeal","Yummy"],"values":[[50,46,58,61,55],[54,50,52,48,60],[42,48,50,57,51],[39,52,43,50,47],[45,40,49,53,50]],"unit":"%"}"""),
        Triple("graph", "Who finds whom", """{"nodes":[{"id":"a","label":"${q(n(0))}","group":"Starter","card":"true"},{"id":"b","label":"${q(n(1))}","group":"Starter","card":"true"},{"id":"c","label":"${q(n(2))}","group":"Extender","card":"true"},{"id":"d","label":"${q(n(3))}","group":"Extender","card":"true"},{"id":"e","label":"${q(n(4))}","group":"Payoff","card":"true"},{"id":"f","label":"Link 2","group":"Payoff"},{"id":"g","label":"End board","group":"Payoff"}],"edges":[["a","c","searches"],["b","c","summons"],["c","d","adds"],["a","f","links"],["d","f","links"],["f","e","revives"],["e","g","makes"],["b","d","sends"]]}"""),
        Triple("flow", "The main line", """{"nodes":[{"id":"Normal ${q(n(0))}","group":"Starter"},{"id":"Search ${q(n(2))}","group":"Extender"},{"id":"Special ${q(n(3))}","group":"Extender"},{"id":"Link 2","group":"Payoff"},{"id":"Revive ${q(n(4))}","group":"Payoff"},{"id":"Rank 4","group":"Payoff"},{"id":"End: 2 interruptions","group":"Payoff"}],"edges":[["Normal ${q(n(0))}","Search ${q(n(2))}","effect"],["Normal ${q(n(0))}","Special ${q(n(3))}","material"],["Search ${q(n(2))}","Link 2"],["Special ${q(n(3))}","Link 2"],["Link 2","Revive ${q(n(4))}"],["Revive ${q(n(4))}","Rank 4"],["Rank 4","End: 2 interruptions"],["Link 2","End: 2 interruptions","negate"]]}"""),
        Triple("table", "Every card", run {
            val rows = (0 until 14).joinToString(",") { i ->
                """["${q(n(i))}",${3 - i % 3},"${"%.1f".format(40.0 - i * 2.3)}%","${listOf("Starter", "Extender", "Hand trap", "Brick")[i % 4]}","${"%.2f".format(1.0 - i * 0.05)}"]"""
            }
            """{"columns":["Card","Copies","Opens","Role","Value"],"rows":[$rows]}"""
        }),
        Triple("cards", "The core", "Starters: 3 ${n(0)}, 3 ${n(1)}\nExtenders: 2 ${n(2)}, 1 ${n(3)}\nPayoff: 1 ${n(4)}"),
        Triple("markdown", "What I found", "## Openings\n\nThe deck opens a starter in **63 %** of hands, but only **41 %** of those survive one hand trap.\n\n- [[${n(0)}]] is the best single card: it starts every line.\n- Two copies of [[${n(5)}]] would lift the survival rate by about 4 points.\n\n| Change | Opens | Survives |\n| --- | ---: | ---: |\n| As is | 63% | 41% |\n| +1 ${n(5)} | 66% | 45% |"),
    )
    var boards = emptyList<Board>()
    specs.forEachIndexed { i, (kind, title, body) ->
        val (k, payload) = ShowSpec.parse(kind, body).getOrElse { throw IllegalStateException("studio board $title: ${it.message}") }
        val (x, y) = WorldCanvas.slot(i)
        val note = when (k.name) {
            "STAT" -> "Seeded so it can be run again and agree."
            "GRAPH" -> "Arrows are what a card does to reach the next."
            else -> ""
        }
        boards = boards + Board("b${i + 1}", title, k, payload, x, y, source = "openings.js", updated = now, note = note)
    }
    val w = World(id = "wstudio", title = "${h.builder.deckName} openings", scope = h.builder.deckId?.let { World.SCOPE_DECK + it }, created = now, updated = now, boards = boards, open = "openings.js")
    val script = """
// How often does the deck open a starter? 100,000 seeded hands, going first.
const deck = world.deck();
const starters = new Set(deck.groups.Starters ?? []);
const rng = world.random(7);

let opens = 0, two = 0;
for (let i = 0; i < 100000; i++) {
  const hand = rng.sample(deck.main, 5);
  const n = hand.filter(c => starters.has(c.name)).length;
  if (n > 0) opens++;
  if (n > 1) two++;
}

print(`opens: ${'$'}{(opens / 1000).toFixed(1)}%`);
print(`two or more: ${'$'}{(two / 1000).toFixed(1)}%`);
show("stat", { value: (opens / 1000).toFixed(1) + "%", label: "Opens a starter" });
""".trimStart()
    val files = mapOf(
        "README.md" to "# ${w.title}\n\nHow often the deck opens, and what it opens into.\n",
        "openings.js" to script,
        "engine.py" to "from world import deck, show\n\nedges = []\nfor card in deck()['main']:\n    pass\n",
        "lib/hands.js" to "export function sample(rng, list, n) {\n  return rng.sample(list, n);\n}\n",
        "notes/plan.md" to "1. Count starters\n2. Map the engine\n3. Compare the flex slots\n",
    )
    val lines = listOf(
        TermLine(TermLine.Kind.COMMAND, "js openings.js"),
        TermLine(TermLine.Kind.OUT, "opens: 63.4%"),
        TermLine(TermLine.Kind.OUT, "two or more: 12.6%"),
        TermLine(TermLine.Kind.NOTE, "— done in 412 ms, 1 board(s)"),
        TermLine(TermLine.Kind.COMMAND, "python engine.py"),
        TermLine(TermLine.Kind.ERR, "NameError: name 'searches' is not defined (line 4)"),
        TermLine(TermLine.Kind.COMMAND, "instrument engine_map {\"deck\":\"open\"}"),
        TermLine(TermLine.Kind.OUT, "18 cards, 31 edges, hubs: ${n(2)}"),
        TermLine(TermLine.Kind.NOTE, "— done in 96 ms, 2 board(s)"),
    )
    val t = now - 60_000
    val events = listOf(
        WorldEvent(t, WorldEvent.Kind.NEW, WorldEvent.AI, text = "Made “${w.title}”"),
        WorldEvent(t + 4_000, WorldEvent.Kind.WRITE, WorldEvent.AI, path = "openings.js", text = "Wrote openings.js (19 lines)"),
        WorldEvent(t + 9_000, WorldEvent.Kind.RUN, WorldEvent.AI, path = "openings.js", text = "Ran openings.js", run = RunRecord("js", "openings.js", ok = true, ms = 412)),
        WorldEvent(t + 9_500, WorldEvent.Kind.SHOW, WorldEvent.AI, board = "b1", text = "Pinned “Opens a starter”"),
        WorldEvent(t + 20_000, WorldEvent.Kind.WRITE, WorldEvent.AI, path = "engine.py", text = "Wrote engine.py (5 lines)"),
        WorldEvent(t + 24_000, WorldEvent.Kind.RUN, WorldEvent.AI, path = "engine.py", text = "Failed engine.py", run = RunRecord("py", "engine.py", ok = false, err = "NameError")),
        WorldEvent(t + 30_000, WorldEvent.Kind.SHOW, WorldEvent.AI, board = "b5", text = "Pinned “Who finds whom”"),
        WorldEvent(t + 41_000, WorldEvent.Kind.WRITE, WorldEvent.YOU, path = "notes/plan.md", text = "Wrote notes/plan.md (3 lines)"),
        WorldEvent(t + 50_000, WorldEvent.Kind.SHOW, WorldEvent.AI, board = "b9", text = "Pinned “What I found”"),
    )
    h.world.seed(w, files, lines, events, "openings.js")
    h.world.selectedBoard = "b5"
    h.world.aiPane = (map["world-ai"] ?: "editor").let { a -> WorldPane.entries.firstOrNull { it.name.equals(a, ignoreCase = true) } }
    map["world-pane"]?.let { p -> WorldPane.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } }?.let {
        h.world.maximized = it
        h.world.focus = it
    }
    fun result(name: String, summary: String) = Part.ToolResult("t-$name", name, "{}", summary = summary)
    h.ai.preview(
        AiSession(
            id = "studio-world",
            title = "Openings",
            connection = "anthropic-demo",
            turns = listOf(
                ChatTurn.user("Study my deck's openings in Ai World."),
                ChatTurn(
                    Role.ASSISTANT,
                    listOf(
                        Part.Reasoning("The question is how often a hand has a starter, and how often that starter survives a hand trap. A seeded simulation answers the first; the engine map answers which cards the second depends on."),
                        Part.Text("I'll count first, then map who finds whom."),
                    ),
                ),
                ChatTurn(Role.USER, listOf(result("world_write", "Wrote openings.js"), result("world_run", "Ran openings.js: opens 63.4%"))),
                ChatTurn(
                    Role.ASSISTANT,
                    listOf(
                        Part.Reasoning("63% is fine but the two-starter rate is low. The engine map should show whether ${n(2)} is the hub."),
                        Part.Text("**63 %** of hands open a starter. ${n(2)} is the hub of the engine — every line passes through it."),
                    ),
                ),
            ),
            createdAt = now,
            updatedAt = now,
        ),
    )
    println("[neue-studio] world: ${w.boards.size} boards, ${h.world.files.size} files, ${h.world.activity.size} events")
}
