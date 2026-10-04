package com.kaiharimoto.mastertool.core.cards

/**
 * Yugipedia's Forbidden & Limited lists, captured live on 2026-10-04 through its MediaWiki API (text CC BY-SA 4.0,
 * yugipedia.com) for `LimitationParser`, `YugipediaLists` and `BanlistHistory`. Each page's wikitext is whole; the
 * JSON answers are verbatim.
 */
object BanlistFixture {
    /** the TCG category, `cmnamespace=0`, verbatim. */
    const val TCG_CATEGORY = """{"batchcomplete":"","query":{"categorymembers":[{"pageid":235952,"ns":0,"title":"April 2003 Lists (TCG)"},{"pageid":7953,"ns":0,"title":"April 2004 Lists"},{"pageid":7971,"ns":0,"title":"April 2005 Lists"},{"pageid":7934,"ns":0,"title":"April 2006 Lists"},{"pageid":542977,"ns":0,"title":"April 2014 Lists (TCG)"},{"pageid":332010,"ns":0,"title":"April 2015 Lists (TCG)"},{"pageid":388209,"ns":0,"title":"April 2016 Lists (TCG)"},{"pageid":608174,"ns":0,"title":"April 2019 Lists (TCG)"},{"pageid":932387,"ns":0,"title":"April 2020 Lists (TCG)"},{"pageid":1054568,"ns":0,"title":"April 2024 Lists (TCG)"},{"pageid":1121246,"ns":0,"title":"April 2025 Lists (TCG)"},{"pageid":266302,"ns":0,"title":"August 2003 Lists"},{"pageid":281959,"ns":0,"title":"August 2004 Lists"},{"pageid":414745,"ns":0,"title":"August 2016 Lists"},{"pageid":194111,"ns":0,"title":"December 2002 Lists"},{"pageid":583089,"ns":0,"title":"December 2018 Lists"},{"pageid":932411,"ns":0,"title":"December 2020 Lists"},{"pageid":933786,"ns":0,"title":"December 2022 Lists (TCG)"},{"pageid":1102255,"ns":0,"title":"December 2024 Lists (TCG)"},{"pageid":281949,"ns":0,"title":"February 2004 Lists"},{"pageid":428356,"ns":0,"title":"February 2016 Lists"},{"pageid":551311,"ns":0,"title":"February 2018 Lists"},{"pageid":933757,"ns":0,"title":"February 2022 Lists"},{"pageid":933796,"ns":0,"title":"February 2023 Lists (TCG)"},{"pageid":1170444,"ns":0,"title":"February 2026 Lists (TCG)"},{"pageid":304603,"ns":0,"title":"January 2014 Lists"},{"pageid":378547,"ns":0,"title":"January 2015 Lists (TCG)"},{"pageid":591794,"ns":0,"title":"January 2019 Lists (TCG)"},{"pageid":932374,"ns":0,"title":"January 2020 Lists (TCG)"},{"pageid":1035710,"ns":0,"title":"January 2024 Lists (TCG)"},{"pageid":194094,"ns":0,"title":"July 2002 Lists"},{"pageid":266281,"ns":0,"title":"July 2003 Lists (TCG)"},{"pageid":326009,"ns":0,"title":"July 2014 Lists (TCG)"},{"pageid":412301,"ns":0,"title":"July 2015 Lists (TCG)"},{"pageid":645580,"ns":0,"title":"July 2019 Lists (TCG)"},{"pageid":932960,"ns":0,"title":"July 2021 Lists (TCG)"},{"pageid":334523,"ns":0,"title":"June 2007 Lists"},{"pageid":498205,"ns":0,"title":"June 2017 Lists"},{"pageid":932396,"ns":0,"title":"June 2020 Lists"},{"pageid":958767,"ns":0,"title":"June 2023 Lists (TCG)"},{"pageid":333223,"ns":0,"title":"March 2007 Lists (TCG)"},{"pageid":339108,"ns":0,"title":"March 2008 Lists (TCG)"},{"pageid":328911,"ns":0,"title":"March 2009 Lists (TCG)"},{"pageid":328998,"ns":0,"title":"March 2010 Lists (TCG)"},{"pageid":330537,"ns":0,"title":"March 2011 Lists (TCG)"},{"pageid":330670,"ns":0,"title":"March 2012 Lists (TCG)"},{"pageid":311431,"ns":0,"title":"March 2013 Lists (TCG)"},{"pageid":481893,"ns":0,"title":"March 2017 Lists"},{"pageid":932927,"ns":0,"title":"March 2021 Lists"},{"pageid":23252,"ns":0,"title":"May 2002 Lists (TCG)"},{"pageid":266235,"ns":0,"title":"May 2003 Lists"},{"pageid":30725,"ns":0,"title":"May 2008 Lists"},{"pageid":560261,"ns":0,"title":"May 2018 Lists"},{"pageid":933764,"ns":0,"title":"May 2022 Lists (TCG)"},{"pageid":1192341,"ns":0,"title":"May 2026 Lists (TCG)"},{"pageid":266309,"ns":0,"title":"November 2003 Lists"},{"pageid":363381,"ns":0,"title":"November 2015 Lists"},{"pageid":544400,"ns":0,"title":"November 2017 Lists"},{"pageid":194102,"ns":0,"title":"October 2002 Lists"},{"pageid":543321,"ns":0,"title":"October 2004 Lists"},{"pageid":427920,"ns":0,"title":"October 2005 Lists"},{"pageid":414903,"ns":0,"title":"October 2013 Lists"},{"pageid":316204,"ns":0,"title":"October 2014 Lists (TCG)"},{"pageid":668216,"ns":0,"title":"October 2019 Lists (TCG)"},{"pageid":932963,"ns":0,"title":"October 2021 Lists (TCG)"},{"pageid":933766,"ns":0,"title":"October 2022 Lists (TCG)"},{"pageid":1155724,"ns":0,"title":"October 2025 Lists (TCG)"},{"pageid":428135,"ns":0,"title":"September 2006 Lists (TCG)"},{"pageid":334532,"ns":0,"title":"September 2007 Lists (TCG)"},{"pageid":340075,"ns":0,"title":"September 2008 Lists (TCG)"},{"pageid":328970,"ns":0,"title":"September 2009 Lists (TCG)"},{"pageid":132599,"ns":0,"title":"September 2010 Lists (TCG)"},{"pageid":330631,"ns":0,"title":"September 2011 Lists (TCG)"},{"pageid":330815,"ns":0,"title":"September 2012 Lists (TCG)"},{"pageid":543156,"ns":0,"title":"September 2013 Lists (TCG)"},{"pageid":526191,"ns":0,"title":"September 2017 Lists"},{"pageid":572181,"ns":0,"title":"September 2018 Lists"},{"pageid":932400,"ns":0,"title":"September 2020 Lists"},{"pageid":1012594,"ns":0,"title":"September 2023 Lists (TCG)"},{"pageid":1078122,"ns":0,"title":"September 2024 Lists (TCG)"},{"pageid":1145604,"ns":0,"title":"September 2025 Lists (TCG)"},{"pageid":1228564,"ns":0,"title":"September 2026 Lists (TCG)"}]}}"""

