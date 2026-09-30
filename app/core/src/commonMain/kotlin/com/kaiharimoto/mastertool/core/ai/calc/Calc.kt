package com.kaiharimoto.mastertool.core.ai.calc

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * A calculator Ai can hand arithmetic to, because a model that works out
 * `1 - C(37,5)/C(40,5)` in its head gets it wrong often enough to matter when the
 * answer is "how often do I open a hand trap". Deck maths is binomials and the
 * hypergeometric distribution, so those are built in: `atleast(40,3,5,1)` is the
 * chance of drawing at least one of three copies in a five-card hand.
 *
 * A plain recursive-descent parser over a closed grammar — no scripting engine, no
 * reflection, nothing a model's text could reach beyond the functions listed here.
 * Input is capped in length and nesting, and every error is a sentence a person can
 * act on ("Expected ) at 7"), since Ai passes it straight back.
 *
 * Grammar, loosest first: `+ -`, then `* /`, then unary minus, then `^` (right
 * associative, so `2^3^2` is 512 and `-2^2` is -4), then a postfix `%` that divides
 * by a hundred.
 */
object Calc {
    const val MAX_LENGTH = 500
    const val MAX_DEPTH = 64

    fun eval(expr: String): Result<Double> {
        if (expr.length > MAX_LENGTH) return Result.failure(CalcError("Expression is too long ($MAX_LENGTH characters at most)"))
        if (expr.isBlank()) return Result.failure(CalcError("Empty expression"))
        return try {
            val parser = Parser(expr)
            val value = parser.parse()
            when {
                value.isNaN() -> Result.failure(CalcError("Not a real number"))
                value.isInfinite() -> Result.failure(CalcError("Result is too large"))
                else -> Result.success(value)
            }
        } catch (e: CalcError) {
            Result.failure(e)
        }
    }

    /**
     * A number as a person reads it: at most six significant digits, no trailing
     * zeros, no exponent unless the number is huge or tiny. Whole numbers below
     * 10^15 are written in full, because a binomial like C(60,10) should be exact.
     * No percent sign is added; the caller knows whether it asked for one.
     */
    fun format(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
        if (x == 0.0) return "0"
        val sign = if (x < 0) "-" else ""
        val a = abs(x)
        if (a < 1e15 && a == floor(a)) return sign + a.toLong().toString()
        val exp = floor(log10(a)).toInt()
        if (exp in -5..14) {
            val decimals = (5 - exp).coerceAtLeast(0)
            val scaled = round(a * 10.0.pow(decimals)).toLong()
            val text = fixed(scaled, decimals)
            return if (text == "0") "0" else sign + text
        }
        // Scientific: a mantissa of six significant digits and a power of ten.
        var e = exp
        var m = round(a / 10.0.pow(e) * 1e5).toLong()
        if (m >= 1_000_000) {
            m /= 10
            e += 1
        }
        return sign + fixed(m, 5) + "e" + e
    }

