package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.math.Cholesky
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.math.Matrix
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shootout's own linear algebra (Phase S §2): the fit's steps and ranges are only as right as this. */
class ShootoutMathTest {

    /** A random symmetric positive-definite matrix: Aᵀ A plus a little of the identity. */
    private fun spd(n: Int, seed: Int): Matrix {
        val r = Random(seed)
        val a = DoubleArray(n * n) { r.nextDouble(-1.0, 1.0) }
        val m = Matrix(n)
        for (i in 0 until n) for (j in 0 until n) {
            var s = if (i == j) 0.1 else 0.0
            for (k in 0 until n) s += a[k * n + i] * a[k * n + j]
            m[i, j] = s
        }
        return m
    }

    @Test
    fun choleskySolvesAndInverts() {
        val a = spd(30, 1)
        val chol = Cholesky.of(a)!!
        assertEquals(0.0, chol.jitter)
        val b = DoubleArray(30) { it - 15.0 }
        val x = chol.solve(b)
        val back = a.times(x)
        for (i in b.indices) assertEquals(b[i], back[i], 1e-8)
        val inv = chol.inverse()
        for (i in 0 until 30) for (j in 0 until 30) {
            var s = 0.0
            for (k in 0 until 30) s += a[i, k] * inv[k, j]
            assertEquals(if (i == j) 1.0 else 0.0, s, 1e-8, "A A⁻¹ at ($i, $j)")
        }
    }

    @Test
    fun aSparseQuadraticIsTheDenseOne() {
        val a = spd(12, 3)
        val index = intArrayOf(1, 4, 5, 11)
        val value = doubleArrayOf(0.5, -1.25, 2.0, 1.75)
        val dense = DoubleArray(12).also { v -> index.forEachIndexed { i, at -> v[at] = value[i] } }
        assertEquals(a.quadratic(dense), a.sparseQuadratic(index, value), 1e-10)
    }

    @Test
    fun aMatrixThatIsNotPositiveDefiniteIsRefusedThenJittered() {
        val m = Matrix(2, doubleArrayOf(1.0, 1.0, 1.0, 1.0)) // singular
        assertNull(Cholesky.of(Matrix(2, doubleArrayOf(1.0, 2.0, 2.0, 1.0))))
        val robust = Cholesky.robust(m)
        assertTrue(robust.jitter > 0 && robust.jitter < 1e-6, "the jitter is the smallest that works: ${robust.jitter}")
    }

    @Test
    fun logisticTailsKeepTheirDigits() {
        assertEquals(0.5, Logistic.of(0.0))
        assertEquals(-800.0, Logistic.logOf(-800.0), 1e-9)
        assertTrue(abs(Logistic.logOf(800.0)) < 1e-300)
        assertEquals(0.2, Logistic.of(Logistic.logit(0.2)), 1e-12)
    }
}
