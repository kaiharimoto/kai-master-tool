package com.kaiharimoto.mastertool.core.shootout.math

import kotlin.math.sqrt

/**
 * A small dense square matrix, row-major (Phase S §2).
 *
 * The shootout's model has about a hundred parameters, so its curvature fits in memory as plain doubles and a
 * library would buy nothing but a dependency. Only what the fit and the picker need is here.
 */
class Matrix(val n: Int, val data: DoubleArray = DoubleArray(n * n)) {

    init {
        require(data.size == n * n) { "a $n×$n matrix needs ${n * n} entries, not ${data.size}" }
    }

    operator fun get(i: Int, j: Int): Double = data[i * n + j]

    operator fun set(i: Int, j: Int, v: Double) {
        data[i * n + j] = v
    }

    /** Adds [v] at (i, j); the fit builds its curvature one trial at a time this way. */
    fun add(i: Int, j: Int, v: Double) {
        data[i * n + j] += v
    }

    fun copy(): Matrix = Matrix(n, data.copyOf())

    /** This matrix times [v]. */
    fun times(v: DoubleArray): DoubleArray {
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            val row = i * n
            for (j in 0 until n) s += data[row + j] * v[j]
            out[i] = s
        }
        return out
    }

    /** vᵀ M v: a variance, when this is a covariance and [v] the gradient of what is reported. */
    fun quadratic(v: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until n) {
            val vi = v[i]
            if (vi == 0.0) continue
            val row = i * n
            var r = 0.0
            for (j in 0 until n) r += data[row + j] * v[j]
            s += vi * r
        }
        return s
    }

    /** xᵀ M x for an x given by its nonzero entries: the variance of one hand's value, without spreading it out. */
    fun sparseQuadratic(index: IntArray, value: DoubleArray): Double {
        var s = 0.0
        for (a in index.indices) {
            val row = index[a] * n
            var r = 0.0
            for (b in index.indices) r += data[row + index[b]] * value[b]
            s += value[a] * r
        }
        return s
    }

    /** The largest diagonal entry, the scale the jitter is measured against. */
    fun maxDiagonal(): Double {
        var m = 0.0
        for (i in 0 until n) m = maxOf(m, kotlin.math.abs(this[i, i]))
        return m
    }
}

/**
 * The Cholesky factor L of a symmetric positive-definite matrix (A = L Lᵀ), with the [jitter] that had to be added
 * to its diagonal to make it one.
 *
 * Newton's step and the Laplace covariance both come from here. A curvature that is only just positive (a card
 * never yet seen, held only by its prior) can fail in floating point, so [robust] adds the smallest diagonal
 * jitter that succeeds and says how much it took, instead of throwing in the middle of a session.
 */
class Cholesky private constructor(val n: Int, private val l: DoubleArray, val jitter: Double) {

    /** x with A x = [b]. */
    fun solve(b: DoubleArray): DoubleArray {
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            val row = i * n
            for (k in 0 until i) s -= l[row + k] * y[k]
            y[i] = s / l[row + i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k * n + i] * x[k]
            x[i] = s / l[i * n + i]
        }
        return x
    }

    /** A⁻¹, symmetric: the Laplace covariance when A is the negative curvature at the mode. */
    fun inverse(): Matrix {
        // L⁻¹ by forward substitution, then A⁻¹ = L⁻ᵀ L⁻¹.
        val inv = DoubleArray(n * n)
        for (j in 0 until n) {
            inv[j * n + j] = 1.0 / l[j * n + j]
            for (i in j + 1 until n) {
                var s = 0.0
                val row = i * n
                for (k in j until i) s -= l[row + k] * inv[k * n + j]
                inv[row + j] = s / l[row + i]
            }
        }
        val out = Matrix(n)
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = 0.0
                for (k in i until n) s += inv[k * n + i] * inv[k * n + j]
                out[i, j] = s
                out[j, i] = s
            }
        }
        return out
    }

    companion object {

        /** The factor of [a] plus [jitter] on its diagonal, or null when that is not positive definite. */
        fun of(a: Matrix, jitter: Double = 0.0): Cholesky? {
            val n = a.n
            val l = DoubleArray(n * n)
            for (i in 0 until n) {
                val rowI = i * n
                for (j in 0..i) {
                    val rowJ = j * n
                    var s = a[i, j] + if (i == j) jitter else 0.0
                    for (k in 0 until j) s -= l[rowI + k] * l[rowJ + k]
                    if (i == j) {
                        if (!(s > 0.0) || s.isNaN()) return null
                        l[rowI + i] = sqrt(s)
                    } else {
                        l[rowI + j] = s / l[rowJ + j]
                    }
                }
            }
            return Cholesky(n, l, jitter)
        }

        /**
         * The factor of [a], adding the smallest jitter (from a millionth of a millionth of its largest diagonal
         * entry, tenfold each try) that makes it positive definite. Never fails for a symmetric matrix with a
         * finite diagonal: the last try is a jitter larger than the matrix itself.
         */
        fun robust(a: Matrix): Cholesky {
            of(a)?.let { return it }
            val scale = maxOf(a.maxDiagonal(), 1e-12)
            var jitter = scale * 1e-12
            while (jitter < scale * 1e4) {
                of(a, jitter)?.let { return it }
                jitter *= 10.0
            }
            return of(a, scale * 1e4 + a.n * scale)
                ?: error("the matrix is not symmetric or holds a NaN; no jitter makes it positive definite")
        }
    }
}
