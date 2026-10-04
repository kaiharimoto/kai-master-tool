package com.kaiharimoto.mastertool.core.data

import com.kaiharimoto.mastertool.core.db.CardEntity
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId

/**
 * Conversions between the SQLite rows and the domain model.
 *
 * List-valued columns are stored as comma-separated text. That is enough for
 * link markers and alternate passcodes, neither of which is ever queried on.
 */
internal object CardMapper {

    private const val SEPARATOR = ","

    fun toDomain(row: CardEntity): Card = Card(
        id = CardId(row.id.toInt()),
        name = row.name,
        type = row.type,
        frameType = row.frameType,
        description = row.description,
        race = row.race,
        attribute = runCatching { Attribute.valueOf(row.attribute) }.getOrDefault(Attribute.UNKNOWN),
        atk = row.atk?.toInt(),
        def = row.def?.toInt(),
        level = row.level?.toInt(),
        linkValue = row.linkValue?.toInt(),
        linkMarkers = row.linkMarkers.splitList(),
        pendulumScale = row.pendulumScale?.toInt(),
        archetype = row.archetype,
        imageUrl = row.imageUrl,
        imageUrlSmall = row.imageUrlSmall,
        tcgBanStatus = row.tcgBanStatus.toBanStatus(),
        ocgBanStatus = row.ocgBanStatus.toBanStatus(),
        alternateIds = row.alternateIds.splitList()
            .mapNotNull { it.toIntOrNull() }
            .map(::CardId),
        konamiId = row.konamiId?.toInt(),
        tcgDate = row.tcgDate,
        ocgDate = row.ocgDate,
        formats = row.formats.splitList(),
        genesysPoints = row.genesysPoints?.toInt(),
    )

    fun joinIds(ids: List<CardId>): String = ids.joinToString(SEPARATOR) { it.value.toString() }

    fun joinStrings(values: List<String>): String =
        values.joinToString(SEPARATOR) { it.replace(SEPARATOR, " ") }

    fun splitIds(raw: String): List<CardId> =
        raw.splitList().mapNotNull { it.toIntOrNull() }.map(::CardId)

    private fun String.splitList(): List<String> =
        if (isBlank()) emptyList() else split(SEPARATOR).map(String::trim).filter(String::isNotEmpty)

    private fun String.toBanStatus(): BanStatus =
        runCatching { BanStatus.valueOf(this) }.getOrDefault(BanStatus.UNLIMITED)
}
