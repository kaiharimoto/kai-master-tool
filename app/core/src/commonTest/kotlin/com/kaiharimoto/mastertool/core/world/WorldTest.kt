package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.book.EngineLayout
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStatsTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, was $actual")

    @Test
    fun summaries() {
        val xs = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        near(5.0, WorldStats.mean(xs))
        near(sqrt(32.0 / 7), WorldStats.sd(xs))
        near(4.5, WorldStats.median(xs))
        near(2.0, WorldStats.quantile(xs, 0.0))
        near(9.0, WorldStats.quantile(xs, 1.0))
    }

    @Test
    fun aHistogramCountsEveryValueOnce() {
        val (edges, counts) = WorldStats.histogram(listOf(0.0, 0.5, 1.0, 9.9, 10.0), 10)
        assertEquals(10, edges.size)
        assertEquals(5, counts.sum())
        assertEquals(2, counts.last()) // 9.9 and the closed top, 10
    }

    @Test
    fun wilsonMatchesThePublishedInterval() {
        // 81 of 263 at 95 %: 0.2553 to 0.3662 (Wilson 1927, as Newcombe 1998 tabulates).
        val (lo, hi) = WorldStats.wilson(81, 263)
        near(0.2553, lo, 1e-3)
        near(0.3662, hi, 1e-3)
    }

    @Test
    fun binomialAndNormalTails() {
        near(0.5, WorldStats.binomCdf(10, 4, 0.5) + WorldStats.binomPmf(10, 5, 0.5) / 2, 1e-9)
        near(0.975002, WorldStats.normalCdf(1.96), 1e-5)
        near(0.5, WorldStats.normalCdf(0.0), 1e-7)
    }

    @Test
    fun chiSquareOfAFairDie() {
        // 60 rolls; the statistic is 3.4 on 5 degrees of freedom, p ≈ 0.639.
        val c = WorldStats.chiSquare(listOf(8.0, 9.0, 14.0, 7.0, 12.0, 10.0), List(6) { 10.0 })
        near(3.4, c.statistic, 1e-9)
        assertEquals(5, c.df)
        near(0.6386, c.p, 1e-3)
    }

    @Test
    fun theIncompleteGammaAgreesWithTheExponential() {
        // P(1, x) = 1 − e^−x.
        listOf(0.1, 1.0, 3.0, 10.0).forEach { x -> near(1 - kotlin.math.exp(-x), WorldStats.gammaP(1.0, x), 1e-9) }
    }
}

class ShowSpecTest {
    @Test
    fun aBarChartIsTheChatsChart() {
        val (kind, payload) = ShowSpec.parse("chart", """{"type":"bar","labels":["a","b"],"series":[{"name":"s","values":[1,2]}]}""").getOrThrow()
        assertEquals(BoardKind.CHART, kind)
        val c = WorldChart.parse(payload).getOrThrow() as WorldChart.Bars
        assertEquals(listOf("a", "b"), c.chart.labels)
    }

    @Test
    fun aHistogramIsBinnedIntoBars() {
        val c = WorldChart.parse("""{"type":"histogram","values":[1,2,2,3,3,3],"bins":3}""").getOrThrow() as WorldChart.Bars
        assertEquals(listOf(1.0, 2.0, 3.0), c.chart.series.single().values)
    }

    @Test
    fun aScatterAndAHeatmapRead() {
        val s = WorldChart.parse("""{"type":"scatter","points":[[1,2],{"x":3,"y":4,"label":"c"}]}""").getOrThrow() as WorldChart.Scatter
        assertEquals(2, s.series.single().points.size)
        val h = WorldChart.parse("""{"type":"heatmap","rows":["a","b"],"cols":["x"],"values":[[1],[2]]}""").getOrThrow() as WorldChart.Heatmap
        assertEquals(2.0, h.max)
        assertTrue(WorldChart.parse("""{"type":"heatmap","rows":["a"],"cols":["x","y"],"values":[[1]]}""").isFailure)
    }

    @Test
    fun aGraphTakesItsNodesFromItsEdges() {
        val g = WorldGraph.parse("""{"edges":[["Ash","Maxx","beats"],{"from":"Maxx","to":"Nibiru"}]}""").getOrThrow()
        assertEquals(listOf("Ash", "Maxx", "Nibiru"), g.nodes.map { it.id })
        assertEquals("beats", g.edges.first().label)
        val again = WorldGraph.parse(WorldGraph.encode(g)).getOrThrow()
        assertEquals(g, again)
    }

    @Test
    fun aTableOfObjectsNamesItsColumns() {
        val t = WorldTable.parse("""{"rows":[{"card":"Ash","copies":3},{"card":"Droll","odds":"0.4"}]}""").getOrThrow()
        assertEquals(listOf("card", "copies", "odds"), t.columns)
        assertEquals(listOf("Droll", "", "0.4"), t.rows[1])
    }

