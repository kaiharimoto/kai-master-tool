package com.kaiharimoto.mastertool.core.ai.eval

/**
 * A decklist as a picture (Phase I, I.6; F1: "decklists read from pictures"; A.md: "pictures follow when a picture set
 * can be made reproducibly"): its rows and the look it is drawn in. The app draws it at run time, so the set is the same
 * twenty lists on every machine, nothing is downloaded or kept, and the key is the rows the picture was drawn from.
 */
data class DecklistPicture(val look: Look, val title: String, val sections: List<Section>) {
    /** How a list comes to someone: a phone's notes, a forum post, a sheet, a photo of a screen. */
    enum class Look {
        /** Typed into a notes app: count first, sans, a heading per section. */
        NOTES,

        /** A forum post: the name first, then "x3", serif. */
        NAME_FIRST,

        /** Main Deck on the left, Extra and Side on the right, monospaced. */
        COLUMNS,

        /** A phone in dark mode, narrow, long names wrapping onto a second line. */
        DARK,

        /** A form: a quantity column and a name column, ruled, each section a band. */
        SHEET,

        /** The notes photographed: turned a few degrees, the paper grey, specks on it. */
        PHOTO,

        /** The notes at six tenths of the size, as a thumbnail is. */
        SMALL,
    }

    data class Section(val name: String, val rows: List<Pair<Int, String>>)

    /** The key: each card with every copy the picture lists, summed over its sections. */
    val expected: Map<String, Int>
        get() = sections.flatMap { it.rows }.groupBy({ it.second }, { it.first }).mapValues { (_, n) -> n.sum() }
}

object DecklistPictures {
    private fun main(vararg rows: Pair<Int, String>) = DecklistPicture.Section("Main Deck", rows.toList())
    private fun extra(vararg rows: Pair<Int, String>) = DecklistPicture.Section("Extra Deck", rows.toList())
    private fun side(vararg rows: Pair<Int, String>) = DecklistPicture.Section("Side Deck", rows.toList())

    private val looks = DecklistPicture.Look.entries