    /** [digits] written with [decimals] of them after a point, trailing zeros dropped. */
    private fun fixed(digits: Long, decimals: Int): String {
        if (decimals == 0) return digits.toString()
        val s = digits.toString().padStart(decimals + 1, '0')
        val whole = s.dropLast(decimals)
        val frac = s.takeLast(decimals).trimEnd('0')
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    // --- The maths the functions stand for -------------------------------------

    /** n choose k by the multiplicative formula, exact in doubles for deck-sized n. */
    fun choose(n: Double, k: Double): Double {
        if (k < 0 || k > n) return 0.0
        val kk = minOf(k, n - k).toInt()
        var r = 1.0
        for (i in 1..kk) r = r * (n - kk + i) / i
        // The running product is whole at every step; rounding sheds the float error.
        return if (r < 1e15) round(r) else r
    }

    /** The chance of exactly [k] successes drawing [n] from [total] holding [successes]. */
    fun hypergeo(total: Double, successes: Double, n: Double, k: Double): Double {
        if (k < 0 || k > n || k > successes || n - k > total - successes) return 0.0
        return choose(successes, k) * choose(total - successes, n - k) / choose(total, n)
    }

    fun atLeast(total: Double, successes: Double, n: Double, k: Double): Double {
        var p = 0.0
        var i = maxOf(k, 0.0)
        while (i <= minOf(n, successes)) {
            p += hypergeo(total, successes, n, i)
            i += 1.0
        }
        return p.coerceIn(0.0, 1.0)
    }

    fun atMost(total: Double, successes: Double, n: Double, k: Double): Double {
        var p = 0.0
        var i = 0.0
        while (i <= minOf(k, n)) {
            p += hypergeo(total, successes, n, i)
            i += 1.0
        }
        return p.coerceIn(0.0, 1.0)
    }

    // --- The parser --------------------------------------------------------------

    private class CalcError(message: String) : IllegalArgumentException(message)

    private class Parser(val src: String) {
        var pos = 0
        var depth = 0

        fun parse(): Double {
            val v = expression()
            skip()
            if (pos < src.length) fail("Unexpected '${src[pos]}' at ${pos + 1}")
            return v
        }

        fun fail(message: String): Nothing = throw CalcError(message)

        fun skip() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        /** The next character after whitespace, with the common typographic signs read as ASCII. */
        fun peek(): Char? {
            skip()
            if (pos >= src.length) return null
            return when (val c = src[pos]) {
                '×' -> '*'
                '÷' -> '/'
                '−' -> '-'
                else -> c
            }
        }

        fun nested(block: () -> Double): Double {
            if (++depth > MAX_DEPTH) fail("Expression is nested too deeply")
            try {
                return block()
            } finally {
                depth--
            }
        }

        fun expression(): Double = nested { sum() }

        fun sum(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= unary() }
                    '/' -> {
                        pos++
                        val d = unary()
                        if (d == 0.0) fail("Division by zero")
                        v /= d
                    }
                    else -> return v
                }
            }
        }

        fun unary(): Double = nested {
            when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> power()
            }
        }

        fun power(): Double {
            val base = postfix()
            if (peek() == '^') {
                pos++
                // The exponent is a unary, so 2^-1 works and 2^3^2 groups to the right.
                val exponent = unary()
                if (base == 0.0 && exponent < 0) fail("Division by zero")
                return base.pow(exponent)
            }
            return base
        }

        fun postfix(): Double {
            var v = primary()
            while (peek() == '%') {
                pos++
                v /= 100.0
            }
            return v
        }

        fun primary(): Double {
            val c = peek() ?: fail("Expression ends too soon")
            return when {
                c == '(' -> {
                    pos++
                    val v = expression()
                    expect(')')
                    v
                }
                c.isDigit() || c == '.' -> number()
                c.isLetter() -> call()
                else -> fail("Unexpected '$c' at ${pos + 1}")
            }
        }

        fun expect(c: Char) {
            if (peek() != c) fail("Expected $c at ${pos + 1}")
            pos++
        }

        fun number(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                var p = pos + 1
                if (p < src.length && (src[p] == '+' || src[p] == '-')) p++
                if (p < src.length && src[p].isDigit()) {
                    pos = p
                    while (pos < src.length && src[pos].isDigit()) pos++
                }
            }
            val text = src.substring(start, pos)
            return text.toDoubleOrNull() ?: fail("Bad number '$text' at ${start + 1}")
        }

        fun call(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos)
            val key = name.lowercase()
            if (peek() != '(') {
                return when (key) {
                    "pi" -> kotlin.math.PI
                    "e" -> kotlin.math.E
                    else -> if (key in FUNCTIONS) fail("Expected ( after $name at ${pos + 1}") else fail("Unknown name $name")
                }
            }
            if (key !in FUNCTIONS) fail("Unknown function $name")
            pos++
            val args = mutableListOf<Double>()
            if (peek() != ')') {
                args += expression()
                while (peek() == ',') {
                    pos++
                    args += expression()
                }
            }
            expect(')')
            return apply(name, key, args)
        }

        fun apply(name: String, key: String, a: List<Double>): Double {
            fun arity(n: Int) {
                if (a.size != n) fail("$name takes $n argument${if (n == 1) "" else "s"}, not ${a.size}")
            }
            fun whole(x: Double, what: String): Double {
                if (x != floor(x) || x < 0) fail("$name needs $what to be a whole number of 0 or more")
                if (x > 1_000_000) fail("$name: $what is too large")
                return x
            }
            fun deck() {
                arity(4)
                val total = whole(a[0], "the deck size")
                val succ = whole(a[1], "the number of hits")
                val drawn = whole(a[2], "the number drawn")
                whole(a[3], "the count")
                if (succ > total) fail("$name: more hits (${format(succ)}) than cards (${format(total)})")
                if (drawn > total) fail("$name: drawing more (${format(drawn)}) than the deck holds (${format(total)})")
            }
            return when (key) {
                "c", "choose" -> {
                    arity(2)
                    choose(whole(a[0], "n"), whole(a[1], "k"))
                }
                "fact" -> {
                    arity(1)
                    val n = whole(a[0], "n")
                    if (n > 170) fail("fact needs n of 170 or less")
                    var r = 1.0
                    for (i in 2..n.toInt()) r *= i
                    r
                }
                "hypergeo" -> { deck(); hypergeo(a[0], a[1], a[2], a[3]) }
                "atleast" -> { deck(); atLeast(a[0], a[1], a[2], a[3]) }
                "atmost" -> { deck(); atMost(a[0], a[1], a[2], a[3]) }
                "min" -> {
                    if (a.isEmpty()) fail("min needs at least one argument")
                    a.min()
                }
                "max" -> {
                    if (a.isEmpty()) fail("max needs at least one argument")
                    a.max()
                }
                "round" -> {
                    if (a.size !in 1..2) fail("round takes 1 or 2 arguments, not ${a.size}")
                    val digits = if (a.size == 2) a[1] else 0.0
                    if (digits != floor(digits) || digits !in 0.0..12.0) fail("round needs 0 to 12 digits")
                    val scale = 10.0.pow(digits)
                    // Half away from zero, as a person rounds.
                    val x = a[0]
                    (if (x < 0) -1.0 else 1.0) * floor(abs(x) * scale + 0.5) / scale
                }
                "sqrt" -> {
                    arity(1)
                    if (a[0] < 0) fail("sqrt needs a number of 0 or more")
                    sqrt(a[0])
                }
                "ln" -> {
                    arity(1)
                    if (a[0] <= 0) fail("ln needs a positive number")
                    ln(a[0])
                }
                "log10" -> {
                    arity(1)
                    if (a[0] <= 0) fail("log10 needs a positive number")
                    log10(a[0])
                }
                else -> fail("Unknown function $name")
            }
        }
    }

    private val FUNCTIONS = setOf(
        "c", "choose", "fact", "hypergeo", "atleast", "atmost", "min", "max", "round", "sqrt", "ln", "log10",
    )
}
