package com.kaiharimoto.mastertool.core.ai.rules

/**
 * Yugipedia's API, captured live on 2026-09-30 for `Wikitext` and `Yugipedia`
 * (text CC BY-SA 4.0, yugipedia.com). [ASH_RULINGS] is the wikitext of
 * `Card_Rulings:Ash_Blossom_&_Joyous_Spring`, trimmed from 40 `{{Ruling}}` templates
 * to 19 (the first 17 and the last 2) with the OCG bullet rulings and the references
 * kept whole. [SNAKE_EYE_PLAYING_STYLE] is section 5 of `Snake-Eye` ("Playing style"
 * and its subsections) with the recommended-cards decklist cut to three entries in
 * each of its first three groups. [SNAKE_EYE_SECTIONS] is `prop=sections` verbatim.
 * The test wraps the wikitext in the API's JSON itself; the error body is verbatim.
 */
object YugipediaFixture {
    val ASH_RULINGS = """
{{Navigation}}

== OCG Rulings ==
* The effect of "[[Ash Blossom & Joyous Spring]]" is a [[Quick Effect]] that activates in the hand.<ref name="No.12950">[http://www.db.yugioh-card.com/yugiohdb/faq_search.action?ope=4&cid=12950  Konami OCG Card Database]: Ash Blossom & Joyous Spring</ref>
* Discarding "[[Ash Blossom & Joyous Spring]]" is a [[cost]] to activate its effect.<ref name="No.12950"/>
* It cannot be activated during the [[Damage Step]].<ref name="No.12950"/>
* The effect of "[[Ash Blossom & Joyous Spring]]" does not target. It is activated directly in Chain to the activation of a Spell/Trap Card, the activation of a Spell/Trap Card's effect, or the activation of a monster effect, that includes any of the "●" effects.<ref name="No.12950"/>

=== Q&A Rulings ===
{{Ruling
| Q = If you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain to the activation of the card "[[Macro Cosmos]]", which effects are negated?
| A = The effect of "[[Ash Blossom & Joyous Spring]]" negates the effect it was directly Chained to. Therefore, in this case, the effect that Special Summons 1 "[[Helios - The Primordial Sun]]" is negated, but the effect that banishes any card sent to the Graveyard is not negated.
| cite = 7315
}}

{{Ruling
| Q = When your opponent activates "[[Foolish Burial]]" while the effect of "[[Dimensional Fissure]]" is applying, can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = While the effect of "[[Dimensional Fissure]]" is applying, any monster sent to the Graveyard is banished instead. Since the effect of "[[Foolish Burial]]" is an effect that sends a card from the Deck the Graveyard, even if your opponent activates "[[Foolish Burial]]" while the effect of "[[Dimensional Fissure]]" is applying, you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 20543
}}

{{Ruling
| Q = If you activate the effect of "[[Stardust Charge Warrior]]" that draws 1 card while the effect of "[[Skill Drain]]" is applying, can your opponent activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = In this case, since the effects of "[[Stardust Charge Warrior]]" are already being negated by "[[Skill Drain]]", your opponent cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 12677
}}

{{Ruling
| Q = When your opponent activates the effect of an "[[Ankuriboh]]" in the Graveyard while they have "[[Monster Reborn]]" in their Graveyard, can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect of an "[[Ankuriboh]]" in the Graveyard, you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. (Whether "[[Monster Reborn]]" is in the Graveyard or not makes no difference.)
| cite = 22475
}}

{{Ruling
| Q = When your opponent activates the effect of "[[Ascator, Dawnwalker]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect of "[[Ascator, Dawnwalker]]", you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. If you do, "[[Ascator, Dawnwalker]]" remains in your opponent's hand, but "''You cannot Special Summon monsters from the Extra Deck the turn you activate this effect, except Synchro Monsters''" is still applied.
| cite = 22687
}}

{{Ruling
| Q = When your opponent activates "[[Book of Eclipse]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates "[[Book of Eclipse]]", you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. (Whether a face-up monster is in your Monster Zone or not when your opponent activates "[[Book of Eclipse]]" makes no difference.)
| cite = 11500
}}

{{Ruling
| Q = When your opponent activates the effect of "[[Card Trooper]]" that increases its ATK, can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect of "[[Card Trooper]]" that increases its ATK, you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 20585
}}

{{Ruling
| Q = When my opponent activates the effect of "[[Cross-Sheep]]", can I chain the effect of "[[Ash Blossom & Joyous Spring]]" from my hand?
| A = When your opponent activates the effect of "[[Cross-Sheep]]", you can chain the effect of "[[Ash Blossom & Joyous Spring]]". (It does not matter whether or not "[[Cross-Sheep]]" is pointing to a Ritual Monster when its effect is activated.)
| cite = 22886
| trans_cite = ocg-11-12-19-rulings-update
}}

{{Ruling
| Q = When your opponent activates "[[Crusadia Vanguard]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates "[[Crusadia Vanguard]]" by Tributing 1 "[[Crusadia]]" or "[[World Legacy]]" monster, since the effect that Special Summons 1 "[[Crusadia]]" or "[[World Legacy]]" monster from the Deck or Graveyard is applied when the activation of the card resolves, you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. If a monster was not Tributed when the card "[[Crusadia Vanguard]]" was activated, you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 12945
}}

{{Ruling
| Q = When your opponent activates the effect of "[[Cyber-Stein]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect of "[[Cyber-Stein]]", you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 20545
}}

{{Ruling
| Q = When my opponent activates the effect of a "[[Danger!? Jackalope?]]" in their hand, can I chain the effect of "[[Ash Blossom & Joyous Spring]]" in my hand?
| A = When your opponent activates the effect of a "[[Danger!? Jackalope?]]" in their hand, you can chain the effect of "[[Ash Blossom & Joyous Spring]]". In that case, the effect of "[[Danger!? Jackalope?]]" is negated, and it remains in your opponent's hand.
| cite = 22838
| trans_cite = ocg-10-01-19-rulings-update
}}

{{Ruling
| Q = When your opponent activates "[[Dark Magical Circle]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates "[[Dark Magical Circle]]", you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. In that case, the effect of your opponent's "[[Dark Magical Circle]]" that is applied at the time of the activation is negated and they do not look at the top 3 cards of their Deck. Since the activation of the card was performed, "[[Dark Magical Circle]]" remains in the Spell & Trap Zone. Also, if the effect of "[[Dark Magical Circle]]" is activated when "[[Dark Magician]]" is Normal or Special Summoned, you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 20542
}}

{{Ruling
| Q = When your opponent activates "[[Dark Sacrifice]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates "[[Dark Sacrifice]]", you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 22441
}}

{{Ruling
| Q = When your opponent activates "[[Enma's Judgment]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates "[[Enma's Judgment]]", you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. (Whether your opponent has less than 5 or 5+ Zombie monsters in their Graveyard when they activate "[[Enma's Judgment]]" makes no difference.)
| cite = 22372
}}

{{Ruling
| Q = When your opponent activates the effect of "[[Fairy Tail - Luna]]" that returns a monster on the field to the hand, can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect that returns both "[[Fairy Tail - Luna]]" and the targeted monster to the hand, you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 11022
}}

{{Ruling
| Q = When your opponent activates the effect of "[[Generaider Boss Room]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect of "[[Generaider Boss Room]]", you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 22759
}}

{{Ruling
| Q = When your opponent activates "[[Gold Sarcophagus]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates "[[Gold Sarcophagus]]", you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 11993
}}

{{Ruling
| Q = When your opponent activates the effect of "[[Zaborg the Mega Monarch]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent activates the effect of "[[Zaborg the Mega Monarch]]", you cannot activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain.
| cite = 20550
}}

{{Ruling
| Q = When your opponent's "[[Zoodiac Drident]]" with "[[Zoodiac Ratpier]]" as Xyz Material activates the effect "●" by detaching "[[Zoodiac Ratpier]]", can you activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain?
| A = When your opponent's "[[Zoodiac Drident]]" activates the effect "●" gained by the effect of "[[Zoodiac Ratpier]]", you can activate the effect of "[[Ash Blossom & Joyous Spring]]" in Chain. In that case, the effect "●" that was activated by your opponent is negated, but the "[[Zoodiac Drident]]" that activated the effect remains face-up in the Monster Zone and its other effects are not negated.
| cite = 20546
}}

== References ==
<references/>

""".trim()

