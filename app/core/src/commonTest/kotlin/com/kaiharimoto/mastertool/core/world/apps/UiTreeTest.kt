package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.BoardKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiTreeTest {
    private fun one(json: String): UiNode = UiTree.parse(json).root

    @Test
    fun everyWidgetReads() {
        val tree = UiTree.parse(
            """
            {"ui":"col","gap":4,"children":[
              {"ui":"row","children":[{"ui":"text","text":"a","tone":"muted","mono":true,"weight":2},{"ui":"space","size":3}]},
              {"ui":"grid","columns":3,"children":[{"ui":"divider"}]},
              {"ui":"section","title":"Odds","children":[{"ui":"note","text":"n"},{"ui":"markdown","text":"**b**"}]},
              {"ui":"kv","rows":[["Deck","40"],{"label":"Hand","value":5}]},
              {"ui":"stat","value":"74%","label":"to open"},
              {"ui":"button","id":"go","label":"Go","kind":"primary"},
              {"ui":"input","id":"name","label":"Name","value":"x","placeholder":"…"},
              {"ui":"input","id":"n","kind":"number","value":3,"min":0,"max":9,"live":true},
              {"ui":"stepper","id":"hand","value":5,"min":5,"max":6,"hint":"5 going first"},
              {"ui":"slider","id":"p","value":0.4,"min":0,"max":1,"step":0.1,"live":true},
              {"ui":"select","id":"s","value":"b","options":["a",{"value":"b","label":"Bee"}]},
              {"ui":"segmented","id":"g","options":["first","second"]},
              {"ui":"toggle","id":"t","value":true},
              {"ui":"checks","id":"c","options":["x","y"],"values":["y"]},
              {"ui":"cardPicker","id":"card","value":14558127},
              {"ui":"deckPicker","id":"deck","value":"d1"},
              {"ui":"table","columns":["a","b"],"rows":[[1,2],{"a":3,"b":4}],"pickable":true},
              {"ui":"cards","cards":["Ash Blossom & Joyous Spring", 14558127]},
              {"ui":"board","kind":"chart","body":{"type":"bar","labels":["5","6"],"series":[{"name":"odds","values":[50,60]}]}},
              {"ui":"progress","value":1.7,"label":"done"},
              {"ui":"empty","text":"Nothing yet."}
            ]}
            """,
        )
        assertTrue(tree.problems.isEmpty(), "${tree.problems}")
        val kids = (tree.root as UiNode.Col).children
        assertEquals(21, kids.size)
        val text = ((kids[0] as UiNode.Row).children[0] as UiNode.Text)
        assertEquals(Tone.MUTED, text.tone)
        assertTrue(text.mono)
        assertEquals(2, text.weight)
        assertEquals(3, (kids[1] as UiNode.Grid).columns)
        assertEquals(listOf("Deck" to "40", "Hand" to "5"), (kids[3] as UiNode.Kv).rows)
        assertTrue((kids[5] as UiNode.Button).primary)
        assertTrue((kids[7] as UiNode.Input).number)
        assertEquals(5.0, (kids[8] as UiNode.Stepper).value)
        assertEquals(UiOption("b", "Bee"), (kids[10] as UiNode.Select).options[1])
        assertEquals(listOf("y"), (kids[13] as UiNode.Checks).values)
        assertEquals(14558127, (kids[14] as UiNode.CardPicker).value)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), (kids[16] as UiNode.Table).rows)
        assertEquals(BoardKind.CHART, (kids[18] as UiNode.Board).kind)
        assertEquals(1.0, (kids[19] as UiNode.Progress).value, "held to 0..1")
    }

    @Test
    fun theLimits() {
        // 3,000 nodes at most.
        val many = (1..5_000).joinToString(",") { """{"ui":"text","text":"$it"}""" }
        val big = UiTree.parse("""{"ui":"col","children":[$many]}""")
        assertTrue(big.nodes <= AppLimits.NODES + 1)
        assertTrue(big.problems.any { "3000" in it || "3,000" in it })
        // 24 deep at most: what is deeper is drawn as one broken line.
        var deep = """{"ui":"text","text":"bottom"}"""
        repeat(40) { deep = """{"ui":"col","children":[$deep]}""" }
        val d = UiTree.parse(deep)
        var n: UiNode = d.root
        var depth = 0
        while (n is UiNode.Col) {
            n = n.children.single()
            depth++
        }
        assertIs<UiNode.Broken>(n)
        assertEquals(AppLimits.DEPTH, depth)
        // A string is cut at 20,000 characters.
        val long = "x".repeat(30_000)
        assertEquals(AppLimits.STRING, (one("""{"ui":"text","text":"$long"}""") as UiNode.Text).text.length)
        // 2,000 rows a page; the rest counted.
        val rows = (1..2_500).joinToString(",") { "[$it]" }
        val t = one("""{"ui":"table","columns":["n"],"rows":[$rows]}""") as UiNode.Table
        assertEquals(AppLimits.ROWS, t.rows.size)
        assertEquals(500, t.more)
        // 200 options.
        val opts = (1..300).joinToString(",") { "\"o$it\"" }
        assertEquals(AppLimits.OPTIONS, (one("""{"ui":"select","id":"s","options":[$opts]}""") as UiNode.Select).options.size)
    }

    @Test
    fun aBrokenNodeIsDrawnInItsPlaceAndTheRestStands() {
        val tree = UiTree.parse("""{"ui":"col","children":[{"ui":"stepper","label":"no id"},{"ui":"text","text":"still here"},{"ui":"board","kind":"chart","body":"not json"},{"label":"no kind"}]}""")
        val kids = (tree.root as UiNode.Col).children
        assertIs<UiNode.Broken>(kids[0])
        assertTrue("id" in (kids[0] as UiNode.Broken).why)
        assertEquals("still here", (kids[1] as UiNode.Text).text)
        assertIs<UiNode.Broken>(kids[2])
        assertIs<UiNode.Broken>(kids[3])
        assertEquals(3, tree.problems.size)
        assertIs<UiNode.Broken>(UiTree.parse("this is not json").root)
    }

    @Test
    fun onePrimaryAScreen() {
        val tree = UiTree.parse("""{"ui":"row","children":[{"ui":"button","id":"a","kind":"primary"},{"ui":"button","id":"b","kind":"primary"},{"ui":"button","id":"c","primary":true}]}""")
        val buttons = (tree.root as UiNode.Row).children.map { it as UiNode.Button }
        assertEquals(listOf(true, false, false), buttons.map { it.primary })
    }

    @Test
    fun colourKeysAreIgnored() {
        val n = one("""{"ui":"text","text":"hi","color":"#ff0000","background":"red","font":"Comic Sans","x":10,"y":20,"style":{"fontSize":99}}""")
        assertEquals(UiNode.Text("hi"), n, "nothing of the colour, the font or the place survives")
        val b = one("""{"ui":"button","id":"go","label":"Go","color":"red"}""") as UiNode.Button
        assertEquals(UiNode.Button("go", "Go"), b)
    }

    @Test
    fun anUnknownWidgetIsKept() {
        val n = one("""{"ui":"hologram","id":"h","weight":3}""")
        assertEquals(UiNode.Unknown("hologram", 3), n)
        // A canvas is no widget: there is no drawing.
        assertIs<UiNode.Unknown>(one("""{"ui":"canvas","paths":[]}"""))
    }

    @Test
    fun aBoardTakesOnlyTheKindsAnAppMayDraw() {
        assertIs<UiNode.Broken>(one("""{"ui":"board","kind":"image","body":"out/x.png"}"""))
        assertIs<UiNode.Broken>(one("""{"ui":"board","kind":"stat","body":{"value":"1","label":"x"}}"""))
        assertIs<UiNode.Board>(one("""{"ui":"board","kind":"markdown","body":"# hi"}"""))
    }

    @Test
    fun linksAreOnlyWorldAddresses() {
        assertTrue(AppLinks.allowed("world://boards/b1"))
        assertTrue(AppLinks.allowed("world://apps/hand-odds"))
        assertFalse(AppLinks.allowed("https://evil.example"))
        assertFalse(AppLinks.allowed("world://files/../x"))
        assertFalse(AppLinks.allowed("javascript:alert(1)"))
        val b = one("""{"ui":"button","id":"b","open":"https://evil.example"}""") as UiNode.Button
        assertEquals(null, b.open, "an outside address is never a button's to open")
        // An input is never a password field.
        val i = one("""{"ui":"input","id":"pw","kind":"password"}""") as UiNode.Input
        assertFalse(i.number)
    }
}
