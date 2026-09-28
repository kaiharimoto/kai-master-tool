package com.kaiharimoto.mastertool.core.scene

import com.kaiharimoto.mastertool.core.motion.Vec3

/**
 * The order the room has to be painted in, for a camera that can move.
 *
 * ## Why depth alone is not enough
 *
 * Sorting solids by the depth of their nearest corner is the painter's rule, and
 * it is right for solids that do not interpenetrate — which every piece of this
 * room satisfies. It is right about *which of two overlapping boxes is nearer*
 * only when neither is much taller than the other, and the room now contains a
 * counter-example: the wall is five hundred pixels tall and stands behind
 * everything, the lamp is two hundred and forty and stands in front of it, and
 * the wall's top-front corner reaches further toward the camera than any part of
 * the lamp does. By nearest-corner depth the wall sorts last, and at the seated
 * seat it paints straight over the top of the lamp for fifty pixels.
 *
 * Grouping by height does not fix it — both stand on the desk. Grouping by
 * "ground and standing" does not fix it either, for the same reason. The
 * information that resolves it is not in either box alone; it is in the pair,
 * and in where the camera is.
 *
 * ## The separating axis
 *
 * Two axis-aligned boxes that are disjoint along some axis have an unambiguous
 * answer: the one on the far side of that axis *from the eye* is behind. Every
 * pair in this room is disjoint along at least one axis, because nothing
 * interpenetrates. So depth gives the starting order and this gives the
 * correction.
 *
 * It reads the camera because "behind" does. Turn the table halfway round and
 * the wall is in front of the lamp, which is not a special case to handle but
 * the definition working.
 *
 * ## Two filters, and the relation needs both
 *
 * A separating axis is sound for any pair a ray can reach both halves of, and
 * says nothing useful about any other pair. Both of the ways it goes wrong are
 * ways of asserting an order for a pair no ray connects, and each needs its own
 * filter — with only one of them in place the room still cycles, which was
 * measured rather than reasoned about.
 *
 * **Pieces that do not overlap on screen are not constrained.** Paint them
 * either way round and the picture is identical, so an edge between them is
 * pure noise that can only close a cycle. The lamp's mast stands on the desk
 * and the window's header is up on the far wall; they share no pixel from any
 * seat, and z alone will still cheerfully report that the header is in front.
 *
 * ## The axes have to agree, and that is what makes it an order at all
 *
 * A pair can be separated on more than one axis, and those axes can disagree
 * about which piece is in front. Picking between them — the first that
 * separates, or the one the camera is most nearly looking along — is what the
 * two earlier versions did, and it makes the relation **cyclic**: A is above B,
 * B is left of C, C is nearer than A, each answer off a different axis. With
 * the window's joinery in place that put fourteen of sixteen pieces into one
 * cycle at the overhead seat, and it was already true of the shipped ten-piece
 * room. The old bubble pass never noticed, because it stops when nothing
 * adjacent wants to swap and a cycle looks exactly like that.
 *
 * Requiring **unanimity** removes the choice, and with the screen filter above
 * it removes the cycles: 432 cameras — two rooms, three seats, a full
 * revolution each — with no cycle and no unsatisfied edge. It is also the
 * answer with an argument behind it rather than a heuristic; see [behind].
 *
 * ## Why it is a topological sort and not a comparison sort
 *
 * [behind] is a *partial* order — two boxes with no separating axis have no
 * opinion about each other — and `sortedWith` requires a total one. Fed a
 * partial comparator it produces an arbitrary order, quietly, on some inputs.
 *
 * The first version answered that with repeated adjacent-swap passes, on the
 * reasoning that bubbling is exact for any set forming a DAG under [behind].
 * **That reasoning is wrong and the room grew until it showed.** Adjacent swaps
 * can only move a piece past its immediate neighbour, so a piece that must
 * overtake another is stuck the moment something with *no opinion either way*
 * sits between them — and pieces with no opinion are the common case here, not
 * the exception. Adding a window's joinery put a glazing bar four places ahead
 * of an ornament on the desk it stands behind, with a stile between them
 * abstaining, and no number of passes could ever have closed it. Bubbling is a
 * total-order algorithm; nothing about running it longer makes it a topological
 * one.
 *
 * So the relation is walked properly: every pair is asked once, and the pieces
 * come out in an order that respects every answer. It costs the same n² calls
 * to [behind] the passes already cost, and for the seventeen boxes this room is
 * made of that is beneath discussion against a budget of eight thousand
 * microseconds.
 *
 * This is `docs/classic/AAA.md` #93's cheap ancestor and it is honest about being one.
 * A real depth sort across the composable tree is still the work that would let
 * a mug stand on the felt.
 */
object ScenePainter {

    /**
     * How near a box comes to the camera, and the rectangle it covers.
     *
     * Both come off the same eight corners and the caller computes them
     * together, because it has the projection and this object deliberately does
     * not. The rectangle is the box's whole silhouette rather than any one
     * face's, which is conservative in the right direction: it can only claim
     * two pieces might overlap when they do not, and a spare edge between two
     * pieces that both have a correct place is harmless where a missing one is
     * a piece painted through.
     */
    data class Reach(
        val depth: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun overlaps(other: Reach): Boolean =
            left < other.right && right > other.left &&
                top < other.bottom && bottom > other.top
    }

