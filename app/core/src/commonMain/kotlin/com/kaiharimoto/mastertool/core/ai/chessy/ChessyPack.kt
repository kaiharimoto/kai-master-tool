package com.kaiharimoto.mastertool.core.ai.chessy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Chessy's pictures as the app carries them (`composeResources/files/chessy/`): every layer, face and mouth part
 * painted as kai approved her in the mockup, each in its own box on the sheet's 1320 × 1740 grid, and the head's
 * sphere the turn bends them round. Written by `tools/chessy/export.js`; read once.
 */
@Serializable
data class ChessyPack(
    val w: Int,
    val h: Int,
    val sphere: Sphere,
    /** The closed smile is drawn shorter about this x ([closedLength]) and moved by [closedDy] (kai's setting). */
    @SerialName("closed_cx") val closedCx: Float,
    val closedLength: Float = 0.89f,
    val closedDy: Float = -5f,
    /** Back to front; per-face layers ([PackLayer.perface]) draw the face shown. */
    val layers: List<PackLayer>,
    val faces: Map<String, PackFace>,
    val parts: PackParts,
) {
    fun layer(id: String): PackLayer? = layers.firstOrNull { it.id == id }

    /** Every picture file the pack names. */
    val files: List<String>
        get() = (layers.flatMap { listOfNotNull(it.pic, it.rim) } +
            faces.values.flatMap { listOfNotNull(it.features, it.brows, it.tongue) } +
            listOf(parts.blink, parts.closed, parts.cline) + parts.talk +
            parts.closedBy.values + parts.openBy.values + parts.blinkBy.values).map { it.file }.distinct()

    companion object {
        const val DIR = "files/chessy"
        private val json = Json { ignoreUnknownKeys = true }
        fun read(text: String): ChessyPack = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Sphere(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/** A picture and where it sits on the sheet. */
@Serializable
data class Pic(val x: Int, val y: Int, val w: Int, val h: Int, val file: String)

@Serializable
data class PackLayer(
    val id: String,
    /** How far in front of the head's surface it sits (back hair behind, bangs in front): its share of the turn. */
    val z: Float,
    /** "rigid": moves as one piece with its [anchor] (the bell, the ribbons); "field": bends with the head. */
    val rig: String,
    val anchor: List<Float>? = null,
    val order: Float,
    val perface: Boolean = false,
    val pic: Pic? = null,
    /** The same picture with its edge against the swinging layers filled: shown while they swing. */
    val rim: Pic? = null,
)

@Serializable
data class PackFace(
    val kao: String = "",
    val name: String = "",
    val features: Pic? = null,
    val brows: Pic? = null,
    val tongue: Pic? = null,
    /** Where the tongue hangs from, on the Tongue face. */
    @SerialName("tongue_root") val tongueRoot: List<Float>? = null,
)

/** The live parts over a face: the blink, the mouths that talk. */
@Serializable
data class PackParts(
    val blink: Pic,
    val closed: Pic,
    /** The closed smile's line, drawn shorter about [ChessyPack.closedCx]. */
    val cline: Pic,
    val talk: List<Pic>,
    val closedBy: Map<String, Pic>,
    val openBy: Map<String, Pic>,
    val blinkBy: Map<String, Pic>,
)

/** A part on each side of her face: left, right. */
@Serializable
data class Sides(val l: Pic, val r: Pic)

/**
 * The pieces her moods are built from (`moods.json`, written by `tools/chessy/parts.py` from the pack): the Fangs'
 * and the Tongue's eyes per side and mouths, laid over the Grin her face layer wears; the blink's lid per side; each
 * brow alone, so a mood can tilt it.
 */
@Serializable
data class ChessyParts(
    val eyes: Map<String, Sides>,
    val mouths: Map<String, Pic>,
    val lids: Map<String, Sides>,
    val brows: Map<String, Sides>,
) {
    val files: List<String>
        get() = ((eyes.values + lids.values + brows.values).flatMap { listOf(it.l, it.r) } + mouths.values).map { it.file }.distinct()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun read(text: String): ChessyParts = json.decodeFromString(serializer(), text)
    }
}