    val SNAKE_EYE_PLAYING_STYLE = """
== Playing style ==
"Snake-Eye" is a theme of mostly [[Level 1]] FIRE Pyro monsters, focused around placing monsters in the [[Spell & Trap Zone]] as [[Continuous Spell Card]]s, then either using them for [[cost]]s for their effects, or Special Summoning them. The archetype is generally played as a [[Link Monster|Link]]-heavy combo Deck.

The Level 1 monsters all have an advantage-giving effect, and each one (except "[[Snake-Eyes Poplar]]") can send itself and another face-up cards to the GY to Special Summon another member of the archetype from the hand or Deck. The main boss of the theme is "[[Snake-Eyes Flamberge Dragon]]", which can place a monster from either GY to its owner's Spell & Trap Zone, and on the opposing turn, can Special Summon a monster from either S/T Zone to the controller's field. If it is sent to the GY, it can summon two Level 1 FIRE monsters from the GY.

Supporting Spells & Traps include:
* "[[Sinful Spoils of Subversion - Snake-Eye|Sinful Spoils of Subversion]]" places 1 monster into the Spell & Trap Zone, acting as generic removal that can be searched by the archetype.
* "[[Divine Temple of the Snake-Eye]]" places a monster from GY to the Spell & Trap Zone, and can Special Summon a monster treated as a Continuous Spell on the opponent's turn.
* "[[Original Sinful Spoils - Snake-Eye|Original Sinful Spoils]]" (''OCG'' / Traditional Format) sends 1 face-up card to the GY to Special Summon a Level 1 FIRE monster.
* "[[Startling Stare of the Snake-Eyes]]" is a trap that can either place a monster into the Spell & Trap Zone or summon one from there, but requires the player to control 2+ Levels of "Snake-Eye" monsters to activate.

=== External support ===
The theme benefits heavily from: 
* Various Level 1, FIRE, and Pyro support cards, such as "[[Bonfire]]", "[[One for One]]", and "[[Promethean Princess, Bestower of Flames]]". Less often played cards include "[[Flametongue the Burning Blade]]" and the "[[Volcanic]]" support from ''[[Duelist Pack: Duelists of Explosion]]. 
** The "[[Fire King]]" support released in ''[[Structure Deck: Fire Kings]]''. 
** With the same summon condition as "Snake-Eyes Poplar", "[[Agnimal Candle]]" allows for the deck to make Synchro plays, such as summoning "[[Snake-Eyes Vengeance Dragon]]" through it and one of the many Level 8 monsters played in "Snake-Eye".
* "[[Azamina]]", another "[[Sinful Spoils]]"-related archetype that can search "Original Sinful Spoils" while getting an omni-negate on field.
* The "[[Fiendsmith]]" cards, which can efficiently bridge with "Snake-Eye" and vice-versa, allowing for a consistent strategy.
* By including Level 4 monsters, such as "[[Summoner Monk]]" or "[[Surfacing Big Jaws]]" with "[[Drake Shark]]", "[[Infernal Flame Banshee]]" can be used to search (and thus summon in the case of "Poplar") key Snake-Eye cards. Additionally, "Banshee" can be summoned without the use of the Normal Summon via "[[Superheavy Samurai Prodigy Wakaushi]]" and its standard package.
* "[[Millennium]]" cards can be used to easily get monsters as Continuous Spells on to your side of the field, setting up for the summon of "[[Snake-Eyes Doomed Dragon]]" as well as the potential of [[Rank 8 Monster Cards|Rank 8]] monsters by extension.
* The "[[Centur-Ion]]" cards can provide the deck easy access to the materials required to summon either "Snake-Eyes Doomed Dragon" or "[[Snake-Eyes Vengeance Dragon]]", while also providing a negate in the form of "[[Centur-Ion True Awakening]]".

===Sample combo===
The "Snake-Eye" strategy can get going by simply [[Summon]]ing "[[Snake-Eye Ash]]", whether by [[Normal Summon]] or a card that can [[Special Summon]] it from elsewhere (i.e. "[[Original Sinful Spoils - Snake-Eye]]" or "[[One for One]]").

The following combo focuses on setting up several points of interaction, as well as follow-up plays from "Ash" and "[[Snake-Eye Oak]]" after "[[Snake-Eyes Flamberge Dragon]]" is sent to the GY. It is capable of playing through "[[Droll & Lock Bird]]", as it would only prevent "[[Snake-Eyes Poplar]]" from searching for a "Snake-Eye" Spell/Trap after "Ash" searches for it.

# Placing "[[I:P Masquerena]]" in the [[Spell & Trap Zone]] with "[[Snake-Eyes Flamberge Dragon]]" so "Flamberge Dragon" can Special Summon it during the opponent's turn for a Link Summon (usually "[[Apollousa, Bow of the Goddess]]" for monster negates or "[[S:P Little Knight]]" for monster removal).
# "[[Promethean Princess, Bestower of Flames]]" in the GY to destroy one of the player's FIRE monsters and one of the opponent's monsters to Special Summon itself after the opponent Summons a monster. 
# "[[Amphibious Swarmship Amblowhale]]" can act as both follow-up and removal, which can be triggered by the effect of "Promethean Princess".

{{Combo steps
| opening = "[[Snake-Eye Ash]]"
| end     = "[[Amphibious Swarmship Amblowhale]]" and "[[Snake-Eyes Flamberge Dragon]]" in the Monster Zones + "[[I:P Masquerena]]" in the Spell & Trap Zone + "[[Promethean Princess, Bestower of Flames]]" in the GY
| steps   = 
# Summon "[[Snake-Eye Ash]]".
# Activate "Ash" to search for "[[Snake-Eyes Poplar]]".
# Activate "Poplar" to Special Summon itself.
# Activate "Poplar" to search for any "Snake-Eye" Spell/Trap.
# [[Link Summon]] "[[Linkuriboh]]" using "Poplar"
# Activate "Poplar" to place itself in the Spell & Trap Zone.
# Activate "Ash" to send itself and "Poplar" to the [[Graveyard|GY]] to Special Summon "[[Snake-Eye Oak]]".
# Activate "Oak" to Special Summon "Ash" or "Poplar" from the GY.
# Activate "Oak" to send itself and "Linkuriboh" to the GY to Special Summon "[[Snake-Eyes Flamberge Dragon]]".
# Link Summon "[[I:P Masquerena]]" using "Flamberge Dragon" and the monster "Oak" Special Summoned earlier.
# Activate "Flamberge Dragon" to Special Summon two of "Oak", "Ash", or "Poplar" from the GY.
# Link Summon "[[Promethean Princess, Bestower of Flames]]" using "Masquerena" and one of the Level 1 monsters.
# Activate "Promethean Princess" to Special Summon "Flamberge Dragon" from the GY.
# Activate "Flamberge Dragon" to place "Masquerena" from the GY to the Spell & Trap Zone.
# Link Summon "[[Amphibious Swarmship Amblowhale]]" using "Promethean Princess" and the other Level 1 monster.
}}

===Recommended cards===
{{Decklist|Recommended cards
<!--This is not an exact Decklist. Please do not add multiples or staples.-->

|effect monsters =
* [[Snake-Eye Ash]]
* [[Snake-Eye Birch]]
* [[Snake-Eye Oak]]
|tuner monsters =
* [[Anu, Knight of the Winter Raven]]
* [[Mart, Knight of the Winter Raven]]
* [[Sinful Spoils of the Winter Raven's Forest]]
|fusion monsters =
* [[Snake-Eyes Doomed Dragon]]
* [[Azamina Ilia Silvia]]
* [[Azamina Mu Rcielago]]
}}

===Weaknesses===
* Due to the archetype being reliant on using [[Graveyard]] to recur resources and facilitate its grind game, anti-GY floodgates such as "[[Necrovalley]]", "[[Macro Cosmos]]", or "[[Dimension Shifter]]" can stop most of the grind game the deck has to offer.
* As the Deck is focused on monster interruption and mostly lacks interaction with [[Spell Card|Spell]] and [[Trap Card]] unless using external staples, the deck is ironically weak against [[Chain Burn]] Decks that can easily exploit the Deck's swarming capabilities to deal game-ending damage in a single turn with cards such as "[[Ceasefire]]" or "[[Just Desserts]]".
{{Archseries navbox}}
{{Sinful Spoils}}
[[Category:TCG and OCG archetypes]]

""".trim()

