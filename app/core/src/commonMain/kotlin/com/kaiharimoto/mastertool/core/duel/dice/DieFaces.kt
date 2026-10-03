package com.kaiharimoto.mastertool.core.duel.dice

/**
 * A die's six faces and what is written on them (1.0.87, the opening roll).
 *
 * The body's faces are its axes — [PX], [NX], [PY], [NY], [PZ], [NZ], each a face's outward normal — and a
 * *labelling* gives each face its number. A proper die is one of [PROPER]: the 24 turns of [STANDARD], so opposite
 * faces sum to seven and 1, 2 and 3 go round their shared corner the same way on every one.
 *
 * The roll is decided before the dice are thrown (the stamped values) and the throw is real physics; [relabel] is
 * what joins them: once the simulation has come to rest, the die's labelling is chosen so that the face physics put
 * on top carries the stamped number — still a proper die — and every frame is drawn with that one labelling.
 */
object DieFaces {
    const val PX = 0
    const val NX = 1
    const val PY = 2
    const val NY = 3
    const val PZ = 4
    const val NZ = 5

    /** Each face's outward normal in the body's frame. */
    val NORMALS: List<V3> = listOf(
        V3(1.0, 0.0, 0.0), V3(-1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.0, -1.0, 0.0), V3(0.0, 0.0, 1.0), V3(0.0, 0.0, -1.0),
    )

    /** The face across from [face]. */
    fun opposite(face: Int): Int = face xor 1

    /** The die as it comes out of the box: 1 on top, 2 to the right (+x), 3 away (+y). */
    val STANDARD: List<Int> = listOf(2, 5, 3, 4, 1, 6)

    /** Every proper labelling — the 24 turns of [STANDARD] — in a fixed order. */
    val PROPER: List<List<Int>> by lazy {
        val out = LinkedHashSet<List<Int>>()
        val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        for (p in perms) for (sx in listOf(1, -1)) for (sy in listOf(1, -1)) for (sz in listOf(1, -1)) {
            val signs = intArrayOf(sx, sy, sz)
            // The matrix taking body axis i to signs[i] · axis p[i]; a turn only when its determinant is +1.
            if (parity(p) * sx * sy * sz != 1) continue
            val label = MutableList(6) { 0 }
            for (face in 0 until 6) {
                val axis = face / 2
                val sign = if (face % 2 == 0) 1 else -1
                val to = p[axis] * 2 + if (sign * signs[axis] > 0) 0 else 1
                label[to] = STANDARD[face]
            }
            out += label
        }
        out.toList()
    }

    private fun parity(p: IntArray): Int {
        var inv = 0
        for (i in p.indices) for (j in i + 1 until p.size) if (p[i] > p[j]) inv++
        return if (inv % 2 == 0) 1 else -1
    }

    /** The face whose normal points most nearly up, for a die turned [q]. */
    fun upFace(q: Quat): Int = NORMALS.indices.maxBy { q.rotate(NORMALS[it]).z }

    /** How far from flat a die turned [q] lies: 1 − the up face's normal's height (0 when it lies flat). */
    fun tilt(q: Quat): Double = 1.0 - q.rotate(NORMALS[upFace(q)]).z

    /** Whether [label] is a proper die: one of [PROPER]. */
    fun proper(label: List<Int>): Boolean = label in PROPER

    /**
     * The proper labelling that writes [value] on [up] — the face physics left on top. Of the four that do, the one
     * that keeps most of [shown]'s numbers on the faces in [seen] (what was in view as the dice left the hand), so a
     * die changes as little as it can where anyone could watch it change; ties go to the first in [PROPER] order
     * after [variety], so the same roll need not always show the same sides.
     */
    fun relabel(up: Int, value: Int, shown: List<Int> = STANDARD, seen: Set<Int> = emptySet(), variety: Int = 0): List<Int> {
        require(value in 1..6) { "A die shows 1 to 6, not $value" }
        val fits = PROPER.filter { it[up] == value }
        val start = ((variety % fits.size) + fits.size) % fits.size
        val order = fits.indices.map { fits[(start + it) % fits.size] }
        return order.maxBy { l -> seen.count { l[it] == shown[it] } }
    }

    /** The two axes along a face, in the body's frame: (u, v) with u × v the face's normal. */
    fun axes(face: Int): Pair<V3, V3> = when (face) {
        PX -> V3(0.0, 1.0, 0.0) to V3(0.0, 0.0, 1.0)
        NX -> V3(0.0, 0.0, 1.0) to V3(0.0, 1.0, 0.0)
        PY -> V3(0.0, 0.0, 1.0) to V3(1.0, 0.0, 0.0)
        NY -> V3(1.0, 0.0, 0.0) to V3(0.0, 0.0, 1.0)
        PZ -> V3(1.0, 0.0, 0.0) to V3(0.0, 1.0, 0.0)
        else -> V3(0.0, 1.0, 0.0) to V3(1.0, 0.0, 0.0)
    }

    /** A face's four corners in the body's frame, going round it. */
    fun corners(face: Int): List<V3> {
        val n = NORMALS[face] * 0.5
        val (u, v) = axes(face)
        val hu = u * 0.5
        val hv = v * 0.5
        return listOf(n - hu - hv, n + hu - hv, n + hu + hv, n - hu + hv)
    }

    /** Where the pips of [value] sit on a face, in its (u, v) from −½ to ½. */
    fun pips(value: Int): List<Pair<Double, Double>> {
        val k = PIP_OFFSET
        return when (value) {
            1 -> listOf(0.0 to 0.0)
            2 -> listOf(-k to -k, k to k)
            3 -> listOf(-k to -k, 0.0 to 0.0, k to k)
            4 -> listOf(-k to -k, k to -k, -k to k, k to k)
            5 -> listOf(-k to -k, k to -k, 0.0 to 0.0, -k to k, k to k)
            6 -> listOf(-k to -k, -k to 0.0, -k to k, k to -k, k to 0.0, k to k)
            else -> emptyList()
        }
    }

    /** A pip's centre in the body's frame. */
    fun pipAt(face: Int, u: Double, v: Double): V3 {
        val (au, av) = axes(face)
        return NORMALS[face] * 0.5 + au * u + av * v
    }

    const val PIP_OFFSET = 0.25
    const val PIP_RADIUS = 0.085
}
