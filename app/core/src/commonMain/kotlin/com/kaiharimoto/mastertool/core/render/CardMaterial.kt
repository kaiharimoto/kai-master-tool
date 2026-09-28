package com.kaiharimoto.mastertool.core.render

import com.kaiharimoto.mastertool.core.model.Card

/**
 * What a card is made of, as far as light is concerned.
 *
 * Three finishes, because a real collection has three. Ordinary cards are
 * glossy card stock in a glossy sleeve; extra-deck cards are the metallic
 * frames, and in every list anyone actually plays they are the foiled ones;
 * the back of a sleeved card is the one surface that is not trying to catch
 * the light at all.
 */
enum class CardFinish { MATTE, GLOSS, FOIL }

/**
 * A surface's response to light.
 *
 * Blinn-Phong, which is the cheapest model that still gets the one thing a
 * card does right: the highlight is a *pool that moves*, not a brightness that
 * changes. A card you tilt to read the small text is doing that because the
 * pool is in the way, and no amount of "gets brighter when tilted" reproduces
 * it.
 */
data class CardMaterial(
    val finish: CardFinish,
    /** The Blinn-Phong exponent: higher is a tighter, harder highlight. */
    val shininess: Float,
    /** How bright the highlight gets at its best angle, 0..1. */
    val specular: Float,
    /**
     * How far the highlight splits into the prismatic ramp, 0..1.
     *
     * This is the app's own colour rule, kept: colour on a card face is
     * *light*, never decoration, so it appears only inside the specular term
     * and vanishes with it.
     */
    val iridescence: Float,
    /** How much the grazing-angle rim lights up, 0..1. */
    val rim: Float,
    /**
     * How much varnish is over the finish, 0..1 — the broad lobe's strength.
     *
     * Printed card stock is lacquered, and the lacquer is smooth where the
     * stock under it is not, so it answers the light over a very wide range of
     * angles where the finish itself answers over a narrow one. That is why a
     * matte card still has a sheen when you tilt it, and it is what stops a
     * highlight existing only at the one geometry that lines up. `Shading.of`
     * has the measurement that made this necessary rather than nice.
     *
     * A sleeve has the most of it, because a sleeve *is* a sheet of plastic
     * over the card. A foil has the least, because the holographic layer is the
     * thing you are meant to see.
     */
    val coat: Float,
    /**
     * How directional the finish is: 0 is an even polish, 1 is a grating.
     *
     * Foil is not a shinier card, it is a *combed* one — a holographic stock is
     * ruled with parallel grooves, and a ruled surface answers the light with a
     * **streak across the grooves** rather than with a round pool. That is the
     * whole read, and `docs/classic/AAA.md` #21 calls it the single change that would
     * make foils look like foil. Two cards with the same `shininess` and the
     * same `specular` and different values here are two materials; today they
     * were two copies of one.
     *
     * It changes the highlight's *shape* and deliberately not its brightness —
     * `docs/classic/DESIGN.md` §7 is that the pool moves rather than brightens, and an
     * anisotropic lobe that also got brighter would be the anti-pattern wearing
     * a physics argument. [Shade.streak] carries the shape out.
     *
     * Trailing and zero by default, so every existing material is unchanged to
     * the bit and `GoldenStageTest` does not move.
     */
    val anisotropy: Float = 0f,
) {
    companion object {
        /** Sleeved card stock: the default, and most of the table. */
        val Gloss = CardMaterial(
            finish = CardFinish.GLOSS,
            coat = 0.15f,
            shininess = 26f,
            specular = 0.18f,
            iridescence = 0f,
            rim = 0.5f,
        )

        /** A metallic frame: the extra deck, and it should look like it. */
        val Foil = CardMaterial(
            finish = CardFinish.FOIL,
            coat = 0.13f,
            shininess = 44f,
            specular = 0.30f,
            iridescence = 0.8f,
            rim = 0.75f,
            // Ruled stock. Against a shininess of 44 this gives a lobe about
            // three times longer than it is wide — see `Shading.of`, where the
            // number comes from the exponents rather than from taste.
            anisotropy = 0.8f,
        )

        /** The back of a sleeve, which is the quietest surface on the table. */
        val Sleeve = CardMaterial(
            finish = CardFinish.MATTE,
            coat = 0.20f,
            shininess = 10f,
            specular = 0.12f,
            iridescence = 0f,
            rim = 0.35f,
        )
    }
}

/** Which stock a given card is printed on. */
object CardStock {

    /**
     * The rule, in one place: you are looking at a sleeve back, a metallic
     * frame, or ordinary card stock.
     *
     * Deliberately derived from the card rather than assigned at random. A foil
     * that lands on a different card every time you reopen the table is a
     * particle effect wearing a material's clothes.
     */
    fun of(card: Card?, faceUp: Boolean): CardMaterial = when {
        !faceUp -> CardMaterial.Sleeve
        card != null && card.isExtraDeck -> CardMaterial.Foil
        else -> CardMaterial.Gloss
    }
}