    /** the OCG category without `cmnamespace` (so its five subcategories, ns 14, are in it), verbatim. */
    const val OCG_CATEGORY = """{"batchcomplete":"","query":{"categorymembers":[{"pageid":519004,"ns":0,"title":"April 2000 Lists"},{"pageid":362843,"ns":0,"title":"April 2003 Lists (OCG)"},{"pageid":542972,"ns":0,"title":"April 2014 Lists (OCG)"},{"pageid":355785,"ns":0,"title":"April 2015 Lists (OCG)"},{"pageid":376333,"ns":0,"title":"April 2016 Lists (OCG)"},{"pageid":479825,"ns":0,"title":"April 2017 Lists"},{"pageid":555298,"ns":0,"title":"April 2018 Lists"},{"pageid":598030,"ns":0,"title":"April 2019 Lists (OCG)"},{"pageid":712981,"ns":0,"title":"April 2020 Lists (OCG)"},{"pageid":712982,"ns":0,"title":"April 2021 Lists"},{"pageid":710520,"ns":0,"title":"April 2022 Lists (OCG)"},{"pageid":940259,"ns":0,"title":"April 2023 Lists (OCG)"},{"pageid":1051357,"ns":0,"title":"April 2024 Lists (OCG)"},{"pageid":1117151,"ns":0,"title":"April 2025 Lists (OCG)"},{"pageid":1181031,"ns":0,"title":"April 2026 Lists (OCG)"},{"pageid":518967,"ns":0,"title":"August 1999 Lists"},{"pageid":436762,"ns":0,"title":"August 2000 Lists"},{"pageid":283895,"ns":0,"title":"February 2014 Lists"},{"pageid":714932,"ns":0,"title":"February 2021 Lists"},{"pageid":436769,"ns":0,"title":"January 2001 Lists"},{"pageid":436779,"ns":0,"title":"January 2002 Lists"},{"pageid":364048,"ns":0,"title":"January 2003 Lists"},{"pageid":378110,"ns":0,"title":"January 2015 Lists (OCG)"},{"pageid":362399,"ns":0,"title":"January 2016 Lists"},{"pageid":428850,"ns":0,"title":"January 2017 Lists"},{"pageid":546443,"ns":0,"title":"January 2018 Lists"},{"pageid":584006,"ns":0,"title":"January 2019 Lists (OCG)"},{"pageid":696113,"ns":0,"title":"January 2020 Lists (OCG)"},{"pageid":715455,"ns":0,"title":"January 2021 Lists (OCG)"},{"pageid":715456,"ns":0,"title":"January 2022 Lists"},{"pageid":715457,"ns":0,"title":"January 2023 Lists (OCG)"},{"pageid":1036421,"ns":0,"title":"January 2024 Lists (OCG)"},{"pageid":1104430,"ns":0,"title":"January 2025 Lists (OCG)"},{"pageid":1165102,"ns":0,"title":"January 2026 Lists (OCG)"},{"pageid":436790,"ns":0,"title":"July 2000 Lists"},{"pageid":7950,"ns":0,"title":"July 2003 Lists (OCG)"},{"pageid":317324,"ns":0,"title":"July 2014 Lists (OCG)"},{"pageid":355812,"ns":0,"title":"July 2015 Lists (OCG)"},{"pageid":452994,"ns":0,"title":"July 2016 Lists"},{"pageid":500321,"ns":0,"title":"July 2017 Lists"},{"pageid":563530,"ns":0,"title":"July 2018 Lists"},{"pageid":631733,"ns":0,"title":"July 2019 Lists (OCG)"},{"pageid":715485,"ns":0,"title":"July 2020 Lists (OCG)"},{"pageid":715486,"ns":0,"title":"July 2021 Lists (OCG)"},{"pageid":715487,"ns":0,"title":"July 2022 Lists (OCG)"},{"pageid":991102,"ns":0,"title":"July 2023 Lists (OCG)"},{"pageid":1066639,"ns":0,"title":"July 2024 Lists (OCG)"},{"pageid":1133459,"ns":0,"title":"July 2025 Lists (OCG)"},{"pageid":1203657,"ns":0,"title":"July 2026 Lists (OCG)"},{"pageid":362765,"ns":0,"title":"March 2004 Lists"},{"pageid":348984,"ns":0,"title":"March 2005 Lists"},{"pageid":348974,"ns":0,"title":"March 2006 Lists"},{"pageid":19524,"ns":0,"title":"March 2007 Lists (OCG)"},{"pageid":86256,"ns":0,"title":"March 2008 Lists (OCG)"},{"pageid":51823,"ns":0,"title":"March 2009 Lists (OCG)"},{"pageid":129129,"ns":0,"title":"March 2010 Lists (OCG)"},{"pageid":161607,"ns":0,"title":"March 2011 Lists (OCG)"},{"pageid":218179,"ns":0,"title":"March 2012 Lists (OCG)"},{"pageid":270718,"ns":0,"title":"March 2013 Lists (OCG)"},{"pageid":436794,"ns":0,"title":"May 2000 Lists"},{"pageid":436775,"ns":0,"title":"May 2001 Lists"},{"pageid":436784,"ns":0,"title":"May 2002 Lists (OCG)"},{"pageid":436764,"ns":0,"title":"November 2000 Lists"},{"pageid":374359,"ns":0,"title":"November 2013 Lists"},{"pageid":362820,"ns":0,"title":"October 2003 Lists"},{"pageid":313557,"ns":0,"title":"October 2014 Lists (OCG)"},{"pageid":405138,"ns":0,"title":"October 2015 Lists"},{"pageid":429159,"ns":0,"title":"October 2016 Lists"},{"pageid":525692,"ns":0,"title":"October 2017 Lists"},{"pageid":572192,"ns":0,"title":"October 2018 Lists"},{"pageid":663793,"ns":0,"title":"October 2019 Lists (OCG)"},{"pageid":716085,"ns":0,"title":"October 2020 Lists (OCG)"},{"pageid":716086,"ns":0,"title":"October 2021 Lists (OCG)"},{"pageid":716087,"ns":0,"title":"October 2022 Lists (OCG)"},{"pageid":1017869,"ns":0,"title":"October 2023 Lists (OCG)"},{"pageid":1083486,"ns":0,"title":"October 2024 Lists (OCG)"},{"pageid":1146673,"ns":0,"title":"October 2025 Lists (OCG)"},{"pageid":1228563,"ns":0,"title":"October 2026 Lists (OCG)"},{"pageid":7965,"ns":0,"title":"September 2004 Lists"},{"pageid":7949,"ns":0,"title":"September 2005 Lists"},{"pageid":7927,"ns":0,"title":"September 2006 Lists (OCG)"},{"pageid":509002,"ns":0,"title":"September 2007 Lists (OCG)"},{"pageid":42994,"ns":0,"title":"September 2008 Lists (OCG)"},{"pageid":110216,"ns":0,"title":"September 2009 Lists (OCG)"},{"pageid":329121,"ns":0,"title":"September 2010 Lists (OCG)"},{"pageid":178113,"ns":0,"title":"September 2011 Lists (OCG)"},{"pageid":237865,"ns":0,"title":"September 2012 Lists (OCG)"},{"pageid":543118,"ns":0,"title":"September 2013 Lists (OCG)"},{"pageid":714229,"ns":14,"title":"Category:Historic OCG Limitations Charts"},{"pageid":1119151,"ns":14,"title":"Category:Asian-English OCG Forbidden & Limited Lists"},{"pageid":714230,"ns":14,"title":"Category:Korean OCG Forbidden & Limited Lists"},{"pageid":714233,"ns":14,"title":"Category:Simplified Chinese OCG Forbidden & Limited Lists"},{"pageid":714235,"ns":14,"title":"Category:World Championship Forbidden & Limited Lists"}]}}"""