    const val SNAKE_EYE_SECTIONS = """{"parse":{"title":"Snake-Eye","pageid":992390,"sections":[{"toclevel":1,"level":"2","line":"Lore","number":"1","index":"1","fromtitle":"Snake-Eye","byteoffset":1307,"anchor":"Lore"},{"toclevel":1,"level":"2","line":"Design","number":"2","index":"2","fromtitle":"Snake-Eye","byteoffset":1472,"anchor":"Design"},{"toclevel":2,"level":"3","line":"Etymology","number":"2.1","index":"3","fromtitle":"Snake-Eye","byteoffset":1485,"anchor":"Etymology"},{"toclevel":2,"level":"3","line":"Appearance","number":"2.2","index":"4","fromtitle":"Snake-Eye","byteoffset":2143,"anchor":"Appearance"},{"toclevel":1,"level":"2","line":"Playing style","number":"3","index":"5","fromtitle":"Snake-Eye","byteoffset":2324,"anchor":"Playing_style"},{"toclevel":2,"level":"3","line":"External support","number":"3.1","index":"6","fromtitle":"Snake-Eye","byteoffset":4003,"anchor":"External_support"},{"toclevel":2,"level":"3","line":"Sample combo","number":"3.2","index":"7","fromtitle":"Snake-Eye","byteoffset":5827,"anchor":"Sample_combo"},{"toclevel":2,"level":"3","line":"Recommended cards","number":"3.3","index":"8","fromtitle":"Snake-Eye","byteoffset":8576,"anchor":"Recommended_cards"},{"toclevel":2,"level":"3","line":"Weaknesses","number":"3.4","index":"9","fromtitle":"Snake-Eye","byteoffset":12782,"anchor":"Weaknesses"}]}}"""

    const val MISSING = """{"error":{"code":"missingtitle","info":"The page you specified doesn't exist.","docref":"See https://yugipedia.com/api.php for API usage. Subscribe to the mediawiki-api-announce mailing list at &lt;https://lists.wikimedia.org/mailman/listinfo/mediawiki-api-announce&gt; for notice of API deprecations and breaking changes."}}"""
}