    /**
     * The room, furthest first, for a camera at [eyeAt].
     *
     * Depth is the *starting* order and the tie-break, never the answer: among
     * the pieces nothing is still waiting on, the one furthest away goes next.
     * That keeps the result stable, keeps it close to what a plain depth sort
     * would have said, and makes it a pure function of the geometry rather than
     * of the order the caller happened to build its list in.
     *
     * @param reachOf where a box lands on the glass and how near it comes — the
     *   caller supplies it because it needs the projection.
     */
    fun order(
        pieces: List<ScenePiece>,
        eyeAt: Vec3,
        reachOf: (SceneBox) -> Reach,
    ): List<ScenePiece> {
        if (pieces.size < 2) return pieces

        // Indices throughout rather than the pieces themselves: two boxes of the
        // same size made of the same thing are an equal `ScenePiece`, and a map
        // keyed on one would quietly merge them into a single node.
        val reaches = pieces.map { reachOf(it.box) }
        val order = pieces.indices.sortedBy { reaches[it].depth }
        val count = order.size
        val after = Array(count) { mutableListOf<Int>() }
        val waiting = IntArray(count)
        for (i in 0 until count) {
            for (j in 0 until count) {
                if (i == j) continue
                // Not overlapping on screen is not "no opinion" — it is that
                // there is nothing for an opinion to be about.
                if (!reaches[order[i]].overlaps(reaches[order[j]])) continue
                if (behind(pieces[order[i]].box, pieces[order[j]].box, eyeAt)) {
                    after[i] += j
                    waiting[j]++
                }
            }
        }

        val painted = BooleanArray(count)
        val out = ArrayList<ScenePiece>(count)
        repeat(count) {
            // The furthest piece with nothing left in front of it. Failing that
            // — the pieces have formed a cycle, and no painter's order is
            // correct for one — the furthest piece left, so that a scene which
            // cannot be ordered still comes out looking like a depth sort rather
            // than like nothing.
            val next = (0 until count).firstOrNull { !painted[it] && waiting[it] == 0 }
                ?: (0 until count).first { !painted[it] }
            painted[next] = true
            out += pieces[order[next]]
            after[next].forEach { if (!painted[it]) waiting[it]-- }
        }
        return out
    }

    /**
     * Whether [a] must be painted before [b]: they are disjoint along some axis,
     * and [a] is on the far side of it from the eye.
     *
     * False when no axis separates them, which is the honest answer rather than
     * a guess — two boxes that overlap on all three axes are interpenetrating,
     * and no painter's order is correct for those.
     */
    fun behind(a: SceneBox, b: SceneBox, eyeAt: Vec3): Boolean {
        // Every separating axis is asked and they have to agree.
        //
        // A single one of them is already sound: if the two are disjoint along
        // x and the eye is on a's side of the gap, then any ray that hits both
        // crosses a's span before b's, because a ray leaving the eye is monotone
        // in x. Nothing about the other two axes can overturn that.
        //
        // So when two axes *disagree*, the premise has failed rather than the
        // logic: no ray hits both, the pair does not overlap on screen, and
        // there is no order to get wrong. Abstaining there is not a hedge, it is
        // the only correct answer — and it is what makes this relation a partial
        // order instead of a bag of cycles.
        //
        // Choosing between the axes instead is what the two earlier versions
        // did, and both were wrong in the same way. Taking the first that
        // separated put the window's sill in front of the lamp on the strength
        // of an x gap between two things that never touch. Taking the one the
        // camera was most nearly looking along fixed that pair and made the
        // relation *cyclic* — fourteen pieces of sixteen in one cycle at the
        // overhead seat — because a pair separated on several axes could then
        // pick a different axis than the next pair along.
        var answer: Boolean? = null
        listOf(
            farther(a.min.x, a.max.x, b.min.x, b.max.x, eyeAt.x),
            farther(a.min.y, a.max.y, b.min.y, b.max.y, eyeAt.y),
            farther(a.min.z, a.max.z, b.min.z, b.max.z, eyeAt.z),
        ).forEach { axis ->
            if (axis == null) return@forEach
            if (answer != null && answer != axis) return false
            answer = axis
        }
        return answer ?: false
    }

    /**
     * One axis: whether the first is the further away along it, or null when
     * there is nothing to say.
     *
     * Two ways there is nothing to say, and the second one is the subtle one.
     * The spans may **overlap**, in which case the axis does not separate them
     * at all. Or the eye may sit **inside the gap between them**, in which case
     * it separates them and still settles nothing: a ray leaving an eye in the
     * gap travels one way or the other, so it can reach one box or the other
     * and never both.
     *
     * Answering that second case anyway — by comparing the eye against the
     * middle of the gap, which is what this did — is how the relation kept
     * finding cycles from real camera positions. The answer is arbitrary, it is
     * about a pair no ray connects, and it then out-votes an axis that had a
     * real answer.
     *
     * Where there *is* an answer it needs no tolerance and admits no argument:
     * with the eye below the gap, a ray that hits both must run up the axis, so
     * it crosses the low box's span first. That is the whole proof, and it is
     * why one separating axis is enough on its own.
     */
    private fun farther(
        aMin: Float,
        aMax: Float,
        bMin: Float,
        bMax: Float,
        eye: Float,
    ): Boolean? = when {
        aMax <= bMin -> when {
            eye <= aMax -> false
            eye >= bMin -> true
            else -> null
        }
        bMax <= aMin -> when {
            eye <= bMax -> true
            eye >= aMin -> false
            else -> null
        }
        else -> null
    }
}