    /** three titles in one `prop=revisions` request — two lists and a page that does not exist — verbatim. */
    const val BATCH = """{"batchcomplete":"","query":{"pages":{"-1":{"ns":0,"title":"No Such Month 1999 Lists","missing":""},"194094":{"pageid":194094,"ns":0,"title":"July 2002 Lists","revisions":[{"contentformat":"text/x-wiki","contentmodel":"wikitext","*":"The '''July 2002 Forbidden and Limited List''' was the second Forbidden and Limited list in the ''[[TCG]]'', going into effect on July 1, [[2002]].<ref>{{cite web | url = http://www.upperdeckentertainment.com/yugioh/forbidden.asp | title = Effective July 1st, 2002, this is the list of Forbidden and Limited Cards from Konami. | publisher = Upper Deck Entertainment | archiveurl = http://web.archive.org/web/20020628090723/http://www.upperdeckentertainment.com/yugioh/forbidden.asp | archivedate = June 28, 2002 | accessdate = September 14, 2011}}</ref> This list updated the previous [[May 2002 Lists (TCG)|May 2002]] list, adding four cards from the [[Booster Pack]] ''[[Metal Raiders]]'', and was followed by the [[October 2002 Lists|October 2002 Forbidden and Limited List]].\n\n{{Limitation list\n| start_date   = July 1, 2002\n| end_date     = September 30, 2002\n| medium       = TCG\n| format       = Advanced Format\n| prev         = May 2002 Lists (TCG)\n| next         = October 2002 Lists\n| limited      = \nExodia the Forbidden One\nLeft Arm of the Forbidden One\nLeft Leg of the Forbidden One\nRight Arm of the Forbidden One\nRight Leg of the Forbidden One\nChange of Heart\nDark Hole\nMonster Reborn\nPot of Greed\nRaigeki\nMirror Force // prev::Not yet released\n| semi_limited =\nSangan // prev::Not yet released\nWitch of the Black Forest // prev::Not yet released\nCard Destruction\nHeavy Storm // prev::Not yet released\nSwords of Revealing Light\n}}\n\n== References ==\n<references />\n\n{{TCG limitation status lists}}"}]},"23252":{"pageid":23252,"ns":0,"title":"May 2002 Lists (TCG)","revisions":[{"contentformat":"text/x-wiki","contentmodel":"wikitext","*":"The '''May 2002 Forbidden and Limited List''' was the first Forbidden and Limited list to go into effect in the ''[[TCG]]'', on May 7, [[2002]].<ref>{{cite web | url = http://www.pojo.com/yu-gi-oh/PriceGuide/Banned-Restricted-List.shtml | title = Forbidden and Limited List - May 2002 | archiveurl = https://web.archive.org/web/20020603064247/http://www.pojo.com/yu-gi-oh/PriceGuide/Banned-Restricted-List.shtml | archivedate = June 3, 2002 | accessdate = March  7, 2017}}</ref> This list covered the [[booster pack]] ''[[Legend of Blue Eyes White Dragon]]'' and [[Starter Deck]]s ''[[Starter Deck: Yugi|Yugi]]'' and ''[[Starter Deck: Kaiba|Kaiba]]'', and was followed by the [[July 2002 Lists|July 2002 Forbidden and Limited List]].\n\nIn total this list contained 12 cards: 5 Monster Cards, 7 Spell Cards, 0 Trap Cards\n\n{{Limitation list\n| start_date   = May 7, 2002\n| end_date     = June 30, 2002\n| medium       = TCG\n| format       = Advanced Format\n| prev         = \n| next         = July 2002 Lists\n| limited    = \nExodia the Forbidden One // prev::Unlimited\nLeft Arm of the Forbidden One // prev::Unlimited\nLeft Leg of the Forbidden One // prev::Unlimited\nRight Arm of the Forbidden One // prev::Unlimited\nRight Leg of the Forbidden One // prev::Unlimited\nChange of Heart // prev::Unlimited\nDark Hole // prev::Unlimited\nMonster Reborn // prev::Unlimited\nPot of Greed // prev::Unlimited\nRaigeki // prev::Unlimited\n| semi_limited =\nCard Destruction // prev::Unlimited\nSwords of Revealing Light // prev::Unlimited\n}}\n\n== References ==\n<references />\n\n{{TCG limitation status lists}}"}]}}}}"""

    /** "April 2025 Lists (TCG)" as `prop=revisions` answers it, verbatim. */
    const val APRIL_2025_TCG_JSON = """{"batchcomplete":"","query":{"pages":{"1121246":{"pageid":1121246,"ns":0,"title":"April 2025 Lists (TCG)","revisions":[{"contentformat":"text/x-wiki","contentmodel":"wikitext","*":"These are the '''April 2025 Forbidden and Limited Lists''' for the ''[[TCG]]'', effective from April 7, 2025.<ref name=\"YGORG 4-6-25\">{{cite web | url = https://ygorganization.com/fiendsmithstilltippin/ | title = April 2025 TCG Forbidden & Limited List Update | author = Satchmo | website = The Organization | date = April 6, 2025 | accessdate = April 6, 2025}}</ref> The lists were first announced during the conclusion of [[Yu-Gi-Oh! Championship Series Houston 2025]] on the day prior.<ref>{{Cite web| url = https://www.youtube.com/watch?v=zW8zR1ro00E&t=34461 | title = Yu-Gi-Oh! TCG YCS Houston, TX \u2013 Day 2 | publisher = Official Yu-Gi-Oh! TRADING CARD GAME (YouTube) | date = April 6, 2025 | accessdate = April 6, 2025}}</ref>\n\n{{Limitation list\n| start_date        = April 7, 2025\n| end_date          = September 14, 2025\n| medium            = TCG\n| format            = Advanced Format\n| prev              = December 2024 Lists (TCG)\n| next              = September 2025 Lists (TCG)\n| forbidden         = \nAbyss Dweller // prev::Unlimited\nAgido the Ancient Sentinel\nApollousa, Bow of the Goddess\nArtifact Scythe\nBahamut Shark // prev::Unlimited\nBaronne de Fleur\nBarrier Statue of the Stormwinds\nBeatrice, Lady of the Eternal\nBlackwing - Gofu the Vague Shadow\nBlock Dragon\nBorreload Savage Dragon\nChaos Ruler, the Chaotic Magical Dragon\nCrystron Halqifibrax\nCurious, the Lightsworn Dominion\nCyber-Stein\nDandylion\nDjinn Releaser of Rituals\nEclipse Wyvern\nElder Entity Norden\nFairy Tail - Snow\nFiber Jar\nFiendsmith's Lacrima\nFishborg Blaster\nGimmick Puppet Nightmare\nGlow-Up Bulb\nGrinder Golem\nGuardragon Agarpain\nGuardragon Elpy\nHeavymetalfoes Electrumite\nHot Red Dragon Archfiend King Calamity\nIsolde, Two Tales of the Noble Knights\nJowgen the Spiritualist\nKashtira Arise-Heart\nKelbek the Ancient Vanguard\nKnightmare Goblin\nKnightmare Gryphon // prev::Unlimited\nKnightmare Mermaid\nLavalval Chain\nLevel Eater\nLink Decoder // prev::Unlimited\nLinkross\nLinkuriboh\nM-X-Saber Invoker\nMagical Scientist\nMaxx \"C\"\nMecha Phantom Beast Auroradon\nMind Master\nNumber 16: Shock Master\nNumber 42: Galaxy Tomahawk\nNumber 86: Heroic Champion - Rhongomyniad\nNumber 89: Diablosis the Mind Hacker\nNumber 95: Galaxy-Eyes Dark Matter Dragon\nNumber S0: Utopic ZEXAL\nOuter Entity Azathot\nPhoenixian Cluster Amaryllis\nPrank-Kids Meow-Meow-Mu\nPredaplant Verte Anaconda\nRonintoadin\nSimorgh, Bird of Sovereignty\nSpright Elf\nSPYRAL Master Plan\nSummon Sorceress\nSuperheavy Samurai Scarecrow\nSupreme King Dragon Starving Venom\nTearlaments Kitkallos\nTempest Magician\nThe Tyrant Neptune\nTopologic Gumblar Dragon\nTrue King of All Calamities\nUnion Carrier\nVictory Dragon\nWind-Up Carrier Zenmaity\nZoodiac Broadbull\nZoodiac Drident\nButterfly Dagger - Elma\nCard of Safe Return\nCold Wave\nConfiscation\nDelinquent Duo\nDimension Fusion\nGiant Trunade\nGraceful Charity\nHeavy Storm\nKaiser Colosseum\nLast Will\nMass Driver\nMetamorphosis\nMirage of Nightmare\nMystic Mine\nOriginal Sinful Spoils - Snake-Eye\nPainful Choice\nPot of Greed\nPremature Burial\nSmoke Grenade of the Thief\nSoul Charge\nThe Forceful Sentry\nAppointer of the Red Lotus\nBranded Expulsion\nImperial Order\nLast Turn\nRed Reboot\nReturn from the Different Dimension\nRoyal Oppression\nSelf-Destruct Button\nSixth Sense\nSummon Limit\nTrap Dustshoot\nUltimate Offering\nVanity's Emptiness\n| limited           = \nArchnemeses Protos\nAstrograph Sorcerer\nBystial Druiswurm // prev::Unlimited\nBystial Magnamhut\nDaigusto Emeral\nDark Grepher\nDimension Shifter // prev::Unlimited\nExodia the Forbidden One\nGem-Knight Master Diamond\nKeldo the Sacred Protector\nLeft Arm of the Forbidden One\nLeft Leg of the Forbidden One\nMaster Peace, the True Dracoslaying King // prev::Forbidden\nMathmech Circular // prev::Forbidden\nMiscellaneousaurus\nMudora the Sword Oracle\nNumber 40: Gimmick Puppet of Strings\nNumber C40: Gimmick Puppet of Dark Strings\nPhantom of Yubel\nPhantom Skyblaster\nPSY-Framegear Gamma\nPSY-Framelord Omega\nRight Arm of the Forbidden One\nRight Leg of the Forbidden One\nRyzeal Detonator // prev::Unlimited\nStriker Dragon\nSubstitoad\nSunavalon Dryas\nSunvine Healer\nT.G. Hyper Librarian\nTearlaments Havnis\nTearlaments Merrli\nTearlaments Scheiren\nTenpai Dragon Chundra\nZoodiac Ratpier\nBonfire // prev::Unlimited\nBranded Fusion\nBrilliant Fusion // prev::Forbidden\nCalled by the Grave\nCard Destruction\nCard of Demise\nChain Strike\nChange of Heart\nChaos Space\nChicken Game\nCrossout Designator // prev::Unlimited\nDivine Wind of Mist Valley\nDragonic Diagram\nFinal Countdown\nFoolish Burial\nGateway of the Six\nGold Sarcophagus\nHarpie's Feather Duster\nInfernity Launcher\nInstant Fusion\nInto the Void\nMagical Mid-Breaker Field\nMonster Gate\nMonster Reborn\nOne Day of Peace\nOne for One\nPot of Prosperity\nReasoning\nReinforcement of the Army\nSangen Kaimen\nSangen Summoning\nSekka's Light\nSet Rotation\nSky Striker Mecha - Hornet Drones\nSlash Draw\nSnatch Steal\nTerraforming\nThat Grass Looks Greener\nTriple Tactics Talent // prev::Unlimited\nZoodiac Barrage\nAnti-Spell Fragrance\nGozen Match\nMagical Explosion\nNaturia Sacred Tree\nRivalry of Warlords\nSkill Drain\nThere Can Be Only One\n| semi_limited      = \nBlack Dragon Collapserpent // prev::Limited\nExt Ryzeal // prev::Unlimited\nIce Ryzeal // prev::Unlimited\nMaliss P Dormouse // prev::Unlimited\nMaliss P White Rabbit // prev::Unlimited\nMorphing Jar // prev::Limited\nSnake-Eye Ash // prev::Limited\nSnake-Eyes Poplar // prev::Limited\nSword Ryzeal // prev::Unlimited\nUnchained Soul of Sharvara // prev::Limited\nWhite Dragon Wyverburster // prev::Limited\nLightning Storm\nMaliss in Underground // prev::Unlimited\nPurrely Delicious Memory\nPurrely Sleepy Memory\nRunick Fountain\n| no_longer_on_list = \nCyber Jar // prev::Semi-Limited\nDanger!? Jackalope? // prev::Semi-Limited\nDanger!? Tsuchinoko? // prev::Semi-Limited\nEva // prev::Semi-Limited\nPerformapal Monkeyboard // prev::Semi-Limited\n}}\n\n== References ==\n<references />\n\n{{TCG limitation status lists}}"}]}}}}"""

