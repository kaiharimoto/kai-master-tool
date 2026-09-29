package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire format of `db.ygoprodeck.com/api/v7/cardinfo.php`. */
@Serializable
internal data class CardInfoResponse(
    val data: List<CardDto> = emptyList(),
)

/** Wire format of `checkDBVer.php`: a list of one. */
@Serializable
internal data class DbVersionDto(
    @SerialName("database_version") val databaseVersion: String = "",
    @SerialName("last_update") val lastUpdate: String = "",
)

@Serializable
internal data class CardDto(
    val id: Int,
    val name: String,
    val type: String = "",
    @SerialName("frameType") val frameType: String = "",
    @SerialName("desc") val description: String = "",
    val race: String? = null,
    val attribute: String? = null,
    val atk: Int? = null,
    val def: Int? = null,
    val level: Int? = null,
    @SerialName("linkval") val linkValue: Int? = null,
    @SerialName("linkmarkers") val linkMarkers: List<String> = emptyList(),
    val scale: Int? = null,
    val archetype: String? = null,
    @SerialName("card_images") val images: List<CardImageDto> = emptyList(),
    @SerialName("banlist_info") val banlist: BanlistDto? = null,
) {
    fun toDomain(): Card {
        // The first image is the default printing; the rest are alternate arts,
        // whose ids appear in deck files exported by other tools.
        val alternateIds = images.map { CardId(it.id) }.distinct()

        return Card(
            id = CardId(id),
            name = name,
            type = type,
            // Xyz monsters report an empty frameType on some rows; fall back to
            // the human-readable type so Extra Deck detection still works.
            frameType = frameType.ifBlank { type },
            description = description,
            race = race,
            attribute = Attribute.fromApi(attribute),
            atk = atk,
            def = def,
            level = level,
            linkValue = linkValue,
            linkMarkers = linkMarkers,
            pendulumScale = scale,
            archetype = archetype,
            imageUrl = images.firstOrNull()?.imageUrl,
            imageUrlSmall = images.firstOrNull()?.imageUrlSmall,
            tcgBanStatus = BanStatus.fromApi(banlist?.tcg),
            ocgBanStatus = BanStatus.fromApi(banlist?.ocg),
            alternateIds = alternateIds,
        )
    }
}

@Serializable
internal data class CardImageDto(
    val id: Int,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("image_url_small") val imageUrlSmall: String? = null,
)

@Serializable
internal data class BanlistDto(
    @SerialName("ban_tcg") val tcg: String? = null,
    @SerialName("ban_ocg") val ocg: String? = null,
)
