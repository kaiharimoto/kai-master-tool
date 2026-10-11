package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.prep.IsoDate

/**
 * The field kept by date (Phase G part two, G2.3; `docs/phases/G2.md`): every field read — today's or a past day's — kept on
 * this device as `<data>/field/archive/<day>-<format>-t<tier>-d<days>.json`, so a past format reads the same lists again,
 * offline, however YGOPRODeck changes. A device's cache like the latest field: never synced or backed up.
 */
object FieldArchive {
    const val FOLDER = "archive"

    private const val DAY_MS = 86_400_000L

    /** The day a snapshot stands for: the day it was read as of, else the day it was read. */
    fun day(s: FieldSnapshot): String = s.asOf?.take(10) ?: IsoDate.of(s.readAt.floorDiv(DAY_MS))

    /** The file a snapshot is kept in, under [FOLDER]. */
    fun name(s: FieldSnapshot): String = name(day(s), s.format, s.tier, s.days)

    fun name(day: String, format: String, tier: Int, days: Int): String = "$day-${format.uppercase()}-t$tier-d$days.json"

    /** A kept file's day, format, tier and window, read back from its [name]; null for anything else. */
    data class Key(val day: String, val format: String, val tier: Int, val days: Int)

    fun key(name: String): Key? {
        val m = Regex("""^(\d{4}-\d{2}-\d{2})-([A-Z]+)-t(\d)-d(\d+)\.json$""").matchEntire(name) ?: return null
        val (day, format, tier, days) = m.destructured
        return Key(day, format, tier.toInt(), days.toInt())
    }

    /**
     * The kept field to read for [day]: the same day, format, tier and window exactly when kept, else the newest of that format
     * kept on or before the day (said by its own day). Null when none was kept before it.
     */
    fun pick(names: Collection<String>, day: String, format: String, tier: Int? = null, days: Int? = null): Key? {
        val keys = names.mapNotNull(::key).filter { it.format == format.uppercase() }
        keys.firstOrNull { it.day == day && (tier == null || it.tier == tier) && (days == null || it.days == days) }?.let { return it }
        val limit = IsoDate.epochDay(day) ?: return null
        return keys.filter { (IsoDate.epochDay(it.day) ?: Long.MAX_VALUE) <= limit && (tier == null || it.tier == tier) }
            .maxWithOrNull(compareBy<Key> { it.day }.thenBy { it.days })
    }
}