    /** "April 2005 Lists" (TCG; no region in the title). */
    const val APRIL_2005 = """These are the '''April 2005 Forbidden and Limited Lists''' for the ''[[TCG]]'' in effect since April 1, 2005.<ref>{{cite web | url = http://www.pojo.com/yu-gi-oh/PriceGuide/Banned-Restricted-List.shtml | title =  Forbidden and Limited List - April 2005. | archiveurl = https://web.archive.org/web/20050305050643/http://www.pojo.com/yu-gi-oh/PriceGuide/Banned-Restricted-List.shtml | archivedate = March 5, 2005 | accessdate = May 14, 2017 }}</ref>
<ref>{{cite web | url = http://entertainment.upperdeck.com/op/policy/files/en/UDEAppendixAYGO01aug2005_en.doc | title = UDE Tournament Appendix | publisher = Upper Deck Entertainment | accessdate = February 2, 2016 | archiveurl = https://web.archive.org/web/20060104203431/http://entertainment.upperdeck.com/op/policy/files/en/UDEAppendixAYGO01aug2005_en.doc | archivedate = January 4, 2006}}</ref>

{{Limitation list
| start_date   = April 1, 2005
| end_date     = September 30, 2005
| medium       = TCG
| format       = Advanced Format
| prev         = October 2004 Lists
| next         = October 2005 Lists
| forbidden    = 
Chaos Emperor Dragon - Envoy of the End
Fiber Jar // prev::Limited
Magical Scientist // prev::Limited
Makyura the Destructor // prev::Unlimited
Witch of the Black Forest
Yata-Garasu
Butterfly Dagger - Elma // prev::Limited
Change of Heart // prev::Limited
Confiscation // prev::Limited
Dark Hole
Harpie's Feather Duster
Mirage of Nightmare // prev::Limited
Monster Reborn
Painful Choice // prev::Limited
Raigeki
The Forceful Sentry // prev::Limited
Imperial Order
| limited      = 
Black Luster Soldier - Envoy of the Beginning
Breaker the Magical Warrior
Cyber Jar
Dark Magician of Chaos
D.D. Warrior Lady // prev::Unlimited
Exiled Force
Exodia the Forbidden One
Injection Fairy Lily
Jinzo
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Morphing Jar
Protector of the Sanctuary
Reflect Bounder
Right Leg of the Forbidden One
Right Arm of the Forbidden One
Sacred Phoenix of Nephthys // prev::Not yet released
Sangan // prev::Forbidden
Sinister Serpent
Tribe-Infecting Virus
Twin-Headed Behemoth
Card Destruction
Delinquent Duo // prev::Forbidden
Graceful Charity // prev::Forbidden
Heavy Storm
Lightning Vortex // prev::Not yet released
Mage Power
Mystical Space Typhoon
Pot of Greed
Premature Burial
Snatch Steal
Swords of Revealing Light
United We Stand // prev::Forbidden
Call of the Haunted
Ceasefire
Deck Devastation Virus // prev::Not yet released
Magic Cylinder
Mirror Force // prev::Forbidden
Reckless Greed
Ring of Destruction
Torrential Tribute
| semi_limited = 
Abyss Soldier // prev::Not yet released
Dark Scorpion - Chick the Yellow // prev::Unlimited
Manticore of Darkness
Marauding Captain
Night Assailant // prev::Unlimited
Vampire Lord // prev::Limited
Creature Swap
Emergency Provisions // prev::Unlimited
Level Limit - Area B // prev::Unlimited
Nobleman of Crossout
Reinforcement of the Army
Upstart Goblin // prev::Limited
Good Goblin Housekeeping // prev::Not yet released
Gravity Bind // prev::Unlimited
Last Turn
| no_longer_on_list = 
Morphing Jar 2 // prev::Semi-Limited
}}

== References ==
<references/>

{{TCG limitation status lists}}"""

