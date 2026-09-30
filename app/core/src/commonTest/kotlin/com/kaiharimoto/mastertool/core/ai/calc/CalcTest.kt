package com.kaiharimoto.mastertool.core.ai.calc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalcTest {
    private fun v(expr: String): Double = Calc.eval(expr).getOrThrow()
    private fun err(expr: String): String = Calc.eval(expr).exceptionOrNull()?.message ?: error("$expr did not fail")
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun precedence() {
        assertEquals(14.0, v("2 + 3 * 4"))
        assertEquals(20.0, v("(2 + 3) * 4"))
        assertEquals(1.0, v("10 - 4 - 5"))
        assertEquals(2.0, v("16 / 4 / 2"))
        assertEquals(-6.0, v("-2 * 3"))
        assertEquals(5.0, v("--5"))
        assertEquals(0.001, v("1e-3"))
        assertEquals(2500.0, v("2.5E3"))
        assertEquals(0.5, v(".5"))
        assertEquals(12.0, v("3 × 4"))
    }

    @Test
    fun powerIsRightAssociativeAndBindsTighterThanMinus() {
        assertEquals(512.0, v("2^3^2"))
        assertEquals(-4.0, v("-2^2"))
        assertEquals(4.0, v("(-2)^2"))
        assertEquals(0.5, v("2^-1"))
        assertEquals(24.0, v("3 * 2^3"))
    }

    @Test
    fun percentDividesByAHundred() {
        assertEquals(0.3, v("30%"))
        assertEquals(15.0, v("50% * 30"))
        assertEquals(1.05, v("1 + 5%"))
    }

    @Test
    fun binomialsAndFactorials() {
        assertEquals(658008.0, v("C(40,5)"))
        assertEquals(658008.0, v("choose(40, 5)"))
        assertEquals(1.0, v("C(5,0)"))
        assertEquals(0.0, v("C(3,5)"))
        assertEquals(75394027566.0, v("C(60,10)"))
        assertEquals(120.0, v("fact(5)"))
        assertEquals(1.0, v("fact(0)"))
    }

    @Test
    fun hypergeometric() {
        // At least one of three copies in a five-card hand from forty.
        val expected = 1 - 435897.0 / 658008.0
        near(expected, v("atleast(40,3,5,1)"))
        near(expected, v("1 - C(37,5)/C(40,5)"))
        near(0.3376, v("atleast(40,3,5,1)"), 1e-4)
        near(1 - expected, v("atmost(40,3,5,0)"))
        near(1 - expected, v("hypergeo(40,3,5,0)"))
        // Exactly k over every k is a whole distribution.
        for ((deck, hits, drawn) in listOf(Triple(40, 3, 5), Triple(40, 9, 6), Triple(60, 12, 5), Triple(20, 20, 7))) {
            val total = (0..drawn).sumOf { k -> v("hypergeo($deck,$hits,$drawn,$k)") }
            near(1.0, total)
        }
        near(1.0, v("atleast(40,3,5,0)"))
        assertEquals(0.0, v("hypergeo(40,3,5,4)"))
    }

    @Test
    fun otherFunctions() {
        assertEquals(2.0, v("min(4, 2, 9)"))
        assertEquals(9.0, v("max(4, 2, 9)"))
        assertEquals(0.338, v("round(0.33755, 3)"))
        assertEquals(3.0, v("round(2.5)"))
        assertEquals(-3.0, v("round(-2.5)"))
        assertEquals(3.0, v("sqrt(9)"))
        assertEquals(2.0, v("log10(100)"))
        near(1.0, v("ln(e)"))
        near(kotlin.math.PI, v("pi"))
    }

    @Test
    fun errorsAreSentences() {
        assertEquals("Unknown function foo", err("foo(2)"))
        assertEquals("Expected ) at 7", err("(1 + 2"))
        assertEquals("Division by zero", err("1 / (2 - 2)"))
        assertEquals("Unexpected ')' at 4", err("1 +)"))
        assertEquals("Unexpected 'x' at 3", err("2 x 3"))
        assertEquals("Empty expression", err("  "))
        assertEquals("C takes 2 arguments, not 1", err("C(4)"))
        assertEquals("sqrt needs a number of 0 or more", err("sqrt(-1)"))
        assertTrue(err("1+".padEnd(501, '1')).startsWith("Expression is too long"))
        assertEquals("Expression is nested too deeply", err("(".repeat(200) + "1" + ")".repeat(200)))
        assertTrue(err("atleast(40,50,5,1)").startsWith("atleast: more hits"))
        assertTrue(err("C(4.5, 2)").contains("whole number"))
    }

    @Test
    fun formatting() {
        assertEquals("658008", Calc.format(658008.0))
        assertEquals("0.337551", Calc.format(1 - 435897.0 / 658008.0))
        assertEquals("0.5", Calc.format(0.5))
        assertEquals("33.7553", Calc.format(33.75530))
        assertEquals("-1.25", Calc.format(-1.25))
        assertEquals("0", Calc.format(-0.0))
        assertEquals("1234.57", Calc.format(1234.5678))
        assertEquals("0.00012", Calc.format(0.00012))
        assertEquals("1.23457e-7", Calc.format(1.234567e-7))
        assertEquals("75394027566", Calc.format(75394027566.0))
        assertEquals("1e20", Calc.format(1e20))
        assertEquals("0.1", Calc.format(0.1 + 0.2 - 0.2))
    }
}
