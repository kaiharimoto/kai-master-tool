package com.kaiharimoto.mastertool.core.scene

import com.kaiharimoto.mastertool.core.motion.Vec3
import com.kaiharimoto.mastertool.core.render.Light
import com.kaiharimoto.mastertool.core.render.Shadows

/**
 * A shadow the room throws on itself, solved with the furniture.
 *
 * `docs/classic/AAA.md` #61d was a *decision* rather than a gap for a long time, and the
 * argument for it was good: one object throwing a shadow onto a desk where
 * nothing else does does not read as better lighting, it reads as the one thing
 * that got special treatment. kai has now asked for the set, so the reason it
 * was refused becomes the reason it has to arrive whole — every fixture that
 * stands on something casts onto the thing it stands on, or none of them do.
 *
 * ## Why this is not a piece
 *
 * There is no depth buffer on this stage and there cannot be one. Paint order in
 * the room is `ScenePainter`'s **separating axis**, chosen because sorting boxes
 * by nearest-corner depth painted a 511-pixel wall over a 241-pixel lamp — and a
 * depth buffer that could disagree with that axis would be a contradiction with
 * no test able to catch it.
 *
 * So a room shadow is a **decal on a receiver's face**, carried by the model,
 * painted with the surface it lands on, and never entered into the piece
 * ordering. Nothing about it can argue with the painter, because it is not
 * something the painter sorts.
 *
 * ## And why it is solved here rather than per frame
 *
 * Furniture is solved twice a day — `Scenery.of` is remembered against the
 * layout and the hour — so the whole set is a pure function of inputs that do
 * not move while anybody is playing. Projecting it here costs nothing at sixty
 * frames a second and, unlike a card's shadow, it can afford to be exact.
 */
data class RoomShadow(
    /** The piece that cast it, for the test that says everything standing casts. */
    val caster: String,
    /** The height of the surface it lands on. */
    val surfaceZ: Float,
    /** Where it landed, on that surface, wound the way the caster's face was. */
    val corners: List<Vec3>,
    /**
     * How soft the edge is, in mat pixels.
     *
     * From the source's own angular radius times how far the shadow was thrown,
     * which is the same arithmetic `Shadows.cast` uses for a card: a window
     * gives a broad gradient and a bulb a hard edge, and neither is a constant
     * anybody chose.
     */
    val spread: Float,
)

/** Solving the shadows a room throws on itself. */
object RoomShadows {

    /**
     * What [piece] throws onto the flat surface at [surfaceZ], if anything.
     *
     * The **box's** bottom face is what casts, not the mesh. Two reasons, and
     * the first is that the box is already what this room is sorted, measured
     * and tested by — `ScenePiece.mesh`'s own KDoc makes the same argument for
     * the same reason. The second is that four corners is exactly the shape a
     * penumbra shader takes, so a room shadow and a card's shadow can stay one
     * drawing rather than two.
     *
     * Null when the piece is already at or below the surface, when the light is
     * parallel to it, or when the light has no size to speak of — a fixture
     * standing *on* the thing it would shade has no shadow to throw.
     */
    fun under(piece: ScenePiece, light: Light, surfaceZ: Float = 0f): RoomShadow? {
        val box = piece.box
        if (box.min.z <= surfaceZ + FLUSH) return null

        val foot = footprint(piece)
        val landed = Shadows.landOn(foot, light, surfaceZ) ?: return null

        val angle = light.angularRadius(box.min)
        val throwLength = landed.rays.average().toFloat()
        return RoomShadow(
            caster = piece.name,
            surfaceZ = surfaceZ,
            corners = landed.corners,
            spread = if (angle <= 0f) 0f else angle * throwLength,
        )
    }

    /**
     * The outline that casts: a rectangle, or an octagon for anything turned.
     *
     * A box is the right thing to cast from for a box, and the wrong thing for
     * a lamp — a round foot throwing a hard-cornered rectangle is the one part
     * of this a person notices immediately, because the shadow and the object
     * standing in it disagree about what shape the object is.
     *
     * `ScenePiece.mesh` is already how this room says "turned on a lathe", so it
     * is what is asked. An octagon inscribed in the box is not the silhouette of
     * a solid of revolution and does not pretend to be: it is within 4% of a
     * circle's area against a square's 27%, it costs four more corners, and at
     * the size a desk lamp's foot is drawn nobody can tell it from round. The
     * exact answer is `Turned`'s own ring at the base, and it is available — it
     * is not taken because a shadow with twenty corners is twenty `Outset`
     * intersections per ring per frame for an edge that is already soft.
     */
    private fun footprint(piece: ScenePiece): List<Vec3> {
        val box = piece.box
        val z = box.min.z
        if (piece.mesh == null) {
            return listOf(
                Vec3(box.min.x, box.min.y, z),
                Vec3(box.max.x, box.min.y, z),
                Vec3(box.max.x, box.max.y, z),
                Vec3(box.min.x, box.max.y, z),
            )
        }
        val midX = (box.min.x + box.max.x) / 2f
        val midY = (box.min.y + box.max.y) / 2f
        val spanX = (box.max.x - box.min.x) / 2f
        val spanY = (box.max.y - box.min.y) / 2f
        // The eight points of a regular octagon, scaled into the box.
        return OCTAGON.map { (ux, uy) -> Vec3(midX + spanX * ux, midY + spanY * uy, z) }
    }

    /** A unit octagon, corners first, so a box scales straight into it. */
    private val OCTAGON: List<Pair<Float, Float>> = run {
        val near = 0.4142136f // tan(22.5 degrees): where a regular octagon's flat starts
        listOf(
            near to -1f, 1f to -near, 1f to near, near to 1f,
            -near to 1f, -1f to near, -1f to -near, -near to -1f,
        )
    }

    /**
     * Everything in [pieces] that stands on the surface at [surfaceZ], shadowed.
     *
     * The "as a set" half of #61d, and the reason this is a list rather than a
     * call per fixture: `SceneryTest` asserts that nothing standing on a surface
     * is missing from it, so a second lamp cannot arrive without one.
     */
    fun onSurface(
        pieces: List<ScenePiece>,
        light: Light,
        receiver: SceneBox,
        surfaceZ: Float = 0f,
    ): List<RoomShadow> = pieces
        .filter { !it.surface.isShell && within(it.box, receiver) }
        .mapNotNull { under(it, light, surfaceZ) }

    /**
     * Whether [box] stands within [receiver]'s own footprint.
     *
     * The second of the two conditions, and the weaker one: [Surface.isShell]
     * is what keeps the room out of its own shadows, and this is what keeps a
     * fixture standing somewhere else from being shadowed onto a surface it is
     * not touching. A shadow does not bend over an edge onto a second receiver
     * unless it is cast against that one too.
     */
    private fun within(box: SceneBox, receiver: SceneBox): Boolean =
        box.min.x >= receiver.min.x && box.max.x <= receiver.max.x &&
            box.min.y >= receiver.min.y && box.max.y <= receiver.max.y

    /**
     * How near a surface a piece may sit and still be considered to be on it.
     *
     * A tenth of a mat pixel. Below that the projection is casting a shadow
     * across a gap nobody can see, and the quad it returns is the caster's own
     * footprint — which is not a shadow, it is a second copy of the object drawn
     * in the dark.
     */
    private const val FLUSH = 0.1f
}