    /** "April 2015 Lists (TCG)". */
    const val APRIL_2015_TCG = """These are the '''April 2015 Forbidden and Limited Lists''' for the ''[[TCG]]'' in effect since April 1, 2015.<ref>{{cite web | url = http://www.yugioh-card.com/en/limited/April_2015.html | title = Forbidden/Limited Card Lists (2015/04) | publisher = Konami | accessdate = July 21, 2015}}</ref><ref>{{cite web | url = https://ygorganization.com/tcg-new-april-1-banlist-up/ | title = <nowiki>[TCG] New April 1 banlist up</nowiki> | author = Deadborder | website = The Organization | date = March 20, 2015 | accessdate = January 7, 2016}}</ref>

{{Limitation list
| start_date   = April 1, 2015
| end_date     = July 15, 2015
| medium       = TCG
| format       = Advanced Format
| prev         = January 2015 Lists (TCG)
| next         = July 2015 Lists (TCG)
| forbidden    = 
Blaster, Dragon Ruler of Infernos // prev::Limited
Brionac, Dragon of the Ice Barrier
Chaos Emperor Dragon - Envoy of the End
Cyber Jar
Cyber-Stein
Dark Magician of Chaos
Destiny HERO - Disk Commander
Elemental HERO Stratos
Fiber Jar
Fishborg Blaster
Magical Scientist
Makyura the Destructor
Mind Master
Morphing Jar
Morphing Jar 2
Number 16: Shock Master
Redox, Dragon Ruler of Boulders // prev::Limited
Rescue Cat
Sangan
Substitoad
Tempest, Dragon Ruler of Storms // prev::Limited
Thousand-Eyes Restrict
Tidal, Dragon Ruler of Waterfalls // prev::Limited
Tribe-Infecting Virus
Trishula, Dragon of the Ice Barrier
Victory Dragon
Wind-Up Carrier Zenmaity
Witch of the Black Forest
Yata-Garasu
Brain Control
Butterfly Dagger - Elma
Card Destruction
Card of Safe Return
Change of Heart
Cold Wave
Confiscation
Delinquent Duo
Dimension Fusion
Future Fusion
Gateway of the Six
Giant Trunade
Graceful Charity
Harpie's Feather Duster
Heavy Storm
Last Will
Mass Driver
Metamorphosis
Mirage of Nightmare
Monster Reborn
Painful Choice
Pot of Avarice
Pot of Greed
Premature Burial
Snatch Steal // prev::Limited
Spellbook of Judgment
Super Polymerization
Super Rejuvenation
The Forceful Sentry
Imperial Order
Last Turn
Return from the Different Dimension
Royal Oppression
Self-Destruct Button
Sixth Sense
Solemn Judgment
Time Seal
Trap Dustshoot
Ultimate Offering
| limited      = 
Artifact Moralltach
Atlantean Dragoons
Black Luster Soldier - Envoy of the Beginning
Dandylion
Dark Armed Dragon
Dark Strike Fighter
Debris Dragon
Deep Sea Diva
Dewloren, Tiger King of the Ice Barrier
Evigishki Gustkraken
Evigishki Mind Augus
Exodia the Forbidden One
Genex Ally Birdman
Glow-Up Bulb
Infernity Archfiend
Inzektor Dragonfly
Inzektor Hornet
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Neo-Spacian Grand Mole
Night Assailant
Red-Eyes Darkness Metal Dragon
Rescue Rabbit
Right Arm of the Forbidden One
Right Leg of the Forbidden One
Sinister Serpent // prev::Forbidden
T.G. Hyper Librarian
Thunder King Rai-Oh
Tour Guide From the Underworld // prev::Unlimited
Wind-Up Magician
Allure of Darkness
Book of Moon
Burial from a Different Dimension
Dimensional Fissure
Divine Wind of Mist Valley
Dragon Ravine // prev::Forbidden
Final Countdown
Foolish Burial
Gold Sarcophagus
Infernity Launcher
Limiter Removal
Mind Control
Monster Gate
One Day of Peace
One for One
Preparation of Rites // prev::Unlimited
Raigeki
Rekindling
Royal Tribute
Saqlifice // prev::Unlimited
Soul Charge
Spellbook of Fate
Symbol of Heritage // prev::Unlimited
Temple of the Kings // prev::Forbidden
Bottomless Trap Hole
Compulsory Evacuation Device
Crush Card Virus // prev::Forbidden
Eradicator Epidemic Virus
Exchange of the Spirit // prev::Forbidden
Geargiagear
Infernity Barrier
Macro Cosmos
Magical Explosion
Ring of Destruction // prev::Forbidden
Skill Drain // prev::Unlimited
Solemn Warning
Soul Drain
Torrential Tribute
Vanity's Emptiness // prev::Unlimited
Wall of Revealing Light
| semi_limited = 
Card Trooper
Chaos Sorcerer
Honest
Legendary Six Samurai - Shi En // prev::Limited
Necroface
Nekroz of Brionac // prev::Unlimited
Qliphort Scout // prev::Unlimited
Summoner Monk
Tragoedia
Advanced Ritual Art
Chain Strike
Charge of the Light Brigade // prev::Limited
Dark Hole
Sacred Sword of Seven Stars // prev::Limited
Ceasefire
Ojama Trio
| no_longer_on_list = 
Brotherhood of the Fire Fist - Spirit // prev::Limited
Burner, Dragon Ruler of Sparks // prev::Forbidden
Gladiator Beast Bestiari // prev::Limited
Gorz the Emissary of Darkness // prev::Semi-Limited
Goyo Guardian // prev::Semi-Limited
Lightning, Dragon Ruler of Drafts // prev::Forbidden
Lonefire Blossom // prev::Semi-Limited
Reactan, Dragon Ruler of Pebbles // prev::Forbidden
Stream, Dragon Ruler of Droplets // prev::Forbidden
Hieratic Seal of Convocation // prev::Semi-Limited
}}

== References ==
<references/>

{{TCG limitation status lists}}"""

    /** "April 2020 Lists (TCG)". */
    const val APRIL_2020_TCG = """These are the '''April 2020 Forbidden and Limited Lists''' for the ''[[TCG]]'' in effect since April 1, 2020.<ref>{{cite web | url = https://ygorganization.com/thepotcomeshomeagain/ | title = <nowiki>The Organization | [TCG] Forbidden & Limited List: April 1st 2020</nowiki> | author = NeoArkadia | publisher = YGOrganization | date = March 24, 2020 | accessdate = March 24, 2020}}</ref>

{{Limitation list
| start_date   = April 1, 2020
| end_date     = June 14, 2020
| medium       = TCG
| format       = Advanced Format
| prev         = January 2020 Lists (TCG)
| next         = June 2020 Lists
| forbidden    = 
Ancient Fairy Dragon
Astrograph Sorcerer
Blackwing - Gofu the Vague Shadow
Blackwing - Steam the Cloak // prev::Unlimited
Blaster, Dragon Ruler of Infernos
Cyber Jar
Dandylion
Denglong, First of the Yang Zing
Destrudo the Lost Dragon's Frisson // prev::Unlimited
Djinn Releaser of Rituals
Double Iris Magician
Eclipse Wyvern
Elder Entity Norden
Fairy Tail - Snow
Fiber Jar
Firewall Dragon
Fishborg Blaster
Glow-Up Bulb // prev::Unlimited
Grinder Golem
Guardragon Agarpain
Heavymetalfoes Electrumite
Ib the World Chalice Justiciar
Knightmare Goblin
Knightmare Mermaid
Lavalval Chain
Level Eater
Lunalight Tiger // prev::Unlimited
M-X-Saber Invoker
Magical Scientist
Majespecter Unicorn - Kirin
Makyura the Destructor
Master Peace, the True Dracoslaying King
Maxx "C"
Mind Master
Number 16: Shock Master
Number 42: Galaxy Tomahawk
Number 86: Heroic Champion - Rhongomyniad
Number 95: Galaxy-Eyes Dark Matter Dragon
Orcust Harp Horror
Outer Entity Azathot
Performage Plushfire
Performapal Monkeyboard
Performapal Skullcrobat Joker
Phoenixian Cluster Amaryllis
Redox, Dragon Ruler of Boulders
Salamangreat Miragestallio
Samsara Lotus
SPYRAL Master Plan // prev::Unlimited
Substitoad
Summon Sorceress
Supreme King Dragon Starving Venom
Tellarknight Ptolemaeus
Tempest Magician
The Phantom Knights of Rusty Bardiche
The Tyrant Neptune
Thunder Dragon Colossus
Tidal, Dragon Ruler of Waterfalls
Topologic Gumblar Dragon
Victory Dragon
Wind-Up Carrier Zenmaity
Yata-Garasu
Zoodiac Broadbull
Brilliant Fusion
Butterfly Dagger - Elma
Card of Safe Return
Change of Heart
Chicken Game
Cold Wave
Confiscation
Delinquent Duo
Dimension Fusion
Giant Trunade
Graceful Charity
Harpie's Feather Duster
Heavy Storm
Kaiser Colosseum
Last Will
Mass Driver
Metamorphosis
Mirage of Nightmare
Painful Choice
Pot of Greed
Premature Burial
Rank-Up-Magic Argent Chaos Force
Sky Striker Mobilize - Engage!
Snatch Steal
Soul Charge
Spellbook of Judgment
That Grass Looks Greener
The Forceful Sentry
Last Turn
Return from the Different Dimension
Royal Oppression
Self-Destruct Button
Sixth Sense
Time Seal
Trap Dustshoot
Ultimate Offering
Vanity's Emptiness
| limited      = 
ABC-Dragon Buster // prev::Unlimited
Altergeist Multifaker
Armageddon Knight
Beatrice, Lady of the Eternal
Black Dragon Collapserpent
Cir, Malebranche of the Burning Abyss
Cyber-Stein
Daigusto Emeral
Danger!? Jackalope? // prev::Semi-Limited
Danger! Nessie!
Danger!? Tsuchinoko? // prev::Semi-Limited
Dark Grepher
Dewloren, Tiger King of the Ice Barrier
Dinomight Knight, the True Dracofighter
Dinowrestler Pankratops
Evigishki Gustkraken
Evigishki Mind Augus
Exodia the Forbidden One
Gem-Knight Master Diamond
Genex Ally Birdman
Graff, Malebranche of the Burning Abyss
Ignister Prominence, the Blasting Dracoslayer
Infernity Archfiend
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Morphing Jar
Nekroz of Unicore
Night Assailant
Phantom Skyblaster
PSY-Framelord Omega
Red-Eyes Darkness Metal Dragon
Right Arm of the Forbidden One
Right Leg of the Forbidden One
Ritual Beast Ulti-Cannahawk
Salamangreat Gazelle
Servant of Endymion
Speedroid Terrortop
SPYRAL Quik-Fix
T.G. Hyper Librarian // prev::Unlimited
Tempest, Dragon Ruler of Storms
Toadally Awesome // prev::Unlimited
Trishula, Dragon of the Ice Barrier // prev::Unlimited
True King Lithosagym, the Disaster
White Dragon Wyverburster
Zoodiac Drident // prev::Forbidden
Zoodiac Ratpier
A Hero Lives
Card Destruction
Card of Demise
Chain Strike
Dimensional Fissure
Divine Wind of Mist Valley
Draco Face-Off
Dragonic Diagram
Emergency Teleport
Final Countdown
Foolish Burial
Gateway of the Six
Gold Sarcophagus
Infernity Launcher
Instant Fusion // prev::Unlimited
Into the Void
Magical Mid-Breaker Field
Mind Control // prev::Semi-Limited
Monster Reborn
One Day of Peace
One for One
Pantheism of the Monarchs
Raigeki
Reasoning
Reinforcement of the Army
Salamangreat Circle
Scapegoat
Sekka's Light
Set Rotation
Sky Striker Mecha - Hornet Drones
Sky Striker Mecha Modules - Multirole
Slash Draw
SPYRAL Resort
Symbol of Heritage
Terraforming
Trickstar Light Stage
Upstart Goblin
Zoodiac Barrage // prev::Unlimited
Imperial Order
Macro Cosmos
Magical Explosion
Metaverse
Red Reboot
Skill Drain
True King's Return
Wall of Revealing Light
| semi_limited = 
Destiny HERO - Malicious // prev::Unlimited
Tour Guide From the Underworld
Sky Striker Mecha - Widow Anchor // prev::Limited
| no_longer_on_list = 
Deep Sea Diva // prev::Semi-Limited
Necroface // prev::Semi-Limited
SPYRAL GEAR - Drone // prev::Limited
Pot of Avarice // prev::Limited
}}

== References ==
<references />

{{TCG limitation status lists}}"""