    @Test
    fun whatWillNotReadSaysWhy() {
        val e = ShowSpec.parse("sculpture", "x").exceptionOrNull()
        assertNotNull(e)
        assertTrue("unknown kind" in e.message.orEmpty())
        assertTrue(ShowSpec.parse("table", "not json").isFailure)
        assertTrue(ShowSpec.parse("image", "../../secrets.png").isFailure)
        assertEquals("out/plot.png", ShowSpec.parse("image", "plot.png").getOrThrow().second)
    }
}

class GraphLayoutTest {
    @Test
    fun theSameWebIsDrawnTheSameWay() {
        val nodes = (1..12).map { "n$it" }
        val edges = nodes.zipWithNext()
        assertEquals(GraphLayout.force(nodes, edges), GraphLayout.force(nodes, edges))
    }

    @Test
    fun everyNodeIsOnThePageAndLinkedNodesSitCloser() {
        val nodes = (1..20).map { "n$it" }
        // Two tight clusters joined by one edge.
        val edges = (1..9).map { "n$it" to "n${it + 1}" } + (11..19).map { "n$it" to "n${it + 1}" } + ("n10" to "n11")
        val pos = GraphLayout.force(nodes, edges)
        assertTrue(pos.values.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 && it.x.isFinite() && it.y.isFinite() })
        fun d(a: String, b: String) = pos.getValue(a).let { p -> pos.getValue(b).let { q -> sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y)) } }
        val linked = edges.map { (a, b) -> d(a, b) }.average()
        val apart = d("n1", "n20")
        assertTrue(linked < apart, "linked $linked, apart $apart")
    }

    @Test
    fun loneNodesAndEmptyWebs() {
        assertTrue(GraphLayout.force(emptyList(), emptyList()).isEmpty())
        assertEquals(GraphLayout.Pos(0.5, 0.5), GraphLayout.force(listOf("a"), emptyList()).getValue("a"))
        // Two on one spot, no edges: still parted.
        val p = GraphLayout.force(listOf("a", "b"), emptyList())
        assertTrue(p.getValue("a") != p.getValue("b"))
    }
}

class EngineLayoutHubsTest {
    @Test
    fun theHubIsTheCardEveryRouteRunsThrough() {
        val e = listOf(
            ReaderGuide.Edge("A", "H"), ReaderGuide.Edge("B", "H"), ReaderGuide.Edge("H", "X"), ReaderGuide.Edge("H", "Y"),
        )
        assertEquals(setOf("H"), EngineLayout.of(e).hubs)
    }

    @Test
    fun aDenseWebIsLaidOutQuickly() {
        // Ten rows of eight, every card leading to every card of the next row: 8^10 routes, counted, not walked.
        val rows = (0 until 10).map { r -> (0 until 8).map { "r${r}c$it" } }
        val edges = rows.zipWithNext().flatMap { (a, b) -> a.flatMap { x -> b.map { y -> ReaderGuide.Edge(x, y) } } }
        val layout = EngineLayout.of(edges)
        assertEquals(10, layout.rows.size)
    }
}

class WorldCodecTest {
    @Test
    fun aWorldRoundTripsAndABadBoardIsDroppedAlone() {
        val w = World("w1", "Odds", "deck:abc", boards = listOf(Board("b1", "Hands", BoardKind.STAT.id, """{"value":"63%","label":"x"}""")))
        assertEquals(w, WorldCodec.decode(WorldCodec.encode(w)))
        val broken = """{"id":"w2","title":"T","boards":[{"id":"ok","kind":"chart"},{"id":7,"kind":{"no":1}}],"future":true}"""
        val read = assertNotNull(WorldCodec.decode(broken))
        assertEquals(listOf("ok"), read.boards.map { it.id })
        assertNull(WorldCodec.decode("not a world"))
    }

    @Test
    fun theLogSkipsALineThatWillNotRead() {
        val a = WorldEvent(1, WorldEvent.Kind.WRITE, path = "sim.js", text = "Wrote sim.js")
        val b = WorldEvent(2, WorldEvent.Kind.RUN, run = RunRecord(out = "ok", boards = listOf("b1")))
        val text = WorldCodec.line(a) + "\n{broken\n" + WorldCodec.line(b) + "\n"
        assertEquals(listOf(a, b), WorldCodec.events(text))
    }

    @Test
    fun pathsStayInsideTheWorld() {
        assertEquals("sim/odds.js", WorldPaths.safe("./sim/odds.js"))
        assertEquals("a/b.py", WorldPaths.safe("a\\b.py"))
        listOf("../x.js", "a/../../x", "C:/x.js", "/etc/passwd", ".hidden", "a/.git/x", "", "a b.js", "x".repeat(200))
            .forEach { assertNull(WorldPaths.safe(it), it) }
        assertEquals("js", WorldPaths.lang("a.js"))
        assertEquals("py", WorldPaths.lang("a.py"))
        assertNull(WorldPaths.lang("notes.md"))
    }

    @Test
    fun newBoardsFindAFreeSlot() {
        val first = WorldCanvas.free(emptyList())
        assertEquals(0.0 to 0.0, first)
        val b = Board("a", x = 0.0, y = 0.0)
        assertEquals(WorldCanvas.slot(1), WorldCanvas.free(listOf(b)))
    }
}