    /** The twenty lists, each a real deck's names spelled as printed, in the looks in turn. */
    val all: List<DecklistPicture> = listOf(
        "Snake-Eye" to listOf(
            main(3 to "Snake-Eye Ash", 3 to "Snake-Eyes Poplar", 2 to "Snake-Eye Oak", 1 to "Snake-Eye Birch", 2 to "Diabellstar the Black Witch", 2 to "Original Sinful Spoils - Snake-Eye", 1 to "WANTED: Seeker of Sinful Spoils", 1 to "Fiendsmith Engraver", 3 to "Ash Blossom & Joyous Spring", 3 to "Infinite Impermanence", 2 to "Called by the Grave"),
            extra(1 to "Snake-Eyes Flamberge Dragon", 1 to "Promethean Princess, Bestower of Flames", 1 to "I:P Masquerena", 1 to "S:P Little Knight", 1 to "Moon of the Closed Heaven"),
        ),
        "Yubel" to listOf(
            main(3 to "Yubel", 1 to "Spirit of Yubel", 2 to "Samsara D Lotus", 3 to "Nightmare Throne", 3 to "Maxx \"C\"", 2 to "Effect Veiler", 1 to "Called by the Grave"),
            extra(1 to "Yubel - Terror Incarnate", 1 to "Yubel - The Ultimate Nightmare", 1 to "Saryuja Skull Dread"),
        ),
        "Labrynth" to listOf(
            main(1 to "Lady Labrynth of the Silver Castle", 2 to "Arianna the Labrynth Servant", 2 to "Ariane the Labrynth Servant", 1 to "Lovely Labrynth of the Silver Castle", 3 to "Labrynth Labyrinth", 3 to "Welcome Labrynth", 2 to "Big Welcome Labrynth", 1 to "Labrynth Chandraglier", 2 to "Labrynth Stovie Torbie", 1 to "Labrynth Cooclock"),
            side(3 to "Dimension Shifter", 2 to "Cosmic Cyclone"),
        ),
        "Tenpai" to listOf(
            main(3 to "Tenpai Dragon Paidra", 3 to "Tenpai Dragon Chundra", 3 to "Tenpai Dragon Fadra", 3 to "Sangen Summoning", 2 to "Sangen Kaimen", 3 to "Ash Blossom & Joyous Spring", 2 to "Triple Tactics Talent"),
            extra(1 to "Sangenpai Bident Dragon", 1 to "Sangenpai Transcendent Dragon", 1 to "Borreload Savage Dragon", 1 to "Baronne de Fleur"),
        ),
        "Kashtira" to listOf(
            main(3 to "Kashtira Unicorn", 3 to "Kashtira Fenrir", 2 to "Kashtira Riseheart", 1 to "Kashtira Ogre", 3 to "Pressured Planet Wraitsoth", 2 to "Kashtira Birth", 1 to "Kashtiratheosis", 3 to "Infinite Impermanence"),
            extra(2 to "Kashtira Arise-Heart", 1 to "Kashtira Shangri-Ira", 1 to "Divine Arsenal AA-ZEUS - Sky Thunder"),
        ),
        "Branded" to listOf(
            main(3 to "Fallen of Albaz", 3 to "Aluber the Jester of Despia", 2 to "Blazing Cartesia, the Virtuous", 1 to "Guardian Chimera", 3 to "Branded Fusion", 2 to "Branded in Red", 1 to "Branded Opening"),
            extra(1 to "Mirrorjade the Iceblade Dragon", 2 to "Albion the Branded Dragon", 1 to "Lubellion the Searing Dragon", 1 to "Granguignol the Dusk Dragon", 1 to "Titaniklad the Ash Dragon", 1 to "Brigrand the Glory Dragon"),
        ),
        "Maliss" to listOf(
            main(3 to "Maliss <P> Dormouse", 3 to "Maliss <P> White Rabbit", 2 to "Maliss <P> March Hare", 3 to "Maliss in Underground", 1 to "Maliss <C> GWC-06", 1 to "Maliss <C> TB-11"),
            extra(1 to "Maliss <Q> Red Ransom", 1 to "Maliss <Q> White Binder", 1 to "Maliss <Q> Hearts Crypter", 1 to "Accesscode Talker"),
        ),
        "Blue-Eyes" to listOf(
            main(3 to "Blue-Eyes White Dragon", 3 to "Blue-Eyes Alternative White Dragon", 3 to "Sage with Eyes of Blue", 2 to "The White Stone of Ancients", 1 to "The White Stone of Legend", 3 to "Dictator of D.", 3 to "Bingo Machine, Go!!!", 2 to "Chaos Form", 1 to "Roar of the Blue-Eyed Dragons", 1 to "True Light"),
            extra(1 to "Blue-Eyes Jet Dragon", 1 to "Blue-Eyes Spirit Dragon"),
        ),
        "Sky Striker" to listOf(
            main(1 to "Sky Striker Ace - Raye", 2 to "Sky Striker Ace - Roze", 3 to "Sky Striker Mobilize - Engage!", 2 to "Sky Striker Mecha - Hornet Drones", 2 to "Sky Striker Mecha - Widow Anchor", 1 to "Sky Striker Maneuver - Afterburners!", 1 to "Sky Striker Airspace - Area Zero"),
            extra(1 to "Sky Striker Ace - Kagari", 2 to "Sky Striker Ace - Shizuku", 1 to "Sky Striker Ace - Hayate", 1 to "Sky Striker Ace - Kaina", 1 to "Sky Striker Ace - Zeke"),
        ),
        "Runick" to listOf(
            main(3 to "Runick Fountain", 3 to "Runick Tip", 2 to "Runick Destruction", 2 to "Runick Flashing Fire", 3 to "Runick Golden Droplet", 1 to "Runick Allure", 2 to "Droll & Lock Bird", 3 to "Pot of Prosperity"),
            extra(1 to "Hugin the Runick Wings", 1 to "Munin the Runick Wings", 1 to "Geri the Runick Fangs", 1 to "Freki the Runick Fangs", 1 to "Sleipnir the Runick Mane"),
        ),
        "Tearlaments" to listOf(
            main(3 to "Tearlaments Scheiren", 1 to "Tearlaments Reinoheart", 3 to "Tearlaments Merrli", 2 to "Tearlaments Havnis", 1 to "Primeval Planet Perlereino", 1 to "Tearlaments Sulliek", 2 to "Mudora the Sword Oracle", 1 to "Agido the Ancient Sentinel", 1 to "Keldo the Sacred Protector", 1 to "Kelbek the Ancient Vanguard", 1 to "Foolish Burial"),
            extra(1 to "Tearlaments Kitkallos", 1 to "Tearlaments Rulkallos", 1 to "Mudragon of the Swamp"),
        ),
        "Spright" to listOf(
            main(3 to "Spright Blue", 3 to "Spright Jet", 2 to "Spright Red", 2 to "Spright Carrot", 3 to "Spright Starter", 1 to "Small World", 2 to "Ghost Belle & Haunted Mansion"),
            extra(2 to "Spright Elf", 1 to "Spright Sprind", 1 to "Gigantic \"Champion\" Sargas", 1 to "Crystron Halqifibrax", 1 to "Linkuriboh"),
        ),
        "Centur-Ion" to listOf(
            main(3 to "Centur-Ion Primera", 2 to "Centur-Ion Trudea", 2 to "Centur-Ion Auxila", 3 to "Stand Up Centur-Ion!", 3 to "Mulcharmy Fuwalos", 1 to "Monster Reborn", 2 to "Book of Moon"),
            extra(1 to "Centur-Ion Legatia", 1 to "Centur-Ion Emeth VI", 1 to "Apollousa, Bow of the Goddess"),
        ),
        "Swordsoul" to listOf(
            main(3 to "Swordsoul of Mo Ye", 2 to "Swordsoul of Taia", 1 to "Swordsoul Strategist Longyuan", 3 to "Swordsoul Emergence", 3 to "Incredible Ecclesia, the Virtuous", 1 to "Psy-Framegear Gamma", 1 to "Psy-Frame Driver"),
            extra(1 to "Swordsoul Sinister Sovereign - Qixing Longyuan", 1 to "Swordsoul Grandmaster - Chixiao", 1 to "Swordsoul Supreme Sovereign - Chengying", 1 to "Baxia, Brightness of the Yang Zing"),
        ),
        "Fiendsmith" to listOf(
            main(3 to "Fiendsmith Engraver", 2 to "Fiendsmith's Tract", 3 to "Mulcharmy Purulia", 2 to "Gold Sarcophagus", 1 to "One for One", 3 to "Forbidden Droplet"),
            extra(1 to "Fiendsmith's Requiem", 1 to "Fiendsmith's Sequence", 1 to "Knightmare Unicorn", 1 to "Knightmare Phoenix", 1 to "Underworld Goddess of the Closed World"),
            side(2 to "Retaliating \"C\"", 1 to "Harpie's Feather Duster", 1 to "Raigeki"),
        ),
        "Hand traps" to listOf(
            main(3 to "Ash Blossom & Joyous Spring", 3 to "Infinite Impermanence", 2 to "Effect Veiler", 3 to "Ghost Ogre & Snow Rabbit", 2 to "Ghost Mourner & Moonlit Chill", 1 to "Nibiru, the Primal Being", 3 to "Maxx \"C\"", 1 to "Dimension Shifter"),
            side(2 to "Dimension Shifter", 2 to "Droll & Lock Bird", 2 to "Evenly Matched", 1 to "Lightning Storm"),
        ),
        "Spells" to listOf(
            main(3 to "Pot of Desires", 2 to "Pot of Extravagance", 3 to "Pot of Prosperity", 1 to "Crossout Designator", 1 to "Dark Ruler No More", 1 to "Super Polymerization", 1 to "Terraforming", 2 to "Triple Tactics Thrust"),
            side(2 to "Cosmic Cyclone", 1 to "Solemn Judgment", 2 to "Solemn Strike"),
        ),
        "Ryzeal" to listOf(
            main(3 to "Ice Ryzeal", 3 to "Thunder Ryzeal", 2 to "Node Ryzeal", 1 to "Sword Ryzeal", 2 to "Triple Tactics Talent", 3 to "Ash Blossom & Joyous Spring", 2 to "Called by the Grave"),
            extra(2 to "Ryzeal Detonator", 1 to "Number 41: Bagooska the Terribly Tired Tapir", 1 to "Dingirsu, the Orcust of the Evening Star"),
        ),
        "Purrely" to listOf(
            main(3 to "Purrely", 3 to "Purrely Sharely!?", 3 to "My Friend Purrely", 2 to "Purrely Happy Memory", 2 to "Purrely Delicious Memory", 2 to "Infinite Impermanence"),
            extra(2 to "Epurrely Happiness", 2 to "Epurrely Beauty", 1 to "Epurrely Plump", 1 to "Epurrely Noir"),
        ),
        "Dark Magician" to listOf(
            main(3 to "Dark Magician", 2 to "Dark Magician Girl", 3 to "Magician's Rod", 2 to "Eternal Soul", 2 to "Dark Magical Circle", 2 to "Illusion Magic", 2 to "Effect Veiler"),
            side(2 to "Ghost Belle & Haunted Mansion", 2 to "Harpie's Feather Duster"),
        ),
    ).mapIndexed { i, (title, sections) -> DecklistPicture(looks[i % looks.size], title, sections) }
}