    /** "September 2026 Lists (TCG)": still in force, an empty end date, a `prev-note` with a `<ref>`. */
    const val SEPTEMBER_2026_TCG = """These are the '''September 2026 Forbidden and Limited Lists''' for the ''[[TCG]]'', effective from September 21, 2026.<ref name="YGOrg 9-20-26">{{cite web | url = https://ygorganization.com/putinvokerbackpls | title = TCG September 2026 Forbidden & Limited List [TCG] | author = Satchmo | website = The Organization | date = September 20, 2026 | accessdate = September 20, 2026}}</ref>

{{Limitation list
| start_date        = September 21, 2026
| end_date          = 
| medium            = TCG
| format            = Advanced Format
| prev              = May 2026 Lists (TCG)
| next              = 
| forbidden         = 
Abyss Dweller
Agido the Ancient Sentinel
Apollousa, Bow of the Goddess
Archlord Kristya // prev::Unlimited
Archnemeses Protos
Artifact Mjollnir
Artifact Scythe
Bahamut Shark
Baronne de Fleur
Barrier Statue of the Drought
Barrier Statue of the Inferno
Barrier Statue of the Stormwinds
Barrier Statue of the Torrent
Beatrice, Lady of the Eternal
Blackwing - Gofu the Vague Shadow
Block Dragon
Borreload Savage Dragon
Chaos Ruler, the Chaotic Magical Dragon
Crystron Halqifibrax
Curious, the Lightsworn Dominion
CXyz Gimmick Puppet Fanatix Machinix
Cyber-Stein
Dandylion
Dimension Shifter // prev::Limited
Djinn Releaser of Rituals
Eclipse Wyvern
Evilswarm Ouroboros
Fiber Jar
Fishborg Blaster
Fossil Dyna Pachycephalo
Gimmick Puppet Nightmare
Grinder Golem
Guardragon Agarpain
Guardragon Elpy
Heavymetalfoes Electrumite
Herald of the Arc Light
Hot Red Dragon Archfiend King Calamity
Isolde, Two Tales of the Noble Knights
Jowgen the Spiritualist
K9-04 Noroi
Kashtira Arise-Heart
Kelbek the Ancient Vanguard
Kewl Tune Rotary // prev::Unlimited
King of the Feral Imps
Knightmare Goblin
Knightmare Gryphon
Knightmare Mermaid
Lavalval Chain
Level Eater
Link Decoder
Linkross
Magical Scientist
Maliss Q White Binder // force-smw
Maxx "C"
Mecha Phantom Beast Auroradon
Moon of the Closed Heaven
Naturia Rosewhip
Number 16: Shock Master
Number 42: Galaxy Tomahawk
Number 67: Pair-a-Dice Smasher
Number 86: Heroic Champion - Rhongomyniad
Number 89: Diablosis the Mind Hacker
Number 95: Galaxy-Eyes Dark Matter Dragon
Number S0: Utopic ZEXAL
Outer Entity Azathot
Phantasmal Lord Ultimitl Bishbaalkin // prev::Unlimited
Phoenixian Cluster Amaryllis
Prank-Kids Meow-Meow-Mu
Predaplant Verte Anaconda
Protectcode Talker // prev::Unlimited
PSY-Framelord Omega // prev::Limited
Reprodocus // prev::Unlimited
Ronintoadin
Simorgh, Bird of Sovereignty
Splash Mage
Spright Elf
SPYRAL Master Plan
Superheavy Samurai Scarecrow
Supreme King Dragon Starving Venom
Tearlaments Kitkallos
Tempest Magician
The Tyrant Neptune
Topologic Gumblar Dragon
True King of All Calamities
Union Carrier
Victory Dragon
Wind-Up Hunter // prev::Unlimited
Zoodiac Broadbull
Butterfly Dagger - Elma
Card of Safe Return
Cold Wave
Confiscation
Delinquent Duo
Dimension Fusion
Giant Trunade
Graceful Charity
Heavy Storm
Kaiser Colosseum
Last Will
Mass Driver
Mystic Mine
Original Sinful Spoils - Snake-Eye
Painful Choice
Pot of Greed
Smoke Grenade of the Thief
Soul Charge
The Forceful Sentry
Appointer of the Red Lotus
Branded Expulsion
Dimensional Barrier
Harpie's Feather Storm
Imperial Order
Last Turn
Red Reboot
Return from the Different Dimension
Royal Oppression
Self-Destruct Button
Sixth Sense
Summon Limit
Trap Dustshoot
Ultimate Offering
Vanity's Emptiness
| limited           = 
Ame no Habakiri no Mitsurugi
Astrograph Sorcerer
Bystial Druiswurm
Bystial Magnamhut
Cupsy☆Yummy
Cupsy★Yummy Way
Daigusto Emeral
Dracotail Mululu
Elfnote Tinia // prev::Unlimited
Exodia the Forbidden One
Ext Ryzeal
Fairy Tail - Snow
Fiendsmith's Lacrima
Gem-Knight Master Diamond
K9-66a Jokul
Keldo the Sacred Protector
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Linkuriboh
Maliss P Dormouse // force-smw
Maliss P White Rabbit // force-smw
Mathmech Circular
Miscellaneousaurus
Mudora the Sword Oracle
Number 40: Gimmick Puppet of Strings
Number C40: Gimmick Puppet of Dark Strings
Phantom of Yubel
PSY-Framegear Gamma
Right Arm of the Forbidden One
Right Leg of the Forbidden One
Ryzeal Detonator
Striker Dragon
Substitoad
Summon Sorceress
Sunavalon Dryas
Sunvine Healer
T.G. Hyper Librarian
Tearlaments Havnis
Tearlaments Merrli
Tearlaments Scheiren
Tenpai Dragon Chundra
Vanquish Soul Hollie Sue
Yummy★Snatchy
Zoodiac Ratpier
"A Case for K9"
Bonfire
Branded Fusion
Brilliant Fusion
Called by the Grave
Card Destruction
Card of Demise
Chain Strike
Chaos Space
Chicken Game
Crossout Designator
Divine Wind of Mist Valley
Final Countdown
Foolish Burial
Gateway of the Six
Gold Sarcophagus
Harpie's Feather Duster
Infernity Launcher
Instant Fusion
Into the Void
Ketu Dracotail
Magical Mid-Breaker Field
Monster Gate
Monster Reborn
Obedience Schooled
One Day of Peace
One for One
Pot of Prosperity
Prohibited Power Patron Portal - Terminus // prev::Unlimited
Radiant Typhoon Chant
Rahu Dracotail
Reasoning
Reinforcement of the Army
Sangen Kaimen
Sangen Summoning
Sekka's Light
Set Rotation
Sky Striker Mecha - Hornet Drones
Slash Draw
Synchro Overtake
Terraforming
That Grass Looks Greener
Triple Tactics Talent
Anti-Spell Fragrance
Gozen Match
Magical Explosion
Naturia Sacred Tree
Rivalry of Warlords
Skill Drain
Solemn Judgment
There Can Be Only One
| semi_limited      = 
Dracotail Arthalion
Dracotail Lukias
Droll & Lock Bird
Ice Ryzeal
Sword Ryzeal
Branded in High Spirits // prev::Unlimited
Maliss in Underground
Purrely Sleepy Memory
| no_longer_on_list = 
Elder Entity Norden // prev::Forbidden; prev-note:: (effective from September 28)<ref name="YGOrg 9-20-26" />
M-X-Saber Invoker // prev::Forbidden
Mind Master // prev::Forbidden; prev-note:: (effective from September 28)<ref name="YGOrg 9-20-26" />
Unchained Soul of Sharvara // prev::Semi-Limited
Wind-Up Carrier Zenmaity // prev::Forbidden
Metamorphosis // prev::Limited
Mirage of Nightmare // prev::Forbidden
Premature Burial // prev::Limited
Purrely Delicious Memory // prev::Semi-Limited
Runick Fountain // prev::Semi-Limited
}}

== References ==
<references />

{{TCG limitation status lists}}"""

    /** "October 2025 Lists (OCG)": no `format` field. */
    const val OCTOBER_2025_OCG = """These are the '''Forbidden and Limited Lists''' for the ''[[OCG]]'', effective from October 1, 2025.<ref name="YGOjp 2025-09-21">{{cite web | url = https://yu-gi-oh.jp/news_detail.php?page=details&id=2341 | title = 2025年10月1日(水)適用の『OCG』リミットレギュレーションを公開！ | website = YU-GI-OH.jp | date = September 21, 2025 | access-date = September 21, 2025}}</ref><ref name="YGOrg 2025-09-21">{{cite web | url = https://ygorganization.com/ripouroboros/ | title = October 1st, 2025 Limit Regulation [OCG] | author = NeoArkadia | website = The Organization | date = September 21, 2025 | access-date = September 21, 2025}}</ref><ref>{{cite web |title=Forbidden and Limited List (JP) Effective from 01/10/2025  |url=https://www.yugioh-card.com/asia/play/forbidden-and-limited-list/?effective-date=9069 |website=Yu-Gi-Oh! Official Card Game |publisher=Konami Digital Entertainment}}</ref> These Lists were announced on September 21, 2025.<ref name="YGOrg 2025-09-12">{{cite web | url = https://ygorganization.com/official-card-game-october-1st-limit-regulation-announcement-announcement/ | title = Official Card Game October 1st Limit Regulation Announcement Announcement | author = NeoArkadia | website = The Organization | date = September 12, 2025 | access-date = September 12, 2025}}</ref>

{{Limitation list
| start_date        = October 1, 2025
| end_date          = December 31, 2025
| medium            = OCG
| prev              = July 2025 Lists (OCG)
| next              = January 2026 Lists (OCG)
| forbidden         = 
Abyss Dweller
Agido the Ancient Sentinel
Amazoness Archer
Apollousa, Bow of the Goddess
Artifact Scythe
Barrier Statue of the Stormwinds
Beatrice, Lady of the Eternal
Blackwing - Gofu the Vague Shadow
Cannon Soldier
Cannon Soldier MK-2
Catapult Turtle
Chaos Ruler, the Chaotic Magical Dragon
Crystron Halqifibrax
Cyber-Stein
Dandylion
Djinn Releaser of Rituals
Elder Entity Norden
Evilswarm Ouroboros // prev::Unlimited
Fiber Jar
Fishborg Blaster
Grinder Golem
Guardragon Agarpain
Guardragon Elpy
Hot Red Dragon Archfiend King Calamity
Kashtira Fenrir
Kelbek the Ancient Vanguard
Knightmare Mermaid
Lavalval Chain
Level Eater
Linkross
Magical Scientist
Mind Master
Number 16: Shock Master
Number 67: Pair-a-Dice Smasher
Number 86: Heroic Champion - Rhongomyniad
Number 95: Galaxy-Eyes Dark Matter Dragon
Number S0: Utopic ZEXAL
Outer Entity Azathot
Phoenixian Cluster Amaryllis
Predaplant Verte Anaconda
Spright Elf
Substitoad
Superheavy Samurai Soulbreaker Armor
Tearlaments Kitkallos
Tempest Magician
The Tyrant Neptune
Toadally Awesome
Toon Cannon Soldier
Topologic Gumblar Dragon
True King of All Calamities
Union Carrier
Victory Dragon
Wind-Up Hunter
Zoodiac Broadbull
Butterfly Dagger - Elma
Card of Safe Return
Cold Wave
Confiscation
Delinquent Duo
Dimension Fusion
Divine Sword - Phoenix Blade
Giant Trunade
Graceful Charity
Kaiser Colosseum
Last Will
Mass Driver
Metamorphosis
Mirage of Nightmare
Mystic Mine
Obedience Schooled // prev::Unlimited
Painful Choice
Pot of Greed
Premature Burial
Smoke Grenade of the Thief
Soul Charge
The Forceful Sentry
Imperial Order
Last Turn
Life Equalizer
Magical Explosion
Return from the Different Dimension
Royal Oppression
Sixth Sense
Summon Limit
Trap Dustshoot
Ultimate Offering
Vanity's Emptiness
| limited           = 
Blaze Fenix, the Burning Bombardment Bird
Block Dragon
Bystial Druiswurm
Bystial Magnamhut
Diabellstar the Black Witch
Dimension Shifter
Eclipse Wyvern // prev::Forbidden
Exodia the Forbidden One
Ext Ryzeal
Fiendsmith Engraver
Heavymetalfoes Electrumite
Herald of Orange Light
Herald of the Arc Light // prev::Unlimited
K9-66a Jokul // prev::Unlimited
Kashtira Unicorn
Keldo the Sacred Protector
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Maliss P Dormouse // force-SMW
Mathmech Circular
Mudora the Sword Oracle
Number 40: Gimmick Puppet of Strings
Phantom of Yubel
PSY-Framegear Gamma
PSY-Framelord Omega
Red-Eyes Dark Dragoon
Right Arm of the Forbidden One
Right Leg of the Forbidden One
S:P Little Knight
Snake-Eye Ash
Snake-Eyes Poplar
Spright Blue
Superheavy Samurai Soulpiercer
Sword Ryzeal
T.G. Hyper Librarian
Tearlaments Havnis
Tearlaments Kashtira
Tearlaments Merrli
Tearlaments Reinoheart
Tearlaments Scheiren
Tenpai Dragon Chundra
The Bystial Lubellion
Vanquish Soul Razen // prev::Unlimited
Zoodiac Ratpier
Allure of Darkness
Bonfire
Branded Fusion
Brilliant Fusion
Card Destruction
Crossout Designator
Fiendsmith's Tract
Foolish Burial
Gateway of the Six
Gold Sarcophagus
Harpie's Feather Duster
Heavy Storm
Instant Fusion
Ketu Dracotail // prev::Unlimited
Monster Reborn
One Day of Peace
One for One
Pot of Prosperity
Primal Seed
Primeval Planet Perlereino
Reinforcement of the Army
Runick Fountain
Sangen Summoning
Set Rotation
Seventh Tachyon
Sky Striker Mecha - Hornet Drones
Spright Starter
Terraforming
That Grass Looks Greener
WANTED: Seeker of Sinful Spoils
Anti-Spell Fragrance
Gozen Match
Red Reboot
Rivalry of Warlords
Skill Drain
There Can Be Only One
Trickstar Reincarnation
| semi_limited      = 
Astrograph Sorcerer // prev::Limited
Bystial Baldrake // prev::Limited
K9-17 Izuna // prev::Unlimited
Maxx "C"
Yummy★Snatchy // prev::Unlimited
Called by the Grave
EMERGENCY! // prev::Limited
Foolish Burial Goods
Pot of Desires
Pot of Extravagance
Pressured Planet Wraitsoth // prev::Limited
Purrely Sleepy Memory
Sangen Kaimen // prev::Unlimited
Super Polymerization
| no_longer_on_list = 
Knightmare Goblin // prev::Limited
Spright Jet // prev::Semi-Limited
Wandering Gryphon Rider // prev::Limited
Emergency Teleport // prev::Semi-Limited
Mask Change II // prev::Semi-Limited
}}

== References ==
<references />

{{OCG limitation status lists}}"""

    /** "October 2015 Lists" (OCG): a name written `Allure of Darkness|sc`. */
    const val OCTOBER_2015_OCG = """These are the '''October 2015 Forbidden and Limited Lists''' for the ''[[OCG]]'' in effect since October 1, 2015.<ref>{{cite web |title=Forbidden/Limited Card Lists (2015/10) |url=http://www.yugioh-card.com/ph/event/rules_guides/forbidden_cardlist.php?list=201510&lang=en |website=Yu-Gi-Oh! Official Card Game |publisher=Konami Digital Entertainment |archive-url=https://web.archive.org/web/20160314023331/http://www.yugioh-card.com:80/ph/event/rules_guides/forbidden_cardlist.php?list=201510&lang=en |archive-date=14 March 2016}}</ref><ref>{{cite web | url = https://ygorganization.com/ocg-october-1st-limit-regulation/ | title = OCG October 1st Limit Regulation | author = Eva | website = The Organization | date = September 15, 2015 | accessdate = January 7, 2016}}</ref>

{{Limitation list
| start_date   = October 1, 2015
| end_date     = December 31, 2015
| medium       = OCG
| prev         = July 2015 Lists (OCG)
| next         = January 2016 Lists
| forbidden    = 
Blaster, Dragon Ruler of Infernos
Brionac, Dragon of the Ice Barrier
Cyber Jar
Destiny HERO - Disk Commander
El Shaddoll Construct
Elder Entity Norden // prev::Unlimited
Fiber Jar
Fishborg Blaster
Goyo Guardian
Magical Scientist
Magician of Faith
Makyura the Destructor
Mind Master
Redox, Dragon Ruler of Boulders
Rescue Cat
Sangan
Substitoad
Tellarknight Ptolemaeus // prev::Unlimited
Tempest, Dragon Ruler of Storms
Thousand-Eyes Restrict
Tidal, Dragon Ruler of Waterfalls
Tribe-Infecting Virus
Victory Dragon
Wind-Up Hunter
Witch of the Black Forest
Yata-Garasu
Brain Control
Butterfly Dagger - Elma
Card of Safe Return
Change of Heart
Cold Wave
Confiscation
Delinquent Duo
Dimension Fusion
Future Fusion
Giant Trunade
Graceful Charity
Heavy Storm
Last Will
Mass Driver
Metamorphosis
Mirage of Nightmare
Painful Choice
Pot of Greed
Premature Burial
Raigeki
Snatch Steal
Spellbook of Judgment
The Forceful Sentry
Imperial Order
Last Turn
Return from the Different Dimension
Royal Oppression
Sixth Sense
Time Seal
Trap Dustshoot
Ultimate Offering
| limited      = 
Armageddon Knight
Artifact Moralltach
Black Luster Soldier - Envoy of the Beginning
Blaze Fenix, the Burning Bombardment Bird
Dandylion
Dark Armed Dragon
Deep Sea Diva // prev::Unlimited
Elemental HERO Shadow Mist // prev::Unlimited
Elemental HERO Stratos
Exodia the Forbidden One
Genex Ally Birdman
Glow-Up Bulb // prev::Forbidden
Inzektor Dragonfly
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Mathematician
Morphing Jar
Necroface
Nekroz of Brionac
Nekroz of Unicore
Night Assailant
Red-Eyes Darkness Metal Dragon
Right Arm of the Forbidden One
Right Leg of the Forbidden One
Shurit, Strategist of the Nekroz // prev::Unlimited
Star Seraph Sovereignty
Summoner Monk
T.G. Hyper Librarian
Trishula, Dragon of the Ice Barrier
Wisdom-Eye Magician // prev::Unlimited
Burial from a Different Dimension
Card Destruction
Dark Hole
Divine Wind of Mist Valley
Dragon Ravine
El Shaddoll Fusion
Foolish Burial
Harpie's Feather Duster
Infernity Launcher
Limiter Removal
Monster Reborn
Nekroz Cycle
One Day of Peace
One for One
Pot of Avarice
Preparation of Rites
Primal Seed
Reinforcement of the Army
Saqlifice
Soul Charge
Super Polymerization
Super Rejuvenation
Symbol of Heritage
Ceasefire
Magical Explosion
Ring of Destruction
Solemn Judgment
Solemn Warning
Vanity's Emptiness
Wall of Revealing Light
| semi_limited = 
Atlantean Dragoons
Destiny HERO - Malicious
Dewloren, Tiger King of the Ice Barrier
Evigishki Gustkraken // prev::Limited
Kuribandit // prev::Limited
Mermail Abyssteus // prev::Limited
Mezuki
Qliphort Scout
Reborn Tengu
Allure of Darkness|sc
Book of Moon // prev::Limited
Chain Strike
Charge of the Light Brigade // prev::Limited
Dragon Shrine // prev::Limited
Gateway of the Six // prev::Limited
Gold Sarcophagus // prev::Limited
Mind Control // prev::Limited
Crush Card Virus // prev::Limited
Sinister Shadow Games // prev::Limited
| no_longer_on_list = 
Blackwing - Gale the Whirlwind // prev::Semi-Limited
Chaos Emperor Dragon - Envoy of the End // prev::Limited
Dark Magician of Chaos // prev::Limited
Elemental HERO Bubbleman // prev::Semi-Limited
Formula Synchron // prev::Semi-Limited
Honest // prev::Semi-Limited
Manju of the Ten Thousand Hands // prev::Semi-Limited
Wind-Up Carrier Zenmaity // prev::Semi-Limited
Instant Fusion // prev::Limited
Magical Stone Excavation // prev::Semi-Limited
Bottomless Trap Hole // prev::Semi-Limited
The Transmigration Prophecy // prev::Limited
}}

== References ==
<references />

{{OCG limitation status lists}}"""

    /** "July 2002 Lists" (TCG): no Forbidden section, `prev::Not yet released`. */
    const val JULY_2002 = """The '''July 2002 Forbidden and Limited List''' was the second Forbidden and Limited list in the ''[[TCG]]'', going into effect on July 1, [[2002]].<ref>{{cite web | url = http://www.upperdeckentertainment.com/yugioh/forbidden.asp | title = Effective July 1st, 2002, this is the list of Forbidden and Limited Cards from Konami. | publisher = Upper Deck Entertainment | archiveurl = http://web.archive.org/web/20020628090723/http://www.upperdeckentertainment.com/yugioh/forbidden.asp | archivedate = June 28, 2002 | accessdate = September 14, 2011}}</ref> This list updated the previous [[May 2002 Lists (TCG)|May 2002]] list, adding four cards from the [[Booster Pack]] ''[[Metal Raiders]]'', and was followed by the [[October 2002 Lists|October 2002 Forbidden and Limited List]].

{{Limitation list
| start_date   = July 1, 2002
| end_date     = September 30, 2002
| medium       = TCG
| format       = Advanced Format
| prev         = May 2002 Lists (TCG)
| next         = October 2002 Lists
| limited      = 
Exodia the Forbidden One
Left Arm of the Forbidden One
Left Leg of the Forbidden One
Right Arm of the Forbidden One
Right Leg of the Forbidden One
Change of Heart
Dark Hole
Monster Reborn
Pot of Greed
Raigeki
Mirror Force // prev::Not yet released
| semi_limited =
Sangan // prev::Not yet released
Witch of the Black Forest // prev::Not yet released
Card Destruction
Heavy Storm // prev::Not yet released
Swords of Revealing Light
}}

== References ==
<references />

{{TCG limitation status lists}}"""

}
